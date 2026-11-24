package net.tfminecraft.trialrooms.dungeon.loot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.tfminecraft.trialrooms.dungeon.locker.Locker;
import net.tfminecraft.trialrooms.dungeon.locker.LockerExtras;
import net.tfminecraft.trialrooms.dungeon.locker.LockerService;
import net.tfminecraft.trialrooms.dungeon.run.RunRewards;
import net.tfminecraft.trialrooms.dungeon.run.RunRewards.Moment;

public class RewardsTest {

  static YamlConfiguration shippedYaml() {
    return YamlConfiguration.loadConfiguration(new InputStreamReader(
        RewardsTest.class.getResourceAsStream("/dungeons/loot.yml"), StandardCharsets.UTF_8));
  }

  public static RewardRules shippedRules() {
    List<String> problems = new ArrayList<>();
    RewardRules r = RewardRules.load(shippedYaml(), LootCoreTest.shipped(), problems);
    assertEquals(List.of(), problems);
    return r;
  }

  /** A Random whose doubles come from a list (then repeat the last). */
  static Random doubles(double... ds) {
    return new Random() {
      int i;

      @Override
      public double nextDouble() {
        return ds[Math.min(i++, ds.length - 1)];
      }
    };
  }

  LootKit kit = LootCoreTest.shipped();
  RewardRules rules = shippedRules();
  DungeonLoot loot = mock(DungeonLoot.class);
  LockerService lockers = mock(LockerService.class);
  Locker locker = new Locker();
  Player ann = mock(Player.class);
  AtomicLong now = new AtomicLong(1_790_000_000_000L); // 2026-09-21, a Monday
  UUID run = UUID.randomUUID();
  ItemStack sword = mock(ItemStack.class);
  ItemStack gem = mock(ItemStack.class);

  DungeonRewards rewards(Random rng) {
    return new DungeonRewards(loot, lockers, () -> rules, rng, now::get, ZoneId.of("UTC"));
  }

  @BeforeEach
  void setUp() {
    when(lockers.locker(ann)).thenReturn(locker);
    when(lockers.file(eq(locker), any())).thenReturn("id");
    when(lockers.saveLocker(ann, locker)).thenReturn(true);
    when(sword.effectiveName()).thenReturn(Component.text("Rimebound Sword"));
    when(gem.effectiveName()).thenReturn(Component.text("Glacial Rimestone"));
    when(loot.roll(eq(ann), anyInt(), anyDouble(), any(), any())).thenReturn(new DungeonLoot.Rolled(sword, null));
    when(loot.gem(anyInt(), anyDouble(), any(), any(), any())).thenReturn(new DungeonLoot.Rolled(gem, null));
  }

  @Test
  void theShippedRulesLoad() {
    assertEquals(1.0, rules.chance(Moment.VAULT));
    assertEquals(0.35, rules.chance(Moment.BOSS));
    assertEquals(10, rules.marks(Moment.VAULT));
    assertEquals(3, rules.weeklyRuns());
    assertEquals(60, rules.weeklyMarks());
    assertEquals(Map.of("rare", 30, "epic", 80), rules.prices());
    assertEquals("gemstones.larimar", rules.gemTemplate());
    assertEquals(5, rules.parMarks());
    assertEquals(0.5, rules.parLuck());
    assertThrows(IllegalArgumentException.class,
        () -> new RewardRules(Map.of(), 0, 0, 0, 0, Map.of(), Map.of(), 1, "t", "p", -1, 0));
    assertThrows(IllegalArgumentException.class,
        () -> new RewardRules(Map.of(), 0, 0, 0, 0, Map.of(), Map.of(), 1, "t", "p", 0, Double.POSITIVE_INFINITY));
    assertThrows(IllegalArgumentException.class,
        () -> new RewardRules(Map.of(), 0, 0, 0, 0, Map.of(), Map.of(), 1, "t", "p", 0, -0.5));
    assertEquals(0.0, new RewardRules(Map.of(), 0, 0, 0, 0, Map.of(), Map.of(), 1, "t", "p").chance(Moment.BOSS));
    assertEquals(0, new RewardRules(Map.of(), 0, 0, 0, 0, Map.of(), Map.of(), 1, "t", "p").marks(Moment.BOSS));
  }

