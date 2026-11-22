package net.tfminecraft.trialrooms.dungeon.locker;

import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * MMOInventory's accessory slots (ring 9, amulet 10) hold a placeholder item when nothing is worn
 * there. A placeholder is never a player's item: wearing over it does not keep it, and taking a
 * loadout off puts the placeholders back where the slots are empty.
 */
public final class Accessories {

    private Accessories() {
    }

    /** MMOInventory marks its placeholder items with this (Utilities.isGuiItem). */
    static final NamespacedKey GUI_ITEM = new NamespacedKey("mmoinventory", "guiitemid");

    /** Whether an inventory slot is one of MMOInventory's accessory slots. */
    static boolean slot(int invSlot) {
        return invSlot == 9 || invSlot == 10;
    }

    /** Whether an item is one of MMOInventory's placeholders (never a player's item). */
    static boolean placeholder(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(GUI_ITEM, PersistentDataType.STRING);
    }

    /** Puts MMOInventory's placeholders back in empty accessory slots (as RPCharacters does after a body swap). */
    static Consumer<Player> refill = Accessories::refillPlaceholders;

    static void refillPlaceholders(Player player) {
        refill(Bukkit.getPluginManager().getPlugin("MMOInventory"), player);
    }

    static void refill(Plugin plugin, Player player) {
        if (plugin == null || !plugin.isEnabled()) {
            return;
        }
        try {
            Object manager = plugin.getClass().getMethod("getInventoryManager").invoke(plugin);
            Object vanilla = manager.getClass().getMethod("getVanilla").invoke(manager);
            if (vanilla != null) {
                vanilla.getClass().getMethod("replacePlaceholderItems", Player.class).invoke(vanilla, player);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // MMOInventory's API moved: the placeholders come back on the player's next join instead
        }
    }
}
