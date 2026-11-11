package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.core.AutomationEvent;
import dev.basehunt.bhclient.core.AutomationManager;
import dev.basehunt.bhclient.core.task.Task;
import dev.basehunt.bhclient.core.task.TaskQueue;
import dev.basehunt.bhclient.core.task.TaskRunner;
import dev.basehunt.bhclient.core.task.TaskState;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The single runtime that advances the {@link TaskQueue}.
 *
 * <p>It owns the one rule the whole automation system rests on: exactly one task is active at a time, and it
 * is driven one tick's worth per client tick on the client thread. A task that navigates, opens a container,
 * transfers and verifies is a sequence of states this engine walks, and no other task can interleave with it,
 * so two storage operations can never click slots at the same time.
 *
 * <p>The engine is not a Meteor {@code System}: it holds only transient runtime state and is installed by the
 * addon entrypoint, which also subscribes it to the event bus. On disconnect it cancels everything rather
 * than leaving a task pointing at a world that no longer exists.
 */
public final class AutomationEngine {
    private static AutomationEngine instance;

    private final TaskQueue queue = new TaskQueue();
    private final Map<Long, TaskRunner> runners = new HashMap<>();
    private final Map<String, Supplier<TaskRunner>> factories = new HashMap<>();
    private long tick;
    private Task activeRunnerTask;

    public static AutomationEngine get() {
        return instance;
    }

    /** Called by the addon entrypoint. Safe to call twice. */
    public static AutomationEngine install() {
        if (instance == null) {
            instance = new AutomationEngine();
            meteordevelopment.meteorclient.MeteorClient.EVENT_BUS.subscribe(instance);
        }
        return instance;
    }

    public TaskQueue queue() {
        return queue;
    }

    public long currentTick() {
        return tick;
    }

    /**
     * Registers how to build the runner for a task category. A category with no factory is refused at submit
     * time rather than failing silently once it is at the head of the queue.
     */
    public void registerFactory(Task.Category category, Supplier<TaskRunner> factory) {
        if (category != null && factory != null) factories.put(category.name(), factory);
    }

    /**
     * Submits a task and returns it, or null when a duplicate is already active or no runner is registered for
     * its category. The duplicate check is what stops a rule that fires every tick from queuing the same task
     * over and over.
     */
    public Task submit(Task task) {
        if (task == null) return null;
        if (!factories.containsKey(task.category().name())) return null;
        if (queue.hasActiveLike(task.name(), task.category())) return null;

        queue.submit(task);
        publish(AutomationEvent.Type.TASK_QUEUED, task);
        return task;
    }

    /** Creates and submits a task in one step. The queue assigns the id from its single counter. */
    public Task submit(String name, String description, Task.Category category,
                       dev.basehunt.bhclient.core.Priority priority) {
        Task task = new Task(0, name, description, category, priority);
        return submit(task);
    }

    public boolean cancel(long id) {
        boolean cancelled = queue.cancel(id);
        if (cancelled) publish(AutomationEvent.Type.TASK_CANCELLED, queue.byId(id));
        return cancelled;
    }

    public int cancelAll(String reason) {
        int count = queue.cancelAll(reason);
        if (activeRunnerTask != null) {
            TaskRunner runner = runners.remove(activeRunnerTask.id());
            if (runner != null) runner.stop(activeRunnerTask);
            activeRunnerTask = null;
        }
        if (count > 0) {
            AutomationManager manager = AutomationManager.get();
            if (manager != null) {
                manager.publish(AutomationEvent.of(AutomationEvent.Type.TASK_CANCELLED, "automation-engine",
                    Map.of("count", String.valueOf(count), "reason", reason == null ? "" : reason)));
            }
        }
        return count;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        tick++;
        queue.tick(tick);

        Task active = queue.active();

        if (active == null) {
            active = queue.poll();
            if (active == null) return;

            TaskRunner runner = buildRunner(active);
            if (runner == null) {
                active.fail("no runner registered for " + active.category().display());
                queue.finishActive();
                publish(AutomationEvent.Type.TASK_FAILED, active);
                return;
            }

            runners.put(active.id(), runner);
            activeRunnerTask = active;
            publish(AutomationEvent.Type.TASK_STARTED, active);

            try {
                runner.start(active);
            } catch (RuntimeException e) {
                fail(active, "runner failed to start: " + e.getMessage());
            }
            return;
        }

        TaskRunner runner = runners.get(active.id());
        if (runner == null) {
            // The runner vanished (a reload, a cancelled task). Retire the task rather than ticking nothing.
            active.fail("the task runner was lost");
            queue.finishActive();
            publish(AutomationEvent.Type.TASK_FAILED, active);
            return;
        }

        try {
            runner.tick(active);
        } catch (RuntimeException e) {
            fail(active, "runner error: " + e.getMessage());
            return;
        }

        if (active.isTerminal()) {
            runner.stop(active);
            runners.remove(active.id());
            activeRunnerTask = null;
            queue.finishActive();
            publish(terminalEvent(active), active);
            return;
        }

        // A non-terminal task that the runner has stopped touching is still owned by the runner; the queue's
        // settle step only re-queues a failed task that has retries left.
        Task settled = queue.settleActive();
        if (settled == null && queue.active() == null && activeRunnerTask == active) {
            // settleActive retired a retryable failure; make sure the runner is released.
            runner.stop(active);
            runners.remove(active.id());
            activeRunnerTask = null;
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        cancelAll("disconnected");
        NavigationManager navigation = NavigationManager.get();
        if (navigation != null) navigation.cancel("disconnected");
    }

    private TaskRunner buildRunner(Task task) {
        Supplier<TaskRunner> factory = factories.get(task.category().name());
        return factory == null ? null : factory.get();
    }

    private void fail(Task task, String reason) {
        task.fail(reason);
        TaskRunner runner = runners.remove(task.id());
        if (runner != null) runner.stop(task);
        activeRunnerTask = null;
        queue.finishActive();
        publish(AutomationEvent.Type.TASK_FAILED, task);
    }

    private AutomationEvent.Type terminalEvent(Task task) {
        return switch (task.state()) {
            case COMPLETED -> AutomationEvent.Type.TASK_COMPLETED;
            case PARTIAL -> AutomationEvent.Type.TASK_PARTIAL;
            case CANCELLED -> AutomationEvent.Type.TASK_CANCELLED;
            default -> AutomationEvent.Type.TASK_FAILED;
        };
    }

    private void publish(AutomationEvent.Type type, Task task) {
        AutomationManager manager = AutomationManager.get();
        if (manager == null || task == null) return;

        Map<String, String> data = new HashMap<>();
        data.put("id", String.valueOf(task.id()));
        data.put("name", task.name());
        data.put("category", task.category().display());
        data.put("state", task.state().name());
        if (!task.error().isEmpty()) data.put("error", task.error());
        if (task.requested() > 0) {
            data.put("attained", String.valueOf(task.attained()));
            data.put("requested", String.valueOf(task.requested()));
        }
        manager.publish(AutomationEvent.of(type, task.name(), data));
    }

    /** A one-line summary for the HUD and terminal. */
    public String statusLine() {
        Task active = queue.active();
        if (active == null) return queue.queueSize() == 0 ? "idle" : queue.queueSize() + " queued";
        return active.describe();
    }

    /** True when the active task is in one of the given states. */
    public boolean activeIn(TaskState... states) {
        Task active = queue.active();
        if (active == null) return false;
        for (TaskState state : states) if (active.state() == state) return true;
        return false;
    }
}
