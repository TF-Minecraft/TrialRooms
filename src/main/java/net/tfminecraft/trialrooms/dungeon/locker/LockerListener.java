package net.tfminecraft.trialrooms.dungeon.locker;

import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;

/**
 * The locker screen's clicks, and the rules that keep worn dungeon items on their wearer: they can't
 * be dropped, put in any other inventory, the crafting grid or a bundle, hung in a frame or on a
 * stand, or picked up off the ground.
 */
public final class LockerListener implements Listener {

    private final LockerService lockers;

    public LockerListener(LockerService lockers) {
        this.lockers = lockers;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof LockerMenu menu) {
            event.setCancelled(true);
            if (event.getClickedInventory() == top) {
                menu.click(event.getRawSlot(), event.isShiftClick());
            }
            return;
        }
        if (!(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        ItemStack hotbar = event.getHotbarButton() >= 0 ? p.getInventory().getItem(event.getHotbarButton()) : null;
        ItemStack offhand = event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
                ? p.getInventory().getItemInOffHand() : null;
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if (!lockers.isDungeonItem(current) && !lockers.isDungeonItem(cursor) && !lockers.isDungeonItem(hotbar)
                && !lockers.isDungeonItem(offhand)) {
            return;
        }
        // On the player's own screen dungeon items move about freely, but never into the crafting
        // grid (a recipe could turn them into plain items), into or out of a bundle, and a dungeon gem
        // is never dropped onto an item from the cursor (that is how MMOItems sockets gems).
        boolean own = top.getType() == InventoryType.CRAFTING;
        if (!own || event.getClickedInventory() == top || bundle(current) || bundle(cursor)
                || event.getAction() == org.bukkit.event.inventory.InventoryAction.SWAP_WITH_CURSOR
                        && lockers.isDungeonItem(cursor) && LockerMenu.gem(cursor)) {
            event.setCancelled(true);
            p.sendMessage("§cDungeon items stay with you.");
        }
    }

    private static boolean bundle(ItemStack it) {
        return it != null && it.getItemMeta() instanceof BundleMeta;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof LockerMenu) {
            event.setCancelled(true);
            return;
        }
        // the top's raw slots: another inventory, or the crafting grid of the player's own screen
        if (lockers.isDungeonItem(event.getOldCursor()) && event.getRawSlots().stream().anyMatch(s -> s < top.getSize())) {
            event.setCancelled(true);
        }
    }

    /**
     * No recipe takes a dungeon item, however it got into the grid (the recipe book fills the grid
     * without a click).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack it : event.getInventory().getMatrix()) {
            if (lockers.isDungeonItem(it)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (lockers.isDungeonItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cDungeon items can't be dropped.");
        }
    }

    /** A dungeon item that got onto the ground anyway is nobody's to pick up: it goes. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        Item item = event.getItem();
        if (lockers.isDungeonItem(item.getItemStack())) {
            event.setCancelled(true);
            item.remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrame(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame
                && lockers.isDungeonItem(event.getPlayer().getInventory().getItem(event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onStand(PlayerArmorStandManipulateEvent event) {
        if (lockers.isDungeonItem(event.getPlayerItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lockers.forget(event.getPlayer());
    }
}
