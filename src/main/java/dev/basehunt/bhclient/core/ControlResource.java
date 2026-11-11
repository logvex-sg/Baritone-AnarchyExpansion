package dev.basehunt.bhclient.core;

/**
 * Player-level resources that automation modules can compete for.
 *
 * <p>Grouping the things two modules could otherwise fight over into named resources is what lets
 * {@link TaskScheduler} guarantee only one owner per resource at a time.
 */
public enum ControlResource {
    /** Movement keys, glide state and flight controllers. */
    MOVEMENT,
    /** Yaw and pitch. */
    ROTATION,
    /** Screen handler slot clicks and inventory moves. */
    INVENTORY,
    /** Armour and chest slot equipment changes. */
    EQUIPMENT,
    /** Block and entity interaction (opening containers, using items). */
    INTERACTION
}
