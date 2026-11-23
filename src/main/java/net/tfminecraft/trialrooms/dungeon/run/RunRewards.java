package net.tfminecraft.trialrooms.dungeon.run;

import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;

/** What a run hands out, and when: told to each member still playing when it happens. */
public interface RunRewards {

    /** The moments a run rewards. */
    enum Moment {
        /** An elite room cleared. */
        ELITE,
        /** A room event finished (braziers lit, runes solved, held out). */
        EVENT,
        /** The boss down. */
        BOSS,
        /** The vault reached after the boss: the run is won. */
        VAULT
    }

    void reward(Player player, String recipe, int tier, Moment moment, UUID run);

    /** A reward for a run that may have beaten its par time (a better vault). */
    default void reward(Player player, String recipe, int tier, Moment moment, UUID run, boolean underPar) {
        reward(player, recipe, tier, moment, run);
    }

    /** A reward of a run whose difficulty pays more for its risk: item chances and marks times {@code boost}. */
    default void reward(Player player, String recipe, int tier, Moment moment, UUID run, boolean underPar,
            double boost) {
        reward(player, recipe, tier, moment, run, underPar);
    }

    /**
     * Spoils that leave the dungeon (act materials, rare items): what a proper player earns in
     * {@code hours} of play, by the act system's hourly budgets.
     */
    default void spoils(Player player, String recipe, double hours, UUID run) {
    }

    /**
     * A feat for cosmetics that leave the dungeon: the boss down (a trophy may drop) or the vault
     * reached (a wardrobe skin may unlock).
     *
     * @param difficulty the run's difficulty id, blank for a dungeon without difficulties
     * @param party      who played the run
     */
    default void feat(Player player, String recipe, String difficulty, Moment moment, UUID run, List<String> party) {
    }
}
