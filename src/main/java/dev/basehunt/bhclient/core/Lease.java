package dev.basehunt.bhclient.core;

import java.util.Set;

/**
 * A grant of one or more {@link ControlResource}s to a single owner.
 *
 * <p>The generation counter is bumped whenever a lease is preempted or released, so a module that was
 * interrupted can tell its lease is stale instead of continuing to act on a resource it no longer owns.
 */
public final class Lease {
    public final String owner;
    public final Priority priority;
    public final Set<ControlResource> resources;
    private final long generation;
    private final long expiresAtTick;

    Lease(String owner, Priority priority, Set<ControlResource> resources, long generation, long expiresAtTick) {
        this.owner = owner;
        this.priority = priority;
        this.resources = Set.copyOf(resources);
        this.generation = generation;
        this.expiresAtTick = expiresAtTick;
    }

    public long generation() {
        return generation;
    }

    public boolean expiredAt(long tick) {
        return expiresAtTick >= 0 && tick >= expiresAtTick;
    }

    public boolean covers(ControlResource resource) {
        return resources.contains(resource);
    }
}
