package net.tfminecraft.trialrooms.environment.spawner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.trialrooms.TrialTestSupport;
import net.tfminecraft.trialrooms.environment.chest.LootChest;
import net.tfminecraft.trialrooms.environment.door.DoorBlock;
import net.tfminecraft.trialrooms.manager.ChestManager;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;

class SpawnerTest extends TrialTestSupport {
  Spawner definition() {
    var y = new YamlConfiguration();
    y.set("block", "v.spawner");
    return new Spawner("crypt", y);
  }

  @Test
  void zeroRequestedSpawnsProducesNoLocations() {
    var world = server.addSimpleWorld("floor");
    for (int x = -1; x <= 1; x++)
      for (int z = -1; z <= 1; z++) world.getBlockAt(x, 63, z).setType(Material.STONE);
    var s = mock(ActiveSpawner.class);
    when(s.getLoc()).thenReturn(new Location(world, 0, 64, 0));
    when(s.getSpawnRadius()).thenReturn(0);
    assertTrue(SpawnPlanner.findSpawnLocations(s, 0).isEmpty());
  }

  @Test
  void cooldownAtZeroReturnsToIdle() {
    try (var holo = mockConstruction(Hologram.class);
        var chests = mockStatic(ChestManager.class)) {
      chests.when(ChestManager::get).thenReturn(mock(ChestManager.class));
      var world = server.addSimpleWorld("dungeon");
      var s = new ActiveSpawner(new Location(world, 0, 64, 0), definition());
      s.onLoaded(plugin);
      s.restoreState(ActiveSpawner.State.COOLDOWN, 0);
      s.slowTick();
      assertEquals(ActiveSpawner.State.IDLE, s.getState());
    }
  }

  @Test
  void boundChestVisibilityFollowsDesiredState() {
    try (var holo = mockConstruction(Hologram.class)) {
      var s = mock(ActiveSpawner.class);
      var chest = new LootChest(null, s);
      chest.setDesiredVisible(false);
      assertFalse(chest.getDesiredVisible());
      chest.setDesiredVisible(true);
      assertTrue(chest.getDesiredVisible());
      var independent = new LootChest(null, null);
      independent.setDesiredVisible(false);
      assertTrue(independent.getDesiredVisible());
    }
  }

  @Test
  void boundChestLocationIsItsOwnBlock() {
    try (var holo = mockConstruction(Hologram.class);
        var chests = mockStatic(ChestManager.class)) {
      var world = server.addSimpleWorld("dungeon");
      var s = new ActiveSpawner(new Location(world, 0, 64, 0), definition());
      assertNull(s.getChestLocation());
      var chest = mock(LootChest.class);
      var loc = new Location(world, 5, 64, 5);
      when(chest.getLocation()).thenReturn(loc);
      chests.when(() -> ChestManager.get(s)).thenReturn(chest);
      assertEquals(loc, s.getChestLocation());
    }
  }

