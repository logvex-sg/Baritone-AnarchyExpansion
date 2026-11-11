package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.core.logic.ShulkerInventory;
import dev.basehunt.bhclient.utils.ContainerHelper;
import dev.basehunt.bhclient.utils.ServerUtils;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and describes the shulker boxes the player carries.
 *
 * <p>Contents come from the item's {@code CONTAINER} component, which the client only has once it has seen
 * the box (the player opened it, or the server sent it). A box the client has never seen the inside of is
 * reported as {@link ShulkerInventory.Status#UNKNOWN} and is never treated as empty, so a "fill the empty
 * shulker" workflow can never pick a box that might be full.
 */
public final class ShulkerManager {
    private ShulkerManager() {
    }

    /** Every shulker box in the player's main inventory and hotbar, with contents when known. */
    public static List<ShulkerInventory.Box> carried() {
        List<ShulkerInventory.Box> boxes = new ArrayList<>();

        var mc = ServerUtils.mc();
        if (mc == null || mc.player == null) return boxes;

        PlayerInventory inventory = mc.player.getInventory();
        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!ContainerHelper.isShulker(stack)) continue;
            boxes.add(ContainerHelper.readShulker(stack, slot, "shulker"));
        }

        return boxes;
    }

    /** Boxes whose contents are known, i.e. the ones a search or a fill can reason about. */
    public static List<ShulkerInventory.Box> known() {
        List<ShulkerInventory.Box> known = new ArrayList<>();
        for (ShulkerInventory.Box box : carried()) if (box.contentsKnown()) known.add(box);
        return known;
    }

    /** How many shulker boxes the player carries. */
    public static int count() {
        return carried().size();
    }

    /** Search the carried boxes for an item. Unknown boxes are not reported as empty, just unsearched. */
    public static List<ShulkerInventory.Hit> find(String itemId) {
        return ShulkerInventory.find(carried(), itemId);
    }

    /** A one-line summary of the carried boxes, for the HUD and commands. */
    public static String status() {
        List<ShulkerInventory.Box> boxes = carried();
        if (boxes.isEmpty()) return "no shulker boxes carried";

        int empty = 0, partial = 0, full = 0, unknown = 0;
        for (ShulkerInventory.Box box : boxes) {
            switch (box.status()) {
                case EMPTY -> empty++;
                case PARTIAL -> partial++;
                case FULL -> full++;
                case UNKNOWN -> unknown++;
            }
        }

        return "%d shulker(s): %d empty, %d partial, %d full, %d unknown"
            .formatted(boxes.size(), empty, partial, full, unknown);
    }

    /** The box best suited to being filled, preferring a labelled one. */
    public static ShulkerInventory.Box chooseForFilling(String preferredLabel) {
        return ShulkerInventory.chooseForFilling(carried(), preferredLabel);
    }
}
