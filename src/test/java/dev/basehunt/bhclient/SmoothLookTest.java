package dev.basehunt.bhclient;

import dev.basehunt.bhclient.core.logic.BehaviorProfiler;
import dev.basehunt.bhclient.core.logic.HumanBehavior;
import dev.basehunt.bhclient.core.logic.LookSmoother;

/**
 * Headless tests for the smooth-look camera and the human-behavior gesture scheduler.
 *
 * <p>Both are pure logic over angles and ticks, so the easing, the wrapping, the speed cap, the arrival
 * tolerance and the anti-repetition rule are pinned here instead of only being felt on a live server. The
 * point of each is the same: make an automated client move the way a person does, without ever being able
 * to turn a small gesture into a long, exposed detour.
 */
public class SmoothLookTest {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  PASS: " + name); }
        else { failed++; System.out.println("  FAIL: " + name + " -> " + detail); }
    }

    static void section(String title) {
        System.out.println("== " + title + " ==");
    }

    public static void main(String[] args) {
        wrapping();
        geometry();
        smoothing();
        jitterAndBounds();
        gestureSchedule();
        gesturePool();
        behaviorProfiler();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    static void wrapping() {
        section("angle wrapping");

        check("wrap folds 190 to -170", LookSmoother.wrap(190.0f) == -170.0f, "" + LookSmoother.wrap(190.0f));
        check("wrap folds -190 to 170", LookSmoother.wrap(-190.0f) == 170.0f, "" + LookSmoother.wrap(-190.0f));
        check("wrap leaves 180 alone", LookSmoother.wrap(180.0f) == 180.0f, "" + LookSmoother.wrap(180.0f));
        check("wrap folds 720 to 0", LookSmoother.wrap(720.0f) == 0.0f, "" + LookSmoother.wrap(720.0f));
        check("wrap360 keeps 0..360", LookSmoother.wrap360(-10.0f) == 350.0f, "" + LookSmoother.wrap360(-10.0f));

        check("shortest delta crosses the seam", LookSmoother.shortestDelta(170.0f, -170.0f) == -20.0f,
            "" + LookSmoother.shortestDelta(170.0f, -170.0f));
        check("shortest delta is signed the short way",
            LookSmoother.shortestDelta(-170.0f, 170.0f) == 20.0f,
            "" + LookSmoother.shortestDelta(-170.0f, 170.0f));

        check("pitch clamps high", LookSmoother.clampPitch(120.0f) == 90.0f, "" + LookSmoother.clampPitch(120.0f));
        check("pitch clamps low", LookSmoother.clampPitch(-120.0f) == -90.0f, "" + LookSmoother.clampPitch(-120.0f));
    }

    static void geometry() {
        section("aim geometry");

        // Minecraft yaw 0 faces +Z, 90 faces -X, so a target straight along +Z is yaw 0.
        check("yaw toward +Z is 0", Math.abs(LookSmoother.yawTo(0, 0, 0, 10)) < 0.01f,
            "" + LookSmoother.yawTo(0, 0, 0, 10));
        check("yaw toward +X is -90", Math.abs(LookSmoother.yawTo(0, 0, 10, 0) + 90.0f) < 0.01f,
            "" + LookSmoother.yawTo(0, 0, 10, 0));
        check("yaw toward -Z is 180", Math.abs(Math.abs(LookSmoother.yawTo(0, 0, 0, -10)) - 180.0f) < 0.01f,
            "" + LookSmoother.yawTo(0, 0, 0, -10));

        // Looking up must be negative pitch, looking down positive.
        check("pitch up is negative", LookSmoother.pitchTo(0, 0, 0, 0, 10, 10) < 0.0f,
            "" + LookSmoother.pitchTo(0, 0, 0, 0, 10, 10));
        check("pitch down is positive", LookSmoother.pitchTo(0, 10, 0, 0, 0, 10) > 0.0f,
            "" + LookSmoother.pitchTo(0, 10, 0, 0, 0, 10));
        check("pitch to level target is 0", Math.abs(LookSmoother.pitchTo(0, 5, 0, 10, 5, 10)) < 0.01f,
            "" + LookSmoother.pitchTo(0, 5, 0, 10, 5, 10));
    }

    static void smoothing() {
        section("smoothing");

        LookSmoother smoother = new LookSmoother(1L, 10.0f, 1.0f, 0.0f);
        smoother.snap(0.0f, 0.0f);
        smoother.aimAt(90.0f, 0.0f);

        LookSmoother.Step first = smoother.tick();
        check("first tick does not reach the target", Math.abs(first.yaw() - 90.0f) > 0.01f, "" + first.yaw());
        check("first tick is capped at the speed", Math.abs(first.yaw() - 0.0f) <= 10.0001f, "" + first.yaw());
        check("first tick is marked moved", first.moved(), "not moved");

        boolean arrived = false;
        for (int i = 0; i < 100 && !arrived; i++) {
            smoother.tick();
            arrived = smoother.atTarget(1.0f);
        }
        check("the aim converges to the target", arrived, "yaw=" + smoother.yaw());
        check("the converged yaw is the target", Math.abs(smoother.yaw() - 90.0f) < 1.0f, "" + smoother.yaw());

        // A turn across the seam must go the short way (20 degrees), not the long way (340).
        LookSmoother seam = new LookSmoother(2L, 8.0f, 1.0f, 0.0f);
        seam.snap(170.0f, 0.0f);
        seam.aimAt(-170.0f, 0.0f);
        LookSmoother.Step seamStep = seam.tick();
        float travelled = Math.abs(LookSmoother.shortestDelta(seamStep.yaw(), 170.0f));
        check("a seam turn takes the short arc", travelled <= 8.0001f, "" + travelled);
        check("a seam turn moves away from the start", travelled > 0.01f, "" + travelled);
    }

    static void jitterAndBounds() {
        section("jitter and bounds");

        LookSmoother steady = new LookSmoother(3L, 10.0f, 1.0f, 0.0f);
        steady.snap(0.0f, 0.0f);
        steady.aimAt(0.0f, 0.0f);
        check("at target with zero jitter", steady.atTarget(0.1f), "not at target");

        LookSmoother jittered = new LookSmoother(4L, 10.0f, 0.3f, 1.0f);
        jittered.snap(0.0f, 0.0f);
        jittered.aimAt(90.0f, 0.0f);
        jittered.tick();
        float afterJitter = jittered.yaw();
        check("jitter keeps the yaw in range", afterJitter > -90.0f && afterJitter < 180.0f, "" + afterJitter);

        LookSmoother a = new LookSmoother(99L, 10.0f, 0.3f, 1.0f);
        LookSmoother b = new LookSmoother(99L, 10.0f, 0.3f, 1.0f);
        a.snap(0.0f, 0.0f);
        b.snap(0.0f, 0.0f);
        a.aimAt(60.0f, 20.0f);
        b.aimAt(60.0f, 20.0f);
        boolean same = true;
        for (int i = 0; i < 20; i++) {
            LookSmoother.Step sa = a.tick();
            LookSmoother.Step sb = b.tick();
            if (sa.yaw() != sb.yaw() || sa.pitch() != sb.pitch()) same = false;
        }
        check("the same seed reproduces the motion", same, "diverged");

        LookSmoother capped = new LookSmoother(5L, 5.0f, 1.0f, 0.0f);
        capped.snap(0.0f, 80.0f);
        capped.aimAt(0.0f, -80.0f);
        float minPitch = 80.0f;
        for (int i = 0; i < 40; i++) {
            minPitch = Math.min(minPitch, capped.tick().pitch());
        }
        check("pitch never leaves -90..90", minPitch >= -90.0f, "" + minPitch);

        LookSmoother setter = new LookSmoother(6L);
        setter.setSpeed(1000.0f);
        setter.setJitter(1000.0f);
        setter.setEasing(0.0f);
        setter.snap(0.0f, 0.0f);
        setter.aimAt(10.0f, 0.0f);
        LookSmoother.Step bounded = setter.tick();
        check("out-of-range settings are clamped", Math.abs(bounded.yaw()) <= 180.0f, "" + bounded.yaw());
    }

    static void gestureSchedule() {
        section("gesture schedule");

        HumanBehavior behavior = new HumanBehavior(1L);
        behavior.configure(5, 5, 3, 3, 10, true, true);
        behavior.reset();

        int gestures = 0;
        int ticks = 0;
        while (gestures < 5 && ticks < 500) {
            if (behavior.tick() != null) gestures++;
            ticks++;
        }
        check("gestures are produced over time", gestures == 5, "gestures=" + gestures);

        HumanBehavior idleCheck = new HumanBehavior(2L);
        idleCheck.configure(20, 20, 2, 2, 10, true, true);
        idleCheck.reset();
        boolean early = false;
        for (int i = 0; i < 19; i++) {
            if (idleCheck.tick() != null) early = true;
        }
        check("no gesture before the minimum idle", !early, "gestured early");

        HumanBehavior durations = new HumanBehavior(3L);
        durations.configure(2, 2, 4, 4, 10, true, true);
        durations.reset();
        HumanBehavior.Gesture gesture = null;
        for (int i = 0; i < 50 && gesture == null; i++) gesture = durations.tick();
        check("a gesture is produced", gesture != null, "none");
        check("the gesture lasts the configured ticks", gesture != null && gesture.durationTicks() == 4,
            gesture == null ? "none" : "" + gesture.durationTicks());
        check("busy while a gesture runs", durations.busy(), "not busy");

        durations.reset();
        check("reset clears the active gesture", !durations.busy(), "still busy");
    }

    static void gesturePool() {
        section("gesture pool");

        HumanBehavior behavior = new HumanBehavior(7L);
        behavior.configure(1, 1, 1, 1, 8, true, true);
        behavior.reset();

        boolean sawJump = false;
        boolean sawSneak = false;
        boolean sawGlance = false;
        boolean sawStrafe = false;
        boolean repeated = false;
        boolean glanceInRange = true;
        boolean strafeUnit = true;
        HumanBehavior.Kind previous = null;

        for (int i = 0; i < 400; i++) {
            HumanBehavior.Gesture gesture = behavior.tick();
            if (gesture == null) continue;

            if (gesture.kind() == previous) repeated = true;
            previous = gesture.kind();

            switch (gesture.kind()) {
                case JUMP -> sawJump = true;
                case SNEAK -> sawSneak = true;
                case GLANCE -> {
                    sawGlance = true;
                    if (Math.abs(gesture.value()) > 8) glanceInRange = false;
                }
                case STRAFE -> {
                    sawStrafe = true;
                    if (Math.abs(gesture.value()) != 1) strafeUnit = false;
                }
                case GLANCE_DOWN -> { }
            }
        }

        check("the pool includes a glance", sawGlance, "never glanced");
        check("the pool includes a strafe", sawStrafe, "never strafed");
        check("the pool includes a jump", sawJump, "never jumped");
        check("the pool includes a sneak", sawSneak, "never sneaked");
        check("glances stay inside the configured range", glanceInRange, "out of range");
        check("a strafe is a single direction", strafeUnit, "not unit");
        check("the same gesture never repeats twice in a row", !repeated, "repeated");

        HumanBehavior noJump = new HumanBehavior(8L);
        noJump.configure(1, 1, 1, 1, 8, false, false);
        noJump.reset();
        boolean jumped = false;
        boolean sneaked = false;
        for (int i = 0; i < 400; i++) {
            HumanBehavior.Gesture gesture = noJump.tick();
            if (gesture == null) continue;
            if (gesture.kind() == HumanBehavior.Kind.JUMP) jumped = true;
            if (gesture.kind() == HumanBehavior.Kind.SNEAK) sneaked = true;
        }
        check("jump is excluded when disabled", !jumped, "jumped");
        check("sneak is excluded when disabled", !sneaked, "sneaked");
        check("gesture count is tracked", noJump.gestures() > 0, "no gestures counted");
    }

    static void behaviorProfiler() {
        section("behavior profiler");

        BehaviorProfiler profiler = new BehaviorProfiler(50);
        check("a fresh profiler has no samples", profiler.samples() == 0, "samples=" + profiler.samples());
        check("a fresh score is zero", profiler.humanLikeness() == 0.0, "" + profiler.humanLikeness());

        // A perfectly mechanical bot: a constant turn rate, a perfectly straight path, never pausing.
        BehaviorProfiler bot = new BehaviorProfiler(60);
        double x = 0.0;
        for (int i = 0; i < 60; i++) {
            bot.sample(i * 10.0, 0.0, x, 0.0, true);
            x += 0.2;
        }
        check("the bot has samples", bot.samples() == 59, "samples=" + bot.samples());
        check("a constant turn rate has near-zero variance", bot.turnVariance() < 0.001,
            "" + bot.turnVariance());
        check("a moving bot never pauses", bot.pauseRatio() == 0.0, "" + bot.pauseRatio());
        check("a straight path scores straightness 1", Math.abs(bot.straightness() - 1.0) < 0.001,
            "" + bot.straightness());
        check("a mechanical bot scores low", bot.humanLikeness() < 25.0, "" + bot.humanLikeness());

        // A human: an irregular turn rate, pauses, and a wandering path.
        BehaviorProfiler human = new BehaviorProfiler(60);
        double hx = 0.0;
        double hz = 0.0;
        double yaw = 0.0;
        double[] turns = {3, 14, 1, 22, 6, 0, 18, 9, 2, 12};
        for (int i = 0; i < 60; i++) {
            boolean moving = i % 3 != 0;
            if (moving) {
                yaw += turns[i % turns.length] * (i % 2 == 0 ? 1 : -1);
                hx += Math.cos(i * 0.7) * 0.2;
                hz += Math.sin(i * 0.7) * 0.2;
            }
            human.sample(yaw, 0.0, hx, hz, moving);
        }
        check("a human turn rate varies", human.turnVariance() > 1.0, "" + human.turnVariance());
        check("a human pauses sometimes", human.pauseRatio() > 0.1, "" + human.pauseRatio());
        check("a wandering path is not straight", human.straightness() < 0.9, "" + human.straightness());
        check("a human scores higher than a bot", human.humanLikeness() > bot.humanLikeness(),
            "human=" + human.humanLikeness() + " bot=" + bot.humanLikeness());

        BehaviorProfiler reset = new BehaviorProfiler(20);
        for (int i = 0; i < 20; i++) reset.sample(i, 0, i, i, true);
        reset.reset();
        check("reset clears the window", reset.samples() == 0, "samples=" + reset.samples());
        check("reset clears the score", reset.humanLikeness() == 0.0, "" + reset.humanLikeness());

        BehaviorProfiler bounded = new BehaviorProfiler(10);
        for (int i = 0; i < 200; i++) bounded.sample(i * 3.0, 0, i, 0, i % 2 == 0);
        check("the window is bounded by the capacity", bounded.samples() == 10, "samples=" + bounded.samples());
        check("the report describes the window", bounded.report().describe().contains("human"),
            bounded.report().describe());
    }
}
