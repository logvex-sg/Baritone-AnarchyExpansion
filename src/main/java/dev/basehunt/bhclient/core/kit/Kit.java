package dev.basehunt.bhclient.core.kit;

import java.util.List;
import java.util.Locale;

/**
 * One claimable kit: a name, the chat line that claims it, and how long the server makes you wait between
 * claims.
 *
 * <p>Anarchy servers hand out kits with a command such as {@code /kit travel}, and the cooldown is enforced
 * server-side. The addon cannot read that cooldown, so it tracks the time since its own last successful claim
 * and refuses to spam the command before the cooldown is up. "Unknown" is a real state: a kit the addon has
 * never claimed has no local cooldown, so the first attempt is always allowed.
 */
public record Kit(String name, String command, int cooldownSeconds, List<String> categories) {

    public Kit {
        if (name == null || name.isBlank()) name = "default";
        if (command == null || command.isBlank()) command = "/kit " + name;
        if (categories == null) categories = List.of();
        cooldownSeconds = Math.max(0, cooldownSeconds);
    }

    public static Kit of(String name, String command, int cooldownSeconds) {
        return new Kit(name, command, cooldownSeconds, List.of());
    }

    /** The chat line to send, normalised to start with a slash. */
    public String claimLine() {
        String line = command.trim();
        return line.startsWith("/") ? line : "/" + line;
    }

    public String key() {
        return name.toLowerCase(Locale.ROOT);
    }
}
