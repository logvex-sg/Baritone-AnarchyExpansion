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

        // Tasks and automation engine
        TASK_QUEUED,
        TASK_STARTED,
        TASK_COMPLETED,
        TASK_PARTIAL,
        TASK_FAILED,
        TASK_CANCELLED,
        NAVIGATION_STARTED,
        NAVIGATION_ARRIVED,
        NAVIGATION_FAILED,

        // Chat and kits
        CHAT_COMMAND,
        CHAT_COMMAND_DENIED,
        KIT_CLAIM_ATTEMPTED,
        KIT_CLAIM_SUCCEEDED,
        KIT_CLAIM_FAILED,
        KIT_ON_COOLDOWN,
        KIT_AVAILABLE,

        // 6b6t kit delivery
        KIT_REQUEST_RECEIVED,
        KIT_DELIVERY_STARTED,
        KIT_DELIVERY_COMPLETED,
        KIT_DELIVERY_FAILED,
        KIT_RESTOCK_NEEDED,

        // 6b6t server features
        SIXBT_HOME_SET,
        SIXBT_HOME_VISITED,
        SIXBT_TPA_SENT,
        SIXBT_TPA_ACCEPTED,
        SIXBT_TPA_DECLINED,
        SIXBT_TPS_LOW,
        SIXBT_HOTSPOT_ACTIVE,
        SIXBT_HOTSPOT_ENDED,

        // Baritone job control (mine, follow, explore, get-to-block, farm, build)
        BARITONE_TASK_STARTED,
        BARITONE_TASK_DONE,
        BARITONE_TASK_FAILED,
        BARITONE_TASK_CANCELLED,

        // Player proximity radar
        PLAYER_NEARBY,
        PLAYER_APPROACHING,
        PLAYER_LEFT_RANGE,

        // Death memory and recovery
        DEATH_RECORDED,
        DEATH_RECOVERY_STARTED,
        DEATH_RECOVERED,
        DEATH_RECOVERY_FAILED,

        // Smooth look and human-like behaviour
        LOOK_COMPLETED,
        HUMAN_GESTURE,

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
