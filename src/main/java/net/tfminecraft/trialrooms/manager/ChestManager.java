package net.tfminecraft.trialrooms.manager;

import net.tfminecraft.trialrooms.TrialRooms;
import net.tfminecraft.trialrooms.environment.chest.LootChest;
import net.tfminecraft.trialrooms.environment.entrance.Entrance;
import net.tfminecraft.trialrooms.environment.spawner.ActiveSpawner;
import net.tfminecraft.trialrooms.environment.spawner.ui.ActiveSpawnerEditor;
import net.tfminecraft.trialrooms.persist.Database;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BrewingStartEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import me.Plugins.TLibs.TLibs;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class ChestManager implements Listener {

    // -------------- Singleton --------------
    private static ChestManager INSTANCE;
    public static ChestManager get() {
        if (INSTANCE == null) INSTANCE = new ChestManager();
        return INSTANCE;
    }

    private ChestManager() {
        new BukkitRunnable() {
            @Override public void run() {
                try {
                    for(LootChest chest : chests.values()) chest.tick();
                } catch (Throwable ignored) {}
            }
        }.runTaskTimer(TrialRooms.getInstance(), 0L, 1L); // every tick
    }

    public static List<LootChest> allChests() {
        return new ArrayList<>(chests.values());
    }
    public static LootChest get(ActiveSpawner spawner) {
        for(LootChest chest : chests.values()) {
            if(chest.isIndependent()) continue;
            if(chest.getSpawner().equals(spawner)) return chest;
        }
        return null;
    }

    // -------------- Storage: single map --------------
    private static final Map<Location, LootChest> chests = new HashMap<>();

    // -------------- Registration --------------
    /** Register an always-visible independent chest at this block location. */
    public LootChest registerIndependent(Location loc) {
        Location key = n(loc);
        LootChest chest = new LootChest(key, null); // spawner == null => independent
        chest.setDesiredVisible(true);
        chests.put(key, chest);
        if (chest.isInLoadedChunk()) chest.show(false);
        return chest;
    }

    /** Register (or overwrite) a chest bound to the given spawner at this block location. */
    public LootChest registerBound(ActiveSpawner spawner, Location loc) {
        if (spawner == null || loc == null) return null;
        Location key = n(loc);
        LootChest chest = new LootChest(key, spawner);
        // visible only during COOLDOWN; hidden otherwise
        chest.setDesiredVisible(spawner.getState() == ActiveSpawner.State.COOLDOWN);
        chests.put(key, chest);
        if (chest.isInLoadedChunk()) chest.enforceNow();
        return chest;
    }

    /** Remove a chest registration (does not alter the world block). */
    public void remove(Location loc) {
        chests.remove(n(loc));
    }

    /** Read-only view of all chests. */
    public Collection<LootChest> all() { return Collections.unmodifiableCollection(chests.values()); }

    // -------------- Spawner hooks --------------
    public void onEnterCooldown(ActiveSpawner spawner) {
        if (spawner == null) return;
        for (LootChest c : chests.values()) {
            if (spawner.equals(c.getSpawner())) {
                c.setDesiredVisible(true);
                // a short flourish before appearing
                Bukkit.getScheduler().runTaskLater(TrialRooms.getInstance(),
                        () -> { if (c.isInLoadedChunk()) c.show(true); }, 20L);
            }
        }
    }

    public void onLeaveCooldown(ActiveSpawner spawner) {
        if (spawner == null) return;
        for (LootChest c : chests.values()) {
            if (spawner.equals(c.getSpawner())) {
                c.setDesiredVisible(false);
                if (c.isInLoadedChunk()) c.hide(true);
            }
        }
    }

    public void onSpawnerLoaded(ActiveSpawner spawner) {
        if (spawner == null) return;
        for (LootChest c : chests.values()) {
            if (spawner.equals(c.getSpawner())) {
                c.setDesiredVisible(spawner.getState() == ActiveSpawner.State.COOLDOWN);
                if (c.isInLoadedChunk()) c.enforceNow();
            }
        }
    }

    @EventHandler
    public void registerIndependentChest(PlayerInteractEvent e) {
        if(!e.getAction().equals(Action.RIGHT_CLICK_BLOCK)) return;
        Location loc = e.getClickedBlock().getLocation();
        if(chests.containsKey(loc)) {
            e.setCancelled(true);
            return;
        }
        Player p = e.getPlayer();
        if(!e.getClickedBlock().getType().equals(Material.CHEST)) return;
        if(!SpawnerManager.hasEditWand(p)) return;
        e.setCancelled(true);
        if(ActiveSpawnerEditor.PENDING.containsKey(p.getUniqueId())) return;
        registerIndependent(loc);
        p.sendMessage(ChatColor.GREEN + "Chest registered at "
                + ChatColor.WHITE + e.getClickedBlock().getX() + ", "
                + e.getClickedBlock().getY() + ", "
                + e.getClickedBlock().getZ() + ChatColor.GREEN + ".");
    }

    @EventHandler
    public void chestBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Location loc = e.getBlock().getLocation();
        if(!chests.containsKey(loc)) return;
        e.setCancelled(true);
        if(!SpawnerManager.hasEditWand(p)) {
            return;
        }
        LootChest chest = chests.get(loc);
        remove(chest);
        p.sendMessage("§cRemoved Chest");
    }

    public static void remove(LootChest chest) {
        chest.getLocation().getBlock().setType(Material.AIR);
        chest.removeHologram();
        chests.remove(chest.getLocation());
        chest.getLocation().getWorld().playSound(chest.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.7f, 1.3f);
    }

    // -------------- Chunk hook (minimal) --------------
    @EventHandler public void onChunkLoad(ChunkLoadEvent e) {
        Chunk ch = e.getChunk();
        World w = ch.getWorld();
        int cx = ch.getX(), cz = ch.getZ();
        // Just scan all – simple, small N. If you have many chests, you can index later.
        for (LootChest c : chests.values()) {
            Location l = c.getLocation();
            if (l == null || l.getWorld() != w) continue;
            if ((l.getBlockX() >> 4) == cx && (l.getBlockZ() >> 4) == cz) {
                c.enforceNow();
            }
        }
    }

    // -------------- Open with key (simple) --------------
    private static final NamespacedKey PDC_ENCODED_LOOT =
            new NamespacedKey(TrialRooms.getInstance(), "encodedLoot");
    private static final NamespacedKey PDC_ENCODED_VER  =
            new NamespacedKey(TrialRooms.getInstance(), "encodedLootVer");
    private static final NamespacedKey PDC_KEY_RARITY   =
            new NamespacedKey(TrialRooms.getInstance(), "keyRarity");
    private static final NamespacedKey PDC_ROLLS_MIN    =
            new NamespacedKey(TrialRooms.getInstance(), "keyRollsMin");
    private static final NamespacedKey PDC_ROLLS_MAX    =
            new NamespacedKey(TrialRooms.getInstance(), "keyRollsMax");

    @EventHandler
    public void onRightClickChest(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;
        if (e.getHand() != EquipmentSlot.HAND) return;

        LootChest chest = chests.get(n(e.getClickedBlock().getLocation()));
        if (chest == null) return;      // not ours
        if (chest.isHidden()) return;   // currently not present

        ItemStack hand = e.getItem();
        if (!isKey(hand)) return;

        // If bound, optionally require matching spawner UUID on key (if you encode it)
        ActiveSpawner bound = chest.getSpawner();

        e.setCancelled(true);

        DecodedKey decoded = decodeKey(hand);
        if (decoded == null || decoded.pool.isEmpty()) {
            e.getPlayer().sendMessage(ChatColor.RED + "That key seems inert.");
            return;
        }

        consumeOne(e.getPlayer(), EquipmentSlot.HAND);

        // Hide now (with FX handled inside LootChest)
        chest.hide(true);

        // After a short beat, pop items
        Location base = chest.getLocation().clone().add(0.5, 0.8, 0.5);
        World w = base.getWorld();
        if (w == null) return;

        Bukkit.getScheduler().runTaskLater(TrialRooms.getInstance(), () -> {
            // FX
            w.playSound(base, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.9f, 0.85f);
            w.playSound(base, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.4f, 1.6f);
            w.spawnParticle(Particle.CLOUD, base, 18, 0.35, 0.25, 0.35, 0.0);
            w.spawnParticle(Particle.CRIT_MAGIC, base, 12, 0.25, 0.20, 0.25, 0.0);

            // Select entries and launch
            List<WeightedEntry> picks = pickWeightedWithoutReplacement(decoded.pool, decoded.rollsMin, decoded.rollsMax);
            for (WeightedEntry we : picks) {
                int amt = rollAmount(we.min, we.max);
                if (amt <= 0) continue;
                ItemStack it = itemFromPath(we.type, amt);
                if (it == null || it.getType() == Material.AIR) continue;

                Item ent = w.dropItem(base.clone().add(0, 0.3, 0), it);
                ent.setCustomName(entityNameFor(it));
                ent.setCustomNameVisible(true);
                kickUp(ent, ThreadLocalRandom.current());
                startCritTrail(ent, 140); // ~7s
            }

            // Independent chests reappear after 5s
            if (bound == null) {
                Bukkit.getScheduler().runTaskLater(TrialRooms.getInstance(),
                        () -> chest.show(true), 100L);
            }
        }, 10L); // 0.5s
    }

    private String displayNameOf(ItemStack it) {
        if (it == null) return "Item";
        var meta = it.getItemMeta();
        if (meta != null && meta.hasDisplayName()) return meta.getDisplayName();

        // Fallback: prettify the material
        String raw = it.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    private String entityNameFor(ItemStack it) {
        return "§f" + it.getAmount() + "x " + displayNameOf(it);
    }


    // -------------- Decoding / selection --------------
    private static class WeightedEntry {
        final String type; final int min; final int max; final int w;
        WeightedEntry(String t, int mi, int ma, int w) { this.type = t; this.min = mi; this.max = ma; this.w = Math.max(1, w); }
    }
    private record DecodedKey(List<WeightedEntry> pool, int rollsMin, int rollsMax) {}

    private boolean isKey(ItemStack stack) {
        if (stack == null) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(PDC_KEY_RARITY, PersistentDataType.STRING)
                && pdc.has(PDC_ENCODED_LOOT, PersistentDataType.STRING);
    }

    private DecodedKey decodeKey(ItemStack key) {
        ItemMeta meta = key.getItemMeta();
        if (meta == null) return null;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        String s = pdc.get(PDC_ENCODED_LOOT, PersistentDataType.STRING);
        if (s == null || s.isEmpty()) return null;

        int min = pdc.getOrDefault(PDC_ROLLS_MIN, PersistentDataType.INTEGER, 1);
        int max = pdc.getOrDefault(PDC_ROLLS_MAX, PersistentDataType.INTEGER, min);

        List<WeightedEntry> pool = new ArrayList<>();
        for (String part : s.split(";")) {
            String[] f = part.split("\\|");
            if (f.length < 4) continue;
            try {
                String type = f[0];
                int mi = Integer.parseInt(f[1]);
                int ma = Integer.parseInt(f[2]);
                int w  = Integer.parseInt(f[3]);
                if (w > 0) pool.add(new WeightedEntry(type, Math.max(0, mi), Math.max(0, ma), w));
            } catch (NumberFormatException ignored) {}
        }
        if (pool.isEmpty()) return null;
        if (max < min) max = min;
        return new DecodedKey(pool, Math.max(0, min), Math.max(0, max));
    }

    private List<WeightedEntry> pickWeightedWithoutReplacement(List<WeightedEntry> pool, int rollsMin, int rollsMax) {
        int rolls = (rollsMin == rollsMax) ? rollsMin : ThreadLocalRandom.current().nextInt(rollsMin, rollsMax + 1);
        rolls = Math.max(0, Math.min(rolls, pool.size()));

        List<WeightedEntry> src = new ArrayList<>(pool);
        List<WeightedEntry> out = new ArrayList<>(rolls);

        for (int i = 0; i < rolls && !src.isEmpty(); i++) {
            long total = 0;
            for (WeightedEntry e : src) total += Math.max(1, e.w);
            long r = ThreadLocalRandom.current().nextLong(total);
            long acc = 0;
            WeightedEntry pick = null;
            for (WeightedEntry e : src) {
                acc += Math.max(1, e.w);
                if (r < acc) { pick = e; break; }
            }
            if (pick == null) pick = src.get(src.size() - 1);
            out.add(pick);
            src.remove(pick);
        }
        return out;
    }

    private int rollAmount(int min, int max) {
        if (max < min) { int t = min; min = max; max = t; }
        min = Math.max(0, min);
        max = Math.max(0, max);
        if (max <= min) return min;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    // -------------- FX / utils --------------
    private String readString(ItemStack stack, NamespacedKey key) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private void consumeOne(Player player, EquipmentSlot hand) {
        ItemStack stack = player.getInventory().getItem(hand);
        if (stack == null) return;
        int amt = stack.getAmount();
        if (amt <= 1) player.getInventory().setItem(hand, null);
        else stack.setAmount(amt - 1);
        player.updateInventory();
    }

    private ItemStack itemFromPath(String path, int amt) {
        try {
            ItemStack it = TLibs.getItemAPI().getCreator().getItemFromPath(path);
            if (it == null || it.getType() == Material.AIR) return null;
            it.setAmount(Math.max(1, Math.min(64, amt)));
            return it;
        } catch (Exception ex) {
            return null;
        }
    }

    private void kickUp(org.bukkit.entity.Item ent, ThreadLocalRandom rng) {
        double vx = randomSigned(rng, 0.05, 0.12);
        double vz = randomSigned(rng, 0.05, 0.12);
        double vy = rng.nextDouble(0.35, 0.75);
        ent.setVelocity(new Vector(vx, vy, vz));
    }

    private double randomSigned(ThreadLocalRandom rng, double min, double max) {
        double v = rng.nextDouble(min, max);
        return rng.nextBoolean() ? v : -v;
    }

    private void startCritTrail(Entity entity, int maxTicks) {
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (entity == null || !entity.isValid() || entity.isDead() || t++ >= maxTicks) { cancel(); return; }
                Location p = entity.getLocation().add(0, 0.1, 0);
                p.getWorld().spawnParticle(Particle.CRIT, p, 4, 0.05, 0.05, 0.05, 0.0);
            }
        }.runTaskTimer(TrialRooms.getInstance(), 0L, 2L);
    }

    private Location n(Location in) {
        if (in == null || in.getWorld() == null) return null;
        // Normalize to block coords; pitch/yaw 0
        return new Location(in.getWorld(), in.getBlockX(), in.getBlockY(), in.getBlockZ());
    }

    public void removeAllHolograms() {
        for (LootChest c : chests.values()) {
            try { c.removeHologram(); } catch (Exception ignored) {}
        }
    }

    // ChestManager snippet
    public void hydrateFrom(List<Database.ChestRecord> records) {
        for (var r : records) {
            Location loc = (r.loc == null) ? null : r.loc.toBukkit();
            if (loc == null) continue;

            ActiveSpawner bound = null;
            if (r.spawnerUUID != null) bound = SpawnerManager.get(r.spawnerUUID); // implement in SpawnerManager

            LootChest c = (bound == null)
                    ? registerIndependent(loc)                   // returns id
                    : registerBound(bound, loc);                 // returns id
            c.setId(UUID.fromString(r.id));

            // Apply facing immediately so the first show() uses it:
            try {
                BlockFace face = BlockFace.valueOf(r.facing == null ? "NORTH" : r.facing);
                // add a setter in LootChest to keep it tidy:
                // c.setFacing(face);
                java.lang.reflect.Field f = LootChest.class.getDeclaredField("facing");
                f.setAccessible(true);
                f.set(c, face);
            } catch (Throwable ignored) {}
        }
    }

}
