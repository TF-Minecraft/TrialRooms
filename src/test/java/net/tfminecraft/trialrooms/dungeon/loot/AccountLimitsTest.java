package net.tfminecraft.trialrooms.dungeon.loot;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class AccountLimitsTest {

  @TempDir
  Path temp;

  @Test
  void eachAccountHasSoManyCosmeticRunsOfEachDungeonAWeek() {
    File f = temp.resolve("accounts.yml").toFile();
    AccountLimits l = new AccountLimits(f, Logger.getLogger("t"));
    UUID ann = UUID.randomUUID();
    assertTrue(l.cosmetics(ann, "delve", "r1", "2026-W48", 2));
    assertTrue(l.cosmetics(ann, "delve", "r1", "2026-W48", 2), "the same run, the same answer");
    assertTrue(l.cosmetics(ann, "delve", "r2", "2026-W48", 2));
    assertFalse(l.cosmetics(ann, "delve", "r3", "2026-W48", 2), "the third run of the week rolls none");
    assertFalse(l.cosmetics(ann, "delve", "r3", "2026-W48", 2));
    assertTrue(l.cosmetics(ann, "warband", "r4", "2026-W48", 2), "another dungeon has its own runs");
    assertTrue(l.cosmetics(UUID.randomUUID(), "delve", "r5", "2026-W48", 2), "another player too");
    // kept on disk
    AccountLimits again = new AccountLimits(f, Logger.getLogger("t"));
    assertFalse(again.cosmetics(ann, "delve", "r6", "2026-W48", 2));
    assertTrue(again.cosmetics(ann, "delve", "r7", "2026-W49", 2), "a new week starts over");
  }

  @Test
  void brokenAccountsAreSkippedAndAFailedSaveIsLogged() throws Exception {
    File f = temp.resolve("accounts.yml").toFile();
    Files.writeString(f.toPath(), "not-a-uuid: {week: x}\n" + UUID.randomUUID() + ": {week: '2026-W48'}\n");
    AccountLimits l = new AccountLimits(f, Logger.getLogger("t"));
    assertTrue(l.cosmetics(UUID.randomUUID(), "delve", "r1", "2026-W48", 1));
    Path blocked = temp.resolve("blocked");
    Files.createDirectories(blocked.resolve("accounts.yml.tmp"));
    AccountLimits b = new AccountLimits(blocked.resolve("accounts.yml").toFile(), Logger.getLogger("t"));
    UUID ann = UUID.randomUUID();
    assertTrue(b.cosmetics(ann, "delve", "r1", "2026-W48", 1));
    assertFalse(b.cosmetics(ann, "delve", "r2", "2026-W48", 1), "still counted in memory");
  }
}
