package net.tfminecraft.trialrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.trialrooms.environment.entrance.*;
import net.tfminecraft.trialrooms.environment.entrance.ui.*;
import net.tfminecraft.trialrooms.environment.spawner.*;
import net.tfminecraft.trialrooms.environment.spawner.ui.*;
import net.tfminecraft.trialrooms.loader.TableLoader;
import net.tfminecraft.trialrooms.loot.LootTable;
import net.tfminecraft.trialrooms.manager.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;

class EditorsTest extends TrialTestSupport {
  InventoryClickEvent click(Player p, int slot) {
    var e = mock(InventoryClickEvent.class);
    when(e.getWhoClicked()).thenReturn(p);
    when(e.getInventory()).thenReturn(p.getOpenInventory().getTopInventory());
    var item = e.getInventory().getItem(slot);
    when(e.getCurrentItem()).thenReturn(item);
    when(e.getRawSlot()).thenReturn(slot);
    return e;
  }

  AsyncPlayerChatEvent chat(Player p, String msg) {
    return new AsyncPlayerChatEvent(false, p, msg, new HashSet<>());
  }

  PlayerInteractEvent interact(Player p, Block b, EquipmentSlot hand) {
    return new PlayerInteractEvent(
        p,
        Action.RIGHT_CLICK_BLOCK,
        new ItemStack(Material.STICK),
        b,
        org.bukkit.block.BlockFace.UP,
        hand);
  }

  void select(ActiveSpawnerEditor editor, Player p, ActiveSpawner s, int slot) {
    ActiveSpawnerEditor.openEditor(p, s);
    editor.onInventoryClick(click(p, slot));
  }

  void edit(ActiveSpawnerEditor editor, Player p, ActiveSpawner s, int slot, String value) {
    select(editor, p, s, slot);
    editor.onChat(chat(p, value));
    server.getScheduler().performTicks(2);
  }

  @Test
  void spawnerChatEditorsValidateEveryFieldAndPreserveInvalidValues() {
    var p = server.addPlayer();
    var world = server.addSimpleWorld("editor");
    var s = mock(ActiveSpawner.class);
    when(s.getLoc()).thenReturn(new Location(world, 0, 64, 0));
    when(s.getId()).thenReturn("crypt");
    when(s.getLootTable()).thenReturn("loot");
    var editor = new ActiveSpawnerEditor();
    ActiveSpawnerEditor.PENDING.clear();
    editor.onChat(chat(p, "ignored"));
    edit(editor, p, s, 14, "ZOMBIE");
    verify(s).setMob("ZOMBIE");
    edit(editor, p, s, 14, "");
    verify(s).setMob(null);
    int[] slots = {15, 16, 17, 20, 21};
    for (int slot : slots) {
      edit(editor, p, s, slot, "2");
      edit(editor, p, s, slot, "-1");
      edit(editor, p, s, slot, "bad");
    }
    verify(s).setAmount(2);
    verify(s).setLevel(2);
    verify(s).setCooldownSeconds(2);
    verify(s).setSpawnRadius(2);
    verify(s).setActivateRadius(2);
    verify(s, never()).setAmount(-1);
    try (var tables = mockStatic(TableLoader.class)) {
      var loot = mock(LootTable.class);
      var map = new LinkedHashMap<String, LootTable>();
      map.put("Alpha", loot);
      tables.when(TableLoader::get).thenReturn(map);
      tables.when(() -> TableLoader.getByString("Alpha")).thenReturn(loot);
      for (int slot : new int[] {22, 23}) {
        edit(editor, p, s, slot, "Alpha");
        edit(editor, p, s, slot, "alpha");
        edit(editor, p, s, slot, "missing");
        edit(editor, p, s, slot, "none");
        edit(editor, p, s, slot, "null");
        edit(editor, p, s, slot, "");
        for (int i = 0; i < 12; i++) map.put("table" + i, loot);
        edit(editor, p, s, slot, "missing");
        map.clear();
        edit(editor, p, s, slot, "missing");
        map.put("Alpha", loot);
      }
      verify(s, times(2)).setLootTable("Alpha");
      verify(s, times(2)).setMobLootTable("Alpha");
    }
    edit(editor, p, s, 14, "cancel");
    assertTrue(ActiveSpawnerEditor.PENDING.isEmpty());
    select(editor, p, s, 25);
    editor.onChat(chat(p, "bad"));
    server.getScheduler().performTicks(2);
    assertFalse(ActiveSpawnerEditor.PENDING.isEmpty());
    editor.onChat(chat(p, "remove"));
    server.getScheduler().performTicks(2);
    assertTrue(ActiveSpawnerEditor.PENDING.isEmpty());
    when(s.removeLastDoorBlock()).thenReturn(true);
    edit(editor, p, s, 25, "remove");
    edit(editor, p, s, 24, "unused");
    doThrow(new IllegalStateException("provider")).when(s).setMob("throws");
    edit(editor, p, s, 14, "throws");
    editor.onInventoryClose(new InventoryCloseEvent(p.getOpenInventory()));
    when(s.getLootTable()).thenReturn(null);
    select(editor, p, s, 24);
    assertTrue(ActiveSpawnerEditor.PENDING.isEmpty());
    when(s.getLoc()).thenReturn(null);
    ActiveSpawnerEditor.openEditor(p, s);
    when(s.hasChestBound()).thenReturn(true);
    when(s.getChestLocation()).thenReturn(new Location(world, 4, 5, 6));
    ActiveSpawnerEditor.openEditor(p, s);
    assertNull(p.getOpenInventory().getTopInventory().getHolder().getInventory());
    var unrelated = mock(InventoryClickEvent.class);
    when(unrelated.getWhoClicked()).thenReturn(mock(HumanEntity.class));
    editor.onInventoryClick(unrelated);
    when(unrelated.getWhoClicked()).thenReturn(p);
    when(unrelated.getInventory()).thenReturn(Bukkit.createInventory(null, 9));
    editor.onInventoryClick(unrelated);
    ActiveSpawnerEditor.openEditor(p, s);
    editor.onInventoryClick(click(p, 0));
    var empty = click(p, 14);
    when(empty.getCurrentItem()).thenReturn(null);
    editor.onInventoryClick(empty);
  }

