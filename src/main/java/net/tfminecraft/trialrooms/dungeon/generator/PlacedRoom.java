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
 * @param elevation   how far the room's base sits above the start room's, in blocks (negative = below)
 */
public record PlacedRoom(MissionNode node, RoomTemplate template, Orientation orientation, int originX, int originZ,
        List<Doorway> doorways, int elevation) {

    public PlacedRoom {
        doorways = List.copyOf(doorways);
    }

    static PlacedRoom place(MissionNode node, RoomTemplate template, Orientation orientation, int originX,
            int originZ, int elevation) {
        List<Doorway> doors = new ArrayList<>();
        for (Doorway d : template.doorways()) {
            doors.add(d.oriented(orientation, template.widthCells(), template.depthCells()).moved(originX, originZ));
        }
        return new PlacedRoom(node, template, orientation, originX, originZ, doors, elevation);
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
        for (int[] c : template.cellList()) {
            int[] o = orientation.transform(c[0], c[1], template.widthCells(), template.depthCells());
            cells.add(new int[] { originX + o[0], originZ + o[1] });
        }
        return cells;
    }
}
