package net.tfminecraft.trialrooms.dungeon.loot;

import java.util.List;
import java.util.Map;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.magic.api.DungeonWeapons;

/** Mage weapons from Magic. */
public final class MagicForge implements MageForge {

    @Override
    public List<String> archetypes() {
        return DungeonWeapons.archetypes();
    }

    @Override
    public List<String> runes(String archetype, String slot) {
        return DungeonWeapons.runes(archetype, slot);
    }

    @Override
    public ItemStack build(String archetype, List<String> runes, double power, Map<String, Double> extraStats, String name,
            List<String> lore) {
        return DungeonWeapons.build(archetype, runes, power, extraStats, name, lore);
    }
}
