package dev.basehunt.bhclient;

import dev.basehunt.bhclient.core.AutomationBus;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.ControlResource;
import dev.basehunt.bhclient.core.Lease;
import dev.basehunt.bhclient.core.Priority;
import dev.basehunt.bhclient.core.TaskScheduler;
import dev.basehunt.bhclient.core.logic.ElytraDeployment;
import dev.basehunt.bhclient.core.logic.ElytraDurability;
import dev.basehunt.bhclient.core.logic.ExplorationPlanner;
import dev.basehunt.bhclient.core.logic.ExploredRegions;
import dev.basehunt.bhclient.core.logic.FlightState;
import dev.basehunt.bhclient.core.logic.InventoryPlan;
import dev.basehunt.bhclient.core.logic.ItemCategory;
import dev.basehunt.bhclient.core.logic.MendingPriorities;
import dev.basehunt.bhclient.core.logic.RecordCodec;
import dev.basehunt.bhclient.core.logic.RetryPolicy;
import dev.basehunt.bhclient.core.logic.StashClassification;
import dev.basehunt.bhclient.core.logic.StashScorer;
import dev.basehunt.bhclient.core.logic.SurvivalStatus;
import dev.basehunt.bhclient.core.logic.TargetPlanner;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Headless tests for the addon's pure logic: flight planning, elytra state, scoring, inventory planning,
 * survival thresholds and the scheduler. None of these touch Minecraft classes, so they run in a plain JVM
 * and catch the arithmetic and state-machine bugs that would otherwise only show up mid-flight.
 */
