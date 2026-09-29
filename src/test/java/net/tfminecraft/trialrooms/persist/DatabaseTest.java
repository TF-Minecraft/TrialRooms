package net.tfminecraft.trialrooms.persist;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import net.tfminecraft.trialrooms.TrialTestSupport;
import net.tfminecraft.trialrooms.environment.chest.LootChest;
import net.tfminecraft.trialrooms.environment.door.DoorBlock;
import net.tfminecraft.trialrooms.environment.entrance.Entrance;
import net.tfminecraft.trialrooms.environment.spawner.*;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.*;

class DatabaseTest extends TrialTestSupport {
  @Test
  void locationAdaptersRoundTripCoordinatesWorldFallbackAndOrientation() {
    var world = server.addSimpleWorld("dungeon");
    var loc = new Location(world, 1, 2, 3, 45, 20);
    var dto = Database.SimpleLocation.of(loc);
    assertEquals(loc, dto.toBukkit());
    dto.worldUUID = UUID.randomUUID();
    assertEquals(loc, dto.toBukkit());
    dto.worldUUID = null;
    assertEquals(loc, dto.toBukkit());
    dto.worldName = null;
    assertNull(dto.toBukkit());
    assertNull(Database.SimpleLocation.of(null));
    assertNull(Database.SimpleLocation.of(new Location(null, 0, 0, 0)));
    var zero = Database.SimpleLocation.of(new Location(world, 0, 0, 0));
    assertNull(zero.yaw);
    assertNull(zero.pitch);
    assertEquals(0, zero.toBukkit().getPitch());
    assertNotNull(Database.SimpleLocation.of(new Location(world, 0, 0, 0, 0, 10)).pitch);
    var adapter = new Database.SimpleLocation.Adapter();
    assertSame(JsonNull.INSTANCE, adapter.serialize(null, null, null));
    assertNull(adapter.deserialize(null, null, null));
    assertNull(adapter.deserialize(JsonNull.INSTANCE, null, null));
    var encoded = adapter.serialize(Database.SimpleLocation.of(loc), null, null);
    assertEquals(loc, adapter.deserialize(encoded, null, null).toBukkit());
    var bare = adapter.serialize(dto, null, null).getAsJsonObject();
    assertFalse(bare.has("world_uuid"));
    assertFalse(bare.has("world_name"));
    var json = JsonParser.parseString("{\"world_uuid\":\"bad\",\"x\":1,\"y\":2,\"z\":3}");
    assertNull(adapter.deserialize(json, null, null).worldUUID);
    var compact = adapter.serialize(zero, null, null).getAsJsonObject();
    assertFalse(compact.has("yaw"));
    assertFalse(compact.has("pitch"));
    assertNotNull(
        adapter.deserialize(JsonParser.parseString("{\"x\":0,\"y\":0,\"z\":0}"), null, null));
  }

