package net.tfminecraft.trialrooms.dungeon.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout;
import net.tfminecraft.trialrooms.dungeon.generator.PlacedRoom;
import net.tfminecraft.trialrooms.dungeon.grid.Orientation;
import net.tfminecraft.trialrooms.dungeon.load.DungeonKit;
import net.tfminecraft.trialrooms.dungeon.template.Doorway;
import net.tfminecraft.trialrooms.dungeon.template.Marker;

/**
 * Builds a layout in the dungeon world: loads its chunks off the main thread and holds them with
 * plugin chunk tickets, then places one room per tick and walls up unused doorways.
 *
 * <p>Grid cell (0, 0) starts at block {@code (originX, originY, originZ)}. Release the tickets
 * when the dungeon is done; the world is never saved, so its chunks then unload empty.
 */
public final class LayoutPlacer {

    private final Plugin plugin;
    private final World world;
    private final DungeonKit kit;
    private final Function<String, Structure> structures;
    private final DungeonLayout layout;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final List<Chunk> held = new ArrayList<>();
    private final CompletableFuture<Location> done = new CompletableFuture<>();
    private BukkitTask task;
    private boolean released;

    public LayoutPlacer(Plugin plugin, World world, DungeonKit kit, Function<String, Structure> structures,
            DungeonLayout layout, int originX, int originY, int originZ) {
        this.plugin = plugin;
        this.world = world;
        this.kit = kit;
        this.structures = structures;
        this.layout = layout;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
    }

    /** Starts building. The future completes on the main thread with the arrival point. */
    public CompletableFuture<Location> place() {
        int cell = kit.catalog().cellSize();
        int[] b = layout.bounds();
        int minChunkX = (originX + b[0] * cell) >> 4;
        int minChunkZ = (originZ + b[1] * cell) >> 4;
        int maxChunkX = (originX + (b[2] + 1) * cell - 1) >> 4;
        int maxChunkZ = (originZ + (b[3] + 1) * cell - 1) >> 4;
        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                loads.add(world.getChunkAtAsync(cx, cz, true));
            }
        }
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> afterLoad(loads, error)));
        return done;
    }

    private void afterLoad(List<CompletableFuture<Chunk>> loads, Throwable error) {
        if (error != null || released) {
            done.completeExceptionally(error != null ? error : new IllegalStateException("released"));
            return;
        }
        for (CompletableFuture<Chunk> f : loads) {
            Chunk chunk = f.join();
            chunk.addPluginChunkTicket(plugin);
            held.add(chunk);
        }
        int[] next = { 0 };
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            try {
                placeRoom(next[0]);
            } catch (RuntimeException e) {
                task.cancel();
                done.completeExceptionally(e);
                return;
            }
            next[0]++;
            if (next[0] == layout.rooms().size()) {
                task.cancel();
                done.complete(arrival());
            }
        }, 1L, 1L);
    }

    /** Places one room and walls up its unused doorways. */
    void placeRoom(int index) {
        PlacedRoom room = layout.rooms().get(index);
        int cell = kit.catalog().cellSize();
        Structure structure = structures.apply(room.template().id());
        if (structure == null) {
            throw new IllegalStateException("No structure for room " + room.template().id());
        }
        int w = room.template().widthCells() * cell;
        int d = room.template().depthCells() * cell;
        BlockVector size = structure.getSize();
        if (size.getBlockX() != w || size.getBlockZ() != d || size.getBlockY() > kit.catalog().cellHeight()) {
            throw new IllegalStateException("Structure " + room.template().id() + " is " + size
                    + " but its footprint needs " + w + "x" + d + " within height " + kit.catalog().cellHeight());
        }
        Orientation o = room.orientation();
        int[] shift = o.transform(0, 0, w, d);
        Location at = new Location(world, originX + room.originX() * cell + shift[0], originY,
                originZ + room.originZ() * cell + shift[1]);
        structure.place(at, false, rotation(o), o.mirrored() ? Mirror.LEFT_RIGHT : Mirror.NONE, 0, 1.0f,
                new Random(0));
        BlockData seal = plugin.getServer().createBlockData(kit.sealBlock());
        for (Doorway door : layout.unusedDoorways(index)) {
            seal(door, cell, seal);
        }
    }

    private void seal(Doorway door, int cell, BlockData seal) {
        int bx = originX + door.cellX() * cell;
        int bz = originZ + door.cellZ() * cell;
        int start = door.offsetInCell(cell);
        for (int k = start; k < start + door.width(); k++) {
            for (int y = 1; y <= kit.doorHeight(); y++) {
                int x;
                int z;
                switch (door.side()) {
                    case NORTH -> { x = bx + k; z = bz; }
                    case SOUTH -> { x = bx + k; z = bz + cell - 1; }
                    case WEST -> { x = bx; z = bz + k; }
                    default -> { x = bx + cell - 1; z = bz + k; }
                }
                world.getBlockAt(x, originY + y, z).setBlockData(seal, false);
            }
        }
    }

    /** The start room's {@code entry} marker, or the centre of its first cell. */
    Location arrival() {
        PlacedRoom start = layout.rooms().get(0);
        int cell = kit.catalog().cellSize();
        int w = start.template().widthCells() * cell;
        int d = start.template().depthCells() * cell;
        int baseX = originX + start.originX() * cell;
        int baseZ = originZ + start.originZ() * cell;
        for (Marker m : start.template().markers()) {
            if (m.kind().equals("entry")) {
                Marker o = m.oriented(start.orientation(), w, d);
                return new Location(world, baseX + o.x() + 0.5, originY + o.y(), baseZ + o.z() + 0.5);
            }
        }
        return new Location(world, baseX + cell / 2 + 0.5, originY + 1, baseZ + cell / 2 + 0.5);
    }

    /** Lets go of every chunk ticket and stops any placement still running. Safe to call twice. */
    public void release() {
        released = true;
        if (task != null) {
            task.cancel();
        }
        done.completeExceptionally(new IllegalStateException("released"));
        for (Chunk chunk : held) {
            chunk.removePluginChunkTicket(plugin);
        }
        held.clear();
    }

    public int heldChunks() {
        return held.size();
    }

    static StructureRotation rotation(Orientation o) {
        return switch (o.quarterTurns()) {
            case 0 -> StructureRotation.NONE;
            case 1 -> StructureRotation.CLOCKWISE_90;
            case 2 -> StructureRotation.CLOCKWISE_180;
            default -> StructureRotation.COUNTERCLOCKWISE_90;
        };
    }
}
