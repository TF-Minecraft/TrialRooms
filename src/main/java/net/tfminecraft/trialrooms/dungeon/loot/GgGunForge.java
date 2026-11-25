package net.tfminecraft.trialrooms.dungeon.loot;

import java.util.List;
import java.util.Map;

import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.gunsandgadgets.api.DungeonGuns;
import net.tfminecraft.gunsandgadgets.loader.PartLoader;

/** Guns from GunsAndGadgets. */
public final class GgGunForge implements GunForge {

    @Override
    public Map<String, List<String>> slots(String type, String metal, String wood) {
        return DungeonGuns.slots(type, metal, wood);
    }

    @Override
    public ItemStack build(String type, Map<String, String> parts, Map<String, Integer> colours, double power, String marker) {
        return DungeonGuns.build(type, parts, colours, power, marker);
    }

    @Override
    public Gun read(ItemStack item) {
        return DungeonGuns.read(item).map(p -> new Gun(p.gunType(), p.partIds(), p.power(), p.marker())).orElse(null);
    }

    @Override
    @SuppressWarnings("deprecation") // GunsAndGadgets' names are legacy text
    public String partName(String part) {
        return ChatColor.stripColor(PartLoader.getByString(part).getName());
    }
}
