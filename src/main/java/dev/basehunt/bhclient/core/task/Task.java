package dev.basehunt.bhclient.core.task;

import dev.basehunt.bhclient.core.Priority;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One unit of automation work, with the bookkeeping the scheduler, the GUI and the terminal all read.
 *
 * <p>A task carries what it is, where it stands and what went wrong, and it can be cancelled at any point.
 * The task itself is pure data: it holds no client references and does no work. The runtime (an
 * {@code AutomationTask} implementation in the systems layer) advances it, which is what keeps the queue
 * ordering, the priority rules and the cancellation semantics testable without a Minecraft client.
 *
 * <p>Progress is expressed as a requested/attained quantity plus a free-form step label, so a withdrawal of
 * 64 items can report "32/64" and a sort can report "move 3 of 7" without the queue needing to know what
 * kind of work it is.
 */
public final class Task {
    /** What family of work a task belongs to, used for grouping and for the terminal listing. */
    public enum Category {
        WITHDRAW,
        DEPOSIT,
        RESTOCK,
        SORT,
        SCAN,
        NAVIGATE,
        KIT,
        COMMAND,
        CUSTOM;

        public String display() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private long id;
    private final String name;
    private final String description;
    private final Category category;
    private final Priority priority;
    private final long createdAt;
    private final Map<String, String> metadata = new LinkedHashMap<>();

    private TaskState state = TaskState.IDLE;
    private String step = "";
    private String error = "";
    private int requested;
    private int attained;
    private long startedAt;
    private long finishedAt;
    private int attempts;
    private int maxAttempts = 1;

    public Task(long id, String name, String description, Category category, Priority priority) {
        this.id = id;
        this.name = name == null ? "task" : name;
        this.description = description == null ? "" : description;
        this.category = category == null ? Category.CUSTOM : category;
        this.priority = priority == null ? Priority.INVENTORY_MANAGEMENT : priority;
        this.createdAt = System.currentTimeMillis();
    }

    // Identity

    public long id() {
        return id;
    }

    /** Assigns the task's id. Called by the queue when it takes ownership of the task. */
    public Task id(long id) {
        this.id = id;
        return this;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public Category category() {
        return category;
    }

    public Priority priority() {
        return priority;
    }

    public long createdAt() {
        return createdAt;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public Task meta(String key, String value) {
        if (key != null && value != null) metadata.put(key, value);
        return this;
    }

    public String meta(String key) {
        return metadata.getOrDefault(key, "");
    }

    // Progress

    /** How many units the task aims for. 0 means "not quantity based". */
    public int requested() {
        return requested;
    }

    public int attained() {
        return attained;
    }

    public Task request(int amount) {
        this.requested = Math.max(0, amount);
        return this;
    }

    /** Records that {@code amount} units were actually obtained; never exceeds the request. */
    public void advance(int amount) {
        attained = Math.min(requested <= 0 ? attained + Math.max(0, amount) : requested,
            attained + Math.max(0, amount));
    }

    public boolean satisfied() {
        return requested <= 0 || attained >= requested;
    }

    public String progressText() {
        if (requested <= 0) return state.display();
        return "%d/%d".formatted(attained, requested);
    }

    public String step() {
        return step;
    }

    public Task step(String value) {
        this.step = value == null ? "" : value;
        return this;
    }

    // State

    public TaskState state() {
        return state;
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }

    public boolean isCancelled() {
        return state == TaskState.CANCELLED;
    }

    public String error() {
        return error;
    }

    public long startedAt() {
        return startedAt;
    }

    public long finishedAt() {
        return finishedAt;
    }

    public int attempts() {
        return attempts;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Task maxAttempts(int value) {
        this.maxAttempts = Math.max(1, value);
        return this;
    }

    /**
     * Moves the task to a new state. Terminal states stamp the finish time and are final: once a task has
     * completed, failed, partially completed or been cancelled, a late state write is ignored rather than
     * resurrecting it.
     */
    public void setState(TaskState next) {
        if (next == null || state.isTerminal()) return;
        this.state = next;
        if (next == TaskState.RUNNING && startedAt == 0) startedAt = System.currentTimeMillis();
        if (next.isTerminal()) finishedAt = System.currentTimeMillis();
    }

    /** Starts the task. */
    public void start() {
        if (state == TaskState.IDLE || state == TaskState.QUEUED) {
            attempts++;
            setState(TaskState.RUNNING);
        }
    }

    public void fail(String reason) {
        if (state.isTerminal()) return;
        this.error = reason == null ? "failed" : reason;
        setState(TaskState.FAILED);
    }

    /**
     * Returns a failed task to the queue for another attempt. Unlike {@link #setState} this deliberately leaves
     * the terminal {@code FAILED} state, because a retry is the one case where a terminal task is resurrected.
     */
    public void requeue() {
        if (state != TaskState.FAILED) return;
        this.error = "";
        this.state = TaskState.QUEUED;
        this.finishedAt = 0;
    }

    public void complete() {
        if (state.isTerminal()) return;
        setState(satisfied() ? TaskState.COMPLETED : TaskState.PARTIAL);
        if (state == TaskState.PARTIAL) error = "only %s of the request was satisfied".formatted(progressText());
    }

    public void cancel() {
        if (state.isTerminal()) return;
        setState(TaskState.CANCELLED);
    }

    /** True when the task failed but still has attempts left, so the queue may retry it. */
    public boolean retryable() {
        return state == TaskState.FAILED && attempts < maxAttempts;
    }

    /** A one-line summary for the terminal, GUI and HUD. */
    public String describe() {
        StringBuilder builder = new StringBuilder("#%d %s [%s] %s".formatted(id, name, category.display(),
            state.display()));
        if (!step.isEmpty() && state.isActive()) builder.append(" (").append(step).append(')');
        if (requested > 0) builder.append(' ').append(progressText());
        if (!error.isEmpty() && state.isTerminal()) builder.append(" - ").append(error);
        return builder.toString();
    }
}
