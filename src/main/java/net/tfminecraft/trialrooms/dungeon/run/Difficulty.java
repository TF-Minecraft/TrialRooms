package net.tfminecraft.trialrooms.dungeon.run;

import java.util.ArrayList;
import java.util.List;

/**
 * One difficulty of a dungeon, chosen at the gate. A harder one plays at a higher tier (bigger
 * fights, harder boss mechanics, better loot) with tougher enemies, and opens once the leader has
 * won the one it needs. Its loot rules say how much of what players find comes home.
 *
 * @param id         its key in recipes.yml (normal, hard, nightmare, mythic)
 * @param name       what players see
 * @param tier       the tier the run plays at, and the tier of its loot
 * @param health     enemy health multiplier (on top of the party's)
 * @param damage     enemy damage multiplier
 * @param needs      the difficulty the leader must have won first, or null
 * @param advice     what gear it is meant for
 * @param minutes    how long a proper party takes for a run at it (spoils are sized on it); 0 = the recipe's
 * @param par        its par time in minutes (a better vault within it); 0 = the recipe's
 * @param banking    how finds are banked and kept (null: they are paid at once, as found)
 * @param gearAtRisk whether a player who falls loses the dungeon gear they wear
 * @param boost      what item chances and marks are multiplied by (for the risk)
 */
public record Difficulty(String id, String name, int tier, double health, double damage, String needs, String advice,
        double minutes, double par, Banking banking, boolean gearAtRisk, double boost) {

    /**
     * How a difficulty banks finds. What players find is carried; a save point banks what is
     * carried; falling loses what is carried. If the boss falls everything comes home; if the run
     * fails, each banked find comes home with the keep chance and carried finds are lost.
     *
     * @param every    a save point after every this many cleared fight rooms (0 = none)
     * @param rest     whether reaching the rest room is a save point
     * @param keep     the chance each banked find comes home when the run fails (0 to 1)
     * @param treasure whether reaching a treasure room (a detour on a branch) is a save point
     * @param exit     the chance each treasure room holds a way out: a player may leave there with
     *                 finds worth up to half of what they would lose (or their best one)
     */
    public record Banking(int every, boolean rest, double keep, boolean treasure, double exit) {
        public Banking {
            if (every < 0 || !(keep >= 0 && keep <= 1) || !(exit >= 0 && exit <= 1)) {
                throw new IllegalArgumentException("bank: every not negative, keep and exit between 0 and 1");
            }
        }

        public Banking(int every, boolean rest, double keep) {
            this(every, rest, keep, false, 0);
        }
    }

    public Difficulty(String id, String name, int tier, double health, double damage, String needs, String advice) {
        this(id, name, tier, health, damage, needs, advice, 0, 0);
    }

    public Difficulty(String id, String name, int tier, double health, double damage, String needs, String advice,
            double minutes, double par) {
        this(id, name, tier, health, damage, needs, advice, minutes, par, null, false, 1);
    }

    public Difficulty {
        if (tier < 1 || !(health > 0) || !(damage > 0) || Double.isInfinite(health) || Double.isInfinite(damage)
                || !(minutes >= 0) || Double.isInfinite(minutes) || !(par >= 0) || Double.isInfinite(par)
                || !(boost >= 1) || Double.isInfinite(boost)) {
            throw new IllegalArgumentException("difficulty " + id
                    + ": tier at least 1, health and damage above 0, minutes and par not negative, boost at least 1");
        }
    }

    /** Its loot rules, as players read them (on the gate's screen and at the start of a run). */
    public List<String> lootRules() {
        List<String> out = new ArrayList<>();
        if (banking != null) {
            List<String> at = new ArrayList<>();
            if (banking.every() > 0) {
                at.add("every " + banking.every() + " cleared fight rooms");
            }
            if (banking.treasure()) {
                at.add("in treasure rooms");
            }
            if (banking.rest()) {
                at.add("at the rest room");
            }
            if (at.isEmpty()) {
                out.add("What you find comes home only if the boss falls.");
            } else {
                String last = at.remove(at.size() - 1);
                out.add("What you find is banked " + (at.isEmpty() ? last : String.join(", ", at) + " and " + last) + ".");
                out.add(banking.keep() >= 1 ? "If the run fails, you keep all you banked."
                        : banking.keep() <= 0 ? "If the run fails, what you banked is lost too."
                        : "If the run fails, each banked find has a " + Math.round(banking.keep() * 100)
                                + "% chance to come home.");
            }
            out.add("Fall, and what you carry is lost.");
            if (banking.exit() > 0) {
                out.add("Some treasure rooms hold a way out: leave alone, with finds worth up to half of what you"
                        + " would lose.");
            }
        }
        if (gearAtRisk) {
            out.add("Your worn dungeon gear is at stake: fall, and it is destroyed.");
        }
        if (boost > 1) {
            out.add("Item chances and marks x" + (boost == Math.floor(boost) ? String.valueOf((int) boost) : String.valueOf(boost))
                    + " for the risk.");
        }
        return out;
    }
}
