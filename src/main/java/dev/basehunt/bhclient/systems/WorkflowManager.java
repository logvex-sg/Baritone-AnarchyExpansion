package dev.basehunt.bhclient.systems;

import dev.basehunt.bhclient.core.logic.RecordCodec;
import dev.basehunt.bhclient.core.logic.WorkflowEngine;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists the user's storage workflows.
 *
 * <p>The engine that runs a workflow is pure and lives in {@code core.logic}; this store only keeps the
 * definitions on disk so a hand-built "deposit then restock then report" sequence survives a restart. The
 * built-in workflows are always present, and a user workflow with the same name overrides one.
 *
 * <p>Reading is defensive through {@link RecordCodec}, so a malformed step cannot stop the addon loading.
 */
public class WorkflowManager extends System<WorkflowManager> {
    private static final int DB_VERSION = 1;

    private final List<WorkflowEngine.Workflow> workflows = new ArrayList<>();
    private int lastSkippedRecords;
    private int lastLoadedVersion = DB_VERSION;

    public WorkflowManager() {
        super("base-hunting-workflows");
    }

    public static WorkflowManager get() {
        return Systems.get(WorkflowManager.class);
    }

    /** Every workflow, built-ins first, with any user override applied by name. */
    public List<WorkflowEngine.Workflow> all() {
        List<WorkflowEngine.Workflow> result = new ArrayList<>(WorkflowEngine.builtins());
        for (WorkflowEngine.Workflow custom : workflows) {
            result.removeIf(existing -> existing.name().equalsIgnoreCase(custom.name()));
            result.add(custom);
        }
        return result;
    }

    public WorkflowEngine.Workflow find(String name) {
        if (name == null) return null;
        for (WorkflowEngine.Workflow workflow : all()) {
            if (workflow.name().equalsIgnoreCase(name.trim())) return workflow;
        }
        return null;
    }

    /** Adds or replaces a user workflow. */
    public void put(WorkflowEngine.Workflow workflow) {
        if (workflow == null) return;
        workflows.removeIf(existing -> existing.name().equalsIgnoreCase(workflow.name()));
        workflows.add(workflow);
        save();
    }

    public boolean remove(String name) {
        boolean removed = workflows.removeIf(workflow -> workflow.name().equalsIgnoreCase(name));
        if (removed) save();
        return removed;
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.putInt("version", DB_VERSION);

        NbtList list = new NbtList();
        for (WorkflowEngine.Workflow workflow : workflows) {
            NbtCompound entry = new NbtCompound();
            entry.putString("name", workflow.name());
            entry.putBoolean("repeat", workflow.repeat());

            NbtList steps = new NbtList();
            for (WorkflowEngine.Step step : workflow.steps()) {
                NbtCompound stepTag = new NbtCompound();
                stepTag.putString("kind", step.kind().name());
                stepTag.putString("payload", step.payload());
                stepTag.putInt("ticks", step.ticks());
                steps.add(stepTag);
            }
            entry.put("steps", steps);
            list.add(entry);
        }

        tag.put("workflows", list);
        return tag;
    }

    @Override
    public WorkflowManager fromTag(NbtCompound tag) {
        workflows.clear();

        var result = RecordCodec.decodeList(tag, "workflows", this::readWorkflow);
        workflows.addAll(result.records());

        lastSkippedRecords = result.skipped();
        lastLoadedVersion = RecordCodec.readVersion(tag, DB_VERSION);

        return this;
    }

    private WorkflowEngine.Workflow readWorkflow(NbtCompound tag) {
        String name = tag.getString("name", "");
        if (name.isEmpty()) return null;

        List<WorkflowEngine.Step> steps = new ArrayList<>();
        for (var element : tag.getListOrEmpty("steps")) {
            if (!(element instanceof NbtCompound stepTag)) continue;
            String kindName = stepTag.getString("kind", "");
            WorkflowEngine.Kind kind;
            try {
                kind = WorkflowEngine.Kind.valueOf(kindName);
            } catch (IllegalArgumentException e) {
                // A step kind from a newer build is skipped rather than failing the whole workflow.
                continue;
            }
            steps.add(new WorkflowEngine.Step(kind, stepTag.getString("payload", ""), stepTag.getInt("ticks", 0)));
        }

        if (steps.isEmpty()) return null;
        return new WorkflowEngine.Workflow(name, steps, tag.getBoolean("repeat", false));
    }

    public int lastSkippedRecords() {
        return lastSkippedRecords;
    }

    public int lastLoadedVersion() {
        return lastLoadedVersion;
    }
}