  @Test
  void spawnerChestSnapshotsRoundTripAndPruneRemovedRecords() throws Exception {
    Database.init(plugin);
    assertTrue(Database.loadSpawners().isEmpty());
    assertTrue(Database.loadChests().isEmpty());
    Database.saveSpawners(null);
    Database.saveChests(null);
    var world = server.addSimpleWorld("dungeon");
    var loc = new Location(world, 1, 64, 3);
    var spawner = mock(ActiveSpawner.class);
    when(spawner.getUUID()).thenReturn("spawner");
    when(spawner.getId()).thenReturn("crypt");
    when(spawner.getLoc()).thenReturn(loc);
    when(spawner.getState()).thenReturn(ActiveSpawner.State.COOLDOWN);
    when(spawner.getDoorBlocks())
        .thenReturn(List.of(new DoorBlock(loc, Material.IRON_BARS).connect(BlockFace.NORTH)));
    Database.saveSpawners(List.of(spawner));
    var loaded = Database.loadSpawners();
    assertEquals(1, loaded.size());
    assertEquals("crypt", loaded.getFirst().id);
    assertEquals(loc, loaded.getFirst().loc.toBukkit());
    assertEquals(List.of("NORTH"), loaded.getFirst().doors.getFirst().faces);
    verify(spawner).onUnloaded(plugin);
    try (var holo = mockConstruction(Hologram.class)) {
      var chest = new LootChest(loc, spawner);
      Database.saveChests(List.of(chest));
      assertEquals("spawner", Database.loadChests().getFirst().spawnerUUID);
      var independent = new LootChest(loc, null);
      Database.saveChests(List.of(independent));
      assertNull(Database.loadChests().getFirst().spawnerUUID);
      assertEquals("NORTH", Database.ChestRecord.from(independent).facing);
    }
    when(spawner.getDoorBlocks()).thenThrow(new IllegalStateException("bad provider"));
    assertTrue(Database.SpawnerRecord.from(spawner).doors.isEmpty());
    assertTrue(Database.DoorRecord.of(null, null, null, null).faces.isEmpty());
    Database.saveSpawners(List.of());
    Database.saveChests(List.of());
    assertTrue(Database.loadSpawners().isEmpty());
    assertTrue(Database.loadChests().isEmpty());
  }

  @Test
  void corruptFilesSurviveLoadAndSubsequentSave() throws Exception {
    Database.init(plugin);
    var spawner = temp.resolve("data/spawners/broken.json");
    var chest = temp.resolve("data/chests/broken.json");
    Files.writeString(spawner, "{broken");
    Files.writeString(chest, "{broken");
    assertTrue(Database.loadSpawners().isEmpty());
    assertTrue(Database.loadChests().isEmpty());
    Database.saveSpawners(List.of());
    Database.saveChests(List.of());
    assertEquals("{broken", Files.readString(spawner));
    assertEquals("{broken", Files.readString(chest));
  }

  @Test
  void entrancesRoundTripAndInvalidRecordsAreIgnored() throws Exception {
    Database.init(plugin);
    var gson = new Gson();
    assertTrue(Database.loadEntrances(plugin, gson).isEmpty());
    var world = server.addSimpleWorld("entrance-world");
    var loc = new Location(world, 1, 64, 3);
    try (var holo = mockConstruction(Hologram.class)) {
      var entrance = new Entrance(loc);
      entrance.setDestination(loc.clone().add(5, 0, 0));
      entrance.setExitLoc(loc.clone().add(6, 0, 0));
      entrance.setKeyPath("v.key");
      Database.saveEntrances(plugin, gson, List.of(entrance));
      var records = Database.loadEntrances(plugin, gson);
      assertEquals(1, records.size());
      assertEquals("v.key", records.getFirst().keyPath);
      assertEquals(loc, records.getFirst().entrance.toBukkit());
      Database.saveEntrances(plugin, gson, List.of());
      assertTrue(Database.loadEntrances(plugin, gson).isEmpty());
      Database.saveEntrances(plugin, gson, List.of(new Entrance(null)));
      assertTrue(Database.loadEntrances(plugin, gson).isEmpty());
      Database.saveEntrances(plugin, gson, List.of(new Entrance(new Location(null, 0, 0, 0))));
    }
    var folder = temp.resolve("data/entrances");
    Files.writeString(folder.resolve("null.json"), "null");
    Files.writeString(folder.resolve("bad.json"), "{");
    Files.writeString(folder.resolve("ignore.txt"), "ignored");
    assertTrue(Database.loadEntrances(plugin, gson).isEmpty());
  }

