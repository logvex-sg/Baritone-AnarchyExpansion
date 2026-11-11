package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.core.logic.ItemClassifier;
import dev.basehunt.bhclient.core.logic.ItemFilter;
import dev.basehunt.bhclient.core.logic.ProtectionGuard;
import dev.basehunt.bhclient.core.logic.ProtectionRules;
import dev.basehunt.bhclient.core.logic.QuantityPlanner;
import dev.basehunt.bhclient.core.logic.SortPlanner;
import dev.basehunt.bhclient.core.logic.StorageCategory;
import dev.basehunt.bhclient.core.logic.TransferPlanner;
import dev.basehunt.bhclient.utils.ContainerHelper;
import dev.basehunt.bhclient.utils.ServerUtils;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a live container screen into the pure-logic planner inputs and back into an executable operation.
 *
 * <p>All the "read the world" work lives here so the modules stay thin: a module decides <em>what</em> to do
 * (sort, deposit, restock), calls one method here, and gets an operation queued on the
 * {@link ContainerInteractionManager}. The planners themselves are pure and tested; this class is the thin
 * adapter that keeps the mapping between Minecraft's slot model and the planners' slot-id model in one place.
 *
 * <p>Player slots are identified by {@code slot.inventory instanceof PlayerInventory} with an index below the
 * armour range, rather than by assuming slot ids are laid out a particular way. That is what makes the same
 * code work for a chest screen, a shulker screen and the always-present player screen without a special case.
 */
public final class StorageOperations {
    private StorageOperations() {
    }

    // Player slot discovery

    /** True when a handler slot is one of the player's 36 main/hotbar slots. */
    public static boolean isPlayerSlot(Slot slot) {
        return slot.inventory instanceof PlayerInventory && slot.getIndex() < 36;
    }

    /** True when a handler slot is a container slot (anything that is not a player main/hotbar slot). */
    public static boolean isContainerSlot(Slot slot, ScreenHandler handler) {
        return !isPlayerSlot(slot) && handler.slots.size() > 36;
    }

