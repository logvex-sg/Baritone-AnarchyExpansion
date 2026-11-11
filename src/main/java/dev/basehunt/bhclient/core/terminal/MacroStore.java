package dev.basehunt.bhclient.core.terminal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Named sequences of terminal commands, the terminal's scripting surface.
 *
 * <p>A macro is written in one settings line as {@code name = step ; step ; step}, for example
 * {@code tidy = storage list ; tasks list ; status}. Running a macro replays each step through the ordinary
 * command registry, so a macro can only do what a typed command can do, and the permission and confirmation
 * rules still apply to every step. Parsing is pure, so the syntax is covered by the headless suite.
 */
public final class MacroStore {
    private final Map<String, List<String>> macros = new LinkedHashMap<>();

    /** Parses a settings line. A line without a name, an equals sign or any step is ignored. */
    public void addLine(String line) {
        if (line == null) return;
        int eq = line.indexOf('=');
        if (eq <= 0) return;

        String name = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) return;

        List<String> steps = new ArrayList<>();
        for (String step : line.substring(eq + 1).split(";")) {
            String trimmed = step.trim();
            if (!trimmed.isEmpty()) steps.add(trimmed);
        }
        if (steps.isEmpty()) return;

        macros.put(name, steps);
    }

    public void clear() {
        macros.clear();
    }

    public boolean isEmpty() {
        return macros.isEmpty();
    }

    public List<String> names() {
        return new ArrayList<>(macros.keySet());
    }

    public boolean has(String name) {
        return name != null && macros.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /** The steps of a macro, or an empty list when it does not exist. */
    public List<String> steps(String name) {
        if (name == null) return List.of();
        List<String> steps = macros.get(name.toLowerCase(Locale.ROOT));
        return steps == null ? List.of() : List.copyOf(steps);
    }

    public int size() {
        return macros.size();
    }
}
