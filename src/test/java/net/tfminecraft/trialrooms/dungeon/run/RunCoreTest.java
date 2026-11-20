package net.tfminecraft.trialrooms.dungeon.run;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonGenerator;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonRecipe;
import net.tfminecraft.trialrooms.dungeon.generator.GreyboxKitTest;
import net.tfminecraft.trialrooms.dungeon.grid.Side;
import net.tfminecraft.trialrooms.dungeon.template.Doorway;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

public class RunCoreTest {

  /** start, combat, rest, boss, vault in a line. */
  public static DungeonLayout line() {
    DungeonRecipe r = new DungeonRecipe("t", 1, 1, Map.of(RoomType.COMBAT, 1), true, true, 0, 0, 1, 1, null, 20,
        20000);
    return DungeonGenerator.generate(GreyboxKitTest.kit().catalog(), r, 3);
  }

  static int index(DungeonLayout l, RoomType t) {
    for (int i = 0; i < l.rooms().size(); i++) {
      if (l.rooms().get(i).node().type() == t) {
        return i;
      }
    }
    return -1;
  }

  @Test
  void membersGoDownReviveBleedOutAndLeave() {
    DungeonRun run = new DungeonRun(UUID.randomUUID(), "t", line(), 1, 5);
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    run.add(a, "ca");
    run.add(b, "cb");
    assertEquals("ca", run.member(a).characterId());
    assertEquals(2, run.playing());
    assertFalse(run.wiped());
    assertTrue(run.down(a, 100));
    assertFalse(run.down(a, 100));
    assertFalse(run.down(UUID.randomUUID(), 1));
    assertEquals(100, run.member(a).downedAtMs());
    assertFalse(run.addRevive(a, 500, 1000));
    assertEquals(500, run.member(a).reviveMs());
    run.resetRevive(a);
    run.resetRevive(UUID.randomUUID());
    assertEquals(0, run.member(a).reviveMs());
    assertFalse(run.addRevive(a, 500, 1000));
    assertTrue(run.addRevive(a, 500, 1000));
    assertFalse(run.addRevive(a, 500, 1000));
    assertFalse(run.addRevive(UUID.randomUUID(), 500, 1000));
    // bleed-outs spend the one life, then put players out
    run.down(a, 1);
    assertTrue(run.bleedOut(a));
    assertEquals(0, run.lives());
    run.down(a, 2);
    assertFalse(run.bleedOut(a));
    assertEquals(DungeonRun.Status.OUT, run.member(a).status());
    assertFalse(run.bleedOut(a));
    assertFalse(run.bleedOut(UUID.randomUUID()));
    assertEquals(3, run.downs());
    assertEquals(2, run.bleedOuts());
    // offline members don't play
    run.offline(b, 9);
    assertFalse(run.member(b).online());
    assertEquals(9, run.member(b).offlineSinceMs());
    assertTrue(run.wiped());
    run.online(b);
    run.online(UUID.randomUUID());
    run.offline(UUID.randomUUID(), 1);
    assertFalse(run.wiped());
    run.leave(b);
    run.leave(UUID.randomUUID());
    assertFalse(run.bleedOut(b));
    assertEquals(1, run.present().size());
    assertEquals(2, run.members().size());
    assertEquals(5, run.startedMs());
  }

  @Test
  void aDownedPlayerWithLivesLeftIsNotAWipe() {
    DungeonRun run = new DungeonRun(UUID.randomUUID(), "t", line(), 1, 0);
    UUID a = UUID.randomUUID();
    run.add(a, "c");
    run.down(a, 0);
    assertFalse(run.wiped(), "a solo player who is down still has a life to wake with");
    run.offline(a, 0);
    assertTrue(run.wiped(), "nobody online to come back");
    UUID out = UUID.randomUUID();
    run.add(out, "out");
    run.leave(out);
    UUID b = UUID.randomUUID();
    run.add(b, "b");
    run.down(b, 0);
    assertFalse(run.wiped(), "a second, online downed member can still wake");
    DungeonRun noLives = new DungeonRun(UUID.randomUUID(), "t", line(), 0, 0);
    noLives.add(a, "c");
    noLives.down(a, 0);
    assertTrue(noLives.wiped());
  }

