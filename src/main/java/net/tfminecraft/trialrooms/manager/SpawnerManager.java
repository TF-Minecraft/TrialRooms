package net.tfminecraft.trialrooms.manager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import io.lumine.mythic.bukkit.events.MythicDamageEvent;
import io.lumine.mythic.core.skills.projectiles.Projectile;
import me.Plugins.TLibs.TLibs;
import me.Plugins.TLibs.Objects.API.SubAPI.ItemChecker;
import net.tfminecraft.trialrooms.TrialRooms;
import net.tfminecraft.trialrooms.cache.Cache;
import net.tfminecraft.trialrooms.environment.door.DoorBlock;
import net.tfminecraft.trialrooms.environment.spawner.ActiveSpawner;
import net.tfminecraft.trialrooms.environment.spawner.Spawner;
import net.tfminecraft.trialrooms.environment.spawner.ActiveSpawner.State;
import net.tfminecraft.trialrooms.environment.spawner.ui.ActiveSpawnerEditor;
import net.tfminecraft.trialrooms.loader.SpawnerLoader;
import net.tfminecraft.trialrooms.persist.Database;
import net.tfminecraft.trialrooms.persist.Database.DoorRecord;

public class SpawnerManager implements Listener {

    private final JavaPlugin plugin;
    private static final Map<Location, ActiveSpawner> spawners = new HashMap<>();

    public SpawnerManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public List<ActiveSpawner> allActiveSpawners() {
        return new ArrayList<>(spawners.values());
    }

    public static ActiveSpawner get(String s) {
        for(ActiveSpawner spawner : spawners.values()) {
            if(spawner.getUUID().equalsIgnoreCase(s)) return spawner;
        }
        return null;
    }

    public static boolean hasEditWand(Player p) {
        ItemChecker checker = TLibs.getItemAPI().getChecker();
        return checker.checkItemWithPath(p.getInventory().getItemInMainHand(), Cache.editTool);
    }

    public void start() {
        slowTickCycle();
        tickCycle();
    }

    public static void resetCooldowns() {
        for(ActiveSpawner s : spawners.values()) {
            if(!s.getState().equals(State.COOLDOWN)) continue;
            s.setCooldownRemainingSeconds(1);
        }
    }

