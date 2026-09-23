package net.tfminecraft.trialrooms.environment.spawner;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import me.Plugins.TLibs.Objects.API.SubAPI.StringFormatter;
import net.tfminecraft.trialrooms.TrialRooms;
import net.tfminecraft.trialrooms.cache.Cache;
import net.tfminecraft.trialrooms.environment.chest.LootChest;
import net.tfminecraft.trialrooms.environment.door.DoorBlock;
import net.tfminecraft.trialrooms.manager.ChestManager;
import net.tfminecraft.trialrooms.manager.PlayerManager;
import net.tfminecraft.trialrooms.persist.Database.DoorRecord;

public class ActiveSpawner {

    public enum State { IDLE, ACTIVE, UNLOCKING, COOLDOWN }

    // --- Identity / config ---
    private final String id;
    private String uuid;
    private final String block;
    private final Location loc;

    private String lootTable; // optional table id (for encoding onto key)
    private String mob = "rpg_skeleton";
    private int amount = 1;
    private int level = 1;
    private int spawnRadius = 5;
    private int activateRadius = 5;

    // --- Identity / config ---
    private String mobLootTable;  // NEW: mob key uses this (null => no mob keys)

    // NEW
    public String getMobLootTable() { return mobLootTable; }
    public void setMobLootTable(String tableId) { this.mobLootTable = tableId; refreshStatusHolograms(); }


    // --- Runtime ---
    private boolean destroyed = false;
    private int enemiesAlive = 0;
    private int pendingSpawns = 0;

    // Cooldown
    private int cooldownSeconds = 10;
    private int cooldownRemaining = 0;

    // Holograms
    private final Hologram levelHologram;
    private final Hologram statusHologram;

    // State + loaded flag
    private State state = State.IDLE;
    private boolean loaded = false;

    // Delegates
    private final SpawnerParticles particles;
    private final KeyService keyService;

    // Doors placed while ACTIVE/UNLOCKING; removed on COOLDOWN
    private final List<DoorBlock> doors = new ArrayList<>();


    // NEW: bound chest helpers
    public void setBoundChest(Location loc) {
        ChestManager.get()
            .registerBound(this, loc);
    }
    public Location getChestLocation() {
        // Optional: you can track last bound location if you want to display it in the editor
        return hasChestBound() ? loc : null;// or return from a stored field you set when binding
    }
    public boolean hasChestBound() {
        // Optional: track a stored boolean if you want
        return ChestManager.get(this) != null;
    }



    // --- Constructor ---
    public ActiveSpawner(Location loc, Spawner spawner) {
        this.uuid = UUID.randomUUID().toString();
        this.id = spawner.getId();
        this.block = spawner.getBlock();
        this.loc = loc;

        this.levelHologram = new Hologram(
            () -> this.loc,
            this::getLevelFormatted
        );
        this.statusHologram = new Hologram(
                () -> this.loc,
                this::getStatusText
        );
        this.statusHologram.setYOffset(1.5);

        this.particles = new SpawnerParticles(this);
        this.keyService = new KeyService(this);
    }

    // --------- Public getters/setters ----------
    public String getId() { return id; }
    public String getUUID() { return uuid; }
    public String getBlock() { return block; }
    public Location getLoc() { return loc; }
    public String getMob() { return mob; }
    public int getAmount() { return amount; }
    public int getLevel() { return level; }
    public int getSpawnRadius() { return spawnRadius; }
    public int getActivateRadius() { return activateRadius; }
    public String getLootTable() { return lootTable; }

    public void setUUID(String uuid) {
        this.uuid = uuid;
    }
    public void setMob(String mob) { this.mob = mob; }
    public void setLevel(int level) { this.level = level; refreshStatusHolograms(); }
    public void setSpawnRadius(int r) { this.spawnRadius = r; }
    public void setActivateRadius(int r) { this.activateRadius = r; }
    public void setAmount(int amount) { this.amount = amount; refreshStatusHolograms(); }
    public void setLootTable(String tableId) { this.lootTable = tableId; refreshStatusHolograms(); }

