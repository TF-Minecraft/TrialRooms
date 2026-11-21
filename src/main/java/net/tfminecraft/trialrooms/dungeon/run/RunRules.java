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
 * @param enemiesPerExtraPlayer extra fight budget (more enemies) per player beyond the first, as a fraction
 * @param eventChance        chance that a plain fight room gets one of the kit's room events (0 to 1)
 * @param maxEvents          the most room events in one dungeon
 */
public record RunRules(int minPlayers, int maxPlayers, String keyPath, int lives, int mobLevel,
        double healthPerExtraPlayer, int tier, String boss, double enemiesPerExtraPlayer, double eventChance,
        int maxEvents) {

    public RunRules(int minPlayers, int maxPlayers, String keyPath, int lives, int mobLevel,
            double healthPerExtraPlayer, int tier, String boss, double enemiesPerExtraPlayer) {
        this(minPlayers, maxPlayers, keyPath, lives, mobLevel, healthPerExtraPlayer, tier, boss, enemiesPerExtraPlayer,
                0, 0);
    }

    public RunRules(int minPlayers, int maxPlayers, String keyPath, int lives, int mobLevel,
            double healthPerExtraPlayer) {
        this(minPlayers, maxPlayers, keyPath, lives, mobLevel, healthPerExtraPlayer, 1, "");
    }

    public RunRules(int minPlayers, int maxPlayers, String keyPath, int lives, int mobLevel,
            double healthPerExtraPlayer, int tier, String boss) {
        this(minPlayers, maxPlayers, keyPath, lives, mobLevel, healthPerExtraPlayer, tier, boss, 0);
    }

    public RunRules {
        if (minPlayers < 1 || maxPlayers < minPlayers) {
            throw new IllegalArgumentException("bad players range " + minPlayers + "-" + maxPlayers);
        }
        if (lives < 0 || mobLevel < 1 || healthPerExtraPlayer < 0 || enemiesPerExtraPlayer < 0) {
            throw new IllegalArgumentException("lives, mob-level, health-per-extra-player and"
                    + " enemies-per-extra-player must not be negative (mob-level at least 1)");
        }
        if (tier < 1) {
            throw new IllegalArgumentException("tier must be at least 1");
        }
        if (!(eventChance >= 0 && eventChance <= 1) || maxEvents < 0) {
            throw new IllegalArgumentException("event-chance must be between 0 and 1, max-events not negative");
        }
        keyPath = keyPath == null ? "" : keyPath.trim();
        boss = boss == null ? "" : boss.trim();
    }

    /** Mob health multiplier for a party of {@code players}. */
    public double healthFactor(int players) {
        return 1.0 + healthPerExtraPlayer * Math.max(0, players - 1);
    }

    /** Fight budget multiplier (how many enemies) for a party of {@code players}. */
    public double enemyFactor(int players) {
        return 1.0 + enemiesPerExtraPlayer * Math.max(0, players - 1);
    }
}