  @Test
  void settingsDoorsAndLifecycleExposeCorrectState() {
    try (var holo = mockConstruction(Hologram.class);
        var chests = mockStatic(ChestManager.class)) {
      var cm = mock(ChestManager.class);
      chests.when(ChestManager::get).thenReturn(cm);
      var world = server.addSimpleWorld("dungeon");
      var loc = new Location(world, 0, 64, 0);
      var s = new ActiveSpawner(loc, definition());
      assertEquals("crypt", s.getId());
      assertEquals("v.spawner", s.getBlock());
      assertNotNull(s.getUUID());
      s.setUUID("saved");
      assertEquals("saved", s.getUUID());
      assertSame(loc, s.getLoc());
      s.setMob("zombie");
      assertEquals("zombie", s.getMob());
      s.setLevel(40);
      assertEquals(40, s.getLevel());
      assertTrue(s.getLevelFormatted().contains("40"));
      s.setLevel(-1);
      assertTrue(s.getLevelFormatted().contains("1"));
      s.setAmount(3);
      assertEquals(3, s.getAmount());
      s.setSpawnRadius(2);
      assertEquals(2, s.getSpawnRadius());
      s.setActivateRadius(4);
      assertEquals(4, s.getActivateRadius());
      s.setLootTable("loot");
      assertEquals("loot", s.getLootTable());
      s.setMobLootTable("mobloot");
      assertEquals("mobloot", s.getMobLootTable());
      assertFalse(s.isDestroyed());
      assertFalse(s.isLoaded());
      assertEquals(0, s.getPendingSpawns());
      s.tick();
      s.slowTick();
      s.spawn();
      s.setBoundChest(loc);
      verify(cm).registerBound(s, loc);
      assertFalse(s.hasChestBound());
      s.addDoorBlock(null);
      assertFalse(s.removeLastDoorBlock());
      var door = mock(DoorBlock.class);
      s.addDoorBlock(door);
      assertEquals(1, s.getDoorBlocksCount());
      assertEquals(List.of(door), s.getDoorBlocks());
      assertTrue(s.removeLastDoorBlock());
      s.addDoorBlock(door);
      s.clearDoorBlocks();
      assertEquals(0, s.getDoorBlocksCount());
      s.addDoorBlock(door);
      s.onLoaded(plugin);
      verify(door).enforceForState(false);
      s.setStateDirect(null);
      when(door.isInLoadedChunk()).thenReturn(true);
      s.setStateDirect(ActiveSpawner.State.ACTIVE);
      verify(door).place(true);
      s.onUnloaded(plugin);
      assertFalse(s.isLoaded());
      assertEquals(ActiveSpawner.State.COOLDOWN, s.getState());
      s.setCooldownSeconds(2);
      assertEquals(2, s.getCooldownSeconds());
      assertEquals(2, s.getCooldownRemaining());
      s.onLoaded(plugin);
      s.slowTick();
      assertEquals(1, s.getCooldownRemaining());
      s.slowTick();
      assertEquals(ActiveSpawner.State.IDLE, s.getState());
      s.setCooldownRemainingSeconds(-2);
      assertEquals(0, s.getCooldownRemaining());
      s.setStateDirect(ActiveSpawner.State.UNLOCKING);
      s.slowTick();
      s.tick();
      s.beginCooldownFromUnlock();
      verify(door).remove(true);
      s.restoreState(ActiveSpawner.State.UNLOCKING, 3);
      assertEquals(ActiveSpawner.State.COOLDOWN, s.getState());
      s.restoreState(null, 0);
      assertEquals(ActiveSpawner.State.IDLE, s.getState());
      s.addDoorBlock(door);
      s.removeLastDoorBlock();
      s.clearDoorBlocks();
      verify(door, atLeastOnce()).remove(false);
      s.onUnloaded(plugin);
      s.destroy();
      assertTrue(s.isDestroyed());
      s.onEnemyDied();
    }
  }

  @Test
  void plannerRespectsSpaceAndFloorAndParticleEffectsHandleMissingWorlds() {
    var s = mock(ActiveSpawner.class);
    assertTrue(SpawnPlanner.findSpawnLocations(s, 1).isEmpty());
    when(s.getLoc()).thenReturn(new Location(null, 0, 64, 0));
    assertTrue(SpawnPlanner.findSpawnLocations(s, 1).isEmpty());
    var world = server.addSimpleWorld("floor");
    when(s.getLoc()).thenReturn(new Location(world, 0, 64, 0));
    assertTrue(SpawnPlanner.findSpawnLocations(s, 1).isEmpty());
    for (int x = -1; x <= 1; x++)
      for (int z = -1; z <= 1; z++) world.getBlockAt(x, 63, z).setType(Material.STONE);
    assertEquals(1, SpawnPlanner.findSpawnLocations(s, 1).size());
    when(s.getSpawnRadius()).thenReturn(1);
    assertEquals(1, SpawnPlanner.findSpawnLocations(s, 4).size());
    world.getBlockAt(0, 64, 0).setType(Material.STONE);
    assertTrue(SpawnPlanner.findSpawnLocations(s, 4).isEmpty());
    var effect = new SpawnerParticles(s);
    when(s.getLoc()).thenReturn(null);
    effect.animateIdleActiveRing();
    when(s.getLoc()).thenReturn(new Location(null, 0, 64, 0));
    effect.animateIdleActiveRing();
    effect.animateOrb();
    effect.traceFromOrbTo(new Location(null, 0, 0, 0));
    World mockWorld = mock(World.class);
    when(s.getLoc()).thenReturn(new Location(mockWorld, 0, 64, 0));
    effect.traceFromOrbTo(new Location(null, 0, 0, 0));
    effect.traceFromOrbTo(new Location(world, 0, 0, 0));
    effect.traceFromOrbTo(new Location(mockWorld, 0, 64, 0));
    for (int i = 0; i < 60; i++) {
      effect.animateIdleActiveRing();
      effect.animateOrb();
    }
    when(s.getState()).thenReturn(ActiveSpawner.State.ACTIVE);
    effect.animateIdleActiveRing();
    verify(mockWorld, atLeastOnce())
        .spawnParticle(
            eq(Particle.FLAME),
            anyDouble(),
            anyDouble(),
            anyDouble(),
            eq(1),
            eq(0.0),
            eq(0.0),
            eq(0.0),
            eq(0.0));
  }
}
