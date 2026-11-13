package dev.basehunt.bhclient.core.logic;

import java.util.Random;

/**
 * Turns a "look here" request into a believable stream of camera angles.
 *
 * <p>An automated client that snaps its view instantly to a target is the single most obvious tell that a
 * human is not at the keyboard. A real player turns the camera over several ticks, eases into the target,
 * overshoots slightly and settles, and never holds a perfectly constant angle. This class reproduces that:
 * it approaches the target along the shortest arc, caps the per-tick angular speed, adds a small amount of
 * seeded jitter that fades as the aim settles, and reports when the aim has arrived.
 *
 * <p>It is pure: angles in, angles out, driven by a seeded {@link Random} so the motion is reproducible in
 * tests. Nothing here touches Minecraft, so the easing, the wrapping and the arrival tolerance are pinned
 * headlessly.
 */
public final class LookSmoother {
    /** The angles to write this tick, and whether they differ from the previous tick. */
    public record Step(float yaw, float pitch, boolean moved) {
    }

    private final Random random;

    private float yaw;
    private float pitch;
    private float targetYaw;
    private float targetPitch;

    private float maxStep;
    private float easing;
    private float jitter;

    private boolean initialised;

    public LookSmoother(long seed) {
        this(seed, 12.0f, 0.35f, 0.8f);
    }

    public LookSmoother(long seed, float maxStepDegrees, float easing, float jitterDegrees) {
        this.random = new Random(seed);
        this.maxStep = clampStep(maxStepDegrees);
        this.easing = clampEasing(easing);
        this.jitter = clampJitter(jitterDegrees);
    }

    public void setSpeed(float maxStepDegrees) {
        this.maxStep = clampStep(maxStepDegrees);
    }

    public void setEasing(float easing) {
        this.easing = clampEasing(easing);
    }

    public void setJitter(float jitterDegrees) {
        this.jitter = clampJitter(jitterDegrees);
    }

    /** Jumps the current angle without any easing, e.g. on enable or a dimension change. */
    public void snap(float yaw, float pitch) {
        this.yaw = wrap(yaw);
        this.pitch = clampPitch(pitch);
        this.initialised = true;
    }

    /** Requests a new look direction. The smoother approaches it over the following ticks. */
    public void aimAt(float yaw, float pitch) {
        this.targetYaw = wrap(yaw);
        this.targetPitch = clampPitch(pitch);
        if (!initialised) {
            // First request after a snap-less start: adopt the current angle so we do not jump from 0,0.
            this.initialised = true;
        }
    }

    /** True when the current aim is within {@code toleranceDegrees} of the target. */
    public boolean atTarget(float toleranceDegrees) {
        return Math.abs(shortestDelta(targetYaw, yaw)) <= toleranceDegrees
            && Math.abs(targetPitch - pitch) <= toleranceDegrees;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public float targetYaw() {
        return targetYaw;
    }

    public float targetPitch() {
        return targetPitch;
    }

    /**
     * Advances the aim by one tick and returns the angles to apply.
     *
     * <p>Yaw takes the shortest arc so a turn from 170° to -170° is 20°, not 340°. The eased step is capped
     * at the configured speed, and the jitter is scaled by how far the aim still has to travel so a settled
     * view is nearly still while a moving one looks alive.
     */
    public Step tick() {
        float previousYaw = yaw;
        float previousPitch = pitch;

        float yawDelta = shortestDelta(targetYaw, yaw);
        float pitchDelta = targetPitch - pitch;

        float yawStep = clamp(yawDelta * easing, -maxStep, maxStep);
        float pitchStep = clamp(pitchDelta * easing, -maxStep, maxStep);

        yaw = wrap(yaw + yawStep);
        pitch = clampPitch(pitch + pitchStep);

        if (jitter > 0.0f) {
            float remaining = (Math.abs(yawDelta) + Math.abs(pitchDelta)) / (maxStep * 4.0f);
            float scale = clamp(remaining, 0.0f, 1.0f);
            if (scale > 0.0f) {
                yaw = wrap(yaw + (float) (random.nextGaussian() * jitter * scale));
                pitch = clampPitch(pitch + (float) (random.nextGaussian() * jitter * scale * 0.6f));
            }
        }

        boolean moved = Math.abs(shortestDelta(previousYaw, yaw)) > 0.01f
            || Math.abs(previousPitch - pitch) > 0.01f;
        return new Step(yaw, pitch, moved);
    }

    /**
     * The signed shortest rotation from {@code from} to {@code to}, in (-180, 180].
     */
    public static float shortestDelta(float to, float from) {
        return wrap(to - from);
    }

    /** Wraps any angle into (-180, 180]. */
    public static float wrap(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped <= -180.0f) wrapped += 360.0f;
        if (wrapped > 180.0f) wrapped -= 360.0f;
        return wrapped;
    }

    /** Wraps an angle into [0, 360). */
    public static float wrap360(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped < 0.0f) wrapped += 360.0f;
        return wrapped;
    }

    public static float clampPitch(float pitch) {
        return clamp(pitch, -90.0f, 90.0f);
    }

    /** The yaw that points from a position toward a target, as Minecraft yaw degrees. */
    public static float yawTo(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        return wrap((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
    }

    /** The pitch that points from a position toward a target, as Minecraft pitch degrees. */
    public static float pitchTo(double fromX, double fromY, double fromZ,
                                double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return clampPitch((float) -Math.toDegrees(Math.atan2(dy, horizontal)));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampStep(float step) {
        return clamp(step, 0.5f, 180.0f);
    }

    private static float clampEasing(float value) {
        return clamp(value, 0.02f, 1.0f);
    }

    private static float clampJitter(float value) {
        return clamp(value, 0.0f, 5.0f);
    }
}
