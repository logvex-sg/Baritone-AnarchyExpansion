package dev.basehunt.bhclient.core.logic;

/**
 * Pure decision function for deploying and keeping an elytra flight alive.
 *
 * <p>This is the logic that stops the addon endlessly retrying a doomed launch. It separates the cases the
 * old "am I gliding? then complain" check conflated: a grounded player can be launched, a falling player can
 * deploy, a worn-out elytra should be swapped rather than relaunched, and with no elytra at all the route must
 * abort. The caller supplies the backoff gate via {@code canAttempt} so retries are throttled outside this
 * pure function.
 */
public final class ElytraDeployment {

    /** What the caller should do this tick. */
    public enum Action {
        /** Nothing to do. */
        NONE,
        /** Enable the flight controller / attempt a takeoff. */
        LAUNCH,
        /** Flight is on and the player is gliding; keep steering. */
        CRUISE,
        /** Swap in the best spare elytra before continuing. */
        REPLACE,
        /** Wait: either the state cannot support a launch, or the backoff gate is closed. */
        WAIT,
        /** No usable elytra remains; the route must stop. */
        ABORT
    }

    private ElytraDeployment() {
    }

    /**
     * @param state             the player's current physical state
     * @param hasElytra         whether an elytra is worn
     * @param wornUsable        whether the worn elytra is above the hard minimum durability
     * @param wornNeedsReplace  whether the worn elytra has dropped to the early-swap threshold
     * @param spareViable       whether a spare elytra above the minimum is carried
     * @param flightOn          whether the flight controller is currently enabled
     * @param wantsFlight       whether a route is active and flight is wanted
     * @param canAttempt        whether the retry/backoff gate allows another launch attempt now
     */
    public static Action decide(FlightState state, boolean hasElytra, boolean wornUsable, boolean wornNeedsReplace,
                                boolean spareViable, boolean flightOn, boolean wantsFlight, boolean canAttempt) {
        if (!wantsFlight) {
            return flightOn ? Action.WAIT : Action.NONE;
        }

        if (state == FlightState.GLIDING) {
            // Already flying. A worn-out elytra is swapped without interrupting the glide.
            if (wornNeedsReplace && spareViable) return Action.REPLACE;
            return Action.CRUISE;
        }

        // Not gliding.
        if (!hasElytra) {
            return spareViable ? Action.REPLACE : Action.ABORT;
        }

        if (!wornUsable) {
            return spareViable ? Action.REPLACE : Action.ABORT;
        }

        // Healthy enough to fly, but a fresh spare is available and the worn one is wearing out: swap before
        // it becomes critical, so a mid-flight break is avoided rather than recovered from.
        if (wornNeedsReplace && spareViable) return Action.REPLACE;

        if (!state.canDeploy()) {
            // Swimming or climbing: a launch cannot succeed, but do not abort - the player may get to land.
            return Action.WAIT;
        }

        return canAttempt ? Action.LAUNCH : Action.WAIT;
    }

    /** True when the action means the route cannot continue and should stop. */
    public static boolean isTerminal(Action action) {
        return action == Action.ABORT;
    }
}
