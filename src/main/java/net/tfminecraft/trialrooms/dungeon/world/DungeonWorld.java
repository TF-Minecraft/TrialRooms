package net.tfminecraft.trialrooms.dungeon.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

/**
 * The plugin-owned void world that holds dungeon runs.
 *
 * <p>It uses the End environment because TFMC's datapack makes the overworld 1,600 blocks tall;
 * the End keeps chunks 256 blocks tall, which the M0 spike measured at about a third of the
 * memory and a tenth of the load time. The world is never saved: its chunks are wiped at every
 * start, so nothing a run built outlives a restart.
 */
public final class DungeonWorld {

    /** Marks a world folder as ours, so the wipe never touches a world it does not own. */
    static final String OWNER_FILE = "trialrooms-dungeons.owner";
    static final String OWNER_TEXT = "TrialRooms dungeon world: chunks are deleted at every start";

    private DungeonWorld() {
    }

    /**
     * Wipes and opens the dungeon world.
     *
     * @param container the server's world container folder
     * @param creator   creates the world; {@code WorldCreator::createWorld} on a server
     * @throws IOException when a folder of that name exists but is not ours, or files can't be written
     */
    public static World open(Path container, String name, Function<WorldCreator, World> creator, Logger log)
            throws IOException {
        Path dir = container.resolve(name);
        Path owner = dir.resolve(OWNER_FILE);
        if (Files.exists(dir) && !Files.isRegularFile(owner)) {
            throw new IOException("World folder " + name + " exists but is not a TrialRooms dungeon world");
        }
        int wiped = wipe(dir);
        World world = creator.apply(new WorldCreator(name).environment(World.Environment.THE_END)
                .generator(new VoidGenerator()).biomeProvider(new VoidBiomes()).generateStructures(false));
        if (world == null) {
            throw new IOException("Dungeon world " + name + " could not be created");
        }
        Files.createDirectories(dir);
        Files.writeString(owner, OWNER_TEXT);
        world.setAutoSave(false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setViewDistance(6);
        world.setSimulationDistance(4);
        log.info("Dungeon world " + name + " ready (" + wiped + " old chunk files wiped)");
        return world;
    }

    /** Deletes the chunk, entity and POI data of an owned world folder. Returns paths deleted. */
    static int wipe(Path dir) throws IOException {
        int deleted = 0;
        // An End-environment world keeps its chunks under DIM1/; the top level is checked as well.
        for (String sub : List.of("region", "entities", "poi", "DIM1/region", "DIM1/entities", "DIM1/poi")) {
            Path p = dir.resolve(sub);
            if (!Files.isDirectory(p)) {
                continue;
            }
            List<Path> paths;
            try (Stream<Path> walk = Files.walk(p)) {
                paths = walk.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path q : paths) {
                Files.delete(q);
                deleted++;
            }
        }
        return deleted;
    }

    /** Generates nothing at all. */
    static final class VoidGenerator extends ChunkGenerator {
        @Override
        public boolean shouldGenerateNoise() {
            return false;
        }

        @Override
        public boolean shouldGenerateSurface() {
            return false;
        }

        @Override
        public boolean shouldGenerateCaves() {
            return false;
        }

        @Override
        public boolean shouldGenerateDecorations() {
            return false;
        }

        @Override
        public boolean shouldGenerateMobs() {
            return false;
        }

        @Override
        public boolean shouldGenerateStructures() {
            return false;
        }
    }

    /** Every block is in The Void biome: no natural spawns, no End islands. */
    static final class VoidBiomes extends BiomeProvider {
        @Override
        public Biome getBiome(WorldInfo info, int x, int y, int z) {
            return Biome.THE_VOID;
        }

        @Override
        public List<Biome> getBiomes(WorldInfo info) {
            return List.of(Biome.THE_VOID);
        }
    }
}
