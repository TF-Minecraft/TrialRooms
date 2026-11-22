package net.tfminecraft.trialrooms.dungeon.loot;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** What loot needs from the item plugins (AdvancedCrafting, MMOItems, TLibs), behind one port. */
public interface LootForge {

    /**
     * An AdvancedCrafting recipe made of the act's standard material.
     *
     * @param template the MMOItems template it builds on (as AdvancedCrafting names it: file.id)
     * @param stats    what it gives made of the standard material
     * @param managed  every stat the recipe owns (zeroed on the template before the item's own go on)
     */
    record Recipe(String template, Map<String, Double> stats, Set<String> managed) {
    }

    /** The recipe made of the standard ingredient for its main type; null when it can't be made. */
    Recipe recipe(String recipe, Map<String, String> standard);

    /** The classes a template is limited to (empty: anyone); null when the template doesn't exist. */
    List<String> classes(String template);

    /** The class a player plays (as items name it), or null. */
    String playerClass(Player player);

    /** Builds a rolled item on its template. */
    ItemStack make(LootRoller.Plan plan, String template, Set<String> managed, String socket);

    /** Builds a dungeon gem on a gem template, coloured for dungeon sockets. */
    ItemStack makeGem(LootRoller.GemPlan plan, String template, String socket);

    /** How a socketing went. */
    enum Socketing {
        DONE, BROKE, NO_FIT
    }

    /** A socketing: the item with the gem in it when DONE. */
    record Socketed(Socketing result, ItemStack item) {
    }

    /** Puts a gem into an item's free socket, as MMOItems does for a player. */
    Socketed socket(Player player, ItemStack item, ItemStack gem);
}
