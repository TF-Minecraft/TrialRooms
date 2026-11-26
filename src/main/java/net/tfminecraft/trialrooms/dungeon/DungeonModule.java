package net.tfminecraft.trialrooms.dungeon;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.Collection;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.structure.Structure;

import net.tfminecraft.trialrooms.dungeon.generator.DungeonGenerator;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout;
import net.tfminecraft.trialrooms.dungeon.load.DungeonKit;
import net.tfminecraft.trialrooms.dungeon.load.KitLoader;
import net.tfminecraft.trialrooms.dungeon.load.RecipeLoader;
import net.tfminecraft.trialrooms.dungeon.allies.AllyFilter;
import net.tfminecraft.trialrooms.dungeon.allies.AllyRules;
import net.tfminecraft.trialrooms.dungeon.run.GateStore;
import net.tfminecraft.trialrooms.dungeon.run.Ports;
import net.tfminecraft.trialrooms.dungeon.locker.ItemCodec;
import net.tfminecraft.trialrooms.dungeon.loot.DungeonLoot;
import net.tfminecraft.trialrooms.dungeon.loot.DungeonRewards;
import net.tfminecraft.trialrooms.dungeon.loot.RewardRules;
import net.tfminecraft.trialrooms.dungeon.loot.LootKit;
import net.tfminecraft.trialrooms.dungeon.loot.MmoLootForge;
import net.tfminecraft.trialrooms.dungeon.locker.LockerListener;
import net.tfminecraft.trialrooms.dungeon.locker.LockerService;
import net.tfminecraft.trialrooms.dungeon.locker.LockerStore;
import net.tfminecraft.trialrooms.dungeon.run.RunFiles;
import net.tfminecraft.trialrooms.dungeon.run.RunListener;
import net.tfminecraft.trialrooms.dungeon.run.RunManager;
import net.tfminecraft.trialrooms.dungeon.run.RunSettings;
import net.tfminecraft.trialrooms.dungeon.world.DungeonWorld;
import net.tfminecraft.trialrooms.dungeon.world.LayoutPlacer;

/**
 * Wires the dungeon engine into the plugin: content under {@code plugins/TrialRooms/dungeons/},
 * the dungeon world, and staff previews. Phase 1 has no player runs yet.
 */
public final class DungeonModule {

    public static final String WORLD_NAME = "tfmc_dungeons";
    /** Floor height of every room. */
    public static final int FLOOR_Y = 64;
    /** Slots run east from here, far from the End's dragon arena around 0,0. */
    public static final int SLOT_START_X = 8192;
    /** Blocks between slot centres; a layout must fit within half of it around its start room. */
    public static final int SLOT_SPACING = 1024;
    /** Bundled default files, copied only when missing. */
    static final List<String> DEFAULT_LISTS = List.of("dungeons/kits/greybox/files.txt",
            "dungeons/kits/seithr/files.txt");
    static final List<String> DEFAULT_FILES = List.of("dungeons/recipes.yml", "dungeons/settings.yml",
            "dungeons/loot.yml");

    private final JavaPlugin plugin;
    private final Function<WorldCreator, World> creator;
    private final Map<String, DungeonKit> kits = new LinkedHashMap<>();
    private final Map<String, RecipeLoader.Entry> recipes = new LinkedHashMap<>();
    private final Map<String, Structure> structureCache = new HashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final List<LayoutPlacer> previews = new ArrayList<>();
    private World world;
    private int nextSlot;
    private RunSettings settings = RunSettings.defaults();
    private net.tfminecraft.trialrooms.dungeon.combat.CombatSettings combat =
            net.tfminecraft.trialrooms.dungeon.combat.CombatSettings.DEFAULTS;
    private net.tfminecraft.trialrooms.dungeon.combat.Attacks attacks;
    private Ports.Mobs mobs;
    /** Enemies staff spawned to watch as leaders (their areas marked on the ground). */
    private final java.util.Set<java.util.UUID> testLeaders = new java.util.HashSet<>();
    private RunManager runs;
    private AllyFilter allyFilter;
    private LootKit lootKit;
    private RewardRules rewardRules;
    private net.tfminecraft.trialrooms.dungeon.loot.Spoils spoils;
    private net.tfminecraft.trialrooms.dungeon.loot.Cosmetics cosmetics;
    private net.tfminecraft.trialrooms.dungeon.run.Affixes affixes;
    private DungeonLoot loot;
    /** Whether GunsAndGadgets was told of dungeon guns. */
    private boolean guns;
    /** Whether Magic holds Equilibrium in runs. */
    private boolean magic;
    private DungeonRewards rewards;
    private net.tfminecraft.trialrooms.dungeon.gear.GearService gear;

