package net.tfminecraft.trialrooms.dungeon.run;

import java.util.List;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

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

    /**
     * A find in a run that banks finds. A rewards that rolls ahead rolls it when it is found (then it
     * holds what it gives); otherwise it is the moment to roll once it comes home. Its value says how
     * strong what it holds is, which decides what an early exit lets a player take out.
     *
     * @param moment what it came from; null for spoils
     * @param hours  hours of spoils, for a spoils find not rolled yet
     * @param label  what players read
     * @param value  how much it is worth
     * @param marks  dungeon marks in it (a rolled find)
     * @param counts whether it came from a run that rolls items this week (a rolled find)
     * @param gear   dungeon items, for the locker (server item bytes)
     * @param spoils items that leave the dungeon (server item bytes)
     * @param rolled whether it was rolled when found
     */
    record Find(Moment moment, double hours, String label, int value, int marks, boolean counts, List<byte[]> gear,
            List<byte[]> spoils, boolean rolled) {

        public Find {
            gear = List.copyOf(gear);
            spoils = List.copyOf(spoils);
        }

        /** A moment to roll once it comes home. */
        public static Find of(Moment m) {
            return new Find(m, 0, m.name().toLowerCase(java.util.Locale.ROOT) + " find", 0, 0, false, List.of(),
                    List.of(), false);
        }

        /** Spoils to roll once they come home. */
        public static Find spoils(double hours) {
            return new Find(null, hours, "spoils", 0, 0, false, List.of(), List.of(), false);
        }
    }

    void reward(Player player, String recipe, int tier, Moment moment, UUID run);

    /** A find for a player at a moment of a banking run (item chances and marks times {@code boost}). */
    default Find find(Player player, String recipe, int tier, Moment moment, UUID run, double boost) {
        return Find.of(moment);
    }

    /** A cleared room's spoils, as a find of a banking run. */
    default Find spoilsFind(Player player, String recipe, double hours, UUID run) {
        return Find.spoils(hours);
    }

    /**
     * Hands a find over. With {@code restore} it is given back by staff: it counts towards no weekly
     * limit.
     */
    default void deliver(Player player, Find find, String recipe, int tier, UUID run, double boost, boolean restore) {
        if (find.rolled()) {
            return; // (only a rewards that rolls ahead makes rolled finds)
        }
        if (find.moment() == null) {
            spoils(player, recipe, find.hours(), run);
        } else {
            reward(player, recipe, tier, find.moment(), run, false, boost);
        }
    }

    /** How a find shows on the finds screen (its lines are added by the screen). */
    default ItemStack icon(Find find) {
        return new ItemStack(find.moment() == null ? Material.BUNDLE : Material.PAPER);
    }

    /** The value of a dungeon item (a worn piece), or 0 when it says none. */
    default int value(ItemStack item) {
        return 0;
    }

    /** Marks a player has; -1 when they can't be paid or spent. */
    default int marks(Player player) {
        return -1;
    }

    /** Takes (a negative amount gives) marks; false when it can't be saved. */
    default boolean spend(Player player, int marks) {
        return false;
    }

    /** What insuring one worn piece costs, in marks, by its value. */
    default int insurance(int value) {
        return value;
    }

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
