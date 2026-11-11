package dev.basehunt.bhclient.core.chat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides who may run which prefix command.
 *
 * <p>Permissions are per-command with a wildcard, and there is a hard split between what is allowed
 * automatically and what is not. Read-only commands (status, list, preview, help) are safe to run from chat.
 * Anything that moves the player, clicks a container or changes settings is destructive and is refused unless
 * the operator has explicitly allowed it, because a chat line is easy to mistype and easier to spoof.
 *
 * <p>The default policy is "allow read-only, refuse destructive", and the operator opts in per command. That
 * is a deliberately conservative default for a client that can empty a chest.
 */
public final class ChatPermissions {

    /** How much a command is allowed to change. */
    public enum Level {
        /** Reads state only. Allowed by default. */
        READ_ONLY,
        /** Moves the player or clicks a container. Refused by default. */
        MOVEMENT,
        /** Changes settings, starts or stops modules, deletes data. Refused by default. */
        CONTROL
    }

    private final Set<String> allowed;
    private final boolean allowAllDestructive;

    private ChatPermissions(Set<String> allowed, boolean allowAllDestructive) {
        this.allowed = allowed;
        this.allowAllDestructive = allowAllDestructive;
    }

    /** A policy that allows every read-only command and no destructive one. */
    public static ChatPermissions readOnlyOnly() {
        return new ChatPermissions(Set.of(), false);
    }

    /** Builds a policy from a configured allow-list of command names ("*" allows everything). */
    public static ChatPermissions of(Set<String> allowList, boolean allowAllDestructive) {
        Set<String> normalised = new HashSet<>();
        if (allowList != null) {
            for (String entry : allowList) {
                if (entry != null && !entry.isBlank()) normalised.add(entry.trim().toLowerCase(Locale.ROOT));
            }
        }
        return new ChatPermissions(normalised, allowAllDestructive);
    }

    /**
     * @param name  the command name
     * @param level how much the command changes
     * @return null when allowed, or a reason it was refused
     */
    public String check(String name, Level level) {
        if (level == Level.READ_ONLY) return null;

        if (allowAllDestructive) return null;

        String key = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (allowed.contains("*") || allowed.contains(key)) return null;

        return level == Level.MOVEMENT
            ? "this command moves the player; add it to the chat allow-list first"
            : "this command changes settings; add it to the chat allow-list first";
    }

    public boolean allows(String name, Level level) {
        return check(name, level) == null;
    }

    /** The command names the operator has allowed, for the terminal's help output. */
    public Set<String> allowList() {
        return allowed;
    }

    /** A static table of the built-in command levels, so the router and the help text agree. */
    public static Map<String, Level> builtinLevels() {
        Map<String, Level> levels = new HashMap<>();
        // Read-only.
        for (String name : new String[] {
            "help", "status", "list", "stash", "players", "player", "elytra", "echest", "inventory",
            "kit", "kits", "route", "storage", "tasks", "whoami", "ping", "version"
        }) {
            levels.put(name, Level.READ_ONLY);
        }
        // Movement.
        for (String name : new String[] {
            "goto", "come", "follow", "goto-player", "withdraw", "deposit", "restock", "grab", "put",
            "echest-open", "sort", "organise", "organize"
        }) {
            levels.put(name, Level.MOVEMENT);
        }
        // Control.
        for (String name : new String[] {
            "start", "stop", "pause", "resume", "toggle", "module", "set", "kit-claim", "claim", "kitbot",
            "explore", "cancel", "clear", "reset"
        }) {
            levels.put(name, Level.CONTROL);
        }
        return levels;
    }

    /** The level of a built-in command, defaulting to CONTROL for anything unrecognised. */
    public static Level levelOf(String name) {
        return builtinLevels().getOrDefault(name == null ? "" : name.toLowerCase(Locale.ROOT), Level.CONTROL);
    }
}
