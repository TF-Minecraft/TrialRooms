package net.tfminecraft.trialrooms.manager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.lumine.mythic.bukkit.events.MythicDamageEvent;
import java.util.*;
import net.tfminecraft.trialrooms.*;
import net.tfminecraft.trialrooms.cache.Cache;
import net.tfminecraft.trialrooms.environment.door.*;
import net.tfminecraft.trialrooms.environment.spawner.*;
import net.tfminecraft.trialrooms.loader.*;
import net.tfminecraft.trialrooms.persist.Database;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.*;
import org.mockito.*;

class SpawnerManagerCoverageTest extends TrialTestSupport {
  SpawnerManager man;
  MockedStatic<PlayerManager> players;
  PlayerManager pm;

  @BeforeEach
  void setupManager() {
    man = new SpawnerManager(plugin);
    man.getSpawners().clear();
    players = mockStatic(PlayerManager.class);
    pm = mock(PlayerManager.class, RETURNS_DEEP_STUBS);
    players.when(PlayerManager::get).thenReturn(pm);
  }

  @AfterEach
  void cleanupManager() {
    man.getSpawners().clear();
    players.close();
  }

  MythicDamageEvent damage(Entity victim, Entity caster) {
    var e = mock(MythicDamageEvent.class, RETURNS_DEEP_STUBS);
    when(e.getTarget().getBukkitEntity()).thenReturn(victim);
    when(e.getCaster().getEntity().getBukkitEntity()).thenReturn(caster);
    return e;
  }

  @Test
  void mythicPlayerAttacksRespectDungeonFriendlyFire() {
    var a = mock(Player.class);
    var b = mock(Player.class);
    when(pm.get(a).isInDungeon()).thenReturn(true);
    when(pm.get(b).isInDungeon()).thenReturn(true);
    when(a.getPersistentDataContainer())
        .thenReturn(
            new org.bukkit.inventory.ItemStack(Material.STONE)
                .getItemMeta()
                .getPersistentDataContainer());
    var e = damage(b, a);
    man.onMobDealsDamage(e);
    verify(e).setCancelled(true);
    verify(e, never()).setDamage(anyDouble());
  }

  @Test
  void invalidDoorDoesNotAbortRestorationAndIsRetained() {
    var world = server.addSimpleWorld("world");
    var record = new Database.SpawnerRecord();
    record.loc = Database.SimpleLocation.of(new Location(world, 1, 2, 3));
    record.id = "test";
    record.uuid = "uuid";
    record.state = "IDLE";
    record.doors.add(new Database.DoorRecord());
    var definition = new Spawner("test", new org.bukkit.configuration.file.YamlConfiguration());
    try (var loader = mockStatic(SpawnerLoader.class);
        var db = mockStatic(Database.class);
        var constructors = mockConstruction(ActiveSpawner.class)) {
      loader.when(() -> SpawnerLoader.getByString("test")).thenReturn(definition);
      assertDoesNotThrow(() -> man.hydrateFrom(List.of(record)));
      db.verify(() -> Database.retainRecord(record));
      assertTrue(man.getSpawners().isEmpty());
    }
  }

