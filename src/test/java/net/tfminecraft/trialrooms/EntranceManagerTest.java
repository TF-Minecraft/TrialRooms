package net.tfminecraft.trialrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.*;
import java.util.*;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.trialrooms.cache.Cache;
import net.tfminecraft.trialrooms.environment.entrance.*;
import net.tfminecraft.trialrooms.environment.spawner.*;
import net.tfminecraft.trialrooms.manager.*;
import net.tfminecraft.trialrooms.persist.Database;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;

class EntranceManagerTest extends TrialTestSupport {
  EntranceManager manager;

  @BeforeEach
  void reset() throws Exception {
    var f = EntranceManager.class.getDeclaredField("INSTANCE");
    f.setAccessible(true);
    f.set(null, null);
    manager = EntranceManager.get();
    assertSame(manager, EntranceManager.get());
    Database.init(plugin);
    Cache.conversions.clear();
  }

  PlayerInteractEvent interact(Player p, Block b, EquipmentSlot hand) {
    return new PlayerInteractEvent(
        p,
        Action.RIGHT_CLICK_BLOCK,
        p.getInventory().getItemInMainHand(),
        b,
        org.bukkit.block.BlockFace.UP,
        hand);
  }

  @Test
  void cancelledBreakKeepsEntranceAndOffhandDoesNotSpendKey() {
    var p = server.addPlayer();
    var world = server.addSimpleWorld("regression");
    var loc = new Location(world, 0, 64, 0);
    p.teleport(loc);
    try (var holo = mockConstruction(Hologram.class);
        var sm = mockStatic(SpawnerManager.class);
        var tl = mockStatic(TLibs.class);
        var pm = mockStatic(PlayerManager.class)) {
      var api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
      tl.when(TLibs::getItemAPI).thenReturn(api);
      when(api.getChecker().checkItemWithPath(any(), eq("v.key"))).thenReturn(true);
      pm.when(PlayerManager::get).thenReturn(mock(PlayerManager.class));
      var entrance = manager.registerOrGet(loc);
      entrance.setDestination(loc.clone().add(10, 0, 0));
      entrance.setKeyPath("v.key");
      p.getInventory().setItemInMainHand(new ItemStack(Material.BLAZE_POWDER, 2));
      manager.onUseEntranceWithKey(interact(p, loc.getBlock(), EquipmentSlot.OFF_HAND));
      assertEquals(2, p.getInventory().getItemInMainHand().getAmount());
      var event = new BlockBreakEvent(loc.getBlock(), p);
      event.setCancelled(true);
      manager.onBreakEntranceBits(event);
      assertSame(entrance, manager.get(loc));
    }
  }

