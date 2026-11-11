package dev.basehunt.bhclient.gui;

import dev.basehunt.bhclient.modules.AutoStorage;
import dev.basehunt.bhclient.modules.AutomationBrain;
import dev.basehunt.bhclient.modules.BaseHunter;
import dev.basehunt.bhclient.modules.BaseHunterV2;
import dev.basehunt.bhclient.modules.ChestSorter;
import dev.basehunt.bhclient.modules.CommandTerminal;
import dev.basehunt.bhclient.modules.EnderChestManager;
import dev.basehunt.bhclient.modules.InventoryManager;
import dev.basehunt.bhclient.modules.KitBot;
import dev.basehunt.bhclient.modules.MendingManager;
import dev.basehunt.bhclient.modules.NewChunks;
import dev.basehunt.bhclient.modules.PlayerLogger;
import dev.basehunt.bhclient.modules.RestockManager;
import dev.basehunt.bhclient.modules.ShulkerManager;
import dev.basehunt.bhclient.modules.StashFinder;
import dev.basehunt.bhclient.modules.StorageMonitor;
import dev.basehunt.bhclient.modules.StorageNetwork;
import dev.basehunt.bhclient.modules.SurvivalManager;
import dev.basehunt.bhclient.modules.WebhookNotifier;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.logic.SurvivalStatus;
import dev.basehunt.bhclient.core.task.Task;
import dev.basehunt.bhclient.core.terminal.TerminalBuffer;
import dev.basehunt.bhclient.systems.AutomationEngine;
import dev.basehunt.bhclient.systems.ContainerInteractionManager;
import dev.basehunt.bhclient.systems.DatabaseIO;
import dev.basehunt.bhclient.systems.EnderChestStore;
import dev.basehunt.bhclient.systems.ExplorationStore;
import dev.basehunt.bhclient.systems.PlayerTracker;
import dev.basehunt.bhclient.systems.StashManager;
import dev.basehunt.bhclient.systems.StorageRegistry;
import dev.basehunt.bhclient.utils.ElytraManager;
import dev.basehunt.bhclient.utils.ServerUtils;
import dev.basehunt.bhclient.utils.WebhookSettings;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WView;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;

import java.util.List;

/**
 * The main control screen for the addon.
 *
 * <p>Rather than duplicate Meteor's settings screens, every button here calls into the same public methods
 * the commands use, so the GUI and the command line can never drift apart. Buttons that would open a
 * module's own settings simply navigate to Meteor's module screen.
 */
public class BaseHuntingScreen extends WindowScreen {
    private WTable stashTable;
    private WTable playerTable;
    private WTextBox searchBox;
    private String search = "";

    public BaseHuntingScreen(GuiTheme theme) {
        super(theme, "Base Hunting");
    }

    /** Opens this screen without leaving a stale parent behind, matching Meteor's own navigation. */
    public static void open(GuiTheme theme) {
        BaseHuntingScreen screen = new BaseHuntingScreen(theme);
        screen.parent = null;
        Utils.screenToOpen = screen;
    }

    @Override
    public void initWidgets() {
        statusSection();
        automationSection();
        explorationSection();
        routeSection();
        activitySection();
        stashSection();
        playerSection();
        equipmentSection();
        storageSection();
        survivalSection();
        notificationSection();
        databaseSection();
        terminalSection();
    }

    // Sections

