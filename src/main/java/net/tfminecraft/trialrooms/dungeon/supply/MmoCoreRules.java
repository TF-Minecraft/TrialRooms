package net.tfminecraft.trialrooms.dungeon.supply;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import io.lumine.mythic.lib.api.player.MMOPlayerData;
import io.lumine.mythic.lib.api.stat.StatMap;
import io.lumine.mythic.lib.api.stat.modifier.StatModifier;
import io.lumine.mythic.lib.player.modifier.ModifierType;
import io.lumine.mythic.lib.player.resource.ResourceUpdateReason;
import net.Indyuce.mmocore.api.player.PlayerData;
import net.tfminecraft.trialrooms.dungeon.run.Ports;

/**
 * MMOCore in dungeon runs. Health doesn't come back on its own there: while a player plays a run, their
 * health regeneration stats are held at nothing (MMOCore heals regen straight through MythicLib, with no
 * event to stop), and given back as soon as they don't. Mana for supplies and the campfire comes from here.
 */
public final class MmoCoreRules implements Listener, Ports.Mana {

    /** The stats MMOCore's health regeneration reads. */
    static final List<String> REGEN = List.of("HEALTH_REGENERATION", "MAX_HEALTH_REGENERATION");
    static final String KEY = "trialrooms_dungeon_regen";

    private final Predicate<Player> playing;
    private final Function<Player, PlayerData> data;
    private final Function<Player, StatMap> stats;
    /** The modifiers put on each player playing a run. */
    private final Map<UUID, List<StatModifier>> held = new HashMap<>();

    /** @param playing whether a player is playing a run in the dungeon world */
    public MmoCoreRules(Predicate<Player> playing) {
        this(playing, PlayerData::get, p -> MMOPlayerData.get(p).getStatMap());
    }

    MmoCoreRules(Predicate<Player> playing, Function<Player, PlayerData> data, Function<Player, StatMap> stats) {
        this.playing = playing;
        this.data = data;
        this.stats = stats;
    }

    /** Once a second: regen held for those playing a run, given back to everyone else. */
    public void tick(Collection<? extends Player> online) {
        Set<UUID> here = new HashSet<>();
        for (Player p : online) {
            here.add(p.getUniqueId());
            try {
                hold(p, playing.test(p));
            } catch (RuntimeException e) { // (one player's stats never stop everyone else's)
                held.remove(p.getUniqueId());
            }
        }
        held.keySet().retainAll(here);
    }

    private void hold(Player p, boolean play) {
        if (play && !held.containsKey(p.getUniqueId())) {
            List<StatModifier> mods = new ArrayList<>();
            StatMap map = stats.apply(p);
            for (String stat : REGEN) {
                // (COMPOUND multiplies, so the total is 0 whatever other modifiers add up to)
                StatModifier m = new StatModifier(KEY, stat, -100, ModifierType.COMPOUND);
                map.getInstance(stat).registerModifier(m);
                mods.add(m);
            }
            held.put(p.getUniqueId(), mods);
        } else if (!play && held.containsKey(p.getUniqueId())) {
            release(p);
        }
    }

    /** Gives a player their regen back (a logout must not carry the hold into their next session). */
    void release(Player p) {
        List<StatModifier> mods = held.remove(p.getUniqueId());
        if (mods == null) {
            return;
        }
        StatMap map = stats.apply(p);
        for (StatModifier m : mods) {
            map.getInstance(m.getStat()).removeModifier(m.getUniqueId());
        }
    }

    /** Gives everyone held their regen back (the plugin stops). */
    public void releaseAll(Collection<? extends Player> online) {
        online.forEach(this::release);
        held.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        release(event.getPlayer());
    }

    @Override
    public double mana(Player player) {
        return data.apply(player).getMana();
    }

    @Override
    public double max(Player player) {
        return data.apply(player).getStats().getStat("MAX_MANA");
    }

    @Override
    public void add(Player player, double amount) {
        PlayerData d = data.apply(player);
        d.giveMana(Math.min(amount, Math.max(0, max(player) - d.getMana())), ResourceUpdateReason.OTHER);
    }
}
