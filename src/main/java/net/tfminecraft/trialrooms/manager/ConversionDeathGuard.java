package net.tfminecraft.trialrooms.manager;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import net.tfminecraft.trialrooms.cache.Cache;
import net.tfminecraft.trialrooms.player.PlayerData;

public final class ConversionDeathGuard implements Listener {

    @EventHandler(priority = EventPriority.LOWEST) // fires FIRST
    public void onPlayerDeath(PlayerDeathEvent e) {
        final Player p = e.getEntity();
        PlayerData data = PlayerManager.get().get(p);
        if(data.isInDungeon()) data.exit();

        // 1) Strip convertible items from drops (works when keepInventory=false)
        e.getDrops().removeIf(this::isConvertible);

        // 2) Also purge from the inventory so keepInventory (or other plugins) can't keep them
        PlayerInventory inv = p.getInventory();

        // storage contents (main inventory + hotbar)
        ItemStack[] storage = inv.getStorageContents();
        boolean changed = false;
        for (int i = 0; i < storage.length; i++) {
            if (isConvertible(storage[i])) { storage[i] = null; changed = true; }
        }
        if (changed) inv.setStorageContents(storage);

        // armor contents
        ItemStack[] armor = inv.getArmorContents();
        changed = false;
        for (int i = 0; i < armor.length; i++) {
            if (isConvertible(armor[i])) { armor[i] = null; changed = true; }
        }
        if (changed) inv.setArmorContents(armor);

        // extra contents (offhand, etc.)
        ItemStack[] extra = inv.getExtraContents();
        changed = false;
        for (int i = 0; i < extra.length; i++) {
            if (isConvertible(extra[i])) { extra[i] = null; changed = true; }
        }
        if (changed) inv.setExtraContents(extra);

        // (No need to call updateInventory() on a dead player; the engine handles it)
    }

    private boolean isConvertible(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return false;
        try {
            // Your existing helper: >0 means it should be converted on exit ⇒ delete on death
            return Cache.getConversionAmount(it) > 0.0;
        } catch (Throwable ignored) {
            return false;
        }
    }
}

