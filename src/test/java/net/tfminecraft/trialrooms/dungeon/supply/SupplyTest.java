package net.tfminecraft.trialrooms.dungeon.supply;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.MockedStatic;

import io.lumine.mythic.lib.api.item.NBTItem;

class SupplyTest {

  @BeforeEach
  void setUp() {
    MockBukkit.mock();
  }

  @AfterEach
  void tearDown() {
    SupplyItems.itemModel = org.bukkit.inventory.meta.ItemMeta::setItemModel;
    MockBukkit.unmock();
  }

  static Supply supply(String id, Supply.Use use, double amount, int cooldown, List<String> kits) {
    return new Supply(id, id, new Supply.Look("SLIME_BALL", 0, null), 3, 2, use, amount, 8, cooldown, 4,
        Set.of("Slowness"), Set.of("DS_Ice_Prison"), kits);
  }

  static YamlConfiguration yml(String text) {
    YamlConfiguration y = new YamlConfiguration();
    try {
      y.loadFromString(text);
    } catch (InvalidConfigurationException e) {
      throw new IllegalStateException(e);
    }
    return y;
  }

  @Test
  void aSupplyIsCheckedAndKnowsItsKits() {
    Supply s = supply("a", Supply.Use.WARD, 0, 0, List.of("seithr"));
    assertEquals(Set.of("slowness"), s.potions());
    assertEquals(Set.of("ds_ice_prison"), s.auras());
    assertTrue(s.forKit("seithr"));
    assertFalse(s.forKit("greybox"));
    assertTrue(supply("b", Supply.Use.HEALTH, 1, 0, List.of()).forKit("any"));
    Supply.Look look = new Supply.Look("PAPER", 0, null);
    Set<String> none = Set.of();
    // a bandage with seconds and a ward with only auras, or only potions, are fine
    assertNotNull(new Supply("x", "x", look, 1, 10000, Supply.Use.BANDAGE, 0.4, 1, 0, 0, none, none, List.of()));
    assertNotNull(new Supply("x", "x", look, 1, 0, Supply.Use.WARD, 0, 0, 0, 0, none, Set.of("Frozen"), List.of()));
    assertNotNull(new Supply("x", "x", look, 1, 0, Supply.Use.WARD, 0, 0, 0, 0, Set.of("slowness"), none, List.of()));
    for (Runnable bad : List.<Runnable>of(
        () -> new Supply(null, "x", look, 1, 0, Supply.Use.HEALTH, 0, 0, 0, 0, none, none, List.of()),
        () -> new Supply(" ", "x", look, 1, 0, Supply.Use.HEALTH, 0, 0, 0, 0, none, none, List.of()),
        () -> new Supply("x", "x", look, 0, 0, Supply.Use.HEALTH, 0, 0, 0, 0, none, none, List.of()),
        () -> new Supply("x", "x", look, 1, -1, Supply.Use.HEALTH, 0, 0, 0, 0, none, none, List.of()),
        () -> new Supply("x", "x", look, 1, 0, Supply.Use.HEALTH, -0.1, 0, 0, 0, none, none, List.of()),
        () -> new Supply("x", "x", look, 1, 0, Supply.Use.HEALTH, 1.1, 0, 0, 0, none, none, List.of()),
        () -> new Supply("x", "x", look, 1, 0, Supply.Use.HEALTH, 0, -1, 0, 0, none, none, List.of()),
        () -> new Supply("x", "x", look, 1, 0, Supply.Use.HEALTH, 0, 0, -1, 0, none, none, List.of()),
        () -> new Supply("x", "x", look, 1, 0, Supply.Use.HEALTH, 0, 0, 0, -1, none, none, List.of()),
        () -> new Supply("x", "x", look, 1, 0, Supply.Use.HEALTH, 0, 0, 0, Double.POSITIVE_INFINITY, none, none,
            List.of()),
        () -> new Supply("x", "x", look, 1, 0, Supply.Use.BANDAGE, 0.4, 0, 0, 0, none, none, List.of()),
        () -> new Supply("x", "x", look, 1, 0, Supply.Use.WARD, 0, 0, 0, 0, none, none, List.of()),
        () -> new Supply("x", "x", look, 1, 10001, Supply.Use.HEALTH, 0, 0, 0, 0, none, none, List.of()))) {
      assertThrows(IllegalArgumentException.class, bad::run);
    }
  }

