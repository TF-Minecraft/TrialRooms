package net.tfminecraft.trialrooms.dungeon.supply;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One kind of dungeon supply: what it looks like, what it costs, and what using it does. Supplies
 * work only inside runs and never leave the dungeon system: they are counted in the locker and handed
 * out as items for a run only.
 *
 * @param id       its key in settings.yml
 * @param name     the name players see
 * @param look     the item it shows as
 * @param stack    how many fill one pouch slot
 * @param price    marks one costs in the locker
 * @param use      what using one does
 * @param amount   HEALTH/BANDAGE: share of max health; MANA: share of max mana
 * @param seconds  BANDAGE: seconds the heal takes
 * @param cooldown seconds before the player can use another supply of the same use
 * @param radius   REVIVE: blocks within which a downed ally is brought back
 * @param potions  WARD: potion effect types (lower case keys) it stops
 * @param auras    WARD: MythicMobs auras (lower case) it stops
 * @param kits     the dungeon kits it is sold and found for (empty: every kit)
 */
public record Supply(String id, String name, Look look, int stack, int price, Use use, double amount, int seconds,
        int cooldown, double radius, Set<String> potions, Set<String> auras, List<String> kits) {

    /** What using a supply does. */
    public enum Use {
        /** Heals a share of max health at once. */
        HEALTH,
        /** Restores a share of max mana at once. */
        MANA,
        /** Heals a share of max health over some seconds, out of combat; taking damage stops it. */
        BANDAGE,
        /** Brings a downed ally nearby back up at once. */
        REVIVE,
        /** Stops the next slowing or freezing effect, until the next fight is over. */
        WARD
    }

    /**
     * The item a supply shows as: a material with a custom model data number (the trial potions'
     * look) or an item model key (an ItemsAdder texture), or neither.
     */
    public record Look(String material, int modelData, String itemModel) {
    }

    public Supply {
        if (id == null || id.isBlank() || stack < 1 || price < 0 || !(amount >= 0 && amount <= 1) || seconds < 0
                || cooldown < 0 || !(radius >= 0) || Double.isInfinite(radius)) {
            throw new IllegalArgumentException("supply " + id
                    + ": stack at least 1, price/seconds/cooldown/radius not negative, amount 0 to 1");
        }
        potions = lower(potions);
        auras = lower(auras);
        kits = List.copyOf(kits);
    }

    private static Set<String> lower(Set<String> names) {
        return Set.copyOf(names.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
    }

    /** Whether it is sold and found in a kit's dungeons. */
    public boolean forKit(String kit) {
        return kits.isEmpty() || kits.contains(kit);
    }
}
