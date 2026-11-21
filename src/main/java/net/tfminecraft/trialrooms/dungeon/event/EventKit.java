package net.tfminecraft.trialrooms.dungeon.event;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.bukkit.configuration.ConfigurationSection;

/**
 * A kit's room events: which ones it uses and their numbers, from the {@code events:} section of
 * kit.yml. The recipe says how often fight rooms get one.
 *
 * @param weights       relative chance of each event; an event missing here never happens
 * @param braziers      braziers to light in a cold-braziers room (at most the room's spawn points)
 * @param lightSeconds  seconds a player stays beside a brazier to light it
 * @param endureSeconds seconds a hold-out room keeps its doors shut
 * @param stones        rune stones in a rune room (at most the room's spawn points)
 * @param runeLength    touches in the rune order at tier 1 (one more per tier above)
 * @param everySeconds  seconds between groups of enemies while braziers are cold or a hold-out lasts
 * @param budget        threat budget of each such group, and of the group a rune mistake brings
 * @param most          the most enemies an event lets stand at once (no group comes past it)
 */
public record EventKit(Map<EventKind, Integer> weights, int braziers, int lightSeconds, int endureSeconds,
        int stones, int runeLength, int everySeconds, double budget, int most) {

    public EventKit {
        weights = Map.copyOf(weights);
        if (weights.isEmpty() || weights.values().stream().anyMatch(w -> w < 1)) {
            throw new IllegalArgumentException("no usable event (weights must be at least 1)");
        }
        if (braziers < 1 || lightSeconds < 1 || endureSeconds < 10 || stones < 2 || runeLength < 2
                || everySeconds < 3 || !(budget > 0) || most < 1) {
            throw new IllegalArgumentException("numbers out of range: braziers, light-seconds and most-enemies at"
                    + " least 1, seconds at least 10, stones and length at least 2, every-seconds at least 3,"
                    + " budget above 0");
        }
    }

    /** The kit's events, or null when kit.yml has no {@code events:} section or it can't be used. */
    public static EventKit load(ConfigurationSection kit, String kitId, List<String> problems) {
        ConfigurationSection s = kit.getConfigurationSection("events");
        if (s == null) {
            return null;
        }
        Map<EventKind, Integer> weights = new EnumMap<>(EventKind.class);
        for (String key : s.getKeys(false)) {
            if (!s.isConfigurationSection(key)) {
                continue; // a shared number, read below
            }
            try {
                weights.put(EventKind.parse(key), s.getConfigurationSection(key).getInt("weight", 1));
            } catch (IllegalArgumentException e) {
                problems.add(kitId + ": events: " + e.getMessage());
            }
        }
        try {
            return new EventKit(weights, s.getInt("kindle.braziers", 3), s.getInt("kindle.light-seconds", 3),
                    s.getInt("endure.seconds", 60), s.getInt("runes.stones", 4), s.getInt("runes.length", 4),
                    s.getInt("every-seconds", 15), s.getDouble("budget", 2), s.getInt("most-enemies", 8));
        } catch (IllegalArgumentException e) {
            problems.add(kitId + ": events: " + e.getMessage());
            return null;
        }
    }

    /** A weighted pick among the kit's events. */
    public EventKind pick(Random rng) {
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        int roll = rng.nextInt(total);
        // in the enum's order, so a seed always picks the same event
        EventKind[] kinds = EventKind.values();
        int i = 0;
        while ((roll -= weights.getOrDefault(kinds[i], 0)) >= 0) {
            i++;
        }
        return kinds[i];
    }
}
