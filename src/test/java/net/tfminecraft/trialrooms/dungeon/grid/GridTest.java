package net.tfminecraft.trialrooms.dungeon.grid;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GridTest {

  @Test
  void sidesTurnMirrorAndParse() {
    assertEquals(Side.SOUTH, Side.NORTH.opposite());
    assertEquals(Side.WEST, Side.EAST.opposite());
    assertEquals(Side.EAST, Side.NORTH.clockwise(1));
    assertEquals(Side.NORTH, Side.WEST.clockwise(1));
    assertEquals(Side.SOUTH, Side.EAST.clockwise(-3));
    assertEquals(Side.SOUTH, Side.NORTH.mirrored());
    assertEquals(Side.NORTH, Side.SOUTH.mirrored());
    assertEquals(Side.EAST, Side.EAST.mirrored());
    assertEquals(Side.WEST, Side.WEST.mirrored());
    assertEquals(0, Side.NORTH.dx());
    assertEquals(-1, Side.NORTH.dz());
    assertEquals(1, Side.EAST.dx());
    assertEquals(Side.WEST, Side.parse(" west "));
    assertEquals(Side.SOUTH, Side.parse("s"));
    assertThrows(IllegalArgumentException.class, () -> Side.parse("up"));
    assertThrows(IllegalArgumentException.class, () -> Side.parse(null));
  }

  /** Vanilla structure placement: mirror z, then rotate clockwise about the origin. */
  private static int[] vanilla(int x, int z, Orientation o) {
    if (o.mirrored()) {
      z = -z;
    }
    return switch (o.quarterTurns()) {
      case 0 -> new int[] {x, z};
      case 1 -> new int[] {-z, x};
      case 2 -> new int[] {-x, -z};
      default -> new int[] {z, -x};
    };
  }

  @Test
  void transformMatchesVanillaPlacementShiftedToTheOrigin() {
    int w = 5;
    int d = 3;
    for (Orientation o : Orientation.values()) {
      int minX = Integer.MAX_VALUE;
      int minZ = Integer.MAX_VALUE;
      for (int x = 0; x < w; x++) {
        for (int z = 0; z < d; z++) {
          int[] v = vanilla(x, z, o);
          minX = Math.min(minX, v[0]);
          minZ = Math.min(minZ, v[1]);
        }
      }
      Set<String> seen = new HashSet<>();
      for (int x = 0; x < w; x++) {
        for (int z = 0; z < d; z++) {
          int[] v = vanilla(x, z, o);
          int[] t = o.transform(x, z, w, d);
          assertEquals(v[0] - minX, t[0], o + " x");
          assertEquals(v[1] - minZ, t[1], o + " z");
          assertTrue(t[0] >= 0 && t[0] < o.orientedWidth(w, d), o + " in width");
          assertTrue(t[1] >= 0 && t[1] < o.orientedDepth(w, d), o + " in depth");
          seen.add(t[0] + "," + t[1]);
        }
      }
      assertEquals(w * d, seen.size(), o + " is a bijection");
    }
  }

  @Test
  void sideFollowsTheTransformOfAnEdgeCell() {
    int w = 3;
    int d = 2;
    for (Orientation o : Orientation.values()) {
      // the north edge cell (1,0) moves to the edge the transformed side names
      int[] north = o.transform(1, 0, w, d);
      Side s = o.side(Side.NORTH);
      int ow = o.orientedWidth(w, d);
      int od = o.orientedDepth(w, d);
      switch (s) {
        case NORTH -> assertEquals(0, north[1], o.name());
        case SOUTH -> assertEquals(od - 1, north[1], o.name());
        case EAST -> assertEquals(ow - 1, north[0], o.name());
        case WEST -> assertEquals(0, north[0], o.name());
      }
    }
    assertEquals(Side.EAST, Orientation.R90.side(Side.NORTH));
    assertEquals(Side.SOUTH, Orientation.M0.side(Side.NORTH));
    assertEquals(Side.WEST, Orientation.M90.side(Side.NORTH));
  }

  @Test
  void orientationLookupAndAxes() {
    assertEquals(Orientation.R270, Orientation.of(-1, false));
    assertEquals(Orientation.M90, Orientation.of(5, true));
    assertTrue(Orientation.R90.swapsAxes());
    assertFalse(Orientation.M180.swapsAxes());
    assertEquals(3, Orientation.R90.orientedWidth(2, 3));
    assertEquals(2, Orientation.R90.orientedDepth(2, 3));
    assertEquals(2, Orientation.R0.orientedWidth(2, 3));
    assertEquals(3, Orientation.R0.orientedDepth(2, 3));
  }
}