  @Test
  void spawnerBlockEditsRejectWrongHandsMaterialsWorldsAndRange() {
    var p = server.addPlayer();
    var world = server.addSimpleWorld("blocks");
    var other = server.addSimpleWorld("other");
    var s = mock(ActiveSpawner.class);
    when(s.getLoc()).thenReturn(new Location(world, 0, 64, 0));
    when(s.getLootTable()).thenReturn("loot");
    var editor = new ActiveSpawnerEditor();
    ActiveSpawnerEditor.PENDING.clear();
    var block = world.getBlockAt(1, 64, 0);
    block.setType(Material.STONE);
    try (var managers = mockStatic(SpawnerManager.class);
        var chests = mockStatic(ChestManager.class)) {
      var cm = mock(ChestManager.class);
      chests.when(ChestManager::get).thenReturn(cm);
      editor.onRightClickChest(interact(p, block, EquipmentSlot.HAND));
      editor.onRightClickChest(
          new PlayerInteractEvent(
              p, Action.RIGHT_CLICK_AIR, null, null, org.bukkit.block.BlockFace.UP));
      editor.onRightClickChest(interact(p, null, EquipmentSlot.HAND));
      select(editor, p, s, 24);
      editor.onRightClickChest(interact(p, block, EquipmentSlot.OFF_HAND));
      editor.onRightClickChest(interact(p, block, EquipmentSlot.HAND));
      managers.when(() -> SpawnerManager.hasEditWand(p)).thenReturn(true);
      editor.onRightClickChest(interact(p, block, EquipmentSlot.HAND));
      block.setType(Material.CHEST);
      editor.onRightClickChest(interact(p, block, EquipmentSlot.HAND));
      verify(cm).registerBound(s, block.getLocation());
      select(editor, p, s, 14);
      editor.onRightClickChest(interact(p, block, EquipmentSlot.HAND));
      select(editor, p, s, 25);
      editor.onRightClickChest(interact(p, block, EquipmentSlot.HAND));
      block.setType(Material.AIR);
      editor.onRightClickChest(interact(p, block, EquipmentSlot.HAND));
      world.getBlockAt(0, 64, 0).setType(Material.STONE);
      editor.onRightClickChest(interact(p, world.getBlockAt(0, 64, 0), EquipmentSlot.HAND));
      var far = world.getBlockAt(33, 64, 0);
      far.setType(Material.STONE);
      editor.onRightClickChest(interact(p, far, EquipmentSlot.HAND));
      var foreign = other.getBlockAt(1, 64, 0);
      foreign.setType(Material.STONE);
      editor.onRightClickChest(interact(p, foreign, EquipmentSlot.HAND));
      block.setType(Material.STONE);
      editor.onRightClickChest(interact(p, block, EquipmentSlot.HAND));
      server.getScheduler().performTicks(2);
      verify(s).addDoorBlock(any());
      select(editor, p, s, 25);
      Block directional = mock(Block.class);
      when(directional.getType()).thenReturn(Material.IRON_DOOR);
      when(directional.getLocation()).thenReturn(block.getLocation());
      var data = mock(org.bukkit.block.data.Directional.class);
      when(data.getFacing()).thenReturn(org.bukkit.block.BlockFace.WEST);
      when(directional.getBlockData()).thenReturn(data);
      editor.onRightClickChest(interact(p, directional, EquipmentSlot.HAND));
      server.getScheduler().performTicks(2);
      select(editor, p, s, 25);
      var multi = mock(org.bukkit.block.data.MultipleFacing.class);
      when(multi.getAllowedFaces())
          .thenReturn(Set.of(org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.WEST));
      when(multi.hasFace(org.bukkit.block.BlockFace.NORTH)).thenReturn(true);
      when(directional.getBlockData()).thenReturn(multi);
      editor.onRightClickChest(interact(p, directional, EquipmentSlot.HAND));
      server.getScheduler().performTicks(2);
    }
  }

