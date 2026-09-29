package net.tfminecraft.trialrooms.environment.spawner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.trialrooms.*;
import net.tfminecraft.trialrooms.cache.Cache;
import net.tfminecraft.trialrooms.loader.TableLoader;
import net.tfminecraft.trialrooms.loot.LootTable;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockito.*;

class KeyServiceTest extends TrialTestSupport {
  ActiveSpawner spawner;
  KeyService service;
  World world;
  Location loc;
  Item entity;
  ItemAPI api;
  MockedStatic<TLibs> tlibs;
  Map<java.lang.reflect.Field, Object> saved = new HashMap<>();

  @BeforeEach
  void setupKeys() throws Exception {
    for (var f : Cache.class.getFields()) saved.put(f, f.get(null));
    Cache.rarityWeightModel = "LINEAR";
    Cache.commonChance = 1;
    Cache.uncommonChance = Cache.rareChance = Cache.epicChance = Cache.legendaryChance = 0;
    Cache.rarityBiasPerLevel = 0;
    Cache.rarityBiasExponent = 1;
    Cache.rarityMultiplierMin = 0;
    Cache.rarityMultiplierMax = 10;
    Cache.jitterMax = 0;
    Cache.spawnerKey = "key";
    Cache.mobKey = "mobkey";
    Cache.lootAmount = 6;
    spawner = mock(ActiveSpawner.class);
    world = mock(World.class);
    loc = new Location(world, 1, 2, 3);
    when(spawner.getLoc()).thenReturn(loc);
    when(spawner.getUUID()).thenReturn("spawner");
    when(spawner.getLevel()).thenReturn(1);
    when(spawner.getState()).thenReturn(ActiveSpawner.State.UNLOCKING);
    when(spawner.isLoaded()).thenReturn(true);
    entity = mock(Item.class);
    when(world.dropItem(any(Location.class), any(ItemStack.class))).thenReturn(entity);
    when(entity.getLocation()).thenReturn(loc.clone());
    when(entity.isValid()).thenReturn(true);
    api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
    when(api.getCreator().getItemFromPath(any())).thenReturn(null);
    tlibs = mockStatic(TLibs.class);
    tlibs.when(TLibs::getItemAPI).thenReturn(api);
    TableLoader.clear();
    service = new KeyService(spawner);
  }

  @AfterEach
  void cleanupKeys() throws Exception {
    tlibs.close();
    TableLoader.clear();
    for (var e : saved.entrySet()) e.getKey().set(null, e.getValue());
  }

  static Object call(Object target, String name, Class<?>[] types, Object... args)
      throws Exception {
    var m = target.getClass().getDeclaredMethod(name, types);
    m.setAccessible(true);
    return m.invoke(target, args);
  }

  Object call(String name, Class<?>[] types, Object... args) throws Exception {
    return call(service, name, types, args);
  }

  @Test
  void zeroDrawCannotChooseDisabledCommonTier() throws Exception {
    Cache.commonChance = 0;
    Cache.rareChance = 1;
    var rng = mock(ThreadLocalRandom.class);
    try (var random = mockStatic(ThreadLocalRandom.class)) {
      random.when(ThreadLocalRandom::current).thenReturn(rng);
      when(rng.nextDouble(anyDouble())).thenReturn(0d);
      assertEquals(KeyService.Rarity.RARE, call("rollRarity", new Class[] {int.class}, 1));
    }
  }

  @Test
  void mobDropChanceReadsCurrentConfiguration() throws Exception {
    Cache.MOB_KEY_BASE_CHANCE = .1;
    Cache.MOB_KEY_CHANCE_PER_LEVEL = .05;
    Cache.MOB_KEY_MAX_CHANCE = .5;
    assertEquals(.2, call("mobKeyChanceForLevel", new Class[] {int.class}, 2));
    Cache.MOB_KEY_BASE_CHANCE = .3;
    assertEquals(.4, call("mobKeyChanceForLevel", new Class[] {int.class}, 2));
    assertEquals(.5, call("mobKeyChanceForLevel", new Class[] {int.class}, 99));
    Cache.MOB_KEY_BASE_CHANCE = -1;
    assertEquals(0d, call("mobKeyChanceForLevel", new Class[] {int.class}, 0));
  }

