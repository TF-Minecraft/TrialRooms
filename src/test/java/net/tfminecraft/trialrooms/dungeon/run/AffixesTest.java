package net.tfminecraft.trialrooms.dungeon.run;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

public class AffixesTest {

  static Affixes shipped() {
    YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(
        AffixesTest.class.getResourceAsStream("/dungeons/settings.yml"), StandardCharsets.UTF_8));
    List<String> problems = new ArrayList<>();
    Affixes a = Affixes.load(y, problems);
    assertEquals(List.of(), problems);
    return a;
  }

  @Test
  void eachWeekEveryoneGetsTheSameAffixes() {
    Affixes a = shipped();
    assertEquals(5, a.pool().size());
    assertEquals(List.of(), a.of("2026-W48", 1), "Normal has none");
    List<WeeklyAffix> hard = a.of("2026-W48", 2);
    List<WeeklyAffix> nightmare = a.of("2026-W48", 3);
    assertEquals(1, hard.size());
    assertEquals(2, nightmare.size());
    assertEquals(hard.getFirst(), nightmare.getFirst(), "Nightmare has Hard's affix and one more");
    assertEquals(hard, a.of("2026-W48", 2), "the same all week");
    Set<WeeklyAffix> seen = new HashSet<>();
    for (int w = 1; w <= 20; w++) {
      seen.add(a.of("2026-W" + w, 2).getFirst());
    }
    assertTrue(seen.size() >= 3, "it rotates: " + seen);
    assertEquals(1.25, a.fortified());
    assertEquals(1.15, a.tyrantDamage());
    assertEquals("2026-W47", Affixes.week(1_795_000_000_000L, ZoneId.of("UTC")));
    for (WeeklyAffix w : WeeklyAffix.values()) {
      assertFalse(w.title().isBlank());
      assertFalse(w.hint().isBlank());
    }
  }

  @Test
  void badAffixesAreRefused() throws Exception {
    List<String> problems = new ArrayList<>();
    assertNull(Affixes.load(new YamlConfiguration(), problems));
    assertTrue(problems.isEmpty(), "no section: no affixes");
    for (String bad : List.of("affixes: {pool: [sneaky]}", "affixes: {pool: [fortified], per-tier: {2: 2}}",
        "affixes: {pool: [fortified], per-tier: {x: 1}}", "affixes: {pool: [fortified], fortified: 0.5}",
        "affixes: {pool: [fortified], raging: {below: 1}}")) {
      problems.clear();
      YamlConfiguration y = new YamlConfiguration();
      y.loadFromString(bad);
      assertNull(Affixes.load(y, problems), bad);
      assertTrue(problems.getFirst().endsWith("so runs have no weekly affixes"), problems.toString());
    }
    YamlConfiguration none = new YamlConfiguration();
    none.loadFromString("affixes: {pool: [fortified]}");
    assertEquals(List.of(), Affixes.load(none, problems).of("2026-W1", 3), "no per-tier: no affixes");
    List<WeeklyAffix> p = List.of(WeeklyAffix.FORTIFIED);
    for (double[] v : List.of(new double[] {0.9, 1, 1, 0.3, 1, 0, 1}, new double[] {1, 0.9, 1, 0.3, 1, 0, 1},
        new double[] {1, 1, 0.9, 0.3, 1, 0, 1}, new double[] {1, 1, 1, 0, 1, 0, 1}, new double[] {1, 1, 1, 0.3, 0.9, 0, 1},
        new double[] {1, 1, 1, 0.3, 1, -1, 1}, new double[] {1, 1, 1, 0.3, 1, 0, 0},
        new double[] {Double.POSITIVE_INFINITY, 1, 1, 0.3, 1, 0, 1}, new double[] {1, Double.POSITIVE_INFINITY, 1, 0.3, 1, 0, 1},
        new double[] {1, 1, Double.POSITIVE_INFINITY, 0.3, 1, 0, 1}, new double[] {1, 1, 1, 0.3, Double.POSITIVE_INFINITY, 0, 1},
        new double[] {1, 1, 1, 0.3, 1, Double.POSITIVE_INFINITY, 1}, new double[] {1, 1, 1, 0.3, 1, 0, Double.POSITIVE_INFINITY})) {
      assertThrows(IllegalArgumentException.class, () -> new Affixes(p, Map.of(), v[0], v[1], v[2], v[3], v[4], v[5], v[6]));
    }
    assertThrows(IllegalArgumentException.class, () -> new Affixes(p, Map.of(2, -1), 1, 1, 1, 0.3, 1, 0, 1));
  }
}
