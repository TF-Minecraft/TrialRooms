package net.tfminecraft.trialrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.*;
import java.util.*;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.trialrooms.environment.chest.*;
import net.tfminecraft.trialrooms.environment.spawner.*;
import net.tfminecraft.trialrooms.environment.spawner.ui.ActiveSpawnerEditor;
import net.tfminecraft.trialrooms.manager.*;
import net.tfminecraft.trialrooms.persist.Database;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.*;

class ChestManagerTest extends TrialTestSupport {
  ChestManager manager;

  @BeforeEach
  void reset() throws Exception {
    var f = ChestManager.class.getDeclaredField("INSTANCE");
    f.setAccessible(true);
    f.set(null, null);
    var m = ChestManager.class.getDeclaredField("chests");
    m.setAccessible(true);
    ((Map<?, ?>) m.get(null)).clear();
    ActiveSpawnerEditor.PENDING.clear();
    manager = ChestManager.get();
    assertSame(manager, ChestManager.get());
  }

  Object call(String method, Class<?>[] types, Object... args) throws Exception {
    var m = ChestManager.class.getDeclaredMethod(method, types);
    m.setAccessible(true);
    try {
      return m.invoke(manager, args);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception ex) throw ex;
      throw e;
    }
  }

  PlayerInteractEvent interact(Player p, Block b, EquipmentSlot hand, ItemStack item) {
    return new PlayerInteractEvent(
        p, Action.RIGHT_CLICK_BLOCK, item, b, org.bukkit.block.BlockFace.UP, hand);
  }

  ItemStack key(String pool, int min, int max) {
    var key = new ItemStack(Material.TRIPWIRE_HOOK);
    var meta = key.getItemMeta();
    var pdc = meta.getPersistentDataContainer();
    pdc.set(new NamespacedKey(plugin, "keyRarity"), PersistentDataType.STRING, "COMMON");
    pdc.set(new NamespacedKey(plugin, "encodedLoot"), PersistentDataType.STRING, pool);
    pdc.set(new NamespacedKey(plugin, "keyRollsMin"), PersistentDataType.INTEGER, min);
    pdc.set(new NamespacedKey(plugin, "keyRollsMax"), PersistentDataType.INTEGER, max);
    key.setItemMeta(meta);
    return key;
  }

  @Test
  void registryHooksChunkLoadsAndRemoval() {
    var world = server.addSimpleWorld("chests");
    var loc = new Location(world, 0, 64, 0);
    var spawner = mock(ActiveSpawner.class);
    when(spawner.getState()).thenReturn(ActiveSpawner.State.IDLE);
    try (var construct =
        mockConstruction(
            LootChest.class,
            (c, context) -> {
              when(c.getLocation()).thenReturn((Location) context.arguments().get(0));
              var bound = (ActiveSpawner) context.arguments().get(1);
              when(c.getSpawner()).thenReturn(bound);
              when(c.isIndependent()).thenReturn(bound == null);
              when(c.isInLoadedChunk()).thenReturn(true);
            })) {
      assertNull(manager.registerBound(null, loc));
      assertNull(manager.registerBound(spawner, null));
      var independent = manager.registerIndependent(loc);
      verify(independent).show(false);
      var bound = manager.registerBound(spawner, loc.clone().add(1, 0, 0));
      verify(bound).setDesiredVisible(false);
      assertSame(bound, ChestManager.get(spawner));
      assertNull(ChestManager.get(mock(ActiveSpawner.class)));
      assertEquals(2, ChestManager.allChests().size());
      assertThrows(UnsupportedOperationException.class, () -> manager.all().clear());
      manager.onEnterCooldown(null);
      manager.onLeaveCooldown(null);
      manager.onSpawnerLoaded(null);
      when(bound.getDesiredVisible()).thenReturn(true);
      manager.onEnterCooldown(spawner);
      server.getScheduler().performTicks(21);
      verify(bound).show(true);
      manager.onLeaveCooldown(spawner);
      verify(bound).hide(true);
      manager.onSpawnerLoaded(spawner);
      when(spawner.getState()).thenReturn(ActiveSpawner.State.COOLDOWN);
      manager.onSpawnerLoaded(spawner);
      when(bound.isInLoadedChunk()).thenReturn(false);
      manager.onEnterCooldown(spawner);
      manager.onLeaveCooldown(spawner);
      manager.onSpawnerLoaded(spawner);
      server.getScheduler().performTicks(21);
      manager.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(0, 0), false));
      manager.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(1, 0), false));
      manager.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(0, 1), false));
      manager.onChunkLoad(
          new ChunkLoadEvent(server.addSimpleWorld("other").getChunkAt(0, 0), false));
      when(bound.getLocation()).thenReturn(null);
      manager.onChunkLoad(new ChunkLoadEvent(world.getChunkAt(0, 0), false));
      manager.removeAllHolograms();
      doThrow(new IllegalStateException()).when(bound).removeHologram();
      manager.removeAllHolograms();
      manager.remove(loc);
      assertEquals(1, manager.all().size());
      manager.remove((Location) null);
      manager.remove(new Location(null, 0, 0, 0));
    }
  }

  @Test
  void editorRegistrationAndProtectedChestBreak() {
    var p = server.addPlayer();
    var world = server.addSimpleWorld("edit");
    var loc = new Location(world, 0, 64, 0);
    loc.getBlock().setType(Material.CHEST);
    try (var holo = mockConstruction(Hologram.class);
        var sm = mockStatic(SpawnerManager.class)) {
      manager.registerIndependentChest(
          new PlayerInteractEvent(
              p, Action.RIGHT_CLICK_AIR, null, null, org.bukkit.block.BlockFace.UP));
      manager.registerIndependentChest(
          interact(p, world.getBlockAt(10, 64, 0), EquipmentSlot.HAND, null));
      manager.registerIndependentChest(interact(p, loc.getBlock(), EquipmentSlot.HAND, null));
      assertTrue(manager.all().isEmpty());
      sm.when(() -> SpawnerManager.hasEditWand(p)).thenReturn(true);
      manager.registerIndependentChest(interact(p, loc.getBlock(), EquipmentSlot.HAND, null));
      assertEquals(1, manager.all().size());
      var event = interact(p, loc.getBlock(), EquipmentSlot.HAND, null);
      manager.registerIndependentChest(event);
      assertTrue(event.isCancelled());
      manager.chestBreak(new BlockBreakEvent(world.getBlockAt(10, 64, 0), p));
      sm.when(() -> SpawnerManager.hasEditWand(p)).thenReturn(false);
      var broken = new BlockBreakEvent(loc.getBlock(), p);
      manager.chestBreak(broken);
      assertTrue(broken.isCancelled());
      assertEquals(1, manager.all().size());
      sm.when(() -> SpawnerManager.hasEditWand(p)).thenReturn(true);
      manager.chestBreak(new BlockBreakEvent(loc.getBlock(), p));
      assertTrue(manager.all().isEmpty());
      assertEquals(Material.AIR, loc.getBlock().getType());
    }
  }

  @Test
  void openingConsumesValidKeyAndDropsDecodedLoot() throws Exception {
    var p = server.addPlayer();
    var world = server.addSimpleWorld("open");
    var loc = new Location(world, 0, 64, 0);
    world.loadChunk(0, 0);
    try (var construct =
            mockConstruction(
                LootChest.class,
                (c, context) -> {
                  when(c.getLocation()).thenReturn((Location) context.arguments().get(0));
                });
        var tl = mockStatic(TLibs.class)) {
      var api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
      tl.when(TLibs::getItemAPI).thenReturn(api);
      when(api.getCreator().getItemFromPath("v.diamond"))
          .thenReturn(new ItemStack(Material.DIAMOND));
      var chest = manager.registerIndependent(loc);
      manager.onRightClickChest(
          new PlayerInteractEvent(
              p, Action.RIGHT_CLICK_AIR, null, null, org.bukkit.block.BlockFace.UP));
      manager.onRightClickChest(interact(p, null, EquipmentSlot.HAND, null));
      manager.onRightClickChest(interact(p, loc.getBlock(), EquipmentSlot.OFF_HAND, null));
      manager.onRightClickChest(interact(p, world.getBlockAt(20, 64, 0), EquipmentSlot.HAND, null));
      when(chest.isHidden()).thenReturn(true);
      manager.onRightClickChest(interact(p, loc.getBlock(), EquipmentSlot.HAND, null));
      when(chest.isHidden()).thenReturn(false);
      manager.onRightClickChest(interact(p, loc.getBlock(), EquipmentSlot.HAND, null));
      manager.onRightClickChest(
          interact(p, loc.getBlock(), EquipmentSlot.HAND, new ItemStack(Material.AIR)));
      manager.onRightClickChest(
          interact(p, loc.getBlock(), EquipmentSlot.HAND, new ItemStack(Material.STICK)));
      var inert = key("bad;v.x|a|1|2;v.x|1|1|0", 1, 1);
      manager.onRightClickChest(interact(p, loc.getBlock(), EquipmentSlot.HAND, inert));
      verify(chest, never()).hide(true);
      var key = key("v.diamond|2|2|1", 1, 1);
      key.setAmount(2);
      p.getInventory().setItemInMainHand(key);
      var event = interact(p, loc.getBlock(), EquipmentSlot.HAND, key);
      manager.onRightClickChest(event);
      assertTrue(event.isCancelled());
      assertEquals(1, p.getInventory().getItemInMainHand().getAmount());
      verify(chest).hide(true);
      server.getScheduler().performTicks(11);
      var dropped = world.getEntitiesByClass(Item.class);
      assertEquals(1, dropped.size());
      assertEquals(2, dropped.iterator().next().getItemStack().getAmount());
      assertTrue(dropped.iterator().next().isCustomNameVisible());
      server.getScheduler().performTicks(300);
      verify(chest).show(true);
      var key2 = key("v.diamond|0|0|1", 1, 1);
      p.getInventory().setItemInMainHand(key2);
      manager.onRightClickChest(interact(p, loc.getBlock(), EquipmentSlot.HAND, key2));
      assertTrue(p.getInventory().getItemInMainHand().getType().isAir());
      server.getScheduler().performTicks(11);
    }
  }

  @Test
  void keyValidationAmountsNamesAndMaterialFailures() throws Exception {
    assertNull(call("decodeKey", new Class[] {ItemStack.class}, new ItemStack(Material.AIR)));
    assertNull(call("decodeKey", new Class[] {ItemStack.class}, new ItemStack(Material.STICK)));
    assertNull(call("decodeKey", new Class[] {ItemStack.class}, key("", 1, 1)));
    assertNull(
        call("decodeKey", new Class[] {ItemStack.class}, key("bad;v.x|a|1|1;v.x|1|1|0", 1, 1)));
    assertNotNull(call("decodeKey", new Class[] {ItemStack.class}, key("v.x|-1|1|1", 2, 1)));
    assertNotNull(call("decodeKey", new Class[] {ItemStack.class}, key("v.x|1|1|1", -1, -1)));
    assertEquals(2, call("rollAmount", new Class[] {int.class, int.class}, 2, 2));
    assertEquals(0, call("rollAmount", new Class[] {int.class, int.class}, -1, -2));
    assertEquals(2, call("rollAmount", new Class[] {int.class, int.class}, 2, 2));
    int n = (int) call("rollAmount", new Class[] {int.class, int.class}, 4, 2);
    assertTrue(n >= 2 && n <= 4);
    assertEquals("Item", call("displayNameOf", new Class[] {ItemStack.class}, (Object) null));
    assertEquals(
        "Diamond",
        call("displayNameOf", new Class[] {ItemStack.class}, new ItemStack(Material.DIAMOND)));
    var named = new ItemStack(Material.DIAMOND);
    var meta = named.getItemMeta();
    meta.setDisplayName("Named");
    named.setItemMeta(meta);
    assertEquals("Named", call("displayNameOf", new Class[] {ItemStack.class}, named));
    try (var tl = mockStatic(TLibs.class)) {
      var api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
      tl.when(TLibs::getItemAPI).thenReturn(api);
      when(api.getCreator().getItemFromPath("missing")).thenReturn(null);
      assertNull(call("itemFromPath", new Class[] {String.class, int.class}, "missing", 1));
      when(api.getCreator().getItemFromPath("air")).thenReturn(new ItemStack(Material.AIR));
      assertNull(call("itemFromPath", new Class[] {String.class, int.class}, "air", 1));
      when(api.getCreator().getItemFromPath("bad")).thenThrow(new IllegalArgumentException());
      assertNull(call("itemFromPath", new Class[] {String.class, int.class}, "bad", 1));
      when(api.getCreator().getItemFromPath("v.x")).thenReturn(new ItemStack(Material.DIAMOND));
      assertEquals(
          64,
          ((ItemStack) call("itemFromPath", new Class[] {String.class, int.class}, "v.x", 100))
              .getAmount());
    }
  }

  @Test
  void inclusiveMaximumAmountsDoNotOverflow() throws Exception {
    int amount =
        (int)
            call(
                "rollAmount",
                new Class[] {int.class, int.class},
                Integer.MAX_VALUE - 1,
                Integer.MAX_VALUE);
    assertTrue(amount >= Integer.MAX_VALUE - 1);
  }

  @Test
  void cancelledBreakAndStaleCooldownTaskCannotResurrectChest() {
    var p = server.addPlayer();
    var world = server.addSimpleWorld("stale");
    var loc = new Location(world, 0, 64, 0);
    var spawner = mock(ActiveSpawner.class);
    try (var construct =
            mockConstruction(
                LootChest.class,
                (c, context) -> {
                  when(c.getLocation()).thenReturn((Location) context.arguments().get(0));
                  when(c.getSpawner()).thenReturn(spawner);
                  when(c.isInLoadedChunk()).thenReturn(true);
                });
        var sm = mockStatic(SpawnerManager.class)) {
      sm.when(() -> SpawnerManager.hasEditWand(p)).thenReturn(true);
      var chest = manager.registerBound(spawner, loc);
      var broken = new BlockBreakEvent(loc.getBlock(), p);
      broken.setCancelled(true);
      manager.chestBreak(broken);
      assertEquals(1, manager.all().size());
      manager.onEnterCooldown(spawner);
      manager.onLeaveCooldown(spawner);
      server.getScheduler().performTicks(21);
      verify(chest, never()).show(true);
    }
  }

  @Test
  void weightedDrawsAreBoundedDistinctAndReachBothEnds() throws Exception {
    Object decoded =
        call("decodeKey", new Class[] {ItemStack.class}, key("v.a|1|1|1;v.b|1|1|1", 1, 2));
    var accessor = decoded.getClass().getDeclaredMethod("pool");
    accessor.setAccessible(true);
    var pool = (List<?>) accessor.invoke(decoded);
    var rng = mock(java.util.concurrent.ThreadLocalRandom.class);
    try (var random = mockStatic(java.util.concurrent.ThreadLocalRandom.class)) {
      random.when(java.util.concurrent.ThreadLocalRandom::current).thenReturn(rng);
      when(rng.nextLong(anyLong())).thenReturn(0L);
      when(rng.nextLong(anyLong(), anyLong())).thenReturn(1L);
      var first =
          (List<?>)
              call(
                  "pickWeightedWithoutReplacement",
                  new Class[] {List.class, int.class, int.class},
                  pool,
                  1,
                  1);
      assertSame(pool.getFirst(), first.getFirst());
      when(rng.nextLong(2L)).thenReturn(1L);
      var last =
          (List<?>)
              call(
                  "pickWeightedWithoutReplacement",
                  new Class[] {List.class, int.class, int.class},
                  pool,
                  1,
                  2);
      assertSame(pool.getLast(), last.getFirst());
      assertEquals(
          2,
          ((List<?>)
                  call(
                      "pickWeightedWithoutReplacement",
                      new Class[] {List.class, int.class, int.class},
                      pool,
                      5,
                      5))
              .size());
      assertTrue(
          ((List<?>)
                  call(
                      "pickWeightedWithoutReplacement",
                      new Class[] {List.class, int.class, int.class},
                      pool,
                      0,
                      0))
              .isEmpty());
      assertEquals(
          1,
          ((List<?>)
                  call(
                      "pickWeightedWithoutReplacement",
                      new Class[] {List.class, int.class, int.class},
                      pool,
                      0,
                      Integer.MAX_VALUE))
              .size());
    }
  }

  @Test
  void trailTerminationAndCompatibilityFailures() throws Exception {
    call("startCritTrail", new Class[] {Entity.class, int.class}, null, 1);
    var invalid = mock(Entity.class);
    call("startCritTrail", new Class[] {Entity.class, int.class}, invalid, 1);
    var dead = mock(Entity.class);
    when(dead.isValid()).thenReturn(true);
    when(dead.isDead()).thenReturn(true);
    call("startCritTrail", new Class[] {Entity.class, int.class}, dead, 1);
    server.getScheduler().performTicks(3);
    var player = mock(Player.class);
    var inv = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(inv);
    call("consumeOne", new Class[] {Player.class, EquipmentSlot.class}, player, EquipmentSlot.HAND);
    var noMeta = mock(ItemStack.class);
    when(noMeta.getType()).thenReturn(Material.STONE);
    assertEquals("Stone", call("displayNameOf", new Class[] {ItemStack.class}, noMeta));
    var item = key("v.x|1|1|1", 1, 1);
    var meta = item.getItemMeta();
    meta.getPersistentDataContainer().remove(new NamespacedKey(plugin, "encodedLoot"));
    item.setItemMeta(meta);
    assertEquals(false, call("isKey", new Class[] {ItemStack.class}, item));
  }

  @Test
  void hydrationRestoresIdsBindingsAndFacingAndSkipsInvalidRecords() {
    var world = server.addSimpleWorld("hydrate");
    var loc = new Location(world, 0, 64, 0);
    var valid = new Database.ChestRecord();
    valid.id = UUID.randomUUID().toString();
    valid.loc = Database.SimpleLocation.of(loc);
    valid.facing = "WEST";
    var spawner = mock(ActiveSpawner.class);
    when(spawner.getState()).thenReturn(ActiveSpawner.State.COOLDOWN);
    valid.spawnerUUID = "bound";
    try (var construct =
            mockConstruction(
                LootChest.class,
                (c, ctx) -> {
                  when(c.getLocation()).thenReturn((Location) ctx.arguments().get(0));
                  when(c.isInLoadedChunk()).thenReturn(ctx.getCount() == 1);
                  when(c.getFacing()).thenCallRealMethod();
                  doCallRealMethod().when(c).setFacing(any());
                });
        var sm = mockStatic(SpawnerManager.class)) {
      sm.when(() -> SpawnerManager.get("bound")).thenReturn(spawner);
      manager.hydrateFrom(List.of(valid));
      var chest = construct.constructed().getFirst();
      verify(chest).setId(UUID.fromString(valid.id));
      assertEquals(org.bukkit.block.BlockFace.WEST, chest.getFacing());
      when(spawner.getState()).thenReturn(ActiveSpawner.State.IDLE);
      manager.hydrateFrom(List.of(valid));
      valid.spawnerUUID = "missing";
      manager.hydrateFrom(List.of(valid));
      valid.spawnerUUID = null;
      valid.facing = null;
      manager.hydrateFrom(List.of(valid));
      assertEquals(org.bukkit.block.BlockFace.NORTH, construct.constructed().getLast().getFacing());
      valid.facing = "BAD";
      manager.hydrateFrom(List.of(valid));
      var invalid = new Database.ChestRecord();
      invalid.id = "bad";
      invalid.loc = Database.SimpleLocation.of(loc.clone().add(1, 0, 0));
      assertDoesNotThrow(() -> manager.hydrateFrom(List.of(invalid, valid)));
      invalid.loc = null;
      manager.hydrateFrom(List.of(invalid));
      invalid.loc = Database.SimpleLocation.of(loc);
      invalid.loc.worldUUID = null;
      invalid.loc.worldName = "absent";
      manager.hydrateFrom(List.of(invalid));
    }
  }

  @Test
  void unloadedRegistrationsStaleCallbacksAndPendingEditsAreSafe() throws Exception {
    var p = server.addPlayer();
    var world = server.addSimpleWorld("pending");
    var loc = new Location(world, 0, 64, 0);
    var spawner = mock(ActiveSpawner.class);
    when(spawner.getState()).thenReturn(ActiveSpawner.State.COOLDOWN);
    when(spawner.getLoc()).thenReturn(loc);
    when(spawner.getLootTable()).thenReturn("loot");
    try (var construct =
            mockConstruction(
                LootChest.class,
                (c, ctx) -> {
                  when(c.getLocation()).thenReturn((Location) ctx.arguments().get(0));
                  when(c.getSpawner()).thenReturn((ActiveSpawner) ctx.arguments().get(1));
                });
        var sm = mockStatic(SpawnerManager.class)) {
      manager.registerIndependent(loc);
      var bound = manager.registerBound(spawner, loc.clone().add(1, 0, 0));
      manager.onEnterCooldown(spawner);
      manager.remove(bound.getLocation());
      server.getScheduler().performTicks(21);
      verify(bound, never()).show(true);
      doThrow(new IllegalStateException()).when(construct.constructed().getFirst()).tick();
      server.getScheduler().performTicks(2);
      sm.when(() -> SpawnerManager.hasEditWand(p)).thenReturn(true);
      ActiveSpawnerEditor.openEditor(p, spawner);
      var inv = p.getOpenInventory().getTopInventory();
      var event = mock(org.bukkit.event.inventory.InventoryClickEvent.class);
      when(event.getWhoClicked()).thenReturn(p);
      when(event.getInventory()).thenReturn(inv);
      when(event.getRawSlot()).thenReturn(24);
      var item = inv.getItem(24);
      when(event.getCurrentItem()).thenReturn(item);
      new ActiveSpawnerEditor().onInventoryClick(event);
      loc = loc.clone().add(5, 0, 0);
      loc.getBlock().setType(Material.CHEST);
      manager.registerIndependentChest(interact(p, loc.getBlock(), EquipmentSlot.HAND, null));
      assertEquals(1, manager.all().size());
    }
  }

  @Test
  void boundLootMissingItemsAndWorldCannotScheduleInvalidDrops() {
    var p = server.addPlayer();
    var world = server.addSimpleWorld("boundLoot");
    var loc = new Location(world, 0, 64, 0);
    var bound = mock(ActiveSpawner.class);
    try (var construct =
            mockConstruction(
                LootChest.class,
                (c, ctx) -> {
                  when(c.getLocation()).thenReturn((Location) ctx.arguments().get(0));
                  when(c.getSpawner()).thenReturn(bound);
                });
        var tl = mockStatic(TLibs.class)) {
      var api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
      tl.when(TLibs::getItemAPI).thenReturn(api);
      when(api.getCreator().getItemFromPath("missing")).thenReturn(null);
      var chest = manager.registerBound(bound, loc);
      var item = key("missing|1|1|1", 1, 1);
      p.getInventory().setItemInMainHand(item);
      manager.onRightClickChest(interact(p, loc.getBlock(), EquipmentSlot.HAND, item));
      server.getScheduler().performTicks(120);
      verify(chest, never()).show(true);
      when(chest.getLocation()).thenReturn(new Location(null, 0, 64, 0));
      p.getInventory().setItemInMainHand(item);
      manager.onRightClickChest(interact(p, loc.getBlock(), EquipmentSlot.HAND, item));
    }
  }

  @Test
  void horizontalKickDirectionCoversBothSigns() throws Exception {
    var rng = mock(java.util.concurrent.ThreadLocalRandom.class);
    when(rng.nextDouble(.05, .12)).thenReturn(.1);
    when(rng.nextBoolean()).thenReturn(true, false);
    assertEquals(
        .1,
        call(
            "randomSigned",
            new Class[] {java.util.concurrent.ThreadLocalRandom.class, double.class, double.class},
            rng,
            .05,
            .12));
    assertEquals(
        -.1,
        call(
            "randomSigned",
            new Class[] {java.util.concurrent.ThreadLocalRandom.class, double.class, double.class},
            rng,
            .05,
            .12));
  }
}
