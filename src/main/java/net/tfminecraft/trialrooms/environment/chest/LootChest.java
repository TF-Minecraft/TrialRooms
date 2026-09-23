package net.tfminecraft.trialrooms.environment.chest;

import net.tfminecraft.trialrooms.TrialRooms;
import net.tfminecraft.trialrooms.environment.spawner.ActiveSpawner;
import net.tfminecraft.trialrooms.environment.spawner.Hologram; // <-- reuse your hologram

import java.util.UUID;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.inventory.ItemStack;

public final class LootChest {

    private static final String CHEST_HOLOGRAM_TEXT =
            "§e§lRight-Click §7with a §6§lKey §7to open";

    private java.util.UUID id;
    private ActiveSpawner spawner; // null for independent
    private Location loc;

    private BlockFace facing = BlockFace.NORTH; // default if none saved

    private boolean hidden = false;

    // Desired visibility for bound chests; independent chests are always visible
    private boolean desiredVisible = true;

    // NEW: hologram shown when chest is visible
    private final Hologram hologram;

    public void tick() {
        if(hologram != null && !hidden) hologram.tick();
    }

    public LootChest(Location loc, ActiveSpawner spawner) {
        this.id = java.util.UUID.randomUUID();
        this.loc = normalize(loc);
        this.spawner = spawner;

        // center-above the chest (same pattern as your spawner holograms)
        this.hologram = new Hologram(
                () -> this.loc,
                () -> CHEST_HOLOGRAM_TEXT
        );
        this.hologram.setYOffset(1.6); // ~1.6 blocks above chest
    }

    public void setId(UUID uuid) {
        this.id = uuid;
    }
    public java.util.UUID getId() { return id; }
    public ActiveSpawner getSpawner() { return spawner; }
    public Location getLocation() { return loc; }
    public boolean isHidden() { return hidden; }
    public boolean isIndependent() { return spawner == null; }

    public void setLocation(Location newLoc) {
        this.loc = normalize(newLoc);
        // If it is currently visible, bump the hologram position immediately
        if (!hidden) {
            try { hologram.refresh(TrialRooms.getInstance()); } catch (Exception ignored) {}
        }
    }

    public void setDesiredVisible(boolean v) { this.desiredVisible = v; }
    public boolean getDesiredVisible() {
        return spawner != null ? true : desiredVisible;
    }

    // ----- Visibility control -----

    /** Ensure the chest is visible right now (place/restore). */
    public void show(boolean withFx) {
        if (loc == null || loc.getWorld() == null) return;
        Block b = loc.getBlock();

        // Already there?
        if (b.getState() instanceof Chest && !hidden) {
            spawnHologram(); // make sure holo exists even if block was already there
            return;
        }

        // Place block
        b.setType(Material.CHEST, false);

        Directional data = (Directional) b.getBlockData();
        data.setFacing(facing);
        b.setBlockData(data, false);

        hidden = false;
        spawnHologram();

        if (withFx) {
            World w = loc.getWorld();
            w.spawnParticle(Particle.CLOUD, loc.clone().add(0.5, 0.8, 0.5), 20, 0.35, 0.25, 0.35, 0.0);
            w.spawnParticle(Particle.END_ROD,  loc.clone().add(0.5, 0.7, 0.5),  8, 0.25, 0.20, 0.25, 0.0);
            w.playSound(loc, Sound.BLOCK_CHEST_OPEN, 0.8f, 1.2f);
            w.playSound(loc, Sound.UI_TOAST_IN,     0.6f, 1.1f);
        }
    }

    /** Hide the chest right now (preserve contents, no drops). */
    public void hide(boolean withFx) {
        if (loc == null || loc.getWorld() == null) return;
        if (hidden) return;

        Block b = loc.getBlock();
        if (b.getType() == Material.CHEST) {
            var bd = b.getBlockData();
            if (bd instanceof Directional d) {
                facing = d.getFacing();
            }
        }
        b.setType(Material.AIR, false);
        hidden = true;

        removeHologram();

        if (withFx) {
            World w = loc.getWorld();
            w.spawnParticle(Particle.CLOUD,        loc.clone().add(0.5, 0.8, 0.5), 18, 0.35, 0.25, 0.35, 0.0);
            w.spawnParticle(Particle.SMOKE_NORMAL, loc.clone().add(0.5, 0.8, 0.5), 10, 0.25, 0.20, 0.25, 0.0);
            w.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.3f);
        }
    }

    /** Enforce current desired visibility (no delay). */
    public void enforceNow() {
        if (getDesiredVisible()) show(false);
        else hide(false);
    }

    // ----- Hologram helpers -----
    public void spawnHologram() {
        try {
            hologram.spawn(TrialRooms.getInstance());
            hologram.refresh(TrialRooms.getInstance());
        } catch (Exception ignored) {}
    }

    public void removeHologram() {
        try {
            hologram.remove(TrialRooms.getInstance());
        } catch (Exception ignored) {}
    }

    private Location normalize(Location in) {
        if (in == null) return null;
        return new Location(in.getWorld(), in.getBlockX(), in.getBlockY(), in.getBlockZ());
    }

    public boolean isInLoadedChunk() {
        return loc != null && loc.getWorld() != null &&
               loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
    }
}
