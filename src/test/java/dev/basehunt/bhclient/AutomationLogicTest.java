package dev.basehunt.bhclient;

import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.Priority;
import dev.basehunt.bhclient.core.chat.ChatCommand;
import dev.basehunt.bhclient.core.chat.ChatCommandRegistry;
import dev.basehunt.bhclient.core.chat.ChatContext;
import dev.basehunt.bhclient.core.chat.ChatPermissions;
import dev.basehunt.bhclient.core.chat.ChatRouter;
import dev.basehunt.bhclient.core.kit.Kit;
import dev.basehunt.bhclient.core.kit.KitRegistry;
import dev.basehunt.bhclient.core.kit.KitResponses;
import dev.basehunt.bhclient.core.logic.InventorySimulator;
import dev.basehunt.bhclient.core.logic.ItemFilter;
import dev.basehunt.bhclient.core.logic.ProtectionGuard;
import dev.basehunt.bhclient.core.logic.ProtectionRules;
import dev.basehunt.bhclient.core.logic.QuantityPlanner;
import dev.basehunt.bhclient.core.logic.SortPlanner;
import dev.basehunt.bhclient.core.logic.StorageCategory;
import dev.basehunt.bhclient.core.rule.Condition;
import dev.basehunt.bhclient.core.rule.Rule;
import dev.basehunt.bhclient.core.rule.RuleSet;
import dev.basehunt.bhclient.core.task.Task;
import dev.basehunt.bhclient.core.task.TaskQueue;
import dev.basehunt.bhclient.core.task.TaskState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Headless tests for the automation rewrite: the task queue, exact-quantity transfers, protection, the chat
 * prefix router, kits and the rule engine. Like the other suites these touch no Minecraft classes, so the
 * scheduling, splitting, permission and edge-trigger rules are all checked in a plain JVM.
 */
