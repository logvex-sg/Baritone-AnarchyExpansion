package dev.basehunt.bhclient;

import dev.basehunt.bhclient.core.sixbt.HomeBook;
import dev.basehunt.bhclient.core.sixbt.HotspotInfo;
import dev.basehunt.bhclient.core.sixbt.KitDeliveryMachine;
import dev.basehunt.bhclient.core.sixbt.KitLoadout;
import dev.basehunt.bhclient.core.sixbt.KitLoadoutRegistry;
import dev.basehunt.bhclient.core.sixbt.KitRequestQueue;
import dev.basehunt.bhclient.core.sixbt.KitRestockPlan;
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
        requestQueue();
        loadouts();
        restock();
        tps();
        hotspot();
        tpaPolicy();
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
        boolean killed = finish.stream().anyMatch(a -> a instanceof KitDeliveryMachine.Action.KillSelf);
        check("kills itself when empty", killed, finish.toString());
        check("finished", machine.state() == KitDeliveryMachine.State.FINISHED, machine.state().name());

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

    // Tpa policy

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
