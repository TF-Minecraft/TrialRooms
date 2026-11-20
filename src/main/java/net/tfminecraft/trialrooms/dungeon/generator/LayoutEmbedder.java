package net.tfminecraft.trialrooms.dungeon.generator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.tfminecraft.trialrooms.dungeon.grid.Orientation;
import net.tfminecraft.trialrooms.dungeon.grid.Side;
import net.tfminecraft.trialrooms.dungeon.template.Doorway;
import net.tfminecraft.trialrooms.dungeon.template.RoomCatalog;
import net.tfminecraft.trialrooms.dungeon.template.RoomTemplate;

/**
 * Fits a room plan onto the cell grid by depth-first search with backtracking. Each room opens
 * from a free doorway of its parent through a doorway of the same width on the facing side, and
 * no two rooms share a cell.
 */
final class LayoutEmbedder {

    private static final List<Orientation> ORIENTATIONS = List.of(Orientation.values());

    private final RoomCatalog catalog;
    private final List<MissionNode> nodes;
    private final Random random;
    private final int budget;
    private final PlacedRoom[] placed;
    private final Map<Long, Integer> occupied = new HashMap<>();
    private final Set<Long> usedDoors = new HashSet<>();
    private final Map<String, Integer> uses = new HashMap<>();
    private final List<DungeonLayout.Connection> connections = new ArrayList<>();
    private int steps;

    LayoutEmbedder(RoomCatalog catalog, List<MissionNode> nodes, Random random, int budget) {
        this.catalog = catalog;
        this.nodes = nodes;
        this.random = random;
        this.budget = budget;
        this.placed = new PlacedRoom[nodes.size()];
    }

    /** Runs the search; true when every room found a place. */
    boolean embed() {
        try {
            return place(0);
        } catch (OutOfSteps e) {
            return false;
        }
    }

    List<PlacedRoom> rooms() {
        return List.of(placed);
    }

    List<DungeonLayout.Connection> connections() {
        return connections;
    }

    private boolean place(int i) {
        if (i == nodes.size()) {
            return true;
        }
        MissionNode node = nodes.get(i);
        if (node.parent() < 0) {
            for (RoomTemplate t : candidates(node)) {
                for (Orientation o : Weighted.shuffled(ORIENTATIONS, random)) {
                    if (attempt(i, t, o, 0, 0, 0, null)) {
                        return true;
                    }
                }
            }
            return false;
        }
        PlacedRoom parent = placed[node.parent()];
        for (int d : freeDoors(node.parent())) {
            Doorway from = parent.doorways().get(d);
            int targetX = from.cellX() + from.side().dx();
            int targetZ = from.cellZ() + from.side().dz();
            Side need = from.side().opposite();
            for (RoomTemplate t : candidates(node)) {
                for (Orientation o : Weighted.shuffled(ORIENTATIONS, random)) {
                    List<Doorway> doors = PlacedRoom.place(node, t, o, 0, 0, 0).doorways();
                    for (int j = 0; j < doors.size(); j++) {
                        Doorway to = doors.get(j);
                        // Joined doorways meet at the same height: the room sits as far up as that needs.
                        int elevation = parent.elevation() + from.floor() - to.floor();
                        if (to.side() == need && to.width() == from.width()
                                && Math.abs(elevation) <= catalog.maxRise()
                                && attempt(i, t, o, targetX - to.cellX(), targetZ - to.cellZ(), elevation,
                                        new int[] { node.parent(), d, j })) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Places room {@code i} if its cells are free, recurses, and undoes it on failure. */
    private boolean attempt(int i, RoomTemplate t, Orientation o, int originX, int originZ, int elevation,
            int[] link) {
        if (++steps > budget) {
            throw new OutOfSteps();
        }
        PlacedRoom room = PlacedRoom.place(nodes.get(i), t, o, originX, originZ, elevation);
        List<int[]> cells = room.cells();
        for (int[] c : cells) {
            if (occupied.containsKey(key(c[0], c[1]))) {
                return false;
            }
        }
        for (int[] c : cells) {
            occupied.put(key(c[0], c[1]), i);
        }
        uses.merge(t.id(), 1, Integer::sum);
        placed[i] = room;
        if (link != null) {
            usedDoors.add(doorKey(link[0], link[1]));
            usedDoors.add(doorKey(i, link[2]));
            connections.add(new DungeonLayout.Connection(link[0], link[1], i, link[2]));
        }
        if (place(i + 1)) {
            return true;
        }
        if (link != null) {
            usedDoors.remove(doorKey(link[0], link[1]));
            usedDoors.remove(doorKey(i, link[2]));
            connections.remove(connections.size() - 1);
        }
        placed[i] = null;
        uses.merge(t.id(), -1, Integer::sum);
        for (int[] c : cells) {
            occupied.remove(key(c[0], c[1]));
        }
        return false;
    }

    /**
     * Templates that fit the room's type, depth and per-run limits, heavier ones first. The
     * parent's own template goes last, so the same room rarely comes twice in a row.
     */
    private List<RoomTemplate> candidates(MissionNode node) {
        List<RoomTemplate> fit = new ArrayList<>();
        for (RoomTemplate t : catalog.ofType(node.type())) {
            if (t.allowsDepth(node.depth()) && (t.maxPerRun() == 0 || uses.getOrDefault(t.id(), 0) < t.maxPerRun())) {
                fit.add(t);
            }
        }
        List<RoomTemplate> ordered = new ArrayList<>(Weighted.order(fit, RoomTemplate::weight, random));
        if (node.parent() >= 0 && ordered.remove(placed[node.parent()].template())) {
            ordered.add(placed[node.parent()].template());
        }
        return ordered;
    }

    private List<Integer> freeDoors(int room) {
        List<Integer> free = new ArrayList<>();
        for (int d = 0; d < placed[room].doorways().size(); d++) {
            if (!usedDoors.contains(doorKey(room, d))) {
                free.add(d);
            }
        }
        return Weighted.shuffled(free, random);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static long doorKey(int room, int door) {
        return ((long) room << 32) | door;
    }

    private static final class OutOfSteps extends RuntimeException {
        private static final long serialVersionUID = 1L;

        OutOfSteps() {
            super(null, null, false, false);
        }
    }
}
