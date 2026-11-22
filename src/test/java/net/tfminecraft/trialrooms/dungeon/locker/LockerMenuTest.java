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

  @Test
  void jewelleryGoesInTheRingAndAmuletSlots() {
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
    LockerMenu.mythicLib = () -> true;
    try (var nbt = mockStatic(io.lumine.mythic.lib.api.item.NBTItem.class)) {
      nbt.when(() -> io.lumine.mythic.lib.api.item.NBTItem.get(item)).thenReturn(tag);
      assertEquals("RING", LockerMenu.mmoType(item));
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
