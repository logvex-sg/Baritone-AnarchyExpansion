package dev.basehunt.bhclient.core.task;

/**
 * The thing that actually performs a {@link Task}'s work.
 *
 * <p>The queue decides <em>when</em> a task runs; a runner does the work and reports back. Keeping the two
 * apart means the scheduling rules (one at a time, priority, retries, cancellation) are pure and testable,
 * while the runner is the only place that touches the client. A runner is given the task on start, ticked
 * while it is active, and told to clean up when it stops.
 *
 * <p>A runner must never block: each {@link #tick} does one tick's worth of work and returns. It reports its
 * outcome by writing to the task's state and progress, not by returning a value, so a partial result can be
 * expressed as {@link TaskState#PARTIAL} with the attained amount set.
 */
public interface TaskRunner {
    /** Called once when the task becomes active. */
    void start(Task task);

    /** Called once per client tick while the task is active. */
    void tick(Task task);

    /**
     * Called once when the task leaves the active slot, whether it finished or was cancelled. Must release
     * anything the runner holds (leases, navigation, queued clicks) so nothing leaks between tasks.
     */
    void stop(Task task);
}