  @Test
  void settingsAreReadWithDefaults() {
    SupplySettings d = SupplySettings.from(null, null);
    assertEquals(3, d.campfireSeconds());
    assertEquals(1.0, d.campfireShare(1));
    assertEquals(0.5, d.campfireShare(4));
    assertEquals(0, d.campfireShare(0), "under the lowest tier listed");
    assertTrue(d.items().isEmpty());
    assertNull(d.supply(null));
    SupplySettings s = SupplySettings.from(yml("seconds: 5\nradius: 2.5\nrestore: {1: 0.8, 3: 0.2}\n"),
        yml("""
            pouch: 6
            items:
              potion: {name: Health Potion, look: {material: SLIME_BALL, model-data: 273}, stack: 3, price: 2,
                use: health, amount: 0.4, cooldown: 20}
              salts: {use: REVIVE, radius: 4, look: {item-model: "tfmc_surgery:smelling_salts"}}
              draught: {use: ward, potions: [slowness], auras: [Frozen], kits: [seithr]}
            drops: {fight: 0.1, elite: 0.3, treasure: 0.5}
            """));
    assertEquals(5, s.campfireSeconds());
    assertEquals(2.5, s.campfireRadius());
    assertEquals(0.8, s.campfireShare(2));
    assertEquals(0.2, s.campfireShare(3));
    assertEquals(6, s.pouch());
    assertEquals(List.of("potion", "salts", "draught"), List.copyOf(s.items().keySet()));
    Supply potion = s.supply("potion");
    assertEquals("Health Potion", potion.name());
    assertEquals(273, potion.look().modelData());
    assertEquals(Supply.Use.REVIVE, s.supply("salts").use());
    assertEquals("salts", s.supply("salts").name());
    assertEquals("tfmc_surgery:smelling_salts", s.supply("salts").look().itemModel());
    assertEquals("PAPER", s.supply("salts").look().material(), "a look without a material: paper");
    assertEquals("PAPER", s.supply("draught").look().material(), "no look: paper");
    assertEquals(List.of("seithr"), s.supply("draught").kits());
    assertEquals(0.1, s.fightDrop());
    assertEquals(0.3, s.eliteDrop());
    assertEquals(0.5, s.treasureDrop());
    SupplySettings noDrops = SupplySettings.from(yml("seconds: 4\n"), yml("pouch: 2\n"));
    assertEquals(4, noDrops.campfireSeconds());
    assertEquals(0.5, noDrops.campfireShare(2), "no restore section: the defaults");
    assertEquals(0, noDrops.fightDrop() + noDrops.eliteDrop() + noDrops.treasureDrop());
    assertThrows(IllegalArgumentException.class, () -> SupplySettings.from(yml("restore: {hard: 1}\n"), null));
    assertThrows(IllegalArgumentException.class, () -> SupplySettings.from(null, yml("items: {x: {use: fly}}\n")));
    assertThrows(IllegalArgumentException.class, () -> SupplySettings.from(null, yml("items: {x: 3}\n")));
    assertThrows(IllegalArgumentException.class, () -> SupplySettings.from(yml("seconds: 0\n"), null));
    assertThrows(IllegalArgumentException.class, () -> SupplySettings.from(yml("radius: 0\n"), null));
    assertThrows(IllegalArgumentException.class, () -> SupplySettings.from(null, yml("pouch: -1\n")));
    assertThrows(IllegalArgumentException.class, () -> SupplySettings.from(null, yml("drops: {fight: 2}\n")));
    assertThrows(IllegalArgumentException.class, () -> SupplySettings.from(null, yml("drops: {elite: -1}\n")));
    assertThrows(IllegalArgumentException.class, () -> SupplySettings.from(null, yml("drops: {treasure: 1.5}\n")));
    assertThrows(IllegalArgumentException.class, () -> SupplySettings.from(yml("restore: {1: 2}\n"), null));
  }

