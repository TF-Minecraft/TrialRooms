package net.tfminecraft.trialrooms.dungeon.fight;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.tfminecraft.trialrooms.dungeon.template.RoomType;

/**
 * Puts a room's fight together from its threat budget: a pack shape filled from the roster,
 * maybe an elite, split into waves, each enemy given a spawn marker. The same seed always gives
 * the same fight. A bigger party brings a bigger budget (the recipe's party factor).
 */
public final class Director {

    private Director() {
    }

    /**
     * One enemy to spawn.
     *
     * @param entry  the roster entry
     * @param marker index into the room's spawn markers, or -1 when the room has none
     * @param reuse  how many enemies of the wave already use that marker (0 = the marker itself)
     * @param affix  the elite's affix, or null for a normal enemy
     */
    public record Spawn(RosterEntry entry, int marker, int reuse, Affix affix) {
        public boolean elite() {
            return affix != null;
        }
    }

    /**
     * A planned fight.
     *
     * @param budget the threat budget it was built from
     * @param pack   the pack shape used ("filler" when none fit)
     * @param waves  enemies per wave, first wave first
     */
    public record Plan(double budget, String pack, List<List<Spawn>> waves) {
        public Plan {
            List<List<Spawn>> copy = new ArrayList<>();
            for (List<Spawn> w : waves) {
                copy.add(List.copyOf(w));
            }
            waves = List.copyOf(copy);
        }

        public int enemies() {
            int n = 0;
            for (List<Spawn> w : waves) {
                n += w.size();
            }
            return n;
        }
    }

    /** The threat budget of a fight room. */
    public static double budget(FightTuning t, RoomType type, int depth, int tier) {
        double base = type == RoomType.ELITE ? t.budgetElite() : t.budgetCombat();
        return base * t.tierFactor(tier) * (1 + t.depthStep() * Math.max(0, depth));
    }

    /** Plans a combat or elite room for one player. */
    public static Plan plan(FightKit kit, RoomType type, int depth, int tier, List<String> markerTags, Random rng) {
        return plan(kit, type, depth, tier, 1, markerTags, rng);
    }

    /**
     * Plans a combat or elite room.
     *
     * @param depth      fight rooms the party has cleared
     * @param party      budget multiplier for the party's size (1 for one player)
     * @param markerTags tags of the room's spawn markers, in order
     */
    public static Plan plan(FightKit kit, RoomType type, int depth, int tier, double party, List<String> markerTags,
            Random rng) {
        FightTuning t = kit.tuning();
        double budget = budget(t, type, depth, tier) * party;
        double[] left = { budget };
        List<RosterEntry> picks = new ArrayList<>();
        List<Affix> affixes = new ArrayList<>();
        if (type == RoomType.ELITE) {
            RosterEntry elite = pick(kit, Role.ELITE, left[0], rng);
            if (elite == null) {
                elite = strongest(kit, t.eliteMinCost(), left[0]);
            }
            if (elite != null) {
                add(picks, affixes, elite, randomAffix(rng), left);
            }
        }
        PackTemplate pack = pack(kit, depth, left[0], rng);
        if (pack != null) {
            for (Role role : pack.slots()) {
                RosterEntry e = pick(kit, role, left[0], rng);
                if (e != null) {
                    add(picks, affixes, e, null, left);
                }
            }
        }
        fill(kit, picks, affixes, left, rng, t.maxAlive() * 3);
        capRanged(kit, picks, affixes, left, rng, t.rangedShare());
        if (type != RoomType.ELITE && depth >= t.eliteMinDepth() && rng.nextDouble() < t.eliteChance()) {
            promote(picks, affixes, t.eliteMinCost(), rng);
        }
        return new Plan(budget, pack == null ? "filler" : pack.id(), waves(t, budget, picks, affixes, markerTags));
    }

    /** Adds for a boss: one group of normal enemies within a budget, on the room's markers. */
    public static List<Spawn> adds(FightKit kit, double budget, List<String> markerTags, Random rng) {
        FightTuning t = kit.tuning();
        double[] left = { budget };
        List<RosterEntry> picks = new ArrayList<>();
        List<Affix> affixes = new ArrayList<>();
        fill(kit, picks, affixes, left, rng, t.maxAlive());
        capRanged(kit, picks, affixes, left, rng, t.rangedShare());
        return assign(picks, affixes, markerTags);
    }

