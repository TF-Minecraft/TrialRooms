package net.tfminecraft.trialrooms.dungeon.run;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;

/**
 * Progress bars at the top of the screen for things that fill or run out: getting revived, getting
 * up alone, bleeding out. One bar per player; a bar nobody refreshed in the last step goes away.
 */
final class Bars {

    private static final class Shown {
        final BossBar bar;
        long seenMs;

        Shown(BossBar bar) {
            this.bar = bar;
        }
    }

    private final Map<UUID, Shown> shown = new HashMap<>();

    /** Shows or updates a player's bar; progress runs from 0 to 1. */
    void show(Player p, String text, double progress, BossBar.Color color, long now) {
        float value = (float) Math.max(0, Math.min(1, progress));
        Shown s = shown.get(p.getUniqueId());
        if (s == null) {
            s = new Shown(BossBar.bossBar(Component.text(text), value, color, BossBar.Overlay.PROGRESS));
            shown.put(p.getUniqueId(), s);
            p.showBossBar(s.bar);
        } else {
            s.bar.name(Component.text(text));
            s.bar.progress(value);
            s.bar.color(color);
        }
        s.seenMs = now;
    }

    /** Takes down every bar not shown since {@code since}. */
    void hideOlder(long since) {
        Iterator<Map.Entry<UUID, Shown>> it = shown.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Shown> e = it.next();
            if (e.getValue().seenMs < since) {
                hide(e.getKey(), e.getValue());
                it.remove();
            }
        }
    }

    private static void hide(UUID id, Shown s) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            p.hideBossBar(s.bar);
        }
    }
}
