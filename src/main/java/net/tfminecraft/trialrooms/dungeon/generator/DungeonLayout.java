package net.tfminecraft.trialrooms.dungeon.generator;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.tfminecraft.trialrooms.dungeon.template.Doorway;

/**
 * A finished dungeon plan: every room with its place, and which doorways join which rooms.
 *
 * @param recipeId    recipe used
 * @param seed        the run seed asked for
 * @param attempt     which derived attempt succeeded (0 = first)
 * @param rooms       placed rooms, indexed like the plan
 * @param connections joined doorway pairs
 */
public record DungeonLayout(String recipeId, long seed, int attempt, List<PlacedRoom> rooms,
        List<Connection> connections) {

    public DungeonLayout {
        rooms = List.copyOf(rooms);
        connections = List.copyOf(connections);
    }

    /**
     * Two joined doorways: doorway {@code doorA} of room {@code roomA} and doorway {@code doorB} of
     * room {@code roomB}. Room A is the one nearer the start.
     */
    public record Connection(int roomA, int doorA, int roomB, int doorB) {
    }

    /** Whether a room's doorway leads into another room; unused doorways are walled up. */
    public boolean isConnected(int room, int door) {
        for (Connection c : connections) {
            if ((c.roomA() == room && c.doorA() == door) || (c.roomB() == room && c.doorB() == door)) {
                return true;
            }
        }
        return false;
    }

    /** The doorways of a room that lead nowhere. */
    public List<Doorway> unusedDoorways(int room) {
        List<Doorway> doors = rooms.get(room).doorways();
        Set<Integer> used = new HashSet<>();
        for (int i = 0; i < doors.size(); i++) {
            if (isConnected(room, i)) {
                used.add(i);
            }
        }
        return java.util.stream.IntStream.range(0, doors.size()).filter(i -> !used.contains(i))
                .mapToObj(doors::get).toList();
    }

    /** Grid bounds as {@code {minX, minZ, maxX, maxZ}}, inclusive. */
    public int[] bounds() {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PlacedRoom r : rooms) {
            minX = Math.min(minX, r.originX());
            minZ = Math.min(minZ, r.originZ());
            maxX = Math.max(maxX, r.originX() + r.widthCells() - 1);
            maxZ = Math.max(maxZ, r.originZ() + r.depthCells() - 1);
        }
        return new int[] { minX, minZ, maxX, maxZ };
    }
}
