package net.tfminecraft.trialrooms.dungeon.generator;

import java.util.ArrayList;
import java.util.List;

import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout.Connection;
import net.tfminecraft.trialrooms.dungeon.template.Doorway;

/**
 * Draws a layout as text for staff dry runs. Each cell is one letter for its room type; between
 * cells, {@code +} marks an open doorway, a space joins cells of the same room, and walls are blank.
 * North is up.
 */
public final class LayoutMap {

    private LayoutMap() {
    }

    /** One letter per room type, in {@link net.tfminecraft.trialrooms.dungeon.template.RoomType} order. */
    static final String LETTERS = "SoCERT?BV";

    public static List<String> render(DungeonLayout layout) {
        int[] b = layout.bounds();
        int w = (b[2] - b[0] + 1) * 2 - 1;
        int h = (b[3] - b[1] + 1) * 2 - 1;
        char[][] grid = new char[h][w];
        for (char[] row : grid) {
            java.util.Arrays.fill(row, ' ');
        }
        java.util.Map<Long, Integer> roomOf = new java.util.HashMap<>();
        for (int i = 0; i < layout.rooms().size(); i++) {
            for (int[] c : layout.rooms().get(i).cells()) {
                roomOf.put(key(c[0], c[1]), i);
            }
        }
        for (int i = 0; i < layout.rooms().size(); i++) {
            char letter = LETTERS.charAt(layout.rooms().get(i).node().type().ordinal());
            for (int[] c : layout.rooms().get(i).cells()) {
                int gx = (c[0] - b[0]) * 2;
                int gz = (c[1] - b[1]) * 2;
                grid[gz][gx] = letter;
                boolean east = roomOf.getOrDefault(key(c[0] + 1, c[1]), -1) == i;
                boolean south = roomOf.getOrDefault(key(c[0], c[1] + 1), -1) == i;
                if (east) {
                    grid[gz][gx + 1] = letter;
                }
                if (south) {
                    grid[gz + 1][gx] = letter;
                }
                if (east && south && roomOf.getOrDefault(key(c[0] + 1, c[1] + 1), -1) == i) {
                    grid[gz + 1][gx + 1] = letter;
                }
            }
        }
        for (Connection c : layout.connections()) {
            Doorway d = layout.rooms().get(c.roomA()).doorways().get(c.doorA());
            int gx = (d.cellX() - b[0]) * 2 + d.side().dx();
            int gz = (d.cellZ() - b[1]) * 2 + d.side().dz();
            grid[gz][gx] = '+';
        }
        List<String> lines = new ArrayList<>();
        for (char[] row : grid) {
            lines.add(new String(row).stripTrailing());
        }
        return lines;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
