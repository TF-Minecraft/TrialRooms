package net.tfminecraft.trialrooms.dungeon.loot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public class CosmeticsTest {

  @AfterEach
  void tearDown() {
    if (MockBukkit.isMocked()) {
      MockBukkit.unmock();
    }
  }

  static Cosmetics shipped() {
    List<String> problems = new ArrayList<>();
    Cosmetics c = Cosmetics.load(RewardsTest.shippedYaml(), problems);
    assertEquals(List.of(), problems);
    return c;
  }

  @Test
  void theShippedCosmeticsLoad() {
    Cosmetics c = shipped();
    assertEquals(1, c.trophies("delve_seithr", "nightmare").size());
    assertEquals(2, c.skins("delve_seithr", "nightmare").size());
    assertEquals(List.of(), c.skins("delve_seithr", "normal"), "no skins on Normal");
    assertEquals(List.of(), c.trophies("other", "normal"));
    assertEquals(List.of(), c.skins("other", "hard"));
    assertTrue(c.unlockCommand().contains("{player}"));
  }

  @Test
  void badCosmeticsAreRefused() throws Exception {
    List<String> problems = new ArrayList<>();
    assertNull(Cosmetics.load(new YamlConfiguration(), problems));
    assertTrue(problems.isEmpty(), "no cosmetics section: none");
    for (String bad : List.of("cosmetics: {trophies: [{recipe: d, chance: 0.1, item: i}]}",
        "cosmetics: {trophies: [{recipe: d, item: i, name: n}]}",
        "cosmetics: {trophies: [{recipe: d, chance: 2, item: i, name: n}]}",
        "cosmetics: {skins: [{recipe: d, chance: 0.1, permission: p, name: n}]}",
        "cosmetics: {unlock-command: 'lp {player}', skins: [{recipe: d, chance: 0.1, permission: p, name: n}]}",
        "cosmetics: {skins: [{recipe: ' ', chance: 0.1, permission: p, name: n}]}",
        "cosmetics: {skins: [{recipe: d, chance: -0.1, permission: p, name: n}]}")) {
      problems.clear();
      YamlConfiguration y = new YamlConfiguration();
      y.loadFromString(bad);
      assertNull(Cosmetics.load(y, problems), bad);
      assertTrue(problems.getFirst().endsWith("so runs give no trophies or skins"), problems.toString());
    }
    YamlConfiguration ok = new YamlConfiguration();
    ok.loadFromString("cosmetics: {trophies: [{recipe: d, chance: 0.5, item: i, name: n}]}");
    Cosmetics c = Cosmetics.load(ok, problems);
    assertEquals("", c.trophies().getFirst().difficulty(), "a dungeon without difficulties");
    assertEquals(1, c.trophies("d", "").size());
  }

  @Test
  void trophiesAreNamedForTheFeat() {
    var server = MockBukkit.mock();
    Cosmetics.runOnConsole("say a skin unlocked"); // (the console runs it; an unknown command does no harm)
    assertNotNull(server.getConsoleSender());
    ItemStack skull = new ItemStack(Material.SKELETON_SKULL, 3);
    ItemStack t = DungeonRewards.dress(skull, "Ice Golem Trophy", List.of("Won on 23 November 2026", "By Ann, Bo"));
    assertEquals(1, t.getAmount());
    assertEquals(3, skull.getAmount(), "the base item is left alone");
    assertEquals("Ice Golem Trophy", PlainTextComponentSerializer.plainText().serialize(t.getItemMeta().displayName()));
    assertEquals(List.of("Won on 23 November 2026", "By Ann, Bo"),
        t.getItemMeta().lore().stream().map(Component.class::cast).map(PlainTextComponentSerializer.plainText()::serialize).toList());
  }
}
