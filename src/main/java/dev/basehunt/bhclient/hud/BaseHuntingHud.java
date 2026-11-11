package dev.basehunt.bhclient.hud;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.modules.BaseHunter;
import dev.basehunt.bhclient.modules.BaseHunterV2;
import dev.basehunt.bhclient.modules.MendingManager;
import dev.basehunt.bhclient.modules.NewChunks;
import dev.basehunt.bhclient.modules.StorageMonitor;
import dev.basehunt.bhclient.modules.SurvivalManager;
import dev.basehunt.bhclient.systems.ExplorationStore;
import dev.basehunt.bhclient.systems.PlayerTracker;
import dev.basehunt.bhclient.systems.StashManager;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.item.Items;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import java.util.ArrayList;
import java.util.List;

/** Compact status readout so the important numbers are visible while flying. */
public class BaseHuntingHud extends HudElement {
    public static final HudElementInfo<BaseHuntingHud> INFO = new HudElementInfo<>(
        BaseHuntingAddon.HUD_GROUP,
        "base-hunting",
        "Shows new chunk, stash and autopilot status.",
        BaseHuntingHud::new
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> showServer = sgGeneral.add(new BoolSetting.Builder()
        .name("show-server")
        .description("Show the server address.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showNewChunks = sgGeneral.add(new BoolSetting.Builder()
        .name("show-new-chunks")
        .description("Show how many new chunks have been found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showStashes = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stashes")
        .description("Show how many stashes have been found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showPlayers = sgGeneral.add(new BoolSetting.Builder()
        .name("show-players")
        .description("Show how many players have been recorded.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showAutopilot = sgGeneral.add(new BoolSetting.Builder()
        .name("show-autopilot")
        .description("Show autopilot destination and distance.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showFireworks = sgGeneral.add(new BoolSetting.Builder()
        .name("show-fireworks")
        .description("Show the remaining firework count.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showDimension = sgGeneral.add(new BoolSetting.Builder()
        .name("show-dimension")
        .description("Show the current dimension.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("show-elytra")
        .description("Show equipped elytra durability and the spare count.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showMending = sgGeneral.add(new BoolSetting.Builder()
        .name("show-mending")
        .description("Show mending status when the mending manager is active.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showFlightState = sgGeneral.add(new BoolSetting.Builder()
        .name("show-flight-state")
        .description("Show whether you are airborne, grounded, swimming or gliding.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("show-inventory")
        .description("Show free inventory slots.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showSurvival = sgGeneral.add(new BoolSetting.Builder()
        .name("show-survival")
        .description("Show a survival warning line when something is wrong.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showExploration = sgGeneral.add(new BoolSetting.Builder()
        .name("show-exploration")
        .description("Show how many chunks have been explored and how many need another look.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showControl = sgGeneral.add(new BoolSetting.Builder()
        .name("show-control")
        .description("Show which automation currently owns movement and inventory.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showStorage = sgGeneral.add(new BoolSetting.Builder()
        .name("show-storage")
        .description("Show registered container count, storage operation and workflow state.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showShulkers = sgGeneral.add(new BoolSetting.Builder()
        .name("show-shulkers")
        .description("Show carried shulker box counts.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showV2Target = sgGeneral.add(new BoolSetting.Builder()
        .name("show-v2-target")
        .description("Show the base hunter v2 target and distance.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> labelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("label-color")
        .description("Colour of the labels.")
        .defaultValue(new SettingColor(150, 150, 150))
        .build()
    );

    private final Setting<SettingColor> valueColor = sgGeneral.add(new ColorSetting.Builder()
        .name("value-color")
        .description("Colour of the values.")
        .defaultValue(new SettingColor(0, 200, 255))
        .build()
    );

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow")
        .description("Draw a shadow behind the text.")
        .defaultValue(true)
        .build()
    );

    public BaseHuntingHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        List<String> lines = buildLines();

        double width = 0;
        for (String line : lines) width = Math.max(width, renderer.textWidth(line, shadow.get()));

        setSize(width, renderer.textHeight(shadow.get()) * lines.size());

        double y = this.y;

        for (String line : lines) {
            int separator = line.indexOf(':');

            if (separator < 0) {
                renderer.text(line, x, y, valueColor.get(), shadow.get());
            } else {
                String label = line.substring(0, separator + 1);
                String value = line.substring(separator + 1);

                double labelWidth = renderer.text(label, x, y, labelColor.get(), shadow.get());
                renderer.text(value, x + labelWidth, y, valueColor.get(), shadow.get());
            }

            y += renderer.textHeight(shadow.get());
        }
    }

    private List<String> buildLines() {
        List<String> lines = new ArrayList<>();

        if (showServer.get()) lines.add("Server: " + ServerUtils.serverAddress());
        lines.add("Position: " + ServerUtils.coordinates());

        if (showDimension.get()) lines.add("Dimension: " + ServerUtils.dimension());

        if (showFlightState.get() && mc.player != null) {
            lines.add("Flight: " + dev.basehunt.bhclient.utils.ElytraManager.flightState()
                .name().toLowerCase(java.util.Locale.ROOT));
        }

        NewChunks newChunks = Modules.get().get(NewChunks.class);
        if (showNewChunks.get() && newChunks != null && newChunks.isActive()) {
            lines.add("New chunks: %d".formatted(newChunks.getTrackedCount()));
        }

        StashManager stashManager = StashManager.get();
        if (showStashes.get() && stashManager != null) {
            lines.add("Stashes: %d".formatted(stashManager.forCurrentServer().size()));
        }

        PlayerTracker tracker = PlayerTracker.get();
        if (showPlayers.get() && tracker != null) {
            lines.add("Players: %d".formatted(tracker.forCurrentServer().size()));
        }

        BaseHunter hunter = Modules.get().get(BaseHunter.class);
        if (showAutopilot.get() && hunter != null && hunter.isActive()) {
            var destination = hunter.getCurrentDestination();

            if (destination != null) {
                double dx = destination.getX() - mc.player.getX();
                double dz = destination.getZ() - mc.player.getZ();
                lines.add("Destination: %d, %d".formatted(destination.getX(), destination.getZ()));
                lines.add("Remaining: %d blocks".formatted((int) Math.sqrt(dx * dx + dz * dz)));
            }

            lines.add("Status: " + (hunter.isPaused() ? "paused" : "flying"));
            lines.add("Flown: %,d blocks".formatted((long) hunter.getTravelledBlocks()));
        }

        BaseHunterV2 v2 = Modules.get().get(BaseHunterV2.class);
        if (showV2Target.get() && v2 != null && v2.isActive() && v2.isStarted()) {
            lines.add("Mode: " + v2.getExploreMode().name().toLowerCase(java.util.Locale.ROOT));

            var target = v2.getCurrentTarget();
            if (target != null) {
                lines.add("V2 target: %d, %d".formatted(target.x(), target.z()));
                lines.add("V2 distance: %d blocks".formatted(
                    (int) dev.basehunt.bhclient.utils.ElytraController.horizontalDistanceTo(target.x(), target.z())));
            }
            lines.add("V2 queue: %d".formatted(v2.getQueuedCount()));
            lines.add("V2 status: " + (v2.isPaused() ? "paused" : "flying"));
        }

        if (showElytra.get() && mc.player != null) {
            lines.add("Elytra: " + dev.basehunt.bhclient.utils.ElytraManager.status());
            lines.add("Spare elytras: %d".formatted(dev.basehunt.bhclient.utils.ElytraManager.spareCount()));
        }

        MendingManager mending = Modules.get().get(MendingManager.class);
        if (showMending.get() && mending != null && mending.isActive()) {
            lines.add("Mending: " + mending.statusLine());
        }

        if (showFireworks.get() && mc.player != null) {
            lines.add("Fireworks: %d".formatted(countFireworks()));
        }

        if (showInventory.get() && mc.player != null) {
            lines.add("Free slots: %d".formatted(countFreeSlots()));
        }

        SurvivalManager survival = Modules.get().get(SurvivalManager.class);
        if (showSurvival.get() && survival != null && survival.isActive()) {
            lines.add("Survival: " + survival.statusLine());
        }

        ExplorationStore exploration = ExplorationStore.get();
        if (showExploration.get() && exploration != null) {
            lines.add("Explored: %,d chunks".formatted(exploration.regions().exploredCount()));
            if (exploration.regions().reviewCount() > 0) {
                lines.add("To review: %,d chunks".formatted(exploration.regions().reviewCount()));
            }
        }

        if (showControl.get()) {
            AutomationManager manager = AutomationManager.get();
            if (manager != null) lines.add("Control: " + manager.controlSummary());
        }

        StorageMonitor storage = Modules.get().get(StorageMonitor.class);
        if (showStorage.get() && storage != null && storage.isActive()) {
            lines.add("Storage: " + storage.statusLine());
        }

        if (showShulkers.get() && mc.player != null) {
            lines.add("Shulkers: " + dev.basehunt.bhclient.systems.ShulkerManager.status());
        }

        return lines;
    }

    private int countFreeSlots() {
        int free = 0;
        for (int slot = 0; slot < net.minecraft.entity.player.PlayerInventory.MAIN_SIZE; slot++) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) free++;
        }
        return free;
    }

    private int countFireworks() {
        int total = 0;

        for (int slot = 0; slot < mc.player.getInventory().size(); slot++) {
            if (mc.player.getInventory().getStack(slot).getItem() == Items.FIREWORK_ROCKET) {
                total += mc.player.getInventory().getStack(slot).getCount();
            }
        }

        return total;
    }
}
