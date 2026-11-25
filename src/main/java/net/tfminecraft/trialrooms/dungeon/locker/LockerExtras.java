package net.tfminecraft.trialrooms.dungeon.locker;

import java.util.List;
import java.util.Map;

import org.bukkit.entity.Player;

/** What the locker screen offers beyond loadouts: the mark shop and socketing dungeon gems. */
public interface LockerExtras {

    /** An item a player can buy: its id, a name to show, and marks by rarity (in order). */
    record Offer(String id, String name, Map<String, Integer> prices) {
    }

    /** The items the player's class can buy. */
    List<Offer> offers(Player player);

    /** Buys an item of the player's choice at a rarity. Returns what to tell them. */
    String buy(Player player, String item, String rarity);

    /** A part two guns can trade: its slot, and the part of each gun. */
    record Part(String slot, String own, String other) {
    }

    /** The parts two of the locker's items can trade (none unless they are guns of a type). */
    List<Part> parts(Player player, String item, String other);

    /** Trades a part between two guns in the locker. Returns what to tell them. */
    String trade(Player player, String item, String other, String slot);

    /** Puts a dungeon gem from the locker into a dungeon item in the locker. Returns what to tell them. */
    String socket(Player player, String gem, String item);
}