    private void statusSection() {
        add(theme.section("Status")).expandX().widget();

        add(theme.label("Server: " + ServerUtils.serverAddress()));
        add(theme.label("Position: " + ServerUtils.coordinates() + "  (" + ServerUtils.dimension() + ")"));
        add(theme.label("Elytra: " + ElytraManager.status()));

        SurvivalManager survival = Modules.get().get(SurvivalManager.class);
        add(theme.label("Survival: " + (survival == null || !survival.isActive() ? "inactive" : survival.statusLine())));

        var actions = add(theme.horizontalList()).expandX().widget();

        WButton start = actions.add(theme.button("Start V2")).expandX().widget();
        start.action = () -> {
            BaseHunterV2 v2 = Modules.get().get(BaseHunterV2.class);
            if (v2 == null) return;
            if (!v2.isActive()) v2.enable();
            v2.start();
            reload();
        };

        WButton pause = actions.add(theme.button("Pause")).expandX().widget();
        pause.action = () -> {
            BaseHunterV2 v2 = Modules.get().get(BaseHunterV2.class);
            if (v2 != null && v2.isStarted()) v2.pause();
            reload();
        };

        WButton resume = actions.add(theme.button("Resume")).expandX().widget();
        resume.action = () -> {
            BaseHunterV2 v2 = Modules.get().get(BaseHunterV2.class);
            if (v2 != null && v2.isStarted()) v2.resume();
            reload();
        };

        WButton stop = actions.add(theme.button("Stop")).expandX().widget();
        stop.action = () -> {
            BaseHunterV2 v2 = Modules.get().get(BaseHunterV2.class);
            if (v2 != null) v2.stop();
            reload();
        };
    }

    private void automationSection() {
        add(theme.section("Automation")).expandX().widget();

        AutomationEngine engine = AutomationEngine.get();
        AutomationBrain brain = Modules.get().get(AutomationBrain.class);
        AutoStorage storage = Modules.get().get(AutoStorage.class);
        KitBot kits = Modules.get().get(KitBot.class);

        add(theme.label("Engine: " + (engine == null ? "unavailable" : engine.statusLine())));
        add(theme.label("Brain: " + (brain == null || !brain.isActive() ? "inactive" : brain.statusLine())));
        add(theme.label("Auto-storage: " + (storage == null || !storage.isActive() ? "inactive" : storage.statusLine())));
        add(theme.label("Kits: " + (kits == null || !kits.isActive() ? "inactive" : kits.statusLine())));

        if (engine != null) {
            Task active = engine.queue().active();
            add(theme.label("Active task: " + (active == null ? "none" : active.describe())));
            add(theme.label("Queued: %d, finished: %d".formatted(
                engine.queue().queueSize(), engine.queue().history().size())));
        }

        AutomationManager manager = AutomationManager.get();
        add(theme.label("Control: " + (manager == null ? "unavailable" : manager.controlSummary())));

        var row1 = add(theme.horizontalList()).expandX().widget();
        WButton brainToggle = row1.add(theme.button(brain != null && brain.isActive() ? "Brain Off" : "Brain On"))
            .expandX().widget();
        brainToggle.action = () -> {
            if (brain != null) brain.toggle();
            reload();
        };

        WButton storageToggle = row1.add(theme.button(storage != null && storage.isActive()
            ? "Storage Off" : "Storage On")).expandX().widget();
        storageToggle.action = () -> {
            if (storage != null) storage.toggle();
            reload();
        };

        WButton kitToggle = row1.add(theme.button(kits != null && kits.isActive() ? "Kits Off" : "Kits On"))
            .expandX().widget();
        kitToggle.action = () -> {
            if (kits != null) kits.toggle();
            reload();
        };

        var row2 = add(theme.horizontalList()).expandX().widget();

        WButton claimAll = row2.add(theme.button("Claim Kits")).expandX().widget();
        claimAll.action = () -> {
            if (kits == null) infoLine("The kit bot is not loaded.");
            else infoLine(kits.claimAll());
            reload();
        };

        WButton cancel = row2.add(theme.button("Cancel Tasks")).expandX().widget();
        cancel.action = () -> {
            if (engine != null) infoLine("Cancelled %d task(s).".formatted(engine.cancelAll("cancelled from GUI")));
            reload();
        };

        WButton reactions = row2.add(theme.button("Reactions")).expandX().widget();
        reactions.action = () -> {
            if (brain != null) for (String line : brain.recent()) infoLine("  " + line);
            reload();
        };
    }

