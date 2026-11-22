package net.tfminecraft.trialrooms.dungeon.locker;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.kyori.adventure.text.Component;

class LockerServiceTest {

  /** Items by index in a list: MockBukkit has no item bytes. */
  static final class FakeCodec implements ItemCodec {
    final List<ItemStack> saved = new ArrayList<>();

    @Override
    public byte[] encode(ItemStack item) {
      saved.add(item.clone());
      return ByteBuffer.allocate(4).putInt(saved.size() - 1).array();
    }

    @Override
    public ItemStack decode(byte[] data) {
      return saved.get(ByteBuffer.wrap(data).getInt()).clone();
    }
  }

  @TempDir Path temp;
  ServerMock server;
  Plugin plugin;
  FakeCodec codec = new FakeCodec();
  Map<String, String> characterOf = new HashMap<>();
  int saves;
  LockerStore store;
  LockerService lockers;
  PlayerMock ann;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    plugin = MockBukkit.createMockPlugin();
    store = new LockerStore(temp, Logger.getLogger("locker-test"));
    lockers = service(store);
    ann = server.addPlayer("Ann");
    characterOf.put("Ann", "c1");
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  LockerService service(LockerStore s) {
    return new LockerService(plugin, s, codec, p -> characterOf.get(p.getName()), p -> saves++);
  }

  static ItemStack named(Material type, String name) {
    ItemStack it = new ItemStack(type);
    ItemMeta meta = it.getItemMeta();
    meta.displayName(Component.text(name));
    it.setItemMeta(meta);
    return it;
  }

  static String name(ItemStack it) {
    return it == null || !it.hasItemMeta() || it.getItemMeta().displayName() == null ? null
        : ((net.kyori.adventure.text.TextComponent) it.getItemMeta().displayName()).content();
  }

  /** A locker with a dungeon helmet and sword in loadout 1. */
  Locker kitted() {
    String helm = lockers.add(ann, named(Material.DIAMOND_HELMET, "Rime Helm"));
    String sword = lockers.add(ann, named(Material.DIAMOND_SWORD, "Rime Edge"));
    Locker l = lockers.locker(ann);
    l.assign(0, 39, helm);
    l.assign(0, 0, sword);
    assertTrue(lockers.saveLocker(ann, l));
    return l;
  }