    public State getState() { return state; }
    public boolean isLoaded() { return loaded; }
    public int getCooldownSeconds() { return cooldownSeconds; }
    public int getCooldownRemaining() { return cooldownRemaining; }
    public void setCooldownSeconds(int seconds) {
        this.cooldownSeconds = Math.max(0, seconds);
        if (state == State.COOLDOWN) {
            this.cooldownRemaining = this.cooldownSeconds;
            refreshStatusHolograms();
        }
    }
    public List<DoorBlock> getDoorBlocks() {
        return doors;
    }

    // For delegates
    int getPendingSpawns() { return pendingSpawns; }
    boolean isDestroyed() { return destroyed; }

    // ---------- Lifecycle from manager ----------
    public void onLoaded(JavaPlugin plugin) {
        this.loaded = true;
        spawnHolograms(plugin);
        ChestManager.get().onSpawnerLoaded(this);

        // Ensure doors match our current state after load
        boolean up = (state == State.ACTIVE || state == State.UNLOCKING);
        for (var d : doors) d.enforceForState(up);
    }


    public void onUnloaded(JavaPlugin plugin) {
        this.loaded = false;
        removeHolograms(plugin);
        killActiveEnemiesSilently(); // << add this
        if(this.state.equals(State.ACTIVE)) {
            cooldownRemaining = cooldownSeconds;
            this.state = State.COOLDOWN;
        }
        // No block change needed here; we'll fix state on next load
    }

    public void destroy() {
        if(hasChestBound()) {
            LootChest chest = ChestManager.get(this);
            ChestManager.remove(chest);
        }
        killActiveEnemiesSilently(); // << add this
        destroyed = true;
        removeHolograms(TrialRooms.getInstance());
    }

    // ---------- Ticking ----------
    /** Called every ~20 ticks (your scheduler). */
    public void slowTick() {
        if (!loaded || loc == null || loc.getWorld() == null) return;

        if (state == State.COOLDOWN) {
            if (cooldownRemaining > 0) {
                cooldownRemaining--;
                refreshStatusHolograms();
                if (cooldownRemaining == 0) setState(State.IDLE);
            }
            return;
        }
        if (state == State.UNLOCKING) return;

        if(state == State.ACTIVE && !hasEligiblePlayerNearby()) {
            cooldownRemaining = cooldownSeconds;
            this.state = State.COOLDOWN;
            killActiveEnemiesSilently();
            refreshStatusHolograms();
        }

        for(DoorBlock b : doors) {
            b.enforceForState(state == State.ACTIVE || state == State.UNLOCKING ? true : false);
        }

        if (state == State.IDLE && hasEligiblePlayerInRange()) {
            Location c = this.loc;
            double r2 = (double) activateRadius * (double) activateRadius;
            for (org.bukkit.entity.Player pl : c.getWorld().getPlayers()) {
                if ((pl.getGameMode() == org.bukkit.GameMode.SURVIVAL || pl.getGameMode() == org.bukkit.GameMode.ADVENTURE)
                        && pl.getWorld().equals(c.getWorld())
                        && pl.getLocation().distanceSquared(c) <= r2) {
                    PlayerManager.get().ensureInside(pl);
                }
            }
            setState(State.ACTIVE);
            spawn(); // begin staggered spawn
        }
    }

    /** Called every tick (your scheduler). */
    public void tick() {
        if (!loaded || loc == null || loc.getWorld() == null) return;

        if(statusHologram != null) statusHologram.tick();
        if(levelHologram != null) levelHologram.tick();

        if (state == State.IDLE || state == State.ACTIVE) {
            particles.animateIdleActiveRing();
        }
        if (state == State.ACTIVE && pendingSpawns > 0) {
            particles.animateOrb();
        }
    }

