package dev.basehunt.bhclient.utils;

import dev.basehunt.bhclient.core.logic.ShulkerInventory;
import net.minecraft.block.AbstractChestBlock;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads container blocks and shulker-box contents out of the world and the player's items.
 *
 * <p>Two facts drive the design. First, a shulker box's contents are known to the client through the item's
 * {@code CONTAINER} component, so a box in the inventory can be searched without opening it - but only when
 * that component is present, which is why {@link ShulkerInventory.Box#contentsKnown()} exists and an
 * un-inspected box is never treated as empty. Second, a container is only usable when it is genuinely within
 * reach and its chunk is loaded, so {@link #isReachable} is the check every routing destination goes through
 * before it is offered as a target.
 */
public final class ContainerHelper {
    /** Interaction reach in blocks, matching the vanilla player reach. */
    public static final double REACH = 4.5;

    private ContainerHelper() {
    }

    /** A human-readable container type for a block, or null when the block is not a supported container. */
    public static String containerType(Block block) {
        if (block instanceof ShulkerBoxBlock) return "shulker";
        if (block instanceof EnderChestBlock) return "ender chest";
        if (block instanceof BarrelBlock) return "barrel";
        if (block instanceof AbstractChestBlock<?>) return "chest";
        return null;
    }

    /** True when a block is a container the storage system can open and use. */
    public static boolean isContainerBlock(Block block) {
        return containerType(block) != null;
    }

    /** The container type at a position, or null when the chunk is not loaded or the block is not one. */
    public static String containerTypeAt(BlockPos pos) {
        var mc = ServerUtils.mc();
        if (mc == null || mc.world == null) return null;
        if (!mc.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return null;

        BlockState state = mc.world.getBlockState(pos);
        return containerType(state.getBlock());
    }

    /**
     * True when a container at {@code pos} can actually be opened right now: the chunk is loaded, the block is
     * a container, and it is within reach. A registered container that fails this is skipped by routing rather
     * than clicked at.
     */
    public static boolean isReachable(BlockPos pos) {
        var mc = ServerUtils.mc();
        if (mc == null || mc.player == null || mc.world == null) return false;
        if (!mc.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        if (containerTypeAt(pos) == null) return false;

        return mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) <= REACH;
    }

    /**
     * The number of rows a chest-like container has, from the live screen handler rather than a guess: a
     * double chest is six rows, a single chest three, and a barrel three.
     */
    public static int rowsFor(net.minecraft.screen.ScreenHandler handler) {
        if (handler instanceof net.minecraft.screen.GenericContainerScreenHandler generic) return generic.getRows();
        if (handler instanceof net.minecraft.screen.ShulkerBoxScreenHandler) return 3;
        return Math.max(0, (handler.slots.size() - 36) / 9);
    }

    /** Reads a shulker box's contents from its item component, or reports them unknown when absent. */
    public static ShulkerInventory.Box readShulker(ItemStack stack, int slotIndex, String fallbackLabel) {
        boolean known = false;
        List<ShulkerInventory.Content> contents = new ArrayList<>();

        if (stack != null && !stack.isEmpty()) {
            ContainerComponent component = stack.get(DataComponentTypes.CONTAINER);
            if (component != null) {
                known = true;
                for (ItemStack inner : component.iterateNonEmpty()) {
                    if (inner == null || inner.isEmpty()) continue;
                    contents.add(new ShulkerInventory.Content(
                        Registries.ITEM.getId(inner.getItem()).getPath(),
                        inner.getName().getString(), inner.getCount()));
                }
            }
        }

        String label = fallbackLabel;
        if (stack != null && !stack.isEmpty() && stack.contains(DataComponentTypes.CUSTOM_NAME)) {
            label = stack.getName().getString();
        }

        return new ShulkerInventory.Box(slotIndex, label == null ? "shulker" : label, contents, known, 27);
    }

    /** True when an item is a shulker box of any colour. */
    public static boolean isShulker(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getItem() instanceof net.minecraft.item.BlockItem blockItem
            && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    /** The item id path for a stack, e.g. {@code "diamond"}. */
    public static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        return Registries.ITEM.getId(stack.getItem()).getPath();
    }

    /** The full item id for a stack, e.g. {@code "minecraft:diamond"}. */
    public static String fullItemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    /** A stable key for a stack that treats two stacks of the same item and components as mergeable. */
    public static String stackKey(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return Registries.ITEM.getId(stack.getItem()).toString() + "#" + stack.getComponents().hashCode();
    }
}
