package dev.basehunt.bhclient.core.sixbt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The kit loadouts a 6b6t kit bot can deliver, keyed by kit name.
 *
 * <p>A single bot may be stocked for more than one kit - a {@code pvp} loadout and a {@code travel} loadout -
 * but it can only carry one at a time. This is the catalogue the menu edits and the delivery bot consults:
 * "which kits can I deliver, and what is in each?". It is a pure container so it can be persisted and tested
 * without a client.
 */
public final class KitLoadoutRegistry {
    private final Map<String, KitLoadout> loadouts = new LinkedHashMap<>();

    /** Adds or replaces a loadout. */
    public void put(KitLoadout loadout) {
        if (loadout == null || loadout.name().isBlank()) return;
        loadouts.put(loadout.name().toLowerCase(Locale.ROOT), loadout);
    }

    public KitLoadout find(String name) {
        if (name == null) return null;
        return loadouts.get(name.trim().toLowerCase(Locale.ROOT));
    }

    /** The loadout for a name, or an empty one so a delivery still has a value to work with. */
    public KitLoadout findOrDefault(String name) {
        KitLoadout loadout = find(name);
        return loadout == null ? KitLoadout.empty(name == null ? "kit" : name) : loadout;
    }

    public boolean contains(String name) {
        return find(name) != null;
    }

    public List<KitLoadout> all() {
        return new ArrayList<>(loadouts.values());
    }

    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (KitLoadout loadout : loadouts.values()) names.add(loadout.name());
        return names;
    }

    public int size() {
        return loadouts.size();
    }

    public boolean isEmpty() {
        return loadouts.isEmpty();
    }

    public void clear() {
        loadouts.clear();
    }

    public void remove(String name) {
        if (name != null) loadouts.remove(name.trim().toLowerCase(Locale.ROOT));
    }

    /** The listing for the menu and the terminal. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        if (loadouts.isEmpty()) {
            lines.add("no kit loadouts configured");
            return lines;
        }
        for (KitLoadout loadout : loadouts.values()) lines.add(loadout.summary());
        return lines;
    }

    /** The default loadouts a fresh install starts with, matching what 6b6t players usually ask for. */
    public static KitLoadoutRegistry defaults() {
        KitLoadoutRegistry registry = new KitLoadoutRegistry();
        registry.put(KitLoadout.of("pvp", List.of(
            new KitLoadout.ItemSpec("netherite_helmet", 1),
            new KitLoadout.ItemSpec("netherite_chestplate", 1),
            new KitLoadout.ItemSpec("netherite_leggings", 1),
            new KitLoadout.ItemSpec("netherite_boots", 1),
            new KitLoadout.ItemSpec("netherite_sword", 1),
            new KitLoadout.ItemSpec("golden_apple", 16),
            new KitLoadout.ItemSpec("totem_of_undying", 1)
        )));
        registry.put(KitLoadout.of("travel", List.of(
            new KitLoadout.ItemSpec("elytra", 1),
            new KitLoadout.ItemSpec("firework_rocket", 64),
            new KitLoadout.ItemSpec("cooked_beef", 32),
            new KitLoadout.ItemSpec("totem_of_undying", 1)
        )));
        registry.put(KitLoadout.of("starter", List.of(
            new KitLoadout.ItemSpec("stone_sword", 1),
            new KitLoadout.ItemSpec("iron_pickaxe", 1),
            new KitLoadout.ItemSpec("bread", 16),
            new KitLoadout.ItemSpec("oak_log", 64)
        )));
        return registry;
    }
}
