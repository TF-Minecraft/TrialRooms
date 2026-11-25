package net.tfminecraft.trialrooms.dungeon.loot;

import java.util.Random;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Base;
import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Rarity;

/** Rolls the weapons that another plugin builds whole (guns, mage weapons). */
public interface WeaponMaker {

    /** What a bonus stat or socket an item can't carry adds to its power instead. */
    double IN_PLACE = 0.05;

    /** Whether it can build this item now. */
    boolean makes(LootKit kit, Base base);

    /** A rolled weapon, or null when it can't be built. {@code dungeon}: as {@link LootRoller#set}. */
    ItemStack make(LootKit kit, Base base, int tier, Rarity rarity, String dungeon, Random rng);
}
