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
    static final List<String> DEFAULT_FILES = List.of("dungeons/recipes.yml", "dungeons/settings.yml");

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
    private RunManager runs;
    private AllyFilter allyFilter;

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
        runs.start();
        plugin.getServer().getPluginManager().registerEvents(new RunListener(plugin, runs), plugin);
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
        File settingsFile = new File(root, "settings.yml");
        settings = settingsFile.isFile()
                ? RunSettings.from(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(settingsFile))
                : RunSettings.defaults();
        recipes.values().removeIf(e -> {
            DungeonKit kit = kits.get(e.kit());
            if (kit == null) {
                problems.add("recipe " + e.recipe().id() + ": unknown kit " + e.kit());
                return true;
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
        return DungeonGenerator.generate(kits.get(entry.kit()).catalog(), entry.recipe(), seed);
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
