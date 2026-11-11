package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The single decision of whether a slot may be touched by an automated transfer.
 *
 * <p>This is enforcement, not a setting. Every move the runtime is about to click is checked here first, and
 * a move that touches a protected slot or moves a protected item is refused before a click is sent. The hotbar
 * is protected by default, as are the worn armour and the offhand, so a bulk deposit cannot empty the tools
 * the player is holding or the elytra on their back.
 *
 * <p>Protection is expressed in player-inventory index space (0..35, hotbar first) plus the four armour slots
 * and the offhand, matching how the rest of the storage code addresses slots. The class is pure, so the
 * "hotbar is always safe" guarantee is asserted by the headless suite rather than trusted.
 */
public final class ProtectionGuard {

    /** Player-inventory indices of the nine hotbar slots. */
    public static final int HOTBAR_SLOTS = 9;
    /** Player-inventory indices of the 27 main inventory slots (9..35). */
    public static final int MAIN_START = 9;

    private final Set<Integer> protectedIndices;
    private final Set<String> protectedItems;
    private final boolean protectArmorAndOffhand;
    private final int selectedSlot;

    private ProtectionGuard(Set<Integer> protectedIndices, Set<String> protectedItems,
                            boolean protectArmorAndOffhand, int selectedSlot) {
        this.protectedIndices = protectedIndices;
        this.protectedItems = protectedItems;
        this.protectArmorAndOffhand = protectArmorAndOffhand;
        this.selectedSlot = selectedSlot;
    }

    /**
     * Builds a guard.
     *
     * @param hotbarSlots            how many hotbar slots (from the left) to protect; the default is 9
     * @param extraProtectedIndices  additional player-inventory indices the user pinned
     * @param protectedItems         item ids that must never move
     * @param protectArmorAndOffhand keep the worn armour and the offhand off limits
     * @param selectedSlot           the currently selected hotbar index, always protected when in range
     */
    public static ProtectionGuard of(int hotbarSlots, Set<Integer> extraProtectedIndices,
                                     Set<String> protectedItems, boolean protectArmorAndOffhand,
                                     int selectedSlot) {
        Set<Integer> indices = new HashSet<>();
        int reserve = Math.max(0, Math.min(HOTBAR_SLOTS, hotbarSlots));
        for (int i = 0; i < reserve; i++) indices.add(i);
        if (extraProtectedIndices != null) indices.addAll(extraProtectedIndices);
        if (selectedSlot >= 0 && selectedSlot < HOTBAR_SLOTS) indices.add(selectedSlot);

        return new ProtectionGuard(indices, normaliseItems(protectedItems),
            protectArmorAndOffhand, selectedSlot);
    }

    /**
     * Normalises protected item ids to the namespace-stripped form the matcher uses, so
     * {@code "minecraft:elytra"} and {@code "elytra"} behave identically.
     */
    private static Set<String> normaliseItems(Set<String> items) {
        if (items == null || items.isEmpty()) return Set.of();
        Set<String> normalised = new HashSet<>();
        for (String item : items) {
            if (item == null || item.isBlank()) continue;
            String id = item.trim().toLowerCase(java.util.Locale.ROOT);
            int colon = id.indexOf(':');
            normalised.add(colon >= 0 ? id.substring(colon + 1) : id);
        }
        return normalised;
    }

    /** A guard that protects the whole hotbar and nothing else, for tests and simple callers. */
    public static ProtectionGuard defaultGuard() {
        return of(HOTBAR_SLOTS, Set.of(), Set.of(), true, -1);
    }

    public Set<Integer> protectedIndices() {
        return protectedIndices;
    }

    public Set<String> protectedItems() {
        return protectedItems;
    }

    public boolean protectsArmorAndOffhand() {
        return protectArmorAndOffhand;
    }

    public boolean isProtectedIndex(int playerIndex) {
        return protectedIndices.contains(playerIndex);
    }

    /** True when a move into or out of this player-inventory index is forbidden. */
    public boolean isProtected(int playerIndex) {
        if (playerIndex < 0) return false;
        return protectedIndices.contains(playerIndex);
    }

    /** True when an item id is on the protected list (namespace-insensitive). */
    public boolean isProtectedItem(String itemId) {
        return ProtectionRules.isProtectedItem(itemId, protectedItems);
    }

    /**
     * Checks a single planned move.
     *
     * @param fromPlayerIndex the source player index, or -1 when the source is a container slot
     * @param toPlayerIndex   the destination player index, or -1 when the destination is a container slot
     * @param itemId          the item being moved
     * @return null when the move is allowed, or a reason it is refused
     */
    public String check(int fromPlayerIndex, int toPlayerIndex, String itemId) {
        if (isProtectedItem(itemId)) return "the item " + itemId + " is protected";
        if (fromPlayerIndex >= 0 && isProtected(fromPlayerIndex)) {
            return "slot " + fromPlayerIndex + " is protected";
        }
        if (toPlayerIndex >= 0 && isProtected(toPlayerIndex)) {
            return "slot " + toPlayerIndex + " is protected";
        }
        return null;
    }

    /**
     * Filters a list of moves down to the ones that are allowed, and reports the refused ones. Used before an
     * operation is submitted so a protected move is dropped with a warning rather than clicked.
     */
    public FilterResult filter(List<SortPlanner.Move> moves, List<String> itemIdsByMove) {
        List<SortPlanner.Move> allowed = new ArrayList<>();
        List<String> refused = new ArrayList<>();

        for (int i = 0; i < moves.size(); i++) {
            SortPlanner.Move move = moves.get(i);
            String item = i < itemIdsByMove.size() ? itemIdsByMove.get(i) : "";
            String reason = check(move.fromSlot(), move.toSlot(), item);
            if (reason == null) allowed.add(move);
            else refused.add("refused %s: %s".formatted(move.describe(), reason));
        }

        return new FilterResult(allowed, refused);
    }

    public record FilterResult(List<SortPlanner.Move> allowed, List<String> refused) {
        public boolean anyRefused() {
            return !refused.isEmpty();
        }
    }
}
