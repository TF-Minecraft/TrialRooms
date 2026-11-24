package net.tfminecraft.trialrooms.dungeon.run;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FindStoreTest {

  @TempDir Path temp;
  UUID ann = UUID.randomUUID();
  UUID run = UUID.randomUUID();
  RunRewards.Find ring = new RunRewards.Find(RunRewards.Moment.BOSS, 0, "Rimebound Ring", 52, 5, true,
      List.of(new byte[] {1, 2}), List.of(), true);
  RunRewards.Find spoils = new RunRewards.Find(null, 0.25, "Spoils: 4 Ignitium", 10, 0, false, List.of(),
      List.of(new byte[] {3}), true);

  FindStore store() {
    return new FindStore(temp.resolve("finds"), Logger.getLogger("finds-test"));
  }

  @Test
  void aPouchIsKeptAndCleared() {
    FindStore s = store();
    assertNull(s.pouch(ann));
    s.clearHold(ann); // nothing to clear
    s.hold(ann, new FindStore.Pouch(run, "c1", "delve", 2, 1.5, List.of(ring), List.of(spoils)));
    FindStore.Pouch p = s.pouch(ann);
    assertEquals(run, p.run());
    assertEquals("c1", p.character());
    assertEquals("delve", p.recipe());
    assertEquals(2, p.tier());
    assertEquals(1.5, p.boost());
    RunRewards.Find back = p.carried().get(0);
    assertEquals(RunRewards.Moment.BOSS, back.moment());
    assertEquals("Rimebound Ring", back.label());
    assertEquals(52, back.value());
    assertEquals(5, back.marks());
    assertTrue(back.counts() && back.rolled());
    assertArrayEquals(new byte[] {1, 2}, back.gear().get(0));
    RunRewards.Find sp = p.banked().get(0);
    assertNull(sp.moment());
    assertEquals(0.25, sp.hours());
    assertArrayEquals(new byte[] {3}, sp.spoils().get(0));
    s.clearHold(ann);
    assertNull(s.pouch(ann));
  }

  @Test
  void pouchesACrashLeftComeHomeAsPending() throws Exception {
    FindStore s = store();
    s.hold(ann, new FindStore.Pouch(run, "c1", "delve", 2, 1, List.of(ring), List.of(spoils)));
    UUID bo = UUID.randomUUID();
    s.hold(bo, new FindStore.Pouch(run, "c2", "delve", 2, 1, List.of(), List.of()));
    s.addPending(UUID.randomUUID(), new FindStore.Pending("c9", "delve", run, 1, 1, false, "x", List.of(), List.of()));
    Files.writeString(temp.resolve("finds").resolve("notes.yml"), "a: 1");
    UUID cy = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    s.hold(cy, new FindStore.Pouch(other, "c3", "delve", 2, 1, List.of(ring), List.of()));
    UUID dee = UUID.randomUUID();
    s.hold(dee, new FindStore.Pouch(other, "c4", "delve", 2, 1, List.of(), List.of()));
    assertEquals(4, s.recover(java.util.Set.of(run)), "four pouches; a file that isn't a player's is skipped");
    assertEquals(List.of(), s.takePending(cy, "c3"), "a run the stop didn't cut off: not paid");
    assertEquals("finds of a run that ended without settling them", s.losses(cy).get(0).why());
    assertNull(s.pouch(cy));
    assertEquals(List.of(), s.losses(dee), "an empty one is nothing to log");
    assertNull(s.pouch(ann));
    assertEquals(List.of(), s.takePending(bo, "c2"), "an empty pouch owes nothing");
    assertEquals(List.of(), s.takePending(ann, "c2"), "another character");
    assertEquals(1, s.pending(ann, "c1").size(), "read, not taken");
    s.addPending(ann, new FindStore.Pending("c2", "delve", run, 1, 1, false, "x", List.of(ring), List.of()));
    List<FindStore.Pending> owed = s.takePending(ann, "c1");
    assertEquals(1, owed.size());
    assertEquals("a stop cut your run short", owed.get(0).why());
    assertEquals(2, owed.get(0).finds().size(), "banked and carried");
    assertFalse(owed.get(0).restore());
    assertEquals(List.of(), s.takePending(ann, "c1"), "taken once");
    assertEquals(1, s.takePending(ann, "c2").size(), "the other character's stays until taken");
    assertEquals(0, new FindStore(temp.resolve("none"), Logger.getLogger("t")).recover(java.util.Set.of()), "no folder yet");
  }

  @Test
  void lossesAreLoggedAndGivenBackOnce() {
    FindStore s = store();
    assertEquals(List.of(), s.losses(ann));
    int id = s.logLoss(ann, new FindStore.Loss(0, 5, "c1", run, "delve", "mythic", 4, "fell", "with 2/20 health",
        List.of(ring, spoils), List.of(new byte[] {9}), false));
    assertEquals(1, id);
    FindStore.Loss l = s.losses(ann).get(0);
    assertEquals(5, l.at());
    assertEquals("mythic", l.difficulty());
    assertEquals(4, l.tier());
    assertEquals("with 2/20 health", l.detail());
    assertEquals(62, l.value());
    assertFalse(l.restored());
    assertNull(s.restore(ann, 7));
    assertEquals(l.why(), s.restore(ann, id).why());
    assertTrue(s.losses(ann).get(0).restored());
    assertNull(s.restore(ann, id), "once");
    FindStore.Pending back = s.takePending(ann, "c1").get(0);
    assertTrue(back.restore());
    assertEquals(2, back.finds().size());
    assertArrayEquals(new byte[] {9}, back.gear().get(0));
    for (int i = 0; i < FindStore.KEEP_LOSSES + 3; i++) {
      s.logLoss(ann, new FindStore.Loss(0, i, "c1", run, "delve", "", 1, "fell", "", List.of(ring), List.of(), false));
    }
    List<FindStore.Loss> kept = s.losses(ann);
    assertEquals(FindStore.KEEP_LOSSES, kept.size(), "the oldest go");
    assertEquals(FindStore.KEEP_LOSSES + 4, kept.get(kept.size() - 1).id());
  }

  @Test
  void aFileThatCantBeReadIsKeptAsideNotOverwritten() throws Exception {
    FindStore s = store();
    Files.createDirectories(temp.resolve("finds"));
    Path f = temp.resolve("finds").resolve(ann + ".yml");
    Files.writeString(f, "pouch: [unclosed");
    assertNull(s.pouch(ann));
    assertFalse(Files.exists(f), "moved aside");
    try (var aside = Files.list(temp.resolve("finds"))) {
      assertTrue(aside.anyMatch(p -> p.getFileName().toString().startsWith(ann + ".yml.broken-")));
    }
    s.hold(ann, new FindStore.Pouch(run, "c1", "delve", 1, 1, List.of(ring), List.of()));
    assertNotNull(s.pouch(ann), "a new file for what comes next");
    // a pouch whose run can't be read is skipped at startup, not thrown
    UUID bo = UUID.randomUUID();
    Files.writeString(temp.resolve("finds").resolve(bo + ".yml"), "pouch: {run: nonsense}");
    assertEquals(1, s.recover(java.util.Set.of(run)));
  }

  @Test
  void aFileThatCantBeWrittenIsLoggedNotThrown() throws Exception {
    Path blocker = temp.resolve("blocked");
    Files.writeString(blocker, "a file where the folder should be");
    FindStore s = new FindStore(blocker, Logger.getLogger("finds-test"));
    s.hold(ann, new FindStore.Pouch(run, "c1", "delve", 1, 1, List.of(ring), List.of()));
    assertNull(s.pouch(ann));
  }
}
