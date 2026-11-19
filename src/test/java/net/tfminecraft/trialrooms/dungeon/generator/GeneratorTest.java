package net.tfminecraft.trialrooms.dungeon.generator;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.tfminecraft.trialrooms.dungeon.grid.Side;
import net.tfminecraft.trialrooms.dungeon.template.Doorway;
import net.tfminecraft.trialrooms.dungeon.template.RoomCatalog;
import net.tfminecraft.trialrooms.dungeon.template.RoomTemplate;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;
import org.junit.jupiter.api.Test;

class GeneratorTest {

  static final List<Side> ALL = List.of(Side.NORTH, Side.EAST, Side.SOUTH, Side.WEST);

  static RoomTemplate room(String id, RoomType type, int maxPerRun, int minDepth, int width, Side... sides) {
    List<Doorway> doors = new ArrayList<>();
    for (Side s : sides) {
      doors.add(new Doorway(0, 0, s, width));
    }
    return new RoomTemplate(id, type, 1, 1, doors, List.of(), 1, minDepth, 0, maxPerRun, Set.of());
  }

  static Map<RoomType, Integer> pool(RoomType type) {
    Map<RoomType, Integer> m = new EnumMap<>(RoomType.class);
    m.put(type, 1);
    return m;
  }

  static DungeonRecipe recipe(int trunk, boolean rest, boolean vault, int branches, int branchLen, int attempts,
      int budget) {
    return new DungeonRecipe("r", trunk, trunk, pool(RoomType.COMBAT), rest, vault, branches, branches, branchLen,
        branchLen, pool(RoomType.CORRIDOR), attempts, budget);
  }

  static RoomCatalog full() {
    Side[] all = ALL.toArray(Side[]::new);
    return new RoomCatalog(32, 24, List.of(room("s", RoomType.START, 0, 0, 4, all),
        room("c", RoomType.COMBAT, 0, 0, 4, all), room("o", RoomType.CORRIDOR, 0, 0, 4, all),
        room("r", RoomType.REST, 0, 0, 4, all), room("t", RoomType.TREASURE, 0, 0, 4, Side.SOUTH),
        room("b", RoomType.BOSS, 0, 0, 4, all), room("v", RoomType.VAULT, 0, 0, 4, Side.SOUTH)));
  }

  @Test
  void planHasTheRecipeShape() {
    List<MissionNode> plan = MissionPlanner.plan(recipe(3, true, true, 2, 2, 1, 1), new Random(1));
    assertEquals(1 + 3 + 1 + 1 + 1 + 2 * 2, plan.size());
    assertEquals(RoomType.START, plan.getFirst().type());
    assertEquals(2, plan.stream().filter(n -> n.type() == RoomType.TREASURE).count());
    for (MissionNode n : plan) {
      assertTrue(n.parent() < n.index());
      if (n.parent() >= 0) {
        assertEquals(plan.get(n.parent()).depth() + 1, n.depth());
      }
    }
    // no trunk rooms: no branches, and the boss follows the start directly
    List<MissionNode> bare = MissionPlanner.plan(recipe(0, false, false, 0, 1, 1, 1), new Random(1));
    assertEquals(List.of(RoomType.START, RoomType.BOSS), bare.stream().map(MissionNode::type).toList());
    // only corridors on the trunk: nothing to branch from
    DungeonRecipe corridors = new DungeonRecipe("c", 2, 2, pool(RoomType.CORRIDOR), false, false, 1, 1, 1, 1,
        Map.of(), 1, 1);
    assertTrue(MissionPlanner.plan(corridors, new Random(1)).stream().allMatch(MissionNode::trunk));
  }

