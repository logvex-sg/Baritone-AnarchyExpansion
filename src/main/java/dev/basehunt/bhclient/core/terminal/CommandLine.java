package dev.basehunt.bhclient.core.terminal;

import java.util.ArrayList;
import java.util.List;

/**
 * A raw terminal line split into a command name and its arguments.
 *
 * <p>The parser is deliberately small but not naive: arguments may be wrapped in double quotes so an item
 * name with a space ("ender pearl") or a chat line stays one argument, and a backslash escapes the next
 * character inside or outside quotes. Parsing is pure, so the terminal's tokenising rules are covered by the
 * headless suite rather than only being exercised by hand.
 */
public record CommandLine(String name, List<String> args, String raw) {

    /** Splits a line into tokens, honouring double quotes and backslash escapes. */
    public static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        if (line == null) return tokens;

        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean hasToken = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '\\' && i + 1 < line.length()) {
                current.append(line.charAt(++i));
                hasToken = true;
                continue;
            }

            if (c == '"') {
                inQuotes = !inQuotes;
                hasToken = true;
                continue;
            }

            if (Character.isWhitespace(c) && !inQuotes) {
                if (hasToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    hasToken = false;
                }
                continue;
            }

            current.append(c);
            hasToken = true;
        }

        if (hasToken) tokens.add(current.toString());
        return tokens;
    }

    /**
     * Parses a line. Returns null when the line is blank or has no command name.
     *
     * <p>A leading command prefix (such as {@code /} or {@code .}) is stripped so a user can type
     * {@code .status} or {@code status} interchangeably.
     */
    public static CommandLine parse(String line) {
        List<String> tokens = tokenize(line);
        if (tokens.isEmpty()) return null;

        String name = tokens.get(0);
        while (!name.isEmpty() && (name.charAt(0) == '/' || name.charAt(0) == '.')) {
            name = name.substring(1);
        }
        if (name.isEmpty()) return null;

        return new CommandLine(name.toLowerCase(java.util.Locale.ROOT), tokens.subList(1, tokens.size()),
            line.trim());
    }

    public String arg(int index) {
        return index >= 0 && index < args.size() ? args.get(index) : "";
    }

    public int argCount() {
        return args.size();
    }

    public String joinFrom(int index) {
        if (index < 0 || index >= args.size()) return "";
        return String.join(" ", args.subList(index, args.size()));
    }

    /** Parses an argument as an integer, or returns the fallback when it is missing or not a number. */
    public int intArg(int index, int fallback) {
        String value = arg(index);
        if (value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