  @Test
  void badRewardsAreRefused() throws Exception {
    List<String> problems = new ArrayList<>();
    assertNull(RewardRules.load(new YamlConfiguration(), null, problems));
    assertTrue(problems.isEmpty(), "no loot at all: nothing more to say");
    assertNull(RewardRules.load(new YamlConfiguration(), kit, problems));
    assertTrue(problems.getFirst().contains("no rewards section"));
    YamlConfiguration only = new YamlConfiguration();
    only.loadFromString("rewards: {gems: {share: 0.5}}");
    problems.clear();
    assertNull(RewardRules.load(only, null, problems), "rewards without a loot kit");
    assertTrue(problems.isEmpty());
    for (String bad : List.of("rewards: {prices: {mythic: 5}}", "rewards: {gems: {pool: nope}}",
        "rewards: {chance: {boss: 2}}")) {
      problems.clear();
      YamlConfiguration y = new YamlConfiguration();
      y.loadFromString(bad);
      assertNull(RewardRules.load(y, kit, problems), bad);
      assertEquals(1, problems.size(), bad);
    }
    Map<Moment, Double> ok = Map.of();
    assertThrows(IllegalArgumentException.class, () -> new RewardRules(Map.of(Moment.BOSS, -0.1), 0, 0, 0, 0, Map.of(), Map.of(), 1, "t", "p"));
    assertThrows(IllegalArgumentException.class, () -> new RewardRules(ok, 1.5, 0, 0, 0, Map.of(), Map.of(), 1, "t", "p"));
    assertThrows(IllegalArgumentException.class, () -> new RewardRules(ok, -1, 0, 0, 0, Map.of(), Map.of(), 1, "t", "p"));
    assertThrows(IllegalArgumentException.class, () -> new RewardRules(ok, 0, -1, 0, 0, Map.of(), Map.of(), 1, "t", "p"));
    assertThrows(IllegalArgumentException.class, () -> new RewardRules(ok, 0, 0, -1, 0, Map.of(), Map.of(), 1, "t", "p"));
    assertThrows(IllegalArgumentException.class, () -> new RewardRules(ok, 0, 0, 0, -1, Map.of(), Map.of(), 1, "t", "p"));
    assertThrows(IllegalArgumentException.class, () -> new RewardRules(ok, 0, 0, 0, 0, Map.of(), Map.of(), 0, "t", "p"));
    assertThrows(IllegalArgumentException.class, () -> new RewardRules(ok, 0, 0, 0, 0, Map.of(Moment.BOSS, -1), Map.of(), 1, "t", "p"));
    assertThrows(IllegalArgumentException.class, () -> new RewardRules(ok, 0, 0, 0, 0, Map.of(), Map.of("rare", 0), 1, "t", "p"));
  }

  @Test
  void theVaultAlwaysGivesSomethingAndPaysMarks() {
    rewards(doubles(0.9)).reward(ann, "delve_seithr", 1, Moment.VAULT, run);
    verify(lockers).file(locker, sword);
    assertEquals(10, locker.marks());
    assertEquals(1, locker.weekRuns("delve_seithr"));
    assertEquals(DungeonRewards.week(now.get(), ZoneId.of("UTC")), locker.week());
    verify(ann).sendMessage("§6You found Rimebound Sword§6! §7It is in your dungeon locker.");
    verify(lockers).saveLocker(ann, locker); // one write for the find, the marks and the counters
  }

  @Test
  void aRiskierDifficultyPaysMore() {
    rewards(doubles(0.5)).reward(ann, "delve_seithr", 1, Moment.BOSS, run, false, 1.5);
    assertEquals(8, locker.marks(), "the boss's 5 marks x1.5, rounded");
    verify(lockers).file(eq(locker), any()); // 0.5 < 0.35 x 1.5: a find a normal run would have missed
  }

  @Test
  void aVaultUnderParPaysMoreAndRollsLuckier() {
    rewards(doubles(0.9)).reward(ann, "delve_seithr", 1, Moment.VAULT, run, true);
    assertEquals(15, locker.marks(), "10 for the vault, 5 for par");
    verify(loot).roll(ann, 1, 0.5, null, null);
    rewards(doubles(0.9)).reward(ann, "delve_seithr", 1, Moment.BOSS, run, true);
    assertEquals(20, locker.marks(), "par never betters another moment: the boss's 5 only");
  }

  @Test
  void chancesGrowWithEachEmptyRollAndAWinStartsThemOver() {
    DungeonRewards r = rewards(doubles(0.5, 0.5, 0.5, 0.9));
    r.reward(ann, "d", 1, Moment.BOSS, run); // 0.5 >= 0.35: nothing
    assertEquals(0.1, locker.pity(), 1e-9);
    r.reward(ann, "d", 1, Moment.BOSS, run); // 0.5 >= 0.45: nothing
    assertEquals(0.2, locker.pity(), 1e-9);
    r.reward(ann, "d", 1, Moment.BOSS, run); // 0.5 < 0.55: a win (then 0.9: an item, not a gem)
    assertEquals(0, locker.pity());
    verify(lockers).file(locker, sword);
    assertEquals(1, locker.weekRuns("d"), "one run, however many rewards in it");
    assertEquals(15, locker.marks());
  }

  @Test
  void someWinsAreGemsAndClassesWithNoItemGetGems() {
    rewards(doubles(0.0, 0.1)).reward(ann, "d", 1, Moment.VAULT, run); // vault (no chance roll), 0.0 < gem-chance
    verify(lockers).file(locker, gem);
    verify(loot, never()).roll(any(), anyInt(), anyDouble(), any(), any());
    when(loot.roll(eq(ann), anyInt(), anyDouble(), any(), any())).thenReturn(new DungeonLoot.Rolled(null, "No dungeon item fits"));
    rewards(doubles(0.9)).reward(ann, "d", 1, Moment.VAULT, UUID.randomUUID());
    verify(lockers, times(2)).file(locker, gem);
  }

