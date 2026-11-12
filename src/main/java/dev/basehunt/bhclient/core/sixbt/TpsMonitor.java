package dev.basehunt.bhclient.core.sixbt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads 6b6t's {@code /tps} output and keeps a rolling history of it.
 *
 * <p>The server answers {@code /tps} with its own TPS and, separately, the TPS <em>you</em> had over the last
 * 5 seconds, 1 minute and 5 minutes. On an anarchy server that second number is the useful one: it tells you
 * whether the lag you feel is the server's or your own connection's. The exact wording is not guaranteed, so
 * the parser pulls the numbers out of whatever the server sent rather than matching a fixed string.
 *
 * <p>The history is bounded, because {@code /tps} can be polled on a timer and an unbounded log would grow
 * for as long as the client runs. Both the parsing and the averaging are pure, so they are covered headlessly.
 */
public final class TpsMonitor {

    /** One reading: the server TPS and the client's own TPS windows, any of which may be absent. */
    public record Reading(double serverTps, double personalTps, long atMillis) {
        public boolean hasServer() {
            return serverTps > 0;
        }

        public boolean hasPersonal() {
            return personalTps > 0;
        }

        /** A short health word for the HUD. */
        public String health() {
            double value = hasServer() ? serverTps : personalTps;
            if (value <= 0) return "unknown";
            if (value >= 19.0) return "good";
            if (value >= 15.0) return "fair";
            if (value >= 10.0) return "laggy";
            return "bad";
        }

        public String describe() {
            StringBuilder builder = new StringBuilder();
            if (hasServer()) builder.append("server %.1f".formatted(serverTps));
            if (hasPersonal()) {
                if (builder.length() > 0) builder.append(", ");
                builder.append("you %.1f".formatted(personalTps));
            }
            if (builder.length() == 0) builder.append("no reading");
            return builder.append(" (").append(health()).append(")").toString();
        }
    }

    private static final int LIMIT = 240;

    private final Deque<Reading> history = new ArrayDeque<>();
    private Reading latest;

    /**
     * Parses a {@code /tps} reply.
     *
     * <p>Numbers are collected in order. The server figure is the first plausible TPS value; the personal
     * figure is the next one that follows a "you"/"personal" hint, or the last value when no hint is present.
     * A reply with no numbers yields an empty result rather than a zero reading.
     */
    public static Optional<Reading> parse(String message, long nowMillis) {
        if (message == null || message.isBlank()) return Optional.empty();

        String lower = message.toLowerCase(Locale.ROOT);
        List<Double> numbers = extractNumbers(message);
        if (numbers.isEmpty()) return Optional.empty();

        double server = 0;
        double personal = 0;

        // The server value is the first number that could be a TPS (0..20 plus a little slack).
        for (double value : numbers) {
            if (value >= 0 && value <= 21.5) {
                server = value;
                break;
            }
        }

        // A personal figure is flagged by wording; take the last plausible number after that hint.
        int hint = personalHint(lower);
        if (hint >= 0) {
            for (int i = 0; i < numbers.size(); i++) {
                double value = numbers.get(i);
                if (value >= 0 && value <= 21.5) personal = value;
            }
        } else if (numbers.size() > 1) {
            double last = numbers.get(numbers.size() - 1);
            if (last >= 0 && last <= 21.5) personal = last;
        }

        return Optional.of(new Reading(server, personal, nowMillis));
    }

    private static int personalHint(String lower) {
        for (String hint : new String[] {"you", "your", "personal", "yourself"}) {
            int index = lower.indexOf(hint);
            if (index >= 0) return index;
        }
        return -1;
    }

    /** Every number in a string, in order, ignoring the digits inside colour codes and percentages. */
    static List<Double> extractNumbers(String text) {
        List<Double> numbers = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') {
                current.append(c);
            } else if (current.length() > 0) {
                addNumber(numbers, current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) addNumber(numbers, current.toString());
        return numbers;
    }

    private static void addNumber(List<Double> numbers, String token) {
        if (token.equals(".") || token.isEmpty()) return;
        try {
            numbers.add(Double.parseDouble(token));
        } catch (NumberFormatException ignored) {
            // A stray token such as "1.2.3" is not a number; skip it rather than failing the whole line.
        }
    }

    /** Records a reading and returns it. */
    public Reading record(Reading reading) {
        if (reading == null) return null;
        latest = reading;
        history.addLast(reading);
        while (history.size() > LIMIT) history.removeFirst();
        return reading;
    }

    /** Records a parsed reading, if there was one. */
    public void record(String message, long nowMillis) {
        parse(message, nowMillis).ifPresent(this::record);
    }

    public Reading latest() {
        return latest;
    }

    public boolean hasReading() {
        return latest != null;
    }

    public int size() {
        return history.size();
    }

    public List<Reading> history() {
        return new ArrayList<>(history);
    }

    /** The mean server TPS over the recorded history, or 0 when nothing was recorded. */
    public double averageServerTps() {
        double sum = 0;
        int count = 0;
        for (Reading reading : history) {
            if (reading.hasServer()) {
                sum += reading.serverTps();
                count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }

    /** The lowest server TPS seen, or 0 when nothing was recorded. */
    public double lowestServerTps() {
        double lowest = 0;
        for (Reading reading : history) {
            if (reading.hasServer() && (lowest == 0 || reading.serverTps() < lowest)) lowest = reading.serverTps();
        }
        return lowest;
    }

    public void clear() {
        history.clear();
        latest = null;
    }

    public String statusLine() {
        if (latest == null) return "no /tps reading yet";
        return "%s | avg %.1f over %d sample(s)".formatted(latest.describe(), averageServerTps(), history.size());
    }
}
