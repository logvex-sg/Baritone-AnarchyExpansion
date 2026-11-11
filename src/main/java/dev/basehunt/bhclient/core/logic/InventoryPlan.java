package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans ender chest and inventory moves without touching the game.
 *
 * <p>The plan is produced before anything is clicked so it can be shown to the user as a preview and so the
 * reserve rules can be applied consistently. Two safety rules are encoded here rather than in the caller:
 * a category's reserve is never broken by a deposit, and precious categories are only ever moved when the
 * user has explicitly selected them - so a stray "deposit everything" can never empty out the elytra or the
 * totems.
 */
public final class InventoryPlan {

    /** One stack observed in a container, described for planning. */
    public record Entry(int slotId, ItemCategory category, String name, int count) {
    }

    /** A single planned move. */
    public record Move(int slotId, ItemCategory category, String name, int count, boolean deposit) {
        public String describe() {
            return "%s %dx %s %s".formatted(deposit ? "deposit" : "withdraw", count, name,
                deposit ? "to the ender chest" : "to the inventory");
        }
    }

    private InventoryPlan() {
    }

    /**
     * Builds the deposit plan: player-inventory slots whose category is selected and whose stack is not
     * needed to satisfy that category's reserve.
     *
     * <p>Moves are whole-stack, because a shift-click cannot move part of a stack. A stack that would only
     * partly fit inside the reserve is left alone entirely, so the reserve is never broken.
     *
     * @param playerEntries items currently carried
     * @param deposit       categories to move into the ender chest
     * @param reserves      minimum number of items to keep per category; a missing entry means zero
     */
    public static List<Move> planDeposit(List<Entry> playerEntries, Set<ItemCategory> deposit, Map<ItemCategory, Integer> reserves) {
        Map<ItemCategory, Integer> remainingReserve = new EnumMap<>(ItemCategory.class);
        for (ItemCategory category : deposit) {
            remainingReserve.put(category, Math.max(0, reserves.getOrDefault(category, 0)));
        }

        List<Move> moves = new ArrayList<>();

        for (Entry entry : playerEntries) {
            if (!deposit.contains(entry.category())) continue;

            int reserve = remainingReserve.getOrDefault(entry.category(), 0);

            if (entry.count() <= reserve) {
                // This whole stack is part of the reserve; nothing leaves.
                remainingReserve.put(entry.category(), reserve - entry.count());
                continue;
            }

            moves.add(new Move(entry.slotId(), entry.category(), entry.name(), entry.count(), true));
        }

        return moves;
    }

    /**
     * Builds the withdrawal plan: chest slots whose category is selected, limited by how many slots the
     * player has free.
     *
     * @param chestEntries items in the ender chest
     * @param withdraw     categories to pull out
     * @param freeSlots    empty player inventory slots
     */
    public static List<Move> planWithdraw(List<Entry> chestEntries, Set<ItemCategory> withdraw, int freeSlots) {
        List<Move> moves = new ArrayList<>();
        if (freeSlots <= 0) return moves;

        for (Entry entry : chestEntries) {
            if (moves.size() >= freeSlots) break;
            if (!withdraw.contains(entry.category())) continue;
            moves.add(new Move(entry.slotId(), entry.category(), entry.name(), entry.count(), false));
        }

        return moves;
    }

    /**
     * True when it is safe to deposit this stack. A precious category is only moved when explicitly selected;
     * this is the guard that keeps a bulk deposit from throwing away the elytra or the totems.
     */
    public static boolean isSafeToDeposit(Entry entry, Set<ItemCategory> deposit) {
        if (!deposit.contains(entry.category())) return false;
        return !entry.category().isPrecious() || deposit.contains(entry.category());
    }

    /** Human-readable preview of a plan, one move per line. */
    public static List<String> preview(List<Move> moves) {
        List<String> lines = new ArrayList<>(moves.size());
        for (Move move : moves) lines.add(move.describe());
        return lines;
    }
}
