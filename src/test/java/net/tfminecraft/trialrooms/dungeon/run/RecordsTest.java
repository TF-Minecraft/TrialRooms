package net.tfminecraft.trialrooms.dungeon.run;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.trialrooms.dungeon.run.Records.Clear;

public class RecordsTest {

  @TempDir
  Path temp;

  static Clear clear(long seconds, String... names) {
    return new Clear(seconds * 1000, List.of(names), 7);
  }

  @Test
  void eachDungeonDifficultyAndPartySizeHasItsOwnBoard() {
    File f = temp.resolve("records.yml").toFile();
    Records r = new Records(f, Logger.getLogger("t"));
    assertEquals(1, r.add("delve", "hard", 1, clear(300, "Ann")));
    assertEquals(1, r.add("delve", "hard", 1, clear(250, "Bo")), "faster: first");
    assertEquals(3, r.add("delve", "hard", 1, clear(300, "Cy")), "a tie goes after the earlier clear");
    assertEquals(1, r.add("delve", "hard", 2, clear(400, "Ann", "Bo")), "a party has its own board");
    assertEquals(1, r.add("delve", null, 1, clear(100, "Di")), "no difficulty");
    assertEquals(List.of("Bo", "Ann", "Cy"), r.top("delve", "hard", 1).stream().map(c -> c.names().getFirst()).toList());
    assertEquals(List.of(), r.top("delve", "normal", 1));
    var best = r.best("delve", "hard");
    assertEquals(List.of(1, 2), List.copyOf(best.keySet()));
    assertEquals(250_000, best.get(1).millis());
    assertEquals(100_000, r.best("delve", null).get(1).millis());
    // a board keeps ten
    for (int i = 0; i < 8; i++) {
      r.add("delve", "hard", 1, clear(500 + i, "X" + i));
    }
    assertEquals(10, r.top("delve", "hard", 1).size());
    assertEquals(0, r.add("delve", "hard", 1, clear(900, "Slow")), "too slow to stay");
    assertEquals(4, r.add("delve", "hard", 1, clear(450, "Mid")), "after Bo, Ann and Cy");
    assertEquals(10, r.top("delve", "hard", 1).size(), "the slowest falls off");
    // kept on disk
    Records again = new Records(f, Logger.getLogger("t"));
    assertEquals(r.top("delve", "hard", 1), again.top("delve", "hard", 1));
    assertEquals(r.top("delve", "hard", 2), again.top("delve", "hard", 2));
    assertEquals("4:10", Records.time(250_000));
    assertEquals("0:05", Records.time(5_999));
  }

  @Test
  void brokenRecordsAreSkippedAndAFailedSaveIsLogged() throws Exception {
    File f = temp.resolve("records.yml").toFile();
    Files.writeString(f.toPath(), """
        boards:
          delve/-/1:
            - {millis: 5000, names: [Ann], at: 1}
            - {millis: x, names: [Bo], at: 2}
            - {millis: 4000, names: Cy, at: 3}
            - {millis: 3000, names: [Di]}
          delve/-/2:
            - {millis: x}
        """);
    Records r = new Records(f, Logger.getLogger("t"));
    assertEquals(List.of(new Clear(5000, List.of("Ann"), 1)), r.top("delve", null, 1));
    assertEquals(List.of(1), List.copyOf(r.best("delve", null).keySet()), "a board with nothing left shows no best");
    // a board with a broken name is skipped; a file that can't be read is kept aside, never overwritten
    File named = temp.resolve("named.yml").toFile();
    Files.writeString(named.toPath(), "boards:\n  delve/hard/solo:\n    - {millis: 1, names: [A], at: 1}\n");
    assertEquals(java.util.Map.of(), new Records(named, Logger.getLogger("t")).best("delve", "hard"));
    File broken = temp.resolve("broken.yml").toFile();
    Files.writeString(broken.toPath(), "boards: [unclosed");
    Records fresh = new Records(broken, Logger.getLogger("t"));
    assertTrue(Files.exists(temp.resolve("broken.yml.broken")), "kept aside");
    assertTrue(fresh.top("delve", null, 1).isEmpty());
    File stuck = temp.resolve("stuck.yml").toFile();
    Files.writeString(stuck.toPath(), "boards: [unclosed");
    Files.createDirectories(temp.resolve("stuck.yml.broken"));
    Files.writeString(temp.resolve("stuck.yml.broken").resolve("in-the-way"), "x");
    assertTrue(new Records(stuck, Logger.getLogger("t")).top("delve", null, 1).isEmpty(),
        "the copy can't be kept aside either: still nothing read, and it says so");
    assertTrue(new Records(temp.resolve("none.yml").toFile(), Logger.getLogger("t")).top("delve", null, 1).isEmpty());
    // the file can't be written: the board still holds the clear
    Path blocked = temp.resolve("blocked");
    Files.createDirectories(blocked.resolve("records.yml.tmp"));
    Records b = new Records(blocked.resolve("records.yml").toFile(), Logger.getLogger("t"));
    assertEquals(1, b.add("delve", null, 1, clear(10, "Ann")));
    assertEquals(1, b.top("delve", null, 1).size());
  }
}
