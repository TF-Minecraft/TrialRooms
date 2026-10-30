package net.tfminecraft.trialrooms.environment.spawner;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * A floating TextDisplay label above a TrialRooms block.
 *
 * <p>The display is a runtime-only entity: it is never saved with the chunk and is
 * recreated by {@link #tick()} while a player is within view range. Every display is
 * tagged with {@link #LABEL_KEY} so leftovers can be recognised by {@link LabelCleanup}.
 */
public class Hologram {
    /** PDC marker on every label display spawned by TrialRooms. */
    public static final NamespacedKey LABEL_KEY = new NamespacedKey("trialrooms", "label");

    /** Every live hologram, so the cleanup knows label positions and current displays. */
    private static final Set<Hologram> REGISTRY = Collections.newSetFromMap(new WeakHashMap<>());

    private final Supplier<Location> locationSupplier; // where to place it
    private final Supplier<String>   nameSupplier;     // what to display

    private UUID entityId;
    private double yOffset = 1.0; // height above block center

    // view radius squared (occlusion is handled by the engine; we keep radius for perf)
    private static final double VIEW_RADIUS_SQ = 96 * 96;

    public Hologram(Supplier<Location> locationSupplier, Supplier<String> nameSupplier) {
        this.locationSupplier = locationSupplier;
        this.nameSupplier = nameSupplier;
        REGISTRY.add(this);
    }

    public void setYOffset(double yOffset) {
        this.yOffset = yOffset;
    }

    /** Shows or updates the label now if a player can see it; otherwise {@link #tick()} shows it later. */
    public void spawn(JavaPlugin plugin) {
        runSync(plugin, this::update);
    }

    /** Updates text and position of the label, creating it only if a player can see it. */
    public void refresh(JavaPlugin plugin) {
        runSync(plugin, this::update);
    }

    public void remove(JavaPlugin plugin) {
        runSync(plugin, this::removeCurrent);
    }

    /** Distance-based lifecycle (spawn near players, despawn when nobody nearby). */
    public void tick() {
        Location pos = position();
        if (pos == null) return;

        TextDisplay td = findDisplay();
        if (anyPlayerNear(pos) && isChunkLoaded(pos)) {
            if (td == null) {
                create(pos);
            } else {
                // keep text up to date in case suppliers change frequently
                applyText(td);
            }
        } else if (td != null) {
            removeCurrent();
        }
    }

    // ===== Registry (used by LabelCleanup) =====

    /** Current label positions of all live holograms (null positions skipped). */
    static List<Location> labelPositions() {
        List<Location> out = new ArrayList<>();
        for (Hologram h : new ArrayList<>(REGISTRY)) {
            Location pos;
            try {
                pos = h.position();
            } catch (IllegalArgumentException worldUnloaded) {
                continue; // Location#getWorld throws once its world was unloaded
            }
            if (pos != null) out.add(pos);
        }
        return out;
    }

    /** UUIDs of the displays the live holograms currently own. */
    static Set<UUID> ownedDisplayIds() {
        Set<UUID> out = new HashSet<>();
        for (Hologram h : new ArrayList<>(REGISTRY)) {
            if (h.entityId != null) out.add(h.entityId);
        }
        return out;
    }

    // ===== Internals =====

    private void update() {
        Location pos = position();
        if (pos == null) return;
        if (!isChunkLoaded(pos)) {
            removeCurrent();
            return;
        }

        TextDisplay td = findDisplay();
        if (td == null) {
            if (!anyPlayerNear(pos)) return;
            create(pos);
            return;
        }
        td.teleport(pos);
        applyText(td);
    }

    private void removeCurrent() {
        TextDisplay td = findDisplay();
        if (td != null) td.remove();
        entityId = null;
    }

    private TextDisplay create(Location pos) {
        World world = pos.getWorld();
        // A display we lost track of (for example after a plugin reload) must not stay next to the new one.
        LabelCleanup.removeLeftovers(world, pos.getBlockX() >> 4, pos.getBlockZ() >> 4,
                world.getNearbyEntitiesByType(TextDisplay.class, pos, LabelCleanup.SEARCH_RADIUS));

        TextDisplay spawned = world.spawn(pos, TextDisplay.class, td -> {
            td.setInvulnerable(true);
            // Runtime-only: tick() recreates it, so it must never be saved with the chunk.
            td.setPersistent(false);
            td.setGravity(false);
            td.getPersistentDataContainer().set(LABEL_KEY, PersistentDataType.BYTE, (byte) 1);
            // Nice defaults (safe across recent Paper/Bukkit versions):
            try { td.setBillboard(org.bukkit.entity.Display.Billboard.CENTER); } catch (Throwable ignored) {}
            try { td.setShadowed(true); } catch (Throwable ignored) {}
            try { td.setSeeThrough(false); } catch (Throwable ignored) {}
            applyText(td);
        });
        entityId = spawned.getUniqueId();
        return spawned;
    }

    private TextDisplay findDisplay() {
        if (entityId == null) return null;
        Entity e = Bukkit.getEntity(entityId);
        return (e instanceof TextDisplay td && !td.isDead()) ? td : null;
    }

    private Location position() {
        Location base = locationSupplier.get();
        if (base == null || base.getWorld() == null) return null;
        return base.clone().add(0.5, yOffset, 0.5);
    }

    private static boolean isChunkLoaded(Location pos) {
        return pos.getWorld().isChunkLoaded(pos.getBlockX() >> 4, pos.getBlockZ() >> 4);
    }

    private static boolean anyPlayerNear(Location pos) {
        for (var p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld() == pos.getWorld() && p.getLocation().distanceSquared(pos) <= VIEW_RADIUS_SQ) {
                return true;
            }
        }
        return false;
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
}
