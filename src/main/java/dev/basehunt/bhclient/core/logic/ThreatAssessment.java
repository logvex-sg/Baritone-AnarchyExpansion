package dev.basehunt.bhclient.core.logic;

/**
 * Scores how dangerous a nearby player is, so a passing stranger and an incoming hunter are not the same
 * alert.
 *
 * <p>A player radar that only reports distance is nearly useless on an anarchy server: someone 60 blocks away
 * flying straight at you is a far bigger problem than someone loitering 30 blocks away in leather armour.
 * The client already sees the distance and the gear every tick; the value is in turning that into a single
 * number and a level that the rest of the addon - and the person watching the HUD - can act on.
 *
 * <p>This class is pure. It takes the facts the caller gathered (distance, how fast it is closing, what the
 * other player is wearing and holding) and returns a bounded score and a level. None of the thresholds or
 * the weighting is decided by the module, so all of it is covered headlessly rather than only being felt
 * during a fight.
 */
public final class ThreatAssessment {
    /** How seriously to take a contact. */
    public enum Level {
        /** Nothing worth noting. */
        CALM,
        /** Worth watching: armed, or moving toward you. */
        WARY,
        /** A real threat: close, or geared and closing. */
        DANGER,
        /** Get out: very close and geared, or closing fast while armed. */
        FLEE
    }

    /**
     * The characteristics of a contact that matter, gathered by the caller from the live entity.
     *
     * @param armorPoints rough armour value, 0 (bare) to 20 (full netherite)
     * @param weapon      holding a sword or axe (or the entity is otherwise clearly armed)
     * @param totem       holding a totem of undying, the clearest sign of a prepared PvP player
     * @param gliding     wearing an elytra and gliding, which usually means deliberate travel
     */
    public record Profile(int armorPoints, boolean weapon, boolean totem, boolean gliding) {
        public static final Profile UNKNOWN = new Profile(0, false, false, false);
    }

    /** Tunable thresholds and weights, supplied from the module's settings. */
    public record Params(double closeDistance, double approachDistance, double gearWeight,
                         double approachWeight, double totemWeight, double weaponWeight) {
        public static Params defaults() {
            return new Params(16.0, 3.0, 1.0, 1.5, 12.0, 8.0);
        }
    }

    /** A scored contact. */
    public record Threat(String name, double distance, double approachRate, int score, Level level) {
        public String describe() {
            return "%s %s %.0fm (score %d)".formatted(name, level.name().toLowerCase(), distance, score);
        }
    }

    private ThreatAssessment() {
    }

    /**
     * Scores one contact on a 0-100 scale.
     *
     * <p>Distance dominates because it is the thing you can react to, but being armed and prepared (a totem,
     * a weapon, armour) raises the score even at range, and a player closing on you raises it more than a
     * stationary one at the same distance. The result is clamped so no single input can make a distant,
     * harmless player read as an emergency.
     *
     * @param distance     current distance in blocks
     * @param approachRate blocks closed per second (negative when receding)
     */
    public static int score(double distance, double approachRate, Profile profile, Params params) {
        Profile p = profile == null ? Profile.UNKNOWN : profile;
        double d = Math.max(0.0, distance);

        // Proximity: full marks at zero, zero marks at the "far" distance (four times close).
        double far = Math.max(params.closeDistance() * 4.0, params.closeDistance() + 1.0);
        double proximity = clamp01(1.0 - d / far) * 55.0;

        // Closing speed: only the part above the threshold counts, capped.
        double closing = Math.max(0.0, approachRate) * params.approachWeight();
        double motion = Math.min(25.0, closing);

        // Gear: armour is worth up to 10, a weapon 8, a totem 12 - a prepared player is dangerous early.
        double gear = clamp01(p.armorPoints() / 20.0) * params.gearWeight() * 10.0;
        if (p.weapon()) gear += params.weaponWeight();
        if (p.totem()) gear += params.totemWeight();
        if (p.gliding()) gear += 4.0;

        return (int) Math.round(Math.min(100.0, proximity + motion + gear));
    }

    /** Maps a score to a level using the caller's thresholds. */
    public static Level level(int score, Params params) {
        // The same close/approach settings shape the bands, so raising "close-distance" also makes the
        // client slower to panic, which is what a person tuning it actually wants.
        double tension = Math.max(1.0, params.approachWeight());
        int wary = (int) Math.round(20.0 + tension * 2.0);
        int danger = (int) Math.round(45.0 + tension * 4.0);
        int flee = (int) Math.round(70.0 + tension * 4.0);

        if (score >= flee) return Level.FLEE;
        if (score >= danger) return Level.DANGER;
        if (score >= wary) return Level.WARY;
        return Level.CALM;
    }

    /** Convenience: score and classify in one call. */
    public static Threat assess(String name, double distance, double approachRate, Profile profile,
                                Params params) {
        int score = score(distance, approachRate, profile, params);
        return new Threat(name, distance, approachRate, score, level(score, params));
    }

    /** True when a level warrants waking someone up (chat, webhook, alert). */
    public static boolean isAlarming(Level level) {
        return level == Level.DANGER || level == Level.FLEE;
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
