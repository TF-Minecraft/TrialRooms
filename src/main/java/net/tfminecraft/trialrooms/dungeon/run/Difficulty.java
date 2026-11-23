package net.tfminecraft.trialrooms.dungeon.run;

/**
 * One difficulty of a dungeon, chosen at the gate. A harder one plays at a higher tier (bigger
 * fights, harder boss mechanics, better loot) with tougher enemies, and opens once the leader has
 * won the one it needs.
 *
 * @param id     its key in recipes.yml (normal, hard, nightmare)
 * @param name   what players see
 * @param tier   the tier the run plays at, and the tier of its loot
 * @param health enemy health multiplier (on top of the party's)
 * @param damage enemy damage multiplier
 * @param needs  the difficulty the leader must have won first, or null
 * @param advice what gear it is meant for
 * @param minutes how long a proper party takes for a run at it (spoils are sized on it); 0 = the recipe's
 * @param par     its par time in minutes (a better vault within it); 0 = the recipe's
 */
public record Difficulty(String id, String name, int tier, double health, double damage, String needs, String advice,
        double minutes, double par) {

    public Difficulty(String id, String name, int tier, double health, double damage, String needs, String advice) {
        this(id, name, tier, health, damage, needs, advice, 0, 0);
    }


    public Difficulty {
        if (tier < 1 || !(health > 0) || !(damage > 0) || Double.isInfinite(health) || Double.isInfinite(damage)
                || !(minutes >= 0) || Double.isInfinite(minutes) || !(par >= 0) || Double.isInfinite(par)) {
            throw new IllegalArgumentException("difficulty " + id
                    + ": tier at least 1, health and damage above 0, minutes and par not negative");
        }
    }
}
