package net.tfminecraft.trialrooms.dungeon.load;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.tfminecraft.trialrooms.dungeon.grid.Side;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadTest {
  @TempDir Path temp;

  static final String ROOM = "type: combat\nfootprint: 1x1\ndoorways:\n  - {cell: '0,0', side: north, width: 4}\n";

  private Path kit(String kitYml) throws IOException {
    Path dir = temp.resolve("k");
    Files.createDirectories(dir.resolve("rooms"));
    if (kitYml != null) {
      Files.writeString(dir.resolve("kit.yml"), kitYml);
    }
    return dir;
  }

  private void room(Path dir, String id, String yml, boolean nbt) throws IOException {
    Files.writeString(dir.resolve("rooms/" + id + ".yml"), yml);
    if (nbt) {
      Files.write(dir.resolve("rooms/" + id + ".nbt"), new byte[] {1});
    }
  }

  @Test
  void loadsRoomsAndSkipsBrokenOnes() throws IOException {
    Path dir = kit("cell-size: 32\ncell-height: 24\ndoor-height: 5\nseal-block: minecraft:stone\n");
    room(dir, "good", ROOM + "weight: 3\nmax-per-run: 2\nmin-depth: 1\nmax-depth: 4\ntags: [x]\n"
        + "markers:\n  - {kind: spawn, at: '1,1,1', tag: melee, facing: south}\n  - {kind: chest, at: '2,1,2'}\n", true);
    room(dir, "nonbt", ROOM, false);
    room(dir, "badtype", "type: dragon\n", true);
    Files.writeString(dir.resolve("rooms/notes.txt"), "ignored");
    List<String> problems = new ArrayList<>();
    DungeonKit kit = KitLoader.load(dir.toFile(), problems);
    assertEquals(2, problems.size(), problems.toString());
    assertEquals("k", kit.id());
    assertEquals(5, kit.doorHeight());
    assertEquals("minecraft:stone", kit.sealBlock());
    var t = kit.catalog().templates().getFirst();
    assertEquals(RoomType.COMBAT, t.type());
    assertEquals(3, t.weight());
    assertEquals(Side.SOUTH, t.markers().getFirst().facing());
    assertEquals("melee", t.markers().getFirst().tag());
    assertNull(t.markers().get(1).facing());
    assertTrue(kit.structures().get("good").getName().endsWith("good.nbt"));
  }

  @Test
  void rejectsBrokenKits() throws IOException {
    List<String> problems = new ArrayList<>();
    assertNull(KitLoader.load(kit(null).toFile(), problems));
    assertTrue(problems.getFirst().contains("missing kit.yml"));
    Path noRooms = kit("cell-size: 32\n");
    Files.delete(noRooms.resolve("rooms"));
    assertNull(KitLoader.load(noRooms.toFile(), problems));
    assertTrue(problems.getLast().contains("no usable rooms"));
    for (String bad : List.of("door-height: 1\n", "door-height: 24\n", "cell-size: 7\n")) {
      Path dir = kit(bad);
      room(dir, "a", ROOM, true);
      assertNull(KitLoader.load(dir.toFile(), problems), bad);
    }
  }

  @Test
  void parsesNumberLists() {
    assertArrayEquals(new int[] {1, 2}, KitLoader.ints(" 1x2 ", "x", 2, "f"));
    assertThrows(IllegalArgumentException.class, () -> KitLoader.ints(null, ",", 3, "f"));
    assertThrows(IllegalArgumentException.class, () -> KitLoader.ints("1,2", ",", 3, "f"));
    assertThrows(IllegalArgumentException.class, () -> KitLoader.ints("1,a,2", ",", 3, "f"));
  }

  @Test
  void loadsRecipesAndSkipsBrokenOnes() throws IOException {
    File f = temp.resolve("recipes.yml").toFile();
    Files.writeString(f.toPath(), """
        plain: 7
        good:
          kit: stone
          trunk: 2-3
          trunk-pool: {combat: 2}
          branches: 1
          branch-length: 1-2
          branch-pool: {corridor: 1}
          rest-before-boss: false
          vault: false
        defaults:
          trunk-pool: {combat: 1}
        badrange:
          trunk: two
        badtype:
          trunk-pool: {dragon: 1}
        """);
    List<String> problems = new ArrayList<>();
    var recipes = RecipeLoader.load(f, problems);
    assertEquals(3, problems.size(), problems.toString());
    var good = recipes.get("good");
    assertEquals("stone", good.kit());
    assertEquals(2, good.recipe().trunkMin());
    assertEquals(3, good.recipe().trunkMax());
    assertFalse(good.recipe().vault());
    var defaults = recipes.get("defaults");
    assertEquals("greybox", defaults.kit());
    assertEquals(3, defaults.recipe().trunkMin());
    assertEquals(20, defaults.recipe().maxAttempts());
    assertArrayEquals(new int[] {4, 4}, RecipeLoader.range(" 4 ", "x"));
    assertThrows(IllegalArgumentException.class, () -> RecipeLoader.range("1-b", "x"));
  }
}
