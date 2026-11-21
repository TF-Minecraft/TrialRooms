package net.tfminecraft.trialrooms.dungeon.generator;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import net.tfminecraft.trialrooms.dungeon.load.DungeonKit;
import net.tfminecraft.trialrooms.dungeon.load.KitLoader;
import net.tfminecraft.trialrooms.dungeon.load.RecipeLoader;
import org.junit.jupiter.api.Test;

/** The shipped Seithr kit and recipe load cleanly and generate valid dungeons for many seeds. */
class SeithrKitTest {

  @Test
  void bundledSeithrKitGeneratesValidLayouts() throws Exception {
    List<String> problems = new ArrayList<>();
    DungeonKit kit = KitLoader.load(new File(GreyboxKitTest.RES, "kits/seithr"), problems);
    assertEquals(List.of(), problems);
    assertEquals(36, kit.catalog().templates().size());
    assertEquals(16, kit.catalog().cellSize());
    assertEquals("The Elite Ice Golem", kit.fights().boss("ice_golem").name());
    assertEquals(10, kit.fights().roster().size());
    var recipes = RecipeLoader.load(new File(GreyboxKitTest.RES, "recipes.yml"), problems);
    assertEquals(List.of(), problems);
    assertEquals("seithr", recipes.get("delve_seithr").kit());
    DungeonRecipe recipe = recipes.get("delve_seithr").recipe();
    int retries = 0;
    boolean raised = false;
    for (long seed = 0; seed < 300; seed++) {
      DungeonLayout layout = DungeonGenerator.generate(kit.catalog(), recipe, seed);
      retries += layout.attempt();
      GreyboxKitTest.assertValid(layout);
      raised |= layout.rooms().stream().anyMatch(r -> r.elevation() != 0);
    }
    assertTrue(retries < 300, "more than one retry per layout on average: " + retries);
    assertTrue(raised, "some layouts change level");
    // shaped rooms leave their empty cells untouched, and small rooms bring smaller fights
    for (var t : kit.catalog().templates()) {
      for (int[] pos : GreyboxKitTest.blockPositions(kit.structures().get(t.id()))) {
        assertTrue(t.occupies(pos[0] / 16, pos[2] / 16), t.id());
      }
    }
    assertEquals(0.5, kit.catalog().templates().stream().filter(t -> t.id().equals("guard_post")).findFirst()
        .orElseThrow().threat());
  }
}
