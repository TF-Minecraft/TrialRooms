package net.tfminecraft.trialrooms.dungeon.generator;

import java.util.ArrayList;
import java.util.List;

import net.tfminecraft.trialrooms.dungeon.grid.Orientation;
import net.tfminecraft.trialrooms.dungeon.template.Doorway;
import net.tfminecraft.trialrooms.dungeon.template.RoomTemplate;

/**
 * A template placed on the dungeon grid.
 *
 * @param node        the plan entry it fills
 * @param template    the template used
 * @param orientation how the template is turned
 * @param originX     grid x of the room's first cell
 * @param originZ     grid z of the room's first cell
 * @param doorways    the room's doorways in grid cell coordinates, in template order
 */
public record PlacedRoom(MissionNode node, RoomTemplate template, Orientation orientation, int originX, int originZ,
        List<Doorway> doorways) {

    public PlacedRoom {
        doorways = List.copyOf(doorways);
    }

    static PlacedRoom place(MissionNode node, RoomTemplate template, Orientation orientation, int originX,
            int originZ) {
        List<Doorway> doors = new ArrayList<>();
        for (Doorway d : template.doorways()) {
            Doorway o = d.oriented(orientation, template.widthCells(), template.depthCells());
            doors.add(new Doorway(o.cellX() + originX, o.cellZ() + originZ, o.side(), o.width()));
        }
        return new PlacedRoom(node, template, orientation, originX, originZ, doors);
    }

    public int widthCells() {
        return orientation.orientedWidth(template.widthCells(), template.depthCells());
    }

    public int depthCells() {
        return orientation.orientedDepth(template.widthCells(), template.depthCells());
    }

    /** Grid cells the room covers, as {@code {x, z}} pairs. */
    public List<int[]> cells() {
        List<int[]> cells = new ArrayList<>();
        for (int x = 0; x < widthCells(); x++) {
            for (int z = 0; z < depthCells(); z++) {
                cells.add(new int[] { originX + x, originZ + z });
            }
        }
        return cells;
    }
}
