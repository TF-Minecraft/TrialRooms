package net.tfminecraft.trialrooms.dungeon.event;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import net.tfminecraft.trialrooms.dungeon.generator.DungeonGenerator;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonRecipe;
import net.tfminecraft.trialrooms.dungeon.generator.GreyboxKitTest;
import net.tfminecraft.trialrooms.dungeon.run.RunRules;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;

class EventCoreTest {

  static EventKit kit(Map<EventKind, Integer> weights) {
    return new EventKit(weights, 3, 3, 60, 4, 4, 15, 2, 8);
  }

  @Test
  void eventKindsParse() {
    assertEquals(EventKind.KINDLE, EventKind.parse(" Kindle "));
    assertEquals("runes", EventKind.RUNES.id());
    assertThrows(IllegalArgumentException.class, () -> EventKind.parse(null));
    assertThrows(IllegalArgumentException.class, () -> EventKind.parse("dance"));
  }

  @Test
  void eventKitsLoadFromKitYml() throws Exception {
    List<String> problems = new ArrayList<>();
    YamlConfiguration none = new YamlConfiguration();
    assertNull(EventKit.load(none, "k", problems));
    YamlConfiguration yml = new YamlConfiguration();
    yml.loadFromString("""
        events:
          kindle: {weight: 2, braziers: 2, light-seconds: 4}
          runes: {stones: 5, length: 3}
          dance: {weight: 1}
          every-seconds: 10
          budget: 3
          most-enemies: 6
        """);
    EventKit k = EventKit.load(yml, "k", problems);
    assertEquals(List.of("k: events: unknown event dance (kindle, endure, runes)"), problems);
    assertEquals(Map.of(EventKind.KINDLE, 2, EventKind.RUNES, 1), k.weights());
    assertEquals(2, k.braziers());
    assertEquals(4, k.lightSeconds());
    assertEquals(60, k.endureSeconds(), "the default");
    assertEquals(5, k.stones());
    assertEquals(3, k.runeLength());
    assertEquals(10, k.everySeconds());
    assertEquals(3, k.budget());
    assertEquals(6, k.most());
    problems.clear();
    yml.loadFromString("events:\n  endure: {seconds: 5}\n");
    assertNull(EventKit.load(yml, "k", problems));
    assertTrue(problems.getFirst().startsWith("k: events: numbers out of range"), problems.toString());
    problems.clear();
    yml.loadFromString("events:\n  budget: 2\n");
    assertNull(EventKit.load(yml, "k", problems));
    assertTrue(problems.getFirst().contains("no usable event"));
  }

  @Test
  void eventKitNumbersAreChecked() {
    Map<EventKind, Integer> one = Map.of(EventKind.KINDLE, 1);
    assertThrows(IllegalArgumentException.class, () -> kit(Map.of()));
    assertThrows(IllegalArgumentException.class, () -> kit(Map.of(EventKind.KINDLE, 0)));
    int[][] bad = {
        {0, 3, 60, 4, 4, 15, 8}, {3, 0, 60, 4, 4, 15, 8}, {3, 3, 9, 4, 4, 15, 8}, {3, 3, 60, 1, 4, 15, 8},
        {3, 3, 60, 4, 1, 15, 8}, {3, 3, 60, 4, 4, 2, 8}, {3, 3, 60, 4, 4, 15, 0}};
    for (int[] b : bad) {
      assertThrows(IllegalArgumentException.class,
          () -> new EventKit(one, b[0], b[1], b[2], b[3], b[4], b[5], 2, b[6]));
    }
    assertThrows(IllegalArgumentException.class, () -> new EventKit(one, 3, 3, 60, 4, 4, 15, 0, 8));
    assertThrows(IllegalArgumentException.class, () -> new EventKit(one, 3, 3, 60, 4, 4, 15, Double.NaN, 8));
  }

  @Test
  void picksFollowTheWeightsAndTheSeed() {
    Map<EventKind, Integer> w = new EnumMap<>(EventKind.class);
    w.put(EventKind.ENDURE, 1);
    w.put(EventKind.RUNES, 3);
    EventKit k = kit(w);
    int runes = 0;
    Random rng = new Random(1);
    for (int i = 0; i < 400; i++) {
      EventKind e = k.pick(rng);
      assertNotEquals(EventKind.KINDLE, e, "never an event without a weight");
      runes += e == EventKind.RUNES ? 1 : 0;
    }
    assertTrue(runes > 250 && runes < 350, "about three in four: " + runes);
    assertEquals(k.pick(new Random(9)), k.pick(new Random(9)));
  }