  @Test
  void roomsArmCountDownSealAndClear() {
    DungeonLayout l = line();
    DungeonRun run = new DungeonRun(UUID.randomUUID(), "t", l, 3, 0);
    int fight = index(l, RoomType.COMBAT);
    int boss = index(l, RoomType.BOSS);
    assertEquals(DungeonRun.Room.CLEARED, run.room(0));
    assertEquals(DungeonRun.Room.IDLE, run.room(fight));
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    run.add(a, "a");
    run.add(b, "b");
    assertEquals(List.of(), run.stepRooms(2, 3));
    run.member(a).room(fight);
    assertEquals(List.of(new DungeonRun.Countdown(fight, 2)), run.stepRooms(2, 3));
    assertEquals(List.of(new DungeonRun.Countdown(fight, 1)), run.stepRooms(2, 3));
    // the only member inside walks out: the countdown stops
    run.member(a).room(0);
    assertEquals(List.of(), run.stepRooms(2, 3));
    assertEquals(DungeonRun.Room.IDLE, run.room(fight));
    run.member(a).room(fight);
    run.stepRooms(2, 3);
    run.stepRooms(2, 3);
    assertEquals(List.of(new DungeonRun.Seal(fight)), run.stepRooms(2, 3));
    assertEquals(DungeonRun.Room.ACTIVE, run.room(fight));
    assertEquals(List.of(), run.stepRooms(2, 3));
    run.clearRoom(fight);
    assertEquals(1, run.roomsCleared());
    // a boss waits for everyone playing, then counts down
    run.member(a).room(boss);
    assertEquals(List.of(), run.stepRooms(2, 3));
    run.member(b).room(boss);
    assertEquals(List.of(new DungeonRun.Countdown(boss, 3)), run.stepRooms(2, 3));
    run.clearRoom(boss);
    assertTrue(run.bossDefeated());
    // a solo player seals at once
    DungeonRun solo = new DungeonRun(UUID.randomUUID(), "t", l, 3, 0);
    solo.add(a, "a").room(fight);
    assertEquals(List.of(new DungeonRun.Seal(fight)), solo.stepRooms(4, 5));
    assertEquals("t", solo.recipeId());
    assertSame(l, solo.layout());
    solo.checkpoint(2);
    assertEquals(2, solo.checkpoint());
    solo.state(DungeonRun.State.COMPLETE);
    assertEquals(DungeonRun.State.COMPLETE, solo.state());
    assertNotNull(solo.id());
    assertTrue(DungeonRun.isFight(RoomType.ELITE));
    assertFalse(DungeonRun.isFight(RoomType.REST));
  }

  @Test
  void geometryFindsRoomsDoorsMarkersAndDirections() {
    DungeonLayout l = line();
    RunGeometry g = new RunGeometry(l, 32, 4, 1000, 64, 0);
    assertSame(l, g.layout());
    assertEquals(64, g.floorY());
    assertEquals(0, g.roomAt(1016, 16));
    assertEquals(-1, g.roomAt(-5000, 16));
    for (Side s : Side.values()) {
      Doorway d = new Doorway(0, 0, s, 4);
      assertEquals(16, g.doorBlocks(d).size());
      double[] in = g.insideDoor(d);
      assertEquals(0, g.roomAt(in[0], in[2]), s.name());
    }
    double[] entry = g.entry(index(l, RoomType.COMBAT));
    assertEquals(index(l, RoomType.COMBAT), g.roomAt(entry[0], entry[2]));
    double[] start = g.entry(0);
    assertEquals(0, g.roomAt(start[0], start[2]));
    int fight = index(l, RoomType.COMBAT);
    List<double[]> spawns = g.markers(fight, "spawn");
    assertEquals(spawns.size(), g.markerTags(fight, "spawn").size());
    assertEquals(List.of(), g.markerTags(fight, "nothing"));
    assertFalse(spawns.isEmpty());
    for (double[] p : spawns) {
      assertEquals(fight, g.roomAt(p[0], p[2]));
    }
    double[] c = g.centre(fight);
    assertEquals(fight, g.roomAt(c[0], c[2]));
    assertTrue(g.safelyInside(fight, c[0], c[2], 2));
    assertFalse(g.safelyInside(0, c[0], c[2], 2));
    // next to each outer wall of the start room is not safely inside; next to an inner join is
    double[] s0 = g.centre(0);
    for (double[] d : new double[][] {{-15, 0}, {14, 0}, {0, -15}, {0, 14}}) {
      double x = s0[0] + d[0];
      double z = s0[2] + d[1];
      assertEquals(0, g.roomAt(x, z));
      assertFalse(g.safelyInside(0, x, z, 2), d[0] + "," + d[1]);
    }
    // facing south (yaw 0)
    assertEquals("5 blocks ahead", RunGeometry.direction(0, 0, 0f, 0, 5));
    assertEquals("1 block behind you", RunGeometry.direction(0, 0, 0f, 0, -1));
    assertEquals("3 blocks to your right", RunGeometry.direction(0, 0, 0f, -3, 0));
    assertEquals("3 blocks to your left", RunGeometry.direction(0, 0, 0f, 3, 0));
  }

