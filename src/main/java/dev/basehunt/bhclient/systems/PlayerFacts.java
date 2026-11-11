package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.core.rule.Condition;
import dev.basehunt.bhclient.utils.ContainerHelper;
import dev.basehunt.bhclient.utils.ElytraManager;
import dev.basehunt.bhclient.utils.ServerUtils;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.HashMap;
import java.util.Map;

/**
 * The numeric facts the rule engine and the HUD both read, computed in one place.
 *
 * <p>A rule such as {@code "elytra_durability < 10"} needs a number, and the HUD needs the same number. If
 * each computed it separately they would eventually disagree, so both go through here. Every fact is a
 * best-effort read: when the player or the world is missing the fact is simply absent, and a condition on an
 * absent fact is false rather than a crash.
 */
public final class PlayerFacts {
    private PlayerFacts() {
    }

    /** A snapshot of the facts, taken once per evaluation so every condition sees the same world. */
    public static final class Snapshot implements Condition.FactSource {
        private final Map<String, Double> values;

        Snapshot(Map<String, Double> values) {
            this.values = values;
        }

        @Override
        public Double fact(String name) {
            return values.get(name);
        }

        public Map<String, Double> values() {
            return values;
        }
    }

    /** Reads the current facts. */
    public static Snapshot snapshot() {
        Map<String, Double> values = new HashMap<>();

        var mc = ServerUtils.mc();
        if (mc == null || mc.player == null) return new Snapshot(values);

        var player = mc.player;
        values.put("health", (double) player.getHealth());
        values.put("max_health", (double) player.getMaxHealth());
        values.put("hunger", (double) player.getHungerManager().getFoodLevel());
        values.put("absorption", (double) player.getAbsorptionAmount());
        values.put("air", (double) player.getAir());
        values.put("xp_level", (double) player.experienceLevel);
        values.put("on_ground", player.isOnGround() ? 1.0 : 0.0);
        values.put("falling", player.getVelocity().y < -0.5 ? 1.0 : 0.0);
        values.put("gliding", player.isGliding() ? 1.0 : 0.0);
        values.put("in_water", player.isTouchingWater() ? 1.0 : 0.0);

        PlayerInventory inventory = player.getInventory();

        int freeSlots = 0;
        int totems = 0;
        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                freeSlots++;
                continue;
            }
            if (stack.isOf(Items.TOTEM_OF_UNDYING)) totems += stack.getCount();
        }
        // Elytra facts. The worn elytra is read from the chest slot, not the inventory scan.
        values.put("elytra_durability", (double) ElytraManager.equippedRemaining());
        values.put("elytra_durability_pct", ElytraManager.equippedPercent());
        values.put("spare_elytra", (double) ElytraManager.spareCount());

        values.put("free_slots", (double) freeSlots);
        values.put("inventory_used_pct", (double) (PlayerInventory.MAIN_SIZE - freeSlots) * 100.0 / PlayerInventory.MAIN_SIZE);
        values.put("totems", (double) totems);

        // Nearest player distance, when anyone is around.
        var nearest = player.networkHandler == null ? null : nearestPlayer();
        values.put("nearby_players", (double) nearbyPlayerCount());
        if (nearest != null) values.put("nearest_player_distance", nearest);

        AutomationEngine engine = AutomationEngine.get();
        values.put("task_queue", engine == null ? 0.0 : (double) engine.queue().queueSize());

        return new Snapshot(values);
    }

    private static int nearbyPlayerCount() {
        var mc = ServerUtils.mc();
        if (mc == null || mc.world == null || mc.player == null) return 0;
        int count = 0;
        for (var entity : mc.world.getPlayers()) {
            if (entity == mc.player) continue;
            if (entity.distanceTo(mc.player) <= 256) count++;
        }
        return count;
    }

    private static Double nearestPlayer() {
        var mc = ServerUtils.mc();
        if (mc == null || mc.world == null || mc.player == null) return null;
        double best = Double.MAX_VALUE;
        for (var entity : mc.world.getPlayers()) {
            if (entity == mc.player) continue;
            best = Math.min(best, entity.distanceTo(mc.player));
        }
        return best == Double.MAX_VALUE ? null : best;
    }

    /** True when the block under the player is a container, for the "standing on storage" hint. */
    public static boolean standingOnContainer() {
        var mc = ServerUtils.mc();
        if (mc == null || mc.player == null) return false;
        return ContainerHelper.containerTypeAt(mc.player.getBlockPos().down()) != null;
    }
}
