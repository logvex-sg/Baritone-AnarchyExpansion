package dev.basehunt.bhclient.core.logic;

/**
 * The policy for parking a route when flying is impossible, and for resuming it afterwards.
 *
 * <p>This exists because the old behaviour - ending the route and disabling the module - was
 * indistinguishable from the addon switching itself off. A route with no elytra, or a player still falling,
 * is a temporary condition, not the end of the hunt: one elytra from a chest revives it, and the ground
 * arrives on its own. Holding keeps the target, so nothing has to be re-planned.
 *
 * <p>Pure logic, no Minecraft types, so the rules are testable headlessly.
 */
public final class FlightHold {

    /** Why the route is parked. Drives the message the player sees. */
    public enum Reason {
        /** Nothing to fly with: no worn elytra and no viable spare. */
        NO_ELYTRA,
        /** Airborne or otherwise unable to launch, but the elytra is fine. */
        NOT_GROUNDED,
        /** Launch attempts kept failing from solid ground, so stop hammering them. */
        LAUNCH_FAILING,
        /** Not holding. */
        NONE
    }

    /** What the caller should do with the route this tick. */
    public enum Decision {
        /** Keep running normally. */
        CONTINUE,
        /** Park the route and keep the target. */
        HOLD,
        /** Leave the hold and fly again. */
        RESUME
    }

    private final int maxRestarts;
    private Reason reason = Reason.NONE;
    private int restarts;
    private boolean reported;

    public FlightHold(int maxRestarts) {
        this.maxRestarts = Math.max(1, maxRestarts);
    }

    public boolean holding() {
        return reason != Reason.NONE;
    }

    public Reason reason() {
        return reason;
    }

    /** True the first time it is called for the current hold, so the message is not repeated every tick. */
    public boolean shouldAnnounce() {
        if (holding() && !reported) {
            reported = true;
            return true;
        }
        return false;
    }

    /** Parks the route. Calling it again while held keeps the original reason. */
    public void hold(Reason why) {
        if (why == null || why == Reason.NONE || holding()) return;
        reason = why;
        reported = false;
    }

    /** Clears the hold and returns to normal flight. */
    public void release() {
        reason = Reason.NONE;
        reported = false;
    }

    /**
     * Counts one restart from the ground after a failed redeploy.
     *
     * @return true when another restart is allowed, false when the budget is spent and the route should hold
     */
    public boolean tryRestart() {
        if (restarts >= maxRestarts) return false;
        restarts++;
        return true;
    }

    public int restarts() {
        return restarts;
    }

    /** A live glide proves the route recovered, so the restart budget is replenished for the next leg. */
    public void noteGlide() {
        restarts = 0;
    }

    /**
     * Decides what to do about a hold this tick.
     *
     * @param holding     whether the route is currently parked
     * @param hasElytra   whether an elytra is worn
     * @param grounded    whether the player is in a state a launch can succeed from
     */
    public static Decision decide(boolean holding, boolean hasElytra, boolean grounded) {
        if (!holding) return Decision.CONTINUE;
        // Both must be true: an elytra alone does not help mid-fall, and solid ground alone does not fly.
        return hasElytra && grounded ? Decision.RESUME : Decision.HOLD;
    }
}