  @Test
  void eachDungeonRollsItemsFromThreeRunsAWeekAndMarksAreCapped() {
    DungeonRewards r = rewards(doubles(0.9));
    for (int i = 0; i < 5; i++) {
      r.reward(ann, "d", 1, Moment.VAULT, UUID.randomUUID());
    }
    verify(lockers, times(3)).file(locker, sword);
    assertEquals(50, locker.marks());
    verify(ann, times(2)).sendMessage(startsWith("§7You have had this week's loot from this dungeon"));
    r.reward(ann, "other", 1, Moment.VAULT, UUID.randomUUID());
    verify(lockers, times(4)).file(locker, sword);
    assertEquals(60, locker.marks(), "the weekly cap");
    r.reward(ann, "other", 1, Moment.VAULT, UUID.randomUUID());
    assertEquals(60, locker.marks());
    verify(lockers, times(5)).file(locker, sword); // its second run this week: still an item run
    // a new week starts the counts over, and a run going on over the turn is judged again
    UUID overTheTurn = UUID.randomUUID();
    r.reward(ann, "other", 1, Moment.ELITE, overTheTurn);
    now.addAndGet(7L * 24 * 3600 * 1000);
    r.reward(ann, "other", 1, Moment.VAULT, overTheTurn);
    assertEquals(1, locker.weekRuns("other"), "counted once in the new week");
    r.reward(ann, "d", 1, Moment.VAULT, UUID.randomUUID());
    verify(lockers, times(7)).file(locker, sword);
    assertEquals(80, locker.marks(), "60 last week, 10 from the run over the turn, 10 now");
  }

  @Test
  void nothingWithoutRulesOrALockerAndLostDropsAreTold() {
    rules = null;
    rewards(doubles(0.9)).reward(ann, "d", 1, Moment.VAULT, run);
    rules = shippedRules();
    when(lockers.locker(ann)).thenReturn(null);
    rewards(doubles(0.9)).reward(ann, "d", 1, Moment.VAULT, run);
    verify(lockers, never()).add(any(), any());
    when(lockers.locker(ann)).thenReturn(locker);
    when(lockers.saveLocker(ann, locker)).thenReturn(false);
    locker.put("id", new byte[] {1});
    rewards(doubles(0.9)).reward(ann, "d", 1, Moment.VAULT, run);
    verify(ann).sendMessage("§cYou found Rimebound Sword§c, but your locker could not be saved yet. Tell staff.");
    assertNotNull(locker.item("id"), "the find stays with its marks for the next save that works");
    clearInvocations(ann);
    rewards(doubles(0.9)).reward(ann, "d", 1, Moment.ELITE, UUID.randomUUID()); // nothing found, save fails quietly
    verify(ann, never()).sendMessage(contains("could not be saved yet"));
    when(lockers.saveLocker(ann, locker)).thenReturn(true);
    // a moment worth nothing and no marks: no message at all about marks
    clearInvocations(ann);
    rules = new RewardRules(new EnumMap<>(Moment.class), 0, 0, 3, 0, Map.of(), Map.of(), 1, "t", "jewellery");
    rewards(doubles(0.9)).reward(ann, "d", 1, Moment.ELITE, UUID.randomUUID());
    verify(ann, never()).sendMessage(startsWith("§b+"));
  }

  @Test
  void theShopSellsItemsOfThePlayersChoice() {
    LootKit.Base helm = kit.base("heavy_helmet");
    when(loot.usable(ann)).thenReturn(List.of(helm));
    DungeonRewards r = rewards(new Random(1));
    List<LockerExtras.Offer> offers = r.offers(ann);
    assertEquals(List.of(new LockerExtras.Offer("heavy_helmet", "Heavy Helmet", Map.of("rare", 30, "epic", 80))), offers);
    assertEquals("§cThat costs 30 marks; you have 0.", r.buy(ann, "heavy_helmet", "rare"));
    locker.marks(100);
    when(loot.roll(ann, 1, "rare", "heavy_helmet")).thenReturn(new DungeonLoot.Rolled(sword, null, "rare"));
    assertEquals("§aBought Rimebound Sword§a for 30 marks.", r.buy(ann, "heavy_helmet", "rare"));
    assertEquals(70, locker.marks());
    assertEquals("§cThat can't be bought.", r.buy(ann, "heavy_helmet", "legendary"));
    locker.marks(200);
    when(loot.roll(ann, 1, "epic", "heavy_helmet")).thenReturn(new DungeonLoot.Rolled(null, "nope"));
    assertEquals("§cnope", r.buy(ann, "heavy_helmet", "epic"));
    when(loot.roll(ann, 1, "epic", "heavy_helmet")).thenReturn(new DungeonLoot.Rolled(sword, null));
    when(lockers.saveLocker(ann, locker)).thenReturn(false);
    locker.put("id", new byte[] {1});
    assertTrue(r.buy(ann, "heavy_helmet", "epic").contains("no marks were spent"));
    assertNull(locker.item("id"));
    when(lockers.saveLocker(ann, locker)).thenReturn(true);
    assertEquals(200, locker.marks());
    when(lockers.locker(ann)).thenReturn(null);
    assertEquals("§cThat can't be bought.", r.buy(ann, "heavy_helmet", "rare"));
    rules = null;
    assertEquals(List.of(), r.offers(ann));
    assertEquals("§cThat can't be bought.", r.buy(ann, "heavy_helmet", "rare"));
    rules = new RewardRules(Map.of(), 0, 0, 3, 0, Map.of(), Map.of(), 1, "t", "jewellery");
    assertEquals(List.of(), r.offers(ann), "no prices: no shop");
  }

