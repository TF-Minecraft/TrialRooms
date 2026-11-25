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
    LockerMenu.itemType = LockerMenu::mmoType;
    Accessories.refill = Accessories::refillPlaceholders;
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
  void aWornLoadoutCanBeForfeited() throws Exception {
    Locker l = kitted();
    assertFalse(lockers.forfeitWorn(ann), "nothing worn");
    ann.getInventory().setItem(0, named(Material.IRON_SWORD, "own sword"));
    assertEquals(LockerService.Result.DONE, lockers.wear(ann, 0));
    assertTrue(lockers.forfeitWorn(ann));
    assertTrue(l.items().isEmpty(), "the worn pieces are gone from the locker");
    assertEquals("own sword", name(ann.getInventory().getItem(0)), "own items come back");
    // a take-off that can't be saved destroys nothing
    Locker l2 = kitted();
    assertEquals(LockerService.Result.DONE, lockers.wear(ann, 0));
    Path tmp = store.file(ann.getUniqueId(), "c1").resolveSibling("c1.locker.tmp");
    Files.createDirectories(tmp);
    assertFalse(lockers.forfeitWorn(ann));
    assertEquals(2, l2.items().size());
    Files.delete(tmp);
    assertNull(new LockerService(plugin, store, codec, p -> null, p -> { }).locker(ann));
    assertFalse(new LockerService(plugin, store, codec, p -> null, p -> { }).forfeitWorn(ann), "no character");
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
  void owedItemsStackAndAnUnsavedLockerIsKeptUntilItSaves() throws Exception {
    Locker l = lockers.locker(ann);
    lockers.owe(l, new ItemStack(Material.STONE, 40));
    lockers.owe(l, new ItemStack(Material.STONE, 40));
    lockers.owe(l, named(Material.IRON_SWORD, "a"));
    lockers.owe(l, named(Material.IRON_SWORD, "a"));
    assertEquals(List.of(64, 16, 1, 1), l.owed().stream().map(codec::decode).map(ItemStack::getAmount).toList(),
        "like items top each other up");
    lockers.owe(l, new ItemStack(Material.STONE, 10));
    assertEquals(List.of(64, 26, 1, 1), l.owed().stream().map(codec::decode).map(ItemStack::getAmount).toList(),
        "all of it fits a stack already owed");
    // a save that fails keeps the locker (and what it owes) in memory, even past forget
    Path tmp = store.file(ann.getUniqueId(), "c1").resolveSibling("c1.locker.tmp");
    Files.createDirectories(store.file(ann.getUniqueId(), "c1").getParent());
    Files.createDirectories(tmp);
    assertFalse(lockers.saveLocker(ann, l));
    lockers.forget(ann);
    assertSame(l, lockers.locker(ann), "still the one in memory");
    lockers.forget(ann);
    assertSame(l, lockers.locker(ann), "kept while it can't be saved");
    characterOf.put("Ann", "c2");
    assertNotSame(l, lockers.locker(ann), "another character");
    lockers.forget(ann); // tries c1 again too: still failing
    lockers.flush();
    Files.delete(tmp);
    lockers.flush(); // (at shutdown) saves it now
    characterOf.put("Ann", "c1");
    lockers.forget(ann);
    Locker read = lockers.locker(ann);
    assertNotSame(l, read, "read from its file again");
    assertEquals(4, read.owed().size());
  }

  @Test
  void aPartHandedOverIsSavedAndAFullInventoryWritesNothing() {
    Locker l = lockers.locker(ann);
    l.owe(new byte[] {1}); // an entry that can't be read any more
    lockers.owe(l, new ItemStack(Material.STONE, 40));
    assertEquals(2, l.owed().size(), "the unreadable entry is kept, the rest still owed");
    l.clearOwed();
    lockers.owe(l, new ItemStack(Material.STONE, 40));
    for (int i = 0; i < ann.getInventory().getSize(); i++) {
      ann.getInventory().setItem(i, new ItemStack(Material.COBBLESTONE, 64));
    }
    ann.getInventory().setItem(0, new ItemStack(Material.STONE, 50));
    int before = saves;
    lockers.settle(ann, false);
    assertEquals(before + 1, saves, "a part went in: the player's file is saved");
    assertEquals(64, ann.getInventory().getItem(0).getAmount());
    assertEquals(26, codec.decode(l.owed().getFirst()).getAmount());
    assertTrue(ann.nextMessage().contains("1 stacks still wait for you"));
    lockers.settle(ann, false);
    assertEquals(before + 1, saves, "nothing went in: nothing written");
    assertTrue(ann.nextMessage().contains("1 stacks still wait for you"));
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
    assertTrue(ann.nextMessage().contains("1 stacks still wait for you"));
    ann.getInventory().setItem(3, null);
    lockers.settle(ann, false);
    assertTrue(l.owed().isEmpty());
    assertEquals("own sword", name(ann.getInventory().getItem(3)));
    assertTrue(ann.nextMessage().contains("are in your inventory"));
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
    ann.getInventory().setItem(12, new ItemStack(Material.STONE)); // not ours at all
    lockers.settle(ann, true);
    assertEquals(Material.STONE, ann.getInventory().getItem(12).getType());
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
    // a mark from some other wear proves nothing; the worn copy does
    ann.getPersistentDataContainer().set(lockers.markKey("c1"), org.bukkit.persistence.PersistentDataType.LONG, 999L);
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
    ann.getPersistentDataContainer().remove(lockers.markKey("c1"));
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
    ann.getPersistentDataContainer().remove(lockers.markKey("c1"));
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
    ann.getPersistentDataContainer().remove(lockers.markKey("c1"));
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

  static ItemStack bundleOf(ItemStack... items) {
    ItemStack bundle = new ItemStack(Material.BUNDLE);
    org.bukkit.inventory.meta.BundleMeta meta = (org.bukkit.inventory.meta.BundleMeta) bundle.getItemMeta();
    meta.setItems(List.of(items));
    bundle.setItemMeta(meta);
    return bundle;
  }

  static List<ItemStack> inside(ItemStack bundle) {
    return ((org.bukkit.inventory.meta.BundleMeta) bundle.getItemMeta()).getItems();
  }

  @Test
  void dungeonItemsHiddenInBundlesComeOutToo() {
    Locker l = kitted();
    assertEquals(LockerService.Result.DONE, lockers.wear(ann, 0));
    ItemStack sword = ann.getInventory().getItem(0);
    ann.getInventory().setItem(0, null);
    ann.getInventory().setItem(9, bundleOf(sword, new ItemStack(Material.STRING)));
    ItemStack helm = ann.getInventory().getHelmet();
    ann.getInventory().setHelmet(null);
    ann.setItemOnCursor(bundleOf(helm));
    // the copy in the bundle is still proof of the wear
    ann.getPersistentDataContainer().remove(lockers.markKey("c1"));
    lockers.settle(ann, false);
    assertEquals(-1, l.worn());
    assertEquals(List.of(Material.STRING), inside(ann.getInventory().getItem(9)).stream().map(ItemStack::getType).toList());
    assertTrue(inside(ann.getItemOnCursor()).isEmpty());
    // bundles in bundles too
    ann.getInventory().setItem(13, bundleOf(bundleOf(lockers.show(l, l.loadout(0).get(0)))));
    lockers.settle(ann, false);
    assertTrue(inside(inside(ann.getInventory().getItem(13)).getFirst()).isEmpty());
    // a bundle with nothing of ours is left alone, as is a plain item
    ann.getInventory().setItem(10, bundleOf(new ItemStack(Material.STRING)));
    ann.getInventory().setItem(11, new ItemStack(Material.STONE));
    ann.setItemOnCursor(new ItemStack(Material.STONE));
    lockers.settle(ann, false);
    assertEquals(1, inside(ann.getInventory().getItem(10)).size());

    // and from a bundle in the crafting grid
    org.bukkit.inventory.Inventory grid = org.mockito.Mockito.mock(org.bukkit.inventory.Inventory.class);
    org.mockito.Mockito.when(grid.getType()).thenReturn(org.bukkit.event.inventory.InventoryType.CRAFTING);
    org.mockito.Mockito.when(grid.getSize()).thenReturn(2);
    ItemStack gridBundle = bundleOf(lockers.show(l, l.loadout(0).get(0)));
    org.mockito.Mockito.when(grid.getItem(1)).thenReturn(gridBundle);
    org.bukkit.inventory.InventoryView view = org.mockito.Mockito.mock(org.bukkit.inventory.InventoryView.class);
    org.mockito.Mockito.when(view.getTopInventory()).thenReturn(grid);
    PlayerMock spied = org.mockito.Mockito.spy(ann);
    org.mockito.Mockito.doReturn(view).when(spied).getOpenInventory();
    spied.setItemOnCursor(null);
    assertEquals(LockerService.Result.DONE, lockers.wear(spied, 0));
    spied.getPersistentDataContainer().remove(lockers.markKey("c1"));
    org.mockito.Mockito.when(grid.getItem(1)).thenReturn(gridBundle);
    lockers.settle(spied, false);
    assertEquals(-1, l.worn());
    org.mockito.Mockito.verify(grid).setItem(1, gridBundle);
    assertTrue(inside(gridBundle).isEmpty());
  }

  @Test
  void anUnreadableLockerStillTakesEveryDungeonItemAway() throws Exception {
    kitted();
    assertEquals(LockerService.Result.DONE, lockers.wear(ann, 0));
    Path f = store.file(ann.getUniqueId(), "c1");
    Files.writeString(f, "junk");
    Files.writeString(f.resolveSibling("c1.locker.prev"), "junk");
    lockers.forget(ann);
    assertEquals(LockerService.Result.BROKEN, lockers.takeOff(ann));
    assertFalse(lockers.isDungeonItem(ann.getInventory().getHelmet()), "nothing leaves with the player");
    assertFalse(lockers.isDungeonItem(ann.getInventory().getItem(0)));
    ann.getInventory().setItem(3, ann.getInventory().getItem(3)); // nothing else to take: no log
    lockers.settle(ann, true);
    characterOf.remove("Ann");
    lockers.settle(ann, false); // no character: nothing at all
  }

  @Test
  void aBackupOneSaveBehindAWearIsRefused() throws Exception {
    kitted();
    assertEquals(LockerService.Result.DONE, lockers.wear(ann, 0));
    Path f = store.file(ann.getUniqueId(), "c1");
    Files.writeString(f, "junk"); // the worn save is lost; the backup says nothing is worn
    lockers.forget(ann);
    assertNull(lockers.locker(ann), "the mark names a newer wear");
    ann.getPersistentDataContainer().remove(lockers.markKey("c1"));
    assertNull(lockers.locker(ann), "so do the worn copies");
    ann.getInventory().clear();
    ann.getInventory().setItem(5, new ItemStack(Material.STONE));
    ItemStack unworn = new ItemStack(Material.SHIELD); // a dungeon item that was never worn
    ItemMeta um = unworn.getItemMeta();
    um.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "dungeon_item"),
        org.bukkit.persistence.PersistentDataType.STRING, "x");
    unworn.setItemMeta(um);
    ann.getInventory().setItem(6, unworn);
    Locker back = lockers.locker(ann);
    assertNotNull(back, "with no proof of a newer wear the backup is used");
    assertEquals(-1, back.worn());
    lockers.settle(ann, false); // nothing owed: nothing to send to staff
    assertFalse(Files.exists(temp.resolve("quarantine")));
    ann.getPersistentDataContainer().set(lockers.markKey("c1"), org.bukkit.persistence.PersistentDataType.LONG, 1L);
    lockers.forget(ann);
    assertNotNull(lockers.locker(ann), "an older mark proves nothing");
  }

  @Test
  void charactersSharingAnInventoryKeepTheirOwnMarks() {
    kitted();
    assertEquals(LockerService.Result.DONE, lockers.wear(ann, 0));
    characterOf.put("Ann", "c2");
    assertFalse(lockers.marked(ann), "c2 wears nothing");
    lockers.settle(ann, false); // c1's copies are strays for c2
    characterOf.put("Ann", "c1");
    assertTrue(lockers.marked(ann), "c1's mark is still there");
    lockers.settle(ann, false);
    assertEquals(-1, lockers.locker(ann).worn(), "taken off the normal way, not quarantined");
    assertFalse(Files.exists(temp.resolve("quarantine")));
    characterOf.remove("Ann");
    assertFalse(lockers.marked(ann));
    assertNotEquals(lockers.markKey("A b"), lockers.markKey("c1"));
    assertNotEquals(lockers.markKey("a_b"), lockers.markKey("A b"), "ids that differ only in case or punctuation");
  }

  /** MMOInventory's "Ring Slot" placeholder, marked the way MMOInventory marks it. */
  static ItemStack placeholder() {
    ItemStack it = new ItemStack(Material.DIAMOND_HOE);
    ItemMeta meta = it.getItemMeta();
    meta.getPersistentDataContainer().set(Accessories.GUI_ITEM, org.bukkit.persistence.PersistentDataType.STRING, "RING");
    it.setItemMeta(meta);
    return it;
  }

  @Test
  void mmoInventoryPlaceholdersAreNeverAPlayersItem() {
    List<Player> refilled = new ArrayList<>();
    Accessories.refill = refilled::add;
    String ring = lockers.add(ann, named(Material.GOLD_NUGGET, "Rime Ring"));
    Locker l = lockers.locker(ann);
    l.assign(0, 9, ring);
    ann.getInventory().setItem(9, placeholder());
    assertEquals(LockerService.Result.DONE, lockers.wear(ann, 0));
    assertTrue(l.displaced().isEmpty(), "the placeholder is not kept as an own item");
    assertEquals(LockerService.Result.DONE, lockers.takeOff(ann));
    assertNull(ann.getInventory().getItem(9));
    assertEquals(List.of(ann), refilled, "MMOInventory is asked to put its placeholder back");
    // a real ring of the player's comes back to its slot, over a placeholder that turned up meanwhile
    ann.getInventory().setItem(9, named(Material.GOLD_NUGGET, "own ring"));
    lockers.wear(ann, 0);
    ann.getInventory().setItem(9, placeholder());
    lockers.takeOff(ann);
    assertEquals("own ring", name(ann.getInventory().getItem(9)));
    // anything else of the player's in the slot is theirs too: kept, and back after the run
    ann.getInventory().setItem(9, named(Material.STONE, "own stone"));
    lockers.wear(ann, 0);
    assertEquals("Rime Ring", name(ann.getInventory().getItem(9)));
    lockers.takeOff(ann);
    assertEquals("own stone", name(ann.getInventory().getItem(9)));
    // a ring put in the slot during the run stays; the own one goes to the bag
    lockers.wear(ann, 0);
    ann.getInventory().setItem(9, named(Material.GOLD_NUGGET, "found ring"));
    lockers.takeOff(ann);
    assertEquals("found ring", name(ann.getInventory().getItem(9)));
    assertTrue(java.util.Arrays.stream(ann.getInventory().getStorageContents()).anyMatch(i -> "own stone".equals(name(i))));
    assertTrue(LockerService.vacant(40, null));
    assertFalse(LockerService.vacant(40, placeholder()), "a placeholder counts only in an accessory slot");
    assertFalse(Accessories.placeholder(null));
    assertFalse(Accessories.placeholder(new ItemStack(Material.STONE)));
    ItemStack named = new ItemStack(Material.STONE);
    named.editMeta(m -> m.setCustomModelData(3));
    assertFalse(Accessories.placeholder(named), "an item with data but no placeholder tag");
    assertFalse(Accessories.placeholder(org.mockito.Mockito.mock(ItemStack.class)), "no meta at all");
  }

  @Test
  void placeholdersKeptByAnOlderVersionAreDropped() {
    String ring = lockers.add(ann, named(Material.GOLD_NUGGET, "Rime Ring"));
    Locker l = lockers.locker(ann);
    l.assign(0, 9, ring);
    l.wear(0, java.util.Map.of(9, codec.encode(placeholder())), l.revision() + 1);
    lockers.saveLocker(ann, l);
    lockers.takeOff(ann);
    assertNull(ann.getInventory().getItem(9), "never handed out as an item");
    l.owe(codec.encode(placeholder()));
    lockers.owe(l, named(Material.STONE, "owed stone")); // (as spoils are owed)
    lockers.settle(ann, false);
    assertTrue(l.owed().isEmpty());
    assertEquals(1, java.util.Arrays.stream(ann.getInventory().getContents()).filter(java.util.Objects::nonNull).count(),
        "only the stone came");
  }

  @Test
  void aSocketedItemKeepsItsId() {
    String id = lockers.add(ann, named(Material.DIAMOND_SWORD, "Rime Edge"));
    Locker l = lockers.locker(ann);
    lockers.replace(l, id, named(Material.DIAMOND_SWORD, "Rime Edge (socketed)"));
    assertEquals("Rime Edge (socketed)", name(lockers.show(l, id)));
    assertEquals(id, lockers.idOf(lockers.show(l, id)));
  }

  @Test
  void accessoryPlaceholdersComeFromMmoInventory() {
    Player p = server.addPlayer("Bo");
    Accessories.refill(null, p); // no MMOInventory
    org.bukkit.plugin.Plugin off = org.mockito.Mockito.mock(org.bukkit.plugin.Plugin.class);
    Accessories.refill(off, p); // not enabled
    FakeInventoryPlugin inv = org.mockito.Mockito.mock(FakeInventoryPlugin.class);
    org.mockito.Mockito.when(inv.isEnabled()).thenReturn(true);
    FakeManager manager = org.mockito.Mockito.mock(FakeManager.class);
    FakeVanilla vanilla = org.mockito.Mockito.mock(FakeVanilla.class);
    org.mockito.Mockito.when(inv.getInventoryManager()).thenReturn(manager);
    org.mockito.Mockito.when(manager.getVanilla()).thenReturn(vanilla);
    Accessories.refill(inv, p);
    org.mockito.Mockito.verify(vanilla).replacePlaceholderItems(p);
    org.mockito.Mockito.when(manager.getVanilla()).thenReturn(null);
    Accessories.refill(inv, p); // no vanilla inventory set up
    org.bukkit.plugin.Plugin odd = org.mockito.Mockito.mock(org.bukkit.plugin.Plugin.class);
    org.mockito.Mockito.when(odd.isEnabled()).thenReturn(true);
    Accessories.refill(odd, p); // not MMOInventory's API: nothing happens
    Accessories.refillPlaceholders(p);
    assertTrue(Accessories.slot(9) && Accessories.slot(10) && Accessories.slot(12) && !Accessories.slot(13));
  }

  public abstract static class FakeInventoryPlugin implements org.bukkit.plugin.Plugin {
    public abstract FakeManager getInventoryManager();
  }

  public abstract static class FakeManager {
    public abstract FakeVanilla getVanilla();
  }

  public abstract static class FakeVanilla {
    public abstract void replacePlaceholderItems(Player p);
  }

  @Test
  void owedItemsInABackupGoToStaffNotThePlayer() throws Exception {
    Locker l = lockers.locker(ann);
    l.owe(codec.encode(named(Material.IRON_SWORD, "owed sword")));
    lockers.saveLocker(ann, l);
    lockers.saveLocker(ann, l); // the backup now owes it too
    Files.writeString(store.file(ann.getUniqueId(), "c1"), "junk");
    lockers.forget(ann);
    lockers.settle(ann, false);
    assertNull(ann.getInventory().getItem(0), "maybe handed over already: not handed out again");
    assertTrue(lockers.locker(ann).owed().isEmpty());
    try (var files = Files.list(temp.resolve("quarantine"))) {
      assertEquals(1, files.count());
    }
    // with quarantine failing the owed items stay put
    l = lockers.locker(ann);
    l.owe(codec.encode(named(Material.IRON_SWORD, "owed sword")));
    lockers.saveLocker(ann, l);
    lockers.saveLocker(ann, l);
    Files.writeString(store.file(ann.getUniqueId(), "c1"), "junk");
    lockers.forget(ann);
    try (var files = Files.list(temp.resolve("quarantine"))) {
      for (Path q : files.toList()) {
        Files.delete(q);
      }
    }
    Files.delete(temp.resolve("quarantine"));
    Files.writeString(temp.resolve("quarantine"), "a file where the folder goes");
    lockers.settle(ann, false);
    assertEquals(1, lockers.locker(ann).owed().size());
    assertNull(ann.getInventory().getItem(0));
  }

  @Test
  void anInsuredPieceIsSparedAndGearGivenBackLandsInTheLocker() throws Exception {
    Locker l = kitted();
    assertEquals(LockerService.Result.DONE, lockers.wear(ann, 0));
    String helm = l.loadout(0).get(39);
    assertTrue(lockers.wears(ann, helm));
    assertFalse(lockers.wears(ann, "ghost"));
    assertFalse(new LockerService(plugin, store, codec, p -> null, p -> { }).wears(ann, helm), "no character");
    l.restoreSlot(0, 1, "ghost"); // a loadout naming a piece the locker no longer has
    List<byte[]> gone = lockers.forfeitWorn(ann, helm);
    assertEquals(1, gone.size(), "the sword; the helmet is spared and the ghost is nothing");
    assertTrue(l.items().containsKey(helm));
    assertEquals(1, l.items().size());
    Path tmp = store.file(ann.getUniqueId(), "c1").resolveSibling("c1.locker.tmp");
    Files.createDirectories(tmp);
    assertFalse(lockers.restore(ann, gone), "a save that fails");
    assertEquals(1, l.items().size(), "takes them back out: never given twice");
    Files.delete(tmp);
    assertTrue(lockers.restore(ann, gone));
    assertEquals(2, l.items().size(), "given back");
    assertFalse(new LockerService(plugin, store, codec, p -> null, p -> { }).restore(ann, gone), "no character");
    assertEquals(List.of(), lockers.forfeitWorn(ann, null), "nothing worn");
    ItemStack it = named(Material.STICK, "a stick");
    assertEquals("a stick", name(lockers.decode(lockers.encode(it))));
  }
}
