package dev.basehunt.bhclient.gui;

import dev.basehunt.bhclient.core.sixbt.KitLoadout;
import dev.basehunt.bhclient.modules.KitBot;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WIntEdit;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The kit-maker loadout editor: the menu where the kit bot's loadouts are built.
 *
 * <p>Each kit is a list of {@code item xN} lines. This screen edits one kit at a time: it lists the lines with
 * a remove button each, and adds new lines either by typing an item id or by taking the item in the player's
 * hand - which is the fast path, because the kit a bot should carry is usually the one being held.
 *
 * <p>Every change goes through {@link KitBot}'s public methods and is persisted immediately, so closing the
 * screen cannot lose an edit. There is no "apply" button to forget to press.
 */
public class KitLoadoutScreen extends WindowScreen {
    private final String kitName;
    private final KitBot bot;
    private WTable itemTable;
    private WTextBox itemBox;
    private WIntEdit countEdit;

    public KitLoadoutScreen(GuiTheme theme, String kitName) {
        super(theme, "Kit: " + kitName);
        this.kitName = kitName;
        this.bot = Modules.get().get(KitBot.class);
    }

    /** Opens the editor for a kit. */
    public static void open(GuiTheme theme, String kitName) {
        KitLoadoutScreen screen = new KitLoadoutScreen(theme, kitName);
        screen.parent = null;
        Utils.screenToOpen = screen;
    }

    @Override
    public void initWidgets() {
        if (bot == null) {
            add(theme.label("The kit bot module is not loaded."));
            return;
        }

        KitLoadout loadout = bot.loadouts().find(kitName);
        add(theme.label(loadout == null ? "This kit has no loadout yet." : loadout.summary()));

        add(theme.section("Items")).expandX().widget();
        itemTable = add(theme.table()).expandX().widget();
        buildItemTable();

        add(theme.section("Add an item")).expandX().widget();

        var addRow = add(theme.horizontalList()).expandX().widget();
        itemBox = addRow.add(theme.textBox("", "item id, e.g. netherite_sword")).expandX().widget();
        countEdit = addRow.add(theme.intEdit(1, 1, 2304, 1, 64, false)).widget();

        var addActions = add(theme.horizontalList()).expandX().widget();

        WButton add = addActions.add(theme.button("Add")).expandX().widget();
        add.action = this::addTyped;

        WButton addHeld = addActions.add(theme.button("Add Held Item")).expandX().widget();
        addHeld.action = this::addHeld;

        WButton clear = addActions.add(theme.button("Clear Kit")).expandX().widget();
        clear.action = () -> {
            bot.loadouts().put(KitLoadout.empty(kitName));
            reload();
        };

        add(theme.section("Delivery check")).expandX().widget();
        var result = bot.restockFor(kitName);
        for (String line : result.describe()) add(theme.label(line)).expandX();
    }

    private void buildItemTable() {
        if (itemTable == null) return;
        itemTable.clear();

        KitLoadout loadout = bot.loadouts().find(kitName);
        if (loadout == null || loadout.items().isEmpty()) {
            itemTable.add(theme.label("(empty)")).expandX();
            itemTable.row();
            return;
        }

        for (KitLoadout.ItemSpec spec : loadout.items()) {
            itemTable.add(theme.label(spec.describe())).expandX();

            WButton remove = itemTable.add(theme.button("Remove")).widget();
            remove.action = () -> {
                bot.removeLoadoutItem(kitName, spec.itemId());
                reload();
            };
            itemTable.row();
        }
    }

    private void addTyped() {
        String item = itemBox.get().trim();
        if (item.isEmpty()) return;
        bot.addLoadoutItem(kitName, item, countEdit.get());
        itemBox.set("");
        reload();
    }

    private void addHeld() {
        if (mc.player == null) return;
        ItemStack held = mc.player.getMainHandStack();
        if (held.isEmpty()) return;
        String id = Registries.ITEM.getId(held.getItem()).toString();
        bot.addLoadoutItem(kitName, id, Math.max(1, held.getCount()));
        reload();
    }
}
