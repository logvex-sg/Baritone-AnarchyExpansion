package dev.basehunt.bhclient.core.sixbt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A kit loadout: the items a 6b6t kit bot carries so it can hand them to a requester.
 *
 * <p>A loadout is a named list of {@link ItemSpec} - an item id and how many of it the kit contains. The
 * bot's job is to hold exactly this set, drop it when a request arrives, and then kill itself and be
 * restocked. The loadout is what the kit-maker menu edits, so it is a plain value with no Minecraft types.
 *
 * <p>Item ids are stored as strings (for example {@code minecraft:diamond_sword}) rather than {@code Item}
 * references, so the loadout can be parsed, compared and persisted without a client - and so an unknown item
 * from a hand-edited config is reported rather than crashing the bot.
 */
public record KitLoadout(String name, List<ItemSpec> items) {

    /** One line of a loadout: an item id and how many. */
    public record ItemSpec(String itemId, int count) {
        public ItemSpec {
            if (itemId == null || itemId.isBlank()) itemId = "";
            itemId = itemId.trim().toLowerCase(Locale.ROOT);
            if (!itemId.isEmpty() && !itemId.contains(":")) itemId = "minecraft:" + itemId;
            count = Math.max(0, count);
        }

        public boolean isValid() {
            return !itemId.isEmpty() && count > 0;
        }

        /** A {@code id xN} line for the menu and the terminal. */
        public String describe() {
            return count > 1 ? itemId + " x" + count : itemId;
        }
    }

    public KitLoadout {
        if (name == null || name.isBlank()) name = "kit";
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static KitLoadout of(String name, List<ItemSpec> items) {
        return new KitLoadout(name, items);
    }

    /** An empty loadout with just a name. */
    public static KitLoadout empty(String name) {
        return new KitLoadout(name, List.of());
    }

    /** The total number of items the loadout contains. */
    public int totalItems() {
        int total = 0;
        for (ItemSpec spec : items) total += spec.count();
        return total;
    }

    public int distinctItems() {
        return items.size();
    }

    public boolean isEmpty() {
        return totalItems() == 0;
    }

    /** Adds an item, merging with an existing line for the same item. */
    public KitLoadout with(ItemSpec spec) {
        if (spec == null || !spec.isValid()) return this;
        List<ItemSpec> next = new ArrayList<>(items);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).itemId().equals(spec.itemId())) {
                next.set(i, new ItemSpec(spec.itemId(), next.get(i).count() + spec.count()));
                return new KitLoadout(name, next);
            }
        }
        next.add(spec);
        return new KitLoadout(name, next);
    }

    /** Removes every line for an item id. */
    public KitLoadout without(String itemId) {
        if (itemId == null) return this;
        String needle = new ItemSpec(itemId, 1).itemId();
        List<ItemSpec> next = new ArrayList<>();
        for (ItemSpec spec : items) if (!spec.itemId().equals(needle)) next.add(spec);
        return new KitLoadout(name, next);
    }

    /** A one-line summary, e.g. {@code pvp: 6 items, 3 distinct}. */
    public String summary() {
        return "%s: %d item(s), %d kind(s)".formatted(name, totalItems(), distinctItems());
    }

    /** The full listing for the menu and the terminal. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add(summary());
        if (items.isEmpty()) {
            lines.add("  (empty - add items with kit add <kit> <item> [count])");
            return lines;
        }
        for (ItemSpec spec : items) lines.add("  " + spec.describe());
        return lines;
    }

    /** Parses a stored {@code id xN} or {@code id:N} line into a spec, or null when it is malformed. */
    public static ItemSpec parseSpec(String line) {
        if (line == null || line.isBlank()) return null;
        String trimmed = line.trim();

        // Prefer whitespace separation so an item id containing an 'x' (e.g. minecraft:...) is not split.
        String id;
        int count = 1;

        String[] parts = trimmed.split("\\s+");
        if (parts.length >= 2) {
            id = parts[0].trim();
            count = parseCount(parts[1].trim(), 1);
        } else {
            int colon = trimmed.lastIndexOf(':');
            String tail = colon >= 0 ? trimmed.substring(colon + 1) : "";
            if (!tail.isEmpty() && tail.chars().allMatch(Character::isDigit)) {
                id = trimmed.substring(0, colon);
                count = parseCount(tail, 1);
            } else {
                id = trimmed;
            }
        }

        if (id.isEmpty()) return null;
        ItemSpec spec = new ItemSpec(id, count);
        return spec.isValid() ? spec : null;
    }

    private static int parseCount(String token, int fallback) {
        String cleaned = token.startsWith("x") || token.startsWith("X") ? token.substring(1) : token;
        try {
            return Integer.parseInt(cleaned.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