  @Test
  void emptyDisplayPathHasSafeFallback() throws Exception {
    assertEquals("Item", call("resolveDisplayNameFromPath", new Class[] {String.class}, ""));
  }

  @Test
  void rarityModelsHandleTuningAndSelectEveryTier() throws Exception {
    for (var rarity : KeyService.Rarity.values()) {
      assertTrue(rarity.displayNameHex().contains("Spawner Key"));
      assertTrue(rarity.displayNameHex("Mob Key").contains("Mob Key"));
    }
    var rng = mock(ThreadLocalRandom.class);
    try (var random = mockStatic(ThreadLocalRandom.class)) {
      random.when(ThreadLocalRandom::current).thenReturn(rng);
      when(rng.nextDouble(anyDouble())).thenAnswer(i -> Math.nextDown((double) i.getArgument(0)));
      when(rng.nextDouble(anyDouble(), anyDouble())).thenReturn(0d);
      for (int i = 0; i < 5; i++) {
        Cache.commonChance = i == 0 ? 1 : 0;
        Cache.uncommonChance = i == 1 ? 1 : 0;
        Cache.rareChance = i == 2 ? 1 : 0;
        Cache.epicChance = i == 3 ? 1 : 0;
        Cache.legendaryChance = i == 4 ? 1 : 0;
        assertEquals(KeyService.Rarity.values()[i], call("rollRarity", new Class[] {int.class}, 0));
      }
      Cache.commonChance =
          Cache.uncommonChance = Cache.rareChance = Cache.epicChance = Cache.legendaryChance = 0;
      assertEquals(KeyService.Rarity.COMMON, call("rollRarity", new Class[] {int.class}, 2));
      Cache.commonChance = 1;
      Cache.rarityBiasPerLevel = 1;
      Cache.rarityBiasExponent = 2;
      call("rollRarity", new Class[] {int.class}, 5);
      Cache.jitterMax = .1;
      for (String model : List.of("EXP", "LOGISTIC", "other", "LINEAR")) {
        Cache.rarityWeightModel = model;
        call("rollRarity", new Class[] {int.class}, 10);
      }
    }
  }

  @Test
  void encodedLootRespectsCapsAndStylesKeysWithStableMetadata() throws Exception {
    var table =
        new LootTable(
            "loot",
            List.of(
                "v.coal 1-1 1 bad",
                "v.iron_ingot 2-2 1 common",
                "v.gold_ingot 1-3 1 uncommon",
                "v.diamond 1-1 1 rare",
                "v.emerald 1-2 1 epic",
                "v.nether_star 1-1 1 legendary"),
            .01);
    TableLoader.get().put("loot", table);
    when(spawner.getLootTable()).thenReturn("loot");
    var types =
        new Class[] {String.class, KeyService.Rarity.class, int.class, long.class, int.class};
    for (String id : Arrays.asList(null, "", "missing"))
      assertTrue(
          ((List<?>) call("encodeLootForKey", types, id, KeyService.Rarity.COMMON, 1, 1L, 3))
              .isEmpty());
    assertTrue(
        ((List<?>) call("encodeLootForKey", types, "loot", KeyService.Rarity.COMMON, 1, 1L, 0))
            .isEmpty());
    TableLoader.get().put("empty", new LootTable("empty", List.of(), 0));
    assertTrue(
        ((List<?>) call("encodeLootForKey", types, "empty", KeyService.Rarity.COMMON, 1, 1L, 3))
            .isEmpty());
    for (var rarity : KeyService.Rarity.values()) {
      var encoded = (List<?>) call("encodeLootForKey", types, "loot", rarity, 1, 4L, 9);
      assertTrue(encoded.size() >= 5);
      var key = new ItemStack(Material.TRIPWIRE_HOOK);
      call(
          "applyKeyStyling",
          new Class[] {
            ItemStack.class, KeyService.Rarity.class, long.class, String.class, String.class
          },
          key,
          rarity,
          42L,
          "Test Key",
          "loot");
      var meta = key.getItemMeta();
      assertTrue(meta.getDisplayName().contains("Test Key"));
      assertTrue(meta.hasEnchant(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA));
      assertTrue(
          meta.getPersistentDataContainer()
              .has(new NamespacedKey(plugin, "encodedloot"), PersistentDataType.STRING));
      assertEquals(
          42L,
          meta.getPersistentDataContainer()
              .get(new NamespacedKey(plugin, "keysalt"), PersistentDataType.LONG));
    }
    var key = new ItemStack(Material.TRIPWIRE_HOOK);
    call(
        "applyKeyStyling",
        new Class[] {
          ItemStack.class, KeyService.Rarity.class, long.class, String.class, String.class
        },
        key,
        KeyService.Rarity.COMMON,
        1L,
        "Test",
        "missing");
    assertFalse(
        key.getItemMeta()
            .getPersistentDataContainer()
            .has(new NamespacedKey(plugin, "encodedloot"), PersistentDataType.STRING));
    call(
        "applyKeyStyling",
        new Class[] {
          ItemStack.class, KeyService.Rarity.class, long.class, String.class, String.class
        },
        new ItemStack(Material.AIR),
        KeyService.Rarity.COMMON,
        1L,
        "Test",
        "missing");
    assertNotNull(call("jitterFor", new Class[] {long.class, String.class}, 1L, null));
  }

