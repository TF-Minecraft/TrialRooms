package net.tfminecraft.trialrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.trialrooms.cache.Cache;
import net.tfminecraft.trialrooms.environment.entrance.Conversion;
import net.tfminecraft.trialrooms.manager.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;

class ManagersTest extends TrialTestSupport {
  @Test
  void playerStateTracksEntryExitAndRegeneration() {
    var manager = new PlayerManager(org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin());
    assertSame(manager, PlayerManager.get());
    var p = server.addPlayer();
    var d = manager.get(p);
    assertFalse(d.isInDungeon());
    assertNull(d.getLastEntranceId());
    assertEquals(0, d.getEnteredAtMs());
    var id = UUID.randomUUID();
    manager.markEntered(p, id);
    assertTrue(manager.isInside(p));
    assertEquals(id, d.getLastEntranceId());
    assertTrue(d.getEnteredAtMs() > 0);
    long entered = d.getEnteredAtMs();
    manager.ensureInside(p);
    assertEquals(entered, d.getEnteredAtMs());
    var natural = new EntityRegainHealthEvent(p, 1, EntityRegainHealthEvent.RegainReason.SATIATED);
    manager.onRegen(natural);
    assertTrue(natural.isCancelled());
    var spell = new EntityRegainHealthEvent(p, 1, EntityRegainHealthEvent.RegainReason.MAGIC);
    manager.onRegen(spell);
    assertFalse(spell.isCancelled());
    manager.onRegen(
        new EntityRegainHealthEvent(
            mock(LivingEntity.class), 1, EntityRegainHealthEvent.RegainReason.SATIATED));
    manager.markExited(p);
    assertFalse(manager.isInside(p));
    var outside = new EntityRegainHealthEvent(p, 1, EntityRegainHealthEvent.RegainReason.SATIATED);
    manager.onRegen(outside);
    assertFalse(outside.isCancelled());
    manager.ensureInside(p);
    assertTrue(manager.isInside(p));
    manager.onQuit(new PlayerQuitEvent(p, "quit"));
    assertFalse(manager.isInside(p));
    manager.ensureInside(p);
    var death = mock(PlayerDeathEvent.class);
    when(death.getEntity()).thenReturn(p);
    manager.onDeath(death);
    assertFalse(manager.isInside(p));
  }

  @Test
  void conversionMatchingAndDefaultAmount() {
    try (var libs = mockStatic(TLibs.class)) {
      var api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
      libs.when(TLibs::getItemAPI).thenReturn(api);
      var item = new ItemStack(Material.EMERALD);
      var c = new Conversion("v.emerald 0.4");
      assertEquals("v.emerald", c.getItem());
      assertEquals(.4, c.getAmount());
      assertEquals("v.emerald 0.4", c.toString());
      when(api.getChecker().checkItemWithPath(item, "v.emerald")).thenReturn(true);
      assertTrue(c.match(item));
      Cache.conversions.clear();
      Cache.conversions.add(new Conversion("v.stone"));
      Cache.conversions.add(c);
      assertEquals(.4, Cache.getConversionAmount(item));
      assertEquals(0, Cache.getConversionAmount(new ItemStack(Material.STONE)));
      assertEquals(1, new Conversion("v.iron").getAmount());
      assertThrows(IllegalArgumentException.class, () -> new Conversion("v.emerald nope"));
      new Cache();
    }
  }

  @Test
  void conversionSyntaxHonorsWhitespaceCommentsAndRejectsInvalidNumbers() {
    assertEquals("v.emerald", new Conversion("  v.emerald 0.4 # reward").getItem());
    assertEquals(1, new Conversion("v.emerald # default").getAmount());
    for (var text :
        Arrays.asList(
            null, "", "   ", "#comment", "v.emerald NaN", "v.emerald Infinity", "v.emerald -1"))
      assertThrows(IllegalArgumentException.class, () -> new Conversion(text));
  }

  @Test
  void deathRemovesConvertibleDropsStorageArmourAndOffhand() {
    var manager = new PlayerManager(org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin());
    var p = server.addPlayer();
    manager.ensureInside(p);
    var drops =
        new ArrayList<ItemStack>(
            Arrays.asList(
                null,
                new ItemStack(Material.AIR),
                new ItemStack(Material.EMERALD),
                new ItemStack(Material.STONE)));
    var e = mock(PlayerDeathEvent.class);
    when(e.getEntity()).thenReturn(p);
    when(e.getDrops()).thenReturn(drops);
    p.getInventory().setItem(0, new ItemStack(Material.EMERALD));
    p.getInventory().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
    p.getInventory().setItemInOffHand(new ItemStack(Material.EMERALD));
    try (var cache = mockStatic(Cache.class)) {
      cache
          .when(() -> Cache.getConversionAmount(any()))
          .thenAnswer(
              a -> {
                Material t = ((ItemStack) a.getArgument(0)).getType();
                return t == Material.STONE ? 0.0 : 1.0;
              });
      var guard = new ConversionDeathGuard();
      guard.onPlayerDeath(e);
      assertFalse(manager.isInside(p));
      assertEquals(3, drops.size());
      assertNull(p.getInventory().getItem(0));
      assertNull(p.getInventory().getHelmet());
      assertEquals(Material.AIR, p.getInventory().getItemInOffHand().getType());
      guard.onPlayerDeath(e);
      cache
          .when(() -> Cache.getConversionAmount(any()))
          .thenThrow(new IllegalStateException("dependency offline"));
      guard.onPlayerDeath(e);
      assertEquals(3, drops.size());
    }
  }

  @Test
  void commandsHandleMissingAndUnknownArgumentsWithoutCrashing() {
    var m = new CommandManager();
    var cmd = mock(org.bukkit.command.Command.class);
    assertTrue(m.onCommand(server.getConsoleSender(), cmd, "tr", new String[0]));
    var p = server.addPlayer();
    assertTrue(m.onCommand(p, cmd, "tr", new String[0]));
    p.setOp(true);
    assertDoesNotThrow(() -> m.onCommand(p, cmd, "tr", new String[0]));
    assertTrue(m.onCommand(p, cmd, "tr", new String[] {"other"}));
    try (var sm = mockStatic(SpawnerManager.class)) {
      assertTrue(m.onCommand(p, cmd, "tr", new String[] {"resetcooldowns"}));
      sm.verify(SpawnerManager::resetCooldowns);
    }
    assertTrue(m.onTabComplete(p, cmd, "tr", new String[0]).isEmpty());
    assertTrue(m.onTabComplete(server.getConsoleSender(), cmd, "tr", new String[0]).isEmpty());
  }
}
