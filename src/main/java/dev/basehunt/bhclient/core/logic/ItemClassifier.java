package dev.basehunt.bhclient.core.logic;

import java.util.Locale;

/**
 * Files an item into a {@link StorageCategory} from its registry path.
 *
 * <p>Rule-based rather than a table of every item, so an item added by a Minecraft update or a mod
 * lands in {@link StorageCategory#MISC} instead of breaking the sorter, and a rule can be tuned in one
 * place. The ordering of the checks is what makes it correct: the specific rules (a diamond sword is a
 * tool) run before the general ones (a diamond is rare).
 *
 * <p>This is pure string logic with no Minecraft types, so the classification table is covered by the
 * headless suite.
 */
public final class ItemClassifier {

    private ItemClassifier() {
    }

    /**
     * Classifies an item by its registry path (e.g. {@code "diamond_sword"}), or its full id
     * ({@code "minecraft:diamond_sword"} — the namespace is stripped).
     */
    public static StorageCategory classify(String itemId) {
        if (itemId == null || itemId.isBlank()) return StorageCategory.MISC;

        String id = itemId.toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        if (colon >= 0) id = id.substring(colon + 1);

        if (id.isEmpty()) return StorageCategory.MISC;

        // Shulker boxes are their own category: a shulker is storage, not building material.
        if (id.equals("shulker_box") || id.endsWith("_shulker_box")) return StorageCategory.SHULKER;

        // Armour before tools, so a golden helmet is armour and not caught by the "golden" rule.
        if (id.endsWith("_helmet") || id.endsWith("_chestplate") || id.endsWith("_leggings")
            || id.endsWith("_boots") || id.equals("turtle_helmet") || id.equals("wolf_armor")
            || id.endsWith("_horse_armor")) {
            return StorageCategory.ARMOR;
        }

        if (id.equals("shield")) return StorageCategory.COMBAT;

        if (id.endsWith("_sword") || id.equals("bow") || id.equals("crossbow") || id.equals("trident")
            || id.equals("mace") || id.equals("arrow") || id.endsWith("_arrow")
            || id.equals("spectral_arrow") || id.equals("tipped_arrow")) {
            return StorageCategory.COMBAT;
        }

        if (id.endsWith("_pickaxe") || id.endsWith("_axe") || id.endsWith("_shovel") || id.endsWith("_hoe")
            || id.equals("shears") || id.equals("fishing_rod") || id.equals("flint_and_steel")
            || id.equals("elytra") || id.equals("brush") || id.equals("carrot_on_a_stick")
            || id.equals("warped_fungus_on_a_stick") || id.equals("lead")) {
            return StorageCategory.TOOLS;
        }

        if (isPotion(id)) return StorageCategory.POTIONS;

        if (id.equals("cooked_beef") || id.equals("cooked_porkchop") || id.equals("cooked_chicken")
            || id.equals("cooked_mutton") || id.equals("cooked_rabbit") || id.equals("cooked_salmon")
            || id.equals("cooked_cod") || id.equals("bread") || id.equals("baked_potato")
            || id.equals("golden_carrot") || id.equals("golden_apple")
            || id.equals("enchanted_golden_apple") || id.equals("apple") || id.equals("honey_bottle")
            || id.endsWith("_stew") || id.endsWith("_soup")) {
            return StorageCategory.FOOD;
        }

        if (isFarming(id)) return StorageCategory.FARMING;

        if (isRedstone(id)) return StorageCategory.REDSTONE;

        if (isRare(id)) return StorageCategory.RARE;

        if (isNether(id)) return StorageCategory.NETHER;

        if (isEnd(id)) return StorageCategory.END;

        if (isOre(id)) return StorageCategory.ORES;

        if (isBuilding(id)) return StorageCategory.BUILDING;

        return StorageCategory.MISC;
    }

    private static boolean isPotion(String id) {
        return id.equals("potion") || id.equals("splash_potion") || id.equals("lingering_potion")
            || id.equals("potion") || id.endsWith("_potion") || id.equals("experience_bottle")
            || id.equals("dragon_breath") || id.equals("glistering_melon_slice")
            || id.equals("fermented_spider_eye") || id.equals("blaze_powder")
            || id.equals("brewing_stand") || id.equals("cauldron") || id.equals("nether_wart")
            || id.equals("magma_cream") || id.equals("ghast_tear") || id.equals("rabbit_foot")
            || id.equals("sugar") || id.equals("glowstone_dust") || id.equals("redstone");
    }