  @Test
  void aLoadoutGoesOnAndComesOffWithTheOwnItemsBack() {
    ann.getInventory().setHelmet(named(Material.IRON_HELMET, "own helm"));
    ann.getInventory().setItem(0, named(Material.IRON_SWORD, "own sword"));
    ann.getInventory().setItem(5, named(Material.BREAD, "bread"));
    Locker l = kitted();
    assertEquals(LockerService.Result.DONE, lockers.wear(ann, 0));
    assertEquals("Rime Helm", name(ann.getInventory().getHelmet()));
    assertEquals("Rime Edge", name(ann.getInventory().getItem(0)));
    assertEquals("bread", name(ann.getInventory().getItem(5)), "slots outside the loadout keep the own items");
    assertTrue(lockers.isDungeonItem(ann.getInventory().getHelmet()));
    assertTrue(lockers.marked(ann));
    assertEquals(1, saves);
    assertEquals(0, l.worn());

    // the worn sword gets changed in the run (a socket, wear): the locker keeps the change
    ItemStack sword = ann.getInventory().getItem(0);
    ItemMeta meta = sword.getItemMeta();
    meta.displayName(Component.text("Rime Edge+"));
    sword.setItemMeta(meta);
    ann.getInventory().setItem(0, sword);

    // copies that are not this wear's: they go without touching the locker
    ItemStack older = ann.getInventory().getHelmet().clone();
    ItemMeta om = older.getItemMeta();
    om.displayName(Component.text("forged"));
    om.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "dungeon_worn"),
        org.bukkit.persistence.PersistentDataType.LONG, 77L);
    older.setItemMeta(om);
    ann.getInventory().setItem(20, older);
    String spare = lockers.add(ann, new ItemStack(Material.SHIELD));
    ann.getInventory().setItem(21, lockers.show(l, spare));
    assertEquals(LockerService.Result.DONE, lockers.takeOff(ann));
    assertNull(ann.getInventory().getItem(20));
    assertNull(ann.getInventory().getItem(21));
    assertEquals("Rime Helm", name(lockers.show(l, l.loadout(0).get(39))), "the forged copy changed nothing");
    assertEquals("own helm", name(ann.getInventory().getHelmet()));
    assertEquals("own sword", name(ann.getInventory().getItem(0)));
    assertFalse(lockers.marked(ann));
    assertEquals(-1, l.worn());
    String id = l.loadout(0).get(0);
    assertEquals("Rime Edge+", name(lockers.show(l, id)));
    assertFalse(lockers.show(l, id).getItemMeta().getPersistentDataContainer()
        .has(new org.bukkit.NamespacedKey(plugin, "dungeon_worn")), "the worn mark is gone from the locker copy");
    assertEquals(LockerService.Result.NOT_WORN, lockers.takeOff(ann));
    // and it is all on disk
    lockers.forget(ann);
    assertEquals(-1, lockers.locker(ann).worn());
  }

  @Test
  void wearsAreRefusedWhenTheyShouldBe() throws Exception {
    Locker l = kitted();
    characterOf.remove("Ann");
    assertEquals(LockerService.Result.NO_CHARACTER, lockers.wear(ann, 0));
    assertEquals(LockerService.Result.NO_CHARACTER, lockers.takeOff(ann));
    characterOf.put("Ann", "c1");
    assertEquals(LockerService.Result.EMPTY, lockers.wear(ann, 1));
    ann.setItemOnCursor(new ItemStack(Material.STONE));
    assertEquals(LockerService.Result.CURSOR, lockers.wear(ann, 0));
    ann.setItemOnCursor(null);
    assertEquals(LockerService.Result.DONE, lockers.wear(ann, 0));
    assertEquals(LockerService.Result.ALREADY_WORN, lockers.wear(ann, 0));
    assertEquals(0, l.worn());

    // a broken file: no locker at all
    characterOf.put("Ann", "c2");
    Files.createDirectories(store.file(ann.getUniqueId(), "c2").getParent());
    Files.writeString(store.file(ann.getUniqueId(), "c2"), "junk");
    assertNull(lockers.locker(ann));
    assertEquals(LockerService.Result.BROKEN, lockers.wear(ann, 0));
    assertEquals(LockerService.Result.BROKEN, lockers.takeOff(ann));
    assertNull(lockers.add(ann, new ItemStack(Material.STONE)));
    lockers.settle(ann, false); // nothing to do without a locker
  }

  @Test
  void aWearThatCannotBeSavedChangesNothing() throws Exception {
    Locker l = kitted();
    ann.getInventory().setItem(0, named(Material.IRON_SWORD, "own sword"));
    Path tmp = store.file(ann.getUniqueId(), "c1").resolveSibling("c1.locker.tmp");
    Files.createDirectories(tmp); // the save's temporary file can't be written
    assertEquals(LockerService.Result.SAVE_FAILED, lockers.wear(ann, 0));
    assertEquals(-1, l.worn());
    assertEquals("own sword", name(ann.getInventory().getItem(0)));
    assertNull(lockers.add(ann, new ItemStack(Material.STONE)), "adding fails the same way");
    assertEquals(2, l.items().size());
    assertFalse(lockers.saveLocker(ann, l));
    assertTrue(ann.nextMessage().contains("could not be saved"));
    Files.delete(tmp);
    assertEquals(LockerService.Result.DONE, lockers.wear(ann, 0));
    Files.createDirectories(tmp);
    assertEquals(LockerService.Result.SAVE_FAILED, lockers.takeOff(ann), "the player is right, the file is not");
    assertEquals("own sword", name(ann.getInventory().getItem(0)));
    assertFalse(lockers.marked(ann));
  }

  @Test
  void ownItemsThatFindNoRoomComeBackLater() {
    kitted();
    ann.getInventory().setItem(0, named(Material.IRON_SWORD, "own sword"));
    assertEquals(LockerService.Result.DONE, lockers.wear(ann, 0));
    // the dungeon copies got lost somehow and every slot is full
    for (int i = 0; i < ann.getInventory().getSize(); i++) {
      ann.getInventory().setItem(i, new ItemStack(Material.COBBLESTONE, 64));
    }
    assertEquals(LockerService.Result.DONE, lockers.takeOff(ann));
    assertTrue(ann.nextMessage().contains("found no room"));
    Locker l = lockers.locker(ann);
    assertEquals(1, l.owed().size());
    assertEquals(2, l.items().size(), "lost copies are still in the locker");
    lockers.settle(ann, false);
    assertEquals(1, l.owed().size(), "still no room");
    ann.getInventory().setItem(3, null);
    lockers.settle(ann, false);
    assertTrue(l.owed().isEmpty());
    assertEquals("own sword", name(ann.getInventory().getItem(3)));
    assertTrue(ann.nextMessage().contains("are back"));
  }

  @Test
  void settlingTakesOffWhatShouldComeOffAndThrowsAwayStrays() {
    Locker l = kitted();
    assertEquals(LockerService.Result.DONE, lockers.wear(ann, 0));
    ItemStack stray = ann.getInventory().getItem(0).clone();
    ItemMeta meta = stray.getItemMeta();
    meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "dungeon_worn"),
        org.bukkit.persistence.PersistentDataType.LONG, 99L);
    stray.setItemMeta(meta);
    ann.getInventory().setItem(9, stray);
    String spare = lockers.add(ann, new ItemStack(Material.SHIELD));
    ann.getInventory().setItem(10, lockers.show(l, spare)); // a plain locker copy, never worn
    ann.getInventory().setItem(11, lockers.show(l, l.loadout(0).get(0))); // a plain copy of a worn item
    lockers.settle(ann, true);
    assertNull(ann.getInventory().getItem(11), "only the copies this wear put on count");
    assertNull(ann.getInventory().getItem(9), "a copy from another wear is thrown away");
    assertNull(ann.getInventory().getItem(10), "and one the wear never put on");
    assertTrue(lockers.isDungeonItem(ann.getInventory().getItem(0)), "the worn ones stay");
    assertEquals(0, l.worn());

    // out of the run: the mark says the wear happened, so it comes off
    lockers.settle(ann, false);
    assertEquals(-1, l.worn());
    assertFalse(lockers.isDungeonItem(ann.getInventory().getItem(0)));

    // not worn but holding a copy anyway, on the cursor and in the crafting grid: they go
    // (MockBukkit can't make a crafting grid: a stand-in view)
    org.bukkit.inventory.Inventory grid = org.mockito.Mockito.mock(org.bukkit.inventory.Inventory.class);
    org.mockito.Mockito.when(grid.getType()).thenReturn(org.bukkit.event.inventory.InventoryType.CRAFTING);
    org.mockito.Mockito.when(grid.getSize()).thenReturn(3);
    ItemStack inGrid = lockers.show(l, l.loadout(0).get(39));
    org.mockito.Mockito.when(grid.getItem(1)).thenReturn(inGrid);
    org.mockito.Mockito.when(grid.getItem(2)).thenReturn(new ItemStack(Material.STICK));
    org.bukkit.inventory.InventoryView view = org.mockito.Mockito.mock(org.bukkit.inventory.InventoryView.class);
    org.mockito.Mockito.when(view.getTopInventory()).thenReturn(grid);
    PlayerMock spied = org.mockito.Mockito.spy(ann);
    org.mockito.Mockito.doReturn(view).when(spied).getOpenInventory();
    spied.setItemOnCursor(lockers.show(l, l.loadout(0).get(0)));
    spied.getInventory().setItem(4, lockers.show(l, l.loadout(0).get(0)));
    lockers.settle(spied, true);
    assertFalse(lockers.isDungeonItem(spied.getItemOnCursor()));
    org.mockito.Mockito.verify(grid).setItem(1, null);
    org.mockito.Mockito.verify(grid, org.mockito.Mockito.never()).setItem(2, null);
    assertNull(spied.getInventory().getItem(4));
    // a different screen on top: nothing to look through there
    org.bukkit.inventory.Inventory chest = org.mockito.Mockito.mock(org.bukkit.inventory.Inventory.class);
    org.mockito.Mockito.when(chest.getType()).thenReturn(org.bukkit.event.inventory.InventoryType.CHEST);
    org.mockito.Mockito.when(view.getTopInventory()).thenReturn(chest);
    lockers.settle(spied, true);
    org.mockito.Mockito.verify(chest, org.mockito.Mockito.never()).getItem(org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void aWornCopyAloneProvesTheWear() {
    Locker l = kitted();
    lockers.wear(ann, 0);
    ann.getPersistentDataContainer().remove(new org.bukkit.NamespacedKey(plugin, "dungeon_locker"));
    lockers.settle(ann, false);
    assertEquals(-1, l.worn());
    assertFalse(lockers.isDungeonItem(ann.getInventory().getHelmet()));
  }

  @Test
  void aWearThatNeverReachedThePlayerIsUndoneWithoutDuplicates() throws Exception {
    Locker l = kitted();
    ann.getInventory().setItem(0, named(Material.IRON_SWORD, "own sword"));
    lockers.wear(ann, 0);
    ItemStack wornCopy = ann.getInventory().getItem(0).clone();
    // the server died before the player's file was saved: the inventory is as before the wear
    ann.getInventory().clear();
    ann.getInventory().setItem(0, named(Material.IRON_SWORD, "own sword"));
    // copies that prove nothing: one from an older wear, one of an item this wear did not put on
    ItemMeta old = wornCopy.getItemMeta();
    old.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "dungeon_worn"),
        org.bukkit.persistence.PersistentDataType.LONG, 1L);
    wornCopy.setItemMeta(old);
    ann.getInventory().setItem(20, wornCopy);
    String spare = lockers.add(ann, new ItemStack(Material.SHIELD));
    ItemStack spareCopy = lockers.show(l, spare);
    ItemMeta sm = spareCopy.getItemMeta();
    sm.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "dungeon_worn"),
        org.bukkit.persistence.PersistentDataType.LONG, l.wornAt());
    spareCopy.setItemMeta(sm);
    ann.getInventory().setItem(21, spareCopy);
    ann.getPersistentDataContainer().remove(new org.bukkit.NamespacedKey(plugin, "dungeon_locker"));
    lockers.settle(ann, false);
    assertEquals(-1, l.worn());
    assertEquals("own sword", name(ann.getInventory().getItem(0)));
    assertNull(ann.getInventory().getItem(1), "no second sword");
    assertNull(ann.getInventory().getItem(20), "stray copies go");
    assertNull(ann.getInventory().getItem(21));
    try (var files = Files.list(temp.resolve("quarantine"))) {
      assertEquals(1, files.count(), "the locker's copy of the own sword went to staff");
    }

    // the same with nothing displaced: no quarantine needed
    lockers.wear(ann, 0);
    l.takeOff();
    l.wear(0, Map.of(), l.revision() + 1);
    lockers.saveLocker(ann, l);
    ann.getInventory().clear();
    ann.getPersistentDataContainer().remove(new org.bukkit.NamespacedKey(plugin, "dungeon_locker"));
    lockers.settle(ann, false);
    assertEquals(-1, l.worn());
    try (var files = Files.list(temp.resolve("quarantine"))) {
      assertEquals(1, files.count());
    }
  }

  @Test
  void aLockerStaysWornUntilItsCopiesAreSafe() throws Exception {
    Locker l = kitted();
    ann.getInventory().setItem(0, named(Material.IRON_SWORD, "own sword"));
    lockers.wear(ann, 0);
    ann.getInventory().clear();
    ann.getPersistentDataContainer().remove(new org.bukkit.NamespacedKey(plugin, "dungeon_locker"));
    Files.writeString(temp.resolve("quarantine"), "a file where the folder goes");
    lockers.settle(ann, false);
    assertEquals(0, l.worn(), "nothing is thrown away while quarantine fails");
  }

  @Test
  void itemsAreRecognisedByTheirId() {
    assertNull(lockers.idOf(null));
    assertNull(lockers.idOf(new ItemStack(Material.AIR)));
    assertNull(lockers.idOf(new ItemStack(Material.STONE)));
    assertNull(lockers.add(ann, null));
    assertNull(lockers.add(ann, new ItemStack(Material.AIR)));
    String id = lockers.add(ann, new ItemStack(Material.STONE));
    Locker l = lockers.locker(ann);
    assertEquals(id, lockers.idOf(lockers.show(l, id)));
    characterOf.remove("Ann");
    assertNull(lockers.locker(ann));
    Player other = server.addPlayer("Bo");
    assertNull(lockers.add(other, new ItemStack(Material.STONE)));
  }
}