  @Test
  void registrationEditingAndBreakingParts() throws Exception {
    var p = server.addPlayer();
    var world = server.addSimpleWorld("entrances");
    var loc = new Location(world, 0, 64, 0);
    loc.getBlock().setType(Material.LODESTONE);
    try (var holo = mockConstruction(Hologram.class);
        var sm = mockStatic(SpawnerManager.class)) {
      manager.onRightClickLodestoneToEdit(
          new PlayerInteractEvent(
              p, Action.LEFT_CLICK_AIR, null, null, org.bukkit.block.BlockFace.UP));
      manager.onRightClickLodestoneToEdit(interact(p, null, EquipmentSlot.HAND));
      manager.onRightClickLodestoneToEdit(interact(p, loc.getBlock(), EquipmentSlot.OFF_HAND));
      manager.onRightClickLodestoneToEdit(interact(p, loc.getBlock(), EquipmentSlot.HAND));
      assertTrue(manager.all().isEmpty());
      sm.when(() -> SpawnerManager.hasEditWand(p)).thenReturn(true);
      manager.onRightClickLodestoneToEdit(
          interact(p, world.getBlockAt(10, 64, 0), EquipmentSlot.HAND));
      manager.onRightClickLodestoneToEdit(interact(p, loc.getBlock(), EquipmentSlot.HAND));
      var entrance = manager.get(loc);
      assertNotNull(entrance);
      assertSame(entrance, manager.registerOrGet(loc));
      assertThrows(UnsupportedOperationException.class, () -> manager.all().clear());
      manager.onRightClickLodestoneToEdit(interact(p, loc.getBlock(), EquipmentSlot.HAND));
      var near = world.getBlockAt(2, 64, 0);
      near.setType(Material.LODESTONE);
      manager.onRightClickLodestoneToEdit(interact(p, near, EquipmentSlot.HAND));
      assertNull(manager.get(near.getLocation()));
      entrance.setExitLoc(new Location(world, 20, 64, 0));
      near = world.getBlockAt(22, 64, 0);
      near.setType(Material.LODESTONE);
      manager.onRightClickLodestoneToEdit(interact(p, near, EquipmentSlot.HAND));
      assertNull(manager.get(near.getLocation()));
      entrance.setDestination(new Location(world, 40, 64, 0));
      near = world.getBlockAt(42, 64, 0);
      near.setType(Material.LODESTONE);
      manager.onRightClickLodestoneToEdit(interact(p, near, EquipmentSlot.HAND));
      assertNull(manager.get(near.getLocation()));
      var foreign = server.addSimpleWorld("other").getBlockAt(0, 64, 0);
      foreign.setType(Material.LODESTONE);
      manager.onRightClickLodestoneToEdit(interact(p, foreign, EquipmentSlot.HAND));
      assertEquals(2, manager.all().size());
      manager.onBreakEntranceBits(new BlockBreakEvent(world.getBlockAt(100, 64, 0), p));
      manager.onBreakEntranceBits(new BlockBreakEvent(entrance.getExitLoc().getBlock(), p));
      assertNull(entrance.getExitLoc());
      manager.onBreakEntranceBits(new BlockBreakEvent(entrance.getDestination().getBlock(), p));
      assertNull(entrance.getDestination());
      manager.edited(entrance);
      manager.saveAllNow();
      manager.removeAllHolograms();
      manager.onBreakEntranceBits(new BlockBreakEvent(loc.getBlock(), p));
      assertNull(manager.get(loc));
      manager.remove(foreign.getLocation());
      manager.remove(foreign.getLocation());
      assertTrue(manager.all().isEmpty());
    }
  }

  @Test
  void keyUseValidatesBeforeConsumingAndMarksDungeon() {
    var p = server.addPlayer();
    var world = server.addSimpleWorld("use");
    var loc = new Location(world, 0, 64, 0);
    p.teleport(loc);
    try (var holo = mockConstruction(Hologram.class);
        var sm = mockStatic(SpawnerManager.class);
        var tl = mockStatic(TLibs.class);
        var pm = mockStatic(PlayerManager.class)) {
      var tracker = mock(PlayerManager.class);
      pm.when(PlayerManager::get).thenReturn(tracker);
      var api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
      tl.when(TLibs::getItemAPI).thenReturn(api);
      sm.when(() -> SpawnerManager.hasEditWand(p)).thenReturn(true);
      manager.onUseEntranceWithKey(interact(p, loc.getBlock(), EquipmentSlot.HAND));
      sm.when(() -> SpawnerManager.hasEditWand(p)).thenReturn(false);
      manager.onUseEntranceWithKey(
          new PlayerInteractEvent(
              p, Action.LEFT_CLICK_BLOCK, null, loc.getBlock(), org.bukkit.block.BlockFace.UP));
      manager.onUseEntranceWithKey(interact(p, loc.getBlock(), EquipmentSlot.HAND));
      var entrance = manager.registerOrGet(loc);
      manager.onUseEntranceWithKey(interact(p, loc.getBlock(), EquipmentSlot.HAND));
      entrance.setKeyPath(" ");
      manager.onUseEntranceWithKey(interact(p, loc.getBlock(), EquipmentSlot.HAND));
      entrance.setKeyPath("v.key");
      manager.onUseEntranceWithKey(interact(p, loc.getBlock(), EquipmentSlot.HAND));
      when(api.getChecker().checkItemWithPath(any(), eq("v.key")))
          .thenThrow(new IllegalArgumentException());
      manager.onUseEntranceWithKey(interact(p, loc.getBlock(), EquipmentSlot.HAND));
      when(api.getChecker().checkItemWithPath(any(), eq("v.key"))).thenReturn(true);
      manager.onUseEntranceWithKey(interact(p, loc.getBlock(), EquipmentSlot.HAND));
      entrance.setDestination(new Location(null, 0, 0, 0));
      manager.onUseEntranceWithKey(interact(p, loc.getBlock(), EquipmentSlot.HAND));
      var dest = loc.clone().add(10, 0, 0);
      entrance.setDestination(dest);
      p.getInventory().setItemInMainHand(new ItemStack(Material.BLAZE_POWDER, 2));
      var event = interact(p, loc.getBlock(), EquipmentSlot.HAND);
      manager.onUseEntranceWithKey(event);
      assertTrue(event.isCancelled());
      assertEquals(dest.clone().add(.5, 1, .5), p.getLocation());
      assertEquals(1, p.getInventory().getItemInMainHand().getAmount());
      verify(tracker).markEntered(p, entrance.getId());
      p.teleport(loc);
      manager.onUseEntranceWithKey(
          new PlayerInteractEvent(
              p,
              Action.RIGHT_CLICK_AIR,
              p.getInventory().getItemInMainHand(),
              null,
              org.bukkit.block.BlockFace.UP,
              EquipmentSlot.HAND));
      assertTrue(p.getInventory().getItemInMainHand().getType().isAir());
    }
  }