  @Test
  void generatesDeterministicLayoutsAndRetries() {
    DungeonRecipe r = recipe(4, true, true, 2, 2, 5, 10_000);
    DungeonLayout a = DungeonGenerator.generate(full(), r, 42);
    assertEquals(LayoutMap.render(a), LayoutMap.render(DungeonGenerator.generate(full(), r, 42)));
    GreyboxKitTest.assertValid(a);
    assertEquals("r", a.recipeId());
    assertEquals(42, a.seed());
    // every unused doorway is reported, used ones are not
    int unused = 0;
    for (int i = 0; i < a.rooms().size(); i++) {
      unused += a.unusedDoorways(i).size();
    }
    int doors = a.rooms().stream().mapToInt(p -> p.doorways().size()).sum();
    assertEquals(doors - 2 * a.connections().size(), unused);
    assertNotEquals(DungeonGenerator.attemptSeed(1, 0), DungeonGenerator.attemptSeed(1, 1));
  }

  @Test
  void failsCleanlyWhenRoomsCannotFit() {
    // a start room with one doorway and combat rooms whose doorway width never matches
    RoomCatalog narrow = new RoomCatalog(32, 24, List.of(room("s", RoomType.START, 0, 0, 4, Side.NORTH),
        room("c", RoomType.COMBAT, 0, 0, 6, ALL.toArray(Side[]::new)), room("b", RoomType.BOSS, 0, 0, 4, Side.SOUTH)));
    var e = assertThrows(DungeonGenerator.GenerationException.class,
        () -> DungeonGenerator.generate(narrow, recipe(1, false, false, 0, 1, 2, 1000), 1));
    assertTrue(e.getMessage().contains("2 attempts"));
    // the step budget stops a hopeless search
    assertThrows(DungeonGenerator.GenerationException.class,
        () -> DungeonGenerator.generate(full(), recipe(30, true, true, 0, 1, 1, 3), 1));
    // a capped room type runs out
    RoomCatalog capped = new RoomCatalog(32, 24, List.of(room("s", RoomType.START, 0, 0, 4, Side.NORTH),
        room("c", RoomType.COMBAT, 1, 0, 4, Side.NORTH, Side.SOUTH), room("b", RoomType.BOSS, 0, 0, 4, Side.SOUTH)));
    assertThrows(DungeonGenerator.GenerationException.class,
        () -> DungeonGenerator.generate(capped, recipe(2, false, false, 0, 1, 1, 1000), 1));
    // a depth floor keeps a room out of early positions
    RoomCatalog deep = new RoomCatalog(32, 24, List.of(room("s", RoomType.START, 0, 0, 4, Side.NORTH),
        room("c", RoomType.COMBAT, 0, 5, 4, Side.NORTH, Side.SOUTH), room("b", RoomType.BOSS, 0, 0, 4, Side.SOUTH)));
    assertThrows(DungeonGenerator.GenerationException.class,
        () -> DungeonGenerator.generate(deep, recipe(1, false, false, 0, 1, 1, 1000), 1));
    // a start room that is never tried
    RoomCatalog late = new RoomCatalog(32, 24, List.of(room("s", RoomType.START, 0, 3, 4, Side.NORTH),
        room("b", RoomType.BOSS, 0, 0, 4, Side.SOUTH)));
    assertThrows(DungeonGenerator.GenerationException.class,
        () -> DungeonGenerator.generate(late, recipe(0, false, false, 0, 1, 1, 1000), 1));
  }

  @Test
  void reportsMissingRoomTypes() {
    RoomCatalog startOnly = new RoomCatalog(32, 24, List.of(room("s", RoomType.START, 0, 0, 4, Side.NORTH)));
    var e = assertThrows(IllegalArgumentException.class,
        () -> DungeonGenerator.generate(startOnly, recipe(1, true, true, 1, 2, 1, 1), 1));
    for (String t : List.of("BOSS", "REST", "VAULT", "TREASURE", "COMBAT", "CORRIDOR")) {
      assertTrue(e.getMessage().contains(t), t);
    }
    assertEquals(Set.of(RoomType.START, RoomType.BOSS, RoomType.COMBAT, RoomType.TREASURE),
        DungeonGenerator.neededTypes(recipe(1, false, false, 1, 1, 1, 1)));
  }

