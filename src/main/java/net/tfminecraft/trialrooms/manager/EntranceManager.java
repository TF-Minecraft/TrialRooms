package net.tfminecraft.trialrooms.manager;

import me.Plugins.TLibs.TLibs;
import me.Plugins.TLibs.Objects.API.SubAPI.StringFormatter;
import net.tfminecraft.DenarEconomy.DenarEconomy;
import net.tfminecraft.trialrooms.TrialRooms;
import net.tfminecraft.trialrooms.cache.Cache;
import net.tfminecraft.trialrooms.environment.entrance.Entrance;
import net.tfminecraft.trialrooms.environment.entrance.ui.EntranceEditor;
import net.tfminecraft.trialrooms.persist.Database;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.*;

public final class EntranceManager implements Listener {

    // ---------- Singleton ----------
    private static EntranceManager INSTANCE;
    public static EntranceManager get() {
        if (INSTANCE == null) INSTANCE = new EntranceManager();
        return INSTANCE;
    }

    private EntranceManager() {
        // Tick task: particles + exit checks every tick
        new BukkitRunnable() {
            @Override public void run() {
                try {
                    tickParticles();
                    checkExitTeleports(); // NEW
                    for(Entrance ent : entrances.values()) ent.tick();
                } catch (Throwable ignored) {}
            }
        }.runTaskTimer(TrialRooms.getInstance(), 0L, 1L); // every tick
    }

    // ---------- Exiting (poll every tick; no PlayerMoveEvent) ----------
    private void checkExitTeleports() {
        long now = System.currentTimeMillis();
        final double radius = 1.5;                 // as requested
        final double r2 = radius * radius;

        for (Entrance ent : entrances.values()) {
            Location exit = ent.getExitLoc();
            if (exit == null) continue;

            World w = exit.getWorld();
            if (w == null) continue;
            if (!w.isChunkLoaded(exit.getBlockX() >> 4, exit.getBlockZ() >> 4)) continue;

            // Use a small AABB query to avoid scanning all players in the world
            Location center = exit.clone().add(0.5, 0.0, 0.5);
            for (org.bukkit.entity.Entity e : w.getNearbyEntities(center, radius, 1.5, radius)) {
                if (!(e instanceof Player p)) continue;

                // Cooldown so you don’t bounce back immediately
                Long last = lastTeleportAt.get(p.getUniqueId());
                if (last != null && now - last < TELEPORT_COOLDOWN_MS) continue;

                // Distance check (circle, not square AABB)
                if (p.getLocation().distanceSquared(center) > r2) continue;

                Location entrance = ent.getEntranceLoc();
                if (entrance == null || entrance.getWorld() == null) continue;

                playWarpFx(p.getLocation());
                p.teleport(entrance.clone().add(0.5, 1.0, 0.5));
                playArriveFx(p.getLocation());
                lastTeleportAt.put(p.getUniqueId(), now);
                PlayerManager.get().markExited(p);
                p.sendMessage("§8[§aTrial§8] §7You leave the dungeon. Natural healing returns.");
                try {
                    double total = 0.0;
                    int count = 0;

                    ItemStack[] contents = p.getInventory().getContents();
                    for (int slot = 0; slot < contents.length; slot++) {
                        ItemStack it = contents[slot];
                        if (it == null || it.getType() == Material.AIR) continue;

                        double perItem = Cache.getConversionAmount(it);
                        if (perItem <= 0) continue;

                        int amt = it.getAmount();
                        total += perItem * amt;
                        count += amt;

                        p.getInventory().setItem(slot, null);
                    }
                    p.updateInventory();

                    // round to 2 decimals
                    double conversions = Math.round(total * 100.0d) / 100.0d;

                    if (conversions > 0.0d) {
                        p.sendMessage("§eConverted §c" + count + " items §e to §6" 
                                    + String.format(java.util.Locale.US, "%.2f", conversions) + "d");
                        DenarEconomy.getMoneyManager().addMoney(p, conversions, false, true);
                    }
                } catch (Throwable ex) {
                    TrialRooms.getInstance().getLogger().warning("Conversion failed: " + ex.getMessage());
                }
            }
        }
    }

    // ---------- Storage ----------
    // Entrances keyed by normalized entrance block location
    private final Map<Location, Entrance> entrances = new HashMap<>();

    // small cooldown so you don't get instant-re-ported by exit area
    private final Map<UUID, Long> lastTeleportAt = new HashMap<>();
    private static final long TELEPORT_COOLDOWN_MS = 600;

