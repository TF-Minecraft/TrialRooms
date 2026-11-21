package net.tfminecraft.trialrooms.dungeon.run;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.tfminecraft.trialrooms.dungeon.event.EventKind;
import net.tfminecraft.trialrooms.dungeon.event.EventKit;
import net.tfminecraft.trialrooms.dungeon.load.DungeonKit;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;

/** Room events in a run, on the fight test's server: braziers, holding out, rune stones. */
class EventsTest {
  @TempDir Path temp;
  FightsTest h;

  @BeforeEach
  void setUp() {
    h = new FightsTest();
    h.temp = temp;
    h.setUp();
  }

  @AfterEach
  void tearDown() {
    h.tearDown();
  }

  /** Every plain fight room gets this kit's events. */
  void events(EventKit k) {
    DungeonKit b = h.kit;
    h.kit = new DungeonKit(b.id(), b.catalog(), b.structures(), b.doorHeight(), b.sealBlock(), b.doorBlock(), Map.of(),
        b.fights(), k);
    h.rules = new RunRules(1, 3, "", 1, 1, 0.5, 1, "warden", 0, 1, 5);
  }

  boolean click(PlayerMock p, int[] at) {
    Block b = mock(Block.class);
    when(b.getX()).thenReturn(at[0]);
    when(b.getY()).thenReturn(at[1]);
    when(b.getZ()).thenReturn(at[2]);
    return h.runs.propClick(p, b);
  }

  Location beside(int[] prop) {
    return new Location(h.dungeon, prop[0] + 0.5, prop[1], prop[2] + 1.5);
  }

  void killAll(Fights.Encounter enc) {
    new ArrayList<>(enc.alive).forEach(h::kill);
  }

  @Test
  void coldBraziersKeepEnemiesComingUntilEveryOneBurns() {
    events(new EventKit(Map.of(EventKind.KINDLE, 1), 2, 1, 60, 4, 4, 3, 2, 8));
    PlayerMock a = h.player("Ann");
    int fight = h.room(RoomType.COMBAT);
    RunManager.ActiveRun ar = h.enter(a, fight);
    assertTrue(h.said(a, "braziers in this room are cold"));
    assertEquals(EventKind.KINDLE, ar.event(fight));
    Events.RoomEvent ev = ar.events.get(fight);
    assertEquals(2, ev.props.size());
    verify(h.block, atLeast(2)).setBlockData(any(), eq(false));
    Fights.Encounter enc = ar.encounters.get(fight);
    assertFalse(enc.alive.isEmpty(), "the room's own fight runs too");
    // the fight's enemies fall, but the room stays shut and more come
    killAll(enc);
    h.ticks(1);
    assertEquals(DungeonRun.Room.ACTIVE, ar.run.room(fight));
    int before = h.spawned.size();
    h.now.addAndGet(3000);
    h.ticks(1);
    assertTrue(h.spawned.size() > before, "a group comes");
    int[] p0 = ev.props.get(0);
    int[] p1 = ev.props.get(1);
    // lighting needs the player beside the brazier
    a.teleport(beside(p1));
    assertTrue(click(a, p0));
    h.ticks(1);
    assertTrue(ev.lighting.isEmpty(), "too far away");
    a.teleport(beside(p0));
    click(a, p0);
    click(a, p0); // a second click while lighting changes nothing
    h.ticks(1);
    assertFalse(ev.lit[0]);
    assertFalse(a.getBossBars().isEmpty(), "a bar fills while lighting");
    h.ticks(1);
    assertTrue(ev.lit[0]);
    assertTrue(h.said(a, "lights a brazier. §7(1 of 2)"));
    click(a, p0);
    assertTrue(ev.lighting.isEmpty(), "a burning brazier needs no more");
    // someone gone, someone lit it first: the lighting stops
    ev.lighting.put(UUID.randomUUID(), 1);
    a.teleport(beside(p1));
    click(a, p1);
    ev.lit[1] = true;
    h.ticks(1);
    assertTrue(ev.lighting.isEmpty());
    ev.lit[1] = false;
    click(a, p1);
    h.ticks(2);
    assertTrue(ev.done);
    assertTrue(h.said(a, "The last brazier blazes"));
    before = h.spawned.size();
    h.now.addAndGet(3000);
    h.ticks(1);
    killAll(enc);
    h.ticks(1);
    assertEquals(before, h.spawned.size(), "no more groups once they burn");
    assertEquals(DungeonRun.Room.CLEARED, ar.run.room(fight));
    assertTrue(ar.events.isEmpty());
    assertFalse(click(a, p0), "the room is done: a plain block again");
  }

