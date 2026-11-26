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
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.tfminecraft.trialrooms.dungeon.DungeonModule;
import net.tfminecraft.trialrooms.dungeon.event.EventKind;
import net.tfminecraft.trialrooms.dungeon.event.EventPlanner;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout;
import net.tfminecraft.trialrooms.dungeon.load.DungeonKit;
import net.tfminecraft.trialrooms.dungeon.load.RecipeLoader;
import net.tfminecraft.trialrooms.dungeon.locker.LockerService;
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
    private Records records;
    private final Ports.Mobs mobs;
    private final Fights fights;
    private final Events events;
    /** One action bar line per player, warnings first. */
    final Bars bars = new Bars();
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
    /** Dungeon lockers; null runs without them (loadouts can't be worn). */
    private LockerService lockers;
    /** What runs hand out; null hands out nothing. */
    private RunRewards rewards;

    /** A party leader waiting for a dungeon of a recipe. */
    record Waiting(UUID leader, String recipe) {
    }

    /** What players find in banking runs, the ways out of treasure rooms, and insurance. */
    final RunFinds finds = new RunFinds(this);

    /** Supplies, the rest room's campfire and supplies found on the way. */
    final Supplies supplies;

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
        /** Fights of kits with a roster, by room. */
        final Map<Integer, Fights.Encounter> encounters = new HashMap<>();
        /** Things that spawned in the run outside a fight (summons): removed when the run ends. */
        final Set<Entity> extras = new HashSet<>();
        /** Damage each player dealt to the run's enemies, for the run log. */
        final Map<UUID, Double> damage = new HashMap<>();
        double healthFactor = 1;
        /** Fight budget multiplier for the party's size. */
        double enemyFactor = 1;
        long finishAtMs = -1;
        /** Rooms a member has stood in. */
        final Set<Integer> visited = new HashSet<>();
        /** When the party last got anywhere (a new room, a fight); -1 until the first step. */
        long progressMs = -1;
        long nextHintMs;
        long lastHintMs = -1;
        /** Hints since the party last got anywhere: each one waits twice as long as the one before. */
        int hints;
        /** Where each player's last hint pointed, shown with particles until hintUntilMs. */
        final Map<UUID, double[]> hintAt = new HashMap<>();
        long hintUntilMs;
        /** The room event planned for each room that has one, by room index. */
        Map<Integer, EventKind> planned = Map.of();
        /** Room events under way or done, by room index, until their room clears. */
        final Map<Integer, Events.RoomEvent> events = new HashMap<>();
        /** The entrance room while its doors stay shut for the party to get ready; -1 once open. */
        int lobbyRoom = -1;
        long lobbyUntilMs;
        /** Members who said they are ready in the lobby. */
        final Set<UUID> ready = new HashSet<>();
        /** The difficulty chosen at the gate, or null for a dungeon without difficulties. */
        Difficulty difficulty;
        /** Enemy damage multiplier from the difficulty (ordinary hits, not boss mechanics). */
        double damageFactor = 1;
        /** Enemy health multiplier from the difficulty, on top of the party's (capped) one. */
        double difficultyHealth = 1;
        /** When the run's doors opened (the lobby ended): spoils never pay more than the time since. */
        long openedMs;
        /** Hours of play the run's spoils have paid so far. */
        double spoilHours;
        /** Hours of play the run's cleared rooms have earned so far (paid as real time allows). */
        double dueHours;
        /** Whether the run counts for the leaderboards (a gate run, not a staff start). */
        boolean ranked;
        /** Who entered the run, by display name, for the leaderboards and trophies. */
        final Map<UUID, String> party = new LinkedHashMap<>();
        /** This week's affixes on this run, and their numbers. */
        List<WeeklyAffix> affixes = List.of();
        Affixes affixConfig;
        /** Enemies made stronger by nearby deaths (Bolstering): how many times. */
        final Map<UUID, Integer> bolstered = new HashMap<>();
        /** How the run's difficulty banks finds (null: paid at once), and whether worn gear is at stake. */
        Difficulty.Banking banking;
        boolean gearAtRisk;
        /** What item chances and marks are multiplied by (the difficulty's, for the risk). */
        double boost = 1;
        /** Members' finds not yet paid. */
        final Map<UUID, RunFinds.Held> held = new HashMap<>();
        /** The worn piece each member insured (spared if they fall), and the marks they paid. */
        final Map<UUID, String> insured = new HashMap<>();
        final Map<UUID, Integer> insurePaid = new HashMap<>();
        /** Treasure rooms holding a way out, and those already shown (their prop placed). */
        final Set<Integer> exits = new HashSet<>();
        final Set<Integer> exitsShown = new HashSet<>();
        /** Treasure rooms that have banked (each banks once). */
        final Set<Integer> treasureBanked = new HashSet<>();
        /** Members who got their supply pouch this run. */
        final Set<UUID> supplied = new HashSet<>();
        /** Members who rested by the rest room's fire this run. */
        final Set<UUID> rested = new HashSet<>();
        /** Treasure rooms each member searched for supplies ("room:uuid"). */
        final Set<String> treasureSearched = new HashSet<>();
        /** Whether finds are still held (a banking run until its vault; after it, finds are paid at once). */
        boolean holding;
        /** Fight rooms cleared (save points come every so many). */
        int fightsCleared;
        /** Who was in the run when it reached the vault (named on trophies secured later). */
        List<String> securedParty = List.of();

        boolean has(WeeklyAffix a) {
            return affixes.contains(a);
        }

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

        /** The event planned for a room, or null. */
        public EventKind event(int room) {
            return planned.get(room);
        }

        public RunGeometry geometry() {
            return geo;
        }

        public Difficulty difficulty() {
            return difficulty;
        }
    }

    public RunManager(JavaPlugin plugin, DungeonModule module, Ports.Characters characters, Ports.Keys keys,
            Ports.Mobs mobs, RunFiles files, GateStore gates, LongSupplier clock) {
        this(plugin, module, characters, keys, mobs, Ports.Fx.NONE, files, gates, clock);
    }

    public RunManager(JavaPlugin plugin, DungeonModule module, Ports.Characters characters, Ports.Keys keys,
            Ports.Mobs mobs, Ports.Fx fx, RunFiles files, GateStore gates, LongSupplier clock) {
        this.plugin = plugin;
        this.module = module;
        this.characters = characters;
        this.keys = keys;
        this.mobs = mobs;
        this.fights = new Fights(this, plugin, mobs, fx, clock);
        this.events = new Events(this, plugin, fights, clock);
        this.supplies = new Supplies(this, bars, clock, java.util.concurrent.ThreadLocalRandom.current()::nextDouble,
                module::supplies);
        this.files = files;
        this.gates = gates;
        this.clock = clock;
        this.mobKey = new NamespacedKey(plugin, "dungeon_run");
        this.spectatorKey = new NamespacedKey(plugin, "dungeon_spectator");
    }

    public void start() {
        Set<UUID> cutOff = files.activeRuns();
        int recovered = files.recoverActive();
        if (recovered > 0) {
            plugin.getLogger().warning("[dungeons] " + recovered + " run(s) were cut off by a stop (keys refunded where paid)");
        }
        int pouches = finds.store() == null ? 0 : finds.store().recover(cutOff);
        if (pouches > 0) {
            plugin.getLogger().warning("[dungeons] finds of " + pouches + " player(s) from runs a stop cut off will come home");
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    /** Ends every run as a server fault: players go back and keys are refunded. */
    public void stop() {
        if (task != null) {
            task.cancel();
        }
        for (ActiveRun ar : new ArrayList<>(runs.values())) {
            end(ar, true, true, "§cThe dungeon fades as the world shifts. Your key is returned.");
        }
        bars.hideOlder(Long.MAX_VALUE); // no tick will take them down any more
        if (lockers != null) {
            lockers.flush();
        }
    }

    public GateStore gates() {
        return gates;
    }

    /** Uses dungeon lockers: worn loadouts come off whenever a player is sent back or found outside. */
    public void lockers(LockerService lockers) {
        this.lockers = lockers;
    }

    public LockerService lockers() {
        return lockers;
    }

    /** Hands out loot and marks at a run's moments (elite rooms, events, the boss, the vault). */
    public void rewards(RunRewards rewards) {
        this.rewards = rewards;
    }

    RunRewards rewards() {
        return rewards;
    }

    /** Keeps finds on disk: what players hold in runs, what is owed to them, and what they lost. */
    public void findStore(FindStore store) {
        finds.store(store);
    }

    long now() {
        return clock.getAsLong();
    }

    /** Tells every member playing the run in the dungeon world about a reward moment. */
    private void reward(ActiveRun ar, RunRewards.Moment moment) {
        reward(ar, moment, false);
    }

    private void reward(ActiveRun ar, RunRewards.Moment moment, boolean underPar) {
        if (rewards != null && ar.holding) { // (holding ends as the vault is reached)
            // carried: rolled once it comes home (or lost on a fall)
            forPlaying(ar, "the carried " + moment, p -> {
                if (ar.run.member(p.getUniqueId()).status() != Status.OUT) { // (out of the fight: nothing to find)
                    RunRewards.Find f = rewards.find(p, ar.run.recipeId(), ar.rules.tier(), moment, ar.run.id(), ar.boost);
                    if (f != null) {
                        finds.carry(ar, p, f);
                    }
                }
            });
            return;
        }
        if (rewards != null) {
            forPlaying(ar, "the " + moment + " reward", p -> {
                if (!(ar.banking != null && ar.run.member(p.getUniqueId()).status() == Status.OUT)) { // (the fallen lose it)
                    rewards.reward(p, ar.run.recipeId(), ar.rules.tier(), moment, ar.run.id(), underPar, ar.boost);
                }
            });
        }
    }

    /**
     * A cleared fight room's spoils: its share of what a proper party earns in the run's length
     * (only fight rooms are cleared; a run given no length pays no spoils). A run never pays more
     * than the time it has actually been played, so clearing faster than a proper party (or a room
     * that spawned nothing) earns no more an hour. Members out of the fight (spectating) get none;
     * the others are all in the room (its seal pulls them in and the fight holds them there).
     */
    private void spoils(ActiveRun ar, boolean won) {
        if (rewards == null || !(ar.rules.minutes() > 0)) {
            return;
        }
        long fights = ar.run.layout().rooms().stream().filter(r -> DungeonRun.isFight(r.node().type())).count();
        double run = ar.rules.minutes() / 60.0;
        // what the cleared rooms have earned (a won run all of it), never more than the time played:
        // time saved in one room counts in the next, and a run played in its length pays all of it
        ar.dueHours = won ? run : Math.min(run, ar.dueHours + run / fights);
        double played = (clock.getAsLong() - ar.openedMs) / 3_600_000.0;
        double hours = Math.min(ar.dueHours, played) - ar.spoilHours;
        if (!(hours > 0)) {
            return;
        }
        ar.spoilHours += hours;
        forPlaying(ar, "the spoils", p -> {
            if (ar.run.member(p.getUniqueId()).status() == Status.OUT) {
                return;
            }
            if (ar.holding) {
                RunRewards.Find f = rewards.spoilsFind(p, ar.run.recipeId(), hours, ar.run.id());
                if (f != null) {
                    finds.carry(ar, p, f);
                }
            } else {
                rewards.spoils(p, ar.run.recipeId(), hours, ar.run.id());
            }
        });
    }

    /** Hands over what waits for a player outside a run (spoils, own items that found no room). */
    public void claim(Player p) {
        var l = lockers == null ? null : lockers.locker(p);
        if (l == null || l.owed().isEmpty()) {
            p.sendMessage("§7Nothing is waiting for you.");
        } else if (inDungeonWorld(p)) {
            p.sendMessage("§7What waits for you comes once you are out of the dungeon.");
        } else {
            sweep(p);
        }
    }

    /** Hands something to each member still playing; one player's failure never stops the others' or the run. */
    void forPlaying(ActiveRun ar, String what, java.util.function.Consumer<Player> give) {
        for (Player p : online(ar)) {
            if (isPlayingMember(ar, p)) { // (the step counts anyone outside the dungeon world as away)
                try {
                    give.accept(p);
                } catch (Exception | LinkageError e) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "[dungeons] " + what + " for " + p.getName() + " failed", e);
                }
            }
        }
    }

    public boolean blocksCommand(String commandLine) {
        return settings().blocks(commandLine);
    }

    public List<ActiveRun> runs() {
        return new ArrayList<>(runs.values());
    }

    RunSettings settings() {
        return module.settings();
    }

    World world() {
        return module.world();
    }

    Fights fights() {
        return fights;
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

    /** Whether an enemy's attack areas are marked on the ground: a run's boss or a room's leader. */
    public boolean isMarked(Entity e) {
        return runs.values().stream().anyMatch(ar -> fights.marked(ar, e));
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

    /** A player used a gate block: a dungeon with difficulties asks which first. */
    public void useGate(Player p, GateStore.Gate gate) {
        RecipeLoader.Entry entry = module.recipes().get(gate.recipe());
        if (entry != null && !entry.difficulties().isEmpty() && !inRun(p)) {
            new DifficultyMenu(this, plugin, p, gate, entry).open();
            return;
        }
        useGate(p, gate, null);
    }

    /** A player opens a gate at a difficulty (null: the first, or none). */
    public void useGate(Player p, GateStore.Gate gate, String difficulty) {
        Location at = new Location(p.getWorld(), gate.x() + 0.5, gate.y() + 0.5, gate.z() + 0.5);
        open(p, gate.recipe(), at, ThreadLocalRandom.current().nextLong(), true, difficulty, true);
    }

    /** A staff member starts a run for themselves and their nearby party, without a key. */
    public void startRun(Player p, String recipeId, long seed) {
        startRun(p, recipeId, seed, null);
    }

    /** As {@link #startRun(Player, String, long)}, at a difficulty (staff need not have opened it). */
    public void startRun(Player p, String recipeId, long seed, String difficulty) {
        open(p, recipeId, p.getLocation(), seed, false, difficulty, false);
    }

    /** Whether a player may open a dungeon at a difficulty: it needs none, or they have won the one it needs. */
    public boolean unlocked(Player p, String recipe, Difficulty d) {
        if (d.needs() == null) {
            return true;
        }
        var locker = lockers == null ? null : lockers.locker(p);
        return locker != null && locker.cleared(recipe).contains(d.needs());
    }

    private void open(Player p, String recipeId, Location at, long seed, boolean useKey, String difficultyId,
            boolean checkOpen) {
        RecipeLoader.Entry entry = module.recipes().get(recipeId);
        if (entry == null) {
            p.sendMessage("§cThis gate leads nowhere (unknown dungeon " + recipeId + ").");
            return;
        }
        Difficulty difficulty = null;
        if (!entry.difficulties().isEmpty()) {
            difficulty = difficultyId == null ? entry.difficulties().getFirst() : entry.difficulty(difficultyId);
            if (difficulty == null) {
                p.sendMessage("§cThis dungeon has no difficulty " + difficultyId + ".");
                return;
            }
            if (checkOpen && !unlocked(p, recipeId, difficulty)) {
                p.sendMessage("§c" + difficulty.name() + " opens once you have won " + entry.difficulty(difficulty.needs()).name()
                        + " here.");
                return;
            }
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
        // Parties already waiting go first: a newcomer joins the line even when a dungeon is free,
        // and the next step offers that dungeon to the front of the line.
        boolean held = p.getUniqueId().equals(heldFor);
        boolean holdForOther = heldFor != null && !held;
        if (!held && !queue.isEmpty()
                || runs.size() + pendingStarts + (holdForOther ? 1 : 0) >= settings().maxRuns()) {
            enqueue(p, recipeId);
            return;
        }
        begin(p, group, entry, recipeId, seed, needsKey ? rules.keyPath() : "", difficulty, useKey);
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
            String keyPath, Difficulty difficulty, boolean ranked) {
        RunRules rules = difficulty == null ? entry.rules() : entry.rules().with(difficulty);
        DungeonLayout layout;
        LayoutPlacer placer;
        try {
            layout = module.generate(recipeId, seed);
            placer = module.placer(layout);
        } catch (RuntimeException e) {
            leader.sendMessage("§cThe gate will not open: " + e.getMessage());
            return;
        }
        if (leader.getUniqueId().equals(heldFor)) {
            heldFor = null; // the dungeon held for this party is now theirs
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
            ActiveRun ar = new ActiveRun(run, geo, placer, kit, rules, leader.getUniqueId(), keyPath, arrival);
            ar.difficulty = difficulty;
            ar.ranked = ranked;
            ar.banking = difficulty == null ? null : difficulty.banking();
            ar.gearAtRisk = difficulty != null && difficulty.gearAtRisk();
            ar.boost = difficulty == null ? 1 : difficulty.boost();
            ar.holding = ar.banking != null;
            ar.affixConfig = module.affixes();
            ar.affixes = weeklyAffixes(recipeId, rules.tier());
            ar.damageFactor = difficulty == null ? 1 : difficulty.damage();
            ar.planned = EventPlanner.plan(layout, kit.events(), rules.eventChance(), rules.maxEvents());
            for (int i = 0; ar.banking != null && i < layout.rooms().size(); i++) {
                if (layout.rooms().get(i).node().type() == RoomType.TREASURE && exitRoll.getAsDouble() < ar.banking.exit()) {
                    ar.exits.add(i);
                }
            }
            runs.put(run.id(), ar);
            // (every run, keyed or not: a crash pays the finds of the runs it cut off, and only those)
            files.saveActive(run.id(), leader.getUniqueId(), keyPath);
            for (int i = 0; i < layout.rooms().size(); i++) {
                if (DungeonRun.isFight(layout.rooms().get(i).node().type())) {
                    setDoors(ar, i, true, true);
                }
            }
            ar.openedMs = clock.getAsLong();
            boolean lobby = settings().lobbySeconds() > 0;
            if (lobby) {
                // the arrival is in the START room, never a fight room (the generator always starts there)
                ar.lobbyRoom = geo.roomAt(arrival.getX(), arrival.getZ());
                ar.lobbyUntilMs = clock.getAsLong() + settings().lobbySeconds() * 1000L;
                setDoors(ar, ar.lobbyRoom, false, true);
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
                ar.party.put(m.getUniqueId(), net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(m.displayName()));
                m.sendMessage("§6You step through the gate. §7Shared lives: " + run.lives()
                        + ". Leave at any time with /tr dungeon leave.");
                if (difficulty != null) {
                    m.sendMessage("§7Difficulty: §f" + difficulty.name() + "§7 (tier " + difficulty.tier() + ").");
                }
                for (String rule : difficulty == null ? List.<String>of() : difficulty.lootRules()) {
                    m.sendMessage((rule.startsWith("Your worn") ? "§4" : "§7") + rule);
                }
                for (WeeklyAffix a : ar.affixes) {
                    m.sendMessage("§5This week: " + a.title() + " §7(" + a.hint() + ")");
                }
                if (lobby) {
                    m.sendMessage(ar.gearAtRisk ? lobbyButtons().append(Component.text(" "))
                            .append(Component.text("[Insure a piece]", NamedTextColor.AQUA)
                                    .clickEvent(ClickEvent.runCommand("/tr dungeon insure"))
                                    .hoverEvent(HoverEvent.showText(Component.text(
                                            "Pay marks so one worn piece is spared if you fall")))) : lobbyButtons());
                }
            }
            ar.healthFactor = rules.healthFactor(n);
            ar.difficultyHealth = difficulty == null ? 1 : difficulty.health();
            ar.enemyFactor = rules.enemyFactor(n);
            if (n == 0) {
                end(ar, true, null);
            } else if (!lobby) {
                supplies.handOut(ar, online(ar).stream().filter(p -> isPlayingMember(ar, p)).toList());
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
        boolean full = runs.size() + pendingStarts >= settings().maxRuns();
        leader.sendMessage((full ? "§eEvery dungeon is taken right now." : "§eOther parties are waiting for a dungeon.")
                + " Your party is number " + (at + 1)
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
        supplies.prune(byPlayer::containsKey);
        bars.hideOlder(now);
    }

    private void step(ActiveRun ar, long now) {
        DungeonRun run = ar.run;
        for (Member m : run.present()) {
            if (!m.online()) {
                if (now - m.offlineSinceMs() >= settings().graceSeconds() * 1000L) {
                    // away too long: as if they had left (what they banked comes home by the rules)
                    if (ar.lobbyRoom >= 0) {
                        finds.refund(ar, m.id());
                    }
                    finds.settleAway(ar, m, keepNow(ar), false,
                            "you were away too long and left the run");
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
                if (m.status() != Status.OUT) { // (a spectator flying about sets no checkpoint and banks nothing)
                    entered(ar, p, room);
                }
            }
            if (room >= 0 && ar.visited.add(room)) {
                ar.progressMs = now;
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
        boolean fighting = false;
        for (int room = 0; room < run.layout().rooms().size(); room++) {
            if (run.room(room) == DungeonRun.Room.ACTIVE) {
                fight(ar, room);
                fighting = true;
            }
        }
        if (ar.lobbyRoom >= 0) {
            lobby(ar, now);
        }
        if (fighting || ar.progressMs < 0 || ar.lobbyRoom >= 0) {
            ar.progressMs = now; // a party getting ready is not stuck
        }
        downed(ar, now);
        supplies.step(ar, now);
        hint(ar, now);
        if (run.state() != DungeonRun.State.RUNNING) {
            return;
        }
        if (run.present().isEmpty()) {
            end(ar, false, null);
        } else if (run.wiped() && run.present().stream().anyMatch(Member::online)) {
            fail(ar);
        }
    }

    // ------------------------------------------------------------------ the lobby

    static Component lobbyButtons() {
        return Component.text("Get ready in this room: ", NamedTextColor.GRAY)
                .append(Component.text("[Locker]", NamedTextColor.GOLD)
                        .clickEvent(ClickEvent.runCommand("/tr dungeon locker"))
                        .hoverEvent(HoverEvent.showText(Component.text("Put on a loadout of dungeon items, or stay in your own gear"))))
                .append(Component.text(" "))
                .append(Component.text("[Supplies]", NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.runCommand("/tr dungeon supplies"))
                        .hoverEvent(HoverEvent.showText(Component.text("Buy and pack what you take along: nothing heals on its own here"))))
                .append(Component.text(" "))
                .append(Component.text("[Ready]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/tr dungeon ready"))
                        .hoverEvent(HoverEvent.showText(Component.text("The doors open once everyone is ready"))));
    }

    /** While the party gets ready: a bar of who is, and the doors open once all are (or time runs out). */
    private void lobby(ActiveRun ar, long now) {
        List<Player> here = new ArrayList<>();
        for (Player p : online(ar)) {
            if (isPlayingMember(ar, p)) { // (the step already counts anyone outside the dungeon world as away)
                here.add(p);
            }
        }
        long ready = here.stream().filter(p -> ar.ready.contains(p.getUniqueId())).count();
        if (now >= ar.lobbyUntilMs || !here.isEmpty() && ready == here.size()) {
            openLobby(ar);
            return;
        }
        long seconds = (ar.lobbyUntilMs - now + 999) / 1000;
        for (Player p : here) {
            bars.show(p, "Ready " + ready + "/" + here.size() + "  ·  the doors open in " + seconds + "s",
                    (double) ready / here.size(), BossBar.Color.GREEN, now);
        }
    }

    private void openLobby(ActiveRun ar) {
        List<Player> here = online(ar).stream().filter(p -> isPlayingMember(ar, p)).toList();
        finds.doorsOpen(ar, here);
        supplies.handOut(ar, here);
        setDoors(ar, ar.lobbyRoom, false, false);
        ar.lobbyRoom = -1;
        ar.openedMs = clock.getAsLong();
        ar.ready.clear();
        for (Player p : online(ar)) {
            p.playSound(p.getLocation(), "minecraft:block.iron_door.open", 1f, 1f);
        }
        tell(ar, "§aThe doors open. Good hunting.");
    }

    /** A member says they are ready. False when their run has no lobby open. */
    public boolean ready(Player p) {
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        if (ar == null || ar.lobbyRoom < 0) {
            return false;
        }
        if (ar.ready.add(p.getUniqueId())) {
            tell(ar, "§7" + p.getName() + " is ready.");
        }
        return true;
    }

    /** Whether a player stands in their run's lobby with its doors still shut: loadouts may change here. */
    public boolean inLobby(Player p) {
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        return ar != null && ar.lobbyRoom >= 0 && inDungeonWorld(p) && isPlayingMember(ar, p)
                && ar.geo.roomAt(p.getLocation().getX(), p.getLocation().getZ()) == ar.lobbyRoom;
    }

    private void entered(ActiveRun ar, Player p, int room) {
        if (room < 0) {
            return;
        }
        RoomType type = ar.run.layout().rooms().get(room).node().type();
        if (type == RoomType.REST && ar.run.checkpoint() != room) {
            ar.run.checkpoint(room);
            tell(ar, "§aA quiet room. Anyone who falls will wake here.");
            if (ar.holding && ar.banking.rest()) {
                finds.bank(ar);
            }
        } else if (type == RoomType.TREASURE) {
            supplies.treasure(ar, p, room);
            if (ar.holding && ar.banking.treasure() && ar.treasureBanked.add(room)) {
                tell(ar, "§aA treasure room: its cache keeps what you carry safe.");
                finds.bank(ar);
            }
            if (ar.holding && ar.exits.contains(room) && ar.exitsShown.add(room)) {
                showExit(ar, room);
            }
        } else if (type == RoomType.VAULT && ar.run.bossDefeated() && ar.run.state() == DungeonRun.State.RUNNING) {
            complete(ar);
        }
    }

    private void countdown(ActiveRun ar, DungeonRun.Countdown c) {
        if (c.stepsLeft() % 2 != 0) {
            return;
        }
        for (Player p : online(ar)) {
            if (ar.run.member(p.getUniqueId()).room() == c.room()) {
                // a tick each second, rising as the doors come to close
                p.playSound(p.getLocation(), "minecraft:block.note_block.hat", 1f,
                        (float) Math.max(0.6, 1.6 - 0.2 * (c.stepsLeft() / 2)));
            }
        }
    }

    private void seal(ActiveRun ar, int room) {
        // Pull everyone first, by where they stand now (not where the last step saw them), then
        // close the doors: nobody is left outside or caught in a doorway.
        if (holdsTheParty(ar, room)) {
            holdIn(ar, room, SEAL_MARGIN);
        }
        setDoors(ar, room, false, true);
        if (ar.kit.fights() != null) {
            for (Player p : online(ar)) {
                p.playSound(p.getLocation(), "minecraft:block.iron_door.close", 1f, 0.8f);
            }
            if (!events.start(ar, room)) {
                fights.start(ar, room);
            }
            return;
        }
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

    /** Files a mob as one of the run's: marked, tracked, and sent back home if it strays. */
    void register(ActiveRun ar, Entity mob, double[] home) {
        mark(ar, mob);
        if (mob instanceof LivingEntity living) {
            // A despawned enemy would count as killed: never let one go for being far from players.
            living.setRemoveWhenFarAway(false);
        }
        byMob.put(mob.getUniqueId(), ar);
        ar.mobHome.put(mob.getUniqueId(), home);
    }

    /** Marks an entity as the run's: it drops nothing and is known after a restart. */
    void mark(ActiveRun ar, Entity e) {
        e.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, ar.run.id().toString());
    }

    void forget(Entity mob) {
        byMob.remove(mob.getUniqueId());
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
            register(ar, mob, at);
            fights.scale(mob, ar.healthFactor * ar.difficultyHealth);
            alive.add(mob);
        }
        ar.roomMobs.put(room, alive);
        return alive.size();
    }

    /** Blocks from a wall to another room that count as the doorway when a room seals. */
    static final int SEAL_MARGIN = 2;

    /**
     * Whether this active room is the fight that holds the party: the first active room. A party
     * split across two rooms that seal at once fights them one after the other instead of being
     * pulled back and forth.
     */
    private static boolean holdsTheParty(ActiveRun ar, int room) {
        for (int r = 0; r < room; r++) {
            if (ar.run.room(r) == DungeonRun.Room.ACTIVE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Puts every member who is playing or downed (and online) inside a fight room: anyone outside
     * it is moved to its entrance. At the seal, standing within {@code margin} blocks of a way out
     * (a doorway) counts as outside; during the fight only leaving the room does, so lag or a stray
     * teleport never leaves someone outside a sealed fight.
     */
    private void holdIn(ActiveRun ar, int room, int margin) {
        double[] e = ar.geo.entry(room);
        for (Member m : ar.run.present()) {
            // Members outside the dungeon world were marked away by this step already.
            Player p = m.online() ? Bukkit.getPlayer(m.id()) : null;
            if (p == null || m.status() == Status.OUT) {
                continue;
            }
            // Where they stand now, downed or not (a downed player can still be moved).
            Location here = p.getLocation();
            if (!ar.geo.safelyInside(room, here.getX(), here.getZ(), margin)) {
                Location to = new Location(module.world(), e[0], e[1], e[2], here.getYaw(), here.getPitch());
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
        if (holdsTheParty(ar, room)) {
            holdIn(ar, room, 0);
        }
        Fights.Encounter enc = ar.encounters.get(room);
        if (enc != null) {
            List<Entity> all = new ArrayList<>(enc.alive);
            all.addAll(enc.adds);
            if (enc.bossMob != null) {
                all.add(enc.bossMob);
            }
            for (Entity mob : all) {
                Location l = mob.getLocation();
                double[] home = ar.mobHome.get(mob.getUniqueId());
                if (mob.isValid() && ar.geo.roomAt(l.getX(), l.getZ()) != room) {
                    mob.teleport(new Location(module.world(), home[0], home[1], home[2]));
                }
            }
            events.step(ar, room);
            if (fights.step(ar, room)) {
                clear(ar, room);
            }
            return;
        }
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
        events.cleared(ar, room);
        fights.cleared(ar, room);
        ar.run.clearRoom(room);
        ar.roomMobs.remove(room);
        setDoors(ar, room, false, false);
        RoomType type = ar.run.layout().rooms().get(room).node().type();
        boolean boss = type == RoomType.BOSS;
        for (Player p : online(ar)) {
            p.playSound(p.getLocation(), "minecraft:block.iron_door.open", 1f, 1.2f);
            Member m = ar.run.member(p.getUniqueId());
            if (boss) {
                p.sendMessage("§6The guardian falls. The way to the vault is open.");
            } else if (m.room() == room) {
                p.sendMessage("§aThe room is clear. The doors open.");
            }
        }
        RunRewards.Moment moment = clearedMoment(type, ar.planned.containsKey(room));
        supplies.cleared(ar, room, type, moment != null);
        if (moment != null) {
            reward(ar, moment);
        }
        if (moment == RunRewards.Moment.BOSS) {
            feat(ar, moment);
        }
        spoils(ar, false);
        ar.fightsCleared++;
        if (ar.holding && ar.banking.every() > 0 && ar.fightsCleared % ar.banking.every() == 0) {
            finds.bank(ar);
        }
    }

    /** What clearing a room of this type pays out: the boss, an elite room, or a finished event. */
    static RunRewards.Moment clearedMoment(RoomType type, boolean event) {
        if (type == RoomType.BOSS) {
            return RunRewards.Moment.BOSS;
        }
        if (type == RoomType.ELITE) {
            return RunRewards.Moment.ELITE;
        }
        return event ? RunRewards.Moment.EVENT : null;
    }

    /**
     * A party that has got nowhere for a while (no new room, no fight) gets one quiet hint: the
     * doorway on the way to the nearest room still to clear (or the vault once the boss is down),
     * named in chat and marked with snow particles for a few seconds. While they still get nowhere,
     * each further hint waits twice as long as the one before (up to 16 times).
     */
    private void hint(ActiveRun ar, long now) {
        if (now < ar.hintUntilMs) {
            for (Player p : online(ar)) {
                double[] d = ar.hintAt.get(p.getUniqueId());
                if (d != null) {
                    p.spawnParticle(org.bukkit.Particle.SNOWFLAKE, d[0], d[1] + 1.5, d[2], 6, 0.6, 1.2, 0.6, 0);
                }
            }
        }
        long wait = settings().hintSeconds() * 1000L;
        if (ar.progressMs > ar.lastHintMs) { // they got somewhere since: start the back-off over
            ar.hints = 0;
            ar.nextHintMs = 0;
        }
        if (ar.run.state() != DungeonRun.State.RUNNING || now - ar.progressMs < wait || now < ar.nextHintMs) {
            return;
        }
        ar.hintAt.clear();
        for (Player p : online(ar)) {
            Member m = ar.run.member(p.getUniqueId());
            double[] d = m.playing() ? wayOn(ar, m.room()) : null;
            if (d == null) {
                continue;
            }
            Location l = p.getLocation();
            ar.hintAt.put(p.getUniqueId(), d);
            p.sendMessage("§7Lost? The way on is " + RunGeometry.direction(l.getX(), l.getY(), l.getZ(), l.getYaw(),
                    d[0], d[1], d[2]) + ".");
            p.playSound(new Location(module.world(), d[0], d[1], d[2]), "minecraft:block.amethyst_block.chime", 1f,
                    1f);
        }
        if (!ar.hintAt.isEmpty()) { // nobody had a way to be shown: the hint is not used up
            ar.hints++;
            ar.lastHintMs = now;
            ar.nextHintMs = now + wait * (1L << Math.min(4, ar.hints));
            ar.hintUntilMs = now + 6000;
        }
    }

    /**
     * The spot just inside the doorway of {@code from} that leads toward the nearest room still to
     * do: a fight room not cleared yet, or the vault once the boss has fallen. Null when there is none,
     * or when the player already stands in such a room (a boss room waiting for the rest of the party).
     */
    private static double[] wayOn(ActiveRun ar, int from) {
        DungeonLayout layout = ar.run.layout();
        int n = layout.rooms().size();
        if (from < 0 || wanted(ar, from)) {
            return null;
        }
        int[] firstDoor = new int[n];
        java.util.Arrays.fill(firstDoor, -2);
        firstDoor[from] = -1;
        java.util.ArrayDeque<Integer> todo = new java.util.ArrayDeque<>(List.of(from));
        while (!todo.isEmpty()) {
            int r = todo.poll();
            if (r != from && wanted(ar, r)) {
                return ar.geo.insideDoor(from, firstDoor[r]);
            }
            for (DungeonLayout.Connection c : layout.connections()) {
                int next = c.roomA() == r ? c.roomB() : c.roomB() == r ? c.roomA() : -1;
                if (next >= 0 && firstDoor[next] == -2) {
                    int door = c.roomA() == r ? c.doorA() : c.doorB();
                    firstDoor[next] = r == from ? door : firstDoor[r];
                    todo.add(next);
                }
            }
        }
        return null;
    }

    /** Before the boss falls: fight rooms not cleared yet. After: only the vault (side fights are optional). */
    private static boolean wanted(ActiveRun ar, int room) {
        RoomType type = ar.run.layout().rooms().get(room).node().type();
        if (ar.run.bossDefeated()) {
            return type == RoomType.VAULT;
        }
        return DungeonRun.isFight(type) && ar.run.room(room) != DungeonRun.Room.CLEARED;
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
            for (int[] b : ar.geo.doorBlocks(room, door)) {
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
        if (!ar.run.alone(p.getUniqueId())) {
            p.sendMessage("§cYou are down. §7A party member crouching beside you for " + settings().reviveSeconds()
                    + " seconds brings you back.");
        } else if (ar.run.canSelfRevive(p.getUniqueId())) {
            p.sendMessage("§cYou are down. §7Nobody can reach you: crouch for " + settings().selfReviveSeconds()
                    + " seconds to get back up." + (ar.run.activeRoom() < 0 ? "" : " You can do this once per fight."));
        } else {
            p.sendMessage("§cYou are down. §7Nobody can reach you, and you already got up once in this fight"
                    + (ar.run.lives() > 0 ? ": in " + settings().aloneBleedOutSeconds()
                    + " seconds a shared life brings you back." : ", with no lives left."));
        }
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
                    double done = (double) ar.run.member(m.id()).reviveMs() / needed;
                    bars.show(reviver, "Reviving " + d.getName(), done, BossBar.Color.GREEN, now);
                    bars.show(d, reviver.getName() + " is reviving you", done, BossBar.Color.GREEN, now);
                }
                continue;
            }
            boolean alone = ar.run.alone(m.id());
            boolean self = ar.run.canSelfRevive(m.id());
            if (self && d.isSneaking()) {
                long selfNeeded = settings().selfReviveSeconds() * 1000L;
                if (ar.run.addSelfRevive(m.id(), STEP_MS, selfNeeded)) {
                    clearDowned(ar, d);
                    d.setHealth(Math.max(1.0, d.getAttribute(Attribute.MAX_HEALTH).getValue() * 0.3));
                    d.sendMessage("§aYou get back up." + (ar.run.activeRoom() < 0 ? ""
                            : " §7If you go down again in this fight, you can't get up on your own."));
                    event(ar, d.getName() + " got back up alone");
                } else {
                    bars.show(d, "Getting up", (double) ar.run.member(m.id()).reviveMs() / selfNeeded,
                            BossBar.Color.GREEN, now);
                }
                continue;
            }
            ar.run.resetRevive(m.id());
            long limit = (alone && !self ? settings().aloneBleedOutSeconds() : settings().bleedOutSeconds()) * 1000L;
            long left = limit - (now - m.downedAtMs());
            if (left <= 0) {
                bleedOut(ar, d);
                continue;
            }
            bars.show(d, self ? "You are down: crouch to get up" : "You are down. Lives left: " + ar.run.lives(),
                    (double) left / limit, self ? BossBar.Color.YELLOW : BossBar.Color.RED, now);
        }
    }

    private void bleedOut(ActiveRun ar, Player p) {
        boolean back = fell(ar, p, "fell", situation(ar, p));
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
        int room = ar.run.activeRoom();
        if (room >= 0) {
            double[] e = ar.geo.entry(room);
            return new Location(module.world(), e[0], e[1], e[2]);
        }
        return checkpoint(ar);
    }



    /** A downed member brought back up at once by another (smelling salts). False when they are not down. */
    boolean reviveNow(ActiveRun ar, Player by, Player down, String with) {
        long needed = settings().reviveSeconds() * 1000L;
        if (!ar.run.addRevive(down.getUniqueId(), needed, needed)) {
            return false;
        }
        clearDowned(ar, down);
        down.setHealth(Math.max(1.0, down.getAttribute(Attribute.MAX_HEALTH).getValue() * 0.3));
        tell(ar, "§a" + by.getName() + " brings " + down.getName() + " back up with " + with + ".");
        event(ar, by.getName() + " revived " + down.getName() + " with " + with);
        return true;
    }

    // ------------------------------------------------------------------ supplies (for the listener)

    /** Whether a player is playing a run here now: what heals them is the dungeon's to say. */
    public boolean playingHere(Player p) {
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        return ar != null && inDungeonWorld(p) && isPlayingMember(ar, p);
    }

    /** A player right-clicks a supply item. */
    public void useSupply(Player p, org.bukkit.inventory.ItemStack item) {
        supplies.use(p, item);
    }

    /** A player in a run took damage. */
    public void hurt(Player p) {
        supplies.hurt(p);
    }

    /** Whether a drunk ward stops this potion effect on a player (used up when it does). */
    public boolean wardsPotion(Player p, String type) {
        return supplies.wardsPotion(p, type);
    }

    /** Whether a drunk ward stops this MythicMobs aura on a player (used up when it does). */
    public boolean wardsAura(Player p, String aura) {
        return supplies.wardsAura(p, aura);
    }

    /** Mana from MMOCore, for supplies and the campfire. */
    public void mana(Ports.Mana mana) {
        supplies.mana = mana;
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
            var cause = p.getLastDamageCause();
            fell(ar, p, "died outright", "of " + (cause == null ? "an unknown cause" : cause.getCause()));
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

    /** Whether the player is in the dungeon world, in this run on the character that entered it. */
    boolean here(ActiveRun ar, Player p) {
        return inDungeonWorld(p) && isPlayingMember(ar, p);
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
        quit(p, "DISCONNECTED");
    }

    /**
     * A player logs out ({@code reason}: how, as the server tells it). Logging out dodges nothing:
     * while down it is a fall, and in the middle of a fight it costs what a fall would (what they
     * carry, and staked gear). Each such loss is logged with how they quit and the fight they left,
     * so staff can give it back when it was a real connection problem.
     */
    public void quit(Player p, String reason) {
        unqueue(p);
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        if (ar == null) {
            return;
        }
        Member m = ar.run.member(p.getUniqueId());
        String detail = "logged out (" + reason + ") " + situation(ar, p);
        if (m.status() == Status.DOWNED) {
            clearDowned(ar, p);
            fell(ar, p, "logged out while down", detail);
        } else if (fightInProgress(ar) && here(ar, p)) {
            // (someone out of the fight lost it all as they fell; after the win no gear is at stake)
            finds.lose(ar, p, "logged out in the middle of a fight", detail);
        }
        ar.run.offline(p.getUniqueId(), clock.getAsLong());
    }

    /** Where a player stands in their run, for the loss log: their health, the room, the fight. */
    private String situation(ActiveRun ar, Player p) {
        int active = ar.run.activeRoom();
        int room = ar.geo.roomAt(p.getLocation().getX(), p.getLocation().getZ());
        return String.format(java.util.Locale.ROOT, "with %.0f/%.0f health in %s%s", p.getHealth(),
                p.getAttribute(Attribute.MAX_HEALTH).getValue(),
                room < 0 ? "between rooms" : ar.run.layout().rooms().get(room).node().type().name().toLowerCase(java.util.Locale.ROOT),
                active < 0 ? ", no fight on" : ", " + ar.roomMobs.getOrDefault(active, Set.of()).size() + " enemies left in the fight");
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
        if (lockers != null) {
            // A worn loadout stays on only for a member playing their run in the dungeon world.
            lockers.settle(p, m != null && Objects.equals(m.characterId(), charId) && inDungeonWorld(p));
        }
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
            // back on another character: they leave the run, by the leaving rules
            if (ar.lobbyRoom >= 0) {
                finds.refund(ar, p.getUniqueId());
            }
            finds.settleAway(ar, m, keepNow(ar), false,
                    "you came back on another character");
            ar.run.leave(p.getUniqueId());
            byPlayer.remove(p.getUniqueId());
        }
        if (inDungeonWorld(p) && (m != null || !p.hasPermission(STAFF_PERMISSION))) {
            sendBack(p, charId);
        } else if (p.getPersistentDataContainer().has(spectatorKey, PersistentDataType.BYTE)) {
            unspectate(p);
        }
        if (!inDungeonWorld(p) && finds.deliverPending(p, charId) && lockers != null) {
            lockers.settle(p, false); // (spoils among them are handed over now)
        }
    }

    private boolean fightInProgress(ActiveRun ar) {
        return ar.run.activeRoom() >= 0;
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
        boolean here = here(ar, p);
        if (ar.lobbyRoom >= 0) {
            finds.refund(ar, p.getUniqueId()); // (left before the doors opened: insurance is given back)
        }
        if (!here) { // (away from the dungeon: the leaving rules, handed over later)
            finds.settleAway(ar, ar.run.member(p.getUniqueId()), keepNow(ar),
                    false, "you left the run while away from it");
        }
        if (here) { // (leaving before the boss falls is a run that failed, for them)
            boolean down = ar.run.member(p.getUniqueId()).status() == Status.DOWNED;
            if (down || ar.gearAtRisk && ar.run.activeRoom() >= 0) {
                // (leaving while down, or mid-fight with gear at stake, dodges no loss)
                finds.lose(ar, p, down ? "left while down" : "left in the middle of a fight", situation(ar, p));
            }
            finds.payout(ar, p, keepNow(ar), false, "left the run");
        }
        depart(ar, p, here);
        if (!inDungeonWorld(p)) {
            sweep(p); // (outside: what came home is handed over now)
        }
        return true;
    }

    /** A member goes: out of the run, back outside, the party told. */
    private void depart(ActiveRun ar, Player p, boolean here) {
        ar.run.leave(p.getUniqueId());
        fights.left(ar, p);
        events.left(ar, p);
        if (here) {
            clearDowned(ar, p);
            sendBack(p, ar.run.member(p.getUniqueId()).characterId());
        } else {
            unspectate(p);
        }
        tell(ar, "§7" + p.getName() + " has left the dungeon.");
    }

    /** A player right-clicked inside the dungeon world: at the vault's exit, they leave. */
    /** A right-click on a block: true when it was a room event's prop (the click is used up). */
    public boolean propClick(Player p, org.bukkit.block.Block b) {
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        if (ar != null && exitAt(ar, b.getX(), b.getY(), b.getZ()) >= 0) {
            openExit(p);
            return true;
        }
        return ar != null && events.click(ar, p, b.getX(), b.getY(), b.getZ());
    }

    // ------------------------------------------------------------------ ways out, finds, insurance

    /** Where a treasure room's way out stands: on its cache spot. */
    private int[] exitBlock(ActiveRun ar, int room) {
        double[] c = ar.geo.markers(room, "chest").get(0); // (the kit loader makes sure every treasure room has one)
        return new int[] {(int) Math.floor(c[0]), (int) Math.floor(c[1]), (int) Math.floor(c[2])};
    }

    private java.util.function.Supplier<ItemStack> wayOutLook = () -> null;

    /** What ways out look like: an item whose model stands there; null for a plain glowing block. */
    public void wayOutLook(java.util.function.Supplier<ItemStack> look) {
        wayOutLook = look;
    }

    private void showExit(ActiveRun ar, int room) {
        int[] b = exitBlock(ar, room);
        ItemStack look = wayOutLook.get();
        // its model stands in a barrier (so it can be clicked); without the model, a glowing block
        world().getBlockAt(b[0], b[1], b[2]).setType(look == null ? Material.CRYING_OBSIDIAN : Material.BARRIER, false);
        if (look != null) {
            ar.extras.add(world().spawn(new Location(world(), b[0] + 0.5, b[1] + 0.5, b[2] + 0.5), org.bukkit.entity.ItemDisplay.class, d -> {
                d.setItemStack(look);
                d.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED);
                d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                d.setPersistent(false);
            }));
        }
        tell(ar, "§5A way out glimmers in the treasure room. §7Right-click it to leave alone, taking some of your finds.");
    }

    /** The treasure room whose way out is at a block, or -1. */
    private int exitAt(ActiveRun ar, int x, int y, int z) {
        for (int room : ar.exitsShown) {
            int[] b = exitBlock(ar, room);
            if (b[0] == x && b[1] == y && b[2] == z) {
                return room;
            }
        }
        return -1;
    }

    /** Why a player can't take a way out now, or null when they can. */
    private String exitProblem(ActiveRun ar, Player p) {
        // (finds stop being held at the win; in a wipe nobody is on their feet)
        if (ar == null || !ar.holding || !here(ar, p)) {
            return "§cThere is no way out for you here.";
        }
        if (ar.run.member(p.getUniqueId()).status() != Status.ALIVE) {
            return "§cOnly someone on their feet can take the way out.";
        }
        if (!ar.exitsShown.contains(ar.geo.roomAt(p.getLocation().getX(), p.getLocation().getZ()))) {
            return "§cStand by a way out in a treasure room to take it.";
        }
        return null;
    }

    /** Opens the way-out screen: pick the finds to take home. */
    public void openExit(Player p) {
        String no = exitProblem(byPlayer.get(p.getUniqueId()), p);
        if (no != null) {
            p.sendMessage(no);
            return;
        }
        new FindsMenu(this, plugin, p, FindsMenu.Mode.EXIT).open();
    }

    /** What a way out would let a player take, or null when they can't take one. */
    RunFinds.Offer exitOffer(Player p) {
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        return exitProblem(ar, p) != null ? null : finds.offer(ar, p);
    }

    /**
     * Leaves through a way out with the picked finds (indexes into the offer's finds at risk).
     * Returns what went wrong, or null when they left.
     */
    String exitWith(Player p, List<Integer> picks) {
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        String no = exitProblem(ar, p);
        if (no != null) {
            return no;
        }
        if (!finds.exit(ar, p, picks)) {
            return "§cThose finds are worth more than the way out lets you take.";
        }
        byPlayer.remove(p.getUniqueId());
        p.sendMessage("§5You take the way out.");
        depart(ar, p, true);
        return null;
    }

    /** What a player holds in a banking run (carried and banked), or null when they are in none. */
    RunFinds.Held heldBy(Player p) {
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        return ar == null || !ar.holding || !here(ar, p) ? null
                : ar.held.getOrDefault(p.getUniqueId(), new RunFinds.Held());
    }

    /** The loot rules of a player's run (for the finds screen). */
    List<String> lootRules(Player p) {
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        return ar == null || ar.difficulty == null ? List.of() : ar.difficulty.lootRules();
    }

    /** How a find shows on the finds screens. */
    org.bukkit.inventory.ItemStack icon(RunRewards.Find f) {
        return rewards == null ? new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER) : rewards.icon(f);
    }

    /** Opens the finds screen: what a player carries and what is banked. */
    public void openFinds(Player p) {
        if (heldBy(p) == null) {
            p.sendMessage("§7You hold no finds: only runs that bank finds keep them until they come home.");
            return;
        }
        new FindsMenu(this, plugin, p, FindsMenu.Mode.VIEW).open();
    }

    /** Opens insurance: in the lobby of a run where worn gear is at stake. */
    public void openInsurance(Player p) {
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        if (ar == null || !ar.gearAtRisk || !inLobby(p)) {
            p.sendMessage("§cInsurance is bought in the lobby of a run where your worn gear is at stake.");
            return;
        }
        new FindsMenu(this, plugin, p, FindsMenu.Mode.INSURE).open();
    }

    List<RunFinds.Insurable> insurable(Player p) {
        return finds.insurable(p);
    }

    /** The worn piece a player insured in their run, or null. */
    String insured(Player p) {
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        return ar == null ? null : ar.insured.get(p.getUniqueId());
    }

    /** Insures a worn piece (picked again: cancels); returns what to tell the player. */
    String insure(Player p, String id) {
        ActiveRun ar = byPlayer.get(p.getUniqueId());
        if (ar == null || !ar.gearAtRisk || !inLobby(p)) {
            return "§cInsurance is bought in the lobby of a run where your worn gear is at stake.";
        }
        return finds.insure(ar, p, id);
    }

    /** A player's dungeon marks, or -1. */
    int marksOf(Player p) {
        return rewards == null ? -1 : rewards.marks(p);
    }

    /** What a player lost in runs, oldest first (for staff looking into a ticket). */
    public List<FindStore.Loss> losses(UUID player) {
        return finds.store() == null ? List.of() : finds.store().losses(player);
    }

    /**
     * Gives a loss back: it waits for the character that lost it, handed over at once when they are
     * online outside a run. Null when there is no such loss or it was given back already.
     */
    public FindStore.Loss restoreLoss(UUID player, int id) {
        FindStore.Loss l = finds.store() == null ? null : finds.store().restore(player, id);
        Player p = Bukkit.getPlayer(player);
        if (l != null && p != null && !inDungeonWorld(p)) {
            sweep(p);
        }
        return l;
    }

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
        if (lockers != null) {
            lockers.takeOff(p); // dungeon items never leave the dungeon world
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
        recordWin(ar);
        ar.securedParty = stayed(ar);
        ar.holding = false; // (what is found from now on is paid at once)
        // the boss fell: what each member here carries and banked comes home
        forPlaying(ar, "the finds", p -> finds.payout(ar, p, 1, true, ""));
        long took = clock.getAsLong() - ar.openedMs;
        boolean underPar = ar.rules.par() > 0 && took <= ar.rules.par() * 60_000;
        if (ar.rules.par() > 0) {
            tell(ar, underPar ? "§a✦ Under par (" + Records.time((long) (ar.rules.par() * 60_000))
                    + ")! §7The vault favours you." : "§7Par was " + Records.time((long) (ar.rules.par() * 60_000)) + ".");
        }
        reward(ar, RunRewards.Moment.VAULT, underPar);
        feat(ar, RunRewards.Moment.VAULT);
        spoils(ar, true);
        record(ar);
    }

    /** A feat for each member still playing: trophies and wardrobe skins may come of it. */
    private void feat(ActiveRun ar, RunRewards.Moment moment) {
        if (rewards != null && ar.holding) { // (holding ends as the vault is reached)
            return; // (a carried boss's trophy roll comes with its reward when it comes home)
        }
        if (rewards != null) {
            String d = ar.difficulty == null ? "" : ar.difficulty.id();
            List<String> party = stayed(ar);
            forPlaying(ar, "the " + moment + " cosmetics", p -> {
                if (ar.run.member(p.getUniqueId()).status() != Status.OUT) { // (spectators carried along get none)
                    rewards.feat(p, ar.run.recipeId(), d, moment, ar.run.id(), party);
                }
            });
        }
    }

    /** A won gate run goes on its leaderboard (its dungeon, difficulty and party size); the party hears its time. */
    private void record(ActiveRun ar) {
        if (records == null || !ar.ranked) {
            return;
        }
        long millis = clock.getAsLong() - ar.openedMs;
        List<String> party = stayed(ar);
        int place = records.add(ar.run.recipeId(), ar.difficulty == null ? null : ar.difficulty.id(), party.size(),
                new Records.Clear(millis, party, clock.getAsLong()));
        String board = (ar.difficulty == null ? "" : ar.difficulty.name() + ", ") + size(party.size());
        String time = Records.time(millis);
        tell(ar, place == 1 ? "§6✦ A new record: §f" + time + " §7(" + board + ")"
                : place > 0 ? "§eYour time: §f" + time + "§7, place " + place + " on the board (" + board + ")"
                : "§7Your time: " + time + " (" + board + ")");
    }

    /** Who entered the run and has not left it, by display name. */
    static List<String> stayed(ActiveRun ar) {
        return ar.party.entrySet().stream().filter(e -> ar.run.member(e.getKey()).status() != Status.LEFT)
                .map(Map.Entry::getValue).toList();
    }

    /**
     * A member falls (a bleed-out, or logging out while down): what they carry is lost (banked
     * finds stay), and where gear is at stake, the dungeon gear they wear is destroyed. True when a
     * life brought them back.
     */
    private boolean fell(ActiveRun ar, Player p, String why, String detail) {
        boolean back = ar.run.bleedOut(p.getUniqueId());
        finds.lose(ar, p, why, detail);
        return back;
    }

    /** The chance each banked find comes home when a run fails. */
    private static double keep(ActiveRun ar) {
        return ar.banking == null ? 1 : ar.banking.keep();
    }

    /** What a member leaving now keeps of what they banked: all once the boss has fallen, else the difficulty's share. */
    private static double keepNow(ActiveRun ar) {
        return ar.run.state() == DungeonRun.State.COMPLETE ? 1 : keep(ar);
    }

    /** Rolls whether a banked find comes home when a run fails. */
    java.util.function.DoubleSupplier keepRoll = () -> ThreadLocalRandom.current().nextDouble();

    /** Rolls whether a treasure room holds a way out. */
    java.util.function.DoubleSupplier exitRoll = () -> ThreadLocalRandom.current().nextDouble();

    boolean safely(Player p, String what, Runnable give) {
        try {
            give.run();
            return true;
        } catch (Exception | LinkageError e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[dungeons] " + what + " for " + p.getName() + " failed", e);
            return false;
        }
    }

    /** A party size as players read it. */
    public static String size(int players) {
        return players == 1 ? "solo" : "party of " + players;
    }

    /**
     * This week's affixes for a run of a dungeon at a tier: none without an affixes section, or for
     * a kit without a fight roster (the affixes work on its fights).
     */
    public List<WeeklyAffix> weeklyAffixes(String recipe, int tier) {
        Affixes a = module.affixes();
        RecipeLoader.Entry entry = module.recipes().get(recipe);
        DungeonKit kit = entry == null ? null : module.kits().get(entry.kit());
        return a == null || kit == null || kit.fights() == null ? List.of() : a.of(Affixes.week(clock.getAsLong()), tier);
    }

    /** The leaderboards, or null when they are off. */
    public Records records() {
        return records;
    }

    public void records(Records records) {
        this.records = records;
    }

    /** A won run at a difficulty: each member playing it has won it, and may open what it leads to. */
    private void recordWin(ActiveRun ar) {
        if (ar.difficulty == null || lockers == null) {
            return;
        }
        String recipe = ar.run.recipeId();
        RecipeLoader.Entry entry = module.recipes().get(recipe);
        for (Player p : online(ar)) {
            var locker = isPlayingMember(ar, p) ? lockers.locker(p) : null;
            if (locker == null || !locker.clear(recipe, ar.difficulty.id())) {
                continue;
            }
            if (!lockers.saveLocker(p, locker)) {
                locker.unclear(recipe, ar.difficulty.id());
                p.sendMessage("§cYour win could not be saved, so it does not open anything yet. Tell staff.");
                continue;
            }
            if (entry != null) {
                entry.difficulties().stream().filter(d -> ar.difficulty.id().equals(d.needs()))
                        .forEach(d -> p.sendMessage("§6" + d.name() + " is now open for you in this dungeon."));
            }
        }
    }

    private void fail(ActiveRun ar) {
        for (Player p : online(ar)) { // the ones still down when the party wipes fall too
            if (ar.run.member(p.getUniqueId()).status() == Status.DOWNED) {
                String detail = situation(ar, p);
                clearDowned(ar, p);
                fell(ar, p, "fell as the party was wiped out", detail);
            }
        }
        ar.run.state(DungeonRun.State.FAILED);
        ar.finishAtMs = clock.getAsLong() + 5000L;
        tell(ar, "§cYour party has fallen. The dungeon casts you out.");
    }

    /** Ends a run now, sending everyone inside back. With {@code fault}, the key is refunded. */
    public void end(ActiveRun ar, boolean fault, String reason) {
        end(ar, fault, false, reason);
    }

    /**
     * As {@link #end(ActiveRun, boolean, String)}; {@code stop} is a server stop: then nobody loses
     * what they found (not their fault), where any other end that isn't a win counts as a failed run.
     */
    public void end(ActiveRun ar, boolean fault, boolean stop, String reason) {
        DungeonRun.State outcome = ar.run.state();
        ar.run.state(DungeonRun.State.ENDED);
        runs.remove(ar.run.id());
        double keep = outcome == DungeonRun.State.COMPLETE || stop ? 1 : keep(ar);
        String why = stop ? "a stop cut your run short" : outcome == DungeonRun.State.COMPLETE ? ""
                : outcome == DungeonRun.State.FAILED ? "the run failed" : "the run was ended";
        if (ar.lobbyRoom >= 0) {
            new ArrayList<>(ar.insurePaid.keySet()).forEach(id -> finds.refund(ar, id)); // (the doors never opened)
        }
        for (Member m : ar.run.members()) {
            byPlayer.remove(m.id(), ar);
            Player p = Bukkit.getPlayer(m.id());
            if (p != null && m.status() != Status.LEFT && here(ar, p)) {
                if (reason != null) {
                    p.sendMessage(reason);
                }
                // what they banked comes home by the difficulty's rules; a fault (a stop) loses nothing
                finds.payout(ar, p, keep, stop, why);
                ar.downedAt.remove(m.id());
                sendBack(p, m.characterId());
            } else if (m.status() != Status.LEFT) {
                // away (offline, or outside the dungeon): the same rules, and it waits for them
                finds.settleAway(ar, m, keep, stop, stop ? why : "you were away when the run ended");
                if (p != null && inDungeonWorld(p)) {
                    sendBack(p, m.characterId());
                } else if (p != null) {
                    sweep(p); // (online outside: what came home is handed over now)
                }
            }
        }
        for (Set<Entity> set : ar.roomMobs.values()) {
            for (Entity mob : set) {
                byMob.remove(mob.getUniqueId());
                mobs.remove(mob);
            }
        }
        ar.roomMobs.clear();
        events.ended(ar);
        fights.ended(ar);
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
        ActiveRun ar = byMob.remove(mob.getUniqueId());
        if (ar != null) {
            fights.died(ar, mob);
        }
    }

    // ------------------------------------------------------------------ fight hooks

    /** How hard a hit from this entity on a run player lands: run mobs may be rallied, enraged or charging. */
    public double outgoingFactor(Entity source) {
        ActiveRun ar = byMob.get(source.getUniqueId());
        if (ar == null) {
            return 1;
        }
        // boss mechanics already grow with the tier: the difficulty's damage is for ordinary hits.
        // Only the mechanic's own hit (this handler runs first) is spared, not hits it sets off.
        return fights.outgoing(ar, source) * (fights.takeMechanic() ? 1 : ar.damageFactor);
    }

    /** How much of a hit a run mob takes (a boss between phases none, a shielded elite less). */
    public double incomingFactor(Entity mob) {
        ActiveRun ar = byMob.get(mob.getUniqueId());
        return ar == null ? 1 : fights.incoming(ar, mob);
    }

    /** Most damage one hit may deal to a run mob: a boss can't be hit past its next phase. */
    public double damageCap(Entity mob) {
        ActiveRun ar = byMob.get(mob.getUniqueId());
        return ar == null ? Double.POSITIVE_INFINITY : fights.cap(ar, mob);
    }

    /** A run mob took damage (from a player, when {@code by} is set). */
    public void mobHurt(Entity mob, Player by, double amount) {
        ActiveRun ar = byMob.get(mob.getUniqueId());
        if (ar == null) {
            return;
        }
        fights.hurt(ar, mob, amount);
        if (by == null) {
            return;
        }
        ar.damage.merge(by.getUniqueId(), amount, Double::sum);
        fights.hit(ar, mob);
    }

    /** Something spawned in the dungeon world: inside a run, it is that run's to clean up. */
    public void spawned(Entity e) {
        if (byMob.containsKey(e.getUniqueId())) {
            return;
        }
        Location l = e.getLocation();
        for (ActiveRun ar : runs.values()) {
            if (ar.geo.roomAt(l.getX(), l.getZ()) >= 0) {
                fights.adopt(ar, e);
                return;
            }
        }
    }

    /** One console line per notable run event, for staff and for tuning. */
    void event(ActiveRun ar, String what) {
        plugin.getLogger().info("[dungeons] run " + ar.run.id().toString().substring(0, 8) + ": " + what);
    }

    void tell(ActiveRun ar, String message) {
        for (Player p : online(ar)) {
            p.sendMessage(message);
        }
    }

    /** Tells the online members standing in a room. */
    void tellRoom(ActiveRun ar, int room, String message) {
        for (Player p : online(ar)) {
            if (ar.run.member(p.getUniqueId()).room() == room) {
                p.sendMessage(message);
            }
        }
    }

    /** Online members still in the run. */
    List<Player> online(ActiveRun ar) {
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
                    .append(esc(m.characterId())).append("\",\"status\":\"").append(m.status())
                    .append("\",\"damage\":").append(Math.round(ar.damage.getOrDefault(m.id(), 0.0))).append("}");
            sep = ",";
        }
        return b.append("]}").toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
