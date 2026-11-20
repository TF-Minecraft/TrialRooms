package net.tfminecraft.trialrooms.dungeon.generator;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.tfminecraft.trialrooms.dungeon.grid.Orientation;
import net.tfminecraft.trialrooms.dungeon.grid.Side;
import net.tfminecraft.trialrooms.dungeon.run.RunGeometry;
import net.tfminecraft.trialrooms.dungeon.template.Doorway;
import net.tfminecraft.trialrooms.dungeon.template.Marker;
import net.tfminecraft.trialrooms.dungeon.template.RoomCatalog;
import net.tfminecraft.trialrooms.dungeon.template.RoomTemplate;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;
import org.junit.jupiter.api.Test;

/** Shaped footprints, doorway floors and heights, and rooms at different heights. */
class ShapesTest {

  static RoomTemplate shaped(String id, RoomType type, int w, int d, Set<Integer> cells, List<Doorway> doors,
      List<Marker> markers) {
    return new RoomTemplate(id, type, w, d, doors, markers, 1, 0, 0, 0, Set.of(), cells);
  }

  /** An L: the 2x2 box without its south-east cell. */
  static final Set<Integer> L = Set.of(0, 1, 2);

  @Test
  void masksShapeRoomsAndAreChecked() {
    RoomTemplate l = shaped("l", RoomType.COMBAT, 2, 2, L,
        List.of(new Doorway(0, 0, Side.NORTH, 4), new Doorway(1, 0, Side.SOUTH, 4)), List.of());
    assertTrue(l.occupies(0, 1));
    assertFalse(l.occupies(1, 1));
    assertFalse(l.occupies(-1, 0));
    assertEquals(3, l.cellList().size());
    // a doorway on a notch edge opens into the cell the room doesn't fill
    assertEquals(Side.SOUTH, l.doorways().get(1).side());
    // no doorway on a cell the room doesn't fill, nor on an inner side
    assertThrows(IllegalArgumentException.class, () -> shaped("x", RoomType.COMBAT, 2, 2, L,
        List.of(new Doorway(1, 1, Side.SOUTH, 4)), List.of()));
    assertThrows(IllegalArgumentException.class, () -> shaped("x", RoomType.COMBAT, 2, 2, L,
        List.of(new Doorway(0, 0, Side.EAST, 4)), List.of()));
    // cells outside the box, and cells that don't join up
    assertThrows(IllegalArgumentException.class, () -> shaped("x", RoomType.COMBAT, 2, 2, Set.of(0, 4),
        List.of(new Doorway(0, 0, Side.NORTH, 4)), List.of()));
    assertThrows(IllegalArgumentException.class, () -> shaped("x", RoomType.COMBAT, 2, 2, Set.of(-1, 0),
        List.of(new Doorway(0, 0, Side.NORTH, 4)), List.of()));
    assertThrows(IllegalArgumentException.class, () -> shaped("x", RoomType.COMBAT, 2, 2, Set.of(0, 3),
        List.of(new Doorway(0, 0, Side.NORTH, 4)), List.of()));
    // a U (3x2 without its middle south cell): the doorway into its pocket is refused
    Set<Integer> u = Set.of(0, 1, 2, 3, 5);
    assertThrows(IllegalArgumentException.class, () -> shaped("x", RoomType.COMBAT, 3, 2, u,
        List.of(new Doorway(1, 0, Side.SOUTH, 4)), List.of()));
    assertEquals(5, shaped("u", RoomType.COMBAT, 3, 2, u, List.of(new Doorway(1, 0, Side.NORTH, 4)), List.of())
        .cellList().size());
    // a marker must stand in a filled cell
    assertThrows(IllegalArgumentException.class, () -> RoomCatalog.check(shaped("x", RoomType.COMBAT, 2, 2, L,
        List.of(new Doorway(0, 0, Side.NORTH, 4)), List.of(new Marker("spawn", "", 40, 1, 40, null))), 32, 24));
    RoomCatalog.check(shaped("x", RoomType.COMBAT, 2, 2, L, List.of(new Doorway(0, 0, Side.NORTH, 4)),
        List.of(new Marker("spawn", "", 40, 1, 8, null))), 32, 24);
  }

  @Test
  void doorwaysHaveFloorsAndHeights() {
    Doorway d = new Doorway(0, 0, Side.NORTH, 4, 6, 8);
    assertEquals(8, d.heightOr(4));
    assertEquals(4, new Doorway(0, 0, Side.NORTH, 4).heightOr(4));
    Doorway o = d.oriented(Orientation.of(1, false), 1, 1);
    assertEquals(6, o.floor());
    assertEquals(8, o.height());
    assertEquals(new Doorway(2, 3, Side.NORTH, 4, 6, 8), d.moved(2, 3));
    assertThrows(IllegalArgumentException.class, () -> new Doorway(0, 0, Side.NORTH, 4, -1, 0));
    assertThrows(IllegalArgumentException.class, () -> new Doorway(0, 0, Side.NORTH, 4, 0, -1));
    // an opening must fit under the room's height
    RoomTemplate tall = shaped("t", RoomType.CORRIDOR, 1, 1, Set.of(), List.of(new Doorway(0, 0, Side.NORTH, 4, 16, 8)),
        List.of());
    assertThrows(IllegalArgumentException.class, () -> RoomCatalog.check(tall, 32, 24));
    RoomCatalog.check(tall, 32, 25);
    assertThrows(IllegalArgumentException.class, () -> new RoomCatalog(32, 24, -1, List.of()));
    assertEquals(24, new RoomCatalog(32, 24, List.of()).maxRise());
  }

