package dev.basehunt.bhclient.gui;

import dev.basehunt.bhclient.core.sixbt.KitLoadout;
import dev.basehunt.bhclient.core.sixbt.SixbtCommand;
import dev.basehunt.bhclient.modules.KitBot;
import dev.basehunt.bhclient.modules.SixbtHomeManager;
import dev.basehunt.bhclient.modules.SixbtHotspotTracker;
import dev.basehunt.bhclient.modules.SixbtPlayerTracker;
import dev.basehunt.bhclient.modules.SixbtTpaManager;
import dev.basehunt.bhclient.modules.SixbtTpsMonitor;
import dev.basehunt.bhclient.systems.SixbtPlayerStore;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The 6b6t control menu: everything the addon knows about the server in one screen.
 *
 * <p>6b6t is an anarchy server with a specific command set, and this screen is organised around it: kit
 * delivery, homes, teleport requests, TPS, hotspots and player history, each with a status line and the
 * controls that matter. Every button calls the same public method the commands and the terminal call, so the
 * three ways into the addon cannot drift apart.
 */
public class SixbtMenuScreen extends WindowScreen {
    private final KitBot bot;

    public SixbtMenuScreen(GuiTheme theme) {
        super(theme, "6b6t");
        this.bot = Modules.get().get(KitBot.class);
    }

    /** Opens the menu without leaving a stale parent behind. */
    public static void open(GuiTheme theme) {
        SixbtMenuScreen screen = new SixbtMenuScreen(theme);
        screen.parent = null;
        Utils.screenToOpen = screen;
    }

    @Override
    public void initWidgets() {
        serverSection();
        kitSection();
        loadoutSection();
        homeSection();
        tpaSection();
        tpsSection();
        hotspotSection();
        playerSection();
        commandSection();
    }

    private void serverSection() {
        add(theme.section("Server")).expandX().widget();
        String address = ServerUtils.serverAddress();
        boolean isSixbt = SixbtCommand.isSixbtServer(address);
        add(theme.label("Address: " + address + (isSixbt ? "  (6b6t)" : "  (not 6b6t)")));
        add(theme.label("Position: " + ServerUtils.coordinates() + "  (" + ServerUtils.dimension() + ")"));
        if (!isSixbt) {
            add(theme.label("These tools are built for play.6b6t.org."));
        }
    }

    private void kitSection() {
        add(theme.section("Kit Delivery")).expandX().widget();

        if (bot == null) {
            add(theme.label("The kit bot module is not loaded."));
            return;
        }

        add(theme.label("Status: " + bot.deliveryStatusLine()));
        add(theme.label("Claiming: " + bot.statusLine()));

        var actions = add(theme.horizontalList()).expandX().widget();

        WButton toggle = actions.add(theme.button(bot.isDeliveryEnabled() ? "Delivery: On" : "Delivery: Off"))
            .expandX().widget();
        toggle.action = () -> {
            bot.setDeliveryEnabled(!bot.isDeliveryEnabled());
            reload();
        };

        WButton clear = actions.add(theme.button("Clear Queue")).expandX().widget();
        clear.action = () -> {
            bot.clearQueue();
            reload();
        };

        WButton settings = actions.add(theme.button("Settings")).expandX().widget();
        settings.action = () -> openModule(KitBot.class);
    }

    private void loadoutSection() {
        add(theme.section("Kit Loadouts")).expandX().widget();

        if (bot == null) return;

        if (bot.loadouts().isEmpty()) {
            add(theme.label("(no loadouts)"));
            return;
        }

        WTable table = add(theme.table()).expandX().widget();
        for (KitLoadout loadout : bot.loadouts().all()) {
            table.add(theme.label(loadout.summary())).expandX();

            WButton edit = table.add(theme.button("Edit")).widget();
            edit.action = () -> KitLoadoutScreen.open(theme, loadout.name());

            WButton delete = table.add(theme.button("Delete")).widget();
            delete.action = () -> {
                bot.deleteLoadout(loadout.name());
                reload();
            };
            table.row();
        }

        var addRow = add(theme.horizontalList()).expandX().widget();
        var newKit = addRow.add(theme.textBox("", "new kit name")).expandX().widget();
        WButton create = addRow.add(theme.button("New Kit")).widget();
        create.action = () -> {
            String name = newKit.get().trim();
            if (!name.isEmpty()) {
                bot.createLoadout(name);
                reload();
            }
        };
    }

