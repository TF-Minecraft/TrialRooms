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
}
