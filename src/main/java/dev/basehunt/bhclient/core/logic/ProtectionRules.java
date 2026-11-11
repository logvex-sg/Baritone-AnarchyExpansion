package dev.basehunt.bhclient.core.logic;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides which slots and items an inventory operation must never touch.
 *
 * <p>This is the guard that keeps a "sort everything" from moving your worn elytra out of its slot or
 * dropping a shulker you forgot was full. It is pure so the rules can be tested without a client: the
 * caller turns them into a slot index set and a predicate before building a {@link SortPlanner} plan.
 */
public final class ProtectionRules {

    private ProtectionRules() {
    }

    /**
     * Player-inventory indices (0..35, hotbar first) that a sort must leave alone.
     *
     * @param hotbarReserve how many hotbar slots to keep free of sorting
     * @param pinned        explicit indices the user pinned
     */
    public static Set<Integer> protectedSlots(int hotbarReserve, Set<Integer> pinned) {
        Set<Integer> protectedIndices = new HashSet<>();
        int reserve = Math.max(0, Math.min(9, hotbarReserve));
        for (int i = 0; i < reserve; i++) protectedIndices.add(i);
        if (pinned != null) protectedIndices.addAll(pinned);
        return protectedIndices;
    }

    /** True when an item id is on the user's protected list (namespaces are ignored). */
    public static boolean isProtectedItem(String itemId, Set<String> protectedIds) {
        if (itemId == null || protectedIds == null || protectedIds.isEmpty()) return false;
        return protectedIds.contains(normalise(itemId));
    }

    /** Parses a comma- or whitespace-separated list of item ids into a normalised set. */
    public static Set<String> parseList(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split("[,\\s]+"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(ProtectionRules::normalise)
            .collect(Collectors.toSet());
    }

    private static String normalise(String itemId) {
        String id = itemId.trim().toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }
}