    private void explorationSection() {
        add(theme.section("Exploration")).expandX().widget();
        ExplorationStore store = ExplorationStore.get();
        int explored = store == null ? 0 : store.regions().exploredCount();
        int review = store == null ? 0 : store.regions().reviewCount();

        add(theme.label("Explored chunks: %,d".formatted(explored)));
        add(theme.label("Needs another look: %,d".formatted(review)));

        NewChunks newChunks = Modules.get().get(NewChunks.class);
        if (newChunks != null) {
            add(theme.label("New chunks tracked: %,d".formatted(newChunks.getTrackedCount())));
        }

        var actions = add(theme.horizontalList()).expandX().widget();

        WButton v1Settings = actions.add(theme.button("V1 Settings")).expandX().widget();
        v1Settings.action = () -> openModule(BaseHunter.class);

        WButton v2Settings = actions.add(theme.button("V2 Settings")).expandX().widget();
        v2Settings.action = () -> openModule(BaseHunterV2.class);

        WButton reset = actions.add(theme.button("Reset Explored")).expandX().widget();
        reset.action = () -> {
            ExplorationStore s = ExplorationStore.get();
            if (s != null) s.clearExplored();
            reload();
        };

        // Sweep pattern selector: picking one sets the mode on V2 and starts it, so the pattern is the same
        // whether it is chosen here or with /bh explore <mode>.
        add(theme.label("Sweep pattern:"));
        var modes = add(theme.horizontalList()).expandX().widget();

        for (BaseHunterV2.ExploreMode mode : new BaseHunterV2.ExploreMode[] {
            BaseHunterV2.ExploreMode.Adaptive, BaseHunterV2.ExploreMode.Grid,
            BaseHunterV2.ExploreMode.Spiral, BaseHunterV2.ExploreMode.Heading }) {

            WButton button = modes.add(theme.button(mode.name())).expandX().widget();
            button.action = () -> {
                BaseHunterV2 v2 = Modules.get().get(BaseHunterV2.class);
                if (v2 == null) return;
                if (!v2.isActive()) v2.enable();
                v2.setExploreMode(mode);
                v2.start();
                reload();
            };
        }
    }

    private void routeSection() {
        add(theme.section("Route")).expandX().widget();

        ExplorationStore store = ExplorationStore.get();
        int waypoints = store == null ? 0 : store.customWaypoints().size();
        add(theme.label("Waypoints: %d".formatted(waypoints)));

        var actions = add(theme.horizontalList()).expandX().widget();

        WButton addHere = actions.add(theme.button("Add Here")).expandX().widget();
        addHere.action = () -> {
            ExplorationStore s = ExplorationStore.get();
            if (s == null || ServerUtils.mc().player == null) return;
            s.addWaypoint("gui", ServerUtils.mc().player.getBlockPos(), ServerUtils.dimension());
            infoLine("Waypoint added at " + ServerUtils.coordinates());
            reload();
        };

        WButton fly = actions.add(theme.button("Fly Route")).expandX().widget();
        fly.action = () -> {
            BaseHunterV2 v2 = Modules.get().get(BaseHunterV2.class);
            ExplorationStore s = ExplorationStore.get();
            if (v2 == null || s == null || s.customWaypoints().isEmpty()) {
                infoLine("No waypoints to fly.");
                return;
            }
            if (!v2.isActive()) v2.enable();
            v2.setExploreMode(BaseHunterV2.ExploreMode.Custom);
            v2.start();
            reload();
        };

        WButton clear = actions.add(theme.button("Clear Route")).expandX().widget();
        clear.action = () -> {
            ExplorationStore s = ExplorationStore.get();
            if (s != null) s.clearWaypoints();
            reload();
        };
    }

    private void activitySection() {
        add(theme.section("Recent Activity")).expandX().widget();

        AutomationManager manager = AutomationManager.get();
        if (manager == null) {
            add(theme.label("Automation bus is not running."));
            return;
        }

        List<AutomationEvent> recent = manager.bus().recent(8);
        if (recent.isEmpty()) {
            add(theme.label("No automation events yet this session."));
            return;
        }

        for (AutomationEvent event : recent) {
            String detail = describeEvent(event);
            add(theme.label("%s%s".formatted(event.type().name(), detail.isEmpty() ? "" : " - " + detail)));
        }
    }

