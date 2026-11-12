package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.utils.BaritoneHelper;
import dev.basehunt.bhclient.utils.ContainerHelper;
import dev.basehunt.bhclient.utils.ServerUtils;
import net.minecraft.util.math.BlockPos;

/**
 * A navigation request that other systems can ask for without knowing Baritone exists.
 *
 * <p>Every module that needs the player to travel somewhere goes through here, so there is one place that
 * decides whether Baritone is loaded, issues the path, notices when the destination has been reached or is
 * unreachable, and stops cleanly when the request is cancelled. That is what stops three modules from each
 * driving Baritone at once and fighting over the player's movement.
 *
 * <p>Baritone is optional and compile-only. When it is absent, {@link #start} refuses with a reason instead
 * of touching a missing class, and a request that needs no travel (the target is already in reach) still
 * succeeds. Nothing here references a Baritone type at class-initialisation time.
 */
public final class NavigationManager {
    private static NavigationManager instance;

    /** How a navigation request ended. */
    public enum Status {
        IDLE,
        PATHING,
        ARRIVED,
        UNREACHABLE,
        FAILED,
        CANCELLED
    }

    /** A live navigation request. */
    public static final class Request {
        private final String owner;
        private final BlockPos destination;
        private final double arrivalDistance;
        private final boolean fly;
        private final long startedAt;

        private Status status = Status.IDLE;
        private String failure = "";
        private boolean pathIssued;
        private int ticksWithoutPath;
        private int issuedCount;

        Request(String owner, BlockPos destination, double arrivalDistance, boolean fly) {
            this.owner = owner;
            this.destination = destination;
            this.arrivalDistance = arrivalDistance;
            this.fly = fly;
            this.startedAt = System.currentTimeMillis();
        }

        public String owner() {
            return owner;
        }

        public BlockPos destination() {
            return destination;
        }

        /** True when this request travels by elytra rather than on foot. */
        public boolean isFlight() {
            return fly;
        }

        public Status status() {
            return status;
        }

        public String failure() {
            return failure;
        }

        public boolean isActive() {
            return status == Status.PATHING;
        }

        public long startedAt() {
            return startedAt;
        }

        public String describe() {
            return switch (status) {
                case IDLE -> "idle";
                case PATHING -> (fly ? "flying to " : "pathing to ") + destination.toShortString();
                case ARRIVED -> "arrived at " + destination.toShortString();
                case UNREACHABLE -> "unreachable: " + failure;
                case FAILED -> "failed: " + failure;
                case CANCELLED -> "cancelled";
            };
        }
    }

    private Request active;

    public static NavigationManager get() {
        return instance;
    }

    /** Called by the addon entrypoint. Safe to call twice. */
    public static NavigationManager install() {
        if (instance == null) instance = new NavigationManager();
        return instance;
    }

    /**
     * Asks the player to travel to a block position.
     *
     * <p>When the destination is already within interaction reach this reports {@link Status#ARRIVED}
     * immediately without moving, so a caller never has to special-case "already there". When Baritone is not
     * loaded, the request is refused with {@link Status#UNREACHABLE} and a clear reason rather than silently
     * doing nothing.
     *
     * @param owner           the requesting system, for preemption and reporting
     * @param destination     where to go
     * @param arrivalDistance how close is close enough (also the interaction reach for a container)
     */
    public Request start(String owner, BlockPos destination, double arrivalDistance) {
        return start(owner, destination, arrivalDistance, false);
    }

    /**
     * Asks the player to travel to a block position, choosing on-foot or elytra travel.
     *
     * @param owner           the requesting system, for preemption and reporting
     * @param destination     where to go
     * @param arrivalDistance how close is close enough (also the interaction reach for a container)
     * @param fly             true to travel by elytra (long range), false to walk
     */
    public Request start(String owner, BlockPos destination, double arrivalDistance, boolean fly) {
        cancel("superseded");

        Request request = new Request(owner, destination, arrivalDistance, fly);

        if (ServerUtils.mc() == null || ServerUtils.mc().player == null) {
            request.status = Status.FAILED;
            request.failure = "no player";
            active = request;
            return request;
        }

        if (withinReach(destination, arrivalDistance)) {
            request.status = Status.ARRIVED;
            active = request;
            return request;
        }

        if (!BaritoneHelper.usable()) {
            request.status = Status.UNREACHABLE;
            String health = BaritoneHelper.health();
            request.failure = health.isEmpty() ? "Baritone is not installed" : "Baritone is unusable: " + health;
            active = request;
            return request;
        }

        issue(request);
        active = request;
        return request;
    }

    /** Drives the active request. Call once per client tick. */
    public void tick() {
        if (active == null || active.status != Status.PATHING) return;

        BlockPos destination = active.destination;

        if (withinReach(destination, active.arrivalDistance)) {
            stopBaritone();
            active.status = Status.ARRIVED;
            return;
        }

        if (!BaritoneHelper.usable()) {
            active.status = Status.UNREACHABLE;
            active.failure = "Baritone became unavailable";
            return;
        }

        if (!active.pathIssued) {
            issue(active);
            return;
        }

        // Baritone stopping without arriving means the path failed or was interrupted. Give it a few retries
        // before declaring the destination unreachable, so a momentarily-blocked path can recover.
        if (!BaritoneHelper.isMoving()) {
            if (active.ticksWithoutPath++ > 40) {
                if (active.issuedCount >= 3) {
                    active.status = Status.UNREACHABLE;
                    active.failure = "could not find a path";
                } else {
                    active.pathIssued = false;
                    active.ticksWithoutPath = 0;
                }
            }
        } else {
            active.ticksWithoutPath = 0;
        }
    }

    /** Stops any active request. Safe to call at any time. */
    public void cancel(String reason) {
        if (active != null && active.status == Status.PATHING) {
            active.status = Status.CANCELLED;
            active.failure = reason == null ? "cancelled" : reason;
        }
        stopBaritone();
    }

    /** Stops the request owned by a specific system, if it is the active one. */
    public void cancelOwner(String owner) {
        if (active != null && active.owner().equals(owner)) cancel("cancelled by " + owner);
    }

    public Request active() {
        return active;
    }

    public boolean isNavigating() {
        return active != null && active.isActive();
    }

    /** The live position of the active destination, for the HUD and GUI. */
    public String statusLine() {
        return active == null ? "idle" : active.describe();
    }

    private void issue(Request request) {
        if (!BaritoneHelper.usable()) return;
        if (request.fly) BaritoneHelper.elytraPathTo(request.destination);
        else BaritoneHelper.walkTo(request.destination);
        request.pathIssued = true;
        request.issuedCount++;
        request.ticksWithoutPath = 0;
    }

    private void stopBaritone() {
        if (BaritoneHelper.usable() && BaritoneHelper.isMoving()) BaritoneHelper.stop();
    }

    private boolean withinReach(BlockPos destination, double distance) {
        var mc = ServerUtils.mc();
        if (mc == null || mc.player == null || mc.world == null) return false;
        if (!ContainerHelper.isChunkLoaded(destination)) return false;
        return mc.player.getEyePos().distanceTo(net.minecraft.util.math.Vec3d.ofCenter(destination)) <= distance;
    }
}
