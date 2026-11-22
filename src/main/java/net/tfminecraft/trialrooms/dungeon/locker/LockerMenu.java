package net.tfminecraft.trialrooms.dungeon.locker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * The locker screen. Every click is the menu's own (nothing is ever picked up), so no item can be
 * carried out of it:
 * <ul>
 * <li>top row: the three loadouts, "wear this loadout" and "wear your own gear";</li>
 * <li>rows two and three: the chosen loadout's slots (armour, off hand, ring, amulet, hotbar);</li>
 * <li>the bottom three rows: every dungeon item in the locker, a page at a time.</li>
 * </ul>
 * Click an item below, then a loadout slot, to put it there; click a filled slot to empty it.
 */
public final class LockerMenu implements InventoryHolder {

    static final int WEAR = 4;
    static final int OWN = 5;
    static final int PREV = 16;
    static final int NEXT = 17;
    static final int STASH = 27;
    static final int PAGE = 27;
    /** Menu slot to inventory slot for a loadout's slots. */
    static final Map<Integer, Integer> LOADOUT_SLOTS = Map.ofEntries(Map.entry(9, 39), Map.entry(10, 38),
            Map.entry(11, 37), Map.entry(12, 36), Map.entry(13, 40), Map.entry(14, 9), Map.entry(15, 10),
            Map.entry(18, 0), Map.entry(19, 1),
            Map.entry(20, 2), Map.entry(21, 3), Map.entry(22, 4), Map.entry(23, 5), Map.entry(24, 6),
            Map.entry(25, 7), Map.entry(26, 8));
    private static final Map<Integer, String> SLOT_NAMES = Map.of(39, "Helmet", 38, "Chestplate", 37, "Leggings",
            36, "Boots", 40, "Off hand", 9, "Ring", 10, "Amulet");

    private final LockerService service;
    private final Player player;
    private final Predicate<Player> canSwap;
    private final Inventory inventory;
    private int loadout;
    private int page;
    private String selected;

    /** @param canSwap whether the player stands where loadouts may be put on and taken off */
    public LockerMenu(LockerService service, Player player, Predicate<Player> canSwap) {
        this.service = service;
        this.player = player;
        this.canSwap = canSwap;
        this.inventory = Bukkit.createInventory(this, 54, Component.text("Dungeon locker"));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    int loadout() {
        return loadout;
    }

    int page() {
        return page;
    }

    String selected() {
        return selected;
    }

    /** Opens the menu; false when the player has no usable locker. */
    public boolean open() {
        if (service.locker(player) == null) {
            player.sendMessage("§cYour dungeon locker can't be opened right now"
                    + " (play a character, or ask staff if this keeps happening).");
            return false;
        }
        render();
        player.openInventory(inventory);
        return true;
    }

    void render() {
        Locker l = service.locker(player);
        inventory.clear();
        if (l == null) {
            return;
        }
        for (int i = 0; i < Locker.LOADOUTS; i++) {
            boolean worn = l.worn() == i;
            inventory.setItem(i, button(i == loadout ? Material.WRITABLE_BOOK : Material.BOOK,
                    "Loadout " + (i + 1) + (worn ? " (worn)" : ""), i == loadout,
                    l.loadout(i).size() + " item(s)"));
        }
        inventory.setItem(WEAR, button(Material.LIME_DYE, "Wear loadout " + (loadout + 1), false,
                "In a dungeon's entrance room, before the doors open:",
                "its items take the place of your own in those slots."));
        inventory.setItem(OWN, button(Material.CHEST, "Wear your own gear", l.worn() >= 0,
                "Puts the loadout back in the locker and your own items back."));
        inventory.setItem(8, button(Material.KNOWLEDGE_BOOK, "Dungeon items stay in the dungeons", false,
                "Click an item below, then a slot above, to put it in this loadout.",
                "Click a filled slot to empty it. One item can be in several loadouts.",
                "Leaving a dungeon puts your own items back, whatever happens."));
        Map<Integer, String> slots = l.loadout(loadout);
        LOADOUT_SLOTS.forEach((menuSlot, invSlot) -> {
            String id = slots.get(invSlot);
            inventory.setItem(menuSlot, id == null || l.item(id) == null
                    ? button(Material.GRAY_STAINED_GLASS_PANE, SLOT_NAMES.getOrDefault(invSlot, "Hotbar " + (invSlot + 1)),
                            false, "Empty")
                    : service.show(l, id));
        });
        List<String> ids = new ArrayList<>(l.items().keySet());
        int pages = Math.max(1, (ids.size() + PAGE - 1) / PAGE);
        page = Math.min(page, pages - 1);
        if (page > 0) {
            inventory.setItem(PREV, button(Material.ARROW, "Previous page", false));
        }
        if (page < pages - 1) {
            inventory.setItem(NEXT, button(Material.ARROW, "Next page", false));
        }
        for (int i = 0; i < PAGE && page * PAGE + i < ids.size(); i++) {
            String id = ids.get(page * PAGE + i);
            ItemStack shown = service.show(l, id);
            if (id.equals(selected)) {
                ItemMeta meta = shown.getItemMeta();
                meta.setEnchantmentGlintOverride(true);
                shown.setItemMeta(meta);
            }
            inventory.setItem(STASH + i, shown);
        }
    }

    private static ItemStack button(Material type, String name, boolean glint, String... lore) {
        ItemStack it = new ItemStack(type);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            lines.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lines);
        if (glint) {
            meta.setEnchantmentGlintOverride(true);
        }
        it.setItemMeta(meta);
        return it;
    }

