package net.tfminecraft.trialrooms.dungeon.fight;

import java.util.List;

/**
 * A pack shape: the roles it is made of.
 *
 * @param id       name in the kit
 * @param slots    one role per enemy
 * @param weight   how often it is picked among the packs that fit
 * @param minDepth fight rooms the party must have cleared before it appears
 */
public record PackTemplate(String id, List<Role> slots, int weight, int minDepth) {

    public PackTemplate {
        if (slots == null || slots.isEmpty()) {
            throw new IllegalArgumentException(id + ": a pack needs slots");
        }
        if (weight < 1 || minDepth < 0) {
            throw new IllegalArgumentException(id + ": weight must be at least 1 and min-depth not negative");
        }
        slots = List.copyOf(slots);
    }
}
