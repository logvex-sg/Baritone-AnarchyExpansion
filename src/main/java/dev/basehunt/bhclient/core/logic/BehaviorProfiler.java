package dev.basehunt.bhclient.core.logic;

/**
 * Scores how human the current movement and camera look, from the raw per-tick samples.
 *
 * <p>A bot moves in a way a person does not: it turns at a near-constant rate, walks in almost perfectly
 * straight lines, and never pauses. This class watches a short rolling window of samples - yaw, pitch,
 * position, whether you are moving - and reduces them to a handful of numbers that separate that from a
 * person: how much the turn rate varies, how often you stop, and how straight the path is. The three are
 * combined into a single 0-100 "human-likeness" score for the HUD.
 *
 * <p>It is pure and bounded: fixed-size ring buffers, no Minecraft types, so the metric is pinned
 * headlessly instead of only being read off a live client.
 */
public final class BehaviorProfiler {
    /** A one-line readout of the window. */
    public record Report(double turnVariance, double pauseRatio, double straightness,
                         double humanLikeness, int samples) {
        public String describe() {
            if (samples == 0) return "no samples";
            return "%.0f%% human (turn var %.1f, pause %.0f%%, straight %.2f)"
                .formatted(humanLikeness, turnVariance, pauseRatio * 100.0, straightness);
        }
    }

    private final int capacity;
    private final double[] turnRates;
    private final boolean[] moving;
    private final double[] posX;
    private final double[] posZ;

    private int size;
    private int head;

    private double lastYaw = Double.NaN;
    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;

    public BehaviorProfiler() {
        this(100);
    }

    public BehaviorProfiler(int capacity) {
        this.capacity = Math.max(4, capacity);
        this.turnRates = new double[this.capacity];
        this.moving = new boolean[this.capacity];
        this.posX = new double[this.capacity];
        this.posZ = new double[this.capacity];
    }

    /** Forgets every sample, e.g. on respawn or a dimension change. */
    public void reset() {
        size = 0;
        head = 0;
        lastYaw = lastX = lastZ = Double.NaN;
    }

    /**
     * Feeds one tick.
     *
     * @param yaw       camera yaw in degrees
     * @param pitch     camera pitch in degrees
     * @param x         player x
     * @param z         player z
     * @param isMoving  whether the player moved this tick
     */
    public void sample(double yaw, double pitch, double x, double z, boolean isMoving) {
        if (!Double.isNaN(lastYaw)) {
            int index = head;
            turnRates[index] = Math.abs(LookSmoother.shortestDelta((float) yaw, (float) lastYaw));
            moving[index] = isMoving;
            posX[index] = x;
            posZ[index] = z;

            head = (head + 1) % capacity;
            if (size < capacity) size++;
        }

        lastYaw = yaw;
        lastX = x;
        lastZ = z;
    }

    public int samples() {
        return size;
    }

    /** The mean absolute turn rate over the window, in degrees per tick. */
    public double averageTurnRate() {
        if (size == 0) return 0.0;
        double sum = 0.0;
        for (int i = 0; i < size; i++) sum += turnRates[i];
        return sum / size;
    }

    /** The variance of the turn rate. A human is irregular; a bot is near-constant. */
    public double turnVariance() {
        if (size < 2) return 0.0;
        double mean = averageTurnRate();
        double sum = 0.0;
        for (int i = 0; i < size; i++) {
            double delta = turnRates[i] - mean;
            sum += delta * delta;
        }
        return sum / size;
    }

    /** The fraction of sampled ticks the player was not moving. */
    public double pauseRatio() {
        if (size == 0) return 0.0;
        int paused = 0;
        for (int i = 0; i < size; i++) if (!moving[i]) paused++;
        return (double) paused / size;
    }

    /**
     * How straight the path is: the straight-line distance between the ends of the window divided by the
     * distance actually walked, in 0..1. 1 is a perfectly straight line (a bot); lower means it wandered.
     */
    public double straightness() {
        if (size < 2) return 0.0;

        int oldest = size == capacity ? head : 0;
        int newest = (head - 1 + capacity) % capacity;

        double dx = posX[newest] - posX[oldest];
        double dz = posZ[newest] - posZ[oldest];
        double net = Math.hypot(dx, dz);

        double total = 0.0;
        int previous = oldest;
        for (int step = 1; step < size; step++) {
            int index = (oldest + step) % capacity;
            total += Math.hypot(posX[index] - posX[previous], posZ[index] - posZ[previous]);
            previous = index;
        }

        if (total < 0.001) return 0.0;
        return Math.max(0.0, Math.min(1.0, net / total));
    }

    /**
     * A 0-100 score: high when the turn rate varies, the player sometimes pauses, and the path is not
     * perfectly straight. The three contribute equally and each is normalised against a plausible human range.
     */
    public double humanLikeness() {
        if (size < 5) return 0.0;

        // Turn variance of ~5-40 (deg/tick)^2 reads as human; flat 0 reads as a bot.
        double varianceScore = clamp01(turnVariance() / 20.0);

        // Pausing 10-60% of the time is human; never pausing is not.
        double pause = pauseRatio();
        double pauseScore = pause <= 0.02 ? 0.0 : clamp01(pause / 0.4);

        // A perfectly straight path (1.0) is a bot; a wandering path is human.
        double straight = straightness();
        double wanderScore = 1.0 - clamp01((straight - 0.6) / 0.4);

        return Math.round((varianceScore * 0.45 + pauseScore * 0.35 + wanderScore * 0.20) * 100.0);
    }

    public Report report() {
        return new Report(turnVariance(), pauseRatio(), straightness(), humanLikeness(), size);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
