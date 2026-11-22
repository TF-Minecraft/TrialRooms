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
  static final String MARKED = ROOM + "markers:\n  - {kind: spawn, at: '1,1,1'}\n";

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
    room(dir, "badwidth", "type: combat\ndoorways:\n  - {cell: '0,0', side: north, width: 3}\n", true);
    room(dir, "badyaml", "type: [combat\n", true);
    Files.writeString(dir.resolve("rooms/notes.txt"), "ignored");
    List<String> problems = new ArrayList<>();
    DungeonKit kit = KitLoader.load(dir.toFile(), problems);
    assertEquals(5, problems.size(), problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.contains("no roster or mobs section")), problems.toString());
    assertEquals(1, kit.catalog().templates().size());
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
    assertNull(kit.fights());
    // a kit with one enemy per marker, and one with a roster
    Path mobs = temp.resolve("mobs");
    java.nio.file.Files.createDirectories(mobs.resolve("rooms"));
    java.nio.file.Files.writeString(mobs.resolve("kit.yml"), "mobs:\n  melee: foot\n");
    room(mobs, "good", MARKED, true);
    List<String> none = new ArrayList<>();
    DungeonKit simple = KitLoader.load(mobs.toFile(), none);
    assertEquals(java.util.Map.of("melee", "foot"), simple.mobs());
    assertEquals(List.of(), none);
    Path roster = temp.resolve("roster");
    java.nio.file.Files.createDirectories(roster.resolve("rooms"));
    java.nio.file.Files.writeString(roster.resolve("kit.yml"), "roster:\n  foot: {mob: f}\n");
    room(roster, "good", MARKED, true);
    DungeonKit fights = KitLoader.load(roster.toFile(), none);
    assertNotNull(fights.fights());
    assertEquals(java.util.Map.of(), fights.mobs());
    assertEquals(List.of(), none);
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
    for (String bad : List.of("door-height: 1\n", "door-height: 24\n", "cell-size: 7\n", "cell-size: [\n")) {
      Path dir = kit(bad);
      room(dir, "a", ROOM, true);
      assertNull(KitLoader.load(dir.toFile(), problems), bad);
    }
  }

  @Test
  void loadsShapesFloorsAndRequiresMarkers() throws IOException {
    Path dir = kit("max-rise: 12\n");
    room(dir, "ell", "type: combat\nfootprint: 2x2\ncells: ['0,0', '1,0', '0,1']\n"
        + "doorways:\n  - {cell: '0,0', side: north, width: 4, floor: 6, height: 5}\n"
        + "markers:\n  - {kind: spawn, at: '8,7,8'}\n", true);
    room(dir, "outside", "type: corridor\nfootprint: 1x1\ncells: ['1,0']\n"
        + "doorways:\n  - {cell: '0,0', side: north}\n", true);
    for (String c : List.of("-1,0", "0,-1", "0,1")) {
      room(dir, "out" + c.replace(",", "_").replace("-", "m"), "type: corridor\nfootprint: 1x1\ncells: ['" + c
          + "']\ndoorways:\n  - {cell: '0,0', side: north}\n", true);
    }
    room(dir, "notalist", "type: corridor\ncells: '0,0'\ndoorways:\n  - {cell: '0,0', side: north}\n", true);
    room(dir, "toohigh", "type: corridor\ndoorways:\n  - {cell: '0,0', side: north, floor: 20}\n", true);
    room(dir, "nospawn", "type: elite\ndoorways:\n  - {cell: '0,0', side: north}\n", true);
    room(dir, "noboss", "type: boss\ndoorways:\n  - {cell: '0,0', side: north}\n"
        + "markers:\n  - {kind: spawn, at: '8,1,8'}\n", true);
    room(dir, "norest", "type: rest\ndoorways:\n  - {cell: '0,0', side: north}\n", true);
    room(dir, "hall", "type: corridor\ndoorways:\n  - {cell: '0,0', side: north}\n", true);
    List<String> problems = new ArrayList<>();
    DungeonKit kit = KitLoader.load(dir.toFile(), problems);
    assertEquals(12, kit.catalog().maxRise());
    assertEquals(List.of("ell", "hall"), kit.catalog().templates().stream().map(t -> t.id()).sorted().toList());
    var ell = kit.catalog().templates().stream().filter(t -> t.id().equals("ell")).findFirst().orElseThrow();
    assertEquals(3, ell.cellList().size());
    assertEquals(6, ell.doorways().getFirst().floor());
    assertEquals(5, ell.doorways().getFirst().height());
    String all = problems.toString();
    assertTrue(all.contains("outside: cell 1,0 is outside the footprint"), all);
    assertTrue(all.contains("nospawn: a elite room needs a spawn marker"), all);
    assertTrue(all.contains("noboss: a boss room needs a spawn marker tagged boss"), all);
    assertTrue(all.contains("norest: a rest room needs a rest marker"), all);
    assertTrue(all.contains("notalist: cells must be a list"), all);
    assertTrue(all.contains("toohigh: doorway"), all);
    List<String> rise = new ArrayList<>();
    assertNull(KitLoader.load(kit("max-rise: 61\n").toFile(), rise));
    assertTrue(rise.getLast().contains("max-rise must be at most 60"), rise.toString());
  }

  @Test
  void parsesNumberLists() {
    assertArrayEquals(new int[] {1, 2}, KitLoader.ints(" 1x2 ", "x", 2, "f"));
    assertThrows(IllegalArgumentException.class, () -> KitLoader.ints(null, ",", 3, "f"));
    assertThrows(IllegalArgumentException.class, () -> KitLoader.ints("1,2", ",", 3, "f"));
    assertThrows(IllegalArgumentException.class, () -> KitLoader.ints("1,a,2", ",", 3, "f"));
  }

  @Test
  void loadsDifficulties() throws IOException {
    File f = temp.resolve("recipes.yml").toFile();
    Files.writeString(f.toPath(), """
        laddered:
          trunk-pool: {combat: 1}
          difficulties:
            normal: {name: Normal, tier: 1, advice: "for own gear"}
            hard: {name: Hard, tier: 2, health: 1.5, damage: 1.25, needs: normal}
        wrongorder:
          trunk-pool: {combat: 1}
          difficulties:
            hard: {tier: 2, needs: normal}
            normal: {tier: 1}
        notasection:
          trunk-pool: {combat: 1}
          difficulties:
            normal: 3
        badtier:
          trunk-pool: {combat: 1}
          difficulties:
            normal: {tier: 0}
        toomany:
          trunk-pool: {combat: 1}
          difficulties: {a: {}, b: {}, c: {}, d: {}, e: {}, f: {}}
        """);
    List<String> problems = new ArrayList<>();
    var recipes = RecipeLoader.load(f, problems);
    assertEquals(4, problems.size(), problems.toString());
    assertTrue(problems.stream().anyMatch(p -> p.contains("at most 5 difficulties")));
    assertTrue(problems.stream().anyMatch(p -> p.contains("needs normal, which is not listed before it")));
    var entry = recipes.get("laddered");
    assertEquals(2, entry.difficulties().size());
    var hard = entry.difficulty("HARD");
    assertEquals(new net.tfminecraft.trialrooms.dungeon.run.Difficulty("hard", "Hard", 2, 1.5, 1.25, "normal", ""), hard);
    assertEquals("for own gear", entry.difficulty("normal").advice());
    assertNull(entry.difficulty("nightmare"));
    assertThrows(IllegalArgumentException.class, () -> new net.tfminecraft.trialrooms.dungeon.run.Difficulty("x", "X", 1, 0, 1, null, ""));
    assertThrows(IllegalArgumentException.class, () -> new net.tfminecraft.trialrooms.dungeon.run.Difficulty("x", "X", 1, 1, 0, null, ""));
    assertThrows(IllegalArgumentException.class, () -> new net.tfminecraft.trialrooms.dungeon.run.Difficulty("x", "X", 1, Double.POSITIVE_INFINITY, 1, null, ""));
    assertThrows(IllegalArgumentException.class, () -> new net.tfminecraft.trialrooms.dungeon.run.Difficulty("x", "X", 1, 1, Double.POSITIVE_INFINITY, null, ""));
    var rules = entry.rules();
    assertEquals(3, rules.withTier(3).tier());
    assertEquals(rules.maxPlayers(), rules.withTier(3).maxPlayers());
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
    // unreadable and missing files are problems, not silent empty configs
    Files.writeString(f.toPath(), "good: [\n");
    List<String> more = new ArrayList<>();
    assertTrue(RecipeLoader.load(f, more).isEmpty());
    assertTrue(RecipeLoader.load(temp.resolve("none.yml").toFile(), more).isEmpty());
    assertEquals(2, more.size(), more.toString());
  }
}
