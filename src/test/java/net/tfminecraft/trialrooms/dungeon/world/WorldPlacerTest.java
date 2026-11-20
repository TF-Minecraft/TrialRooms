package net.tfminecraft.trialrooms.dungeon.world;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonGenerator;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonRecipe;
import net.tfminecraft.trialrooms.dungeon.generator.GreyboxKitTest;
import net.tfminecraft.trialrooms.dungeon.grid.Orientation;
import net.tfminecraft.trialrooms.dungeon.grid.Side;
import net.tfminecraft.trialrooms.dungeon.load.DungeonKit;
import net.tfminecraft.trialrooms.dungeon.template.Doorway;
import net.tfminecraft.trialrooms.dungeon.template.RoomCatalog;
import net.tfminecraft.trialrooms.dungeon.template.RoomTemplate;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;
import org.bukkit.Chunk;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.plugin.Plugin;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class WorldPlacerTest {
  @TempDir Path temp;
  ServerMock server;
  Plugin plugin;
  World world;
  Block block;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    Server srv = mock(Server.class);
    when(srv.getScheduler()).thenReturn(server.getScheduler());
    when(srv.createBlockData(anyString())).thenReturn(mock(BlockData.class));
    plugin = mock(Plugin.class);
    when(plugin.getServer()).thenReturn(srv);
    when(plugin.isEnabled()).thenReturn(true);
    world = mock(World.class);
    when(world.getMaxHeight()).thenReturn(256);
    block = mock(Block.class);
    when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
    when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true)))
        .thenAnswer(i -> CompletableFuture.completedFuture(mock(Chunk.class)));
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  static Structure sized(int x, int y, int z) {
    Structure s = mock(Structure.class);
    when(s.getSize()).thenReturn(new BlockVector(x, y, z));
    return s;
  }

  static DungeonLayout layout(DungeonKit kit, long seed) {
    DungeonRecipe r = new DungeonRecipe("t", 3, 3, java.util.Map.of(RoomType.COMBAT, 1), true, true, 1, 1, 1, 1,
        null, 5, 20000);
    return DungeonGenerator.generate(kit.catalog(), r, seed);
  }

  @Test
  void opensWipesAndConfiguresTheWorld() throws IOException {
    Path dir = temp.resolve("d");
    Files.createDirectories(dir.resolve("region/sub"));
    Files.writeString(dir.resolve("region/sub/r.0.0.mca"), "x");
    Files.createDirectories(dir.resolve("poi"));
    Files.createDirectories(dir.resolve("DIM1/region"));
    Files.writeString(dir.resolve("DIM1/region/r.0.0.mca"), "x");
    Files.writeString(dir.resolve(DungeonWorld.OWNER_FILE), "x");
    AtomicReference<WorldCreator> made = new AtomicReference<>();
    World w = DungeonWorld.open(temp, "d", c -> {
      made.set(c);
      return world;
    }, Logger.getLogger("t"));
    assertSame(world, w);
    assertFalse(Files.exists(dir.resolve("region")));
    assertFalse(Files.exists(dir.resolve("poi")));
    assertFalse(Files.exists(dir.resolve("DIM1/region")));
    assertEquals(World.Environment.THE_END, made.get().environment());
    verify(world).setAutoSave(false);
    verify(world).setGameRule(GameRule.DO_MOB_SPAWNING, false);
    verify(world).setViewDistance(6);
    var gen = made.get().generator();
    assertFalse(gen.shouldGenerateNoise() || gen.shouldGenerateSurface() || gen.shouldGenerateCaves()
        || gen.shouldGenerateDecorations() || gen.shouldGenerateMobs() || gen.shouldGenerateStructures());
    var biomes = made.get().biomeProvider();
    assertEquals(Biome.THE_VOID, biomes.getBiome(null, 0, 0, 0));
    assertEquals(List.of(Biome.THE_VOID), biomes.getBiomes(null));
    // a fresh folder is fine too; a foreign one is refused; a failed create is reported
    assertSame(world, DungeonWorld.open(temp, "fresh", c -> {
      try {
        Files.createDirectories(temp.resolve("fresh"));
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      return world;
    }, Logger.getLogger("t")));
    Files.createDirectories(temp.resolve("foreign"));
    assertThrows(IOException.class, () -> DungeonWorld.open(temp, "foreign", c -> world, Logger.getLogger("t")));
    assertThrows(IOException.class, () -> DungeonWorld.open(temp, "none", c -> null, Logger.getLogger("t")));
  }

  @Test
  void placesEveryRoomSealsUnusedDoorsAndReleases() {
    DungeonKit kit = GreyboxKitTest.kit();
    DungeonLayout layout = layout(kit, 5);
    var structures = new java.util.HashMap<String, Structure>();
    for (RoomTemplate t : kit.catalog().templates()) {
      structures.put(t.id(), sized(t.widthCells() * 32, 12, t.depthCells() * 32));
    }
    LayoutPlacer placer = new LayoutPlacer(plugin, world, kit, structures::get, layout, 8192, 64, 0);
    assertEquals(8192, placer.originX());
    assertEquals(64, placer.originY());
    assertEquals(0, placer.originZ());
    CompletableFuture<Location> done = placer.place();
    server.getScheduler().performTicks(layout.rooms().size() + 3);
    Location at = done.join();
    assertEquals(65, at.getBlockY());
    assertTrue(placer.heldChunks() > 0);
    long placed = structures.values().stream()
        .mapToLong(s -> mockingDetails(s).getInvocations().stream().filter(i -> i.getMethod().getName().equals("place")).count())
        .sum();
    assertEquals(layout.rooms().size(), placed);
    int unused = 0;
    for (int i = 0; i < layout.rooms().size(); i++) {
      unused += layout.unusedDoorways(i).size();
    }
    verify(block, times(unused * 4 * kit.doorHeight())).setBlockData(any(), eq(false));
    placer.release();
    placer.release();
    assertEquals(0, placer.heldChunks());
  }

  /** A one-room kit with a start room in every orientation, to reach each seal side and the fallback arrival. */
  @Test
  void sealsEverySideAndFallsBackToTheCellCentre() {
    RoomTemplate start = new RoomTemplate("s", RoomType.START, 1, 1,
        List.of(new Doorway(0, 0, Side.NORTH, 4), new Doorway(0, 0, Side.EAST, 4), new Doorway(0, 0, Side.SOUTH, 4),
            new Doorway(0, 0, Side.WEST, 4)),
        List.of(new net.tfminecraft.trialrooms.dungeon.template.Marker("spawn", "", 1, 1, 1, null)), 1, 0, 0, 0,
        Set.of());
    RoomTemplate boss = new RoomTemplate("b", RoomType.BOSS, 1, 1, List.of(new Doorway(0, 0, Side.SOUTH, 4)),
        List.of(), 1, 0, 0, 0, Set.of());
    DungeonKit kit = new DungeonKit("k", new RoomCatalog(32, 24, List.of(start, boss)), java.util.Map.of(), 4, "x",
        "minecraft:iron_bars", java.util.Map.of());
    DungeonRecipe r = new DungeonRecipe("t", 0, 0, null, false, false, 0, 0, 1, 1, null, 1, 100);
    DungeonLayout layout = DungeonGenerator.generate(kit.catalog(), r, 1);
    Structure s = sized(32, 8, 32);
    LayoutPlacer placer = new LayoutPlacer(plugin, world, kit, id -> s, layout, 0, 64, 0);
    placer.placeRoom(0);
    verify(block, times(3 * 4 * 4)).setBlockData(any(), eq(false));
    Location at = placer.arrival();
    double[] in = new net.tfminecraft.trialrooms.dungeon.run.RunGeometry(layout, 32, 4, 0, 64, 0).insideDoor(0, 0);
    assertEquals(in[0], at.getX());
    assertEquals(in[2], at.getZ());
    assertEquals(65, at.getY());
    // a room that would reach past the world's top or bottom is refused
    assertThrows(IllegalStateException.class,
        () -> new LayoutPlacer(plugin, world, kit, id -> s, layout, 0, 250, 0).placeRoom(0));
    when(world.getMinHeight()).thenReturn(100);
    assertThrows(IllegalStateException.class, () -> placer.placeRoom(0));
  }

  @Test
  void reportsBadStructuresAndLoadFailures() {
    DungeonKit kit = GreyboxKitTest.kit();
    DungeonLayout layout = layout(kit, 9);
    for (Structure bad : new Structure[] {null, sized(31, 12, 32), sized(32, 12, 31), sized(32, 25, 32)}) {
      LayoutPlacer p = new LayoutPlacer(plugin, world, kit, id -> bad, layout, 0, 64, 0);
      var f = p.place();
      server.getScheduler().performTicks(3);
      assertTrue(f.isCompletedExceptionally());
      assertEquals(0, p.heldChunks());
    }
    // a chunk that fails to load
    when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("io")));
    var failed = new LayoutPlacer(plugin, world, kit, id -> null, layout, 0, 64, 0).place();
    server.getScheduler().performTicks(2);
    assertTrue(failed.isCompletedExceptionally());
    // released before the chunks arrive
    CompletableFuture<Chunk> slow = new CompletableFuture<>();
    when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true))).thenReturn(slow);
    LayoutPlacer early = new LayoutPlacer(plugin, world, kit, id -> null, layout, 0, 64, 0);
    var pending = early.place();
    early.release();
    slow.complete(mock(Chunk.class));
    server.getScheduler().performTicks(2);
    assertTrue(pending.isCompletedExceptionally());
    assertEquals(0, early.heldChunks());
  }

  @Test
  void releasingMidwayStopsPlacement() {
    DungeonKit kit = GreyboxKitTest.kit();
    DungeonLayout layout = layout(kit, 3);
    Structure any = mock(Structure.class);
    when(any.getSize()).thenAnswer(i -> new BlockVector(32, 12, 32));
    LayoutPlacer p = new LayoutPlacer(plugin, world, kit, id -> sized(32 * kit.catalog().templates().stream()
        .filter(t -> t.id().equals(id)).findFirst().orElseThrow().widthCells(), 12, 32 * kit.catalog().templates()
        .stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow().depthCells()), layout, 0, 64, 0);
    var f = p.place();
    server.getScheduler().performTicks(2);
    p.release();
    assertTrue(f.isCompletedExceptionally());
  }

  @Test
  void rotationsMapToVanilla() {
    assertEquals(StructureRotation.NONE, LayoutPlacer.rotation(Orientation.R0));
    assertEquals(StructureRotation.CLOCKWISE_90, LayoutPlacer.rotation(Orientation.M90));
    assertEquals(StructureRotation.CLOCKWISE_180, LayoutPlacer.rotation(Orientation.R180));
    assertEquals(StructureRotation.COUNTERCLOCKWISE_90, LayoutPlacer.rotation(Orientation.R270));
    assertNotNull(Mirror.LEFT_RIGHT);
  }
}
