package net.tfminecraft.trialrooms.dungeon.template;

import net.tfminecraft.trialrooms.dungeon.grid.Orientation;
import net.tfminecraft.trialrooms.dungeon.grid.Side;

/**
 * An opening on the edge of one of a room's cells, centred on that cell's side.
 *
 * @param cellX  cell column inside the room's footprint
 * @param cellZ  cell row inside the room's footprint
 * @param side   the cell side the opening is on; must be an outer edge of the footprint
 * @param width  opening width in blocks; even, so it stays centred under every orientation
 * @param floor  height of the opening's floor above the room's base, in blocks; joined doorways
 *               meet at the same height, so a room whose doorways have different floors changes level
 * @param height opening height in blocks; 0 = the kit's door height
 */
public record Doorway(int cellX, int cellZ, Side side, int width, int floor, int height) {

    public Doorway(int cellX, int cellZ, Side side, int width) {
        this(cellX, cellZ, side, width, 0, 0);
    }

    public Doorway {
        if (side == null) {
            throw new IllegalArgumentException("Doorway needs a side");
        }
        if (floor < 0 || height < 0) {
            throw new IllegalArgumentException("Doorway floor and height cannot be negative");
        }
    }

    /** This doorway once the room is placed in {@code orientation}; footprint in cells. */
    public Doorway oriented(Orientation orientation, int widthCells, int depthCells) {
        int[] cell = orientation.transform(cellX, cellZ, widthCells, depthCells);
        return new Doorway(cell[0], cell[1], orientation.side(side), width, floor, height);
    }

    /** The same opening moved by whole cells. */
    public Doorway moved(int dx, int dz) {
        return new Doorway(cellX + dx, cellZ + dz, side, width, floor, height);
    }

    /** The first block of the opening along its edge, counted from the cell's corner. */
    public int offsetInCell(int cellSize) {
        return (cellSize - width) / 2;
    }

    /** The opening's height, or the kit's door height when it sets none. */
    public int heightOr(int kitDoorHeight) {
        return height > 0 ? height : kitDoorHeight;
    }
}
