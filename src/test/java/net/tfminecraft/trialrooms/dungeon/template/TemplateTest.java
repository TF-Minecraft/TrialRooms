package net.tfminecraft.trialrooms.dungeon.template;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import net.tfminecraft.trialrooms.dungeon.grid.Orientation;
import net.tfminecraft.trialrooms.dungeon.grid.Side;
import org.junit.jupiter.api.Test;

class TemplateTest {

  static Doorway door(int x, int z, Side s) {
    return new Doorway(x, z, s, 4);
  }

  static RoomTemplate room(String id, RoomType type, int w, int d, List<Doorway> doors) {
    return new RoomTemplate(id, type, w, d, doors, List.of(), 1, 0, 0, 0, Set.of());
  }

  @Test
  void doorwaysAndMarkersFollowTheOrientation() {
    Doorway d = door(0, 1, Side.WEST).oriented(Orientation.R90, 1, 2);
    assertEquals(new Doorway(0, 0, Side.NORTH, 4), d);
    assertEquals(14, d.offsetInCell(32));
    assertEquals(13, new Doorway(0, 0, Side.EAST, 6).offsetInCell(32));
    assertThrows(IllegalArgumentException.class, () -> new Doorway(0, 0, null, 4));

    Marker m = new Marker("spawn", null, 1, 2, 3, Side.NORTH).oriented(Orientation.R180, 32, 32);
    assertEquals(new Marker("spawn", "", 30, 2, 28, Side.SOUTH), m);
    assertNull(new Marker("chest", "x", 0, 0, 0, null).oriented(Orientation.M0, 32, 32).facing());
    assertThrows(IllegalArgumentException.class, () -> new Marker(" ", "", 0, 0, 0, null));
    assertThrows(IllegalArgumentException.class, () -> new Marker(null, "", 0, 0, 0, null));
  }

  @Test
  void templateRulesAreChecked() {
    List<Doorway> one = List.of(door(0, 0, Side.NORTH));
    RoomTemplate ok = new RoomTemplate("a_1", RoomType.COMBAT, 2, 2,
        List.of(door(1, 0, Side.NORTH), door(1, 1, Side.EAST), door(0, 1, Side.SOUTH), door(0, 0, Side.WEST)),
        List.of(), 2, 2, 4, 1, Set.of("t"));
    assertTrue(ok.allowsDepth(3));
    assertFalse(ok.allowsDepth(1));
    assertFalse(ok.allowsDepth(5));
    assertTrue(room("b", RoomType.START, 1, 1, one).allowsDepth(99));
    String[] bad = {null, "Bad"};
    for (String id : bad) {
      assertThrows(IllegalArgumentException.class, () -> room(id, RoomType.COMBAT, 1, 1, one));
    }
    assertThrows(IllegalArgumentException.class, () -> room("x", null, 1, 1, one));
    assertThrows(IllegalArgumentException.class, () -> room("x", RoomType.COMBAT, 0, 1, one));
    assertThrows(IllegalArgumentException.class, () -> room("x", RoomType.COMBAT, 1, 0, one));
    assertThrows(IllegalArgumentException.class, () -> room("x", RoomType.COMBAT, 1, 1, List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new RoomTemplate("x", RoomType.COMBAT, 1, 1, one, List.of(), 0, 0, 0, 0, Set.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new RoomTemplate("x", RoomType.COMBAT, 1, 1, one, List.of(), 1, -1, 0, 0, Set.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new RoomTemplate("x", RoomType.COMBAT, 1, 1, one, List.of(), 1, 0, -1, 0, Set.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new RoomTemplate("x", RoomType.COMBAT, 1, 1, one, List.of(), 1, 0, 0, -1, Set.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new RoomTemplate("x", RoomType.COMBAT, 1, 1, one, List.of(), 1, 3, 2, 0, Set.of()));
    assertThrows(IllegalArgumentException.class,
        () -> room("x", RoomType.COMBAT, 1, 1, List.of(door(0, 0, Side.NORTH), door(0, 0, Side.NORTH))));
    // doorways must sit on the outer edge of their own side
    for (Doorway d : List.of(door(-1, 0, Side.WEST), door(0, -1, Side.NORTH), door(2, 0, Side.EAST),
        door(0, 2, Side.SOUTH), door(0, 1, Side.NORTH), door(0, 0, Side.SOUTH), door(1, 0, Side.WEST),
        door(0, 0, Side.EAST))) {
      assertThrows(IllegalArgumentException.class, () -> room("x", RoomType.COMBAT, 2, 2, List.of(d)), d.toString());
    }
  }

  @Test
  void roomTypesParse() {
    assertEquals(RoomType.BOSS, RoomType.parse(" boss "));
    assertThrows(IllegalArgumentException.class, () -> RoomType.parse("dragon"));
    assertThrows(IllegalArgumentException.class, () -> RoomType.parse(null));
  }

  @Test
  void catalogChecksSizesWidthsAndMarkers() {
    List<Doorway> one = List.of(door(0, 0, Side.NORTH));
    RoomTemplate a = room("a", RoomType.COMBAT, 1, 1, one);
    RoomCatalog c = new RoomCatalog(32, 24, List.of(a));
    assertEquals(32, c.cellSize());
    assertEquals(24, c.cellHeight());
    assertEquals(List.of(a), c.templates());
    assertEquals(List.of(a), c.ofType(RoomType.COMBAT));
    assertEquals(List.of(), c.ofType(RoomType.BOSS));
    assertThrows(IllegalArgumentException.class, () -> new RoomCatalog(31, 24, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new RoomCatalog(6, 24, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new RoomCatalog(32, 3, List.of()));
    assertThrows(IllegalArgumentException.class, () -> new RoomCatalog(32, 24, List.of(a, a)));
    for (int width : new int[] {1, 0, 5, 32}) {
      RoomTemplate t = room("w", RoomType.COMBAT, 1, 1, List.of(new Doorway(0, 0, Side.NORTH, width)));
      assertThrows(IllegalArgumentException.class, () -> new RoomCatalog(32, 24, List.of(t)), "width " + width);
    }
    for (int[] p : new int[][] {{-1, 0, 0}, {0, -1, 0}, {0, 0, -1}, {32, 0, 0}, {0, 24, 0}, {0, 0, 32}}) {
      RoomTemplate t = new RoomTemplate("m", RoomType.COMBAT, 1, 1, one,
          List.of(new Marker("spawn", "", p[0], p[1], p[2], null)), 1, 0, 0, 0, Set.of());
      assertThrows(IllegalArgumentException.class, () -> new RoomCatalog(32, 24, List.of(t)));
    }
  }
}