    public DungeonModule(JavaPlugin plugin, Function<WorldCreator, World> creator) {
        this.plugin = plugin;
        this.creator = creator;
    }

    public void enable() {
        installDefaults();
        reload();
        World loaded = plugin.getServer().getWorld(WORLD_NAME);
        if (loaded != null) {
            // Re-enabled on a running server: keep the loaded world, never wipe it under itself.
            world = loaded;
            return;
        }
        try {
            world = DungeonWorld.open(plugin.getServer().getWorldContainer().toPath(), WORLD_NAME, creator,
                    plugin.getLogger());
        } catch (IOException | RuntimeException e) {
            // A broken dungeon world never takes the rest of TrialRooms down with it.
            plugin.getLogger().severe("Dungeons are off: " + e);
        }
    }

    public void disable() {
        if (gear != null) {
            gear.stop();
        }
        if (attacks != null) {
            attacks.stop();
        }
        if (guns) {
            net.tfminecraft.gunsandgadgets.api.DungeonGuns.onRefresh(null);
        }
        if (magic) {
            net.tfminecraft.magic.api.DungeonWeapons.holdEquilibrium(null);
        }
        if (runs != null) {
            runs.stop();
        }
        clearPreviews();
    }

    /** Starts dungeon runs with the given plugin bridges and registers their events. */
    public void startRuns(Ports.Characters characters, Ports.Keys keys, Ports.Mobs mobs) {
        startRuns(characters, keys, mobs, Ports.Fx.NONE);
    }

    public void startRuns(Ports.Characters characters, Ports.Keys keys, Ports.Mobs mobs, Ports.Fx fx) {
        Path root = plugin.getDataFolder().toPath().resolve("dungeons");
        runs = new RunManager(plugin, this, characters, keys, mobs, fx, new RunFiles(root, plugin.getLogger()),
                new GateStore(root.resolve("gates.yml").toFile(), plugin.getLogger()), System::currentTimeMillis);
        LockerService lockers = new LockerService(plugin, new LockerStore(root.resolve("lockers"), plugin.getLogger()),
                ItemCodec.PAPER, characters::activeCharacterId, org.bukkit.entity.Player::saveData);
        runs.lockers(lockers);
        runs.findStore(new net.tfminecraft.trialrooms.dungeon.run.FindStore(root.resolve("finds"), plugin.getLogger()));
        runs.records(new net.tfminecraft.trialrooms.dungeon.run.Records(root.resolve("records.yml").toFile(),
                plugin.getLogger()));
        var plugins = plugin.getServer().getPluginManager();
        if (plugins.isPluginEnabled("MMOItems")) {
            // lockers may hold MMOItems even with loot off: they are never reforged
            plugins.registerEvents(new net.tfminecraft.trialrooms.dungeon.loot.ReforgeGuard(lockers::isDungeonItem), plugin);
        }
        if (plugins.isPluginEnabled("MMOItems") && plugins.isPluginEnabled("AdvancedCrafting")) {
            loot = new DungeonLoot(new MmoLootForge(plugin.getLogger()), this::lootKit, new java.util.Random());
            if (plugins.isPluginEnabled("GunsAndGadgets")) {
                optional("dungeon guns", this::startGuns);
            }
            if (plugins.isPluginEnabled("Magic")) {
                optional("dungeon mage weapons", this::startMageWeapons);
            }
            rewards = new DungeonRewards(loot, lockers, this::rewardRules, new java.util.Random(),
                    System::currentTimeMillis, java.time.ZoneId.systemDefault(), this::spoils, plugin.getLogger())
                    .cosmetics(this::cosmetics, net.tfminecraft.trialrooms.dungeon.loot.Cosmetics::runOnConsole)
                    .accounts(new net.tfminecraft.trialrooms.dungeon.loot.AccountLimits(root.resolve("accounts.yml").toFile(),
                            plugin.getLogger()));
            runs.rewards(rewards);
            runs.wayOutLook(this::wayOut);
            startGear(new net.tfminecraft.trialrooms.dungeon.gear.MythicStats());
        } else {
            plugin.getLogger().warning("[dungeons] dungeon loot is off: it needs MMOItems and AdvancedCrafting");
        }
        this.mobs = mobs;
        // (after set bonuses, before the run's own judgement: both act on hits at HIGHEST)
        startCombat(new net.tfminecraft.trialrooms.dungeon.combat.MythicControl(this::attacks, plugin.getLogger()));
        runs.start();
        plugin.getServer().getPluginManager().registerEvents(new RunListener(plugin, runs), plugin);
        plugin.getServer().getPluginManager().registerEvents(new LockerListener(lockers), plugin);
    }