  @Test
  void pickupAddsConversionLoreOnlyOnce() {
    var p = server.addPlayer();
    var entity = mock(Item.class);
    var event = new EntityPickupItemEvent(p, entity, 0);
    manager.onPickupConvertibleItem(new EntityPickupItemEvent(mock(LivingEntity.class), entity, 0));
    manager.onPickupConvertibleItem(event);
    when(entity.getItemStack()).thenReturn(new ItemStack(Material.AIR));
    manager.onPickupConvertibleItem(event);
    var stack = new ItemStack(Material.DIAMOND, 3);
    when(entity.getItemStack()).thenReturn(stack);
    manager.onPickupConvertibleItem(event);
    try (var cache = mockStatic(Cache.class)) {
      cache.when(() -> Cache.getConversionAmount(any())).thenReturn(1.25);
      manager.onPickupConvertibleItem(event);
      assertEquals(1, stack.getItemMeta().getLore().size());
      assertTrue(stack.getItemMeta().getLore().getFirst().contains("1.25"));
      manager.onPickupConvertibleItem(event);
      assertEquals(1, stack.getItemMeta().getLore().size());
      var second = new ItemStack(Material.GOLD_INGOT);
      var meta = second.getItemMeta();
      meta.setLore(List.of("Original"));
      second.setItemMeta(meta);
      when(entity.getItemStack()).thenReturn(second);
      cache.when(() -> Cache.getConversionAmount(any())).thenReturn(2.0);
      manager.onPickupConvertibleItem(event);
      assertEquals(3, second.getItemMeta().getLore().size());
      assertEquals("Original", second.getItemMeta().getLore().getFirst());
      var noMeta = mock(ItemStack.class);
      when(noMeta.getType()).thenReturn(Material.DIAMOND);
      when(entity.getItemStack()).thenReturn(noMeta);
      manager.onPickupConvertibleItem(event);
    }
  }

  @Test
  void diskLoadingAutosaveAndScheduledTicks() throws Exception {
    var world = server.addSimpleWorld("disk");
    world.loadChunk(0, 0);
    try (var holo = mockConstruction(Hologram.class)) {
      var entrance = manager.registerOrGet(new Location(world, 0, 64, 0));
      manager.saveAllNow();
      manager.remove(entrance.getEntranceLoc());
      manager.loadAllFromDisk();
      assertNotNull(manager.get(entrance.getEntranceLoc()));
      server.getScheduler().performTicks(201);
      manager.edited(entrance);
      server.getScheduler().performTicks(200);
      assertFalse(Database.loadEntrances(plugin, new com.google.gson.Gson()).isEmpty());
      manager.removeAllHolograms();
    }
  }

