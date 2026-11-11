package dev.basehunt.bhclient.core.terminal;

import dev.basehunt.bhclient.core.chat.ChatPermissions;

import java.util.ArrayList;
import java.util.List;

/**
 * The metadata for one terminal command: what it is called, how it is used, what it does, how much it may
 * change, how its arguments complete, and whether it must be confirmed.
 *
 * <p>The metadata is separate from the handler for the same reason the chat command metadata is: the help
 * text, the tab-completion and the permission check all read from one descriptor, so they cannot drift apart.
 *
 * <p>A command may declare subcommands, and each subcommand may carry its own permission level. That is what
 * lets {@code storage list} be a read-only command while {@code storage withdraw} is not, without splitting
 * the command into two unrelated entries.
 */
public record TerminalCommand(String name, String usage, String description, ChatPermissions.Level level,
                              List<String> aliases, List<Sub> subcommands, CompletionProvider completion,
                              boolean confirm) {

    /** A subcommand with its own permission level and confirmation requirement. */
    public record Sub(String name, ChatPermissions.Level level, boolean confirm) {
        public Sub(String name, ChatPermissions.Level level) {
            this(name, level, false);
        }
    }

    /** Supplies argument completions for a given argument index (0 is the first argument after the name). */
    @FunctionalInterface
    public interface CompletionProvider {
        List<String> complete(int argIndex);
    }

    public TerminalCommand {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        subcommands = subcommands == null ? List.of() : List.copyOf(subcommands);
    }

    /** The effective level of an invocation: the matching subcommand's level, else the command's own. */
    public ChatPermissions.Level levelFor(List<String> args) {
        if (args == null || args.isEmpty()) return level;
        String first = args.get(0).toLowerCase(java.util.Locale.ROOT);
        for (Sub sub : subcommands) {
            if (sub.name().equalsIgnoreCase(first)) return sub.level();
        }
        return level;
    }

    /** True when the invocation must be confirmed: the command or its matching subcommand says so. */
    public boolean confirmFor(List<String> args) {
        if (confirm) return true;
        if (args == null || args.isEmpty()) return false;
        String first = args.get(0).toLowerCase(java.util.Locale.ROOT);
        for (Sub sub : subcommands) {
            if (sub.name().equalsIgnoreCase(first)) return sub.confirm();
        }
        return false;
    }

    /** A help line, e.g. {@code withdraw <item> [amount] - pull items out of the open container}. */
    public String helpLine() {
        return "%s - %s".formatted(usage, description);
    }

    public boolean matches(String candidate) {
        if (candidate == null) return false;
        if (name.equalsIgnoreCase(candidate)) return true;
        for (String alias : aliases) if (alias.equalsIgnoreCase(candidate)) return true;
        return false;
    }

    public static Builder builder(String name, String usage, String description, ChatPermissions.Level level) {
        return new Builder(name, usage, description, level);
    }

    /** Fluent construction that keeps the record's argument list readable at the call site. */
    public static final class Builder {
        private final String name;
        private final String usage;
        private final String description;
        private final ChatPermissions.Level level;
        private final List<String> aliases = new ArrayList<>();
        private final List<Sub> subcommands = new ArrayList<>();
        private CompletionProvider completion;
        private boolean confirm;

        private Builder(String name, String usage, String description, ChatPermissions.Level level) {
            this.name = name;
            this.usage = usage;
            this.description = description;
            this.level = level;
        }

        public Builder aliases(String... names) {
            for (String alias : names) if (alias != null && !alias.isBlank()) aliases.add(alias);
            return this;
        }

        public Builder sub(String subName, ChatPermissions.Level subLevel) {
            subcommands.add(new Sub(subName, subLevel));
            return this;
        }

        public Builder sub(String subName, ChatPermissions.Level subLevel, boolean needsConfirm) {
            subcommands.add(new Sub(subName, subLevel, needsConfirm));
            return this;
        }

        public Builder completions(CompletionProvider provider) {
            this.completion = provider;
            return this;
        }

        public Builder confirm() {
            this.confirm = true;
            return this;
        }

        public TerminalCommand build() {
            return new TerminalCommand(name, usage, description, level, aliases, subcommands, completion, confirm);
        }
    }
}
