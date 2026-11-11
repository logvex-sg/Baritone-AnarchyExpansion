package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps the slots of an open container screen onto the player inventory and the container.
 *
 * <p>Every vanilla container screen is laid out the same way: the container's own slots come first, then
 * the player's 27 main slots, then the 9 hotbar slots. Getting this arithmetic right is what stops the
 * sorter from "depositing" a stack into the player's own hotbar, so it lives in one tested place rather
 * than being re-derived in each caller.
 *
 * <p>Slot ids here are the ids reported by {@code ScreenHandler.getSlot(i).id}, which are stable for the
 * lifetime of a screen and are what a click must address. The player's main-inventory index (0..35, where
 * 0..8 is the hotbar) is {@code index - playerStart}.
 */
public final class ContainerLayout {

    /** The player inventory always contributes this many slots to a container screen. */
    public static final int PLAYER_SLOTS = 36;

    private final int containerSlots;
    private final int playerStart;

    private ContainerLayout(int containerSlots) {
        this.containerSlots = Math.max(0, containerSlots);
        this.playerStart = this.containerSlots;
    }

    /** A layout for a generic chest-like container of {@code rows} rows. */
    public static ContainerLayout forRows(int rows) {
        return new ContainerLayout(Math.max(0, rows) * 9);
    }

    /** A layout derived from the total slot count, assuming the rest is the player inventory. */
    public static ContainerLayout forTotalSlots(int totalSlots) {
        return new ContainerLayout(Math.max(0, totalSlots - PLAYER_SLOTS));
    }

    public int containerSlots() {
        return containerSlots;
    }

    /** The slot id of the first player slot, i.e. one past the last container slot. */
    public int playerStart() {
        return playerStart;
    }

    public int playerSlots() {
        return PLAYER_SLOTS;
    }

    public int totalSlots() {
        return containerSlots + PLAYER_SLOTS;
    }

    /** True when a slot id belongs to the container rather than the player. */
    public boolean isContainerSlot(int slotId) {
        return slotId >= 0 && slotId < containerSlots;
    }

    /** True when a slot id belongs to the player inventory (main slots or hotbar). */
    public boolean isPlayerSlot(int slotId) {
        return slotId >= playerStart && slotId < totalSlots();
    }

    /** True when a slot id is one of the nine hotbar slots. */
    public boolean isHotbarSlot(int slotId) {
        return slotId >= playerStart + 27 && slotId < totalSlots();
    }

    /** Player-inventory index (0..35) for a slot id, or -1 when the slot is the container's. */
    public int playerIndex(int slotId) {
        if (!isPlayerSlot(slotId)) return -1;
        return slotId - playerStart;
    }

    /**
     * True when a click between these two slots is a player-inventory rearrangement rather than a move
     * into or out of the container. Used to decide whether a planned move needs the container at all.
     */
    public boolean bothInPlayer(int fromSlotId, int toSlotId) {
        return isPlayerSlot(fromSlotId) && isPlayerSlot(toSlotId);
    }

    /** All container slot ids, in order. */
    public List<Integer> containerSlotIds() {
        List<Integer> ids = new ArrayList<>(containerSlots);
        for (int i = 0; i < containerSlots; i++) ids.add(i);
        return ids;
    }

    /** All player slot ids, in order: 27 main slots then the 9 hotbar slots. */
    public List<Integer> playerSlotIds() {
        List<Integer> ids = new ArrayList<>(PLAYER_SLOTS);
        for (int i = playerStart; i < totalSlots(); i++) ids.add(i);
        return ids;
    }
}