  @Test
  void exitingTeleportsAndConvertsItemsWithCooldown() throws Exception {
    World world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    when(world.getName()).thenReturn("exit");
    when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
    var p = mock(Player.class);
    var id = UUID.randomUUID();
    when(p.getUniqueId()).thenReturn(id);
    var inventory = server.addPlayer().getInventory();
    when(p.getInventory()).thenReturn(inventory);
    var exit = new Location(world, 10, 64, 0);
    when(p.getLocation()).thenReturn(exit.clone().add(.5, 0, .5));
    when(world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(List.of(mock(Entity.class), p));
    var check = EntranceManager.class.getDeclaredMethod("checkExitTeleports");
    check.setAccessible(true);
    var cooldown = EntranceManager.class.getDeclaredField("lastTeleportAt");
    cooldown.setAccessible(true);
    var last = (Map<UUID, Long>) cooldown.get(manager);
    try (var holo = mockConstruction(Hologram.class);
        var players = mockStatic(PlayerManager.class);
        var cache = mockStatic(Cache.class);
        var denar = mockStatic(net.tfminecraft.denareconomy.DenarEconomy.class)) {
      var tracker = mock(PlayerManager.class);
      players.when(PlayerManager::get).thenReturn(tracker);
      var money = mock(net.tfminecraft.denareconomy.managers.MoneyManager.class);
      denar.when(net.tfminecraft.denareconomy.DenarEconomy::getMoneyManager).thenReturn(money);
      var ent = manager.registerOrGet(new Location(world, 0, 64, 0));
      check.invoke(manager);
      ent.setExitLoc(new Location(null, 0, 0, 0));
      check.invoke(manager);
      ent.setExitLoc(exit);
      when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
      check.invoke(manager);
      when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
      when(p.getLocation()).thenReturn(exit.clone().add(4, 0, 0));
      check.invoke(manager);
      verify(p, never()).teleport(any(Location.class));
      when(p.getLocation()).thenReturn(exit.clone().add(.5, 0, .5));
      inventory.setItem(0, new ItemStack(Material.DIAMOND, 3));
      inventory.setItem(1, new ItemStack(Material.STONE));
      cache
          .when(() -> Cache.getConversionAmount(any()))
          .thenAnswer(
              a -> {
                ItemStack it = a.getArgument(0);
                return it.getType() == Material.DIAMOND ? 1.25 : 0.0;
              });
      check.invoke(manager);
      verify(money).addMoney(p, 3.75, false, true);
      verify(tracker).markExited(p);
      assertNull(inventory.getItem(0));
      assertNotNull(inventory.getItem(1));
      check.invoke(manager);
      verify(tracker, times(1)).markExited(p);
      last.put(id, 0L);
      check.invoke(manager);
      verify(tracker, times(2)).markExited(p);
      last.clear();
      cache
          .when(() -> Cache.getConversionAmount(any()))
          .thenThrow(new IllegalStateException("conversion"));
      assertDoesNotThrow(() -> check.invoke(manager));
    }
  }

  @Test
  void malformedEntranceWorldIsRetainedAcrossAutosave() throws Exception {
    var folder = temp.resolve("data/entrances");
    java.nio.file.Files.createDirectories(folder);
    var file = folder.resolve("missing.json");
    String content = "{\"entrance\":{\"world_name\":\"unloaded\",\"x\":1,\"y\":2,\"z\":3}}";
    java.nio.file.Files.writeString(file, content);
    manager.loadAllFromDisk();
    manager.saveAllNow();
    assertEquals(content, java.nio.file.Files.readString(file));
  }

  Object call(String name, Class<?>[] types, Object... args) throws Exception {
    var method = EntranceManager.class.getDeclaredMethod(name, types);
    method.setAccessible(true);
    try {
      return method.invoke(manager, args);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception ex) throw ex;
      throw e;
    }
  }