    // ---------- Spawning ----------
    public void spawn() {
        if (!loaded || loc == null || loc.getWorld() == null) return;

        List<Location> targets = SpawnPlanner.findSpawnLocations(this, Math.max(0, amount));
        if (targets.isEmpty()) return;

        setState(State.ACTIVE);
        pendingSpawns = targets.size();
        refreshStatusHolograms();

        long cumulative = 0L;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (Location target : targets) {
            cumulative += rng.nextLong(20L, 61L); // 1–3s

            Bukkit.getScheduler().runTaskLater(TrialRooms.getInstance(), () -> {
                if (!loaded || destroyed || state != State.ACTIVE) return;
                World w = target.getWorld();
                if (w == null) return;

                particles.traceFromOrbTo(target);

                w.spawnParticle(Particle.CLOUD, target.getX(), target.getY() + 0.1, target.getZ(),
                        16, 0.25, 0.25, 0.25, 0.0);
                w.spawnParticle(Particle.EXPLOSION_NORMAL, target.getX(), target.getY() + 0.1, target.getZ(),
                        16, 0.25, 0.25, 0.25, 0.0);
                w.playSound(target, Sound.ITEM_FIRECHARGE_USE, 0.6f, 1.6f);

                MythicMob mm = getMythicMob();
                if (mm == null) return;

                ActiveMob am = mm.spawn(BukkitAdapter.adapt(target), 1);
                LivingEntity ent = (LivingEntity) am.getEntity().getBukkitEntity();

                // Tag with spawner UUID (existing)
                ent.getPersistentDataContainer().set(
                        new NamespacedKey(TrialRooms.getInstance(), "spawnerId"),
                        PersistentDataType.STRING,
                        this.uuid
                );

                // Also store the level for fast damage-scaling later
                ent.getPersistentDataContainer().set(
                        new NamespacedKey(TrialRooms.getInstance(), "spawnerLevel"),
                        PersistentDataType.INTEGER,
                        this.level
                );

                // Custom name
                ent.setCustomName(getLevelFormatted());
                ent.setCustomNameVisible(true);

                // Scale health: 10 HP per level
                try {
                    var attr = ent.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                    if (attr != null) {
                        double max = Math.max(1.0, ent.getHealth() + Cache.healthPerLevel * this.level); // 10 hp per level
                        attr.setBaseValue(max);
                        ent.setHealth(max);
                    }
                } catch (Throwable ignored) { /* some mobs may not expose the attribute cleanly */ }

                onEnemySpawned();
                pendingSpawns = Math.max(0, pendingSpawns - 1);
                refreshStatusHolograms();
            }, cumulative);
        }
    }

    // ---------- Enemies ----------
    public void onEnemySpawned() {
        enemiesAlive++;
        refreshStatusHolograms();
    }
    public void onEnemyDied() {
        if(destroyed) return;
        if (enemiesAlive > 0) enemiesAlive--;
        refreshStatusHolograms();

        if (enemiesLeft() == 0 && state == State.ACTIVE) {
            setState(State.UNLOCKING);      // show “Unlocking…”
            keyService.startUnlockSequence(); // 2s unlock → windup → drop key → begin cooldown
        }
    }
    private int enemiesLeft() { return pendingSpawns + enemiesAlive; }

    // ---------- Holograms ----------
    public void spawnHolograms(JavaPlugin plugin) {
        levelHologram.spawn(plugin);
        statusHologram.spawn(plugin);
    }
    public void removeHolograms(JavaPlugin plugin) {
        levelHologram.remove(plugin);
        statusHologram.remove(plugin);
    }
    public void refreshStatusHolograms() {
        levelHologram.refresh(TrialRooms.getInstance());
        statusHologram.refresh(TrialRooms.getInstance());
    }

    // ---------- State / helpers ----------
    void beginCooldownFromUnlock() { 
        cooldownRemaining = cooldownSeconds;
        setState(State.COOLDOWN); 
    }

