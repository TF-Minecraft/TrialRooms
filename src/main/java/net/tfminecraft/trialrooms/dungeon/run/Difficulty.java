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
 */
public record Difficulty(String id, String name, int tier, double health, double damage, String needs, String advice) {

    public Difficulty {
        if (tier < 1 || !(health > 0) || !(damage > 0) || Double.isInfinite(health) || Double.isInfinite(damage)) {
            throw new IllegalArgumentException("difficulty " + id + ": tier at least 1, health and damage above 0");
        }
    }
}