    /** Player slot ids in player-index order (hotbar 0..8 first, then main 9..35). */
    public static List<Integer> playerSlotIds(ScreenHandler handler) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) if (isPlayerSlot(slot)) slots.add(slot);
        slots.sort(Comparator.comparingInt(Slot::getIndex));
        List<Integer> ids = new ArrayList<>(slots.size());
        for (Slot slot : slots) ids.add(slot.id);
        return ids;
    }

    /** Player slot ids for the 27 main inventory slots (indices 9..35), the usual sort target. */
    public static List<Integer> mainSlotIds(ScreenHandler handler) {
        List<Integer> ids = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (isPlayerSlot(slot) && slot.getIndex() >= 9) ids.add(slot.id);
        }
        ids.sort(Comparator.comparingInt(id -> handler.getSlot(id).getIndex()));
        return ids;
    }

    /** Player slot ids for the 9 hotbar slots. */
    public static List<Integer> hotbarSlotIds(ScreenHandler handler) {
        List<Integer> ids = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (isPlayerSlot(slot) && slot.getIndex() < 9) ids.add(slot.id);
        }
        ids.sort(Comparator.comparingInt(id -> handler.getSlot(id).getIndex()));
        return ids;
    }

    /** Container slot ids, in order. */
    public static List<Integer> containerSlotIds(ScreenHandler handler) {
        List<Integer> ids = new ArrayList<>();
        for (Slot slot : handler.slots) if (isContainerSlot(slot, handler)) ids.add(slot.id);
        return ids;
    }

    /** Empty player slot ids, in main-then-hotbar order. */
    public static List<Integer> emptyPlayerSlots(ScreenHandler handler) {
        List<Integer> ids = new ArrayList<>();
        for (int id : playerSlotIds(handler)) if (handler.getSlot(id).getStack().isEmpty()) ids.add(id);
        return ids;
    }

    /** Empty container slot ids, in order. */
    public static List<Integer> emptyContainerSlots(ScreenHandler handler) {
        List<Integer> ids = new ArrayList<>();
        for (int id : containerSlotIds(handler)) if (handler.getSlot(id).getStack().isEmpty()) ids.add(id);
        return ids;
    }

    // Stack reading

    /** The player's occupied stacks, in player-index order. */
    public static List<TransferPlanner.Stack> playerStacks(ScreenHandler handler) {
        List<TransferPlanner.Stack> result = new ArrayList<>();
        for (int id : playerSlotIds(handler)) {
            ItemStack stack = handler.getSlot(id).getStack();
            if (stack.isEmpty()) continue;
            result.add(new TransferPlanner.Stack(id, ContainerHelper.itemId(stack), stack.getName().getString(),
                ItemClassifier.classify(ContainerHelper.fullItemId(stack)), stack.getCount()));
        }
        return result;
    }

    /** The container's occupied stacks, in slot order. */
    public static List<TransferPlanner.Stack> containerStacks(ScreenHandler handler) {
        List<TransferPlanner.Stack> result = new ArrayList<>();
        for (int id : containerSlotIds(handler)) {
            ItemStack stack = handler.getSlot(id).getStack();
            if (stack.isEmpty()) continue;
            result.add(new TransferPlanner.Stack(id, ContainerHelper.itemId(stack), stack.getName().getString(),
                ItemClassifier.classify(ContainerHelper.fullItemId(stack)), stack.getCount()));
        }
        return result;
    }

    /** Turns player stacks into sort items, marking protected slots as locked. */
    public static List<SortPlanner.SortItem> sortItems(List<TransferPlanner.Stack> stacks, Set<Integer> lockedIds,
                                                       Set<String> protectedItems) {
        List<SortPlanner.SortItem> items = new ArrayList<>(stacks.size());

        for (TransferPlanner.Stack stack : stacks) {
            boolean locked = lockedIds.contains(stack.slotId()) || protectedItems.contains(stack.itemId());
            items.add(new SortPlanner.SortItem(stack.slotId(), stack.itemId() + "#" + stack.slotId(),
                stack.name(), stack.category(), stack.count(), 64, locked, stack.category().isPrecious()));
        }

        return items;
    }

    // Operation construction

    /** Builds a sort plan for the given target slots from the live handler. */
    public static SortPlanner.Plan buildSort(ScreenHandler handler, List<Integer> targetSlots,
                                             List<StorageCategory> order, boolean merge,
                                             Set<Integer> lockedIds, Set<String> protectedItems) {
        List<SortPlanner.SortItem> items = sortItems(playerStacks(handler), lockedIds, protectedItems);
        return SortPlanner.plan(items, targetSlots, order, SortPlanner.byName(), merge);
    }

    /** Builds a deposit plan from the live handler. */
    public static TransferPlanner.Plan buildDeposit(ScreenHandler handler, Set<StorageCategory> deposit,
                                                    Map<StorageCategory, Integer> reserves,
                                                    Set<String> protectedItems) {
        return TransferPlanner.planDeposit(playerStacks(handler), emptyContainerSlots(handler),
            deposit, reserves, protectedItems);
    }

    /** Builds a withdrawal plan from the live handler. */
    public static TransferPlanner.Plan buildWithdraw(ScreenHandler handler, Set<StorageCategory> withdraw,
                                                     Set<String> protectedItems) {
        return TransferPlanner.planWithdraw(containerStacks(handler), emptyPlayerSlots(handler),
            withdraw, protectedItems);
    }

    // Container interaction

    /** Opens a container the player is looking at. Returns null on success, or a reason it could not. */
    public static String openLookingAt() {
        var mc = ServerUtils.mc();
        if (mc == null || mc.player == null || mc.world == null) return "no world";

        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != net.minecraft.util.hit.HitResult.Type.BLOCK) {
            return "not looking at a block";
        }

        BlockPos pos = hit.getBlockPos();
        if (ContainerHelper.containerTypeAt(pos) == null) return "the block you are looking at is not a container";
        if (!ContainerHelper.isReachable(pos)) return "the container is out of reach";

        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
        return null;
    }

    /** Opens a specific container block already within reach. Returns null on success, or a reason. */
    public static String openContainerBlock(BlockPos pos) {
        var mc = ServerUtils.mc();
        if (mc == null || mc.player == null || mc.world == null) return "no world";
        if (ContainerHelper.containerTypeAt(pos) == null) return "no container at that position";
        if (!ContainerHelper.isReachable(pos)) return "the container is out of reach";

        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
        mc.player.swingHand(Hand.MAIN_HAND);
        return null;
    }

    // Registry observation

    /** Records the container's current contents into the registry. */
    public static void observe(StorageRegistry.Container container, ScreenHandler handler) {
        if (container == null || handler == null) return;

        List<StorageRegistry.Observed> observed = new ArrayList<>();
        for (int id : containerSlotIds(handler)) {
            ItemStack stack = handler.getSlot(id).getStack();
            if (stack.isEmpty()) continue;
            observed.add(new StorageRegistry.Observed(ContainerHelper.fullItemId(stack),
                stack.getName().getString(), stack.getCount()));
        }

        StorageRegistry registry = StorageRegistry.get();
        if (registry != null) registry.recordContents(container, observed, emptyContainerSlots(handler).size());
    }

    // Exact-quantity transfer inputs

    /** The player's occupied stacks as quantity-plan sources. */
    public static List<QuantityPlanner.Source> playerSources(ScreenHandler handler) {
        List<QuantityPlanner.Source> sources = new ArrayList<>();
        for (int id : playerSlotIds(handler)) {
            ItemStack stack = handler.getSlot(id).getStack();
            if (stack.isEmpty()) continue;
            sources.add(new QuantityPlanner.Source(id, ContainerHelper.fullItemId(stack),
                stack.getName().getString(), ItemClassifier.classify(ContainerHelper.fullItemId(stack)),
                stack.getCount(), stack.getMaxCount()));
        }
        return sources;
    }

    /** The container's occupied stacks as quantity-plan sources. */
    public static List<QuantityPlanner.Source> containerSources(ScreenHandler handler) {
        List<QuantityPlanner.Source> sources = new ArrayList<>();
        for (int id : containerSlotIds(handler)) {
            ItemStack stack = handler.getSlot(id).getStack();
            if (stack.isEmpty()) continue;
            sources.add(new QuantityPlanner.Source(id, ContainerHelper.fullItemId(stack),
                stack.getName().getString(), ItemClassifier.classify(ContainerHelper.fullItemId(stack)),
                stack.getCount(), stack.getMaxCount()));
        }
        return sources;
    }

    /**
     * The player's slots that can receive an item: empty slots first, then partial stacks of the same item.
     * Protected player slots are excluded, so a withdrawal can never place items into a protected hotbar slot.
     */
    public static List<QuantityPlanner.Destination> playerDestinations(ScreenHandler handler, String itemId,
                                                                       Set<Integer> protectedPlayerIndices) {
        List<QuantityPlanner.Destination> empty = new ArrayList<>();
        List<QuantityPlanner.Destination> partial = new ArrayList<>();

        for (int id : playerSlotIds(handler)) {
            int index = playerIndex(handler, id);
            if (protectedPlayerIndices != null && protectedPlayerIndices.contains(index)) continue;

            ItemStack stack = handler.getSlot(id).getStack();
            if (stack.isEmpty()) {
                empty.add(new QuantityPlanner.Destination(id, null, 0, 64));
            } else if (itemId != null && ContainerHelper.fullItemId(stack).equals(itemId)
                && stack.getCount() < stack.getMaxCount()) {
                partial.add(new QuantityPlanner.Destination(id, ContainerHelper.fullItemId(stack),
                    stack.getCount(), stack.getMaxCount()));
            }
        }

        // Merge into partial stacks first (fewer slots used), then fill empties.
        partial.addAll(empty);
        return partial;
    }

    /** The container's slots that can receive an item, empty slots first then matching partial stacks. */
    public static List<QuantityPlanner.Destination> containerDestinations(ScreenHandler handler, String itemId) {
        List<QuantityPlanner.Destination> empty = new ArrayList<>();
        List<QuantityPlanner.Destination> partial = new ArrayList<>();

        for (int id : containerSlotIds(handler)) {
            ItemStack stack = handler.getSlot(id).getStack();
            if (stack.isEmpty()) {
                empty.add(new QuantityPlanner.Destination(id, null, 0, 64));
            } else if (itemId != null && ContainerHelper.fullItemId(stack).equals(itemId)
                && stack.getCount() < stack.getMaxCount()) {
                partial.add(new QuantityPlanner.Destination(id, ContainerHelper.fullItemId(stack),
                    stack.getCount(), stack.getMaxCount()));
            }
        }

        partial.addAll(empty);
        return partial;
    }

    /** The player-inventory index (0..35) of a handler slot, or -1 when it is a container/armour slot. */
    public static int playerIndex(ScreenHandler handler, int slotId) {
        Slot slot = handler.getSlot(slotId);
        if (slot == null || !isPlayerSlot(slot)) return -1;
        return slot.getIndex();
    }

    /** How many of a matching item the player currently carries. */
    public static int countInPlayer(ScreenHandler handler, ItemFilter filter, Set<String> protectedItems) {
        int total = 0;
        for (QuantityPlanner.Source source : playerSources(handler)) {
            if (protectedItems != null && protectedItems.contains(source.itemId())) continue;
            if (filter.matches(source.itemId(), source.name(), source.category())) total += source.count();
        }
        return total;
    }

    /** How many of a matching item the open container currently holds. */
    public static int countInContainer(ScreenHandler handler, ItemFilter filter, Set<String> protectedItems) {
        int total = 0;
        for (QuantityPlanner.Source source : containerSources(handler)) {
            if (protectedItems != null && protectedItems.contains(source.itemId())) continue;
            if (filter.matches(source.itemId(), source.name(), source.category())) total += source.count();
        }
        return total;
    }

    /**
     * Refuses any move that touches a protected slot or moves a protected item, returning the moves that are
     * safe to send. This is where the hotbar protection is enforced: not in a setting, but on the exact moves
     * that are about to be clicked.
     */
    public static List<SortPlanner.Move> enforceProtection(ScreenHandler handler, List<SortPlanner.Move> moves,
                                                           ProtectionGuard guard, List<String> refusedOut) {
        List<SortPlanner.Move> allowed = new ArrayList<>(moves.size());

        for (SortPlanner.Move move : moves) {
            int fromIndex = playerIndex(handler, move.fromSlot());
            int toIndex = playerIndex(handler, move.toSlot());
            String item = ContainerHelper.fullItemId(handler.getSlot(move.fromSlot()).getStack());

            String reason = guard.check(fromIndex, toIndex, item);
            if (reason == null) {
                allowed.add(move);
            } else if (refusedOut != null) {
                refusedOut.add("refused %s: %s".formatted(move.describe(), reason));
            }
        }

        return allowed;
    }
}
