package dev.basehunt.bhclient;

import dev.basehunt.bhclient.core.sixbt.ChestKitSearch;
import dev.basehunt.bhclient.core.sixbt.HomeBook;
import dev.basehunt.bhclient.core.sixbt.HotspotInfo;
import dev.basehunt.bhclient.core.sixbt.KitDeliveryMachine;
import dev.basehunt.bhclient.core.sixbt.KitLoadout;
import dev.basehunt.bhclient.core.sixbt.KitLoadoutRegistry;
import dev.basehunt.bhclient.core.sixbt.KitMessages;
import dev.basehunt.bhclient.core.sixbt.KitRequestFlow;
import dev.basehunt.bhclient.core.sixbt.KitRequestPolicy;
import dev.basehunt.bhclient.core.sixbt.KitRequestQueue;
import dev.basehunt.bhclient.core.sixbt.KitRestockPlan;
import dev.basehunt.bhclient.core.sixbt.KitSources;
import dev.basehunt.bhclient.core.sixbt.RequesterPresence;
import dev.basehunt.bhclient.core.sixbt.SixbtChat;
import dev.basehunt.bhclient.core.sixbt.SixbtCommand;
import dev.basehunt.bhclient.core.sixbt.TpaPolicy;
import dev.basehunt.bhclient.core.sixbt.TpsMonitor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Headless tests for the 6b6t feature set: the server command catalogue, the kit-request parser, the kit
 * delivery state machine, the loadout model, the restock comparison, the TPS and hotspot parsers, the tpa
 * policy and the home book.
 *
 * <p>Like the other suites these touch no Minecraft classes. The delivery machine is driven by observations
 * rather than by a live client, and every parser is fed a string, so the whole 6b6t contract is checked in a
 * plain JVM. That is what makes a bot that teleports and kills itself safe to change.
 */
