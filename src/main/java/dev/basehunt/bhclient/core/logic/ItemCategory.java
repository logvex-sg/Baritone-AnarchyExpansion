package dev.basehunt.bhclient.core.logic;

import java.util.Locale;

/**
 * Categories an inventory item can be filed under for ender chest and inventory organisation.
 *
 * <p>Classification is driven by item id names rather than by a hardcoded list of every block, so new items
 * from a Minecraft update fall into {@link #OTHER} instead of breaking, and the "valuable" set can be tuned
 * without a code change.
 */
public enum ItemCategory {
    ELYTRA,
    ARMOR,
    TOOLS,
    FIREWORKS,
    FOOD,
    TOTEMS,
    XP_SUPPLIES,
    BUILDING_BLOCKS,
    UTILITY,
    SPARE_EQUIPMENT,
    VALUABLE,
    OTHER;

    /**
     * Files an item by its registry path (e.g. {@code "diamond_chestplate"}).
     *
     * <p>Ordering matters: the more specific rules run before the general ones so a diamond chestplate is
     * armour rather than being swept up by the "valuable" rule.
     */
    public static ItemCategory classify(String itemId) {
        if (itemId == null || itemId.isBlank()) return OTHER;

        String id = itemId.toLowerCase(Locale.ROOT);

        if (id.contains("elytra")) return ELYTRA;

        if (id.endsWith("_helmet") || id.endsWith("_chestplate") || id.endsWith("_leggings")
            || id.endsWith("_boots") || id.equals("turtle_helmet") || id.endsWith("_horse_armor")
            || id.equals("shield") || id.equals("wolf_armor")) {
            return ARMOR;
        }

        if (id.equals("firework_rocket")) return FIREWORKS;
        if (id.equals("totem_of_undying")) return TOTEMS;
        if (id.contains("experience_bottle") || id.equals("enchanted_book") || id.equals("experience_bottle")) return XP_SUPPLIES;

        if (id.endsWith("_sword") || id.endsWith("_pickaxe") || id.endsWith("_axe") || id.endsWith("_shovel")
            || id.endsWith("_hoe") || id.equals("bow") || id.equals("crossbow") || id.equals("trident")
            || id.equals("fishing_rod") || id.equals("shears") || id.equals("flint_and_steel")
            || id.equals("elytra") || id.equals("mace")) {
            return TOOLS;
        }

        if (id.equals("cooked_beef") || id.equals("cooked_porkchop") || id.equals("cooked_chicken")
            || id.equals("bread") || id.equals("golden_carrot") || id.equals("golden_apple")
            || id.equals("enchanted_golden_apple") || id.equals("cooked_mutton") || id.equals("cooked_salmon")
            || id.equals("cooked_cod") || id.equals("baked_potato") || id.equals("apple")
            || id.endsWith("_stew") || id.equals("honey_bottle")) {
            return FOOD;
        }

        if (isBuildingBlock(id)) return BUILDING_BLOCKS;

        if (id.equals("shulker_box") || id.endsWith("_shulker_box") || id.equals("ender_chest")
            || id.equals("ender_pearl") || id.equals("ender_eye") || id.equals("chest") || id.equals("barrel")
            || id.equals("water_bucket") || id.equals("lava_bucket") || id.equals("bucket")
            || id.equals("crafting_table") || id.equals("anvil") || id.equals("grindstone")
            || id.equals("enchanting_table") || id.equals("anvil") || id.equals("respawn_anchor")
            || id.equals("obsidian") || id.equals("crying_obsidian") || id.equals("end_crystal")
            || id.equals("tnt") || id.equals("bed") || id.endsWith("_bed")) {
            return UTILITY;
        }

        if (isValuable(id)) return VALUABLE;

        return OTHER;
    }

    /** True for a plain building material (stone, planks, concrete, glass and similar). */
    private static boolean isBuildingBlock(String id) {
        return id.endsWith("_planks") || id.endsWith("_log") || id.endsWith("_wood")
            || id.endsWith("_concrete") || id.endsWith("_concrete_powder") || id.endsWith("_terracotta")
            || id.endsWith("_wool") || id.endsWith("_carpet") || id.endsWith("_glass") || id.endsWith("_glass_pane")
            || id.endsWith("_bricks") || id.endsWith("_stairs") || id.endsWith("_slab") || id.endsWith("_wall")
            || id.endsWith("_fence") || id.endsWith("_fence_gate") || id.endsWith("_door") || id.endsWith("_trapdoor")
            || id.equals("stone") || id.equals("cobblestone") || id.equals("dirt") || id.equals("grass_block")
            || id.equals("sand") || id.equals("gravel") || id.equals("netherrack") || id.equals("end_stone")
            || id.equals("deepslate") || id.equals("tuff") || id.equals("calcite") || id.equals("diorite")
            || id.equals("granite") || id.equals("andesite") || id.equals("scaffolding") || id.equals("torch")
            || id.equals("glowstone") || id.equals("sea_lantern") || id.equals("shroomlight");
    }

    /** True for items a base hunter would not want to lose (ores, ingots, gems, rare drops). */
    private static boolean isValuable(String id) {
        return id.endsWith("_ingot") || id.endsWith("_nugget") || id.endsWith("_ore")
            || id.equals("diamond") || id.equals("emerald") || id.equals("netherite_scrap")
            || id.equals("ancient_debris") || id.equals("nether_star") || id.equals("dragon_egg")
            || id.equals("echo_shard") || id.equals("heart_of_the_sea") || id.equals("nautilus_shell")
            || id.equals("wither_skeleton_skull") || id.endsWith("_skull") || id.endsWith("_head")
            || id.equals("gunpowder") || id.equals("blaze_rod") || id.equals("ghast_tear")
            || id.equals("shulker_shell") || id.equals("beacon") || id.equals("conduit")
            || id.equals("trident") || id.equals("totem_of_undying") || id.equals("enchanted_golden_apple")
            || id.equals("netherite_ingot") || id.equals("netherite_block") || id.equals("diamond_block")
            || id.equals("emerald_block") || id.equals("gold_block") || id.equals("iron_block");
    }

    /** True when losing this item is serious enough that it must never be auto-dropped. */
    public boolean isPrecious() {
        return this == ELYTRA || this == ARMOR || this == TOOLS || this == TOTEMS
            || this == VALUABLE || this == SPARE_EQUIPMENT || this == XP_SUPPLIES;
    }
}