  @Test
  void lightingStopsWhenThePlayerGoesDown() {
    events(new EventKit(Map.of(EventKind.KINDLE, 1), 1, 5, 60, 4, 4, 3, 2, 8));
    PlayerMock a = h.player("Ann");
    int fight = h.room(RoomType.COMBAT);
    RunManager.ActiveRun ar = h.enter(a, fight);
    Events.RoomEvent ev = ar.events.get(fight);
    a.teleport(beside(ev.props.get(0)));
    click(a, ev.props.get(0));
    h.ticks(1);
    assertFalse(ev.lighting.isEmpty());
    assertTrue(h.runs.down(a));
    assertTrue(click(a, ev.props.get(0)), "a downed player's click is used up, and does nothing");
    h.ticks(1);
    assertTrue(ev.lighting.isEmpty());
    // a stranger to the run clicking a prop: not ours to handle
    PlayerMock stranger = h.player("Cy");
    assertFalse(click(stranger, ev.props.get(0)));
    assertTrue(h.runs.leave(a), "leaving a brazier room: no event bar to take down");
  }

  /** Every fight room cut down to its first spawn point. */
  void oneSpawnPoint() {
    var cat = h.kit.catalog();
    List<net.tfminecraft.trialrooms.dungeon.template.RoomTemplate> ts = new ArrayList<>();
    for (var t : cat.templates()) {
      List<net.tfminecraft.trialrooms.dungeon.template.Marker> ms = new ArrayList<>();
      boolean one = false;
      for (var m : t.markers()) {
        if (!m.kind().equals("spawn") || t.type() != RoomType.COMBAT || !one) {
          ms.add(m);
          one |= m.kind().equals("spawn");
        }
      }
      ts.add(new net.tfminecraft.trialrooms.dungeon.template.RoomTemplate(t.id(), t.type(), t.widthCells(),
          t.depthCells(), t.doorways(), ms, t.weight(), t.minDepth(), t.maxDepth(), t.maxPerRun(), t.tags(),
          t.cells(), t.threat()));
    }
    var small = new net.tfminecraft.trialrooms.dungeon.template.RoomCatalog(cat.cellSize(), 24, 24, ts);
    DungeonKit b = h.kit;
    h.kit = new DungeonKit(b.id(), small, b.structures(), b.doorHeight(), b.sealBlock(), b.doorBlock(), Map.of(),
        b.fights(), b.events());
    h.layout = net.tfminecraft.trialrooms.dungeon.generator.DungeonGenerator.generate(small,
        new net.tfminecraft.trialrooms.dungeon.generator.DungeonRecipe("delve", 1, 1, Map.of(RoomType.COMBAT, 1),
            true, true, 0, 0, 1, 1, null, 20, 20000), 4);
    h.geo = new RunGeometry(h.layout, 32, 4, 0, 64, 0);
    h.placed = java.util.concurrent.CompletableFuture.completedFuture(h.at(h.geo.entry(0)));
  }