  @Test
  void helperBoundariesAndHologramFailures() throws Exception {
    var world = server.addSimpleWorld("boundary");
    var loc = new Location(world, 0, 64, 0);
    var invalid = new Location(null, 0, 0, 0);
    assertEquals(
        false,
        call("isNearExistingEntranceParts", new Class[] {Location.class, double.class}, null, 4.0));
    assertEquals(
        false,
        call(
            "isNearExistingEntranceParts",
            new Class[] {Location.class, double.class},
            invalid,
            4.0));
    assertEquals(
        false,
        call(
            "sameWorldAndWithin",
            new Class[] {Location.class, Location.class, double.class},
            loc,
            null,
            4.0));
    assertEquals(
        false,
        call(
            "sameWorldAndWithin",
            new Class[] {Location.class, Location.class, double.class},
            invalid,
            loc,
            4.0));
    assertEquals(
        false,
        call(
            "sameWorldAndWithin",
            new Class[] {Location.class, Location.class, double.class},
            loc,
            invalid,
            4.0));
    assertNull(
        call("nearestEntranceWithin", new Class[] {Location.class, double.class}, null, 4.0));
    assertNull(
        call("nearestEntranceWithin", new Class[] {Location.class, double.class}, invalid, 4.0));
    for (String name : List.of("playWarpFx", "playArriveFx")) {
      call(name, new Class[] {Location.class}, (Object) null);
      call(name, new Class[] {Location.class}, invalid);
    }
    call("drawRing", new Class[] {Location.class, Color.class}, null, Color.RED);
    call("drawRing", new Class[] {Location.class, Color.class}, invalid, Color.RED);
    var p = server.addPlayer();
    p.getInventory().setItemInOffHand(new ItemStack(Material.DIAMOND));
    call("consumeOne", new Class[] {Player.class, EquipmentSlot.class}, p, EquipmentSlot.OFF_HAND);
    assertTrue(p.getInventory().getItemInOffHand().getType().isAir());
    var mockPlayer = mock(Player.class);
    when(mockPlayer.getInventory()).thenReturn(mock(PlayerInventory.class));
    call(
        "consumeOne",
        new Class[] {Player.class, EquipmentSlot.class},
        mockPlayer,
        EquipmentSlot.HAND);
    try (var holo = mockConstruction(Hologram.class);
        var entrances =
            mockConstruction(
                Entrance.class,
                (e, c) -> {
                  when(e.getEntranceLoc()).thenReturn((Location) c.arguments().getFirst());
                  doThrow(new IllegalStateException()).when(e).removeHolograms(plugin);
                  doThrow(new IllegalStateException()).when(e).tick();
                })) {
      var entrance = manager.registerOrGet(loc);
      server.getScheduler().performTicks(2);
      manager.removeAllHolograms();
      manager.onBreakEntranceBits(new BlockBreakEvent(loc.getBlock(), p));
      assertTrue(manager.all().isEmpty());
    }
  }

  @Test
  void ioExceptionsDoNotEscapeAutosaveOrExplicitSave() {
    try (var db = mockStatic(Database.class)) {
      db.when(() -> Database.saveEntrances(eq(plugin), any(), any()))
          .thenThrow(new IllegalStateException("disk"));
      manager.loadAllFromDisk();
      manager.edited(null);
      server.getScheduler().performTicks(201);
      assertDoesNotThrow(manager::saveAllNow);
    }
  }

  @Test
  void nullRegistrationDoesNotLeaveAnInvalidEntrance() {
    try (var holo = mockConstruction(Hologram.class)) {
      assertThrows(NullPointerException.class, () -> manager.registerOrGet(null));
      assertTrue(manager.all().isEmpty());
    }
  }

  @Test
  void nearbySelectionParticlesAndUnavailableWorlds() throws Exception {
    var world = server.addSimpleWorld("nearby");
    world.loadChunk(0, 0);
    var loc = new Location(world, 0, 64, 0);
    try (var holo = mockConstruction(Hologram.class)) {
      var first = manager.registerOrGet(loc);
      first.setExitLoc(new Location(world, 8, 64, 0));
      manager.registerOrGet(loc.clone().add(1, 0, 0));
      manager.registerOrGet(new Location(server.addSimpleWorld("farworld"), 0, 64, 0));
      manager.registerOrGet(new Location(null, 0, 0, 0));
      assertNotNull(
          call(
              "nearestEntranceWithin",
              new Class[] {Location.class, double.class},
              new Location(world, 1, 64, .5),
              2.0));
      assertNull(
          call(
              "nearestEntranceWithin",
              new Class[] {Location.class, double.class},
              new Location(world, 100, 64, 0),
              2.0));
      assertEquals(
          false,
          call(
              "isNearExistingEntranceParts",
              new Class[] {Location.class, double.class},
              new Location(world, 100, 64, 0),
              2.0));
      assertEquals(
          false,
          call(
              "sameWorldAndWithin",
              new Class[] {Location.class, Location.class, double.class},
              null,
              loc,
              2.0));
      call("tickParticles", new Class[] {});
      manager.loadAllFromDisk();
      manager.saveAllNow();
      server.getScheduler().performTicks(401);
    }
  }

