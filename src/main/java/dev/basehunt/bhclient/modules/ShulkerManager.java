package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.logic.ProtectionRules;
import dev.basehunt.bhclient.core.logic.ShulkerInventory;
import dev.basehunt.bhclient.core.logic.SortPlanner;
import dev.basehunt.bhclient.core.logic.StorageCategory;
import dev.basehunt.bhclient.core.logic.TransferPlanner;
import dev.basehunt.bhclient.systems.ContainerInteractionManager;
import dev.basehunt.bhclient.systems.StorageOperations;
import dev.basehunt.bhclient.utils.CategoryParser;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ShulkerBoxScreenHandler;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fills and empties shulker boxes, and searches them without opening them.
 *
 * <p>An open shulker is a normal container screen, so filling and emptying reuse the same planners and the
 * same {@link ContainerInteractionManager} that every other storage feature uses. Searching does not need the
 * screen at all: a shulker's contents live in the item's {@code CONTAINER} component, so a carried box can be
 * searched from the inventory - but only when that component is present, which is why a box the client has
 * never seen the inside of is reported as unknown and never treated as empty.
 */
public class ShulkerManager extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFill = settings.createGroup("Fill");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    // General

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print shulker activity in chat.")
        .defaultValue(true)
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

    // Fill

    private final Setting<Boolean> fillOnOpen = sgFill.add(new BoolSetting.Builder()
        .name("fill-on-open")
        .description("Fill the shulker's selected categories from your inventory when a shulker screen opens.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> fillCategories = sgFill.add(new StringListSetting.Builder()
        .name("fill-categories")
        .description("Categories to move into an open shulker box.")
        .defaultValue(List.of("ores", "nether", "end", "rare", "misc"))
        .build()
    );

    private final Setting<Boolean> emptyOnOpen = sgFill.add(new BoolSetting.Builder()
        .name("empty-on-open")
        .description("Empty the shulker's selected categories into your inventory when it opens.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> emptyCategories = sgFill.add(new StringListSetting.Builder()
        .name("empty-categories")
        .description("Categories to pull out of an open shulker box.")
        .defaultValue(List.of("ores", "food", "building"))
        .build()
    );

    // Safety

    private final Setting<List<String>> protectedItems = sgSafety.add(new StringListSetting.Builder()
        .name("protected-items")
        .description("Item ids that must never be moved into or out of a shulker.")
        .defaultValue(List.of("elytra", "totem_of_undying", "shulker_box"))
        .build()
    );

    private int pendingDelay;
    private boolean handledScreen;

    public ShulkerManager() {
        super(BaseHuntingAddon.CATEGORY, "shulker-manager",
            "Fills, empties and searches shulker boxes without treating an unopened box as empty.");
    }

    @Override
    public void onActivate() {
        pendingDelay = 0;
        handledScreen = false;
    }

    @Override
    public void onDeactivate() {
        ContainerInteractionManager manager = ContainerInteractionManager.get();
        if (manager != null) manager.cancelOwner(name);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (!(mc.currentScreen instanceof HandledScreen<?> screen)
            || !(screen.getScreenHandler() instanceof ShulkerBoxScreenHandler handler)) {
            handledScreen = false;
            return;
        }

        if (pendingDelay > 0) {
            pendingDelay--;
            return;
        }

        if (handledScreen) return;
        handledScreen = true;
        pendingDelay = 0;

        if (fillOnOpen.get()) fill(handler);
        if (emptyOnOpen.get()) empty(handler);
    }

    /** Moves the selected categories from the player into the open shulker. */
    public String fill(ShulkerBoxScreenHandler handler) {
        ContainerInteractionManager manager = ContainerInteractionManager.get();
        if (manager == null) return "storage system unavailable";

        Set<StorageCategory> categories = CategoryParser.parse(fillCategories.get());
        if (categories.isEmpty()) return "no fill categories selected";

        Set<String> protectedSet = ProtectionRules.parseList(String.join(",", protectedItems.get()));

        TransferPlanner.Plan plan = TransferPlanner.planDeposit(
            StorageOperations.playerStacks(handler), StorageOperations.emptyContainerSlots(handler),
            categories, Map.of(), protectedSet);

        if (plan.isEmpty()) return "nothing to fill with";

        boolean queued = manager.submit(new ContainerInteractionManager.Operation(
            name, ContainerInteractionManager.Kind.SHULKER_FILL, "shulker fill",
            TransferPlanner.toMoves(plan.transfers()), true, 300, tickDelay.get()));
        if (!queued) return "a storage operation is already running";

        if (chatFeedback.get()) info("Filling shulker with %d stack(s).", plan.transfers().size());
        publish(AutomationEvent.Type.SHULKER_FILLED, Map.of("stacks", String.valueOf(plan.transfers().size())));
        return "filling with %d stack(s)".formatted(plan.transfers().size());
    }

    /** Moves the selected categories from the open shulker into the player. */
    public String empty(ShulkerBoxScreenHandler handler) {
        ContainerInteractionManager manager = ContainerInteractionManager.get();
        if (manager == null) return "storage system unavailable";

        Set<StorageCategory> categories = CategoryParser.parse(emptyCategories.get());
        if (categories.isEmpty()) return "no empty categories selected";

        Set<String> protectedSet = ProtectionRules.parseList(String.join(",", protectedItems.get()));

        TransferPlanner.Plan plan = TransferPlanner.planWithdraw(
            StorageOperations.containerStacks(handler), StorageOperations.emptyPlayerSlots(handler),
            categories, protectedSet);

        if (plan.isEmpty()) return "nothing to empty";

        boolean queued = manager.submit(new ContainerInteractionManager.Operation(
            name, ContainerInteractionManager.Kind.SHULKER_EMPTY, "shulker empty",
            TransferPlanner.toMoves(plan.transfers()), true, 300, tickDelay.get()));
        if (!queued) return "a storage operation is already running";

        if (chatFeedback.get()) info("Emptying shulker: %d stack(s).", plan.transfers().size());
        publish(AutomationEvent.Type.SHULKER_EMPTIED, Map.of("stacks", String.valueOf(plan.transfers().size())));
        return "emptying %d stack(s)".formatted(plan.transfers().size());
    }

    /** Sorts the open shulker's contents by category. */
    public String sortShulker(ShulkerBoxScreenHandler handler) {
        ContainerInteractionManager manager = ContainerInteractionManager.get();
        if (manager == null) return "storage system unavailable";

        List<Integer> targets = StorageOperations.containerSlotIds(handler);
        Set<String> protectedSet = ProtectionRules.parseList(String.join(",", protectedItems.get()));

        List<SortPlanner.SortItem> items = StorageOperations.sortItems(
            StorageOperations.containerStacks(handler), Set.of(), protectedSet);

        SortPlanner.Plan plan = SortPlanner.plan(items, targets, StorageCategory.defaultOrder(),
            SortPlanner.byName(), true);

        if (!plan.changed()) return "shulker already sorted";

        boolean queued = manager.submit(new ContainerInteractionManager.Operation(
            name, ContainerInteractionManager.Kind.SORT, "shulker sort", plan.moves(), true, 300, tickDelay.get()));
        if (!queued) return "a storage operation is already running";

        if (chatFeedback.get()) info("Sorting shulker: %d move(s).", plan.moves().size());
        publish(AutomationEvent.Type.STORAGE_SORTED, Map.of(
            "moves", String.valueOf(plan.moves().size()), "scope", "shulker"));
        return "sorting %d move(s)".formatted(plan.moves().size());
    }

    /** Searches the carried shulker boxes for an item, without opening them. */
    public String search(String itemId) {
        if (itemId == null || itemId.isBlank()) return "specify an item to search for";

        List<ShulkerInventory.Hit> hits = dev.basehunt.bhclient.systems.ShulkerManager.find(itemId);
        if (hits.isEmpty()) {
            List<ShulkerInventory.Box> unknown = ShulkerInventory.unknown(
                dev.basehunt.bhclient.systems.ShulkerManager.carried());
            if (!unknown.isEmpty()) {
                return "no %s found in known boxes; %d box(es) have unknown contents"
                    .formatted(itemId, unknown.size());
            }
            return "no " + itemId + " found in your shulker boxes";
        }

        int total = 0;
        for (ShulkerInventory.Hit hit : hits) total += hit.count();
        return "found %d %s in %d box(es)".formatted(total, itemId, hits.size());
    }

    /** A status line for the HUD and commands. */
    public String status() {
        return dev.basehunt.bhclient.systems.ShulkerManager.status();
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