    /** Starts what another plugin's newer version is for; without it, the rest goes on. */
    void optional(String what, Runnable start) {
        try {
            start.run();
        } catch (LinkageError e) {
            plugin.getLogger().warning("[dungeons] " + what + " are off: their plugin is too old (" + e + ")");
        }
    }

    private void startMageWeapons() {
        var forge = new net.tfminecraft.trialrooms.dungeon.loot.MagicForge();
        forge.archetypes(); // (fails here, not mid-run, when Magic doesn't have them)
        // what a character trains belongs to the gear they own: casting in a run never moves Equilibrium
        net.tfminecraft.magic.api.DungeonWeapons.holdEquilibrium(runs::inDungeonWorld);
        magic = true;
        loot.maker(new net.tfminecraft.trialrooms.dungeon.loot.MageLoot(forge, MmoLootForge::skin));
    }

    /** Dungeon guns drop, and keep their dungeon lines when GunsAndGadgets brings their stats up to date. */
    private void startGuns() {
        var guns = new net.tfminecraft.trialrooms.dungeon.loot.GunLoot(
                new net.tfminecraft.trialrooms.dungeon.loot.GgGunForge(), this::lootKit);
        net.tfminecraft.gunsandgadgets.api.DungeonGuns.onRefresh(guns::refreshed);
        loot.guns(guns);
        this.guns = true;
    }

    /** Starts set bonuses and weapon traits (inside dungeons only). */
    void startGear(net.tfminecraft.trialrooms.dungeon.gear.GearService.Stats stats) {
        gear = new net.tfminecraft.trialrooms.dungeon.gear.GearService(plugin.getServer(), runs::inDungeonWorld,
                java.util.function.Predicate.not(runs::isDowned), this::enemy, this::sets, this::worth, stats,
                System::currentTimeMillis, this::later);
        plugin.getServer().getPluginManager().registerEvents(gear, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, gear::tick, 20L, 10L);
    }

