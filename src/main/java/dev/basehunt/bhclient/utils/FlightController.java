package dev.basehunt.bhclient.utils;

import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.logic.ElytraDeployment;
import dev.basehunt.bhclient.core.logic.ElytraDurability;
import dev.basehunt.bhclient.core.logic.FlightState;
import dev.basehunt.bhclient.core.logic.RetryPolicy;

import java.util.Map;

/**
 * Keeps an elytra flight alive across landings, interruptions and dimension changes.
 *
 * <p>This is the piece the old modules were missing. Rather than checking "am I gliding?" and giving up, the
 * controller runs a small state machine each tick: it distinguishes grounded, falling, swimming and climbing
 * states, re-equips a broken elytra, retries a failed launch with exponential backoff, and - crucially -
 * gives up after a bounded number of failures instead of hammering a doomed takeoff forever.
 *
 * <p>It owns no elytra replacement of its own: it asks {@link ElytraManager} to swap, so there is still a
 * single owner of the chest slot.
 */
public class FlightController {
    private final String owner;
    private final RetryPolicy launchPolicy;

    private int minimumDurability = 16;
    private int replaceThreshold;
    private boolean wantsFlight;
    private boolean started;
    private boolean deployFailureReported;
    private long lastStateChangeAt;
    private FlightState lastState = FlightState.UNKNOWN;
    private ElytraDeployment.Action lastAction = ElytraDeployment.Action.NONE;

    public FlightController(String owner, int maxLaunchAttempts, long launchDelayMs, long maxLaunchDelayMs) {
        this.owner = owner;
        this.launchPolicy = new RetryPolicy(maxLaunchAttempts, launchDelayMs, maxLaunchDelayMs);
    }

    public void setMinimumDurability(int minimumDurability) {
        this.minimumDurability = Math.max(1, minimumDurability);
    }

    /** Durability at or below which the worn elytra should be swapped for a spare while still healthy. */
    public void setReplaceThreshold(int replaceThreshold) {
        this.replaceThreshold = Math.max(0, replaceThreshold);
    }

    /** Marks a route as active/inactive. When set false the controller stops trying to fly. */
    public void setWantsFlight(boolean wants) {
        this.wantsFlight = wants;
        if (wants) {
            started = true;
            launchPolicy.reset();
            deployFailureReported = false;
        }
    }

    public boolean wantsFlight() {
        return wantsFlight;
    }

    public boolean isStarted() {
        return started;
    }

    public FlightState state() {
        return ElytraManager.flightState();
    }

    public ElytraDeployment.Action lastAction() {
        return lastAction;
    }

    public int launchAttempts() {
        return launchPolicy.attempts();
    }

    public boolean launchExhausted() {
        return launchPolicy.exhausted();
    }

    public void resetLaunchPolicy() {
        launchPolicy.reset();
        deployFailureReported = false;
    }

    /** Marks the start of a new flight leg, resetting the launch backoff so a fresh leg may retry. */
    public void beginLeg() {
        launchPolicy.reset();
        deployFailureReported = false;
    }

    /**
     * Runs one tick of deployment/recovery logic and applies the chosen action.
     *
     * @param cruiseY preferred cruise altitude for the flight controller
     * @return the action taken this tick
     */
    public ElytraDeployment.Action tick(double cruiseY) {
        FlightState state = ElytraManager.flightState();
        long now = System.currentTimeMillis();

        if (state != lastState) {
            FlightState previous = lastState;
            lastState = state;
            lastStateChangeAt = now;

            // Distinguish a glide that ended on solid ground (a landing) from one that ended in mid-air (a
            // break or a server interruption). The former can simply be relaunched from the ground; the
            // latter may not, and both are worth reporting separately.
            if (previous == FlightState.GLIDING && wantsFlight && state.isSupported()) {
                publish(AutomationEvent.Type.LANDED_UNEXPECTEDLY, Map.of(
                    "state", state.name().toLowerCase(),
                    "altitude", String.valueOf((int) ElytraManager.playerY())));
            }
        }

        boolean hasElytra = ElytraManager.hasElytraEquipped();
        int wornRemaining = ElytraManager.equippedRemaining();
        boolean wornUsable = ElytraDurability.isViable(wornRemaining, minimumDurability);
        boolean wornNeedsReplace = wornRemaining >= 0 && replaceThreshold > 0 && wornRemaining <= replaceThreshold;
        boolean spareViable = ElytraManager.hasViableSpare(minimumDurability);
        boolean flightOn = ElytraController.available() && ElytraController.isFlying();

        boolean canAttempt = launchPolicy.canAttempt(now);

        ElytraDeployment.Action action = ElytraDeployment.decide(
            state, hasElytra, wornUsable, wornNeedsReplace, spareViable, flightOn, wantsFlight, canAttempt);

        lastAction = action;

        switch (action) {
            case LAUNCH -> {
                launchPolicy.recordAttempt(now);
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
                if (launchPolicy.exhausted() && !deployFailureReported) {
                    // The retries are used up and the player still is not gliding: report why once rather
                    // than every tick, so the reason is visible in chat and on the webhook without spam.
                    deployFailureReported = true;
                    publish(AutomationEvent.Type.FLIGHT_DEPLOY_FAILED, Map.of(
                        "state", state.name().toLowerCase(),
                        "attempts", String.valueOf(launchPolicy.attempts()),
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
        return wantsFlight && launchPolicy.exhausted() && lastAction == ElytraDeployment.Action.WAIT
            && !ElytraManager.hasElytraEquipped() && !ElytraManager.hasSpare();
    }

    /** Called when the player changes dimension: the flight is torn down and will be redeployed. */
    public void onDimensionChanged() {
        ElytraController.disable();
        launchPolicy.reset();
        deployFailureReported = false;
        publish(AutomationEvent.Type.DIMENSION_CHANGED, Map.of("dimension", ServerUtils.dimension()));
    }

    /** Called when the connection drops: no further attempts should be made until a new route starts. */
    public void onDisconnected() {
        wantsFlight = false;
        started = false;
        deployFailureReported = false;
        ElytraController.disable();
        launchPolicy.reset();
    }

    /** Human-readable status for chat, the HUD and the status command. */
    public String describe() {
        return "%s, elytra %s, attempts %d".formatted(lastState.name().toLowerCase(), ElytraManager.status(), launchPolicy.attempts());
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, owner, data));
    }
}