  /** start (one door south), ramps that climb 6 blocks going south, a boss at the bottom. */
  static RoomCatalog ramps(int maxRise) {
    RoomTemplate start = shaped("s", RoomType.START, 1, 1, Set.of(), List.of(new Doorway(0, 0, Side.SOUTH, 4)),
        List.of());
    RoomTemplate rampUp = shaped("up", RoomType.CORRIDOR, 1, 1, Set.of(),
        List.of(new Doorway(0, 0, Side.NORTH, 4, 0, 0), new Doorway(0, 0, Side.SOUTH, 4, 6, 0)), List.of());
    RoomTemplate flat = shaped("flat", RoomType.CORRIDOR, 1, 1, Set.of(),
        List.of(new Doorway(0, 0, Side.NORTH, 4), new Doorway(0, 0, Side.SOUTH, 4)), List.of());
    RoomTemplate boss = shaped("b", RoomType.BOSS, 2, 2, L,
        List.of(new Doorway(0, 0, Side.NORTH, 4, 2, 0)), List.of());
    return new RoomCatalog(32, 24, maxRise, List.of(start, rampUp, flat, boss));
  }

  static DungeonRecipe corridors(int n) {
    return new DungeonRecipe("r", n, n, Map.of(RoomType.CORRIDOR, 1), false, false, 0, 0, 1, 1, null, 50, 20000);
  }

  @Test
  void roomsSitAtTheHeightTheirDoorwaysMeetAt() {
    boolean rose = false;
    for (long seed = 1; seed <= 20; seed++) {
      DungeonLayout l = DungeonGenerator.generate(ramps(24), corridors(4), seed);
      for (DungeonLayout.Connection c : l.connections()) {
        PlacedRoom a = l.rooms().get(c.roomA());
        PlacedRoom b = l.rooms().get(c.roomB());
        assertEquals(a.elevation() + a.doorways().get(c.doorA()).floor(),
            b.elevation() + b.doorways().get(c.doorB()).floor(), "seed " + seed);
      }
      for (PlacedRoom r : l.rooms()) {
        assertTrue(Math.abs(r.elevation()) <= 24);
        rose |= r.elevation() != 0;
      }
      // the same corridor never comes twice in a row while the other one fits
      for (DungeonLayout.Connection c : l.connections()) {
        PlacedRoom a = l.rooms().get(c.roomA());
        PlacedRoom b = l.rooms().get(c.roomB());
        if (a.node().type() == RoomType.CORRIDOR && b.node().type() == RoomType.CORRIDOR) {
          assertNotEquals(a.template(), b.template(), "seed " + seed);
        }
      }
    }
    assertTrue(rose, "ramps change the level");
    // with a small rise allowed, no room leaves it (a ramp only fits when the next one climbs back)
    for (long seed = 1; seed <= 10; seed++) {
      for (PlacedRoom r : DungeonGenerator.generate(ramps(2), corridors(3), seed).rooms()) {
        assertTrue(Math.abs(r.elevation()) <= 2);
      }
    }
  }

  @Test
  void geometryAndMapFollowShapesAndHeights() {
    DungeonLayout l = DungeonGenerator.generate(ramps(24), corridors(4), 7);
    RunGeometry g = new RunGeometry(l, 32, 4, 0, 64, 0);
    int boss = l.rooms().size() - 1;
    PlacedRoom b = l.rooms().get(boss);
    assertEquals(RoomType.BOSS, b.node().type());
    assertEquals(3, b.cells().size());
    Set<Long> filled = new HashSet<>();
    for (int[] c : b.cells()) {
      filled.add(((long) c[0] << 32) ^ (c[1] & 0xffffffffL));
      assertEquals(boss, g.roomAt(c[0] * 32 + 16, c[1] * 32 + 16));
    }
    assertEquals(3, filled.size());
    for (int r = 0; r < l.rooms().size(); r++) {
      PlacedRoom room = l.rooms().get(r);
      assertEquals(64 + room.elevation(), g.baseY(r));
      for (int d = 0; d < room.doorways().size(); d++) {
        int floor = 64 + room.elevation() + room.doorways().get(d).floor();
        assertEquals(floor, g.doorFloorY(r, d));
        assertEquals(floor + 1, g.insideDoor(r, d)[1]);
        assertEquals(floor + 1, g.doorBlocks(r, d).getFirst()[1]);
      }
    }
    // the map draws the L without its missing cell
    String map = String.join("\n", LayoutMap.render(l));
    assertEquals(5, map.chars().filter(ch -> ch == 'B').count(), map);
  }
}
