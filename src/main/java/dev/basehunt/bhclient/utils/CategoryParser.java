package dev.basehunt.bhclient.utils;

import dev.basehunt.bhclient.core.logic.StorageCategory;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Converts the category names a user types in a Meteor string-list setting into the enum the planners use.
 *
 * <p>Users type "building", "ores", "nether" - not the enum constants - so matching is case-insensitive and
 * accepts the display names too. An unrecognised entry is ignored rather than failing the whole selection, so
 * one typo cannot silently disable a deposit.
 */
public final class CategoryParser {
    private CategoryParser() {
    }

    /** Parses a list of category names, ignoring anything unrecognised. */
    public static Set<StorageCategory> parse(List<String> names) {
        Set<StorageCategory> categories = EnumSet.noneOf(StorageCategory.class);
        if (names == null) return categories;

        for (String name : names) {
            StorageCategory category = one(name);
            if (category != null) categories.add(category);
        }

        return categories;
    }

    /** Parses a single category name, or null when it is not recognised. */
    public static StorageCategory one(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return null;

        for (StorageCategory category : StorageCategory.values()) {
            if (category.name().equalsIgnoreCase(trimmed)) return category;
            if (category.display().equalsIgnoreCase(trimmed)) return category;
        }

        return switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "tools", "tool" -> StorageCategory.TOOLS;
            case "combat", "weapons", "weapon" -> StorageCategory.COMBAT;
            case "armour", "armor" -> StorageCategory.ARMOR;
            case "food", "edible" -> StorageCategory.FOOD;
            case "potion", "potions", "brewing" -> StorageCategory.POTIONS;
            case "ore", "ores", "minerals" -> StorageCategory.ORES;
            case "redstone", "redstone_components" -> StorageCategory.REDSTONE;
            case "nether" -> StorageCategory.NETHER;
            case "end", "ender" -> StorageCategory.END;
            case "farming", "farm", "plants" -> StorageCategory.FARMING;
            case "building", "build", "blocks" -> StorageCategory.BUILDING;
            case "shulker", "shulkers" -> StorageCategory.SHULKER;
            case "rare", "valuable", "valuables" -> StorageCategory.RARE;
            case "misc", "other", "junk" -> StorageCategory.MISC;
            default -> null;
        };
    }

    /** The category names a user can type, for command autocomplete and the GUI. */
    public static List<String> suggestions() {
        return java.util.Arrays.stream(StorageCategory.values())
            .map(category -> category.name().toLowerCase(Locale.ROOT))
            .toList();
    }
}
