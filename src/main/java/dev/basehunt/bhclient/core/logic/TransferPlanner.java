package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans whole-stack transfers between the player inventory and a container, as explicit slot pairs.
 *
 * <p>Unlike {@code InventoryPlan} (which only decides <em>what</em> should move), this decides <em>where</em>
 * it moves to, so the transfer can be executed as an ordinary two-slot swap and verified by the same
 * simulation that guards a sort. A deposit fills a specific empty container slot; a withdrawal fills a
 * specific empty player slot.
 *
 * <p>Three safety rules are encoded here rather than left to the caller:
 *
 * <ul>
 *   <li>a reserve is never broken - a stack that would drop a category below its reserve is left alone
 *       entirely, because a shift-click cannot move part of a stack;</li>
 *   <li>a protected item is never moved, whatever its category;</li>
 *   <li>precious categories (elytra, armour, tools, totems, valuables) only move when explicitly selected,
 *       so a bulk "deposit everything" cannot empty your gear.</li>
 * </ul>
 */
public final class TransferPlanner {

    /** One carried or stored stack, in screen-slot-id space. */
    public record Stack(int slotId, String itemId, String name, StorageCategory category, int count) {
    }

    /** A planned transfer: move the whole stack from {@code fromSlotId} into {@code toSlotId}. */
    public record Transfer(int fromSlotId, int toSlotId, String itemId, String name, int count) {
    }

    public record Plan(List<Transfer> transfers, List<String> warnings) {
        public boolean isEmpty() {
            return transfers.isEmpty();
        }
    }

    private TransferPlanner() {
    }

    /**
     * Plans deposits from player slots into empty container slots.
     *
     * @param playerStacks   the player's occupied slots
     * @param emptyContainer the container's empty slot ids, in the order they should be filled
     * @param deposit        the categories the user selected to deposit
     * @param reserves       minimum to keep per category
     * @param protectedItems item ids that must never move
     */
    public static Plan planDeposit(List<Stack> playerStacks, List<Integer> emptyContainer,
                                   Set<StorageCategory> deposit, Map<StorageCategory, Integer> reserves,
                                   Set<String> protectedItems) {
        List<String> warnings = new ArrayList<>();
        List<Transfer> transfers = new ArrayList<>();

        Map<StorageCategory, Integer> remaining = new EnumMap<>(StorageCategory.class);
        for (StorageCategory category : deposit) {
            remaining.put(category, Math.max(0, reserves.getOrDefault(category, 0)));
        }

        int free = emptyContainer.size();
        int index = 0;

        for (Stack stack : playerStacks) {
            if (index >= free) break;
            if (!deposit.contains(stack.category())) continue;
            if (stack.category().isPrecious() && !deposit.contains(stack.category())) continue;
            if (protectedItems.contains(stack.itemId())) continue;

            int reserve = remaining.getOrDefault(stack.category(), 0);
            if (stack.count() <= reserve) {
                remaining.put(stack.category(), reserve - stack.count());
                continue;
            }

            transfers.add(new Transfer(stack.slotId(), emptyContainer.get(index), stack.itemId(),
                stack.name(), stack.count()));
            index++;
        }

        if (index >= free && !transfers.isEmpty()) {
            warnings.add("the container filled before everything could be deposited");
        }

        return new Plan(transfers, warnings);
    }

    /**
     * Plans withdrawals from container slots into empty player slots.
     *
     * @param containerStacks the container's occupied slots
     * @param emptyPlayer     the player's empty slot ids, in the order they should be filled
     * @param withdraw        the categories the user selected to withdraw
     * @param protectedItems  item ids that must never move
     */
    public static Plan planWithdraw(List<Stack> containerStacks, List<Integer> emptyPlayer,
                                    Set<StorageCategory> withdraw, Set<String> protectedItems) {
        List<String> warnings = new ArrayList<>();
        List<Transfer> transfers = new ArrayList<>();

        Set<Integer> used = new HashSet<>();
        int free = emptyPlayer.size();
        int index = 0;

        for (Stack stack : containerStacks) {
            if (index >= free) break;
            if (!withdraw.contains(stack.category())) continue;
            if (protectedItems.contains(stack.itemId())) continue;

            int target = emptyPlayer.get(index);
            if (used.contains(target)) continue;
            used.add(target);

            transfers.add(new Transfer(stack.slotId(), target, stack.itemId(), stack.name(), stack.count()));
            index++;
        }

        if (index >= free && !transfers.isEmpty()) {
            warnings.add("the inventory filled before everything could be withdrawn");
        }

        return new Plan(transfers, warnings);
    }

    /** Converts transfers into the swap moves the container operation manager executes. */
    public static List<SortPlanner.Move> toMoves(List<Transfer> transfers) {
        List<SortPlanner.Move> moves = new ArrayList<>(transfers.size());
        for (Transfer transfer : transfers) {
            moves.add(new SortPlanner.Move(SortPlanner.Kind.SWAP, transfer.fromSlotId(), transfer.toSlotId(),
                transfer.count()));
        }
        return moves;
    }

    /**
     * Plans a pull of specific items from a container into empty player slots, for a restock.
     *
     * <p>Unlike {@link #planWithdraw}, which works by category, this works by item and amount: the caller says
     * how many of each item are needed and this fills the need from the container's stacks. A whole stack is
     * moved at a time - a shift-click cannot move part of a stack - so a need of 5 diamond may be satisfied by
     * a stack of 64. The need is decremented by the amount actually taken, so it never over-pulls across
     * containers.
     *
     * @param containerStacks the container's occupied slots
     * @param emptyPlayer     the player's empty slot ids
     * @param needByItem      how many of each item (by item id) are still needed
     * @param protectedItems  item ids that must never move
     */
    public static Plan planPull(List<Stack> containerStacks, List<Integer> emptyPlayer,
                                Map<String, Integer> needByItem, Set<String> protectedItems) {
        List<String> warnings = new ArrayList<>();
        List<Transfer> transfers = new ArrayList<>();

        Map<String, Integer> remaining = new java.util.LinkedHashMap<>(needByItem);
        int free = emptyPlayer.size();
        int index = 0;

        for (Stack stack : containerStacks) {
            if (index >= free) break;

            Integer needed = remaining.get(stack.itemId());
            if (needed == null || needed <= 0) continue;
            if (protectedItems.contains(stack.itemId())) continue;

            transfers.add(new Transfer(stack.slotId(), emptyPlayer.get(index), stack.itemId(), stack.name(),
                stack.count()));
            index++;

            int left = needed - stack.count();
            if (left <= 0) remaining.remove(stack.itemId());
            else remaining.put(stack.itemId(), left);
        }

        if (index >= free && !transfers.isEmpty()) {
            warnings.add("the inventory filled before the restock was complete");
        }

        return new Plan(transfers, warnings);
    }
}
