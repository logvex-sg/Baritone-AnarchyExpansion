package dev.basehunt.bhclient.core.task;

import java.util.EnumSet;
import java.util.Set;

/**
 * The lifecycle a {@link Task} moves through.
 *
 * <p>The states exist so a caller can tell "it went out" apart from "it worked": a task reaches
 * {@link #COMPLETED} only once its effect was verified, never because a click was sent. {@link #PARTIAL}
 * exists because a withdrawal that could not be fully satisfied is a real outcome that must be reported
 * rather than rounded up to success.
 */
public enum TaskState {
    IDLE,
    QUEUED,
    RUNNING,
    NAVIGATING,
    WAITING,
    PROCESSING,
    VERIFYING,
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED;

    /** True for states from which a task never moves again. */
    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIAL || this == FAILED || this == CANCELLED;
    }

    /** True while the task still needs ticks. */
    public boolean isActive() {
        return !isTerminal() && this != IDLE;
    }

    public String display() {
        return switch (this) {
            case IDLE -> "idle";
            case QUEUED -> "queued";
            case RUNNING -> "running";
            case NAVIGATING -> "navigating";
            case WAITING -> "waiting";
            case PROCESSING -> "processing";
            case VERIFYING -> "verifying";
            case COMPLETED -> "completed";
            case PARTIAL -> "partially completed";
            case FAILED -> "failed";
            case CANCELLED -> "cancelled";
        };
    }

    /** The states a user-facing task list should treat as "done and not retryable". */
    public static Set<TaskState> terminalStates() {
        return EnumSet.of(COMPLETED, PARTIAL, FAILED, CANCELLED);
    }
}