    /** One-line summary of an event for the activity list. */
    private String describeEvent(AutomationEvent event) {
        return switch (event.type()) {
            case STASH_DISCOVERED, STASH_UPGRADED, STRUCTURE_FOUND -> "%s (score %s)".formatted(
                event.get("classification"), event.get("score"));
            case PLAYER_SIGHTED -> "%s at %s, %s".formatted(event.get("player"), event.get("x"), event.get("z"));
            case ELYTRA_REPLACED, ELYTRA_DURABILITY_LOW, ELYTRA_BROKEN, ELYTRA_MISSING -> firstNonEmpty(
                event.get("status"), event.get("remaining"), event.source());
            case ROUTE_INTERRUPTED, FLIGHT_DEPLOY_FAILED, LANDED_UNEXPECTEDLY -> firstNonEmpty(
                event.get("reason"), event.get("state"), event.source());
            default -> event.source();
        };
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    private void stashSection() {
        add(theme.section("Stash Database")).expandX().widget();

        var actions = add(theme.horizontalList()).expandX().widget();

        WButton finder = actions.add(theme.button("Stash Finder")).expandX().widget();
        finder.action = () -> openModule(StashFinder.class);

        WButton clear = actions.add(theme.button("Clear Server")).expandX().widget();
        clear.action = () -> {
            StashManager manager = StashManager.get();
            if (manager != null) manager.clearCurrentServer();
            reload();
        };

        stashTable = add(theme.table()).expandX().widget();
        buildStashTable();
    }

    private void buildStashTable() {
        stashTable.clear();

        StashManager manager = StashManager.get();
        if (manager == null) return;

        List<StashManager.Stash> stashes = manager.forCurrentServer();
        if (stashes.isEmpty()) {
            stashTable.add(theme.label("No stashes recorded for this server."));
            stashTable.row();
            return;
        }

        for (StashManager.Stash stash : stashes) {
            stashTable.add(theme.label(stash.pos.toShortString()));
            stashTable.add(theme.label(stash.classification));
            stashTable.add(theme.label(String.valueOf(stash.score())));
            stashTable.row();
        }
    }

    private void playerSection() {
        add(theme.section("Players")).expandX().widget();

        var searchRow = add(theme.horizontalList()).expandX().widget();
        searchBox = searchRow.add(theme.textBox(search, "search name...")).expandX().widget();
        searchBox.action = () -> {
            search = searchBox.get();
            buildPlayerTable();
        };

        var actions = add(theme.horizontalList()).expandX().widget();

        WButton logger = actions.add(theme.button("Player Logger")).expandX().widget();
        logger.action = () -> openModule(PlayerLogger.class);

        WButton clear = actions.add(theme.button("Clear Server")).expandX().widget();
        clear.action = () -> {
            PlayerTracker tracker = PlayerTracker.get();
            if (tracker != null) tracker.clearCurrentServer();
            reload();
        };

        playerTable = add(theme.table()).expandX().widget();
        buildPlayerTable();
    }

    private void buildPlayerTable() {
        if (playerTable == null) return;
        playerTable.clear();

        PlayerTracker tracker = PlayerTracker.get();
        if (tracker == null) return;

        List<PlayerTracker.Entry> entries = search.isBlank() ? tracker.forCurrentServer() : tracker.search(search);
        if (entries.isEmpty()) {
            playerTable.add(theme.label("No matching players."));
            playerTable.row();
            return;
        }

        for (PlayerTracker.Entry entry : entries) {
            playerTable.add(theme.label(entry.name));
            playerTable.add(theme.label(entry.lastCoords()));
            playerTable.add(theme.label(entry.hasLogoutSpot() ? entry.logoutCoords() : "-"));
            playerTable.row();
        }
    }

    private void equipmentSection() {
        add(theme.section("Equipment")).expandX().widget();

        add(theme.label("Elytra: " + ElytraManager.status()));
        add(theme.label("Spare elytras: %d".formatted(ElytraManager.spareCount())));

        var actions = add(theme.horizontalList()).expandX().widget();

        WButton elytra = actions.add(theme.button("Equip Best Elytra")).expandX().widget();
        elytra.action = () -> {
            boolean equipped = ElytraManager.equipBest();
            infoLine(equipped ? "Equipped: " + ElytraManager.status() : "No spare elytra found");
            reload();
        };

        WButton mending = actions.add(theme.button("Mending")).expandX().widget();
        mending.action = () -> openModule(MendingManager.class);

        WButton echest = actions.add(theme.button("Ender Chest")).expandX().widget();
        echest.action = () -> openModule(EnderChestManager.class);
    }

    private void storageSection() {
        add(theme.section("Storage & Inventory")).expandX().widget();

        StorageRegistry registry = StorageRegistry.get();
        ContainerInteractionManager interaction = ContainerInteractionManager.get();
        StorageMonitor monitor = Modules.get().get(StorageMonitor.class);
        InventoryManager inventory = Modules.get().get(InventoryManager.class);

        int containers = registry == null ? 0 : registry.forCurrentServer().size();
        int verified = 0;
        int reachable = 0;
        if (registry != null) {
            for (StorageRegistry.Container container : registry.forCurrentServer()) {
                if (container.verified) verified++;
                if (dev.basehunt.bhclient.utils.ContainerHelper.isReachable(container.pos)) reachable++;
            }
        }

        add(theme.label("Containers: %d (%d observed, %d in reach)".formatted(containers, verified, reachable)));
        add(theme.label("Interaction: " + (interaction == null ? "unavailable" : interaction.status())));
        add(theme.label("Queue: " + (interaction == null ? 0 : interaction.queueSize())));
        add(theme.label("Workflow: " + (monitor == null ? "module inactive" : monitor.engine().describe())));
        add(theme.label("Free slots: " + (inventory == null ? "?" : inventory.freeSlots())));
        add(theme.label("Shulkers: " + dev.basehunt.bhclient.systems.ShulkerManager.status()));

        ContainerInteractionManager.Result last = interaction == null ? null : interaction.lastResult();
        if (last != null) {
            add(theme.label("Last: %s %s (%s)".formatted(last.success() ? "ok" : "failed", last.label(), last.message())));
        }

        var actions = add(theme.horizontalList()).expandX().widget();

        WButton sort = actions.add(theme.button("Sort Inventory")).expandX().widget();
        sort.action = () -> {
            if (inventory == null) { infoLine("Inventory manager is not loaded."); return; }
            infoLine(inventory.sortNow("gui"));
            reload();
        };

        WButton register = actions.add(theme.button("Register Container")).expandX().widget();
        register.action = () -> {
            StorageNetwork network = Modules.get().get(StorageNetwork.class);
            if (network == null) { infoLine("Storage network is not loaded."); return; }
            infoLine(network.registerLooking());
            reload();
        };

        var actions2 = add(theme.horizontalList()).expandX().widget();

        WButton runWorkflow = actions2.add(theme.button("Run Workflow")).expandX().widget();
        runWorkflow.action = () -> {
            if (monitor == null) { infoLine("Storage monitor is not loaded."); return; }
            infoLine(monitor.start(monitor.workflowName()));
            reload();
        };

        WButton restock = actions2.add(theme.button("Restock")).expandX().widget();
        restock.action = () -> {
            RestockManager manager = Modules.get().get(RestockManager.class);
            if (manager == null) { infoLine("Restock manager is not loaded."); return; }
            infoLine(manager.start());
            reload();
        };

        var modules = add(theme.horizontalList()).expandX().widget();

        WButton inv = modules.add(theme.button("Inventory")).expandX().widget();
        inv.action = () -> openModule(InventoryManager.class);

        WButton chest = modules.add(theme.button("Chest Sorter")).expandX().widget();
        chest.action = () -> openModule(ChestSorter.class);

        var modules2 = add(theme.horizontalList()).expandX().widget();

        WButton networkButton = modules2.add(theme.button("Network")).expandX().widget();
        networkButton.action = () -> openModule(StorageNetwork.class);

        WButton shulkerButton = modules2.add(theme.button("Shulkers")).expandX().widget();
        shulkerButton.action = () -> openModule(ShulkerManager.class);

        WButton monitorButton = modules2.add(theme.button("Monitor")).expandX().widget();
        monitorButton.action = () -> openModule(StorageMonitor.class);

        if (registry != null && !registry.forCurrentServer().isEmpty()) {
            add(theme.label("Registered:"));
            int shown = 0;
            for (StorageRegistry.Container container : registry.forCurrentServer()) {
                if (shown++ >= 8) { add(theme.label("  ... and %d more".formatted(containers - 8))); break; }
                add(theme.label("  " + container.describe()
                    + (container.verified ? " (" + container.contents().size() + " stacks)" : " (unobserved)")));
            }
        }
    }

    private void survivalSection() {
        add(theme.section("Survival")).expandX().widget();

        SurvivalManager survival = Modules.get().get(SurvivalManager.class);
        if (survival != null && survival.isActive()) {
            add(theme.label("Status: " + survival.statusLine()));
            for (SurvivalStatus.Issue issue : survival.issues()) {
                add(theme.label(" - " + issue.description()));
            }
        } else {
            add(theme.label("Survival manager is inactive."));
        }

        var actions = add(theme.horizontalList()).expandX().widget();

        WButton toggle = actions.add(theme.button(survival != null && survival.isActive() ? "Disable" : "Enable"))
            .expandX().widget();
        toggle.action = () -> {
            if (survival != null) survival.toggle();
            reload();
        };

        WButton settings = actions.add(theme.button("Thresholds")).expandX().widget();
        settings.action = () -> openModule(SurvivalManager.class);
    }

    private void notificationSection() {
        add(theme.section("Notifications")).expandX().widget();

        WebhookNotifier notifier = Modules.get().get(WebhookNotifier.class);
        add(theme.label("Webhook notifier: " + (notifier != null && notifier.isActive() ? "active" : "inactive")));
        add(theme.label("Global mute: " + (WebhookSettings.globallyMuted() ? "on" : "off")));

        var actions = add(theme.horizontalList()).expandX().widget();

        WButton settings = actions.add(theme.button("Settings")).expandX().widget();
        settings.action = () -> openModule(WebhookNotifier.class);

        WButton test = actions.add(theme.button("Send Test")).expandX().widget();
        test.action = () -> {
            if (notifier == null || !notifier.isActive()) {
                infoLine("Enable the webhook-notifier module first.");
            } else {
                notifier.sendTest();
                infoLine("Test webhook queued.");
            }
        };
    }

    private void databaseSection() {
        add(theme.section("Database")).expandX().widget();

        StashManager stashes = StashManager.get();
        PlayerTracker players = PlayerTracker.get();
        EnderChestStore chests = EnderChestStore.get();
        ExplorationStore exploration = ExplorationStore.get();

        add(theme.label("Stashes: %,d total, %,d here".formatted(
            stashes == null ? 0 : stashes.getAll().size(),
            stashes == null ? 0 : stashes.forCurrentServer().size())));
        add(theme.label("Players: %,d total, %,d here".formatted(
            players == null ? 0 : players.getAll().size(),
            players == null ? 0 : players.forCurrentServer().size())));
        add(theme.label("Ender chests recorded: %,d".formatted(chests == null ? 0 : chests.forCurrentServer().size())));
        add(theme.label("Explored chunks: %,d".formatted(
            exploration == null ? 0 : exploration.regions().exploredCount())));

        int skipped = (stashes == null ? 0 : stashes.lastSkippedRecords())
            + (players == null ? 0 : players.lastSkippedRecords())
            + (chests == null ? 0 : chests.lastSkippedRecords())
            + (exploration == null ? 0 : exploration.lastSkippedRecords());

        add(theme.label(skipped > 0
            ? "Integrity: %d unreadable record(s) skipped".formatted(skipped)
            : "Integrity: all records readable"));

        var actions = add(theme.horizontalList()).expandX().widget();

        WButton exportFile = actions.add(theme.button("Export")).expandX().widget();
        exportFile.action = () -> {
            boolean ok = DatabaseIO.exportToFile();
            infoLine(ok ? "Exported to " + DatabaseIO.exportFile().getName() : "Export failed");
            reload();
        };

        WButton importFile = actions.add(theme.button("Import")).expandX().widget();
        importFile.action = () -> {
            int applied = DatabaseIO.importFromFile();
            infoLine(applied == 0 ? "Nothing imported (no export file?)" : "Imported %d section(s)".formatted(applied));
            reload();
        };

        WButton clipboard = actions.add(theme.button("Copy")).expandX().widget();
        clipboard.action = () -> {
            boolean ok = DatabaseIO.exportToClipboard();
            infoLine(ok ? "Copied database to clipboard" : "Copy failed");
        };
    }

    // Helpers

    private void terminalSection() {
        add(theme.section("Terminal")).expandX().widget();

        CommandTerminal terminal = Modules.get().get(CommandTerminal.class);
        if (terminal == null) {
            add(theme.label("The command terminal module is not loaded."));
            return;
        }

        add(theme.label("Type a command and press Enter. Tab completes; Up/Down walk the history."));
        add(theme.label("Aliases: " + (terminal.aliases().isEmpty() ? "none"
            : String.join(", ", terminal.aliases().all().keySet()))));

        // Scrollable output. The buffer holds the real lines; a widget is rebuilt from it each time.
        WView output = add(theme.view()).expandX().widget();
        output.maxHeight = 200;
        output.scrollOnlyWhenMouseOver = true;

        List<TerminalBuffer.Line> lines = terminal.buffer().lines();
        int from = Math.max(0, lines.size() - 24);
        for (int i = from; i < lines.size(); i++) {
            TerminalBuffer.Line line = lines.get(i);
            String prefix = terminal.showTimestamps() ? timePrefix(line.timestamp()) : "";
            output.add(theme.label(prefix + line.text())).expandX();
        }
        if (lines.isEmpty()) output.add(theme.label("(no output yet - try 'help' or 'status')")).expandX();

        // Input row. The renderer routes Tab completion to the terminal's registry.
        TerminalRenderer.completionSource = terminal::suggest;
        WTextBox input = add(theme.textBox("type a command...", (text, c) -> true, TerminalRenderer.class))
            .expandX().widget();
        input.action = () -> {
            String command = input.get().trim();
            if (command.isEmpty()) return;
            terminal.execute(command);
            input.set("");
            reload();
        };

        var actions = add(theme.horizontalList()).expandX().widget();

        WButton help = actions.add(theme.button("Help")).expandX().widget();
        help.action = () -> {
            terminal.execute("help");
            reload();
        };

        WButton status = actions.add(theme.button("Status")).expandX().widget();
        status.action = () -> {
            terminal.execute("status");
            reload();
        };

        WButton clear = actions.add(theme.button("Clear")).expandX().widget();
        clear.action = () -> {
            terminal.buffer().clear();
            reload();
        };

        WButton terminalSettings = actions.add(theme.button("Settings")).expandX().widget();
        terminalSettings.action = () -> openModule(CommandTerminal.class);
    }

    private static String timePrefix(long timestamp) {
        long seconds = Math.max(0, (System.currentTimeMillis() - timestamp) / 1000L);
        return "[%02d:%02d] ".formatted(seconds / 60, seconds % 60);
    }

    private void infoLine(String message) {
        add(theme.label(message)).expandX();
    }

    private void openModule(Class<? extends Module> type) {
        Module module = Modules.get().get(type);
        if (module == null) return;

        // Defer navigation until after this frame so the widget tree is not mutated mid-render.
        taskAfterRender = () -> {
            WidgetScreen screen = GuiThemes.get().moduleScreen(module);
            screen.parent = null;
            Utils.screenToOpen = screen;
        };
    }
}
