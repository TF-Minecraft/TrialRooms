package net.tfminecraft.trialrooms.dungeon.loot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import io.lumine.mythic.lib.api.item.NBTItem;
import net.Indyuce.mmoitems.ItemStats;
import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.api.item.mmoitem.LiveMMOItem;
import net.Indyuce.mmoitems.api.player.PlayerData;
import net.Indyuce.mmoitems.manager.StatManager;
import net.Indyuce.mmoitems.stat.data.DoubleData;
import net.Indyuce.mmoitems.stat.data.StringListData;
import net.Indyuce.mmoitems.stat.type.ItemStat;
import net.Indyuce.mmoitems.stat.type.NameData;
import net.Indyuce.mmoitems.stat.type.StatHistory;
import net.tfminecraft.advancedcrafting.loaders.IngredientLoader;
import net.tfminecraft.advancedcrafting.loaders.RecipeLoader;
import net.tfminecraft.advancedcrafting.objects.crafting.CraftingRecipe;
import net.tfminecraft.advancedcrafting.objects.data.StatData;
import net.tfminecraft.advancedcrafting.objects.ingredients.Ingredient;
import net.tfminecraft.advancedcrafting.objects.stats.StatModifier;
import net.tfminecraft.advancedcrafting.utils.CraftStatCalculator;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;

class MmoLootForgeTest {

  MmoLootForge forge = new MmoLootForge(Logger.getLogger("forge-test"));
  ItemAPI items = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
  MockedStatic<TLibs> tlibs;
  MockedStatic<NBTItem> nbt;
  MMOItems oldPlugin;

  @BeforeEach
  void setUp() throws Exception {
    tlibs = mockStatic(TLibs.class);
    tlibs.when(TLibs::getItemAPI).thenReturn(items);
    nbt = mockStatic(NBTItem.class);
    nbt.when(() -> NBTItem.get(any(ItemStack.class))).thenReturn(mock(NBTItem.class));
    oldPlugin = MMOItems.plugin;
    if (io.lumine.mythic.lib.MythicLib.plugin == null) {
      // MMOItems' item classes read MythicLib's version when they load
      io.lumine.mythic.lib.MythicLib.plugin = mock(io.lumine.mythic.lib.MythicLib.class, RETURNS_DEEP_STUBS);
      when(io.lumine.mythic.lib.MythicLib.plugin.getName()).thenReturn("MythicLib");
      when(io.lumine.mythic.lib.MythicLib.plugin.namespace()).thenReturn("mythiclib");
    }
    // MMOItems' stat list reads MMOItems' own server settings when it loads: load it once on a stand-in
    MMOItems.plugin = mock(MMOItems.class, RETURNS_DEEP_STUBS);
    when(MMOItems.plugin.getName()).thenReturn("MMOItems");
    when(MMOItems.plugin.namespace()).thenReturn("mmoitems");
    assertNotNull(ItemStats.NAME);
    MMOItems.plugin = oldPlugin;
  }

  @AfterEach
  void tearDown() {
    MmoLootForge.builder = MmoLootForge::build;
    tlibs.close();
    nbt.close();
    MMOItems.plugin = oldPlugin;
  }

  static Ingredient ingredient(String id, String type, int tier) {
    Ingredient i = mock(Ingredient.class, RETURNS_DEEP_STUBS);
    when(i.getId()).thenReturn(id);
    when(i.getIngredientData().getType().getId()).thenReturn(type);
    when(i.getIngredientData().getTier()).thenReturn(tier);
    return i;
  }

