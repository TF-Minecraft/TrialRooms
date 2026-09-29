package net.tfminecraft.trialrooms;

import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;
import org.mockito.MockedStatic;

public abstract class TrialTestSupport {
  @TempDir protected Path temp;
  protected ServerMock server;
  protected TrialRooms plugin;
  MockedStatic<TrialRooms> singleton;

  @BeforeEach
  void start() {
    server = MockBukkit.mock();
    plugin = mock(TrialRooms.class);
    when(plugin.getName()).thenReturn("TrialRooms");
    when(plugin.namespace()).thenReturn("trialrooms");
    when(plugin.getDataFolder()).thenReturn(temp.toFile());
    when(plugin.getLogger()).thenReturn(Logger.getLogger("TrialTest"));
    when(plugin.isEnabled()).thenReturn(true);
    when(plugin.getServer()).thenReturn(server);
    singleton = mockStatic(TrialRooms.class, CALLS_REAL_METHODS);
    singleton.when(TrialRooms::getInstance).thenReturn(plugin);
  }

  @AfterEach
  void stop() {
    try {
      MockBukkit.unmock();
    } finally {
      singleton.close();
    }
  }
}
