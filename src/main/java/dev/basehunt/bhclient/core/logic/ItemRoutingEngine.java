package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chooses where each carried stack should be deposited, across a set of registered containers.
 *
 * <p>The routing engine is the piece that turns "I have a full inventory" into "these stacks go to that
 * chest". It never assumes a destination is usable: a container the caller has not marked reachable is
 * skipped, so an unloaded or protected chest is simply not a candidate rather than a failed click.
 *
 * <p>Destination priority is a number where <b>lower wins</b>. A destination with no matching category is
 * still eligible when it accepts the catch-all {@link StorageCategory#MISC}, which is how a "junk chest"
 * picks up the overflow. Capacity is measured in free slots; a stack consumes one.
 */
public final class ItemRoutingEngine {

    /** A registered destination. {@code id} is a stable key (usually the block position string). */
    public record Destination(int id, String label, Set<StorageCategory> categories, int priority,
                              int freeSlots, boolean reachable) {
        public boolean accepts(StorageCategory category) {
            return categories.contains(category) || categories.contains(StorageCategory.MISC);
        }
    }

    /** A carried stack the caller wants routed. */
    public record Carried(int slotIndex, String itemKey, String name, StorageCategory category, int count) {
    }

    /** One planned transfer. */
    public record Assignment(int itemSlot, String itemKey, String name, int count,
                             StorageCategory category, int destinationId, String destinationLabel) {
    }

    public record Result(List<Assignment> assignments, List<Carried> overflow, List<String> warnings) {
        public boolean isEmpty() {
            return assignments.isEmpty();
        }
    }

    private ItemRoutingEngine() {
    }

    /**
     * Routes each carried stack to the highest-priority reachable destination that accepts its category.
     *
     * @param carried      the stacks to route
     * @param destinations the registered destinations
     * @param onlySelected when non-empty, only these categories may be routed (a category filter)
     */
    public static Result route(List<Carried> carried, List<Destination> destinations, Set<StorageCategory> onlySelected) {
        List<String> warnings = new ArrayList<>();

        List<Destination> reachable = new ArrayList<>();
        for (Destination destination : destinations) if (destination.reachable()) reachable.add(destination);
        reachable.sort(Comparator.comparingInt(Destination::priority).thenComparingInt(Destination::id));

        Map<Integer, Integer> free = new LinkedHashMap<>();
        for (Destination destination : reachable) free.put(destination.id(), destination.freeSlots());

        List<Assignment> assignments = new ArrayList<>();
        List<Carried> overflow = new ArrayList<>();

        for (Carried stack : carried) {
            if (onlySelected != null && !onlySelected.isEmpty() && !onlySelected.contains(stack.category())) {
                continue;
            }

            Destination chosen = null;
            for (Destination destination : reachable) {
                if (!destination.accepts(stack.category())) continue;
                if (free.getOrDefault(destination.id(), 0) <= 0) continue;
                chosen = destination;
                break;
            }

            if (chosen == null) {
                overflow.add(stack);
                continue;
            }

            free.put(chosen.id(), free.get(chosen.id()) - 1);
            assignments.add(new Assignment(stack.slotIndex(), stack.itemKey(), stack.name(), stack.count(),
                stack.category(), chosen.id(), chosen.label()));
        }

        if (!overflow.isEmpty()) {
            warnings.add("%d stack(s) had no reachable destination and were left in the inventory."
                .formatted(overflow.size()));
        }

        return new Result(assignments, overflow, warnings);
    }

    /** Convenience overload with no category filter. */
    public static Result route(List<Carried> carried, List<Destination> destinations) {
        return route(carried, destinations, Set.of());
    }
}
