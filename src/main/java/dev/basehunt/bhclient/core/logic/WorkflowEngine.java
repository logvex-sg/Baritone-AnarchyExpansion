package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs a named sequence of storage steps, one at a time.
 *
 * <p>A workflow is the "do all of this" button: organise the inventory, deposit what is not needed, then
 * restock before a trip. The engine is what makes that repeatable and safe - it never overlaps two steps,
 * it stops the whole run on the first failure rather than ploughing on with a broken inventory, and it can
 * be stopped at any point.
 *
 * <p>The engine itself is pure: a step is reported finished by the runtime once its {@link OperationState}
 * completes, and the engine just advances the cursor. That keeps the sequencing logic testable without a
 * client.
 */
public final class WorkflowEngine {

    /** The kind of work a step performs. The runtime maps these onto real operations. */
    public enum Kind {
        SORT_INVENTORY,
        ORGANIZE_HOTBAR,
        DEPOSIT,
        WITHDRAW,
        RESTOCK,
        SORT_CONTAINER,
        FILL_SHULKER,
        EMPTY_SHULKER,
        SORT_SHULKER,
        CLOSE_CONTAINER,
        WAIT,
        REPORT;

        public boolean isAction() {
            return this != WAIT && this != REPORT;
        }
    }

    /**
     * One step.
     *
     * @param kind    what to do
     * @param payload a free-form argument (a preset name, a shulker label, a container label, a duration)
     * @param ticks   for {@link Kind#WAIT}, how many ticks to wait
     */
    public record Step(Kind kind, String payload, int ticks) {
        public static Step of(Kind kind) {
            return new Step(kind, "", 0);
        }

        public static Step of(Kind kind, String payload) {
            return new Step(kind, payload, 0);
        }

        public static Step wait(int ticks) {
            return new Step(Kind.WAIT, "", Math.max(1, ticks));
        }

        public String describe() {
            return kind.name().toLowerCase(Locale.ROOT) + (payload.isEmpty() ? "" : " " + payload);
        }
    }

    /** A named sequence of steps. */
    public record Workflow(String name, List<Step> steps, boolean repeat) {
    }

    public enum Status {
        IDLE,
        RUNNING,
        DONE,
        FAILED
    }

    private final Map<String, Workflow> workflows = new LinkedHashMap<>();

    private Workflow active;
    private int stepIndex;
    private Status status = Status.IDLE;
    private String failure;
    private int waitRemaining;

    /** Registers or replaces a workflow. */
    public void put(Workflow workflow) {
        if (workflow == null) return;
        workflows.put(workflow.name().toLowerCase(Locale.ROOT), workflow);
    }

    public List<Workflow> workflows() {
        return new ArrayList<>(workflows.values());
    }

    public Workflow workflow(String name) {
        if (name == null) return null;
        return workflows.get(name.trim().toLowerCase(Locale.ROOT));
    }

    public boolean remove(String name) {
        return name != null && workflows.remove(name.trim().toLowerCase(Locale.ROOT)) != null;
    }

    public Status status() {
        return status;
    }

    public boolean isRunning() {
        return status == Status.RUNNING;
    }

    public String failure() {
        return failure;
    }

    public Workflow activeWorkflow() {
        return active;
    }

    /** The step to execute now, or null when the run is idle, finished or waiting. */
    public Step currentStep() {
        if (status != Status.RUNNING || active == null) return null;
        if (stepIndex >= active.steps().size()) return null;
        return active.steps().get(stepIndex);
    }

    public int stepIndex() {
        return stepIndex;
    }

    public int stepCount() {
        return active == null ? 0 : active.steps().size();
    }

    /** Starts a workflow by name. */
    public boolean start(String name) {
        Workflow workflow = workflow(name);
        if (workflow == null || workflow.steps().isEmpty()) return false;

        active = workflow;
        stepIndex = 0;
        status = Status.RUNNING;
        failure = null;
        waitRemaining = 0;
        return true;
    }

    /**
     * Reports the current step finished.
     *
     * @param success false when the step failed; the run stops rather than continuing with a broken state
     */
    public void completeStep(boolean success) {
        if (status != Status.RUNNING) return;

        if (!success) {
            status = Status.FAILED;
            failure = "step '%s' failed".formatted(active.steps().get(stepIndex).describe());
            return;
        }

        advance();
    }

    /**
     * Advances a WAIT step. Call once per tick while the current step is a wait.
     *
     * @return true when the wait finished and the run advanced
     */
    public boolean tickWait() {
        if (status != Status.RUNNING) return false;
        Step step = currentStep();
        if (step == null || step.kind() != Kind.WAIT) return false;

        if (waitRemaining == 0) waitRemaining = step.ticks();
        waitRemaining--;

        if (waitRemaining <= 0) {
            advance();
            return true;
        }
        return false;
    }

    private void advance() {
        stepIndex++;
        waitRemaining = 0;

        if (stepIndex < active.steps().size()) return;

        if (active.repeat()) {
            stepIndex = 0;
            return;
        }

        status = Status.DONE;
    }

    /** Stops the run. */
    public void stop() {
        if (status == Status.RUNNING) status = Status.IDLE;
    }

    /** Clears the active run and returns to idle. */
    public void reset() {
        active = null;
        stepIndex = 0;
        status = Status.IDLE;
        failure = null;
        waitRemaining = 0;
    }

    public String describe() {
        if (active == null) return "idle";
        return switch (status) {
            case IDLE -> "idle";
            case RUNNING -> "%s: step %d/%d (%s)".formatted(active.name(), stepIndex + 1,
                active.steps().size(), currentStep() == null ? "..." : currentStep().describe());
            case DONE -> "%s: done".formatted(active.name());
            case FAILED -> "%s: failed - %s".formatted(active.name(), failure);
        };
    }

    /** The workflows that ship with the addon. */
    public static List<Workflow> builtins() {
        return List.of(
            new Workflow("quick deposit", List.of(
                Step.of(Kind.SORT_INVENTORY),
                Step.of(Kind.DEPOSIT),
                Step.of(Kind.REPORT)
            ), false),
            new Workflow("full cleanup", List.of(
                Step.of(Kind.SORT_INVENTORY),
                Step.of(Kind.DEPOSIT),
                Step.of(Kind.RESTOCK),
                Step.of(Kind.REPORT)
            ), false),
            new Workflow("pre-flight", List.of(
                Step.of(Kind.ORGANIZE_HOTBAR),
                Step.of(Kind.RESTOCK, "travel"),
                Step.of(Kind.SORT_INVENTORY),
                Step.of(Kind.REPORT)
            ), false),
            new Workflow("storage maintenance", List.of(
                Step.of(Kind.SORT_CONTAINER),
                Step.of(Kind.SORT_SHULKER),
                Step.of(Kind.REPORT)
            ), false)
        );
    }
}
