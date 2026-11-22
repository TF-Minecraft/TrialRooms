package net.tfminecraft.trialrooms.dungeon.locker;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LockerCoreTest {

  @TempDir Path temp;
  final UUID who = UUID.randomUUID();
  final Logger log = Logger.getLogger("locker-test");

  static byte[] b(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void loadoutsHoldItemsTheLockerOwns() {
    Locker l = new Locker();
    assertEquals(3, Locker.LOADOUTS);
    l.put("sword", b("S"));
    l.put("helm", b("H"));
    assertFalse(l.assign(0, 39, "nothing"), "only items in the locker");
    assertFalse(l.assign(0, 20, "helm"), "only loadout slots");
    assertTrue(l.assign(0, 39, "helm"));
    assertTrue(l.assign(0, 0, "sword"));
    assertTrue(l.assign(1, 1, "sword"), "one item may sit in several loadouts");
    assertTrue(l.assign(0, 2, "sword"), "but in one slot of a loadout");
    assertEquals(Map.of(39, "helm", 2, "sword"), l.loadout(0));
    assertTrue(l.unassign(0, 2));
    assertFalse(l.unassign(0, 2));
    assertArrayEquals(b("S"), l.item("sword"));
    assertEquals(List.of("sword", "helm"), List.copyOf(l.items().keySet()));

    l.wear(0, Map.of(39, b("own helm")), 7);
    assertEquals(0, l.worn());
    assertEquals(7, l.wornAt());
    assertEquals(Map.of(39, "helm"), l.wornSlots());
    assertArrayEquals(b("own helm"), l.displaced().get(39));
    assertThrows(IllegalStateException.class, () -> l.wear(1, Map.of(), 8));
    assertFalse(l.assign(1, 2, "helm"), "no changes while worn");
    assertFalse(l.unassign(1, 1));
    assertFalse(l.remove("helm"), "a worn item stays");
    assertTrue(l.remove("sword"), "an item not worn can go");
    assertTrue(l.loadout(1).isEmpty(), "and leaves every loadout");
    assertFalse(l.remove("sword"));
    l.takeOff();
    assertEquals(-1, l.worn());
    assertEquals(-1, l.wornAt());
    assertTrue(l.wornSlots().isEmpty() && l.displaced().isEmpty());
    assertThrows(IllegalArgumentException.class, () -> l.wear(2, Map.of(), 9), "an empty loadout");

    l.owe(b("x"));
    assertEquals(1, l.owed().size());
    l.clearOwed();
    assertTrue(l.owed().isEmpty());
  }

  @Test
  void lockersSurviveTheTripToDiskAndBack() throws Exception {
    LockerStore store = new LockerStore(temp, log);
    assertEquals(0, store.read(who, "c1").items().size(), "a new locker");
    Locker l = new Locker();
    l.put("a", b("A"));
    l.put("b", b("B"));
    l.assign(0, 39, "a");
    l.assign(2, 8, "b");
    l.owe(b("left over"));
    assertTrue(store.write(who, "c1", l));
    assertEquals(1, l.revision());
    l.wear(0, Map.of(39, b("own")), 2);
    assertTrue(store.write(who, "c1", l));
    Locker back = store.read(who, "c1");
    assertEquals(2, back.wornAt());
    assertEquals(2, back.revision());
    assertArrayEquals(b("B"), back.item("b"));
    assertEquals(Map.of(8, "b"), back.loadout(2));
    assertEquals(0, back.worn());
    assertEquals(Map.of(39, "a"), back.wornSlots());
    assertArrayEquals(b("own"), back.displaced().get(39));
    assertArrayEquals(b("left over"), back.owed().getFirst());
    assertTrue(Files.exists(store.file(who, "c1").resolveSibling("c1.locker.prev")));
    assertEquals("we_ird_id", LockerStore.safe("we/ird.id"));
  }

  @Test
  void aDamagedFileFallsBackToItsBackupOrIsRefused() throws Exception {
    LockerStore store = new LockerStore(temp, log);
    Locker l = new Locker();
    l.put("a", b("A"));
    store.write(who, "c", l);
    l.put("b", b("B"));
    store.write(who, "c", l);
    Path f = store.file(who, "c");
    Files.writeString(f, Files.readString(f).replace("revision: 2", "revision: 9"));
    Locker back = store.read(who, "c");
    assertEquals(1, back.revision(), "the checksum caught the edit; the backup was read");
    assertNull(back.item("b"));
    Files.writeString(f.resolveSibling("c.locker.prev"), "junk");
    assertThrows(LockerStore.BrokenLocker.class, () -> store.read(who, "c"));
    // only the backup is left: it is still read
    Files.delete(f);
    store.write(who, "d", l);
    Files.move(store.file(who, "d"), store.file(who, "d").resolveSibling("d.locker.prev"));
    assertNotNull(store.read(who, "d").item("b"));
  }

  @Test
  void theFormatIsChecked() {
    assertThrows(IllegalArgumentException.class, () -> LockerStore.parse("nonsense"));
    assertThrows(IllegalArgumentException.class, () -> LockerStore.parse("TRLOCKER1 abc def\nx"));
    assertThrows(IllegalArgumentException.class, () -> LockerStore.parse("OTHER abc\nx"));
    String body = "items: [unclosed";
    assertThrows(IllegalArgumentException.class,
        () -> LockerStore.parse(LockerStore.MAGIC + " " + LockerStore.sha256(body) + "\n" + body));
    String empty = "revision: 4\n";
    Locker l = LockerStore.parse(LockerStore.MAGIC + " " + LockerStore.sha256(empty) + "\n" + empty);
    assertEquals(4, l.revision());
    assertEquals(-1, l.worn());
    assertThrows(IllegalStateException.class, () -> LockerStore.digest("NO-SUCH-HASH", "x"));
  }

  @Test
  void failedWritesChangeNothingAndQuarantineIsKept() throws Exception {
    Files.writeString(temp.resolve("blocked"), "a file where a folder should be");
    LockerStore bad = new LockerStore(temp.resolve("blocked"), log);
    Locker l = new Locker();
    assertFalse(bad.write(who, "c", l));
    assertEquals(0, l.revision());
    assertFalse(bad.quarantine(who, "c", "test", List.of(b("x"))));

    LockerStore store = new LockerStore(temp.resolve("ok"), log);
    assertTrue(store.quarantine(who, "c", "test", List.of(b("x"), b("y"))));
    try (var files = Files.list(temp.resolve("ok/quarantine"))) {
      String text = Files.readString(files.findFirst().orElseThrow());
      assertTrue(text.contains("reason: test") && text.contains("eA=="), text);
    }
  }
}
