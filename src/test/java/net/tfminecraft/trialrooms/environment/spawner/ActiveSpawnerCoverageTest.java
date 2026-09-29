package net.tfminecraft.trialrooms.environment.spawner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.bukkit.*;
import io.lumine.mythic.core.mobs.ActiveMob;
import java.util.*;
import java.util.function.Supplier;
import net.tfminecraft.trialrooms.*;
import net.tfminecraft.trialrooms.cache.Cache;
import net.tfminecraft.trialrooms.environment.chest.*;
import net.tfminecraft.trialrooms.environment.door.*;
import net.tfminecraft.trialrooms.manager.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockito.*;

class ActiveSpawnerCoverageTest extends TrialTestSupport {
  MockedConstruction<Hologram> holograms;
  MockedConstruction<KeyService> keys;
  MockedConstruction<SpawnerParticles> particles;
  MockedStatic<ChestManager> chests;
  MockedStatic<PlayerManager> players;
  MockedStatic<SpawnPlanner> planner;
  List<Supplier<?>> suppliers = new ArrayList<>();
  double oldHealth;
  World world;
  Location loc;
  ActiveSpawner s;
  PlayerManager playerManager;
  ChestManager chestManager;

  @BeforeEach
  void setupSpawner() {
    oldHealth = Cache.healthPerLevel;
    world = mock(World.class);
    loc = new Location(world, 0, 64, 0);
    holograms =
        mockConstruction(
            Hologram.class,
            (m, c) -> {
              suppliers.add((Supplier<?>) c.arguments().get(0));
              suppliers.add((Supplier<?>) c.arguments().get(1));
            });
    keys = mockConstruction(KeyService.class);
    particles = mockConstruction(SpawnerParticles.class);
    chests = mockStatic(ChestManager.class);
    chestManager = mock(ChestManager.class);
    chests.when(ChestManager::get).thenReturn(chestManager);
    players = mockStatic(PlayerManager.class);
    playerManager = mock(PlayerManager.class);
    players.when(PlayerManager::get).thenReturn(playerManager);
    planner = mockStatic(SpawnPlanner.class);
    var y = new org.bukkit.configuration.file.YamlConfiguration();
    y.set("block", "v.spawner");
    s = spy(new ActiveSpawner(loc, new Spawner("test", y)));
    doReturn(null).when(s).getMythicMob();
  }

  @AfterEach
  void cleanupSpawner() {
    Cache.healthPerLevel = oldHealth;
    planner.close();
    players.close();
    chests.close();
    particles.close();
    keys.close();
    holograms.close();
  }

  Player player(GameMode mode, double x, double y, double z) {
    var p = mock(Player.class);
    when(p.getGameMode()).thenReturn(mode);
    when(p.getWorld()).thenReturn(world);
    when(p.getLocation()).thenReturn(new Location(world, x, y, z));
    return p;
  }

  @Test
  void eligiblePlayersActivateAndLeavingTransitionsToCooldown() {
    s.onLoaded(plugin);
    s.slowTick();
    var nonplayer = mock(Entity.class);
    var creative = player(GameMode.CREATIVE, 0, 64, 0);
    var high = player(GameMode.SURVIVAL, 0, 68, 0);
    var far = player(GameMode.SURVIVAL, 6, 64, 6);
    var near = player(GameMode.ADVENTURE, 1, 64, 1);
    when(world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(List.of(nonplayer, creative, high, far));
    s.slowTick();
    assertEquals(ActiveSpawner.State.IDLE, s.getState());
    when(world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble()))
        .thenReturn(List.of(near));
    var survival = player(GameMode.SURVIVAL, 0, 64, 0);
    when(world.getPlayers()).thenReturn(List.of(creative, far, near, survival));
    s.slowTick();
    assertEquals(ActiveSpawner.State.ACTIVE, s.getState());
    verify(playerManager).ensureInside(near);
    s.slowTick();
    assertEquals(ActiveSpawner.State.ACTIVE, s.getState());
    var distant = player(GameMode.SURVIVAL, 80, 64, 0);
    when(world.getPlayers()).thenReturn(List.of(creative, distant));
    s.slowTick();
    assertEquals(ActiveSpawner.State.COOLDOWN, s.getState());
  }

