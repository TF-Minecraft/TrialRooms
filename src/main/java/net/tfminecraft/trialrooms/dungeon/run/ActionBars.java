package net.tfminecraft.trialrooms.dungeon.run;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;

/**
 * The action bar is one line: a danger warning must not be written over by a status line. Each
 * message has a level and holds the bar for a while against lower levels.
 */
final class ActionBars {

    /** Most important last. */
    enum Level {
        INFO, DOWNED, WARNING
    }

    private record Held(Level level, long untilMs) {
    }

    private final Map<UUID, Held> held = new HashMap<>();

    /** Shows a message unless a more important one still holds the bar. True when shown. */
    boolean send(Player p, Level level, String text, long now, long holdMs) {
        Held h = held.get(p.getUniqueId());
        if (h != null && h.untilMs() > now && h.level().compareTo(level) > 0) {
            return false;
        }
        held.put(p.getUniqueId(), new Held(level, now + holdMs));
        p.sendActionBar(Component.text(text));
        return true;
    }
}
