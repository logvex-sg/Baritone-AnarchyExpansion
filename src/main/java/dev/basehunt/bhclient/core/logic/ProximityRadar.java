package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks players near you and classifies how their distance is changing.
 *
 * <p>"There is a player 80 blocks away" is far less useful on an anarchy server than "a player is closing on
 * you". The client already sees every nearby player every tick; the value is in turning that stream into
 * discrete facts - someone entered your range, someone is approaching, someone left - that the rest of the
 * addon can react to without re-deriving them.
 *
 * <p>This class is pure: it takes names, positions, a distance and a timestamp, and it keeps a bounded,
 * evicting contact list so a busy server cannot grow it without limit. The thresholds and the transition
 * rules are covered headlessly.
 */
public final class ProximityRadar {
    /** How a contact's distance changed since the previous sample. */
    public enum Transition {
        /** First time this player was seen inside the tracked range. */
        ENTERED,
        /** Distance fell by at least the approach threshold. */
        APPROACHING,
        /** Distance rose by at least the approach threshold. */
        RECEDING,
        /** Present, but the distance barely moved. */
        STEADY,
        /** The contact was not in this sample; it has left the tracked range. */
        LEFT
    }

    /** A tracked player. */
    public record Contact(String name, int x, int y, int z, double distance, long lastSeen,
                          double previousDistance) {
        public String coords() {
            return "%d, %d, %d".formatted(x, y, z);
        }
    }

    private final Map<String, Contact> contacts = new LinkedHashMap<>();
    private int cap = 64;
    private double approachThreshold = 4.0;

    public ProximityRadar() {
    }

    public ProximityRadar(int cap, double approachThreshold) {
        this.cap = Math.max(1, cap);
        this.approachThreshold = Math.max(0.1, approachThreshold);
    }

    public void setApproachThreshold(double threshold) {
        this.approachThreshold = Math.max(0.1, threshold);
    }

    /**
     * Feeds one observation and returns how it relates to the previous one.
     *
     * @param name     player name
     * @param x        player x
     * @param y        player y
     * @param z        player z
     * @param distance horizontal distance from you
     * @param now      current time in milliseconds
     */
    public Transition observe(String name, int x, int y, int z, double distance, long now) {
        if (name == null || name.isBlank()) return Transition.STEADY;

        Contact previous = contacts.get(name);
        Transition transition;

        if (previous == null) {
            transition = Transition.ENTERED;
        } else if (previous.distance() - distance >= approachThreshold) {
            transition = Transition.APPROACHING;
        } else if (distance - previous.distance() >= approachThreshold) {
            transition = Transition.RECEDING;
        } else {
            transition = Transition.STEADY;
        }

        Contact updated = new Contact(name, x, y, z, distance, now,
            previous == null ? distance : previous.distance());
        // Re-insert at the end so the iteration order is least-recently-seen first, which the eviction uses.
        contacts.remove(name);
        contacts.put(name, updated);
        evict();
        return transition;
    }

    /**
     * Drops contacts not seen for longer than {@code maxAgeMs} and returns the names that left.
     */
    public List<String> expire(long now, long maxAgeMs) {
        List<String> left = new ArrayList<>();
        List<String> toRemove = new ArrayList<>();

        for (Contact contact : contacts.values()) {
            if (now - contact.lastSeen() > maxAgeMs) {
                toRemove.add(contact.name());
                left.add(contact.name());
            }
        }

        for (String name : toRemove) contacts.remove(name);
        return left;
    }

    /** Contacts seen within {@code maxAgeMs}, nearest first. */
    public List<Contact> active(long now, long maxAgeMs) {
        List<Contact> result = new ArrayList<>();
        for (Contact contact : contacts.values()) {
            if (now - contact.lastSeen() <= maxAgeMs) result.add(contact);
        }
        result.sort(Comparator.comparingDouble(Contact::distance));
        return result;
    }

    public int size() {
        return contacts.size();
    }

    public void clear() {
        contacts.clear();
    }

    public Contact get(String name) {
        return contacts.get(name);
    }

    private void evict() {
        while (contacts.size() > cap) {
            String oldest = contacts.keySet().iterator().next();
            contacts.remove(oldest);
        }
    }

    /** A compact "3 nearby, nearest Steve 42m" summary for the HUD. */
    public String summary(long now, long maxAgeMs) {
        List<Contact> active = active(now, maxAgeMs);
        if (active.isEmpty()) return "clear";
        Contact nearest = active.get(0);
        return "%d nearby, nearest %s %.0fm".formatted(active.size(), nearest.name(), nearest.distance());
    }
}
