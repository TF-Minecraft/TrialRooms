package net.tfminecraft.trialrooms.dungeon.generator;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout.Connection;
import net.tfminecraft.trialrooms.dungeon.load.DungeonKit;
import net.tfminecraft.trialrooms.dungeon.load.KitLoader;
import net.tfminecraft.trialrooms.dungeon.load.RecipeLoader;
import net.tfminecraft.trialrooms.dungeon.template.Doorway;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;
import org.junit.jupiter.api.Test;

/** The shipped greybox kit and recipe generate valid dungeons for many seeds. */
public class GreyboxKitTest {

  static final File RES = new File("src/main/resources/dungeons");

  public static DungeonKit kit() {
    List<String> problems = new ArrayList<>();
    DungeonKit kit = KitLoader.load(new File(RES, "kits/greybox"), problems);
    assertEquals(List.of(), problems);
    return kit;
  }

  @Test
  void bundledKitAndRecipeGenerateValidLayouts() {
    DungeonKit kit = kit();
    assertEquals(14, kit.catalog().templates().size());
    assertNotNull(kit.fights());
    assertEquals("The Bone Warden", kit.fights().boss("").name());
    List<String> problems = new ArrayList<>();
    var recipes = RecipeLoader.load(new File(RES, "recipes.yml"), problems);
    assertEquals(List.of(), problems);
    DungeonRecipe recipe = recipes.get("delve_greybox").recipe();
    int retries = 0;
    for (long seed = 0; seed < 300; seed++) {
      DungeonLayout layout = DungeonGenerator.generate(kit.catalog(), recipe, seed);
      retries += layout.attempt();
      assertValid(layout);
      assertEquals(
          LayoutMap.render(layout), LayoutMap.render(DungeonGenerator.generate(kit.catalog(), recipe, seed)));
    }
    assertTrue(retries < 30, "too many retries: " + retries);
  }

  /** A shaped room's structure has no blocks at all in the cells it doesn't fill, so it never overwrites a neighbour. */
  @Test
  void shapedRoomsLeaveTheirEmptyCellsUntouched() throws Exception {
    DungeonKit kit = kit();
    int shaped = 0;
    for (var t : kit.catalog().templates()) {
      List<int[]> positions = blockPositions(kit.structures().get(t.id()));
      assertFalse(positions.isEmpty(), t.id());
      for (int[] pos : positions) {
        assertTrue(t.occupies(pos[0] / 32, pos[2] / 32), t.id() + " has a block at " + pos[0] + "," + pos[2]);
      }
      if (!t.cells().isEmpty()) {
        shaped++;
      }
    }
    assertTrue(shaped > 0);
  }

  /** The block positions of a vanilla structure file (gzipped NBT). */
  @SuppressWarnings("unchecked")
  static List<int[]> blockPositions(File nbt) throws java.io.IOException {
    try (var in = new java.io.DataInputStream(new java.util.zip.GZIPInputStream(new java.io.FileInputStream(nbt)))) {
      in.readByte();
      in.readUTF();
      Map<String, Object> root = (Map<String, Object>) tag(in, 10);
      List<int[]> out = new ArrayList<>();
      for (Object b : (List<Object>) root.get("blocks")) {
        List<Object> pos = (List<Object>) ((Map<String, Object>) b).get("pos");
        out.add(new int[] {(Integer) pos.get(0), (Integer) pos.get(1), (Integer) pos.get(2)});
      }
      return out;
    }
  }

  private static Object tag(java.io.DataInputStream in, int type) throws java.io.IOException {
    switch (type) {
      case 1: return in.readByte();
      case 2: return in.readShort();
      case 3: return in.readInt();
      case 4: return in.readLong();
      case 5: return in.readFloat();
      case 6: return in.readDouble();
      case 7: { byte[] a = new byte[in.readInt()]; in.readFully(a); return a; }
      case 8: return in.readUTF();
      case 9: {
        int inner = in.readByte();
        int n = in.readInt();
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
          list.add(tag(in, inner));
        }
        return list;
      }
      case 10: {
        Map<String, Object> map = new HashMap<>();
        for (int t = in.readByte(); t != 0; t = in.readByte()) {
          String name = in.readUTF();
          map.put(name, tag(in, t));
        }
        return map;
      }
      case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) { a[i] = in.readInt(); } return a; }
      default: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) { a[i] = in.readLong(); } return a; }
    }
  }

  public static void assertValid(DungeonLayout layout) {
    List<PlacedRoom> rooms = layout.rooms();
    assertEquals(RoomType.START, rooms.getFirst().node().type());
    Map<Long, Integer> cells = new HashMap<>();
    for (int i = 0; i < rooms.size(); i++) {
      for (int[] c : rooms.get(i).cells()) {
        assertNull(cells.put(((long) c[0] << 32) ^ (c[1] & 0xffffffffL), i), "overlap at " + c[0] + "," + c[1]);
      }
    }
    assertEquals(rooms.size() - 1, layout.connections().size());
    Set<Integer> reached = new HashSet<>(Set.of(0));
    for (Connection c : layout.connections()) {
      Doorway a = rooms.get(c.roomA()).doorways().get(c.doorA());
      Doorway b = rooms.get(c.roomB()).doorways().get(c.doorB());
      assertEquals(a.side().opposite(), b.side());
      assertEquals(a.cellX() + a.side().dx(), b.cellX());
      assertEquals(a.cellZ() + a.side().dz(), b.cellZ());
      assertEquals(a.width(), b.width());
      assertEquals(rooms.get(c.roomA()).elevation() + a.floor(), rooms.get(c.roomB()).elevation() + b.floor(),
          "joined doorways meet at the same height");
      assertEquals(rooms.get(c.roomB()).node().parent(), c.roomA());
      assertTrue(reached.contains(c.roomA()), "parent placed before child");
      reached.add(c.roomB());
    }
    assertEquals(rooms.size(), reached.size());
    long bosses = rooms.stream().filter(r -> r.node().type() == RoomType.BOSS).count();
    assertEquals(1, bosses);
  }
}