  @Test
  void gemsAreSocketedFromTheLocker() {
    locker.put("g", new byte[] {1});
    locker.put("i", new byte[] {2});
    when(lockers.show(locker, "g")).thenReturn(gem);
    when(lockers.show(locker, "i")).thenReturn(sword);
    ItemStack socketed = mock(ItemStack.class);
    DungeonRewards r = rewards(new Random(1));
    when(loot.socket(ann, sword, gem)).thenReturn(new LootForge.Socketed(LootForge.Socketing.NO_FIT, null));
    assertTrue(r.socket(ann, "g", "i").contains("doesn't fit"));
    assertNotNull(locker.item("g"));
    when(loot.socket(ann, sword, gem)).thenReturn(new LootForge.Socketed(LootForge.Socketing.DONE, socketed));
    assertEquals("§aThe gem settles into its socket.", r.socket(ann, "g", "i"));
    verify(lockers).replace(locker, "i", socketed);
    assertNull(locker.item("g"), "the gem is used up");
    locker.put("g", new byte[] {1});
    when(loot.socket(ann, sword, gem)).thenReturn(new LootForge.Socketed(LootForge.Socketing.BROKE, null));
    assertEquals("§cThe gem shattered.", r.socket(ann, "g", "i"));
    assertNull(locker.item("g"));
    assertEquals("§cThat gem or item is gone.", r.socket(ann, "g", "i"));
    assertEquals("§cThat gem or item is gone.", r.socket(ann, "i", "x"));
    locker.put("g", new byte[] {1});
    locker.assign(0, 0, "i");
    locker.wear(0, Map.of(), 1);
    assertEquals("§cTake the worn loadout off first.", r.socket(ann, "g", "i"));
    when(lockers.locker(ann)).thenReturn(null);
    assertEquals("§cThat gem or item is gone.", r.socket(ann, "g", "i"));
  }

  /** An item mock whose clones keep the amount they are given. */
  static ItemStack stackable(String name, int max) {
    ItemStack it = mock(ItemStack.class);
    when(it.getMaxStackSize()).thenReturn(max);
    when(it.effectiveName()).thenReturn(Component.text(name));
    when(it.clone()).thenAnswer(i -> {
      ItemStack c = mock(ItemStack.class);
      java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
      doAnswer(a -> {
        n.set(a.getArgument(0));
        return null;
      }).when(c).setAmount(anyInt());
      when(c.getAmount()).thenAnswer(a -> n.get());
      return c;
    });
    return it;
  }

  @Test
  void spoilsWaitInTheLockerUntilThePlayerIsOutside() {
    Spoils s = new Spoils(Map.of("m.materials.ignitium", 70.0, "v.nothing", 1.0), Map.of(), List.of());
    ItemStack ignitium = stackable("Ignitium", 64);
    when(loot.item("m.materials.ignitium")).thenReturn(ignitium);
    java.util.logging.Logger log = mock(java.util.logging.Logger.class);
    DungeonRewards r = new DungeonRewards(loot, lockers, () -> rules, doubles(0.5), now::get, ZoneId.of("UTC"),
        () -> s, log);
    r.spoils(ann, "d", 1, run);
    var owed = org.mockito.ArgumentCaptor.forClass(ItemStack.class);
    verify(lockers, times(2)).owe(eq(locker), owed.capture());
    assertEquals(List.of(64, 6), owed.getAllValues().stream().map(ItemStack::getAmount).toList(), "in whole stacks");
    verify(log).warning("[dungeons] spoils: no item v.nothing");
    when(loot.item("v.nothing")).thenThrow(new IllegalStateException("a broken item plugin"));
    verify(lockers).saveLocker(ann, locker);
    verify(ann).sendMessage("§eSpoils: §f70 Ignitium §7(yours when you leave the dungeon)");
    r.spoils(ann, "d", 1, run);
    verify(log, times(2)).warning("[dungeons] spoils: no item v.nothing");
    // nothing rolled: nothing saved or said
    clearInvocations(lockers, ann);
    r.spoils(ann, "d", 0, run);
    verify(lockers, never()).saveLocker(any(), any());
    // a failed save keeps them in memory for the next save that works, and says so
    when(lockers.saveLocker(ann, locker)).thenReturn(false);
    r.spoils(ann, "d", 1, run);
    verify(ann).sendMessage("§cYour spoils could not be saved yet. Tell staff.");
    // no spoils set up, or no locker: nothing
    clearInvocations(lockers);
    rewards(doubles(0.5)).spoils(ann, "d", 1, run);
    when(lockers.locker(ann)).thenReturn(null);
    r.spoils(ann, "d", 1, run);
    verify(lockers, never()).owe(any(), any());
  }

