package net.tfminecraft.trialrooms.dungeon.template;

import net.tfminecraft.trialrooms.dungeon.grid.Orientation;
import net.tfminecraft.trialrooms.dungeon.grid.Side;

/**
 * An opening on the edge of one of a room's cells, centred on that cell's side.
 *
 * @param cellX cell column inside the room's footprint
 * @param cellZ cell row inside the room's footprint
 * @param side  the cell side the opening is on; must be an outer edge of the footprint
 * @param width opening width in blocks; even, so it stays centred under every orientation
 */
public record Doorway(int cellX, int cellZ, Side side, int width) {

    public Doorway {
        if (side == null) {
            throw new IllegalArgumentException("Doorway needs a side");
        }
    }

    /** This doorway once the room is placed in {@code orientation}; footprint in cells. */
    public Doorway oriented(Orientation orientation, int widthCells, int depthCells) {
        int[] cell = orientation.transform(cellX, cellZ, widthCells, depthCells);
        return new Doorway(cell[0], cell[1], orientation.side(side), width);
    }

    /** The first block of the opening along its edge, counted from the cell's corner. */
    public int offsetInCell(int cellSize) {
        return (cellSize - width) / 2;
    }
}
