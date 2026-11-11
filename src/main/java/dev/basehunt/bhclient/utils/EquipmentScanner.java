package dev.basehunt.bhclient.utils;

import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared helpers for finding and describing damageable equipment.
 *
 * <p>Kept separate from the Mending module so the "which items are Mending and damaged" question has one
 * answer used by the repair logic, the HUD and the webhook, rather than three slightly different loops.
 */
public final class EquipmentScanner {
    /** One damageable equipment stack and where it lives. */
    public record Equipped(String label, ItemStack stack, int armorEntitySlot, int inventorySlot, boolean worn) {
        /** Armour slots report {@code armorEntitySlot >= 0}; carried items report {@code inventorySlot >= 0}. */
        public boolean isArmor() {
            return armorEntitySlot >= 0;
        }
    }

    private EquipmentScanner() {
    }

    /** True when the stack carries the Mending enchantment. */
    public static boolean hasMending(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return Utils.hasEnchantments(stack, Enchantments.MENDING);
    }

    public static boolean isDamaged(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.isDamageable() && stack.getDamage() > 0;
    }

    public static int remaining(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageable()) return -1;
        return stack.getMaxDamage() - stack.getDamage();
    }

    /**
     * Worn armour (helmet, chest, legs, boots) plus the main and off hand.
     *
     * <p>Armour is reported with the equipment slot id so the repair logic can move it to the offhand and
     * back using {@code toArmor}/{@code fromArmor}.
     */
    public static List<Equipped> worn() {
        List<Equipped> result = new ArrayList<>();
        var player = ServerUtils.mc().player;
        if (player == null) return result;

        result.add(new Equipped("helmet", player.getEquippedStack(EquipmentSlot.HEAD), 3, -1, true));
        result.add(new Equipped("chest", player.getEquippedStack(EquipmentSlot.CHEST), 2, -1, true));
        result.add(new Equipped("legs", player.getEquippedStack(EquipmentSlot.LEGS), 1, -1, true));
        result.add(new Equipped("boots", player.getEquippedStack(EquipmentSlot.FEET), 0, -1, true));
        result.add(new Equipped("main hand", player.getMainHandStack(), -1, player.getInventory().getSelectedSlot(), true));
        result.add(new Equipped("off hand", player.getOffHandStack(), -1, -1, true));

        return result;
    }

    /**
     * Damaged Mending equipment in the main inventory (not the armour or offhand), so spares can be brought
     * out for repair without disturbing what is worn.
     */
    public static List<Equipped> damagedCarried() {
        List<Equipped> result = new ArrayList<>();
        var player = ServerUtils.mc().player;
        if (player == null) return result;

        for (int slot = 0; slot < PlayerInventory.MAIN_SIZE; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!hasMending(stack) || !isDamaged(stack)) continue;

            result.add(new Equipped(stack.getName().getString(), stack, -1, slot, false));
        }

        return result;
    }

    /** The armour entity slot id for a piece, or -1 when the stack is not worn armour. */
    public static int armorSlotOf(ItemStack stack) {
        var player = ServerUtils.mc().player;
        if (player == null) return -1;

        if (player.getEquippedStack(EquipmentSlot.HEAD) == stack) return 3;
        if (player.getEquippedStack(EquipmentSlot.CHEST) == stack) return 2;
        if (player.getEquippedStack(EquipmentSlot.LEGS) == stack) return 1;
        if (player.getEquippedStack(EquipmentSlot.FEET) == stack) return 0;
        return -1;
    }
}