  @Test
  void damageScalingAndDeathRoutingUseRealPersistentTags() {
    var victim = mock(Player.class);
    var mob = mock(LivingEntity.class);
    var other = mock(Entity.class);
    var pdc =
        new org.bukkit.inventory.ItemStack(Material.STONE)
            .getItemMeta()
            .getPersistentDataContainer();
    when(mob.getPersistentDataContainer()).thenReturn(pdc);
    man.onMobDealsDamage(damage(other, mob));
    man.onMobDealsDamage(damage(victim, other));
    man.onMobDealsDamage(damage(victim, mob));
    var level = new NamespacedKey(plugin, "spawnerlevel");
    pdc.set(level, PersistentDataType.INTEGER, 0);
    man.onMobDealsDamage(damage(victim, mob));
    pdc.set(level, PersistentDataType.INTEGER, 3);
    double old = Cache.damagePerLevel;
    Cache.damagePerLevel = 2;
    try {
      var e = damage(victim, mob);
      when(e.getDamage()).thenReturn(7d);
      man.onMobDealsDamage(e);
      verify(e).setDamage(13);
      verify(pm).ensureInside(victim);
      when(pm.isInside(victim)).thenReturn(true);
      man.onMobDealsDamage(e);
      verify(pm, times(1)).ensureInside(victim);
    } finally {
      Cache.damagePerLevel = old;
    }
    var player = mock(Player.class);
    when(player.getPersistentDataContainer()).thenReturn(pdc);
    man.onMobDealsDamage(damage(victim, player));
    when(pm.get(victim).isInDungeon()).thenReturn(true);
    man.onMobDealsDamage(damage(victim, player));
    var death = mock(org.bukkit.event.entity.EntityDeathEvent.class);
    when(death.getEntity()).thenReturn(mob);
    man.onMobDeath(death);
    man.onSpawnerMobDeath(death);
    var id = new NamespacedKey(plugin, "spawnerid");
    pdc.set(id, PersistentDataType.STRING, "missing");
    man.onMobDeath(death);
    man.onSpawnerMobDeath(death);
    var active = mock(ActiveSpawner.class);
    when(active.getUUID()).thenReturn("found");
    var loc = new Location(server.addSimpleWorld("world"), 1, 2, 3);
    when(mob.getLocation()).thenReturn(loc);
    man.getSpawners().put(loc, active);
    pdc.set(id, PersistentDataType.STRING, "FOUND");
    man.onMobDeath(death);
    man.onSpawnerMobDeath(death);
    verify(active).onEnemyDied();
    verify(active).maybeDropMobKey(loc);
    assertNull(SpawnerManager.get("other"));
  }

  @Test
  void regularFriendlyFireAndScheduledLifecycle() {
    var a = mock(Player.class);
    var b = mock(Player.class);
    var other = mock(Entity.class);
    for (Entity[] pair : new Entity[][] {{other, b}, {a, other}, {a, b}}) {
      var e = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
      when(e.getDamager()).thenReturn(pair[0]);
      when(e.getEntity()).thenReturn(pair[1]);
      man.noFriendlyFire(e);
    }
    when(pm.get(a).isInDungeon()).thenReturn(true);
    var e = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
    when(e.getDamager()).thenReturn(a);
    when(e.getEntity()).thenReturn(b);
    man.noFriendlyFire(e);
    when(pm.get(b).isInDungeon()).thenReturn(true);
    man.noFriendlyFire(e);
    verify(e).setCancelled(true);
    var idle = mock(ActiveSpawner.class);
    var cooling = mock(ActiveSpawner.class);
    when(idle.getState()).thenReturn(ActiveSpawner.State.IDLE);
    when(cooling.getState()).thenReturn(ActiveSpawner.State.COOLDOWN);
    man.getSpawners().put(new Location(null, 1, 2, 3), idle);
    man.getSpawners().put(new Location(null, 4, 5, 6), cooling);
    assertEquals(2, man.allActiveSpawners().size());
    SpawnerManager.resetCooldowns();
    verify(cooling).setCooldownRemainingSeconds(1);
    man.start();
    server.getScheduler().performTicks(21);
    verify(idle, atLeastOnce()).slowTick();
    verify(cooling, atLeastOnce()).tick();
    man.removeAllHolograms();
    verify(idle).removeHolograms(plugin);
  }

  @Test
  void chunkEventsOnlyReachMatchingLocations() {
    var world = server.addSimpleWorld("world");
    var other = server.addSimpleWorld("other");
    var chunk = world.getChunkAt(1, 2);
    var match = mock(ActiveSpawner.class);
    man.getSpawners().put(new Location(world, 16, 0, 32), match);
    for (Location loc :
        Arrays.asList(
            null,
            new Location(null, 0, 0, 0),
            new Location(other, 16, 0, 32),
            new Location(world, 0, 0, 32),
            new Location(world, 16, 0, 0))) man.getSpawners().put(loc, mock(ActiveSpawner.class));
    man.onChunkLoad(new org.bukkit.event.world.ChunkLoadEvent(chunk, false));
    man.onChunkUnload(new org.bukkit.event.world.ChunkUnloadEvent(chunk));
    verify(match).onLoaded(plugin);
    verify(match).onUnloaded(plugin);
    for (var sp : man.getSpawners().values()) if (sp != match) verifyNoInteractions(sp);
  }