  @Test
  void recipesAreMadeOfTheStandardMaterial() {
    CraftingRecipe r = mock(CraftingRecipe.class);
    when(r.getMainType()).thenReturn("Metal");
    HashMap<String, Integer> parts = new LinkedHashMap<>();
    parts.put("metal", 4);
    parts.put("feather", 4);
    when(r.getRecipe()).thenReturn(parts);
    when(r.getTemplate()).thenReturn("helmets.light_custom_helmet");
    StatData data = mock(StatData.class);
    when(data.getModifiers()).thenReturn(List.of(new StatModifier("ARMOR", 2.5), new StatModifier("max_item_damage", 300)));
    try (MockedStatic<RecipeLoader> recipes = mockStatic(RecipeLoader.class);
        MockedStatic<IngredientLoader> ingredients = mockStatic(IngredientLoader.class);
        MockedStatic<CraftStatCalculator> calc = mockStatic(CraftStatCalculator.class)) {
      recipes.when(() -> RecipeLoader.getByString("light_helmet")).thenReturn(r);
      Ingredient steel = ingredient("steel_ingot", "metal", 2);
      List<Ingredient> all = List.of(ingredient("goose", "feather", 2), ingredient("hen", "feather", 1),
          ingredient("crow", "feather", 1), steel);
      ingredients.when(IngredientLoader::get).thenReturn(all);
      ingredients.when(() -> IngredientLoader.getByString("steel_ingot")).thenReturn(steel);
      Map<String, Integer> expect = Map.of("ingredient.steel_ingot", 4, "ingredient.crow", 4);
      calc.when(() -> CraftStatCalculator.compute(eq(r), eq(expect))).thenReturn(data);
      calc.when(() -> CraftStatCalculator.collectManagedStatIds(r)).thenReturn(Set.of("armor", "max_item_damage"));

      LootForge.Recipe made = forge.recipe("light_helmet", Map.of("metal", "steel_ingot"));
      assertEquals("helmets.light_custom_helmet", made.template());
      assertEquals(Map.of("armor", 2.5), made.stats(), "durability left out: dungeon items never break");
      assertEquals(Set.of("armor", "max_item_damage"), made.managed());

      assertNull(forge.recipe("nothing", Map.of("metal", "steel_ingot")));
      assertNull(forge.recipe("light_helmet", Map.of("wood", "maple")), "no standard for its main type");
      assertNull(forge.recipe("light_helmet", Map.of("metal", "stel_ingot")), "a standard AC doesn't know");
      ingredients.when(IngredientLoader::get).thenReturn(List.of());
      assertNull(forge.recipe("light_helmet", Map.of("metal", "steel_ingot")), "no feather at all");
    }
  }

  @Test
  void classesComeFromTheTemplate() {
    ItemStack it = mock(ItemStack.class);
    when(it.getType()).thenReturn(Material.IRON_HELMET);
    when(items.getCreator().getItemFromPath("m.helmets.heavy")).thenReturn(it);
    ItemStack air = mock(ItemStack.class);
    when(air.getType()).thenReturn(Material.AIR);
    when(items.getCreator().getItemFromPath("m.helmets.air")).thenReturn(air);
    when(items.getCreator().getItemFromPath("m.helmets.missing")).thenReturn(null);
    try (MockedConstruction<LiveMMOItem> live = mockConstruction(LiveMMOItem.class, (m, c) ->
        when(m.getData(ItemStats.REQUIRED_CLASS)).thenReturn(new StringListData(List.of("Juggernaut"))))) {
      assertEquals(List.of("Juggernaut"), forge.classes("helmets.heavy"));
      assertNull(forge.classes("helmets.missing"));
      assertNull(forge.classes("helmets.air"));
    }
    try (MockedConstruction<LiveMMOItem> live = mockConstruction(LiveMMOItem.class)) {
      assertEquals(List.of(), forge.classes("helmets.heavy"), "no class list: anyone");
    }
  }