  @Test
  void recipeRulesAreChecked() {
    Map<RoomType, Integer> c = pool(RoomType.COMBAT);
    assertThrows(IllegalArgumentException.class, () -> new DungeonRecipe(" ", 1, 1, c, true, true, 0, 0, 1, 1, c, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new DungeonRecipe(null, 1, 1, c, true, true, 0, 0, 1, 1, c, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new DungeonRecipe("r", -1, 1, c, true, true, 0, 0, 1, 1, c, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new DungeonRecipe("r", 2, 1, c, true, true, 0, 0, 1, 1, c, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new DungeonRecipe("r", 1, 1, c, true, true, 0, 0, 0, 1, c, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new DungeonRecipe("r", 1, 1, null, true, true, 0, 0, 1, 1, c, 1, 1));
    assertThrows(IllegalArgumentException.class,
        () -> new DungeonRecipe("r", 1, 1, pool(RoomType.BOSS), true, true, 0, 0, 1, 1, c, 1, 1));
    Map<RoomType, Integer> zero = new EnumMap<>(RoomType.class);
    zero.put(RoomType.COMBAT, 0);
    assertThrows(IllegalArgumentException.class, () -> new DungeonRecipe("r", 1, 1, zero, true, true, 0, 0, 1, 1, c, 1, 1));
    Map<RoomType, Integer> nul = new EnumMap<>(RoomType.class);
    nul.put(RoomType.COMBAT, null);
    assertThrows(IllegalArgumentException.class, () -> new DungeonRecipe("r", 1, 1, nul, true, true, 0, 0, 1, 1, c, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new DungeonRecipe("r", 1, 1, c, true, true, 1, 1, 2, 2, null, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new DungeonRecipe("r", 0, 0, null, true, true, 1, 1, 1, 1, null, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new DungeonRecipe("r", 1, 1, c, true, true, 0, 0, 1, 1, c, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new DungeonRecipe("r", 1, 1, c, true, true, 0, 0, 1, 1, c, 1, 0));
    // branches of length 1 need no branch pool; no trunk needs no trunk pool
    assertDoesNotThrow(() -> new DungeonRecipe("r", 1, 1, c, true, true, 1, 1, 1, 1, null, 1, 1));
    assertDoesNotThrow(() -> new DungeonRecipe("r", 0, 0, null, true, true, 0, 0, 1, 1, null, 1, 1));
  }

  @Test
  void weightedPicksFollowWeights() {
    Map<String, Integer> w = new java.util.LinkedHashMap<>();
    w.put("rare", 1);
    w.put("common", 99);
    Random r = new Random(3);
    int common = 0;
    for (int i = 0; i < 1000; i++) {
      common += Weighted.pick(w, r).equals("common") ? 1 : 0;
    }
    assertTrue(common > 950);
    assertEquals(3, Weighted.shuffled(List.of(1, 2, 3), r).size());
  }

  @Test
  void mapDrawsRoomsDoorsAndMultiCellRooms() {
    RoomTemplate wide = new RoomTemplate("w", RoomType.BOSS, 2, 2, List.of(new Doorway(0, 1, Side.SOUTH, 4)),
        List.of(), 1, 0, 0, 0, Set.of());
    RoomCatalog cat = new RoomCatalog(32, 24, List.of(room("s", RoomType.START, 0, 0, 4, Side.NORTH), wide));
    DungeonLayout l = DungeonGenerator.generate(cat, recipe(0, false, false, 0, 1, 1, 1000), 7);
    List<String> map = LayoutMap.render(l);
    assertEquals(String.join("\n", map).chars().filter(ch -> ch == 'B').count(), 9);
    assertTrue(String.join("\n", map).contains("+"));
    assertTrue(String.join("\n", map).contains("S"));
    assertEquals(2, l.rooms().getLast().widthCells());
    assertEquals(4, l.rooms().getLast().cells().size());
    assertFalse(l.isConnected(0, 5));
  }
}