    private void homeSection() {
        add(theme.section("Homes")).expandX().widget();
        SixbtHomeManager homes = Modules.get().get(SixbtHomeManager.class);
        if (homes == null) {
            add(theme.label("The home manager module is not loaded."));
            return;
        }

        add(theme.label("Status: " + homes.statusLine()));
        for (String line : homes.describe()) add(theme.label(line));

        var actions = add(theme.horizontalList()).expandX().widget();

        WButton set = actions.add(theme.button("Set Here")).expandX().widget();
        set.action = () -> {
            homes.setHome("");
            reload();
        };

        WButton settings = actions.add(theme.button("Settings")).expandX().widget();
        settings.action = () -> openModule(SixbtHomeManager.class);
    }

    private void tpaSection() {
        add(theme.section("Teleport Requests")).expandX().widget();
        SixbtTpaManager tpa = Modules.get().get(SixbtTpaManager.class);
        if (tpa == null) {
            add(theme.label("The tpa manager module is not loaded."));
            return;
        }
        add(theme.label("Status: " + tpa.statusLine()));

        var actions = add(theme.horizontalList()).expandX().widget();
        WButton cancel = actions.add(theme.button("Cancel Request")).expandX().widget();
        cancel.action = () -> {
            tpa.cancel();
            reload();
        };

        WButton settings = actions.add(theme.button("Settings")).expandX().widget();
        settings.action = () -> openModule(SixbtTpaManager.class);
    }

    private void tpsSection() {
        add(theme.section("Server TPS")).expandX().widget();
        SixbtTpsMonitor tps = Modules.get().get(SixbtTpsMonitor.class);
        if (tps == null) {
            add(theme.label("The TPS monitor module is not loaded."));
            return;
        }
        add(theme.label("Status: " + tps.statusLine()));

        var actions = add(theme.horizontalList()).expandX().widget();
        WButton poll = actions.add(theme.button("Poll Now")).expandX().widget();
        poll.action = () -> {
            if (mc.player != null && mc.player.networkHandler != null) {
                mc.player.networkHandler.sendChatMessage("/tps");
            }
            reload();
        };

        WButton settings = actions.add(theme.button("Settings")).expandX().widget();
        settings.action = () -> openModule(SixbtTpsMonitor.class);
    }

    private void hotspotSection() {
        add(theme.section("Hotspot")).expandX().widget();
        SixbtHotspotTracker hotspot = Modules.get().get(SixbtHotspotTracker.class);
        if (hotspot == null) {
            add(theme.label("The hotspot tracker module is not loaded."));
            return;
        }
        add(theme.label("Status: " + hotspot.statusLine()));

        var actions = add(theme.horizontalList()).expandX().widget();

        WButton join = actions.add(theme.button("Join")).expandX().widget();
        join.action = () -> {
            hotspot.join();
            reload();
        };

        WButton info = actions.add(theme.button("Info")).expandX().widget();
        info.action = () -> {
            hotspot.requestInfo();
            reload();
        };

        WButton settings = actions.add(theme.button("Settings")).expandX().widget();
        settings.action = () -> openModule(SixbtHotspotTracker.class);
    }

    private void playerSection() {
        add(theme.section("Players")).expandX().widget();
        SixbtPlayerTracker tracker = Modules.get().get(SixbtPlayerTracker.class);
        if (tracker == null) {
            add(theme.label("The player tracker module is not loaded."));
            return;
        }
        add(theme.label("Status: " + tracker.statusLine()));

        SixbtPlayerStore store = SixbtPlayerStore.get();
        if (store != null) {
            List<SixbtPlayerStore.Record> records = store.forServer(ServerUtils.serverAddress());
            WTable table = add(theme.table()).expandX().widget();
            int shown = 0;
            for (SixbtPlayerStore.Record record : records) {
                if (shown++ >= 12) break;
                table.add(theme.label(record.name)).expandX();
                table.add(theme.label(record.coords())).expandX();
                table.add(theme.label(record.sightings + "x")).widget();
                table.row();
            }
            if (records.isEmpty()) add(theme.label("(no players recorded yet)"));
        }
    }

    private void commandSection() {
        add(theme.section("6b6t Commands")).expandX().widget();

        WTable table = add(theme.table()).expandX().widget();
        for (SixbtCommand.Entry entry : SixbtCommand.all()) {
            table.add(theme.label("/" + entry.name())).expandX();
            table.add(theme.label(entry.summary())).expandX();
            table.row();
        }
    }

    private void openModule(Class<? extends Module> type) {
        Module module = Modules.get().get(type);
        if (module == null) return;

        taskAfterRender = () -> {
            meteordevelopment.meteorclient.gui.WidgetScreen screen = meteordevelopment.meteorclient.gui.GuiThemes.get()
                .moduleScreen(module);
            screen.parent = null;
            Utils.screenToOpen = screen;
        };
    }
}
