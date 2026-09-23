package net.tfminecraft.trialrooms.environment.door;

import net.tfminecraft.trialrooms.persist.Database;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;

import java.util.*;

public final class DoorBlock {

    private final Location loc;                 // normalized block coords
    private final Material material;            // IRON_BARS, IRON_DOOR, etc.
    private BlockFace facing = BlockFace.NORTH; // for Directional
    private final EnumSet<BlockFace> faces = EnumSet.noneOf(BlockFace.class); // for MultipleFacing

    private BlockData original;   // original blockdata to restore
    private boolean placed = false;

    // --- constructors ---
    public DoorBlock(Location loc, Material material) {
        this.loc = normalize(loc);
        this.material = material == null ? Material.IRON_BARS : material;
    }

    /** Persistence / bulk ctor (material + optional faces + optional facing). */
    public DoorBlock(Location loc, Material material, Collection<BlockFace> faces, BlockFace facing) {
        this(loc, material);
        if (faces != null) this.faces.addAll(faces);
        if (facing != null) this.facing = facing;
    }

    // --- fluid setters for editor use ---
    public DoorBlock facing(BlockFace face) { if (face != null) this.facing = face; return this; }
    public DoorBlock connect(BlockFace... fs) { if (fs != null) for (BlockFace f : fs) if (f != null) faces.add(f); return this; }

    // --- getters (used by persistence) ---
    public Location getLocation() { return loc; }
    public Material getMaterial() { return material; }
    public Set<BlockFace> getFaces() { return Collections.unmodifiableSet(faces); }
    public BlockFace getFacing() { return facing; }

    // --- placing / removing ---
    public void place(boolean withFx) {
        if (!isLocValid()) return;
        Block b = loc.getBlock();

        if (!placed) original = b.getBlockData().clone();

        BlockData data = material.createBlockData();

        if (data instanceof Directional dir) {
            try { dir.setFacing(facing); } catch (IllegalArgumentException ignored) {}
        }
        if (!faces.isEmpty() && data instanceof MultipleFacing mf) {
            for (BlockFace f : faces) {
                try { mf.setFace(f, true); } catch (IllegalArgumentException ignored) {}
            }
        }

        b.setBlockData(data, false);
        placed = true;

        if (withFx) {
            World w = loc.getWorld();
            w.spawnParticle(Particle.CLOUD, loc.clone().add(0.5, 0.8, 0.5), 14, 0.25, 0.20, 0.25, 0.0);
            w.playSound(loc, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.8f, 1.0f);
        }
    }

    public void remove(boolean withFx) {
        if (!isLocValid()) return;
        Block b = loc.getBlock();

        if (original != null) b.setBlockData(original, false);
        else b.setType(Material.AIR, false);

        placed = false;
        original = null;

        if (withFx) {
            World w = loc.getWorld();
            w.spawnParticle(Particle.SMOKE_NORMAL, loc.clone().add(0.5, 0.8, 0.5), 10, 0.25, 0.20, 0.25, 0.0);
            w.playSound(loc, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.8f, 0.9f);
        }
    }

    /** Apply the correct state immediately for a given spawner state. */
    public void enforceForState(boolean activeOrUnlocking) {
        if (!isInLoadedChunk()) return;
        if (activeOrUnlocking) place(false); else remove(false);
    }

    public boolean isInLoadedChunk() {
        return isLocValid() && loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }

    // --- persistence bridge ---
    public Database.DoorRecord toRecord() {
        List<String> fs = new ArrayList<>();
        for (BlockFace f : faces) fs.add(f.name());
        return Database.DoorRecord.of(
                Database.SimpleLocation.of(this.loc),
                this.material.name(),
                this.facing.name(),
                fs
        );
    }

    public static DoorBlock fromRecord(Database.DoorRecord r) {
        if (r == null || r.loc == null) return null;
        Location loc = r.loc.toBukkit();
        if (loc == null) return null;

        Material mat = safeMaterial(r.material);
        if (mat == null) mat = Material.IRON_BARS;

        BlockFace face = safeFace(r.facing);
        if (face == null) face = BlockFace.NORTH;

        Set<BlockFace> fs = EnumSet.noneOf(BlockFace.class);
        if (r.faces != null) {
            for (String s : r.faces) {
                BlockFace bf = safeFace(s);
                if (bf != null) fs.add(bf);
            }
        }
        return new DoorBlock(loc, mat, fs, face);
    }

    // --- helpers ---
    private boolean isLocValid() { return loc != null && loc.getWorld() != null; }
    private static Location normalize(Location in) {
        if (in == null) return null;
        return new Location(in.getWorld(), in.getBlockX(), in.getBlockY(), in.getBlockZ());
    }
    private static Material safeMaterial(String s) {
        if (s == null || s.isBlank()) return null;
        Material m = Material.matchMaterial(s);
        if (m == null) try { m = Material.valueOf(s.toUpperCase(Locale.ROOT)); } catch (Exception ignored) {}
        return m;
    }
    private static BlockFace safeFace(String s) {
        if (s == null || s.isBlank()) return null;
        try { return BlockFace.valueOf(s.toUpperCase(Locale.ROOT)); } catch (Exception ignored) { return null; }
    }
}
