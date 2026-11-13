package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.logic.ObjectivePlanner;
import dev.basehunt.bhclient.systems.ObjectiveStore;
import dev.basehunt.bhclient.systems.PlayerTracker;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.ItemEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Keeps a ranked to-do list of anarchy errands so loot left behind is not forgotten.
 *
 * <p>A stash run is more than the one task it was started for. On the way there you pass a dropped shulker,
 * near a base a player logged out, and a stash you meant to revisit. This module watches for those
 * opportunities and queues them through the pure {@link ObjectivePlanner}, which ranks them against where you
 * are so the nearest worthwhile thing comes first. The list is persisted by {@link ObjectiveStore}, so it
 * survives a restart - which is exactly when you want it back.
 *
 * <p>It only ever records and reports. It does not path anywhere on its own; the {@code baritone-control}
 * module (or the player) acts on the list through the {@code /bh objective next} command. That keeps the
 * single-owner rule intact: the queue decides <i>what</i> is worth doing, never <i>who</i> drives.
 */
public class ObjectiveQueue extends Module {
    private static final int SCAN_INTERVAL_TICKS = 20;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> trackDrops = sgGeneral.add(new BoolSetting.Builder()
        .name("track-item-drops")
        .description("Queue notable item drops you pass within range.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> dropRadius = sgGeneral.add(new IntSetting.Builder()
        .name("drop-radius")
        .description("Only queue drops within this many blocks.")
        .defaultValue(24)
        .min(2)
        .sliderRange(2, 128)
        .visible(trackDrops::get)
        .build()
    );

    private final Setting<Integer> dropMinCount = sgGeneral.add(new IntSetting.Builder()
        .name("drop-min-count")
        .description("Only queue a drop pile holding at least this many items.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 64)
        .visible(trackDrops::get)
        .build()
    );

    private final Setting<Boolean> trackLogoutSpots = sgGeneral.add(new BoolSetting.Builder()
        .name("track-logout-spots")
        .description("Queue the last-seen spot of a tracked player, to raid before they return.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> mergeRadius = sgGeneral.add(new IntSetting.Builder()
        .name("merge-radius")
        .description("Objectives of the same kind within this many blocks are merged rather than duplicated.")
        .defaultValue(16)
        .min(1)
        .sliderRange(1, 128)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Report newly queued objectives in chat.")
        .defaultValue(false)
        .build()
    );

    private int tickCounter;

    public ObjectiveQueue() {
        super(BaseHuntingAddon.CATEGORY, "objective-queue",
            "Keeps a ranked to-do list of anarchy errands so loot left behind is not forgotten.");
    }

    @Override
    public void onActivate() {
        tickCounter = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (++tickCounter % SCAN_INTERVAL_TICKS != 0) return;

        ObjectiveStore store = ObjectiveStore.get();
        if (store == null) return;

        int merge = mergeRadius.get();

        if (trackDrops.get()) scanDrops(store, merge);
        if (trackLogoutSpots.get()) scanLogoutSpots(store, merge);
    }

    private void scanDrops(ObjectiveStore store, int merge) {
        double radius = dropRadius.get();
        int minCount = dropMinCount.get();
        int before = store.planner().size();

        for (ItemEntity item : mc.world.getEntitiesByClass(ItemEntity.class,
            mc.player.getBoundingBox().expand(radius), e -> true)) {
            if (item.getStack().getCount() < minCount) continue;

            int x = (int) Math.floor(item.getX());
            int y = (int) Math.floor(item.getY());
            int z = (int) Math.floor(item.getZ());
            ObjectivePlanner.Objective objective = store.add(ObjectivePlanner.Kind.DROP_PICKUP,
                "%d x %s".formatted(item.getStack().getCount(),
                    item.getStack().getItem().getName().getString()),
                x, y, z, merge);

            if (chatFeedback.get() && store.planner().size() > before) {
                info("Queued drop: %s at %s.", objective.label(), objective.coords());
            }
        }
    }

    private void scanLogoutSpots(ObjectiveStore store, int merge) {
        PlayerTracker tracker = PlayerTracker.get();
        if (tracker == null) return;

        String server = ServerUtils.serverAddress();
        for (PlayerTracker.Entry entry : tracker.forCurrentServer()) {
            if (!entry.hasLogoutSpot()) continue;
            if (!server.equals(entry.server)) continue;

            int before = store.planner().size();
            ObjectivePlanner.Objective objective = store.add(ObjectivePlanner.Kind.LOGOUT_SPOT,
                "%s logged out".formatted(entry.name),
                entry.logoutX, entry.logoutY, entry.logoutZ, merge);

            if (chatFeedback.get() && store.planner().size() > before) {
                info("Queued logout spot: %s.", objective.label());
            }
        }
    }

    /** The next objective to pursue, or null. */
    public ObjectivePlanner.Ranked next() {
        ObjectiveStore store = ObjectiveStore.get();
        if (store == null || mc.player == null) return null;
        return store.planner().next((int) Math.floor(mc.player.getX()),
            (int) Math.floor(mc.player.getY()), (int) Math.floor(mc.player.getZ()),
            System.currentTimeMillis());
    }

    /** Sends the player to the top objective via Baritone, if there is one and Baritone is usable. */
    public String travelToNext() {
        ObjectivePlanner.Ranked next = next();
        if (next == null) return "the objective list is empty";

        ObjectivePlanner.Objective objective = next.objective();
        if (!dev.basehunt.bhclient.utils.BaritoneHelper.usable()) {
            return "Baritone is not usable: " + dev.basehunt.bhclient.utils.BaritoneHelper.health();
        }

        dev.basehunt.bhclient.utils.BaritoneHelper.walkTo(
            new net.minecraft.util.math.BlockPos(objective.x(), objective.y(), objective.z()));

        publish(AutomationEvent.Type.OBJECTIVE_PURSUED, Map.of(
            "objective", objective.label(),
            "coords", objective.coords(),
            "kind", objective.kind().name().toLowerCase()));

        return "heading to %s (%s)".formatted(objective.label(), objective.coords());
    }

    public String statusLine() {
        ObjectiveStore store = ObjectiveStore.get();
        if (store == null) return "not loaded";

        ObjectivePlanner.Ranked next = next();
        int size = store.planner().size();
        if (next == null) return size == 0 ? "no objectives" : "%d queued".formatted(size);
        return "next: %s %.0fm".formatted(next.objective().label(), next.priority());
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        ObjectiveStore store = ObjectiveStore.get();
        if (store == null || mc.player == null) {
            lines.add("the objective store is not loaded");
            return lines;
        }

        int px = (int) Math.floor(mc.player.getX());
        int py = (int) Math.floor(mc.player.getY());
        int pz = (int) Math.floor(mc.player.getZ());
        long now = System.currentTimeMillis();

        List<ObjectivePlanner.Ranked> ranked = store.planner().ranked(px, py, pz, now);
        if (ranked.isEmpty()) {
            lines.add("no objectives queued");
            return lines;
        }

        lines.add("objectives: %d".formatted(ranked.size()));
        for (ObjectivePlanner.Ranked entry : ranked) {
            ObjectivePlanner.Objective objective = entry.objective();
            double distance = objective.distanceTo(px, py, pz);
            lines.add("  #%d %s [%s] %s %.0fm (%.0f)".formatted(objective.id(), objective.label(),
                objective.kind().name().toLowerCase(), objective.coords(), distance, entry.priority()));
        }
        return lines;
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
