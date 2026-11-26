package net.tfminecraft.trialrooms.dungeon.supply;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import io.lumine.mythic.lib.api.event.PlayerClickEvent;
import io.lumine.mythic.lib.api.event.skill.PlayerCastSkillEvent;
import io.lumine.mythic.lib.api.player.MMOPlayerData;
import io.lumine.mythic.lib.api.stat.StatInstance;
import io.lumine.mythic.lib.api.stat.StatMap;
import io.lumine.mythic.lib.api.stat.modifier.StatModifier;
import io.lumine.mythic.lib.player.resource.ResourceUpdateReason;
import io.lumine.mythic.lib.skill.Skill;
import io.lumine.mythic.lib.skill.handler.SkillHandler;
import net.Indyuce.mmocore.api.player.PlayerData;
import net.Indyuce.mmocore.api.player.stats.PlayerStats;

/** What MMOCore and MythicLib may not do for players in a run: heal on their own, or with outside potions. */
class RunRulesTest {

  Player player() {
    Player p = mock(Player.class);
    when(p.getUniqueId()).thenReturn(UUID.randomUUID());
    return p;
  }

  @Test
  void regenIsHeldWhilePlayingAndGivenBackAfter() {
    Set<Player> playing = new HashSet<>();
    StatMap map = mock(StatMap.class);
    Map<String, StatInstance> instances = Map.of("HEALTH_REGENERATION", mock(StatInstance.class),
        "MAX_HEALTH_REGENERATION", mock(StatInstance.class));
    when(map.getInstance(anyString())).thenAnswer(i -> instances.get(i.getArgument(0)));
    MmoCoreRules r = new MmoCoreRules(playing::contains, p -> null, p -> map);
    Player a = player();
    Player b = player();
    r.tick(List.of(a, b));
    verifyNoInteractions(map); // nobody plays
    playing.add(a);
    r.tick(List.of(a, b));
    r.tick(List.of(a, b)); // (held once, not twice)
    List<StatModifier> put = new ArrayList<>();
    for (String stat : MmoCoreRules.REGEN) {
      var captor = org.mockito.ArgumentCaptor.forClass(StatModifier.class);
      verify(instances.get(stat), times(1)).registerModifier(captor.capture());
      assertEquals(stat, captor.getValue().getStat());
      assertEquals(-100, captor.getValue().getValue());
      put.add(captor.getValue());
    }
    playing.remove(a);
    r.tick(List.of(a, b));
    for (StatModifier m : put) {
      verify(instances.get(m.getStat())).removeModifier(m.getUniqueId());
    }
    // a logout gives it back; someone gone from the server is simply forgotten
    playing.add(a);
    r.tick(List.of(a));
    PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
    when(quit.getPlayer()).thenReturn(a);
    r.onQuit(quit);
    verify(instances.get("HEALTH_REGENERATION"), times(2)).removeModifier(any());
    r.onQuit(quit); // nothing held any more
    r.tick(List.of(a));
    r.tick(List.of()); // gone: forgotten without touching their stats
    r.tick(List.of(a));
    verify(instances.get("HEALTH_REGENERATION"), times(4)).registerModifier(any());
    r.releaseAll(List.of(a));
    verify(instances.get("HEALTH_REGENERATION"), times(3)).removeModifier(any());
  }

  @Test
  void manaComesFromMmoCore() {
    PlayerData data = mock(PlayerData.class);
    PlayerStats stats = mock(PlayerStats.class);
    when(data.getStats()).thenReturn(stats);
    when(stats.getStat("MAX_MANA")).thenReturn(40.0);
    when(data.getMana()).thenReturn(30.0);
    Player p = player();
    MmoCoreRules r = new MmoCoreRules(x -> true, x -> data, x -> null);
    assertEquals(30, r.mana(p));
    assertEquals(40, r.max(p));
    r.add(p, 25); // never past the max
    verify(data).giveMana(10.0, ResourceUpdateReason.OTHER);
  }