    public Map<Location, Entrance> all() { return Collections.unmodifiableMap(entrances); }

    public Entrance get(Location loc) { return entrances.get(Entrance.normalizeBlock(loc)); }

    // when registering or removing an entrance
    public Entrance registerOrGet(Location lodestoneBlockLoc) {
        Location key = Entrance.normalizeBlock(lodestoneBlockLoc);
        Entrance ent = entrances.computeIfAbsent(key, k -> {
            Entrance ne = new Entrance(k);
            markDirty();
            return ne;
        });
        // spawn holo if already loaded
        if (key.getWorld() != null && key.getWorld().isChunkLoaded(key.getBlockX() >> 4, key.getBlockZ() >> 4)) {
            ent.spawnHolograms(TrialRooms.getInstance());
        }
        return ent;
    }

    public void remove(Location lodestoneBlockLoc) {
        Entrance removed = entrances.remove(Entrance.normalizeBlock(lodestoneBlockLoc));
        if (removed != null) {
            removed.removeHolograms(TrialRooms.getInstance());
            markDirty();
        }
    }

    public void edited(Entrance e) { markDirty(); }


    // ---------- Editor entry: Right-click Lodestone with edit wand ----------
    @EventHandler
    public void onRightClickLodestoneToEdit(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;
        if (e.getHand() != EquipmentSlot.HAND) return;

        if (!SpawnerManager.hasEditWand(e.getPlayer())) return;
        if (e.getClickedBlock().getType() != Material.LODESTONE) return;

        e.setCancelled(true);

        Location clicked = Entrance.normalizeBlock(e.getClickedBlock().getLocation());
        Entrance existing = entrances.get(clicked);
        if (existing != null) {
            // Already registered here → just open the editor
            EntranceEditor.open(e.getPlayer(), existing);
            return;
        }

        // Proximity check: disallow if another entrance/exit/destination is within 4 blocks
        if (isNearExistingEntranceParts(clicked, 4.0)) {
            e.getPlayer().sendMessage(ChatColor.RED + "Too close to another entrance/exit/destination. "
                    + ChatColor.GRAY + "(Need at least 4 blocks of spacing.)");
            return;
        }

        Entrance ent = registerOrGet(clicked);
        e.getPlayer().sendMessage(ChatColor.GREEN + "Entrance registered.");
        EntranceEditor.open(e.getPlayer(), ent);
    }

    private boolean isNearExistingEntranceParts(Location candidateBlock, double minDist) {
        if (candidateBlock == null || candidateBlock.getWorld() == null) return false;
        double r2 = minDist * minDist;

        for (Entrance e : entrances.values()) {
            // entrance
            if (sameWorldAndWithin(e.getEntranceLoc(), candidateBlock, r2)) return true;
            // exit
            if (sameWorldAndWithin(e.getExitLoc(), candidateBlock, r2)) return true;
            // destination (compare block position)
            if (e.getDestination() != null) {
                Location destBlock = Entrance.normalizeBlock(e.getDestination());
                if (sameWorldAndWithin(destBlock, candidateBlock, r2)) return true;
            }
        }
        return false;
    }

