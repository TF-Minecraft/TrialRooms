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
 * @param minutes            how long a proper party takes for a run: what its spoils (act materials and
 *                           rare items that leave the dungeon) are sized on; 0 = no spoils
 * @param par                the par time in minutes: reaching the vault within it gives a better vault; 0 = none
 * @param minRoomCells       the fewest cells a fight room fills (big parties need room); 1 = any
 * @param bossHealth         the boss's health multiplier on top of party scaling (a tougher boss for big parties)
 * @param healthCap          the most an enemy's health is multiplied by (party and elite); 0 = the kit's
 * @param maxDepth           the most cleared rooms that make fights bigger (long runs); 0 = no limit
 */
public record RunRules(int minPlayers, int maxPlayers, String keyPath, int lives, int mobLevel,
        double healthPerExtraPlayer, int tier, String boss, double enemiesPerExtraPlayer, double eventChance,
        int maxEvents, double minutes, double par, int minRoomCells, double bossHealth, double healthCap, int maxDepth) {

    public RunRules(int minPlayers, int maxPlayers, String keyPath, int lives, int mobLevel,
            double healthPerExtraPlayer, int tier, String boss, double enemiesPerExtraPlayer, double eventChance,
            int maxEvents, double minutes, double par, int minRoomCells, double bossHealth) {
        this(minPlayers, maxPlayers, keyPath, lives, mobLevel, healthPerExtraPlayer, tier, boss, enemiesPerExtraPlayer,
                eventChance, maxEvents, minutes, par, minRoomCells, bossHealth, 0, 0);
    }

    public RunRules(int minPlayers, int maxPlayers, String keyPath, int lives, int mobLevel,
            double healthPerExtraPlayer, int tier, String boss, double enemiesPerExtraPlayer, double eventChance,
            int maxEvents, double minutes, double par) {
        this(minPlayers, maxPlayers, keyPath, lives, mobLevel, healthPerExtraPlayer, tier, boss, enemiesPerExtraPlayer,
                eventChance, maxEvents, minutes, par, 1, 1);
    }

    public RunRules(int minPlayers, int maxPlayers, String keyPath, int lives, int mobLevel,
            double healthPerExtraPlayer, int tier, String boss, double enemiesPerExtraPlayer, double eventChance,
            int maxEvents, double minutes) {
        this(minPlayers, maxPlayers, keyPath, lives, mobLevel, healthPerExtraPlayer, tier, boss, enemiesPerExtraPlayer,
                eventChance, maxEvents, minutes, 0);
    }

    public RunRules(int minPlayers, int maxPlayers, String keyPath, int lives, int mobLevel,
            double healthPerExtraPlayer, int tier, String boss, double enemiesPerExtraPlayer, double eventChance,
            int maxEvents) {
        this(minPlayers, maxPlayers, keyPath, lives, mobLevel, healthPerExtraPlayer, tier, boss, enemiesPerExtraPlayer,
                eventChance, maxEvents, 0);
    }

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

    /** The same rules at another tier (a harder difficulty). */
    public RunRules withTier(int t) {
        return new RunRules(minPlayers, maxPlayers, keyPath, lives, mobLevel, healthPerExtraPlayer, t, boss,
                enemiesPerExtraPlayer, eventChance, maxEvents, minutes, par, minRoomCells, bossHealth, healthCap, maxDepth);
    }

    /** The same rules played at a difficulty: its tier, and its run length when it gives one. */
    public RunRules with(Difficulty d) {
        return new RunRules(minPlayers, maxPlayers, keyPath, lives, mobLevel, healthPerExtraPlayer, d.tier(), boss,
                enemiesPerExtraPlayer, eventChance, maxEvents, d.minutes() > 0 ? d.minutes() : minutes,
                d.par() > 0 ? d.par() : par, minRoomCells, bossHealth, healthCap, maxDepth);
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
        if (!(minutes >= 0) || Double.isInfinite(minutes) || !(par >= 0) || Double.isInfinite(par)) {
            throw new IllegalArgumentException("minutes and par must not be negative");
        }
        if (minRoomCells < 1 || !(bossHealth > 0) || Double.isInfinite(bossHealth)) {
            throw new IllegalArgumentException("min-room-cells must be at least 1 and boss-health above 0");
        }
        if (!(healthCap == 0 || healthCap >= 1) || Double.isInfinite(healthCap) || maxDepth < 0) {
            throw new IllegalArgumentException("health-cap must be 0 (the kit's) or at least 1; max-depth not negative");
        }
        keyPath = keyPath == null ? "" : keyPath.trim();
        boss = boss == null ? "" : boss.trim();
    }

    /** The fight depth a run uses after {@code cleared} rooms: no deeper than max-depth. */
    public int depth(int cleared) {
        return maxDepth > 0 ? Math.min(cleared, maxDepth) : cleared;
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
