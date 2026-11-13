package dev.basehunt.bhclient.core.logic;

import java.util.Random;

/**
 * Decides the small, purposeless movements a real player makes so an idling client does not look like a bot.
 *
 * <p>Standing perfectly still, always at the same angle, never jumping or sneaking, is as distinctive as
 * snapping the camera. This class schedules a sequence of short, bounded gestures - a glance, a tiny
 * strafe, a jump, a brief sneak, a look down at the hotbar - each lasting a random handful of ticks and
 * separated by a random pause, so the movement is irregular without ever being dangerous.
 *
 * <p>It is deliberately conservative: it exposes the gesture as data ({@link Gesture}) and lets the caller
 * decide how to apply it, never repeats the same gesture twice in a row, and keeps every duration inside a
 * configured bound so a "gesture" can never turn into a long, exposed detour. Pure and seeded, so the
 * schedule is reproducible headlessly.
 */
public final class HumanBehavior {
    /** A single movement gesture. The durations are in ticks. */
    public record Gesture(Kind kind, int durationTicks, int value) {
        /** A glance toward a yaw offset. */
        public static Gesture glance(int yawOffsetDegrees, int durationTicks) {
            return new Gesture(Kind.GLANCE, durationTicks, yawOffsetDegrees);
        }

        /** A short step in a direction (see {@link Kind#STRAFE}). */
        public static Gesture step(int strafe, int durationTicks) {
            return new Gesture(Kind.STRAFE, durationTicks, strafe);
        }

        public static Gesture jump(int durationTicks) {
            return new Gesture(Kind.JUMP, durationTicks, 0);
        }

        public static Gesture sneak(int durationTicks) {
            return new Gesture(Kind.SNEAK, durationTicks, 0);
        }

        /** Look at the hotbar / inventory for a moment. */
        public static Gesture glanceDown(int durationTicks) {
            return new Gesture(Kind.GLANCE_DOWN, durationTicks, 0);
        }
    }

    public enum Kind {
        /** Turn the view by a small offset, then let it drift back. */
        GLANCE,
        /** Press a strafe direction for a moment. */
        STRAFE,
        /** Jump once. */
        JUMP,
        /** Hold sneak for a moment. */
        SNEAK,
        /** Look down at the held item / hotbar. */
        GLANCE_DOWN
    }

    private final Random random;

    private int minIdleTicks;
    private int maxIdleTicks;
    private int minGestureTicks;
    private int maxGestureTicks;
    private int glanceRange;
    private boolean allowJump;
    private boolean allowSneak;

    private int idleTicks;
    private Gesture active;
    private int activeRemaining;
    private Kind lastKind;
    private long gestures;

    public HumanBehavior(long seed) {
        this.random = new Random(seed);
        this.minIdleTicks = 20;
        this.maxIdleTicks = 120;
        this.minGestureTicks = 2;
        this.maxGestureTicks = 12;
        this.glanceRange = 12;
        this.allowJump = true;
        this.allowSneak = true;
    }

    public void configure(int minIdleTicks, int maxIdleTicks, int minGestureTicks, int maxGestureTicks,
                          int glanceRange, boolean allowJump, boolean allowSneak) {
        this.minIdleTicks = Math.max(1, Math.min(minIdleTicks, maxIdleTicks));
        this.maxIdleTicks = Math.max(this.minIdleTicks, maxIdleTicks);
        this.minGestureTicks = Math.max(1, Math.min(minGestureTicks, maxGestureTicks));
        this.maxGestureTicks = Math.max(this.minGestureTicks, maxGestureTicks);
        this.glanceRange = Math.max(1, glanceRange);
        this.allowJump = allowJump;
        this.allowSneak = allowSneak;
    }

    /** True while a gesture is being performed. */
    public boolean busy() {
        return active != null;
    }

    /** The gesture currently being performed, or null. */
    public Gesture active() {
        return active;
    }

    /** How many gestures have been performed since the last reset. */
    public long gestures() {
        return gestures;
    }

    public void reset() {
        active = null;
        activeRemaining = 0;
        idleTicks = nextIdle();
        lastKind = null;
    }

    /**
     * Advances one tick.
     *
     * @return the gesture to start this tick, or null when nothing new begins. A gesture is returned once,
     *         on the tick it starts; the caller keeps applying it until {@link #busy()} becomes false.
     */
    public Gesture tick() {
        if (active != null) {
            if (--activeRemaining <= 0) {
                active = null;
                idleTicks = nextIdle();
            }
            return null;
        }

        if (--idleTicks > 0) return null;

        Gesture gesture = choose();
        if (gesture == null) {
            idleTicks = nextIdle();
            return null;
        }

        active = gesture;
        activeRemaining = gesture.durationTicks();
        lastKind = gesture.kind();
        gestures++;
        return gesture;
    }

    private Gesture choose() {
        Kind kind = pickKind();
        int duration = between(minGestureTicks, maxGestureTicks);

        return switch (kind) {
            case GLANCE -> Gesture.glance(between(-glanceRange, glanceRange), duration);
            case STRAFE -> Gesture.step(random.nextBoolean() ? 1 : -1, duration);
            case JUMP -> Gesture.jump(duration);
            case SNEAK -> Gesture.sneak(duration);
            case GLANCE_DOWN -> Gesture.glanceDown(duration);
        };
    }

    private Kind pickKind() {
        Kind[] pool = pool();
        for (int attempt = 0; attempt < 6; attempt++) {
            Kind candidate = pool[random.nextInt(pool.length)];
            // Never twice in a row: a bot that jumps, jumps, jumps is as obvious as one that never moves.
            if (candidate != lastKind) return candidate;
        }
        return pool[random.nextInt(pool.length)];
    }

    private Kind[] pool() {
        if (allowJump && allowSneak) {
            return new Kind[] {Kind.GLANCE, Kind.GLANCE, Kind.STRAFE, Kind.GLANCE_DOWN, Kind.JUMP, Kind.SNEAK};
        }
        if (allowJump) {
            return new Kind[] {Kind.GLANCE, Kind.GLANCE, Kind.STRAFE, Kind.GLANCE_DOWN, Kind.JUMP};
        }
        if (allowSneak) {
            return new Kind[] {Kind.GLANCE, Kind.GLANCE, Kind.STRAFE, Kind.GLANCE_DOWN, Kind.SNEAK};
        }
        return new Kind[] {Kind.GLANCE, Kind.GLANCE, Kind.STRAFE, Kind.GLANCE_DOWN};
    }

    private int nextIdle() {
        return between(minIdleTicks, maxIdleTicks);
    }

    private int between(int min, int max) {
        if (max <= min) return min;
        return min + random.nextInt(max - min + 1);
    }
}