  @Test
  void aRoomWithOneSpawnPointGetsAPlainFightForRunesAndBraziersOnItsPoint() {
    events(new EventKit(Map.of(EventKind.RUNES, 1), 3, 3, 60, 4, 4, 3, 2, 8));
    oneSpawnPoint();
    PlayerMock a = h.player("Ann");
    int fight = h.room(RoomType.COMBAT);
    RunManager.ActiveRun ar = h.enter(a, fight);
    assertTrue(ar.events.isEmpty(), "no rune stones");
    assertTrue(ar.encounters.get(fight).plan.enemies() > 0, "a plain fight instead");
    h.runs.end(ar, false, null);
    events(new EventKit(Map.of(EventKind.KINDLE, 1), 3, 3, 60, 4, 4, 3, 2, 8));
    oneSpawnPoint();
    ar = h.enter(a, fight);
    Events.RoomEvent ev = ar.events.get(fight);
    assertEquals(1, ev.props.size());
    for (Entity e : ar.encounters.get(fight).alive) {
      assertEquals(ev.props.getFirst()[1] + 1, e.getLocation().getY(), "the fight's enemies stand on the brazier");
    }
  }

  @Test
  void holdingOutKeepsTheDoorsShutForItsTime() {
    events(new EventKit(Map.of(EventKind.ENDURE, 1), 3, 3, 10, 4, 4, 3, 2, 8));
    PlayerMock a = h.player("Ann");
    int fight = h.room(RoomType.COMBAT);
    RunManager.ActiveRun ar = h.enter(a, fight);
    assertTrue(h.said(a, "Hold out for 10 seconds"));
    Fights.Encounter enc = ar.encounters.get(fight);
    int first = enc.alive.size();
    assertTrue(first > 0, "the first group comes at once");
    assertEquals(1, a.getBossBars().size());
    killAll(enc);
    h.ticks(1);
    assertEquals(DungeonRun.Room.ACTIVE, ar.run.room(fight), "killing them all is not enough");
    h.now.addAndGet(3000);
    h.ticks(1);
    assertFalse(enc.alive.isEmpty(), "another group");
    h.now.addAndGet(7000);
    h.ticks(1);
    assertTrue(h.said(a, "You held the room"));
    verify(h.mobs, atLeastOnce()).remove(any());
    assertTrue(a.getBossBars().isEmpty());
    h.ticks(1);
    assertEquals(DungeonRun.Room.CLEARED, ar.run.room(fight));
  }

  @Test
  void aRunThatEndsMidHoldTakesItsBarDown() {
    events(new EventKit(Map.of(EventKind.ENDURE, 1), 3, 3, 10, 4, 4, 3, 2, 8));
    PlayerMock a = h.player("Ann");
    RunManager.ActiveRun ar = h.enter(a, h.room(RoomType.COMBAT));
    h.ticks(1);
    assertEquals(1, a.getBossBars().size());
    h.runs.end(ar, false, null);
    assertTrue(a.getBossBars().isEmpty());
  }

  @Test
  void leavingMidHoldTakesTheBarDown() {
    events(new EventKit(Map.of(EventKind.ENDURE, 1), 3, 3, 10, 4, 4, 3, 2, 8));
    PlayerMock a = h.player("Ann");
    h.enter(a, h.room(RoomType.COMBAT));
    h.ticks(1);
    assertEquals(1, a.getBossBars().size());
    assertTrue(h.runs.leave(a));
    assertTrue(a.getBossBars().isEmpty());
  }

