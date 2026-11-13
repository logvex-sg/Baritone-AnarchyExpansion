package dev.basehunt.bhclient;

import dev.basehunt.bhclient.core.logic.ObjectivePlanner;
import dev.basehunt.bhclient.core.logic.SafeSpotFinder;
import dev.basehunt.bhclient.core.logic.ThreatAssessment;

import java.util.List;

/**
 * Headless tests for the anarchy-survival additions: threat scoring, safe-spot ranking and the objective
 * queue.
 *
 * <p>All three are pure logic over numbers and positions, so the weighting, the thresholds, the merge and
 * eviction rules and the ordering are pinned here rather than only being felt during a fight or a stash run.
 * The property each one has to keep is the same: be monotonic and bounded, so a bigger danger or a nearer,
 * richer objective always ranks accordingly and no single input turns a quiet moment into a false alarm.
 */
public class AnarchySurvivalTest {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS: " + name); }
        else { failed++; System.out.println("  FAIL: " + name + " -> " + detail); }
    }

    static void section(String title) {
        System.out.println("== " + title + " ==");
    }

    public static void main(String[] args) {
        threatScoring();
        threatLevels();
        safeSpots();
        objectiveQueue();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    static void threatScoring() {
        section("threat scoring");

        ThreatAssessment.Params params = ThreatAssessment.Params.defaults();
        ThreatAssessment.Profile bare = new ThreatAssessment.Profile(0, false, false, false);
        ThreatAssessment.Profile geared = new ThreatAssessment.Profile(20, true, true, false);

        int farBare = ThreatAssessment.score(60.0, 0.0, bare, params);
        int nearBare = ThreatAssessment.score(5.0, 0.0, bare, params);
        check("closer is more threatening", nearBare > farBare, farBare + " vs " + nearBare);

        int still = ThreatAssessment.score(20.0, 0.0, bare, params);
        int closing = ThreatAssessment.score(20.0, 5.0, bare, params);
        check("approaching is more threatening than still", closing > still, still + " vs " + closing);

        int receding = ThreatAssessment.score(20.0, -5.0, bare, params);
        check("receding is no worse than still", receding <= still, receding + " vs " + still);

        int farGeared = ThreatAssessment.score(60.0, 0.0, geared, params);
        check("gear raises a distant player above a bare one", farGeared > farBare,
            farBare + " vs " + farGeared);

        int maxed = ThreatAssessment.score(0.0, 50.0, geared, params);
        check("the score is capped at 100", maxed == 100, "" + maxed);

        int empty = ThreatAssessment.score(5000.0, 0.0, bare, params);
        check("a distant bare player scores low", empty <= 20, "" + empty);

        check("a null profile is treated as unknown",
            ThreatAssessment.score(20.0, 0.0, null, params) == still, "diverged");

        int totem = ThreatAssessment.score(20.0, 0.0,
            new ThreatAssessment.Profile(20, true, true, false), params);
        int noTotem = ThreatAssessment.score(20.0, 0.0,
            new ThreatAssessment.Profile(20, true, false, false), params);
        check("a totem raises the score", totem > noTotem, noTotem + " vs " + totem);
    }

    static void threatLevels() {
        section("threat levels");

        ThreatAssessment.Params params = ThreatAssessment.Params.defaults();
        check("a quiet contact is calm", ThreatAssessment.level(5, params) == ThreatAssessment.Level.CALM,
            "" + ThreatAssessment.level(5, params));
        check("a moderate contact is wary",
            ThreatAssessment.level(30, params) == ThreatAssessment.Level.WARY,
            "" + ThreatAssessment.level(30, params));
        check("a strong contact is danger",
            ThreatAssessment.level(60, params) == ThreatAssessment.Level.DANGER,
            "" + ThreatAssessment.level(60, params));
        check("an overwhelming contact is flee",
            ThreatAssessment.level(95, params) == ThreatAssessment.Level.FLEE,
            "" + ThreatAssessment.level(95, params));

        check("levels are ordered", ThreatAssessment.Level.CALM.ordinal()
            < ThreatAssessment.Level.FLEE.ordinal(), "ordering");

        check("danger and flee alarm", ThreatAssessment.isAlarming(ThreatAssessment.Level.DANGER)
            && ThreatAssessment.isAlarming(ThreatAssessment.Level.FLEE), "isAlarming");
        check("calm and wary do not alarm", !ThreatAssessment.isAlarming(ThreatAssessment.Level.CALM)
            && !ThreatAssessment.isAlarming(ThreatAssessment.Level.WARY), "isAlarming");

        ThreatAssessment.Threat threat = ThreatAssessment.assess("Steve", 12.0, 2.0,
            new ThreatAssessment.Profile(20, true, true, false), params);
        check("assess describes a threat", threat.describe().contains("Steve"), threat.describe());
        check("assess keeps the primitive facts", threat.distance() == 12.0 && threat.name().equals("Steve"),
            "facts");
    }

    static void safeSpots() {
        section("safe spots");

        SafeSpotFinder.Spot open = new SafeSpotFinder.Spot(0, 64, 0, 0, false, false, false, false, 0, 15);
        SafeSpotFinder.Spot roofed = new SafeSpotFinder.Spot(1, 64, 1, 0, true, false, false, false, 0, 8);
        SafeSpotFinder.Spot enclosed = new SafeSpotFinder.Spot(2, 64, 2, 4, true, false, false, false, 0, 4);
        SafeSpotFinder.Spot lava = new SafeSpotFinder.Spot(3, 64, 3, 4, true, true, false, false, 0, 4);

        check("cover scores above open", SafeSpotFinder.score(enclosed) > SafeSpotFinder.score(open),
            SafeSpotFinder.score(enclosed) + " vs " + SafeSpotFinder.score(open));
        check("a roof beats no roof", SafeSpotFinder.score(roofed) > SafeSpotFinder.score(open),
            SafeSpotFinder.score(roofed) + " vs " + SafeSpotFinder.score(open));
        check("lava is disqualified", SafeSpotFinder.score(lava) == 0, "" + SafeSpotFinder.score(lava));

        SafeSpotFinder.Spot fire = new SafeSpotFinder.Spot(4, 64, 4, 4, true, false, true, false, 0, 4);
        check("fire is disqualified", SafeSpotFinder.score(fire) == 0, "" + SafeSpotFinder.score(fire));

        SafeSpotFinder.Spot dark = new SafeSpotFinder.Spot(5, 64, 5, 2, true, false, false, false, 0, 0);
        SafeSpotFinder.Spot lit = new SafeSpotFinder.Spot(6, 64, 6, 2, true, false, false, false, 0, 15);
        check("darker scores above brighter", SafeSpotFinder.score(dark) > SafeSpotFinder.score(lit),
            SafeSpotFinder.score(dark) + " vs " + SafeSpotFinder.score(lit));

        SafeSpotFinder.Spot drop = new SafeSpotFinder.Spot(7, 64, 7, 2, true, false, false, false, 12, 4);
        check("a long drop is penalised", SafeSpotFinder.score(drop) < SafeSpotFinder.score(dark),
            SafeSpotFinder.score(drop) + " vs " + SafeSpotFinder.score(dark));

        SafeSpotFinder.Spot water = new SafeSpotFinder.Spot(8, 64, 8, 0, true, false, false, true, 0, 4);
        check("water is a small plus", SafeSpotFinder.score(water) > SafeSpotFinder.score(roofed),
            SafeSpotFinder.score(water) + " vs " + SafeSpotFinder.score(roofed));

        check("scores are bounded to 100",
            SafeSpotFinder.score(enclosed) <= 100, "" + SafeSpotFinder.score(enclosed));

        List<SafeSpotFinder.Ranked> ranked = SafeSpotFinder.rank(List.of(open, lava, enclosed, roofed));
        check("ranking drops the hazard", ranked.size() == 3, "size " + ranked.size());
        check("the enclosed spot ranks first", ranked.get(0).spot().equals(enclosed),
            ranked.get(0).reason());
        check("the worst ranked spot is the open one",
            ranked.get(ranked.size() - 1).spot().equals(open), ranked.get(ranked.size() - 1).reason());

        check("best of an empty list is null", SafeSpotFinder.best(List.of()) == null, "not null");
        check("best returns the top spot", SafeSpotFinder.best(List.of(open, enclosed)).spot().equals(enclosed),
            "wrong spot");
    }

    static void objectiveQueue() {
        section("objective queue");

        ObjectivePlanner planner = new ObjectivePlanner(8);
        ObjectivePlanner.Objective drop = planner.add(ObjectivePlanner.Kind.DROP_PICKUP, "loot", 0, 64, 0, 1000, 16);
        check("an objective is added", planner.size() == 1, "" + planner.size());

        planner.add(ObjectivePlanner.Kind.DROP_PICKUP, "loot again", 4, 64, 4, 2000, 16);
        check("a nearby objective of the same kind is merged", planner.size() == 1, "" + planner.size());
        check("the merge refreshes the position", planner.get(drop.id()).x() == 4, "not refreshed");

        planner.add(ObjectivePlanner.Kind.DROP_PICKUP, "far loot", 400, 64, 0, 2000, 16);
        check("a distant objective of the same kind is separate", planner.size() == 2, "" + planner.size());

        ObjectivePlanner.Objective stash = planner.add(ObjectivePlanner.Kind.STASH_REVISIT, "stash", 10, 64, 10, 3000, 16);
        check("a different kind is not merged", planner.size() == 3, "" + planner.size());

        // Near the stash and a merged drop, the drop is both closer-worth more, so it is chosen first.
        long now = 4000;
        ObjectivePlanner.Ranked next = planner.next(8, 64, 8, now);
        check("the best-ranked objective is chosen first",
            next.objective().kind() == ObjectivePlanner.Kind.DROP_PICKUP,
            next.objective().label() + " priority " + next.priority());

        // A fresh objective near the player beats a stale one in the same place.
        ObjectivePlanner fresh = new ObjectivePlanner(8);
        ObjectivePlanner.Objective oldObs = fresh.add(ObjectivePlanner.Kind.CUSTOM, "old", 0, 64, 0, 0, 16);
        ObjectivePlanner.Objective newObs = fresh.add(ObjectivePlanner.Kind.CUSTOM, "new", 1, 64, 1, 600000, 1);
        ObjectivePlanner.Ranked freshNext = fresh.next(0, 64, 0, 600000);
        check("a fresh objective beats a stale one", freshNext.objective().equals(newObs),
            freshNext.objective().label());

        // Distance ranks two equal objectives.
        ObjectivePlanner byDistance = new ObjectivePlanner(8);
        ObjectivePlanner.Objective near = byDistance.add(ObjectivePlanner.Kind.CUSTOM, "near", 5, 64, 0, 1000, 1);
        ObjectivePlanner.Objective far = byDistance.add(ObjectivePlanner.Kind.CUSTOM, "far", 200, 64, 0, 1000, 1);
        ObjectivePlanner.Ranked nearest = byDistance.next(0, 64, 0, 1000);
        check("the nearer of two equal objectives wins", nearest.objective().equals(near),
            nearest.objective().label());

        // Completion.
        check("completing an objective removes it", planner.complete(stash.id()) && planner.get(stash.id()) == null,
            "not removed");
        check("completing a missing objective is false", !planner.complete(9999), "reported true");

        // Boundedness: the cap is respected no matter how many are added.
        ObjectivePlanner bounded = new ObjectivePlanner(4);
        for (int i = 0; i < 50; i++) {
            bounded.add(ObjectivePlanner.Kind.CUSTOM, "o" + i, i * 1000, 64, 0, 1000 + i, 1);
        }
        check("the queue is bounded by its cap", bounded.size() == 4, "" + bounded.size());

        // Restoring from a loaded list rebuilds the id counter past the highest id.
        ObjectivePlanner restored = new ObjectivePlanner(8);
        restored.replaceAll(List.of(new ObjectivePlanner.Objective(42, ObjectivePlanner.Kind.CUSTOM, "x", 0, 0, 0, 0)));
        ObjectivePlanner.Objective afterRestore = restored.add(ObjectivePlanner.Kind.CUSTOM, "y", 500, 0, 0, 1, 1);
        check("ids continue past the restored maximum", afterRestore.id() == 43, "" + afterRestore.id());

        // Kind worths are fixed and ordered so a death drop outranks a region review.
        check("a drop is worth more than a region review",
            ObjectivePlanner.Kind.DROP_PICKUP.baseWorth > ObjectivePlanner.Kind.REVIEW_REGION.baseWorth,
            "worths");

        // Priority is bounded and monotonic in distance.
        ObjectivePlanner.Objective o = new ObjectivePlanner.Objective(1, ObjectivePlanner.Kind.STASH_REVISIT,
            "s", 0, 64, 0, 1000);
        double atZero = ObjectivePlanner.priority(o, 0, 64, 0, 1000);
        double atFar = ObjectivePlanner.priority(o, 1000, 64, 0, 1000);
        check("farther is lower priority", atZero > atFar, atZero + " vs " + atFar);
    }
}
