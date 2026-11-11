package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.logic.ProtectionRules;
import dev.basehunt.bhclient.core.logic.SortPlanner;
import dev.basehunt.bhclient.core.logic.StorageCategory;
import dev.basehunt.bhclient.core.logic.TransferPlanner;
import dev.basehunt.bhclient.systems.ContainerInteractionManager;
import dev.basehunt.bhclient.systems.StorageOperations;
import dev.basehunt.bhclient.systems.StorageRegistry;
import dev.basehunt.bhclient.utils.CategoryParser;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.ScreenHandler;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works with any container screen: it sorts the container, deposits into it, withdraws from it, and registers
 * it in the storage network.
 *
 * <p>The module is screen-driven: when a container opens it records the container's contents into the
 * {@link StorageRegistry} so the network knows what is where, and it can then sort or move items on request.
 * Sorting and transfers are planned by the pure planners and executed by the {@link ContainerInteractionManager},
 * so this module never clicks a slot itself and cannot race another storage feature.
 *
 * <p>Nothing moves on its own: an automatic action only happens when the corresponding setting is on, and a
 * deposit only ever moves a category the user selected.
 */
public class ChestSorter extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDeposit = settings.createGroup("Deposit");
    private final SettingGroup sgWithdraw = settings.createGroup("Withdraw");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    // General

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print container activity in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> registerOnOpen = sgGeneral.add(new BoolSetting.Builder()
        .name("register-on-open")
        .description("Record every container you open into the storage network.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> sortOnOpen = sgGeneral.add(new BoolSetting.Builder()
        .name("sort-on-open")
        .description("Sort the container's contents by category when it is opened.")
        .defaultValue(false)
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

    // Deposit

    private final Setting<Boolean> depositOnOpen = sgDeposit.add(new BoolSetting.Builder()
        .name("deposit-on-open")
        .description("Deposit the selected categories into a container when it is opened.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> depositCategories = sgDeposit.add(new StringListSetting.Builder()
        .name("deposit-categories")
        .description("Categories to deposit. Precious categories must be listed explicitly to be deposited.")
        .defaultValue(List.of("building", "ores", "redstone", "nether", "end", "farming", "misc"))
        .build()
    );

    // Withdraw

    private final Setting<Boolean> withdrawOnOpen = sgWithdraw.add(new BoolSetting.Builder()
        .name("withdraw-on-open")
        .description("Withdraw the selected categories from a container when it is opened.")
        .defaultValue(false)
        .build()
    );

    private final Setting<List<String>> withdrawCategories = sgWithdraw.add(new StringListSetting.Builder()
        .name("withdraw-categories")
        .description("Categories to withdraw.")
        .defaultValue(List.of("ores", "food"))
        .build()
    );

    // Safety

    private final Setting<List<String>> protectedItems = sgSafety.add(new StringListSetting.Builder()
        .name("protected-items")
        .description("Item ids that must never be deposited or withdrawn.")
        .defaultValue(List.of("elytra", "totem_of_undying", "shulker_box"))
        .build()
    );

    private final Setting<Boolean> preserveReserve = sgSafety.add(new BoolSetting.Builder()
        .name("preserve-reserve")
        .description("Keep a survival reserve of food and travel supplies in the inventory.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> reserveStacks = sgSafety.add(new IntSetting.Builder()
        .name("reserve-stacks")
        .description("How many stacks of each reserve category to keep.")
        .defaultValue(2)
        .min(0)
        .sliderRange(0, 9)
        .visible(preserveReserve::get)
        .build()
    );

    private int pendingDelay;
    private boolean actionTakenForScreen;
    private net.minecraft.util.math.BlockPos openedContainerPos;

    public ChestSorter() {
        super(BaseHuntingAddon.CATEGORY, "chest-sorter",
            "Sorts containers, deposits and withdraws selected categories, and registers storage.");
    }

    @Override
    public void onActivate() {
        pendingDelay = 0;
        actionTakenForScreen = false;
    }

    @Override
    public void onDeactivate() {
        ContainerInteractionManager manager = ContainerInteractionManager.get();
        if (manager != null) manager.cancelOwner(name);
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        if (!(event.screen instanceof HandledScreen<?> screen)) return;

        // The container's block position is not carried by the screen handler, so it is captured from the
        // crosshair at the moment the screen opens, while the player is still facing the block.
        openedContainerPos = null;
        if (mc.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult hit
            && hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
            if (dev.basehunt.bhclient.utils.ContainerHelper.containerTypeAt(hit.getBlockPos()) != null) {
                openedContainerPos = hit.getBlockPos();
            }
        }

        // A small delay lets the server finish sending the contents before anything is moved.
        pendingDelay = 5;
        actionTakenForScreen = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            actionTakenForScreen = false;
            return;
        }

        if (pendingDelay > 0) {
            pendingDelay--;
            return;
        }

        if (actionTakenForScreen) return;
        if (!StorageOperations.containerSlotIds(screen.getScreenHandler()).isEmpty()) {
            // The screen has contents; safe to act.
        }

        actionTakenForScreen = true;
        handleContainer(screen.getScreenHandler());
    }

    /** Acts on a freshly opened container: register, then optionally sort, deposit or withdraw. */
    private void handleContainer(ScreenHandler handler) {
        StorageRegistry registry = StorageRegistry.get();
        if (registry != null && registerOnOpen.get() && openedContainerPos != null) {
            String type = dev.basehunt.bhclient.utils.ContainerHelper.containerTypeAt(openedContainerPos);
            var container = registry.register(openedContainerPos, type == null ? "container" : type,
                StorageOperations.containerSlotIds(handler).size() / 9);
            StorageOperations.observe(container, handler);
            publish(AutomationEvent.Type.CONTAINER_REGISTERED, Map.of(
                "type", container.type, "pos", container.pos.toShortString()));
        }

        // Deposit before withdrawing: freeing player slots first lets a withdrawal pull more in.
        if (sortOnOpen.get()) sortContainer(handler);
        if (depositOnOpen.get()) deposit(handler);
        if (withdrawOnOpen.get()) withdraw(handler);
    }

    /** Sorts the open container's contents into category order. */
    public String sortContainer(ScreenHandler handler) {
        ContainerInteractionManager manager = ContainerInteractionManager.get();
        if (manager == null) return "storage system unavailable";

        List<Integer> targets = StorageOperations.containerSlotIds(handler);
        Set<String> protectedSet = ProtectionRules.parseList(String.join(",", protectedItems.get()));

        // Sort the container's own slots; the items live in container slots, so build sort items from them.
        List<SortPlanner.SortItem> items = StorageOperations.sortItems(
            StorageOperations.containerStacks(handler), Set.of(), protectedSet);

        SortPlanner.Plan plan = SortPlanner.plan(items, targets, StorageCategory.defaultOrder(),
            SortPlanner.byName(), true);

        if (!plan.changed()) return "container already sorted";

        boolean queued = manager.submit(new ContainerInteractionManager.Operation(
            name, ContainerInteractionManager.Kind.SORT, "container sort", plan.moves(), true, 300, tickDelay.get()));
        if (!queued) return "a storage operation is already running";

        if (chatFeedback.get()) info("Sorting container: %d move(s).", plan.moves().size());
        publish(AutomationEvent.Type.STORAGE_SORTED, Map.of(
            "moves", String.valueOf(plan.moves().size()), "scope", "container"));
        return "sorting %d move(s)".formatted(plan.moves().size());
    }

    /** Deposits the selected categories into the open container. */
    public String deposit(ScreenHandler handler) {
        ContainerInteractionManager manager = ContainerInteractionManager.get();
        if (manager == null) return "storage system unavailable";

        Set<StorageCategory> categories = CategoryParser.parse(depositCategories.get());
        if (categories.isEmpty()) return "no deposit categories selected";

        Set<String> protectedSet = ProtectionRules.parseList(String.join(",", protectedItems.get()));
        Map<StorageCategory, Integer> reserves = reserves();

        TransferPlanner.Plan plan = TransferPlanner.planDeposit(
            StorageOperations.playerStacks(handler), StorageOperations.emptyContainerSlots(handler),
            categories, reserves, protectedSet);

        if (plan.isEmpty()) return "nothing to deposit";

        boolean queued = manager.submit(new ContainerInteractionManager.Operation(
            name, ContainerInteractionManager.Kind.DEPOSIT, "deposit",
            TransferPlanner.toMoves(plan.transfers()), true, 300, tickDelay.get()));
        if (!queued) return "a storage operation is already running";

        if (chatFeedback.get()) info("Depositing %d stack(s).", plan.transfers().size());
        publish(AutomationEvent.Type.ITEMS_DEPOSITED, Map.of("stacks", String.valueOf(plan.transfers().size())));
        return "depositing %d stack(s)".formatted(plan.transfers().size());
    }

    /** Withdraws the selected categories from the open container. */
    public String withdraw(ScreenHandler handler) {
        ContainerInteractionManager manager = ContainerInteractionManager.get();
        if (manager == null) return "storage system unavailable";

        Set<StorageCategory> categories = CategoryParser.parse(withdrawCategories.get());
        if (categories.isEmpty()) return "no withdraw categories selected";

        Set<String> protectedSet = ProtectionRules.parseList(String.join(",", protectedItems.get()));

        TransferPlanner.Plan plan = TransferPlanner.planWithdraw(
            StorageOperations.containerStacks(handler), StorageOperations.emptyPlayerSlots(handler),
            categories, protectedSet);

        if (plan.isEmpty()) return "nothing to withdraw";

        boolean queued = manager.submit(new ContainerInteractionManager.Operation(
            name, ContainerInteractionManager.Kind.WITHDRAW, "withdraw",
            TransferPlanner.toMoves(plan.transfers()), true, 300, tickDelay.get()));
        if (!queued) return "a storage operation is already running";

        if (chatFeedback.get()) info("Withdrawing %d stack(s).", plan.transfers().size());
        publish(AutomationEvent.Type.ITEMS_WITHDRAWN, Map.of("stacks", String.valueOf(plan.transfers().size())));
        return "withdrawing %d stack(s)".formatted(plan.transfers().size());
    }

    private Map<StorageCategory, Integer> reserves() {
        Map<StorageCategory, Integer> reserves = new EnumMap<>(StorageCategory.class);
        if (!preserveReserve.get()) return reserves;

        int stacks = reserveStacks.get();
        // A reserve is expressed in stacks; the planner compares it against stack counts, so scale by a stack.
        reserves.put(StorageCategory.FOOD, stacks * 64);
        reserves.put(StorageCategory.POTIONS, stacks * 16);
        return reserves;
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