    private static boolean isFarming(String id) {
        return id.endsWith("_seeds") || id.equals("wheat") || id.equals("carrot") || id.equals("potato")
            || id.equals("beetroot") || id.equals("sugar_cane") || id.equals("bamboo")
            || id.equals("kelp") || id.equals("cactus") || id.equals("melon_slice") || id.equals("melon")
            || id.equals("pumpkin") || id.equals("sweet_berries") || id.equals("glow_berries")
            || id.equals("cocoa_beans") || id.equals("bone_meal") || id.equals("hay_block")
            || id.equals("egg") || id.equals("leather") || id.equals("feather") || id.equals("honeycomb")
            || id.equals("honey_bottle") || id.endsWith("_wool") || id.endsWith("_carpet")
            || id.equals("milk_bucket") || id.equals("composter") || id.equals("beehive")
            || id.equals("bee_nest") || id.endsWith("_sapling") || id.equals("moss_block");
    }

    private static boolean isRedstone(String id) {
        return id.equals("redstone") || id.equals("redstone_torch") || id.equals("redstone_block")
            || id.equals("repeater") || id.equals("comparator") || id.equals("observer")
            || id.equals("piston") || id.equals("sticky_piston") || id.equals("dispenser")
            || id.equals("dropper") || id.equals("hopper") || id.equals("lever")
            || id.equals("tripwire_hook") || id.equals("target") || id.equals("daylight_detector")
            || id.equals("note_block") || id.equals("jukebox") || id.equals("lightning_rod")
            || id.equals("sculk_sensor") || id.equals("calibrated_sculk_sensor")
            || id.equals("redstone_lamp") || id.equals("trapped_chest") || id.equals("crafter")
            || id.equals("tnt") || id.equals("tnt_minecart") || id.endsWith("_rail")
            || id.equals("minecart") || id.endsWith("_pressure_plate") || id.endsWith("_button")
            || id.equals("redstone_ore") || id.equals("deepslate_redstone_ore")
            || id.equals("slime_block") || id.equals("honey_block") || id.equals("string");
    }

    private static boolean isNether(String id) {
        return id.contains("nether") || id.equals("blaze_rod") || id.equals("blaze_powder")
            || id.equals("magma_cream") || id.equals("glowstone") || id.equals("glowstone_dust")
            || id.equals("quartz") || id.equals("soul_sand") || id.equals("soul_soil")
            || id.equals("crimson_fungus") || id.equals("warped_fungus") || id.equals("shroomlight")
            || id.equals("crying_obsidian") || id.equals("obsidian") || id.equals("basalt")
            || id.equals("blackstone") || id.equals("gilded_blackstone")
            || id.equals("ancient_debris") || id.equals("netherite_scrap")
            || id.equals("netherite_ingot") || id.equals("netherite_block")
            || id.equals("wither_skeleton_skull") || id.equals("respawn_anchor")
            || id.equals("lodestone") || id.equals("weeping_vines") || id.equals("twisting_vines");
    }

    private static boolean isEnd(String id) {
        return id.contains("end_") || id.equals("ender_pearl") || id.equals("ender_eye")
            || id.equals("ender_chest") || id.equals("chorus_fruit") || id.equals("popped_chorus_fruit")
            || id.equals("shulker_shell") || id.equals("dragon_egg") || id.equals("dragon_breath")
            || id.equals("end_crystal") || id.equals("end_stone") || id.equals("purpur_block")
            || id.equals("purpur_pillar") || id.equals("elytra") || id.equals("end_rod")
            || id.equals("obsidian") || id.equals("ender_dragon_spawn_egg");
    }

