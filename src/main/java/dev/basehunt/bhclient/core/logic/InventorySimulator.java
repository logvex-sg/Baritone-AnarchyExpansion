package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies {@link SortPlanner} moves to a slot snapshot without a client.
 *
 * <p>This is what makes the sort testable end to end: the planner emits moves, the simulator executes them
 * with vanilla pick-up/place semantics, and the test asserts that no item was lost or duplicated and that
 * the final order is the one the planner promised. It also produces the expected slot fingerprint the
 * runtime uses to verify that a live container operation actually landed.
 */
public final class InventorySimulator {

    /** A slot's contents. {@code key} identifies a mergeable item; an empty slot has a null key. */
    public record Slot(int index, String key, String name, int count) {
        public boolean isEmpty() {
            return key == null || count <= 0;
        }
    }

    /** The stack held by the cursor during a simulated click sequence. */
    private record Cursor(String key, String name, int count) {
        boolean isEmpty() {
            return key == null || count <= 0;
        }
    }

    private InventorySimulator() {
    }

    /**
     * Executes the moves and returns the resulting slots.
     *
     * @throws IllegalStateException when a move would place an item onto a different item, which the
     *                               planner must never emit
     */
    public static List<Slot> apply(List<SortPlanner.Move> moves, List<Slot> initial, int maxPerSlot) {
        Map<Integer, Slot> slots = new HashMap<>();
        for (Slot slot : initial) slots.put(slot.index(), slot);

        Cursor cursor = new Cursor(null, null, 0);

        for (SortPlanner.Move move : moves) {
            Slot from = slots.getOrDefault(move.fromSlot(), new Slot(move.fromSlot(), null, null, 0));

            // Every move begins by picking the source stack up.
            cursor = new Cursor(from.key(), from.name(), from.count());
            slots.put(move.fromSlot(), new Slot(move.fromSlot(), null, null, 0));

            if (move.kind() == SortPlanner.Kind.SWAP) {
                Slot to = slots.getOrDefault(move.toSlot(), new Slot(move.toSlot(), null, null, 0));
                // A swap exchanges two whole stacks, so it can never mix items: place the cursor contents,
                // then carry whatever was in the target back to the source slot.
                slots.put(move.toSlot(), new Slot(move.toSlot(), cursor.key(), cursor.name(), cursor.count()));
                slots.put(move.fromSlot(), new Slot(move.fromSlot(), to.key(), to.name(), to.count()));
                cursor = new Cursor(null, null, 0);
            } else {
                // MERGE: top up the destination, leave the remainder on the cursor.
                Slot to = slots.getOrDefault(move.toSlot(), new Slot(move.toSlot(), null, null, 0));
                if (!to.isEmpty() && !to.key().equals(cursor.key())) {
                    throw new IllegalStateException("MERGE would mix items in slot " + move.toSlot());
                }

                int space = maxPerSlot - to.count();
                int placed = Math.min(space, cursor.count());
                String key = to.isEmpty() ? cursor.key() : to.key();
                String name = to.isEmpty() ? cursor.name() : to.name();
                slots.put(move.toSlot(), new Slot(move.toSlot(), key, name, to.count() + placed));
                cursor = new Cursor(cursor.key(), cursor.name(), cursor.count() - placed);

                // Put the remainder back in the source slot.
                slots.put(move.fromSlot(), new Slot(move.fromSlot(), cursor.key(), cursor.name(), cursor.count()));
                cursor = new Cursor(null, null, 0);
            }
        }

        if (!cursor.isEmpty()) {
            throw new IllegalStateException("moves left items on the cursor: " + cursor.count() + " " + cursor.name());
        }

        List<Slot> result = new ArrayList<>(slots.values());
        result.sort((a, b) -> Integer.compare(a.index(), b.index()));
        return result;
    }

    /**
     * A stable fingerprint of the given slots, used to detect that a live container has settled. Only
     * occupied slots are included, so a container that finishes a transfer and then has trailing empty slots
     * reports the same fingerprint regardless of how the empty slots are ordered.
     */
    public static String fingerprint(List<Slot> slots) {
        StringBuilder builder = new StringBuilder();
        for (Slot slot : slots) {
            if (slot.isEmpty()) continue;
            builder.append(slot.index()).append('=').append(slot.key()).append('x').append(slot.count()).append(';');
        }
        return builder.toString();
    }

    /** Total item count across the slots, for a lost/duplicated-item assertion. */
    public static int totalItems(List<Slot> slots) {
        int total = 0;
        for (Slot slot : slots) total += Math.max(0, slot.count());
        return total;
    }
}
