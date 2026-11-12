package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.sixbt.HomeBook;
import dev.basehunt.bhclient.systems.SixbtServerStore;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import java.util.List;
import java.util.Map;

/**
 * Manages 6b6t homes and warns before the server's home limit bites.
 *
 * <p>6b6t gives every player a handful of homes ({@code /sethome <name>}, {@code /home <name>}), with more for
 * ranked and voting players; AnarchyMod adds two more. The client cannot read the server's list, so this
 * module keeps its own book of the homes it created, which is enough to warn before a {@code /sethome} fails
 * and to suggest a name when the player does not give one.
 *
 * <p>It also watches for a home being replaced: 6b6t asks before overwriting an existing home, so a
 * {@code /sethome} on a name already in the book is reported rather than silently assumed.
 */
public class SixbtHomeManager extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> homeLimit = sgGeneral.add(new IntSetting.Builder()
        .name("home-limit")
        .description("How many homes your rank allows on 6b6t (raise it after voting or ranking up).")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Boolean> warnWhenFull = sgGeneral.add(new BoolSetting.Builder()
        .name("warn-when-full")
        .description("Warn in chat when a new /sethome would exceed the limit.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> trackFromChat = sgGeneral.add(new BoolSetting.Builder()
        .name("track-from-chat")
        .description("Notice homes the server confirms in chat, even ones set without the addon.")
        .defaultValue(true)
        .build()
    );

    private HomeBook book = new HomeBook();

    public SixbtHomeManager() {
        super(BaseHuntingAddon.CATEGORY, "6b6t-homes",
            "Tracks 6b6t homes and warns before the server's home limit is reached.");
    }

    @Override
    public void onActivate() {
        reload();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        reload();
    }

    /** Rebuilds the home book from the saved store for the current server. */
    public void reload() {
        SixbtServerStore store = SixbtServerStore.get();
        book = store == null ? new HomeBook(homeLimit.get()) : store.homesFor(ServerUtils.serverAddress());
        book.setLimit(homeLimit.get());
    }

    public HomeBook book() {
        return book;
    }

    /** Sets a home at the current position: sends {@code /sethome <name>} and records it. */
    public String setHome(String name) {
        String resolved = name == null || name.isBlank() ? suggestName() : name.trim();
        if (book.isFull() && !book.contains(resolved)) {
            if (warnWhenFull.get()) warning("Home limit reached (%d/%d); /sethome will be refused.",
                book.size(), book.limit());
            return "home limit reached";
        }
        if (book.contains(resolved)) {
            warning("A home named %s already exists; the server may ask before replacing it.", resolved);
        }

        if (mc.player == null || mc.player.networkHandler == null) return "not connected";
        sendLine("/sethome " + resolved);

        var pos = mc.player.getBlockPos();
        HomeBook.Home home = new HomeBook.Home(resolved, ServerUtils.dimension(),
            pos.getX(), pos.getY(), pos.getZ(), System.currentTimeMillis());
        book.put(home);

        SixbtServerStore store = SixbtServerStore.get();
        if (store != null) store.put(ServerUtils.serverAddress(), home);

        publish(AutomationEvent.Type.SIXBT_HOME_SET, Map.of("home", resolved, "coords", home.coords()));
        info("Home %s set at %s.", resolved, home.coords());
        return "set " + resolved;
    }

    /** Visits a home: sends {@code /home <name>}. */
    public String visitHome(String name) {
        if (name == null || name.isBlank()) return "usage: home <name>";
        if (!book.contains(name)) warning("No home named %s is recorded; sending /home anyway.", name);

        if (mc.player == null || mc.player.networkHandler == null) return "not connected";
        sendLine("/home " + name);
        publish(AutomationEvent.Type.SIXBT_HOME_VISITED, Map.of("home", name));
        info("Teleporting to %s.", name);
        return "visiting " + name;
    }

    /** Deletes a home: sends {@code /delhome <name>} and forgets it. */
    public String deleteHome(String name) {
        if (name == null || name.isBlank()) return "usage: delhome <name>";
        if (mc.player == null || mc.player.networkHandler == null) return "not connected";
        sendLine("/delhome " + name);

        book.remove(name);
        SixbtServerStore store = SixbtServerStore.get();
        if (store != null) store.remove(ServerUtils.serverAddress(), name);

        info("Deleted home %s.", name);
        return "deleted " + name;
    }

    /** Cancels a home teleport in progress: sends {@code /homecancel}. */
    public String cancel() {
        sendLine("/homecancel");
        return "cancelled";
    }

    /** A name for a new home that is unlikely to collide. */
    private String suggestName() {
        int index = 1;
        while (book.contains("home" + index)) index++;
        return "home" + index;
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        if (!trackFromChat.get()) return;
        String text = event.getMessage().getString();
        String lower = text.toLowerCase(java.util.Locale.ROOT);

        // "Home 'base' created" / "You set your home to base".
        if (lower.contains("home") && (lower.contains("created") || lower.contains("set"))) {
            String name = extractQuoted(text);
            if (!name.isBlank() && !book.contains(name) && mc.player != null) {
                var pos = mc.player.getBlockPos();
                HomeBook.Home home = new HomeBook.Home(name, ServerUtils.dimension(),
                    pos.getX(), pos.getY(), pos.getZ(), System.currentTimeMillis());
                book.put(home);
                SixbtServerStore store = SixbtServerStore.get();
                if (store != null) store.put(ServerUtils.serverAddress(), home);
            }
        }
    }

    /** The first 'quoted' or "quoted" word in a message. */
    private static String extractQuoted(String text) {
        for (char quote : new char[] {'\'', '"'}) {
            int open = text.indexOf(quote);
            if (open < 0) continue;
            int close = text.indexOf(quote, open + 1);
            if (close > open + 1) return text.substring(open + 1, close).trim();
        }
        return "";
    }

    private void sendLine(String line) {
        if (mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.sendChatMessage(line);
        }
    }

    public String statusLine() {
        return "%d/%d homes".formatted(book.size(), book.limit());
    }

    public List<String> describe() {
        return book.describe();
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
