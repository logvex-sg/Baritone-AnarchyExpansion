package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.logic.ProtectionRules;
import dev.basehunt.bhclient.core.logic.SortPlanner;
import dev.basehunt.bhclient.core.logic.StorageCategory;
import dev.basehunt.bhclient.systems.ContainerInteractionManager;
import dev.basehunt.bhclient.systems.StorageOperations;
import dev.basehunt.bhclient.utils.CategoryParser;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ProvidedStringSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sorts the player's own inventory into a stable, category-ordered layout.
 *
 * <p>The actual rearrangement is planned by {@link SortPlanner} and executed by the
 * {@link ContainerInteractionManager}, which is the only code that clicks slots; this module only decides
 * <em>when</em> to sort and <em>what</em> the target order is. It can sort once on activation, re-sort after a
 * container is closed (the usual moment an inventory becomes messy), and periodically while a long sorting
 * session runs.
 *
 * <p>It never shuffles the hotbar reserve, the selected slot or any item on the protected list, and it never
 * splits a stack, so a sort cannot drop an item on the floor or merge two different items.
 */
public class InventoryManager extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLayout = settings.createGroup("Layout");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    // General

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print sorting activity in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sortOnActivate = sgGeneral.add(new BoolSetting.Builder()
        .name("sort-on-activate")
        .description("Sort the inventory once as soon as the module is enabled.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sortAfterContainer = sgGeneral.add(new BoolSetting.Builder()
        .name("sort-after-closing")
        .description("Sort the inventory when a container screen is closed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> rescanInterval = sgGeneral.add(new IntSetting.Builder()
        .name("rescan-interval")
        .description("Ticks between periodic re-sorts while enabled. 0 disables periodic sorting.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 1200)
        .build()
    );

    private final Setting<Integer> tickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("tick-delay")
        .description("Ticks between slot moves, to let the server keep up.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    // Layout

    private final Setting<Boolean> sortHotbar = sgLayout.add(new BoolSetting.Builder()
        .name("sort-hotbar")
        .description("Include the hotbar slots in the sort, not just the main inventory.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> mergeStacks = sgLayout.add(new BoolSetting.Builder()
        .name("merge-stacks")
        .description("Combine partial stacks of the same item before ordering.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> preset = sgLayout.add(new ProvidedStringSetting.Builder()
        .name("preset")
        .description("The category order to sort into.")
        .defaultValue("General storage")
        .supplier(() -> StorageCategory.presets().stream().map(StorageCategory.Preset::name).toArray(String[]::new))
        .build()
    );

    // Safety

    private final Setting<Integer> hotbarReserve = sgSafety.add(new IntSetting.Builder()
        .name("hotbar-reserve")
        .description("Number of hotbar slots (from the left) to leave untouched.")
        .defaultValue(9)
        .min(0)
        .sliderRange(0, 9)
        .build()
    );

    private final Setting<Boolean> protectSelected = sgSafety.add(new BoolSetting.Builder()
        .name("protect-selected")
        .description("Never move the currently selected hotbar slot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<String>> protectedItems = sgSafety.add(new StringListSetting.Builder()
        .name("protected-items")
        .description("Item ids that must never be moved, e.g. elytra or totem_of_undying.")
        .defaultValue(List.of("elytra", "totem_of_undying"))
        .build()
    );

    private int tickCounter;
    private boolean wasContainerOpen;
    private int lastFreeSlots = -1;

    public InventoryManager() {
        super(BaseHuntingAddon.CATEGORY, "inventory-manager",
            "Sorts your inventory into a stable category order using real slot clicks.");
    }

    @Override
    public void onActivate() {
        tickCounter = 0;
        wasContainerOpen = false;
        lastFreeSlots = -1;
        if (sortOnActivate.get()) sortNow("inventory");
    }

    @Override
    public void onDeactivate() {
        ContainerInteractionManager manager = ContainerInteractionManager.get();
        if (manager != null) manager.cancelOwner(name);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        boolean containerOpen = mc.currentScreen instanceof HandledScreen<?>;
        if (wasContainerOpen && !containerOpen && sortAfterContainer.get()) {
            // A container just closed: the classic moment an inventory is left messy.
            sortNow("after closing a container");
        }
        wasContainerOpen = containerOpen;

        if (rescanInterval.get() > 0) {
            if (tickCounter > 0) {
                tickCounter--;
            } else {
                tickCounter = rescanInterval.get();
                sortNow("periodic");
            }
        }
    }

    /** Builds and submits a sort of the player inventory. Returns a short result message. */
    public String sortNow(String scope) {
        var mc = ServerUtils.mc();
        if (mc == null || mc.player == null) return "no player";

        ContainerInteractionManager manager = ContainerInteractionManager.get();
        if (manager == null) return "storage system unavailable";

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null) return "no inventory";

        List<Integer> targets = sortHotbar.get()
            ? StorageOperations.playerSlotIds(handler)
            : StorageOperations.mainSlotIds(handler);

        Set<Integer> locked = lockedSlots(handler);
        Set<String> protectedSet = ProtectionRules.parseList(String.join(",", protectedItems.get()));

        StorageCategory.Preset chosen = StorageCategory.preset(preset.get());
        List<StorageCategory> order = chosen == null ? StorageCategory.defaultOrder() : chosen.order();

        SortPlanner.Plan plan = StorageOperations.buildSort(handler, targets, order, mergeStacks.get(),
            locked, protectedSet);

        if (!plan.changed()) {
            return "already sorted";
        }

        boolean queued = manager.submit(new ContainerInteractionManager.Operation(
            name, ContainerInteractionManager.Kind.SORT, "inventory sort", plan.moves(), false, 200, tickDelay.get()));

        if (!queued) return "a storage operation is already running";

        if (chatFeedback.get()) info("Sorting %d move(s) (%s).", plan.moves().size(), scope);
        publish(AutomationEvent.Type.STORAGE_SORTED, Map.of(
            "moves", String.valueOf(plan.moves().size()), "scope", scope));
        return "sorting %d move(s)".formatted(plan.moves().size());
    }

    private Set<Integer> lockedSlots(ScreenHandler handler) {
        Set<Integer> locked = new HashSet<>();

        int reserve = hotbarReserve.get();
        List<Integer> playerSlots = StorageOperations.playerSlotIds(handler);
        // Player slot ids are ordered by player index, so the first `reserve` are the leftmost hotbar slots.
        for (int i = 0; i < Math.min(reserve, playerSlots.size()); i++) locked.add(playerSlots.get(i));

        if (protectSelected.get()) {
            int selected = mc.player.getInventory().getSelectedSlot();
            if (selected >= 0 && selected < playerSlots.size()) locked.add(playerSlots.get(selected));
        }

        return locked;
    }

    /** Number of free main-inventory slots, for the HUD. */
    public int freeSlots() {
        var mc = ServerUtils.mc();
        if (mc == null || mc.player == null) return -1;

        int free = 0;
        for (int slot = 0; slot < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; slot++) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) free++;
        }
        return free;
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }

    /** The category order currently in force, for the GUI. */
    public List<String> currentOrder() {
        StorageCategory.Preset chosen = StorageCategory.preset(preset.get());
        List<StorageCategory> order = chosen == null ? StorageCategory.defaultOrder() : chosen.order();
        List<String> names = new ArrayList<>();
        for (StorageCategory category : order) names.add(category.display());
        return names;
    }
}
