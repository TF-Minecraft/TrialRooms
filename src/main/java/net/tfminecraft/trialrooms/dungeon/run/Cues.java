package net.tfminecraft.trialrooms.dungeon.run;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

/**
 * The cue line: short status and danger text in the middle of the screen, shown as a subtitle
 * (the server's stats display owns the action bar and redraws it twice a second). It is one line,
 * so a danger warning must not be written over by a status line: each cue has a level and holds
 * the line for a while against lower levels.
 */
final class Cues {

    /** Most important last. */
    enum Level {
        INFO, DOWNED, WARNING
    }

    private record Held(Level level, long untilMs) {
    }

    private final Map<UUID, Held> held = new HashMap<>();

    /** Shows a cue unless a more important one still holds the line. True when shown. */
    boolean send(Player p, Level level, String text, long now, long holdMs) {
        Held h = held.get(p.getUniqueId());
        if (h != null && h.untilMs() > now && h.level().compareTo(level) > 0) {
            return false;
        }
        held.put(p.getUniqueId(), new Held(level, now + holdMs));
        p.showTitle(Title.title(Component.empty(), Component.text(text),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(holdMs), Duration.ofMillis(250))));
        return true;
    }
}
