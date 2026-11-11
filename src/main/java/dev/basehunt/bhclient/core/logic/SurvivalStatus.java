package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates a survival snapshot against configurable thresholds and reports what is wrong.
 *
 * <p>Centralising the checks here means the survival module, the HUD and the auto-log trigger all agree on
 * what "in danger" means. The checks are pure functions of the snapshot so they can be tested without a
 * running game.
 */
public final class SurvivalStatus {
    /** Everything the survival manager needs to know about the player right now. */
    public record Snapshot(
        float health,
        float maxHealth,
        int foodLevel,
        int elytraRemaining,
        int spareElytras,
        int totems,
        int foodItems,
        int fireworks,
        int freeSlots,
        boolean gliding,
        boolean hasElytraEquipped
    ) {
    }

    public record Thresholds(
        float minHealth,
        int minFood,
        int minElytraDurability,
        int minSpareElytras,
        int minTotems,
        int minFoodItems,
        int minFireworks,
        int minFreeSlots
    ) {
        public static Thresholds defaults() {
            return new Thresholds(8f, 6, 24, 1, 1, 8, 16, 2);
        }
    }

    /** A single thing that is wrong, with the severity that decides the response. */
    public record Issue(String code, String description, boolean critical) {
    }

    private SurvivalStatus() {
    }

    /** All issues implied by the snapshot, worst first. */
    public static List<Issue> evaluate(Snapshot s, Thresholds t) {
        List<Issue> issues = new ArrayList<>();

        if (s.health() <= t.minHealth()) {
            issues.add(new Issue("health", "health %s".formatted(format(s.health())), true));
        }

        if (s.foodLevel() <= t.minFood()) {
            issues.add(new Issue("hunger", "hunger %d".formatted(s.foodLevel()), s.foodLevel() <= 1));
        }

        if (s.hasElytraEquipped() && s.elytraRemaining() >= 0 && s.elytraRemaining() <= t.minElytraDurability()) {
            issues.add(new Issue("elytra-durability",
                "elytra at %d durability".formatted(s.elytraRemaining()),
                s.elytraRemaining() <= Math.max(1, t.minElytraDurability() / 3)));
        }

        if (s.gliding() && !s.hasElytraEquipped()) {
            issues.add(new Issue("elytra-missing", "gliding without an elytra", true));
        }

        if (s.hasElytraEquipped() && s.spareElytras() < t.minSpareElytras()) {
            issues.add(new Issue("no-spare-elytra",
                "no spare elytra (have %d)".formatted(s.spareElytras()), false));
        }

        if (s.totems() < t.minTotems()) {
            issues.add(new Issue("no-totem", "no totem of undying", s.totems() == 0));
        }

        if (s.foodItems() < t.minFoodItems()) {
            issues.add(new Issue("low-food", "%d food items".formatted(s.foodItems()), s.foodItems() == 0));
        }

        if (s.gliding() && s.fireworks() < t.minFireworks()) {
            issues.add(new Issue("low-fireworks", "%d fireworks".formatted(s.fireworks()), false));
        }

        if (s.freeSlots() < t.minFreeSlots()) {
            issues.add(new Issue("inventory-full", "%d free slots".formatted(s.freeSlots()), s.freeSlots() == 0));
        }

        return issues;
    }

    /** True when any critical issue is present, which should cancel an active route. */
    public static boolean hasCritical(List<Issue> issues) {
        for (Issue issue : issues) if (issue.critical()) return true;
        return false;
    }

    private static String format(float value) {
        return value == Math.floor(value) ? String.valueOf((int) value) : String.valueOf(value);
    }
}