public class SixbtLogicTest {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS: " + name); }
        else { failed++; System.out.println("  FAIL: " + name + " -> " + detail); }
    }

    static void section(String title) {
        System.out.println("== " + title + " ==");
    }

    public static void main(String[] args) {
        commands();
        chatParsing();
        kitRequests();
        deliveryMachine();
        requesterPresence();
        requestQueue();
        loadouts();
        restock();
        tps();
        hotspot();
        tpaPolicy();
        requestPolicy();
        kitMessages();
        requestFlow();
        chestSearch();
        kitSources();
        homeBook();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    // SixbtCommand

    private static void commands() {
        section("6b6t command catalogue");

        check("tpa is known", SixbtCommand.isKnown("tpa"), "not found");
        check("slash is stripped", SixbtCommand.isKnown("/home"), "not found");
        check("unknown command", !SixbtCommand.isKnown("ban"), "found");
        check("tpa is a home command",
            SixbtCommand.categoryOf("tpa") == SixbtCommand.Category.HOMES, "wrong category");
        check("kill is gameplay",
            SixbtCommand.categoryOf("kill") == SixbtCommand.Category.GAMEPLAY, "wrong category");
        check("unknown category", SixbtCommand.categoryOf("nope") == SixbtCommand.Category.UNKNOWN, "wrong");
        check("java address is 6b6t", SixbtCommand.isSixbtServer("play.6b6t.org"), "not recognised");
        check("bedrock address is 6b6t", SixbtCommand.isSixbtServer("br.6b6t.org"), "not recognised");
        check("other address is not 6b6t", !SixbtCommand.isSixbtServer("2b2t.org"), "wrongly recognised");
        check("empty address is not 6b6t", !SixbtCommand.isSixbtServer(""), "wrongly recognised");
        check("first token", SixbtCommand.firstToken("/home base").equals("home"), "wrong token");
    }

    // SixbtChat

    private static void chatParsing() {
        section("6b6t chat parsing");

        SixbtChat.Line vanilla = SixbtChat.parse("<Steve> !kit pvp");
        check("vanilla sender", vanilla.sender().equals("Steve"), vanilla.sender());
        check("vanilla body", vanilla.body().equals("!kit pvp"), vanilla.body());

        SixbtChat.Line ranked = SixbtChat.parse("[VIP] Alex: hello there");
        check("ranked sender", ranked.sender().equals("Alex"), ranked.sender());
        check("ranked body", ranked.body().equals("hello there"), ranked.body());

        SixbtChat.Line multi = SixbtChat.parse("[Elite] [Ultra] Notch: hey");
        check("multi-rank sender", multi.sender().equals("Notch"), multi.sender());

        SixbtChat.Line command = SixbtChat.parse("/home base");
        check("command echo flagged", command.commandEcho(), "not flagged");

        SixbtChat.Line plain = SixbtChat.parse("server restarting soon");
        check("plain line has no sender", !plain.hasSender(), "sender found");

        // A sender written without a rank tag: "Name: body" or "Name » body".
        SixbtChat.Line bare = SixbtChat.parse("Steve: !kit pvp");
        check("bare colon sender", bare.sender().equals("Steve"), bare.sender());
        check("bare colon body", bare.body().equals("!kit pvp"), bare.body());

        SixbtChat.Line arrow = SixbtChat.parse("Steve \u00bb hello there");
        check("arrow sender", arrow.sender().equals("Steve"), arrow.sender());
        check("arrow body", arrow.body().equals("hello there"), arrow.body());

        SixbtChat.Line sentence = SixbtChat.parse("server restarting soon: be careful");
        check("sentence with colon has no sender", !sentence.hasSender(), sentence.sender());

        check("bare colon request found",
            SixbtChat.findRequest("Steve: !kit pvp", List.of("!"), true, List.of("pvp")).isPresent(),
            "not found");
        check("arrow request found",
            SixbtChat.findRequest("Steve \u00bb >lxkit totem", List.of(">lxkit"), true, List.of("totem"))
                .isPresent(), "not found");

        Optional<String> tpa = SixbtChat.findTpaRequest("Steve wants to teleport to you. /tpy Steve /tpn Steve");
        check("tpa requester found", tpa.isPresent() && tpa.get().equals("Steve"), tpa.toString());
        check("no tpa request in chat", SixbtChat.findTpaRequest("<Steve> hi").isEmpty(), "found one");
    }

    // Kit requests

    private static void kitRequests() {
        section("kit request parsing");

        List<String> prefixes = List.of("!", ".kit");
        List<String> kits = List.of("pvp", "travel");

        Optional<SixbtChat.Request> request =
            SixbtChat.findRequest("<Steve> !kit pvp", prefixes, false, kits);
        check("kit request found", request.isPresent(), "not found");
        check("request requester", request.isPresent() && request.get().requester().equals("Steve"),
            request.toString());
        check("request kit", request.isPresent() && request.get().kit().equals("pvp"), request.toString());

        check("dot prefix works",
            SixbtChat.findRequest("<Steve> .kit travel", prefixes, false, kits).isPresent(), "not found");
        check("bare request off by default",
            SixbtChat.findRequest("<Steve> !pvp", prefixes, false, kits).isEmpty(), "found anyway");
        check("bare request on",
            SixbtChat.findRequest("<Steve> !pvp", prefixes, true, kits).isPresent(), "not found");
        check("unknown kit ignored",
            SixbtChat.findRequest("<Steve> !kit diamond", prefixes, false, kits).isEmpty(), "found");
        check("no sender is not a request",
            SixbtChat.findRequest("!kit pvp", prefixes, false, kits).isEmpty(), "found");
        check("command echo is not a request",
            SixbtChat.findRequest("/kit pvp", prefixes, false, kits).isEmpty(), "found");
        check("plain chat is not a request",
            SixbtChat.findRequest("<Steve> hello", prefixes, false, kits).isEmpty(), "found");

        // The default prefix is ">lxkit": ">lxkit pvp" (bare) or ">lxkit kit pvp".
        List<String> lxkit = List.of(">lxkit");
        Optional<SixbtChat.Request> bareLxkit =
            SixbtChat.findRequest("<Steve> >lxkit pvp", lxkit, true, kits);
        check(">lxkit bare request found", bareLxkit.isPresent(), "not found");
        check(">lxkit bare kit", bareLxkit.isPresent() && bareLxkit.get().kit().equals("pvp"),
            bareLxkit.toString());
        check(">lxkit kit keyword",
            SixbtChat.findRequest("<Steve> >lxkit kit travel", lxkit, false, kits).isPresent(), "not found");
        check(">lxkit prefix implies kit so bare works with bare off",
            SixbtChat.findRequest("<Steve> >lxkit pvp", lxkit, false, kits).isPresent(), "not found");
        check(">lxkit unknown kit ignored",
            SixbtChat.findRequest("<Steve> >lxkit diamond", lxkit, true, kits).isEmpty(), "found");
        check(">lxkit with no kit ignored",
            SixbtChat.findRequest("<Steve> >lxkit", lxkit, true, kits).isEmpty(), "found");
        check(">lxkit command echo not a request",
            SixbtChat.findRequest("<Steve> /lxkit pvp", lxkit, true, kits).isEmpty(), "found");
        check(">lxkit totem request",
            SixbtChat.findRequest("<Steve> >lxkit totem", List.of(">lxkit"), true,
                List.of("pvp", "travel", "totem")).isPresent(), "not found");
    }

    // Delivery machine

    private static void deliveryMachine() {
        section("kit delivery state machine");

        KitDeliveryMachine machine = new KitDeliveryMachine();
        check("starts idle", machine.state() == KitDeliveryMachine.State.IDLE, machine.state().name());

        List<KitDeliveryMachine.Action> start = machine.start("Steve", "pvp");
        check("start sends tpa", start.size() == 1
            && start.get(0) instanceof KitDeliveryMachine.Action.SendTpa, start.toString());
        check("waiting for arrival", machine.state() == KitDeliveryMachine.State.WAITING_ARRIVAL,
            machine.state().name());
        check("busy", machine.busy(), "not busy");

        check("idle tick does nothing",
            machine.tick(KitDeliveryMachine.Observation.idle()).isEmpty(), "acted");

        check("arrival moves to delivering",
            machine.tick(new KitDeliveryMachine.Observation(true, false, false, 3, false)).isEmpty(), "acted");
        check("delivering", machine.state() == KitDeliveryMachine.State.DELIVERING, machine.state().name());

        List<KitDeliveryMachine.Action> drop =
            machine.tick(new KitDeliveryMachine.Observation(false, false, false, 2, false));
        check("drops one item", drop.size() == 1
            && drop.get(0) instanceof KitDeliveryMachine.Action.DropItem, drop.toString());

        machine.tick(new KitDeliveryMachine.Observation(false, false, false, 1, false));
        List<KitDeliveryMachine.Action> finish =
            machine.tick(new KitDeliveryMachine.Observation(false, false, false, 0, false));
        check("empty drops enter the confirm window",
            machine.state() == KitDeliveryMachine.State.CONFIRMING, machine.state().name());
        check("no kill during the confirm window",
            finish.stream().noneMatch(a -> a instanceof KitDeliveryMachine.Action.KillSelf), finish.toString());

        // Hold position for the confirmation window, then hand over and reset.
        machine.setConfirmTicks(3);
        boolean killed = false;
        for (int i = 0; i < 5 && !killed; i++) {
            List<KitDeliveryMachine.Action> actions =
                machine.tick(new KitDeliveryMachine.Observation(false, false, false, 0, false));
            killed = actions.stream().anyMatch(a -> a instanceof KitDeliveryMachine.Action.KillSelf);
        }
        check("kills itself after the confirm window", killed, "no kill");
        check("finished", machine.state() == KitDeliveryMachine.State.FINISHED, machine.state().name());

        // A requester who leaves during the confirm window fails the delivery rather than silently succeeding.
        KitDeliveryMachine leftDuringConfirm = new KitDeliveryMachine();
        leftDuringConfirm.setConfirmTicks(100);
        leftDuringConfirm.start("Steve", "pvp");
        leftDuringConfirm.tick(new KitDeliveryMachine.Observation(true, false, false, 1, false));
        leftDuringConfirm.tick(new KitDeliveryMachine.Observation(false, false, false, 0, false));
        leftDuringConfirm.tick(new KitDeliveryMachine.Observation(false, false, false, 0, true));
        check("requester gone during confirm fails",
            leftDuringConfirm.state() == KitDeliveryMachine.State.FAILED, leftDuringConfirm.state().name());

        // Refusal path.
        KitDeliveryMachine refused = new KitDeliveryMachine();
        refused.start("Alex", "pvp");
        List<KitDeliveryMachine.Action> refusal =
            refused.tick(new KitDeliveryMachine.Observation(false, true, false, 0, false));
        check("refusal cancels tpa", refusal.stream()
            .anyMatch(a -> a instanceof KitDeliveryMachine.Action.CancelTpa), refusal.toString());
        check("refusal fails", refused.state() == KitDeliveryMachine.State.FAILED, refused.state().name());

        // Timeout path.
        KitDeliveryMachine timeout = new KitDeliveryMachine();
        timeout.start("Alex", "pvp");
        timeout.tick(new KitDeliveryMachine.Observation(false, false, true, 0, false));
        check("timeout fails", timeout.state() == KitDeliveryMachine.State.FAILED, timeout.state().name());

        // Requester gone while delivering.
        KitDeliveryMachine gone = new KitDeliveryMachine();
        gone.start("Alex", "pvp");
        gone.tick(new KitDeliveryMachine.Observation(true, false, false, 3, false));
        gone.tick(new KitDeliveryMachine.Observation(false, false, false, 3, true));
        check("requester gone fails", gone.state() == KitDeliveryMachine.State.FAILED, gone.state().name());

        // Cancel.
        KitDeliveryMachine cancelled = new KitDeliveryMachine();
        cancelled.start("Alex", "pvp");
        cancelled.cancel("stopped");
        check("cancel fails", cancelled.state() == KitDeliveryMachine.State.FAILED, cancelled.state().name());
        cancelled.reset();
        check("reset returns to idle", cancelled.state() == KitDeliveryMachine.State.IDLE, cancelled.state().name());
    }

    // Requester presence

    private static void requesterPresence() {
        section("requester presence");

        // The bug this guards: before the teleport lands the requester is out of range, and treating that as
        // "left" aborted every delivery a few seconds after it began.
        RequesterPresence p = new RequesterPresence(3);
        check("not seen yet is not gone", !p.observe(false), "gone before being seen");
        check("still not gone after many unseen ticks", !p.observe(false) && !p.observe(false)
            && !p.observe(false) && !p.observe(false), "gave up before ever seeing the requester");
        check("has not been seen", !p.hasBeenSeen(), "claims to have seen");

        // Seen, then gone for longer than the window: that is a real departure.
        check("seen resets the missing counter", !p.observe(true), "gone while visible");
        check("has been seen", p.hasBeenSeen(), "not marked seen");
        check("briefly missing is not gone", !p.observe(false) && !p.observe(false), "gave up too early");
        boolean goneNow = p.observe(false) | p.observe(false);
        check("gone after the window", goneNow, "never reported gone");

        // Coming back clears the gone state.
        check("reappearing is not gone", !p.observe(true), "still gone after returning");
        check("missing counter reset on return", !p.observe(false), "counted a stale miss");

        // Reset for a new delivery forgets the previous one.
        p.reset();
        check("reset forgets the sighting", !p.hasBeenSeen(), "still seen after reset");
        check("unseen after reset is not gone", !p.observe(false), "gone after reset");

        // A zero window is clamped so a one-tick flicker cannot fail a delivery.
        RequesterPresence clamped = new RequesterPresence(0);
        clamped.observe(true);
        check("zero window is clamped to at least one tick",
            !clamped.observe(false) && clamped.observe(false), "clamp failed");
    }

    // Request queue

    private static void requestQueue() {
        section("kit request queue");

        KitRequestQueue queue = new KitRequestQueue(2, 60_000);
        check("offer accepted", queue.offer(new KitRequestQueue.Pending("Steve", "pvp", 0), 0) == null, "refused");
        check("duplicate refused",
            "already queued".equals(queue.offer(new KitRequestQueue.Pending("Steve", "pvp", 0), 0)), "accepted");
        check("second accepted", queue.offer(new KitRequestQueue.Pending("Alex", "travel", 0), 0) == null,
            "refused");
        check("capacity enforced", queue.offer(new KitRequestQueue.Pending("Notch", "pvp", 0), 0) != null,
            "accepted beyond capacity");
        check("size is two", queue.size() == 2, String.valueOf(queue.size()));

        KitRequestQueue.Pending first = queue.poll();
        check("oldest served first", first != null && first.requester().equals("Steve"), String.valueOf(first));
        queue.served("Steve", 1000);
        check("cooldown blocks repeat",
            queue.offer(new KitRequestQueue.Pending("Steve", "pvp", 1000), 2000) != null, "accepted");
        check("cooldown elapses",
            queue.offer(new KitRequestQueue.Pending("Steve", "pvp", 1000), 100_000) == null, "refused");
    }

    // Loadouts

    private static void loadouts() {
        section("kit loadouts");

        KitLoadout loadout = KitLoadout.empty("pvp")
            .with(new KitLoadout.ItemSpec("diamond_sword", 1))
            .with(new KitLoadout.ItemSpec("golden_apple", 8))
            .with(new KitLoadout.ItemSpec("golden_apple", 8));

        check("merge on add", loadout.distinctItems() == 2, String.valueOf(loadout.distinctItems()));
        check("total items", loadout.totalItems() == 17, String.valueOf(loadout.totalItems()));
        check("namespace added",
            loadout.items().get(0).itemId().equals("minecraft:diamond_sword"),
            loadout.items().get(0).itemId());

        KitLoadout trimmed = loadout.without("golden_apple");
        check("remove works", trimmed.distinctItems() == 1, String.valueOf(trimmed.distinctItems()));

        KitLoadout.ItemSpec parsed = KitLoadout.parseSpec("minecraft:arrow x32");
        check("parse id xN", parsed != null && parsed.count() == 32, String.valueOf(parsed));
        check("parse malformed is null", KitLoadout.parseSpec("") == null, "parsed");

        KitLoadoutRegistry registry = new KitLoadoutRegistry();
        registry.put(loadout);
        check("registry find", registry.find("PVP") != null, "not found");
        check("registry names", registry.names().contains("pvp"), registry.names().toString());
        registry.remove("pvp");
        check("registry remove", registry.isEmpty(), "still present");

        KitLoadoutRegistry defaults = KitLoadoutRegistry.defaults();
        check("defaults include pvp", defaults.contains("pvp"), "missing");
        check("defaults include travel", defaults.contains("travel"), "missing");
    }

    // Restock

    private static void restock() {
        section("restock comparison");

        KitLoadout loadout = KitLoadout.of("pvp", List.of(
            new KitLoadout.ItemSpec("diamond_sword", 1),
            new KitLoadout.ItemSpec("golden_apple", 16)));

        Map<String, Integer> held = Map.of("minecraft:diamond_sword", 1, "minecraft:golden_apple", 10);
        KitRestockPlan.Result result = KitRestockPlan.compare(loadout, held);
        check("not ready when short", !result.isReady(), "ready");
        check("missing count", result.missingItems() == 6, String.valueOf(result.missingItems()));
        check("short item identified", result.missing().get(0).itemId().equals("minecraft:golden_apple"),
            result.missing().get(0).itemId());

        Map<String, Integer> full = Map.of("diamond_sword", 1, "golden_apple", 20);
        KitRestockPlan.Result ready = KitRestockPlan.compare(loadout, full);
        check("ready when stocked", ready.isReady(), "short");
        check("surplus detected", ready.surplus().size() == 1, String.valueOf(ready.surplus().size()));

        check("counts build map",
            KitRestockPlan.counts(loadout.items()).get("minecraft:golden_apple") == 16, "wrong count");
    }

    // TPS

    private static void tps() {
        section("tps parsing");

        Optional<TpsMonitor.Reading> reading =
            TpsMonitor.parse("Server TPS: 19.8 | Your TPS: 20.0, 19.5, 18.2", 1000);
        check("tps parsed", reading.isPresent(), "not parsed");
        check("server tps", reading.isPresent() && reading.get().serverTps() == 19.8,
            reading.map(TpsMonitor.Reading::describe).orElse("none"));
        check("personal tps", reading.isPresent() && reading.get().personalTps() > 0,
            reading.map(TpsMonitor.Reading::describe).orElse("none"));
        check("health good", reading.isPresent() && reading.get().health().equals("good"),
            reading.map(TpsMonitor.Reading::health).orElse("none"));

        check("no numbers is empty", TpsMonitor.parse("server is lagging", 0).isEmpty(), "parsed");
        check("blank is empty", TpsMonitor.parse("", 0).isEmpty(), "parsed");

        TpsMonitor monitor = new TpsMonitor();
        monitor.record(new TpsMonitor.Reading(20, 0, 0));
        monitor.record(new TpsMonitor.Reading(10, 0, 0));
        check("average", Math.abs(monitor.averageServerTps() - 15.0) < 0.01,
            String.valueOf(monitor.averageServerTps()));
        check("lowest", monitor.lowestServerTps() == 10, String.valueOf(monitor.lowestServerTps()));
        check("latest", monitor.latest().serverTps() == 10, "wrong latest");

        TpsMonitor.Reading low = new TpsMonitor.Reading(8, 0, 0);
        check("low health bad", low.health().equals("bad"), low.health());
    }

    // Hotspot

    private static void hotspot() {
        section("hotspot parsing");

        Optional<HotspotInfo.Snapshot> live =
            HotspotInfo.parse("Hotspot 'Spawn Fight' created at 100, 64, -200", "overworld", 5000);
        check("hotspot parsed", live.isPresent(), "not parsed");
        check("hotspot active", live.isPresent() && live.get().active(), "not active");
        check("hotspot x", live.isPresent() && live.get().x() == 100, String.valueOf(live.orElse(null)));
        check("hotspot z", live.isPresent() && live.get().z() == -200, String.valueOf(live.orElse(null)));

        Optional<HotspotInfo.Snapshot> ended = HotspotInfo.parse("The hotspot has ended.", "overworld", 6000);
        check("ended detected", ended.isPresent() && !ended.get().active(), String.valueOf(ended.orElse(null)));

        check("non-hotspot line ignored", HotspotInfo.parse("hello world", "overworld", 0).isEmpty(), "parsed");
    }

    // Request policy (whitelist)

    private static void requestPolicy() {
        section("kit request policy (whitelist)");

        // Closed by default: nobody is served and nothing is blocked.
        KitRequestPolicy closed = KitRequestPolicy.denyAll();
        check("closed serves nobody", closed.servesNobody(), "serves someone");
        check("closed ignores stranger",
            closed.decide("Steve") == KitRequestPolicy.Decision.NOT_WHITELISTED, "allowed");
        check("closed status", closed.statusLine().contains("closed"), closed.statusLine());

        KitRequestPolicy policy =
            new KitRequestPolicy(List.of("Steve", "Alex"), List.of("Griefer"), false);
        check("whitelisted allowed", policy.decide("steve") == KitRequestPolicy.Decision.ALLOW, "not allowed");
        check("whitelisted allowed (case)", policy.isAllowed("ALEX"), "not allowed");
        check("blocked refused",
            policy.decide("GRIEFER") == KitRequestPolicy.Decision.BLOCKED, "not blocked");
        check("stranger not whitelisted",
            policy.decide("Random") == KitRequestPolicy.Decision.NOT_WHITELISTED, "wrong decision");
        check("null not whitelisted",
            policy.decide(null) == KitRequestPolicy.Decision.NOT_WHITELISTED, "wrong decision");
        check("blank not whitelisted",
            policy.decide("  ") == KitRequestPolicy.Decision.NOT_WHITELISTED, "wrong decision");
        check("not serves nobody", !policy.servesNobody(), "serves nobody");

        // Block-list beats allow-anyone, so a griefer is shut out even on an open bot.
        KitRequestPolicy open = new KitRequestPolicy(List.of(), List.of("Griefer"), true);
        check("open allows stranger",
            open.decide("Random") == KitRequestPolicy.Decision.ALLOW, "not allowed");
        check("block-list beats allow-anyone",
            open.decide("Griefer") == KitRequestPolicy.Decision.BLOCKED, "not blocked");
        check("open serves somebody", !open.servesNobody(), "serves nobody");

        // Blank entries are ignored rather than becoming a match on the empty name.
        KitRequestPolicy blanks = new KitRequestPolicy(List.of("", "  ", "Steve"), List.of(), false);
        check("blank allow entry ignored", blanks.decide("") != KitRequestPolicy.Decision.ALLOW, "allowed blank");
        check("real entry kept", blanks.isAllowed("Steve"), "not allowed");
    }

    private static void tpaPolicy() {
        section("tpa policy");

        TpaPolicy policy = new TpaPolicy(List.of("Steve", "Alex"), List.of("Griefer"), false);
        check("allowed accepts", policy.decide("steve") == TpaPolicy.Decision.ACCEPT, "not accepted");
        check("blocked declines", policy.decide("GRIEFER") == TpaPolicy.Decision.DECLINE, "not declined");
        check("stranger ignored", policy.decide("Random") == TpaPolicy.Decision.IGNORE, "not ignored");
        check("null ignored", policy.decide(null) == TpaPolicy.Decision.IGNORE, "not ignored");

        TpaPolicy open = new TpaPolicy(List.of(), List.of(), true);
        check("accept anyone", open.decide("Random") == TpaPolicy.Decision.ACCEPT, "not accepted");

        TpaPolicy deny = TpaPolicy.denyAll();
        check("deny all ignores", deny.decide("Steve") == TpaPolicy.Decision.IGNORE, "accepted");
    }

    // Kit messages

    private static void kitMessages() {
        section("kit messages");

        KitMessages messages = new KitMessages();
        check("grabbing default", messages.template(KitMessages.Kind.GRABBING).contains("Grabbing"),
            messages.template(KitMessages.Kind.GRABBING));
        check("not whitelisted default",
            messages.template(KitMessages.Kind.NOT_WHITELISTED).contains("Not whitelisted"),
            messages.template(KitMessages.Kind.NOT_WHITELISTED));
        check("unknown kit default", messages.template(KitMessages.Kind.UNKNOWN_KIT).contains("doesnt exist"),
            messages.template(KitMessages.Kind.UNKNOWN_KIT));
        check("cooldown default", messages.template(KitMessages.Kind.COOLDOWN).contains("cooldown"),
            messages.template(KitMessages.Kind.COOLDOWN));
        check("queue full default", messages.template(KitMessages.Kind.QUEUE_FULL).contains("max"),
            messages.template(KitMessages.Kind.QUEUE_FULL));
        check("queued default", messages.template(KitMessages.Kind.QUEUED).contains("place"),
            messages.template(KitMessages.Kind.QUEUED));
        check("delivered default", messages.template(KitMessages.Kind.DELIVERED).contains("delivered"),
            messages.template(KitMessages.Kind.DELIVERED));

        check("whisper is a /msg", messages.whisper(KitMessages.Kind.DELIVERED, "Steve", Map.of())
            .equals("/msg Steve Kit delivered."), messages.whisper(KitMessages.Kind.DELIVERED, "Steve", Map.of()));
        check("whisper with no player is empty",
            messages.whisper(KitMessages.Kind.DELIVERED, "", Map.of()).isEmpty(), "not empty");

        check("cooldown renders minutes",
            messages.render(KitMessages.Kind.COOLDOWN, Map.of("minutes", "10"))
                .equals("Error, 10 minute cooldown active."),
            messages.render(KitMessages.Kind.COOLDOWN, Map.of("minutes", "10")));
        check("queue full renders max",
            messages.render(KitMessages.Kind.QUEUE_FULL, Map.of("max", "3"))
                .equals("Error, too many kits (max 3)."),
            messages.render(KitMessages.Kind.QUEUE_FULL, Map.of("max", "3")));
        check("queued renders place",
            messages.render(KitMessages.Kind.QUEUED, Map.of("place", "2"))
                .equals("You are in the queue, please wait. You are in place 2."),
            messages.render(KitMessages.Kind.QUEUED, Map.of("place", "2")));
        check("unknown placeholder left alone",
            messages.render(KitMessages.Kind.DELIVERED, Map.of("nope", "x")).equals("Kit delivered."),
            messages.render(KitMessages.Kind.DELIVERED, Map.of("nope", "x")));

        // Overrides
        KitMessages custom = KitMessages.fromLines(List.of(
            "delivered:Enjoy your {kit}, {player}!",
            "cooldown:wait {seconds}s"));
        check("override delivered",
            custom.render(KitMessages.Kind.DELIVERED, Map.of("kit", "totem", "player", "Steve"))
                .equals("Enjoy your totem, Steve!"),
            custom.render(KitMessages.Kind.DELIVERED, Map.of("kit", "totem", "player", "Steve")));
        check("override cooldown", custom.template(KitMessages.Kind.COOLDOWN).equals("wait {seconds}s"),
            custom.template(KitMessages.Kind.COOLDOWN));
        check("untouched kind keeps default",
            custom.template(KitMessages.Kind.GRABBING).equals(messages.template(KitMessages.Kind.GRABBING)),
            custom.template(KitMessages.Kind.GRABBING));
        check("blank line ignored", KitMessages.fromLines(List.of("", "  ", "notakind:x"))
            .template(KitMessages.Kind.DELIVERED).equals("Kit delivered."), "changed");
        check("parseKind case-insensitive", KitMessages.parseKind("DELIVERED") == KitMessages.Kind.DELIVERED,
            "not parsed");
        check("parseKind unknown is null", KitMessages.parseKind("bogus") == null, "parsed");
        check("describe covers every kind", messages.describe().size() == KitMessages.Kind.values().length,
            String.valueOf(messages.describe().size()));
    }

    // Request flow

    private static void requestFlow() {
        section("kit request flow");

        KitRequestFlow.Reply denied = KitRequestFlow.evaluate(KitRequestPolicy.Decision.NOT_WHITELISTED,
            true, null, 0, 0, 3);
        check("stranger is told not whitelisted", denied.kind() == KitMessages.Kind.NOT_WHITELISTED,
            denied.kind().toString());

        KitRequestFlow.Reply blocked = KitRequestFlow.evaluate(KitRequestPolicy.Decision.BLOCKED,
            true, null, 0, 0, 3);
        check("blocked is told not whitelisted", blocked.kind() == KitMessages.Kind.NOT_WHITELISTED,
            blocked.kind().toString());

        KitRequestFlow.Reply unknown = KitRequestFlow.evaluate(KitRequestPolicy.Decision.ALLOW,
            false, null, 0, 0, 3);
        check("unknown kit reported", unknown.kind() == KitMessages.Kind.UNKNOWN_KIT, unknown.kind().toString());

        KitRequestFlow.Reply full = KitRequestFlow.evaluate(KitRequestPolicy.Decision.ALLOW,
            true, "full", 0, 0, 3);
        check("full queue reported", full.kind() == KitMessages.Kind.QUEUE_FULL, full.kind().toString());
        check("full carries max", full.vars().get("max").equals("3"), full.vars().toString());

        KitRequestFlow.Reply cooldown = KitRequestFlow.evaluate(KitRequestPolicy.Decision.ALLOW,
            true, "cooldown", 0, 42, 3);
        check("cooldown reported", cooldown.kind() == KitMessages.Kind.COOLDOWN, cooldown.kind().toString());
        check("cooldown rounds up to minutes", cooldown.vars().get("minutes").equals("1"),
            cooldown.vars().toString());
        KitRequestFlow.Reply longCooldown = KitRequestFlow.evaluate(KitRequestPolicy.Decision.ALLOW,
            true, "cooldown", 0, 600, 3);
        check("long cooldown is 10 minutes", longCooldown.vars().get("minutes").equals("10"),
            longCooldown.vars().toString());

        KitRequestFlow.Reply queued = KitRequestFlow.evaluate(KitRequestPolicy.Decision.ALLOW,
            true, null, 2, 0, 3);
        check("accepted is queued", queued.kind() == KitMessages.Kind.QUEUED, queued.kind().toString());
        check("queued carries place", queued.vars().get("place").equals("2"), queued.vars().toString());

        KitRequestFlow.Reply next = KitRequestFlow.evaluate(KitRequestPolicy.Decision.ALLOW,
            true, null, 1, 0, 3);
        check("next in line is told grabbing", next.kind() == KitMessages.Kind.GRABBING,
            next.kind().toString());
        KitRequestFlow.Reply first = KitRequestFlow.evaluate(KitRequestPolicy.Decision.ALLOW,
            true, null, 0, 0, 3);
        check("first in line is told grabbing", first.kind() == KitMessages.Kind.GRABBING,
            first.kind().toString());

        // A request from a stranger for a kit that does not exist: access wins, so nothing is leaked.
        KitRequestFlow.Reply strangerBadKit = KitRequestFlow.evaluate(
            KitRequestPolicy.Decision.NOT_WHITELISTED, false, null, 0, 0, 3);
        check("access beats unknown kit", strangerBadKit.kind() == KitMessages.Kind.NOT_WHITELISTED,
            strangerBadKit.kind().toString());

        check("zero position becomes place 1", KitRequestFlow.queued(0).vars().get("place").equals("1"),
            KitRequestFlow.queued(0).vars().toString());
    }

    // Chest kit search

    private static void chestSearch() {
        section("chest kit search");

        KitLoadout totem = KitLoadout.of("totem", List.of(
            new KitLoadout.ItemSpec("minecraft:totem_of_undying", 2),
            new KitLoadout.ItemSpec("minecraft:golden_apple", 8)));

        ChestKitSearch.Container chest = new ChestKitSearch.Container("chest", 10, 64, 20, List.of(
            new KitLoadout.ItemSpec("minecraft:totem_of_undying", 2),
            new KitLoadout.ItemSpec("minecraft:golden_apple", 8),
            new KitLoadout.ItemSpec("minecraft:dirt", 64)));
        ChestKitSearch.Container barrel = new ChestKitSearch.Container("barrel", 12, 64, 22, List.of(
            new KitLoadout.ItemSpec("minecraft:totem_of_undying", 1)));

        ChestKitSearch.Result complete = ChestKitSearch.find(List.of(barrel, chest), totem);
        check("complete kit found", complete.complete(), complete.summary());
        check("best container is the chest", complete.label().equals("chest"), complete.label());
        check("coordinates kept", complete.coords().equals("10, 64, 20"), complete.coords());

        ChestKitSearch.Result partial = ChestKitSearch.find(List.of(barrel), totem);
        check("partial kit not complete", !partial.complete(), partial.summary());
        check("partial found the totem", partial.hasSource(), "no source");
        check("partial missing the apples", partial.missing().contains("minecraft:golden_apple x8"),
            partial.missing().toString());
        check("partial missing one totem", partial.missing().contains("minecraft:totem_of_undying"),
            partial.missing().toString());

        ChestKitSearch.Result none = ChestKitSearch.find(
            List.of(new ChestKitSearch.Container("dirt", 0, 0, 0,
                List.of(new KitLoadout.ItemSpec("minecraft:dirt", 64)))), totem);
        check("no source found", !none.hasSource(), "found one");
        check("none reports the shortfall", none.missing().size() == 2, none.missing().toString());

        check("empty containers list", !ChestKitSearch.find(List.of(), totem).hasSource(), "found");
        check("null loadout safe", !ChestKitSearch.find(List.of(chest), null).hasSource(), "found");
        check("empty loadout safe", !ChestKitSearch.find(List.of(chest), KitLoadout.empty("x")).hasSource(),
            "found");

        check("countOf sums stacks", ChestKitSearch.countOf(chest.contents(), "minecraft:totem_of_undying") == 2,
            String.valueOf(ChestKitSearch.countOf(chest.contents(), "minecraft:totem_of_undying")));
        check("countOf unknown is zero", ChestKitSearch.countOf(chest.contents(), "minecraft:diamond") == 0,
            "non-zero");
        check("container label defaults", new ChestKitSearch.Container("", 0, 0, 0, List.of()).label()
            .equals("container"), "not defaulted");
    }

    // Name-based kit sources (shulker renamed after the kit)

    private static void kitSources() {
        section("kit name sources");

        check("plain shulker id", KitSources.isShulkerId("minecraft:shulker_box"), "not a shulker");
        check("coloured shulker id", KitSources.isShulkerId("minecraft:red_shulker_box"), "not a shulker");
        check("chest is not a shulker", !KitSources.isShulkerId("minecraft:chest"), "wrongly a shulker");

        check("normalize lowercase", KitSources.normalize("Totem").equals("totem"), KitSources.normalize("Totem"));
        check("normalize colour code", KitSources.normalize("\u00a76Totem").equals("totem"),
            KitSources.normalize("\u00a76Totem"));
        check("normalize underscores", KitSources.normalize("totem_shulker_box").equals("totem"),
            KitSources.normalize("totem_shulker_box"));
        check("normalize shulker suffix", KitSources.normalize("totem shulker").equals("totem"),
            KitSources.normalize("totem shulker"));
        check("normalize shulker box suffix", KitSources.normalize("totem Shulker Box").equals("totem"),
            KitSources.normalize("totem Shulker Box"));
        check("normalize kit prefix", KitSources.normalize("kit totem").equals("totem"),
            KitSources.normalize("kit totem"));
        check("normalize blank", KitSources.normalize("  ").isEmpty(), KitSources.normalize("  "));

        check("exact match", KitSources.matches("totem", "totem"), "no match");
        check("case-insensitive match", KitSources.matches("totem", "Totem"), "no match");
        check("colour code match", KitSources.matches("totem", "\u00a7cTotem"), "no match");
        check("shulker suffix match", KitSources.matches("totem", "totem shulker box"), "no match");
        check("numbered match", KitSources.matches("totem", "totem 2"), "no match");
        check("separator match", KitSources.matches("totem", "totem-2"), "no match");
        check("near-miss rejected", !KitSources.matches("totem", "totems"), "wrongly matched");
        check("different kit rejected", !KitSources.matches("totem", "pvp"), "wrongly matched");
        check("empty kit rejected", !KitSources.matches("", "totem"), "wrongly matched");
        check("empty name rejected", !KitSources.matches("totem", ""), "wrongly matched");

        check("username check", KitSources.isUsername("Steve"), "rejected");
        check("username rejects spaces", !KitSources.isUsername("Steve Smith"), "accepted");
        check("username rejects sentence", !KitSources.isUsername("server restarting"), "accepted");

        List<KitSources.NamedStack> stacks = List.of(
            new KitSources.NamedStack("minecraft:chest", "junk", 1),
            new KitSources.NamedStack("minecraft:shulker_box", "totem", 1),
            new KitSources.NamedStack("minecraft:red_shulker_box", "pvp shulker", 1));

        check("find shulker by name",
            KitSources.findShulkerByName(stacks, "totem").isPresent(), "not found");
        check("find shulker normalises",
            KitSources.findShulkerByName(stacks, "pvp").isPresent(), "not found");
        check("named non-shulker not a shulker kit",
            KitSources.findShulkerByName(List.of(new KitSources.NamedStack("minecraft:chest", "totem", 1)),
                "totem").isEmpty(), "found a chest");
        check("missing name is empty", KitSources.findShulkerByName(stacks, "build").isEmpty(), "found");
    }

    // Home book

    private static void homeBook() {
        section("home book");

        HomeBook book = new HomeBook(2);
        check("empty book", book.size() == 0, String.valueOf(book.size()));
        check("put first", book.put(new HomeBook.Home("base", "overworld", 100, 64, 200, 0)), "refused");
        check("put second", book.put(new HomeBook.Home("farm", "overworld", -50, 70, 300, 0)), "refused");
        check("full", book.isFull(), "not full");
        check("new home refused when full",
            !book.put(new HomeBook.Home("third", "overworld", 0, 0, 0, 0)), "accepted");
        check("replace existing when full",
            book.put(new HomeBook.Home("base", "overworld", 1, 2, 3, 0)), "refused");
        check("free slots", book.freeSlots() == 0, String.valueOf(book.freeSlots()));

        check("rename", book.rename("base", "home"), "rename failed");
        check("renamed lookup", book.find("home") != null, "not found");
        check("old name gone", book.find("base") == null, "still present");

        HomeBook.Home nearest = book.nearest(0, 0);
        check("nearest home", nearest != null && nearest.name().equals("home"), String.valueOf(nearest));

        check("remove", book.remove("farm"), "remove failed");
        check("size after remove", book.size() == 1, String.valueOf(book.size()));
    }
}