    // ------------------------------------------------------------------ picking

    /** A weighted roster pick of a role that fits the budget left, or null. */
    static RosterEntry pick(FightKit kit, Role role, double left, Random rng) {
        List<RosterEntry> fits = new ArrayList<>();
        int total = 0;
        for (RosterEntry e : kit.ofRole(role)) {
            if (e.cost() <= left + 1e-9) {
                fits.add(e);
                total += e.weight();
            }
        }
        if (fits.isEmpty()) {
            return null;
        }
        int roll = rng.nextInt(total);
        for (int i = 0; i < fits.size() - 1; i++) {
            roll -= fits.get(i).weight();
            if (roll < 0) {
                return fits.get(i);
            }
        }
        return fits.getLast();
    }

    /** The costliest roster entry at or above a cost that fits the budget, or null. */
    static RosterEntry strongest(FightKit kit, double minCost, double left) {
        RosterEntry best = null;
        for (RosterEntry e : kit.roster().values()) {
            if (e.cost() >= minCost && e.cost() <= left + 1e-9 && (best == null || e.cost() > best.cost())) {
                best = e;
            }
        }
        return best;
    }

    /** A weighted pick among the pack shapes allowed at this depth whose cheapest fill fits, or null. */
    static PackTemplate pack(FightKit kit, int depth, double left, Random rng) {
        List<PackTemplate> fits = new ArrayList<>();
        int total = 0;
        for (PackTemplate p : kit.packs()) {
            if (p.minDepth() <= depth && cheapest(kit, p) <= left + 1e-9) {
                fits.add(p);
                total += p.weight();
            }
        }
        if (fits.isEmpty()) {
            return null;
        }
        int roll = rng.nextInt(total);
        for (int i = 0; i < fits.size() - 1; i++) {
            roll -= fits.get(i).weight();
            if (roll < 0) {
                return fits.get(i);
            }
        }
        return fits.getLast();
    }

    /** The cheapest a pack can be filled for; infinite when the roster lacks one of its roles. */
    static double cheapest(FightKit kit, PackTemplate p) {
        double sum = 0;
        for (Role r : p.slots()) {
            double min = Double.POSITIVE_INFINITY;
            for (RosterEntry e : kit.ofRole(r)) {
                min = Math.min(min, e.cost());
            }
            sum += min;
        }
        return sum;
    }

    private static void add(List<RosterEntry> picks, List<Affix> affixes, RosterEntry e, Affix affix, double[] left) {
        picks.add(e);
        affixes.add(affix);
        left[0] -= e.cost();
    }

    /** Spends what is left of the budget on grunts and ranged enemies (two to one). */
    private static void fill(FightKit kit, List<RosterEntry> picks, List<Affix> affixes, double[] left, Random rng,
            int most) {
        while (picks.size() < most) {
            Role first = rng.nextInt(3) == 2 ? Role.RANGED : Role.GRUNT;
            RosterEntry e = pick(kit, first, left[0], rng);
            if (e == null) {
                e = pick(kit, first == Role.GRUNT ? Role.RANGED : Role.GRUNT, left[0], rng);
            }
            if (e == null) {
                e = cheapestNonElite(kit, left[0]);
            }
            if (e == null) {
                return;
            }
            add(picks, affixes, e, null, left);
        }
    }

    private static RosterEntry cheapestNonElite(FightKit kit, double left) {
        RosterEntry best = null;
        for (RosterEntry e : kit.roster().values()) {
            if (e.role() != Role.ELITE && e.cost() <= left + 1e-9 && (best == null || e.cost() < best.cost())) {
                best = e;
            }
        }
        return best;
    }

