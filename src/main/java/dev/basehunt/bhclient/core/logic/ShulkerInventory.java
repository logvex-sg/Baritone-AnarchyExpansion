package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Describes and reasons about shulker boxes without opening them.
 *
 * <p>A shulker's contents are only knowable when the box is legitimately open (a vanilla shulker screen)
 * or when the server has sent the contents in the item's component data. Everywhere else the contents are
 * <em>unknown</em>, and this class is careful never to present an unknown box as empty: an empty box is a
 * fact, an unknown box is the absence of one, and treating them the same is how you throw away a full
 * shulker you "checked".
 */
public final class ShulkerInventory {

    /** How full a shulker box is. */
    public enum Status {
        EMPTY,
        PARTIAL,
        FULL,
        UNKNOWN
    }

    /** One stack inside a shulker. */
    public record Content(String itemKey, String name, int count) {
    }

    /**
     * A shulker box the client knows about.
     *
     * @param slotIndex     player-inventory index of the box
     * @param label         a user label, or the box's custom name, or a default
     * @param contents      known contents, empty when {@code contentsKnown} is false
     * @param contentsKnown whether the contents are actually known rather than merely absent
     * @param capacity      total stack slots in the box (27 for a vanilla shulker)
     */
    public record Box(int slotIndex, String label, List<Content> contents, boolean contentsKnown, int capacity) {
        public int usedSlots() {
            return contents.size();
        }

        public int freeSlots() {
            return Math.max(0, capacity - usedSlots());
        }

        public int totalItems() {
            int total = 0;
            for (Content content : contents) total += content.count();
            return total;
        }

        public Status status() {
            if (!contentsKnown) return Status.UNKNOWN;
            if (contents.isEmpty()) return Status.EMPTY;
            if (usedSlots() >= capacity) return Status.FULL;
            return Status.PARTIAL;
        }

        public boolean isEmptyKnown() {
            return status() == Status.EMPTY;
        }
    }

    /** A search hit: an item and how much of it is in a given box. */
    public record Hit(Box box, String itemKey, String name, int count) {
    }

    private ShulkerInventory() {
    }

    /** Normalises an item id so {@code minecraft:stone} and {@code stone} match. */
    public static String normalise(String itemId) {
        if (itemId == null) return "";
        String id = itemId.toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    /** True when the box's label or its custom name contains the query, case-insensitively. */
    public static boolean labelMatches(Box box, String query) {
        if (query == null || query.isBlank()) return false;
        return box.label().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT).trim());
    }

    /** Searches known box contents for an item. Unknown boxes are reported as unsearched, not as empty. */
    public static List<Hit> find(List<Box> boxes, String itemId) {
        String query = normalise(itemId);
        List<Hit> hits = new ArrayList<>();

        for (Box box : boxes) {
            if (!box.contentsKnown()) continue;
            for (Content content : box.contents()) {
                if (normalise(content.itemKey()).equals(query)) {
                    hits.add(new Hit(box, content.itemKey(), content.name(), content.count()));
                }
            }
        }

        return hits;
    }

    /** How many of an item are available across all known boxes. */
    public static int countOf(List<Box> boxes, String itemId) {
        int total = 0;
        for (Hit hit : find(boxes, itemId)) total += hit.count();
        return total;
    }

    /** Boxes whose contents are unknown and therefore must not be treated as empty. */
    public static List<Box> unknown(List<Box> boxes) {
        List<Box> result = new ArrayList<>();
        for (Box box : boxes) if (!box.contentsKnown()) result.add(box);
        return result;
    }

    /** The first known-empty box, or null. */
    public static Box firstEmpty(List<Box> boxes) {
        for (Box box : boxes) if (box.isEmptyKnown()) return box;
        return null;
    }

    /** The box with the most free space, preferring known contents, or null when every box is full. */
    public static Box withMostFreeSpace(List<Box> boxes) {
        Box best = null;
        int bestFree = 0;

        for (Box box : boxes) {
            if (!box.contentsKnown()) continue;
            int free = box.freeSlots();
            if (free > bestFree) {
                bestFree = free;
                best = box;
            }
        }

        return best;
    }

    /**
     * Picks the best box to fill with a category, preferring an existing box of the same label so storage
     * categories are not mixed. Returns null when nothing has room.
     */
    public static Box chooseForFilling(List<Box> boxes, String preferredLabel) {
        if (preferredLabel != null && !preferredLabel.isBlank()) {
            Box labelled = null;
            for (Box box : boxes) {
                if (!box.contentsKnown() || box.freeSlots() <= 0) continue;
                if (!box.label().equalsIgnoreCase(preferredLabel.trim())) continue;
                if (labelled == null || box.freeSlots() > labelled.freeSlots()) labelled = box;
            }
            if (labelled != null) return labelled;
        }

        return withMostFreeSpace(boxes);
    }

    /** Boxes sorted so the most-filled known boxes come first, useful for a "consolidate" view. */
    public static List<Box> byFill(List<Box> boxes) {
        List<Box> sorted = new ArrayList<>(boxes);
        sorted.sort(Comparator.comparingInt(Box::totalItems).reversed());
        return sorted;
    }
}
