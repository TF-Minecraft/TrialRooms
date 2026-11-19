package net.tfminecraft.trialrooms.dungeon.run;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import net.tfminecraft.rpcharacters.lifecycle.CharacterActivatedEvent;
import net.tfminecraft.rpcharacters.lifecycle.CharacterSelectionRequestEvent;

/**
 * Turns server events into run decisions: no friendly fire, downed instead of dead, the dungeon
 * world's rules, gates, and sending back anyone who doesn't belong in the dungeon world.
 */
public final class RunListener implements Listener {

    /** Players with this may build and use any command in the dungeon world. */
    public static final String BUILD_PERMISSION = "trialrooms.admin";

    private final Plugin plugin;
    private final RunManager runs;

    public RunListener(Plugin plugin, RunManager runs) {
        this.plugin = plugin;
        this.runs = runs;
    }

    /** The player behind a hit: the attacker, or whoever shot the projectile. */
    static Player attacker(EntityDamageEvent event) {
        if (event.getDamageSource().getCausingEntity() instanceof Player p) {
            return p;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Player p) {
                return p;
            }
            if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
                return p;
            }
        }
        return null;
    }

    /** First look at every hit: downed players can't be hurt or hurt others, and players never hurt players. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent event) {
        if (!runs.inDungeonWorld(event.getEntity())) {
            return;
        }
        Player attacker = attacker(event);
        if (attacker != null && runs.isDowned(attacker)) {
            event.setCancelled(true);
            return;
        }
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (attacker != null && !attacker.equals(victim)) {
            event.setCancelled(true);
            return;
        }
        if (runs.isDowned(victim)) {
            event.setCancelled(true);
            if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
                victim.teleport(runs.downedSpot(victim));
            }
        }
    }

    /** The entity behind a hit: whoever caused it, the attacker, or whoever shot the projectile. */
    static Entity source(EntityDamageEvent event) {
        Entity causing = event.getDamageSource().getCausingEntity();
        if (causing != null) {
            return causing;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
                return shooter;
            }
            return damager;
        }
        return null;
    }

    /** Run enemies hit as hard as their fight says: rallied, enraged, or not at all while charging. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEnemyHit(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player) || !runs.inDungeonWorld(event.getEntity())) {
            return;
        }
        Entity source = source(event);
        if (source == null) {
            return;
        }
        double factor = runs.outgoingFactor(source);
        if (factor != 1) {
            event.setDamage(event.getDamage() * factor);
        }
    }

    /**
     * Hits on run enemies: none land on a boss between phases, a shielded elite takes less, and no
     * single hit takes a boss past its next phase.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnemyHurt(EntityDamageEvent event) {
        Entity mob = event.getEntity();
        if (!runs.inDungeonWorld(mob) || !runs.isRunMob(mob)) {
            return;
        }
        double factor = runs.incomingFactor(mob);
        if (factor <= 0) {
            event.setCancelled(true);
            return;
        }
        if (factor != 1) {
            event.setDamage(event.getDamage() * factor);
        }
        double cap = runs.damageCap(mob);
        double dealt = event.getFinalDamage();
        if (dealt > cap) {
            if (cap <= 0) {
                event.setCancelled(true);
                return;
            }
            event.setDamage(event.getDamage() * cap / dealt);
        }
    }

    /** Counts what players dealt to run enemies, and that the fight is moving. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnemyHurtDone(EntityDamageEvent event) {
        if (runs.inDungeonWorld(event.getEntity()) && runs.isRunMob(event.getEntity())) {
            runs.mobHurt(event.getEntity(), attacker(event), event.getFinalDamage());
        }
    }

    /** Summons and other spawns inside a run are cleaned up with it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(org.bukkit.event.entity.CreatureSpawnEvent event) {
        if (runs.inDungeonWorld(event.getEntity())) {
            runs.spawned(event.getEntity());
        }
    }

    /**
     * A lethal hit on a run member downs them instead. This runs before RPCharacters' own
     * knockout handler (HIGHEST, skips cancelled hits), so neither its knockouts nor deaths happen.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLethal(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p) || runs.runOf(p) == null || !runs.inDungeonWorld(p)) {
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            Location back = runs.rescuePoint(p);
            if (back != null) {
                event.setCancelled(true);
                p.setFallDistance(0f);
                p.teleport(back);
            }
            return;
        }
        if (p.getHealth() - event.getFinalDamage() <= 0 && runs.down(p)) {
            event.setCancelled(true);
        }
    }

    /** A death in the dungeon world (only /kill and the like get through): keep everything, no grave. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        if (!runs.inDungeonWorld(p)) {
            return;
        }
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        runs.died(p);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Location at = runs.respawnPoint(event.getPlayer());
        if (at != null) {
            event.setRespawnLocation(at);
            later(() -> runs.respawned(event.getPlayer()));
        }
    }

    /** Downed players stay where they fell; they may look around. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if ((to.getX() != from.getX() || to.getY() != from.getY() || to.getZ() != from.getZ())
                && runs.isDowned(event.getPlayer())) {
            Location stay = from.clone();
            stay.setYaw(to.getYaw());
            stay.setPitch(to.getPitch());
            event.setTo(stay);
        }
    }

    /** Downed players don't heal on their own; only a revive or a bleed-out brings them back. */
    @EventHandler(ignoreCancelled = true)
    public void onRegain(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player p && runs.isDowned(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onMobDeath(EntityDeathEvent event) {
        if (runs.isRunMob(event.getEntity())) {
            // No loot from run mobs until dungeon loot joins the act system.
            event.getDrops().clear();
            event.setDroppedExp(0);
            runs.mobDied(event.getEntity());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        runs.quit(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // A teleport during the join event is lost on 1.21.10; wait a tick.
        later(() -> runs.sweep(event.getPlayer()));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        later(() -> runs.sweep(event.getPlayer()));
    }

    @EventHandler
    public void onCharacter(CharacterActivatedEvent event) {
        later(() -> {
            runs.giveRefunds(event.getOwner());
            runs.sweep(event.getOwner());
        });
    }

    @EventHandler
    public void onCharacterSwitch(CharacterSelectionRequestEvent event) {
        if (runs.inRun(event.getPlayer())) {
            event.deny("You can't change character inside a dungeon. Leave first with /tr dungeon leave.");
        }
    }

    /** Gates come first: a gate block does nothing else, whatever other plugins think of the click. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player p = event.getPlayer();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            GateStore.Gate gate = runs.gates().at(event.getClickedBlock());
            if (gate != null) {
                event.setCancelled(true);
                runs.useGate(p, gate);
                return;
            }
        }
        if ((event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR)
                && runs.inDungeonWorld(p) && runs.tryExit(p)) {
            event.setCancelled(true);
        }
    }

    // ---- dungeon world rules ----

    private boolean restricted(Player p) {
        return runs.inDungeonWorld(p) && !p.hasPermission(BUILD_PERMISSION);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if ((cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || cause == PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT
                || cause == PlayerTeleportEvent.TeleportCause.SPECTATE) && restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (event.getEntity() instanceof Player p && restricted(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() == InventoryType.ENDER_CHEST && event.getPlayer() instanceof Player p
                && restricted(p)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (restricted(event.getPlayer()) && runs.blocksCommand(event.getMessage())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cThat doesn't work inside a dungeon. Leave with /tr dungeon leave.");
        }
    }

    private void later(Runnable r) {
        plugin.getServer().getScheduler().runTask(plugin, r);
    }
}