  @Test
  void missingUnreadableAndUnwritableDirectoriesDoNotCrash() throws Exception {
    Database.init(plugin);
    var folder = temp.resolve("data/spawners");
    Files.writeString(folder.resolve("null.json"), "null");
    Files.writeString(folder.resolve("ignore.txt"), "");
    assertTrue(Database.loadSpawners().isEmpty());
    Files.setPosixFilePermissions(folder, Set.of());
    try {
      assertTrue(Database.loadSpawners().isEmpty());
      Database.saveSpawners(List.of());
    } finally {
      Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
    }
    Files.delete(folder.resolve("null.json"));
    Files.delete(folder.resolve("ignore.txt"));
    Files.delete(folder);
    assertTrue(Database.loadSpawners().isEmpty());
    var spawner = mock(ActiveSpawner.class);
    when(spawner.getUUID()).thenReturn("a");
    when(spawner.getState()).thenReturn(ActiveSpawner.State.IDLE);
    assertDoesNotThrow(() -> Database.saveSpawners(List.of(spawner)));
    Files.writeString(folder, "not a directory");
    assertTrue(Database.loadSpawners().isEmpty());
    var entrances = temp.resolve("data/entrances");
    Files.writeString(entrances, "file");
    assertTrue(Database.loadEntrances(plugin, new Gson()).isEmpty());
    try (var holo = mockConstruction(Hologram.class)) {
      Database.saveEntrances(plugin, new Gson(), List.of(new Entrance(null)));
    }
  }

  @Test
  void failedRecordsAreRetainedAndReplacementQuarantinesOriginalBytes() throws Exception {
    Database.init(plugin);
    Database.retainRecord(new Object());
    var world = server.addSimpleWorld("preserve");
    var spawner = mock(ActiveSpawner.class);
    when(spawner.getUUID()).thenReturn("reused");
    when(spawner.getState()).thenReturn(ActiveSpawner.State.IDLE);
    var file = temp.resolve("data/spawners/reused.json");
    Files.writeString(file, "{broken");
    Database.loadSpawners();
    Database.saveSpawners(List.of(spawner));
    assertTrue(Files.readString(file).contains("reused"));
    try (var files = Files.list(file.getParent())) {
      var backups = files.filter(f -> f.getFileName().toString().contains(".rejected-")).toList();
      assertEquals(1, backups.size());
      assertEquals("{broken", Files.readString(backups.getFirst()));
    }
    var record = Database.loadSpawners().getFirst();
    Database.retainRecord(record);
    Database.saveSpawners(List.of());
    assertTrue(Files.exists(file));
    Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("r-x------"));
    try {
      Database.saveSpawners(List.of(spawner));
      assertTrue(Files.exists(file));
    } finally {
      Files.setPosixFilePermissions(file.getParent(), PosixFilePermissions.fromString("rwx------"));
    }
    var folder = temp.resolve("data/entrances");
    Files.createDirectories(folder);
    try (var holo = mockConstruction(Hologram.class)) {
      var entrance = new Entrance(new Location(world, 0, 64, 0));
      var ef = folder.resolve("entrance_preserve_0_64_0.json");
      Files.writeString(ef, "{broken");
      var gson = new Gson();
      Database.loadEntrances(plugin, gson);
      Database.saveEntrances(plugin, gson, List.of());
      assertEquals("{broken", Files.readString(ef));
      Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-x------"));
      try {
        Database.saveEntrances(plugin, gson, List.of(entrance));
        assertEquals("{broken", Files.readString(ef));
      } finally {
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
      }
      Database.saveEntrances(plugin, gson, List.of(entrance));
      assertTrue(Files.readString(ef).contains("entrance"));
    }
  }

  @Test
  void uninitializedReadAndFailedOrphanDeletionKeepData() throws Exception {
    var read = Database.class.getDeclaredMethod("readAll", java.io.File.class, Class.class);
    read.setAccessible(true);
    assertEquals(List.of(), read.invoke(null, null, Database.SpawnerRecord.class));
    Database.init(plugin);
    var folder = temp.resolve("data/spawners");
    var file = folder.resolve("orphan.json");
    Files.writeString(file, "{}");
    Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-x------"));
    try {
      Database.saveSpawners(List.of());
      assertEquals("{}", Files.readString(file));
    } finally {
      Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
    }
  }
}