    public void slowTickCycle() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (ActiveSpawner s : spawners.values()) {
                s.slowTick();
            }
        }, 20L, 20L);
    }

    public void tickCycle() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (ActiveSpawner s : spawners.values()) {
                s.tick();
            }
        }, 0L, 1L);
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent e) {
        String spawnerId = e.getEntity().getPersistentDataContainer().get(
                new NamespacedKey(TrialRooms.getInstance(), "spawnerId"),
                PersistentDataType.STRING
        );
        if (spawnerId == null) return;

        ActiveSpawner s = get(spawnerId);
        if (s != null) {
            s.onEnemyDied();
        }
    }

    // ============== Creation / Editing ==============
    @EventHandler
    public void createSpawner(PlayerInteractEvent e) {
        if (!e.getAction().equals(Action.RIGHT_CLICK_BLOCK)) return;

        Player p = e.getPlayer();
        if (!hasEditWand(p)) return;
        if (e.getClickedBlock() == null) return;
        Location loc = e.getClickedBlock().getLocation();
        Spawner s = SpawnerLoader.getByBlock(e.getClickedBlock());
        if (s == null) return;

        if (ActiveSpawnerEditor.PENDING.get(p.getUniqueId()) != null) {
            p.sendMessage("§cError already editing a spawner!");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        ActiveSpawner active;
        if (spawners.containsKey(loc)) {
            active = spawners.get(loc);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1f);
        } else {
            active = new ActiveSpawner(loc, s);
            spawners.put(loc, active);
            // Spawn hologram immediately if the chunk is loaded
            if (loc.getWorld() != null && loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                active.spawnHolograms(plugin);
            }
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }
        if (loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            active.onLoaded(plugin);
        }
        ActiveSpawnerEditor.openEditor(p, active);
    }

    // ============== Chunk lifecycle ==============
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        Chunk chunk = e.getChunk();
        World world = e.getWorld();

        for (Map.Entry<Location, ActiveSpawner> entry : spawners.entrySet()) {
            Location loc = entry.getKey();
            if (!sameChunk(world, chunk, loc)) continue;
            entry.getValue().onLoaded(plugin); // sets loaded=true + spawns/refreshes hologram
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        Chunk chunk = e.getChunk();
        World world = e.getWorld();

        for (Map.Entry<Location, ActiveSpawner> entry : spawners.entrySet()) {
            Location loc = entry.getKey();
            if (!sameChunk(world, chunk, loc)) continue;
            entry.getValue().onUnloaded(plugin); // sets loaded=false + removes hologram
        }
    }


    // ============== Helpers ==============
    private boolean sameChunk(World world, Chunk chunk, Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        if (!loc.getWorld().equals(world)) return false;
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        return (chunk.getX() == cx && chunk.getZ() == cz);
    }

    /** Call this on plugin disable to remove all holograms safely. */
    public void removeAllHolograms() {
        for (ActiveSpawner s : spawners.values()) {
            s.removeHolograms(plugin);
        }
    }

    /** Optional: expose your map if needed elsewhere. */
    public Map<Location, ActiveSpawner> getSpawners() {
        return spawners;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSpawnerBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();

        if (e.getBlock() == null) return;
        Location loc = e.getBlock().getLocation();
        if(!spawners.containsKey(loc)) return;
        // Only act if we actually track a spawner at this location
        if (!hasEditWand(p)) {
            e.setCancelled(true);
            return; // only with the edit wand
        }
        ActiveSpawner removed = spawners.remove(loc);
        if (removed == null) return;

        // Clean up the hologram
        removed.destroy();

        // feedback
        p.sendMessage("§eSpawner removed");
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.7f, 1.2f);
    }

    // at top of SpawnerManager
    private static final NamespacedKey PDC_SPAWNER_LEVEL =
            new NamespacedKey(TrialRooms.getInstance(), "spawnerLevel");
    // (optional if you want a fallback) 
    private static final NamespacedKey PDC_SPAWNER_ID =
            new NamespacedKey(TrialRooms.getInstance(), "spawnerId");

    @EventHandler
    public void noFriendlyFire(EntityDamageByEntityEvent e){
        if(!(e.getDamager() instanceof Player && e.getEntity() instanceof Player)) return;
        Player target = (Player) e.getEntity();
        Player damager = (Player) e.getDamager();
        PlayerManager man = PlayerManager.get();
        if(man.get(damager).isInDungeon() && man.get(target).isInDungeon()) e.setCancelled(true);
    }

    // ...
    @org.bukkit.event.EventHandler
    public void onMobDealsDamage(MythicDamageEvent e) {
        // Only scale when a player is the victim
        if (!(e.getTarget().getBukkitEntity() instanceof Player)) return;
        Player p = (Player) e.getTarget().getBukkitEntity();

        LivingEntity attacker = null;

        if(attacker instanceof Player) {
            Player a = (Player) attacker;
            if(PlayerManager.get().get(p).isInDungeon() && PlayerManager.get().get(a).isInDungeon()) e.setCancelled(true);
        }

        // Direct melee attacker
        if (e.getCaster().getEntity().getBukkitEntity() instanceof LivingEntity le) {
            attacker = le;
        }

        if (attacker == null) return;
        if(!attacker.getPersistentDataContainer().has(PDC_SPAWNER_LEVEL, PersistentDataType.INTEGER)) return;

        // Fast path: use level we wrote onto the mob
        Integer lvl = attacker.getPersistentDataContainer().get(PDC_SPAWNER_LEVEL, PersistentDataType.INTEGER);

        // Optional fallback if you have a lookup by spawner UUID:
        // if (lvl == null) {
        //     String sid = attacker.getPersistentDataContainer().get(PDC_SPAWNER_ID, PersistentDataType.STRING);
        //     if (sid != null) {
        //         ActiveSpawner sp = getByUUID(sid); // implement if you have a map
        //         if (sp != null) lvl = sp.getLevel();
        //     }
        // }

        if (lvl == null || lvl <= 0) return;
        if (!PlayerManager.get().isInside(p)) {
            PlayerManager.get().ensureInside(p);
        }

        double extra = Cache.damagePerLevel * lvl;                 // +0.2 per level (additive)
        e.setDamage(e.getDamage() + extra);
    }

    @EventHandler
    public void onSpawnerMobDeath(EntityDeathEvent e) {
        var ent = e.getEntity();
        var pdc = ent.getPersistentDataContainer();
        String spawnerId = pdc.get(new NamespacedKey(TrialRooms.getInstance(), "spawnerId"),
                                PersistentDataType.STRING);
        if (spawnerId == null) return;

        ActiveSpawner sp = SpawnerManager.get(spawnerId); // assumes you have this
        if (sp == null) return;

        sp.maybeDropMobKey(ent.getLocation());
    }


    // SpawnerManager snippet
    public void hydrateFrom(List<Database.SpawnerRecord> records) {
        for (var r : records) {
            Location loc = (r.loc == null) ? null : r.loc.toBukkit();
            if (loc == null) continue;

            Spawner def = SpawnerLoader.getByString(r.id);
            if (def == null) continue;

            ActiveSpawner s = new ActiveSpawner(loc, def);
            s.setUUID(r.uuid);
            s.setMob(r.mob);
            s.setAmount(r.amount);
            s.setLevel(r.level);
            s.setSpawnRadius(r.spawnRadius);
            s.setActivateRadius(r.activateRadius);
            s.setLootTable(r.lootTable);
            s.setMobLootTable(r.mobLootTable);
            s.setCooldownSeconds(r.cooldownSeconds);

            // NEW: restore persisted state + remaining cooldown
            try {
                ActiveSpawner.State st = ActiveSpawner.State.valueOf(r.state); // ensure your record has "state"
                switch(st) {
                    case ACTIVE:
                        st = ActiveSpawner.State.COOLDOWN;
                        r.cooldownRemaining = r.cooldownSeconds;
                        break;
                    case COOLDOWN:
                        break;
                    case IDLE:
                        break;
                    case UNLOCKING:
                        st = ActiveSpawner.State.COOLDOWN;
                        r.cooldownRemaining = r.cooldownSeconds;
                        break;
                    default:
                        break;
                    
                }
                s.restoreState(st, r.cooldownRemaining);                       // ensure your record has "cooldownRemaining"
            } catch (Exception ignored) {
                // fallback if missing/invalid in older saves
                s.restoreState(ActiveSpawner.State.IDLE, 0);
            }

            for(DoorRecord dr : r.doors) {
                DoorBlock door = DoorBlock.fromRecord(dr);
                s.addDoorBlock(door);
                if(door.isInLoadedChunk()) door.enforceForState(false);
            }

            spawners.put(loc, s);
        }
        reconcileLoadedState(); // <-- important
    }
    
    private void reconcileLoadedState() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Map.Entry<Location, ActiveSpawner> e : spawners.entrySet()) {
                Location loc = e.getKey();
                World w = loc.getWorld();
                if (w == null) continue;
                int cx = loc.getBlockX() >> 4;
                int cz = loc.getBlockZ() >> 4;
                if (w.isChunkLoaded(cx, cz)) {
                    e.getValue().onLoaded(plugin); // sets loaded=true + spawns holos
                }
            }
        });
    }
}
