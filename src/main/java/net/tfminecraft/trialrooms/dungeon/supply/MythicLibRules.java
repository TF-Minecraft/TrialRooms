package net.tfminecraft.trialrooms.dungeon.supply;

import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import io.lumine.mythic.lib.api.event.PlayerClickEvent;
import io.lumine.mythic.lib.api.event.skill.PlayerCastSkillEvent;

/**
 * MythicLib in dungeon runs. The class skills that heal out of combat on their own (TFMC_RECOVERY,
 * which every class has) are held back, and healing items from outside (MMOItems potions, which act on
 * MythicLib's click event before the server's interact event reaches TrialRooms) don't work: between
 * fights, health comes from healers, lifesteal, supplies and the rest room's fire.
 */
public final class MythicLibRules implements Listener {

    private final Predicate<Player> playing;
    private final Supplier<SupplySettings> settings;
    private final Predicate<ItemStack> outsideHeal;

    /**
     * @param playing     whether a player is playing a run in the dungeon world
     * @param outsideHeal whether an item is healing from outside the dungeons
     */
    public MythicLibRules(Predicate<Player> playing, Supplier<SupplySettings> settings, Predicate<ItemStack> outsideHeal) {
        this.playing = playing;
        this.settings = settings;
        this.outsideHeal = outsideHeal;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCast(PlayerCastSkillEvent event) {
        if (settings.get().holds(event.getCast().getHandler().getId()) && playing.test(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** (RunListener tells the player; this only keeps MMOItems from using the item first.) */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(PlayerClickEvent event) {
        if (!event.isLeftClick() && event.hasItem() && outsideHeal.test(event.getItem()) && playing.test(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
