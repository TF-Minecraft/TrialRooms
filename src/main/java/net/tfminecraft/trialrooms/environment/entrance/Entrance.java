package net.tfminecraft.trialrooms.environment.entrance;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.trialrooms.TrialRooms;
import net.tfminecraft.trialrooms.environment.spawner.Hologram;
import net.tfminecraft.trialrooms.persist.Database;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.UUID;

public final class Entrance {

    private final UUID id = UUID.randomUUID();

    /** The Lodestone block location that acts as the “entrance” (keyed by manager map). */
    private final Location entranceLoc; // normalized (block coords)

    /** The target location players are teleported to when using the entrance with a key. */
    private Location destination;       // can be exact (not normalized)

    /** The Lodestone block location that acts as the “exit” trigger (normalized). */
    private Location exitLoc;           // optional; normalized

    /** TLibs item path required to use the entrance, e.g. "v.blaze_powder". */
    private String keyPath;

    // Holograms
    private final Hologram entranceHolo;
    private final Hologram exitHolo;

    public Entrance(Location entranceLodestone) {
        this.entranceLoc = normalizeBlock(entranceLodestone);

        this.entranceHolo = new Hologram(
                () -> this.entranceLoc,
                this::buildEntranceText
        );
        this.entranceHolo.setYOffset(1.55);

        this.exitHolo = new Hologram(
                () -> this.exitLoc,
                () -> "§7Step within the §c§lcircle§7 to leave"
        );
        this.exitHolo.setYOffset(1.55);
    }

    public UUID getId() { return id; }
    public Location getEntranceLoc() { return entranceLoc; }

    public Location getDestination() { return destination; }
    public void setDestination(Location dest) {
        this.destination = (dest == null ? null : dest.clone());
        refreshHolograms(TrialRooms.getInstance());
    }

    public Location getExitLoc() { return exitLoc; }
    public void setExitLoc(Location exit) {
        this.exitLoc = (exit == null ? null : normalizeBlock(exit));
        refreshHolograms(TrialRooms.getInstance());
    }

    public String getKeyPath() { return keyPath; }
    public void setKeyPath(String keyPath) {
        this.keyPath = (keyPath == null ? null : keyPath.trim());
        refreshHolograms(TrialRooms.getInstance());
    }

    // ---------- Holograms control (called by manager on chunk load/unload) ----------
    public void spawnHolograms(JavaPlugin plugin) {
        // Entrance holo always
        try { entranceHolo.spawn(plugin); entranceHolo.refresh(plugin); } catch (Exception ignored) {}

        // Exit only if set
        if (exitLoc != null && exitLoc.getWorld() != null) {
            try { exitHolo.spawn(plugin); exitHolo.refresh(plugin); } catch (Exception ignored) {}
        } else {
            try { exitHolo.remove(plugin); } catch (Exception ignored) {}
        }
    }

    public void removeHolograms(JavaPlugin plugin) {
        try { entranceHolo.remove(plugin); } catch (Exception ignored) {}
        try { exitHolo.remove(plugin); } catch (Exception ignored) {}
    }

    public void refreshHolograms(JavaPlugin plugin) {
        // Cheap refresh (safe even if not spawned)
        try { entranceHolo.refresh(plugin); } catch (Exception ignored) {}
        if (exitLoc != null && exitLoc.getWorld() != null) {
            try { exitHolo.refresh(plugin); } catch (Exception ignored) {}
        }
    }

    // ---------- Text ----------
    private String buildEntranceText() {
        String nice = resolveKeyDisplayName();
        // Two lines, readable & crisp
        // Example: "Right-Click with Blaze Powder" / "to enter"
        return "§e§lRight-Click §7with a §6" + nice + "\n§7to enter";
    }

    private String resolveKeyDisplayName() {
        if (keyPath == null || keyPath.isBlank()) return ChatColor.RED + "Unset";

        ItemStack it = resolveItemFromPath(keyPath);
        if (it != null && it.getType() != Material.AIR) {
            if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) {
                return it.getItemMeta().getDisplayName();
            }
            return prettifyMaterial(it.getType());
        }
        return ChatColor.RED + "Unset";
    }

    // Try the method name you mentioned; fall back to the legacy we’ve used elsewhere.
    private ItemStack resolveItemFromPath(String path) {
        try {
            Object creator = TLibs.getItemAPI().getCreator();
            try {
                Method m = creator.getClass().getMethod("getItemStackFromPath", String.class);
                Object obj = m.invoke(creator, path);
                return (obj instanceof ItemStack) ? (ItemStack) obj : null;
            } catch (NoSuchMethodException ignored) {
                // Fall back to getItemFromPath
                try { return TLibs.getItemAPI().getCreator().getItemFromPath(path); }
                catch (Throwable ignored2) { return null; }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    public void tick() {
        if(entranceHolo != null) entranceHolo.tick();
        if(exitHolo != null) exitHolo.tick();
    }

    private String prettifyMaterial(Material m) {
        String s = m.name().toLowerCase().replace('_', ' ');
        if (s.isEmpty()) return "Item";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- Helpers ----
    public static Location normalizeBlock(Location in) {
        if (in == null) return null;
        return new Location(in.getWorld(), in.getBlockX(), in.getBlockY(), in.getBlockZ());
    }

    // Entrance.java (add at bottom)

    public Database.EntranceRecord toRecord() {
        return Database.EntranceRecord.of(
                id.toString(),
                Database.SimpleLocation.of(this.entranceLoc),
                Database.SimpleLocation.of(this.destination),
                Database.SimpleLocation.of(this.exitLoc),
                this.keyPath
        );
    }

    public static Entrance fromRecord(Database.EntranceRecord r) {
        if (r == null || r.entrance == null) return null;
        Location entrance = r.entrance.toBukkit();
        if (entrance == null) return null;

        Entrance e = new Entrance(entrance);
        if (r.destination != null) e.setDestination(r.destination.toBukkit());
        if (r.exit != null)        e.setExitLoc(r.exit.toBukkit());
        e.setKeyPath(r.keyPath);
        return e;
    }

}