  @Test
  void aPlayersClassIsTheOneItemsCheck() {
    Player p = mock(Player.class);
    PlayerData data = mock(PlayerData.class, RETURNS_DEEP_STUBS);
    when(data.getRPG().getClassName()).thenReturn("Warden");
    try (MockedStatic<PlayerData> pd = mockStatic(PlayerData.class)) {
      pd.when(() -> PlayerData.get(p)).thenReturn(data);
      assertEquals("Warden", forge.playerClass(p));
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  @Test
  void itemsAreWrittenLikeCraftedOnes() throws Exception {
    MMOItems plugin = mock(MMOItems.class);
    StatManager stats = mock(StatManager.class);
    when(plugin.getStats()).thenReturn(stats);
    ItemStat armor = mock(ItemStat.class);
    ItemStat health = mock(ItemStat.class);
    when(stats.get("ARMOR")).thenReturn(armor);
    when(stats.get("MAX_HEALTH")).thenReturn(health);
    MMOItems.plugin = plugin;
    ItemStack it = mock(ItemStack.class);
    when(it.getType()).thenReturn(Material.IRON_HELMET);
    when(items.getCreator().getItemFromPath("m.helmets.heavy")).thenReturn(it);
    ItemStack built = mock(ItemStack.class);
    ItemStack skinned = mock(ItemStack.class);
    when(items.getArmorMerger().merge(built, Optional.empty(), "ia.tfmc_armor:x_helmet")).thenReturn(skinned);
    StatHistory armorHist = mock(StatHistory.class);
    DoubleData og = new DoubleData(9);
    when(armorHist.getOriginalData()).thenReturn(og);
    StatHistory nameHist = mock(StatHistory.class);
    NameData ogName = new NameData("Template");
    when(nameHist.getOriginalData()).thenReturn(ogName);
    LootKit.Rarity epic = LootCoreTest.shipped().rarity("epic");
    LootKit.Base base = LootCoreTest.shipped().base("heavy_helmet");
    Map<String, Double> planned = new LinkedHashMap<>();
    planned.put("armor", 7.0);
    planned.put("max_health", 1.5);
    planned.put("glow", 1.0);
    MmoLootForge.builder = m -> built;
    LootRoller.Plan plan = new LootRoller.Plan(base, epic, 1, "Heavy Seithr Helmet of the Thaw", planned, 2,
        "ia.tfmc_armor:x_helmet", List.of("Epic dungeon item, tier 1"));
    try (MockedConstruction<LiveMMOItem> live = mockConstruction(LiveMMOItem.class, (m, c) -> {
      when(m.computeStatHistory(armor)).thenReturn(armorHist);
      when(m.computeStatHistory(health)).thenReturn(null);
      when(m.computeStatHistory(ItemStats.NAME)).thenReturn(nameHist);
    })) {
      assertSame(skinned, forge.make(plan, "helmets.heavy", Set.of("armor", "durability"), "Dungeon Gem"));
      LiveMMOItem m = live.constructed().getFirst();
      assertEquals(0, og.getValue(), "the template's own armour zeroed");
      verify(armorHist).clearExternalData();
      verify(armorHist).registerExternalData(argThat(d -> ((DoubleData) d).getValue() == 7.0));
      verify(m).setData(eq(health), argThat(d -> ((DoubleData) d).getValue() == 1.5));
      assertEquals("§x§b§0§5§c§f§fHeavy Seithr Helmet of the Thaw", ogName.getString());
      verify(m).setData(eq(ItemStats.GEM_SOCKETS), argThat(d -> d.toString() != null));
      verify(m).setData(eq(ItemStats.UNBREAKABLE), any());
      verify(m).setData(eq(ItemStats.LORE), argThat(d -> ((StringListData) d).getList().equals(List.of("§7Epic dungeon item, tier 1"))));
    }
    // without a look, or without a template
    LootRoller.Plan plain = new LootRoller.Plan(base, epic, 1, "Plain", Map.of(), 0, null, List.of());
    try (MockedConstruction<LiveMMOItem> live = mockConstruction(LiveMMOItem.class, (m, c) -> {
      when(m.computeStatHistory(ItemStats.NAME)).thenReturn(null);
      when(m.computeStatHistory(armor)).thenReturn(mock(StatHistory.class));
    })) {
      assertSame(built, forge.make(plain, "helmets.heavy", Set.of("armor"), "Dungeon Gem"));
      when(items.getCreator().getItemFromPath("m.helmets.none")).thenReturn(null);
      assertNull(forge.make(plain, "helmets.none", Set.of(), "Dungeon Gem"));
    }
    try (MockedConstruction<LiveMMOItem> live = mockConstruction(LiveMMOItem.class, (m, c) -> {
      StatHistory odd = mock(StatHistory.class);
      when(odd.getOriginalData()).thenReturn(new StringListData(List.of()));
      when(m.computeStatHistory(ItemStats.NAME)).thenReturn(odd);
      when(m.computeStatHistory(armor)).thenReturn(odd);
    })) {
      forge.make(plain, "helmets.heavy", Set.of("armor"), "Dungeon Gem"); // odd originals are left alone
    }
  }

  @Test
  void theBuilderIsMmoItems() {
    // MMOItems' builder loads only with a server's attribute registry: MockBukkit has one
    org.mockbukkit.mockbukkit.MockBukkit.mock();
    try {
      MMOItems.plugin = mock(MMOItems.class, RETURNS_DEEP_STUBS);
      when(MMOItems.plugin.getName()).thenReturn("MMOItems");
      when(MMOItems.plugin.namespace()).thenReturn("mmoitems");
      var builder = mock(net.Indyuce.mmoitems.api.item.build.ItemStackBuilder.class);
      ItemStack built = mock(ItemStack.class);
      when(builder.build()).thenReturn(built);
      var mmo = mock(net.Indyuce.mmoitems.api.item.mmoitem.MMOItem.class);
      when(mmo.newBuilder()).thenReturn(builder);
      assertSame(built, MmoLootForge.build(mmo));
    } finally {
      org.mockbukkit.mockbukkit.MockBukkit.unmock();
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  @Test
  void gemsAreMadeAndSocketedTheMmoItemsWay() {
    MMOItems plugin = mock(MMOItems.class);
    StatManager stats = mock(StatManager.class);
    when(plugin.getStats()).thenReturn(stats);
    ItemStat health = mock(ItemStat.class);
    when(stats.get("MAX_HEALTH")).thenReturn(health);
    MMOItems.plugin = plugin;
    ItemStack template = mock(ItemStack.class);
    when(template.getType()).thenReturn(Material.AMETHYST_SHARD);
    when(items.getCreator().getItemFromPath("m.gemstones.larimar")).thenReturn(template);
    when(items.getCreator().getItemFromPath("m.gemstones.none")).thenReturn(null);
    ItemStack built = mock(ItemStack.class);
    MmoLootForge.builder = m -> built;
    LootRoller.GemPlan plan = new LootRoller.GemPlan(LootCoreTest.shipped().rarity("rare"), 1, "Glacial Rimestone",
        "max_health", 1.5, List.of("Rare dungeon gem, tier 1"));
    try (MockedConstruction<LiveMMOItem> live = mockConstruction(LiveMMOItem.class)) {
      assertSame(built, forge.makeGem(plan, "gemstones.larimar", "Dungeon Gem"));
      LiveMMOItem m = live.constructed().getFirst();
      verify(m).setData(eq(ItemStats.GEM_COLOR), argThat(d -> "Dungeon Gem".equals(((net.Indyuce.mmoitems.stat.data.StringData) d).getString())));
      verify(m).setData(eq(health), argThat(d -> ((DoubleData) d).getValue() == 1.5));
      assertNull(forge.makeGem(plan, "gemstones.none", "Dungeon Gem"));
    }
    Player p = mock(Player.class);
    ItemStack item = mock(ItemStack.class);
    ItemStack gem = mock(ItemStack.class);
    ItemStack socketed = mock(ItemStack.class);
    // (TLibs' merger hands back the rebuilt item as it is when the old one is empty, as this stand-in is)
    when(item.getType()).thenReturn(Material.AIR);
    ItemStack merged = socketed;
    try (MockedStatic<PlayerData> pd = mockStatic(PlayerData.class);
        MockedStatic<net.Indyuce.mmoitems.api.Type> types = mockStatic(net.Indyuce.mmoitems.api.Type.class)) {
      pd.when(() -> PlayerData.get(p)).thenReturn(mock(PlayerData.class));
      for (var result : List.of(net.Indyuce.mmoitems.api.interaction.GemStone.ResultType.SUCCESS,
          net.Indyuce.mmoitems.api.interaction.GemStone.ResultType.FAILURE,
          net.Indyuce.mmoitems.api.interaction.GemStone.ResultType.NONE)) {
        var applied = mock(net.Indyuce.mmoitems.api.interaction.GemStone.ApplyResult.class);
        when(applied.getType()).thenReturn(result);
        when(applied.getResult()).thenReturn(socketed);
        try (MockedConstruction<net.Indyuce.mmoitems.api.interaction.GemStone> stones =
            mockConstruction(net.Indyuce.mmoitems.api.interaction.GemStone.class,
                (s, c) -> when(s.applyOntoItem(any(NBTItem.class), any())).thenAnswer(i -> {
                  assertTrue(MmoLootForge.socketing(), "the guard lets the locker's own socketing through");
                  return applied;
                }))) {
          LootForge.Socketed s = forge.socket(p, item, gem);
          assertEquals(switch (result) {
            case SUCCESS -> LootForge.Socketing.DONE;
            case FAILURE -> LootForge.Socketing.BROKE;
            default -> LootForge.Socketing.NO_FIT;
          }, s.result());
          assertEquals(result == net.Indyuce.mmoitems.api.interaction.GemStone.ResultType.SUCCESS ? merged : null, s.item(),
              "the look TLibs keeps is put back on the rebuilt item");
          assertFalse(MmoLootForge.socketing());
        }
      }
    }
  }

  @Test
  void gemsNeverComeOutOfOrGoIntoDungeonItemsOutsideTheLocker() {
    ItemStack dungeon = mock(ItemStack.class);
    var guard = new ReforgeGuard(it -> it == dungeon);
    var read = mock(net.Indyuce.mmoitems.api.item.mmoitem.VolatileMMOItem.class, RETURNS_DEEP_STUBS);
    when(read.getNBT().getItem()).thenReturn(dungeon);
    var plainRead = mock(net.Indyuce.mmoitems.api.item.mmoitem.VolatileMMOItem.class, RETURNS_DEEP_STUBS);
    when(plainRead.getNBT().getItem()).thenReturn(mock(ItemStack.class));
    var fresh = mock(net.Indyuce.mmoitems.api.item.mmoitem.MMOItem.class);
    var unsocket = mock(net.Indyuce.mmoitems.api.event.item.UnsocketGemStoneEvent.class);
    when(unsocket.getTargetItem()).thenReturn(read);
    guard.onUnsocket(unsocket);
    verify(unsocket).setCancelled(true);
    var unsocketPlain = mock(net.Indyuce.mmoitems.api.event.item.UnsocketGemStoneEvent.class);
    when(unsocketPlain.getTargetItem()).thenReturn(fresh);
    guard.onUnsocket(unsocketPlain);
    verify(unsocketPlain, never()).setCancelled(anyBoolean());
    var gemIn = mock(net.Indyuce.mmoitems.api.event.item.ApplyGemStoneEvent.class);
    when(gemIn.getGemStone()).thenReturn(read);
    guard.onSocket(gemIn);
    verify(gemIn).setCancelled(true);
    verify(gemIn).setResult(net.Indyuce.mmoitems.api.interaction.GemStone.ResultType.NONE);
    var into = mock(net.Indyuce.mmoitems.api.event.item.ApplyGemStoneEvent.class);
    when(into.getGemStone()).thenReturn(plainRead);
    when(into.getTargetItem()).thenReturn(read);
    guard.onSocket(into);
    verify(into).setCancelled(true);
    var normal = mock(net.Indyuce.mmoitems.api.event.item.ApplyGemStoneEvent.class);
    when(normal.getGemStone()).thenReturn(plainRead);
    when(normal.getTargetItem()).thenReturn(plainRead);
    guard.onSocket(normal);
    verify(normal, never()).setCancelled(anyBoolean());
    MmoLootForge.socketing = true;
    try {
      var locker = mock(net.Indyuce.mmoitems.api.event.item.ApplyGemStoneEvent.class);
      when(locker.getGemStone()).thenReturn(read);
      guard.onSocket(locker);
      verify(locker, never()).setCancelled(anyBoolean());
    } finally {
      MmoLootForge.socketing = false;
    }
  }

  @Test
  void otherConsumablesNeverWorkOnDungeonItems() {
    ItemStack dungeon = mock(ItemStack.class);
    var guard = new ReforgeGuard(it -> it == dungeon);
    NBTItem marked = mock(NBTItem.class);
    when(marked.getItem()).thenReturn(dungeon);
    NBTItem plain = mock(NBTItem.class);
    when(plain.getItem()).thenReturn(mock(ItemStack.class));
    var deconstruct = mock(net.Indyuce.mmoitems.api.event.item.DeconstructItemEvent.class);
    when(deconstruct.getDeconstructedItem()).thenReturn(marked);
    guard.onDeconstruct(deconstruct);
    verify(deconstruct).setCancelled(true);
    var repair = mock(net.Indyuce.mmoitems.api.event.item.RepairItemEvent.class);
    when(repair.getTargetItem()).thenReturn(plain);
    guard.onRepair(repair);
    verify(repair, never()).setCancelled(anyBoolean());
    var soulbound = mock(net.Indyuce.mmoitems.api.event.item.ApplySoulboundEvent.class);
    when(soulbound.getTargetItem()).thenReturn(null);
    guard.onSoulbound(soulbound);
    verify(soulbound, never()).setCancelled(anyBoolean());
    var soulbound2 = mock(net.Indyuce.mmoitems.api.event.item.ApplySoulboundEvent.class);
    when(soulbound2.getTargetItem()).thenReturn(marked);
    guard.onSoulbound(soulbound2);
    verify(soulbound2).setCancelled(true);
    var read = mock(net.Indyuce.mmoitems.api.item.mmoitem.VolatileMMOItem.class, RETURNS_DEEP_STUBS);
    when(read.getNBT().getItem()).thenReturn(dungeon);
    var upgrade = mock(net.Indyuce.mmoitems.api.event.item.UpgradeItemEvent.class);
    when(upgrade.getTargetItem()).thenReturn(read);
    guard.onUpgrade(upgrade);
    verify(upgrade).setCancelled(true);
    var upgradePlain = mock(net.Indyuce.mmoitems.api.event.item.UpgradeItemEvent.class);
    when(upgradePlain.getTargetItem()).thenReturn(mock(net.Indyuce.mmoitems.api.item.mmoitem.MMOItem.class));
    guard.onUpgrade(upgradePlain);
    verify(upgradePlain, never()).setCancelled(anyBoolean());
  }

  @Test
  void dungeonItemsAreNeverReforged() {
    var guard = new ReforgeGuard(it -> it != null);
    var event = mock(net.Indyuce.mmoitems.api.event.MMOItemReforgeEvent.class, RETURNS_DEEP_STUBS);
    when(event.getReforger().getStack()).thenReturn(mock(ItemStack.class));
    guard.onReforge(event);
    verify(event).setCancelled(true);
    var plain = mock(net.Indyuce.mmoitems.api.event.MMOItemReforgeEvent.class, RETURNS_DEEP_STUBS);
    when(plain.getReforger().getStack()).thenReturn(null);
    guard.onReforge(plain);
    verify(plain, never()).setCancelled(anyBoolean());
  }

  @Test
  void smallHelpers() {
    assertTrue(MmoLootForge.durability("durability"));
    assertFalse(MmoLootForge.durability("armor"));
    assertEquals("§x§1§2§a§b§c§d", MmoLootForge.colour("#12abcd"));
  }

  static void set(Object target, String field, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(field);
    f.setAccessible(true);
    f.set(target, value);
  }
}
