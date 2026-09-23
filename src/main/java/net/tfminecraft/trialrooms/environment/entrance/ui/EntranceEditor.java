package net.tfminecraft.trialrooms.environment.entrance.ui;

import net.tfminecraft.trialrooms.TrialRooms;
import net.tfminecraft.trialrooms.environment.entrance.Entrance;
import net.tfminecraft.trialrooms.manager.EntranceManager;
import net.tfminecraft.trialrooms.manager.SpawnerManager;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public final class EntranceEditor implements Listener {

    public enum Field { KEY, DESTINATION, EXIT }
    private record Pending(Player player, Entrance entrance, Field field) {}

    private static final Map<UUID, Pending> PENDING = new HashMap<>();

    public static void open(Player p, Entrance ent) {
        Inventory inv = Bukkit.createInventory(new Holder(ent), 27, ChatColor.DARK_AQUA + "Edit Entrance");
        populate(inv, ent);
        p.openInventory(inv);
    }

    private static void populate(Inventory inv, Entrance e) {
        inv.clear();
        // filler
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta(); fm.setDisplayName(" "); filler.setItemMeta(fm);
        for (int i = 0; i < inv.getSize(); i++) if (inv.getItem(i) == null) inv.setItem(i, filler);

        // re-set
        inv.setItem(10, info("Entrance", locText(e.getEntranceLoc())));
        inv.setItem(12, info("Destination", locText(e.getDestination())));
        inv.setItem(14, info("Exit", locText(e.getExitLoc())));
        inv.setItem(20, editable(Field.KEY, "Key", e.getKeyPath() == null ? "none" : e.getKeyPath(), Material.BLAZE_POWDER));
        inv.setItem(22, editable(Field.DESTINATION, "Destination", locText(e.getDestination()), Material.ENDER_PEARL));
        inv.setItem(24, editable(Field.EXIT, "Exit (Lodestone)", locText(e.getExitLoc()), Material.LODESTONE));
    }

    private static String locText(Location l) {
        if (l == null) return ChatColor.GRAY + "none";
        return ChatColor.WHITE + l.getWorld().getName()
                + ChatColor.GRAY + " @ "
                + ChatColor.WHITE + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }

    private static ItemStack info(String title, String value) {
        ItemStack it = new ItemStack(Material.BOOK);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.GOLD + title);
        m.setLore(Collections.singletonList(ChatColor.YELLOW + (value == null ? "none" : value)));
        it.setItemMeta(m);
        return it;
    }

    private static ItemStack editable(Field f, String title, String value, Material icon) {
        ItemStack it = new ItemStack(icon);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + title);
        m.setLore(Arrays.asList(ChatColor.GRAY + "Current: " + ChatColor.WHITE + value,
                ChatColor.AQUA + "Click to edit"));
        it.setItemMeta(m);
        return it;
    }

    private static class Holder implements InventoryHolder {
        final Entrance entrance;
        Holder(Entrance entrance) { this.entrance = entrance; }
        @Override public Inventory getInventory() { return null; }
    }

    // ----- GUI clicks -----
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!(e.getInventory().getHolder() instanceof Holder holder)) return;
        e.setCancelled(true);
        ItemStack it = e.getCurrentItem();
        if (it == null) return;

        int slot = e.getRawSlot();
        Field field = switch (slot) {
            case 20 -> Field.KEY;
            case 22 -> Field.DESTINATION;
            case 24 -> Field.EXIT;
            default -> null;
        };
        if (field == null) return;

        // Prompt flows
        PENDING.put(p.getUniqueId(), new Pending(p, holder.entrance, field));
        p.closeInventory();

        switch (field) {
            case KEY -> {
                p.sendMessage(ChatColor.GREEN + "Type the key item path (e.g. " + ChatColor.GOLD + "v.blaze_powder"
                        + ChatColor.GREEN + ") in chat. Type " + ChatColor.RED + "cancel" + ChatColor.GREEN + " to abort.");
            }
            case DESTINATION -> {
                p.sendMessage(ChatColor.GREEN + "Right-click any block within 32 blocks to set Destination. "
                        + ChatColor.DARK_GRAY + "(Type 'cancel' to abort.)");
            }
            case EXIT -> {
                p.sendMessage(ChatColor.GREEN + "Right-click a " + ChatColor.AQUA + "Lodestone " + ChatColor.GREEN +
                        "within 32 blocks to set Exit. " + ChatColor.DARK_GRAY + "(Type 'cancel' to abort.)");
            }
        }
    }

    // ----- Setting KEY via chat -----
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Pending pen = PENDING.get(e.getPlayer().getUniqueId());
        if (pen == null || pen.field != Field.KEY) return;

        e.setCancelled(true);
        String msg = e.getMessage().trim();

        if (msg.equalsIgnoreCase("cancel")) {
            PENDING.remove(e.getPlayer().getUniqueId());
            e.getPlayer().sendMessage(ChatColor.YELLOW + "Edit cancelled.");
            Bukkit.getScheduler().runTask(TrialRooms.getInstance(), () -> open(e.getPlayer(), pen.entrance));
            return;
        }

        // Set key path
        pen.entrance.setKeyPath(msg);
        PENDING.remove(e.getPlayer().getUniqueId());
        e.getPlayer().sendMessage(ChatColor.GREEN + "Key set to " + ChatColor.GOLD + msg);
        Bukkit.getScheduler().runTask(TrialRooms.getInstance(), () -> open(e.getPlayer(), pen.entrance));
    }

    // ----- Setting DESTINATION / EXIT via right-click -----
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onClickLocation(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;
        if (e.getHand() != EquipmentSlot.HAND) return;

        Pending pen = PENDING.get(e.getPlayer().getUniqueId());
        if (pen == null) return;

        // Only accept with edit wand
        if (!SpawnerManager.hasEditWand(e.getPlayer())) return;

        e.setCancelled(true);

        // Range check (≤32)
        Location base = pen.entrance.getEntranceLoc();
        if (base == null || base.getWorld() == null) return;
        Location clicked = e.getClickedBlock().getLocation();
        if (!base.getWorld().equals(clicked.getWorld()) ||
                base.distanceSquared(clicked) > (32 * 32)) {
            e.getPlayer().sendMessage(ChatColor.RED + "Too far away (max 32).");
            return;
        }

        if (pen.field == Field.DESTINATION) {
            // center-ish
            pen.entrance.setDestination(clicked.clone().add(0.5, 0.0, 0.5));
            e.getPlayer().sendMessage(ChatColor.GREEN + "Destination set.");
        } else if (pen.field == Field.EXIT) {
            if (e.getClickedBlock().getType() != Material.LODESTONE) {
                e.getPlayer().sendMessage(ChatColor.RED + "Exit must be a Lodestone.");
                return;
            }
            pen.entrance.setExitLoc(clicked);
            e.getPlayer().sendMessage(ChatColor.GREEN + "Exit set.");
        }

        PENDING.remove(e.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTask(TrialRooms.getInstance(), () -> open(e.getPlayer(), pen.entrance));
    }

    @EventHandler public void onClose(InventoryCloseEvent e) { /* noop */ }
}
