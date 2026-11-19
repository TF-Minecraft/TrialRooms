package net.tfminecraft.trialrooms.dungeon.run;

import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/** What the run engine needs from other plugins, behind small interfaces it can be tested without. */
public final class Ports {

    private Ports() {
    }

    /** Characters and parties (RPCharacters). */
    public interface Characters {
        /** The id of the character the player is playing, or null when none is active. */
        String activeCharacterId(Player player);

        /** The player's party leader, or null when they are not in a party. */
        UUID partyLeader(Player player);

        /** Everyone in the player's party, leader included; just the player without a party. */
        List<UUID> partyMembers(Player player);
    }

    /** Key items (TLibs item paths). */
    public interface Keys {
        boolean holds(Player player, String path);

        /** Takes one matching item from the player's inventory; false when they have none. */
        boolean take(Player player, String path);

        /** Gives one item, dropping it at the player's feet when the inventory is full. */
        void give(Player player, String path);
    }

    /** Enemies (MythicMobs). */
    public interface Mobs {
        /** Spawns a mob, or returns null when the type is unknown. */
        Entity spawn(String type, Location at, int level);

        /** Removes a spawned mob and everything its plugin keeps for it. */
        void remove(Entity mob);
    }
}
