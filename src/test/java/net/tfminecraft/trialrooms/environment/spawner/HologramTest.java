package net.tfminecraft.trialrooms.environment.spawner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.tfminecraft.trialrooms.TrialTestSupport;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

class HologramTest extends TrialTestSupport {
  World world;
  TextDisplay display;
  PersistentDataContainer pdc;
  UUID id;
  Player player;
  MockedStatic<Bukkit> bukkit;

  @BeforeEach
  void setUpWorld() {
    world = mock(World.class);
    when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
    display = mock(TextDisplay.class);
    pdc = mock(PersistentDataContainer.class);
    id = UUID.randomUUID();
    when(display.getUniqueId()).thenReturn(id);
    when(display.getPersistentDataContainer()).thenReturn(pdc);
    when(world.spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class)))
        .thenAnswer(
            a -> {
              ((Consumer<TextDisplay>) a.getArgument(2)).accept(display);
              return display;
            });
    player = mock(Player.class);
    when(player.getWorld()).thenReturn(world);
    bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
    bukkit.when(() -> Bukkit.getEntity(id)).thenReturn(display);
    bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
  }

  @AfterEach
  void closeBukkit() {
    bukkit.close();
    // Holograms stay registered until collected; make their world look unloaded to later tests.
    reset(world);
  }

  private void spawns(int times) {
    verify(world, times(times))
        .spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class));
  }

  @Test
  void spawnCreatesOneTaggedRuntimeOnlyDisplayAndUpdatesIt() {
    var loc = new Location(world, 1, 64, 3);
    var text = new AtomicReference<String>("hello");
    when(player.getLocation()).thenReturn(loc);
    var h = new Hologram(() -> loc, text::get);
    h.setYOffset(2);
    var pos = loc.clone().add(.5, 2, .5);

    h.spawn(plugin);
    spawns(1);
    verify(world).spawn(eq(pos), eq(TextDisplay.class), any(Consumer.class));
    verify(display).setInvulnerable(true);
    verify(display).setPersistent(false);
    verify(display, never()).setPersistent(true);
    verify(display).setGravity(false);
    verify(pdc).set(Hologram.LABEL_KEY, PersistentDataType.BYTE, (byte) 1);
    verify(display).setBillboard(Display.Billboard.CENTER);
    verify(display).setText("hello");
    // Before spawning it looks for a lost display at the exact label position.
    verify(world).getNearbyEntitiesByType(TextDisplay.class, pos, LabelCleanup.SEARCH_RADIUS);

    // A second spawn, refresh and tick reuse the display.
    h.spawn(plugin);
    text.set(null);
    h.refresh(plugin);
    h.tick();
    spawns(1);
    verify(display, times(2)).teleport(pos);
    verify(display, times(2)).setText("");
    assertTrue(Hologram.ownedDisplayIds().contains(id));
    assertTrue(Hologram.labelPositions().contains(pos));

    h.remove(plugin);
    verify(display).remove();
    assertFalse(Hologram.ownedDisplayIds().contains(id));
  }

  @Test
  void displayIsOnlyCreatedWhileAPlayerIsInViewRangeAndTheChunkIsLoaded() {
    var loc = new Location(world, 0, 64, 0);
    var h = new Hologram(() -> loc, () -> "text");

    // Nobody in range: spawn/refresh/tick never create (and tick has nothing to remove).
    when(player.getLocation()).thenReturn(loc.clone().add(200, 0, 0));
    h.spawn(plugin);
    h.refresh(plugin);
    h.tick();
    when(player.getWorld()).thenReturn(mock(World.class));
    when(player.getLocation()).thenReturn(loc);
    h.tick();
    spawns(0);

    // Player near but the label's chunk is not loaded.
    when(player.getWorld()).thenReturn(world);
    when(world.isChunkLoaded(0, 0)).thenReturn(false);
    h.spawn(plugin);
    h.tick();
    spawns(0);

    // Chunk loaded: tick creates it, then updates it while the player stays.
    when(world.isChunkLoaded(0, 0)).thenReturn(true);
    h.tick();
    spawns(1);
    h.tick();
    spawns(1);
    verify(display, atLeast(2)).setText("text");

    // Player leaves: removed; next tick does nothing more.
    when(player.getLocation()).thenReturn(loc.clone().add(200, 0, 0));
    h.tick();
    verify(display).remove();
    bukkit.when(() -> Bukkit.getEntity(id)).thenReturn(null);
    h.tick();
    verify(display, times(1)).remove();

    // Player back while an (unexpected) display exists but the chunk unloaded: removed.
    bukkit.when(() -> Bukkit.getEntity(id)).thenReturn(display);
    when(player.getLocation()).thenReturn(loc);
    h.tick();
    spawns(2);
    when(world.isChunkLoaded(0, 0)).thenReturn(false);
    h.tick();
    verify(display, times(2)).remove();
    h.tick();
    h.refresh(plugin);
    verify(display, times(2)).remove();
  }

  @Test
  void lostOrDeadDisplaysAreReplacedAndUnloadedLabelsAreDropped() {
    var loc = new Location(world, 0, 64, 0);
    when(player.getLocation()).thenReturn(loc);
    var h = new Hologram(() -> loc, () -> "text");
    h.tick();
    spawns(1);
    // Display unloaded with its chunk (UUID no longer resolves): one replacement.
    bukkit.when(() -> Bukkit.getEntity(id)).thenReturn(null);
    h.refresh(plugin);
    spawns(2);
    // The UUID resolves to something that is not a TextDisplay.
    bukkit.when(() -> Bukkit.getEntity(id)).thenReturn(mock(Entity.class));
    h.tick();
    spawns(3);
    // Dead display is replaced, never removed again.
    bukkit.when(() -> Bukkit.getEntity(id)).thenReturn(display);
    when(display.isDead()).thenReturn(true);
    h.tick();
    spawns(4);
    h.remove(plugin);
    verify(display, never()).remove();
    // Chunk unloaded: refresh drops the reference without spawning.
    when(display.isDead()).thenReturn(false);
    when(world.isChunkLoaded(0, 0)).thenReturn(false);
    h.refresh(plugin);
    spawns(4);
  }

  @Test
  void missingLocationsAndWorldsAreIgnored() {
    var current = new AtomicReference<Location>(null);
    var h = new Hologram(current::get, () -> "text");
    h.tick();
    h.spawn(plugin);
    h.refresh(plugin);
    h.remove(plugin);
    current.set(new Location(null, 0, 0, 0));
    h.tick();
    h.spawn(plugin);
    assertFalse(Hologram.labelPositions().contains(null));
    spawns(0);
    var unloaded = new Hologram(() -> {
      throw new IllegalArgumentException("World unloaded");
    }, () -> "text");
    assertDoesNotThrow(Hologram::labelPositions);
    unloaded.setYOffset(1);
  }

  @Test
  void compatibilityFailuresAndOffThreadCallsAreHandled() {
    var loc = new Location(world, 0, 64, 0);
    when(player.getLocation()).thenReturn(loc);
    doThrow(new UnsupportedOperationException()).when(display).setBillboard(any());
    doThrow(new UnsupportedOperationException()).when(display).setShadowed(true);
    doThrow(new UnsupportedOperationException()).when(display).setSeeThrough(false);
    var h = new Hologram(() -> loc, () -> "text");
    bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
    h.spawn(plugin);
    spawns(0);
    bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
    server.getScheduler().performOneTick();
    spawns(1);
    verify(display).setText("text");
  }

  @Test
  void spawningRemovesALostDisplayAtTheSamePosition() {
    var loc = new Location(world, 0, 64, 0);
    var pos = loc.clone().add(.5, 1, .5);
    when(player.getLocation()).thenReturn(loc);
    var lost = mock(TextDisplay.class);
    var lostPdc = mock(PersistentDataContainer.class);
    when(lost.getUniqueId()).thenReturn(UUID.randomUUID());
    when(lost.getPersistentDataContainer()).thenReturn(lostPdc);
    when(lostPdc.has(Hologram.LABEL_KEY, PersistentDataType.BYTE)).thenReturn(true);
    when(lost.getLocation()).thenReturn(pos.clone());
    doReturn(List.of(lost))
        .when(world)
        .getNearbyEntitiesByType(TextDisplay.class, pos, LabelCleanup.SEARCH_RADIUS);
    var h = new Hologram(() -> loc, () -> "text");
    h.tick();
    verify(lost).remove();
    spawns(1);
  }
}
