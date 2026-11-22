package net.tfminecraft.trialrooms.dungeon.locker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Set;

import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LockerListenerTest {

  LockerService lockers;
  LockerListener l;
  ItemStack bound = mock(ItemStack.class);
  ItemStack plain = mock(ItemStack.class);
  Player p;
  PlayerInventory inv;

  @BeforeEach
  void setUp() {
    lockers = mock(LockerService.class);
    when(lockers.isDungeonItem(bound)).thenReturn(true);
    l = new LockerListener(lockers);
    p = mock(Player.class);
    inv = mock(PlayerInventory.class);
    when(p.getInventory()).thenReturn(inv);
  }

  InventoryClickEvent click(Inventory top, HumanEntity who) {
    InventoryClickEvent e = mock(InventoryClickEvent.class);
    InventoryView view = mock(InventoryView.class);
    when(view.getTopInventory()).thenReturn(top);
    when(e.getView()).thenReturn(view);
    when(e.getWhoClicked()).thenReturn(who);
    when(e.getHotbarButton()).thenReturn(-1);
    when(e.getClick()).thenReturn(ClickType.LEFT);
    return e;
  }

  static Inventory inventory(InventoryType type) {
    Inventory i = mock(Inventory.class);
    when(i.getType()).thenReturn(type);
    when(i.getSize()).thenReturn(27);
    return i;
  }

  InventoryDragEvent drag(Inventory top, ItemStack cursor, Set<Integer> slots) {
    InventoryDragEvent d = mock(InventoryDragEvent.class);
    InventoryView view = mock(InventoryView.class);
    when(view.getTopInventory()).thenReturn(top);
    when(d.getView()).thenReturn(view);
    when(d.getOldCursor()).thenReturn(cursor);
    when(d.getRawSlots()).thenReturn(slots);
    return d;
  }

  @Test
  void theMenuOwnsEveryClick() {
    LockerMenu menu = mock(LockerMenu.class);
    Inventory top = inventory(InventoryType.CHEST);
    when(top.getHolder()).thenReturn(menu);
    InventoryClickEvent e = click(top, p);
    when(e.getClickedInventory()).thenReturn(top);
    when(e.getRawSlot()).thenReturn(4);
    l.onClick(e);
    verify(e).setCancelled(true);
    verify(menu).click(4);
    InventoryClickEvent below = click(top, p);
    when(below.getClickedInventory()).thenReturn(inv);
    l.onClick(below);
    verify(below).setCancelled(true);
    verifyNoMoreInteractions(menu);

    InventoryDragEvent d = drag(top, plain, Set.of(1));
    l.onDrag(d);
    verify(d).setCancelled(true);
  }

  @Test
  void dungeonItemsNeverGoIntoOtherInventories() {
    Inventory chest = inventory(InventoryType.CHEST);
    Inventory grid = inventory(InventoryType.CRAFTING);
    InventoryClickEvent own = click(grid, p);
    when(own.getCurrentItem()).thenReturn(bound);
    when(own.getClickedInventory()).thenReturn(inv);
    l.onClick(own);
    verify(own, never()).setCancelled(anyBoolean()); // moving it about the own inventory is fine
    InventoryClickEvent intoGrid = click(grid, p);
    when(intoGrid.getCursor()).thenReturn(bound);
    when(intoGrid.getClickedInventory()).thenReturn(grid);
    l.onClick(intoGrid);
    verify(intoGrid).setCancelled(true);
    ItemStack bundle = mock(ItemStack.class);
    when(bundle.getItemMeta()).thenReturn(mock(org.bukkit.inventory.meta.BundleMeta.class));
    when(plain.getItemMeta()).thenReturn(mock(org.bukkit.inventory.meta.ItemMeta.class));
    InventoryClickEvent intoBundle = click(grid, p);
    when(intoBundle.getCursor()).thenReturn(bound);
    when(intoBundle.getCurrentItem()).thenReturn(bundle);
    when(intoBundle.getClickedInventory()).thenReturn(inv);
    l.onClick(intoBundle);
    verify(intoBundle).setCancelled(true);
    InventoryClickEvent scoop = click(grid, p);
    when(scoop.getCursor()).thenReturn(bundle);
    when(scoop.getCurrentItem()).thenReturn(bound);
    when(scoop.getClickedInventory()).thenReturn(inv);
    l.onClick(scoop);
    verify(scoop).setCancelled(true);
    InventoryClickEvent plainSwap = click(grid, p);
    when(plainSwap.getCursor()).thenReturn(plain);
    when(plainSwap.getCurrentItem()).thenReturn(bound);
    when(plainSwap.getClickedInventory()).thenReturn(inv);
    l.onClick(plainSwap);
    verify(plainSwap, never()).setCancelled(anyBoolean());
    InventoryClickEvent notAPlayer = click(chest, mock(HumanEntity.class));
    when(notAPlayer.getCurrentItem()).thenReturn(bound);
    l.onClick(notAPlayer);
    verify(notAPlayer, never()).setCancelled(anyBoolean());

    InventoryClickEvent current = click(chest, p);
    when(current.getCurrentItem()).thenReturn(bound);
    l.onClick(current);
    verify(current).setCancelled(true);
    verify(p, times(4)).sendMessage("§cDungeon items stay with you.");
    InventoryClickEvent cursor = click(chest, p);
    when(cursor.getCursor()).thenReturn(bound);
    l.onClick(cursor);
    verify(cursor).setCancelled(true);
    InventoryClickEvent key = click(chest, p);
    when(key.getHotbarButton()).thenReturn(3);
    when(inv.getItem(3)).thenReturn(bound);
    l.onClick(key);
    verify(key).setCancelled(true);
    InventoryClickEvent offhand = click(chest, p);
    when(offhand.getClick()).thenReturn(ClickType.SWAP_OFFHAND);
    when(inv.getItemInOffHand()).thenReturn(bound);
    l.onClick(offhand);
    verify(offhand).setCancelled(true);
    InventoryClickEvent fine = click(chest, p);
    when(fine.getCurrentItem()).thenReturn(plain);
    when(fine.getHotbarButton()).thenReturn(2);
    l.onClick(fine);
    verify(fine, never()).setCancelled(anyBoolean());

    InventoryDragEvent below = drag(chest, bound, Set.of(30, 31));
    l.onDrag(below);
    verify(below, never()).setCancelled(anyBoolean()); // only into the player's own slots
    InventoryDragEvent into = drag(chest, bound, Set.of(30, 2));
    l.onDrag(into);
    verify(into).setCancelled(true);
    InventoryDragEvent plainDrag = drag(chest, plain, Set.of(2));
    l.onDrag(plainDrag);
    verify(plainDrag, never()).setCancelled(anyBoolean());
    Inventory craft = inventory(InventoryType.CRAFTING);
    when(craft.getSize()).thenReturn(5);
    InventoryDragEvent ownDrag = drag(craft, bound, Set.of(12, 20));
    l.onDrag(ownDrag);
    verify(ownDrag, never()).setCancelled(anyBoolean());
    InventoryDragEvent gridDrag = drag(craft, bound, Set.of(12, 3));
    l.onDrag(gridDrag);
    verify(gridDrag).setCancelled(true);
  }

  @Test
  void dungeonItemsCantBeDroppedHungOrPickedUp() {
    PlayerDropItemEvent drop = mock(PlayerDropItemEvent.class);
    Item dropped = mock(Item.class);
    when(drop.getItemDrop()).thenReturn(dropped);
    when(drop.getPlayer()).thenReturn(p);
    when(dropped.getItemStack()).thenReturn(bound);
    l.onDrop(drop);
    verify(drop).setCancelled(true);
    Item droppedPlain = mock(Item.class);
    when(droppedPlain.getItemStack()).thenReturn(plain);
    PlayerDropItemEvent drop2 = mock(PlayerDropItemEvent.class);
    when(drop2.getItemDrop()).thenReturn(droppedPlain);
    l.onDrop(drop2);
    verify(drop2, never()).setCancelled(anyBoolean());

    EntityPickupItemEvent pick = mock(EntityPickupItemEvent.class);
    Item ground = mock(Item.class);
    when(pick.getItem()).thenReturn(ground);
    when(ground.getItemStack()).thenReturn(bound);
    l.onPickup(pick);
    verify(pick).setCancelled(true);
    verify(ground).remove();
    Item groundPlain = mock(Item.class);
    when(groundPlain.getItemStack()).thenReturn(plain);
    EntityPickupItemEvent pick2 = mock(EntityPickupItemEvent.class);
    when(pick2.getItem()).thenReturn(groundPlain);
    l.onPickup(pick2);
    verify(pick2, never()).setCancelled(anyBoolean());

    PlayerInteractEntityEvent frame = mock(PlayerInteractEntityEvent.class);
    when(frame.getRightClicked()).thenReturn(mock(ItemFrame.class));
    when(frame.getPlayer()).thenReturn(p);
    when(frame.getHand()).thenReturn(EquipmentSlot.HAND);
    when(inv.getItem(EquipmentSlot.HAND)).thenReturn(bound);
    l.onFrame(frame);
    verify(frame).setCancelled(true);
    PlayerInteractEntityEvent other = mock(PlayerInteractEntityEvent.class);
    when(other.getRightClicked()).thenReturn(mock(Entity.class));
    l.onFrame(other);
    verify(other, never()).setCancelled(anyBoolean());
    PlayerInteractEntityEvent plainFrame = mock(PlayerInteractEntityEvent.class);
    when(plainFrame.getRightClicked()).thenReturn(mock(ItemFrame.class));
    when(plainFrame.getPlayer()).thenReturn(p);
    when(plainFrame.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
    l.onFrame(plainFrame);
    verify(plainFrame, never()).setCancelled(anyBoolean());

    PlayerArmorStandManipulateEvent stand = mock(PlayerArmorStandManipulateEvent.class);
    when(stand.getPlayerItem()).thenReturn(bound);
    l.onStand(stand);
    verify(stand).setCancelled(true);
    PlayerArmorStandManipulateEvent stand2 = mock(PlayerArmorStandManipulateEvent.class);
    when(stand2.getPlayerItem()).thenReturn(plain);
    l.onStand(stand2);
    verify(stand2, never()).setCancelled(anyBoolean());

    PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
    when(quit.getPlayer()).thenReturn(p);
    l.onQuit(quit);
    verify(lockers).forget(p);
  }

  @Test
  void paperItemBytesAreTheServers() {
    ItemStack item = mock(ItemStack.class);
    when(item.serializeAsBytes()).thenReturn(new byte[] {1, 2});
    assertArrayEquals(new byte[] {1, 2}, ItemCodec.PAPER.encode(item));
    try (var statics = mockStatic(ItemStack.class)) {
      statics.when(() -> ItemStack.deserializeBytes(any())).thenReturn(item);
      assertSame(item, ItemCodec.PAPER.decode(new byte[] {1, 2}));
    }
  }
}
