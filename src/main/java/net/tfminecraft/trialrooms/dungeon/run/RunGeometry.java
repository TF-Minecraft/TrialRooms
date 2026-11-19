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

    public int floorY() {
        return originY;
    }

    /** The room a block position is in, or -1 outside every room. */
    public int roomAt(double x, double z) {
        int cx = Math.floorDiv((int) Math.floor(x) - originX, cell);
        int cz = Math.floorDiv((int) Math.floor(z) - originZ, cell);
        return roomByCell.getOrDefault(key(cx, cz), -1);
    }

    /** The blocks that fill a doorway's opening on its own room's wall, as {@code {x, y, z}}. */
    public List<int[]> doorBlocks(Doorway door) {
        int bx = originX + door.cellX() * cell;
        int bz = originZ + door.cellZ() * cell;
        int start = door.offsetInCell(cell);
        List<int[]> blocks = new ArrayList<>();
        for (int k = start; k < start + door.width(); k++) {
            for (int y = 1; y <= doorHeight; y++) {
                switch (door.side()) {
                    case NORTH -> blocks.add(new int[] { bx + k, originY + y, bz });
                    case SOUTH -> blocks.add(new int[] { bx + k, originY + y, bz + cell - 1 });
                    case WEST -> blocks.add(new int[] { bx, originY + y, bz + k });
                    default -> blocks.add(new int[] { bx + cell - 1, originY + y, bz + k });
                }
            }
        }
        return blocks;
    }

    /** A standing spot three blocks inside a doorway, as {@code {x, y, z}}. */
    public double[] insideDoor(Doorway door) {
        double bx = originX + door.cellX() * cell;
        double bz = originZ + door.cellZ() * cell;
        double mid = cell / 2.0;
        return switch (door.side()) {
            case NORTH -> new double[] { bx + mid, originY + 1, bz + 3.5 };
            case SOUTH -> new double[] { bx + mid, originY + 1, bz + cell - 3.5 };
            case WEST -> new double[] { bx + 3.5, originY + 1, bz + mid };
            default -> new double[] { bx + cell - 3.5, originY + 1, bz + mid };
        };
    }

    /** Where a room is entered: inside the doorway it was reached through, or its first doorway. */
    public double[] entry(int room) {
        for (DungeonLayout.Connection c : layout.connections()) {
            if (c.roomB() == room) {
                return insideDoor(layout.rooms().get(room).doorways().get(c.doorB()));
            }
        }
        return insideDoor(layout.rooms().get(room).doorways().get(0));
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
                out.add(new double[] { originX + r.originX() * cell + o.x() + 0.5, originY + o.y(),
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

    /** The middle of a room at standing height. */
    public double[] centre(int room) {
        PlacedRoom r = layout.rooms().get(room);
        return new double[] { originX + (r.originX() + r.widthCells() / 2.0) * cell, originY + 1,
            originZ + (r.originZ() + r.depthCells() / 2.0) * cell };
    }

    /**
     * Describes where a point is relative to someone looking along {@code yaw} (Minecraft degrees:
     * 0 = south, 90 = west), for players who navigate by text and sound.
     */
    public static String direction(double fromX, double fromZ, float yaw, double toX, double toZ) {
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
        return distance + (distance == 1 ? " block " : " blocks ") + where;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
