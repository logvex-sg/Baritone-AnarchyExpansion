package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.core.logic.RestockPlanner;
import dev.basehunt.bhclient.utils.ContainerHelper;
import dev.basehunt.bhclient.utils.ServerUtils;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the inputs the {@link RestockPlanner} needs from the live player and the storage registry.
 *
 * <p>Everything here is a read: it counts what the player carries, and what the registry last saw inside
 * containers that are reachable right now. Unreachable containers are excluded rather than promised, so a
 * restock plan never assumes items that are in an unloaded chunk.
 */
public final class RestockManager {
    private RestockManager() {
    }

    /** How many of each item the player carries, keyed by full item id. */
    public static Map<String, Integer> carriedByItem() {
        Map<String, Integer> carried = new HashMap<>();

        var mc = ServerUtils.mc();
        if (mc == null || mc.player == null) return carried;

        PlayerInventory inventory = mc.player.getInventory();
        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            carried.merge(ContainerHelper.fullItemId(stack), stack.getCount(), Integer::sum);
        }

        return carried;
    }

    /** Every stack of every tracked item available in a reachable registered container. */
    public static List<RestockPlanner.Stock> reachableStock() {
        List<RestockPlanner.Stock> stock = new ArrayList<>();

        StorageRegistry registry = StorageRegistry.get();
        if (registry == null) return stock;

        for (StorageRegistry.Container container : registry.forCurrentServer()) {
            if (!ContainerHelper.isReachable(container.pos)) continue;
            if (!container.verified) continue;

            for (StorageRegistry.Observed observed : container.contents()) {
                stock.add(new RestockPlanner.Stock(container.id, container.describe(),
                    observed.itemId(), observed.count()));
            }
        }

        return stock;
    }

    /** Plans a restock for a profile against the current player and reachable storage. */
    public static RestockPlanner.Plan planProfile(String profileName) {
        RestockPlanner.Profile profile = RestockPlanner.profile(profileName);
        if (profile == null) profile = RestockPlanner.profile("travel");

        var mc = ServerUtils.mc();
        int freeSlots = mc == null || mc.player == null ? 0 : countFreeSlots();

        return RestockPlanner.plan(carriedByItem(), reachableStock(), profile.rules(), freeSlots);
    }

    /** Plans a restock for explicit rules. */
    public static RestockPlanner.Plan planRules(List<RestockPlanner.Rule> rules) {
        int freeSlots = countFreeSlots();
        return RestockPlanner.plan(carriedByItem(), reachableStock(), rules, freeSlots);
    }

    /** Number of empty main/hotbar slots the player has. */
    public static int countFreeSlots() {
        var mc = ServerUtils.mc();
        if (mc == null || mc.player == null) return 0;

        int free = 0;
        PlayerInventory inventory = mc.player.getInventory();
        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            if (inventory.getStack(slot).isEmpty()) free++;
        }
        return free;
    }

    /** How many of a specific item the player carries. */
    public static int carried(String itemId) {
        return carriedByItem().getOrDefault(itemId, 0);
    }
}
