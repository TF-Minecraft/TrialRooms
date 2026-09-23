package net.tfminecraft.trialrooms.manager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.tfminecraft.trialrooms.TrialRooms;
import net.tfminecraft.trialrooms.player.PlayerData;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerManager implements Listener {

    private static PlayerManager INSTANCE;
    public static PlayerManager get() { return INSTANCE; }

    private final JavaPlugin plugin;
    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();

    public PlayerManager(JavaPlugin plugin) {
        this.plugin = plugin;
        INSTANCE = this;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public PlayerData get(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> new PlayerData());
    }

    public void markEntered(Player p, java.util.UUID entranceId) {
        get(p).enter(entranceId);
        // Optional feedback:
        // p.sendMessage("§8[§cTrial§8] §7Your natural healing is suppressed in the dungeon.");
    }

    /** Use when you just want to ensure they’re considered “inside” (e.g., spawner activation). */
    public void ensureInside(Player p) { get(p).ensureInside(); }

    public void markExited(Player p) { get(p).exit(); }

    public boolean isInside(Player p) { return get(p).isInDungeon(); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRegen(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!isInside(p)) return;
        if (e.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED) {
            e.setCancelled(true); // disable saturation-based regen while inside
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onQuit(PlayerQuitEvent e) {
        // Clean slate on quit
        data.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDeath(PlayerDeathEvent e) {
        // Dying inside/outside—either way, stop tracking
        data.remove(e.getEntity().getUniqueId());
    }

    /** Call once on plugin enable: new PlayerManager(TrialRooms.getInstance()); */
}