    private boolean sameWorldAndWithin(Location a, Location b, double r2) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) return false;
        if (!a.getWorld().equals(b.getWorld())) return false;
        return a.distanceSquared(b) <= r2;
    }


    // ---------- Using the Entrance (with key) ----------
    @EventHandler(priority = EventPriority.NORMAL)
    public void onUseEntranceWithKey(PlayerInteractEvent e) {
        // Player right-clicks (air or block) near an entrance (≤ 1 block)
        if (SpawnerManager.hasEditWand(e.getPlayer())) return; // ignore when editing

        Action a = e.getAction();
        if (!(a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK)) return;

        Player p = e.getPlayer();
        Entrance ent = nearestEntranceWithin(p.getLocation(), 1.5);
        if (ent == null) return;

        // Need key item
        String keyPath = ent.getKeyPath();
        if (keyPath == null || keyPath.isBlank()) {
            p.sendMessage(ChatColor.RED + "This entrance requires a key item to use.");
            return;
        }

        if (!handMatchesPath(p, keyPath)) {
            p.sendMessage(ChatColor.RED + "You need " + ChatColor.GOLD + keyPath + ChatColor.RED + " to use this entrance.");
            return;
        }

        Location dest = ent.getDestination();
        if (dest == null || dest.getWorld() == null) {
            p.sendMessage(ChatColor.RED + "This entrance has no destination set.");
            return;
        }

        e.setCancelled(true);
        consumeOne(p, EquipmentSlot.HAND);

        // FX + teleport
        playWarpFx(p.getLocation());
        p.teleport(dest.clone().add(0.5, 1.0, 0.5));
        playArriveFx(p.getLocation());
        lastTeleportAt.put(p.getUniqueId(), System.currentTimeMillis());
        // after teleport + lastTeleportAt.put(...)
        PlayerManager.get().markEntered(p, ent.getId());
        p.sendMessage("§8[§cTrial§8] §7You feel an oppressive force. §oNatural healing is suppressed.");
    }

    // ---------- Particles ----------
    private void tickParticles() {
        for (Entrance ent : entrances.values()) {
            drawRing(ent.getEntranceLoc(), Color.fromRGB(0x55, 0xFF, 0x55)); // green
            if (ent.getExitLoc() != null) {
                drawRing(ent.getExitLoc(), Color.fromRGB(0xFF, 0x55, 0x55)); // red
            }
        }
    }

    private void drawRing(Location centerBlock, Color color) {
        if (centerBlock == null || centerBlock.getWorld() == null) return;
        World w = centerBlock.getWorld();
        if (!w.isChunkLoaded(centerBlock.getBlockX() >> 4, centerBlock.getBlockZ() >> 4)) return;

        Location c = centerBlock.clone().add(0.5, 1.1, 0.5);
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.2f);
        int points = 16;
        double r = 1.5;
        for (int i = 0; i < points; i++) {
            double a = (Math.PI * 2 * i) / points;
            double x = c.getX() + Math.cos(a) * r;
            double z = c.getZ() + Math.sin(a) * r;
            w.spawnParticle(Particle.REDSTONE, x, c.getY(), z, 1, 0, 0, 0, 0, dust);
        }
    }

    // ---------- Helpers ----------
    private Entrance nearestEntranceWithin(Location loc, double radius) {
        if (loc == null || loc.getWorld() == null) return null;
        double r2 = radius * radius;
        Entrance best = null;
        double bestD2 = Double.MAX_VALUE;
        for (Entrance e : entrances.values()) {
            if (e.getEntranceLoc() == null || !loc.getWorld().equals(e.getEntranceLoc().getWorld())) continue;
            double d2 = loc.distanceSquared(e.getEntranceLoc().clone().add(0.5, 0, 0.5));
            if (d2 <= r2 && d2 < bestD2) { best = e; bestD2 = d2; }
        }
        return best;
    }

    private boolean handMatchesPath(Player p, String path) {
        try {
            return TLibs.getItemAPI().getChecker().checkItemWithPath(p.getInventory().getItemInMainHand(), path);
        } catch (Exception ex) {
            return false;
        }
    }

    private void consumeOne(Player p, EquipmentSlot slot) {
        ItemStack stack = (slot == EquipmentSlot.HAND)
                ? p.getInventory().getItemInMainHand()
                : p.getInventory().getItemInOffHand();
        if (stack == null) return;
        int amt = stack.getAmount();
        if (amt <= 1) {
            if (slot == EquipmentSlot.HAND) p.getInventory().setItemInMainHand(null);
            else p.getInventory().setItemInOffHand(null);
        } else {
            stack.setAmount(amt - 1);
        }
        p.updateInventory();
    }

    @EventHandler
    public void onBreakEntranceBits(org.bukkit.event.block.BlockBreakEvent e) {
        Block b = e.getBlock();
        Location broken = Entrance.normalizeBlock(b.getLocation());
        Player p = e.getPlayer();

        // 1) Exact entrance block? → remove the entrance entirely
        Entrance ent = entrances.remove(broken);
        if (ent != null) {
            try { ent.removeHolograms(TrialRooms.getInstance()); } catch (Exception ignored) {}
            p.sendMessage(ChatColor.YELLOW + "Entrance removed.");
            return;
        }

        // 2) Exit / Destination of any entrance? → null it
        for (Entrance other : entrances.values()) {
            // Exit?
            if (other.getExitLoc() != null && broken.equals(other.getExitLoc())) {
                other.setExitLoc(null);
                try { other.spawnHolograms(TrialRooms.getInstance()); } catch (Exception ignored) {} // refresh text
                p.sendMessage(ChatColor.YELLOW + "Exit cleared for that entrance.");
                return;
            }
            // Destination? (compare block of destination)
            if (other.getDestination() != null) {
                Location destBlock = Entrance.normalizeBlock(other.getDestination());
                if (broken.equals(destBlock)) {
                    other.setDestination(null);
                    try { other.spawnHolograms(TrialRooms.getInstance()); } catch (Exception ignored) {}
                    p.sendMessage(ChatColor.YELLOW + "Destination cleared for that entrance.");
                    return;
                }
            }
        }
    }

    private static final NamespacedKey PDC_CONV_TAG =
        new NamespacedKey(TrialRooms.getInstance(), "convTagged");

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickupConvertibleItem(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        Item itemEnt = e.getItem();
        ItemStack stack = itemEnt.getItemStack();
        if (stack == null || stack.getType() == Material.AIR) return;

        // Is this item convertible?
        double perItem = Cache.getConversionAmount(stack);
        if (perItem <= 0) return;

        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;

        // Already tagged? Don't add duplicate lore.
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(PDC_CONV_TAG, PersistentDataType.BYTE)) return;

        // Build/extend lore: always add a separator line, then the info line
        java.util.List<String> lore = meta.hasLore()
                ? new java.util.ArrayList<>(meta.getLore())
                : new java.util.ArrayList<>();

        // Separator (thin gray rule)
        if(lore.size() > 0) lore.add("");

        // Total value for the stack at pickup time

        // “Converts to Xd on exit”, styled
        String info = StringFormatter.formatHex(
                "#cbb36aConverts to #ffb028" + formatDenar(perItem) + "d§7/#87db70Item #cbb36aon exit"
        );
        lore.add(info);

        meta.setLore(lore);
        // Tag so we don’t add lore again if the item is dropped/picked up later
        pdc.set(PDC_CONV_TAG, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);

        // Write back to the item entity to ensure the tagged/lore’d stack is what goes into the inventory
        itemEnt.setItemStack(stack);
    }

    private String formatDenar(double v) {
        // compact formatting: integers w/o decimals; otherwise up to 2 decimals
        if (Math.floor(v) == v) return Long.toString((long) v);
        java.text.DecimalFormat df = new java.text.DecimalFormat("#.##");
        return df.format(v);
    }


    private void playWarpFx(Location at) {
        if (at == null || at.getWorld() == null) return;
        World w = at.getWorld();
        w.spawnParticle(Particle.PORTAL, at.clone().add(0, 1.0, 0), 30, 0.5, 0.8, 0.5, 0.1);
        w.playSound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.1f);
    }

    private void playArriveFx(Location at) {
        if (at == null || at.getWorld() == null) return;
        World w = at.getWorld();
        w.spawnParticle(Particle.END_ROD, at.clone().add(0, 1.0, 0), 18, 0.4, 0.6, 0.4, 0.0);
        w.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.35f);
    }

    public void removeAllHolograms() {
        for (Entrance ent : entrances.values()) {
            try { ent.removeHolograms(TrialRooms.getInstance()); } catch (Exception ignored) {}
        }
    }

    // EntranceManager.java (fields)
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private boolean dirty = false;

    // call from plugin onEnable after registering listeners:
    public void loadAllFromDisk() {
        List<Database.EntranceRecord> recs = Database.loadEntrances(TrialRooms.getInstance(), gson);
        for (Database.EntranceRecord r : recs) {
            Entrance e = Entrance.fromRecord(r);
            if (e == null) continue;
            Location key = Entrance.normalizeBlock(e.getEntranceLoc());
            entrances.put(key, e);

            // spawn holo immediately if chunk is loaded
            Location loc = e.getEntranceLoc();
            if (loc.getWorld() != null &&
                loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                e.spawnHolograms(TrialRooms.getInstance());
            }
        }
        // start autosave loop
        startAutosave();
    }

    private void startAutosave() {
        new BukkitRunnable() {
            @Override public void run() {
                if (!dirty) return;
                dirty = false;
                try {
                    Database.saveEntrances(TrialRooms.getInstance(), gson, entrances.values());
                } catch (Exception ex) {
                    TrialRooms.getInstance().getLogger().warning("Failed to save entrances: " + ex.getMessage());
                }
            }
        }.runTaskTimer(TrialRooms.getInstance(), 200L, 200L); // every 10s
    }

    private void markDirty() { dirty = true; }

    // expose for plugin disable
    public void saveAllNow() {
        try {
            Database.saveEntrances(TrialRooms.getInstance(), gson, entrances.values());
            dirty = false;
        } catch (Exception ex) {
            TrialRooms.getInstance().getLogger().warning("Failed to save entrances: " + ex.getMessage());
        }
    }

}
