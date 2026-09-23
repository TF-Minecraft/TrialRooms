package net.tfminecraft.trialrooms.environment.spawner;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.function.Supplier;

public class Hologram {
    private final Supplier<Location> locationSupplier; // where to place it
    private final Supplier<String>   nameSupplier;     // what to display

    private UUID entityId;
    private double yOffset = 1.0; // height above block center

    // view radius squared (occlusion is handled by the engine; we keep radius for perf)
    private static final double VIEW_RADIUS_SQ = 96 * 96;

    public Hologram(Supplier<Location> locationSupplier, Supplier<String> nameSupplier) {
        this.locationSupplier = locationSupplier;
        this.nameSupplier = nameSupplier;
    }

    public void setYOffset(double yOffset) {
        this.yOffset = yOffset;
    }

    public void spawn(JavaPlugin plugin) {
        runSync(plugin, () -> {
            TextDisplay td = getOrCreateDisplay();
            if (td == null) return;
            Location pos = position();
            if (pos != null) td.teleport(pos);
            applyText(td);
        });
    }

    public void refresh(JavaPlugin plugin) {
        runSync(plugin, () -> {
            TextDisplay td = findDisplay();
            if (td == null || td.isDead()) {
                td = getOrCreateDisplay();
                if (td == null) return;
            }
            Location pos = position();
            if (pos != null) td.teleport(pos);
            applyText(td);
        });
    }

    public void remove(JavaPlugin plugin) {
        runSync(plugin, () -> {
            TextDisplay td = findDisplay();
            if (td != null && !td.isDead()) td.remove();
            entityId = null;
        });
    }

    /** Distance-based lifecycle (spawn near players, despawn when nobody nearby). */
    public void tick() {
        Location pos = position();
        if (pos == null) return;

        TextDisplay td = findDisplay();

        boolean anyNearby = false;
        for (var p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld() == pos.getWorld() && p.getLocation().distanceSquared(pos) <= VIEW_RADIUS_SQ) {
                anyNearby = true; break;
            }
        }

        if (anyNearby) {
            if (td == null || td.isDead()) {
                spawn(plugin());
            } else {
                // keep text up to date in case suppliers change frequently
                applyText(td);
            }
        } else if (td != null) {
            remove(plugin());
        }
    }

    // ===== Internals =====

    private TextDisplay getOrCreateDisplay() {
        Location base = locationSupplier.get();
        if (base == null) return null;
        World world = base.getWorld();
        if (world == null) return null;

        TextDisplay existing = findDisplay();
        if (existing != null && !existing.isDead()) return existing;

        Location pos = position();
        TextDisplay spawned = world.spawn(pos, TextDisplay.class, td -> {
            td.setInvulnerable(true);
            td.setPersistent(true);
            td.setGravity(false);
            // Nice defaults (safe across recent Paper/Bukkit versions):
            try { td.setBillboard(org.bukkit.entity.Display.Billboard.CENTER); } catch (Throwable ignored) {}
            try { td.setShadowed(true); } catch (Throwable ignored) {}
            try { td.setSeeThrough(false); } catch (Throwable ignored) {}
            // optional: remove the dark background if your server build supports it
            // try { td.setBackgroundColor(org.joml.Vector4f...); } catch (Throwable ignored) {}
            applyText(td);
        });
        entityId = spawned.getUniqueId();
        return spawned;
    }

    private TextDisplay findDisplay() {
        if (entityId == null) return null;
        Entity e = Bukkit.getEntity(entityId);
        return (e instanceof TextDisplay td) ? td : null;
    }

    private Location position() {
        Location base = locationSupplier.get();
        if (base == null) return null;
        return base.clone().add(0.5, yOffset, 0.5);
    }

    private void applyText(TextDisplay td) {
        String raw = nameSupplier.get();
        if (raw == null) raw = "";
        td.setText(raw);
    }

    private void runSync(JavaPlugin plugin, Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(plugin, r);
    }

    private JavaPlugin plugin() {
        // Replace with your plugin singleton if you like; this avoids passing it around for tick()
        return net.tfminecraft.trialrooms.TrialRooms.getInstance();
    }
}
