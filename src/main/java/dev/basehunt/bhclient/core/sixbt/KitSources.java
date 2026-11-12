package dev.basehunt.bhclient.core.sixbt;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Matches a requested kit name to the container that holds it, by name.
 *
 * <p>A kit maker does not have to enumerate every item of a kit. The tidiest way to stock one is to pre-pack
 * a shulker box, rename it after the kit, and carry it: a box called <em>totem</em> answers {@code >lxkit
 * totem}. The kit name and the container name are then the same string, which is what this class decides.
 *
 * <p>Matching is on the <em>normalised</em> name, because the same kit gets written many ways by hand - a
 * colour code, a {@code _} instead of a space, a trailing "shulker box", or a "kit " prefix. Normalising both
 * sides means {@code Totem}, {@code §6totem}, {@code totem_shulker_box} and {@code kit totem} all answer the
 * request {@code totem}.
 *
 * <p>It is deliberately strict about one thing: a display name only counts when it is the kit name or the kit
 * name followed by a separator ({@code totem 2}, {@code totem-2}). A box called {@code totems} does not answer
 * {@code totem}, so an accidental near-miss delivers nothing rather than the wrong kit.
 *
 * <p>Pure string logic, so every spelling rule is covered by the headless suite.
 */
public final class KitSources {

    /** A stack the bot can see, with the name it is displayed under. */
    public record NamedStack(String itemId, String displayName, int count) {
        public NamedStack {
            itemId = itemId == null ? "" : itemId.trim().toLowerCase(Locale.ROOT);
            displayName = displayName == null ? "" : displayName;
            count = Math.max(0, count);
        }

        /** True when this stack is a shulker box of any colour. */
        public boolean isShulker() {
            return isShulkerId(itemId);
        }

        /** A one-line description for chat and the terminal. */
        public String describe() {
            return displayName.isBlank() ? itemId : displayName;
        }
    }

    /** {@code §x} formatting codes, which a server may put in a renamed container. */
    private static final Pattern FORMATTING = Pattern.compile("\u00a7.");

    /** A plausible Minecraft username: 3-16 of {@code A-Z a-z 0-9 _}. */
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    /** Suffixes stripped from a container name, longest first so "shulker box" wins over "shulker". */
    private static final List<String> SUFFIXES = List.of(" shulker box", " shulker", " kit");

    private KitSources() {
    }

    /** True when an item id is a shulker box, in any colour. */
    public static boolean isShulkerId(String itemId) {
        return itemId != null && itemId.trim().toLowerCase(Locale.ROOT).endsWith("shulker_box");
    }

    /**
     * Reduces a name to the form two spellings of the same kit share: lowercase, no colour codes, single
     * spaces, and no trailing "shulker"/"shulker box"/"kit" or leading "kit ".
     */
    public static String normalize(String name) {
        if (name == null) return "";
        String value = FORMATTING.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("");
        value = value.replace('_', ' ').replaceAll("\\s+", " ").trim();
        if (value.isEmpty()) return "";

        boolean changed = true;
        while (changed) {
            changed = false;
            for (String suffix : SUFFIXES) {
                if (value.length() > suffix.length() && value.endsWith(suffix)) {
                    value = value.substring(0, value.length() - suffix.length()).trim();
                    changed = true;
                }
            }
        }
        if (value.startsWith("kit ")) value = value.substring(4).trim();
        return value;
    }

    /**
     * True when a container displayed as {@code displayName} holds the kit {@code kitName}.
     *
     * <p>The name must be the kit name exactly, or the kit name followed by a separator - so "totem" and
     * "totem 2" answer a request for "totem", while "totems" does not.
     */
    public static boolean matches(String kitName, String displayName) {
        String kit = normalize(kitName);
        String shown = normalize(displayName);
        if (kit.isEmpty() || shown.isEmpty()) return false;
        if (kit.equals(shown)) return true;
        if (shown.length() <= kit.length()) return false;

        // The next character must not be a letter or digit, so "totem 2" matches but "totems" does not.
        char next = shown.charAt(kit.length());
        return !Character.isLetterOrDigit(next);
    }

    /** True when a display name looks like a Minecraft username, used to read a bare {@code Name: body} line. */
    public static boolean isUsername(String name) {
        return name != null && USERNAME.matcher(name.trim()).matches();
    }

    /** The first stack whose display name matches the kit, or empty. */
    public static Optional<NamedStack> findByName(List<NamedStack> stacks, String kitName) {
        if (stacks == null) return Optional.empty();
        for (NamedStack stack : stacks) {
            if (stack != null && matches(kitName, stack.displayName())) return Optional.of(stack);
        }
        return Optional.empty();
    }

    /** The first shulker box whose display name matches the kit, or empty. */
    public static Optional<NamedStack> findShulkerByName(List<NamedStack> stacks, String kitName) {
        if (stacks == null) return Optional.empty();
        for (NamedStack stack : stacks) {
            if (stack != null && stack.isShulker() && matches(kitName, stack.displayName())) {
                return Optional.of(stack);
            }
        }
        return Optional.empty();
    }
}
