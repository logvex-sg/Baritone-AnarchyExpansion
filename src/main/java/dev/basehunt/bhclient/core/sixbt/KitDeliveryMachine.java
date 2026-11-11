package dev.basehunt.bhclient.core.sixbt;

import java.util.List;

/**
 * The delivery sequence a 6b6t kit bot runs, as a pure state machine.
 *
 * <p>On 6b6t a kit bot is a real player. It watches chat for a request, {@code /tpa}s to the requester, waits
 * until the teleport lands, drops the kit, and finally {@code /kill}s itself so it respawns at the world spawn
 * ready for the next request. That sequence has one correct order and several ways to go wrong (the request
 * is refused, the player never accepts, the player dies on the way), so it is modelled as a small machine
 * whose transitions are driven by observations rather than by wall-clock sleeps.
 *
 * <p>The machine never touches Minecraft: {@link #tick} takes an {@link Observation} and returns the
 * {@link Action}s to perform this tick. That keeps every transition - arrival, refusal, timeout, completion,
 * cancellation - testable without a client, which is the only way to trust a bot that is meant to run
 * unattended.
 */
public final class KitDeliveryMachine {

    /** Where the delivery is. */
    public enum State {
        /** Nothing in progress. */
        IDLE,
        /** {@code /tpa} sent; waiting for the requester to accept and for the player to come into range. */
        WAITING_ARRIVAL,
        /** The requester is in range; dropping the loadout. */
        DELIVERING,
        /** The kit is handed over; the bot should kill itself to reset. */
        FINISHED,
        /** The delivery could not complete (refused, timed out, cancelled). */
        FAILED
    }

    /** What the runtime observed this tick. */
    public record Observation(boolean arrived, boolean refused, boolean timedOut, int remainingItems,
                              boolean requesterGone) {
        /** A convenience for tests and for the common "nothing happened" tick. */
        public static Observation idle() {
            return new Observation(false, false, false, 0, false);
        }
    }

    /** Something for the runtime to do. */
    public sealed interface Action {
        /** Send {@code /tpa <player>}. */
        record SendTpa(String player) implements Action {
        }

        /** Send {@code /tpcancel} to withdraw a pending request. */
        record CancelTpa() implements Action {
        }

        /** Drop one item from the inventory (the runtime picks the next loadout stack). */
        record DropItem() implements Action {
        }

        /** Send {@code /kill} to reset the bot. */
        record KillSelf() implements Action {
        }

        /** Report why the delivery stopped, for chat and webhooks. */
        record Report(String reason) implements Action {
        }
    }

    private State state = State.IDLE;
    private String requester = "";
    private String kitName = "";
    private int ticksInState;
    private int dropAttempts;

    /** Begins a delivery for a request. Returns the actions to run immediately. */
    public List<Action> start(String requester, String kitName) {
        if (requester == null || requester.isBlank()) return List.of();

        this.state = State.WAITING_ARRIVAL;
        this.requester = requester;
        this.kitName = kitName == null ? "" : kitName;
        this.ticksInState = 0;
        this.dropAttempts = 0;

        return List.of(new Action.SendTpa(requester));
    }

    /**
     * Advances the machine one tick.
     *
     * @param observation what the runtime saw this tick
     * @return the actions to perform, possibly empty
     */
    public List<Action> tick(Observation observation) {
        if (state == State.IDLE || state == State.FINISHED || state == State.FAILED) return List.of();
        ticksInState++;
        Observation obs = observation == null ? Observation.idle() : observation;

        switch (state) {
            case WAITING_ARRIVAL -> {
                if (obs.refused()) {
                    state = State.FAILED;
                    return List.of(new Action.CancelTpa(), new Action.Report("request refused by " + requester));
                }
                if (obs.timedOut()) {
                    state = State.FAILED;
                    return List.of(new Action.CancelTpa(), new Action.Report("timed out waiting for " + requester));
                }
                if (obs.requesterGone()) {
                    state = State.FAILED;
                    return List.of(new Action.CancelTpa(), new Action.Report(requester + " left"));
                }
                if (obs.arrived()) {
                    state = State.DELIVERING;
                    return List.of();
                }
                return List.of();
            }
            case DELIVERING -> {
                if (obs.requesterGone()) {
                    state = State.FAILED;
                    return List.of(new Action.Report(requester + " left before the kit was dropped"));
                }
                if (obs.remainingItems() > 0 && dropAttempts < 4096) {
                    dropAttempts++;
                    return List.of(new Action.DropItem());
                }
                state = State.FINISHED;
                return List.of(new Action.Report("delivered " + kitName + " to " + requester),
                    new Action.KillSelf());
            }
            default -> {
                return List.of();
            }
        }
    }

    /** Abandons a delivery in progress. */
    public List<Action> cancel(String reason) {
        if (state == State.IDLE || state == State.FINISHED || state == State.FAILED) return List.of();
        state = State.FAILED;
        return List.of(new Action.CancelTpa(), new Action.Report(reason == null ? "cancelled" : reason));
    }

    /** Forgets the last delivery so the next {@link #start} begins clean. */
    public void reset() {
        state = State.IDLE;
        requester = "";
        kitName = "";
        ticksInState = 0;
        dropAttempts = 0;
    }

    public State state() {
        return state;
    }

    public boolean busy() {
        return state == State.WAITING_ARRIVAL || state == State.DELIVERING;
    }

    public String requester() {
        return requester;
    }

    public String kitName() {
        return kitName;
    }

    public int ticksInState() {
        return ticksInState;
    }

    /** A one-line status for the HUD and terminal. */
    public String statusLine() {
        return switch (state) {
            case IDLE -> "idle";
            case WAITING_ARRIVAL -> "waiting for " + requester + " (" + ticksInState + " ticks)";
            case DELIVERING -> "delivering " + kitName + " to " + requester;
            case FINISHED -> "delivered to " + requester;
            case FAILED -> "failed";
        };
    }
}
