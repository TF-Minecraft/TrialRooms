package net.tfminecraft.trialrooms.dungeon.locker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class LockerMenuTest {

  @TempDir Path temp;
  ServerMock server;
  Plugin plugin;
  Map<String, String> characterOf = new HashMap<>();
  LockerStore store;
  LockerService lockers;
  PlayerMock ann;
  boolean inLobby = true;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    plugin = MockBukkit.createMockPlugin();
    store = new LockerStore(temp, Logger.getLogger("menu-test"));
    lockers = new LockerService(plugin, store, new LockerServiceTest.FakeCodec(), p -> characterOf.get(p.getName()),
        p -> { });
    ann = server.addPlayer("Ann");
    characterOf.put("Ann", "c1");
    // MockBukkit has no equippable parts or material slots: go by the material's name
    LockerMenu.materialSlot = LockerMenuTest::byName;
    LockerMenu.wornOn = it -> byName(it.getType());
    LockerMenu.itemType = it -> it.getType() == Material.GOLD_NUGGET ? "RING" : it.getType() == Material.GOLD_INGOT
        ? "AMULET" : null;
  }

  @AfterEach
  void tearDown() {
    LockerMenu.wornOn = LockerMenu::wornOn;
    LockerMenu.materialSlot = Material::getEquipmentSlot;
    LockerMenu.itemType = LockerMenu::mmoType;
    LockerMenu.mythicLib = LockerMenu::mythicLibEnabled;
    LockerMenu.isGem = LockerMenu::mmoGem;
    MockBukkit.unmock();
  }

  LockerMenu menu() {
    return new LockerMenu(lockers, ann, p -> inLobby);
  }

  String last() {
    String m;
    String out = null;
    while ((m = ann.nextMessage()) != null) {
      out = m;
    }
    return out;
  }

  static org.bukkit.inventory.EquipmentSlot byName(Material m) {
    String n = m.name();
    return n.endsWith("_HELMET") ? org.bukkit.inventory.EquipmentSlot.HEAD
        : n.endsWith("_CHESTPLATE") ? org.bukkit.inventory.EquipmentSlot.CHEST
        : n.endsWith("_LEGGINGS") ? org.bukkit.inventory.EquipmentSlot.LEGS
        : n.endsWith("_BOOTS") ? org.bukkit.inventory.EquipmentSlot.FEET : org.bukkit.inventory.EquipmentSlot.HAND;
  }

  @Test
  void theMenuOpensOnlyWithALocker() {
    characterOf.remove("Ann");
    assertFalse(menu().open());
    assertTrue(last().contains("can't be opened"));
    characterOf.put("Ann", "c1");
    LockerMenu m = menu();
    assertTrue(m.open());
    assertSame(m, ann.getOpenInventory().getTopInventory().getHolder());
    assertSame(m.getInventory(), ann.getOpenInventory().getTopInventory());
    assertEquals(Material.WRITABLE_BOOK, m.getInventory().getItem(0).getType(), "the chosen loadout");
    assertEquals(Material.BOOK, m.getInventory().getItem(1).getType());
    assertEquals(Material.GRAY_STAINED_GLASS_PANE, m.getInventory().getItem(9).getType(), "an empty slot");
    assertNull(m.getInventory().getItem(LockerMenu.NEXT), "one page only");
  }

  @Test
  void itemsArePutInLoadoutsByClicking() {
    String helm = lockers.add(ann, new ItemStack(Material.DIAMOND_HELMET));
    String sword = lockers.add(ann, new ItemStack(Material.DIAMOND_SWORD));
    LockerMenu m = menu();
    m.open();
    assertEquals(Material.DIAMOND_HELMET, m.getInventory().getItem(LockerMenu.STASH).getType());
    m.click(LockerMenu.STASH + 1); // the sword
    assertEquals(sword, m.selected());
    assertTrue(m.getInventory().getItem(LockerMenu.STASH + 1).getItemMeta().getEnchantmentGlintOverride());
    m.click(9); // the helmet slot: a sword doesn't go there
    assertTrue(last().contains("doesn't go in the helmet slot"));
    m.click(18); // hotbar 1
    assertNull(m.selected());
    m.click(LockerMenu.STASH);
    m.click(LockerMenu.STASH); // a second click lets go
    assertNull(m.selected());
    m.click(LockerMenu.STASH);
    m.click(9);
    Locker l = lockers.locker(ann);
    assertEquals(Map.of(39, helm, 0, sword), l.loadout(0));
    assertEquals(Material.DIAMOND_HELMET, m.getInventory().getItem(9).getType());
    m.click(18); // nothing chosen: the slot empties
    assertEquals(Map.of(39, helm), l.loadout(0));
    m.click(LockerMenu.STASH + 20); // an empty stash place
    assertNull(m.selected());
    m.click(1);
    assertEquals(1, m.loadout());
    assertTrue(l.loadout(1).isEmpty());
    m.click(45 + 8); // nothing there in the last row
    m.click(7); // nor in the top row
    // a saved loadout naming an item that is gone shows as empty
    l.restoreSlot(1, 40, "gone");
    m.render();
    assertEquals(Material.GRAY_STAINED_GLASS_PANE, m.getInventory().getItem(13).getType());
  }

  @Test
  void bigLockersHavePages() {
    for (int i = 0; i < 30; i++) {
      lockers.add(ann, new ItemStack(Material.STONE, i + 1));
    }
    LockerMenu m = menu();
    m.open();
    assertNull(m.getInventory().getItem(LockerMenu.PREV));
    assertNotNull(m.getInventory().getItem(LockerMenu.NEXT));
    m.click(LockerMenu.NEXT);
    assertEquals(1, m.page());
    assertEquals(28, m.getInventory().getItem(LockerMenu.STASH).getAmount());
    assertNull(m.getInventory().getItem(LockerMenu.NEXT));
    m.click(LockerMenu.STASH + 1);
    assertNotNull(m.selected());
    m.click(LockerMenu.NEXT); // past the end: stays on the last page
    assertEquals(1, m.page());
    m.click(LockerMenu.PREV);
    m.click(LockerMenu.PREV);
    assertEquals(0, m.page());
  }

  @Test
  void loadoutsGoOnOnlyInTheLobby() {
    String helm = lockers.add(ann, new ItemStack(Material.DIAMOND_HELMET));
    lockers.locker(ann).assign(0, 39, helm);
    LockerMenu m = menu();
    m.open();
    inLobby = false;
    m.click(LockerMenu.WEAR);
    assertTrue(last().contains("only in a dungeon's entrance room"));
    inLobby = true;
    m.click(LockerMenu.OWN);
    assertTrue(last().contains("already in your own gear"));
    m.click(2);
    m.click(LockerMenu.WEAR);
    assertTrue(last().contains("is empty"));
    m.click(0);
    ann.setItemOnCursor(new ItemStack(Material.STONE));
    m.click(LockerMenu.WEAR);
    assertTrue(last().contains("Put down"));
    ann.setItemOnCursor(null);
    m.click(LockerMenu.WEAR);
    assertTrue(last().contains("You put on loadout 1"));
    assertTrue(((net.kyori.adventure.text.TextComponent) m.getInventory().getItem(0).getItemMeta().displayName())
        .content().endsWith("(worn)"));
    m.click(LockerMenu.WEAR);
    assertTrue(last().contains("already on"));
    m.click(9);
    assertTrue(last().contains("Take the worn loadout off first"));
    m.click(LockerMenu.OWN);
    assertTrue(last().contains("back in your own gear"));
  }

  @Test
  void failuresAreToldPlainly() throws Exception {
    String helm = lockers.add(ann, new ItemStack(Material.DIAMOND_HELMET));
    lockers.locker(ann).assign(0, 39, helm);
    LockerMenu m = menu();
    m.open();
    Path tmp = store.file(ann.getUniqueId(), "c1").resolveSibling("c1.locker.tmp");
    Files.createDirectories(tmp);
    m.click(LockerMenu.WEAR);
    assertTrue(last().contains("nothing changed"));
    Files.delete(tmp);
    m.click(LockerMenu.WEAR);
    Files.createDirectories(tmp);
    m.click(LockerMenu.OWN);
    assertTrue(last().contains("could not be saved. Tell staff"));
    Files.delete(tmp);

    LockerService broken = spy(lockers);
    doReturn(LockerService.Result.BROKEN).when(broken).wear(any(), anyInt());
    LockerMenu b = new LockerMenu(broken, ann, p -> true);
    b.click(LockerMenu.WEAR);
    assertTrue(last().contains("can't be used right now"));

    // the character goes away under an open menu
    characterOf.remove("Ann");
    m.click(LockerMenu.WEAR);
    assertTrue(m.getInventory().isEmpty());
    m.render();
    assertTrue(m.getInventory().isEmpty());
  }

  @Test
  void armourSlotsTakeOnlyTheirArmour() {
    assertTrue(LockerMenu.fits(new ItemStack(Material.DIAMOND_HELMET), 39));
    assertTrue(LockerMenu.fits(new ItemStack(Material.DIAMOND_CHESTPLATE), 38));
    assertTrue(LockerMenu.fits(new ItemStack(Material.DIAMOND_LEGGINGS), 37));
    assertTrue(LockerMenu.fits(new ItemStack(Material.DIAMOND_BOOTS), 36));
    assertFalse(LockerMenu.fits(new ItemStack(Material.DIAMOND_BOOTS), 39));
    assertTrue(LockerMenu.fits(new ItemStack(Material.DIAMOND_BOOTS), 40), "the off hand takes anything");
    assertTrue(LockerMenu.fits(new ItemStack(Material.STONE), 3));
  }

  /** Extras that record what was asked. */
  static final class FakeExtras implements LockerExtras {
    final java.util.List<String> asked = new java.util.ArrayList<>();
    java.util.List<Offer> offers = java.util.List.of(
        new Offer("heavy_helmet", "Heavy Helmet", new java.util.LinkedHashMap<>(java.util.Map.of("rare", 30))),
        new Offer("sword", "Sword", new java.util.LinkedHashMap<>(java.util.Map.of("rare", 30))));

    @Override
    public java.util.List<Offer> offers(org.bukkit.entity.Player p) {
      return offers;
    }

    @Override
    public String buy(org.bukkit.entity.Player p, String item, String rarity) {
      asked.add("buy " + item + " " + rarity);
      return "bought";
    }

    @Override
    public String socket(org.bukkit.entity.Player p, String gem, String item) {
      asked.add("socket " + gem + " " + item);
      return "socketed";
    }

    java.util.List<Part> parts = java.util.List.of();

    @Override
    public java.util.List<Part> parts(org.bukkit.entity.Player p, String item, String other) {
      return parts;
    }

    @Override
    public String trade(org.bukkit.entity.Player p, String item, String other, String slot) {
      asked.add("trade " + item + " " + other + " " + slot);
      return "traded";
    }
  }

  @Test
  void theMarkShopSellsItemsOfYourChoice() {
    FakeExtras extras = new FakeExtras();
    java.util.LinkedHashMap<String, Integer> two = new java.util.LinkedHashMap<>();
    two.put("rare", 30);
    two.put("epic", 80);
    extras.offers = java.util.List.of(new LockerExtras.Offer("heavy_helmet", "Heavy Helmet", two),
        new LockerExtras.Offer("sword", "Sword", new java.util.LinkedHashMap<>(java.util.Map.of("rare", 30))));
    lockers.locker(ann).marks(45);
    LockerMenu m = new LockerMenu(lockers, ann, p -> true, extras);
    m.open();
    assertEquals(Material.EMERALD, m.getInventory().getItem(LockerMenu.SHOP).getType());
    m.click(LockerMenu.SHOP);
    assertTrue(m.shopping());
    assertEquals(Material.PAPER, m.getInventory().getItem(LockerMenu.OFFERS).getType());
    m.click(LockerMenu.OFFERS);
    m.click(LockerMenu.OFFERS, true);
    m.click(LockerMenu.OFFERS + 1, true); // the sword has one price only: shift does nothing
    m.click(LockerMenu.OFFERS + 5); // no offer there
    m.click(4); // the marks sign
    assertEquals(java.util.List.of("buy heavy_helmet rare", "buy heavy_helmet epic"), extras.asked);
    assertEquals("bought", last());
    m.click(LockerMenu.BACK);
    assertFalse(m.shopping());
    assertEquals(Material.BOOK, m.getInventory().getItem(1).getType());
    // no extras: no shop button, and the slot does nothing
    LockerMenu plain = menu();
    plain.open();
    assertNull(plain.getInventory().getItem(LockerMenu.SHOP));
    plain.click(LockerMenu.SHOP);
    assertFalse(plain.shopping());
  }

  @Test
  void gemsAreSocketedFromTheStash() {
    LockerMenu.isGem = it -> it.getType() == Material.AMETHYST_SHARD;
    FakeExtras extras = new FakeExtras();
    String gem = lockers.add(ann, new ItemStack(Material.AMETHYST_SHARD));
    String helm = lockers.add(ann, new ItemStack(Material.DIAMOND_HELMET));
    String gem2 = lockers.add(ann, new ItemStack(Material.AMETHYST_SHARD));
    LockerMenu m = new LockerMenu(lockers, ann, p -> true, extras);
    m.open();
    m.click(LockerMenu.STASH); // the gem
    m.click(LockerMenu.STASH); // again: let go
    assertNull(m.selected());
    m.click(LockerMenu.STASH);
    m.click(LockerMenu.STASH + 2); // another gem: just picks that one
    assertEquals(gem2, m.selected());
    m.click(LockerMenu.STASH + 1); // the helmet: socket it
    assertEquals(java.util.List.of("socket " + gem2 + " " + helm), extras.asked);
    assertEquals(0, m.page(), "the item's page is shown");
    assertNull(m.selected());
    assertEquals("socketed", last());
    m.click(LockerMenu.STASH + 1); // the helmet picked, then a gem: just picks the gem
    m.click(LockerMenu.STASH);
    assertEquals(gem, m.selected());
    m.click(18); // a gem never goes in a loadout
    assertTrue(last().contains("doesn't go in the hotbar slot"));
    // without extras a gem click is a plain pick
    LockerMenu plain = menu();
    plain.open();
    plain.click(LockerMenu.STASH);
    plain.click(LockerMenu.STASH + 1);
    assertEquals(helm, plain.selected());
  }

  @Test
  void gunsTradePartsFromTheStash() {
    FakeExtras extras = new FakeExtras();
    String a = lockers.add(ann, new ItemStack(Material.STONE_HOE));
    String b = lockers.add(ann, new ItemStack(Material.WOODEN_HOE));
    LockerMenu m = new LockerMenu(lockers, ann, p -> true, extras);
    m.open();
    m.click(LockerMenu.STASH, true); // nothing picked yet: a plain pick
    assertEquals(a, m.selected());
    m.click(LockerMenu.STASH, true); // the same gun: let go
    assertNull(m.selected());
    m.click(LockerMenu.STASH);
    m.click(LockerMenu.STASH + 1, true); // nothing to trade: a plain pick
    assertEquals(b, m.selected());
    extras.parts = java.util.List.of(new LockerExtras.Part("barrel", "Long", "Short"), new LockerExtras.Part("stock", "A", "B"));
    m.click(LockerMenu.STASH, true);
    assertEquals(Material.WOODEN_HOE, m.getInventory().getItem(LockerMenu.OWN_GUN).getType());
    assertEquals(Material.STONE_HOE, m.getInventory().getItem(LockerMenu.OTHER_GUN).getType());
    m.click(LockerMenu.PARTS + 1);
    assertEquals(java.util.List.of("trade " + b + " " + a + " stock"), extras.asked);
    assertEquals("traded", last());
    m.click(LockerMenu.PARTS + 2); // no part there
    m.click(LockerMenu.PARTS - 1);
    assertEquals(1, extras.asked.size());
    extras.parts = java.util.List.of();
    m.render(); // a gun went away
    assertNull(m.getInventory().getItem(LockerMenu.OWN_GUN));
    m.click(LockerMenu.BACK);
    assertEquals(Material.STONE_HOE, m.getInventory().getItem(LockerMenu.STASH).getType());
    // without extras: a plain pick
    LockerMenu plain = menu();
    plain.open();
    plain.click(LockerMenu.STASH);
    plain.click(LockerMenu.STASH + 1, true);
    assertEquals(b, plain.selected());
  }

  @Test
  void jewelleryGoesInTheRingAndAmuletSlots() {
    assertFalse(LockerMenu.fits(new ItemStack(Material.GOLD_NUGGET), 11), "artifacts only");
    assertFalse(LockerMenu.fits(new ItemStack(Material.STONE), 12));
    assertTrue(LockerMenu.fits(new ItemStack(Material.GOLD_NUGGET), 9));
    assertFalse(LockerMenu.fits(new ItemStack(Material.GOLD_INGOT), 9));
    assertTrue(LockerMenu.fits(new ItemStack(Material.GOLD_INGOT), 10));
    assertFalse(LockerMenu.fits(new ItemStack(Material.STONE), 10));
    String ring = lockers.add(ann, new ItemStack(Material.GOLD_NUGGET));
    LockerMenu m = menu();
    m.open();
    m.click(LockerMenu.STASH);
    m.click(15); // the amulet slot
    assertTrue(last().contains("doesn't go in the amulet slot"));
    m.click(14);
    assertEquals(java.util.Map.of(9, ring), lockers.locker(ann).loadout(0));
    ItemStack item = new ItemStack(Material.GOLD_NUGGET);
    io.lumine.mythic.lib.api.item.NBTItem tag = mock(io.lumine.mythic.lib.api.item.NBTItem.class);
    when(tag.getType()).thenReturn("RING");
    assertFalse(LockerMenu.mythicLibEnabled());
    assertNull(LockerMenu.mmoType(item), "no MythicLib on this server: no MMOItems types");
    assertFalse(LockerMenu.mmoGem(item), "nor gems");
    LockerMenu.mythicLib = () -> true;
    try (var nbt = mockStatic(io.lumine.mythic.lib.api.item.NBTItem.class)) {
      nbt.when(() -> io.lumine.mythic.lib.api.item.NBTItem.get(item)).thenReturn(tag);
      assertEquals("RING", LockerMenu.mmoType(item));
      assertFalse(LockerMenu.mmoGem(item), "a ring has no gem colour");
      when(tag.hasTag("MMOITEMS_GEM_COLOR")).thenReturn(true);
      assertTrue(LockerMenu.mmoGem(item), "a gem colour makes a gem");
    }
  }

  @Test
  void itemsAreWornWhereTheirEquippablePartOrMaterialSays() {
    ItemStack skinned = mock(ItemStack.class);
    org.bukkit.inventory.meta.ItemMeta meta = mock(org.bukkit.inventory.meta.ItemMeta.class);
    var eq = mock(org.bukkit.inventory.meta.components.EquippableComponent.class);
    when(skinned.getItemMeta()).thenReturn(meta);
    when(skinned.getType()).thenReturn(Material.PAPER);
    when(meta.hasEquippable()).thenReturn(true);
    when(meta.getEquippable()).thenReturn(eq);
    when(eq.getSlot()).thenReturn(org.bukkit.inventory.EquipmentSlot.HEAD);
    assertEquals(org.bukkit.inventory.EquipmentSlot.HEAD, LockerMenu.wornOn(skinned));
    when(meta.hasEquippable()).thenReturn(false);
    assertEquals(org.bukkit.inventory.EquipmentSlot.HAND, LockerMenu.wornOn(skinned));
    ItemStack bare = mock(ItemStack.class);
    when(bare.getType()).thenReturn(Material.DIAMOND_BOOTS);
    assertEquals(org.bukkit.inventory.EquipmentSlot.FEET, LockerMenu.wornOn(bare), "no meta at all");
  }
}
