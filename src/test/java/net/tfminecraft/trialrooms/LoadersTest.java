package net.tfminecraft.trialrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.BlockAPI;
import net.tfminecraft.trialrooms.cache.Cache;
import net.tfminecraft.trialrooms.loader.*;
import org.bukkit.*;
import org.junit.jupiter.api.*;

class LoadersTest extends TrialTestSupport {
  @Test
  void configReloadReplacesConversionsInsteadOfRetainingOldValues() throws Exception {
    var file = temp.resolve("config.yml");
    Cache.conversions.clear();
    Files.writeString(
        file,
        "conversions: [v.emerald 1]\n"
            + "rarity-multiplier-max: 0\n"
            + "rarity-weight-model: linear\n"
            + "jitter-max: -2");
    var loader = new ConfigLoader();
    loader.loadConfig(file.toFile());
    assertEquals(1, Cache.conversions.size());
    assertEquals(Double.POSITIVE_INFINITY, Cache.rarityMultiplierMax);
    assertEquals("LINEAR", Cache.rarityWeightModel);
    assertEquals(0, Cache.jitterMax);
    Files.writeString(file, "conversions: [v.emerald 2]");
    loader.loadConfig(file.toFile());
    assertEquals(1, Cache.conversions.size());
    assertEquals(2, Cache.conversions.getFirst().getAmount());
    Files.writeString(file, "");
    loader.loadConfig(file.toFile());
    assertTrue(Cache.conversions.isEmpty());
  }

  @Test
  void spawnerAndLootTablesAcceptModernAndLegacyYaml() throws Exception {
    var file = temp.resolve("data.yml");
    Files.writeString(file, "crypt: {block: v.spawner}");
    new SpawnerLoader().load(file.toFile());
    var spawner = SpawnerLoader.getByString("crypt");
    assertNotNull(spawner);
    assertEquals("crypt", spawner.getId());
    assertEquals("v.spawner", spawner.getBlock());
    assertEquals(1, SpawnerLoader.get().size());
    assertNull(SpawnerLoader.getByString("unknown"));
    var block = server.addSimpleWorld("test").getBlockAt(0, 1, 0);
    try (var libs = mockStatic(TLibs.class)) {
      var api = mock(BlockAPI.class, RETURNS_DEEP_STUBS);
      libs.when(TLibs::getBlockAPI).thenReturn(api);
      assertNull(SpawnerLoader.getByBlock(block));
      when(api.getChecker().checkBlock(block, "v.spawner")).thenReturn(true);
      assertSame(spawner, SpawnerLoader.getByBlock(block));
    }
    Files.writeString(
        file, "modern: {drops: [v.iron 1-2 1], amounts: [common 1-2]}\nlegacy: [v.gold 2-3 1]");
    new TableLoader().load(file.toFile());
    assertEquals(2, TableLoader.get().size());
    assertEquals("v.gold", TableLoader.getByString("legacy").getEntries().getFirst().type);
    assertNotNull(TableLoader.getByString("modern"));
    assertNull(TableLoader.getByString("missing"));
    Files.writeString(file, "bad: [");
    new TableLoader().load(file.toFile());
    new SpawnerLoader().load(file.toFile());
    new ConfigLoader().loadConfig(file.toFile());
    Files.delete(file);
    new TableLoader().load(file.toFile());
    new SpawnerLoader().load(file.toFile());
    new ConfigLoader().loadConfig(file.toFile());
    assertTrue(TableLoader.get().isEmpty());
    assertTrue(SpawnerLoader.get().isEmpty());
  }

  @Test
  void invalidConversionsAreSkippedWithoutLosingLaterValidEntries() throws Exception {
    var file = temp.resolve("config.yml");
    Files.writeString(
        file,
        "conversions: ['v.emerald 1', '', '# comment', 'v.gold -1', 'v.iron NaN', 'v.stone wrong',"
            + " 'v.diamond 2']\n");
    assertDoesNotThrow(() -> new ConfigLoader().loadConfig(file.toFile()));
    assertEquals(
        java.util.List.of("v.emerald", "v.diamond"),
        Cache.conversions.stream().map(c -> c.getItem()).toList());
    assertEquals(2, Cache.conversions.getLast().getAmount());
  }
}
