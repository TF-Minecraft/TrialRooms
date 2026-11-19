package net.tfminecraft.trialrooms.dungeon.fight;

/**
 * One enemy a kit's fights can use.
 *
 * @param id     name in the kit
 * @param mob    MythicMobs type
 * @param role   what it does in a fight
 * @param cost   share of a room's threat budget it takes
 * @param weight how often it is picked among its role
 */
public record RosterEntry(String id, String mob, Role role, double cost, int weight) {

    public RosterEntry {
        if (mob == null || mob.isBlank()) {
            throw new IllegalArgumentException(id + ": missing mob");
        }
        if (cost <= 0 || weight < 1) {
            throw new IllegalArgumentException(id + ": cost must be above 0 and weight at least 1");
        }
    }
}
