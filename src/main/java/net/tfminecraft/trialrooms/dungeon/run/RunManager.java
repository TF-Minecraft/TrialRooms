package net.tfminecraft.trialrooms.dungeon.run;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.tfminecraft.trialrooms.dungeon.DungeonModule;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout;
import net.tfminecraft.trialrooms.dungeon.load.DungeonKit;
import net.tfminecraft.trialrooms.dungeon.load.RecipeLoader;
import net.tfminecraft.trialrooms.dungeon.run.DungeonRun.Member;
import net.tfminecraft.trialrooms.dungeon.run.DungeonRun.Status;
import net.tfminecraft.trialrooms.dungeon.template.Doorway;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;
import net.tfminecraft.trialrooms.dungeon.world.LayoutPlacer;

/**
 * Runs dungeons: opens gates, takes keys, builds layouts, moves players in and out, and drives
 * rooms, fights, downed players and lives every half second.
 *
 * <p>Safety rules: a key is taken only once the dungeon is built, and refunded when the server
 * stops mid-run; every character's way back is saved before it enters; anyone found in the
 * dungeon world without a live place in a run is sent back.
 */
public final class RunManager {

    /** Players with this may stand in the dungeon world without being in a run. */
    public static final String STAFF_PERMISSION = "trialrooms.admin";
    static final long STEP_MS = 500;

    private final JavaPlugin plugin;
    private final DungeonModule module;
    private final Ports.Characters characters;
    private final Ports.Keys keys;
    private final Ports.Mobs mobs;
    private final RunFiles files;
    private final GateStore gates;
    private final LongSupplier clock;
    private final NamespacedKey mobKey;
    private final NamespacedKey spectatorKey;
    private final Map<UUID, ActiveRun> runs = new LinkedHashMap<>();
    private final Map<UUID, ActiveRun> byPlayer = new HashMap<>();
    private final Map<UUID, ActiveRun> byMob = new HashMap<>();
    private final Set<UUID> starting = new HashSet<>();
    /** Members who died in their run and have not respawned yet. */
    private final Set<UUID> diedInRun = new HashSet<>();
    private int pendingStarts;
    private BukkitTask task;
    /** Parties waiting for a free dungeon, first in line first. */
    private final List<Waiting> queue = new ArrayList<>();
    /** The leader a freed dungeon is held for, until {@link #heldUntilMs}. */
    private UUID heldFor;
    private long heldUntilMs;

    /** A party leader waiting for a dungeon of a recipe. */
    record Waiting(UUID leader, String recipe) {
    }

    /** A run with what it needs in the world. */
    public static final class ActiveRun {
        final DungeonRun run;
        final RunGeometry geo;
        final LayoutPlacer placer;
        final DungeonKit kit;
        final RunRules rules;
        final UUID leader;
        final String keyPath;
        final Location arrival;
        final Map<Integer, Set<Entity>> roomMobs = new HashMap<>();
        final Map<UUID, double[]> mobHome = new HashMap<>();
        final Map<UUID, Location> downedAt = new HashMap<>();
        double healthFactor = 1;
        long finishAtMs = -1;

        ActiveRun(DungeonRun run, RunGeometry geo, LayoutPlacer placer, DungeonKit kit, RunRules rules, UUID leader,
                String keyPath, Location arrival) {
            this.run = run;
            this.geo = geo;
            this.placer = placer;
            this.kit = kit;
            this.rules = rules;
            this.leader = leader;
            this.keyPath = keyPath;
            this.arrival = arrival;
        }

        public DungeonRun run() {
            return run;
        }

        public RunGeometry geometry() {
            return geo;
        }
    }

    public RunManager(JavaPlugin plugin, DungeonModule module, Ports.Characters characters, Ports.Keys keys,
            Ports.Mobs mobs, RunFiles files, GateStore gates, LongSupplier clock) {
        this.plugin = plugin;
        this.module = module;
        this.characters = characters;
        this.keys = keys;
        this.mobs = mobs;
        this.files = files;
        this.gates = gates;
        this.clock = clock;
        this.mobKey = new NamespacedKey(plugin, "dungeon_run");
        this.spectatorKey = new NamespacedKey(plugin, "dungeon_spectator");
    }