  @Test
  void displayFallbackAndPercentFormattingAreReadable() throws Exception {
    assertEquals(
        "Item", call("resolveDisplayNameFromPath", new Class[] {String.class}, (Object) null));
    assertEquals(
        "Iron ingot",
        call("resolveDisplayNameFromPath", new Class[] {String.class}, "v.IRON_INGOT"));
    when(api.getCreator().getItemFromPath("air")).thenReturn(new ItemStack(Material.AIR));
    assertEquals("Air", call("resolveDisplayNameFromPath", new Class[] {String.class}, "air"));
    when(api.getCreator().getItemFromPath("error")).thenThrow(new IllegalArgumentException());
    assertEquals("Error", call("resolveDisplayNameFromPath", new Class[] {String.class}, "error"));
    var named = new ItemStack(Material.DIAMOND);
    when(api.getCreator().getItemFromPath("gem")).thenReturn(named);
    assertEquals("Gem", call("resolveDisplayNameFromPath", new Class[] {String.class}, "gem"));
    var meta = named.getItemMeta();
    meta.setDisplayName("Named");
    named.setItemMeta(meta);
    assertEquals("Named", call("resolveDisplayNameFromPath", new Class[] {String.class}, "gem"));
    assertEquals("10%", call("formatPct", new Class[] {double.class}, 9.96));
    assertEquals("2%", call("formatPct", new Class[] {double.class}, 2d));
    assertEquals("2.4%", call("formatPct", new Class[] {double.class}, 2.43));
  }

  @Test
  void unlockTimelineDropsOneKeyThenBeginsCooldown() {
    service.setDebugKeys(false);
    assertFalse(service.isDebugKeys());
    when(api.getCreator().getItemFromPath("key"))
        .thenAnswer(i -> new ItemStack(Material.TRIPWIRE_HOOK));
    service.startUnlockSequence();
    server.getScheduler().performTicks(52);
    verify(world).dropItem(any(Location.class), any(ItemStack.class));
    verify(spawner).beginCooldownFromUnlock();
    verify(entity).setCustomNameVisible(true);
    server.getScheduler().performTicks(405);
  }

  @Test
  void debugUnlockSimulatesInsteadOfDropping() {
    service.setDebugKeys(true);
    assertTrue(service.isDebugKeys());
    service.startUnlockSequence();
    server.getScheduler().performTicks(52);
    verify(world, never()).dropItem(any(), any());
    verify(spawner).beginCooldownFromUnlock();
  }