public class AutomationLogicTest {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS: " + name); }
        else { failed++; System.out.println("  FAIL: " + name + " -> " + detail); }
    }

    static void section(String title) {
        System.out.println("== " + title + " ==");
    }

    public static void main(String[] args) {
        taskLifecycle();
        taskQueueOrdering();
        taskQueueRetryAndCancel();
        itemFilterMatching();
        quantityPlanner();
        quantitySimulation();
        protectionGuard();
        chatRouterParsing();
        chatPermissions();
        chatDispatch();
        kitRegistry();
        kitResponses();
        conditions();
        rules();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    // Tasks

    private static void taskLifecycle() {
        section("task lifecycle");

        Task task = new Task(1, "test", "desc", Task.Category.WITHDRAW, Priority.INVENTORY_MANAGEMENT);
        check("a new task is idle", task.state() == TaskState.IDLE, "got " + task.state());
        check("idle is not active", !task.state().isActive(), "active");
        check("idle is not terminal", !task.isTerminal(), "terminal");

        task.start();
        check("start moves to running", task.state() == TaskState.RUNNING, "got " + task.state());
        check("start records an attempt", task.attempts() == 1, "got " + task.attempts());

        task.request(64);
        task.advance(32);
        check("progress tracks the request", task.attained() == 32, "got " + task.attained());
        check("a partial task is not satisfied", !task.satisfied(), "satisfied");

        task.advance(64);
        check("progress is capped at the request", task.attained() == 64, "got " + task.attained());
        check("a full task is satisfied", task.satisfied(), "not satisfied");

        task.complete();
        check("a satisfied task completes", task.state() == TaskState.COMPLETED, "got " + task.state());
        check("completion stamps the finish time", task.finishedAt() > 0, "no time");

        Task partial = new Task(2, "partial", "", Task.Category.WITHDRAW, Priority.INVENTORY_MANAGEMENT);
        partial.start();
        partial.request(64);
        partial.advance(20);
        partial.complete();
        check("an under-filled task is partial", partial.state() == TaskState.PARTIAL, "got " + partial.state());
        check("a partial task carries a reason", !partial.error().isEmpty(), "no reason");

        Task failed = new Task(3, "fail", "", Task.Category.DEPOSIT, Priority.INVENTORY_MANAGEMENT);
        failed.start();
        failed.fail("boom");
        check("fail records the reason", failed.error().equals("boom"), "got " + failed.error());
        check("a terminal task ignores a late state write", ignoreLateWrite(failed), "resurrected");
    }

    private static boolean ignoreLateWrite(Task task) {
        task.setState(TaskState.RUNNING);
        return task.state() == TaskState.FAILED;
    }

    private static void taskQueueOrdering() {
        section("task queue ordering");

        TaskQueue queue = new TaskQueue();
        Task background = queue.create("scan", "", Task.Category.SCAN, Priority.BACKGROUND_DISCOVERY);
        Task critical = queue.create("heal", "", Task.Category.CUSTOM, Priority.CRITICAL_SURVIVAL);
        Task inventory = queue.create("sort", "", Task.Category.SORT, Priority.INVENTORY_MANAGEMENT);

        check("queue size counts pending tasks", queue.queueSize() == 3, "got " + queue.queueSize());
        check("the next task is the highest priority",
            queue.next() == critical, "got " + queue.next().name());
        check("the ordering is priority then submission",
            queue.queued().get(0) == critical && queue.queued().get(2) == background, "wrong order");

        Task first = queue.poll();
        check("poll returns the highest priority", first == critical, "got " + first.name());
        check("poll removes it from the queue", queue.queueSize() == 2, "got " + queue.queueSize());
        check("a busy queue does not poll again", queue.poll() == null, "polled twice");

        first.complete();
        queue.finishActive();
        check("finishing frees the queue", queue.active() == null, "still active");
        check("the finished task is in history", queue.history().get(0) == critical, "not in history");

        Task next = queue.poll();
        check("the next poll is the next priority", next == inventory, "got " + next.name());
    }

    private static void taskQueueRetryAndCancel() {
        section("task queue retry and cancel");

        TaskQueue queue = new TaskQueue();
        Task task = queue.create("flaky", "", Task.Category.WITHDRAW, Priority.INVENTORY_MANAGEMENT);
        task.maxAttempts(3);

        Task active = queue.poll();
        active.fail("transient");
        Task settled = queue.settleActive();
        check("a retryable failure is re-queued", settled == null && queue.queueSize() == 1, "not re-queued");
        check("a re-queued task is queued again", task.state() == TaskState.QUEUED, "got " + task.state());

        Task retried = queue.poll();
        check("the retry runs again", retried == task, "wrong task");
        check("the retry counts as another attempt", task.attempts() == 2, "got " + task.attempts());

        task.fail("again");
        queue.settleActive();
        queue.poll().fail("again");
        queue.settleActive();
        check("a task that runs out of attempts stays failed", task.state() == TaskState.FAILED,
            "got " + task.state());

        // Cancel by id, both queued and active.
        TaskQueue cancelQueue = new TaskQueue();
        Task queued = cancelQueue.create("queued", "", Task.Category.SORT, Priority.INVENTORY_MANAGEMENT);
        check("cancelling a queued task works", cancelQueue.cancel(queued.id()), "not cancelled");
        check("a cancelled task is terminal", queued.state() == TaskState.CANCELLED, "got " + queued.state());

        Task running = cancelQueue.create("running", "", Task.Category.SORT, Priority.INVENTORY_MANAGEMENT);
        cancelQueue.poll();
        check("cancelling the active task works", cancelQueue.cancel(running.id()), "not cancelled");
        check("the active slot is freed on cancel", cancelQueue.active() == null, "still active");

        // Duplicate protection.
        TaskQueue dupQueue = new TaskQueue();
        dupQueue.create("same", "", Task.Category.DEPOSIT, Priority.INVENTORY_MANAGEMENT);
        check("an identical pending task is detected", dupQueue.hasActiveLike("same", Task.Category.DEPOSIT), "missed");
        check("a different category is not a duplicate",
            !dupQueue.hasActiveLike("same", Task.Category.WITHDRAW), "false positive");

        int cancelled = cancelQueue.cancelAll("disconnect");
        check("cancelAll reports what it cancelled", cancelled >= 0, "negative");
    }

    // Item filter

    private static void itemFilterMatching() {
        section("item filter");

        check("an exact id matches", ItemFilter.item("minecraft:ender_pearl")
            .matches("minecraft:ender_pearl", "Ender Pearl", StorageCategory.END), "no match");
        check("a bare id matches a full id", ItemFilter.item("ender_pearl")
            .matches("minecraft:ender_pearl", "Ender Pearl", StorageCategory.END), "no match");
        check("a term matches a word in the id", ItemFilter.term("pearl")
            .matches("minecraft:ender_pearl", "Ender Pearl", StorageCategory.END), "no match");
        check("a term matches the display name", ItemFilter.term("Ender")
            .matches("minecraft:ender_pearl", "Ender Pearl", StorageCategory.END), "no match");
        check("a term is not a raw substring of the id", !ItemFilter.term("nder_pear")
            .matches("minecraft:ender_pearl", "Ender Pearl", StorageCategory.END), "matched");
        check("a category filter matches by category", ItemFilter.category(StorageCategory.ORES)
            .matches("minecraft:diamond", "Diamond", StorageCategory.ORES), "no match");
        check("a category filter rejects another category", !ItemFilter.category(StorageCategory.ORES)
            .matches("minecraft:diamond", "Diamond", StorageCategory.FOOD), "matched");
        check("an any filter matches everything", ItemFilter.any()
            .matches("minecraft:anything", "Anything", StorageCategory.MISC), "no match");

        Set<ItemFilter> parsed = ItemFilter.parseList("ender_pearl, ores, totem_of_undying");
        check("parseList recognises categories", parsed.contains(ItemFilter.category(StorageCategory.ORES)),
            "no category");
        check("parseList keeps terms", parsed.contains(ItemFilter.term("ender_pearl")), "no term");
        check("parseList keeps every entry", parsed.size() == 3, "got " + parsed.size());
    }

    // Quantity planner

    private static void quantityPlanner() {
        section("quantity planner");

        List<QuantityPlanner.Source> sources = List.of(
            new QuantityPlanner.Source(10, "minecraft:ender_pearl", "Ender Pearl", StorageCategory.END, 64, 64)
        );
        List<QuantityPlanner.Destination> dests = List.of(
            new QuantityPlanner.Destination(40, null, 0, 64),
            new QuantityPlanner.Destination(41, null, 0, 64)
        );

        // A whole stack fits: a plain swap.
        QuantityPlanner.Plan whole = QuantityPlanner.plan(sources, dests, ItemFilter.item("ender_pearl"),
            64, true, Set.of());
        check("a whole stack is one move", whole.moves().size() == 1, "got " + whole.moves().size());
        check("a whole stack is a swap", whole.moves().get(0).kind() == SortPlanner.Kind.SWAP, "not a swap");
        check("a whole stack is complete", whole.complete(), "incomplete");

        // An exact partial: 20 out of 64 becomes one TRANSFER of 20.
        QuantityPlanner.Plan partial = QuantityPlanner.plan(sources, dests, ItemFilter.item("ender_pearl"),
            20, true, Set.of());
        check("a partial request is one move", partial.moves().size() == 1, "got " + partial.moves().size());
        check("a partial request is a transfer", partial.moves().get(0).kind() == SortPlanner.Kind.TRANSFER,
            "not a transfer");
        check("a partial request moves exactly the amount", partial.moves().get(0).amount() == 20,
            "got " + partial.moves().get(0).amount());
        check("a partial request is complete", partial.complete(), "incomplete");

        // A request larger than the source, with partials allowed.
        QuantityPlanner.Plan short_ = QuantityPlanner.plan(sources, dests, ItemFilter.item("ender_pearl"),
            200, true, Set.of());
        check("an over-large request attains only what exists", short_.attainable() == 64,
            "got " + short_.attainable());
        check("an over-large request is not complete", !short_.complete(), "complete");
        check("an over-large request warns", !short_.warnings().isEmpty(), "no warning");

        // A request larger than the source, partials refused.
        QuantityPlanner.Plan refused = QuantityPlanner.plan(sources, dests, ItemFilter.item("ender_pearl"),
            200, false, Set.of());
        check("a refused partial produces no moves", refused.isEmpty(), "produced moves");

        // A protected item is never moved.
        QuantityPlanner.Plan protectedPlan = QuantityPlanner.plan(sources, dests,
            ItemFilter.item("ender_pearl"), 64, true, ProtectionRules.parseList("minecraft:ender_pearl"));
        check("a protected item is not moved", protectedPlan.isEmpty(), "moved");
        QuantityPlanner.Plan protectedBare = QuantityPlanner.plan(sources, dests,
            ItemFilter.item("minecraft:ender_pearl"), 64, true, ProtectionRules.parseList("ender_pearl"));
        check("a bare-id protection also refuses", protectedBare.isEmpty(), "moved");

        // The request spreads across several destination slots.
        List<QuantityPlanner.Source> bigSource = List.of(
            new QuantityPlanner.Source(10, "minecraft:cobblestone", "Cobblestone", StorageCategory.BUILDING, 192, 64)
        );
        List<QuantityPlanner.Destination> manyDests = List.of(
            new QuantityPlanner.Destination(40, null, 0, 64),
            new QuantityPlanner.Destination(41, null, 0, 64),
            new QuantityPlanner.Destination(42, null, 0, 64)
        );
        QuantityPlanner.Plan spread = QuantityPlanner.plan(bigSource, manyDests,
            ItemFilter.item("cobblestone"), 192, true, Set.of());
        check("a request spanning slots produces several moves", spread.moves().size() >= 2,
            "got " + spread.moves().size());
        check("a request spanning slots is complete", spread.complete(), "incomplete");

        check("availability is summed", QuantityPlanner.available(sources, ItemFilter.item("ender_pearl"),
            Set.of()) == 64, "wrong total");
        check("room is detected", QuantityPlanner.hasRoom(dests), "no room");
        check("a full destination has no room", !QuantityPlanner.hasRoom(List.of(
            new QuantityPlanner.Destination(40, "minecraft:stone", 64, 64))), "reported room");
    }

    private static void quantitySimulation() {
        section("quantity simulation");

        // Simulate a partial transfer of 20 out of a stack of 64 into an empty slot and verify the counts.
        List<QuantityPlanner.Source> sources = List.of(
            new QuantityPlanner.Source(1, "minecraft:ender_pearl", "Ender Pearl", StorageCategory.END, 64, 64)
        );
        List<QuantityPlanner.Destination> dests = List.of(new QuantityPlanner.Destination(2, null, 0, 64));

        QuantityPlanner.Plan plan = QuantityPlanner.plan(sources, dests, ItemFilter.item("ender_pearl"),
            20, true, Set.of());

        InventorySimulator.Slot[] initial = new InventorySimulator.Slot[] {
            new InventorySimulator.Slot(0, "minecraft:ender_pearl", "Ender Pearl", 64),
            new InventorySimulator.Slot(1, null, null, 0)
        };

        // The planner works in screen-slot-id space; map 1 -> index 0, 2 -> index 1 for the simulation.
        List<SortPlanner.Move> simMoves = new ArrayList<>();
        for (SortPlanner.Move move : plan.moves()) {
            simMoves.add(new SortPlanner.Move(move.kind(), move.fromSlot() - 1, move.toSlot() - 1, move.amount()));
        }

        List<InventorySimulator.Slot> result = InventorySimulator.apply(simMoves, List.of(initial), 64);
        check("the simulation applies cleanly", result != null, "threw");

        if (result != null) {
            check("the source keeps the remainder", slotCount(result, 0) == 44,
                "got " + slotCount(result, 0));
            check("the destination gets exactly the amount", slotCount(result, 1) == 20,
                "got " + slotCount(result, 1));
            check("no items are lost", slotCount(result, 0) + slotCount(result, 1) == 64, "lost items");
        }
    }

    private static int slotCount(List<InventorySimulator.Slot> slots, int index) {
        for (InventorySimulator.Slot slot : slots) if (slot.index() == index) return slot.count();
        return 0;
    }

    // Protection

    private static void protectionGuard() {
        section("protection guard");

        ProtectionGuard guard = ProtectionGuard.of(9, Set.of(), Set.of("minecraft:elytra"), true, 3);
        check("a protected id is stored namespace-stripped", guard.protectedItems().contains("elytra"),
            "got " + guard.protectedItems());

        check("a hotbar slot is protected", guard.isProtected(4), "not protected");
        check("the selected slot is protected", guard.isProtected(3), "not protected");
        check("a main-inventory slot is not protected", !guard.isProtected(20), "protected");
        check("a protected item is refused", guard.check(-1, 20, "minecraft:elytra") != null, "allowed");
        check("moving out of the hotbar is refused", guard.check(4, 20, "minecraft:stone") != null, "allowed");
        check("moving into the hotbar is refused", guard.check(20, 4, "minecraft:stone") != null, "allowed");
        check("a container-to-main move is allowed", guard.check(-1, 20, "minecraft:stone") == null, "refused");

        // Only the first N hotbar slots are reserved when configured that way.
        ProtectionGuard narrow = ProtectionGuard.of(3, Set.of(), Set.of(), true, -1);
        check("the reserved hotbar width is respected", narrow.isProtected(2), "not protected");
        check("slots past the reserved width are free", !narrow.isProtected(5), "protected");

        // Filtering a move list drops the refused ones.
        List<SortPlanner.Move> moves = List.of(
            new SortPlanner.Move(SortPlanner.Kind.SWAP, 40, 20, 0),
            new SortPlanner.Move(SortPlanner.Kind.SWAP, 40, 4, 0)
        );
        ProtectionGuard.FilterResult result = guard.filter(moves, List.of("minecraft:stone", "minecraft:stone"));
        check("filtering keeps the safe move", result.allowed().size() == 1, "got " + result.allowed().size());
        check("filtering reports the refused move", result.anyRefused(), "not reported");
    }

    // Chat

    private static void chatRouterParsing() {
        section("chat router");

        List<String> prefixes = List.of("!", ".bh");

        check("a prefixed line parses", ChatRouter.parse("!help", prefixes, ChatRouter.Origin.LOCAL_PLAYER) != null,
            "no parse");
        check("a line without a prefix is ignored",
            ChatRouter.parse("hello", prefixes, ChatRouter.Origin.LOCAL_PLAYER) == null, "parsed");
        check("a remote player's line is never a command",
            ChatRouter.parse("!help", prefixes, ChatRouter.Origin.REMOTE_PLAYER) == null, "parsed");
        check("the addon's own output is never a command",
            ChatRouter.parse("!help", prefixes, ChatRouter.Origin.ADDON) == null, "parsed");

        ChatRouter.Parsed parsed = ChatRouter.parse("!withdraw ender_pearl 64", prefixes,
            ChatRouter.Origin.LOCAL_PLAYER);
        check("the command name is parsed", parsed != null && parsed.name().equals("withdraw"), "wrong name");
        check("the first argument is parsed", parsed != null && parsed.arg(0).equals("ender_pearl"), "wrong arg");
        check("the second argument is parsed", parsed != null && parsed.arg(1).equals("64"), "wrong arg");

        ChatRouter.Parsed longest = ChatRouter.parse(".bh help", prefixes, ChatRouter.Origin.LOCAL_PLAYER);
        check("the longest prefix wins", longest != null && longest.prefix().equals(".bh"),
            "got " + (longest == null ? "null" : longest.prefix()));

        check("a bare prefix is not a command",
            ChatRouter.parse("!", prefixes, ChatRouter.Origin.LOCAL_PLAYER) == null, "parsed");
        check("hasPrefix detects a prefix", ChatRouter.hasPrefix("!x", prefixes), "missed");
    }

    private static void chatPermissions() {
        section("chat permissions");

        ChatPermissions readOnly = ChatPermissions.readOnlyOnly();
        check("a read-only command is allowed", readOnly.allows("status", ChatPermissions.Level.READ_ONLY), "refused");
        check("a movement command is refused", !readOnly.allows("withdraw", ChatPermissions.Level.MOVEMENT),
            "allowed");
        check("a control command is refused", !readOnly.allows("stop", ChatPermissions.Level.CONTROL), "allowed");

        ChatPermissions allowWithdraw = ChatPermissions.of(Set.of("withdraw"), false);
        check("an allow-listed movement command is allowed",
            allowWithdraw.allows("withdraw", ChatPermissions.Level.MOVEMENT), "refused");
        check("an unlisted control command is still refused",
            !allowWithdraw.allows("stop", ChatPermissions.Level.CONTROL), "allowed");

        ChatPermissions all = ChatPermissions.of(Set.of(), true);
        check("allow-all permits everything", all.allows("stop", ChatPermissions.Level.CONTROL), "refused");

        ChatPermissions wildcard = ChatPermissions.of(Set.of("*"), false);
        check("a wildcard permits everything", wildcard.allows("withdraw", ChatPermissions.Level.MOVEMENT),
            "refused");

        check("builtin levels classify withdraw as movement",
            ChatPermissions.levelOf("withdraw") == ChatPermissions.Level.MOVEMENT, "wrong level");
        check("builtin levels classify status as read-only",
            ChatPermissions.levelOf("status") == ChatPermissions.Level.READ_ONLY, "wrong level");
        check("an unknown command defaults to control",
            ChatPermissions.levelOf("mystery") == ChatPermissions.Level.CONTROL, "wrong level");
    }

    private static void chatDispatch() {
        section("chat dispatch");

        ChatCommandRegistry registry = new ChatCommandRegistry();
        List<String> output = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        registry.register(new ChatCommand("status", "status", "show status", ChatPermissions.Level.READ_ONLY),
            context -> context.info("all good"));
        registry.register(new ChatCommand("stop", "stop", "stop everything", ChatPermissions.Level.CONTROL),
            context -> context.info("stopped"));

        registry.setPermissions(ChatPermissions.readOnlyOnly());

        ChatContext statusContext = context("status", output, errors);
        String result = registry.dispatch(statusContext);
        check("a read-only command runs", result.equals("ok"), "got " + result);
        check("its output is written", output.contains("all good"), "no output");

        ChatContext stopContext = context("stop", output, errors);
        String denied = registry.dispatch(stopContext);
        check("a control command is denied", denied.equals("denied"), "got " + denied);
        check("a denial is reported as an error", !errors.isEmpty(), "no error");
        check("a denied command does not run", !output.contains("stopped"), "ran anyway");

        ChatContext unknown = context("mystery", output, errors);
        check("an unknown command is rejected", registry.dispatch(unknown).equals("unknown"), "not rejected");

        check("the command list is populated", registry.names().size() == 2, "got " + registry.names().size());
        check("help lists the commands", registry.help("!").size() == 3, "wrong help size");

        // Aliases resolve to the same command.
        registry.register(new ChatCommand("kits", "kits", "list kits", ChatPermissions.Level.READ_ONLY,
            new String[] {"kit"}), context -> context.info("kits here"));
        check("an alias resolves", registry.find("kit") != null, "not found");
    }

    private static ChatContext context(String name, List<String> out, List<String> err) {
        ChatRouter.Parsed parsed = new ChatRouter.Parsed("!", name, List.of(), name);
        return new ChatContext(parsed, "!", out::add, err::add);
    }

    // Kits

    private static void kitRegistry() {
        section("kit registry");

        KitRegistry registry = new KitRegistry();
        registry.register(Kit.of("travel", "/kit travel", 60));

        check("a kit is found by name", registry.find("travel") != null, "not found");
        check("an unknown kit is null", registry.find("nope") == null, "found");
        check("a never-claimed kit is ready", registry.ready("travel", 1000), "not ready");

        registry.recordClaim("travel", 1000);
        check("a just-claimed kit is on cooldown", !registry.ready("travel", 1000), "ready");
        check("the cooldown remaining is correct", registry.cooldownRemaining("travel", 1000) == 60000,
            "got " + registry.cooldownRemaining("travel", 1000));
        check("halfway through the cooldown it is still cooling",
            registry.cooldownRemaining("travel", 31000) == 30000,
            "got " + registry.cooldownRemaining("travel", 31000));
        check("after the cooldown it is ready", registry.ready("travel", 61000), "not ready");
        check("the claim is counted", registry.claims("travel") == 1, "got " + registry.claims("travel"));

        check("readyKits lists ready kits", registry.readyKits(61000).size() == 1, "wrong count");
        check("status summarises the kits", registry.status(61000).contains("ready"), "wrong status");
        check("describe is per kit", registry.describe(61000).size() == 1, "wrong size");
    }

    private static void kitResponses() {
        section("kit responses");

        check("a cooldown reply is recognised",
            KitResponses.classify("You must wait 30s before claiming this kit") == KitResponses.Verdict.COOLDOWN,
            "not recognised");
        check("a denial is recognised",
            KitResponses.classify("You do not have permission to use this kit") == KitResponses.Verdict.DENIED,
            "not recognised");
        check("a grant is recognised",
            KitResponses.classify("You have been given the travel kit") == KitResponses.Verdict.GRANTED,
            "not recognised");
        check("ordinary chat is neutral",
            KitResponses.classify("hello everyone") == KitResponses.Verdict.NEUTRAL, "not neutral");
        check("a cooldown resolves the claim", KitResponses.resolves(KitResponses.Verdict.COOLDOWN), "unresolved");
        check("neutral does not resolve", !KitResponses.resolves(KitResponses.Verdict.NEUTRAL), "resolved");
    }

    // Rules

    private static void conditions() {
        section("rule conditions");

        Condition.FactSource facts = name -> switch (name) {
            case "health" -> 6.0;
            case "free_slots" -> 0.0;
            default -> null;
        };

        check("a less-than condition fires", Condition.parse("health < 8").test(facts), "no fire");
        check("a less-than condition does not fire above", !Condition.parse("health < 5").test(facts), "fired");
        check("a less-or-equal condition fires", Condition.parse("health <= 6").test(facts), "no fire");
        check("a greater-than condition fires", Condition.parse("health > 5").test(facts), "no fire");
        check("an equality condition fires", Condition.parse("health == 6").test(facts), "no fire");
        check("a not-equal condition fires", Condition.parse("health != 7").test(facts), "no fire");
        check("a missing fact is false", !Condition.parse("nope < 8").test(facts), "fired");
        check("a malformed condition is inert", !Condition.parse("health 8").test(facts), "fired");
        check("a malformed condition is flagged", Condition.parse("health 8").malformed(), "not flagged");
        check("a blank condition is malformed", Condition.parse("").malformed(), "not flagged");
    }

    private static void rules() {
        section("rule engine");

        double[] health = {10.0};
        Condition.FactSource facts = name -> "health".equals(name) ? health[0] : null;

        int[] fired = {0};
        RuleSet set = new RuleSet();
        set.add(new Rule.Builder("low-health")
            .when("health < 8")
            .cooldown(10)
            .action((rule, tick) -> { fired[0]++; return "heal"; })
            .build());

        set.evaluateTick(facts, 1);
        check("a rule does not fire while the condition is false", fired[0] == 0, "fired " + fired[0]);

        health[0] = 5.0;
        set.evaluateTick(facts, 2);
        check("a rule fires when the condition becomes true", fired[0] == 1, "fired " + fired[0]);

        set.evaluateTick(facts, 3);
        check("a rule does not re-fire while the condition stays true", fired[0] == 1, "fired " + fired[0]);

        health[0] = 10.0;
        set.evaluateTick(facts, 4);
        health[0] = 5.0;
        set.evaluateTick(facts, 5);
        check("a rule is held by its cooldown", fired[0] == 1, "fired " + fired[0]);

        health[0] = 10.0;
        set.evaluateTick(facts, 20);
        health[0] = 5.0;
        set.evaluateTick(facts, 21);
        check("a rule fires again after the cooldown", fired[0] == 2, "fired " + fired[0]);

        // A failing action is recorded, not thrown.
        RuleSet failing = new RuleSet();
        failing.add(new Rule.Builder("boom")
            .when("health < 8")
            .action((rule, tick) -> { throw new RuntimeException("bad"); })
            .build());
        failing.evaluateTick(facts, 1);
        check("a failing action is recorded", failing.recent(5).get(0).startsWith("!"), "not recorded");

        // Event-driven rules fire on their trigger only.
        RuleSet eventDriven = new RuleSet();
        int[] eventFired = {0};
        eventDriven.add(new Rule.Builder("on-sighting")
            .trigger(AutomationEvent.Type.PLAYER_SIGHTED)
            .action((rule, tick) -> { eventFired[0]++; return null; })
            .build());

        eventDriven.evaluateTick(facts, 1);
        check("an event-driven rule does not fire on tick", eventFired[0] == 0, "fired");
        eventDriven.evaluateEvent(AutomationEvent.of(AutomationEvent.Type.PLAYER_SIGHTED, "test"), facts, 2);
        check("an event-driven rule fires on its event", eventFired[0] == 1, "not fired");
        eventDriven.evaluateEvent(AutomationEvent.of(AutomationEvent.Type.STASH_DISCOVERED, "test"), facts, 3);
        check("an event-driven rule ignores other events", eventFired[0] == 1, "fired");

        check("the rule set reports its size", set.status().contains("1 rule"), "wrong status");
    }
}
