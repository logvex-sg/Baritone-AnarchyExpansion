package dev.basehunt.bhclient.core.logic;

/**
 * The physical state of the player, as it matters to flight automation.
 *
 * <p>Redeployment after an interruption must distinguish "on the ground and able to jump-start a glide"
 * from "falling with no elytra deployed" from "swimming". Collapsing those into a single "not gliding"
 * check is what causes endless failed deployment attempts.
 */
public enum FlightState {
    /** No world or player. */
    UNKNOWN,
    /** Standing or walking on solid ground. */
    GROUNDED,
    /** In the air under a live elytra glide. */
    GLIDING,
    /** Falling with no glide active. */
    FALLING,
    /** In water, submerged or floating. */
    SWIMMING,
    /** On a ladder, vine or similar climbable. */
    CLIMBING,
    /** Airborne but not falling fast - jumping or being pushed. */
    AIRBORNE;

    /**
     * Classifies the player from cheap boolean observations, in priority order.
     *
     * <p>Gliding wins over everything: an active glide is unambiguous. Climbing and swimming win over
     * grounded/airborne because they change whether a launch is even possible. A downward vertical velocity
     * distinguishes falling from a simple jump.
     */
    public static FlightState classify(boolean gliding, boolean swimming, boolean climbing,
                                       boolean onGround, double verticalVelocity) {
        if (gliding) return GLIDING;
        if (swimming) return SWIMMING;
        if (climbing) return CLIMBING;
        if (onGround) return GROUNDED;
        return verticalVelocity < -0.05 ? FALLING : AIRBORNE;
    }

    /** True when the player is not on solid ground and not in a glide. */
    public boolean isAirborneWithoutGlide() {
        return this == FALLING || this == AIRBORNE;
    }

    /** True when a fresh elytra deployment could plausibly succeed from here. */
    public boolean canDeploy() {
        return this == GROUNDED || this == AIRBORNE || this == FALLING;
    }

    /** True when the player is safely supported (ground, water or a climbable). */
    public boolean isSupported() {
        return this == GROUNDED || this == SWIMMING || this == CLIMBING;
    }
}
