package net.tfminecraft.trialrooms.dungeon.run;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.tfminecraft.trialrooms.dungeon.generator.DungeonRecipe;
import net.tfminecraft.trialrooms.dungeon.load.RecipeLoader;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;

class DifficultyMenuTest {

  ServerMock server;
  Plugin plugin;
  RunManager runs;
  PlayerMock ann;
  GateStore.Gate gate = new GateStore.Gate("w", 1, 2, 3, "delve");
  Difficulty normal = new Difficulty("normal", "Normal", 1, 1, 1, null, "Made for own gear.");
  Difficulty hard = new Difficulty("hard", "Hard", 2, 1.5, 1.25, "normal", "");
  Difficulty lost = new Difficulty("lost", "Lost", 3, 2, 2, "ghost", "");

  GateStore gates;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    plugin = MockBukkit.createMockPlugin();
    runs = mock(RunManager.class);
    gates = mock(GateStore.class);
    when(runs.gates()).thenReturn(gates);
    var w = server.addSimpleWorld("w");
    when(gates.at(any())).thenReturn(gate);
    ann = server.addPlayer("Ann");
    ann.teleport(new org.bukkit.Location(w, 2, 2, 5));
    when(runs.unlocked(ann, "delve", normal)).thenReturn(true);
  }

  @Test
  void aChoiceCountsOnlyByTheSameGate() {
    DifficultyMenu m = new DifficultyMenu(runs, plugin, ann, gate, entry(List.of(normal)));
    assertTrue(m.stillAtGate());
    ann.teleport(new org.bukkit.Location(ann.getWorld(), 40, 2, 40));
    assertFalse(m.stillAtGate(), "carried off");
    ann.teleport(new org.bukkit.Location(ann.getWorld(), 2, 2, 5));
    when(gates.at(any())).thenReturn(null);
    assertFalse(m.stillAtGate(), "the gate was removed");
    m.click(m.slot(0), false);
    server.getScheduler().performOneTick();
    verify(runs, never()).useGate(any(), any(), any());
    when(gates.at(any())).thenReturn(gate);
    ann.teleport(new org.bukkit.Location(server.addSimpleWorld("other"), 2, 2, 5));
    assertFalse(m.stillAtGate(), "another world");
    DifficultyMenu nowhere = new DifficultyMenu(runs, plugin, ann, new GateStore.Gate("gone", 1, 2, 3, "delve"),
        entry(List.of(normal)));
    assertFalse(nowhere.stillAtGate(), "the gate's world is gone");
    ann.disconnect();
    assertFalse(m.stillAtGate(), "offline");
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  RecipeLoader.Entry entry(List<Difficulty> ds) {
    DungeonRecipe recipe = new DungeonRecipe("delve", 1, 1, Map.of(RoomType.COMBAT, 1), true, true, 0, 0, 1, 1, null, 20, 20000);
    return new RecipeLoader.Entry(recipe, "greybox", new RunRules(1, 3, "", 3, 1, 0.5), ds);
  }

  @Test
  void eachDifficultyIsAButtonAndLockedOnesSayWhatOpensThem() {
    DifficultyMenu m = new DifficultyMenu(runs, plugin, ann, gate, entry(List.of(normal, hard, lost)));
    m.open();
    assertSame(m, ann.getOpenInventory().getTopInventory().getHolder());
    assertEquals(List.of(11, 13, 15), List.of(m.slot(0), m.slot(1), m.slot(2)), "centred, a gap between");
    assertEquals(Material.IRON_SWORD, m.getInventory().getItem(11).getType());
    assertEquals(Material.BARRIER, m.getInventory().getItem(13).getType());
    String lore = m.getInventory().getItem(13).getItemMeta().lore().toString();
    assertTrue(lore.contains("Opens once you have won Normal here."), lore);
    assertTrue(lore.contains("x1.5 health, x1.25 damage"), lore);
    assertTrue(m.getInventory().getItem(15).getItemMeta().lore().toString().contains("won ghost"), "an unknown need by its id");
    assertFalse(m.getInventory().getItem(11).getItemMeta().lore().toString().contains("health"), "plain enemies: no line");

    m.click(13, false);
    assertEquals("§cHard is not open for you yet.", ann.nextMessage());
    m.click(4, false); // nothing there
    m.click(11, false);
    verify(runs, never()).useGate(any(), any(), any());
    server.getScheduler().performOneTick();
    verify(runs).useGate(ann, gate, "normal");
  }

  @Test
  void manyDifficultiesShowTheFirstFive() {
    Difficulty[] six = new Difficulty[6];
    for (int i = 0; i < 6; i++) {
      six[i] = new Difficulty("d" + i, "D" + i, i + 1, 1, 1, null, "");
      when(runs.unlocked(ann, "delve", six[i])).thenReturn(true);
    }
    DifficultyMenu m = new DifficultyMenu(runs, plugin, ann, gate, entry(List.of(six)));
    m.open();
    assertEquals(9, m.slot(0));
    assertEquals(17, m.slot(4));
    assertEquals(Material.NETHERITE_SWORD, m.getInventory().getItem(17).getType(), "the third icon and on");
    m.click(17, false);
    server.getScheduler().performOneTick();
    verify(runs).useGate(ann, gate, "d4");
  }
}