  @Test
  void theSeedDecidesWhichFightRoomsGetEvents() {
    DungeonRecipe recipe = new DungeonRecipe("d", 6, 6, Map.of(RoomType.COMBAT, 1), true, true, 0, 0, 1, 1, null, 20,
        20000);
    var catalog = GreyboxKitTest.kit().catalog();
    EventKit both = kit(Map.of(EventKind.KINDLE, 1, EventKind.RUNES, 1));
    EventKit single = kit(Map.of(EventKind.ENDURE, 1));
    boolean twice = false;
    for (long seed = 0; seed < 30; seed++) {
      DungeonLayout layout = DungeonGenerator.generate(catalog, recipe, seed);
      Map<Integer, EventKind> all = EventPlanner.plan(layout, both, 1, 99);
      assertEquals(all, EventPlanner.plan(layout, both, 1, 99), "the same seed, the same events");
      for (int i = 0; i < layout.rooms().size(); i++) {
        assertEquals(layout.rooms().get(i).node().type() == RoomType.COMBAT, all.containsKey(i), "only plain fights");
      }
      assertEquals(2, EventPlanner.plan(layout, single, 1, 2).size(), "at most max-events");
      assertTrue(EventPlanner.plan(layout, single, 0, 5).isEmpty());
      assertTrue(EventPlanner.plan(layout, null, 1, 5).isEmpty());
      EventKind last = null;
      for (EventKind e : EventPlanner.plan(layout, single, 1, 5).values()) {
        twice |= e == last;
        last = e;
      }
    }
    assertTrue(twice, "one kind of event can still come twice in a row");
  }

  @Test
  void runeOrdersAreTouchedInTurn() {
    assertThrows(IllegalArgumentException.class, () -> new RuneSequence(1, 3, new Random(1)));
    assertThrows(IllegalArgumentException.class, () -> new RuneSequence(3, 0, new Random(1)));
    Set<Integer> used = new HashSet<>();
    for (int s = 0; s < 20; s++) {
      RuneSequence r = new RuneSequence(3, 6, new Random(s));
      for (int i = 0; i < r.length(); i++) {
        used.add(r.stone(i));
        if (i > 0) {
          assertNotEquals(r.stone(i - 1), r.stone(i), "never the same stone twice in a row");
        }
      }
    }
    assertEquals(Set.of(0, 1, 2), used);
    RuneSequence r = new RuneSequence(4, 3, new Random(5));
    assertEquals(RuneSequence.Press.RIGHT, r.press(r.stone(0)));
    assertEquals(1, r.progress());
    assertEquals(RuneSequence.Press.WRONG, r.press((r.stone(1) + 1) % 4));
    assertEquals(0, r.progress(), "a mistake goes back to the start");
    r.press(r.stone(0));
    r.restart();
    assertEquals(0, r.progress());
    r.press(r.stone(0));
    r.press(r.stone(1));
    assertFalse(r.solved());
    assertEquals(RuneSequence.Press.SOLVED, r.press(r.stone(2)));
    assertTrue(r.solved());
    assertEquals(RuneSequence.Press.SOLVED, r.press(0), "touches after it is solved change nothing");
  }

  @Test
  void recipeEventNumbersAreChecked() {
    RunRules ok = new RunRules(1, 3, "", 3, 1, 0.5, 1, "", 0.25, 0.4, 2);
    assertEquals(0.4, ok.eventChance());
    assertEquals(2, ok.maxEvents());
    assertEquals(0, new RunRules(1, 3, "", 3, 1, 0.5, 1, "", 0.25).eventChance());
    for (double chance : new double[] {-0.1, 1.5, Double.NaN}) {
      assertThrows(IllegalArgumentException.class, () -> new RunRules(1, 3, "", 3, 1, 0.5, 1, "", 0.25, chance, 2));
    }
    assertThrows(IllegalArgumentException.class, () -> new RunRules(1, 3, "", 3, 1, 0.5, 1, "", 0.25, 0.5, -1));
  }
}
