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
    assertEquals(11, kit.catalog().templates().size());
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
      assertEquals(rooms.get(c.roomB()).node().parent(), c.roomA());
      assertTrue(reached.contains(c.roomA()), "parent placed before child");
      reached.add(c.roomB());
    }
    assertEquals(rooms.size(), reached.size());
    long bosses = rooms.stream().filter(r -> r.node().type() == RoomType.BOSS).count();
    assertEquals(1, bosses);
  }
}