  @Test
  void bossesMayDropTrophiesAndVaultsMayUnlockSkins() {
    Cosmetics c = new Cosmetics("lp user {player} permission set {permission} true",
        List.of(new Cosmetics.Trophy("d", "hard", 0.5, "v.skull", "Golem Trophy")),
        List.of(new Cosmetics.Skin("d", "hard", 0.5, "skin.frost", "Frost"),
            new Cosmetics.Skin("d", "hard", 0.5, "skin.glacial", "Glacial")));
    ItemStack skull = stackable("Skull", 64);
    when(loot.item("v.skull")).thenReturn(skull);
    List<String> console = new ArrayList<>();
    java.util.logging.Logger log = mock(java.util.logging.Logger.class);
    Random rng = doubles(0.9, 0.1, 0.1);
    DungeonRewards r = new DungeonRewards(loot, lockers, () -> rules, rng, now::get, ZoneId.of("UTC"), () -> null, log)
        .cosmetics(() -> c, console::add);
    when(ann.getName()).thenReturn("Ann");
    // a run that doesn't roll items this week rolls no cosmetics either
    locker.lastRun("another run", true);
    r.feat(ann, "d", "hard", Moment.BOSS, run, List.of("Ann"));
    locker.lastRun(run.toString(), false);
    r.feat(ann, "d", "hard", Moment.BOSS, run, List.of("Ann"));
    verify(lockers, never()).owe(any(), any());
    locker.lastRun(run.toString(), true);
    r.feat(ann, "d", "hard", Moment.BOSS, run, List.of("Ann")); // 0.9: no trophy
    verify(lockers, never()).owe(any(), any());
    r.feat(ann, "d", "hard", Moment.BOSS, run, List.of("Ann", "Bo")); // 0.1: a trophy
    verify(lockers).owe(eq(locker), any());
    verify(lockers).saveLocker(ann, locker);
    verify(ann).sendMessage("§6✦ A trophy: Golem Trophy! §7It waits for you outside the dungeon.");
    // the vault: a skin the player lacks (Frost is theirs already)
    when(ann.hasPermission("skin.frost")).thenReturn(true);
    r.feat(ann, "d", "hard", Moment.VAULT, run, List.of("Ann"));
    assertEquals(List.of("lp user Ann permission set skin.glacial true"), console);
    verify(ann).sendMessage("§d✦ You unlocked the Glacial skin! §7Use it on your own gear in /armourshop.");
    new DungeonRewards(loot, lockers, () -> rules, doubles(0.9), now::get, ZoneId.of("UTC"), () -> null, log)
        .cosmetics(() -> c, console::add).feat(ann, "d", "hard", Moment.VAULT, run, List.of("Ann"));
    assertEquals(1, console.size(), "a roll that misses unlocks nothing");
    new DungeonRewards(loot, lockers, () -> rules, doubles(0.1), now::get, ZoneId.of("UTC"), () -> null, log)
        .cosmetics(() -> c, cmd -> false).feat(ann, "d", "hard", Moment.VAULT, run, List.of("Ann"));
    verify(log).warning("[dungeons] the skin unlock failed: lp user Ann permission set skin.glacial true");
    verify(ann).sendMessage("§cYou earned the Glacial skin, but it could not be unlocked. Tell staff.");
    when(ann.hasPermission("skin.glacial")).thenReturn(true);
    r.feat(ann, "d", "hard", Moment.VAULT, run, List.of("Ann"));
    assertEquals(1, console.size(), "every skin is theirs: nothing more");
    // a trophy whose item is missing, or broken, or that can't be saved
    Random low = doubles(0.0);
    DungeonRewards r2 = new DungeonRewards(loot, lockers, () -> rules, low, now::get, ZoneId.of("UTC"), () -> null, log)
        .cosmetics(() -> c, console::add);
    when(loot.item("v.skull")).thenReturn(null);
    r2.feat(ann, "d", "hard", Moment.BOSS, run, List.of("Ann"));
    when(loot.item("v.skull")).thenThrow(new IllegalStateException("broken"));
    r2.feat(ann, "d", "hard", Moment.BOSS, run, List.of("Ann"));
    verify(log, times(2)).warning("[dungeons] trophy: no item v.skull");
    doReturn(skull).when(loot).item("v.skull");
    when(lockers.saveLocker(ann, locker)).thenReturn(false);
    r2.feat(ann, "d", "hard", Moment.BOSS, run, List.of("Ann"));
    verify(ann).sendMessage("§cYour trophy could not be saved yet. Tell staff.");
    // none set up, or no locker: nothing
    rewards(doubles(0.0)).feat(ann, "d", "hard", Moment.BOSS, run, List.of("Ann"));
    when(lockers.locker(ann)).thenReturn(null);
    r2.feat(ann, "d", "hard", Moment.BOSS, run, List.of("Ann"));
    verify(lockers, times(2)).owe(eq(locker), any());
  }

