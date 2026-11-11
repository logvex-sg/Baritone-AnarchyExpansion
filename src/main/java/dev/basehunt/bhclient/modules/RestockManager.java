package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.logic.ProtectionRules;
import dev.basehunt.bhclient.core.logic.RestockPlanner;
import dev.basehunt.bhclient.core.logic.TransferPlanner;
import dev.basehunt.bhclient.systems.ContainerInteractionManager;
import dev.basehunt.bhclient.systems.StorageOperations;
import dev.basehunt.bhclient.systems.StorageRegistry;
import dev.basehunt.bhclient.utils.BaritoneHelper;
import dev.basehunt.bhclient.utils.ContainerHelper;
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
import net.minecraft.screen.ScreenHandler;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * Tops the player up from storage before a trip.
 *
 * <p>Restocking is planned as "what is missing, and how much", then satisfied one container at a time: the
 * module finds a registered container that holds a needed item, travels to it if it is out of reach, opens it
 * through ordinary interaction, pulls the needed stacks, and repeats until the plan is empty or the inventory
 * is full. It never promises items that are not in reachable storage - the plan is built from the registry's
 * last observations, and a container that is not loaded is skipped.
 *
 * <p>Baritone is optional: without it, only containers already in reach can be used, and that is reported.
 */
public class RestockManager extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print restock activity in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> profile = sgGeneral.add(new ProvidedStringSetting.Builder()
        .name("profile")
        .description("Which restock profile to satisfy.")
        .defaultValue("travel")
        .supplier(() -> RestockPlanner.profiles().stream().map(RestockPlanner.Profile::name).toArray(String[]::new))
        .build()
    );

    private final Setting<Boolean> travelToStorage = sgGeneral.add(new BoolSetting.Builder()
        .name("travel-to-storage")
        .description("Walk to a registered container when a needed item is not in reach.")
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

    private final Setting<List<String>> protectedItems = sgSafety.add(new StringListSetting.Builder()
        .name("protected-items")
        .description("Item ids that must never be moved while restocking.")
        .defaultValue(List.of("elytra", "totem_of_undying"))
        .build()
    );

    private int navigateTargetId = -1;
    private boolean pathIssued;
    private boolean running;
    private Map<String, Integer> pendingNeed = new LinkedHashMap<>();

    public RestockManager() {
        super(BaseHuntingAddon.CATEGORY, "restock-manager",
            "Tops up travel supplies from your registered storage before a trip.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        stopNavigation();
        reset();
        ContainerInteractionManager manager = ContainerInteractionManager.get();
        if (manager != null) manager.cancelOwner(name);
    }

    private void reset() {
        navigateTargetId = -1;
        pathIssued = false;
        running = false;
        pendingNeed = new LinkedHashMap<>();
    }

    /** Begins a restock run. Returns a short result message. */
    public String start() {
        return start(null);
    }

    /**
     * Begins a restock run against a named profile, or the module's own profile when {@code profileName} is
     * null. A workflow step passes its payload here so "restock travel" means the travel profile regardless
     * of what the module setting currently is.
     */
    public String start(String profileName) {
        String name = profileName == null || profileName.isBlank() ? profile.get() : profileName;

        RestockPlanner.Plan plan = dev.basehunt.bhclient.systems.RestockManager.planProfile(name);
        if (plan.isEmpty()) {
            running = false;
            return "already stocked up";
        }

        pendingNeed = new LinkedHashMap<>();
        for (RestockPlanner.Need need : plan.needs()) {
            if (need.required() > 0) pendingNeed.put(need.itemKey(), need.required());
        }

        running = true;
        if (chatFeedback.get()) info("Restocking %d item type(s).", pendingNeed.size());
        publish(AutomationEvent.Type.MILESTONE, Map.of("action", "restock started"));
        return "restocking " + pendingNeed.size() + " item type(s)";
    }

    public void stop() {
        running = false;
        stopNavigation();
    }

    public boolean isRunning() {
        return running;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (!running) return;

        // If a container screen is open, the pull has already been queued; wait for the operation to finish.
        ContainerInteractionManager interaction = ContainerInteractionManager.get();
        if (interaction != null && interaction.isBusyWith(name)) return;

        if (mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> screen) {
            ScreenHandler handler = screen.getScreenHandler();
            String result = pullFrom(handler);
            if (chatFeedback.get()) info("Restock: %s.", result);
            running = false;
            return;
        }

        if (navigateTargetId >= 0) {
            tickNavigation();
            return;
        }

        // Find a registered container holding a needed item.
        StorageRegistry registry = StorageRegistry.get();
        if (registry == null) {
            running = false;
            return;
        }

        StorageRegistry.Container best = null;
        for (StorageRegistry.Container container : registry.forCurrentServer()) {
            boolean holds = false;
            for (String item : pendingNeed.keySet()) {
                if (container.countOf(item) > 0) { holds = true; break; }
            }
            if (!holds) continue;
            if (best == null || container.priority < best.priority) best = container;
        }

        if (best == null) {
            if (chatFeedback.get()) warning("No registered container holds the needed items.");
            running = false;
            return;
        }

        if (ContainerHelper.isReachable(best.pos)) {
            String error = StorageOperations.openContainerBlock(best.pos);
            if (error != null) {
                if (chatFeedback.get()) warning("Could not open storage: %s.", error);
                running = false;
            }
            return;
        }

        if (!travelToStorage.get() || !BaritoneHelper.AVAILABLE) {
            if (chatFeedback.get()) warning("A container with the needed items is out of reach.");
            running = false;
            return;
        }

        navigateTargetId = best.id;
        pathIssued = false;
    }

    private void tickNavigation() {
        StorageRegistry registry = StorageRegistry.get();
        if (registry == null) { stopNavigation(); return; }

        StorageRegistry.Container container = registry.byId(navigateTargetId);
        if (container == null) { stopNavigation(); return; }

        if (ContainerHelper.isReachable(container.pos)) {
            if (BaritoneHelper.AVAILABLE && BaritoneHelper.isPathing()) BaritoneHelper.stop();
            StorageOperations.openContainerBlock(container.pos);
            stopNavigation();
            return;
        }

        if (!pathIssued) {
            BaritoneHelper.walkTo(container.pos);
            pathIssued = true;
        } else if (!BaritoneHelper.isPathing()) {
            // Pathing stopped short; give up rather than looping forever.
            if (chatFeedback.get()) warning("Could not reach the storage container.");
            stopNavigation();
            running = false;
        }
    }

    /** Pulls the needed stacks from the open container. */
    private String pullFrom(ScreenHandler handler) {
        ContainerInteractionManager manager = ContainerInteractionManager.get();
        if (manager == null) return "storage system unavailable";

        Set<String> protectedSet = ProtectionRules.parseList(String.join(",", protectedItems.get()));

        TransferPlanner.Plan plan = TransferPlanner.planPull(
            StorageOperations.containerStacks(handler), StorageOperations.emptyPlayerSlots(handler),
            pendingNeed, protectedSet);

        if (plan.isEmpty()) return "nothing to pull from this container";

        boolean queued = manager.submit(new ContainerInteractionManager.Operation(
            name, ContainerInteractionManager.Kind.RESTOCK, "restock",
            TransferPlanner.toMoves(plan.transfers()), true, 300, tickDelay.get()));
        if (!queued) return "a storage operation is already running";

        int stacks = plan.transfers().size();
        publish(AutomationEvent.Type.ITEMS_RESTOCKED, Map.of("items", String.valueOf(stacks)));
        return "pulling %d stack(s)".formatted(stacks);
    }

    /** A summary of what a restock would do, for the command and GUI. */
    public List<String> preview() {
        RestockPlanner.Plan plan = dev.basehunt.bhclient.systems.RestockManager.planProfile(profile.get());
        List<String> lines = new java.util.ArrayList<>();
        for (RestockPlanner.Need need : plan.needs()) {
            lines.add("%s: have %d, need %d%s".formatted(need.name(), need.carried(), need.required(),
                need.emergency() ? " (emergency)" : ""));
        }
        for (String warning : plan.warnings()) lines.add("! " + warning);
        return lines;
    }

    private void stopNavigation() {
        navigateTargetId = -1;
        pathIssued = false;
        if (BaritoneHelper.AVAILABLE && BaritoneHelper.isPathing()) BaritoneHelper.stop();
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
