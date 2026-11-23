package net.tfminecraft.trialrooms.dungeon.loot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.bukkit.configuration.ConfigurationSection;

/**
 * What leaves a dungeon with the player: act materials and rare items, never dungeon gear. A
 * dungeon is a run source of the act system (ACT-SYSTEM.md, rule 5): an hour of proper play pays a
 * full act budget and a full rare budget, like an hour of mining or infestations.
 *
 * @param perHour item (a TLibs item path, or {@code gem} or {@code denars}) to how many a proper
 *                player earns in an hour
 * @param gems    the gem pouch each {@code gem} gives, by weight
 * @param coins   coin items and their denar value; denars are paid in the largest coins first
 */
public record Spoils(Map<String, Double> perHour, Map<String, Integer> gems, List<Coin> coins) {

    /** A coin item and what it is worth in denars. */
    public record Coin(String item, int value) {
    }

    public Spoils {
        perHour = Map.copyOf(perHour);
        gems = Map.copyOf(gems);
        coins = coins.stream().sorted(Comparator.comparingInt(Coin::value).reversed()).toList();
        for (Map.Entry<String, Double> e : perHour.entrySet()) {
            if (!(e.getValue() >= 0) || Double.isInfinite(e.getValue())) {
                throw new IllegalArgumentException(e.getKey() + ": a number of items an hour, not negative");
            }
        }
        if (gems.values().stream().anyMatch(w -> w < 0)
                || perHour.containsKey("gem") && gems.values().stream().mapToInt(Integer::intValue).sum() <= 0) {
            throw new IllegalArgumentException("gem needs gem pouches with weights");
        }
        if (perHour.containsKey("denars") && (coins.isEmpty() || coins.getLast().value() != 1)) {
            throw new IllegalArgumentException("denars need coins, the smallest worth 1");
        }
        if (coins.stream().anyMatch(c -> c.value() < 1)) {
            throw new IllegalArgumentException("a coin is worth at least 1 denar");
        }
    }

    /**
     * What {@code hours} of proper play pay: each item's hourly amount times the hours, its fraction
     * paid as a chance of one more, so the average is exact.
     */
    public Map<String, Integer> roll(double hours, Random rng) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : new java.util.TreeMap<>(perHour).entrySet()) {
            double amount = e.getValue() * hours;
            int n = (int) Math.floor(amount);
            if (rng.nextDouble() < amount - n) {
                n++;
            }
            if (n <= 0) {
                continue;
            }
            switch (e.getKey()) {
                case "gem" -> {
                    for (int i = 0; i < n; i++) {
                        out.merge(pouch(rng), 1, Integer::sum);
                    }
                }
                case "denars" -> {
                    int left = n;
                    for (Coin c : coins) {
                        if (left >= c.value()) {
                            out.merge(c.item(), left / c.value(), Integer::sum);
                            left %= c.value();
                        }
                    }
                }
                default -> out.merge(e.getKey(), n, Integer::sum);
            }
        }
        return out;
    }

    private static String text(Map<?, ?> m, String key) {
        if (!(m.get(key) instanceof String v) || v.isBlank()) {
            throw new IllegalArgumentException("an entry without " + key);
        }
        return v.trim();
    }

    private static double number(Map<?, ?> m, String key) {
        if (!(m.get(key) instanceof Number v)) {
            throw new IllegalArgumentException(m.get("item") + ": no " + key);
        }
        return v.doubleValue();
    }

    private String pouch(Random rng) {
        java.util.TreeMap<Integer, String> upTo = new java.util.TreeMap<>();
        int total = 0;
        for (Map.Entry<String, Integer> e : new java.util.TreeMap<>(gems).entrySet()) {
            total += e.getValue();
            upTo.putIfAbsent(total, e.getKey()); // (a pouch weighted 0 never comes)
        }
        return upTo.higherEntry(rng.nextInt(total)).getValue();
    }

    /** Reads loot.yml's spoils section; null (with a problem) when it is wrong, null alone when there is none. */
    public static Spoils load(ConfigurationSection y, List<String> problems) {
        ConfigurationSection s = y.getConfigurationSection("spoils");
        if (s == null) {
            return null;
        }
        try {
            Map<String, Double> perHour = new LinkedHashMap<>();
            for (Map<?, ?> m : s.getMapList("per-hour")) {
                if (perHour.put(text(m, "item"), number(m, "amount")) != null) {
                    throw new IllegalArgumentException(m.get("item") + " is listed twice");
                }
            }
            if (perHour.isEmpty()) {
                throw new IllegalArgumentException("no per-hour items");
            }
            Map<String, Integer> gems = new LinkedHashMap<>();
            for (Map<?, ?> m : s.getMapList("gems")) {
                gems.put(text(m, "item"), (int) number(m, "weight"));
            }
            List<Coin> coins = new ArrayList<>();
            for (Map<?, ?> m : s.getMapList("coins")) {
                coins.add(new Coin(text(m, "item"), (int) number(m, "value")));
            }
            return new Spoils(perHour, gems, coins);
        } catch (RuntimeException e) {
            problems.add("loot.yml spoils: " + e.getMessage() + ", so runs pay no spoils");
            return null;
        }
    }
}
