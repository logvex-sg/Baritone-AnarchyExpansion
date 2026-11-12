package dev.basehunt.bhclient.core.logic;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.List;

/**
 * The remainder of a flight route, in a form that survives a restart.
 *
 * <p>{@code BaseHunterV2} keeps its pending targets in memory only, so quitting the game, switching server or
 * crossing a portal mid-flight loses the queue. Everything the user set up then has to be rebuilt by hand.
 * This is the small, versioned snapshot that closes that gap: the target being flown to, the targets still
 * queued behind it, and the server and dimension they belong to.
 *
 * <p>It is deliberately not a second route planner. It stores only what the existing planner produced, so
 * restoring is a matter of re-queueing the same coordinates - the pattern generator, the stash ranking and the
 * frontier logic all stay the single source of truth for what to fly to next.
 *
 * <p>Only NBT is touched, so the whole class (including its tolerant decode) is exercised headlessly.
 */
public final class JourneyPlan {

    /** The coordinates are remembered as the planner emitted them; the reason is kept for the chat line. */
    public record Target(int x, int z, String reason) {
    }

    /** Upper bound on remembered targets, so a long sweep cannot grow the saved file without limit. */
    public static final int MAX_PENDING = 256;

    private String server = "";
    private String dimension = "";
    private long savedAt;
    private Target interrupted;
    private final List<Target> pending = new ArrayList<>();

    public JourneyPlan() {
    }

    public String server() {
        return server;
    }

    public String dimension() {
        return dimension;
    }

    public long savedAt() {
        return savedAt;
    }

    public void setServer(String server) {
        this.server = server == null ? "" : server;
    }

    public void setDimension(String dimension) {
        this.dimension = dimension == null ? "" : dimension;
    }

    public void touch() {
        this.savedAt = System.currentTimeMillis();
    }

    /** Records the target that was in progress when the route was cut short. */
    public void setInterrupted(int x, int z, String reason) {
        this.interrupted = new Target(x, z, reason == null || reason.isBlank() ? "saved" : reason);
    }

    public boolean hasInterrupted() {
        return interrupted != null;
    }

    public Target interrupted() {
        return interrupted;
    }

    /** Queues a target still to be flown. Silently ignored once {@link #MAX_PENDING} is reached. */
    public void addPending(int x, int z, String reason) {
        if (pending.size() >= MAX_PENDING) return;
        pending.add(new Target(x, z, reason == null || reason.isBlank() ? "saved" : reason));
    }

    public List<Target> pending() {
        return java.util.Collections.unmodifiableList(pending);
    }

    public int size() {
        return pending.size() + (interrupted == null ? 0 : 1);
    }

    /** True when there is nothing worth saving or restoring. */
    public boolean isEmpty() {
        return interrupted == null && pending.isEmpty();
    }

    /** True when the plan was saved for this exact server and dimension, so its coordinates still mean something. */
    public boolean matches(String server, String dimension) {
        return this.server.equals(server == null ? "" : server)
            && this.dimension.equals(dimension == null ? "" : dimension);
    }

    /** Age of the snapshot in milliseconds, or -1 when it was never stamped. */
    public long ageMillis() {
        return savedAt <= 0 ? -1 : Math.max(0, System.currentTimeMillis() - savedAt);
    }

    /** One-line summary for chat and the HUD. */
    public String describe() {
        if (isEmpty()) return "no saved route";
        String age = ageMillis() < 0 ? "unknown age" : humanAge(ageMillis());
        return "%d target(s) saved %s ago for %s in %s".formatted(size(), age,
            server.isBlank() ? "this server" : server, dimension.isBlank() ? "this dimension" : dimension);
    }

    private static String humanAge(long millis) {
        long minutes = millis / 60_000L;
        if (minutes < 1) return "less than a minute";
        if (minutes < 60) return minutes + " minute(s)";
        long hours = minutes / 60;
        if (hours < 24) return hours + " hour(s)";
        return (hours / 24) + " day(s)";
    }

    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.putInt("version", 1);
        tag.putString("server", server);
        tag.putString("dimension", dimension);
        tag.putLong("savedAt", savedAt);

        if (interrupted != null) {
            NbtCompound interruptedTag = new NbtCompound();
            interruptedTag.putInt("x", interrupted.x());
            interruptedTag.putInt("z", interrupted.z());
            interruptedTag.putString("reason", interrupted.reason());
            tag.put("interrupted", interruptedTag);
        }

        NbtList list = new NbtList();
        for (Target target : pending) {
            NbtCompound entry = new NbtCompound();
            entry.putInt("x", target.x());
            entry.putInt("z", target.z());
            entry.putString("reason", target.reason());
            list.add(entry);
        }
        tag.put("pending", list);

        return tag;
    }

    /**
     * Reads a plan out of a tag, tolerating anything malformed: a missing field yields an empty plan rather
     * than an exception, and one unreadable pending entry is skipped so the rest of the route still loads.
     */
    public static JourneyPlan fromTag(NbtCompound tag) {
        JourneyPlan plan = new JourneyPlan();
        if (tag == null) return plan;

        plan.server = tag.getString("server", "");
        plan.dimension = tag.getString("dimension", "");
        plan.savedAt = tag.getLong("savedAt", 0L);

        if (tag.contains("interrupted")) {
            NbtCompound interruptedTag = tag.getCompoundOrEmpty("interrupted");
            plan.interrupted = new Target(
                interruptedTag.getInt("x", 0),
                interruptedTag.getInt("z", 0),
                interruptedTag.getString("reason", "saved"));
        }

        var decoded = RecordCodec.decodeList(tag, "pending", entry -> new Target(
            entry.getInt("x", 0),
            entry.getInt("z", 0),
            entry.getString("reason", "saved")));
        for (Target target : decoded.records()) plan.addPending(target.x(), target.z(), target.reason());

        return plan;
    }
}