    private static boolean isOre(String id) {
        return id.endsWith("_ore") || id.endsWith("_ingot") || id.endsWith("_nugget")
            || id.equals("diamond") || id.equals("emerald") || id.equals("coal") || id.equals("charcoal")
            || id.equals("iron_block") || id.equals("gold_block") || id.equals("diamond_block")
            || id.equals("emerald_block") || id.equals("copper_block") || id.equals("lapis_block")
            || id.equals("coal_block") || id.equals("raw_iron") || id.equals("raw_gold")
            || id.equals("raw_copper") || id.equals("amethyst_shard") || id.equals("lapis_lazuli")
            || id.equals("quartz") || id.equals("copper_ingot") || id.equals("flint")
            || id.equals("gunpowder");
    }

    private static boolean isRare(String id) {
        return id.equals("nether_star") || id.equals("beacon") || id.equals("conduit")
            || id.equals("totem_of_undying") || id.equals("enchanted_golden_apple")
            || id.equals("trident") || id.equals("nautilus_shell") || id.equals("heart_of_the_sea")
            || id.equals("echo_shard") || id.equals("disc_fragment_5") || id.equals("recovery_compass")
            || id.equals("enchanted_book") || id.equals("experience_bottle") || id.endsWith("_skull")
            || id.endsWith("_head") || id.equals("bundle") || id.equals("spyglass")
            || id.equals("music_disc_13") || id.startsWith("music_disc_") || id.equals("saddle")
            || id.equals("name_tag") || id.equals("firework_rocket") || id.equals("firework_star");
    }

    private static boolean isBuilding(String id) {
        return id.endsWith("_planks") || id.endsWith("_log") || id.endsWith("_wood")
            || id.endsWith("_stem") || id.endsWith("_hyphae") || id.endsWith("_concrete")
            || id.endsWith("_concrete_powder") || id.endsWith("_terracotta") || id.endsWith("_glazed_terracotta")
            || id.endsWith("_bricks") || id.endsWith("_stairs") || id.endsWith("_slab")
            || id.endsWith("_wall") || id.endsWith("_fence") || id.endsWith("_fence_gate")
            || id.endsWith("_door") || id.endsWith("_trapdoor") || id.endsWith("_glass")
            || id.endsWith("_glass_pane") || id.endsWith("_stained_glass") || id.equals("stone")
            || id.equals("cobblestone") || id.equals("mossy_cobblestone") || id.equals("dirt")
            || id.equals("grass_block") || id.equals("sand") || id.equals("red_sand") || id.equals("gravel")
            || id.equals("clay") || id.equals("deepslate") || id.equals("cobbled_deepslate")
            || id.equals("tuff") || id.equals("calcite") || id.equals("diorite") || id.equals("granite")
            || id.equals("andesite") || id.equals("scaffolding") || id.equals("torch")
            || id.equals("soul_torch") || id.equals("lantern") || id.equals("sea_lantern")
            || id.equals("prismarine") || id.equals("prismarine_bricks") || id.equals("dark_prismarine")
            || id.equals("snow_block") || id.equals("ice") || id.equals("packed_ice") || id.equals("blue_ice")
            || id.equals("mud") || id.equals("mud_bricks") || id.equals("brick") || id.equals("nether_bricks")
            || id.equals("glass") || id.equals("bedrock") || id.equals("barrier")
            || id.equals("coarse_dirt") || id.equals("rooted_dirt") || id.equals("podzol")
            || id.equals("mycelium") || id.equals("soul_sand") || id.equals("sandstone")
            || id.endsWith("_sandstone") || id.equals("cobweb") || id.equals("ladder");
    }

    /** True when the item is a container that can itself hold items. */
    public static boolean isContainer(String itemId) {
        if (itemId == null) return false;
        String id = itemId.toLowerCase(Locale.ROOT);
        int colon = id.indexOf(':');
        if (colon >= 0) id = id.substring(colon + 1);
        return id.equals("chest") || id.equals("trapped_chest") || id.equals("barrel")
            || id.equals("ender_chest") || id.equals("shulker_box") || id.endsWith("_shulker_box")
            || id.equals("hopper") || id.equals("dispenser") || id.equals("dropper")
            || id.equals("furnace") || id.equals("blast_furnace") || id.equals("smoker")
            || id.equals("brewing_stand") || id.equals("crafter");
    }
}
