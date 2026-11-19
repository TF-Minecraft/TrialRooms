package net.tfminecraft.trialrooms.dungeon.generator;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import net.tfminecraft.trialrooms.dungeon.template.RoomType;

/**
 * How one dungeon type is laid out: a trunk from the start room to the boss, and side branches
 * that each end in a treasure room.
 *
 * @param id             recipe id
 * @param trunkMin       fewest rooms between the start and the boss (rest room not counted)
 * @param trunkMax       most rooms between the start and the boss
 * @param trunkPool      room types for the trunk, with relative weights
 * @param restBeforeBoss whether a rest room always comes right before the boss
 * @param vault          whether a vault room follows the boss
 * @param branchesMin    fewest side branches
 * @param branchesMax    most side branches
 * @param branchMin      fewest rooms in a branch, its treasure room included
 * @param branchMax      most rooms in a branch
 * @param branchPool     room types for a branch before its treasure room, with relative weights
 * @param maxAttempts    how many seeds derived from the run seed are tried before giving up
 * @param stepBudget     how many placement tries one attempt may spend
 */
public record DungeonRecipe(String id, int trunkMin, int trunkMax, Map<RoomType, Integer> trunkPool,
        boolean restBeforeBoss, boolean vault, int branchesMin, int branchesMax, int branchMin, int branchMax,
        Map<RoomType, Integer> branchPool, int maxAttempts, int stepBudget) {

    /** Types the recipe places itself; pools may not contain them. */
    static final Set<RoomType> FIXED = Set.of(RoomType.START, RoomType.BOSS, RoomType.VAULT);

    public DungeonRecipe {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Recipe needs an id");
        }
        range(id, "trunk", trunkMin, trunkMax, 0);
        range(id, "branches", branchesMin, branchesMax, 0);
        range(id, "branch length", branchMin, branchMax, 1);
        trunkPool = pool(id, "trunk", trunkPool, trunkMax > 0);
        branchPool = pool(id, "branch", branchPool, branchesMax > 0 && branchMax > 1);
        if (branchesMax > 0 && trunkMax == 0) {
            throw new IllegalArgumentException(id + ": branches need at least one trunk room to grow from");
        }
        if (maxAttempts < 1 || stepBudget < 1) {
            throw new IllegalArgumentException(id + ": attempts and step budget must be at least 1");
        }
    }

    private static void range(String id, String what, int min, int max, int floor) {
        if (min < floor || max < min) {
            throw new IllegalArgumentException(id + ": bad " + what + " range " + min + "-" + max);
        }
    }

    private static Map<RoomType, Integer> pool(String id, String what, Map<RoomType, Integer> pool, boolean needed) {
        Map<RoomType, Integer> copy = new EnumMap<>(RoomType.class);
        if (pool != null) {
            copy.putAll(pool);
        }
        for (Map.Entry<RoomType, Integer> e : copy.entrySet()) {
            if (FIXED.contains(e.getKey())) {
                throw new IllegalArgumentException(id + ": " + what + " pool cannot contain " + e.getKey());
            }
            if (e.getValue() == null || e.getValue() < 1) {
                throw new IllegalArgumentException(id + ": " + what + " pool weight must be at least 1");
            }
        }
        if (needed && copy.isEmpty()) {
            throw new IllegalArgumentException(id + ": " + what + " pool is empty");
        }
        return Collections.unmodifiableMap(copy);
    }
}
