package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans an inventory or container re-sort as an explicit list of click operations.
 *
 * <p>The plan is pure: it takes a snapshot of the slots, decides the target arrangement, and returns the
 * moves that realise it. Nothing here touches the game, so the arrangement, the merging and the permutation
 * it produces are all covered by the headless suite - including a simulator that applies the moves to an
 * array and checks the result, which is what proves no item is lost or duplicated.
 *
 * <p>Two move kinds are emitted, matching the two vanilla click patterns:
 *
 * <ul>
 *   <li>{@link Kind#SWAP} - pick up {@code from}, click {@code to}, click {@code from} again. Exchanges the
 *       contents of two slots. Used to reorder whole stacks.</li>
 *   <li>{@link Kind#MERGE} - pick up {@code from}, click {@code to} (the same item), click {@code from} to
 *       put the remainder back. Fills {@code to} up to its max and leaves the overflow in {@code from}.
 *       Only emitted between two slots holding the same item, so it can never mix items.</li>
 * </ul>
 *
 * <p>The planner never splits a stack, so a merge only ever combines what already fits. That keeps the
 * click count bounded and means a rejected click cannot leave a half-stack stranded on the cursor.
 */
public final class SortPlanner {

    /** One stack as the planner sees it. {@code key} identifies a mergeable item (id plus components). */
    public record SortItem(int slotIndex, String key, String name, StorageCategory category,
                           int count, int maxCount, boolean locked, boolean precious) {
    }

    public enum Kind {
        SWAP,
        MERGE,
        /**
         * Move exactly {@code amount} items from one slot into another, where {@code amount} is between 1 and
         * the source stack's count. A whole-stack move is expressed as a SWAP; a TRANSFER is the partial case,
         * which is what an exact-quantity withdrawal or deposit needs. It is executed as a pick-up, then
         * {@code amount} single-item places, then a put-back of the remainder, and the simulator models exactly
         * that, so a partial move can be verified like any other.
         */
        TRANSFER
    }

    /** A single click operation. {@code fromSlot} and {@code toSlot} are player-inventory slot indices. */
    public record Move(Kind kind, int fromSlot, int toSlot, int amount) {
        public String describe() {
            return "%s %d -> %d%s".formatted(kind.name().toLowerCase(), fromSlot, toSlot,
                amount > 0 ? " (" + amount + ")" : "");
        }

        /** How many client clicks this move costs, for the operation's budget and timeout estimate. */
        public int clickCost() {
            return kind == Kind.TRANSFER ? Math.max(1, amount) + 2 : 3;
        }
    }

    /**
     * @param moves    the click operations, in the order they must be executed
     * @param preview  one human-readable line per occupied target slot, in final order
     * @param changed  false when the inventory is already in the desired order
     * @param warnings anything the user should know (protected items skipped, too many stacks, ...)
     */
    public record Plan(List<Move> moves, List<String> preview, boolean changed, List<String> warnings) {
    }

    private SortPlanner() {
    }

    /** Sort by category order, then name. */
    public static Comparator<SortItem> byName() {
        return Comparator.comparing(SortItem::name, String.CASE_INSENSITIVE_ORDER);
    }

    /** Sort by category order, then largest stacks first. */
    public static Comparator<SortItem> byCount() {
        return (a, b) -> Integer.compare(b.count(), a.count());
    }

    /**
     * Builds a sort plan.
     *
     * @param items       every stack the caller can see, including ones outside the target slots
     * @param targetSlots the slots that may be rearranged, in the order they should be filled
     * @param order       category order; categories missing from it sort last, in enum order
     * @param tieBreak    ordering within a category
     * @param merge       whether to combine partial stacks of the same item
     */
    public static Plan plan(List<SortItem> items, List<Integer> targetSlots, List<StorageCategory> order,
                            Comparator<SortItem> tieBreak, boolean merge) {
        List<String> warnings = new ArrayList<>();
        List<Move> moves = new ArrayList<>();

        Set<Integer> targetSet = new HashSet<>(targetSlots);

        // A locked stack that sits in a target slot keeps that slot: it is removed from the usable set so the
        // rearrangement cannot displace it.
        Set<Integer> reserved = new HashSet<>();
        for (SortItem item : items) {
            if (item.locked() && targetSet.contains(item.slotIndex())) reserved.add(item.slotIndex());
        }

        List<Integer> usable = new ArrayList<>();
        for (int slot : targetSlots) if (!reserved.contains(slot)) usable.add(slot);

        // Live stacks: movable, not locked, inside a target slot. Identity is the current slot index.
        Map<Integer, Live> live = new LinkedHashMap<>();
        for (SortItem item : items) {
            if (item.locked() || !targetSet.contains(item.slotIndex())) continue;
            if (item.count() <= 0) continue;
            live.put(item.slotIndex(), new Live(item.slotIndex(), item));
        }

        if (live.isEmpty()) {
            return new Plan(List.of(), List.of(), false, warnings);
        }

        if (merge) {
            planMerges(live, moves, warnings);
        }

        // Assign the surviving stacks to the usable slots in category order.
        List<Live> ordered = new ArrayList<>(live.values());
        Map<StorageCategory, Integer> rank = new HashMap<>();
        List<StorageCategory> effectiveOrder = order == null || order.isEmpty()
            ? StorageCategory.defaultOrder() : order;
        for (int i = 0; i < effectiveOrder.size(); i++) rank.put(effectiveOrder.get(i), i);

        ordered.sort((a, b) -> {
            int ra = rank.getOrDefault(a.item.category(), effectiveOrder.size());
            int rb = rank.getOrDefault(b.item.category(), effectiveOrder.size());
            if (ra != rb) return Integer.compare(ra, rb);
            int byTie = tieBreak.compare(a.item, b.item);
            if (byTie != 0) return byTie;
            return Integer.compare(a.slot, b.slot);
        });

        if (ordered.size() > usable.size()) {
            warnings.add("%d stacks but only %d slots: the tail was left in place."
                .formatted(ordered.size(), usable.size()));
        }

        int placed = Math.min(ordered.size(), usable.size());

        // Build the desired arrangement of stack identities, then realise it with swaps. The stack ids are
        // unique, so the result is a permutation: no item can be lost or duplicated.
        int[] desired = new int[usable.size()];
        java.util.Arrays.fill(desired, -1);
        for (int i = 0; i < placed; i++) desired[i] = ordered.get(i).slot;

        int[] current = new int[usable.size()];
        java.util.Arrays.fill(current, -1);
        for (int i = 0; i < usable.size(); i++) {
            int slot = usable.get(i);
            if (live.containsKey(slot)) current[i] = slot;
        }

        planSwaps(usable, current, desired, live, moves);

        boolean changed = !moves.isEmpty();
        List<String> preview = buildPreview(usable, desired, live);

        return new Plan(moves, preview, changed, warnings);
    }

    /** Combines same-item partial stacks, mutating {@code live} and appending MERGE moves. */
    private static void planMerges(Map<Integer, Live> live, List<Move> moves, List<String> warnings) {
        Map<String, List<Live>> groups = new LinkedHashMap<>();
        for (Live stack : live.values()) groups.computeIfAbsent(stack.item.key(), k -> new ArrayList<>()).add(stack);

        for (List<Live> group : groups.values()) {
            if (group.size() < 2) continue;

            // Fill the stack with the most free space first so the fewest moves are needed.
            group.sort((a, b) -> Integer.compare(b.capacity(), a.capacity()));

            Live sink = group.get(0);
            for (int i = 1; i < group.size(); i++) {
                Live source = group.get(i);
                if (sink.capacity() <= 0) break;
                if (source.remaining() <= 0) continue;

                int amount = Math.min(sink.capacity(), source.remaining());
                moves.add(new Move(Kind.MERGE, source.slot, sink.slot, amount));
                sink.merged += amount;
                source.merged -= amount;

                if (source.remaining() <= 0) live.remove(source.slot);
            }
        }
    }

    /**
     * Selection sort over stack identities: for each slot in order, if it does not already hold the stack
     * that belongs there, swap it with the slot that does. Already-correct slots before the cursor are never
     * touched, so the move list is a valid permutation.
     */
    private static void planSwaps(List<Integer> usable, int[] current, int[] desired,
                                  Map<Integer, Live> live, List<Move> moves) {
        for (int i = 0; i < usable.size(); i++) {
            if (current[i] == desired[i]) continue;
            if (desired[i] == -1) {
                // This slot should be empty. Find any later slot that is empty and swap the extra stack back.
                int j = indexOf(current, -1, i + 1);
                if (j < 0) continue;
                moves.add(new Move(Kind.SWAP, usable.get(i), usable.get(j), 0));
                int tmp = current[i];
                current[i] = current[j];
                current[j] = tmp;
                continue;
            }

            int j = indexOf(current, desired[i], i + 1);
            if (j < 0) continue;

            // Two stacks of the same item are interchangeable: skip the swap so identical items are never
            // shuffled needlessly (and so a "swap" is never mistaken for a merge).
            if (sameKey(current[i], desired[i], live)) continue;

            moves.add(new Move(Kind.SWAP, usable.get(i), usable.get(j), 0));
            int tmp = current[i];
            current[i] = current[j];
            current[j] = tmp;
        }
    }

    private static boolean sameKey(int a, int b, Map<Integer, Live> live) {
        if (a < 0 || b < 0) return false;
        Live la = live.get(a);
        Live lb = live.get(b);
        return la != null && lb != null && la.item.key().equals(lb.item.key());
    }

    private static int indexOf(int[] array, int value, int from) {
        for (int i = from; i < array.length; i++) if (array[i] == value) return i;
        return -1;
    }

    private static List<String> buildPreview(List<Integer> usable, int[] desired, Map<Integer, Live> live) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < usable.size(); i++) {
            int id = desired[i];
            if (id < 0) continue;
            Live stack = live.get(id);
            if (stack == null) continue;
            lines.add("slot %d: %s x%d".formatted(usable.get(i), stack.item.name(), stack.remaining()));
        }
        return lines;
    }

    /** A live stack plus the merge bookkeeping the planner needs. */
    private static final class Live {
        final int slot;
        final SortItem item;
        int merged;

        Live(int slot, SortItem item) {
            this.slot = slot;
            this.item = item;
        }

        int remaining() {
            return item.count() + merged;
        }

        /** Free space in the destination stack, capped so a merge never exceeds a slot's max count. */
        int capacity() {
            return Math.max(0, item.maxCount() - remaining());
        }
    }
}
