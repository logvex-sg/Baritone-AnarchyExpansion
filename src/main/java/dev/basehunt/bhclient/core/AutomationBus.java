package dev.basehunt.bhclient.core;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * In-process publish/subscribe bus used to decouple the addon's subsystems.
 *
 * <p>Publishing is synchronous and callers are expected to publish from the client thread. A bounded
 * per-type history is kept so the GUI, the status command and the webhook layer can report recent activity
 * without each having to subscribe for themselves. The history is bounded so a long session cannot grow
 * without limit.
 */
public class AutomationBus {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int HISTORY_LIMIT = 256;

    private final Map<AutomationEvent.Type, List<Consumer<AutomationEvent>>> subscribers = new EnumMap<>(AutomationEvent.Type.class);
    private final List<Consumer<AutomationEvent>> globalSubscribers = new ArrayList<>();
    private final Deque<AutomationEvent> history = new ArrayDeque<>();
    private boolean reportingFailure;

    /** Subscribes to one event type. */
    public synchronized void subscribe(AutomationEvent.Type type, Consumer<AutomationEvent> listener) {
        subscribers.computeIfAbsent(type, k -> new ArrayList<>()).add(listener);
    }

    /** Subscribes to every event, for logging and diagnostics. */
    public synchronized void subscribeAll(Consumer<AutomationEvent> listener) {
        globalSubscribers.add(listener);
    }

    public synchronized void unsubscribe(AutomationEvent.Type type, Consumer<AutomationEvent> listener) {
        List<Consumer<AutomationEvent>> list = subscribers.get(type);
        if (list != null) list.remove(listener);
    }

    /** Removes a listener registered with {@link #subscribeAll}. */
    public synchronized void unsubscribeAll(Consumer<AutomationEvent> listener) {
        globalSubscribers.remove(listener);
    }

    /** Publishes an event to all matching subscribers and appends it to the bounded history. */
    public void publish(AutomationEvent event) {
        if (event == null) return;

        record(event);

        List<Consumer<AutomationEvent>> typed;
        List<Consumer<AutomationEvent>> global;

        synchronized (this) {
            List<Consumer<AutomationEvent>> list = subscribers.get(event.type());
            typed = list == null ? List.of() : new ArrayList<>(list);
            global = new ArrayList<>(globalSubscribers);
        }

        for (Consumer<AutomationEvent> listener : typed) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                reportListenerFailure(event, e);
            }
        }

        for (Consumer<AutomationEvent> listener : global) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                reportListenerFailure(event, e);
            }
        }
    }

    /**
     * Turns a listener crash into a {@code SYSTEM_ERROR} event instead of letting it escape into the tick
     * loop. A module whose listener throws is a real fault the user should see, but it must not take the
     * client down, and the failure event must never recurse if a listener on {@code SYSTEM_ERROR} throws too.
     */
    private void reportListenerFailure(AutomationEvent event, Exception e) {
        LOG.error("Automation listener failed for {}", event.type(), e);

        if (event.type() == AutomationEvent.Type.SYSTEM_ERROR || reportingFailure) return;

        reportingFailure = true;
        try {
            publish(AutomationEvent.of(AutomationEvent.Type.SYSTEM_ERROR, "automation-bus",
                Map.of("source", event.source(), "event", event.type().name(), "error", String.valueOf(e))));
        } finally {
            reportingFailure = false;
        }
    }

    private synchronized void record(AutomationEvent event) {
        history.addLast(event);
        while (history.size() > HISTORY_LIMIT) history.removeFirst();
    }

    /** Most recent events, newest first. */
    public synchronized List<AutomationEvent> recent(int limit) {
        List<AutomationEvent> result = new ArrayList<>(Math.min(limit, history.size()));
        var it = history.descendingIterator();
        while (it.hasNext() && result.size() < limit) result.add(it.next());
        return result;
    }

    /** Most recent events of a single type, newest first. */
    public synchronized List<AutomationEvent> recent(AutomationEvent.Type type, int limit) {
        List<AutomationEvent> result = new ArrayList<>();
        var it = history.descendingIterator();
        while (it.hasNext() && result.size() < limit) {
            AutomationEvent event = it.next();
            if (event.type() == type) result.add(event);
        }
        return result;
    }

    public synchronized int historySize() {
        return history.size();
    }

    public synchronized void clear() {
        history.clear();
    }
}
