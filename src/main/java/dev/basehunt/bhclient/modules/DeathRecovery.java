package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.logic.DeathMemory;
import dev.basehunt.bhclient.systems.DeathStore;
import dev.basehunt.bhclient.systems.NavigationManager;
import dev.basehunt.bhclient.utils.BaritoneHelper;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;

/**
 * Remembers where you died - and what you were carrying - and can fly you back to recover it.
 *
 * <p>On an anarchy server the moment that matters most is the moment you die, because that is where your
 * loot is. Vanilla forgets it the instant you respawn. This module records the death position, the dimension
 * and the approximate amount carried, persists it per server (so a restart does not lose it), warns you, and
 * optionally navigates back with Baritone once you respawn.
 *
 * <p>The memory, the nearest-death search and the recovery marking live in the pure {@link DeathMemory}; this
 * class only detects the death (from the vanilla death screen), reads the live inventory and drives the
 * navigation layer. It does not forge packets and does not move you without a navigation request you can
 * cancel.
 */
public class DeathRecovery extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRecovery = settings.createGroup("Recovery");

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Announce a recorded death and where it was.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoRecover = sgRecovery.add(new BoolSetting.Builder()
        .name("auto-recover")
        .description("After respawning, fly back to the nearest un-recovered death in this dimension.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> flyBack = sgRecovery.add(new BoolSetting.Builder()
        .name("fly-back")
        .description("Recover by elytra rather than walking. Needs Baritone.")
        .defaultValue(true)
        .visible(autoRecover::get)
        .build()
    );

    private final Setting<Integer> arrivalDistance = sgRecovery.add(new IntSetting.Builder()
        .name("arrival-distance")
        .description("How close counts as arrived at the death spot.")
        .defaultValue(8)
        .min(1)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<Integer> recoveredRadius = sgRecovery.add(new IntSetting.Builder()
        .name("recovered-radius")
        .description("Mark a death recovered once you are within this many blocks of it.")
        .defaultValue(12)
        .min(1)
        .sliderRange(1, 64)
        .build()
    );

    private double lastX;
    private double lastY;
    private double lastZ;
    private String lastDimension = "unknown";
    private boolean sawDeathScreen;
    private boolean recordedThisDeath;

    public DeathRecovery() {
        super(BaseHuntingAddon.CATEGORY, "death-recovery",
            "Records where you died and what you carried, and can fly you back to recover it.");
    }

    @Override
    public void onActivate() {
        sawDeathScreen = false;
        recordedThisDeath = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        boolean dead = mc.currentScreen instanceof DeathScreen || mc.player.isDead();

        if (dead) {
            // Capture while the death screen is up: the position is still the death position and the
            // inventory has not been cleared by the respawn yet.
            if (!recordedThisDeath) {
                recordDeath();
                recordedThisDeath = true;
            }
            sawDeathScreen = true;
            return;
        }

        // Respawn: the death screen is gone and the player is alive again.
        if (sawDeathScreen) {
            sawDeathScreen = false;
            recordedThisDeath = false;
            onRespawn();
        }

        // Track the last known good position while alive, so a death has somewhere to point at.
        lastX = mc.player.getX();
        lastY = mc.player.getY();
        lastZ = mc.player.getZ();
        lastDimension = ServerUtils.dimension();

        checkRecovered();
    }

    private void recordDeath() {
        DeathStore store = DeathStore.get();
        if (store == null) return;

        int carried = countCarried();

        DeathMemory.DeathRecord record = store.record(
            ServerUtils.serverAddress(),
            DeathMemory.normalizeDimension(lastDimension),
            (int) Math.floor(lastX), (int) Math.floor(lastY), (int) Math.floor(lastZ),
            "", carried, System.currentTimeMillis());

        publish(AutomationEvent.Type.DEATH_RECORDED, Map.of(
            "coords", record.coords(),
            "dimension", record.dimension(),
            "carried", Integer.toString(carried)));

        if (chatFeedback.get()) {
            warning("Died at %s (%s) carrying %d item(s). Use /bh death recover to fly back.",
                record.coords(), record.dimension(), carried);
        }
    }

    private void onRespawn() {
        if (!autoRecover.get()) return;

        DeathMemory.DeathRecord target = nearestPending();
        if (target == null) return;

        recover(target);
    }

    /** Navigates to the nearest un-recovered death in the current dimension. */
    public String recoverNearest() {
        DeathMemory.DeathRecord target = nearestPending();
        if (target == null) return "nothing to recover in this dimension";
        return recover(target);
    }

    /** Navigates to a specific death by id. */
    public String recoverById(long id) {
        DeathStore store = DeathStore.get();
        if (store == null) return "the death store is not loaded";

        for (DeathMemory.DeathRecord record : store.memory().all()) {
            if (record.id() == id) {
                if (record.recovered()) return "death #" + id + " is already marked recovered";
                return recover(record);
            }
        }
        return "no death with id " + id;
    }

    private String recover(DeathMemory.DeathRecord record) {
        if (!record.dimension().equals(DeathMemory.normalizeDimension(ServerUtils.dimension()))) {
            String message = "death #%d is in the %s, you are in the %s"
                .formatted(record.id(), record.dimension(), ServerUtils.dimension());
            if (chatFeedback.get()) warning("%s", message);
            return message;
        }

        if (!BaritoneHelper.usable()) {
            String health = BaritoneHelper.health();
            String message = "cannot navigate: " + (health.isEmpty() ? "Baritone is not installed" : health);
            if (chatFeedback.get()) warning("%s", message);
            return message;
        }

        NavigationManager manager = NavigationManager.get();
        if (manager == null) return "the navigation layer is not installed";

        BlockPos destination = new BlockPos(record.x(), record.y(), record.z());
        manager.start(name, destination, arrivalDistance.get(), flyBack.get());

        publish(AutomationEvent.Type.DEATH_RECOVERY_STARTED, Map.of(
            "id", Long.toString(record.id()), "coords", record.coords()));

        if (chatFeedback.get()) info("Navigating back to death #%d at %s.", record.id(), record.coords());
        return "recovering death #" + record.id() + " at " + record.coords();
    }

    private void checkRecovered() {
        DeathStore store = DeathStore.get();
        if (store == null) return;

        int px = (int) Math.floor(lastX);
        int pz = (int) Math.floor(lastZ);
        String dimension = DeathMemory.normalizeDimension(lastDimension);
        int radius = recoveredRadius.get();

        for (DeathMemory.DeathRecord record : store.memory().pending()) {
            if (!record.server().equals(ServerUtils.serverAddress())) continue;
            if (!record.dimension().equals(dimension)) continue;
            if (record.distanceTo(px, pz) > radius) continue;

            if (store.markRecovered(record.id())) {
                publish(AutomationEvent.Type.DEATH_RECOVERED, Map.of(
                    "id", Long.toString(record.id()), "coords", record.coords()));
                if (chatFeedback.get()) info("Reached death #%d; marked recovered.", record.id());
            }
        }
    }

    private DeathMemory.DeathRecord nearestPending() {
        DeathStore store = DeathStore.get();
        if (store == null) return null;

        return store.memory()
            .nearestPending(ServerUtils.serverAddress(),
                DeathMemory.normalizeDimension(ServerUtils.dimension()),
                (int) Math.floor(lastX), (int) Math.floor(lastZ))
            .orElse(null);
    }

    private int countCarried() {
        int total = 0;
        for (int slot = 0; slot < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; slot++) {
            if (!mc.player.getInventory().getStack(slot).isEmpty()) total++;
        }
        return total;
    }

    public List<DeathMemory.DeathRecord> records() {
        DeathStore store = DeathStore.get();
        return store == null ? List.of() : store.forServer(ServerUtils.serverAddress());
    }

    public String statusLine() {
        DeathStore store = DeathStore.get();
        if (store == null) return "not loaded";

        int pending = store.memory().pendingCount();
        DeathMemory.DeathRecord nearest = nearestPending();
        if (nearest == null) return pending == 0 ? "no deaths" : "%d pending elsewhere".formatted(pending);
        return "nearest #%d %.0fm".formatted(nearest.id(),
            nearest.distanceTo((int) Math.floor(lastX), (int) Math.floor(lastZ)));
    }

    public List<String> describe() {
        List<String> lines = new java.util.ArrayList<>();
        DeathStore store = DeathStore.get();
        if (store == null) {
            lines.add("the death store is not loaded");
            return lines;
        }
        List<DeathMemory.DeathRecord> records = store.forServer(ServerUtils.serverAddress());
        lines.add("deaths here: %d (%d pending)".formatted(records.size(), store.memory().pendingCount()));
        for (DeathMemory.DeathRecord record : records) {
            lines.add("  " + DeathMemory.describe(record) + (record.carriedItems() > 0
                ? " carried " + record.carriedItems() + " stack(s)" : ""));
        }
        return lines;
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
