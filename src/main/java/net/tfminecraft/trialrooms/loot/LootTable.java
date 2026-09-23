package net.tfminecraft.trialrooms.loot;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Loot table that parses simple lines like:
 *   "v.emerald 2-5 0.9 [tier]"
 * where:
 *   type        -> string id you resolve elsewhere
 *   amount      -> "min-max" (inclusive)
 *   weight      -> double
 *   tier        -> optional; one of: bad, common, uncommon, rare, epic, legendary (or numbers 1..5)
 *
 * Level bias (biasPerLevel) increases/decreases weights by tier:
 * adjustedWeight = baseWeight * clamp(0.05..10.0, 1 + biasPerLevel * level * tierScore)
 * tierScore: BAD=-2, COMMON=-1, UNCOMMON=0, RARE=+1, EPIC=+2, LEGENDARY=+3
 */
public final class LootTable {

    public enum Tier {
        BAD(-2), COMMON(-1), UNCOMMON(0), RARE(1), EPIC(2), LEGENDARY(3);
        public final int score;
        Tier(int s) { this.score = s; }

        public static Tier parse(String s) {
            if (s == null) return COMMON;
            String t = s.trim().toUpperCase(Locale.ROOT);
            if (t.matches("\\d+")) {
                int n = Integer.parseInt(t);
                return switch (Math.max(1, Math.min(5, n))) {
                    case 1 -> COMMON;
                    case 2 -> UNCOMMON;
                    case 3 -> RARE;
                    case 4 -> EPIC;
                    default -> LEGENDARY;
                };
            }
            return switch (t) {
                case "BAD" -> BAD;
                case "COMMON" -> COMMON;
                case "UNCOMMON" -> UNCOMMON;
                case "RARE" -> RARE;
                case "EPIC" -> EPIC;
                case "LEGENDARY" -> LEGENDARY;
                default -> COMMON;
            };
        }
    }

    public static final class Entry {
        public final String type;        // e.g. "v.emerald"
        public final int minAmount;
        public final int maxAmount;
        public final double baseWeight;
        public final Tier tier;

        public Entry(String type, int minAmount, int maxAmount, double baseWeight, Tier tier) {
            this.type = type;
            this.minAmount = minAmount;
            this.maxAmount = maxAmount;
            this.baseWeight = baseWeight;
            this.tier = tier;
        }

        public int rollAmount(ThreadLocalRandom rng) {
            if (minAmount >= maxAmount) return Math.max(0, minAmount);
            return rng.nextInt(minAmount, maxAmount + 1);
        }

        @Override
        public String toString() {
            return type + " " + minAmount + "-" + maxAmount + " w=" + baseWeight + " " + tier;
        }
    }

    public static final class Drop {
        public final String type;
        public final int amount;
        public Drop(String type, int amount) { this.type = type; this.amount = amount; }
        @Override public String toString() { return type + " x" + amount; }
    }

    private final String name;
    private final List<Entry> entries;
    private final double biasPerLevel;

    public LootTable(String name, List<String> lines, double biasPerLevel) {
        this.name = name;
        this.biasPerLevel = biasPerLevel;
        this.entries = parseLines(lines);
    }

    public String getName() { return name; }
    public List<Entry> getEntries() { return entries; }
    public double getBiasPerLevel() { return biasPerLevel; }
    public boolean isEmpty() { return entries.isEmpty(); }

    /** Weighted random pick of ONE entry given a spawner level. */
    public Entry pickOne(int level, ThreadLocalRandom rng) {
        if (entries.isEmpty()) return null;
        double total = 0.0;
        double[] weights = new double[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            double w = adjustedWeight(entries.get(i), level);
            weights[i] = w;
            total += w;
        }
        if (total <= 0.0) return null;
        double r = rng.nextDouble() * total;
        double acc = 0.0;
        for (int i = 0; i < entries.size(); i++) {
            acc += weights[i];
            if (r <= acc) return entries.get(i);
        }
        return entries.get(entries.size() - 1); // fallback
    }

