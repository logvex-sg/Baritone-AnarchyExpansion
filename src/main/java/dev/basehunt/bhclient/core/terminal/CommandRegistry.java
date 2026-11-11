package dev.basehunt.bhclient.core.terminal;

import dev.basehunt.bhclient.core.chat.ChatPermissions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The table of terminal commands, their dispatcher, tab-completion and confirmation gate.
 *
 * <p>This is the engine behind the in-game terminal. It is pure with respect to Minecraft: a handler is a
 * {@link Handler} that takes a {@link TerminalContext} and writes to its sinks, so the whole
 * "resolve the name, check the permission, run the handler" path - including aliases, unknown-command
 * errors and the confirmation gate - is covered by the headless suite.
 *
 * <p>Completion and permission come from the same {@link TerminalCommand} descriptors the help listing uses,
 * so a command that completes cannot be one the operator is not allowed to run without being told.
 */
public final class CommandRegistry {
    /** A command implementation. */
    @FunctionalInterface
    public interface Handler {
        void handle(TerminalContext context);
    }

    private record Entry(TerminalCommand command, Handler handler) {
    }

    private final Map<String, Entry> byName = new LinkedHashMap<>();
    private final List<Entry> ordered = new ArrayList<>();
    private ChatPermissions permissions = ChatPermissions.readOnlyOnly();

    /** Names of commands awaiting a confirming second invocation. */
    private final java.util.Set<String> pendingConfirmation = new java.util.HashSet<>();

    public void setPermissions(ChatPermissions permissions) {
        this.permissions = permissions == null ? ChatPermissions.readOnlyOnly() : permissions;
    }

    public ChatPermissions permissions() {
        return permissions;
    }

    /** Registers a command. A duplicate name replaces the earlier one, so re-registration is idempotent. */
    public void register(TerminalCommand command, Handler handler) {
        if (command == null || handler == null) return;

        Entry entry = new Entry(command, handler);
        ordered.removeIf(existing -> existing.command.name().equalsIgnoreCase(command.name()));
        ordered.add(entry);

        byName.put(command.name().toLowerCase(Locale.ROOT), entry);
        for (String alias : command.aliases()) byName.put(alias.toLowerCase(Locale.ROOT), entry);
    }

    public void clear() {
        byName.clear();
        ordered.clear();
        pendingConfirmation.clear();
    }

    public List<TerminalCommand> commands() {
        List<TerminalCommand> list = new ArrayList<>();
        for (Entry entry : ordered) list.add(entry.command());
        return list;
    }

    public TerminalCommand find(String name) {
        Entry entry = name == null ? null : byName.get(name.toLowerCase(Locale.ROOT));
        return entry == null ? null : entry.command();
    }

