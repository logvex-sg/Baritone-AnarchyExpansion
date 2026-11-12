package dev.basehunt.bhclient.core.sixbt;

import java.util.Optional;

/**
 * The current 6b6t Hotspot, as announced in chat.
 *
 * <p>A Hotspot is a short-lived player-run event: someone runs {@code /hotspot create}, everyone who wants to
 * join runs {@code /hotspot} to teleport there, and it ends when it is cancelled or the time runs out. On an
 * anarchy server a Hotspot is also a magnet for fights, so knowing one is live - and where - matters for
 * deciding whether to join or to stay away.
 *
 * <p>The server announces a Hotspot in chat; the wording is not guaranteed, so this pulls the fields out of
 * whatever it can find and reports "unknown" rather than guessing. Pure string logic, so it is tested
 * headlessly.
 */
public final class HotspotInfo {

    /** One observed Hotspot. */
    public record Snapshot(String name, String dimension, int x, int z, long atMillis, boolean active) {
        public String coords() {
            return "%d, %d".formatted(x, z);
        }

        public String describe() {
            if (!active) return "no active hotspot";
            return "%s [%s] %s".formatted(name.isBlank() ? "hotspot" : name, dimension, coords());
        }
    }

    private HotspotInfo() {
    }

    /**
     * Reads a Hotspot announcement.
     *
     * <p>Coordinates are taken from the first {@code x, z} or {@code x, y, z} triple on the line; the name is
     * the text after the word "hotspot" when the server names it. A line that only says a Hotspot ended is
     * reported as inactive.
     */
    public static Optional<Snapshot> parse(String message, String dimension, long nowMillis) {
        if (message == null || message.isBlank()) return Optional.empty();
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("hotspot")) return Optional.empty();

        boolean ended = lower.contains("ended") || lower.contains("cancelled") || lower.contains("canceled")
            || lower.contains("over") || lower.contains("no longer");

        int[] coords = extractCoords(message);
        String name = extractName(message);

        Snapshot snapshot = new Snapshot(
            name,
            dimension == null || dimension.isBlank() ? "unknown" : dimension,
            coords.length >= 2 ? coords[0] : 0,
            coords.length >= 2 ? coords[1] : 0,
            nowMillis,
            !ended);

        return Optional.of(snapshot);
    }

    /** The first coordinate triple or pair on the line, as {x, y, z} or {x, z}. */
    static int[] extractCoords(String text) {
        // Find runs of "-123, 64, -456" or "-123, -456".
        String[] tokens = text.replace("(", " ").replace(")", " ").split("[,\\s]+");
        int[] found = new int[3];
        int count = 0;
        for (String token : tokens) {
            String cleaned = token.replaceAll("[^0-9-]", "");
            if (cleaned.isEmpty() || cleaned.equals("-")) continue;
            try {
                int value = Integer.parseInt(cleaned);
                if (count < 3) found[count++] = value;
            } catch (NumberFormatException ignored) {
                // Not a coordinate; keep scanning.
            }
            if (count == 3) break;
        }
        if (count == 0) return new int[0];
        if (count == 1) return new int[] {found[0]};
        if (count == 2) return new int[] {found[0], found[1]};
        return new int[] {found[0], found[2]};
    }

    private static String extractName(String message) {
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        int index = lower.indexOf("hotspot");
        if (index < 0) return "";
        String rest = message.substring(index + "hotspot".length()).trim();
        rest = rest.replaceAll("^[:\\-]+", "").trim();
        // Cut at the first coordinate-looking token.
        rest = rest.split("\\s*[-+]?\\d")[0].trim();
        return rest.length() > 48 ? rest.substring(0, 48) : rest;
    }
}