    private void setState(State newState) {
        this.state = newState;
        refreshStatusHolograms();

        // Chest visibility you already have:
        if (newState == State.COOLDOWN) ChestManager.get().onEnterCooldown(this);
        else ChestManager.get().onLeaveCooldown(this);

        // Doors: place on ACTIVE/UNLOCKING, remove on COOLDOWN (and keep removed on IDLE)
        if (!loaded) return;
        switch (newState) {
            case ACTIVE, UNLOCKING -> { for (var d : doors) if (d.isInLoadedChunk()) d.place(true); }
            case COOLDOWN          -> { for (var d : doors) if (d.isInLoadedChunk()) d.remove(true); }
            case IDLE              -> { /* keep removed */ }
        }
    }

    public String getLevelFormatted() {
        // Stage A: 1..30   : soft gold -> dark red
        // Stage B: 30..100 : dark red   -> near black
        final int aR = 0xF7, aG = 0xEC, aB = 0x88; // start (lv 1)
        final int mR = 0x40, mG = 0x04, mB = 0x04; // pivot "dark red" (lv ~30)
        final int zR = 0x0A, zG = 0x01, zB = 0x01; // near black (lv 100)

        int lv = Math.max(1, Math.min(100, this.level));

        int r, g, b;
        if (lv <= 30) {
            double t = (lv - 1) / 29.0; // 0..1 across 1..30
            r = (int) Math.round(aR + (mR - aR) * t);
            g = (int) Math.round(aG + (mG - aG) * t);
            b = (int) Math.round(aB + (mB - aB) * t);
        } else {
            double t = (lv - 30) / 70.0; // 0..1 across 30..100
            r = (int) Math.round(mR + (zR - mR) * t);
            g = (int) Math.round(mG + (zG - mG) * t);
            b = (int) Math.round(mB + (zB - mB) * t);
        }

        String hex = String.format("#%02x%02x%02x", r, g, b);
        String raw = "§7[§eLVL: " + hex + lv + "§7]";
        return StringFormatter.formatHex(raw);
    }

    public void maybeDropMobKey(Location at) {
        if (keyService != null) keyService.maybeDropMobKeyAt(at);
    }

