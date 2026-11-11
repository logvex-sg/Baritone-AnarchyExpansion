package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Plans an exact-quantity transfer of one item between a container and the player inventory.
 *
 * <p>This is the piece that makes "withdraw 64 ender pearls" mean 64 and not "a stack" or "everything in the
 * category". It is given the source stacks (in screen-slot-id space), the destination slots, and an
 * {@link ItemFilter} plus an amount, and it returns the exact moves needed:
 *
 * <ul>
 *   <li>a stack that fits whole becomes a {@link SortPlanner.Kind#SWAP} (a plain two-slot swap);</li>
 *   <li>a stack that has to be split becomes a {@link SortPlanner.Kind#TRANSFER} of exactly the missing
 *       count, so a request for 8 out of a stack of 64 moves 8 and leaves 56 behind;</li>
 *   <li>the request is filled across several source stacks and several destination slots, in order.</li>
 * </ul>
 *
 * <p>It reports what it could not do rather than silently shorting the user: a request that cannot be fully
 * met returns a plan whose {@code attainable} is smaller than the request, and the runtime turns that into a
 * {@code PARTIAL} task, not a success. The class is pure, so the split arithmetic, the partial-stack handling
 * and the full-destination cases are all covered by the headless suite.
 */
public final class QuantityPlanner {

    /** One source stack, in screen-slot-id space. */
    public record Source(int slotId, String itemId, String name, StorageCategory category, int count,
                         int maxCount) {
    }

    /** A destination slot that is either empty or already holds a stack of the same item. */
    public record Destination(int slotId, String itemId, int count, int maxCount) {
        public boolean empty() {
            return itemId == null || itemId.isEmpty() || count <= 0;
        }

        public int freeSpace() {
            return Math.max(0, maxCount - Math.max(0, count));
        }
    }

    /** The plan plus an honest account of what it can and cannot move. */
    public record Plan(List<SortPlanner.Move> moves, int requested, int attainable, List<String> warnings) {
        public boolean isEmpty() {
            return moves.isEmpty();
        }

        public boolean complete() {
            return attainable >= requested && requested > 0;
        }
    }

    private QuantityPlanner() {
    }

    /**
     * Plans moving exactly {@code amount} of a matching item from the sources into the destinations.
     *
     * @param sources      the stacks to take from, in preference order
     * @param destinations the slots to fill, in preference order
     * @param filter       which item to take
     * @param amount       how many to take; {@code Integer.MAX_VALUE} means "all available"
     * @param allowPartial when false, a plan that cannot fully satisfy the request is refused
     * @param protectedItems item ids that must never be moved
     */
    public static Plan plan(List<Source> sources, List<Destination> destinations, ItemFilter filter,
                            int amount, boolean allowPartial, Set<String> protectedItems) {
        List<String> warnings = new ArrayList<>();
        List<SortPlanner.Move> moves = new ArrayList<>();

        int requested = amount;
        boolean all = amount == Integer.MAX_VALUE;

        // Work on mutable copies of the destination free space, so a request spanning several stacks is
        // spread across the slots that actually have room.
        List<Destination> dest = new ArrayList<>(destinations);
        int destIndex = 0;

        int remaining = all ? Integer.MAX_VALUE : Math.max(0, amount);
        int attainable = 0;

        for (Source source : sources) {
            if (remaining <= 0) break;
            if (source.count() <= 0) continue;
            if (ProtectionRules.isProtectedItem(source.itemId(), protectedItems)) continue;
            if (!filter.matches(source.itemId(), source.name(), source.category())) continue;

            int available = source.count();

            // Fill destination slots in order until the source stack is used up or the request is met.
            while (available > 0 && remaining > 0 && destIndex < dest.size()) {
                Destination target = dest.get(destIndex);
                int space = target.freeSpace();
                if (space <= 0) {
                    destIndex++;
                    continue;
                }

                int take = all ? Math.min(available, space) : Math.min(Math.min(available, remaining), space);
                if (take <= 0) {
                    destIndex++;
                    continue;
                }

                if (take == available) {
                    // The whole source stack fits: a plain swap, which is the cheapest click pattern.
                    moves.add(new SortPlanner.Move(SortPlanner.Kind.SWAP, source.slotId(), target.slotId(), 0));
                } else {
                    // A split: move exactly `take`, leaving the rest in the source.
                    moves.add(new SortPlanner.Move(SortPlanner.Kind.TRANSFER, source.slotId(), target.slotId(), take));
                }

                available -= take;
                if (!all) remaining -= take;
                attainable += take;

                // Record the space we just consumed so the next move does not overfill the slot.
                dest.set(destIndex, new Destination(target.slotId(), target.empty() ? source.itemId() : target.itemId(),
                    Math.max(0, target.count()) + take, target.maxCount()));
            }

            if (all) remaining = 0;
        }

        if (requested != Integer.MAX_VALUE && attainable < requested) {
            warnings.add("only %d of the %d requested could be moved".formatted(attainable, requested));
            if (!allowPartial) {
                return new Plan(List.of(), requested, 0, List.of(
                    "only %d of the %d requested are available; partial moves are disabled"
                        .formatted(attainable, requested)));
            }
        }

        if (moves.isEmpty()) {
            warnings.add("no matching items were found");
        }

        return new Plan(moves, requested, attainable, warnings);
    }

    /**
     * Convenience for the common case of taking the first N of a single item id, used by the deposit path
     * where the "sources" are the player's own stacks.
     */
    public static Plan planItem(List<Source> sources, List<Destination> destinations, String itemId, int amount,
                                boolean allowPartial, Set<String> protectedItems) {
        return plan(sources, destinations, ItemFilter.item(itemId), amount, allowPartial, protectedItems);
    }

    /** Total count of matching items across the sources, for "is there any point going there". */
    public static int available(List<Source> sources, ItemFilter filter, Set<String> protectedItems) {
        int total = 0;
        for (Source source : sources) {
            if (ProtectionRules.isProtectedItem(source.itemId(), protectedItems)) continue;
            if (filter.matches(source.itemId(), source.name(), source.category())) total += Math.max(0, source.count());
        }
        return total;
    }

    /** True when any destination has room for at least one item. */
    public static boolean hasRoom(List<Destination> destinations) {
        for (Destination destination : destinations) {
            if (destination.freeSpace() > 0) return true;
        }
        return false;
    }
}
