package net.tfminecraft.trialrooms.dungeon.loot;

import java.util.List;
import java.util.Map;

import org.bukkit.inventory.ItemStack;

/** What dungeon guns need from GunsAndGadgets. */
public interface GunForge {

    /** A gun taken apart: part ids by slot, and what its maker wrote on it. */
    record Gun(String type, Map<String, String> parts, double power, String marker) {
    }

    /** The parts each slot of a gun type can take in this metal and wood. */
    Map<String, List<String>> slots(String type, String metal, String wood);

    /** A finished gun, or null when these parts don't make one. */
    ItemStack build(String type, Map<String, String> parts, Map<String, Integer> colours, double power, String marker);

    /** A dungeon gun's parts; null for anything else. */
    Gun read(ItemStack item);

    /** A part's name, as players see it. */
    String partName(String part);
}
