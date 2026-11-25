package net.tfminecraft.trialrooms.dungeon.loot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.bukkit.configuration.ConfigurationSection;

import net.tfminecraft.trialrooms.dungeon.gear.GearSet;
import net.tfminecraft.trialrooms.dungeon.gear.Trait;

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
 * @param prefixes  words for gems' names, and for items of no set (in the material's place)
 * @param suffixes  endings for the rarer items' names
 * @param looks     where the sets' looks are (a TLibs item path prefix): LOOKS + set + _ + look
 * @param sets      the gear sets items come in, by id
 * @param worth     what one unit of a bonus of each stat is
 * @param unscaled  stats rarity and tier never change (speeds, durability)
 */
public record LootKit(Map<String, String> standard, List<Rarity> rarities, Map<Integer, Tier> tiers, String socket,
        Map<String, Map<String, Range>> pools, List<Base> bases, List<String> prefixes, List<String> suffixes,
        String looks, Map<String, GearSet> sets, Map<String, Double> worth, Set<String> unscaled) {

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

    /** Where an item goes. */
    public enum Slot {
        HELMET, CHESTPLATE, LEGGINGS, BOOTS, WEAPON, OFF_HAND, RING, AMULET, ARTIFACT;

        public boolean armour() {
            return ordinal() <= BOOTS.ordinal();
        }

        public String piece() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** What it counts as in a set. */
        public GearSet.Kind kind() {
            return armour() ? GearSet.Kind.ARMOUR : this == WEAPON ? GearSet.Kind.WEAPON
                    : this == OFF_HAND ? GearSet.Kind.OFF_HAND : GearSet.Kind.JEWELLERY;
        }
    }

    /**
     * An item that can drop: an AdvancedCrafting recipe (armour and weapons), an MMOItems template
     * with its own stat ranges (jewellery), or a weapon another plugin builds ({@link WeaponMaker}).
     *
     * @param name a name with %material% where a prefix goes
     * @param looks the looks of a set it may take (an armour weight; a weapon's own); empty keeps the
     *              template's look
     * @param weapon  what another plugin builds: gun.TYPE or magic.ARCHETYPE
     * @param classes who can use such a weapon (for the others their template says)
     * @param runes   how many runes a mage weapon has at the least
     */
    public record Base(String id, String name, Slot slot, String recipe, String template, String pool,
            Map<String, Range> stats, List<String> looks, String weapon, List<String> classes, int runes) {
        public Base {
            if (Stream.of(recipe, template, weapon).filter(Objects::nonNull).count() != 1) {
                throw new IllegalArgumentException("item " + id + ": give a recipe, a template or a weapon (one of them)");
            }
        }

        public Base(String id, String name, Slot slot, String recipe, String template, String pool,
                Map<String, Range> stats, List<String> looks) {
            this(id, name, slot, recipe, template, pool, stats, looks, null, List.of(), 0);
        }

        /** Whether it gets bonus stats (a gun's go into its power). */
        boolean bonuses() {
            return weapon == null || !weapon.startsWith(GunLoot.PREFIX);
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
            if (b.bonuses() && !pools.containsKey(b.pool())) {
                throw new IllegalArgumentException("item " + b.id() + ": no bonus pool " + b.pool());
            }
        }
        for (GearSet s : sets.values()) {
            if (!s.id().matches("\\w+")) {
                throw new IllegalArgumentException("set " + s.id() + ": letters, digits and _ only");
            }
            if (rarity(rarities, s.rarity()) == null) {
                throw new IllegalArgumentException("set " + s.id() + ": no rarity " + s.rarity());
            }
            for (var bonuses : List.of(s.armour(), s.jewellery())) {
                bonuses.values().forEach(stats -> stats.keySet().forEach(stat -> {
                    if (!worth.containsKey(stat)) {
                        throw new IllegalArgumentException("set " + s.id() + ": no worth for " + stat);
                    }
                }));
            }
        }
    }

    public Rarity rarity(String id) {
        return rarity(rarities, id);
    }

    private static Rarity rarity(List<Rarity> rarities, String id) {
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
                        ranges(b.getConfigurationSection("stats")),
                        b.isList("look") ? b.getStringList("look") : b.isString("look") ? List.of(b.getString("look")) : List.of(),
                        b.getString("weapon"), b.getStringList("classes"), b.getInt("runes")));
            }
            Map<String, Double> worth = new LinkedHashMap<>();
            ConfigurationSection ws = section(y, "worth");
            ws.getKeys(false).forEach(k -> worth.put(k.toLowerCase(Locale.ROOT), ws.getDouble(k)));
            Map<String, GearSet> sets = new LinkedHashMap<>();
            ConfigurationSection ss = section(y, "sets");
            for (String id : ss.getKeys(false)) {
                sets.put(id, set(id, ss.getConfigurationSection(id)));
            }
            return new LootKit(standard, rarities, tiers, y.getString("socket", "Dungeon Gem"), pools, bases,
                    y.getStringList("names.prefixes"), y.getStringList("names.suffixes"), y.getString("looks", ""), sets, worth,
                    Set.copyOf(y.getStringList("unscaled").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList()));
        } catch (RuntimeException e) {
            problems.add("loot.yml: " + e.getMessage());
            return null;
        }
    }

    private static GearSet set(String id, ConfigurationSection s) {
        return new GearSet(id, s.getString("name", id), s.getString("rarity", ""), s.getStringList("dungeons"),
                bonuses(s, "armour"), bonuses(s, "jewellery"),
                new Trait(Trait.Kind.valueOf(s.getString("trait.kind", "").toUpperCase(Locale.ROOT)), s.getDouble("trait.value")),
                s.isList("gun") ? GearSet.Tints.of(id, s.getStringList("gun")) : null);
    }

    /** pieces: {stat: units}. */
    private static Map<Integer, Map<String, Double>> bonuses(ConfigurationSection s, String path) {
        Map<Integer, Map<String, Double>> out = new TreeMap<>();
        ConfigurationSection b = section(s, path);
        for (String pieces : b.getKeys(false)) {
            ConfigurationSection stats = section(b, pieces);
            Map<String, Double> units = new LinkedHashMap<>();
            for (String k : stats.getKeys(false)) {
                if (k.equalsIgnoreCase("damage")) { // whichever kind the class deals: each player has the use of one
                    GearSet.DAMAGE.forEach(d -> units.put(d, stats.getDouble(k)));
                } else {
                    units.put(k.toLowerCase(Locale.ROOT), stats.getDouble(k));
                }
            }
            out.put(Integer.parseInt(pieces), units);
        }
        return out;
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