  @Test
  void runeStonesAnswerTheirOrder() {
    events(new EventKit(Map.of(EventKind.RUNES, 1), 3, 3, 60, 3, 2, 3, 2, 1));
    PlayerMock a = h.player("Ann");
    int fight = h.room(RoomType.COMBAT);
    RunManager.ActiveRun ar = h.enter(a, fight);
    assertTrue(h.said(a, "Rune stones wake"));
    Events.RoomEvent ev = ar.events.get(fight);
    Fights.Encounter enc = ar.encounters.get(fight);
    assertTrue(enc.alive.isEmpty(), "no fight of its own");
    assertEquals(3, ev.props.size());
    assertEquals(2, ev.runes.length());
    int right = ev.runes.stone(0);
    int wrong = (right + 1) % 3;
    // touches while the order shows do nothing
    assertTrue(click(a, ev.props.get(right)));
    assertEquals(0, ev.runes.progress());
    h.ticks(4 + 7);
    assertEquals(-1, ev.showStep);
    // a mistake: a group, a pause, the order again
    click(a, ev.props.get(wrong));
    assertTrue(h.said(a, "The stones go dark"));
    assertEquals(1, enc.alive.size());
    assertEquals(4, ev.pause);
    click(a, ev.props.get(right));
    assertEquals(0, ev.runes.progress(), "not during the pause");
    h.ticks(4 + 7);
    // another mistake with the room at its fill: no more enemies
    click(a, ev.props.get(wrong));
    assertEquals(1, enc.alive.size());
    h.ticks(4 + 7);
    // left alone, the stones show their order again
    h.now.addAndGet(Events.REPLAY_SECONDS * 1000L);
    h.ticks(1);
    assertTrue(h.said(a, "light their order again"));
    h.ticks(7);
    // a player outside the room can't touch them
    a.teleport(h.at(h.middle(0)));
    assertTrue(click(a, ev.props.get(right)));
    assertEquals(0, ev.runes.progress());
    a.teleport(h.at(h.middle(fight)));
    h.ticks(1);
    click(a, ev.props.get(right));
    assertEquals(1, ev.runes.progress());
    assertEquals(right, ev.flash);
    click(a, ev.props.get(right)); // a held click or a second player: the same stone again is ignored
    assertEquals(1, ev.runes.progress());
    assertFalse(h.said(a, "The stones go dark"));
    h.ticks(1);
    assertEquals(-1, ev.flash);
    click(a, ev.props.get(ev.runes.stone(1)));
    assertTrue(ev.done);
    assertTrue(h.said(a, "The stones answer together"));
    killAll(enc);
    h.ticks(1);
    assertEquals(DungeonRun.Room.CLEARED, ar.run.room(fight));
  }

  @Test
  void eventsLeaveOneSpawnPointFreeForTheirEnemies() {
    events(new EventKit(Map.of(EventKind.RUNES, 1), 3, 3, 60, 99, 2, 3, 2, 8));
    PlayerMock a = h.player("Ann");
    int fight = h.room(RoomType.COMBAT);
    RunManager.ActiveRun ar = h.enter(a, fight);
    Events.RoomEvent ev = ar.events.get(fight);
    List<double[]> markers = h.geo.markers(fight, "spawn");
    assertEquals(markers.size() - 1, ev.props.size());
    h.ticks(4 + 7);
    int wrong = (ev.runes.stone(0) + 1) % ev.props.size();
    click(a, ev.props.get(wrong));
    List<Entity> came = new ArrayList<>(ar.encounters.get(fight).alive);
    assertFalse(came.isEmpty());
    double[] free = ev.spawnAt.getFirst();
    for (Entity e : came) {
      assertEquals(free[1], e.getLocation().getY(), "at the free point, not on a stone");
      assertEquals(free[0], e.getLocation().getX());
    }
    // a group whose enemies fail to spawn brings nobody
    h.ticks(4 + 7);
    doReturn(null).when(h.mobs).spawn(anyString(), any(), anyInt());
    click(a, ev.props.get(wrong));
    assertEquals(came.size(), ar.encounters.get(fight).alive.size());
    // a block that is no prop is not the event's
    assertFalse(click(a, new int[] {ev.props.get(0)[0], -5, 0}));
    assertFalse(click(a, new int[] {ev.props.get(0)[0], ev.props.get(0)[1], -999}));
    assertFalse(click(a, new int[] {-999, ev.props.get(0)[1], ev.props.get(0)[2]}));
    // a solved room's stones stay lit, and a touch after does nothing
    ev.done = true;
    assertTrue(click(a, ev.props.get(0)));
    h.ticks(1);
  }
}
