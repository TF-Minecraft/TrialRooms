package net.tfminecraft.trialrooms.dungeon.loot;

import java.util.function.Predicate;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import net.Indyuce.mmoitems.api.event.MMOItemReforgeEvent;

/**
 * MMOItems never reforges a dungeon item. A template revision rebuilds items from the template on
 * a click, pickup or join, and keeps only the stats: the dungeon mark, name, sockets and look would
 * go, and an unmarked copy could walk out of the dungeon while the locker still holds the original.
 */
public final class ReforgeGuard implements Listener {

    private final Predicate<ItemStack> dungeonItem;

    public ReforgeGuard(Predicate<ItemStack> dungeonItem) {
        this.dungeonItem = dungeonItem;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onReforge(MMOItemReforgeEvent event) {
        if (dungeonItem.test(event.getReforger().getStack())) {
            event.setCancelled(true);
        }
    }
}
