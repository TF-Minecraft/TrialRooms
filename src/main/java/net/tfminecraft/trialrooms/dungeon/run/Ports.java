package net.tfminecraft.trialrooms.dungeon.run;

import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
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

    /** Mana (MMOCore). */
    public interface Mana {
        double mana(Player player);

        double max(Player player);

        /** Adds mana (never past the max). */
        void add(Player player, double amount);
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

    /** What fights do to the world: particles that show warnings, and the damage mechanics deal. */
    public interface Fx {
        /** No particles and plain damage; for tests. */
        Fx NONE = new Fx() {
        };

        /**
         * Mechanic damage: a share of max health that armour should not soak (the server adapter
         * uses magic damage). By default plain damage, from the source when there is one.
         */
        default void hurt(Player player, double amount, Entity source) {
            if (source == null) {
                player.damage(amount);
            } else {
                player.damage(amount, source);
            }
        }

        /** A ring on the ground marking a warned area. */
        default void ring(World world, double x, double y, double z, double radius) {
        }

        /** A short burst at an enemy: a shield holding, a rally. */
        default void burst(Location at) {
        }
    }
}