  @Test
  void entranceEditorChatAndLocationFlows() {
    var p = server.addPlayer();
    var world = server.addSimpleWorld("entranceEditor");
    var editor = new EntranceEditor();
    try (var holo = mockConstruction(Hologram.class);
        var managers = mockStatic(SpawnerManager.class)) {
      var e = new Entrance(new Location(world, 0, 64, 0));
      editor.onChat(chat(p, "ignored"));
      EntranceEditor.open(p, e);
      assertNull(p.getOpenInventory().getTopInventory().getHolder().getInventory());
      editor.onClick(click(p, 0));
      var empty = click(p, 20);
      when(empty.getCurrentItem()).thenReturn(null);
      editor.onClick(empty);
      editor.onClick(click(p, 20));
      var event = chat(p, " v.key ");
      editor.onChat(event);
      server.getScheduler().performTicks(1);
      assertTrue(event.isCancelled());
      assertEquals("v.key", e.getKeyPath());
      editor.onClick(click(p, 20));
      editor.onChat(chat(p, "cancel"));
      server.getScheduler().performTicks(1);
      assertEquals("v.key", e.getKeyPath());
      var block = world.getBlockAt(1, 64, 0);
      block.setType(Material.STONE);
      editor.onClickLocation(interact(p, block, EquipmentSlot.HAND));
      editor.onClickLocation(interact(p, null, EquipmentSlot.HAND));
      editor.onClickLocation(
          new PlayerInteractEvent(
              p, Action.LEFT_CLICK_AIR, null, null, org.bukkit.block.BlockFace.UP));
      editor.onClickLocation(interact(p, block, EquipmentSlot.OFF_HAND));
      editor.onClick(click(p, 22));
      editor.onChat(chat(p, "ignored"));
      editor.onClickLocation(interact(p, block, EquipmentSlot.HAND));
      managers.when(() -> SpawnerManager.hasEditWand(p)).thenReturn(true);
      editor.onClickLocation(interact(p, world.getBlockAt(33, 64, 0), EquipmentSlot.HAND));
      editor.onClickLocation(
          interact(p, server.addSimpleWorld("foreign").getBlockAt(0, 64, 0), EquipmentSlot.HAND));
      editor.onClickLocation(interact(p, block, EquipmentSlot.HAND));
      server.getScheduler().performTicks(1);
      assertEquals(new Location(world, 1.5, 64, 0.5), e.getDestination());
      editor.onClick(click(p, 24));
      editor.onClickLocation(interact(p, block, EquipmentSlot.HAND));
      assertNull(e.getExitLoc());
      block.setType(Material.LODESTONE);
      editor.onClickLocation(interact(p, block, EquipmentSlot.HAND));
      server.getScheduler().performTicks(1);
      assertEquals(block.getLocation(), e.getExitLoc());
      editor.onClose(new InventoryCloseEvent(p.getOpenInventory()));
      var unrelated = mock(InventoryClickEvent.class);
      when(unrelated.getWhoClicked()).thenReturn(mock(HumanEntity.class));
      editor.onClick(unrelated);
      when(unrelated.getWhoClicked()).thenReturn(p);
      when(unrelated.getInventory()).thenReturn(Bukkit.createInventory(null, 9));
      editor.onClick(unrelated);
    }
  }

