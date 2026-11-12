package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.ControlResource;
import dev.basehunt.bhclient.core.Priority;
import dev.basehunt.bhclient.core.logic.MendingPriorities;
import dev.basehunt.bhclient.utils.EquipmentScanner;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Repairs damaged Mending equipment using XP that is genuinely available.
 *
 * <p>The distinction this module makes that Meteor's built-in AutoMend does not: it never swaps a damaged item
 * into the offhand unless there is an XP source to actually repair it. Without that check the module shuffles
 * inventory around for no benefit, and worse, can leave a damaged item in the offhand when no XP is coming.
 *
 * <p>XP is treated as scarce. The item with the most missing durability is repaired first (see
 * {@link MendingPriorities}), an item that is already whole is never selected, and the item that was in the
 * offhand before the repair is put back afterwards so a totem or weapon is not left displaced.
 */
public class MendingManager extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargets = settings.createGroup("Targets");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    // General

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print repair activity in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minMissingDurability = sgGeneral.add(new IntSetting.Builder()
        .name("min-missing-durability")
        .description("Do not bother repairing an item until this much durability is missing.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 200)
        .build()
    );

    private final Setting<Integer> tickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("tick-delay")
        .description("Ticks to wait between inventory moves, to let the server keep up.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    // Targets

    private final Setting<Boolean> repairElytra = sgTargets.add(new BoolSetting.Builder()
        .name("repair-elytra")
        .description("Repair Mending elytras.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> repairCarried = sgTargets.add(new BoolSetting.Builder()
        .name("repair-carried")
        .description("Repair damaged Mending items carried in the main inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> carriedPriorityWeight = sgTargets.add(new IntSetting.Builder()
        .name("carried-priority")
        .description("Higher weight means carried items are repaired before other equal-damage items.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 100)
        .visible(repairCarried::get)
        .build()
    );

    // Safety

    private final Setting<Boolean> keepTotem = sgSafety.add(new BoolSetting.Builder()
        .name("keep-totem")
        .description("Never displace a totem of undying from the offhand.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> xpOrbRadius = sgSafety.add(new IntSetting.Builder()
        .name("xp-orb-radius")
        .description("Radius in which incoming XP orbs count as an available XP source.")
        .defaultValue(16)
        .min(1)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<Boolean> useBottles = sgSafety.add(new BoolSetting.Builder()
        .name("use-xp-bottles")
        .description("Treat carried bottles o' enchanting as an available XP source.")
        .defaultValue(false)
        .build()
    );

    // State

    private int tickCounter;
    private int carriedSlot = -1;
    private int displacedSlot = -1;
    private ItemStack displaced = ItemStack.EMPTY;
    private String lastRepairedName;

    public MendingManager() {
        super(BaseHuntingAddon.CATEGORY, "mending-manager",
            "Repairs damaged Mending equipment, but only when XP is actually available.");
    }

    @Override
    public void onActivate() {
        resetState();
    }

    @Override
    public void onDeactivate() {
        restoreOffhand();
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.releaseAll(name);
    }

    private void resetState() {
        tickCounter = 0;
        carriedSlot = -1;
        displacedSlot = -1;
        displaced = ItemStack.EMPTY;
        lastRepairedName = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (tickCounter > 0) {
            tickCounter--;
            return;
        }

        // If a repair item is currently in the offhand, finish the cycle when it is whole or XP has run out.
        if (carriedSlot >= 0) {
            ItemStack offhand = mc.player.getOffHandStack();
            boolean done = !EquipmentScanner.hasMending(offhand) || !EquipmentScanner.isDamaged(offhand);
            if (done || !hasXpSource()) {
                if (done) {
                    lastRepairedName = offhand.getName().getString();
                    if (chatFeedback.get()) info("Repaired %s.", lastRepairedName);
                    publish(AutomationEvent.Type.MENDING_REPAIR_DONE, Map.of("item", lastRepairedName));
                }
                restoreOffhand();
            }
            return;
        }

        if (!hasXpSource()) return;

        int slot = selectTarget();
        if (slot < 0) return;

        // Mending moves items into the offhand, so it must own the inventory while it works. A higher
        // priority holder (e.g. a forced deposit) wins and the repair waits for the next tick.
        AutomationManager manager = AutomationManager.get();
        if (manager != null && manager.tryAcquire(name, Priority.EQUIPMENT_RECOVERY,
                java.util.Set.of(ControlResource.INVENTORY)) == null) {
            return;
        }

        beginRepair(slot);
    }

    /**
     * Selects the inventory slot of the item to repair. Worn armour is not moved here - only carried items
     * and the worn elytra (which lives in the chest slot and cannot simply be moved to the offhand).
     */
    private int selectTarget() {
        List<MendingPriorities.Candidate> candidates = new ArrayList<>();

        if (repairCarried.get()) {
            for (EquipmentScanner.Equipped equipped : EquipmentScanner.damagedCarried()) {
                ItemStack stack = equipped.stack();
                candidates.add(new MendingPriorities.Candidate(
                    stack.getName().getString(),
                    equipped.inventorySlot(),
                    EquipmentScanner.remaining(stack),
                    stack.getMaxDamage(),
                    false,
                    carriedPriorityWeight.get()
                ));
            }
        }

        if (repairElytra.get()) {
            ItemStack chest = mc.player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST);
            if (chest.getItem() == Items.ELYTRA && EquipmentScanner.hasMending(chest) && EquipmentScanner.isDamaged(chest)) {
                int slot = findInventorySlotFor(chest);
                if (slot >= 0) {
                    candidates.add(new MendingPriorities.Candidate(
                        "elytra", slot, EquipmentScanner.remaining(chest), chest.getMaxDamage(), true, 50));
                }
            }
        }

        int slot = MendingPriorities.selectSlot(candidates);
        if (slot < 0) return -1;

        // Only repair if the item is damaged enough to be worth it.
        for (MendingPriorities.Candidate candidate : candidates) {
            if (candidate.slot() == slot) {
                if (!MendingPriorities.shouldRepair(true, candidate.missing(), minMissingDurability.get())) return -1;
                break;
            }
        }

        return slot;
    }

    private int findInventorySlotFor(ItemStack stack) {
        for (int slot = 0; slot < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; slot++) {
            if (mc.player.getInventory().getStack(slot) == stack) return slot;
        }
        return -1;
    }

    private void beginRepair(int slot) {
        ItemStack offhand = mc.player.getOffHandStack();
        if (keepTotem.get() && offhand.getItem() == Items.TOTEM_OF_UNDYING) return;

        int freeSlot = findFreeSlot();
        if (freeSlot < 0) {
            if (chatFeedback.get()) warning("No free slot to stash the offhand item, skipping repair.");
            return;
        }

        // Stash the current offhand item, then bring the damaged item in.
        if (!offhand.isEmpty()) {
            InvUtils.move().fromOffhand().to(freeSlot);
            displaced = offhand.copy();
            displacedSlot = freeSlot;
        } else {
            displaced = ItemStack.EMPTY;
            displacedSlot = -1;
        }

        InvUtils.move().from(slot).toOffhand();
        carriedSlot = slot;
        tickCounter = tickDelay.get();

        if (chatFeedback.get()) info("Collecting XP to repair an item.");
        publish(AutomationEvent.Type.MENDING_REPAIR_NEEDED, Map.of("slot", String.valueOf(slot)));
    }

    private void restoreOffhand() {
        if (mc.player == null) {
            resetState();
            return;
        }

        if (carriedSlot >= 0) {
            // Move the (now repaired) item back where it came from.
            ItemStack offhand = mc.player.getOffHandStack();
            if (!offhand.isEmpty()) {
                int target = carriedSlot;
                // The original slot may now hold the displaced item if it was the same slot; find a safe one.
                if (target < 0 || target >= net.minecraft.entity.player.PlayerInventory.MAIN_SIZE) {
                    target = findFreeSlot();
                }
                if (target >= 0) InvUtils.move().fromOffhand().to(target);
            }
        }

        if (displacedSlot >= 0 && !displaced.isEmpty()) {
            // Return the stashed item to the offhand if it is still where we left it.
            InvUtils.move().from(displacedSlot).toOffhand();
        }

        resetState();
    }

    private int findFreeSlot() {
        for (int slot = 0; slot < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; slot++) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) return slot;
        }
        return -1;
    }

    /**
     * True when a legitimate XP source is present: XP orbs are already in the world nearby, or (optionally)
     * the player is carrying bottles o' enchanting. A repair is never started on the assumption that XP
     * <i>might</i> appear.
     */
    public boolean hasXpSource() {
        if (mc.world == null || mc.player == null) return false;

        double radius = xpOrbRadius.get();
        double radiusSq = radius * radius;

        List<ExperienceOrbEntity> orbs = mc.world.getEntitiesByClass(
            ExperienceOrbEntity.class,
            mc.player.getBoundingBox().expand(radius),
            orb -> true
        );

        for (ExperienceOrbEntity orb : orbs) {
            if (mc.player.squaredDistanceTo(orb) <= radiusSq) return true;
        }

        if (useBottles.get()) {
            for (int slot = 0; slot < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; slot++) {
                if (mc.player.getInventory().getStack(slot).getItem() == Items.EXPERIENCE_BOTTLE) return true;
            }
        }

        return false;
    }

    /** Count of damaged Mending items, for the HUD and the status command. */
    public int damagedMendingCount() {
        if (mc.player == null) return 0;

        int count = 0;
        for (EquipmentScanner.Equipped equipped : EquipmentScanner.worn()) {
            if (EquipmentScanner.hasMending(equipped.stack()) && EquipmentScanner.isDamaged(equipped.stack())) count++;
        }
        for (EquipmentScanner.Equipped equipped : EquipmentScanner.damagedCarried()) count++;

        return count;
    }

    public boolean isRepairing() {
        return carriedSlot >= 0;
    }

    public String lastRepairedName() {
        return lastRepairedName;
    }

    /** Short status line for the HUD and the status command. */
    public String statusLine() {
        if (isRepairing()) return "repairing" + (lastRepairedName == null ? "" : " " + lastRepairedName);

        int damaged = damagedMendingCount();
        if (damaged == 0) return "nothing to repair";

        return hasXpSource() ? "%d damaged, XP available".formatted(damaged) : "%d damaged, no XP".formatted(damaged);
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
