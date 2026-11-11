package dev.basehunt.bhclient.core;

/**
 * Arbitration order for automation tasks. Lower weight wins.
 *
 * <p>Every automation that touches the player (movement, rotation, inventory slots, equipment) must go
 * through {@link TaskScheduler} with one of these priorities, so survival always beats exploration and two
 * modules can never drive the same resource at once.
 */
public enum Priority {
    CRITICAL_SURVIVAL(0),
    EQUIPMENT_RECOVERY(1),
    FLIGHT_SAFETY(2),
    INVENTORY_MANAGEMENT(3),
    ACTIVE_EXPLORATION(4),
    TARGET_INVESTIGATION(5),
    BACKGROUND_DISCOVERY(6);

    public final int weight;

    Priority(int weight) {
        this.weight = weight;
    }

    /** True when this priority should be allowed to preempt the other. */
    public boolean outranks(Priority other) {
        return weight < other.weight;
    }
}
