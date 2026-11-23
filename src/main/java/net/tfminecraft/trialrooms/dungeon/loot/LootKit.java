package net.tfminecraft.trialrooms.dungeon.loot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Dungeon loot settings from {@code dungeons/loot.yml}: what dungeon items are made of, how much
 * stronger each rarity and tier makes them, their names and looks.
 *
 * @param standard  the act's standard AdvancedCrafting ingredient for each recipe main type
 *                  (metal: steel_ingot): items start as that recipe made of it
 * @param rarities  from the most common up
 * @param tiers     dungeon tier to its power and luck
 * @param socket    the socket colour dungeon items carry (only dungeon gems fit it)
 * @param pools     bonus stat pools: stat to its range at tier 1 before the rarity's power
 * @param bases     the items that can drop
 * @param prefixes  words that stand in for the material in a base's name
 * @param suffixes  endings for the rarer items' names
 * @param looks     rarity, then look group (an armour weight), to the skin sets that armour may get
 *                  (ia.tfmc_armor:SET_PIECE)
 * @param unscaled  stats rarity and tier never change (speeds, durability)
 */
public record LootKit(Map<String, String> standard, List<Rarity> rarities, Map<Integer, Tier> tiers, String socket,
        Map<String, Map<String, Range>> pools, List<Base> bases, List<String> prefixes, List<String> suffixes,
        Map<String, Map<String, List<String>>> looks, Set<String> unscaled) {

    /** How rare and how strong. */
    public record Rarity(String id, String name, int weight, double power, int bonus, int sockets, String colour) {
        public Rarity {
            if (weight < 0 || !(power > 0) || bonus < 0 || sockets < 0 || !colour.matches("#[0-9a-fA-F]{6}")) {
                throw new IllegalArgumentException("rarity " + id + ": numbers out of range or a bad colour");
            }
        }
    }

    /** A dungeon tier: how much stronger its items are, and how much rarer items it favours. */
    public record Tier(double power, double luck) {
        public Tier {
            if (!(power > 0) || !(luck >= 0)) {
                throw new IllegalArgumentException("tier: power must be above 0 and luck 0 or more");
            }
        }
    }

    /** A stat range. */
    public record Range(double min, double max) {
        public Range {
            if (!(min <= max) || Double.isInfinite(max)) {
                throw new IllegalArgumentException("a range needs min <= max");
            }
        }

        double roll(double t) {
            return min + (max - min) * t;
        }
    }

    /** Where an item goes. Armour pieces take skins; the rest keep their template's look. */
    public enum Slot {
        HELMET, CHESTPLATE, LEGGINGS, BOOTS, WEAPON, RING, AMULET;

        public boolean armour() {
            return ordinal() <= BOOTS.ordinal();
        }

        public String piece() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * An item that can drop: an AdvancedCrafting recipe (armour and weapons), or an MMOItems template
     * with its own stat ranges (jewellery).
     *
     * @param name a name with %material% where a prefix goes
     * @param look the look group its skins come from (armour weight), or null to keep the template's look
     */
    public record Base(String id, String name, Slot slot, String recipe, String template, String pool,
            Map<String, Range> stats, String look) {
        public Base {
            if ((recipe == null) == (template == null)) {
                throw new IllegalArgumentException("item " + id + ": give a recipe or a template, not both");
            }
        }
    }

    public LootKit {
        if (rarities.isEmpty() || tiers.isEmpty() || bases.isEmpty() || prefixes.isEmpty() || suffixes.isEmpty()) {
            throw new IllegalArgumentException("loot needs rarities, tiers, items, prefixes and suffixes");
        }
        if (rarities.stream().mapToInt(Rarity::weight).sum() <= 0) {
            throw new IllegalArgumentException("loot: the rarity weights add up to nothing");
        }
        for (Base b : bases) {
            if (!pools.containsKey(b.pool())) {
                throw new IllegalArgumentException("item " + b.id() + ": no bonus pool " + b.pool());
            }
        }
    }

    public Rarity rarity(String id) {
        return rarities.stream().filter(r -> r.id().equalsIgnoreCase(id)).findFirst().orElse(null);
    }

    public Base base(String id) {
        return bases.stream().filter(b -> b.id().equalsIgnoreCase(id)).findFirst().orElse(null);
    }

    /** The tier's settings; tiers past the last use the last. */
    public Tier tier(int tier) {
        Map.Entry<Integer, Tier> e = new TreeMap<>(tiers).floorEntry(tier);
        return e == null ? tiers.values().iterator().next() : e.getValue();
    }

    /** Reads loot.yml; problems are listed and the kit is null when it can't be used. */
    public static LootKit load(ConfigurationSection y, List<String> problems) {
        try {
            Map<String, String> standard = strings(y.getConfigurationSection("standard"));
            List<Rarity> rarities = new ArrayList<>();
            ConfigurationSection rs = section(y, "rarities");
            for (String id : rs.getKeys(false)) {
                ConfigurationSection r = rs.getConfigurationSection(id);
                rarities.add(new Rarity(id, r.getString("name", id), r.getInt("weight"), r.getDouble("power", 1),
                        r.getInt("bonus"), r.getInt("sockets"), r.getString("colour", "")));
            }
            Map<Integer, Tier> tiers = new LinkedHashMap<>();
            ConfigurationSection ts = section(y, "tiers");
            for (String t : ts.getKeys(false)) {
                ConfigurationSection s = ts.getConfigurationSection(t);
                tiers.put(Integer.parseInt(t), new Tier(s.getDouble("power", 1), s.getDouble("luck", 0)));
            }
            Map<String, Map<String, Range>> pools = pools(y);
            List<Base> bases = new ArrayList<>();
            ConfigurationSection is = section(y, "items");
            for (String id : is.getKeys(false)) {
                ConfigurationSection b = is.getConfigurationSection(id);
                bases.add(new Base(id, b.getString("name", id), Slot.valueOf(b.getString("slot", "").toUpperCase(Locale.ROOT)),
                        b.getString("recipe"), b.getString("template"), b.getString("pool", ""),
                        ranges(b.getConfigurationSection("stats")), b.getString("look")));
            }
            Map<String, Map<String, List<String>>> looks = new LinkedHashMap<>();
            ConfigurationSection ls = section(y, "looks");
            for (String r : ls.getKeys(false)) {
                ConfigurationSection groups = section(ls, r);
                Map<String, List<String>> byGroup = new LinkedHashMap<>();
                groups.getKeys(false).forEach(g -> byGroup.put(g, groups.getStringList(g)));
                looks.put(r, byGroup);
            }
            return new LootKit(standard, rarities, tiers, y.getString("socket", "Dungeon Gem"), pools, bases,
                    y.getStringList("names.prefixes"), y.getStringList("names.suffixes"), looks,
                    Set.copyOf(y.getStringList("unscaled").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList()));
        } catch (RuntimeException e) {
            problems.add("loot.yml: " + e.getMessage());
            return null;
        }
    }

    private static ConfigurationSection section(ConfigurationSection y, String path) {
        ConfigurationSection s = y.getConfigurationSection(path);
        if (s == null) {
            throw new IllegalArgumentException("no " + path + " section");
        }
        return s;
    }

    private static Map<String, String> strings(ConfigurationSection s) {
        Map<String, String> out = new LinkedHashMap<>();
        if (s != null) {
            s.getKeys(false).forEach(k -> out.put(k.toLowerCase(Locale.ROOT), s.getString(k)));
        }
        return out;
    }

    /**
     * Bonus pools: each lists stats, and every bonus stat is worth the same, bonus-worth [min, max]
     * units, where worth gives what one unit of each stat is (so no pick is better than another).
     */
    private static Map<String, Map<String, Range>> pools(ConfigurationSection y) {
        List<Double> span = y.getDoubleList("bonus-worth");
        if (span.size() != 2) {
            throw new IllegalArgumentException("bonus-worth: give [min, max]");
        }
        ConfigurationSection worth = section(y, "worth");
        Map<String, Map<String, Range>> pools = new LinkedHashMap<>();
        ConfigurationSection ps = section(y, "bonus");
        for (String pool : ps.getKeys(false)) {
            Map<String, Range> stats = new LinkedHashMap<>();
            for (String stat : ps.getStringList(pool)) {
                double unit = worth.getDouble(stat, 0);
                if (!(unit > 0)) {
                    throw new IllegalArgumentException("bonus pool " + pool + ": no worth for " + stat);
                }
                stats.put(stat.toLowerCase(Locale.ROOT), new Range(unit * span.get(0), unit * span.get(1)));
            }
            if (stats.isEmpty()) {
                throw new IllegalArgumentException("bonus pool " + pool + " lists no stats");
            }
            pools.put(pool, stats);
        }
        return pools;
    }

    /** stat: [min, max] lists. */
    private static Map<String, Range> ranges(ConfigurationSection s) {
        Map<String, Range> out = new LinkedHashMap<>();
        if (s != null) {
            for (String stat : s.getKeys(false)) {
                List<Double> mm = s.getDoubleList(stat);
                if (mm.size() != 2) {
                    throw new IllegalArgumentException(stat + ": give [min, max]");
                }
                out.put(stat.toLowerCase(Locale.ROOT), new Range(mm.get(0), mm.get(1)));
            }
        }
        return out;
    }
}