  @Test
  void entranceChatIsDeferredToServerThreadAndCancelWorksForLocationEdits() {
    var p = server.addPlayer();
    var world = server.addSimpleWorld("chatfix");
    var editor = new EntranceEditor();
    try (var holo = mockConstruction(Hologram.class);
        var sm = mockStatic(SpawnerManager.class);
        var em = mockStatic(EntranceManager.class)) {
      var manager = mock(EntranceManager.class);
      em.when(EntranceManager::get).thenReturn(manager);
      sm.when(() -> SpawnerManager.hasEditWand(p)).thenReturn(true);
      var entrance = new Entrance(new Location(world, 0, 64, 0));
      EntranceEditor.open(p, entrance);
      editor.onClick(click(p, 20));
      editor.onChat(chat(p, "v.key"));
      assertNull(entrance.getKeyPath());
      server.getScheduler().performTicks(1);
      assertEquals("v.key", entrance.getKeyPath());
      verify(manager).edited(entrance);
      editor.onClick(click(p, 24));
      editor.onChat(chat(p, "cancel"));
      server.getScheduler().performTicks(1);
      var block = world.getBlockAt(1, 64, 0);
      block.setType(Material.LODESTONE);
      editor.onClickLocation(interact(p, block, EquipmentSlot.HAND));
      assertNull(entrance.getExitLoc());
    }
  }

  @Test
  void staleChatAndMissingLocationsDoNotMutateNewEdits() throws Exception {
    var p = server.addPlayer();
    var world = server.addSimpleWorld("staleEditor");
    var block = world.getBlockAt(1, 64, 0);
    var editor = new EntranceEditor();
    try (var holo = mockConstruction(Hologram.class);
        var sm = mockStatic(SpawnerManager.class)) {
      sm.when(() -> SpawnerManager.hasEditWand(p)).thenReturn(true);
      var entrance = new Entrance(null);
      EntranceEditor.open(p, entrance);
      editor.onClick(click(p, 22));
      editor.onClickLocation(interact(p, block, EquipmentSlot.HAND));
      entrance = new Entrance(new Location(world, 0, 64, 0));
      EntranceEditor.open(p, entrance);
      editor.onClick(click(p, 20));
      editor.onChat(chat(p, "old"));
      EntranceEditor.open(p, entrance);
      editor.onClick(click(p, 20));
      editor.onClickLocation(interact(p, block, EquipmentSlot.HAND));
      server.getScheduler().performTicks(1);
      assertNull(entrance.getKeyPath());
      editor.onChat(chat(p, "cancel"));
      server.getScheduler().performTicks(1);
      editor.onClick(click(p, 22));
      entrance.getEntranceLoc().setWorld(null);
      editor.onClickLocation(interact(p, block, EquipmentSlot.HAND));
      assertNull(entrance.getDestination());
    }
    var spawnerEditor = new ActiveSpawnerEditor();
    var same =
        ActiveSpawnerEditor.class.getDeclaredMethod("sameWorld", Location.class, Location.class);
    same.setAccessible(true);
    assertEquals(false, same.invoke(spawnerEditor, null, new Location(world, 0, 0, 0)));
    assertEquals(false, same.invoke(spawnerEditor, new Location(world, 0, 0, 0), null));
    assertEquals(
        false,
        same.invoke(spawnerEditor, new Location(null, 0, 0, 0), new Location(world, 0, 0, 0)));
    var apply =
        ActiveSpawnerEditor.class.getDeclaredMethod(
            "applyEdit",
            org.bukkit.entity.Player.class,
            ActiveSpawner.class,
            ActiveSpawnerEditor.Field.class,
            String.class);
    apply.setAccessible(true);
    assertEquals(
        true,
        apply.invoke(
            null, p, mock(ActiveSpawner.class), ActiveSpawnerEditor.Field.DOOR_BLOCKS, ""));
  }
}
