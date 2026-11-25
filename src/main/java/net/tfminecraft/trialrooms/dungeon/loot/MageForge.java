package net.tfminecraft.trialrooms.dungeon.loot;

import java.util.List;
import java.util.Map;

import org.bukkit.inventory.ItemStack;

/** What dungeon mage weapons need from Magic. */
public interface MageForge {

    /** The archetypes it can build (staff, wand, sword). */
    List<String> archetypes();

    /** The runes that fit a socket slot (item paths, m.school.rune). */
    List<String> runes(String archetype, String slot);

    /** A finished weapon with these runes in it, or null. */
    ItemStack build(String archetype, List<String> runes, double power, Map<String, Double> extraStats, String name,
            List<String> lore);
}
