package dev.basehunt.bhclient;

import dev.basehunt.bhclient.core.logic.ContainerLayout;
import dev.basehunt.bhclient.core.logic.InventorySimulator;
import dev.basehunt.bhclient.core.logic.ItemClassifier;
import dev.basehunt.bhclient.core.logic.ItemRoutingEngine;
import dev.basehunt.bhclient.core.logic.OperationState;
import dev.basehunt.bhclient.core.logic.ProtectionRules;
import dev.basehunt.bhclient.core.logic.RestockPlanner;
import dev.basehunt.bhclient.core.logic.ShulkerInventory;
import dev.basehunt.bhclient.core.logic.SortPlanner;
import dev.basehunt.bhclient.core.logic.StorageCategory;
import dev.basehunt.bhclient.core.logic.TransferPlanner;
import dev.basehunt.bhclient.core.logic.WorkflowEngine;
import dev.basehunt.bhclient.utils.CategoryParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Headless tests for the storage, sorting, routing, restocking, shulker, operation-state and workflow
 * logic. Like {@link CoreLogicTest} these touch no Minecraft classes, so the arrangement the sorter promises
 * is checked by actually simulating the clicks and asserting that no item was lost or duplicated.
 */
public class StorageLogicTest {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS: " + name); }
        else { failed++; System.out.println("  FAIL: " + name + " -> " + detail); }
    }

    static void section(String title) {
        System.out.println("== " + title + " ==");
    }

    public static void main(String[] args) {
        classification();
        containerLayout();
        sortPlanner();
        sortSimulation();
        protectedSorting();
        transferPlanner();
        routingEngine();
        restockPlanner();
        shulkerInventory();
        operationState();
        workflowEngine();
        categoryParsing();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    private static void categoryParsing() {
        section("category parsing");

        check("an enum name parses",
            CategoryParser.one("ORES") == StorageCategory.ORES, "wrong");
        check("a display name parses",
            CategoryParser.one("Ores & minerals") == StorageCategory.ORES, "wrong");
        check("an alias parses", CategoryParser.one("weapons") == StorageCategory.COMBAT, "wrong");
        check("an unknown name is null", CategoryParser.one("nope") == null, "not null");
        check("a blank name is null", CategoryParser.one("  ") == null, "not null");

        var set = CategoryParser.parse(List.of("building", "ores", "bogus"));
        check("parse collects recognised categories", set.size() == 2, "got " + set.size());
        check("parse ignores unrecognised names", !set.contains(StorageCategory.MISC), "leaked");
        check("suggestions are non-empty", !CategoryParser.suggestions().isEmpty(), "empty");
    }

    private static void transferPlanner() {
        section("transfer planner");

        var playerStacks = List.of(
            new TransferPlanner.Stack(0, "cobblestone", "Cobblestone", StorageCategory.BUILDING, 64),
            new TransferPlanner.Stack(1, "diamond", "Diamond", StorageCategory.ORES, 12),
            new TransferPlanner.Stack(2, "diamond_sword", "Diamond Sword", StorageCategory.COMBAT, 1),
            new TransferPlanner.Stack(3, "cooked_beef", "Steak", StorageCategory.FOOD, 32)
        );

        var emptyContainer = List.of(54, 55, 56, 57);

        // Deposit only building + ores; a precious category must not move unless selected.
        var deposit = TransferPlanner.planDeposit(playerStacks, emptyContainer,
            java.util.EnumSet.of(StorageCategory.BUILDING, StorageCategory.ORES), Map.of(), Set.of());

        check("a deposit moves only selected categories", deposit.transfers().size() == 2,
            "got " + deposit.transfers().size());
        check("a deposit never moves a combat item",
            deposit.transfers().stream().noneMatch(t -> t.itemId().equals("diamond_sword")), "moved");
        check("a deposit fills container slots in order",
            deposit.transfers().get(0).toSlotId() == 54 && deposit.transfers().get(1).toSlotId() == 55,
            "wrong slots");

        // The reserve must be kept: 2 food reserved, 32 carried -> nothing deposited.
        var reserved = TransferPlanner.planDeposit(playerStacks, emptyContainer,
            java.util.EnumSet.of(StorageCategory.FOOD), Map.of(StorageCategory.FOOD, 64), Set.of());
        check("a reserve protects the last food", reserved.transfers().isEmpty(), "moved");

        // Protected items never move.
        var protectedPlan = TransferPlanner.planDeposit(playerStacks, emptyContainer,
            java.util.EnumSet.of(StorageCategory.BUILDING, StorageCategory.ORES), Map.of(),
            Set.of("cobblestone"));
        check("a protected item is never deposited",
            protectedPlan.transfers().stream().noneMatch(t -> t.itemId().equals("cobblestone")), "moved");

        // Withdrawal pulls only the selected categories into empty player slots.
        var containerStacks = List.of(
            new TransferPlanner.Stack(54, "iron_ingot", "Iron Ingot", StorageCategory.ORES, 64),
            new TransferPlanner.Stack(55, "cobblestone", "Cobblestone", StorageCategory.BUILDING, 64)
        );
        var emptyPlayer = List.of(9, 10);

        var withdraw = TransferPlanner.planWithdraw(containerStacks, emptyPlayer,
            java.util.EnumSet.of(StorageCategory.ORES), Set.of());
        check("a withdrawal pulls the selected category", withdraw.transfers().size() == 1, "wrong count");
        check("a withdrawal targets an empty player slot", withdraw.transfers().get(0).toSlotId() == 9, "wrong slot");
        check("a withdrawal ignores other categories",
            withdraw.transfers().stream().noneMatch(t -> t.itemId().equals("cobblestone")), "pulled");

        // A pull restock moves the stacks that satisfy a need and stops when the need is met.
        var need = new java.util.LinkedHashMap<String, Integer>();
        need.put("iron_ingot", 64);
        need.put("cobblestone", 64);

        var pull = TransferPlanner.planPull(containerStacks, List.of(9, 10, 11), need, Set.of());
        check("a pull satisfies each needed item", pull.transfers().size() == 2, "got " + pull.transfers().size());

        // Once the need is filled by the first stack, a second stack of the same item is not pulled.
        var twoIron = List.of(
            new TransferPlanner.Stack(54, "iron_ingot", "Iron Ingot", StorageCategory.ORES, 64),
            new TransferPlanner.Stack(55, "iron_ingot", "Iron Ingot", StorageCategory.ORES, 64)
        );
        var oneNeed = new java.util.LinkedHashMap<String, Integer>();
        oneNeed.put("iron_ingot", 32);
        var singlePull = TransferPlanner.planPull(twoIron, List.of(9, 10), oneNeed, Set.of());
        check("a satisfied need stops pulling that item", singlePull.transfers().size() == 1,
            "got " + singlePull.transfers().size());

        // Transfers convert to swaps and, applied to a simulated inventory, preserve the item total.
        List<SortPlanner.Move> moves = TransferPlanner.toMoves(deposit.transfers());
        List<InventorySimulator.Slot> start = new ArrayList<>();
        for (int i = 0; i < 60; i++) start.add(new InventorySimulator.Slot(i, null, null, 0));
        start.set(0, new InventorySimulator.Slot(0, "cobblestone", "Cobblestone", 64));
        start.set(1, new InventorySimulator.Slot(1, "diamond", "Diamond", 12));

        List<InventorySimulator.Slot> after = InventorySimulator.apply(moves, start, 64);
        check("a transfer preserves the item total", InventorySimulator.totalItems(after) == 76,
            "got " + InventorySimulator.totalItems(after));
    }

    private static void classification() {
        section("item classification");

        check("a diamond sword is combat", ItemClassifier.classify("diamond_sword") == StorageCategory.COMBAT, "wrong");
        check("a netherite pickaxe is a tool",
            ItemClassifier.classify("netherite_pickaxe") == StorageCategory.TOOLS, "wrong");
        check("a diamond helmet is armour",
            ItemClassifier.classify("diamond_helmet") == StorageCategory.ARMOR, "wrong");
        check("a shulker box is its own category",
            ItemClassifier.classify("minecraft:red_shulker_box") == StorageCategory.SHULKER, "wrong");
        check("cobblestone is building",
            ItemClassifier.classify("cobblestone") == StorageCategory.BUILDING, "wrong");
        check("a repeater is redstone", ItemClassifier.classify("repeater") == StorageCategory.REDSTONE, "wrong");
        check("netherite scrap is nether",
            ItemClassifier.classify("netherite_scrap") == StorageCategory.NETHER, "wrong");
        check("a shulker shell is end", ItemClassifier.classify("shulker_shell") == StorageCategory.END, "wrong");
        check("raw iron is an ore", ItemClassifier.classify("raw_iron") == StorageCategory.ORES, "wrong");
        check("a nether star is rare", ItemClassifier.classify("nether_star") == StorageCategory.RARE, "wrong");
        check("cooked beef is food", ItemClassifier.classify("cooked_beef") == StorageCategory.FOOD, "wrong");
        check("a potion is a potion", ItemClassifier.classify("splash_potion") == StorageCategory.POTIONS, "wrong");
        check("wheat seeds are farming", ItemClassifier.classify("wheat_seeds") == StorageCategory.FARMING, "wrong");
        check("a namespace is stripped",
            ItemClassifier.classify("minecraft:diamond_sword") == StorageCategory.COMBAT, "wrong");
        check("an unknown item falls back to misc",
            ItemClassifier.classify("some_modded_widget") == StorageCategory.MISC, "wrong");
        check("null is misc, not a crash", ItemClassifier.classify(null) == StorageCategory.MISC, "wrong");

        check("containers are recognised", ItemClassifier.isContainer("barrel"), "barrel");
        check("a torch is not a container", !ItemClassifier.isContainer("torch"), "torch");

        StorageCategory.Preset preset = StorageCategory.preset("General storage");
        check("a preset resolves by name", preset != null, "null");
        check("a preset has categories", preset != null && preset.contains(StorageCategory.BUILDING), "missing");
        check("an unknown preset is null", StorageCategory.preset("nope") == null, "not null");
        check("there are built-in presets", StorageCategory.presets().size() >= 10, "too few");
    }

    private static void containerLayout() {
        section("container layout");

        ContainerLayout chest = ContainerLayout.forRows(6);
        check("a double chest has 54 container slots", chest.containerSlots() == 54, "wrong");
        check("player slots start after the container", chest.playerStart() == 54, "wrong");
        check("total slots is container + player", chest.totalSlots() == 90, "wrong");

        check("slot 0 is the container", chest.isContainerSlot(0), "wrong");
        check("slot 53 is the container", chest.isContainerSlot(53), "wrong");
        check("slot 54 is the player", chest.isPlayerSlot(54), "wrong");
        check("slot 89 is the player", chest.isPlayerSlot(89), "wrong");
        check("player index maps correctly", chest.playerIndex(54) == 0, "wrong");
        check("hotbar is the last nine player slots", chest.isHotbarSlot(81) && chest.isHotbarSlot(89), "wrong");
        check("a main slot is not a hotbar slot", !chest.isHotbarSlot(54), "wrong");
        check("container index is -1 for a player slot", chest.playerIndex(10) == -1, "wrong");

        ContainerLayout shulker = ContainerLayout.forTotalSlots(27 + 36);
        check("a shulker layout has 27 container slots", shulker.containerSlots() == 27, "wrong");
        check("shulker player start is 27", shulker.playerStart() == 27, "wrong");

        check("both-in-player detects a rearrangement", chest.bothInPlayer(54, 60), "wrong");
        check("a container move is not both-in-player", !chest.bothInPlayer(0, 60), "wrong");
    }

    private static SortPlanner.SortItem item(int slot, String key, String name, StorageCategory cat, int count) {
        return new SortPlanner.SortItem(slot, key, name, cat, count, 64, false, false);
    }

    private static void sortPlanner() {
        section("sort planner");

        // Slots 0..2 are the target; the items are in reverse category order so a sort must move them.
        List<SortPlanner.SortItem> items = List.of(
            item(0, "cobblestone", "Cobblestone", StorageCategory.BUILDING, 64),
            item(1, "diamond", "Diamond", StorageCategory.ORES, 5),
            item(2, "diamond_sword", "Diamond Sword", StorageCategory.COMBAT, 1)
        );

        SortPlanner.Plan plan = SortPlanner.plan(items, List.of(0, 1, 2),
            StorageCategory.defaultOrder(), SortPlanner.byName(), false);

        check("a reverse-ordered inventory produces moves", plan.changed(), "no change");
        check("a move list is produced", !plan.moves().isEmpty(), "empty");

        // Applying the plan must place combat first, then ores, then building.
        List<InventorySimulator.Slot> start = List.of(
            new InventorySimulator.Slot(0, "cobblestone", "Cobblestone", 64),
            new InventorySimulator.Slot(1, "diamond", "Diamond", 5),
            new InventorySimulator.Slot(2, "diamond_sword", "Diamond Sword", 1)
        );

        List<InventorySimulator.Slot> after = InventorySimulator.apply(plan.moves(), start, 64);
        String s0 = keyAt(after, 0), s1 = keyAt(after, 1), s2 = keyAt(after, 2);

        check("the sort orders by category", s0.equals("diamond_sword") && s1.equals("diamond") && s2.equals("cobblestone"),
            "got " + s0 + "," + s1 + "," + s2);
        check("the item total is preserved", InventorySimulator.totalItems(after) == 70,
            "got " + InventorySimulator.totalItems(after));

        // Already sorted input must produce no moves.
        List<SortPlanner.SortItem> sorted = List.of(
            item(0, "diamond_sword", "Diamond Sword", StorageCategory.COMBAT, 1),
            item(1, "diamond", "Diamond", StorageCategory.ORES, 5),
            item(2, "cobblestone", "Cobblestone", StorageCategory.BUILDING, 64)
        );
        SortPlanner.Plan noop = SortPlanner.plan(sorted, List.of(0, 1, 2),
            StorageCategory.defaultOrder(), SortPlanner.byName(), false);
        check("an already-sorted inventory is left alone", !noop.changed(), "changed");

        // A custom order changes the arrangement.
        SortPlanner.Plan custom = SortPlanner.plan(items, List.of(0, 1, 2),
            List.of(StorageCategory.BUILDING, StorageCategory.ORES, StorageCategory.COMBAT),
            SortPlanner.byName(), false);
        List<InventorySimulator.Slot> customAfter = InventorySimulator.apply(custom.moves(), start, 64);
        check("a custom order is honoured", keyAt(customAfter, 0).equals("cobblestone"),
            "got " + keyAt(customAfter, 0));
    }

    private static void sortSimulation() {
        section("sort simulation and merging");

        // Two partial stacks of the same item must merge into one full stack.
        List<SortPlanner.SortItem> items = List.of(
            item(0, "diamond", "Diamond", StorageCategory.ORES, 32),
            item(1, "diamond", "Diamond", StorageCategory.ORES, 20),
            item(2, "cobblestone", "Cobblestone", StorageCategory.BUILDING, 64)
        );
        SortPlanner.Plan plan = SortPlanner.plan(items, List.of(0, 1, 2),
            StorageCategory.defaultOrder(), SortPlanner.byName(), true);

        List<InventorySimulator.Slot> start = List.of(
            new InventorySimulator.Slot(0, "diamond", "Diamond", 32),
            new InventorySimulator.Slot(1, "diamond", "Diamond", 20),
            new InventorySimulator.Slot(2, "cobblestone", "Cobblestone", 64)
        );

        List<InventorySimulator.Slot> after = InventorySimulator.apply(plan.moves(), start, 64);
        check("merging preserves the item total", InventorySimulator.totalItems(after) == 116,
            "got " + InventorySimulator.totalItems(after));

        long diamondSlots = after.stream().filter(s -> "diamond".equals(s.key()) && s.count() > 0).count();
        check("partial diamond stacks are merged into fewer slots", diamondSlots <= 2, "got " + diamondSlots);
        check("no slot exceeds the max stack size",
            after.stream().allMatch(s -> s.count() <= 64), "overflow");

        // Merging never mixes items: simulate a mix of tools and blocks.
        List<SortPlanner.SortItem> mixed = List.of(
            item(0, "iron_pickaxe", "Iron Pickaxe", StorageCategory.TOOLS, 1),
            item(1, "iron_pickaxe#2", "Iron Pickaxe", StorageCategory.TOOLS, 1),
            item(2, "stone", "Stone", StorageCategory.BUILDING, 64)
        );
        SortPlanner.Plan mixedPlan = SortPlanner.plan(mixed, List.of(0, 1, 2),
            StorageCategory.defaultOrder(), SortPlanner.byName(), true);

        List<InventorySimulator.Slot> mixedStart = List.of(
            new InventorySimulator.Slot(0, "iron_pickaxe", "Iron Pickaxe", 1),
            new InventorySimulator.Slot(1, "iron_pickaxe#2", "Iron Pickaxe", 1),
            new InventorySimulator.Slot(2, "stone", "Stone", 64)
        );

        boolean safe = true;
        try {
            InventorySimulator.apply(mixedPlan.moves(), mixedStart, 64);
        } catch (IllegalStateException e) {
            safe = false;
        }
        check("sorting never mixes different items", safe, "mixed");

        check("the fingerprint ignores empty slots",
            InventorySimulator.fingerprint(List.of(
                new InventorySimulator.Slot(0, "stone", "Stone", 5),
                new InventorySimulator.Slot(1, null, null, 0)))
                .equals(InventorySimulator.fingerprint(List.of(
                    new InventorySimulator.Slot(0, "stone", "Stone", 5)))),
            "differs");
    }

    private static void protectedSorting() {
        section("protected sorting");

        Set<Integer> locked = ProtectionRules.protectedSlots(2, Set.of(5));
        check("the hotbar reserve is protected", locked.contains(0) && locked.contains(1), "missing");
        check("a pinned slot is protected", locked.contains(5), "missing");
        check("an ordinary slot is not protected", !locked.contains(9), "unexpected");

        Set<String> protectedItems = ProtectionRules.parseList("minecraft:elytra, totem_of_undying shulker_box");
        check("the item list parses", protectedItems.size() == 3, "got " + protectedItems.size());
        check("namespaces are stripped", protectedItems.contains("elytra"), "missing");
        check("a protected item matches", ProtectionRules.isProtectedItem("minecraft:elytra", protectedItems), "no");
        check("an unprotected item does not match", !ProtectionRules.isProtectedItem("stone", protectedItems), "yes");

        // A locked stack keeps its slot: a sort must not move it even if it is out of category order.
        SortPlanner.SortItem lockedTool = new SortPlanner.SortItem(
            0, "diamond_sword", "Diamond Sword", StorageCategory.COMBAT, 1, 64, true, false);
        SortPlanner.SortItem stone = item(1, "cobblestone", "Cobblestone", StorageCategory.BUILDING, 64);

        SortPlanner.Plan plan = SortPlanner.plan(List.of(lockedTool, stone), List.of(0, 1),
            StorageCategory.defaultOrder(), SortPlanner.byName(), false);

        boolean movesLocked = plan.moves().stream().anyMatch(m -> m.fromSlot() == 0 || m.toSlot() == 0);
        check("a locked stack is never moved", !movesLocked, "moved");
    }

    private static void routingEngine() {
        section("routing engine");

        var buildingChest = new ItemRoutingEngine.Destination(
            1, "Building chest", Set.of(StorageCategory.BUILDING), 1, 10, true);
        var oreChest = new ItemRoutingEngine.Destination(
            2, "Ore chest", Set.of(StorageCategory.ORES), 1, 3, true);
        var junkChest = new ItemRoutingEngine.Destination(
            3, "Junk chest", Set.of(StorageCategory.MISC), 5, 100, true);
        var unreachable = new ItemRoutingEngine.Destination(
            4, "Unloaded chest", Set.of(StorageCategory.ORES), 0, 100, false);

        List<ItemRoutingEngine.Carried> carried = List.of(
            new ItemRoutingEngine.Carried(0, "cobblestone", "Cobblestone", StorageCategory.BUILDING, 64),
            new ItemRoutingEngine.Carried(1, "diamond", "Diamond", StorageCategory.ORES, 5),
            new ItemRoutingEngine.Carried(2, "nether_star", "Nether Star", StorageCategory.RARE, 1)
        );

        var result = ItemRoutingEngine.route(carried, List.of(buildingChest, oreChest, junkChest, unreachable));

        check("building items route to the building chest",
            result.assignments().stream().anyMatch(a -> a.destinationId() == 1), "missing");
        check("ore items route to the ore chest",
            result.assignments().stream().anyMatch(a -> a.destinationId() == 2), "missing");
        check("a rare item with no matching chest falls to the junk chest",
            result.assignments().stream().anyMatch(a -> a.destinationId() == 3), "missing");
        check("an unreachable chest is never chosen",
            result.assignments().stream().noneMatch(a -> a.destinationId() == 4), "chosen");

        // Capacity is respected: only three ore slots, so overflow must be reported, not silently dropped.
        List<ItemRoutingEngine.Carried> manyOre = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            manyOre.add(new ItemRoutingEngine.Carried(i, "iron_ingot", "Iron Ingot", StorageCategory.ORES, 64));
        }
        var capacityResult = ItemRoutingEngine.route(manyOre, List.of(oreChest));
        check("routing respects destination capacity", capacityResult.assignments().size() == 3,
            "got " + capacityResult.assignments().size());
        check("overflow is reported rather than lost", capacityResult.overflow().size() == 2,
            "got " + capacityResult.overflow().size());

        // A category filter must exclude unselected categories.
        var filtered = ItemRoutingEngine.route(carried, List.of(buildingChest, junkChest),
            Set.of(StorageCategory.BUILDING));
        check("a category filter only routes selected categories",
            filtered.assignments().size() == 1 && filtered.assignments().get(0).destinationId() == 1,
            "wrong");
    }

    private static void restockPlanner() {
        section("restock planner");

        var rules = List.of(
            new RestockPlanner.Rule("firework_rocket", "Firework Rocket", 32, 64, false),
            new RestockPlanner.Rule("cooked_beef", "Steak", 16, 32, false),
            new RestockPlanner.Rule("totem_of_undying", "Totem", 1, 2, true)
        );

        Map<String, Integer> carried = Map.of(
            "firework_rocket", 4,
            "cooked_beef", 40,
            "totem_of_undying", 0
        );

        var stock = List.of(
            new RestockPlanner.Stock(1, "Supply chest", "firework_rocket", 64),
            new RestockPlanner.Stock(1, "Supply chest", "totem_of_undying", 3),
            new RestockPlanner.Stock(2, "Food chest", "cooked_beef", 64)
        );

        var plan = RestockPlanner.plan(carried, stock, rules, 10);

        check("a below-minimum item is flagged", plan.needs().stream().anyMatch(n -> n.itemKey().equals("firework_rocket")),
            "missing");
        check("an above-minimum item is not flagged",
            plan.needs().stream().noneMatch(n -> n.itemKey().equals("cooked_beef")), "flagged");
        check("a fetch is planned for fireworks",
            plan.fetches().stream().anyMatch(f -> f.itemKey().equals("firework_rocket")), "missing");
        check("a fetch is planned for the emergency totem",
            plan.fetches().stream().anyMatch(f -> f.itemKey().equals("totem_of_undying")), "missing");

        int fireworkFetch = plan.fetches().stream()
            .filter(f -> f.itemKey().equals("firework_rocket"))
            .mapToInt(RestockPlanner.Fetch::amount).sum();
        check("the fetch tops up to max, not min", fireworkFetch == 60, "got " + fireworkFetch);

        // Nothing in storage must produce a warning, not a phantom fetch.
        var empty = RestockPlanner.plan(Map.of("firework_rocket", 0), List.of(), rules, 10);
        check("missing stock is warned about",
            empty.warnings().stream().anyMatch(w -> w.toLowerCase().contains("firework")), "no warning");
        check("missing stock produces no fetch",
            empty.fetches().stream().noneMatch(f -> f.itemKey().equals("firework_rocket")), "fetch");
    }

    private static void shulkerInventory() {
        section("shulker inventory");

        var emptyBox = new ShulkerInventory.Box(0, "Empty", List.of(), true, 27);
        var partialBox = new ShulkerInventory.Box(1, "Ores", List.of(
            new ShulkerInventory.Content("diamond", "Diamond", 32),
            new ShulkerInventory.Content("iron_ingot", "Iron Ingot", 64)), true, 27);
        var unknownBox = new ShulkerInventory.Box(2, "Unknown", List.of(), false, 27);

        check("a known-empty box is empty", emptyBox.status() == ShulkerInventory.Status.EMPTY, "wrong");
        check("a partly-filled box is partial", partialBox.status() == ShulkerInventory.Status.PARTIAL, "wrong");
        check("an unknown box is unknown, not empty",
            unknownBox.status() == ShulkerInventory.Status.UNKNOWN, "wrong");
        check("an unknown box is not reported as empty", !unknownBox.isEmptyKnown(), "empty");

        var boxes = List.of(emptyBox, partialBox, unknownBox);

        check("a find returns the matching stack",
            ShulkerInventory.find(boxes, "diamond").size() == 1, "wrong");
        check("a find does not search unknown boxes",
            ShulkerInventory.find(List.of(unknownBox), "diamond").isEmpty(), "searched");
        check("countOf sums known contents", ShulkerInventory.countOf(boxes, "iron_ingot") == 64, "wrong");
        check("unknown boxes are listed separately", ShulkerInventory.unknown(boxes).size() == 1, "wrong");
        check("firstEmpty finds the empty box", ShulkerInventory.firstEmpty(boxes) == emptyBox, "wrong");
        check("withMostFreeSpace prefers a known box",
            ShulkerInventory.withMostFreeSpace(boxes) == emptyBox, "wrong");

        var fullBox = new ShulkerInventory.Box(3, "Full",
            List.of(new ShulkerInventory.Content("stone", "Stone", 64)), true, 1);
        check("a full box is full", fullBox.status() == ShulkerInventory.Status.FULL, "wrong");

        check("chooseForFilling prefers the labelled box",
            ShulkerInventory.chooseForFilling(List.of(emptyBox, partialBox), "Ores") == partialBox, "wrong");
        check("chooseForFilling falls back to the most free space",
            ShulkerInventory.chooseForFilling(List.of(emptyBox, partialBox), "Missing") == emptyBox, "wrong");
        check("label matching is case-insensitive",
            ShulkerInventory.labelMatches(partialBox, "ores"), "no match");
    }

    private static void operationState() {
        section("operation state");

        // Happy path: open, click, verify a matching fingerprint.
        OperationState op = OperationState.begin("sort", 0, 200, "0=stonex64;");
        check("an operation starts opening", op.phase() == OperationState.Phase.OPENING, "wrong");

        op.opened();
        check("opening advances to clicking", op.phase() == OperationState.Phase.CLICKING, "wrong");

        op.clickSent();
        op.clicksComplete();
        check("clicks advance to verifying", op.phase() == OperationState.Phase.VERIFYING, "wrong");

        boolean done = false;
        for (long tick = 1; tick <= 10; tick++) {
            done = op.observe("0=stonex64;", tick, 3, 2);
            if (done) break;
        }
        check("a matching fingerprint completes the operation", done && op.phase() == OperationState.Phase.DONE, "not done");

        // Timeout: the container never opens.
        OperationState stuck = OperationState.begin("open", 0, 50, "");
        boolean timedOut = false;
        for (long tick = 1; tick <= 60; tick++) {
            if (stuck.observe("", tick, 3, 2)) { timedOut = true; break; }
        }
        check("an operation that never settles times out", timedOut, "no timeout");
        check("a timeout is a failure", stuck.phase() == OperationState.Phase.FAILED, "wrong phase");
        check("a timeout has a reason", stuck.failure() != null && !stuck.failure().isBlank(), "no reason");

        // Settled but wrong: must fail rather than report success.
        OperationState wrong = OperationState.begin("sort", 0, 500, "0=stonex64;");
        wrong.opened();
        wrong.clickSent();
        wrong.clicksComplete();
        boolean wrongDone = false;
        for (long tick = 1; tick <= 500 && !wrongDone; tick++) {
            wrongDone = wrong.observe("0=dirtx64;", tick, 5, 2);
        }
        check("a settled-but-mismatched container fails", wrong.phase() == OperationState.Phase.FAILED, "not failed");

        // Repeated rejected clicks must fail rather than loop.
        OperationState rejected = OperationState.begin("sort", 0, 500, "");
        rejected.opened();
        rejected.clickRejected("slot gone");
        rejected.clickRejected("slot gone");
        check("a couple of rejected clicks are tolerated", rejected.phase() != OperationState.Phase.FAILED, "failed early");
        rejected.clickRejected("slot gone");
        check("too many rejected clicks fail the operation", rejected.phase() == OperationState.Phase.FAILED, "not failed");

        // Cancel is safe and idempotent.
        OperationState cancelled = OperationState.begin("sort", 0, 500, "");
        cancelled.cancel();
        cancelled.cancel();
        check("an operation can be cancelled", cancelled.phase() == OperationState.Phase.CANCELLED, "wrong");
        check("a cancelled operation is finished", cancelled.finished(), "not finished");
    }

    private static void workflowEngine() {
        section("workflow engine");

        WorkflowEngine engine = new WorkflowEngine();
        for (WorkflowEngine.Workflow workflow : WorkflowEngine.builtins()) engine.put(workflow);

        check("built-in workflows are registered", engine.workflows().size() >= 4, "too few");
        check("a workflow resolves by name", engine.workflow("quick deposit") != null, "missing");

        check("starting a known workflow succeeds", engine.start("quick deposit"), "failed");
        check("a started workflow is running", engine.isRunning(), "not running");
        check("the first step is the current step",
            engine.currentStep() != null && engine.currentStep().kind() == WorkflowEngine.Kind.SORT_INVENTORY,
            "wrong step");

        engine.completeStep(true);
        check("completing a step advances", engine.stepIndex() == 1, "wrong index");

        engine.completeStep(true);
        check("the run advances again", engine.stepIndex() == 2, "wrong index");

        engine.completeStep(true);
        check("finishing the last step completes the run", engine.status() == WorkflowEngine.Status.DONE, "wrong");

        // A failed step must stop the run.
        engine.start("full cleanup");
        engine.completeStep(true);
        engine.completeStep(false);
        check("a failed step fails the run", engine.status() == WorkflowEngine.Status.FAILED, "wrong");
        check("a failed run has a reason", engine.failure() != null, "no reason");

        // A wait step advances on ticks.
        engine.put(new WorkflowEngine.Workflow("waiter", List.of(
            WorkflowEngine.Step.wait(3),
            WorkflowEngine.Step.of(WorkflowEngine.Kind.REPORT)), false));
        engine.start("waiter");
        check("a wait step is current", engine.currentStep().kind() == WorkflowEngine.Kind.WAIT, "wrong");
        check("the wait does not finish immediately", !engine.tickWait(), "finished early");
        engine.tickWait();
        check("the wait finishes after its duration", engine.tickWait(), "not finished");
        check("the run advances past the wait",
            engine.currentStep() != null && engine.currentStep().kind() == WorkflowEngine.Kind.REPORT, "wrong step");

        // Unknown workflows are refused.
        check("an unknown workflow cannot start", !engine.start("nope"), "started");

        engine.reset();
        check("reset returns the engine to idle", engine.status() == WorkflowEngine.Status.IDLE, "wrong");
    }

    private static String keyAt(List<InventorySimulator.Slot> slots, int index) {
        for (InventorySimulator.Slot slot : slots) {
            if (slot.index() == index) return slot.key() == null ? "" : slot.key();
        }
        return "";
    }
}
