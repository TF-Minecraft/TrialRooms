package net.tfminecraft.trialrooms.loot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;

class LootTableTest {
  @BeforeEach
  void setup() {
    MockBukkit.mock();
  }

  @AfterEach
  void teardown() {
    MockBukkit.unmock();
  }

  @Test
  void tierAliasesAndRangeMetadata() {
    assertEquals(LootTable.Tier.COMMON, LootTable.Tier.parse(null));
    assertEquals(LootTable.Tier.COMMON, LootTable.Tier.parse("other"));
    for (var t : LootTable.Tier.values())
      assertEquals(t, LootTable.Tier.parse(t.name().toLowerCase()));
    var tiers =
        List.of(
            LootTable.Tier.COMMON,
            LootTable.Tier.UNCOMMON,
            LootTable.Tier.RARE,
            LootTable.Tier.EPIC,
            LootTable.Tier.LEGENDARY);
    for (int i = 1; i <= 5; i++) assertEquals(tiers.get(i - 1), LootTable.Tier.parse("" + i));
    assertEquals(LootTable.Tier.COMMON, LootTable.Tier.parse("0"));
    assertEquals(LootTable.Tier.LEGENDARY, LootTable.Tier.parse("100"));
    var range = new LootTable.Range(-1, -10);
    assertEquals(0, range.min);
    assertEquals(0, range.max);
    var drop = new LootTable.Drop("v.iron", 2);
    assertEquals("v.iron x2", drop.toString());
  }

  @Test
  void malformedRowsAreSkippedAndRangesNormalized() {
    var table =
        new LootTable(
            "test",
            Arrays.asList(
                null,
                "",
                "# comment",
                "v.x",
                "v.x 1 1",
                "v.x x-y 1",
                "v.x 1-2 bad",
                "v.x 5-2 -1 common",
                "v.y 1-1 2 #comment",
                "v.z 0-0 1 rare"),
            .2);
    assertEquals("test", table.getName());
    assertEquals(.2, table.getBiasPerLevel());
    assertEquals(3, table.getEntries().size());
    var e = table.getEntries().getFirst();
    assertEquals(2, e.minAmount);
    assertEquals(5, e.maxAmount);
    assertEquals(0, e.baseWeight);
    assertTrue(e.toString().contains("v.x 2-5"));
    assertThrows(UnsupportedOperationException.class, () -> table.getEntries().clear());
    assertTrue(new LootTable("empty", null, 0).isEmpty());
  }

  @Test
  void weightedPicksHonorRandomBoundaryAndIgnoreZeroWeight() {
    var table = new LootTable("test", List.of("disabled 1-1 0", "enabled 1-1 1"), 0);
    var rng = mock(ThreadLocalRandom.class);
    when(rng.nextDouble()).thenReturn(0.0);
    assertEquals("enabled", table.pickOne(0, rng).type);
  }

  @Test
  void picksRespectTiersAndEmptyTables() {
    var rng = mock(ThreadLocalRandom.class);
    var table = new LootTable("test", List.of("low 1-1 1 common", "high 2-4 1 legendary"), .2);
    when(rng.nextDouble()).thenReturn(.99);
    assertEquals("high", table.pickOne(100, rng).type);
    when(rng.nextDouble()).thenReturn(0.0);
    assertEquals("low", table.pickOne(-1, rng).type);
    assertNull(new LootTable("empty", List.of(), 0).pickOne(0, rng));
    assertNull(new LootTable("zero", List.of("x 1-1 0"), 0).pickOne(0, rng));
    assertTrue(new LootTable("empty", null, 0).rollMany(0, 2).isEmpty());
    assertTrue(table.rollMany(0, -1).isEmpty());
    assertEquals(3, table.rollMany(0, 3).size());
    assertTrue(new LootTable("zero", List.of("x 0-0 1"), 0).rollMany(0, 3).isEmpty());
    var e = new LootTable.Entry("x", 4, 4, 1, LootTable.Tier.COMMON);
    assertEquals(4, e.rollAmount(rng));
    assertEquals(0, new LootTable.Entry("x", -1, -2, 1, LootTable.Tier.COMMON).rollAmount(rng));
  }

  @Test
  void validMaximumIntegerAmountMustNotOverflowRandomBounds() {
    var entry =
        new LootTable.Entry(
            "x", Integer.MAX_VALUE - 1, Integer.MAX_VALUE, 1, LootTable.Tier.COMMON);
    int value = assertDoesNotThrow(() -> entry.rollAmount(ThreadLocalRandom.current()));
    assertTrue(value >= Integer.MAX_VALUE - 1);
  }

  @Test
  void yamlAmountsParseValidRowsAndIgnoreMalformedOnes() throws Exception {
    assertNull(LootTable.fromSection("x", null, 0));
    var y = new YamlConfiguration();
    y.loadFromString(
        "drops: [v.iron 1-2 1]\n"
            + "amounts: ['# skip', bad, 'common nope', 'rare a-b', 'epic 1-3', 'legendary 3-1']");
    var table = LootTable.fromSection("x", y, 0);
    assertEquals(1, table.getEntries().size());
    assertNull(table.getAmountRangeForKeyRarityName(null));
    assertNull(table.getAmountRangeForKeyRarityName("common"));
    assertEquals(3, table.getAmountRangeForKeyRarityName("EPIC").max);
    assertEquals(3, table.getAmountRangeForKeyRarityName("legendary").min);
    assertTrue(LootTable.fromSection("empty", new YamlConfiguration(), 0).isEmpty());
  }

  @Test
  void invalidWeightsCannotPoisonSelection() {
    var table = new LootTable("test", List.of("bad 1-1 NaN", "bad2 1-1 Infinity", "good 1-1 1"), 0);
    assertEquals(1, table.getEntries().size());
    assertEquals("good", table.pickOne(0, ThreadLocalRandom.current()).type);
  }

  @Test
  void extremelyLargeNumericTierClampsToLegendary() {
    assertEquals(
        LootTable.Tier.LEGENDARY,
        assertDoesNotThrow(() -> LootTable.Tier.parse("99999999999999999")));
  }

  @Test
  void finiteHugeWeightsAndInvalidBiasDoNotOverflowSelection() {
    var rng = mock(ThreadLocalRandom.class);
    when(rng.nextDouble()).thenReturn(0.0);
    var huge =
        new LootTable(
            "huge",
            List.of("first 1-1 1.7976931348623157E308", "last 1-1 1.7976931348623157E308"),
            1);
    assertEquals("first", huge.pickOne(100, rng).type);
    var nan = new LootTable("nan", List.of("first 1-1 1", "last 1-1 1"), Double.NaN);
    assertEquals("first", nan.pickOne(100, rng).type);
  }
}