  @Test
  void theServerConstructorReadsMythicLibStats() {
    StatMap map = mock(StatMap.class);
    when(map.getInstance(anyString())).thenReturn(mock(StatInstance.class));
    MMOPlayerData data = mock(MMOPlayerData.class);
    when(data.getStatMap()).thenReturn(map);
    try (var ml = mockStatic(MMOPlayerData.class)) {
      ml.when(() -> MMOPlayerData.get(any(OfflinePlayer.class))).thenReturn(data);
      MmoCoreRules r = new MmoCoreRules(x -> true);
      r.tick(List.of(player()));
      verify(map, times(2)).getInstance(anyString());
    }
  }

  @Test
  void heldSkillsDontRunInARun() {
    SupplySettings s = SupplySettings.DEFAULTS;
    assertTrue(s.holds("TFMC_RECOVERY"));
    assertTrue(s.holds("tfmc_recovery"));
    assertFalse(s.holds("Warrior_Heal"));
    assertFalse(s.holds(null));
    Set<Player> playing = new HashSet<>();
    MythicLibRules r = new MythicLibRules(playing::contains, () -> s, it -> it.getType() == Material.SLIME_BALL);
    Player p = player();
    PlayerCastSkillEvent cast = mock(PlayerCastSkillEvent.class, RETURNS_DEEP_STUBS);
    when(cast.getPlayer()).thenReturn(p);
    Skill skill = mock(Skill.class);
    SkillHandler<?> handler = mock(SkillHandler.class);
    doReturn(handler).when(skill).getHandler();
    when(handler.getId()).thenReturn("TFMC_RECOVERY");
    when(cast.getCast()).thenReturn(skill);
    r.onCast(cast);
    verify(cast, never()).setCancelled(true); // not playing
    playing.add(p);
    r.onCast(cast);
    verify(cast).setCancelled(true);
    when(handler.getId()).thenReturn("Warrior_Heal");
    r.onCast(cast);
    verify(cast, times(1)).setCancelled(true); // a healer's skill still works
  }

  @Test
  void outsidePotionsAreStoppedBeforeMmoItemsUsesThem() {
    Set<Player> playing = new HashSet<>();
    MythicLibRules r = new MythicLibRules(playing::contains, () -> SupplySettings.DEFAULTS,
        it -> it.getType() == Material.SLIME_BALL);
    Player p = player();
    PlayerClickEvent click = mock(PlayerClickEvent.class);
    when(click.getPlayer()).thenReturn(p);
    when(click.hasItem()).thenReturn(true);
    when(click.getItem()).thenReturn(new ItemStack(Material.SLIME_BALL));
    r.onClick(click);
    verify(click, never()).setCancelled(true); // not playing
    playing.add(p);
    when(click.isLeftClick()).thenReturn(true);
    r.onClick(click);
    verify(click, never()).setCancelled(true); // a left click uses nothing
    when(click.isLeftClick()).thenReturn(false);
    when(click.hasItem()).thenReturn(false);
    r.onClick(click);
    verify(click, never()).setCancelled(true);
    when(click.hasItem()).thenReturn(true);
    when(click.getItem()).thenReturn(new ItemStack(Material.APPLE));
    r.onClick(click);
    verify(click, never()).setCancelled(true); // not a heal
    when(click.getItem()).thenReturn(new ItemStack(Material.SLIME_BALL));
    r.onClick(click);
    verify(click).setCancelled(true);
  }

  @Test
  void theHeldSkillsComeFromTheSettings() {
    YamlConfiguration y = new YamlConfiguration();
    assertEquals(SupplySettings.DEFAULT_HELD, SupplySettings.from(null, null, null).heldSkills());
    SupplySettings s = SupplySettings.from(null, y.createSection("supplies"), List.of("my_regen"));
    assertTrue(s.holds("MY_REGEN"));
    assertFalse(s.holds("TFMC_RECOVERY"));
    assertEquals(Set.of(), new SupplySettings(3, 4, new TreeMap<>(), 4, Map.of(), 0, 0, 0, Set.of()).heldSkills());
  }
}
