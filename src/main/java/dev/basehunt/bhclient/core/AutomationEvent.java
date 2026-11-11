package dev.basehunt.bhclient.core;

import java.util.Map;

/**
 * A single fact published to {@link AutomationBus}.
 *
 * <p>Modules do not call each other directly. When something notable happens a module publishes an event
 * describing it, and any module interested in reacting subscribes. That keeps the reaction graph explicit
 * and means a new consumer never has to edit the producer.
 */
public record AutomationEvent(Type type, String source, long timestamp, Map<String, String> data) {

    public enum Type {
        // Survival and safety
        HEALTH_CRITICAL,
        HUNGER_CRITICAL,
        DANGER_DETECTED,

        // Equipment
        ELYTRA_DURABILITY_LOW,
        ELYTRA_REPLACED,
        ELYTRA_MISSING,
        ELYTRA_BROKEN,
        EQUIPMENT_DURABILITY_LOW,
        MENDING_REPAIR_NEEDED,
        MENDING_REPAIR_DONE,
        INVENTORY_FULL,

        // Flight
        FLIGHT_STARTED,
        FLIGHT_INTERRUPTED,
        LANDED_UNEXPECTEDLY,
        FLIGHT_DEPLOY_FAILED,
        DIMENSION_CHANGED,

        // Exploration
        ROUTE_STARTED,
        ROUTE_INTERRUPTED,
        ROUTE_RESUMED,
        ROUTE_COMPLETED,
        TARGET_REACHED,
        FRONTIER_PUSHED,
        EXPLORATION_STALLED,

        // Discovery
        STASH_DISCOVERED,
        STASH_UPGRADED,
        STRUCTURE_FOUND,
        NEW_CHUNK_CLUSTER,
        PLAYER_SIGHTED,
        PLAYER_LOGOUT_SPOT,

        // Ender chest / inventory
        ENDER_CHEST_FOUND,
        ENDER_CHEST_OPENED,
        ENDER_CHEST_CLOSED,
        INVENTORY_ORGANISED,

        // Storage and sorting
        STORAGE_SORTED,
        ITEMS_DEPOSITED,
        ITEMS_WITHDRAWN,
        ITEMS_RESTOCKED,
        CONTAINER_REGISTERED,
        CONTAINER_OP_FAILED,
        SHULKER_FILLED,
        SHULKER_EMPTIED,
        WORKFLOW_STARTED,
        WORKFLOW_STEP_DONE,
        WORKFLOW_COMPLETED,
        WORKFLOW_FAILED,

        // System
        SYSTEM_ERROR,
        MILESTONE
    }

    public static AutomationEvent of(Type type, String source) {
        return new AutomationEvent(type, source, java.lang.System.currentTimeMillis(), Map.of());
    }

    public static AutomationEvent of(Type type, String source, Map<String, String> data) {
        return new AutomationEvent(type, source, java.lang.System.currentTimeMillis(), Map.copyOf(data));
    }

    public String get(String key) {
        return data.getOrDefault(key, "");
    }

    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(data.get(key));
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    public double getDouble(String key, double fallback) {
        try {
            return Double.parseDouble(data.get(key));
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }
}
