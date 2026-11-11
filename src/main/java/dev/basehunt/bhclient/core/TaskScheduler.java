package dev.basehunt.bhclient.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Priority-based arbiter for player-level control.
 *
 * <p>Every automation that moves the player, aims the camera, clicks inventory slots or changes equipment
 * takes a {@link Lease} first. A resource has exactly one owner at a time, and a higher priority request
 * preempts a lower one instead of the two racing. This is the single place that decides who is allowed to
 * act, which is what stops, for example, a route controller and a landing-recovery routine from both
 * writing rotation on the same tick.
 *
 * <p>The class is pure logic (no Minecraft or Meteor types) so its behaviour can be verified headlessly.
 */
public class TaskScheduler {
    private static final class Held {
        final Lease lease;
        final long acquiredTick;

        Held(Lease lease, long acquiredTick) {
            this.lease = lease;
            this.acquiredTick = acquiredTick;
        }
    }

    private final Map<ControlResource, Held> holders = new EnumMap<>(ControlResource.class);
    private final List<Consumer<Lease>> preemptionListeners = new ArrayList<>();
    private long generation;

    /** Notified when an existing owner loses a resource to a higher priority request. */
    public void addPreemptionListener(Consumer<Lease> listener) {
        preemptionListeners.add(listener);
    }

    /**
     * Requests the given resources for {@code owner}.
     *
     * @return a lease when granted, or {@code null} when an equal or higher priority owner already holds any
     *         of the requested resources.
     */
    public Lease acquire(String owner, Priority priority, Set<ControlResource> resources, long tick) {
        return acquire(owner, priority, resources, tick, -1);
    }

    public Lease acquire(String owner, Priority priority, Set<ControlResource> resources, long tick, long timeoutTicks) {
        if (resources.isEmpty()) return null;

        // Re-entrant request from the current owner: refresh the existing lease in place.
        if (isCurrentOwner(owner, resources)) {
            return refresh(owner, priority, resources, tick, timeoutTicks);
        }

        // A request may only proceed if it outranks or equals the holder of every resource it needs. Equal
        // priority does not preempt: that would let two same-tier modules livelock swapping ownership.
        for (ControlResource resource : resources) {
            Held held = holders.get(resource);
            if (held != null && !priority.outranks(held.lease.priority)) return null;
        }

        for (ControlResource resource : resources) {
            Held held = holders.get(resource);
            if (held != null) notifyPreempted(held.lease);
        }

        long expiresAt = timeoutTicks < 0 ? -1 : tick + timeoutTicks;
        Lease lease = new Lease(owner, priority, resources, ++generation, expiresAt);

        for (ControlResource resource : resources) holders.put(resource, new Held(lease, tick));
        return lease;
    }

    private Lease refresh(String owner, Priority priority, Set<ControlResource> resources, long tick, long timeoutTicks) {
        long expiresAt = timeoutTicks < 0 ? -1 : tick + timeoutTicks;
        Lease lease = new Lease(owner, priority, resources, ++generation, expiresAt);
        for (ControlResource resource : resources) holders.put(resource, new Held(lease, tick));
        return lease;
    }

    private boolean isCurrentOwner(String owner, Set<ControlResource> resources) {
        for (ControlResource resource : resources) {
            Held held = holders.get(resource);
            if (held == null || !held.lease.owner.equals(owner)) return false;
        }
        return true;
    }

    /** True when {@code owner} currently holds every listed resource. */
    public boolean owns(String owner, ControlResource... resources) {
        for (ControlResource resource : resources) {
            Held held = holders.get(resource);
            if (held == null || !held.lease.owner.equals(owner)) return false;
        }
        return true;
    }

    public boolean owns(String owner, Set<ControlResource> resources) {
        return isCurrentOwner(owner, resources);
    }

    /** Releases every resource held by the given lease. Safe to call with {@code null}. */
    public void release(Lease lease) {
        if (lease == null) return;

        for (ControlResource resource : lease.resources) {
            Held held = holders.get(resource);
            if (held != null && held.lease.generation() == lease.generation()) holders.remove(resource);
        }
    }

    /** Releases everything a named owner holds, regardless of lease identity. */
    public void releaseAll(String owner) {
        for (ControlResource resource : ControlResource.values()) {
            Held held = holders.get(resource);
            if (held != null && held.lease.owner.equals(owner)) holders.remove(resource);
        }
    }

    /** Drops timed-out leases. Call once per tick with the current tick counter. */
    public void tick(long tick) {
        for (ControlResource resource : ControlResource.values()) {
            Held held = holders.get(resource);
            if (held != null && held.lease.expiredAt(tick)) {
                holders.remove(resource);
                notifyPreempted(held.lease);
            }
        }
    }

    public String ownerOf(ControlResource resource) {
        Held held = holders.get(resource);
        return held == null ? null : held.lease.owner;
    }

    public boolean isFree(ControlResource resource) {
        return !holders.containsKey(resource);
    }

    /** Human-readable snapshot of current ownership, for the status command and HUD. */
    public String describe() {
        StringBuilder builder = new StringBuilder();

        for (ControlResource resource : ControlResource.values()) {
            Held held = holders.get(resource);
            if (held == null) continue;
            if (!builder.isEmpty()) builder.append(", ");
            builder.append(resource.name().toLowerCase()).append('=').append(held.lease.owner);
        }

        return builder.isEmpty() ? "idle" : builder.toString();
    }

    private void notifyPreempted(Lease lease) {
        for (Consumer<Lease> listener : preemptionListeners) listener.accept(lease);
    }
}
