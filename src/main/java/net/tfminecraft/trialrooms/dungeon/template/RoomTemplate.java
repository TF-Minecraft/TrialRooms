package net.tfminecraft.trialrooms.dungeon.template;

import java.util.List;
import java.util.Set;

import net.tfminecraft.trialrooms.dungeon.grid.Side;

/**
 * A room the generator can place: its footprint in cells, its openings, and its gameplay points.
 *
 * @param id          unique template id; also names the structure file
 * @param type        what the room is for
 * @param widthCells  footprint width (x) in cells
 * @param depthCells  footprint depth (z) in cells
 * @param doorways    openings, in template cell coordinates
 * @param markers     gameplay points, in template block coordinates
 * @param weight      relative chance among templates of the same type; at least 1
 * @param minDepth    earliest room index along the path where it may appear (0 = anywhere)
 * @param maxDepth    latest room index where it may appear; 0 = no limit
 * @param maxPerRun   how many times it may appear in one dungeon; 0 = no limit
 * @param tags        free tags, such as a theme
 * @param cells       the cells of the footprint box the room fills, as {@code z * widthCells + x}; empty
 *                    = the whole box. Lets a room be L, T, U or cross shaped. Its structure must leave
 *                    the other cells without blocks (not even air), or it would overwrite a neighbour.
 * @param threat      multiplier on a fight's threat budget in this room: below 1 for a small room,
 *                    above 1 for a big one; between 0.25 and 3
 */
public record RoomTemplate(String id, RoomType type, int widthCells, int depthCells, List<Doorway> doorways,
        List<Marker> markers, int weight, int minDepth, int maxDepth, int maxPerRun, Set<String> tags,
        Set<Integer> cells, double threat) {

    public RoomTemplate(String id, RoomType type, int widthCells, int depthCells, List<Doorway> doorways,
            List<Marker> markers, int weight, int minDepth, int maxDepth, int maxPerRun, Set<String> tags,
            Set<Integer> cells) {
        this(id, type, widthCells, depthCells, doorways, markers, weight, minDepth, maxDepth, maxPerRun, tags, cells,
                1);
    }

    public RoomTemplate(String id, RoomType type, int widthCells, int depthCells, List<Doorway> doorways,
            List<Marker> markers, int weight, int minDepth, int maxDepth, int maxPerRun, Set<String> tags) {
        this(id, type, widthCells, depthCells, doorways, markers, weight, minDepth, maxDepth, maxPerRun, tags,
                Set.of());
    }

    public RoomTemplate {
        if (id == null || !id.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Template id must be lowercase letters, digits or _: " + id);
        }
        if (type == null) {
            throw new IllegalArgumentException(id + ": missing type");
        }
        if (widthCells < 1 || depthCells < 1) {
            throw new IllegalArgumentException(id + ": footprint must be at least 1x1 cells");
        }
        if (weight < 1) {
            throw new IllegalArgumentException(id + ": weight must be at least 1");
        }
        if (minDepth < 0 || maxDepth < 0 || maxPerRun < 0) {
            throw new IllegalArgumentException(id + ": depth and limits cannot be negative");
        }
        if (!(threat >= 0.25 && threat <= 3)) {
            throw new IllegalArgumentException(id + ": threat must be between 0.25 and 3");
        }
        if (maxDepth != 0 && maxDepth < minDepth) {
            throw new IllegalArgumentException(id + ": max-depth is below min-depth");
        }
        doorways = List.copyOf(doorways);
        markers = List.copyOf(markers);
        tags = Set.copyOf(tags);
        cells = Set.copyOf(cells);
        checkCells(id, widthCells, depthCells, cells);
        if (doorways.isEmpty()) {
            throw new IllegalArgumentException(id + ": needs at least one doorway");
        }
        for (Doorway d : doorways) {
            if (!onOuterEdge(d, widthCells, depthCells, cells)) {
                throw new IllegalArgumentException(id + ": doorway " + d + " is not on an outer edge");
            }
            if (inPocket(d, widthCells, depthCells, cells)) {
                throw new IllegalArgumentException(id + ": doorway " + d + " opens into a pocket of its own room");
            }
        }
        if (doorways.stream().map(d -> d.cellX() + "," + d.cellZ() + "," + d.side()).distinct()
                .count() != doorways.size()) {
            throw new IllegalArgumentException(id + ": two doorways share one cell side");
        }
    }

    /** The mask must name cells inside the box, and they must join up side by side. */
    private static void checkCells(String id, int w, int d, Set<Integer> cells) {
        if (cells.isEmpty()) {
            return;
        }
        for (int c : cells) {
            if (c < 0 || c >= w * d) {
                throw new IllegalArgumentException(id + ": cell " + c + " is outside the footprint");
            }
        }
        java.util.ArrayDeque<Integer> todo = new java.util.ArrayDeque<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int first = cells.iterator().next();
        todo.add(first);
        seen.add(first);
        while (!todo.isEmpty()) {
            int c = todo.poll();
            for (Side s : Side.values()) {
                int x = c % w + s.dx();
                int z = c / w + s.dz();
                int n = z * w + x;
                if (x >= 0 && z >= 0 && x < w && z < d && cells.contains(n) && seen.add(n)) {
                    todo.add(n);
                }
            }
        }
        if (seen.size() != cells.size()) {
            throw new IllegalArgumentException(id + ": the room's cells don't join up");
        }
    }

    /** Whether the room fills a cell of its footprint box. */
    public boolean occupies(int x, int z) {
        return occupies(x, z, widthCells, depthCells, cells);
    }

    private static boolean occupies(int x, int z, int w, int d, Set<Integer> cells) {
        return x >= 0 && z >= 0 && x < w && z < d && (cells.isEmpty() || cells.contains(z * w + x));
    }

    /** The filled cells of the footprint box, as {@code {x, z}} pairs. */
    public List<int[]> cellList() {
        List<int[]> out = new java.util.ArrayList<>();
        for (int x = 0; x < widthCells; x++) {
            for (int z = 0; z < depthCells; z++) {
                if (occupies(x, z)) {
                    out.add(new int[] { x, z });
                }
            }
        }
        return out;
    }

    /** Whether a doorway sits on a filled cell, on a side that faces out of the room. */
    static boolean onOuterEdge(Doorway d, int w, int depth, Set<Integer> cells) {
        return occupies(d.cellX(), d.cellZ(), w, depth, cells)
                && !occupies(d.cellX() + d.side().dx(), d.cellZ() + d.side().dz(), w, depth, cells);
    }

    /**
     * Whether a doorway opens into a notch the room wraps on three sides (inside a U): only a
     * one-cell dead end could ever fit there.
     */
    static boolean inPocket(Doorway d, int w, int depth, Set<Integer> cells) {
        int x = d.cellX() + d.side().dx();
        int z = d.cellZ() + d.side().dz();
        int around = 0;
        for (Side s : Side.values()) {
            if (occupies(x + s.dx(), z + s.dz(), w, depth, cells)) {
                around++;
            }
        }
        return around >= 3;
    }

    /** Whether this template may appear at path depth {@code depth}. */
    public boolean allowsDepth(int depth) {
        return depth >= minDepth && (maxDepth == 0 || depth <= maxDepth);
    }
}