  @Test
  void lifecycleGuardsCancelBothAnimationStages() throws Exception {
    for (String method : List.of("startUnlockSequence", "windupAndDropKey", "dropKeyNow")) {
      when(spawner.isLoaded()).thenReturn(false);
      call(method, new Class[] {});
      when(spawner.isLoaded()).thenReturn(true);
      when(spawner.isDestroyed()).thenReturn(true);
      call(method, new Class[] {});
      when(spawner.isDestroyed()).thenReturn(false);
      when(spawner.getState()).thenReturn(ActiveSpawner.State.IDLE);
      if (method.equals("windupAndDropKey")) call(method, new Class[] {});
      when(spawner.getState()).thenReturn(ActiveSpawner.State.UNLOCKING);
      when(spawner.getLoc()).thenReturn(null);
      call(method, new Class[] {});
      when(spawner.getLoc()).thenReturn(new Location(null, 0, 0, 0));
      call(method, new Class[] {});
      when(spawner.getLoc()).thenReturn(loc);
    }
    for (String method : List.of("startUnlockSequence", "windupAndDropKey"))
      for (int reason = 0; reason < 3; reason++) {
        call(method, new Class[] {});
        if (reason == 0) when(spawner.isLoaded()).thenReturn(false);
        if (reason == 1) when(spawner.isDestroyed()).thenReturn(true);
        if (reason == 2) when(spawner.getState()).thenReturn(ActiveSpawner.State.IDLE);
        server.getScheduler().performOneTick();
        when(spawner.isLoaded()).thenReturn(true);
        when(spawner.isDestroyed()).thenReturn(false);
        when(spawner.getState()).thenReturn(ActiveSpawner.State.UNLOCKING);
      }
    call("dropKeyNow", new Class[] {});
    when(api.getCreator().getItemFromPath("key")).thenReturn(new ItemStack(Material.AIR));
    call("dropKeyNow", new Class[] {});
    verify(world, never()).dropItem(any(), any());
  }

  @Test
  void mobKeyDropsRequireConfiguredTableAndChance() throws Exception {
    service.maybeDropMobKeyAt(null);
    service.maybeDropMobKeyAt(new Location(null, 0, 0, 0));
    service.maybeDropMobKeyAt(loc);
    when(spawner.getMobLootTable()).thenReturn("missing");
    service.maybeDropMobKeyAt(loc);
    TableLoader.get().put("mob", new LootTable("mob", List.of("v.diamond 1-1 1 common"), 0));
    when(spawner.getMobLootTable()).thenReturn("mob");
    Cache.MOB_KEY_BASE_CHANCE = 0;
    Cache.MOB_KEY_CHANCE_PER_LEVEL = 0;
    Cache.MOB_KEY_MAX_CHANCE = 1;
    var rng = mock(ThreadLocalRandom.class);
    try (var random = mockStatic(ThreadLocalRandom.class)) {
      random.when(ThreadLocalRandom::current).thenReturn(rng);
      when(rng.nextDouble()).thenReturn(0d);
      service.maybeDropMobKeyAt(loc);
      verify(world, never()).dropItem(any(), any());
      Cache.MOB_KEY_BASE_CHANCE = 1;
      service.maybeDropMobKeyAt(loc);
      when(api.getCreator().getItemFromPath("mobkey")).thenReturn(new ItemStack(Material.AIR));
      service.maybeDropMobKeyAt(loc);
      when(api.getCreator().getItemFromPath("mobkey"))
          .thenAnswer(i -> new ItemStack(Material.TRIPWIRE_HOOK));
      when(rng.nextBoolean()).thenReturn(true, false);
      service.maybeDropMobKeyAt(loc);
      verify(world).dropItem(any(), any());
      verify(entity).setCustomNameVisible(true);
    }
    call(
        "dropMobKeyNow",
        new Class[] {Location.class, String.class},
        new Location(null, 0, 0, 0),
        "mob");
    when(entity.isDead()).thenReturn(true);
    server.getScheduler().performOneTick();
    when(entity.isDead()).thenReturn(false);
    when(entity.isValid()).thenReturn(false);
    call("startCritTrail", new Class[] {Entity.class, int.class}, entity, 5);
    server.getScheduler().performTicks(2);
    call("startCritTrail", new Class[] {Entity.class, int.class}, null, 5);
    server.getScheduler().performTicks(2);
  }

  @Test
  void rarityNegativeLinearAndKeyRollAmountOverrides() throws Exception {
    Cache.commonScore = -1;
    Cache.rarityBiasPerLevel = 1;
    Cache.rarityBiasExponent = 1;
    call("rollRarity", new Class[] {int.class}, 3);
    Cache.rarityBiasExponent = 2;
    call("rollRarity", new Class[] {int.class}, 3);
    var cfg = new org.bukkit.configuration.file.YamlConfiguration();
    cfg.set("drops", List.of("v.coal 1-1 1 bad"));
    cfg.set("amounts", List.of("common 2-2"));
    var table = LootTable.fromSection("fixed", cfg, 0);
    TableLoader.get().put("fixed", table);
    var key = new ItemStack(Material.TRIPWIRE_HOOK);
    call(
        "applyKeyStyling",
        new Class[] {
          ItemStack.class, KeyService.Rarity.class, long.class, String.class, String.class
        },
        key,
        KeyService.Rarity.COMMON,
        4L,
        "Key",
        "fixed");
    assertTrue(key.getItemMeta().getLore().getFirst().contains("Drops §f2 §7"));
  }

