package net.tfminecraft.trialrooms.dungeon.run;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout;
import net.tfminecraft.trialrooms.dungeon.generator.PlacedRoom;
import net.tfminecraft.trialrooms.dungeon.template.Doorway;
import net.tfminecraft.trialrooms.dungeon.template.Marker;

/**
 * Where a placed layout's rooms, doorways and markers are in block coordinates. Grid cell (0, 0)
 * starts at block ({@code originX}, {@code originY}, {@code originZ}); {@code originY} is the floor.
 */
public final class RunGeometry {

    private final DungeonLayout layout;
    private final int cell;
    private final int doorHeight;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final Map<Long, Integer> roomByCell = new HashMap<>();

    public RunGeometry(DungeonLayout layout, int cellSize, int doorHeight, int originX, int originY, int originZ) {
        this.layout = layout;
        this.cell = cellSize;
        this.doorHeight = doorHeight;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        for (int i = 0; i < layout.rooms().size(); i++) {
            for (int[] c : layout.rooms().get(i).cells()) {
                roomByCell.put(key(c[0], c[1]), i);
            }
        }
    }

    public DungeonLayout layout() {
        return layout;
    }

    /** The room a block position is in, or -1 outside every room. */
    public int roomAt(double x, double z) {
        int cx = Math.floorDiv((int) Math.floor(x) - originX, cell);
        int cz = Math.floorDiv((int) Math.floor(z) - originZ, cell);
        return roomByCell.getOrDefault(key(cx, cz), -1);
    }

    /**
     * Whether a position is in a room and at least {@code margin} blocks from its outer walls (a
     * player standing in a doorway is not), so a door can close without catching them.
     */
    public boolean safelyInside(int room, double x, double z, int margin) {
        if (roomAt(x, z) != room) {
            return false;
        }
        int lx = Math.floorMod((int) Math.floor(x) - originX, cell);
        int lz = Math.floorMod((int) Math.floor(z) - originZ, cell);
        for (int[] d : new int[][] { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } }) {
            int along = d[0] != 0 ? lx : lz;
            boolean nearWall = d[0] + d[1] < 0 ? along < margin : along >= cell - margin;
            if (nearWall && roomAt(x + d[0] * margin, z + d[1] * margin) != room) {
                return false;
            }
        }
        return true;
    }

    /** The block height of a room's base: the kit's floor plus the room's elevation. */
    public int baseY(int room) {
        return originY + layout.rooms().get(room).elevation();
    }

    /** The block height of a doorway's floor. */
    public int doorFloorY(int room, int door) {
        return baseY(room) + layout.rooms().get(room).doorways().get(door).floor();
    }

    /** The blocks that fill a doorway's opening on its own room's wall, as {@code {x, y, z}}. */
    public List<int[]> doorBlocks(int room, int door) {
        Doorway d = layout.rooms().get(room).doorways().get(door);
        int floor = doorFloorY(room, door);
        int bx = originX + d.cellX() * cell;
        int bz = originZ + d.cellZ() * cell;
        int start = d.offsetInCell(cell);
        List<int[]> blocks = new ArrayList<>();
        for (int k = start; k < start + d.width(); k++) {
            for (int y = 1; y <= d.heightOr(doorHeight); y++) {
                switch (d.side()) {
                    case NORTH -> blocks.add(new int[] { bx + k, floor + y, bz });
                    case SOUTH -> blocks.add(new int[] { bx + k, floor + y, bz + cell - 1 });
                    case WEST -> blocks.add(new int[] { bx, floor + y, bz + k });
                    default -> blocks.add(new int[] { bx + cell - 1, floor + y, bz + k });
                }
            }
        }
        return blocks;
    }

    /** A standing spot three blocks inside a doorway, on its floor, as {@code {x, y, z}}. */
    public double[] insideDoor(int room, int door) {
        Doorway d = layout.rooms().get(room).doorways().get(door);
        double bx = originX + d.cellX() * cell;
        double bz = originZ + d.cellZ() * cell;
        double y = doorFloorY(room, door) + 1;
        double mid = cell / 2.0;
        return switch (d.side()) {
            case NORTH -> new double[] { bx + mid, y, bz + 3.5 };
            case SOUTH -> new double[] { bx + mid, y, bz + cell - 3.5 };
            case WEST -> new double[] { bx + 3.5, y, bz + mid };
            default -> new double[] { bx + cell - 3.5, y, bz + mid };
        };
    }

    /** Where a room is entered: inside the doorway it was reached through, or its first doorway. */
    public double[] entry(int room) {
        for (DungeonLayout.Connection c : layout.connections()) {
            if (c.roomB() == room) {
                return insideDoor(room, c.doorB());
            }
        }
        return insideDoor(room, 0);
    }

    /** Every marker of a kind in a room, in world block coordinates (block centres), as {@code {x, y, z}}. */
    public List<double[]> markers(int room, String kind) {
        PlacedRoom r = layout.rooms().get(room);
        int w = r.template().widthCells() * cell;
        int d = r.template().depthCells() * cell;
        List<double[]> out = new ArrayList<>();
        for (Marker m : r.template().markers()) {
            if (m.kind().equals(kind)) {
                Marker o = m.oriented(r.orientation(), w, d);
                out.add(new double[] { originX + r.originX() * cell + o.x() + 0.5, baseY(room) + o.y(),
                    originZ + r.originZ() * cell + o.z() + 0.5 });
            }
        }
        return out;
    }

    /** Markers of a kind with their tags, in the same order as {@link #markers}. */
    public List<String> markerTags(int room, String kind) {
        List<String> tags = new ArrayList<>();
        for (Marker m : layout.rooms().get(room).template().markers()) {
            if (m.kind().equals(kind)) {
                tags.add(m.tag());
            }
        }
        return tags;
    }

    /**
     * A safe spot in a room when a marker is missing: its entry. (A shaped room's box centre can be
     * in a wall or outside the room, and a sunken room's floor is not at the doorways' height.)
     */
    public double[] centre(int room) {
        return entry(room);
    }

    /**
     * Describes where a point is relative to someone looking along {@code yaw} (Minecraft degrees:
     * 0 = south, 90 = west), for players who navigate by text and sound.
     */
    public static String direction(double fromX, double fromZ, float yaw, double toX, double toZ) {
        return direction(fromX, 0, fromZ, yaw, toX, 0, toZ);
    }

    /** As above, and says "above" or "below" when the point is two or more blocks up or down. */
    public static String direction(double fromX, double fromY, double fromZ, float yaw, double toX, double toY,
            double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        long distance = Math.round(Math.sqrt(dx * dx + dz * dz));
        double target = Math.toDegrees(Math.atan2(-dx, dz));
        double diff = ((target - yaw) % 360 + 540) % 360 - 180;
        String where;
        if (Math.abs(diff) <= 45) {
            where = "ahead";
        } else if (Math.abs(diff) >= 135) {
            where = "behind you";
        } else if (diff > 0) {
            where = "to your right";
        } else {
            where = "to your left";
        }
        double dy = toY - fromY;
        String level = dy >= 2 ? ", above" : dy <= -2 ? ", below" : "";
        return distance + (distance == 1 ? " block " : " blocks ") + where + level;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
