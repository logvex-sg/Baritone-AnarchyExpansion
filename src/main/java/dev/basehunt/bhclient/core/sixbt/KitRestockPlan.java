package dev.basehunt.bhclient.core.sixbt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Works out what a 6b6t kit bot is short of, so it can be restocked before it serves a request.
 *
 * <p>A delivery bot that teleports to a player and finds it has three of the sixteen golden apples the kit
 * promises has wasted everyone's time. Before accepting a request the bot can compare what it is carrying
 * against the loadout and either restock from storage or decline. This is that comparison: it takes the counts
 * of what the bot holds and the loadout it should hold, and reports the shortfall and any surplus.
 *
 * <p>Pure arithmetic over item ids, so the "short", "exact" and "surplus" cases are covered headlessly.
 */
public final class KitRestockPlan {

    /** What to do about one item. */
    public record Delta(String itemId, int required, int held, int missing, int surplus) {
        public boolean isShort() {
            return missing > 0;
        }

        public boolean isSurplus() {
            return surplus > 0;
        }

        public String describe() {
            if (isShort()) return "%s: need %d more (have %d/%d)".formatted(itemId, missing, held, required);
            if (isSurplus()) return "%s: %d over (%d/%d)".formatted(itemId, surplus, held, required);
            return "%s: ok (%d)".formatted(itemId, held);
        }
    }

    /** The result of comparing a loadout with an inventory. */
    public record Result(List<Delta> deltas) {
        public List<Delta> missing() {
            List<Delta> list = new ArrayList<>();
            for (Delta delta : deltas) if (delta.isShort()) list.add(delta);
            return list;
        }

        public List<Delta> surplus() {
            List<Delta> list = new ArrayList<>();
            for (Delta delta : deltas) if (delta.isSurplus()) list.add(delta);
            return list;
        }

        public boolean isReady() {
            return missing().isEmpty();
        }

        public int missingItems() {
            int total = 0;
            for (Delta delta : missing()) total += delta.missing();
            return total;
        }

        public List<String> describe() {
            List<String> lines = new ArrayList<>();
            if (isReady()) {
                lines.add("stocked for the loadout");
            } else {
                lines.add("missing %d item(s):".formatted(missingItems()));
                for (Delta delta : missing()) lines.add("  " + delta.describe());
            }
            return lines;
        }
    }

    private KitRestockPlan() {
    }

    /**
     * Compares a loadout against the counts the bot currently holds.
     *
     * @param loadout  the kit it should be carrying
     * @param heldById the number of each item id currently held (ids normalised to {@code namespace:path})
     * @return the per-item deltas, in loadout order
     */
    public static Result compare(KitLoadout loadout, Map<String, Integer> heldById) {
        Map<String, Integer> held = normalise(heldById);
        List<Delta> deltas = new ArrayList<>();
        if (loadout == null) return new Result(deltas);

        for (KitLoadout.ItemSpec spec : loadout.items()) {
            int have = held.getOrDefault(spec.itemId(), 0);
            int missing = Math.max(0, spec.count() - have);
            int surplus = Math.max(0, have - spec.count());
            deltas.add(new Delta(spec.itemId(), spec.count(), have, missing, surplus));
        }
        return new Result(deltas);
    }

    /** Builds an id -> count map from a list of {@code id xN} spec lines. */
    public static Map<String, Integer> counts(List<KitLoadout.ItemSpec> specs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (specs == null) return map;
        for (KitLoadout.ItemSpec spec : specs) {
            if (spec == null || !spec.isValid()) continue;
            map.merge(spec.itemId(), spec.count(), Integer::sum);
        }
        return map;
    }

    private static Map<String, Integer> normalise(Map<String, Integer> raw) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (raw == null) return map;
        for (Map.Entry<String, Integer> entry : raw.entrySet()) {
            if (entry.getKey() == null) continue;
            String id = entry.getKey().trim().toLowerCase(Locale.ROOT);
            if (!id.contains(":")) id = "minecraft:" + id;
            map.merge(id, entry.getValue() == null ? 0 : entry.getValue(), Integer::sum);
        }
        return map;
    }
}
