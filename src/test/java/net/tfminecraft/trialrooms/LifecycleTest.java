package net.tfminecraft.trialrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.trialrooms.loader.*;
import net.tfminecraft.trialrooms.manager.*;
import net.tfminecraft.trialrooms.persist.Database;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class LifecycleTest extends TrialTestSupport {
  @Test
  void pluginInitializesHandlersLoadersPersistenceAndShutdown() {
    try (var spawners = mockConstruction(SpawnerManager.class);
        var config = mockConstruction(ConfigLoader.class);
        var sl = mockConstruction(SpawnerLoader.class);
        var tl = mockConstruction(TableLoader.class);
        var db = mockStatic(Database.class);
        var chests = mockStatic(ChestManager.class);
        var entrances = mockStatic(EntranceManager.class)) {
      var cm = mock(ChestManager.class);
      var em = mock(EntranceManager.class);
      chests.when(ChestManager::get).thenReturn(cm);
      entrances.when(EntranceManager::get).thenReturn(em);
      singleton.when(TrialRooms::getInstance).thenCallRealMethod();
      var loaded = MockBukkit.load(TrialRooms.class);
      assertSame(loaded, TrialRooms.getInstance());
      assertSame(spawners.constructed().getFirst(), loaded.getSpawnerManager());
      verify(cm).hydrateFrom(List.of());
      verify(em).loadAllFromDisk();
      loaded.createConfigs();
      loaded.loadConfigs();
      loaded.onDisable();
      verify(em).saveAllNow();
      verify(cm).removeAllHolograms();
      verify(loaded.getSpawnerManager()).removeAllHolograms();
      server.getPluginManager().disablePlugin(loaded);
    }
  }

  @Test
  void disableAfterStartupFailureDoesNotThrow() {
    var partial = mock(TrialRooms.class, CALLS_REAL_METHODS);
    var logger = plugin.getLogger();
    when(partial.getLogger()).thenReturn(logger);
    try (var db = mockStatic(Database.class);
        var cm = mockStatic(ChestManager.class);
        var em = mockStatic(EntranceManager.class)) {
      cm.when(ChestManager::get).thenReturn(mock(ChestManager.class));
      em.when(EntranceManager::get).thenReturn(mock(EntranceManager.class));
      assertDoesNotThrow(partial::onDisable);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"entrances", "spawners", "chests"})
  void incompleteHydrationPreservesSavedFilesAndStillCleansDisplays(String stage) throws Exception {
    var paths = new java.util.ArrayList<java.nio.file.Path>();
    try (var spawners =
            mockConstruction(
                SpawnerManager.class,
                (mock, context) -> {
                  if (stage.equals("spawners"))
                    doThrow(new IllegalStateException("hydrate failure"))
                        .when(mock)
                        .hydrateFrom(anyList());
                });
        var config = mockConstruction(ConfigLoader.class);
        var sl = mockConstruction(SpawnerLoader.class);
        var tl = mockConstruction(TableLoader.class);
        var chests = mockStatic(ChestManager.class);
        var entrances = mockStatic(EntranceManager.class)) {
      var cm = mock(ChestManager.class);
      var em = mock(EntranceManager.class);
      chests.when(ChestManager::get).thenReturn(cm);
      entrances.when(EntranceManager::get).thenReturn(em);
      singleton.when(TrialRooms::getInstance).thenCallRealMethod();
      doAnswer(
              invocation -> {
                var root = TrialRooms.getInstance().getDataFolder().toPath();
                for (String kind : List.of("spawners", "chests")) {
                  var path = root.resolve("data/" + kind + "/saved.json");
                  java.nio.file.Files.writeString(path, "{\"uuid\":\"preserved\"}");
                  paths.add(path);
                }
                if (stage.equals("entrances"))
                  throw new IllegalStateException("entrance hydration failure");
                return null;
              })
          .when(em)
          .loadAllFromDisk();
      if (stage.equals("chests"))
        doThrow(new IllegalStateException("chest hydration failure"))
            .when(cm)
            .hydrateFrom(anyList());
      // Keep the actual database save/prune behavior while loading empty DTO lists.
      try (var db = mockStatic(Database.class, CALLS_REAL_METHODS)) {
        db.when(Database::loadSpawners).thenReturn(List.of());
        db.when(Database::loadChests).thenReturn(List.of());
        assertThrows(RuntimeException.class, () -> MockBukkit.load(TrialRooms.class));
        var partial = TrialRooms.getInstance();
        assertNotNull(partial.getSpawnerManager());
        assertDoesNotThrow(partial::onDisable);
        for (var path : paths)
          assertEquals("{\"uuid\":\"preserved\"}", java.nio.file.Files.readString(path));
        verify(em, never()).saveAllNow();
        verify(cm, atLeastOnce()).removeAllHolograms();
        verify(partial.getSpawnerManager(), atLeastOnce()).removeAllHolograms();
      }
    }
  }
}