public class CoreLogicTest {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS: " + name); }
        else { failed++; System.out.println("  FAIL: " + name + " -> " + detail); }
    }

    static void section(String title) {
        System.out.println("== " + title + " ==");
    }

    public static void main(String[] args) {
        elytraDurability();
        deploymentStateMachine();
        flightStateClassification();
        mendingPriorities();
        itemClassification();
        inventoryPlanning();
        stashScoring();
        chunkDeduplication();
        recordRecovery();
        targetPrioritisation();
        retryPolicy();
        explorationPlanner();
        survivalThresholds();
        scheduler();
        eventBus();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    static void elytraDurability() {
        section("elytra durability");

        check("remaining is max minus damage", ElytraDurability.remaining(432, 400) == 32, "wrong");
        check("remaining never negative", ElytraDurability.remaining(432, 500) == 0, "wrong");
        check("percent is 100 when undamaged", ElytraDurability.percent(432, 432) == 100.0, "wrong");
        check("percent is 0 when broken", ElytraDurability.percent(432, 0) == 0.0, "wrong");
        check("percent for a non-damageable stack is 0", ElytraDurability.percent(0, 100) == 0.0, "wrong");

        check("replace when below threshold", ElytraDurability.shouldReplace(10, 20), "not replaced");
        check("do not replace when above threshold", !ElytraDurability.shouldReplace(30, 20), "replaced");
        check("zero threshold never forces a replace", !ElytraDurability.shouldReplace(1, 0), "replaced");

        check("viable at exactly the minimum", ElytraDurability.isViable(16, 16), "not viable");
        check("not viable below the minimum", !ElytraDurability.isViable(15, 16), "viable");
        check("a missing elytra (-1) is never viable", !ElytraDurability.isViable(-1, 0), "viable");

        // Healthiest spare wins, and a slot below the minimum is never selected.
        int[] spares = {5, 120, 60, 0};
        check("bestSlot picks the healthiest", ElytraDurability.bestSlot(spares, 16) == 1,
            "got " + ElytraDurability.bestSlot(spares, 16));
        check("bestSlot returns -1 when none are viable",
            ElytraDurability.bestSlot(new int[] {5, 6}, 16) == -1, "got a slot");
        check("bestSlot ignores empty slots", ElytraDurability.bestSlot(new int[] {-1, -1}, 1) == -1, "got a slot");

        check("journey completable with a healthy elytra",
            ElytraDurability.canCompleteJourney(200, new int[] {100}, 5000, 16), "not completable");
        check("journey refused when everything is too worn",
            !ElytraDurability.canCompleteJourney(10, new int[] {5}, 100_000, 16), "allowed");
    }

    static void deploymentStateMachine() {
        section("elytra deployment");

        check("grounded with a usable elytra launches",
            ElytraDeployment.decide(FlightState.GROUNDED, true, true, false, false, false, true, true)
                == ElytraDeployment.Action.LAUNCH, "wrong action");

        check("worn elytra with a spare swaps instead of launching",
            ElytraDeployment.decide(FlightState.GROUNDED, true, true, true, true, false, true, true)
                == ElytraDeployment.Action.REPLACE, "wrong action");

        check("no elytra worn but a spare exists triggers an equip/swap",
            ElytraDeployment.decide(FlightState.GROUNDED, false, false, false, true, false, true, true)
                == ElytraDeployment.Action.REPLACE, "wrong action");

        check("no elytra anywhere aborts the route",
            ElytraDeployment.decide(FlightState.GROUNDED, false, false, false, false, false, true, true)
                == ElytraDeployment.Action.ABORT, "wrong action");

        check("falling deploys rather than aborting",
            ElytraDeployment.decide(FlightState.FALLING, true, true, false, false, false, true, true)
                == ElytraDeployment.Action.LAUNCH, "wrong action");

        check("swimming waits instead of launching",
            ElytraDeployment.decide(FlightState.SWIMMING, true, true, false, false, false, true, true)
                == ElytraDeployment.Action.WAIT, "wrong action");

        check("already gliding cruises",
            ElytraDeployment.decide(FlightState.GLIDING, true, true, false, false, true, true, true)
                == ElytraDeployment.Action.CRUISE, "wrong action");

        check("gliding on a worn elytra with a spare swaps without landing",
            ElytraDeployment.decide(FlightState.GLIDING, true, true, true, true, true, true, true)
                == ElytraDeployment.Action.REPLACE, "wrong action");

        check("a closed retry gate turns a launch into a wait",
            ElytraDeployment.decide(FlightState.GROUNDED, true, true, false, false, false, true, false)
                == ElytraDeployment.Action.WAIT, "wrong action");

        check("with no route wanted and flight off nothing happens",
            ElytraDeployment.decide(FlightState.GROUNDED, true, true, false, false, false, false, true)
                == ElytraDeployment.Action.NONE, "wrong action");

        check("abort is terminal", ElytraDeployment.isTerminal(ElytraDeployment.Action.ABORT), "not terminal");
        check("launch is not terminal", !ElytraDeployment.isTerminal(ElytraDeployment.Action.LAUNCH), "terminal");
    }

    static void flightStateClassification() {
        section("flight state");

        check("gliding wins over everything",
            FlightState.classify(true, true, true, true, -1.0) == FlightState.GLIDING, "wrong");
        check("swimming beats climbing",
            FlightState.classify(false, true, true, false, 0) == FlightState.SWIMMING, "wrong");
        check("climbing beats grounded",
            FlightState.classify(false, false, true, true, 0) == FlightState.CLIMBING, "wrong");
        check("on ground is grounded",
            FlightState.classify(false, false, false, true, 0) == FlightState.GROUNDED, "wrong");
        check("descending in air is falling",
            FlightState.classify(false, false, false, false, -0.5) == FlightState.FALLING, "wrong");
        check("rising in air is airborne",
            FlightState.classify(false, false, false, false, 0.5) == FlightState.AIRBORNE, "wrong");

        check("falling can deploy", FlightState.FALLING.canDeploy(), "cannot deploy");
        check("swimming cannot deploy", !FlightState.SWIMMING.canDeploy(), "can deploy");
        check("grounded is supported", FlightState.GROUNDED.isSupported(), "not supported");
        check("falling is not supported", !FlightState.FALLING.isSupported(), "supported");
        check("airborne without a glide is detected", FlightState.FALLING.isAirborneWithoutGlide(), "not detected");
    }

    static void mendingPriorities() {
        section("mending priorities");

        List<MendingPriorities.Candidate> candidates = new ArrayList<>(List.of(
            new MendingPriorities.Candidate("elytra", 0, 400, 432, true, 10),
            new MendingPriorities.Candidate("pickaxe", 1, 1500, 1561, false, 1),
            new MendingPriorities.Candidate("boots", 2, 100, 481, false, 1)
        ));

        List<MendingPriorities.Candidate> ranked = MendingPriorities.rank(candidates);
        check("ranking drops nothing that needs repair", ranked.size() == 3, "got " + ranked.size());
        check("the most damaged item ranks first", ranked.get(0).name().equals("boots"),
            "got " + ranked.get(0).name());

        List<MendingPriorities.Candidate> whole = List.of(
            new MendingPriorities.Candidate("helmet", 0, 363, 363, true, 1));
        check("an undamaged item is not a repair candidate", MendingPriorities.rank(whole).isEmpty(), "candidate kept");

        check("no repair when no XP is available",
            !MendingPriorities.shouldRepair(false, 100, 10), "repaired without XP");
        check("repair when XP is available and something is damaged",
            MendingPriorities.shouldRepair(true, 100, 10), "did not repair");
        check("no repair when damage is below the minimum",
            !MendingPriorities.shouldRepair(true, 5, 10), "repaired too little");

        check("selectSlot picks the worst item's slot", MendingPriorities.selectSlot(candidates) == 2,
            "got " + MendingPriorities.selectSlot(candidates));
        check("selectSlot returns -1 for an empty list", MendingPriorities.selectSlot(List.of()) == -1, "got a slot");
    }

    static void itemClassification() {
        section("item classification");

        check("elytra classified", ItemCategory.classify("elytra") == ItemCategory.ELYTRA, "wrong");
        check("diamond chestplate is armor", ItemCategory.classify("diamond_chestplate") == ItemCategory.ARMOR, "wrong");
        check("firework rocket classified", ItemCategory.classify("firework_rocket") == ItemCategory.FIREWORKS, "wrong");
        check("golden apple is food", ItemCategory.classify("golden_apple") == ItemCategory.FOOD, "wrong");
        check("cobblestone is building", ItemCategory.classify("cobblestone") == ItemCategory.BUILDING_BLOCKS, "wrong");
        check("totem classified", ItemCategory.classify("totem_of_undying") == ItemCategory.TOTEMS, "wrong");
        check("netherite ingot is valuable", ItemCategory.classify("netherite_ingot") == ItemCategory.VALUABLE, "wrong");
        check("shulker box is utility", ItemCategory.classify("shulker_box") == ItemCategory.UTILITY, "wrong");
        check("experience bottle is an XP supply",
            ItemCategory.classify("experience_bottle") == ItemCategory.XP_SUPPLIES, "wrong");
        check("unknown item falls back to other", ItemCategory.classify("definitely_not_an_item") == ItemCategory.OTHER, "wrong");
        check("blank id falls back to other", ItemCategory.classify("") == ItemCategory.OTHER, "wrong");

        check("elytra is precious", ItemCategory.ELYTRA.isPrecious(), "not precious");
        check("building blocks are not precious", !ItemCategory.BUILDING_BLOCKS.isPrecious(), "precious");
        check("totems are precious", ItemCategory.TOTEMS.isPrecious(), "not precious");
    }

    static void inventoryPlanning() {
        section("inventory planning");

        List<InventoryPlan.Entry> carried = new ArrayList<>(List.of(
            new InventoryPlan.Entry(10, ItemCategory.BUILDING_BLOCKS, "Cobblestone", 64),
            new InventoryPlan.Entry(11, ItemCategory.BUILDING_BLOCKS, "Cobblestone", 64),
            new InventoryPlan.Entry(12, ItemCategory.ELYTRA, "Elytra", 1)
        ));

        Set<ItemCategory> deposit = EnumSet.of(ItemCategory.BUILDING_BLOCKS);
        Map<ItemCategory, Integer> reserves = Map.of(ItemCategory.BUILDING_BLOCKS, 64);

        List<InventoryPlan.Move> plan = InventoryPlan.planDeposit(carried, deposit, reserves);
        check("reserve leaves one stack behind", plan.size() == 1, "got " + plan.size());
        check("the deposited stack is the surplus one", plan.get(0).slotId() == 11, "got slot " + plan.get(0).slotId());
        check("the move is a deposit", plan.get(0).deposit(), "not a deposit");

        check("unselected precious category is untouched",
            InventoryPlan.planDeposit(carried, EnumSet.of(ItemCategory.BUILDING_BLOCKS), Map.of()).stream()
                .noneMatch(m -> m.category() == ItemCategory.ELYTRA),
            "elytra was deposited");

        check("nothing is deposited when every stack is inside the reserve",
            InventoryPlan.planDeposit(carried, deposit, Map.of(ItemCategory.BUILDING_BLOCKS, 128)).isEmpty(),
            "deposited into the reserve");

        check("isSafeToDeposit refuses an unselected category",
            !InventoryPlan.isSafeToDeposit(new InventoryPlan.Entry(0, ItemCategory.ELYTRA, "Elytra", 1),
                EnumSet.of(ItemCategory.BUILDING_BLOCKS)), "allowed");
        check("isSafeToDeposit allows a selected category",
            InventoryPlan.isSafeToDeposit(new InventoryPlan.Entry(0, ItemCategory.BUILDING_BLOCKS, "Stone", 64),
                EnumSet.of(ItemCategory.BUILDING_BLOCKS)), "refused");

        List<InventoryPlan.Entry> chest = new ArrayList<>(List.of(
            new InventoryPlan.Entry(0, ItemCategory.ELYTRA, "Elytra", 1),
            new InventoryPlan.Entry(1, ItemCategory.FIREWORKS, "Rocket", 64),
            new InventoryPlan.Entry(2, ItemCategory.FOOD, "Steak", 64)
        ));

        List<InventoryPlan.Move> withdraw = InventoryPlan.planWithdraw(chest,
            EnumSet.of(ItemCategory.ELYTRA, ItemCategory.FIREWORKS), 1);
        check("withdraw respects the free-slot limit", withdraw.size() == 1, "got " + withdraw.size());
        check("withdraw only takes selected categories", !withdraw.get(0).category().equals(ItemCategory.FOOD),
            "took food");

        check("withdraw with no free slots does nothing",
            InventoryPlan.planWithdraw(chest, EnumSet.of(ItemCategory.ELYTRA), 0).isEmpty(), "moved something");

        check("preview describes each move", InventoryPlan.preview(withdraw).size() == withdraw.size(), "mismatch");
    }

    static void stashScoring() {
        section("stash scoring");

        StashScorer.Evidence lone = new StashScorer.Evidence(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        StashScorer.Evidence base = new StashScorer.Evidence(12, 2, 6, 4, 2, 4, 2, 1, 1, 0, 0);

        check("one chest is not a base", StashScorer.score(lone) < 24, "score " + StashScorer.score(lone));
        check("a full base scores highly", StashScorer.score(base) > 120, "score " + StashScorer.score(base));
        check("more containers never scores less",
            StashScorer.score(base) > StashScorer.score(new StashScorer.Evidence(3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
            "monotonicity broken");
        check("container count is the sum of storage blocks", lone.containerCount() == 1, "wrong");
        check("total storage includes utility blocks", base.totalStorage() > base.containerCount(), "wrong");

        check("one chest classifies as at most a possible stash",
            StashScorer.classify(StashScorer.score(lone), 1) == StashClassification.POSSIBLE_STASH
                || StashScorer.classify(StashScorer.score(lone), 1) == StashClassification.UNCONFIRMED,
            "got " + StashScorer.classify(StashScorer.score(lone), 1));

        check("a large base classifies as noteworthy",
            StashScorer.classify(StashScorer.score(base), base.containerCount()).isNoteworthy(),
            "got " + StashScorer.classify(StashScorer.score(base), base.containerCount()));

        check("nothing at all is unconfirmed",
            StashScorer.classify(0, 0) == StashClassification.UNCONFIRMED, "got " + StashScorer.classify(0, 0));

        check("threshold met on score alone", StashScorer.meetsThreshold(50, 1, 40, 64), "not met");
        check("threshold met on containers alone", StashScorer.meetsThreshold(0, 64, 40, 64), "not met");
        check("threshold not met below both", !StashScorer.meetsThreshold(10, 2, 40, 64), "met");

        check("classification describe is non-empty",
            !StashClassification.MAJOR_STORAGE.describe().isBlank(), "blank");

        // Structural evidence: a beacon is worth a lot on its own, a couple of utility blocks less so.
        StashScorer.Evidence beacon = new StashScorer.Evidence(0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0);
        StashScorer.Evidence utilities = new StashScorer.Evidence(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2);
        check("a beacon scores well above a lone chest",
            StashScorer.score(beacon) > StashScorer.score(lone), "beacon " + StashScorer.score(beacon));
        check("utility blocks contribute to the score", StashScorer.score(utilities) > 0,
            "got " + StashScorer.score(utilities));
        check("an empty chunk still scores zero",
            StashScorer.score(new StashScorer.Evidence(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)) == 0, "non-zero");
        check("interesting structure is noteworthy",
            StashClassification.INTERESTING_STRUCTURE.isNoteworthy(), "not noteworthy");
        check("unconfirmed is not noteworthy", !StashClassification.UNCONFIRMED.isNoteworthy(), "noteworthy");
    }

    static void chunkDeduplication() {
        section("chunk deduplication");

        ExploredRegions regions = new ExploredRegions(100_000);

        check("unknown chunk is not explored", !regions.wasExplored(10, 10), "explored");
        regions.markExplored(10, 10);
        check("marked chunk is explored", regions.wasExplored(10, 10), "not explored");
        check("neighbouring chunk is not explored", !regions.wasExplored(10, 11), "explored");

        regions.markExplored(10, 10);
        check("marking twice does not double count", regions.exploredCount() == 1, "got " + regions.exploredCount());

        regions.markRequiresReview(20, 20);
        check("review flag recorded", regions.needsReview(20, 20), "not recorded");
        regions.clearReview(20, 20);
        check("review flag cleared", !regions.needsReview(20, 20), "still set");

        // The in-memory set is bounded so a very long session cannot grow without limit. The floor is 1024.
        ExploredRegions bounded = new ExploredRegions(1);
        for (int i = 0; i < 3000; i++) bounded.markExplored(i, 0);
        check("bounded store respects its floor limit", bounded.exploredCount() == 1024,
            "got " + bounded.exploredCount());

        regions.markExplored(11, 10);
        double coverage = regions.coverageAround(10, 10, 1);
        check("coverage reflects the explored chunks", coverage > 0.0 && coverage < 1.0, "got " + coverage);

        check("packed keys round-trip", ExploredRegions.keyX(ExploredRegions.key(-5, 900)) == -5
            && ExploredRegions.keyZ(ExploredRegions.key(-5, 900)) == 900, "wrong");

        regions.clear();
        check("clear empties the store", regions.exploredCount() == 0, "not empty");
    }

    static void recordRecovery() {
        section("record recovery");

        // A corrupt record must be skipped without costing the good ones. The decoder here mirrors what the
        // stores do: one entry returns null (unreadable), the rest decode.
        NbtList list = new NbtList();
        list.add(compound(1));
        list.add(new NbtList());
        list.add(compound(2));
        list.add(compound(0));

        RecordCodec.Result<Integer> result = RecordCodec.decode(list, tag -> {
            int id = tag.getInt("id", 0);
            return id == 0 ? null : id;
        });

        check("good records survive a corrupt neighbour", result.size() == 2, "got " + result.size());
        check("skipped records are counted", result.skipped() == 2, "got " + result.skipped());
        check("anySkipped reports corruption", result.anySkipped(), "not reported");
        check("decode order is preserved", result.records().get(0) == 1 && result.records().get(1) == 2, "wrong order");

        // A decoder that throws is treated exactly like one that returns null.
        NbtList throwing = new NbtList();
        throwing.add(compound(7));
        RecordCodec.Result<Integer> survived = RecordCodec.decode(throwing, tag -> {
            throw new IllegalStateException("boom");
        });
        check("a throwing decoder does not abort the load", survived.size() == 0 && survived.skipped() == 1,
            "wrong result");

        // Missing data decodes to an empty list rather than a crash.
        check("a missing list decodes to empty",
            RecordCodec.decodeList(new NbtCompound(), "nope", tag -> 1).size() == 0, "got records");
        check("a null root decodes to empty",
            RecordCodec.decodeList(null, "nope", tag -> 1).size() == 0, "got records");

        // Version handling: absent means current, a higher number means the file is from a newer build.
        NbtCompound current = new NbtCompound();
        current.putInt("version", 3);
        check("version is read back", RecordCodec.readVersion(current, 1) == 3, "wrong version");
        check("an absent version falls back", RecordCodec.readVersion(new NbtCompound(), 2) == 2, "wrong fallback");
        NbtCompound nonsense = new NbtCompound();
        nonsense.putInt("version", -4);
        check("a nonsense version falls back", RecordCodec.readVersion(nonsense, 2) == 2, "wrong fallback");

        NbtCompound newer = new NbtCompound();
        newer.putInt("version", 99);
        check("a newer file is detected", RecordCodec.isFromNewerVersion(newer, 3), "not detected");
        check("a current file is not flagged", !RecordCodec.isFromNewerVersion(current, 3), "flagged");

        NbtCompound older = new NbtCompound();
        older.putInt("version", 1);
        check("an older file needs migration", RecordCodec.needsMigration(older, 3), "not flagged");
        check("a current file does not need migration", !RecordCodec.needsMigration(current, 3), "flagged");
    }

    private static NbtCompound compound(int id) {
        NbtCompound tag = new NbtCompound();
        tag.putInt("id", id);
        return tag;
    }

    static void targetPrioritisation() {
        section("target prioritisation");

        List<TargetPlanner.Candidate> candidates = new ArrayList<>(List.of(
            new TargetPlanner.Candidate("near-empty", 200, 0, "overworld", 0, false, 0, "unknown"),
            new TargetPlanner.Candidate("far-rich", 5000, 0, "overworld", 200, false, 0, "large stash"),
            new TargetPlanner.Candidate("near-rich", 300, 0, "overworld", 150, false, 0, "medium stash")
        ));

        List<TargetPlanner.Scored> ranked = TargetPlanner.rank(candidates, 0, 0, "overworld", TargetPlanner.Weights.defaults());
        check("every candidate is scored", ranked.size() == 3, "got " + ranked.size());
        check("the empty nearby target is not first",
            !ranked.get(0).candidate().label().equals("near-empty"), "empty target won");
        check("scores are descending",
            ranked.get(0).score() >= ranked.get(1).score() && ranked.get(1).score() >= ranked.get(2).score(),
            "not sorted");

        // A revisit penalty should demote an already-seen location.
        List<TargetPlanner.Candidate> withSeen = new ArrayList<>(List.of(
            new TargetPlanner.Candidate("seen", 300, 0, "overworld", 150, true, 0, "medium stash"),
            new TargetPlanner.Candidate("fresh", 300, 0, "overworld", 150, false, 0, "medium stash")
        ));
        List<TargetPlanner.Scored> seenRanked = TargetPlanner.rank(withSeen, 0, 0, "overworld", TargetPlanner.Weights.defaults());
        check("a previously discovered target is demoted",
            seenRanked.get(0).candidate().label().equals("fresh"), "got " + seenRanked.get(0).candidate().label());

        check("targets in another dimension are skipped",
            TargetPlanner.rank(candidates, 0, 0, "nether", TargetPlanner.Weights.defaults()).isEmpty(),
            "cross-dimension target kept");

        check("best returns null for no candidates",
            TargetPlanner.best(List.of(), 0, 0, "overworld", TargetPlanner.Weights.defaults()) == null, "not null");

        check("aggressive weights prefer the rich target",
            TargetPlanner.best(candidates, 0, 0, "overworld", TargetPlanner.Weights.aggressive()).label().equals("far-rich"),
            "wrong winner");

        check("cautious weights prefer a nearby target",
            TargetPlanner.best(candidates, 0, 0, "overworld", TargetPlanner.Weights.cautious()).label().equals("near-rich"),
            "wrong winner");
    }

    static void retryPolicy() {
        section("retry policy");

        RetryPolicy policy = new RetryPolicy(3, 100, 1000);
        check("first attempt allowed", policy.canAttempt(0), "blocked");
        check("not exhausted initially", !policy.exhausted(), "exhausted");

        policy.recordAttempt(0);
        check("second attempt blocked by the delay", !policy.canAttempt(50), "allowed too early");
        check("second attempt allowed after the delay", policy.canAttempt(150), "still blocked");

        policy.recordAttempt(200);
        policy.recordAttempt(400);
        check("attempts counted", policy.attempts() == 3, "got " + policy.attempts());
        check("exhausted after the limit", policy.exhausted(), "not exhausted");
        check("exhausted policy refuses further attempts", !policy.canAttempt(100_000), "allowed");

        policy.reset();
        check("reset clears attempts", policy.attempts() == 0 && !policy.exhausted(), "not reset");
        check("backoff never exceeds the cap", new RetryPolicy(10, 100, 500).millisUntilNextAttempt(0) >= 0, "negative");
    }

    static void explorationPlanner() {
        section("exploration planner");

        List<ExplorationPlanner.Waypoint> grid = ExplorationPlanner.grid(0, 0, 1000, 500, 4);
        check("grid produces a leg and a turn per pass", grid.size() == 8, "got " + grid.size());
        check("grid advances along X", grid.get(0).x() == 1000, "got " + grid.get(0).x());
        check("grid boustrophedons back", grid.get(2).x() == -1000, "got " + grid.get(2).x());

        List<ExplorationPlanner.Waypoint> spiral = ExplorationPlanner.spiral(0, 0, 1000, 5000);
        check("spiral produces waypoints", spiral.size() > 5, "got " + spiral.size());
        check("spiral starts at the centre", spiral.get(0).x() == 0 && spiral.get(0).z() == 0, "wrong start");

        List<ExplorationPlanner.Waypoint> ordered = ExplorationPlanner.orderByNearest(grid, 0, 0);
        check("ordering keeps every waypoint", ordered.size() == grid.size(), "lost waypoints");
        check("ordering starts with the nearest", ordered.get(0).x() == 1000, "got " + ordered.get(0).x());

        double length = ExplorationPlanner.routeLength(grid, 0, 0);
        check("route length is positive", length > 0, "got " + length);

        ExplorationPlanner.Waypoint expanded = ExplorationPlanner.expandFrom(1000, 0, 0, 0, 2000);
        check("expansion pushes beyond the last point",
            Math.hypot(expanded.x(), expanded.z()) > 2000, "got " + expanded.x() + "," + expanded.z());

        ExplorationPlanner.Waypoint fromOrigin = ExplorationPlanner.expandFrom(0, 0, 0, 0, 500);
        check("expansion with no heading pushes east", fromOrigin.x() == 500, "got " + fromOrigin.x());

        List<ExplorationPlanner.Waypoint> adaptive = ExplorationPlanner.adaptive(
            0, 0, 1000, 4, List.of(new int[] {0, 0}), 200, 100, 1000);
        check("adaptive produces waypoints", adaptive.size() == 8, "got " + adaptive.size());

        // Heading mode: bearing 0 is north (negative Z), 90 is east (positive X).
        ExplorationPlanner.Waypoint north = ExplorationPlanner.headingLeg(0, 0, 0, 1000, 0, 256);
        check("heading north goes negative Z", north.z() < 0 && Math.abs(north.x()) < 10,
            "got " + north.x() + "," + north.z());

        ExplorationPlanner.Waypoint east = ExplorationPlanner.headingLeg(0, 0, 90, 1000, 0, 256);
        check("heading east goes positive X", east.x() > 0 && Math.abs(east.z()) < 10,
            "got " + east.x() + "," + east.z());

        // Later legs are offset sideways so a heading sweep covers a band rather than a single line.
        ExplorationPlanner.Waypoint secondLeg = ExplorationPlanner.headingLeg(0, 0, 0, 1000, 1, 256);
        check("later heading legs are offset sideways", Math.abs(secondLeg.x()) >= 250,
            "got " + secondLeg.x());
    }

    static void survivalThresholds() {
        section("survival thresholds");

        SurvivalStatus.Thresholds t = SurvivalStatus.Thresholds.defaults();

        SurvivalStatus.Snapshot healthy = new SurvivalStatus.Snapshot(
            20, 20, 20, 200, 2, 2, 64, 64, 10, false, true);
        check("a healthy player has no issues", SurvivalStatus.evaluate(healthy, t).isEmpty(), "issues present");
        check("healthy is not critical", !SurvivalStatus.hasCritical(SurvivalStatus.evaluate(healthy, t)), "critical");

        SurvivalStatus.Snapshot dying = new SurvivalStatus.Snapshot(
            4, 20, 20, 200, 2, 2, 64, 64, 10, false, true);
        List<SurvivalStatus.Issue> issues = SurvivalStatus.evaluate(dying, t);
        check("low health is reported", !issues.isEmpty(), "not reported");
        check("low health is critical", SurvivalStatus.hasCritical(issues), "not critical");

        SurvivalStatus.Snapshot glidingNoElytra = new SurvivalStatus.Snapshot(
            20, 20, 20, -1, 0, 0, 0, 0, 10, true, false);
        check("gliding without an elytra is critical",
            SurvivalStatus.hasCritical(SurvivalStatus.evaluate(glidingNoElytra, t)), "not critical");

        SurvivalStatus.Snapshot worn = new SurvivalStatus.Snapshot(
            20, 20, 20, 5, 2, 2, 64, 64, 10, false, true);
        check("worn elytra is reported", SurvivalStatus.evaluate(worn, t).stream()
            .anyMatch(i -> i.code().equals("elytra-durability")), "not reported");

        SurvivalStatus.Snapshot noTotem = new SurvivalStatus.Snapshot(
            20, 20, 20, 200, 2, 0, 64, 64, 10, false, true);
        check("missing totem is reported", SurvivalStatus.evaluate(noTotem, t).stream()
            .anyMatch(i -> i.code().equals("no-totem")), "not reported");

        SurvivalStatus.Snapshot full = new SurvivalStatus.Snapshot(
            20, 20, 20, 200, 2, 2, 64, 64, 0, false, true);
        check("a full inventory is reported", SurvivalStatus.evaluate(full, t).stream()
            .anyMatch(i -> i.code().equals("inventory-full")), "not reported");
    }

    static void scheduler() {
        section("scheduler");

        TaskScheduler scheduler = new TaskScheduler();

        Lease low = scheduler.acquire("scanner", Priority.BACKGROUND_DISCOVERY,
            Set.of(ControlResource.MOVEMENT), 0, 100);
        check("first lease is granted", low != null, "not granted");

        Lease high = scheduler.acquire("survival", Priority.CRITICAL_SURVIVAL,
            Set.of(ControlResource.MOVEMENT), 0, 100);
        check("higher priority preempts", high != null, "not granted");
        check("the resource owner is the higher priority holder",
            "survival".equals(scheduler.ownerOf(ControlResource.MOVEMENT)), "wrong owner");

        // A lower priority request must not displace the current holder.
        Lease lower = scheduler.acquire("scanner2", Priority.BACKGROUND_DISCOVERY,
            Set.of(ControlResource.MOVEMENT), 0, 100);
        check("lower priority does not preempt", lower == null, "preempted");

        // Equal priority must not preempt either, or two same-tier modules would livelock.
        Lease equal = scheduler.acquire("other", Priority.CRITICAL_SURVIVAL,
            Set.of(ControlResource.MOVEMENT), 0, 100);
        check("equal priority does not preempt", equal == null, "preempted");

        // Re-requesting from the current owner refreshes rather than blocking.
        Lease refreshed = scheduler.acquire("survival", Priority.CRITICAL_SURVIVAL,
            Set.of(ControlResource.MOVEMENT), 0, 100);
        check("the current owner can refresh its lease", refreshed != null, "refused");

        scheduler.tick(500);
        check("expired lease releases the resource", scheduler.ownerOf(ControlResource.MOVEMENT) == null, "still held");

        Lease reacquired = scheduler.acquire("flight", Priority.FLIGHT_SAFETY,
            Set.of(ControlResource.MOVEMENT), 500, 50);
        check("resource can be reacquired after expiry", reacquired != null, "not reacquired");
        check("owns() reports the holder", scheduler.owns("flight", ControlResource.MOVEMENT), "not owned");

        scheduler.release(reacquired);
        check("release frees the resource", scheduler.isFree(ControlResource.MOVEMENT), "still held");

        // An independent resource is unaffected by contention on another.
        scheduler.acquire("inventory", Priority.INVENTORY_MANAGEMENT, Set.of(ControlResource.INVENTORY), 0, 100);
        check("independent resources are held separately",
            scheduler.ownerOf(ControlResource.INVENTORY).equals("inventory"), "wrong owner");
        check("describe is non-empty", !scheduler.describe().isBlank(), "blank");
    }

    static void eventBus() {
        section("event bus");

        AutomationBus bus = new AutomationBus();

        List<AutomationEvent> seen = new ArrayList<>();
        bus.subscribe(AutomationEvent.Type.STASH_DISCOVERED, seen::add);

        bus.publish(AutomationEvent.of(AutomationEvent.Type.STASH_DISCOVERED, "test", Map.of("score", "120")));
        check("typed subscriber received the event", seen.size() == 1, "got " + seen.size());

        bus.publish(AutomationEvent.of(AutomationEvent.Type.PLAYER_SIGHTED, "test"));
        check("typed subscriber ignores other types", seen.size() == 1, "got " + seen.size());

        check("history records events", bus.historySize() == 2, "got " + bus.historySize());
        check("recent returns newest first",
            bus.recent(1).get(0).type() == AutomationEvent.Type.PLAYER_SIGHTED, "wrong order");
        check("recent by type filters", bus.recent(AutomationEvent.Type.STASH_DISCOVERED, 5).size() == 1, "wrong count");

        check("event data is readable", seen.get(0).getInt("score", 0) == 120, "wrong value");
        check("missing data returns the fallback", seen.get(0).getInt("nope", -1) == -1, "wrong fallback");

        // A failing listener must not stop the other listeners from running.
        List<AutomationEvent> after = new ArrayList<>();
        bus.subscribe(AutomationEvent.Type.MILESTONE, e -> { throw new RuntimeException("boom"); });
        bus.subscribe(AutomationEvent.Type.MILESTONE, after::add);
        bus.publish(AutomationEvent.of(AutomationEvent.Type.MILESTONE, "test"));
        check("a failing listener does not break the others", after.size() == 1, "got " + after.size());
        check("a listener crash is reported as a system error",
            bus.recent(AutomationEvent.Type.SYSTEM_ERROR, 5).size() == 1, "not reported");

        // Global subscribers see everything.
        List<AutomationEvent> all = new ArrayList<>();
        bus.subscribeAll(all::add);
        bus.publish(AutomationEvent.of(AutomationEvent.Type.SYSTEM_ERROR, "test"));
        check("a global subscriber sees every event", all.size() == 1, "got " + all.size());

        bus.clear();
        check("clear empties the history", bus.historySize() == 0, "not empty");
    }
}
