package net.tfminecraft.trialrooms.dungeon.loot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

import net.tfminecraft.trialrooms.dungeon.gear.GearSet;
import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Base;
import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Range;
import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Rarity;
import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Tier;

/** Rolls dungeon items: which item, how rare, its stats, name and look. Plain Java, seeded. */
public final class LootRoller {

    /**
     * A rolled item, ready to build.
     *
     * @param stats   final stat values (the base's, scaled, plus the bonus stats)
     * @param look    a skin path, or null to keep the template's look
     * @param lore    lines under the item's stats
     * @param set     the gear set it belongs to, or null
     * @param power   how strong it is (tier times rarity)
     */
    public record Plan(Base base, Rarity rarity, int tier, String name, Map<String, Double> stats, int sockets,
            String look, List<String> lore, GearSet set, double power) {
    }

    /** A rolled dungeon gem: one stat, for a dungeon socket. */
    public record GemPlan(Rarity rarity, int tier, String name, String stat, double value, List<String> lore) {
    }

    private LootRoller() {
    }

    /** A dungeon gem: one stat from a pool, scaled like items by the tier and rarity. */
    public static GemPlan gem(LootKit kit, String pool, int tier, Rarity rarity, Random rng) {
        List<Map.Entry<String, Range>> stats = new ArrayList<>(kit.pools().get(pool).entrySet());
        Map.Entry<String, Range> e = stats.get(rng.nextInt(stats.size()));
        double value = round(e.getValue().roll(rng.nextDouble()) * kit.tier(tier).power() * rarity.power());
        String name = kit.prefixes().get(rng.nextInt(kit.prefixes().size())) + " Delvestone";
        return new GemPlan(rarity, tier, name, e.getKey(), value, List.of(rarity.name() + " dungeon gem, tier " + tier,
                "Socket it into a dungeon item from the locker.", "Stays in the dungeons."));
    }

    /**
     * A rarity, by weight; each point of the tier's luck makes every step up the list that much more
     * likely (weight x (1 + luck) ^ step).
     */
    public static Rarity rarity(LootKit kit, int tier, Random rng) {
        return rarity(kit, tier, 0, rng);
    }

    /** A rarity at a tier with extra luck on top of the tier's (a run under par). */
    public static Rarity rarity(LootKit kit, int tier, double extraLuck, Random rng) {
        double luck = kit.tier(tier).luck() + extraLuck;
        List<Rarity> rs = kit.rarities();
        double[] w = new double[rs.size()];
        double sum = 0;
        for (int i = 0; i < rs.size(); i++) {
            w[i] = rs.get(i).weight() * Math.pow(1 + luck, i);
            sum += w[i];
        }
        double roll = rng.nextDouble() * sum;
        int i = 0;
        while ((roll -= w[i]) >= 0 && i < w.length - 1) {
            i++;
        }
        return rs.get(i);
    }

    /** One of the sets of a rarity that drop in a dungeon (null: those that drop anywhere); null when there is none. */
    public static GearSet set(LootKit kit, Rarity rarity, String dungeon, Random rng) {
        List<GearSet> fits = kit.sets().values().stream()
                .filter(s -> s.rarity().equalsIgnoreCase(rarity.id()) && s.drops(dungeon)).toList();
        return fits.isEmpty() ? null : fits.get(rng.nextInt(fits.size()));
    }

    /** One of the items a player can use, or null when none fits. */
    public static Base base(LootKit kit, Predicate<Base> usable, Random rng) {
        List<Base> fits = kit.bases().stream().filter(usable).toList();
        return fits.isEmpty() ? null : fits.get(rng.nextInt(fits.size()));
    }

    /**
     * Rolls an item of a base and rarity. {@code baseStats} are the base's own numbers (for a recipe,
     * what it gives made of the act's standard material); jewellery rolls its own from its ranges.
     */
    public static Plan plan(LootKit kit, Base base, Map<String, Double> baseStats, int tier, Rarity rarity,
            GearSet set, Random rng) {
        Tier t = kit.tier(tier);
        double power = t.power() * rarity.power();
        Map<String, Double> stats = new LinkedHashMap<>();
        baseStats.forEach((stat, v) -> stats.put(stat, kit.unscaled().contains(stat) ? v : round(v * power)));
        base.stats().forEach((stat, range) -> stats.merge(stat, round(range.roll(rng.nextDouble()) * power), Double::sum));
        List<Map.Entry<String, Range>> pool = new ArrayList<>(kit.pools().get(base.pool()).entrySet());
        for (int i = 0; i < rarity.bonus() && !pool.isEmpty(); i++) {
            Map.Entry<String, Range> e = pool.remove(rng.nextInt(pool.size()));
            stats.merge(e.getKey(), round(e.getValue().roll(rng.nextDouble()) * power), (a, b) -> round(a + b));
        }
        int step = kit.rarities().indexOf(rarity);
        String prefix = set != null ? set.name() : kit.prefixes().get(rng.nextInt(kit.prefixes().size()));
        String name = base.name().contains("%material%") ? base.name().replace("%material%", prefix)
                : prefix + " " + base.name();
        if (step >= 2) {
            name += " " + kit.suffixes().get(rng.nextInt(kit.suffixes().size()));
        }
        String look = null;
        if (set != null && !base.looks().isEmpty()) {
            look = kit.looks() + set.id() + "_" + base.looks().get(rng.nextInt(base.looks().size()))
                    + (base.slot().armour() ? "_" + base.slot().piece() : "");
        }
        List<String> lore = new ArrayList<>(List.of(rarity.name() + " dungeon item, tier " + tier));
        if (set != null) {
            lore.addAll(set.lore(base.slot().kind(), power, kit.worth()));
        }
        lore.add("Stays in the dungeons.");
        return new Plan(base, rarity, tier, name, stats, rarity.sockets(), look, lore, set, power);
    }

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
