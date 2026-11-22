package net.tfminecraft.trialrooms.dungeon.loot;

import java.util.function.Predicate;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import net.Indyuce.mmoitems.api.event.MMOItemReforgeEvent;
import net.Indyuce.mmoitems.api.event.item.ApplyGemStoneEvent;
import net.Indyuce.mmoitems.api.event.item.ApplySoulboundEvent;
import net.Indyuce.mmoitems.api.event.item.DeconstructItemEvent;
import net.Indyuce.mmoitems.api.event.item.RepairItemEvent;
import net.Indyuce.mmoitems.api.event.item.UpgradeItemEvent;
import net.Indyuce.mmoitems.api.event.item.UnsocketGemStoneEvent;
import net.Indyuce.mmoitems.api.interaction.GemStone;
import net.Indyuce.mmoitems.api.item.mmoitem.MMOItem;
import net.Indyuce.mmoitems.api.item.mmoitem.ReadMMOItem;

/**
 * MMOItems never reforges a dungeon item, never takes a gem out of one (an Unsocket Tool would hand
 * the gem back as a normal gem), and never sockets a gem into or out of the dungeons outside the
 * locker's own socketing.
 *
 * <p>Reforging: A template revision rebuilds items from the template on
 * a click, pickup or join, and keeps only the stats: the dungeon mark, name, sockets and look would
 * go, and an unmarked copy could walk out of the dungeon while the locker still holds the original.
 */
public final class ReforgeGuard implements Listener {

    private final Predicate<ItemStack> dungeonItem;

    public ReforgeGuard(Predicate<ItemStack> dungeonItem) {
        this.dungeonItem = dungeonItem;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUnsocket(UnsocketGemStoneEvent event) {
        if (dungeon(event.getTargetItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSocket(ApplyGemStoneEvent event) {
        if (!MmoLootForge.socketing() && (dungeon(event.getGemStone()) || dungeon(event.getTargetItem()))) {
            event.setCancelled(true);
            event.setResult(GemStone.ResultType.NONE);
        }
    }

    /** MMOItems' other consumables never work on a dungeon item (deconstructing one would make loot of it). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeconstruct(DeconstructItemEvent event) {
        cancelOn(event, event.getDeconstructedItem());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUpgrade(UpgradeItemEvent event) {
        if (dungeon(event.getTargetItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRepair(RepairItemEvent event) {
        cancelOn(event, event.getTargetItem());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSoulbound(ApplySoulboundEvent event) {
        cancelOn(event, event.getTargetItem());
    }

    private void cancelOn(org.bukkit.event.Cancellable event, io.lumine.mythic.lib.api.item.NBTItem target) {
        if (target != null && dungeonItem.test(target.getItem())) {
            event.setCancelled(true);
        }
    }

    private boolean dungeon(MMOItem item) {
        return item instanceof ReadMMOItem read && dungeonItem.test(read.getNBT().getItem());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onReforge(MMOItemReforgeEvent event) {
        if (dungeonItem.test(event.getReforger().getStack())) {
            event.setCancelled(true);
        }
    }
}