    public void start() {
        int recovered = files.recoverActive();
        if (recovered > 0) {
            plugin.getLogger().warning("[dungeons] " + recovered + " run(s) were cut off by a stop; keys refunded");
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    /** Ends every run as a server fault: players go back and keys are refunded. */
    public void stop() {
        if (task != null) {
            task.cancel();
        }
        for (ActiveRun ar : new ArrayList<>(runs.values())) {
            end(ar, true, "§cThe dungeon fades as the world shifts. Your key is returned.");
        }
    }

    public GateStore gates() {
        return gates;
    }

    public boolean blocksCommand(String commandLine) {
        return settings().blocks(commandLine);
    }

    public List<ActiveRun> runs() {
        return new ArrayList<>(runs.values());
    }

    private RunSettings settings() {
        return module.settings();
    }

    // ------------------------------------------------------------------ queries

    public boolean inDungeonWorld(Entity entity) {
        World w = module.world();
        return w != null && entity.getWorld().getUID().equals(w.getUID());
    }

    public boolean inRun(Player p) {
        return byPlayer.containsKey(p.getUniqueId()) || starting.contains(p.getUniqueId());
    }

    public ActiveRun runOf(Player p) {
        return byPlayer.get(p.getUniqueId());
    }

    private Member member(Player p) {
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        return ar == null ? null : ar.run.member(p.getUniqueId());
    }

    public boolean isDowned(Player p) {
        Member m = member(p);
        return m != null && m.status() == Status.DOWNED;
    }

    public boolean isRunMob(Entity e) {
        return byMob.containsKey(e.getUniqueId())
                || e.getPersistentDataContainer().has(mobKey, PersistentDataType.STRING);
    }

    /** Where a downed player is held. */
    public Location downedSpot(Player p) {
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        return ar == null ? null : ar.downedAt.get(p.getUniqueId());
    }

    // ------------------------------------------------------------------ entering

    /** A player used a gate block. */
    public void useGate(Player p, GateStore.Gate gate) {
        Location at = new Location(p.getWorld(), gate.x() + 0.5, gate.y() + 0.5, gate.z() + 0.5);
        open(p, gate.recipe(), at, ThreadLocalRandom.current().nextLong(), true);
    }

    /** A staff member starts a run for themselves and their nearby party, without a key. */
    public void startRun(Player p, String recipeId, long seed) {
        open(p, recipeId, p.getLocation(), seed, false);
    }

    private void open(Player p, String recipeId, Location at, long seed, boolean useKey) {
        RecipeLoader.Entry entry = module.recipes().get(recipeId);
        if (entry == null) {
            p.sendMessage("§cThis gate leads nowhere (unknown dungeon " + recipeId + ").");
            return;
        }
        if (inRun(p)) {
            p.sendMessage("§cYou are already in a dungeon.");
            return;
        }
        if (characters.activeCharacterId(p) == null) {
            p.sendMessage("§cPlay a character to enter a dungeon.");
            return;
        }
        UUID leader = characters.partyLeader(p);
        if (leader != null && !leader.equals(p.getUniqueId())) {
            p.sendMessage("§cOnly your party leader can open the gate.");
            return;
        }
        List<Player> group = gather(p, at);
        RunRules rules = entry.rules();
        if (group.size() < rules.minPlayers() || group.size() > rules.maxPlayers()) {
            p.sendMessage("§cThis dungeon takes " + rules.minPlayers() + "-" + rules.maxPlayers()
                    + " players; " + group.size() + " of your party are here.");
            return;
        }
        boolean needsKey = useKey && !rules.keyPath().isBlank();
        if (needsKey && !keys.holds(p, rules.keyPath())) {
            p.sendMessage("§cThe gate needs a key in your hand.");
            return;
        }
        boolean holdForOther = heldFor != null && !heldFor.equals(p.getUniqueId());
        if (runs.size() + pendingStarts + (holdForOther ? 1 : 0) >= settings().maxRuns()) {
            enqueue(p, recipeId);
            return;
        }
        if (p.getUniqueId().equals(heldFor)) {
            heldFor = null;
        }
        begin(p, group, entry, recipeId, seed, needsKey ? rules.keyPath() : "");
    }

    /** The opener and every party member near the gate who can enter. */
    private List<Player> gather(Player p, Location at) {
        List<Player> group = new ArrayList<>();
        group.add(p);
        double radius = settings().gateRadius();
        for (UUID id : characters.partyMembers(p)) {
            Player m = Bukkit.getPlayer(id);
            if (m == null || m.equals(p) || !m.getWorld().equals(at.getWorld())
                    || m.getLocation().distance(at) > radius || inRun(m)
                    || characters.activeCharacterId(m) == null) {
                continue;
            }
            group.add(m);
        }
        return group;
    }

    private void begin(Player leader, List<Player> group, RecipeLoader.Entry entry, String recipeId, long seed,
            String keyPath) {
        DungeonLayout layout;
        LayoutPlacer placer;
        try {
            layout = module.generate(recipeId, seed);
            placer = module.placer(layout);
        } catch (RuntimeException e) {
            leader.sendMessage("§cThe gate will not open: " + e.getMessage());
            return;
        }
        Map<UUID, String> chosen = new LinkedHashMap<>();
        for (Player m : group) {
            chosen.put(m.getUniqueId(), characters.activeCharacterId(m));
            starting.add(m.getUniqueId());
            m.sendMessage("§7The gate stirs...");
        }
        pendingStarts++;
        placer.place().whenComplete((arrival, error) -> {
            pendingStarts--;
            chosen.keySet().forEach(starting::remove);
            if (error != null) {
                placer.release();
                plugin.getLogger().warning("[dungeons] a run of " + recipeId + " failed to build: " + error);
                group.forEach(m -> m.sendMessage("§cThe gate shudders and stays shut."));
                return;
            }
            if (!keyPath.isBlank() && (!leader.isOnline() || !keys.take(leader, keyPath))) {
                placer.release();
                group.forEach(m -> m.sendMessage("§cThe key is gone; the gate closes."));
                return;
            }
            DungeonKit kit = module.kits().get(entry.kit());
            DungeonRun run = new DungeonRun(UUID.randomUUID(), recipeId, layout, entry.rules().lives(), clock.getAsLong());
            RunGeometry geo = new RunGeometry(layout, kit.catalog().cellSize(), kit.doorHeight(), placer.originX(),
                    placer.originY(), placer.originZ());
            ActiveRun ar = new ActiveRun(run, geo, placer, kit, entry.rules(), leader.getUniqueId(), keyPath, arrival);
            runs.put(run.id(), ar);
            if (!keyPath.isBlank()) {
                files.saveActive(run.id(), leader.getUniqueId(), keyPath);
            }
            for (int i = 0; i < layout.rooms().size(); i++) {
                if (DungeonRun.isFight(layout.rooms().get(i).node().type())) {
                    setDoors(ar, i, true, true);
                }
            }
            int n = 0;
            for (Player m : group) {
                String charId = chosen.get(m.getUniqueId());
                // Still online and on the character that stood at the gate (nobody here can be in
                // another run: the starting set kept them out of every gate meanwhile).
                if (!m.isOnline() || !Objects.equals(characters.activeCharacterId(m), charId)) {
                    continue;
                }
                files.saveReturn(m.getUniqueId(), charId, m.getLocation());
                run.add(m.getUniqueId(), charId);
                byPlayer.put(m.getUniqueId(), ar);
                if (!m.teleport(arrival.clone().add((n % 3) - 1, 0, (n / 3) * 1.5))) {
                    run.leave(m.getUniqueId());
                    byPlayer.remove(m.getUniqueId());
                    files.clearReturn(m.getUniqueId(), charId);
                    m.sendMessage("§cSomething held you back at the gate.");
                    continue;
                }
                n++;
                m.sendMessage("§6You step through the gate. §7Shared lives: " + run.lives()
                        + ". Leave at any time with /tr dungeon leave.");
            }
            ar.healthFactor = entry.rules().healthFactor(n);
            if (n == 0) {
                end(ar, true, null);
            }
        });
    }

    // ------------------------------------------------------------------ the queue

    private void enqueue(Player leader, String recipeId) {
        int at = position(leader.getUniqueId());
        if (at < 0) {
            queue.add(new Waiting(leader.getUniqueId(), recipeId));
            at = queue.size() - 1;
        }
        leader.sendMessage("§eEvery dungeon is taken right now. Your party is number " + (at + 1)
                + " in line; you will be told when one is free. §7(/tr dungeon leave to stop waiting)");
    }

    private int position(UUID leader) {
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).leader().equals(leader)) {
                return i;
            }
        }
        return -1;
    }

    /** Whether a player's party is waiting in line. */
    public boolean queued(Player p) {
        return position(p.getUniqueId()) >= 0 || p.getUniqueId().equals(heldFor);
    }

    /** Takes a player's party out of the line (and gives up a dungeon held for them). */
    public boolean unqueue(Player p) {
        boolean removed = queue.removeIf(w -> w.leader().equals(p.getUniqueId()));
        if (p.getUniqueId().equals(heldFor)) {
            heldFor = null;
            offerNext();
            return true;
        }
        if (removed) {
            announcePlaces();
        }
        return removed;
    }

    /** Holds the freed dungeon for the next leader in line who is online, and tells them. */
    private void offerNext() {
        if (heldFor != null || runs.size() + pendingStarts >= settings().maxRuns()) {
            return;
        }
        while (!queue.isEmpty()) {
            Waiting w = queue.remove(0);
            Player leader = Bukkit.getPlayer(w.leader());
            if (leader == null) {
                continue;
            }
            heldFor = w.leader();
            heldUntilMs = clock.getAsLong() + settings().holdSeconds() * 1000L;
            leader.sendMessage("§aA dungeon is free for your party: open the gate within " + settings().holdSeconds()
                    + " seconds.");
            leader.playSound(leader.getLocation(), "minecraft:block.note_block.bell", 1f, 1.2f);
            announcePlaces();
            return;
        }
    }

    private void announcePlaces() {
        for (int i = 0; i < queue.size(); i++) {
            tell(queue.get(i).leader(), "§7Your party is now number " + (i + 1) + " in line for a dungeon.");
        }
    }

    private static void tell(UUID id, String message) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            p.sendMessage(message);
        }
    }

    // ------------------------------------------------------------------ the half-second step

    void tick() {
        long now = clock.getAsLong();
        if (heldFor != null && now >= heldUntilMs) {
            tell(heldFor, "§cThe dungeon held for your party was given to the next in line.");
            heldFor = null;
        }
        offerNext();
        for (ActiveRun ar : new ArrayList<>(runs.values())) {
            // A won run keeps its fights and downed players going until everyone is sent back.
            if (ar.run.state() != DungeonRun.State.FAILED) {
                step(ar, now);
            }
            DungeonRun.State state = ar.run.state();
            if (state != DungeonRun.State.RUNNING && state != DungeonRun.State.ENDED
                    && (now >= ar.finishAtMs || ar.run.present().isEmpty())) {
                end(ar, false, null);
            }
        }
    }

    private void step(ActiveRun ar, long now) {
        DungeonRun run = ar.run;
        for (Member m : run.present()) {
            if (!m.online()) {
                if (now - m.offlineSinceMs() >= settings().graceSeconds() * 1000L) {
                    run.leave(m.id());
                    byPlayer.remove(m.id());
                }
                continue;
            }
            Player p = Bukkit.getPlayer(m.id());
            if (p == null || !inDungeonWorld(p)) {
                // Not here (yet): character selection after a reconnect, or moved out. They don't
                // count as playing, and the grace time runs until they come back.
                run.offline(m.id(), now);
                continue;
            }
            int room = ar.geo.roomAt(p.getLocation().getX(), p.getLocation().getZ());
            if (room != m.room()) {
                m.room(room);
                entered(ar, p, room);
            }
        }
        for (DungeonRun.Action a : run.stepRooms(settings().countdownSeconds() * 2,
                settings().bossCountdownSeconds() * 2)) {
            if (a instanceof DungeonRun.Countdown c) {
                countdown(ar, c);
            } else {
                seal(ar, ((DungeonRun.Seal) a).room());
            }
        }
        for (int room = 0; room < run.layout().rooms().size(); room++) {
            if (run.room(room) == DungeonRun.Room.ACTIVE) {
                fight(ar, room);
            }
        }
        downed(ar, now);
        if (run.state() != DungeonRun.State.RUNNING) {
            return;
        }
        if (run.present().isEmpty()) {
            end(ar, false, null);
        } else if (run.wiped() && run.present().stream().anyMatch(Member::online)) {
            fail(ar);
        }
    }

    private void entered(ActiveRun ar, Player p, int room) {
        if (room < 0) {
            return;
        }
        RoomType type = ar.run.layout().rooms().get(room).node().type();
        if (type == RoomType.REST && ar.run.checkpoint() != room) {
            ar.run.checkpoint(room);
            tell(ar, "§aA quiet room. Anyone who falls will wake here.");
        } else if (type == RoomType.VAULT && ar.run.bossDefeated() && ar.run.state() == DungeonRun.State.RUNNING) {
            complete(ar);
        } else if (type == RoomType.TREASURE) {
            p.sendActionBar(Component.text("A treasure room (treasure comes in a later update)."));
        }
    }

    private void countdown(ActiveRun ar, DungeonRun.Countdown c) {
        if (c.stepsLeft() % 2 != 0) {
            return;
        }
        for (Player p : online(ar)) {
            if (ar.run.member(p.getUniqueId()).room() == c.room()) {
                p.sendActionBar(Component.text("The doors close in " + c.stepsLeft() / 2 + "..."));
                p.playSound(p.getLocation(), "minecraft:block.note_block.hat", 1f, 1f);
            }
        }
    }

    private void seal(ActiveRun ar, int room) {
        // Pull everyone first, by where they stand now (not where the last step saw them), then
        // close the doors: nobody is left outside or caught in a doorway.
        holdIn(ar, room);
        setDoors(ar, room, false, true);
        int spawned = spawn(ar, room);
        for (Player p : online(ar)) {
            p.playSound(p.getLocation(), "minecraft:block.iron_door.close", 1f, 0.8f);
            if (ar.run.member(p.getUniqueId()).room() == room) {
                p.sendMessage("§cThe doors seal. §7Enemies: " + spawned + ".");
            }
        }
        if (spawned == 0) {
            clear(ar, room);
        }
    }

    private int spawn(ActiveRun ar, int room) {
        List<double[]> points = ar.geo.markers(room, "spawn");
        List<String> tags = ar.geo.markerTags(room, "spawn");
        Set<Entity> alive = new HashSet<>();
        for (int i = 0; i < points.size(); i++) {
            String type = ar.kit.mobFor(tags.get(i));
            double[] at = points.get(i);
            Entity mob = type == null ? null
                    : mobs.spawn(type, new Location(module.world(), at[0], at[1], at[2]), ar.rules.mobLevel());
            if (mob == null) {
                plugin.getLogger().warning("[dungeons] no mob for spawn tag '" + tags.get(i) + "' in kit " + ar.kit.id());
                continue;
            }
            mob.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, ar.run.id().toString());
            if (mob instanceof LivingEntity living) {
                AttributeInstance max = living.getAttribute(Attribute.MAX_HEALTH);
                if (max != null) {
                    max.setBaseValue(max.getBaseValue() * ar.healthFactor);
                    living.setHealth(max.getValue());
                }
            }
            alive.add(mob);
            byMob.put(mob.getUniqueId(), ar);
            ar.mobHome.put(mob.getUniqueId(), at);
        }
        ar.roomMobs.put(room, alive);
        return alive.size();
    }

    /**
     * Puts every member who is playing or downed (and online) inside a fight room: anyone outside
     * it, or standing in a doorway, is moved to its entrance. Runs at the seal and every step of
     * the fight, so lag or a stray teleport never leaves someone outside a sealed fight.
     */
    private void holdIn(ActiveRun ar, int room) {
        double[] e = ar.geo.entry(room);
        for (Member m : ar.run.present()) {
            // Members outside the dungeon world were marked away by this step already.
            Player p = m.online() ? Bukkit.getPlayer(m.id()) : null;
            if (p == null || m.status() == Status.OUT) {
                continue;
            }
            Location here = m.status() == Status.DOWNED ? ar.downedAt.get(m.id()) : p.getLocation();
            if (!ar.geo.safelyInside(room, here.getX(), here.getZ(), 2)) {
                Location to = new Location(module.world(), e[0], e[1], e[2]);
                p.teleport(to);
                if (m.status() == Status.DOWNED) {
                    ar.downedAt.put(m.id(), to);
                }
            }
            m.room(room);
        }
    }

    /** Drops dead mobs, brings strays back, and clears the room when none are left. */
    private void fight(ActiveRun ar, int room) {
        holdIn(ar, room);
        Set<Entity> alive = ar.roomMobs.getOrDefault(room, new HashSet<>());
        Iterator<Entity> it = alive.iterator();
        while (it.hasNext()) {
            Entity mob = it.next();
            if (!mob.isValid() || mob.isDead()) {
                it.remove();
                byMob.remove(mob.getUniqueId());
                continue;
            }
            Location l = mob.getLocation();
            if (ar.geo.roomAt(l.getX(), l.getZ()) != room) {
                double[] home = ar.mobHome.get(mob.getUniqueId());
                mob.teleport(new Location(module.world(), home[0], home[1], home[2]));
            }
        }
        if (alive.isEmpty()) {
            clear(ar, room);
        }
    }

    private void clear(ActiveRun ar, int room) {
        ar.run.clearRoom(room);
        ar.roomMobs.remove(room);
        setDoors(ar, room, false, false);
        boolean boss = ar.run.layout().rooms().get(room).node().type() == RoomType.BOSS;
        for (Player p : online(ar)) {
            p.playSound(p.getLocation(), "minecraft:block.iron_door.open", 1f, 1.2f);
            Member m = ar.run.member(p.getUniqueId());
            if (boss) {
                p.sendMessage("§6The guardian falls. The way to the vault is open.");
            } else if (m.room() == room) {
                p.sendMessage("§aThe room is clear. The doors open.");
            }
            if (m.room() == room) {
                String next = nextDoor(ar, room, p);
                if (next != null) {
                    p.sendActionBar(Component.text("Next door: " + next));
                }
            }
        }
    }

    /** Text direction to the nearest doorway leading deeper from a room, for players who navigate by text. */
    private String nextDoor(ActiveRun ar, int room, Player p) {
        String best = null;
        double bestDistance = Double.MAX_VALUE;
        for (DungeonLayout.Connection c : ar.run.layout().connections()) {
            if (c.roomA() != room) {
                continue;
            }
            double[] d = ar.geo.insideDoor(ar.run.layout().rooms().get(room).doorways().get(c.doorA()));
            Location l = p.getLocation();
            double dist = Math.hypot(d[0] - l.getX(), d[2] - l.getZ());
            if (dist < bestDistance) {
                bestDistance = dist;
                best = RunGeometry.direction(l.getX(), l.getZ(), l.getYaw(), d[0], d[2]);
            }
        }
        return best;
    }

    /**
     * Shuts or opens a room's doorways: all of them, or with {@code onwardOnly} just those leading
     * deeper (to rooms placed from this one).
     */
    private void setDoors(ActiveRun ar, int room, boolean onwardOnly, boolean closed) {
        BlockData data = plugin.getServer().createBlockData(closed ? ar.kit.doorBlock() : "minecraft:air");
        for (DungeonLayout.Connection c : ar.run.layout().connections()) {
            int door;
            if (c.roomA() == room) {
                door = c.doorA();
            } else if (c.roomB() == room && !onwardOnly) {
                door = c.doorB();
            } else {
                continue;
            }
            Doorway d = ar.run.layout().rooms().get(room).doorways().get(door);
            for (int[] b : ar.geo.doorBlocks(d)) {
                module.world().getBlockAt(b[0], b[1], b[2]).setBlockData(data, true);
            }
        }
    }

    // ------------------------------------------------------------------ downed players

    /** A run member took a lethal hit: they go down instead of dying. True when they were downed. */
    public boolean down(Player p) {
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        if (ar == null || ar.run.state() == DungeonRun.State.FAILED || !ar.run.down(p.getUniqueId(), clock.getAsLong())) {
            return false;
        }
        p.setHealth(1.0);
        ar.downedAt.put(p.getUniqueId(), p.getLocation().clone());
        p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, PotionEffect.INFINITE_DURATION, 0, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, PotionEffect.INFINITE_DURATION, 6, false, false));
        p.sendMessage("§cYou are down. §7A party member crouching beside you for " + settings().reviveSeconds()
                + " seconds brings you back.");
        tell(ar, "§c" + p.getName() + " is down!");
        event(ar, p.getName() + " went down");
        return true;
    }

    private void downed(ActiveRun ar, long now) {
        long needed = settings().reviveSeconds() * 1000L;
        double radius = settings().reviveRadius();
        for (Member m : ar.run.present()) {
            if (m.status() != Status.DOWNED) {
                continue; // a downed member who quits has already bled out
            }
            Player d = Bukkit.getPlayer(m.id());
            if (d == null) {
                continue;
            }
            Player reviver = null;
            for (Player a : online(ar)) {
                if (ar.run.member(a.getUniqueId()).playing() && a.isSneaking()
                        && a.getLocation().distance(d.getLocation()) <= radius) {
                    reviver = a;
                    break;
                }
            }
            if (reviver != null) {
                if (ar.run.addRevive(m.id(), STEP_MS, needed)) {
                    clearDowned(ar, d);
                    d.setHealth(Math.max(1.0, d.getAttribute(Attribute.MAX_HEALTH).getValue() * 0.3));
                    tell(ar, "§a" + reviver.getName() + " brings " + d.getName() + " back up.");
                    event(ar, reviver.getName() + " revived " + d.getName());
                } else {
                    int pct = (int) (100 * ar.run.member(m.id()).reviveMs() / needed);
                    reviver.sendActionBar(Component.text("Reviving " + d.getName() + "... " + pct + "%"));
                    d.sendActionBar(Component.text(reviver.getName() + " is reviving you... " + pct + "%"));
                }
                continue;
            }
            ar.run.resetRevive(m.id());
            long left = settings().bleedOutSeconds() * 1000L - (now - m.downedAtMs());
            if (left <= 0) {
                bleedOut(ar, d);
                continue;
            }
            d.sendActionBar(Component.text("You are down: " + (left + 999) / 1000 + "s. Lives left: " + ar.run.lives()));
            for (Player a : online(ar)) {
                if (ar.run.member(a.getUniqueId()).playing()) {
                    Location l = a.getLocation();
                    a.sendActionBar(Component.text(d.getName() + " is down, " + RunGeometry.direction(l.getX(), l.getZ(),
                            l.getYaw(), d.getLocation().getX(), d.getLocation().getZ())));
                }
            }
        }
    }

    private void bleedOut(ActiveRun ar, Player p) {
        boolean back = ar.run.bleedOut(p.getUniqueId());
        event(ar, p.getName() + (back ? " bled out and spent a life" : " bled out with no lives left"));
        clearDowned(ar, p);
        p.teleport(wakePoint(ar));
        if (back) {
            p.setHealth(p.getAttribute(Attribute.MAX_HEALTH).getValue());
            tell(ar, "§e" + p.getName() + " wakes at the last safe room. §7Shared lives left: " + ar.run.lives());
        } else {
            spectate(p);
            p.sendMessage("§cNo lives are left: you are out of this run. §7Watch, or leave with /tr dungeon leave.");
        }
        ar.run.member(p.getUniqueId()).room(-1);
    }

    /**
     * Where a fallen member comes back: inside the fight in progress (its doors are sealed, so the
     * checkpoint would lock them out), otherwise at the checkpoint.
     */
    private Location wakePoint(ActiveRun ar) {
        for (int room = 0; room < ar.run.layout().rooms().size(); room++) {
            if (ar.run.room(room) == DungeonRun.Room.ACTIVE) {
                double[] e = ar.geo.entry(room);
                return new Location(module.world(), e[0], e[1], e[2]);
            }
        }
        return checkpoint(ar);
    }

    private void clearDowned(ActiveRun ar, Player p) {
        ar.downedAt.remove(p.getUniqueId());
        p.removePotionEffect(PotionEffectType.GLOWING);
        p.removePotionEffect(PotionEffectType.SLOWNESS);
    }

    private Location checkpoint(ActiveRun ar) {
        int room = ar.run.checkpoint();
        if (room == 0) {
            return ar.arrival.clone();
        }
        List<double[]> rest = ar.geo.markers(room, "rest");
        double[] at = rest.isEmpty() ? ar.geo.centre(room) : rest.get(0);
        return new Location(module.world(), at[0], at[1] + (rest.isEmpty() ? 0 : 1), at[2]);
    }

    /** A run member died anyway (void, /kill): it counts as a bleed-out. Their items were kept. */
    public void died(Player p) {
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        if (ar != null && ar.run.state() != DungeonRun.State.FAILED && isPlayingMember(ar, p)) {
            diedInRun.add(p.getUniqueId());
            ar.downedAt.remove(p.getUniqueId());
            ar.run.bleedOut(p.getUniqueId());
            var cause = p.getLastDamageCause();
            event(ar, p.getName() + " died outright (" + (cause == null ? "unknown cause" : cause.getCause()) + ")");
        }
    }

    /** Where a run member respawns after a death, or null when they are not in a run. */
    /**
     * Where a member who died in their run respawns: back inside it. Anyone else respawns as usual.
     * (Decided at the death: by the respawn event the player may already count as elsewhere.)
     */
    public Location respawnPoint(Player p) {
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        return diedInRun.remove(p.getUniqueId()) && ar != null && isPlayingMember(ar, p) ? wakePoint(ar) : null;
    }

    /** Where a member who fell into the void is put back, or null for anyone not playing in a run here. */
    public Location rescuePoint(Player p) {
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        return ar == null || !inDungeonWorld(p) || !isPlayingMember(ar, p) ? null : wakePoint(ar);
    }

    /** Whether the player is in this run on the character that entered it. */
    private boolean isPlayingMember(ActiveRun ar, Player p) {
        return Objects.equals(ar.run.member(p.getUniqueId()).characterId(), characters.activeCharacterId(p));
    }

    /** Spectating an out member; tagged so the game mode is put back wherever they turn up. */
    private void spectate(Player p) {
        p.setGameMode(GameMode.SPECTATOR);
        p.getPersistentDataContainer().set(spectatorKey, PersistentDataType.BYTE, (byte) 1);
    }

    /** After a respawn: a member who is out watches as a spectator. */
    public void respawned(Player p) {
        Member m = member(p);
        if (m != null && m.status() == Status.OUT) {
            spectate(p);
        }
    }

    // ------------------------------------------------------------------ leaving and coming back

    public void quit(Player p) {
        unqueue(p);
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        if (ar == null) {
            return;
        }
        Member m = ar.run.member(p.getUniqueId());
        if (m.status() == Status.DOWNED) {
            clearDowned(ar, p);
            ar.run.bleedOut(p.getUniqueId());
        }
        ar.run.offline(p.getUniqueId(), clock.getAsLong());
    }

    /**
     * Makes sure a player is where they belong. A player without an active character yet (still in
     * character selection) is left alone until a character is played. In the dungeon world only a
     * member of a live run on the character that entered it (or staff) may stay; anyone else is
     * sent back. A member who turns up on another character leaves the run. A dungeon spectator
     * found outside a run gets their game mode back.
     */
    public void sweep(Player p) {
        String charId = characters.activeCharacterId(p);
        if (charId == null) {
            return;
        }
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        Member m = ar == null ? null : ar.run.member(p.getUniqueId());
        if (m != null && Objects.equals(m.characterId(), charId)) {
            if (!inDungeonWorld(p)) {
                unspectate(p); // an out member moved out of the dungeon doesn't stay a spectator
                return; // not back in the dungeon yet; the step counts them as away
            }
            ar.run.online(p.getUniqueId());
            respawned(p);
            if (m.status() != Status.OUT) {
                Location fight = wakePoint(ar);
                if (ar.geo.roomAt(fight.getX(), fight.getZ()) != ar.geo.roomAt(p.getLocation().getX(), p.getLocation().getZ())
                        && fightInProgress(ar)) {
                    p.teleport(fight);
                }
            }
            return;
        }
        if (m != null) {
            ar.run.leave(p.getUniqueId());
            byPlayer.remove(p.getUniqueId());
        }
        if (inDungeonWorld(p) && (m != null || !p.hasPermission(STAFF_PERMISSION))) {
            sendBack(p, charId);
        } else if (p.getPersistentDataContainer().has(spectatorKey, PersistentDataType.BYTE)) {
            unspectate(p);
        }
    }

    private boolean fightInProgress(ActiveRun ar) {
        for (int room = 0; room < ar.run.layout().rooms().size(); room++) {
            if (ar.run.room(room) == DungeonRun.Room.ACTIVE) {
                return true;
            }
        }
        return false;
    }

    /** Hands over keys owed from runs a server stop cut off; called once a character is played. */
    public void giveRefunds(Player p) {
        for (String key : files.takeRefunds(p.getUniqueId())) {
            keys.give(p, key);
            p.sendMessage("§aA dungeon key you lost to a server stop has been returned to you.");
        }
    }

    private void unspectate(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR) {
            p.setGameMode(GameMode.SURVIVAL);
        }
        p.getPersistentDataContainer().remove(spectatorKey);
    }

    /** A player leaves their run by choice. */
    public boolean leave(Player p) {
        ActiveRun ar = byPlayer.remove(p.getUniqueId());
        if (ar == null) {
            return false;
        }
        boolean here = inDungeonWorld(p) && isPlayingMember(ar, p);
        ar.run.leave(p.getUniqueId());
        if (here) {
            clearDowned(ar, p);
            sendBack(p, ar.run.member(p.getUniqueId()).characterId());
        } else {
            unspectate(p);
        }
        tell(ar, "§7" + p.getName() + " has left the dungeon.");
        return true;
    }

    /** A player right-clicked inside the dungeon world: at the vault's exit, they leave. */
    public boolean tryExit(Player p) {
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        if (ar == null || ar.run.state() != DungeonRun.State.COMPLETE) {
            return false;
        }
        Location l = p.getLocation();
        for (int room = 0; room < ar.run.layout().rooms().size(); room++) {
            for (double[] e : ar.geo.markers(room, "exit")) {
                if (Math.hypot(e[0] - l.getX(), e[2] - l.getZ()) <= 2.5) {
                    p.sendMessage("§6You leave the dungeon.");
                    return leave(p);
                }
            }
        }
        return false;
    }

    /** Sends a player to a character's saved return point, or the main world's spawn. */
    void sendBack(Player p, String charId) {
        Location to = files.returnPoint(p.getUniqueId(), charId);
        if (to == null) {
            to = Bukkit.getWorlds().get(0).getSpawnLocation();
        }
        unspectate(p);
        p.removePotionEffect(PotionEffectType.GLOWING);
        p.removePotionEffect(PotionEffectType.SLOWNESS);
        p.setFallDistance(0f);
        // Keep the return point when the teleport is refused (dead, or another plugin said no):
        // the next sweep tries again.
        if (p.teleport(to)) {
            files.clearReturn(p.getUniqueId(), charId);
        }
    }

    // ------------------------------------------------------------------ ending

    private void complete(ActiveRun ar) {
        ar.run.state(DungeonRun.State.COMPLETE);
        ar.finishAtMs = clock.getAsLong() + settings().finishSeconds() * 1000L;
        tell(ar, "§6The vault is yours. §7Right-click by the exit to leave; everyone is sent back in "
                + settings().finishSeconds() + " seconds.");
    }

    private void fail(ActiveRun ar) {
        ar.run.state(DungeonRun.State.FAILED);
        ar.finishAtMs = clock.getAsLong() + 5000L;
        tell(ar, "§cYour party has fallen. The dungeon casts you out.");
    }

    /** Ends a run now, sending everyone inside back. With {@code fault}, the key is refunded. */
    public void end(ActiveRun ar, boolean fault, String reason) {
        DungeonRun.State outcome = ar.run.state();
        ar.run.state(DungeonRun.State.ENDED);
        runs.remove(ar.run.id());
        for (Member m : ar.run.members()) {
            byPlayer.remove(m.id(), ar);
            Player p = Bukkit.getPlayer(m.id());
            if (p != null && m.status() != Status.LEFT && inDungeonWorld(p)) {
                if (reason != null) {
                    p.sendMessage(reason);
                }
                ar.downedAt.remove(m.id());
                sendBack(p, m.characterId());
            }
        }
        for (Set<Entity> set : ar.roomMobs.values()) {
            for (Entity mob : set) {
                byMob.remove(mob.getUniqueId());
                mobs.remove(mob);
            }
        }
        ar.roomMobs.clear();
        ar.placer.release();
        files.clearActive(ar.run.id());
        if (fault && !ar.keyPath.isBlank()) {
            files.addRefund(ar.leader, ar.keyPath);
            Player leader = Bukkit.getPlayer(ar.leader);
            if (leader != null && characters.activeCharacterId(leader) != null) {
                giveRefunds(leader);
            }
        }
        files.logRun(json(ar, fault ? "STOPPED" : outcome.name()));
    }

    /** A mob died: forget it right away (the step also catches mobs that vanish other ways). */
    public void mobDied(Entity mob) {
        byMob.remove(mob.getUniqueId());
    }

    /** One console line per notable run event, for staff and for tuning. */
    private void event(ActiveRun ar, String what) {
        plugin.getLogger().info("[dungeons] run " + ar.run.id().toString().substring(0, 8) + ": " + what);
    }

    private void tell(ActiveRun ar, String message) {
        for (Player p : online(ar)) {
            p.sendMessage(message);
        }
    }

    /** Online members still in the run. */
    private List<Player> online(ActiveRun ar) {
        List<Player> out = new ArrayList<>();
        for (Member m : ar.run.present()) {
            Player p = m.online() ? Bukkit.getPlayer(m.id()) : null;
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    static String json(ActiveRun ar, String outcome) {
        DungeonRun r = ar.run;
        StringBuilder b = new StringBuilder("{\"run\":\"").append(r.id()).append("\",\"recipe\":\"")
                .append(esc(r.recipeId())).append("\",\"seed\":").append(r.layout().seed()).append(",\"started\":")
                .append(r.startedMs()).append(",\"ended\":").append(System.currentTimeMillis())
                .append(",\"outcome\":\"").append(outcome).append("\",\"lives_left\":").append(r.lives())
                .append(",\"downs\":").append(r.downs()).append(",\"bleed_outs\":").append(r.bleedOuts())
                .append(",\"rooms_cleared\":").append(r.roomsCleared()).append(",\"boss\":").append(r.bossDefeated())
                .append(",\"members\":[");
        String sep = "";
        for (Member m : r.members()) {
            b.append(sep).append("{\"player\":\"").append(m.id()).append("\",\"character\":\"")
                    .append(esc(m.characterId())).append("\",\"status\":\"").append(m.status()).append("\"}");
            sep = ",";
        }
        return b.append("]}").toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
