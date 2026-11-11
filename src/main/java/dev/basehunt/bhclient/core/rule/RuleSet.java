package dev.basehunt.bhclient.core.rule;

import dev.basehunt.bhclient.core.AutomationEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The set of rules, evaluated in order, with the run log that keeps them honest.
 *
 * <p>Every tick the runtime evaluates the tick-driven rules; event-driven rules are evaluated when their
 * trigger fires. The set records what fired and what the action reported, so the terminal and the webhook
 * layer can show why something happened, not just that it did.
 *
 * <p>A rule whose action throws is caught and recorded as a failure rather than allowed to break the tick, the
 * same way the event bus treats a bad listener. That is the difference between a rule the user mistyped and a
 * client crash.
 */
public final class RuleSet {
    private final List<Rule> rules = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private static final int LOG_LIMIT = 64;

    public void add(Rule rule) {
        if (rule != null) rules.add(rule);
    }

    public void clear() {
        rules.clear();
    }

    public List<Rule> all() {
        return rules;
    }

    public Rule find(String name) {
        if (name == null) return null;
        for (Rule rule : rules) if (rule.name().equalsIgnoreCase(name)) return rule;
        return null;
    }

    public boolean remove(String name) {
        Rule rule = find(name);
        return rule != null && rules.remove(rule);
    }

    public void reset() {
        for (Rule rule : rules) rule.reset();
        log.clear();
    }

    /** Evaluates every rule that is not waiting on a specific event. */
    public void evaluateTick(Condition.FactSource facts, long tick) {
        for (Rule rule : rules) {
            if (rule.isEventDriven()) continue;
            evaluate(rule, facts, tick);
        }
    }

    /** Evaluates the rules that are waiting on this event's type. */
    public void evaluateEvent(AutomationEvent event, Condition.FactSource facts, long tick) {
        if (event == null) return;
        for (Rule rule : rules) {
            if (rule.trigger() == event.type()) evaluate(rule, facts, tick);
        }
    }

    private void evaluate(Rule rule, Condition.FactSource facts, long tick) {
        Rule.Action action;
        try {
            action = rule.evaluate(facts, tick);
        } catch (RuntimeException e) {
            record("! " + rule.name() + " condition error: " + e.getMessage());
            return;
        }

        if (action == null) return;

        try {
            String result = action.run(rule, tick);
            record(rule.name() + (result == null || result.isBlank() ? "" : " -> " + result));
        } catch (RuntimeException e) {
            record("! " + rule.name() + " action failed: " + e.getMessage());
        }
    }

    /** Recent rule activity, newest first. */
    public List<String> recent(int limit) {
        List<String> result = new ArrayList<>();
        for (int i = log.size() - 1; i >= 0 && result.size() < limit; i--) result.add(log.get(i));
        return result;
    }

    /** A one-line summary for the HUD. */
    public String status() {
        if (rules.isEmpty()) return "no rules";
        int enabled = 0;
        for (Rule rule : rules) if (rule.enabled()) enabled++;
        return "%d rule(s), %d enabled".formatted(rules.size(), enabled);
    }

    private void record(String line) {
        log.add(line);
        while (log.size() > LOG_LIMIT) log.remove(0);
    }

    /** The fact names the built-in rules use, for the terminal's help output. */
    public static Map<String, String> factHelp() {
        Map<String, String> help = new LinkedHashMap<>();
        help.put("health", "the player's current health");
        help.put("hunger", "the player's current food level");
        help.put("elytra_durability", "remaining durability of the worn elytra");
        help.put("elytra_durability_pct", "remaining durability of the worn elytra, as a percentage");
        help.put("spare_elytra", "how many spare elytras are carried");
        help.put("totems", "how many totems of undying are carried");
        help.put("free_slots", "empty main-inventory slots");
        help.put("inventory_used_pct", "how full the inventory is, as a percentage");
        help.put("nearby_players", "players within the configured radius");
        help.put("target_distance", "distance to the current exploration target");
        help.put("task_queue", "how many tasks are waiting");
        return help;
    }
}