  @Test
  void hydrationRestoresAllStatesAndRetainsRejectedRecords() {
    var world = mock(World.class);
    when(world.getName()).thenReturn("loaded");
    when(world.isChunkLoaded(0, 0)).thenReturn(true);
    var unloaded = mock(World.class);
    when(unloaded.getName()).thenReturn("unloaded");
    var def = new Spawner("def", new org.bukkit.configuration.file.YamlConfiguration());
    try (var loader = mockStatic(SpawnerLoader.class);
        var db = mockStatic(Database.class);
        var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
        var doors = mockStatic(DoorBlock.class);
        var constructors = mockConstruction(ActiveSpawner.class)) {
      bukkit.when(() -> Bukkit.getWorld("loaded")).thenReturn(world);
      bukkit.when(() -> Bukkit.getWorld("unloaded")).thenReturn(unloaded);
      loader.when(() -> SpawnerLoader.getByString("def")).thenReturn(def);
      var records = new ArrayList<Database.SpawnerRecord>();
      for (String state : Arrays.asList("IDLE", "ACTIVE", "UNLOCKING", "COOLDOWN", "bad", null)) {
        var r = new Database.SpawnerRecord();
        r.id = "def";
        r.uuid = "uuid";
        r.loc = Database.SimpleLocation.of(new Location(world, records.size(), 2, 3));
        r.state = state;
        r.cooldownSeconds = 12;
        r.cooldownRemaining = 4;
        r.amount = 3;
        r.level = 2;
        r.mob = "mob";
        r.lootTable = "loot";
        r.mobLootTable = "mobloot";
        records.add(r);
      }
      var dr = new Database.DoorRecord();
      var door = mock(DoorBlock.class);
      when(door.isInLoadedChunk()).thenReturn(true);
      doors.when(() -> DoorBlock.fromRecord(dr)).thenReturn(door);
      records.getFirst().doors.add(dr);
      var dr2 = new Database.DoorRecord();
      var door2 = mock(DoorBlock.class);
      doors.when(() -> DoorBlock.fromRecord(dr2)).thenReturn(door2);
      records.getFirst().doors.add(dr2);
      var r = new Database.SpawnerRecord();
      r.id = "def";
      r.loc = Database.SimpleLocation.of(new Location(unloaded, 0, 2, 3));
      r.state = "IDLE";
      records.add(r);
      man.hydrateFrom(records);
      assertEquals(7, man.allActiveSpawners().size());
      verify(constructors.constructed().get(0)).restoreState(ActiveSpawner.State.IDLE, 4);
      verify(constructors.constructed().get(1)).restoreState(ActiveSpawner.State.COOLDOWN, 12);
      verify(constructors.constructed().get(2)).restoreState(ActiveSpawner.State.COOLDOWN, 12);
      verify(constructors.constructed().get(3)).restoreState(ActiveSpawner.State.COOLDOWN, 4);
      verify(constructors.constructed().get(4)).restoreState(ActiveSpawner.State.IDLE, 0);
      verify(door).enforceForState(false);
      server.getScheduler().performOneTick();
      verify(constructors.constructed().getFirst()).onLoaded(plugin);
      verify(constructors.constructed().getLast(), never()).onLoaded(plugin);
      var noLoc = new Database.SpawnerRecord();
      var noWorld = new Database.SpawnerRecord();
      noWorld.loc = Database.SimpleLocation.of(new Location(world, 1, 2, 3));
      noWorld.loc.worldName = "missing";
      var noDef = new Database.SpawnerRecord();
      noDef.loc = Database.SimpleLocation.of(new Location(world, 1, 2, 3));
      var noDoors = new Database.SpawnerRecord();
      noDoors.loc = noDef.loc;
      noDoors.id = "def";
      noDoors.doors = null;
      for (var bad : List.of(noLoc, noWorld, noDef, noDoors)) {
        man.hydrateFrom(List.of(bad));
        db.verify(() -> Database.retainRecord(bad));
      }
      man.getSpawners().put(new Location(null, 0, 0, 0), mock(ActiveSpawner.class));
      server.getScheduler().performOneTick();
    }
  }