  @Test
  void zeroWeightsAndCappedTiersCannotBeEncoded() throws Exception {
    for (String line : List.of("v.coal 1-1 0 common", "v.star 1-1 1 legendary")) {
      TableLoader.get().put("limited", new LootTable("limited", List.of(line), 0));
      assertTrue(
          ((List<?>)
                  call(
                      "encodeLootForKey",
                      new Class[] {
                        String.class, KeyService.Rarity.class, int.class, long.class, int.class
                      },
                      "limited",
                      KeyService.Rarity.COMMON,
                      1,
                      1L,
                      2))
              .isEmpty());
    }
  }

  @Test
  void largeWeightsDoNotWrapIntoAlmostImpossibleLoot() throws Exception {
    TableLoader.get()
        .put("large", new LootTable("large", List.of("v.diamond 1-1 3000000 common"), 0));
    var enc =
        (List<?>)
            call(
                "encodeLootForKey",
                new Class[] {
                  String.class, KeyService.Rarity.class, int.class, long.class, int.class
                },
                "large",
                KeyService.Rarity.COMMON,
                1,
                1L,
                1);
    var f = enc.getFirst().getClass().getDeclaredField("w");
    f.setAccessible(true);
    assertEquals(Integer.MAX_VALUE, f.get(enc.getFirst()));
  }

  @Test
  void finiteHugeWeightsRemainSelectable() throws Exception {
    TableLoader.get()
        .put(
            "huge",
            new LootTable(
                "huge",
                List.of(
                    "v.diamond 1-1 1.7976931348623157E308 common",
                    "v.emerald 1-1 1.7976931348623157E308 common"),
                0));
    Cache.commonScore = 0;
    var rng = mock(ThreadLocalRandom.class);
    try (var random = mockStatic(ThreadLocalRandom.class)) {
      random.when(ThreadLocalRandom::current).thenReturn(rng);
      when(rng.nextDouble(anyDouble()))
          .thenAnswer(
              i -> {
                double bound = i.getArgument(0);
                assertTrue(Double.isFinite(bound));
                assertTrue(bound > 0);
                return 0d;
              });
      var encoded =
          (List<?>)
              call(
                  "encodeLootForKey",
                  new Class[] {
                    String.class, KeyService.Rarity.class, int.class, long.class, int.class
                  },
                  "huge",
                  KeyService.Rarity.COMMON,
                  1,
                  1L,
                  2);
      assertEquals(2, encoded.size());
      for (var entry : encoded) {
        var field = entry.getClass().getDeclaredField("w");
        field.setAccessible(true);
        assertEquals(Integer.MAX_VALUE, field.get(entry));
      }
    }
  }

  @Test
  void selectionUsesHalfOpenWeightedIntervals() throws Exception {
    TableLoader.get()
        .put(
            "bounds",
            new LootTable(
                "bounds",
                List.of("v.coal 1-1 1 common", "v.air 1-1 0 common", "v.diamond 1-1 1 common"),
                0));
    var rng = mock(ThreadLocalRandom.class);
    try (var random = mockStatic(ThreadLocalRandom.class)) {
      random.when(ThreadLocalRandom::current).thenReturn(rng);
      for (boolean last : new boolean[] {false, true}) {
        when(rng.nextDouble(anyDouble()))
            .thenAnswer(i -> last ? Math.nextDown((double) i.getArgument(0)) : 0d);
        var entries =
            (List<?>)
                call(
                    "encodeLootForKey",
                    new Class[] {
                      String.class, KeyService.Rarity.class, int.class, long.class, int.class
                    },
                    "bounds",
                    KeyService.Rarity.COMMON,
                    1,
                    1L,
                    1);
        var field = entries.getFirst().getClass().getDeclaredField("type");
        field.setAccessible(true);
        assertEquals(last ? "v.diamond" : "v.coal", field.get(entries.getFirst()));
      }
    }
  }
}
