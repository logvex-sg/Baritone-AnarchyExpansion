package dev.basehunt.bhclient.core.chat;

/**
 * The metadata for one chat/terminal command: what it is called, how it is used, what it does, and how much it
 * is allowed to change. The metadata is separate from the handler so the help text, the terminal's command
 * list and the permission check all read from the same descriptor and cannot drift apart.
 */
public record ChatCommand(String name, String usage, String description, ChatPermissions.Level level,
                          String[] aliases) {
    public ChatCommand(String name, String usage, String description, ChatPermissions.Level level) {
        this(name, usage, description, level, new String[0]);
    }

    /** A help line, e.g. {@code "!withdraw <item> [amount] - pull items out of the open container"}. */
    public String helpLine(String prefix) {
        return "%s%s - %s".formatted(prefix, usage, description);
    }

    public boolean matches(String candidate) {
        if (candidate == null) return false;
        if (name.equalsIgnoreCase(candidate)) return true;
        for (String alias : aliases) if (alias.equalsIgnoreCase(candidate)) return true;
        return false;
    }
}
