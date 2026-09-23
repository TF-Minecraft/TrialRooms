package net.tfminecraft.trialrooms.environment.spawner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

final class SpawnPlanner {

    private SpawnPlanner() {}

    static List<Location> findSpawnLocations(ActiveSpawner s, int max) {
        List<Location> results = new ArrayList<>(max);
        Location loc = s.getLoc();
        if (loc == null) return results;

        World world = loc.getWorld();
        if (world == null) return results;

        final int r = Math.max(0, s.getSpawnRadius());
        final int baseY = loc.getBlockY();

        List<int[]> candidates = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz <= r * r) candidates.add(new int[]{dx, dz});
            }
        }
        Collections.shuffle(candidates, ThreadLocalRandom.current());

        final int searchBelow = 2, searchAbove = 2;
        for (int[] off : candidates) {
            int x = loc.getBlockX() + off[0];
            int z = loc.getBlockZ() + off[1];

            boolean placed = false;
            for (int y = baseY - searchBelow; y <= baseY + searchAbove; y++) {
                if (is3x3x3ClearAir(world, x, y, z) && is3x3FloorSolid(world, x, y - 1, z)) {
                    results.add(new Location(world, x + 0.5, y, z + 0.5));
                    placed = true;
                    break;
                }
            }
            if (placed && results.size() >= max) break;
        }
        return results;
    }

    private static boolean is3x3x3ClearAir(World world, int x, int y, int z) {
        for (int oy = 0; oy < 3; oy++) {
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    Block b = world.getBlockAt(x + ox, y + oy, z + oz);
                    if (!b.getType().isAir()) return false;
                }
            }
        }
        return true;
    }

    private static boolean is3x3FloorSolid(World world, int x, int yFloor, int z) {
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                Block b = world.getBlockAt(x + ox, yFloor, z + oz);
                if (!b.getType().isSolid()) return false;
            }
        }
        return true;
    }
}
