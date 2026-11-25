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
    assertEquals("The Ice Golem", kit.fights().boss("ice_golem").name());
    assertEquals(23, kit.fights().roster().size());
    assertEquals("vaaramereios", kit.fights().bossFor(null, 4, 99).id(), "Mythic always ends with the general");
    for (int tier = 1; tier <= 4; tier++) {
      var fights = kit.fights().atTier(tier);
      for (var role : List.of(net.tfminecraft.trialrooms.dungeon.fight.Role.GRUNT, net.tfminecraft.trialrooms.dungeon.fight.Role.RANGED,
          net.tfminecraft.trialrooms.dungeon.fight.Role.SWARM, net.tfminecraft.trialrooms.dungeon.fight.Role.ELITE)) {
        assertFalse(fights.ofRole(role).isEmpty(), "tier " + tier + " has a " + role);
      }
      assertFalse(fights.packs().isEmpty(), "tier " + tier + " has packs");
    }
    var recipes = RecipeLoader.load(new File(GreyboxKitTest.RES, "recipes.yml"), problems);
    assertEquals(List.of(), problems);
    assertEquals("seithr", recipes.get("delve_seithr").kit());
    assertEquals(0.4, recipes.get("delve_seithr").rules().eventChance());
    assertEquals(2, recipes.get("delve_seithr").rules().maxEvents());
    assertEquals(java.util.Map.of(net.tfminecraft.trialrooms.dungeon.event.EventKind.KINDLE, 2,
        net.tfminecraft.trialrooms.dungeon.event.EventKind.ENDURE, 1,
        net.tfminecraft.trialrooms.dungeon.event.EventKind.RUNES, 2), kit.events().weights());
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
