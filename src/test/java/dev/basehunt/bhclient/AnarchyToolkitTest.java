package dev.basehunt.bhclient;

import dev.basehunt.bhclient.core.logic.BaritoneTaskCatalog;
import dev.basehunt.bhclient.core.logic.BaritoneTaskCatalog.Kind;
import dev.basehunt.bhclient.core.logic.BaritoneTaskCatalog.Request;
import dev.basehunt.bhclient.core.logic.DeathMemory;
import dev.basehunt.bhclient.core.logic.ProximityRadar;

import java.util.List;

/**
 * Headless tests for the anarchy toolkit: the Baritone job catalogue, death memory and the player radar.
 *
 * <p>All three are pure logic over text, positions and timestamps, so the parsing rules, the eviction bounds
 * and the transition classifier are pinned here rather than only being felt on a live server. The point of
 * each is the same: turn a stream of raw facts (a command line, a death, a moving player) into a decision
 * that is worth trusting without a client.
 */
public class AnarchyToolkitTest {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS: " + name); }
        else { failed++; System.out.println("  FAIL: " + name + " -> " + detail); }
    }

    static void section(String title) {
        System.out.println("== " + title + " ==");
    }

    public static void main(String[] args) {
        jobCatalogue();
        jobParsing();
        deathMemory();
        proximityRadar();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    static void jobCatalogue() {
        section("job catalogue");

        Request mine = BaritoneTaskCatalog.parseLine("mine diamond_ore 64");
        check("mine parses the block", mine.valid() && mine.kind() == Kind.MINE && mine.target().equals("diamond_ore"),
            mine.toString());
        check("mine parses the quantity", mine.quantity() == 64, "got " + mine.quantity());

        Request mineReversed = BaritoneTaskCatalog.parseLine("mine 32 iron_ore");
        check("mine tolerates quantity first", mineReversed.valid() && mineReversed.quantity() == 32
            && mineReversed.target().equals("iron_ore"), mineReversed.toString());

        Request mineNoQuantity = BaritoneTaskCatalog.parseLine("mine obsidian");
        check("mine without a quantity is valid", mineNoQuantity.valid() && mineNoQuantity.quantity() == 0,
            mineNoQuantity.toString());

        Request namespaced = BaritoneTaskCatalog.parseLine("mine minecraft:ancient_debris 5");
        check("namespace is stripped", namespaced.target().equals("ancient_debris"), namespaced.target());

        Request gotoPos = BaritoneTaskCatalog.parseLine("goto 100 64 -200");
        check("goto parses coordinates", gotoPos.valid() && gotoPos.kind() == Kind.GOTO
            && gotoPos.x() == 100 && gotoPos.y() == 64 && gotoPos.z() == -200, gotoPos.toString());

        Request follow = BaritoneTaskCatalog.parseLine("follow Steve");
        check("follow parses a name", follow.valid() && follow.kind() == Kind.FOLLOW
            && follow.target().equals("Steve"), follow.toString());

        Request explore = BaritoneTaskCatalog.parseLine("explore 1000 2000");
        check("explore parses a centre", explore.valid() && explore.x() == 1000 && explore.z() == 2000,
            explore.toString());

        Request exploreDefault = BaritoneTaskCatalog.parseLine("explore");
        check("explore with no centre is valid", exploreDefault.valid() && exploreDefault.x() == 0,
            exploreDefault.toString());

        Request farm = BaritoneTaskCatalog.parseLine("farm 32");
        check("farm parses a range", farm.valid() && farm.quantity() == 32, farm.toString());

        Request tunnel = BaritoneTaskCatalog.parseLine("tunnel");
        check("tunnel is a valid job", tunnel.valid() && tunnel.kind() == Kind.TUNNEL, tunnel.toString());

        Request build = BaritoneTaskCatalog.parseLine("build house.schem 10 70 10");
        check("build parses a file and origin", build.valid() && build.target().equals("house.schem")
            && build.x() == 10 && build.y() == 70 && build.z() == 10, build.toString());

        check("a bare stop is valid", BaritoneTaskCatalog.parseLine("stop").kind() == Kind.STOP, "wrong kind");
    }

    static void jobParsing() {
        section("job parsing failures");

        check("an unknown verb is refused", !BaritoneTaskCatalog.parseLine("dance").valid(), "accepted");
        check("an unknown verb carries a reason", !BaritoneTaskCatalog.parseLine("dance").error().isEmpty(),
            "no reason");

        check("mine without a block is refused", !BaritoneTaskCatalog.parseLine("mine").valid(), "accepted");
        check("mine with a non-numeric quantity is refused",
            !BaritoneTaskCatalog.parseLine("mine iron_ore many").valid(), "accepted");

        check("goto without coordinates is refused", !BaritoneTaskCatalog.parseLine("goto 1 2").valid(),
            "accepted");
        check("goto with a non-numeric coordinate is refused",
            !BaritoneTaskCatalog.parseLine("goto 1 2 up").valid(), "accepted");

        check("follow without a name is refused", !BaritoneTaskCatalog.parseLine("follow").valid(), "accepted");
        check("explore with one coordinate is refused", !BaritoneTaskCatalog.parseLine("explore 5").valid(),
            "accepted");
        check("build without an origin is refused",
            !BaritoneTaskCatalog.parseLine("build house.schem 1 2").valid(), "accepted");
        check("an empty line is refused", !BaritoneTaskCatalog.parseLine("").valid(), "accepted");

        Request huge = BaritoneTaskCatalog.parseLine("mine stone 999999999");
        check("an absurd quantity is clamped, not accepted raw",
            huge.valid() && huge.quantity() <= 65_536, "got " + huge.quantity());

        Request outOfRange = BaritoneTaskCatalog.parseLine("goto 999999999 64 0");
        check("an out-of-range coordinate is refused", !outOfRange.valid(), "accepted");

        check("a negative quantity is refused", !BaritoneTaskCatalog.parseLine("mine iron -5").valid()
            || BaritoneTaskCatalog.parseLine("mine iron -5").quantity() >= 0, "negative accepted");

        check("describe summarises a mine job",
            BaritoneTaskCatalog.parseLine("mine gold_ore 8").describe().contains("gold_ore"), "no target");
        check("describe reports an invalid job", BaritoneTaskCatalog.parseLine("dance").describe()
            .startsWith("invalid"), "not marked invalid");
    }

    static void deathMemory() {
        section("death memory");

        DeathMemory memory = new DeathMemory(3);
        check("a fresh memory is empty", memory.size() == 0, "not empty");

        DeathMemory.DeathRecord first = memory.add("srv", "overworld", 0, 64, 0, "", 12, 1000);
        check("a death is stored", memory.size() == 1, "not stored");
        check("a death gets a non-zero id", first.id() > 0, "id " + first.id());
        check("a death starts un-recovered", !first.recovered(), "recovered");

        memory.add("srv", "overworld", 100, 64, 100, "", 5, 2000);
        memory.add("srv", "overworld", 200, 64, 200, "", 5, 3000);

        DeathMemory.DeathRecord nearest = memory.nearestPending("srv", "overworld", 90, 90).orElseThrow();
        check("nearest picks the closest death", nearest.x() == 100 && nearest.z() == 100,
            nearest.coords());

        check("a different dimension is ignored",
            memory.nearestPending("srv", "nether", 0, 0).isEmpty(), "found one in the nether");
        check("a different server is ignored",
            memory.nearestPending("other", "overworld", 0, 0).isEmpty(), "found one on another server");

        // Cap is 3: adding a fourth evicts the oldest (id 1).
        memory.add("srv", "overworld", 300, 64, 300, "", 5, 4000);
        check("the list is bounded by the cap", memory.size() == 3, "size " + memory.size());
        check("the oldest death is evicted", memory.all().stream().noneMatch(r -> r.id() == first.id()),
            "id 1 survived");

        check("marking a death recovered works", memory.markRecovered(2), "not found");
        check("a recovered death is no longer pending",
            memory.nearestPending("srv", "overworld", 0, 0).map(DeathMemory.DeathRecord::id).orElse(-1L) != 2L,
            "still pending");
        check("pendingCount excludes recovered", memory.pendingCount() == 2, "got " + memory.pendingCount());

        check("a wrong id does not mark anything", !memory.markRecovered(999), "marked a ghost");

        DeathMemory rebuilt = new DeathMemory(3);
        rebuilt.replaceAll(memory.all());
        check("replacing preserves the records", rebuilt.size() == 3, "size " + rebuilt.size());
        DeathMemory.DeathRecord next = rebuilt.add("srv", "overworld", 1, 1, 1, "", 0, 5000);
        check("a rebuild continues the id sequence", next.id() > 4, "id " + next.id());

        check("distanceTo is horizontal",
            Math.abs(new DeathMemory.DeathRecord(1, "s", "overworld", 30, 64, 40, 0, "", 0, false)
                .distanceTo(0, 0) - 50.0) < 0.001, "wrong distance");

        check("dimension normalisation strips the namespace",
            DeathMemory.normalizeDimension("minecraft:overworld").equals("overworld"), "not stripped");
    }

    static void proximityRadar() {
        section("proximity radar");

        ProximityRadar radar = new ProximityRadar(8, 4.0);

        check("the first sighting is ENTERED",
            radar.observe("Steve", 0, 64, 100, 100, 1000) == ProximityRadar.Transition.ENTERED, "wrong");
        check("a big step closer is APPROACHING",
            radar.observe("Steve", 0, 64, 50, 50, 1100) == ProximityRadar.Transition.APPROACHING, "wrong");
        check("a big step away is RECEDING",
            radar.observe("Steve", 0, 64, 100, 100, 1200) == ProximityRadar.Transition.RECEDING, "wrong");
        check("a small step is STEADY",
            radar.observe("Steve", 0, 64, 98, 98, 1300) == ProximityRadar.Transition.STEADY, "wrong");

        radar.observe("Alex", 0, 64, 20, 20, 1300);
        check("a second player is tracked", radar.size() == 2, "size " + radar.size());

        List<ProximityRadar.Contact> active = radar.active(1400, 1000);
        check("active returns both players", active.size() == 2, "size " + active.size());
        check("active is sorted nearest first", active.get(0).name().equals("Alex"), active.get(0).name());

        List<String> left = radar.expire(5000, 1000);
        check("stale contacts expire", left.size() == 2, "left " + left.size());
        check("the radar is empty after expiry", radar.size() == 0, "size " + radar.size());

        ProximityRadar capped = new ProximityRadar(2, 4.0);
        capped.observe("a", 0, 0, 0, 0, 1);
        capped.observe("b", 0, 0, 0, 0, 1);
        capped.observe("c", 0, 0, 0, 0, 1);
        check("the contact list is bounded", capped.size() == 2, "size " + capped.size());

        ProximityRadar blank = new ProximityRadar(4, 4.0);
        check("a blank name is ignored",
            blank.observe("", 0, 0, 0, 0, 1) == ProximityRadar.Transition.STEADY, "wrong");
        check("a blank name is not tracked", blank.size() == 0, "size " + blank.size());

        check("summary reports clear when empty", new ProximityRadar(4, 4.0).summary(0, 1000)
            .equals("clear"), "not clear");
    }
}
