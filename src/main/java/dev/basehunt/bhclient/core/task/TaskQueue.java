package dev.basehunt.bhclient.core.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A priority-ordered queue of {@link Task}s that runs exactly one at a time.
 *
 * <p>Running one at a time is the whole point: two storage operations that both click slots would fight
 * over the cursor and the container, so the queue serialises them and the runtime only ever advances the
 * head. Tasks are ordered by priority first and submission order second, so a critical survival task can
 * jump ahead of a batch of background scans without a background task ever being able to jump ahead of a
 * critical one.
 *
 * <p>The queue is pure: it decides order, state transitions, retries and history, and knows nothing about
 * Minecraft. That is what lets the scheduling, cancellation and retry rules be covered by the headless suite.
 *
 * <p>Loop prevention is explicit: a task that keeps failing is retried up to its {@code maxAttempts} and
 * then parked as failed. A task that re-queues itself would otherwise be able to spin forever, so the queue
 * also refuses to accept a duplicate of a still-active task with the same name and category.
 */
public final class TaskQueue {
    private static final int HISTORY_LIMIT = 128;

    private final Map<Long, Task> pending = new LinkedHashMap<>();
    private final List<Task> history = new ArrayList<>();
    private final List<Consumer<Task>> finishedListeners = new ArrayList<>();

    private long nextId = 1;
    private Task active;
    private long activeTick;

    /** Submits a task and returns its assigned id. */
    public long submit(Task task) {
        if (task == null) return -1;
        if (task.id() <= 0) task.id(nextId++);
        pending.put(task.id(), task);
        task.setState(TaskState.QUEUED);
        return task.id();
    }

    /** Creates a task with the next id and submits it. */
    public Task create(String name, String description, Task.Category category,
                       dev.basehunt.bhclient.core.Priority priority) {
        Task task = new Task(nextId++, name, description, category, priority);
        submit(task);
        return task;
    }

    /** True when a still-active task has the same name and category, used to refuse obvious duplicates. */
    public boolean hasActiveLike(String name, Task.Category category) {
        if (active != null && active.name().equals(name) && active.category() == category) return true;
        for (Task task : pending.values()) {
            if (task.name().equals(name) && task.category() == category) return true;
        }
        return false;
    }

    public Task active() {
        return active;
    }

    public boolean isBusy() {
        return active != null;
    }

    /** Every task that has not started yet, in the order they will run. */
    public List<Task> queued() {
        List<Task> tasks = new ArrayList<>(pending.values());
        tasks.sort(ordering());
        return tasks;
    }

    public int queueSize() {
        return pending.size();
    }

    /** The task that will run next, or null. */
    public Task next() {
        Task best = null;
        for (Task task : pending.values()) {
            if (best == null || ordering().compare(task, best) < 0) best = task;
        }
        return best;
    }

    /**
     * Pulls the next task into the active slot if the queue is free.
     *
     * @return the newly active task, or null when nothing started
     */
    public Task poll() {
        if (active != null) return null;

        Task task = next();
        if (task == null) return null;

        pending.remove(task.id());
        active = task;
        task.start();
        return task;
    }

    /** The active task reached a terminal state; retire it and free the slot. */
    public void finishActive() {
        if (active == null) return;
        retire(active);
        active = null;
    }

    /**
     * Called once per tick by the runtime. If the active task has failed and can be retried, it is re-queued
     * instead of being retired, so a transient failure (a container that had not loaded yet) does not lose
     * the user's request.
     *
     * @return the task to keep running, or null when the slot is now free
     */
    public Task settleActive() {
        if (active == null) return null;

        if (active.retryable()) {
            Task retry = active;
            active = null;
            retry.requeue();
            pending.put(retry.id(), retry);
            return null;
        }

        if (active.isTerminal()) {
            finishActive();
            return null;
        }

        return active;
    }

    /** Cancels a task by id, wherever it is. Returns true when one was found. */
    public boolean cancel(long id) {
        if (active != null && active.id() == id) {
            active.cancel();
            retire(active);
            active = null;
            return true;
        }

        Task queued = pending.remove(id);
        if (queued != null) {
            queued.cancel();
            retire(queued);
            return true;
        }

        return false;
    }

    /** Cancels every task. Called on disconnect. */
    public int cancelAll(String reason) {
        int count = 0;
        if (active != null) {
            active.fail(reason == null ? "cancelled" : reason);
            if (!active.isCancelled()) active.cancel();
            retire(active);
            active = null;
            count++;
        }
        for (Task task : new ArrayList<>(pending.values())) {
            task.cancel();
            retire(task);
            count++;
        }
        pending.clear();
        return count;
    }

    public Task byId(long id) {
        if (active != null && active.id() == id) return active;
        return pending.get(id);
    }

    public long activeTick() {
        return activeTick;
    }

    /** Advances the runtime clock; used by the GUI to show how long the active task has run. */
    public void tick(long tick) {
        this.activeTick = tick;
    }

    public void addFinishedListener(Consumer<Task> listener) {
        if (listener != null) finishedListeners.add(listener);
    }

    /** Recently finished tasks, newest first. */
    public List<Task> history() {
        List<Task> copy = new ArrayList<>(history);
        java.util.Collections.reverse(copy);
        return copy;
    }

    public List<Task> history(Task.Category category, int limit) {
        List<Task> result = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && result.size() < limit; i--) {
            Task task = history.get(i);
            if (category == null || task.category() == category) result.add(task);
        }
        return result;
    }

    public void clearHistory() {
        history.clear();
    }

    private void retire(Task task) {
        history.add(task);
        while (history.size() > HISTORY_LIMIT) history.remove(0);
        for (Consumer<Task> listener : finishedListeners) {
            try {
                listener.accept(task);
            } catch (RuntimeException ignored) {
                // A listener fault must not corrupt the queue; the task is already retired.
            }
        }
    }

    private static Comparator<Task> ordering() {
        return Comparator.comparingInt((Task task) -> task.priority().weight)
            .thenComparingLong(Task::createdAt)
            .thenComparingLong(Task::id);
    }

    /** Human-readable summary for the terminal and HUD. */
    public String describe() {
        if (active == null && pending.isEmpty()) return "idle";
        if (active == null) return "%d queued".formatted(pending.size());
        return "%s | %d queued".formatted(active.describe(), pending.size());
    }
}
