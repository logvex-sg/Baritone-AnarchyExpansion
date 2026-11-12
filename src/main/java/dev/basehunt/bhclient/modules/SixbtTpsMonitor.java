package dev.basehunt.bhclient.modules;

import dev.basehunt.bhclient.BaseHuntingAddon;
import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.sixbt.TpsMonitor;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;

import java.util.List;
import java.util.Map;

/**
 * Polls 6b6t's {@code /tps} and reports when the server (or your own connection) is lagging.
 *
 * <p>On an anarchy server, "is it lagging?" has two answers: the server's TPS and yours. 6b6t reports both,
 * and the difference matters - a healthy server with a bad personal TPS means the problem is your link, not
 * the server, and that is worth knowing before blaming a failed teleport on the server. The module polls on a
 * timer, parses whatever the server sends, and warns when the server drops below a threshold.
 *
 * <p>The parsing and the rolling average live in the pure {@link TpsMonitor}; this class is only the poll
 * timer and the chat plumbing.
 */
public class SixbtTpsMonitor extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> autoPoll = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-poll")
        .description("Ask the server for its TPS on a timer.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> pollSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("poll-seconds")
        .description("Seconds between /tps polls.")
        .defaultValue(60)
        .min(10)
        .sliderRange(10, 600)
        .visible(autoPoll::get)
        .build()
    );

    private final Setting<Double> lowThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("low-threshold")
        .description("Warn when the server TPS drops below this.")
        .defaultValue(15.0)
        .min(1.0)
        .sliderRange(1.0, 20.0)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print a low-TPS warning in chat.")
        .defaultValue(true)
        .build()
    );

    private final TpsMonitor monitor = new TpsMonitor();
    private int pollTimer;
    private boolean warnedLow;

    public SixbtTpsMonitor() {
        super(BaseHuntingAddon.CATEGORY, "6b6t-tps",
            "Polls 6b6t's /tps and warns when the server or your connection is lagging.");
    }

    @Override
    public void onActivate() {
        pollTimer = 0;
        warnedLow = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (!autoPoll.get()) return;
        if (--pollTimer > 0) return;
        pollTimer = pollSeconds.get() * 20;
        sendLine("/tps");
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        String text = event.getMessage().getString();
        TpsMonitor.parse(text, System.currentTimeMillis()).ifPresent(reading -> {
            monitor.record(reading);
            if (reading.hasServer() && reading.serverTps() < lowThreshold.get()) {
                if (!warnedLow) {
                    warnedLow = true;
                    publish(AutomationEvent.Type.SIXBT_TPS_LOW, Map.of(
                        "serverTps", "%.1f".formatted(reading.serverTps()),
                        "personalTps", "%.1f".formatted(reading.personalTps())));
                    if (chatFeedback.get()) {
                        warning("Server TPS is %.1f (%s).", reading.serverTps(), reading.health());
                    }
                }
            } else {
                warnedLow = false;
            }
        });
    }

    private void sendLine(String line) {
        if (mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.sendChatMessage(line);
        }
    }

    public TpsMonitor monitor() {
        return monitor;
    }

    public String statusLine() {
        return monitor.statusLine();
    }

    public List<String> describe() {
        List<String> lines = new java.util.ArrayList<>();
        lines.add(monitor.statusLine());
        lines.add("average server TPS: %.1f".formatted(monitor.averageServerTps()));
        lines.add("lowest server TPS: %.1f".formatted(monitor.lowestServerTps()));
        lines.add("samples: %d".formatted(monitor.size()));
        return lines;
    }

    private void publish(AutomationEvent.Type type, Map<String, String> data) {
        AutomationManager manager = AutomationManager.get();
        if (manager != null) manager.publish(AutomationEvent.of(type, name, data));
    }
}