    void later(Runnable task) {
        if (plugin.isEnabled()) { // (stopping: what is left of a trait is dropped)
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    /** Whether an entity is a run's enemy (a player's summon there isn't). */
    boolean enemy(org.bukkit.entity.Entity e) {
        return runs.isRunMob(e) && owner.apply(e) == null;
    }

    /** The player behind a summon (needs MythicMobs running). */
    java.util.function.Function<org.bukkit.entity.Entity, org.bukkit.entity.Player> owner = AllyFilter::ownerOf;

    /** What ways out look like, or null without the look. */
    org.bukkit.inventory.ItemStack wayOut() {
        return lootKit == null ? null : loot.look(lootKit.looks() + "way_out");
    }

    Map<String, net.tfminecraft.trialrooms.dungeon.gear.GearSet> sets() {
        return lootKit == null ? Map.of() : lootKit.sets();
    }

    Map<String, Double> worth() {
        return lootKit == null ? Map.of() : lootKit.worth();
    }

    /** Enemy attacks: windups, strikes, openings (every tick). */
    void startCombat(net.tfminecraft.trialrooms.dungeon.combat.MobControl control) {
        attacks = new net.tfminecraft.trialrooms.dungeon.combat.Attacks(control, this::combat, this::marked);
        var pm = plugin.getServer().getPluginManager();
        if (control instanceof org.bukkit.event.Listener l) {
            pm.registerEvents(l, plugin);
        }
        pm.registerEvents(attacks, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, attacks::tick, 1L, 1L);
    }

    /** Whether an enemy's attack areas are marked on the ground: a run's boss or leader, or a staff test leader. */
    boolean marked(org.bukkit.entity.Entity e) {
        return testLeaders.contains(e.getUniqueId()) | runs.isMarked(e); // (both cheap)
    }

    /** Enemy attacks at work, or null before runs start. */
    public net.tfminecraft.trialrooms.dungeon.combat.Attacks attacks() {
        return attacks;
    }

    /** settings.yml's combat section. */
    public net.tfminecraft.trialrooms.dungeon.combat.CombatSettings combat() {
        return combat;
    }

    /**
     * Staff: one enemy at a spot to watch its attacks, at a difficulty's level; as a leader its attack
     * areas are marked on the ground. Null when the type is unknown.
     */
    public org.bukkit.entity.Entity mobTest(String type, org.bukkit.Location at, int tier, boolean leader) {
        org.bukkit.entity.Entity mob = mobs == null ? null : mobs.spawn(type, at, Math.max(1, tier));
        testLeaders.removeIf(id -> plugin.getServer().getEntity(id) == null); // (gone ones)
        if (mob != null && leader) {
            testLeaders.add(mob.getUniqueId());
        }
        return mob;
    }

    /** Set bonuses at work, or null without loot. */
    public net.tfminecraft.trialrooms.dungeon.gear.GearService gear() {
        return gear;
    }

    public RunManager runs() {
        return runs;
    }

    /**
     * Starts the ally filter (no friendly fire of any kind in dungeons) over MythicMobs mechanics:
     * once shortly after start, then every minute to catch mechanics parsed later, and after each
     * MythicMobs reload.
     */
    public AllyFilter startAllyFilter(Supplier<Collection<?>> mechanics) {
        AllyRules rules = new AllyRules(runs::inDungeonWorld, AllyFilter::ownerOf);
        AllyFilter filter = new AllyFilter(rules, () -> settings.harmfulAuras(), () -> settings.helpfulAuras(),
                mechanics, plugin.getLogger());
        plugin.getServer().getPluginManager().registerEvents(filter, plugin);
        allyFilter = filter;
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int added = filter.attach();
            if (added > 0) {
                plugin.getLogger().info("[dungeons] ally filter on " + added + " more MythicMobs mechanics");
            }
        }, 100L, 1200L);
        return filter;
    }

    public AllyFilter allyFilter() {
        return allyFilter;
    }

    /** loot.yml, or null when it is missing or has problems. */
    public LootKit lootKit() {
        return lootKit;
    }

    /** Dungeon loot, or null without its item plugins. */
    public DungeonLoot loot() {
        return loot;
    }

    /** loot.yml's rewards, or null. */
    public RewardRules rewardRules() {
        return rewardRules;
    }

    /** This week's affixes and their numbers; null when none. */
    public net.tfminecraft.trialrooms.dungeon.run.Affixes affixes() {
        return affixes;
    }

    /** Trophies and wardrobe skins; null when none. */
    public net.tfminecraft.trialrooms.dungeon.loot.Cosmetics cosmetics() {
        return cosmetics;
    }

