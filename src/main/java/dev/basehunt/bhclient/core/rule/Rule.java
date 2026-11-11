package dev.basehunt.bhclient.core.rule;

import dev.basehunt.bhclient.core.AutomationEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * A reactive rule: when a set of conditions holds, do an action - once, not every tick.
 *
 * <p>This is the piece that lets modules react to each other without being wired together. A rule watches the
 * automation bus for a trigger event, checks its conditions, and fires an action. The two things that make it
 * safe are:
 *
 * <ul>
 *   <li><b>Edge triggering.</b> A rule fires on the transition from "conditions false" to "conditions true",
 *       never while they stay true. Without this, a rule watching "health &lt; 8" would fire every tick and
 *       queue a hundred heal tasks.</li>
 *   <li><b>Cooldown.</b> Even an edge-triggered rule can oscillate (health dips, heals, dips again). A
 *       per-rule cooldown bounds how often it can fire.</li>
 * </ul>
 *
 * <p>The rule is pure: it decides <em>whether</em> to fire from the facts it is handed and the clock it is
 * given, and returns the action to run. Nothing here touches the client.
 */
public final class Rule {
    private final String name;
    private final List<Condition> conditions;
    private final Action action;
    private final AutomationEvent.Type trigger;
    private final int cooldownTicks;
    private final boolean enabled;

    private boolean lastHeld;
    private long lastFiredTick = Long.MIN_VALUE;

    public Rule(String name, List<Condition> conditions, Action action, AutomationEvent.Type trigger,
                int cooldownTicks, boolean enabled) {
        this.name = name == null ? "rule" : name;
        this.conditions = conditions == null ? List.of() : List.copyOf(conditions);
        this.action = action;
        this.trigger = trigger;
        this.cooldownTicks = Math.max(0, cooldownTicks);
        this.enabled = enabled;
    }

    public String name() {
        return name;
    }

    public List<Condition> conditions() {
        return conditions;
    }

    public Action action() {
        return action;
    }

    public AutomationEvent.Type trigger() {
        return trigger;
    }

    public boolean enabled() {
        return enabled;
    }

    public int cooldownTicks() {
        return cooldownTicks;
    }

    public boolean lastHeld() {
        return lastHeld;
    }

    public long lastFiredTick() {
        return lastFiredTick;
    }

    /**
     * Evaluates the rule.
     *
     * @param facts the current fact values
     * @param tick  the current tick, for the cooldown
     * @return the action to run, or null when the rule does not fire this tick
     */
    public Action evaluate(Condition.FactSource facts, long tick) {
        if (!enabled || action == null) return null;

        boolean held = true;
        for (Condition condition : conditions) {
            if (!condition.test(facts)) {
                held = false;
                break;
            }
        }

        if (!held) {
            lastHeld = false;
            return null;
        }

        // The condition is true. If it was already true last evaluation this is not a rising edge, so the rule
        // does not fire again.
        if (lastHeld) return null;

        if (cooldownTicks > 0 && lastFiredTick != Long.MIN_VALUE && tick - lastFiredTick < cooldownTicks) {
            // The edge is real but the cooldown is still running. Do NOT consume the edge: leave lastHeld false
            // so the rule fires as soon as the cooldown expires, rather than silently losing the transition.
            return null;
        }

        lastHeld = true;
        lastFiredTick = tick;
        return action;
    }

    /** True when this rule is subscribed to a trigger event rather than evaluated every tick. */
    public boolean isEventDriven() {
        return trigger != null;
    }

    /** Resets the edge state, e.g. after the module is re-enabled. */
    public void reset() {
        lastHeld = false;
        lastFiredTick = Long.MIN_VALUE;
    }

    /** A builder, because a rule is described by the user and should read like one. */
    public static final class Builder {
        private final String name;
        private final List<Condition> conditions = new ArrayList<>();
        private Action action;
        private AutomationEvent.Type trigger;
        private int cooldownTicks;
        private boolean enabled = true;

        public Builder(String name) {
            this.name = name;
        }

        public Builder when(String condition) {
            conditions.add(Condition.parse(condition));
            return this;
        }

        public Builder trigger(AutomationEvent.Type type) {
            this.trigger = type;
            return this;
        }

        public Builder cooldown(int ticks) {
            this.cooldownTicks = ticks;
            return this;
        }

        public Builder action(Action action) {
            this.action = action;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Rule build() {
            return new Rule(name, conditions, action, trigger, cooldownTicks, enabled);
        }
    }

    /** What a rule does when it fires. */
    @FunctionalInterface
    public interface Action {
        /**
         * @param rule the rule that fired
         * @param tick the tick it fired on
         * @return a short result message for the log, or null
         */
        String run(Rule rule, long tick);
    }
}
