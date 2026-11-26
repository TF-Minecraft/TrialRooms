package net.tfminecraft.trialrooms.dungeon.supply;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.trialrooms.dungeon.locker.Locker;
import net.tfminecraft.trialrooms.dungeon.locker.LockerService;

class SupplyMenuTest {

  ServerMock server;
  PlayerMock ann;
  LockerService lockers;
  Locker locker;
  AtomicBoolean saves = new AtomicBoolean(true);
  SupplySettings settings;

  static Supply supply(String id, int stack, int price, List<String> kits) {
    return new Supply(id, "S " + id, new Supply.Look("SLIME_BALL", 1, null), stack, price, Supply.Use.HEALTH, 0.4, 0, 0, 0,
        Set.of(), Set.of(), kits);
  }

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    ann = server.addPlayer("Ann");
    locker = new Locker();
    lockers = mock(LockerService.class);
    when(lockers.locker(ann)).thenAnswer(i -> locker);
    when(lockers.saveLocker(eq(ann), any())).thenAnswer(i -> saves.get());
    Map<String, Supply> items = new LinkedHashMap<>();
    items.put("potion", supply("potion", 3, 2, List.of()));
    items.put("salts", supply("salts", 2, 1, List.of("seithr")));
    settings = new SupplySettings(3, 4, new TreeMap<>(Map.of(1, 1.0)), 2, items, 0, 0, 0);
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  static String text(Component c) {
    return PlainTextComponentSerializer.plainText().serialize(c);
  }

  static String lore(ItemStack it) {
    return String.join("|", it.lore().stream().map(SupplyMenuTest::text).toList());
  }

  SupplyMenu menu() {
    SupplyMenu m = new SupplyMenu(lockers, ann, () -> settings);
    assertTrue(m.open());
    return m;
  }

  @Test
  void noLockerNoScreen() {
    locker = null;
    SupplyMenu m = new SupplyMenu(lockers, ann, () -> settings);
    assertFalse(m.open());
    assertTrue(ann.nextMessage().contains("can't be opened"));
    m.render();
    m.click(SupplyMenu.SHOP, false); // nothing happens
    assertTrue(m.getInventory().isEmpty());
  }

  @Test
  void buyingCostsMarksAndPacksWhileThereIsRoom() {
    locker.marks(3);
    SupplyMenu m = menu();
    assertTrue(text(m.getInventory().getItem(SupplyMenu.INFO).getItemMeta().displayName()).contains("0/2 slots"));
    assertTrue(lore(m.getInventory().getItem(SupplyMenu.SHOP)).contains("buy one for 2 marks"));
    assertTrue(lore(m.getInventory().getItem(SupplyMenu.SHOP + 1)).contains("for 1 mark|"));
    assertTrue(lore(m.getInventory().getItem(SupplyMenu.SHOP + 1)).contains("Sold for: seithr"));
    assertEquals(Material.GRAY_STAINED_GLASS_PANE, m.getInventory().getItem(SupplyMenu.POUCH).getType());
    m.click(SupplyMenu.SHOP, true);
    assertTrue(ann.nextMessage().contains("cost 10 marks; you have 3"));
    m.click(SupplyMenu.SHOP, false);
    assertTrue(ann.nextMessage().contains("(1 packed)"));
    assertEquals(1, locker.marks());
    assertEquals(1, locker.packed("potion"));
    assertEquals(Material.SLIME_BALL, m.getInventory().getItem(SupplyMenu.POUCH).getType());
    locker.marks(20);
    m.click(SupplyMenu.SHOP + 1, true); // five salts: two stacks of two fill the last slot... one slot is left
    assertTrue(ann.nextMessage().contains("(2 packed)"), "one slot left: one stack of two");
    assertEquals(5, locker.supplies("salts"));
    assertEquals(2, locker.packed("salts"));
    m.click(SupplyMenu.SHOP, false); // second and third potions still fit their started stack
    assertTrue(ann.nextMessage().contains("(1 packed)"));
    m.click(SupplyMenu.SHOP, false);
    assertTrue(ann.nextMessage().contains("(1 packed)"));
    m.click(SupplyMenu.SHOP, false); // a fourth would need a new slot
    assertTrue(ann.nextMessage().endsWith("marks."), "bought, not packed");
    assertEquals(2, SupplyMenu.slots(locker, settings));
    locker.out("potion", 1);
    m.render();
    assertTrue(lore(m.getInventory().getItem(SupplyMenu.SHOP)).contains("(1 in your run)"));
    // a save that fails takes the purchase back
    saves.set(false);
    int marks = locker.marks();
    m.click(SupplyMenu.SHOP + 1, false);
    assertEquals(marks, locker.marks());
    assertEquals(5, locker.supplies("salts"));
    assertEquals(2, locker.packed("salts"));
  }

  @Test
  void packingFitsThePouch() {
    locker.supplies("potion", 7);
    locker.supplies("salts", 1);
    locker.supplies("gone", 4);
    locker.packed("gone", 4);
    assertEquals(0, SupplyMenu.slots(locker, settings), "an unknown supply takes no slot");
    SupplyMenu m = menu();
    m.click(SupplyMenu.POUCH + 1, false);
    m.click(SupplyMenu.POUCH + 1, false);
    assertTrue(ann.nextMessage().contains("no more S salts"));
    for (int i = 0; i < 3; i++) {
      m.click(SupplyMenu.POUCH, false);
    }
    assertEquals(3, locker.packed("potion"));
    m.click(SupplyMenu.POUCH, false);
    assertTrue(ann.nextMessage().contains("pouch is full"));
    m.click(SupplyMenu.POUCH, true);
    assertEquals(2, locker.packed("potion"));
    assertEquals(2, m.getInventory().getItem(SupplyMenu.POUCH).getAmount());
    saves.set(false);
    m.click(SupplyMenu.POUCH, true);
    assertEquals(2, locker.packed("potion"), "not saved: as it was");
    saves.set(true);
    locker.out("potion", 2);
    m.click(SupplyMenu.POUCH, false);
    assertTrue(ann.nextMessage().contains("out in a run"));
    m.click(53, false); // nothing there
    m.click(SupplyMenu.INFO, false);
    m.click(SupplyMenu.SHOP + 5, false);
    assertEquals(2, locker.packed("potion"));
  }

  @Test
  void atMostOneRowOfSuppliesIsShown() {
    Map<String, Supply> many = new LinkedHashMap<>();
    for (int i = 0; i < 11; i++) {
      many.put("s" + i, supply("s" + i, 1, 0, List.of()));
    }
    settings = new SupplySettings(3, 4, new TreeMap<>(Map.of(1, 1.0)), 2, many, 0, 0, 0);
    SupplyMenu m = menu();
    assertNotNull(m.getInventory().getItem(SupplyMenu.SHOP + 8));
    assertNull(m.getInventory().getItem(SupplyMenu.SHOP + 9));
  }
}