  @Test
  void worldlessAndDestroyedLifecyclesAreSafe() throws Exception {
    for (Location missing : Arrays.asList(null, new Location(null, 0, 64, 0))) {
      var x =
          new ActiveSpawner(
              missing, new Spawner("x", new org.bukkit.configuration.file.YamlConfiguration()));
      x.onLoaded(plugin);
      x.tick();
      x.slowTick();
      x.spawn();
      x.destroy();
      assertTrue(x.isDestroyed());
      assertFalse((boolean) KeyServiceTest.call(x, "hasEligiblePlayerInRange", new Class[] {}));
    }
    var chest = mock(LootChest.class);
    chests.when(() -> ChestManager.get(s)).thenReturn(chest);
    s.destroy();
    chests.verify(() -> ChestManager.remove(chest));
    s.onEnemyDied();
  }

  @Test
  void spawnCallbacksTagScaleCountAndUnlockAfterFinalDeath() throws Exception {
    s.onLoaded(plugin);
    s.setAmount(3);
    var targets = List.of(loc.clone(), loc.clone().add(1, 0, 0), loc.clone().add(2, 0, 0));
    planner.when(() -> SpawnPlanner.findSpawnLocations(s, 3)).thenReturn(targets);
    var mm = mock(MythicMob.class);
    doReturn(mm).when(s).getMythicMob();
    var mob = mock(ActiveMob.class, RETURNS_DEEP_STUBS);
    var entity = mock(LivingEntity.class);
    var pdc =
        new org.bukkit.inventory.ItemStack(Material.STONE)
            .getItemMeta()
            .getPersistentDataContainer();
    when(entity.getPersistentDataContainer()).thenReturn(pdc);
    when(mob.getEntity().getBukkitEntity()).thenReturn(entity);
    when(mm.spawn(any(), eq(1d))).thenReturn(mob);
    when(entity.getHealth()).thenReturn(20d);
    var attr = mock(org.bukkit.attribute.AttributeInstance.class);
    when(entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)).thenReturn(attr);
    Cache.healthPerLevel = 2;
    s.setLevel(4);
    try (var adapt = mockStatic(BukkitAdapter.class)) {
      s.spawn();
      assertEquals(3, s.getPendingSpawns());
      assertTrue(
          ((String) KeyServiceTest.call(s, "getStatusText", new Class[] {})).contains("Spawning"));
      s.tick();
      verify(particles.constructed().getFirst()).animateOrb();
      server.getScheduler().performTicks(190);
      assertEquals(0, s.getPendingSpawns());
      assertEquals(
          4, pdc.get(new NamespacedKey(plugin, "spawnerlevel"), PersistentDataType.INTEGER));
      verify(entity, times(3)).setHealth(28d);
      s.onEnemyDied();
      s.onEnemyDied();
      assertEquals(ActiveSpawner.State.ACTIVE, s.getState());
      s.onEnemyDied();
      assertEquals(ActiveSpawner.State.UNLOCKING, s.getState());
      verify(keys.constructed().getFirst()).startUnlockSequence();
      s.onEnemyDied();
    }
  }

  @Test
  void failedMobLookupDoesNotLeaveSpawnQueuedForever() {
    s.onLoaded(plugin);
    planner.when(() -> SpawnPlanner.findSpawnLocations(s, 1)).thenReturn(List.of(loc));
    s.spawn();
    server.getScheduler().performTicks(65);
    assertEquals(0, s.getPendingSpawns());
  }

  @Test
  void oldWaveCannotSpawnAfterUnloadReloadAndNewActivation() {
    s.onLoaded(plugin);
    planner.when(() -> SpawnPlanner.findSpawnLocations(s, 1)).thenReturn(List.of(loc));
    s.spawn();
    s.onUnloaded(plugin);
    s.onLoaded(plugin);
    s.setStateDirect(ActiveSpawner.State.ACTIVE);
    clearInvocations(s);
    server.getScheduler().performTicks(65);
    verify(s, never()).getMythicMob();
  }

  @Test
  void doorsHologramSuppliersStatusesAndDurations() throws Exception {
    var first = holograms.constructed().getFirst();
    s.onLoaded(plugin);
    s.tick();
    verify(first).tick();
    var d = mock(DoorBlock.class);
    s.addDoorBlock(d);
    s.setStateDirect(ActiveSpawner.State.ACTIVE);
    s.addDoorBlock(d);
    s.setStateDirect(ActiveSpawner.State.UNLOCKING);
    s.addDoorBlock(d);
    s.setStateDirect(ActiveSpawner.State.COOLDOWN);
    when(d.isInLoadedChunk()).thenReturn(true);
    s.setStateDirect(ActiveSpawner.State.ACTIVE);
    s.setStateDirect(ActiveSpawner.State.COOLDOWN);
    s.maybeDropMobKey(loc);
    verify(keys.constructed().getFirst()).maybeDropMobKeyAt(loc);
    for (int seconds : new int[] {0, 5, 60, 65, 3600, 3605, 3660, 3665})
      assertNotNull(KeyServiceTest.call(s, "formatHMS", new Class[] {int.class}, seconds));
    for (var state : ActiveSpawner.State.values()) {
      s.setStateDirect(state);
      assertNotNull(KeyServiceTest.call(s, "getStatusText", new Class[] {}));
    }
    s.setStateDirect(ActiveSpawner.State.ACTIVE);
    for (int i = 0; i < suppliers.size(); i += 2) assertNotNull(suppliers.get(i).get());
    s.onEnemySpawned();
    assertTrue(
        ((String) KeyServiceTest.call(s, "getStatusText", new Class[] {}))
            .contains("Enemies Alive"));
  }

  @Test
  void mobNameLookupAndSilentKillUseRealTags() {
    var actual =
        new ActiveSpawner(
            loc, new Spawner("test", new org.bukkit.configuration.file.YamlConfiguration()));
    actual.setMob(null);
    assertNull(actual.getMythicMob());
    assertEquals("Unset", actual.getMobName());
    try (var mythic = mockStatic(MythicBukkit.class)) {
      var instance = mock(MythicBukkit.class, RETURNS_DEEP_STUBS);
      mythic.when(MythicBukkit::inst).thenReturn(instance);
      actual.setMob("zombie");
      when(instance.getMobManager().getMythicMob("zombie")).thenReturn(Optional.empty());
      assertEquals("Unset", actual.getMobName());
      var mm = mock(MythicMob.class);
      when(instance.getMobManager().getMythicMob("zombie")).thenReturn(Optional.of(mm));
      var display = mock(io.lumine.mythic.api.skills.placeholders.PlaceholderString.class);
      when(mm.getDisplayName()).thenReturn(display);
      assertEquals("Mob", actual.getMobName());
      when(display.isPresent()).thenReturn(true);
      when(display.get()).thenReturn("Zombie");
      assertEquals("Zombie", actual.getMobName());
    }
    var matched = mock(LivingEntity.class);
    var other = mock(LivingEntity.class);
    var missing = mock(LivingEntity.class);
    for (var e : List.of(matched, other, missing))
      when(e.getPersistentDataContainer())
          .thenReturn(
              new org.bukkit.inventory.ItemStack(Material.STONE)
                  .getItemMeta()
                  .getPersistentDataContainer());
    matched
        .getPersistentDataContainer()
        .set(new NamespacedKey(plugin, "spawnerid"), PersistentDataType.STRING, s.getUUID());
    other
        .getPersistentDataContainer()
        .set(new NamespacedKey(plugin, "spawnerid"), PersistentDataType.STRING, "other");
    when(world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble(), any()))
        .thenAnswer(
            i -> {
              java.util.function.Predicate<Entity> filter = i.getArgument(4);
              assertTrue(filter.test(matched));
              assertFalse(filter.test(mock(Entity.class)));
              return List.of(matched, other, missing);
            });
    s.onUnloaded(plugin);
    verify(matched).remove();
    verify(other, never()).remove();
  }

  @Test
  void unloadingUnlockingSpawnerRecoversIntoCooldown() {
    s.onLoaded(plugin);
    s.setStateDirect(ActiveSpawner.State.UNLOCKING);
    s.onUnloaded(plugin);
    s.onLoaded(plugin);
    assertEquals(ActiveSpawner.State.COOLDOWN, s.getState());
    assertEquals(s.getCooldownSeconds(), s.getCooldownRemaining());
  }

  @Test
  void leavingEncounterNotifiesBoundChestOfCooldown() {
    s.onLoaded(plugin);
    s.setStateDirect(ActiveSpawner.State.ACTIVE);
    s.slowTick();
    verify(chestManager).onEnterCooldown(s);
  }

  @Test
  void delayedCallbacksCancelAndMissingWorldIsAccountedFor() {
    for (int reason = 0; reason < 4; reason++) {
      s =
          new ActiveSpawner(
              loc, new Spawner("x", new org.bukkit.configuration.file.YamlConfiguration()));
      s.onLoaded(plugin);
      Location target = reason == 3 ? new Location(null, 0, 0, 0) : loc;
      planner.when(() -> SpawnPlanner.findSpawnLocations(s, 1)).thenReturn(List.of(target));
      s.spawn();
      if (reason == 0) s.onUnloaded(plugin);
      if (reason == 1) s.destroy();
      if (reason == 2) s.setStateDirect(ActiveSpawner.State.IDLE);
      server.getScheduler().performTicks(65);
    }
  }

  @Test
  void stateDoorsCountersAndChunkTransitions() throws Exception {
    var d = mock(DoorBlock.class);
    s.addDoorBlock(d);
    s.setCooldownSeconds(2);
    s.onLoaded(plugin);
    s.slowTick();
    s.setStateDirect(ActiveSpawner.State.ACTIVE);
    s.onLoaded(plugin);
    s.setStateDirect(ActiveSpawner.State.UNLOCKING);
    s.onLoaded(plugin);
    s.slowTick();
    s.tick();
    s.setStateDirect(ActiveSpawner.State.COOLDOWN);
    s.tick();
    s.setCooldownSeconds(1);
    s.slowTick();
    assertEquals(ActiveSpawner.State.IDLE, s.getState());
    s.setStateDirect(ActiveSpawner.State.COOLDOWN);
    s.setCooldownRemainingSeconds(0);
    s.slowTick();
    s.setStateDirect(ActiveSpawner.State.ACTIVE);
    var near = player(GameMode.ADVENTURE, 0, 64, 0);
    when(world.getPlayers()).thenReturn(List.of(near));
    s.slowTick();
    s.tick();
    s.onEnemySpawned();
    planner
        .when(() -> SpawnPlanner.findSpawnLocations(s, 1))
        .thenReturn(List.of(new Location(null, 0, 0, 0)));
    s.spawn();
    server.getScheduler().performTicks(65);
    assertEquals(ActiveSpawner.State.ACTIVE, s.getState());
    assertTrue(s.removeLastDoorBlock());
    s.getDoorBlocks().add(null);
    assertTrue(s.removeLastDoorBlock());
    s.addDoorBlock(d);
    s.onUnloaded(plugin);
    assertTrue(s.removeLastDoorBlock());
    s.clearDoorBlocks();
  }

  @Test
  void spawnHealthFallbacksStillCountMobs() {
    s.onLoaded(plugin);
    s.setAmount(2);
    planner.when(() -> SpawnPlanner.findSpawnLocations(s, 2)).thenReturn(List.of(loc, loc));
    var mm = mock(MythicMob.class);
    doReturn(mm).when(s).getMythicMob();
    var mob = mock(ActiveMob.class, RETURNS_DEEP_STUBS);
    var entity = mock(LivingEntity.class);
    when(entity.getPersistentDataContainer())
        .thenReturn(
            new org.bukkit.inventory.ItemStack(Material.STONE)
                .getItemMeta()
                .getPersistentDataContainer());
    when(mob.getEntity().getBukkitEntity()).thenReturn(entity);
    when(mm.spawn(any(), eq(1d))).thenReturn(mob);
    when(entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH))
        .thenReturn(null)
        .thenThrow(new IllegalStateException("unsupported"));
    try (var adapt = mockStatic(BukkitAdapter.class)) {
      s.spawn();
      server.getScheduler().performTicks(125);
      assertEquals(0, s.getPendingSpawns());
      s.onEnemyDied();
      assertEquals(ActiveSpawner.State.ACTIVE, s.getState());
      s.onEnemyDied();
      assertEquals(ActiveSpawner.State.UNLOCKING, s.getState());
    }
  }
}