    /** Roll N drops (with replacement) at a given level. */
    public List<Drop> rollMany(int level, int rolls) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<Drop> out = new ArrayList<>(Math.max(0, rolls));
        for (int i = 0; i < rolls; i++) {
            Entry e = pickOne(level, rng);
            if (e == null) continue;
            int amt = e.rollAmount(rng);
            if (amt > 0) out.add(new Drop(e.type, amt));
        }
        return out;
    }

    // ---------------- internals ----------------

    private List<Entry> parseLines(List<String> lines) {
        if (lines == null) return Collections.emptyList();
        List<Entry> list = new ArrayList<>();
        for (String raw : lines) {
            Entry e = parseLine(raw);
            if (e != null) list.add(e);
        }
        return Collections.unmodifiableList(list);
    }

    /** Accepts: "type min-max weight [tier]" */
    private Entry parseLine(String line) {
        if (line == null) return null;
        String trimmed = stripComment(line.trim());
        if (trimmed.isEmpty()) return null;

        String[] parts = trimmed.split("\\s+");
        if (parts.length < 3) {
            Bukkit.getLogger().warning("[LootTable:" + name + "] Bad line (need type min-max weight): " + line);
            return null;
        }

        String type = parts[0];

        int dash = parts[1].indexOf('-');
        if (dash <= 0) {
            Bukkit.getLogger().warning("[LootTable:" + name + "] Bad range (use min-max): " + line);
            return null;
        }

        int min, max;
        try {
            min = Integer.parseInt(parts[1].substring(0, dash));
            max = Integer.parseInt(parts[1].substring(dash + 1));
            if (min > max) { int t = min; min = max; max = t; }
        } catch (NumberFormatException ex) {
            Bukkit.getLogger().warning("[LootTable:" + name + "] Bad numbers in range: " + line);
            return null;
        }

        double weight;
        try {
            weight = Double.parseDouble(parts[2]);
        } catch (NumberFormatException ex) {
            Bukkit.getLogger().warning("[LootTable:" + name + "] Bad weight: " + line);
            return null;
        }

        LootTable.Tier tier = (parts.length >= 4) ? LootTable.Tier.parse(parts[3]) : LootTable.Tier.COMMON;

        return new Entry(type, Math.max(0, min), Math.max(0, max), Math.max(0.0, weight), tier);
    }

    private String stripComment(String s) {
        int i = s.indexOf('#');
        return (i >= 0) ? s.substring(0, i).trim() : s;
    }

    private double adjustedWeight(Entry e, int level) {
        // linear bias by level and tier score, clamped to sensible range
        double mult = 1.0 + biasPerLevel * Math.max(0, level) * e.tier.score;
        mult = Math.max(0.05, Math.min(mult, 10.0));
        return e.baseWeight * mult;
    }

    /** Min–max draws for a given key rarity (COMMON..LEGENDARY). */
    public static final class Range { public final int min, max;
        public Range(int min, int max) { this.min = Math.max(0, min); this.max = Math.max(this.min, max); } }

    // rarity name (lowercase) -> range
    private final Map<String, Range> amountsByKeyRarity = new HashMap<>();

    // NEW: factory for the new YAML shape
    public static LootTable fromSection(String name, ConfigurationSection section, double biasPerLevel) {
        if (section == null) return null;

        // parse drops
        List<String> drops = section.getStringList("drops");
        // fallback for legacy (flat list of lines directly under table)
        if (drops == null || drops.isEmpty()) drops = section.getStringList(""); // often empty, harmless

        LootTable lt = new LootTable(name, drops, biasPerLevel);

        // parse amounts
        List<String> amounts = section.getStringList("amounts");
        if (amounts != null) lt.parseAmounts(amounts);

        return lt;
    }

    public Range getAmountRangeForKeyRarityName(String rarityName) {
        if (rarityName == null) return null;
        return amountsByKeyRarity.get(rarityName.toLowerCase(Locale.ROOT));
    }

    private void parseAmounts(List<String> lines) {
        // lines like: "common 2-3"
        for (String raw : lines) {
            String s = stripComment(String.valueOf(raw).trim());
            if (s.isEmpty()) continue;

            String[] parts = s.split("\\s+");
            if (parts.length < 2) {
                Bukkit.getLogger().warning("[LootTable:" + name + "] Bad amounts line (need '<rarity> min-max'): " + raw);
                continue;
            }
            String rarityKey = parts[0].toLowerCase(Locale.ROOT);

            int dash = parts[1].indexOf('-');
            if (dash <= 0) {
                Bukkit.getLogger().warning("[LootTable:" + name + "] Bad amounts range (use min-max): " + raw);
                continue;
            }
            try {
                int min = Integer.parseInt(parts[1].substring(0, dash));
                int max = Integer.parseInt(parts[1].substring(dash + 1));
                amountsByKeyRarity.put(rarityKey, new Range(min, max));
            } catch (NumberFormatException ex) {
                Bukkit.getLogger().warning("[LootTable:" + name + "] Bad numbers in amounts range: " + raw);
            }
        }
    }
}
