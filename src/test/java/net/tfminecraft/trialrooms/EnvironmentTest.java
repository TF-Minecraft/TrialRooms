package net.tfminecraft.trialrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.function.Supplier;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.trialrooms.environment.chest.*;
import net.tfminecraft.trialrooms.environment.door.*;
import net.tfminecraft.trialrooms.environment.entrance.*;
import net.tfminecraft.trialrooms.environment.spawner.*;
import net.tfminecraft.trialrooms.persist.Database;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;

class EnvironmentTest extends TrialTestSupport {
  @Test
  void doorsRestoreOriginalBlocksAndValidateRecords() {
    World world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    when(world.getName()).thenReturn("doors");
    Block block = mock(Block.class);
    when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
    when(world.getBlockAt(any(Location.class))).thenReturn(block);
    BlockData original = mock(BlockData.class);
    when(block.getBlockData()).thenReturn(original);
    when(original.clone()).thenReturn(original);
    var loc = new Location(world, 1.8, 64.7, 3.6);
    var door = new DoorBlock(loc, null);
    assertEquals(1, door.getLocation().getX());
    assertEquals(Material.IRON_BARS, door.getMaterial());
    assertSame(door, door.facing(null));
    assertSame(door, door.connect((BlockFace[]) null));
    door.connect(null, BlockFace.NORTH, BlockFace.UP);
    assertThrows(UnsupportedOperationException.class, () -> door.getFaces().clear());
    var bars = mock(MultipleFacing.class);
    doThrow(new IllegalArgumentException()).when(bars).setFace(BlockFace.UP, true);
    var directional = mock(Directional.class);
    doThrow(new IllegalArgumentException()).when(directional).setFacing(BlockFace.UP);
    try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
      bukkit.when(() -> Bukkit.createBlockData(Material.IRON_BARS)).thenReturn(bars);
      bukkit.when(() -> Bukkit.createBlockData(Material.IRON_DOOR)).thenReturn(directional);
      door.place(true);
      door.place(false);
      verify(block, times(1)).getBlockData();
      door.remove(true);
      verify(block).setBlockData(original, false);
      door.remove(false);
      verify(block).setType(Material.AIR, false);
      new DoorBlock(loc, Material.IRON_DOOR)
          .facing(BlockFace.UP)
          .connect(BlockFace.NORTH)
          .place(false);
      new DoorBlock(loc, Material.IRON_BARS, null, null).place(false);
      new DoorBlock(loc, Material.IRON_DOOR).place(false);
      assertFalse(door.isInLoadedChunk());
      door.enforceForState(true);
      when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
      assertTrue(door.isInLoadedChunk());
      door.enforceForState(true);
      door.enforceForState(false);
    }
    for (var invalid :
        List.of(new DoorBlock(null, null), new DoorBlock(new Location(null, 0, 0, 0), null))) {
      invalid.place(true);
      invalid.remove(true);
      assertFalse(invalid.isInLoadedChunk());
      invalid.enforceForState(false);
    }
    var real = server.addSimpleWorld("real");
    var record =
        new DoorBlock(
                new Location(real, 1, 2, 3),
                Material.IRON_BARS,
                List.of(BlockFace.WEST),
                BlockFace.EAST)
            .toRecord();
    var restored = DoorBlock.fromRecord(record);
    assertEquals(BlockFace.EAST, restored.getFacing());
    assertEquals(Set.of(BlockFace.WEST), restored.getFaces());
    assertNull(DoorBlock.fromRecord(null));
    assertNull(DoorBlock.fromRecord(new Database.DoorRecord()));
    record.loc.worldName = "missing";
    record.loc.worldUUID = null;
    assertNull(DoorBlock.fromRecord(record));
    record.loc = Database.SimpleLocation.of(new Location(real, 0, 0, 0));
    for (String name : Arrays.asList(null, " ", "invalid")) {
      record.material = name;
      record.facing = name;
      record.faces = Arrays.asList(null, " ", "invalid", "south");
      assertEquals(Material.IRON_BARS, DoorBlock.fromRecord(record).getMaterial());
      assertEquals(Set.of(BlockFace.SOUTH), DoorBlock.fromRecord(record).getFaces());
    }
    record.faces = null;
    assertTrue(DoorBlock.fromRecord(record).getFaces().isEmpty());
  }

  @Test
  void chestVisibilityPlacementFacingAndHologramLifecycle() {
    World world = mock(World.class);
    Block block = mock(Block.class);
    when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
    when(world.getBlockAt(any(Location.class))).thenReturn(block);
    var directional = mock(Directional.class);
    when(directional.getFacing()).thenReturn(BlockFace.WEST);
    when(block.getBlockData()).thenReturn(directional);
    when(block.getType()).thenReturn(Material.CHEST);
    when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
    try (var holo =
        mockConstruction(
            Hologram.class,
            (h, c) -> {
              assertEquals(
                  "§e§lRight-Click §7with a §6§lKey §7to open",
                  ((Supplier<?>) c.arguments().get(1)).get());
              ((Supplier<?>) c.arguments().getFirst()).get();
            })) {
      var spawner = mock(ActiveSpawner.class);
      var chest = new LootChest(new Location(world, 1.5, 2.5, 3.5), spawner);
      UUID id = UUID.randomUUID();
      chest.setId(id);
      assertEquals(id, chest.getId());
      assertEquals(spawner, chest.getSpawner());
      assertFalse(chest.isIndependent());
      assertFalse(chest.isHidden());
      assertTrue(chest.isInLoadedChunk());
      chest.tick();
      chest.hide(true);
      assertTrue(chest.isHidden());
      chest.tick();
      chest.hide(false);
      chest.setLocation(new Location(world, 5, 6, 7));
      chest.show(true);
      verify(directional).setFacing(BlockFace.WEST);
      assertFalse(chest.isHidden());
      when(block.getState()).thenReturn(mock(Chest.class));
      chest.show(false);
      chest.setLocation(new Location(world, 6, 6, 7));
      chest.setDesiredVisible(false);
      chest.enforceNow();
      assertTrue(chest.isHidden());
      chest.setDesiredVisible(true);
      chest.enforceNow();
      assertFalse(chest.isHidden());
      var h = holo.constructed().getFirst();
      doThrow(new IllegalStateException()).when(h).refresh(plugin);
      chest.setLocation(chest.getLocation());
      chest.spawnHologram();
      doThrow(new IllegalStateException()).when(h).remove(plugin);
      chest.removeHologram();
      when(block.getType()).thenReturn(Material.STONE);
      chest.hide(false);
      var invalid = new LootChest(null, null);
      assertTrue(invalid.isIndependent());
      invalid.show(true);
      invalid.hide(true);
      assertFalse(invalid.isInLoadedChunk());
      invalid.setLocation(new Location(null, 0, 0, 0));
      invalid.show(true);
      invalid.hide(true);
      assertFalse(invalid.isInLoadedChunk());
      when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
      assertFalse(chest.isInLoadedChunk());
    }
  }

  @Test
  void entranceMetadataHologramsAndRestore() {
    var world = server.addSimpleWorld("entrances");
    var text = new ArrayList<Supplier<?>>();
    try (var holo =
            mockConstruction(
                Hologram.class,
                (h, c) -> {
                  text.add((Supplier<?>) c.arguments().get(1));
                  ((Supplier<?>) c.arguments().get(0)).get();
                });
        var tlibs = mockStatic(TLibs.class)) {
      var api = mock(net.tfminecraft.tlibs.objects.api.ItemAPI.class, RETURNS_DEEP_STUBS);
      tlibs.when(TLibs::getItemAPI).thenReturn(api);
      var entrance = new Entrance(new Location(world, 1.7, 2.4, 3.9));
      assertNotNull(entrance.getId());
      assertEquals(1, entrance.getEntranceLoc().getX());
      assertTrue(text.getFirst().get().toString().contains("Unset"));
      assertTrue(text.get(1).get().toString().contains("leave"));
      entrance.setKeyPath(" ");
      assertTrue(text.getFirst().get().toString().contains("Unset"));
      entrance.setKeyPath(" v.key ");
      assertEquals("v.key", entrance.getKeyPath());
      when(api.getCreator().getItemFromPath("v.key")).thenReturn(null);
      assertTrue(text.getFirst().get().toString().contains("Unset"));
      when(api.getCreator().getItemFromPath("v.key")).thenReturn(new ItemStack(Material.AIR));
      assertTrue(text.getFirst().get().toString().contains("Unset"));
      var key = new ItemStack(Material.BLAZE_POWDER);
      when(api.getCreator().getItemFromPath("v.key")).thenReturn(key);
      assertTrue(text.getFirst().get().toString().contains("Blaze powder"));
      var meta = key.getItemMeta();
      meta.setLore(List.of("lore"));
      key.setItemMeta(meta);
      assertTrue(text.getFirst().get().toString().contains("Blaze powder"));
      meta.setDisplayName("Key name");
      key.setItemMeta(meta);
      assertTrue(text.getFirst().get().toString().contains("Key name"));
      when(api.getCreator().getItemFromPath("v.key")).thenThrow(new IllegalArgumentException());
      assertTrue(text.getFirst().get().toString().contains("Unset"));
      tlibs.when(TLibs::getItemAPI).thenThrow(new IllegalStateException());
      assertTrue(text.getFirst().get().toString().contains("Unset"));
      entrance.spawnHolograms(plugin);
      entrance.setExitLoc(new Location(null, 0, 0, 0));
      entrance.spawnHolograms(plugin);
      entrance.setExitLoc(new Location(world, 8.9, 9, 10));
      entrance.setDestination(new Location(world, 20, 21, 22));
      entrance.spawnHolograms(plugin);
      entrance.refreshHolograms(plugin);
      entrance.tick();
      entrance.removeHolograms(plugin);
      var restored = Entrance.fromRecord(entrance.toRecord());
      assertEquals(entrance.getDestination(), restored.getDestination());
      assertEquals(entrance.getExitLoc(), restored.getExitLoc());
      entrance.setDestination(null);
      entrance.setExitLoc(null);
      entrance.setKeyPath(null);
      assertNull(Entrance.fromRecord(entrance.toRecord()).getDestination());
      assertNull(Entrance.fromRecord(null));
      assertNull(Entrance.fromRecord(new Database.EntranceRecord()));
      var record = entrance.toRecord();
      record.entrance.worldUUID = null;
      record.entrance.worldName = "absent";
      assertNull(Entrance.fromRecord(record));
      assertNull(Entrance.normalizeBlock(null));
      for (var h : holo.constructed()) {
        doThrow(new IllegalStateException()).when(h).spawn(plugin);
        doThrow(new IllegalStateException()).when(h).refresh(plugin);
        doThrow(new IllegalStateException()).when(h).remove(plugin);
      }
      entrance.spawnHolograms(plugin);
      entrance.refreshHolograms(plugin);
      entrance.removeHolograms(plugin);
      entrance.setExitLoc(new Location(world, 0, 0, 0));
      entrance.spawnHolograms(plugin);
      entrance.refreshHolograms(plugin);
    }
  }

  public static class ModernCreator extends net.tfminecraft.tlibs.objects.api.subapi.ItemCreator {
    public ModernCreator() {
      super(null);
    }

    public Object getItemStackFromPath(String path) {
      return path.equals("modern") ? new ItemStack(Material.DIAMOND) : "invalid";
    }
  }

  @Test
  void entranceSupportsModernCreatorCompatibility() {
    var texts = new ArrayList<Supplier<?>>();
    try (var holo =
            mockConstruction(
                Hologram.class, (h, c) -> texts.add((Supplier<?>) c.arguments().get(1)));
        var tl = mockStatic(TLibs.class)) {
      var api = mock(net.tfminecraft.tlibs.objects.api.ItemAPI.class);
      tl.when(TLibs::getItemAPI).thenReturn(api);
      var modern = mock(ModernCreator.class, CALLS_REAL_METHODS);
      when(api.getCreator()).thenReturn(modern);
      var entrance = new Entrance(null);
      entrance.setKeyPath("modern");
      assertTrue(texts.getFirst().get().toString().contains("Diamond"));
      entrance.setKeyPath("bad");
      assertTrue(texts.getFirst().get().toString().contains("Unset"));
    }
  }
}