  @Test
  void itemsCarryTheirSupplyAndSayWhatTheyDo() {
    java.util.Map<org.bukkit.inventory.meta.ItemMeta, org.bukkit.NamespacedKey> models = new java.util.HashMap<>();
    SupplyItems.itemModel = models::put;
    Supply potion = new Supply("potion", "Health Potion", new Supply.Look("slime_ball", 273, null), 3, 2,
        Supply.Use.HEALTH, 0.4, 0, 20, 0, Set.of(), Set.of(), List.of());
    ItemStack it = SupplyItems.make(potion, 9);
    assertEquals(Material.SLIME_BALL, it.getType());
    assertEquals(3, it.getAmount(), "one stack at most");
    assertEquals(1, SupplyItems.make(potion, 0).getAmount());
    assertEquals("potion", SupplyItems.idOf(it));
    assertEquals(273, it.getItemMeta().getCustomModelData());
    assertNull(SupplyItems.idOf(null));
    assertNull(SupplyItems.idOf(new ItemStack(Material.AIR)));
    assertNull(SupplyItems.idOf(new ItemStack(Material.STONE)));
    Supply salts = new Supply("salts", "Smelling Salts", new Supply.Look("NO_SUCH", 0, "tfmc_surgery:smelling_salts"), 2,
        3, Supply.Use.REVIVE, 0, 0, 0, 4.5, Set.of(), Set.of(), List.of());
    ItemStack s = SupplyItems.make(salts, 1);
    assertEquals(Material.PAPER, s.getType(), "an unknown material is paper");
    assertEquals(java.util.List.of(org.bukkit.NamespacedKey.fromString("tfmc_surgery:smelling_salts")),
        java.util.List.copyOf(models.values()));
    ItemStack named = new ItemStack(Material.STONE);
    var meta = named.getItemMeta();
    meta.setDisplayName("x");
    named.setItemMeta(meta);
    assertNull(SupplyItems.idOf(named), "an item with meta but no supply id");
    ItemStack bare = mock(ItemStack.class);
    when(bare.getType()).thenReturn(Material.STONE);
    assertNull(SupplyItems.idOf(bare), "no meta at all");
    assertEquals(Material.PAPER, SupplyItems.make(new Supply("w", "w", new Supply.Look("water", 0, "Bad Key!"), 1, 0,
        Supply.Use.MANA, 0.5, 0, 0, 0, Set.of(), Set.of(), List.of()), 1).getType(), "not an item: paper");
    assertEquals(Material.PAPER, SupplyItems.make(new Supply("v", "v", new Supply.Look("AIR", 0, null), 1, 0,
        Supply.Use.MANA, 0.5, 0, 0, 0, Set.of(), Set.of(), List.of()), 1).getType());
    assertTrue(SupplyItems.lore(potion).get(0).contains("40%"));
    assertTrue(SupplyItems.lore(potion).get(1).contains("20 seconds"));
    assertTrue(SupplyItems.lore(salts).get(0).contains("4.5 blocks"));
    assertTrue(SupplyItems.lore(supply("r", Supply.Use.REVIVE, 0, 0, List.of())).get(0).contains("4 blocks"));
    assertTrue(SupplyItems.lore(supply("m", Supply.Use.MANA, 0.5, 0, List.of())).get(0).contains("mana"));
    assertTrue(SupplyItems.lore(supply("b", Supply.Use.BANDAGE, 0.4, 0, List.of())).get(1).contains("out of a fight"));
    assertTrue(SupplyItems.lore(supply("w", Supply.Use.WARD, 0, 0, List.of())).get(0).contains("slowing"));
  }

  @Test
  void mmoItemsThatRestoreHealthOrManaCountAsHealing() {
    assertFalse(MmoHealing.heals(null));
    assertFalse(MmoHealing.heals(new ItemStack(Material.AIR)));
    try (MockedStatic<NBTItem> nbt = mockStatic(NBTItem.class)) {
      NBTItem health = mock(NBTItem.class);
      when(health.hasTag("MMOITEMS_RESTORE_HEALTH")).thenReturn(true);
      NBTItem mana = mock(NBTItem.class);
      when(mana.hasTag("MMOITEMS_RESTORE_MANA")).thenReturn(true);
      NBTItem plain = mock(NBTItem.class);
      ItemStack a = new ItemStack(Material.SLIME_BALL);
      ItemStack b = new ItemStack(Material.SLIME_BALL, 2);
      ItemStack c = new ItemStack(Material.BREAD);
      nbt.when(() -> NBTItem.get(a)).thenReturn(health);
      nbt.when(() -> NBTItem.get(b)).thenReturn(mana);
      nbt.when(() -> NBTItem.get(c)).thenReturn(plain);
      assertTrue(MmoHealing.heals(a));
      assertTrue(MmoHealing.heals(b));
      assertFalse(MmoHealing.heals(c));
    }
  }
}
