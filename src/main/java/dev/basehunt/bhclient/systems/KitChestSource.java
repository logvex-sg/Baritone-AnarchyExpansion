package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.core.sixbt.ChestKitSearch;
import dev.basehunt.bhclient.core.sixbt.KitLoadout;
import dev.basehunt.bhclient.utils.ServerUtils;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds a registered kit among the containers the bot has already read.
 *
 * <p>A kit bot is not a walking chest: it is restocked from storage. The addon's {@link StorageRegistry}
 * remembers what each container held the last time it was opened, and this class turns those observations
 * into the {@link ChestKitSearch} inputs - so a request for {@code totem} becomes "which container near me
 * was last seen holding the totem kit's items, and is the whole kit there?".
 *
 * <p>Only containers on the current server, in the current dimension and within {@code radius} blocks are
 * considered, and the nearest full match wins. The player's own inventory is offered as a source too, so a
 * bot that is already carrying the kit needs no chest at all.
 *
 * <p><b>Reading nearby chests directly.</b> Minecraft does not send a container's contents to the client
 * until that container has been opened, so a chest the bot has never opened reads as empty and cannot be
 * searched. This class therefore reads the live inventory of every loaded container block entity it can (which
 * is populated for anything opened this session, and for shulker boxes) <em>and</em> falls back to the
 * registry's last-observed contents. A container that has never been opened contributes nothing rather than a
 * false "empty" - the honest answer, and the reason the bot reports "not stocked" instead of guessing.
 *
 * <p>This class only <em>decides</em> which container to use; opening it and moving the items is the
 * runtime's job, through the one {@link ContainerInteractionManager} that owns the inventory.
 */
public final class KitChestSource {

    private KitChestSource() {
    }

    /** The containers within {@code radius} blocks of the player that the registry has contents for. */
    public static List<ChestKitSearch.Container> nearbyContainers(int radius) {
        List<ChestKitSearch.Container> containers = new ArrayList<>();
        MinecraftClient mc = ServerUtils.mc();
        if (mc == null || mc.player == null) return containers;

        BlockPos origin = mc.player.getBlockPos();

        // Live, loaded container block entities first: these are the freshest data the client has.
        readLoadedContainers(mc, origin, radius, containers);

        // Then the registry's last-observed contents, for containers whose chunk is not loaded right now.
        StorageRegistry registry = StorageRegistry.get();
        if (registry != null) {
            for (StorageRegistry.Container container : registry.forCurrentServer()) {
                if (container == null || container.pos == null) continue;
                if (!container.dimension.equals(ServerUtils.dimension())) continue;
                if (!container.pos.isWithinDistance(origin, radius)) continue;
                if (container.lastContents.isEmpty()) continue;
                if (containsAt(containers, container.pos)) continue;

                List<KitLoadout.ItemSpec> contents = new ArrayList<>();
                for (StorageRegistry.Observed observed : container.lastContents) {
                    if (observed == null || observed.itemId() == null) continue;
                    contents.add(new KitLoadout.ItemSpec(observed.itemId(), observed.count()));
                }
                String label = container.label == null || container.label.isBlank()
                    ? container.type : container.label;
                containers.add(new ChestKitSearch.Container(label, container.pos.getX(), container.pos.getY(),
                    container.pos.getZ(), contents));
            }
        }

        containers.sort(Comparator.comparingDouble(c -> squaredDistance(origin, c)));
        return containers;
    }

    /**
     * Reads the inventory of every loaded container block entity within {@code radius} whose contents the
     * client actually knows (a non-empty inventory), appending each as a searchable container.
     */
    private static void readLoadedContainers(MinecraftClient mc, BlockPos origin, int radius,
                                             List<ChestKitSearch.Container> out) {
        if (mc.world == null) return;
        int chunkRadius = (radius >> 4) + 1;

        for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
            for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
                int chunkX = (origin.getX() >> 4) + cx;
                int chunkZ = (origin.getZ() >> 4) + cz;
                if (!mc.world.isChunkLoaded(chunkX, chunkZ)) continue;

                WorldChunk chunk = mc.world.getChunk(chunkX, chunkZ);
                if (chunk == null) continue;

                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof LootableContainerBlockEntity container)) continue;
                    BlockPos pos = container.getPos();
                    if (!pos.isWithinDistance(origin, radius)) continue;
                    if (containsAt(out, pos)) continue;

                    Inventory inventory = container;
                    if (inventory.isEmpty()) continue; // Never opened: the client has no contents to trust.

                    List<KitLoadout.ItemSpec> contents = new ArrayList<>();
                    for (int slot = 0; slot < inventory.size(); slot++) {
                        ItemStack stack = inventory.getStack(slot);
                        if (stack.isEmpty()) continue;
                        String id = Registries.ITEM.getId(stack.getItem()).toString();
                        contents.add(new KitLoadout.ItemSpec(id, stack.getCount()));
                    }
                    if (contents.isEmpty()) continue;

                    String label = Registries.BLOCK_ENTITY_TYPE.getId(container.getType()).getPath();
                    out.add(new ChestKitSearch.Container(label, pos.getX(), pos.getY(), pos.getZ(), contents));
                }
            }
        }
    }

    private static boolean containsAt(List<ChestKitSearch.Container> containers, BlockPos pos) {
        for (ChestKitSearch.Container container : containers) {
            if (container.x() == pos.getX() && container.y() == pos.getY() && container.z() == pos.getZ()) {
                return true;
            }
        }
        return false;
    }

    /** The best nearby container for a kit, including the player's own inventory as a source. */
    public static ChestKitSearch.Result find(KitLoadout loadout, int radius) {
        if (loadout == null) return new ChestKitSearch.Result(null, 0, 0, 0, 0, 0, List.of());

        List<ChestKitSearch.Container> candidates = new ArrayList<>();
        ChestKitSearch.Container carried = carriedAsContainer();
        if (carried != null) candidates.add(carried);
        candidates.addAll(nearbyContainers(radius));

        return ChestKitSearch.find(candidates, loadout);
    }

    /** The player's main inventory as a searchable container, or null when not connected. */
    public static ChestKitSearch.Container carriedAsContainer() {
        MinecraftClient mc = ServerUtils.mc();
        if (mc == null || mc.player == null) return null;

        List<KitLoadout.ItemSpec> contents = new ArrayList<>();
        var inventory = mc.player.getInventory();
        for (int slot = 0; slot < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; slot++) {
            var stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;
            String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString();
            contents.add(new KitLoadout.ItemSpec(id, stack.getCount()));
        }
        BlockPos pos = mc.player.getBlockPos();
        return new ChestKitSearch.Container("inventory", pos.getX(), pos.getY(), pos.getZ(), contents);
    }

    private static double squaredDistance(BlockPos origin, ChestKitSearch.Container container) {
        double dx = container.x() - origin.getX();
        double dy = container.y() - origin.getY();
        double dz = container.z() - origin.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** A one-line report for chat and the terminal. */
    public static String describe(KitLoadout loadout, int radius) {
        return find(loadout, radius).summary();
    }
}
