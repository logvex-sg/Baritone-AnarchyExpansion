package dev.basehunt.bhclient.utils;

import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.logic.DeploymentGovernor;
import dev.basehunt.bhclient.core.logic.ElytraDeployment;
import dev.basehunt.bhclient.core.logic.ElytraDurability;
import dev.basehunt.bhclient.core.logic.FlightState;

import java.util.Map;

/**
 * Keeps an elytra flight alive across landings, interruptions and dimension changes.
 *
 * <p>This is the piece the old modules were missing. Rather than checking "am I gliding?" and giving up, the
 * controller runs a small state machine each tick: it distinguishes grounded, falling, swimming and climbing
 * states, re-equips a broken elytra, retries a failed launch with exponential backoff, and - crucially -
 * gives up after a bounded number of failures instead of hammering a doomed takeoff forever.
 *
 * <p>All retry and transition bookkeeping lives in the pure {@link DeploymentGovernor}; this class only
 * reads the client state to feed it and applies the chosen action. Keeping the policy out of here is what
 * lets the "never retry forever" rule be verified headlessly.
 *
 * <p>It owns no elytra replacement of its own: it asks {@link ElytraManager} to swap, so there is still a
 * single owner of the chest slot.
 */
public class FlightController {
    private final String owner;
    private final DeploymentGovernor governor;

    private int minimumDurability = 16;
    private int replaceThreshold;
    private ElytraDeployment.Action lastAction = ElytraDeployment.Action.NONE;

    public FlightController(String owner, int maxLaunchAttempts, long launchDelayMs, long maxLaunchDelayMs) {
        this.owner = owner;
        this.governor = new DeploymentGovernor(maxLaunchAttempts, launchDelayMs, maxLaunchDelayMs);
    }

    public void setMinimumDurability(int minimumDurability) {
        this.minimumDurability = Math.max(1, minimumDurability);
    }

    /** Durability at or below which the worn elytra should be swapped for a spare while still healthy. */
    public void setReplaceThreshold(int replaceThreshold) {
        this.replaceThreshold = Math.max(0, replaceThreshold);
    }

    /**
     * Marks a route as active/inactive. Only a transition to active starts a new flight; re-asserting true
     * every tick leaves the retry budget intact (see {@link DeploymentGovernor#setWantsFlight}).
     */
    public void setWantsFlight(boolean wants) {
        governor.setWantsFlight(wants);
    }

    public boolean wantsFlight() {
        return governor.wantsFlight();
    }

    public boolean isStarted() {
        return governor.started();
    }

    public FlightState state() {
        return ElytraManager.flightState();
    }

    public ElytraDeployment.Action lastAction() {
        return lastAction;
    }

    public int launchAttempts() {
        return governor.attempts();
    }

    public boolean launchExhausted() {
        return governor.exhausted();
    }

    public void resetLaunchPolicy() {
        governor.beginLeg();
    }

    /** Marks the start of a new flight leg, resetting the launch backoff so a fresh leg may retry. */
    public void beginLeg() {
        governor.beginLeg();
    }

    /**
     * Runs one tick of deployment/recovery logic and applies the chosen action.
     *
     * @param cruiseY preferred cruise altitude for the flight controller
     * @return the action taken this tick
     */
    public ElytraDeployment.Action tick(double cruiseY) {
        FlightState state = ElytraManager.flightState();

        governor.noteState(state);

        // A glide that ended on a supported surface is a landing, not a break: report it separately so the
        // user can tell a normal touchdown from a mid-air failure.
        if (governor.justLanded() && state.isSupported()) {
            publish(AutomationEvent.Type.LANDED_UNEXPECTEDLY, Map.of(
                "state", state.name().toLowerCase(),
                "altitude", String.valueOf((int) ElytraManager.playerY())));
        }

        boolean hasElytra = ElytraManager.hasElytraEquipped();
        int wornRemaining = ElytraManager.equippedRemaining();
        boolean wornUsable = ElytraDurability.isViable(wornRemaining, minimumDurability);
        boolean wornNeedsReplace = wornRemaining >= 0 && replaceThreshold > 0 && wornRemaining <= replaceThreshold;
        boolean spareViable = ElytraManager.hasViableSpare(minimumDurability);
        boolean flightOn = ElytraController.available() && ElytraController.isFlying();

        boolean canAttempt = governor.canAttempt(System.currentTimeMillis());

        ElytraDeployment.Action action = ElytraDeployment.decide(
            state, hasElytra, wornUsable, wornNeedsReplace, spareViable, flightOn, governor.wantsFlight(), canAttempt);

        lastAction = action;

        switch (action) {
            case LAUNCH -> {
                governor.recordAttempt(System.currentTimeMillis());
                ElytraController.enable(cruiseY, true);
            }
            case REPLACE -> {
                boolean swapped = hasElytra
                    ? ElytraManager.replaceIfBelow(replaceThreshold, minimumDurability)
                    : ElytraManager.equipBest();
                if (swapped) {
                    publish(AutomationEvent.Type.ELYTRA_REPLACED,
                        Map.of("status", ElytraManager.status()));
                } else if (!ElytraManager.hasElytraEquipped()) {
                    publish(AutomationEvent.Type.ELYTRA_MISSING, Map.of());
                } else if (!spareViable) {
                    // The worn elytra is spent and there is nothing better to swap to. That is an equipment
                    // problem rather than a replacement, and the route cannot continue indefinitely.
                    publish(AutomationEvent.Type.EQUIPMENT_DURABILITY_LOW,
                        Map.of("item", "elytra", "remaining", String.valueOf(wornRemaining)));
                }
            }
            case ABORT -> publish(AutomationEvent.Type.ELYTRA_BROKEN, Map.of());
            case WAIT -> {
                if (governor.shouldReportDeployFailure()) {
                    // The retries are used up and the player still is not gliding: report why once rather
                    // than every tick, so the reason is visible in chat and on the webhook without spam.
                    publish(AutomationEvent.Type.FLIGHT_DEPLOY_FAILED, Map.of(
                        "state", state.name().toLowerCase(),
                        "attempts", String.valueOf(governor.attempts()),
                        "elytra", ElytraManager.status()));
                }
            }
            case CRUISE, NONE -> {
                // Nothing to apply: steering is the caller's job.
            }
        }

        return action;
    }

    /** True when the launch backoff has been exhausted and the caller should escalate. */
    public boolean shouldAbort() {
        return governor.wantsFlight() && governor.exhausted() && lastAction == ElytraDeployment.Action.WAIT
            && !ElytraManager.hasElytraEquipped() && !ElytraManager.hasSpare();
    }

    /** Called when the player changes dimension: the flight is torn down and will be redeployed. */
    public void onDimensionChanged() {
        ElytraController.disable();
        governor.beginLeg();
        publish(AutomationEvent.Type.DIMENSION_CHANGED, Map.of("dimension", ServerUtils.dimension()));
    }

    /** Called when the connection drops: no further attempts should be made until a new route starts. */
    public void onDisconnected() {
        governor.setWantsFlight(false);
        ElytraController.disable();
        governor.beginLeg();
    }

    /** Human-readable status for chat, the HUD and the status command. */
    public String describe() {
        return "%s, elytra %s, attempts %d".formatted(
            governor.lastState().name().toLowerCase(), ElytraManager.status(), governor.attempts());
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, owner, data));
    }
}
