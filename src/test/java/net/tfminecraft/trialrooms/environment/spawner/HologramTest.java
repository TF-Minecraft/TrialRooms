package net.tfminecraft.trialrooms.environment.spawner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.tfminecraft.trialrooms.TrialTestSupport;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.junit.jupiter.api.*;

class HologramTest extends TrialTestSupport {
  @Test
  void displayLifecycleUpdatesTextAndRemovesOutsideViewRange() {
    World world = mock(World.class);
    var loc = new Location(world, 1, 64, 3);
    var current = new AtomicReference<Location>(loc);
    var text = new AtomicReference<String>("hello");
    var display = mock(TextDisplay.class);
    var id = UUID.randomUUID();
    when(display.getUniqueId()).thenReturn(id);
    when(world.spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class)))
        .thenAnswer(
            a -> {
              ((Consumer<TextDisplay>) a.getArgument(2)).accept(display);
              return display;
            });
    try (var b = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
      b.when(() -> Bukkit.getEntity(id)).thenReturn(display);
      var h = new Hologram(current::get, text::get);
      h.setYOffset(2);
      h.spawn(plugin);
      verify(display).setInvulnerable(true);
      verify(display).setPersistent(true);
      verify(display).setGravity(false);
      verify(display, atLeastOnce()).setText("hello");
      verify(display).teleport(loc.clone().add(.5, 2, .5));
      h.spawn(plugin);
      verify(world, times(1))
          .spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class));
      text.set(null);
      h.refresh(plugin);
      verify(display).setText("");
      var p = mock(Player.class);
      when(p.getWorld()).thenReturn(world);
      when(p.getLocation()).thenReturn(loc);
      b.when(Bukkit::getOnlinePlayers).thenReturn(List.of(p));
      h.tick();
      when(display.isDead()).thenReturn(true);
      h.tick();
      h.refresh(plugin);
      when(display.isDead()).thenReturn(false);
      when(p.getLocation()).thenReturn(loc.clone().add(200, 0, 0));
      h.tick();
      verify(display).remove();
      h.tick();
      when(p.getWorld()).thenReturn(mock(World.class));
      h.tick();
      current.set(null);
      h.tick();
      h.spawn(plugin);
      h.refresh(plugin);
      h.remove(plugin);
      current.set(new Location(null, 0, 0, 0));
      h.spawn(plugin);
      h.refresh(plugin);
    }
  }

  @Test
  void absentDisplayAndCompatibilityFailuresAreHandled() {
    World world = mock(World.class);
    var loc = new Location(world, 0, 64, 0);
    var display = mock(TextDisplay.class);
    var id = UUID.randomUUID();
    when(display.getUniqueId()).thenReturn(id);
    doThrow(new UnsupportedOperationException()).when(display).setBillboard(any());
    doThrow(new UnsupportedOperationException()).when(display).setShadowed(true);
    doThrow(new UnsupportedOperationException()).when(display).setSeeThrough(false);
    when(world.spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class)))
        .thenAnswer(
            a -> {
              ((Consumer<TextDisplay>) a.getArgument(2)).accept(display);
              return display;
            });
    try (var b = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
      var h = new Hologram(() -> loc, () -> "text");
      h.refresh(plugin);
      b.when(() -> Bukkit.getEntity(id)).thenReturn(mock(Entity.class));
      h.refresh(plugin);
      b.when(() -> Bukkit.getEntity(id)).thenReturn(display);
      when(display.isDead()).thenReturn(true);
      h.remove(plugin);
      verify(display, never()).remove();
      b.when(Bukkit::isPrimaryThread).thenReturn(false);
      h.spawn(plugin);
      b.when(Bukkit::isPrimaryThread).thenReturn(true);
      server.getScheduler().performOneTick();
      verify(world, atLeast(3))
          .spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class));
    }
  }

  @Test
  void disappearingLocationBetweenDisplayLookupAndTeleportIsSafe() {
    World world = mock(World.class);
    var loc = new Location(world, 0, 64, 0);
    var display = mock(TextDisplay.class);
    var id = UUID.randomUUID();
    when(display.getUniqueId()).thenReturn(id);
    when(world.spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class)))
        .thenAnswer(
            a -> {
              ((Consumer<TextDisplay>) a.getArgument(2)).accept(display);
              return display;
            });
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    try (var b = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
      var h = new Hologram(() -> calls.incrementAndGet() <= 2 ? loc : null, () -> "text");
      h.spawn(plugin);
      verify(display, never()).teleport(any(Location.class));
      b.when(() -> Bukkit.getEntity(id)).thenReturn(display);
      h.refresh(plugin);
      verify(display, never()).teleport(any(Location.class));
      var p = mock(Player.class);
      when(p.getWorld()).thenReturn(world);
      when(p.getLocation()).thenReturn(loc);
      b.when(Bukkit::getOnlinePlayers).thenReturn(List.of(p));
      new Hologram(() -> loc, () -> "new").tick();
      verify(world, times(2))
          .spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class));
    }
  }
}
