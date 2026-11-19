package net.tfminecraft.trialrooms.dungeon.run;

/**
 * How a dungeon type is played, from its recipe.
 *
 * @param minPlayers         fewest players a gate lets in
 * @param maxPlayers         most players a gate lets in
 * @param keyPath            TLibs item path of the key a gate takes; blank = no key
 * @param lives              shared lives: each bleed-out spends one, then players are out
 * @param mobLevel           MythicMobs level for the run's mobs
 * @param healthPerExtraPlayer extra mob health per player beyond the first, as a fraction (0.5 = +50%)
 * @param tier               difficulty tier (1 and up): bigger fights and harder mechanics
 * @param boss               the kit boss this dungeon ends with; blank for the kit's first
 */
public record RunRules(int minPlayers, int maxPlayers, String keyPath, int lives, int mobLevel,
        double healthPerExtraPlayer, int tier, String boss) {

    public RunRules(int minPlayers, int maxPlayers, String keyPath, int lives, int mobLevel,
            double healthPerExtraPlayer) {
        this(minPlayers, maxPlayers, keyPath, lives, mobLevel, healthPerExtraPlayer, 1, "");
    }

    public RunRules {
        if (minPlayers < 1 || maxPlayers < minPlayers) {
            throw new IllegalArgumentException("bad players range " + minPlayers + "-" + maxPlayers);
        }
        if (lives < 0 || mobLevel < 1 || healthPerExtraPlayer < 0) {
            throw new IllegalArgumentException("lives, mob-level and health-per-extra-player must not be negative"
                    + " (mob-level at least 1)");
        }
        if (tier < 1) {
            throw new IllegalArgumentException("tier must be at least 1");
        }
        keyPath = keyPath == null ? "" : keyPath.trim();
        boss = boss == null ? "" : boss.trim();
    }

    /** Mob health multiplier for a party of {@code players}. */
    public double healthFactor(int players) {
        return 1.0 + healthPerExtraPlayer * Math.max(0, players - 1);
    }
}
