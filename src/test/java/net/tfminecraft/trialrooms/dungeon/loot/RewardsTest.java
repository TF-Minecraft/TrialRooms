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
    when(loot.roll(eq(ann), anyInt(), any(), any())).thenReturn(new DungeonLoot.Rolled(sword, null));
    when(loot.gem(anyInt(), any(), any(), any())).thenReturn(new DungeonLoot.Rolled(gem, null));
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
    verify(loot, never()).roll(any(), anyInt(), any(), any());
    when(loot.roll(eq(ann), anyInt(), any(), any())).thenReturn(new DungeonLoot.Rolled(null, "No dungeon item fits"));
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
    when(loot.roll(ann, 1, "rare", "heavy_helmet")).thenReturn(new DungeonLoot.Rolled(sword, null));
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
    verify(lockers).saveLocker(ann, locker);
    verify(ann).sendMessage("§eSpoils: §f70 Ignitium §7(yours when you leave the dungeon)");
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
  void weeksAreIsoWeeks() {
    assertEquals("2026-W39", DungeonRewards.week(1_790_000_000_000L, ZoneId.of("UTC")));
    assertEquals("2026-W53", DungeonRewards.week(1_798_761_600_000L, ZoneId.of("UTC")), "2027-01-01 is still in 2026's week 53");
    assertNotNull(Set.of());
  }
}
