package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.systems.StorageOperations;
import dev.basehunt.bhclient.systems.StorageRegistry;
import dev.basehunt.bhclient.utils.BaritoneHelper;
import dev.basehunt.bhclient.utils.ContainerHelper;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The storage network: a directory of every registered container, plus navigation to one on request.
 *
 * <p>Registration happens when you look at a container and press the key (or run the command); the block you
 * are facing is registered with its real type. Navigation is a two-step: Baritone walks the player to the
 * container, and once it is in reach the module opens it through ordinary interaction, at which point the
 * screen-driven {@link ChestSorter} records its contents.
 *
 * <p>Baritone is optional. When it is not loaded the module still registers containers and reports their
 * contents, and a navigation request is refused with a clear message rather than failing silently.
 */
public class StorageNetwork extends Module {
    /** How to travel to a container. */
    public enum TravelMode {
        WALK,
        ELYTRA
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTravel = settings.createGroup("Travel");

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print storage network activity in chat.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> registerLooking = sgGeneral.add(new BoolSetting.Builder()
        .name("register-when-looking")
        .description("Register the container you are looking at automatically when you open it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<TravelMode> travelMode = sgTravel.add(new EnumSetting.Builder<TravelMode>()
        .name("travel-mode")
        .description("How to travel to a container you navigate to.")
        .defaultValue(TravelMode.WALK)
        .build()
    );

    private final Setting<Integer> arrivalDistance = sgTravel.add(new meteordevelopment.meteorclient.settings.IntSetting.Builder()
        .name("arrival-distance")
        .description("How close to get before opening the container.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 8)
        .build()
    );

    private int navigateTargetId = -1;
    private boolean pathIssued;
    private int retryCounter;

    public StorageNetwork() {
        super(BaseHuntingAddon.CATEGORY, "storage-network",
            "Keeps a directory of your storage containers and can travel to one on request.");
    }

    @Override
    public void onActivate() {
        navigateTargetId = -1;
        pathIssued = false;
        retryCounter = 0;
    }

    @Override
    public void onDeactivate() {
        stopNavigation();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // Registering the container the player is looking at as it opens is handled by the screen listener in
        // ChestSorter; here we only keep an eye on the current navigation request.
        if (navigateTargetId < 0) return;

        StorageRegistry registry = StorageRegistry.get();
        if (registry == null) return;

        StorageRegistry.Container container = registry.byId(navigateTargetId);
        if (container == null) {
            if (chatFeedback.get()) warning("The container is no longer registered.");
            stopNavigation();
            return;
        }

        BlockPos target = container.pos;

        if (ContainerHelper.isReachable(target) && ContainerHelper.withinReach(target, arrivalDistance.get())) {
            if (BaritoneHelper.AVAILABLE && BaritoneHelper.isPathing()) BaritoneHelper.stop();
            String error = StorageOperations.openContainerBlock(target);
            if (error == null) {
                if (chatFeedback.get()) info("Opened %s.", container.describe());
                publish(AutomationEvent.Type.ENDER_CHEST_OPENED, Map.of("container", container.describe()));
                stopNavigation();
            } else {
                if (chatFeedback.get()) warning("Could not open: %s.", error);
                stopNavigation();
            }
            return;
        }

        if (!BaritoneHelper.AVAILABLE) {
            if (chatFeedback.get()) warning("Baritone is not loaded, so navigation is unavailable.");
            stopNavigation();
            return;
        }

        if (!pathIssued) {
            if (travelMode.get() == TravelMode.ELYTRA) BaritoneHelper.elytraPathTo(target);
            else BaritoneHelper.walkTo(target);
            pathIssued = true;
            if (chatFeedback.get()) info("Travelling to %s.", container.describe());
        }

        // If pathing stopped short of the target, reissue once in a while rather than spamming Baritone.
        if (retryCounter > 0) {
            retryCounter--;
        } else if (!BaritoneHelper.isPathing()) {
            retryCounter = 40;
            pathIssued = false;
        }
    }

    /** Registers the container the player is looking at. Returns a short result message. */
    public String registerLooking() {
        var mc = ServerUtils.mc();
        if (mc == null || mc.player == null) return "no player";

        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return "not looking at a block";
        }

        BlockPos pos = hit.getBlockPos();
        String type = ContainerHelper.containerTypeAt(pos);
        if (type == null) return "the block you are looking at is not a container";

        StorageRegistry registry = StorageRegistry.get();
        if (registry == null) return "storage registry unavailable";

        StorageRegistry.Container container = registry.register(pos, type, 0);
        if (chatFeedback.get()) info("Registered %s.", container.describe());
        publish(AutomationEvent.Type.CONTAINER_REGISTERED, Map.of("type", type, "pos", pos.toShortString()));
        return "registered " + container.describe();
    }

    /** Starts travelling to a container by id. */
    public String gotoContainer(int id) {
        StorageRegistry registry = StorageRegistry.get();
        if (registry == null) return "storage registry unavailable";

        StorageRegistry.Container container = registry.byId(id);
        if (container == null) return "no container with id " + id;

        navigateTargetId = id;
        pathIssued = false;
        retryCounter = 0;
        return "navigating to " + container.describe();
    }

    /** Stops any navigation in progress. */
    public void stopNavigation() {
        navigateTargetId = -1;
        pathIssued = false;
        if (BaritoneHelper.AVAILABLE && BaritoneHelper.isPathing()) BaritoneHelper.stop();
    }

    public boolean isNavigating() {
        return navigateTargetId >= 0;
    }

    /** A listing of the containers on the current server, for the command and GUI. */
    public List<String> listing() {
        List<String> lines = new ArrayList<>();
        StorageRegistry registry = StorageRegistry.get();
        if (registry == null) return lines;

        for (StorageRegistry.Container container : registry.forCurrentServer()) {
            boolean reachable = ContainerHelper.isReachable(container.pos);
            lines.add("%s [%s]%s".formatted(container.describe(),
                container.verified ? container.contents().size() + " stacks seen" : "unverified",
                reachable ? " (in reach)" : ""));
        }
        return lines;
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