  @Test
  void breakingExitAndDestinationSurvivesHologramProviderFailure() {
    var world = server.addSimpleWorld("failure");
    var loc = new Location(world, 0, 64, 0);
    var p = server.addPlayer();
    try (var construct =
        mockConstruction(
            Entrance.class,
            (e, c) -> when(e.getEntranceLoc()).thenReturn((Location) c.arguments().getFirst()))) {
      var entrance = manager.registerOrGet(loc);
      when(entrance.getExitLoc()).thenReturn(loc.clone().add(1, 0, 0));
      when(entrance.getDestination()).thenReturn(loc.clone().add(2, 0, 0));
      doThrow(new IllegalStateException()).when(entrance).spawnHolograms(plugin);
      manager.onBreakEntranceBits(new BlockBreakEvent(entrance.getExitLoc().getBlock(), p));
      verify(entrance).setExitLoc(null);
      manager.onBreakEntranceBits(new BlockBreakEvent(entrance.getDestination().getBlock(), p));
      verify(entrance).setDestination(null);
    }
  }

  @Test
  void breakingEntrancePartsIsIncludedInAutosave() {
    var world = server.addSimpleWorld("autosaveBreak");
    var loc = new Location(world, 0, 64, 0);
    var p = server.addPlayer();
    try (var holo = mockConstruction(Hologram.class)) {
      manager.loadAllFromDisk();
      var entrance = manager.registerOrGet(loc);
      entrance.setExitLoc(loc.clone().add(1, 0, 0));
      entrance.setDestination(loc.clone().add(2, 0, 0));
      manager.saveAllNow();
      manager.onBreakEntranceBits(new BlockBreakEvent(entrance.getExitLoc().getBlock(), p));
      server.getScheduler().performTicks(201);
      assertNull(Database.loadEntrances(plugin, new com.google.gson.Gson()).getFirst().exit);
      manager.onBreakEntranceBits(new BlockBreakEvent(entrance.getDestination().getBlock(), p));
      server.getScheduler().performTicks(200);
      assertNull(Database.loadEntrances(plugin, new com.google.gson.Gson()).getFirst().destination);
      manager.onBreakEntranceBits(new BlockBreakEvent(loc.getBlock(), p));
      server.getScheduler().performTicks(200);
      assertTrue(Database.loadEntrances(plugin, new com.google.gson.Gson()).isEmpty());
    }
  }

  @Test
  void exitsSkipUnloadedEntranceWorldAndIgnoreAirStacks() throws Exception {
    World world = mock(World.class);
    when(world.getUID()).thenReturn(UUID.randomUUID());
    when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
    var p = mock(Player.class);
    when(p.getUniqueId()).thenReturn(UUID.randomUUID());
    var exit = new Location(world, 10, 64, 0);
    when(p.getLocation()).thenReturn(exit.clone().add(.5, 0, .5));
    when(world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(List.of(p));
    var inv = mock(PlayerInventory.class);
    when(inv.getContents()).thenReturn(new ItemStack[] {new ItemStack(Material.AIR)});
    when(p.getInventory()).thenReturn(inv);
    try (var holo = mockConstruction(Hologram.class);
        var players = mockStatic(PlayerManager.class)) {
      players.when(PlayerManager::get).thenReturn(mock(PlayerManager.class));
      var entrance = manager.registerOrGet(new Location(null, 0, 64, 0));
      entrance.setExitLoc(exit);
      call("checkExitTeleports", new Class[] {});
      verify(p, never()).teleport(any(Location.class));
      entrance.getEntranceLoc().setWorld(world);
      call("checkExitTeleports", new Class[] {});
      verify(p).teleport(any(Location.class));
      verify(inv, never()).setItem(anyInt(), any());
    }
  }

  @Test
  void diskRestoreLeavesDistantChunkUnloaded() {
    var world = server.addSimpleWorld("unloaded");
    var loc = new Location(world, 512, 64, 512);
    try (var holo = mockConstruction(Hologram.class)) {
      manager.registerOrGet(loc);
      manager.saveAllNow();
      manager.remove(loc);
      assertFalse(world.isChunkLoaded(32, 32));
      manager.loadAllFromDisk();
      assertNotNull(manager.get(loc));
      assertFalse(world.isChunkLoaded(32, 32));
    }
  }
}