  @Test
  void theJoinInsideAWideRoomIsSafe() {
    var wide = new net.tfminecraft.trialrooms.dungeon.template.RoomTemplate("s", RoomType.START, 2, 1,
        List.of(new Doorway(0, 0, Side.NORTH, 4)), List.of(), 1, 0, 0, 0, java.util.Set.of());
    var boss = new net.tfminecraft.trialrooms.dungeon.template.RoomTemplate("b", RoomType.BOSS, 1, 1,
        List.of(new Doorway(0, 0, Side.SOUTH, 4)), List.of(), 1, 0, 0, 0, java.util.Set.of());
    var cat = new net.tfminecraft.trialrooms.dungeon.template.RoomCatalog(32, 24, List.of(wide, boss));
    var r = new DungeonRecipe("w", 0, 0, null, false, false, 0, 0, 1, 1, null, 5, 1000);
    DungeonLayout l = DungeonGenerator.generate(cat, r, 1);
    RunGeometry g = new RunGeometry(l, 32, 4, 0, 64, 0);
    var start = l.rooms().getFirst();
    // the boundary between the wide room's two cells
    double x;
    double z;
    if (start.widthCells() == 2) {
      x = (start.originX() + 1) * 32 + 0.5;
      z = start.originZ() * 32 + 16;
    } else {
      x = start.originX() * 32 + 16;
      z = (start.originZ() + 1) * 32 + 0.5;
    }
    assertTrue(g.safelyInside(0, x, z, 2));
  }

  @Test
  void settingsAndRulesParseAndCheck() {
    RunSettings d = RunSettings.defaults();
    assertTrue(d.blocks("/home"));
    assertTrue(d.blocks("/essentials:HOME there"));
    assertTrue(d.blocks("tpa Bob"));
    assertFalse(d.blocks("/tr dungeon leave"));
    YamlConfiguration y = new YamlConfiguration();
    y.set("max-runs", 0);
    y.set("blocked-commands", List.of("Fly"));
    y.set("harmful-auras", List.of("Nasty"));
    y.set("helpful-auras", List.of("Nice"));
    y.set("queue-hold-seconds", 3);
    RunSettings s = RunSettings.from(y);
    assertEquals(1, s.maxRuns());
    assertEquals(java.util.Set.of("nasty"), s.harmfulAuras());
    assertEquals(java.util.Set.of("nice"), s.helpfulAuras());
    assertEquals(10, s.holdSeconds());
    assertTrue(RunSettings.from(new YamlConfiguration()).harmfulAuras().contains("slowed"));
    assertTrue(s.blocks("/fly"));
    assertEquals(d.bleedOutSeconds(), s.bleedOutSeconds());
    assertEquals(d.blockedCommands(), RunSettings.from(new YamlConfiguration()).blockedCommands());

    RunRules r = new RunRules(1, 3, null, 3, 1, 0.5);
    assertEquals("", r.keyPath());
    assertEquals(1.0, r.healthFactor(1));
    assertEquals(2.0, r.healthFactor(3));
    assertThrows(IllegalArgumentException.class, () -> new RunRules(0, 3, "", 3, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> new RunRules(2, 1, "", 3, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> new RunRules(1, 1, "", -1, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> new RunRules(1, 1, "", 1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new RunRules(1, 1, "", 1, 1, -1));
    assertThrows(IllegalArgumentException.class, () -> new RunRules(1, 1, "", 1, 1, 0, 0, ""));
    assertEquals("", new RunRules(1, 1, "", 1, 1, 0, 2, null).boss());
    assertEquals("warden", new RunRules(1, 1, "", 1, 1, 0, 2, " warden ").boss());
    assertEquals(1, r.tier());
  }

  @Test
  void actionBarsKeepWarningsOnTop() {
    org.mockbukkit.mockbukkit.ServerMock server = org.mockbukkit.mockbukkit.MockBukkit.mock();
    try {
      var p = server.addPlayer();
      Cues cues = new Cues();
      assertTrue(cues.send(p, Cues.Level.WARNING, "move", 0, 1000));
      assertFalse(cues.send(p, Cues.Level.INFO, "info", 500, 1000), "a warning holds the bar");
      assertTrue(cues.send(p, Cues.Level.WARNING, "again", 600, 1000), "the same level may replace it");
      assertTrue(cues.send(p, Cues.Level.INFO, "info", 1700, 1000), "once the hold runs out");
      assertTrue(cues.send(p, Cues.Level.DOWNED, "down", 1800, 1000), "a higher level goes over a lower one");
    } finally {
      org.mockbukkit.mockbukkit.MockBukkit.unmock();
    }
  }
}