    /** Every command name and alias, for completion of the first token. */
    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (Entry entry : ordered) {
            names.add(entry.command.name());
            names.addAll(entry.command.aliases());
        }
        return names;
    }

    /**
     * Runs a parsed line.
     *
     * <p>A command marked as requiring confirmation is not run on its first invocation: it arms itself and
     * returns {@code "confirm"}, and only the immediate next invocation of the same command runs it. Any other
     * command disarms it. That is how a destructive action can be asked to be typed twice.
     *
     * @return a short result word: {@code "ok"}, {@code "denied"}, {@code "unknown"}, {@code "empty"} or
     *         {@code "confirm"}
     */
    public String dispatch(TerminalContext context) {
        if (context == null || context.name().isEmpty()) return "empty";

        String key = context.name().toLowerCase(Locale.ROOT);
        Entry entry = byName.get(key);
        if (entry == null) {
            context.error("unknown command '%s'; type help".formatted(context.name()));
            return "unknown";
        }

        String canonical = entry.command.name().toLowerCase(Locale.ROOT);
        boolean needsConfirm = entry.command.confirmFor(context.args());
        if (needsConfirm && !pendingConfirmation.remove(canonical)) {
            // Arm the confirmation and clear any other armed command, then ask the user to repeat it.
            pendingConfirmation.clear();
            pendingConfirmation.add(canonical);
            context.info("'%s' changes things; run it again to confirm.".formatted(entry.command.name()));
            return "confirm";
        }
        // Running anything else clears a stale armed command.
        pendingConfirmation.clear();

        String refusal = permissions.check(entry.command.name(), entry.command.levelFor(context.args()));
        if (refusal != null) {
            context.error("%s is not permitted here: %s".formatted(entry.command.name(), refusal));
            return "denied";
        }

        try {
            entry.handler.handle(context);
        } catch (RuntimeException e) {
            context.error("command '%s' failed: %s".formatted(entry.command.name(), e.getMessage()));
            return "error";
        }

        return "ok";
    }

    /**
     * Tab-completes a partial line.
     *
     * @param input the text typed so far
     * @return full replacement lines (the typed prefix plus each candidate), never null
     */
    public List<String> complete(String input) {
        if (input == null) input = "";
        boolean trailingSpace = !input.isEmpty() && Character.isWhitespace(input.charAt(input.length() - 1));
        int lastSpace = lastUnquotedSpace(input);

        String partial = trailingSpace || lastSpace < 0 ? "" : input.substring(lastSpace + 1);
        String prefix = lastSpace < 0 ? "" : input.substring(0, lastSpace + 1);

        // Completing the command name itself.
        if (lastSpace < 0) {
            int lead = 0;
            while (lead < partial.length() && (partial.charAt(lead) == '/' || partial.charAt(lead) == '.')) lead++;
            return filter(partial.substring(0, lead), names(), partial.substring(lead));
        }

        List<String> tokens = CommandLine.tokenize(input.trim());
        if (tokens.isEmpty()) return List.of();

        String commandName = tokens.get(0);
        while (!commandName.isEmpty() && (commandName.charAt(0) == '/' || commandName.charAt(0) == '.')) {
            commandName = commandName.substring(1);
        }
        Entry entry = byName.get(commandName.toLowerCase(Locale.ROOT));
        if (entry == null) return List.of();

        int argIndex = trailingSpace ? tokens.size() - 1 : tokens.size() - 2;
        if (argIndex < 0) argIndex = 0;

        List<String> candidates = new ArrayList<>();
        if (argIndex == 0) {
            for (TerminalCommand.Sub sub : entry.command.subcommands()) candidates.add(sub.name());
        }
        if (entry.command.completion() != null) candidates.addAll(entry.command.completion().complete(argIndex));

        return filter(prefix, candidates, partial);
    }

    /** Builds the help listing, one line per command. */
    public List<String> help() {
        List<String> lines = new ArrayList<>();
        lines.add("Terminal commands (%d):".formatted(ordered.size()));
        for (Entry entry : ordered) {
            ChatPermissions.Level level = entry.command.level();
            String suffix = level == ChatPermissions.Level.READ_ONLY ? "" : " [" + level.name().toLowerCase(Locale.ROOT) + "]";
            String confirm = entry.command.confirm() ? " (needs confirm)" : "";
            lines.add("  " + entry.command.helpLine() + suffix + confirm);
        }
        return lines;
    }

    /** Help for a single command, or a short "unknown" note. */
    public List<String> help(String name) {
        Entry entry = name == null ? null : byName.get(name.toLowerCase(Locale.ROOT));
        if (entry == null) return List.of("no command named '%s'".formatted(name));
        List<String> lines = new ArrayList<>();
        lines.add(entry.command.usage() + " - " + entry.command.description());
        lines.add("  level: " + entry.command.level().name().toLowerCase(Locale.ROOT)
            + (entry.command.confirm() ? ", needs confirmation" : ""));
        if (!entry.command.aliases().isEmpty()) lines.add("  aliases: " + String.join(", ", entry.command.aliases()));
        if (!entry.command.subcommands().isEmpty()) {
            List<String> subNames = new ArrayList<>();
            for (TerminalCommand.Sub sub : entry.command.subcommands()) subNames.add(sub.name());
            lines.add("  subcommands: " + String.join(", ", subNames));
        }
        return lines;
    }

    private static List<String> filter(String prefix, List<String> candidates, String partial) {
        List<String> result = new ArrayList<>();
        String needle = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (candidate == null || candidate.isEmpty()) continue;
            if (needle.isEmpty() || candidate.toLowerCase(Locale.ROOT).startsWith(needle)) {
                String line = prefix + candidate;
                if (!result.contains(line)) result.add(line);
            }
        }
        java.util.Collections.sort(result);
        return result;
    }

    private static int lastUnquotedSpace(String input) {
        boolean inQuotes = false;
        int last = -1;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            else if (Character.isWhitespace(c) && !inQuotes) last = i;
        }
        return last;
    }
}