  @Test
  void anAccountsCharactersShareTheirCosmeticRuns(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) {
    Cosmetics c = new Cosmetics("", List.of(new Cosmetics.Trophy("d", "hard", 1, "v.skull", "Trophy")), List.of());
    ItemStack skull = stackable("Skull", 64);
    when(loot.item("v.skull")).thenReturn(skull);
    AccountLimits limits = new AccountLimits(temp.resolve("accounts.yml").toFile(), java.util.logging.Logger.getLogger("t"));
    DungeonRewards r = new DungeonRewards(loot, lockers, () -> rules, doubles(0.0), now::get, ZoneId.of("UTC"), () -> null,
        java.util.logging.Logger.getLogger("t")).cosmetics(() -> c, cmd -> true).accounts(limits);
    UUID account = UUID.randomUUID();
    when(ann.getUniqueId()).thenReturn(account);
    for (int i = 0; i < 4; i++) { // four runs, as if on four characters (each counts for its own character)
      UUID each = UUID.randomUUID();
      locker.lastRun(each.toString(), true);
      r.feat(ann, "d", "hard", Moment.BOSS, each, List.of("Ann"));
      r.feat(ann, "d", "hard", Moment.BOSS, each, List.of("Ann")); // (the same run asks again)
    }
    verify(lockers, times(6)).owe(eq(locker), any()); // 3 runs x 2 asks: the fourth run rolls none
    // without reward rules there is no weekly number to count against: no limit
    DungeonRewards loose = new DungeonRewards(loot, lockers, () -> null, doubles(0.0), now::get, ZoneId.of("UTC"),
        () -> null, java.util.logging.Logger.getLogger("t")).cosmetics(() -> c, cmd -> true).accounts(limits);
    UUID fifth = UUID.randomUUID();
    locker.lastRun(fifth.toString(), true);
    loose.feat(ann, "d", "hard", Moment.BOSS, fifth, List.of("Ann"));
    verify(lockers, times(7)).owe(eq(locker), any());
  }

  @Test
  void weeksAreIsoWeeks() {
    assertEquals("2026-W39", DungeonRewards.week(1_790_000_000_000L, ZoneId.of("UTC")));
    assertEquals("2026-W53", DungeonRewards.week(1_798_761_600_000L, ZoneId.of("UTC")), "2027-01-01 is still in 2026's week 53");
    assertNotNull(Set.of());
  }

