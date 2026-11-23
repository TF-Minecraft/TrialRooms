package net.tfminecraft.trialrooms.dungeon.run;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.bukkit.configuration.ConfigurationSection;

/**
 * The weekly affixes, from settings.yml's {@code affixes:} section. Each ISO week the pool is
 * shuffled the same way for everyone; a run gets the first affixes of that order, as many as its
 * tier asks for (Hard one, Nightmare two: Nightmare always has Hard's affix and one more).
 *
 * @param pool       the affixes that rotate
 * @param byTier     how many affixes a run of each tier gets (tiers not listed get none)
 * @param fortified  ordinary enemies' health multiplier
 * @param tyrantHealth bosses' health multiplier
 * @param tyrantDamage bosses' damage multiplier
 * @param rageBelow  the share of health under which an enemy rages
 * @param rageDamage a raging enemy's damage multiplier
 * @param bolster    damage each nearby death adds to an enemy, as a fraction
 * @param bolsterRadius blocks within which a death bolsters the others
 */
public record Affixes(List<WeeklyAffix> pool, Map<Integer, Integer> byTier, double fortified, double tyrantHealth,
        double tyrantDamage, double rageBelow, double rageDamage, double bolster, double bolsterRadius) {

    public Affixes {
        pool = List.copyOf(pool);
        byTier = Map.copyOf(byTier);
        if (!(fortified >= 1) || !(tyrantHealth >= 1) || !(tyrantDamage >= 1) || !(rageBelow > 0 && rageBelow < 1)
                || !(rageDamage >= 1) || !(bolster >= 0) || !(bolsterRadius > 0) || Double.isInfinite(fortified)
                || Double.isInfinite(tyrantHealth) || Double.isInfinite(tyrantDamage) || Double.isInfinite(rageDamage)
                || Double.isInfinite(bolster) || Double.isInfinite(bolsterRadius)) {
            throw new IllegalArgumentException("affixes: multipliers of 1 or more, rage-below between 0 and 1");
        }
        int size = pool.size();
        if (byTier.values().stream().anyMatch(n -> n < 0 || n > size)) {
            throw new IllegalArgumentException("affixes: a tier can't have more affixes than the pool");
        }
    }

    /** The ISO week a moment falls in, as 2026-W47. */
    public static String week(long millis, ZoneId zone) {
        LocalDate d = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
        return d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    /** This week's affixes for a run of a tier. */
    public List<WeeklyAffix> of(String week, int tier) {
        List<WeeklyAffix> order = new ArrayList<>(pool);
        Collections.shuffle(order, new Random(week.hashCode()));
        return List.copyOf(order.subList(0, byTier.getOrDefault(tier, 0)));
    }

    /** Reads settings.yml's affixes section; null (with a problem) when it is wrong, null alone when there is none. */
    public static Affixes load(ConfigurationSection y, List<String> problems) {
        ConfigurationSection s = y.getConfigurationSection("affixes");
        if (s == null) {
            return null;
        }
        try {
            List<WeeklyAffix> pool = new ArrayList<>();
            for (String a : s.getStringList("pool")) {
                pool.add(WeeklyAffix.valueOf(a.trim().toUpperCase(Locale.ROOT)));
            }
            Map<Integer, Integer> byTier = new TreeMap<>();
            ConfigurationSection t = s.getConfigurationSection("per-tier");
            if (t != null) {
                for (String k : t.getKeys(false)) {
                    byTier.put(Integer.parseInt(k), t.getInt(k));
                }
            }
            return new Affixes(pool, byTier, s.getDouble("fortified", 1.25), s.getDouble("tyrannical.health", 1.3),
                    s.getDouble("tyrannical.damage", 1.15), s.getDouble("raging.below", 0.3),
                    s.getDouble("raging.damage", 1.25), s.getDouble("bolstering.damage", 0.1),
                    s.getDouble("bolstering.radius", 12));
        } catch (RuntimeException e) {
            problems.add("settings.yml affixes: " + e.getMessage() + ", so runs have no weekly affixes");
            return null;
        }
    }
}
