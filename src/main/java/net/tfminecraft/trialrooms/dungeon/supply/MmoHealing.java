package net.tfminecraft.trialrooms.dungeon.supply;

import org.bukkit.inventory.ItemStack;

import io.lumine.mythic.lib.api.item.NBTItem;

/** MMOItems consumables that restore health or mana: healing from outside, which doesn't work in runs. */
public final class MmoHealing {

    private MmoHealing() {
    }

    public static boolean heals(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        NBTItem nbt = NBTItem.get(item);
        return nbt.hasTag("MMOITEMS_RESTORE_HEALTH") || nbt.hasTag("MMOITEMS_RESTORE_MANA");
    }
}
