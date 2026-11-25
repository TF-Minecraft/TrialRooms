package net.tfminecraft.trialrooms.dungeon.gear;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.entity.Player;

import io.lumine.mythic.lib.api.player.EquipmentSlot;
import io.lumine.mythic.lib.api.player.MMOPlayerData;
import io.lumine.mythic.lib.api.stat.modifier.StatModifier;
import io.lumine.mythic.lib.player.modifier.ModifierSource;
import io.lumine.mythic.lib.player.modifier.ModifierType;

/** Set bonuses as MythicLib stat modifiers. */
public final class MythicStats implements GearService.Stats {

    private static final String KEY = "trialrooms_dungeon_sets";

    private final Map<UUID, Set<String>> on = new HashMap<>();

    @Override
    public void set(Player player, Map<String, Double> stats) {
        MMOPlayerData data = MMOPlayerData.get(player.getUniqueId());
        on.getOrDefault(player.getUniqueId(), Set.of()).forEach(stat -> data.getStatMap().getInstance(stat).remove(KEY));
        on.remove(player.getUniqueId());
        stats.forEach((stat, v) -> new StatModifier(KEY, id(stat), v, ModifierType.FLAT, EquipmentSlot.OTHER,
                ModifierSource.OTHER).register(data));
        if (!stats.isEmpty()) {
            on.put(player.getUniqueId(), Set.copyOf(stats.keySet().stream().map(MythicStats::id).toList()));
        }
    }

    private static String id(String stat) {
        return stat.toUpperCase(Locale.ROOT);
    }
}