    /** What leaves dungeons with the players (act materials, rare items); null when none. */
    public net.tfminecraft.trialrooms.dungeon.loot.Spoils spoils() {
        return spoils;
    }

    /** What runs hand out (and the locker's shop and socketing), or null without loot. */
    public DungeonRewards rewards() {
        return rewards;
    }

    public RunSettings settings() {
        return settings;
    }

    /** Reloads kits and recipes from disk. Returns the problems found. */
    public List<String> reload() {
        problems.clear();
        kits.clear();
        recipes.clear();
        structureCache.clear();
        File root = new File(plugin.getDataFolder(), "dungeons");
        File[] kitDirs = new File(root, "kits").listFiles(File::isDirectory);
        List<File> dirs = new ArrayList<>(kitDirs == null ? List.of() : List.of(kitDirs));
        dirs.sort(null);
        for (File dir : dirs) {
            DungeonKit kit = KitLoader.load(dir, problems);
            if (kit != null) {
                kits.put(kit.id(), kit);
            }
        }
        recipes.putAll(RecipeLoader.load(new File(root, "recipes.yml"), problems));
        File lootFile = new File(root, "loot.yml");
        var lootYml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(lootFile);
        lootKit = lootFile.isFile() ? LootKit.load(lootYml, problems) : null;
        rewardRules = RewardRules.load(lootYml, lootKit, problems);
        spoils = net.tfminecraft.trialrooms.dungeon.loot.Spoils.load(lootYml, problems);
        cosmetics = net.tfminecraft.trialrooms.dungeon.loot.Cosmetics.load(lootYml, problems);
        File settingsFile = new File(root, "settings.yml");
        affixes = settingsFile.isFile() ? net.tfminecraft.trialrooms.dungeon.run.Affixes.load(
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(settingsFile), problems) : null;
        settings = settingsFile.isFile()
                ? RunSettings.from(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(settingsFile))
                : RunSettings.defaults();
        try {
            combat = net.tfminecraft.trialrooms.dungeon.combat.CombatSettings.from(settingsFile.isFile()
                    ? org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(settingsFile).getConfigurationSection("combat")
                    : null);
        } catch (IllegalArgumentException e) {
            problems.add("settings.yml combat: " + e.getMessage());
            combat = net.tfminecraft.trialrooms.dungeon.combat.CombatSettings.DEFAULTS;
        }
        recipes.values().removeIf(e -> {
            DungeonKit kit = kits.get(e.kit());
            if (kit == null) {
                problems.add("recipe " + e.recipe().id() + ": unknown kit " + e.kit());
                return true;
            }
            var roomy = kit.catalog().roomy(e.rules().minRoomCells());
            var needed = DungeonGenerator.neededTypes(e.recipe());
            for (var type : java.util.List.of(net.tfminecraft.trialrooms.dungeon.template.RoomType.COMBAT,
                    net.tfminecraft.trialrooms.dungeon.template.RoomType.ELITE)) {
                if (e.rules().minRoomCells() > 1 && needed.contains(type) && roomy.ofType(type).isEmpty()) {
                    problems.add("recipe " + e.recipe().id() + ": kit " + e.kit() + " has no " + type.name().toLowerCase()
                            + " room of at least " + e.rules().minRoomCells() + " cells");
                    return true;
                }
            }
            if (kit.fights() != null && kit.fights().boss(e.rules().boss()) == null) {
                problems.add("recipe " + e.recipe().id() + ": kit " + e.kit() + " has no boss '" + e.rules().boss()
                        + "'; its boss room holds a strong pack instead");
            }
            return false;
        });
        for (String p : problems) {
            plugin.getLogger().warning("[dungeons] " + p);
        }
        plugin.getLogger().info("[dungeons] " + kits.size() + " kits, " + recipes.size() + " recipes");
        return List.copyOf(problems);
    }