  @Test
  void creationEditingAndProtectedBreaks() throws Exception {
    var world = mock(World.class);
    var loc = new Location(world, 1, 2, 3);
    var block = mock(org.bukkit.block.Block.class);
    when(block.getLocation()).thenReturn(loc);
    var player = server.addPlayer();
    var definition = new Spawner("test", new org.bukkit.configuration.file.YamlConfiguration());
    var interact = mock(org.bukkit.event.player.PlayerInteractEvent.class);
    when(interact.getPlayer()).thenReturn(player);
    when(interact.getHand()).thenReturn(org.bukkit.inventory.EquipmentSlot.HAND);
    var api = mock(net.tfminecraft.tlibs.objects.api.ItemAPI.class, RETURNS_DEEP_STUBS);
    try (var tlibs = mockStatic(net.tfminecraft.tlibs.TLibs.class);
        var loader = mockStatic(SpawnerLoader.class);
        var editor =
            mockStatic(
                net.tfminecraft.trialrooms.environment.spawner.ui.ActiveSpawnerEditor.class);
        var constructors = mockConstruction(ActiveSpawner.class)) {
      tlibs.when(net.tfminecraft.tlibs.TLibs::getItemAPI).thenReturn(api);
      when(interact.getAction()).thenReturn(org.bukkit.event.block.Action.LEFT_CLICK_BLOCK);
      man.createSpawner(interact);
      when(interact.getAction()).thenReturn(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK);
      man.createSpawner(interact);
      when(api.getChecker().checkItemWithPath(any(), any())).thenReturn(true);
      man.createSpawner(interact);
      when(interact.getClickedBlock()).thenReturn(block);
      man.createSpawner(interact);
      loader.when(() -> SpawnerLoader.getByBlock(block)).thenReturn(definition);
      var pendingClass =
          Class.forName(
              "net.tfminecraft.trialrooms.environment.spawner.ui.ActiveSpawnerEditor$PendingEdit");
      var constructor = pendingClass.getDeclaredConstructors()[0];
      constructor.setAccessible(true);
      var pending =
          constructor.newInstance(
              mock(ActiveSpawner.class),
              net.tfminecraft.trialrooms.environment.spawner.ui.ActiveSpawnerEditor.Field.values()[
                  0]);
      ((Map) net.tfminecraft.trialrooms.environment.spawner.ui.ActiveSpawnerEditor.PENDING)
          .put(player.getUniqueId(), pending);
      man.createSpawner(interact);
      assertTrue(man.getSpawners().isEmpty());
      net.tfminecraft.trialrooms.environment.spawner.ui.ActiveSpawnerEditor.PENDING.clear();
      when(interact.getHand()).thenReturn(org.bukkit.inventory.EquipmentSlot.OFF_HAND);
      man.createSpawner(interact);
      assertTrue(man.getSpawners().isEmpty());
      when(interact.getHand()).thenReturn(org.bukkit.inventory.EquipmentSlot.HAND);
      man.createSpawner(interact);
      var first = constructors.constructed().getFirst();
      assertSame(first, man.getSpawners().get(loc));
      verify(first, never()).onLoaded(plugin);
      when(world.isChunkLoaded(0, 0)).thenReturn(true);
      man.createSpawner(interact);
      verify(first).onLoaded(plugin);
      man.getSpawners().clear();
      man.createSpawner(interact);
      var second = constructors.constructed().getLast();
      verify(second).spawnHolograms(plugin);
      verify(second).onLoaded(plugin);
      var broken = new org.bukkit.event.block.BlockBreakEvent(block, player);
      when(api.getChecker().checkItemWithPath(any(), any())).thenReturn(false);
      man.onSpawnerBreak(broken);
      assertTrue(broken.isCancelled());
      assertSame(second, man.getSpawners().get(loc));
      when(api.getChecker().checkItemWithPath(any(), any())).thenReturn(true);
      man.onSpawnerBreak(broken);
      verify(second).destroy();
      assertTrue(man.getSpawners().isEmpty());
      man.onSpawnerBreak(broken);
      man.getSpawners().put(loc, null);
      man.onSpawnerBreak(broken);
    }
  }
}