  @Test
  void theShippedValuesLoad() {
    RewardRules.Values v = rules.values();
    assertEquals(Map.of("uncommon", 10, "rare", 30, "epic", 80, "legendary", 200), v.rarities());
    assertEquals(160, v.item("epic", 2, false));
    assertEquals(80, v.item("epic", 2, true), "a gem is worth half");
    assertEquals(0, v.item("mythic", 2, false));
    assertEquals(1.0, v.mark());
    assertEquals(40.0, v.spoils());
    assertEquals(0.2, v.insure());
    assertEquals(RewardRules.Values.NONE, new RewardRules(Map.of(), 0, 0, 0, 0, Map.of(), Map.of(), 1, "t", "p").values());
    assertThrows(IllegalArgumentException.class, () -> new RewardRules.Values(Map.of("rare", -1), 0, 0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new RewardRules.Values(Map.of(), -1, 0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new RewardRules.Values(Map.of(), 0, -1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new RewardRules.Values(Map.of(), 0, 0, -1, 0));
    assertThrows(IllegalArgumentException.class, () -> new RewardRules.Values(Map.of(), 0, 0, 0, -1));
    assertThrows(IllegalArgumentException.class,
        () -> new RewardRules.Values(Map.of(), 0, 0, Double.POSITIVE_INFINITY, 0));
    // no value section: nothing is worth anything
    List<String> problems = new ArrayList<>();
    YamlConfiguration y = shippedYaml();
    y.set("rewards.value", null);
    y.set("rewards.insure", null);
    assertEquals(0, RewardRules.load(y, kit, problems).values().item("epic", 1, false));
    assertEquals(List.of("loot.yml rewards: no value section: finds count 1 each in ways out",
        "loot.yml rewards: no insure: insuring a worn piece costs 1 mark"), problems);
  }

  @Test
  void findsAreRolledWhenFoundAndHandedOverWhenTheyComeHome() {
    when(loot.roll(eq(ann), anyInt(), anyDouble(), any(), any())).thenReturn(new DungeonLoot.Rolled(sword, null, "epic"));
    when(loot.power(anyInt())).thenReturn(1.25);
    when(lockers.encode(sword)).thenReturn(new byte[] {7});
    when(lockers.decode(any())).thenReturn(sword);
    DungeonRewards r = rewards(doubles(0.0, 0.9)); // the boss's chance wins; an item, not a gem
    locker.pity(0.2);
    RunRewards.Find f = r.find(ann, "delve", 1, Moment.BOSS, run, 1);
    assertEquals("Rimebound Sword and 5 marks", f.label());
    assertEquals(5, f.value(), "a mocked item carries no value; the marks do");
    assertTrue(f.counts() && f.rolled());
    assertEquals(0.0, locker.pity(), "a win starts the pity over");
    verify(lockers, never()).file(any(), any());
    assertEquals(0, locker.marks(), "nothing handed over yet");
    assertEquals(1, locker.weekRuns("delve"), "the run counts at its first find (lost or not)");
    r.deliver(ann, f, "delve", 1, run, 1, false);
    verify(lockers).file(locker, sword);
    assertEquals(5, locker.marks());
    assertEquals(1, locker.weekRuns("delve"));
    verify(ann).sendMessage("§b+5 dungeon marks §7(5 in your locker)");
    // a second find of the same run: rolls as the run did, counts it no more
    RunRewards.Find again = r.find(ann, "delve", 1, Moment.BOSS, run, 1);
    r.deliver(ann, again, "delve", 1, run, 1, false);
    assertEquals(1, locker.weekRuns("delve"));
    // this week's runs used up: a new run finds marks only, and hears so once
    locker.weekRun("delve", 3);
    UUID run2 = UUID.randomUUID();
    RunRewards.Find marks = r.find(ann, "delve", 1, Moment.ELITE, run2, 1);
    assertEquals("2 dungeon marks", marks.label());
    assertFalse(marks.counts());
    r.find(ann, "delve", 1, Moment.ELITE, run2, 1);
    verify(ann, times(1)).sendMessage(startsWith("§7You have had this week's loot from this dungeon"));
    assertEquals(3, locker.weekRuns("delve"));
    assertEquals(run2.toString(), locker.lastRun());
    assertFalse(locker.lastRunRewarded());
    // the weekly mark cap holds at hand-over; a find staff give back skips it
    locker.weekMarks(60);
    clearInvocations(ann);
    r.deliver(ann, marks, "delve", 1, run2, 1, false);
    verify(ann, never()).sendMessage(startsWith("§b+"));
    int before = locker.marks();
    r.deliver(ann, marks, "delve", 1, UUID.randomUUID(), 1, true);
    assertEquals(before + 2, locker.marks());
    // a save that fails keeps it all in memory and says so
    when(lockers.saveLocker(ann, locker)).thenReturn(false);
    r.deliver(ann, f, "delve", 1, run, 1, false);
    verify(ann).sendMessage("§cYour finds could not be saved yet. Tell staff.");
    when(lockers.saveLocker(ann, locker)).thenReturn(true);
    // an item that can't be read changes nothing
    when(lockers.decode(any())).thenThrow(new IllegalStateException("bad bytes"));
    int had = locker.marks();
    assertThrows(IllegalStateException.class, () -> r.deliver(ann, f, "delve", 1, run, 1, false));
    assertEquals(had, locker.marks());
    doReturn(sword).when(lockers).decode(any());
    // finds not rolled ahead are rolled at hand-over
    clearInvocations(lockers);
    r.deliver(ann, RunRewards.Find.of(Moment.ELITE), "delve", 1, UUID.randomUUID(), 1, false);
    verify(lockers).saveLocker(ann, locker);
    r.deliver(ann, RunRewards.Find.spoils(1), "delve", 1, run, 1, false); // (no spoils set up: nothing)
    // no locker, no rules
    Player bo = mock(Player.class);
    assertNull(r.find(bo, "delve", 1, Moment.BOSS, run, 1));
    assertThrows(IllegalStateException.class, () -> r.deliver(bo, f, "delve", 1, run, 1, false));
    assertNull(new DungeonRewards(loot, lockers, () -> null, doubles(0), now::get, ZoneId.of("UTC"))
        .find(ann, "delve", 1, Moment.BOSS, run, 1));
  }

  @Test
  void aFindHoldsWhatWasRolled() {
    when(lockers.encode(any())).thenReturn(new byte[] {7});
    // nothing at all: no find
    rules = new RewardRules(Map.of(), 0, 0, 3, 60, Map.of(), Map.of(), 1, "t", "p");
    assertNull(rewards(doubles(0.0)).find(ann, "delve", 1, Moment.EVENT, run, 1));
    // a sure item and no marks: the item alone
    rules = new RewardRules(Map.of(Moment.VAULT, 1.0), 0, 0, 3, 60, Map.of(), Map.of(), 1, "t", "p");
    RunRewards.Find f = rewards(doubles(0.9)).find(ann, "delve", 1, Moment.VAULT, run, 1);
    assertEquals("Rimebound Sword", f.label());
    assertEquals(0, f.marks());
    // a lost roll grows the pity; a sure one (boosted to 1) leaves it be
    rules = new RewardRules(Map.of(Moment.ELITE, 0.5), 0, 0.1, 3, 60, Map.of(), Map.of(), 1, "t", "p");
    rewards(doubles(0.9)).find(ann, "delve", 1, Moment.ELITE, run, 1);
    assertEquals(0.1, locker.pity(), 1e-9);
    rewards(doubles(0.9)).find(ann, "delve", 1, Moment.ELITE, run, 2);
    assertEquals(0.1, locker.pity(), 1e-9);
  }

  @Test
  void spoilsFindsAreRolledWhenFound() {
    Spoils s = new Spoils(Map.of("m.materials.ignitium", 70.0), Map.of(), List.of());
    ItemStack ignitium = stackable("Ignitium", 64);
    when(loot.item("m.materials.ignitium")).thenReturn(ignitium);
    when(lockers.encode(any())).thenReturn(new byte[] {3});
    java.util.function.Supplier<RewardRules>[] now2 = new java.util.function.Supplier[] {() -> rules};
    DungeonRewards r = new DungeonRewards(loot, lockers, () -> now2[0].get(), doubles(0.5), now::get,
        ZoneId.of("UTC"), () -> s, java.util.logging.Logger.getLogger("t"));
    RunRewards.Find f = r.spoilsFind(ann, "d", 1, run);
    assertEquals("Spoils: 70 Ignitium", f.label());
    assertEquals(40, f.value());
    assertEquals(2, f.spoils().size(), "in whole stacks");
    assertNull(r.spoilsFind(ann, "d", 0, run), "nothing rolled");
    assertEquals(1, r.spoilsFind(ann, "d", 0.01, run).value(), "a handful is still worth something");
    now2[0] = () -> null;
    assertEquals(0, r.spoilsFind(ann, "d", 1, run).value(), "no rules: no value");
    assertNull(rewards(doubles(0.5)).spoilsFind(ann, "d", 1, run), "no spoils set up");
    // handed over: the spoils wait outside
    when(lockers.decode(any())).thenReturn(ignitium);
    r.deliver(ann, f, "d", 1, run, 1, false);
    verify(lockers, times(2)).owe(eq(locker), eq(ignitium));
  }

  @Test
  void itemsCarryTheirValueAndMarksPayForInsurance() {
    org.mockbukkit.mockbukkit.MockBukkit.mock();
    try {
      DungeonRewards r = rewards(doubles(0.5));
      ItemStack helm = new ItemStack(org.bukkit.Material.IRON_HELMET);
      assertEquals(0, r.value(null));
      assertEquals(0, r.value(new ItemStack(org.bukkit.Material.STICK)), "no meta yet");
      assertEquals(0, r.value(helm));
      helm.editMeta(m -> m.setCustomModelData(1));
      assertEquals(0, r.value(helm), "meta, but no value");
      assertSame(helm, DungeonRewards.valued(helm, 0));
      DungeonRewards.valued(helm, 64);
      assertEquals(64, r.value(helm));
      assertNull(DungeonRewards.valued(null, 5));
      // icons: the item, the spoils, the marks
      when(lockers.decode(new byte[0])).thenReturn(helm);
      when(lockers.decode(any())).thenReturn(helm);
      assertSame(helm, r.icon(new RunRewards.Find(Moment.BOSS, 0, "x", 1, 0, true, List.of(new byte[] {1}), List.of(), true)));
      assertSame(helm, r.icon(new RunRewards.Find(null, 1, "x", 1, 0, false, List.of(), List.of(new byte[] {1}), true)));
      ItemStack em = r.icon(new RunRewards.Find(Moment.ELITE, 0, "x", 1, 90, false, List.of(), List.of(), true));
      assertEquals(org.bukkit.Material.EMERALD, em.getType());
      assertEquals(64, em.getAmount());
      assertEquals(1, r.icon(new RunRewards.Find(Moment.ELITE, 0, "x", 1, 0, false, List.of(), List.of(), true)).getAmount());
      assertEquals(org.bukkit.Material.PAPER, r.icon(RunRewards.Find.of(Moment.BOSS)).getType());
      assertEquals(org.bukkit.Material.BUNDLE, r.icon(RunRewards.Find.spoils(1)).getType());
      // a drop of a known rarity is tagged with its value
      ItemStack iron = spy(new ItemStack(org.bukkit.Material.IRON_SWORD));
      doReturn(Component.text("Iron Edge")).when(iron).effectiveName(); // (MockBukkit has no effective names)
      when(loot.roll(eq(ann), anyInt(), anyDouble(), any(), any())).thenReturn(new DungeonLoot.Rolled(iron, null, "rare"));
      when(loot.power(anyInt())).thenReturn(1.0);
      when(lockers.encode(any())).thenReturn(new byte[] {7});
      var held = org.mockito.ArgumentCaptor.forClass(ItemStack.class);
      rewards(doubles(0.9)).reward(ann, "delve", 1, Moment.VAULT, run); // (the vault's item is sure; 0.9: not a gem)
      verify(lockers).file(eq(locker), held.capture());
      assertEquals(30, r.value(held.getValue()));
    } finally {
      org.mockbukkit.mockbukkit.MockBukkit.unmock();
    }
  }

  @Test
  void marksAreSpentAndGivenBack() {
    DungeonRewards r = rewards(doubles(0.5));
    locker.marks(20);
    assertEquals(20, r.marks(ann));
    assertFalse(r.spend(ann, 30));
    assertTrue(r.spend(ann, 15));
    assertEquals(5, locker.marks());
    assertTrue(r.spend(ann, -15), "given back");
    assertEquals(20, locker.marks());
    when(lockers.saveLocker(ann, locker)).thenReturn(false);
    assertFalse(r.spend(ann, 5));
    assertEquals(20, locker.marks(), "a spend that can't be saved is undone");
    Player bo = mock(Player.class);
    assertEquals(-1, r.marks(bo));
    assertFalse(r.spend(bo, 1));
    assertEquals(11, r.insurance(52), "a fifth of the value, rounded up");
    when(loot.power(Integer.MAX_VALUE)).thenReturn(1.9);
    assertEquals(76, r.insurance(0), "a piece without a value insures as the best item");
    assertEquals(0, new DungeonRewards(loot, lockers, () -> null, doubles(0), now::get, ZoneId.of("UTC")).insurance(52));
  }
}
