package net.tfminecraft.trialrooms.environment.spawner.ui;

import net.tfminecraft.trialrooms.TrialRooms;
import net.tfminecraft.trialrooms.environment.spawner.ActiveSpawner;
import net.tfminecraft.trialrooms.loader.TableLoader;
import net.tfminecraft.trialrooms.manager.ChestManager;
import net.tfminecraft.trialrooms.manager.SpawnerManager;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class ActiveSpawnerEditor implements Listener {

    // Tracks which player is currently typing a new value for which field
    public static final Map<UUID, PendingEdit> PENDING = new HashMap<>();

    public enum Field {
        MOB, AMOUNT, LEVEL, SPAWN_RADIUS, ACTIVATE_RADIUS, COOLDOWN, LOOT_TABLE, CHEST, MOB_LOOT_TABLE, DOOR_BLOCKS // NEW
    }



    private record PendingEdit(ActiveSpawner spawner, Field field) {}

    // ---- Public API to open the editor ----
    public static void openEditor(Player player, ActiveSpawner spawner) {
        Inventory inv = Bukkit.createInventory(new Holder(spawner), 27, ChatColor.DARK_AQUA + "Edit Active Spawner");
        populate(inv, spawner);
        player.openInventory(inv);
    }

    // ---- Inventory rendering ----
    private static void populate(Inventory inv, ActiveSpawner s) {
        inv.clear();
        // Cosmetic separators
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler());
        }
        // Re-set our items (ensure they’re not overwritten by filler)
        inv.setItem(10, info("ID", s.getId()));
        inv.setItem(11, info("Block", s.getBlock()));
        inv.setItem(12, info("Location",
                "World: " + (s.getLoc() != null ? s.getLoc().getWorld().getName() : "null"),
                "X: " + (s.getLoc() != null ? s.getLoc().getBlockX() : 0),
                "Y: " + (s.getLoc() != null ? s.getLoc().getBlockY() : 0),
                "Z: " + (s.getLoc() != null ? s.getLoc().getBlockZ() : 0)));
        inv.setItem(14, editable(Field.MOB, "Mob", s.getMob(), Material.ZOMBIE_HEAD));
        inv.setItem(15, editable(Field.AMOUNT, "Amount", String.valueOf(s.getAmount()), Material.PAPER));
        inv.setItem(16, editable(Field.LEVEL, "Level", String.valueOf(s.getLevel()), Material.EXPERIENCE_BOTTLE));
        inv.setItem(17, editable(Field.COOLDOWN, "Cooldown (s)", String.valueOf(s.getCooldownSeconds()), Material.REPEATER));
        inv.setItem(20, editable(Field.SPAWN_RADIUS, "Spawn Radius", String.valueOf(s.getSpawnRadius()), Material.COMPASS));
        inv.setItem(21, editable(Field.ACTIVATE_RADIUS, "Activate Radius", String.valueOf(s.getActivateRadius()), Material.CLOCK));
        inv.setItem(22, editable(Field.LOOT_TABLE, "Loot Table", String.valueOf(s.getLootTable()), Material.CHEST));
        inv.setItem(23, editable(Field.MOB_LOOT_TABLE, "Mob Loot Table",
            String.valueOf(s.getMobLootTable()), Material.BARREL));
        inv.setItem(24, editable(Field.CHEST,
            s.hasChestBound()
                ? "Bound Chest"
                : "Bind Chest",
            s.hasChestBound()
                ? (s.getChestLocation().getBlockX()+", "+s.getChestLocation().getBlockY()+", "+s.getChestLocation().getBlockZ())
                : "none",
            Material.CHEST_MINECART));
        inv.setItem(25, editable(Field.DOOR_BLOCKS,
            "Door Blocks",
            "count: " + s.getDoorBlocksCount(),
            Material.IRON_BARS));
    }

    private static ItemStack filler() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(" ");
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack info(String title, String value) {
        ItemStack it = new ItemStack(Material.BOOK);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + title);
        meta.setLore(Collections.singletonList(ChatColor.YELLOW + (value == null ? "null" : value)));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack info(String title, String... lines) {
        ItemStack it = new ItemStack(Material.BOOK);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + title);
        List<String> lore = new ArrayList<>();
        for (String l : lines) lore.add(ChatColor.YELLOW + l);
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack editable(Field field, String title, String value, Material icon) {
        ItemStack it = new ItemStack(icon);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + title);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Current: " + ChatColor.WHITE + (value == null ? "null" : value));
        lore.add(ChatColor.DARK_GRAY + "Click to edit");
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    // ---- Inventory holder to identify our GUI ----
    private static class Holder implements InventoryHolder {
        private final ActiveSpawner spawner;
        Holder(ActiveSpawner spawner) { this.spawner = spawner; }
        @Override public Inventory getInventory() { return null; }
    }

    // ---- Click handling: pick a field, close, and prompt in chat ----
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!(e.getInventory().getHolder() instanceof Holder holder)) return;

        e.setCancelled(true);
        if (e.getCurrentItem() == null) return;

        int slot = e.getRawSlot();
        Field field = switch (slot) {
            case 14 -> Field.MOB;
            case 15 -> Field.AMOUNT;
            case 16 -> Field.LEVEL;
            case 17 -> Field.COOLDOWN;
            case 20 -> Field.SPAWN_RADIUS;
            case 21 -> Field.ACTIVATE_RADIUS;
            case 22 -> Field.LOOT_TABLE;
            case 23 -> Field.MOB_LOOT_TABLE;
            case 24 -> Field.CHEST;
            case 25 -> Field.DOOR_BLOCKS;
            default -> null;
        };
        if (field == null) return;
        if(field.equals(Field.CHEST) && holder.spawner.getLootTable() == null) {
            player.sendMessage(ChatColor.RED + "Spawner has no loot table!");
            return;
        }

        PENDING.put(player.getUniqueId(), new PendingEdit(holder.spawner, field));
        player.closeInventory();
        player.sendMessage(ChatColor.GREEN + "Type a new value for " + ChatColor.AQUA + fieldName(field)
                + ChatColor.GREEN + " in chat. Type " + ChatColor.RED + "cancel" + ChatColor.GREEN + " to abort.");

        // Provide tiny hints
        switch (field) {
            case AMOUNT, LEVEL, SPAWN_RADIUS, ACTIVATE_RADIUS, COOLDOWN ->
                player.sendMessage(ChatColor.DARK_GRAY + "(integer expected)");
            case MOB ->
                    player.sendMessage(ChatColor.DARK_GRAY + "(string; e.g. ZOMBIE, SKELETON — your logic)");
            case LOOT_TABLE ->
                player.sendMessage(ChatColor.DARK_GRAY + "(string; existing table id — type 'null' or 'none' to clear)");
            case CHEST -> {
                PENDING.put(player.getUniqueId(), new PendingEdit(holder.spawner, field));
                player.closeInventory();
                player.sendMessage(ChatColor.GREEN + "Right-click a chest block with your edit wand to bind it.");
                player.sendMessage(ChatColor.DARK_GRAY + "(Type " + ChatColor.RED + "cancel" + ChatColor.DARK_GRAY + " in chat to abort.)");
                return;
            }
            case DOOR_BLOCKS -> {
                PENDING.put(player.getUniqueId(), new PendingEdit(holder.spawner, field));
                player.closeInventory();
                player.sendMessage(ChatColor.GREEN + "Right-click a block (≤32 blocks away) to add a Door Block, "
                        + "or type " + ChatColor.RED + "remove" + ChatColor.GREEN + " to remove the latest.");
                player.sendMessage(ChatColor.DARK_GRAY + "(Block must not be the spawner block or a chest. Type 'cancel' to abort.)");
                return;
            }
            case MOB_LOOT_TABLE ->
                player.sendMessage(ChatColor.DARK_GRAY + "(string; loot table id used for MOB keys — 'null' to disable)");
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onRightClickChest(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null) return;

        Player p = e.getPlayer();
        PendingEdit pending = PENDING.get(p.getUniqueId());
        if (pending == null) return;

        // Only main hand (avoid double fires)
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (!SpawnerManager.hasEditWand(p)) return;

        // ----- Binding CHEST (existing behavior) -----
        if (pending.field() == Field.CHEST) {
            if (e.getClickedBlock().getType() != Material.CHEST) {
                p.sendMessage(ChatColor.RED + "That’s not a chest. Right-click a chest block.");
                return;
            }
            e.setCancelled(true);
            ChestManager.get().registerBound(pending.spawner(), e.getClickedBlock().getLocation());
            PENDING.remove(p.getUniqueId());
            p.sendMessage(ChatColor.GREEN + "Chest bound at "
                    + ChatColor.WHITE + e.getClickedBlock().getX() + ", "
                    + e.getClickedBlock().getY() + ", "
                    + e.getClickedBlock().getZ() + ChatColor.GREEN + ".");
            return;
        }

        // ----- Adding DOOR_BLOCKS -----
        if (pending.field() == Field.DOOR_BLOCKS) {
            e.setCancelled(true);

            var clicked = e.getClickedBlock();
            var type = clicked.getType();

            // Reject spawner block / chest / air
            if (type == Material.AIR || type == Material.CHEST) {
                p.sendMessage(ChatColor.RED + "Pick a solid, non-chest block.");
                return;
            }
            if (clicked.getLocation().equals(pending.spawner().getLoc())) {
                p.sendMessage(ChatColor.RED + "You can’t use the spawner block.");
                return;
            }

            // Range check (≤ 32 blocks)
            if (!sameWorld(pending.spawner().getLoc(), clicked.getLocation())
                    || pending.spawner().getLoc().distanceSquared(clicked.getLocation()) > (32 * 32)) {
                p.sendMessage(ChatColor.RED + "That block is too far away (max 32).");
                return;
            }

            // Build DoorBlock with material + facing + connections (if present)
            var doorLoc = clicked.getLocation();
            var data = clicked.getBlockData();
            var door = new net.tfminecraft.trialrooms.environment.door.DoorBlock(doorLoc, type);

            if (data instanceof org.bukkit.block.data.Directional dir) {
                door.facing(dir.getFacing());
            }
            if (data instanceof org.bukkit.block.data.MultipleFacing mf) {
                for (org.bukkit.block.BlockFace f : mf.getAllowedFaces()) {
                    if (mf.hasFace(f)) door.connect(f);
                }
            }

            pending.spawner().addDoorBlock(door);
            PENDING.remove(p.getUniqueId());

            p.sendMessage(ChatColor.GREEN + "Added door block: "
                    + ChatColor.WHITE + type + ChatColor.GREEN + " at "
                    + ChatColor.WHITE + doorLoc.getBlockX() + ", "
                    + doorLoc.getBlockY() + ", "
                    + doorLoc.getBlockZ());

            // (Optional) Re-open editor for quick chaining
            Bukkit.getScheduler().runTask(TrialRooms.getInstance(), () -> openEditor(p, pending.spawner()));
        }
    }

    private boolean sameWorld(Location a, Location b) {
        return a != null && b != null && a.getWorld() != null && a.getWorld().equals(b.getWorld());
    }



    // ---- If the player closes the inv without selecting anything, no-op ----
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        // no special behavior required
    }

    // ---- Chat handling: read value, validate, set, reopen ----
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Player player = e.getPlayer();
        PendingEdit pending = PENDING.get(player.getUniqueId());
        if (pending == null) return;

        e.setCancelled(true);
        String msg = e.getMessage().trim();

        if (msg.equalsIgnoreCase("cancel")) {
            PENDING.remove(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "Edit cancelled.");
            Bukkit.getScheduler().runTask(TrialRooms.getInstance(), 
                () -> openEditor(player, pending.spawner()));
            return;
        }

        // Process on main thread (we’re about to modify the spawner and open GUIs)
        Bukkit.getScheduler().runTask(TrialRooms.getInstance(), () -> {
            if (pending.field() == Field.DOOR_BLOCKS) {
                if (msg.equalsIgnoreCase("remove")) {
                    boolean ok = pending.spawner().removeLastDoorBlock();
                    player.sendMessage(ok
                            ? ChatColor.YELLOW + "Removed the latest door block."
                            : ChatColor.RED + "There are no door blocks to remove.");
                    PENDING.remove(player.getUniqueId());
                    Bukkit.getScheduler().runTask(TrialRooms.getInstance(), () -> openEditor(player, pending.spawner()));
                    return;
                }
                if (msg.equalsIgnoreCase("cancel")) {
                    PENDING.remove(player.getUniqueId());
                    player.sendMessage(ChatColor.YELLOW + "Edit cancelled.");
                    Bukkit.getScheduler().runTask(TrialRooms.getInstance(), () -> openEditor(player, pending.spawner()));
                    return;
                }
                // Any other typed input is ignored for DOOR_BLOCKS; user must right-click a block.
                player.sendMessage(ChatColor.RED + "Right-click a block to add a Door Block, or type 'remove'/'cancel'.");
                return;
            }
            boolean ok = applyEdit(player, pending.spawner(), pending.field(), msg);
            if (ok) {
                player.sendMessage(ChatColor.GREEN + "Updated " + ChatColor.AQUA + fieldName(pending.field()) + ChatColor.GREEN + "!");
            }
            PENDING.remove(player.getUniqueId());
            openEditor(player, pending.spawner());
        });
    }

    private static String fieldName(Field f) {
        return switch (f) {
            case MOB -> "Mob";
            case AMOUNT -> "Amount";
            case LEVEL -> "Level";
            case SPAWN_RADIUS -> "Spawn Radius";
            case ACTIVATE_RADIUS -> "Activate Radius";
            case COOLDOWN -> "Cooldown";
            case LOOT_TABLE -> "Loot Table";
            case CHEST -> "Chest";
            case DOOR_BLOCKS -> "Door Blocks";
            case MOB_LOOT_TABLE -> "Mob Loot Table";
        };
    }

    private static boolean applyEdit(Player player, ActiveSpawner s, Field field, String raw) {
        try {
            switch (field) {
                case MOB -> {
                    String val = raw.isEmpty() ? null : raw;
                    s.setMob(val);
                }
                case AMOUNT -> {
                    int v = Integer.parseInt(raw);
                    if (v < 0) throw new IllegalArgumentException("Amount must be >= 0");
                    s.setAmount(v);
                }
                case LEVEL -> {
                    int v = Integer.parseInt(raw);
                    if (v < 1) throw new IllegalArgumentException("Level must be >= 1");
                    s.setLevel(v);
                }
                case SPAWN_RADIUS -> {
                    int v = Integer.parseInt(raw);
                    if (v < 0) throw new IllegalArgumentException("Spawn radius must be >= 0");
                    s.setSpawnRadius(v);
                }
                case ACTIVATE_RADIUS -> {
                    int v = Integer.parseInt(raw);
                    if (v < 0) throw new IllegalArgumentException("Activate radius must be >= 0");
                    s.setActivateRadius(v);
                }
                case COOLDOWN -> {
                    int v = Integer.parseInt(raw);
                    if (v < 0) throw new IllegalArgumentException("Cooldown must be >= 0");
                    s.setCooldownSeconds(v);
                }
                case CHEST -> {
                    break;
                }
                case DOOR_BLOCKS -> {
                    break;
                }
                case MOB_LOOT_TABLE -> {
                    String rawId = raw.trim();
                    if (rawId.equalsIgnoreCase("null") || rawId.equalsIgnoreCase("none") || rawId.isEmpty()) {
                        s.setMobLootTable(null); // disabling mob keys
                        break;
                    }

                    if (TableLoader.getByString(rawId) != null) { s.setMobLootTable(rawId); break; }

                    String resolved = null;
                    for (String key : TableLoader.get().keySet()) {
                        if (key.equalsIgnoreCase(rawId)) { resolved = key; break; }
                    }
                    if (resolved != null) { s.setMobLootTable(resolved); break; }

                    player.sendMessage(ChatColor.RED + "Unknown loot table: " + rawId);
                    StringBuilder sb = new StringBuilder(ChatColor.GRAY + "Available: " + ChatColor.WHITE);
                    int shown = 0;
                    for (String key : TableLoader.get().keySet()) {
                        if (shown++ > 0) sb.append(ChatColor.GRAY).append(", ").append(ChatColor.WHITE);
                        sb.append(key);
                        if (shown >= 10) { sb.append(ChatColor.GRAY).append(", ..."); break; }
                    }
                    player.sendMessage(sb.toString());
                    return false;
                }
                case LOOT_TABLE -> {
                    String rawId = raw.trim();
                    if (rawId.equalsIgnoreCase("null") || rawId.equalsIgnoreCase("none") || rawId.isEmpty()) {
                        s.setLootTable(null);
                        break;
                    }

                    // Exact match
                    if (TableLoader.getByString(rawId) != null) {
                        s.setLootTable(rawId);
                        break;
                    }

                    // Case-insensitive resolve
                    String resolved = null;
                    for (String key : TableLoader.get().keySet()) {
                        if (key.equalsIgnoreCase(rawId)) { resolved = key; break; }
                    }
                    if (resolved != null) {
                        s.setLootTable(resolved);
                        break;
                    }

                    // Not found -> show suggestions
                    player.sendMessage(ChatColor.RED + "Unknown loot table: " + rawId);
                    StringBuilder sb = new StringBuilder(ChatColor.GRAY + "Available: " + ChatColor.WHITE);
                    int shown = 0;
                    for (String key : TableLoader.get().keySet()) {
                        if (shown++ > 0) sb.append(ChatColor.GRAY).append(", ").append(ChatColor.WHITE);
                        sb.append(key);
                        if (shown >= 10) { sb.append(ChatColor.GRAY).append(", ..."); break; }
                    }
                    player.sendMessage(sb.toString());
                    return false;
                }
            }
            s.refreshStatusHolograms();
            return true;
        } catch (NumberFormatException ex) {
            player.sendMessage(ChatColor.RED + "That wasn’t a valid number.");
        } catch (IllegalArgumentException ex) {
            player.sendMessage(ChatColor.RED + ex.getMessage());
        } catch (Exception ex) {
            player.sendMessage(ChatColor.RED + "Error: " + ex.getMessage());
        }
        return false;
    }
}
