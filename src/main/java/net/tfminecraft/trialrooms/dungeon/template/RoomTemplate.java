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
 */
public record RoomTemplate(String id, RoomType type, int widthCells, int depthCells, List<Doorway> doorways,
        List<Marker> markers, int weight, int minDepth, int maxDepth, int maxPerRun, Set<String> tags) {

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
        if (maxDepth != 0 && maxDepth < minDepth) {
            throw new IllegalArgumentException(id + ": max-depth is below min-depth");
        }
        doorways = List.copyOf(doorways);
        markers = List.copyOf(markers);
        tags = Set.copyOf(tags);
        if (doorways.isEmpty()) {
            throw new IllegalArgumentException(id + ": needs at least one doorway");
        }
        for (Doorway d : doorways) {
            if (!onOuterEdge(d, widthCells, depthCells)) {
                throw new IllegalArgumentException(id + ": doorway " + d + " is not on an outer edge");
            }
        }
        if (doorways.stream().map(d -> d.cellX() + "," + d.cellZ() + "," + d.side()).distinct()
                .count() != doorways.size()) {
            throw new IllegalArgumentException(id + ": two doorways share one cell side");
        }
    }

    /** Whether a doorway sits on the footprint's outer edge on its own side. */
    static boolean onOuterEdge(Doorway d, int widthCells, int depthCells) {
        if (d.cellX() < 0 || d.cellZ() < 0 || d.cellX() >= widthCells || d.cellZ() >= depthCells) {
            return false;
        }
        Side s = d.side();
        return switch (s) {
            case NORTH -> d.cellZ() == 0;
            case SOUTH -> d.cellZ() == depthCells - 1;
            case WEST -> d.cellX() == 0;
            case EAST -> d.cellX() == widthCells - 1;
        };
    }

    /** Whether this template may appear at path depth {@code depth}. */
    public boolean allowsDepth(int depth) {
        return depth >= minDepth && (maxDepth == 0 || depth <= maxDepth);
    }
}
