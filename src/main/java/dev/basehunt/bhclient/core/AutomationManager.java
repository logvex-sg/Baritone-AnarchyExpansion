package dev.basehunt.bhclient.core;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Central hub the addon's subsystems talk through.
 *
 * <p>Holds the shared {@link AutomationBus} and {@link TaskScheduler} and advances the scheduler's clock once
 * per tick so timed leases expire. Modules publish events here instead of calling each other, and take leases
 * from here instead of driving the player directly, which is what keeps two automations from fighting.
 *
 * <p>The manager is created and registered by the addon entrypoint. It is not a Meteor {@code System}: it
 * holds no persisted state of its own, only transient runtime coordination.
 */
public class AutomationManager {
    private static AutomationManager instance;

    private final AutomationBus bus = new AutomationBus();
    private final TaskScheduler scheduler = new TaskScheduler();
    private final AtomicLong tick = new AtomicLong();

    public static AutomationManager get() {
        return instance;
    }

    /** Called by the addon entrypoint. Safe to call twice. */
    public static AutomationManager install() {
        if (instance == null) {
            instance = new AutomationManager();
            // The manager is not a Module, so nothing subscribes it automatically; without this its tick
            // handler never runs and timed leases never expire.
            meteordevelopment.meteorclient.MeteorClient.EVENT_BUS.subscribe(instance);
        }
        return instance;
    }

    public AutomationBus bus() {
        return bus;
    }

    public TaskScheduler scheduler() {
        return scheduler;
    }

    public long currentTick() {
        return tick.get();
    }

    /** Publishes an event. Convenience wrapper so callers do not need to fetch the bus first. */
    public void publish(AutomationEvent event) {
        bus.publish(event);
    }

    /**
     * Requests player-level control for a module.
     *
     * <p>Every automation that moves the player, aims the camera or clicks inventory slots goes through here
     * so only one owner acts on a resource at a time. A higher priority request preempts a lower one; a module
     * that is refused must back off for the tick rather than acting anyway.
     *
     * @return a lease when granted, or {@code null} when an equal or higher priority owner holds the resource
     */
    public Lease tryAcquire(String owner, Priority priority, java.util.Set<ControlResource> resources) {
        // A short timeout means a module that stops ticking (disabled, disconnected) releases control on its
        // own instead of leaving the resource stuck forever.
        return scheduler.acquire(owner, priority, resources, tick.get(), 60);
    }

    /** Releases every resource the named owner holds. Call from a module's deactivate. */
    public void releaseAll(String owner) {
        scheduler.releaseAll(owner);
    }

    /** Human-readable summary of who currently controls what, for the status command and HUD. */
    public String controlSummary() {
        return scheduler.describe();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        scheduler.tick(tick.incrementAndGet());
    }
}
