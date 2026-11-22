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

    /** Puts a dungeon gem from the locker into a dungeon item in the locker. Returns what to tell them. */
    String socket(Player player, String gem, String item);
}
