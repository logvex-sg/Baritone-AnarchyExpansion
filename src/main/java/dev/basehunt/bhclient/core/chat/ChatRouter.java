package dev.basehunt.bhclient.core.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recognises addon commands typed into Minecraft chat behind a configurable prefix.
 *
 * <p>The prefix system has one job that matters more than the rest: it must never let a message the player did
 * not type be treated as a command. A received chat message from another player is data, not an instruction,
 * and a message the addon itself printed is its own output. Only a message the player typed into the chat box
 * is a candidate, and even then it must carry the prefix and pass the permission check.
 *
 * <p>This class is pure: it takes a line and a prefix and says whether it is a command and what the parts are.
 * The routing decision (whether the player is allowed to run it) is {@link ChatPermissions}, and the execution
 * is the runtime. Splitting it this way is what lets the recognition and permission rules be covered by the
 * headless suite.
 */
public final class ChatRouter {

    /** Where a line came from, which decides whether it may ever be executed. */
    public enum Origin {
        /** Typed by the local player into the chat box. The only executable origin. */
        LOCAL_PLAYER,
        /** Sent by another player. Never executable. */
        REMOTE_PLAYER,
        /** Printed by the addon itself. Never executable. */
        ADDON,
        /** Anything else (server, system). Never executable. */
        SYSTEM
    }

    /** A parsed command line. */
    public record Parsed(String prefix, String name, List<String> args, String raw) {
        public String arg(int index) {
            return index >= 0 && index < args.size() ? args.get(index) : "";
        }

        public String joinFrom(int index) {
            if (index < 0 || index >= args.size()) return "";
            return String.join(" ", args.subList(index, args.size()));
        }
    }

    private ChatRouter() {
    }

    /**
     * Parses a line if it starts with any of the configured prefixes and came from an executable origin.
     *
     * @param line    the raw chat line
     * @param prefixes the configured prefixes, e.g. {@code ["!", ".bh"]}
     * @param origin  where the line came from
     * @return the parsed command, or null when the line is not a command or is not executable
     */
    public static Parsed parse(String line, List<String> prefixes, Origin origin) {
        if (line == null || origin != Origin.LOCAL_PLAYER) return null;
        if (prefixes == null || prefixes.isEmpty()) return null;

        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;

        String matched = null;
        for (String prefix : prefixes) {
            if (prefix == null || prefix.isEmpty()) continue;
            if (trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
                // Prefer the longest matching prefix so ".bh" wins over "." when both are configured.
                if (matched == null || prefix.length() > matched.length()) matched = prefix;
            }
        }
        if (matched == null) return null;

        String rest = trimmed.substring(matched.length()).trim();
        if (rest.isEmpty()) return null;

        String[] tokens = rest.split("\\s+");
        String name = tokens[0].toLowerCase(Locale.ROOT);
        List<String> args = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) args.add(tokens[i]);

        return new Parsed(matched, name, args, rest);
    }

    /** True when the line would be recognised as a command, regardless of origin. */
    public static boolean hasPrefix(String line, List<String> prefixes) {
        if (line == null || prefixes == null) return false;
        String trimmed = line.trim();
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.isEmpty() && trimmed.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return true;
            }
        }
        return false;
    }
}
