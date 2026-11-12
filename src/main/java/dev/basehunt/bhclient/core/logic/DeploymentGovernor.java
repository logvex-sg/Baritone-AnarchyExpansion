package dev.basehunt.bhclient.core.logic;

/**
 * The retry and transition policy behind elytra redeployment, with no Minecraft types.
 *
 * <p>This is the piece that must not regress, so it is separated from {@code FlightController} and tested
 * headlessly. It answers three questions:
 *
 * <ul>
 *   <li>is a flight wanted right now?</li>
 *   <li>may another launch be attempted yet, or is the retry budget spent?</li>
 *   <li>did the physical state just change in a way that matters (a glide began, or a glide ended)?</li>
 * </ul>
 *
 * <p>The subtle rule this class exists to enforce: re-asserting "flight is wanted" every tick must
 * <em>not</em> reset the retry budget. A caller that sets the flag each tick (as the route modules do)
 * would otherwise retry a doomed takeoff forever. Only a genuine transition from "not wanted" to "wanted",
 * an explicit {@link #beginLeg()}, or a glide actually starting replenishes the budget.
 */
public final class DeploymentGovernor {
    private final RetryPolicy launchPolicy;

    private boolean wantsFlight;
    private boolean started;
    private boolean deployFailureReported;
    private FlightState lastState = FlightState.UNKNOWN;
    private boolean lastTransitionEndedGlide;

    public DeploymentGovernor(int maxLaunchAttempts, long launchDelayMs, long maxLaunchDelayMs) {
        this.launchPolicy = new RetryPolicy(maxLaunchAttempts, launchDelayMs, maxLaunchDelayMs);
    }

    /**
     * Sets whether a flight is wanted. Only a transition from false to true starts a new flight and resets
     * the retry budget; repeated {@code true} calls leave the budget untouched.
     */
    public void setWantsFlight(boolean wants) {
        if (wants && !wantsFlight) {
            started = true;
            launchPolicy.reset();
            deployFailureReported = false;
        }
        this.wantsFlight = wants;
    }

    public boolean wantsFlight() {
        return wantsFlight;
    }

    public boolean started() {
        return started;
    }

    /** Marks the start of a new flight leg, resetting the launch backoff so a fresh leg may retry. */
    public void beginLeg() {
        launchPolicy.reset();
        deployFailureReported = false;
    }

    /**
     * Feeds the current physical state.
     *
     * @return true when a glide just began, so the caller knows the budget was replenished
     */
    public boolean noteState(FlightState state) {
        if (state == null) return false;

        // A repeat of the same state is not a transition, so it must clear the one-shot landing flag.
        if (state == lastState) {
            lastTransitionEndedGlide = false;
            return false;
        }

        FlightState previous = lastState;
        lastState = state;
        lastTransitionEndedGlide = previous == FlightState.GLIDING && state != FlightState.GLIDING;

        // A live glide means the launch eventually succeeded; replenish the budget for the next leg so a
        // launch that used most of its attempts once does not leave the next interruption with almost none.
        if (state == FlightState.GLIDING && previous != FlightState.GLIDING) {
            launchPolicy.reset();
            deployFailureReported = false;
            return true;
        }

        return false;
    }

    /** True when the most recent state change ended a glide and the flight is still wanted. */
    public boolean justLanded() {
        return lastTransitionEndedGlide && wantsFlight;
    }

    public FlightState lastState() {
        return lastState;
    }

    public boolean canAttempt(long nowMs) {
        return launchPolicy.canAttempt(nowMs);
    }

    public void recordAttempt(long nowMs) {
        launchPolicy.recordAttempt(nowMs);
    }

    public int attempts() {
        return launchPolicy.attempts();
    }

    public boolean exhausted() {
        return launchPolicy.exhausted();
    }

    public long millisUntilNextAttempt(long nowMs) {
        return launchPolicy.millisUntilNextAttempt(nowMs);
    }

    /** True the first time it is called after the budget is spent, so a failure is reported once, not per tick. */
    public boolean shouldReportDeployFailure() {
        if (launchPolicy.exhausted() && !deployFailureReported) {
            deployFailureReported = true;
            return true;
        }
        return false;
    }

    public boolean deployFailureReported() {
        return deployFailureReported;
    }
}
