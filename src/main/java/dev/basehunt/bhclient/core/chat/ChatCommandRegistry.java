package dev.basehunt.bhclient.core.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The table of chat commands and the dispatcher that runs them.
 *
 * <p>Dispatch enforces the permission policy before a handler is called: a command whose level the operator
 * has not allowed is refused with a reason and its handler never runs. The dispatcher is deliberately pure
 * with respect to Minecraft - a handler is a {@link Handler} that takes a {@link ChatContext} - so the
 * "read-only allowed, destructive refused, unknown rejected" rules are all covered by the headless suite.
 */
public final class ChatCommandRegistry {
    /** A command implementation. */
    @FunctionalInterface
    public interface Handler {
        void handle(ChatContext context);
    }

    private final Map<String, Entry> byName = new LinkedHashMap<>();
    private final List<Entry> ordered = new ArrayList<>();
    private ChatPermissions permissions = ChatPermissions.readOnlyOnly();

    private record Entry(ChatCommand command, Handler handler) {
    }

    public void setPermissions(ChatPermissions permissions) {
        this.permissions = permissions == null ? ChatPermissions.readOnlyOnly() : permissions;
    }

    public ChatPermissions permissions() {
        return permissions;
    }

    /** Registers a command. A duplicate name replaces the earlier one, which keeps re-registration idempotent. */
    public void register(ChatCommand command, Handler handler) {
        if (command == null || handler == null) return;

        Entry entry = new Entry(command, handler);
        ordered.removeIf(existing -> existing.command.name().equalsIgnoreCase(command.name()));
        ordered.add(entry);

        byName.put(command.name().toLowerCase(java.util.Locale.ROOT), entry);
        for (String alias : command.aliases()) {
            byName.put(alias.toLowerCase(java.util.Locale.ROOT), entry);
        }
    }

    public List<ChatCommand> commands() {
        List<ChatCommand> list = new ArrayList<>();
        for (Entry entry : ordered) list.add(entry.command());
        return list;
    }

    public ChatCommand find(String name) {
        Entry entry = name == null ? null : byName.get(name.toLowerCase(java.util.Locale.ROOT));
        return entry == null ? null : entry.command();
    }

    /**
     * Runs a parsed command.
     *
     * @return a short result word for logging: {@code "ok"}, {@code "denied"}, {@code "unknown"} or
     *         {@code "empty"}
     */
    public String dispatch(ChatContext context) {
        if (context == null) return "empty";

        Entry entry = byName.get(context.name().toLowerCase(java.util.Locale.ROOT));
        if (entry == null) {
            context.error("unknown command '%s'; try %shelp".formatted(context.name(), context.prefix()));
            return "unknown";
        }

        String refusal = permissions.check(entry.command.name(), entry.command.level());
        if (refusal != null) {
            context.error("%s is not permitted from chat: %s".formatted(entry.command.name(), refusal));
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

    /** Builds the help listing, one line per command. */
    public List<String> help(String prefix) {
        List<String> lines = new ArrayList<>();
        lines.add("Commands (prefix %s):".formatted(prefix));
        for (Entry entry : ordered) {
            ChatPermissions.Level level = entry.command.level();
            String suffix = level == ChatPermissions.Level.READ_ONLY ? "" : " [" + level.name().toLowerCase() + "]";
            lines.add("  " + entry.command.helpLine(prefix) + suffix);
        }
        return lines;
    }

    /** Command names for autocomplete. */
    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (Entry entry : ordered) names.add(entry.command.name());
        return names;
    }
}
