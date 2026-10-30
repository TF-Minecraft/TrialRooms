package net.tfminecraft.trialrooms.environment.spawner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.trialrooms.TrialRooms;

/**
 * Removes TrialRooms label displays that are not the current one of their label.
 *
 * <p>Older versions saved every label display with the chunk and spawned a new one after
 * each restart or chunk reload, so thousands of identical copies piled up. A display is
 * only removed when all of these hold:
 * <ul>
 *   <li>it is a {@link TextDisplay} that rides nothing;</li>
 *   <li>it sits exactly (within {@link #POSITION_EPSILON}) on the position of a live
 *       TrialRooms label in the same world and chunk;</li>
 *   <li>no live label currently owns it;</li>
 *   <li>it carries {@link Hologram#LABEL_KEY}, or it is a legacy label: persistent, with
 *       an empty PersistentDataContainer and no scoreboard tags.</li>
 * </ul>
 */
public final class LabelCleanup implements Listener {

    private record ChunkKey(World world, int x, int z) {}

    /** Largest coordinate difference still counted as "the label position". */
    static final double POSITION_EPSILON = 1.0e-3;
    /** Radius of the entity lookup around a label before it spawns its display. */
    static final double SEARCH_RADIUS = 0.25;

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        Chunk chunk = e.getChunk();
        removeLeftovers(chunk.getWorld(), chunk.getX(), chunk.getZ(), e.getEntities());
    }

    /**
     * Cleans the chunks of all live labels whose entities are already loaded.
     * Chunks that load later are handled by {@link #onEntitiesLoad(EntitiesLoadEvent)}.
     *
     * @return the number of displays removed
     */
    public static int sweepLoadedChunks() {
        Set<ChunkKey> chunks = new LinkedHashSet<>();
        for (Location pos : Hologram.labelPositions()) {
            chunks.add(new ChunkKey(pos.getWorld(), pos.getBlockX() >> 4, pos.getBlockZ() >> 4));
        }
        int removed = 0;
        for (ChunkKey key : chunks) {
            if (!key.world().isChunkLoaded(key.x(), key.z())) continue;
            Chunk chunk = key.world().getChunkAt(key.x(), key.z());
            if (!chunk.isEntitiesLoaded()) continue;
            removed += removeLeftovers(key.world(), key.x(), key.z(), List.of(chunk.getEntities()));
        }
        return removed;
    }

    /**
     * Removes leftover label displays among {@code entities} for the labels in chunk
     * ({@code cx}, {@code cz}) of {@code world}, logging one line when any are removed.
     *
     * @return the number of displays removed
     */
    static int removeLeftovers(World world, int cx, int cz, Collection<? extends Entity> entities) {
        List<TextDisplay> displays = new ArrayList<>();
        for (Entity entity : entities) {
            if (entity instanceof TextDisplay td) displays.add(td);
        }
        if (displays.isEmpty()) return 0;

        List<Location> labels = new ArrayList<>();
        for (Location pos : Hologram.labelPositions()) {
            if (world.equals(pos.getWorld()) && (pos.getBlockX() >> 4) == cx && (pos.getBlockZ() >> 4) == cz) {
                labels.add(pos);
            }
        }
        if (labels.isEmpty()) return 0;

        Set<UUID> owned = Hologram.ownedDisplayIds();
        int removed = 0;
        for (TextDisplay td : displays) {
            if (owned.contains(td.getUniqueId())) continue;
            if (!isLeftoverLabel(td, labels)) continue;
            td.remove();
            removed++;
        }
        if (removed > 0) {
            TrialRooms.getInstance().getLogger().info("Removed " + removed + " leftover label displays at "
                    + world.getName() + " chunk " + cx + "," + cz + " (blocks " + (cx << 4) + "," + (cz << 4) + ")");
        }
        return removed;
    }

    /** Whether {@code td} is a TrialRooms label display sitting on one of {@code labels}. */
    static boolean isLeftoverLabel(TextDisplay td, List<Location> labels) {
        if (td.isDead() || td.getVehicle() != null) return false;
        PersistentDataContainer pdc = td.getPersistentDataContainer();
        if (!pdc.has(Hologram.LABEL_KEY, PersistentDataType.BYTE) && !isLegacyLabel(td, pdc)) return false;
        Location at = td.getLocation();
        for (Location label : labels) {
            if (samePosition(at, label)) return true;
        }
        return false;
    }

    private static boolean isLegacyLabel(TextDisplay td, PersistentDataContainer pdc) {
        return td.isPersistent() && pdc.isEmpty() && td.getScoreboardTags().isEmpty();
    }

    static boolean samePosition(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && Math.abs(a.getX() - b.getX()) <= POSITION_EPSILON
                && Math.abs(a.getY() - b.getY()) <= POSITION_EPSILON
                && Math.abs(a.getZ() - b.getZ()) <= POSITION_EPSILON;
    }
}