    /**
     * Keeps ranged and caster enemies to their share (at least one is always allowed once there are
     * two enemies): the extra ones, last first, become grunts when the budget allows.
     */
    static void capRanged(FightKit kit, List<RosterEntry> picks, List<Affix> affixes, double[] left,
            Random rng, double share) {
        int n = picks.size();
        int allowed = n <= 1 ? n : Math.max(1, (int) Math.floor(share * n));
        int ranged = 0;
        for (RosterEntry e : picks) {
            ranged += e.role().ranged() ? 1 : 0;
        }
        for (int i = picks.size() - 1; i >= 0 && ranged > allowed; i--) {
            RosterEntry e = picks.get(i);
            if (!e.role().ranged() || affixes.get(i) != null) {
                continue;
            }
            ranged--;
            left[0] += e.cost();
            RosterEntry grunt = pick(kit, Role.GRUNT, left[0], rng);
            if (grunt == null) {
                picks.remove(i);
                affixes.remove(i);
            } else {
                picks.set(i, grunt);
                left[0] -= grunt.cost();
            }
        }
    }

    /** Makes the costliest normal enemy (at or above the minimum cost) an elite. */
    static void promote(List<RosterEntry> picks, List<Affix> affixes, double minCost, Random rng) {
        int best = -1;
        for (int i = 0; i < picks.size(); i++) {
            RosterEntry e = picks.get(i);
            if (affixes.get(i) == null && e.cost() >= minCost && (best < 0 || e.cost() > picks.get(best).cost())) {
                best = i;
            }
        }
        if (best >= 0) {
            affixes.set(best, randomAffix(rng));
        }
    }

    private static Affix randomAffix(Random rng) {
        Affix[] all = Affix.values();
        return all[rng.nextInt(all.length)];
    }

    // ------------------------------------------------------------------ waves and markers

    /** Splits the enemies into waves (elites in the first), none bigger than the cap. */
    static List<List<Spawn>> waves(FightTuning t, double budget, List<RosterEntry> picks, List<Affix> affixes,
            List<String> markerTags) {
        List<RosterEntry> order = new ArrayList<>();
        List<Affix> orderAffix = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < picks.size(); i++) {
                if ((affixes.get(i) != null) == (pass == 0)) {
                    order.add(picks.get(i));
                    orderAffix.add(affixes.get(i));
                }
            }
        }
        double[] shares = budget >= t.thirdWave() ? new double[] { 0.45, 0.35, 0.2 }
                : budget >= t.waveSplit() ? new double[] { 0.6, 0.4 } : new double[] { 1 };
        List<List<Spawn>> out = new ArrayList<>();
        int count = Math.min(shares.length, order.size());
        int from = 0;
        for (int w = 0; w < count; w++) {
            int to = w == count - 1 ? order.size()
                    : Math.min(order.size() - (count - 1 - w),
                            from + Math.max(1, (int) Math.round(shares[w] * order.size())));
            for (int start = from; start < to; start += t.maxAlive()) {
                int end = Math.min(to, start + t.maxAlive());
                out.add(assign(order.subList(start, end), orderAffix.subList(start, end), markerTags));
            }
            from = to;
        }
        return out;
    }

    /**
     * Gives each enemy a spawn marker: its role's preferred tag first, then any other marker, and
     * only then a marker already used in this wave. Boss markers are left for the boss unless the
     * room has nothing else.
     */
    static List<Spawn> assign(List<RosterEntry> picks, List<Affix> affixes, List<String> markerTags) {
        List<Integer> usable = new ArrayList<>();
        for (int i = 0; i < markerTags.size(); i++) {
            if (!"boss".equals(markerTags.get(i))) {
                usable.add(i);
            }
        }
        if (usable.isEmpty()) {
            for (int i = 0; i < markerTags.size(); i++) {
                usable.add(i);
            }
        }
        int[] used = new int[markerTags.size()];
        List<Spawn> out = new ArrayList<>();
        for (int k = 0; k < picks.size(); k++) {
            RosterEntry e = picks.get(k);
            // The least used marker; among those, one with the role's tag; then the first.
            String tag = e.role().markerTag();
            int marker = -1;
            for (int i : usable) {
                if (marker < 0 || used[i] < used[marker]
                        || used[i] == used[marker] && tag.equals(markerTags.get(i)) && !tag.equals(markerTags.get(marker))) {
                    marker = i;
                }
            }
            int reuse = marker < 0 ? 0 : used[marker]++;
            out.add(new Spawn(e, marker, reuse, affixes.get(k)));
        }
        return out;
    }
}
