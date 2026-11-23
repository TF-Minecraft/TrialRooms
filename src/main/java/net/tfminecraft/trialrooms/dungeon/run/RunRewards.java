package net.tfminecraft.trialrooms.dungeon.run;

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

    /**
     * Spoils that leave the dungeon (act materials, rare items): what a proper player earns in
     * {@code hours} of play, by the act system's hourly budgets.
     */
    default void spoils(Player player, String recipe, double hours, UUID run) {
    }
}
