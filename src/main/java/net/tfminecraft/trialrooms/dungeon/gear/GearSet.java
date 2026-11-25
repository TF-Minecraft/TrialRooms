package net.tfminecraft.trialrooms.dungeon.gear;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A set of dungeon items: armour with bonuses at 2 and 4 pieces, jewellery with its own at 2 and 4,
 * and weapons that stay outside the count and carry the set's trait instead.
 *
 * @param rarity    the rarity its items come at
 * @param dungeons  the dungeons (recipe ids) it drops in; empty: any
 * @param armour    armour pieces to the stats they give (in bonus units: worth times power)
 * @param jewellery jewellery pieces to the stats they give
 * @param tints     the colours of its gun parts, or null for plain ones
 */
public record GearSet(String id, String name, String rarity, List<String> dungeons,
        Map<Integer, Map<String, Double>> armour, Map<Integer, Map<String, Double>> jewellery, Trait trait, Tints tints) {

    /** The damage stats a "damage" bonus raises together. */
    public static final List<String> DAMAGE = List.of("physical_damage", "projectile_damage", "magic_damage");

    /** What a piece counts as. */
    public enum Kind {
        ARMOUR, JEWELLERY, WEAPON, OFF_HAND
    }

    /** What colour a set's metal, wood and fittings are on a gun. */
    public record Tints(int metal, int wood, int fittings) {

        /** [metal, wood, fittings], each #rrggbb. */
        public static Tints of(String set, List<String> hex) {
            if (hex.size() != 3 || !hex.stream().allMatch(h -> h.matches("#[0-9a-fA-F]{6}"))) {
                throw new IllegalArgumentException("set " + set + ": gun needs three #rrggbb colours");
            }
            return new Tints(rgb(hex.get(0)), rgb(hex.get(1)), rgb(hex.get(2)));
        }

        private static int rgb(String hex) {
            return Integer.parseInt(hex.substring(1), 16);
        }

        /** The colour of a gun's slot. */
        public int of(String slot) {
            return switch (slot) {
                case "stock" -> wood;
                case "action", "loader" -> fittings;
                default -> metal;
            };
        }
    }

    /** A set whose gun parts are plain. */
    public GearSet(String id, String name, String rarity, List<String> dungeons, Map<Integer, Map<String, Double>> armour,
            Map<Integer, Map<String, Double>> jewellery, Trait trait) {
        this(id, name, rarity, dungeons, armour, jewellery, trait, null);
    }

    public GearSet {
        dungeons = List.copyOf(dungeons);
        armour = new TreeMap<>(armour);
        jewellery = new TreeMap<>(jewellery);
    }

    /** Whether it drops in a dungeon (null: only the sets that drop anywhere). */
    public boolean drops(String dungeon) {
        return dungeons.isEmpty() || dungeon != null && dungeons.contains(dungeon);
    }

    /** The bonuses of a kind that is counted (armour or jewellery). */
    public Map<Integer, Map<String, Double>> bonuses(Kind kind) {
        return kind == Kind.ARMOUR ? armour : kind == Kind.JEWELLERY ? jewellery : Map.of();
    }

    /** The lines an item of this set carries, with its numbers at the item's power. */
    public List<String> lore(Kind kind, double power, Map<String, Double> worth) {
        return lore(kind, power, worth, 1);
    }

    /** With a weapon's share of the trait. */
    public List<String> lore(Kind kind, double power, Map<String, Double> worth, double share) {
        List<String> out = new ArrayList<>();
        switch (kind) {
            case WEAPON -> {
                out.add(name + " weapon: " + trait.text(share) + ".");
                out.add("Stronger with 2 pieces of " + name + " armour.");
            }
            case OFF_HAND -> out.add(name + " off hand: " + trait.text(Trait.OFF_HAND) + ".");
            default -> {
                out.add(name + (kind == Kind.ARMOUR ? " armour set" : " jewellery set"));
                bonuses(kind).forEach((pieces, stats) -> out.add(pieces + " pieces: " + text(stats, power, worth)));
            }
        }
        return out;
    }

    /** "+1.5 Max Health, +4 Armor". */
    static String text(Map<String, Double> stats, double power, Map<String, Double> worth) {
        List<String> parts = new ArrayList<>();
        boolean all = stats.keySet().containsAll(DAMAGE);
        stats.forEach((stat, units) -> {
            if (all && DAMAGE.contains(stat)) {
                if (stat.equals(DAMAGE.getFirst())) {
                    parts.add("+" + Trait.trim(amount(stat, units, power, worth)) + "% Damage");
                }
                return;
            }
            parts.add("+" + Trait.trim(amount(stat, units, power, worth)) + " " + title(stat));
        });
        return String.join(", ", parts);
    }

    /** What units of a stat come to. */
    public static double amount(String stat, double units, double power, Map<String, Double> worth) {
        return units * worth.get(stat) * power;
    }

    static String title(String stat) {
        StringBuilder b = new StringBuilder();
        for (String w : stat.split("_")) {
            b.append(b.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }
}