    /** A click on the menu's own slots (raw slot 0-53). */
    public void click(int slot) {
        Locker l = service.locker(player);
        if (l == null) {
            inventory.clear(); // the character went away under the menu
            return;
        }
        if (slot < Locker.LOADOUTS) {
            loadout = slot;
        } else if (slot == WEAR || slot == OWN) {
            swap(slot == WEAR);
        } else if (slot == PREV) {
            page = Math.max(0, page - 1);
        } else if (slot == NEXT) {
            page++;
        } else if (LOADOUT_SLOTS.containsKey(slot)) {
            slotClick(l, LOADOUT_SLOTS.get(slot));
        } else if (slot >= STASH) { // the stash rows run to the end of the menu
            List<String> ids = new ArrayList<>(l.items().keySet());
            int at = page * PAGE + slot - STASH;
            if (at < ids.size()) {
                String id = ids.get(at);
                selected = id.equals(selected) ? null : id;
            }
        }
        render();
    }

    private void slotClick(Locker l, int invSlot) {
        if (l.worn() >= 0) {
            player.sendMessage("§cTake the worn loadout off first (wear your own gear) to change loadouts.");
            return;
        }
        if (selected == null) {
            l.unassign(loadout, invSlot);
        } else {
            ItemStack item = service.show(l, selected);
            if (!fits(item, invSlot)) {
                player.sendMessage("§cThat doesn't go in the " + SLOT_NAMES.get(invSlot).toLowerCase() + " slot.");
                return;
            }
            l.assign(loadout, invSlot, selected);
            selected = null;
        }
        service.saveLocker(player, l);
    }

    /**
     * Armour slots take only what is worn there, the ring and amulet slots only MMOItems rings and
     * amulets (as MMOInventory allows); the off hand and hotbar take anything.
     */
    static boolean fits(ItemStack item, int invSlot) {
        if (invSlot == 9 || invSlot == 10) {
            return (invSlot == 9 ? "RING" : "AMULET").equalsIgnoreCase(itemType.apply(item));
        }
        EquipmentSlot need = switch (invSlot) {
            case 39 -> EquipmentSlot.HEAD;
            case 38 -> EquipmentSlot.CHEST;
            case 37 -> EquipmentSlot.LEGS;
            case 36 -> EquipmentSlot.FEET;
            default -> null;
        };
        return need == null || wornOn.apply(item) == need;
    }

    /** Where an item is worn: by its equippable part (skinned items), else by its material. */
    static Function<ItemStack, EquipmentSlot> wornOn = LockerMenu::wornOn;

    static Function<Material, EquipmentSlot> materialSlot = Material::getEquipmentSlot;

    /** An item's MMOItems type (RING, AMULET, ...), or null. */
    static Function<ItemStack, String> itemType = LockerMenu::mmoType;

    static String mmoType(ItemStack item) {
        return io.lumine.mythic.lib.api.item.NBTItem.get(item).getType();
    }

    static EquipmentSlot wornOn(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.hasEquippable() ? meta.getEquippable().getSlot() : materialSlot.apply(item.getType());
    }

    private void swap(boolean wear) {
        if (!canSwap.test(player)) {
            player.sendMessage("§cLoadouts go on and come off only in a dungeon's entrance room, before its doors open.");
            return;
        }
        LockerService.Result r = wear ? service.wear(player, loadout) : service.takeOff(player);
        player.sendMessage(switch (r) {
            case DONE -> wear ? "§aYou put on loadout " + (loadout + 1) + ". Your own items wait in the locker."
                    : "§aYou are back in your own gear.";
            case ALREADY_WORN -> "§cA loadout is already on: wear your own gear first.";
            case NOT_WORN -> "§7You are already in your own gear.";
            case EMPTY -> "§cLoadout " + (loadout + 1) + " is empty.";
            case CURSOR -> "§cPut down the item you are holding first.";
            case SAVE_FAILED -> wear ? "§cThe locker could not be saved, so nothing changed. Tell staff if this keeps happening."
                    : "§eYou are back in your own gear, but the locker could not be saved. Tell staff.";
            default -> "§cYour dungeon locker can't be used right now. Tell staff if this keeps happening.";
        });
    }
}