    /** Copies bundled kits and recipes into the data folder; existing files are never overwritten. */
    void installDefaults() {
        List<String> files = new ArrayList<>(DEFAULT_FILES);
        for (String list : DEFAULT_LISTS) {
            String dir = list.substring(0, list.lastIndexOf('/') + 1);
            // A bundled kit installs once, as a whole: rooms staff delete stay deleted.
            if (Files.exists(plugin.getDataFolder().toPath().resolve(dir))) {
                continue;
            }
            for (String line : resourceLines(list)) {
                files.add(dir + line);
            }
        }
        for (String path : files) {
            Path target = plugin.getDataFolder().toPath().resolve(path);
            if (Files.exists(target)) {
                continue;
            }
            InputStream in = plugin.getResource(path);
            if (in == null) {
                plugin.getLogger().warning("[dungeons] bundled file missing: " + path);
                continue;
            }
            // Copy to a temporary file first, so a failed copy never leaves a partial file that
            // later starts would treat as installed.
            Path partial = target.resolveSibling(target.getFileName() + ".tmp");
            try (in) {
                Files.createDirectories(target.getParent());
                Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
                Files.move(partial, target);
            } catch (IOException e) {
                plugin.getLogger().warning("[dungeons] could not install " + path + ": " + e.getMessage());
                partial.toFile().delete(); // best effort; the next start overwrites it anyway
            }
        }
    }

    private List<String> resourceLines(String path) {
        InputStream in = plugin.getResource(path);
        if (in == null) {
            return List.of();
        }
        try (in) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * @throws IllegalArgumentException for an unknown recipe or a kit missing a needed room type
     * @throws DungeonGenerator.GenerationException when no layout was found
     */
    public DungeonLayout generate(String recipeId, long seed) {
        RecipeLoader.Entry entry = recipes.get(recipeId);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown recipe: " + recipeId);
        }
        return DungeonGenerator.generate(kits.get(entry.kit()).catalog().roomy(entry.rules().minRoomCells()),
                entry.recipe(), seed);
    }

    /** Builds a layout in the next free slot; the future gives the arrival point. */
    public CompletableFuture<Location> preview(DungeonLayout layout) {
        LayoutPlacer placer;
        try {
            placer = placer(layout);
        } catch (IllegalStateException e) {
            return CompletableFuture.failedFuture(e);
        }
        previews.add(placer);
        return placer.place();
    }

    /**
     * A placer for a layout in the next free slot (slots are never reused within one uptime).
     *
     * @throws IllegalStateException when the world is not open or the layout is too large for a slot
     */
    public LayoutPlacer placer(DungeonLayout layout) {
        if (world == null) {
            throw new IllegalStateException("The dungeon world is not open");
        }
        DungeonKit kit = kits.get(recipes.get(layout.recipeId()).kit());
        int cell = kit.catalog().cellSize();
        int[] b = layout.bounds();
        int reach = Math.max(Math.max(-b[0], -b[1]), Math.max(b[2] + 1, b[3] + 1)) * cell;
        if (reach > SLOT_SPACING / 2) {
            throw new IllegalStateException(
                    "Layout reaches " + reach + " blocks from its start; a slot allows " + SLOT_SPACING / 2);
        }
        int slot = nextSlot++;
        return new LayoutPlacer(plugin, world, kit, id -> structure(kit, id), layout,
                SLOT_START_X + slot * SLOT_SPACING, FLOOR_Y, 0);
    }

    /** Releases every preview's chunks. Returns how many previews were cleared. */
    public int clearPreviews() {
        int n = previews.size();
        for (LayoutPlacer p : previews) {
            p.release();
        }
        previews.clear();
        return n;
    }

    Structure structure(DungeonKit kit, String roomId) {
        return structureCache.computeIfAbsent(kit.id() + "/" + roomId, key -> {
            try {
                return plugin.getServer().getStructureManager().loadStructure(kit.structures().get(roomId));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    public Map<String, DungeonKit> kits() {
        return kits;
    }

    public Map<String, RecipeLoader.Entry> recipes() {
        return recipes;
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    public World world() {
        return world;
    }
}