    private boolean hasEligiblePlayerInRange() {
        if (loc == null || loc.getWorld() == null) return false;

        final World w = loc.getWorld();
        final Location c = this.loc;
        final double r = this.activateRadius;
        final double r2 = r * r;
        final double yTol = 2.0; // ±2 blocks in Y

        for (org.bukkit.entity.Entity e : w.getNearbyEntities(c, r, yTol, r)) {
            if (!(e instanceof Player p)) continue;

            // mode gate
            switch (p.getGameMode()) {
                case SURVIVAL, ADVENTURE -> { /* ok */ }
                default -> { continue; }
            }

            // vertical band
            if (Math.abs(p.getLocation().getY() - c.getY()) > yTol) continue;

            // horizontal (XZ) circle
            double dx = p.getLocation().getX() - c.getX();
            double dz = p.getLocation().getZ() - c.getZ();
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    private boolean hasEligiblePlayerNearby() {
        double r2 = (double) 50.0 * (double) 50.0;
        Location c = this.loc;
        return c.getWorld().getPlayers().stream()
                .filter(p -> p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
                .anyMatch(p -> p.getWorld().equals(c.getWorld()) && p.getLocation().distanceSquared(c) <= r2);
    }

    public String getMobName() {
        MythicMob mm = getMythicMob();
        if (mm == null) return "Unset";
        if(!mm.getDisplayName().isPresent()) return "Mob";
        return mm.getDisplayName().get();
    }
    public MythicMob getMythicMob() {
        if (mob == null) return null;
        return MythicBukkit.inst().getMobManager().getMythicMob(mob).orElse(null);
    }

    private String getStatusText() {
        return switch (state) {
            case IDLE      -> "§7" + getMobName() + ": §f" + amount;
            case UNLOCKING -> "§aUnlocking…";
            case COOLDOWN  -> "§cCooldown §7" + formatHMS(Math.max(0, cooldownRemaining));
            case ACTIVE    -> (enemiesAlive > 0)
                    ? "§cEnemies Alive: §f" + enemiesAlive + " §8(§7Queued: " + pendingSpawns + "§8)"
                    : (pendingSpawns > 0 ? "§eSpawning… §8(§7Queued: " + pendingSpawns + "§8)" : "§aEnemies Alive: §f0");
        };
    }

    private String formatHMS(int totalSec) {
        int h = Math.max(0, totalSec) / 3600;
        int m = (Math.max(0, totalSec) % 3600) / 60;
        int s = Math.max(0, totalSec) % 60;

        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append("h");
        if (m > 0) { if (sb.length() > 0) sb.append(" "); sb.append(m).append("m"); }
        if (s > 0) { if (sb.length() > 0) sb.append(" "); sb.append(s).append("s"); }
        return (sb.length() == 0) ? "0s" : sb.toString();
    }

    //Doors
    public void clearDoorBlocks() {
        if (loaded) for (var d : doors) d.remove(false);
        doors.clear();
    }

    // --- Door blocks API (put near your other helpers) ---
    public void addDoorBlock(DoorBlock d) {
        if (d == null) return;
        doors.add(d); // your 'doors' list from the DoorBlock integration
        if (isLoaded()) d.enforceForState(getState() == State.ACTIVE || getState() == State.UNLOCKING);
    }

    public boolean removeLastDoorBlock() {
        if (doors.isEmpty()) return false;
        var d = doors.remove(doors.size() - 1);
        if (isLoaded() && d != null) d.remove(false);
        return true;
    }

    public int getDoorBlocksCount() { return doors.size(); }

    /** Set the state directly, applying chest visibility + doors, etc. */
    public void setStateDirect(State newState) {
        if (newState == null) newState = State.IDLE;
        setState(newState); // uses your existing private setter
    }

    /** Override the current remaining cooldown (in seconds). */
    public void setCooldownRemainingSeconds(int seconds) {
        this.cooldownRemaining = Math.max(0, seconds);
        refreshStatusHolograms();
    }

    public void restoreState(State persistedState, int persistedCooldownRemainingSeconds) {
        State s = (persistedState == null) ? State.IDLE : persistedState;

        // We can’t reliably resume UNLOCKING after a restart; treat it as COOLDOWN.
        if (s == State.UNLOCKING) {
            s = State.COOLDOWN;
        }

        this.cooldownRemaining = Math.max(0, persistedCooldownRemainingSeconds);
        // If we’re entering COOLDOWN with 0 remaining, you can either:
        //  - leave it at 0 (slowTick will flip to IDLE),
        //  - or force IDLE immediately. We’ll leave it as-is to respect persisted data.

        setState(s); // doors/chests/holograms enforced here (and again in onLoaded if needed)
    }

    // --- kill all spawned mobs for this spawner, silently (no drops/keys/unlock) ---
    private void killActiveEnemiesSilently() {
        if (loc == null || loc.getWorld() == null) return;
        World w = loc.getWorld();

        // Search a reasonable area around the spawner to avoid scanning the whole world
        double radius = Math.max(Math.max(activateRadius, spawnRadius), 16) + 48; // ~64-80 blocks
        Location center = loc.clone().add(0.5, 0.5, 0.5);
        NamespacedKey key = new NamespacedKey(TrialRooms.getInstance(), "spawnerId");

        for (Entity e : w.getNearbyEntities(center, radius, radius, radius, ent -> ent instanceof LivingEntity)) {
            LivingEntity le = (LivingEntity) e;
            String sid = le.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            if (sid != null && sid.equals(this.uuid)) {
                // Remove without firing EntityDeathEvent → no loot/keys/unlock side-effects
                le.remove();
            }
        }

        // Reset runtime counters/UI; do NOT trigger unlocking
        enemiesAlive = 0;
        pendingSpawns = 0;
        refreshStatusHolograms();
    }
}
