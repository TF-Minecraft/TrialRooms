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
    assertEquals(List.of(), a.of(46, 1), "Normal has none");
    List<WeeklyAffix> hard = a.of(46, 2);
    List<WeeklyAffix> nightmare = a.of(46, 3);
    assertEquals(1, hard.size());
    assertEquals(2, nightmare.size());
    assertEquals(hard.getFirst(), nightmare.getFirst(), "Nightmare has Hard's affix and one more");
    assertNotEquals(nightmare.get(0), nightmare.get(1));
    assertEquals(hard, a.of(46, 2), "the same all week");
    for (long w = -12; w < 300; w++) {
      assertNotEquals(a.of(w, 2), a.of(w + 1, 2), "never the same affix two weeks running (week " + w + ")");
    }
    Set<WeeklyAffix> turn = new HashSet<>();
    for (long w = 50; w < 55; w++) {
      turn.add(a.of(w, 2).getFirst());
    }
    assertEquals(5, turn.size(), "each affix leads once a turn");
    assertEquals(1.25, a.fortified());
    assertEquals(1.15, a.tyrantDamage());
    assertEquals(0, Affixes.week(java.time.LocalDate.of(2026, 1, 5).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()));
    assertEquals(0, Affixes.week(java.time.LocalDate.of(2026, 1, 11).atTime(23, 59).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()),
        "Monday to Sunday, UTC");
    assertEquals(1, Affixes.week(java.time.LocalDate.of(2026, 1, 12).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()));
    Affixes one = new Affixes(List.of(WeeklyAffix.RAGING), Map.of(2, 1), 1, 1, 1, 0.3, 1, 0, 1);
    assertEquals(List.of(WeeklyAffix.RAGING), one.of(7, 2), "a pool of one");
    for (int size = 2; size <= 5; size++) {
      Affixes p = new Affixes(List.of(WeeklyAffix.values()).subList(0, size), Map.of(2, 1, 3, 2), 1, 1, 1, 0.3, 1, 0, 1);
      for (long w = -50; w < 3000; w++) {
        assertNotEquals(p.of(w, 2), p.of(w + 1, 2), "pool of " + size + ", week " + w);
        if (size >= 4) {
          assertNotEquals(java.util.Set.copyOf(p.of(w, 3)), java.util.Set.copyOf(p.of(w + 1, 3)),
              "Nightmare's pair never repeats (pool of " + size + ", week " + w + ")");
        }
      }
    }
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
    assertEquals(List.of(), Affixes.load(none, problems).of(1, 3), "no per-tier: no affixes");
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
