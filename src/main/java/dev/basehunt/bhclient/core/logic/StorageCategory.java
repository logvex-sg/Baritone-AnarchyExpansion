package dev.basehunt.bhclient.core.logic;

import java.util.List;

/**
 * Storage buckets used by the sorting and routing systems.
 *
 * <p>This is deliberately separate from {@link ItemCategory}: {@code ItemCategory} answers "is this
 * precious enough to keep out of a bulk deposit", while this answers "which chest does this belong in".
 * A diamond sword is {@code TOOLS} for storage but {@code VALUABLE} for deposit safety, and conflating
 * the two would either drop gear into a dump chest or refuse to file your cobblestone.
 *
 * <p>The ordering of the enum is the default sort order, so building blocks sort before food.
 */
public enum StorageCategory {
    TOOLS("Tools"),
    COMBAT("Combat"),
    ARMOR("Armour"),
    FOOD("Food"),
    POTIONS("Potions"),
    ORES("Ores & minerals"),
    REDSTONE("Redstone"),
    NETHER("Nether"),
    END("End"),
    FARMING("Farming"),
    BUILDING("Building"),
    SHULKER("Shulker boxes"),
    RARE("Rare items"),
    MISC("Miscellaneous");

    private final String display;

    StorageCategory(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }

    /** Categories a bulk deposit must never sweep up unless the user selected them explicitly. */
    public boolean isPrecious() {
        return this == TOOLS || this == COMBAT || this == ARMOR || this == RARE || this == SHULKER;
    }

    /** A named, ordered set of categories — the shape of a sorting preset. */
    public record Preset(String name, List<StorageCategory> order) {
        public boolean contains(StorageCategory category) {
            return order.contains(category);
        }
    }

    /** The built-in presets. Users can also build their own from the GUI. */
    public static List<Preset> presets() {
        return List.of(
            new Preset("General storage", List.of(BUILDING, ORES, REDSTONE, NETHER, END, FARMING, MISC)),
            new Preset("Building materials", List.of(BUILDING, MISC)),
            new Preset("Ores & minerals", List.of(ORES, NETHER, END, RARE, MISC)),
            new Preset("Redstone", List.of(REDSTONE, MISC)),
            new Preset("Farming", List.of(FARMING, FOOD, MISC)),
            new Preset("Combat equipment", List.of(COMBAT, ARMOR, RARE, MISC)),
            new Preset("Tools", List.of(TOOLS, RARE, MISC)),
            new Preset("Food", List.of(FOOD, FARMING, MISC)),
            new Preset("Potions", List.of(POTIONS, MISC)),
            new Preset("Nether resources", List.of(NETHER, RARE, MISC)),
            new Preset("End resources", List.of(END, RARE, MISC)),
            new Preset("Rare items", List.of(RARE, ORES, MISC)),
            new Preset("Shulker storage", List.of(SHULKER, MISC))
        );
    }

    /** Looks up a preset by its display name, case-insensitively. */
    public static Preset preset(String name) {
        if (name == null) return null;
        for (Preset preset : presets()) {
            if (preset.name().equalsIgnoreCase(name.trim())) return preset;
        }
        return null;
    }

    /** The default order, used when no preset is selected. */
    public static List<StorageCategory> defaultOrder() {
        return List.of(values());
    }
}
