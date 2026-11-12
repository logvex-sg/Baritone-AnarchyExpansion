package dev.basehunt.bhclient.core.sixbt;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The chat replies a 6b6t kit bot whispers to a requester, as configurable templates.
 *
 * <p>A kit bot runs unattended, so its replies are its only voice: the requester has to be told that the bot
 * is fetching the kit, that they are queued behind other people, that they are not allowed, or that the kit
 * they asked for does not exist. Those replies are public-facing text, so they are data - one template per
 * {@link Kind}, overridable from the settings - rather than strings buried in the delivery code.
 *
 * <p>Replies are sent as a private message ({@code /msg <player> <text>}) so a busy anarchy chat is not
 * spammed. Templates may use {@code {player}}, {@code {kit}}, {@code {place}}, {@code {minutes}} and
 * {@code {max}}; an unknown placeholder is left as-is rather than throwing, so a typo in a template never
 * breaks a delivery.
 *
 * <p>Pure string logic, so every template and every substitution is covered by the headless suite.
 */
public final class KitMessages {

    /** The situations the bot speaks about. */
    public enum Kind {
        /** Fetching the kit from storage before teleporting. */
        GRABBING,
        /** The requester is not on the allow-list. */
        NOT_WHITELISTED,
        /** No loadout is registered under the requested name. */
        UNKNOWN_KIT,
        /** The requester asked again inside the cooldown window. */
        COOLDOWN,
        /** The queue already holds the maximum number of requests. */
        QUEUE_FULL,
        /** The request was accepted but the requester is behind others. */
        QUEUED,
        /** The kit was handed over and confirmed. */
        DELIVERED,
        /** The delivery could not complete. */
        FAILED
    }

    /** The default template per kind. */
    private static final Map<Kind, String> DEFAULTS = new EnumMap<>(Kind.class);

    static {
        DEFAULTS.put(Kind.GRABBING, "Grabbing the kit, please wait.");
        DEFAULTS.put(Kind.NOT_WHITELISTED, "Not whitelisted (contact logvex).");
        DEFAULTS.put(Kind.UNKNOWN_KIT, "Kit doesnt exist.");
        DEFAULTS.put(Kind.COOLDOWN, "Error, {minutes} minute cooldown active.");
        DEFAULTS.put(Kind.QUEUE_FULL, "Error, too many kits (max {max}).");
        DEFAULTS.put(Kind.QUEUED, "You are in the queue, please wait. You are in place {place}.");
        DEFAULTS.put(Kind.DELIVERED, "Kit delivered.");
        DEFAULTS.put(Kind.FAILED, "Error, the delivery could not be completed.");
    }

    private final Map<Kind, String> templates = new EnumMap<>(Kind.class);

    public KitMessages() {
        templates.putAll(DEFAULTS);
    }

    /** A catalogue with the defaults, plus any {@code kind:template} overrides. */
    public static KitMessages fromLines(List<String> lines) {
        KitMessages messages = new KitMessages();
        if (lines == null) return messages;
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            Kind kind = parseKind(line.substring(0, colon));
            if (kind == null) continue;
            String template = line.substring(colon + 1).trim();
            if (!template.isEmpty()) messages.templates.put(kind, template);
        }
        return messages;
    }

    /** The {@link Kind} for a config token, or null when it is not one. */
    public static Kind parseKind(String token) {
        if (token == null) return null;
        String key = token.trim().toLowerCase(Locale.ROOT);
        for (Kind kind : Kind.values()) {
            if (kind.name().toLowerCase(Locale.ROOT).equals(key)) return kind;
        }
        return null;
    }

    /** The default template for a kind. */
    public static String defaultTemplate(Kind kind) {
        return DEFAULTS.getOrDefault(kind, "");
    }

    /** The active template for a kind. */
    public String template(Kind kind) {
        return templates.getOrDefault(kind, defaultTemplate(kind));
    }

    /** Renders a template with {@code {placeholder}} substitutions from {@code vars}. */
    public String render(Kind kind, Map<String, String> vars) {
        return substitute(template(kind), vars);
    }

    /**
     * Renders a kind as a private-message command, e.g. {@code /msg Steve Kit delivered.}.
     *
     * @param player the requester to whisper
     * @param vars   placeholder values
     */
    public String whisper(Kind kind, String player, Map<String, String> vars) {
        if (player == null || player.isBlank()) return "";
        return "/msg " + player.trim() + " " + render(kind, vars);
    }

    /** Every {@code kind:template} line, for the terminal and the menu. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            lines.add(kind.name().toLowerCase(Locale.ROOT) + ":" + template(kind));
        }
        return lines;
    }

    /** Replaces {@code {name}} tokens; an unknown token is left untouched. */
    static String substitute(String template, Map<String, String> vars) {
        if (template == null) return "";
        if (vars == null || vars.isEmpty()) return template;

        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
