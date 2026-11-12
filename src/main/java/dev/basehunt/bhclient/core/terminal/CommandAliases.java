package dev.basehunt.bhclient.core.terminal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands configurable shortcuts before a line reaches the {@link CommandRegistry}.
 *
 * <p>A shortcut is a short alias a user defines, e.g. {@code s} for {@code status} or {@code w ender_pearl}
 * for {@code withdraw ender_pearl}. The first token is matched case-insensitively and replaced with the
 * expansion; the remaining arguments are appended. Expansions may themselves contain arguments
 * ({@code w ender_pearl} above), and expansion is single-pass so a shortcut cannot loop.
 *
 * <p>Expansion is pure, so the terminal's shortcut rules are covered by the headless suite.
 */
public final class CommandAliases {
    private final Map<String, String> aliases = new LinkedHashMap<>();

    /** Adds or replaces a shortcut. Blank names or expansions are ignored. */
    public void put(String name, String expansion) {
        if (name == null || name.isBlank() || expansion == null || expansion.isBlank()) return;
        aliases.put(name.trim().toLowerCase(java.util.Locale.ROOT), expansion.trim());
    }

    public void remove(String name) {
        if (name != null) aliases.remove(name.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public void clear() {
        aliases.clear();
    }

    public boolean isEmpty() {
        return aliases.isEmpty();
    }

    public Map<String, String> all() {
        return Map.copyOf(aliases);
    }

    public String expansion(String name) {
        return name == null ? null : aliases.get(name.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Expands a shortcut at the start of a line.
     *
     * @return the expanded line, or the original when the first token is not a shortcut
     */
    public String expand(String line) {
        if (line == null || line.isBlank()) return line;

        Matcher matcher = Pattern.compile("^\\s*(\\S+)(.*)$", Pattern.DOTALL).matcher(line);
        if (!matcher.matches()) return line;

        String head = matcher.group(1);
        String rest = matcher.group(2);

        // A leading command prefix is ignored for lookup, so ".s" and "s" both expand.
        String lookup = head;
        while (!lookup.isEmpty() && (lookup.charAt(0) == '/' || lookup.charAt(0) == '.')) lookup = lookup.substring(1);

        String expansion = aliases.get(lookup.toLowerCase(java.util.Locale.ROOT));
        if (expansion == null) return line;

        String expanded = rest.isBlank() ? expansion : expansion + " " + rest.trim();
        return expanded;
    }
}
