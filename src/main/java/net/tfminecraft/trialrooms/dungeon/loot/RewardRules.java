package net.tfminecraft.trialrooms.dungeon.loot;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;

import net.tfminecraft.trialrooms.dungeon.run.RunRewards.Moment;

/**
 * How runs hand out loot, from loot.yml's {@code rewards:} section.
 *
 * @param chances     the chance of an item roll at each moment (1 = always)
 * @param gemChance   the share of won rolls that give a dungeon gem instead of an item
 * @param pity        added to a player's next chance after each roll that gives nothing
 * @param weeklyRuns  runs of one dungeon each character rolls items from per week; more runs give marks only
 * @param weeklyMarks most marks a character earns per week
 * @param marks       marks each moment pays
 * @param prices      marks an item of the player's choice costs, by rarity
 * @param shopTier    the tier bought items are made at
 * @param gemTemplate the MMOItems gem template dungeon gems are made on (file.id)
 * @param gemPool     the bonus pool a gem's one stat comes from
 * @param parMarks    extra marks for reaching the vault under par (inside the weekly cap)
 * @param parLuck     extra luck for the vault's roll under par (added to the tier's)
 * @param values      what finds are worth (what an early exit lets out, and what insurance costs)
 */
public record RewardRules(Map<Moment, Double> chances, double gemChance, double pity, int weeklyRuns, int weeklyMarks,
        Map<Moment, Integer> marks, Map<String, Integer> prices, int shopTier, String gemTemplate, String gemPool,
        int parMarks, double parLuck, Values values) {

    /**
     * What finds are worth: an item its rarity's value times its tier's power (so the stronger the
     * item, the more it is worth), a gem a share of that, each mark and each hour of spoils their own.
     *
     * @param rarities value of an item of each rarity at tier power 1
     * @param gem      a gem's share of an item's value
     * @param mark     value of one mark
     * @param spoils   value of an hour of spoils
     * @param insure   marks insuring a worn piece costs per point of its value
     */
    public record Values(Map<String, Integer> rarities, double gem, double mark, double spoils, double insure) {
        public static final Values NONE = new Values(Map.of(), 0, 0, 0, 0);

        public Values {
            if (rarities.values().stream().anyMatch(v -> v < 0) || !(gem >= 0) || !(mark >= 0) || !(spoils >= 0)
                    || !(insure >= 0) || Double.isInfinite(gem + mark + spoils + insure)) {
                throw new IllegalArgumentException("rewards value: numbers can't be negative");
            }
        }

        /** An item's value: its rarity's, times its tier's power (a share of it for a gem). */
        public int item(String rarity, double power, boolean gem) {
            return (int) Math.round(rarities.getOrDefault(rarity, 0) * power * (gem ? this.gem : 1));
        }
    }

    public RewardRules(Map<Moment, Double> chances, double gemChance, double pity, int weeklyRuns, int weeklyMarks,
            Map<Moment, Integer> marks, Map<String, Integer> prices, int shopTier, String gemTemplate, String gemPool,
            int parMarks, double parLuck) {
        this(chances, gemChance, pity, weeklyRuns, weeklyMarks, marks, prices, shopTier, gemTemplate, gemPool, parMarks,
                parLuck, Values.NONE);
    }

    public RewardRules(Map<Moment, Double> chances, double gemChance, double pity, int weeklyRuns, int weeklyMarks,
            Map<Moment, Integer> marks, Map<String, Integer> prices, int shopTier, String gemTemplate, String gemPool) {
        this(chances, gemChance, pity, weeklyRuns, weeklyMarks, marks, prices, shopTier, gemTemplate, gemPool, 0, 0);
    }

    public RewardRules {
        for (double c : chances.values()) {
            if (!(c >= 0 && c <= 1)) {
                throw new IllegalArgumentException("rewards: chances run from 0 to 1");
            }
        }
        if (!(gemChance >= 0 && gemChance <= 1) || !(pity >= 0) || weeklyRuns < 0 || weeklyMarks < 0 || shopTier < 1
                || parMarks < 0 || !(parLuck >= 0) || Double.isInfinite(parLuck)
                || marks.values().stream().anyMatch(m -> m < 0) || prices.values().stream().anyMatch(p -> p <= 0)) {
            throw new IllegalArgumentException("rewards: numbers out of range");
        }
    }

    public double chance(Moment m) {
        return chances.getOrDefault(m, 0.0);
    }

    public int marks(Moment m) {
        return marks.getOrDefault(m, 0);
    }

    /** Reads the rewards section; null (with a problem) when it is missing or wrong. */
    public static RewardRules load(ConfigurationSection y, LootKit kit, List<String> problems) {
        ConfigurationSection r = y.getConfigurationSection("rewards");
        if (r == null || kit == null) {
            if (kit != null) {
                problems.add("loot.yml: no rewards section, so runs drop nothing");
            }
            return null;
        }
        try {
            Map<Moment, Double> chances = new EnumMap<>(Moment.class);
            Map<Moment, Integer> marks = new EnumMap<>(Moment.class);
            for (Moment m : Moment.values()) {
                String key = m.name().toLowerCase(Locale.ROOT);
                chances.put(m, r.getDouble("chance." + key, 0));
                marks.put(m, r.getInt("marks." + key, 0));
            }
            Map<String, Integer> prices = new LinkedHashMap<>();
            ConfigurationSection ps = r.getConfigurationSection("prices");
            if (ps != null) {
                for (String rarity : ps.getKeys(false)) {
                    if (kit.rarity(rarity) == null) {
                        throw new IllegalArgumentException("prices: no rarity " + rarity);
                    }
                    prices.put(rarity, ps.getInt(rarity));
                }
            }
            String pool = r.getString("gems.pool", "jewellery");
            if (kit.pools().getOrDefault(pool, Map.of()).isEmpty()) {
                throw new IllegalArgumentException("gems: no bonus pool " + pool + " with stats in it");
            }
            Map<String, Integer> values = new LinkedHashMap<>();
            ConfigurationSection vs = r.getConfigurationSection("value");
            if (vs != null) {
                for (LootKit.Rarity rarity : kit.rarities()) {
                    values.put(rarity.id(), vs.getInt(rarity.id(), 0));
                }
            }
            RewardRules rules = new RewardRules(chances, r.getDouble("gem-chance", 0), r.getDouble("pity", 0),
                    r.getInt("weekly.runs", 3), r.getInt("weekly.marks", 100), marks, prices, r.getInt("shop-tier", 1),
                    r.getString("gems.template", "gemstones.larimar"), pool, r.getInt("par.marks", 0),
                    r.getDouble("par.luck", 0), new Values(values, r.getDouble("value.gem", 0.5),
                            r.getDouble("value.mark", 1), r.getDouble("value.spoils-hour", 0), r.getDouble("insure", 0)));
            if (vs == null) {
                problems.add("loot.yml rewards: no value section: finds count 1 each in ways out");
            }
            if (!r.contains("insure")) {
                problems.add("loot.yml rewards: no insure: insuring a worn piece costs 1 mark");
            }
            return rules;
        } catch (RuntimeException e) {
            problems.add("loot.yml rewards: " + e.getMessage());
            return null;
        }
    }
}
