package net.tfminecraft.trialrooms.dungeon.loot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import net.tfminecraft.trialrooms.dungeon.loot.Spoils.Coin;

public class SpoilsTest {

  static Spoils shipped() {
    List<String> problems = new ArrayList<>();
    Spoils s = Spoils.load(RewardsTest.shippedYaml(), problems);
    assertEquals(List.of(), problems);
    return s;
  }

  @Test
  void theShippedSpoilsPayAnActHourAnHour() {
    Spoils s = shipped();
    assertEquals(18.9, s.perHour().get("m.materials.ignitium"));
    assertEquals(85.0, s.perHour().get("denars"));
    assertEquals(100, s.coins().getFirst().value(), "the largest coin first");
    // an hour's rolls average the hourly amounts
    Map<String, Integer> total = new HashMap<>();
    Random rng = new Random(5);
    for (int i = 0; i < 400; i++) {
      s.roll(1, rng).forEach((k, v) -> total.merge(k, v, Integer::sum));
    }
    assertEquals(18.9, total.get("m.materials.ignitium") / 400.0, 0.1);
    assertEquals(0.47, total.get("m.materials.raw_tin") / 400.0, 0.1);
    int pouches = total.entrySet().stream().filter(e -> e.getKey().contains("gemstone_pouch"))
        .mapToInt(Map.Entry::getValue).sum();
    assertEquals(21.2, pouches / 400.0, 0.5);
    assertTrue(total.get("m.loot.basic_gemstone_pouch") > total.get("m.loot.mythical_gemstone_pouch"));
    int denars = total.getOrDefault("m.currency.pouch_of_coins", 0) * 100
        + total.getOrDefault("m.currency.stack_of_coins", 0) * 10
        + total.getOrDefault("m.currency.handful_of_coins", 0) * 5 + total.getOrDefault("m.currency.gold_coin", 0);
    assertEquals(85, denars / 400.0, 1);
  }

  @Test
  void fractionsAreAChanceOfOneMore() {
    Spoils s = new Spoils(Map.of("a", 1.5, "denars", 117.0, "gem", 1.0, "none", 0.0),
        Map.of("g0", 0, "g1", 1, "g2", 3), List.of(new Coin("c1", 1), new Coin("c5", 5), new Coin("c100", 100)));
    Map<String, Integer> low = s.roll(1, LootCoreTest.fixed(0));
    assertEquals(2, low.get("a"), "0 < 0.5: one more");
    assertEquals(Map.of("a", 2, "c100", 1, "c5", 3, "c1", 2, "g1", 1), low, "117 = 100 + 3x5 + 2");
    Map<String, Integer> high = s.roll(1, new Random() {
      @Override
      public double nextDouble() {
        return 0.99;
      }

      @Override
      public int nextInt(int bound) {
        return bound - 1;
      }
    });
    assertEquals(1, high.get("a"), "0.99 >= 0.5: no more");
    assertEquals(1, high.get("g2"), "the last pouch by weight");
    assertEquals(Map.of(), s.roll(0, LootCoreTest.fixed(0.5)), "no play, nothing");
  }

  @Test
  void badSpoilsAreRefused() throws Exception {
    List<String> problems = new ArrayList<>();
    assertNull(Spoils.load(new YamlConfiguration(), problems));
    assertTrue(problems.isEmpty(), "no spoils section: none, nothing to say");
    for (String bad : List.of("spoils: {}", "spoils: {per-hour: [{amount: 1}]}", "spoils: {per-hour: [{item: a}]}",
        "spoils: {per-hour: [{item: ' ', amount: 1}]}", "spoils: {per-hour: [{item: gem, amount: 1}]}",
        "spoils: {per-hour: [{item: denars, amount: 1}], coins: [{item: c, value: 5}]}",
        "spoils: {per-hour: [{item: a, amount: -1}]}",
        "spoils: {per-hour: [{item: a, amount: 1}], coins: [{item: c, value: 0}]}",
        "spoils: {per-hour: [{item: a, amount: 1}], gems: [{item: g}]}")) {
      problems.clear();
      YamlConfiguration y = new YamlConfiguration();
      y.loadFromString(bad);
      assertNull(Spoils.load(y, problems), bad);
      assertEquals(1, problems.size(), bad);
      assertTrue(problems.getFirst().endsWith("so runs pay no spoils"), problems.toString());
    }
    assertThrows(IllegalArgumentException.class, () -> new Spoils(Map.of("a", Double.POSITIVE_INFINITY), Map.of(), List.of()));
    assertThrows(IllegalArgumentException.class, () -> new Spoils(Map.of("denars", 1.0), Map.of(), List.of()));
    assertThrows(IllegalArgumentException.class, () -> new Spoils(Map.of("gem", 1.0), Map.of("g", 0), List.of()));
    assertThrows(IllegalArgumentException.class, () -> new Spoils(Map.of("a", 1.0), Map.of("g", -1), List.of()));
    YamlConfiguration ok = new YamlConfiguration();
    ok.loadFromString("spoils: {per-hour: [{item: a, amount: 2}]}");
    assertEquals(Map.of("a", 2.0), Spoils.load(ok, problems).perHour(), "gems and coins are optional");
  }
}
