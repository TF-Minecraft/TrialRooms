package net.tfminecraft.trialrooms.dungeon.template;

import net.tfminecraft.trialrooms.dungeon.grid.Orientation;
import net.tfminecraft.trialrooms.dungeon.grid.Side;

/**
 * A gameplay point inside a room template, in template block coordinates.
 *
 * @param kind   what the point is for, such as {@code spawn}, {@code chest} or {@code entry}
 * @param tag    an optional qualifier such as {@code melee} or {@code boss}; empty when unused
 * @param x      block x inside the template
 * @param y      block y inside the template
 * @param z      block z inside the template
 * @param facing the way the point faces, or {@code null}
 */
public record Marker(String kind, String tag, int x, int y, int z, Side facing) {

    public Marker {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("Marker needs a kind");
        }
        tag = tag == null ? "" : tag;
    }

    /** This marker once the room is placed in {@code orientation}; template size in blocks. */
    public Marker oriented(Orientation orientation, int widthBlocks, int depthBlocks) {
        int[] pos = orientation.transform(x, z, widthBlocks, depthBlocks);
        return new Marker(kind, tag, pos[0], y, pos[1], facing == null ? null : orientation.side(facing));
    }
}
