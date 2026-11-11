package dev.basehunt.bhclient.core.sixbt;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads the shape of a 6b6t chat line: who sent it, what they said, and whether it is a kit request.
 *
 * <p>On 6b6t, kits are handed out by <em>delivery bots</em>. A player types a request such as {@code !kit pvp}
 * into public chat, a bot sees it, {@code /tpa}s to the player, drops the kit and then {@code /kill}s itself.
 * The addon can be that bot. To do it, it has to read a chat line well enough to know (a) that it is a request,
 * (b) who asked, and (c) which kit they want - and it has to do that from the text alone, because the client
 * is not told who sent a message.
 *
 * <p>The parser handles the two shapes anarchy servers use: the vanilla {@code <Name> message}, and the
 * ranked {@code [Rank] Name: message}. A line that starts with one of 6b6t's own commands is an echo of a
 * command, not a player message, and is reported as such so the delivery bot never answers the server.
 *
 * <p>Everything here is pure string logic, so the parsing rules are covered by the headless suite rather than
 * only being exercised by talking to a live server.
 */
public final class SixbtChat {

    /** A parsed chat line. */
    public record Line(String sender, String body, String raw, boolean commandEcho) {
        public boolean hasSender() {
            return sender != null && !sender.isBlank();
        }
    }

    /** A kit request found in chat. */
    public record Request(String requester, String kit, String raw) {
    }

    private SixbtChat() {
    }

    /**
     * Splits a chat line into sender and body.
     *
     * <p>Handles {@code <Name> body} and {@code [Rank] Name: body}. When no sender can be found the whole line
     * is the body and {@code sender} is empty - a caller must not treat a message with no sender as a request
     * from a known player.
     */
    public static Line parse(String raw) {
        if (raw == null) return new Line("", "", "", false);

        String line = raw.trim();
        boolean commandEcho = SixbtCommand.isKnown(SixbtCommand.firstToken(line));

        // Vanilla shape: <Name> message
        if (line.startsWith("<")) {
            int close = line.indexOf('>');
            if (close > 1) {
                String sender = line.substring(1, close).trim();
                String body = line.substring(close + 1).trim();
                return new Line(sender, body, line, commandEcho);
            }
        }

        // Ranked shape: [Rank] Name: message  (possibly several leading [..] tags)
        int cursor = 0;
        while (cursor < line.length() && line.charAt(cursor) == '[') {
            int close = line.indexOf(']', cursor);
            if (close < 0) break;
            cursor = close + 1;
            while (cursor < line.length() && line.charAt(cursor) == ' ') cursor++;
        }
        if (cursor > 0 && cursor < line.length()) {
            int colon = line.indexOf(':', cursor);
            if (colon > cursor) {
                String sender = line.substring(cursor, colon).trim();
                String body = line.substring(colon + 1).trim();
                // Only treat it as a sender when the name has no spaces and is short, so a sentence with a
                // colon is not mistaken for a speaker.
                if (!sender.isEmpty() && sender.length() <= 16 && sender.indexOf(' ') < 0) {
                    return new Line(sender, body, line, commandEcho);
                }
            }
        }

        return new Line("", line, line, commandEcho);
    }

    /**
     * Finds a kit request in a chat line.
     *
     * <p>A request is a body whose first token is {@code <prefix>kit} (optionally with the kit name after it)
     * or, when {@code bareRequests} is on, {@code <prefix><name>}. The requester is the parsed sender; a line
     * with no sender is never a request, because the bot cannot {@code /tpa} to nobody.
     *
     * @param raw             the raw chat line
     * @param prefixes        the request prefixes, e.g. {@code ["!", ".kit"]}
     * @param bareRequests    whether {@code !pvp} (no {@code kit} keyword) counts as a request
     * @param knownKits       the kit names the bot can deliver; a request for anything else is ignored
     * @return the request, or empty
     */
    public static Optional<Request> findRequest(String raw, List<String> prefixes, boolean bareRequests,
                                                List<String> knownKits) {
        Line line = parse(raw);
        if (!line.hasSender() || line.commandEcho()) return Optional.empty();
        if (prefixes == null || prefixes.isEmpty()) return Optional.empty();

        String body = line.body();
        if (body.isEmpty()) return Optional.empty();

        String matched = null;
        for (String prefix : prefixes) {
            if (prefix == null || prefix.isEmpty()) continue;
            if (body.regionMatches(true, 0, prefix, 0, prefix.length())) {
                if (matched == null || prefix.length() > matched.length()) matched = prefix;
            }
        }
        if (matched == null) return Optional.empty();

        String rest = body.substring(matched.length()).trim();
        if (rest.isEmpty()) return Optional.empty();

        String[] tokens = rest.split("\\s+");
        String first = tokens[0].toLowerCase(Locale.ROOT);

        // A prefix that already ends in "kit" (e.g. ".kit") does not need the keyword repeated.
        boolean prefixImpliesKit = matched.toLowerCase(Locale.ROOT).endsWith("kit");

        String kitName;
        if (first.equals("kit") || first.equals("kits")) {
            if (tokens.length < 2) return Optional.empty();
            kitName = tokens[1].toLowerCase(Locale.ROOT);
        } else if (bareRequests || prefixImpliesKit) {
            kitName = first;
        } else {
            return Optional.empty();
        }

        if (knownKits != null && !knownKits.isEmpty() && !containsIgnoreCase(knownKits, kitName)) {
            return Optional.empty();
        }

        return Optional.of(new Request(line.sender(), kitName, line.raw()));
    }

    /** True when the line is a request from a specific player (used to cancel a request on the requester's death). */
    public static boolean mentions(String raw, String player) {
        if (raw == null || player == null) return false;
        Line line = parse(raw);
        return line.hasSender() && line.sender().equalsIgnoreCase(player);
    }

    /**
     * Finds the player name in a 6b6t {@code /tpa} request notice.
     *
     * <p>The server tells you to answer with {@code /tpy <name>} or {@code /tpn <name>}, so the name after
     * those tokens is the requester. Reading it from there is more reliable than guessing at the sentence,
     * because the tokens are the server's own and do not change with localisation.
     */
    public static java.util.Optional<String> findTpaRequest(String raw) {
        if (raw == null || raw.isBlank()) return java.util.Optional.empty();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (!lower.contains("tpa") && !lower.contains("teleport")) return java.util.Optional.empty();

        for (String token : new String[] {"/tpy ", "tpy ", "/tpn ", "tpn "}) {
            int index = lower.indexOf(token);
            if (index >= 0) {
                String rest = raw.substring(index + token.length()).trim();
                String name = rest.split("[\\s,.!?]+")[0].trim();
                if (!name.isEmpty() && name.length() <= 16) return java.util.Optional.of(name);
            }
        }
        return java.util.Optional.empty();
    }

    private static boolean containsIgnoreCase(List<String> values, String needle) {
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(needle)) return true;
        }
        return false;
    }
}
