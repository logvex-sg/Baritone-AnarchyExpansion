package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.sixbt.HotspotInfo;
import dev.basehunt.bhclient.utils.ServerUtils;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import java.util.List;
import java.util.Map;

/**
 * Tracks the current 6b6t Hotspot and tells you when one is live.
 *
 * <p>A Hotspot is a short-lived player-run event: someone runs {@code /hotspot create}, everyone who wants to
 * join runs {@code /hotspot} to teleport there, and it ends when it is cancelled or its time runs out. On an
 * anarchy server a Hotspot is also a magnet for fights, so the useful thing is to know one exists - and where -
 * before you decide to walk into it.
 *
 * <p>The server announces a Hotspot in chat; the module records the last announcement, notifies once when a
 * new one appears, and can teleport you to it on request. The parsing is the pure {@link HotspotInfo}.
 */
public class SixbtHotspotTracker extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> notifyOnStart = sgGeneral.add(new BoolSetting.Builder()
        .name("notify-on-start")
        .description("Print in chat when a Hotspot goes live.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoJoin = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-join")
        .description("Teleport to a Hotspot as soon as it appears. On an anarchy server this is a fight.")
        .defaultValue(false)
        .build()
    );

    private HotspotInfo.Snapshot current;

    public SixbtHotspotTracker() {
        super(BaseHuntingAddon.CATEGORY, "6b6t-hotspot",
            "Tracks the current 6b6t Hotspot and can teleport you to it.");
    }

    @Override
    public void onActivate() {
        current = null;
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        String text = event.getMessage().getString();
        HotspotInfo.parse(text, ServerUtils.dimension(), System.currentTimeMillis()).ifPresent(snapshot -> {
            boolean wasActive = current != null && current.active();
            current = snapshot;

            if (snapshot.active() && !wasActive) {
                publish(AutomationEvent.Type.SIXBT_HOTSPOT_ACTIVE,
                    Map.of("name", snapshot.name(), "coords", snapshot.coords()));
                if (notifyOnStart.get()) {
                    info("Hotspot live: %s.", snapshot.describe());
                    warning("A hotspot attracts players; expect a fight.");
                }
                if (autoJoin.get()) join();
            } else if (!snapshot.active() && wasActive) {
                publish(AutomationEvent.Type.SIXBT_HOTSPOT_ENDED, Map.of("name", snapshot.name()));
                if (notifyOnStart.get()) info("Hotspot ended.");
            }
        });
    }

    /** Teleports to the current Hotspot with {@code /hotspot}. */
    public String join() {
        if (mc.player == null || mc.player.networkHandler == null) return "not connected";
        sendLine("/hotspot");
        info("Teleporting to the hotspot.");
        return "joining hotspot";
    }

    /** Creates a Hotspot here, if the rank allows it: {@code /hotspot create}. */
    public String create() {
        sendLine("/hotspot create");
        info("Requested a hotspot at your position.");
        return "creating hotspot";
    }

    /** Cancels the Hotspot you created: {@code /hotspot cancel}. */
    public String cancel() {
        sendLine("/hotspot cancel");
        return "cancelling hotspot";
    }

    /** Asks the server for Hotspot information: {@code /hotspot info}. */
    public String requestInfo() {
        sendLine("/hotspot info");
        return "requested hotspot info";
    }

    private void sendLine(String line) {
        if (mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.sendChatMessage(line);
        }
    }

    public HotspotInfo.Snapshot current() {
        return current;
    }

    public String statusLine() {
        if (current == null) return "no hotspot seen";
        return current.active() ? current.describe() : "hotspot ended";
    }

    public List<String> describe() {
        return List.of(
            "current: " + statusLine(),
            "auto-join: " + autoJoin.get(),
            "notify: " + notifyOnStart.get()
        );
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
