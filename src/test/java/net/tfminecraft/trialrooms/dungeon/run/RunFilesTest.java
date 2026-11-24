package net.tfminecraft.trialrooms.dungeon.run;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class RunFilesTest {
  @TempDir Path temp;
  ServerMock server;
  final Logger log = Logger.getLogger("runfiles-test");

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  @Test
  void returnPointsArePerCharacterAndSurviveReloads() {
    World w = server.addSimpleWorld("main");
    RunFiles f = new RunFiles(temp, log);
    UUID p = UUID.randomUUID();
    f.saveReturn(p, "alpha", new Location(w, 1.5, 70, -3.5, 90f, 10f));
    f.saveReturn(p, "beta.two", new Location(w, 9, 64, 9));
    Location a = new RunFiles(temp, log).returnPoint(p, "alpha");
    assertEquals(1.5, a.getX());
    assertEquals(-3.5, a.getZ());
    assertEquals(90f, a.getYaw());
    assertEquals(9, f.returnPoint(p, "beta.two").getX());
    assertNull(f.returnPoint(p, "gamma"));
    assertNull(f.returnPoint(UUID.randomUUID(), null));
    f.clearReturn(p, "alpha");
    assertNull(f.returnPoint(p, "alpha"));
    assertTrue(Files.exists(temp.resolve("returns/" + p + ".yml")));
    f.clearReturn(p, "beta.two");
    assertFalse(Files.exists(temp.resolve("returns/" + p + ".yml")));
  }

  @Test
  void activeRunsLeftAtStartBecomeRefunds() throws Exception {
    RunFiles f = new RunFiles(temp, log);
    assertEquals(0, f.recoverActive());
    assertEquals(java.util.Set.of(), f.activeRuns(), "no folder yet");
    UUID leader = UUID.randomUUID();
    UUID run = UUID.randomUUID();
    f.saveActive(run, leader, "m.loot.trial_key");
    f.saveActive(UUID.randomUUID(), UUID.randomUUID(), "");
    UUID cleared = UUID.randomUUID();
    f.saveActive(cleared, leader, "x");
    f.clearActive(cleared);
    Files.writeString(temp.resolve("active/broken.yml"), "key: y\n");
    assertEquals(2, f.activeRuns().size(), "a stray file is skipped");
    assertTrue(f.activeRuns().contains(run));
    assertEquals(3, f.recoverActive());
    assertEquals(List.of("m.loot.trial_key"), f.takeRefunds(leader));
    assertEquals(List.of(), f.takeRefunds(leader));
    f.addRefund(leader, "a");
    f.addRefund(leader, "b");
    assertEquals(List.of("a", "b"), f.takeRefunds(leader));
  }

  @Test
  void logsRunsAndSurvivesUnwritableFolders() throws Exception {
    RunFiles f = new RunFiles(temp, log);
    f.logRun("{\"a\":1}");
    f.logRun("{\"a\":2}");
    assertEquals(2, Files.readAllLines(temp.resolve("runs.log")).size());
    // a file where the folder should be: writes fail and are only logged
    Path blocked = temp.resolve("blocked");
    Files.writeString(blocked, "x");
    RunFiles bad = new RunFiles(blocked, log);
    assertDoesNotThrow(() -> bad.logRun("{}"));
    assertDoesNotThrow(() -> bad.addRefund(UUID.randomUUID(), "k"));
  }

  @Test
  void gatesAreStoredAndFound() throws Exception {
    World w = server.addSimpleWorld("main");
    Path file = temp.resolve("gates.yml");
    Files.writeString(file, "gates:\n- {world: main, x: 1, y: 2, z: 3, recipe: r}\n- {world: main, x: oops}\n");
    GateStore g = new GateStore(file.toFile(), log);
    Block at = w.getBlockAt(1, 2, 3);
    assertEquals("r", g.at(at).recipe());
    Block other = w.getBlockAt(5, 5, 5);
    assertNull(g.at(other));
    g.add(other, "s");
    assertEquals(2, new GateStore(file.toFile(), log).all().size());
    assertTrue(g.remove(other));
    assertFalse(g.remove(other));
    assertEquals(1, new GateStore(file.toFile(), log).all().size());
    // saving into a folder that is a file only logs
    Path blocked = temp.resolve("nofolder");
    Files.writeString(blocked, "x");
    GateStore bad = new GateStore(blocked.resolve("gates.yml").toFile(), log);
    assertDoesNotThrow(() -> bad.add(at, "r"));
    Block mocked = mock(Block.class);
    when(mocked.getWorld()).thenReturn(w);
    assertNull(g.at(mocked));
  }
}
