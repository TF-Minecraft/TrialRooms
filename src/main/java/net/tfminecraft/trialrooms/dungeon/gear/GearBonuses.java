package net.tfminecraft.trialrooms.dungeon.gear;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.tfminecraft.trialrooms.dungeon.gear.GearSet.Kind;
import net.tfminecraft.trialrooms.dungeon.gear.GearTags.Piece;

/**
 * What the dungeon gear someone wears gives together: the stats of their set bonuses, and the traits
 * of what they hold. Plain Java.
 */
public final class GearBonuses {

    /** A trait in effect and how strong it is (1: as written). */
    public record Active(Trait trait, double strength) {

        public double value() {
            return trait.value() * strength;
        }
    }

    /**
     * @param stats  stat to what the set bonuses add
     * @param traits the traits of the weapon held and the off hand
     * @param lines  what players read (/tr dungeon sets)
     */
    public record Result(Map<String, Double> stats, List<Active> traits, List<String> lines) {

        public static final Result NONE = new Result(Map.of(), List.of(), List.of());

        /** How strong a kind of trait is in all (0: none). */
        public double value(Trait.Kind kind) {
            return traits.stream().filter(a -> a.trait().kind() == kind).mapToDouble(Active::value).sum();
        }
    }

    private GearBonuses() {
    }

    /**
     * @param pieces what is worn and held (the weapon in hand, not the ones in the bag)
     * @param sets   set id to set
     * @param worth  what one bonus unit of each stat is
     */
    public static Result of(List<Piece> pieces, Map<String, GearSet> sets, Map<String, Double> worth) {
        Map<String, Double> stats = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        Map<String, List<Piece>> armour = group(pieces, Kind.ARMOUR, sets);
        count(armour, Kind.ARMOUR, sets, worth, stats, lines);
        count(group(pieces, Kind.JEWELLERY, sets), Kind.JEWELLERY, sets, worth, stats, lines);
        List<Active> traits = new ArrayList<>();
        for (Piece p : pieces) {
            GearSet set = sets.get(p.set());
            if (set == null || p.kind() != Kind.WEAPON && p.kind() != Kind.OFF_HAND) {
                continue;
            }
            boolean resonates = armour.getOrDefault(p.set(), List.of()).size() >= 2;
            double strength = p.share() * (p.kind() == Kind.OFF_HAND ? Trait.OFF_HAND : 1) * (resonates ? Trait.RESONANCE : 1);
            traits.add(new Active(set.trait(), strength));
            lines.add(set.name() + (p.kind() == Kind.OFF_HAND ? " off hand" : " weapon") + (resonates ? " (resonating)" : "")
                    + ": " + set.trait().text(strength));
        }
        return new Result(stats, traits, lines);
    }

    private static Map<String, List<Piece>> group(List<Piece> pieces, Kind kind, Map<String, GearSet> sets) {
        Map<String, List<Piece>> out = new LinkedHashMap<>();
        pieces.stream().filter(p -> p.kind() == kind && sets.containsKey(p.set()))
                .forEach(p -> out.computeIfAbsent(p.set(), s -> new ArrayList<>()).add(p));
        return out;
    }

    private static void count(Map<String, List<Piece>> bySet, Kind kind, Map<String, GearSet> sets,
            Map<String, Double> worth, Map<String, Double> stats, List<String> lines) {
        bySet.forEach((id, worn) -> {
            GearSet set = sets.get(id);
            // the pieces' own strength decides the bonus's: an early piece never gives a late piece's numbers
            double power = worn.stream().mapToDouble(Piece::power).average().orElseThrow();
            lines.add(set.name() + (kind == Kind.ARMOUR ? " armour" : " jewellery") + ": " + worn.size() + " of 4");
            set.bonuses(kind).forEach((pieces, bonus) -> {
                if (worn.size() < pieces) {
                    return;
                }
                bonus.forEach((stat, units) -> stats.merge(stat, GearSet.amount(stat, units, power, worth), Double::sum));
                lines.add("  " + pieces + " pieces: " + GearSet.text(bonus, power, worth));
            });
        });
    }
}
