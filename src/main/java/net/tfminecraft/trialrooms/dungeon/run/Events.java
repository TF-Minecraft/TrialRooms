package net.tfminecraft.trialrooms.dungeon.run;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.tfminecraft.trialrooms.dungeon.event.EventKind;
import net.tfminecraft.trialrooms.dungeon.event.EventKit;
import net.tfminecraft.trialrooms.dungeon.event.RuneSequence;
import net.tfminecraft.trialrooms.dungeon.run.RunManager.ActiveRun;

/**
 * Room events: cold braziers to light, a room to hold, rune stones to touch in order. An event
 * starts when its room's doors seal and keeps the room's fight from ending until it is done. Its
 * props (braziers, stones) stand on the room's spawn points; enemies it brings come at the others.
 */
final class Events {

    static final String COLD_BRAZIER = "minecraft:soul_campfire[lit=false]";
    static final String LIT_BRAZIER = "minecraft:soul_campfire[lit=true]";
    static final String STONE = "minecraft:chiseled_deepslate";
    static final String LIT_STONE = "minecraft:sea_lantern";
    /** Blocks a player may stand from a brazier while lighting it. */
    static final double REACH = 3.5;
    /** Seconds without a touch before a rune order is shown again. */
    static final int REPLAY_SECONDS = 25;

    /** One room's event. */
    static final class RoomEvent {
        final int room;
        final EventKind kind;
        final List<int[]> props = new ArrayList<>();
        final List<double[]> spawnAt = new ArrayList<>();
        long nextGroupMs;
        boolean done;
        // braziers
        boolean[] lit = new boolean[0];
        final Map<UUID, Integer> lighting = new HashMap<>();
        final Map<UUID, Long> lightingMs = new HashMap<>();
        // hold out
        long startMs;
        long endsMs;
        BossBar bar;
        // rune stones
        RuneSequence runes;
        /** Step of showing the order (three steps a stone), or -1 when not showing. */
        int showStep = -1;
        /** Steps to wait before showing the order (after a mistake). */
        int pause;
        long lastTouchMs;
        int flash = -1;

        RoomEvent(int room, EventKind kind) {
            this.room = room;
            this.kind = kind;
        }

        int prop(int x, int y, int z) {
            for (int i = 0; i < props.size(); i++) {
                int[] b = props.get(i);
                if (b[0] == x && b[1] == y && b[2] == z) {
                    return i;
                }
            }
            return -1;
        }
    }

    private final RunManager runs;
    private final JavaPlugin plugin;
    private final Fights fights;
    private final LongSupplier clock;

    Events(RunManager runs, JavaPlugin plugin, Fights fights, LongSupplier clock) {
        this.runs = runs;
        this.plugin = plugin;
        this.fights = fights;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ starting

    /**
     * The doors of a room just sealed: starts its event, fight included. False when the room has
     * none, or too few spawn points for it (rune stones need two): it gets a plain fight.
     */
    boolean start(ActiveRun ar, int room) {
        EventKind kind = ar.planned.get(room);
        List<double[]> points = ar.geo.markers(room, "spawn");
        if (kind == null) {
            return false;
        }
        if (kind == EventKind.RUNES && points.size() < 2) {
            runs.event(ar, "room " + room + " has too few spawn points for rune stones: a plain fight");
            return false;
        }
        EventKit kit = ar.kit.events();
        long now = clock.getAsLong();
        RoomEvent ev = new RoomEvent(room, kind);
        // the props go down first: the fight then keeps its enemies off them (Fights.points)
        ar.events.put(room, ev);
        Fights.Encounter enc;
        switch (kind) {
            case KINDLE -> {
                props(ar, ev, points, kit.braziers(), 1, COLD_BRAZIER);
                ev.lit = new boolean[ev.props.size()];
                fights.start(ar, room);
                enc = ar.encounters.get(room);
                ev.nextGroupMs = now + kit.everySeconds() * 500L; // the first group comes sooner
                runs.tellRoom(ar, room, "§bThe braziers in this room are cold. §7Enemies keep coming until all "
                        + ev.props.size() + " burn: right-click one and stay beside it to light it.");
            }
            case ENDURE -> {
                spawnAt(ev, points, List.of());
                enc = fights.startHeld(ar, room, "hold out");
                ev.startMs = now;
                ev.endsMs = now + kit.endureSeconds() * 1000L;
                ev.bar = BossBar.bossBar(Component.text("Hold out"), 1f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
                runs.tellRoom(ar, room, "§bThe ice seals the doors. §7Hold out for " + kit.endureSeconds()
                        + " seconds.");
                group(ar, ev, enc, kit.budget() * 1.5);
                ev.nextGroupMs = now + kit.everySeconds() * 1000L;
            }
            default -> {
                props(ar, ev, points, kit.stones(), 2, STONE);
                enc = fights.startHeld(ar, room, "rune stones");
                ev.runes = new RuneSequence(ev.props.size(), kit.runeLength() + ar.rules.tier() - 1,
                        new Random(ar.run.layout().seed() * 31 + room));
                ev.showStep = 0;
                ev.pause = 4; // two seconds to look round before the first stone lights
                ev.lastTouchMs = now;
                runs.tellRoom(ar, room, "§bRune stones wake as the doors close. §7Watch the order they light in,"
                        + " then right-click them in that order. A mistake wakes the ice.");
            }
        }
        enc.hold = () -> !ev.done;
        runs.event(ar, "room " + room + " event " + kind.id() + " with " + ev.props.size() + " props");
        return true;
    }

    /**
     * Puts up to {@code want} props on spawn points far apart from each other, leaving one point free
     * for enemies when the room has more than {@code least}; enemies come at the free ones.
     */
    private void props(ActiveRun ar, RoomEvent ev, List<double[]> points, int want, int least, String block) {
        int n = Math.min(want, Math.max(least, points.size() - 1));
        double[] entry = ar.geo.entry(ev.room);
        List<double[]> chosen = new ArrayList<>();
        List<double[]> left = new ArrayList<>(points);
        while (chosen.size() < n) {
            double[] best = null;
            double bestD = -1;
            for (double[] p : left) {
                double d = Double.MAX_VALUE;
                for (double[] c : chosen.isEmpty() ? List.of(entry) : chosen) {
                    d = Math.min(d, Math.hypot(p[0] - c[0], p[2] - c[2]));
                }
                if (d > bestD) {
                    bestD = d;
                    best = p;
                }
            }
            left.remove(best);
            chosen.add(best);
        }
        for (double[] p : chosen) {
            int[] b = { (int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2]) };
            ev.props.add(b);
            set(b, block);
        }
        spawnAt(ev, left, chosen);
    }

    /** Where an event's enemies come: the spawn points without a prop, or on top of the props in a room without. */
    private static void spawnAt(RoomEvent ev, List<double[]> free, List<double[]> props) {
        if (!free.isEmpty()) {
            ev.spawnAt.addAll(free);
        } else {
            for (double[] p : props) {
                ev.spawnAt.add(new double[] { p[0], p[1] + 1, p[2] });
            }
        }
    }

    private void set(int[] b, String block) {
        runs.world().getBlockAt(b[0], b[1], b[2]).setBlockData(plugin.getServer().createBlockData(block), false);
    }

    // ------------------------------------------------------------------ the half-second step

    /** One step of a room's event, before its fight's step. */
    void step(ActiveRun ar, int room) {
        RoomEvent ev = ar.events.get(room);
        if (ev == null || ev.done) {
            return;
        }
        long now = clock.getAsLong();
        Fights.Encounter enc = ar.encounters.get(room);
        EventKit kit = ar.kit.events();
        switch (ev.kind) {
            case KINDLE -> {
                light(ar, ev, kit, now);
                if (!ev.done && now >= ev.nextGroupMs) {
                    group(ar, ev, enc, kit.budget());
                    ev.nextGroupMs = now + kit.everySeconds() * 1000L;
                }
            }
            case ENDURE -> hold(ar, ev, enc, kit, now);
            default -> runes(ar, ev, enc, kit, now);
        }
    }

    private void light(ActiveRun ar, RoomEvent ev, EventKit kit, long now) {
        long need = kit.lightSeconds() * 1000L;
        Iterator<Map.Entry<UUID, Integer>> it = ev.lighting.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            int i = e.getValue();
            int[] b = ev.props.get(i);
            if (p == null || !ar.run.member(e.getKey()).playing() || ev.lit[i]
                    || p.getLocation().distance(new Location(runs.world(), b[0] + 0.5, b[1], b[2] + 0.5)) > REACH) {
                it.remove();
                ev.lightingMs.remove(e.getKey());
                continue;
            }
            long ms = ev.lightingMs.merge(e.getKey(), RunManager.STEP_MS, Long::sum);
            if (ms < need) {
                runs.bars.show(p, "Lighting the brazier", (double) ms / need, BossBar.Color.YELLOW, now);
                p.playSound(p.getLocation(), "minecraft:item.flintandsteel.use", 0.6f, 1f);
                continue;
            }
            it.remove();
            ev.lightingMs.remove(e.getKey());
            ev.lit[i] = true;
            set(b, LIT_BRAZIER);
            int burning = 0;
            for (boolean l : ev.lit) {
                burning += l ? 1 : 0;
            }
            for (Player q : runs.online(ar)) {
                q.playSound(new Location(runs.world(), b[0], b[1], b[2]), "minecraft:item.firecharge.use", 1f, 0.8f);
                q.spawnParticle(Particle.SOUL_FIRE_FLAME, b[0] + 0.5, b[1] + 1, b[2] + 0.5, 30, 0.4, 0.6, 0.4, 0.02);
            }
            if (burning < ev.lit.length) {
                runs.tellRoom(ar, ev.room, "§b" + p.getName() + " lights a brazier. §7(" + burning + " of "
                        + ev.lit.length + ")");
            } else {
                ev.done = true;
                runs.tellRoom(ar, ev.room, "§bThe last brazier blazes and the cold breaks: no more enemies come.");
                runs.event(ar, "room " + ev.room + " braziers all lit");
            }
        }
    }

    private void hold(ActiveRun ar, RoomEvent ev, Fights.Encounter enc, EventKit kit, long now) {
        long left = ev.endsMs - now;
        if (left <= 0) {
            ev.done = true;
            hideBar(ar, ev);
            fights.removeAll(enc.alive);
            for (Player p : runs.online(ar)) {
                p.playSound(p.getLocation(), "minecraft:block.glass.break", 1f, 0.6f);
            }
            runs.tellRoom(ar, ev.room, "§bThe cold takes the last of them. §7You held the room.");
            runs.event(ar, "room " + ev.room + " held out");
            return;
        }
        ev.bar.progress((float) Math.max(0, Math.min(1, (double) left / (ev.endsMs - ev.startMs))));
        ev.bar.name(Component.text("Hold out: " + (left + 999) / 1000 + "s"));
        for (Player p : runs.online(ar)) {
            p.showBossBar(ev.bar); // the doors are shut: the party is in the room
        }
        if (now >= ev.nextGroupMs) {
            group(ar, ev, enc, kit.budget());
            ev.nextGroupMs = now + kit.everySeconds() * 1000L;
        }
    }

    private void runes(ActiveRun ar, RoomEvent ev, Fights.Encounter enc, EventKit kit, long now) {
        if (ev.flash >= 0) {
            set(ev.props.get(ev.flash), STONE);
            ev.flash = -1;
        }
        if (ev.pause > 0) {
            ev.pause--;
            return;
        }
        if (ev.showStep >= 0) {
            int len = ev.runes.length();
            int k = ev.showStep;
            if (k >= len * 3) {
                ev.showStep = -1;
                ev.lastTouchMs = now;
                return;
            }
            int stone = ev.runes.stone(k / 3);
            if (k % 3 == 0) {
                set(ev.props.get(stone), LIT_STONE);
                note(ar, ev, stone);
            } else if (k % 3 == 2) {
                set(ev.props.get(stone), STONE);
            }
            ev.showStep++;
            return;
        }
        if (now - ev.lastTouchMs >= REPLAY_SECONDS * 1000L) {
            show(ar, ev, "§7The stones light their order again.");
        }
    }

    private void show(ActiveRun ar, RoomEvent ev, String message) {
        ev.runes.restart();
        ev.showStep = 0;
        runs.tellRoom(ar, ev.room, message);
    }

    private void note(ActiveRun ar, RoomEvent ev, int stone) {
        int[] b = ev.props.get(stone);
        for (Player p : runs.online(ar)) {
            p.playSound(new Location(runs.world(), b[0], b[1], b[2]), "minecraft:block.note_block.chime", 1f,
                    0.6f + 0.25f * stone);
            p.spawnParticle(Particle.END_ROD, b[0] + 0.5, b[1] + 1.2, b[2] + 0.5, 8, 0.3, 0.3, 0.3, 0.01);
        }
    }

    /** A group of enemies, unless the room already has its fill. */
    private void group(ActiveRun ar, RoomEvent ev, Fights.Encounter enc, double budget) {
        if (enc.alive.size() < ar.kit.events().most()) {
            fights.reinforce(ar, ev.room, budget, ev.spawnAt);
        }
    }

    // ------------------------------------------------------------------ players

    /** A right-click on a block in the dungeon. True when it was one of the run's props (the click is used up). */
    boolean click(ActiveRun ar, Player p, int x, int y, int z) {
        for (RoomEvent ev : ar.events.values()) {
            int i = ev.prop(x, y, z);
            if (i < 0) {
                continue;
            }
            Location l = p.getLocation();
            if (ev.done || !ar.run.member(p.getUniqueId()).playing() || ar.geo.roomAt(l.getX(), l.getZ()) != ev.room) {
                return true; // used up, but nothing happens
            }
            if (ev.kind == EventKind.KINDLE) {
                if (!ev.lit[i] && !ev.lighting.containsKey(p.getUniqueId())) {
                    ev.lighting.put(p.getUniqueId(), i);
                    p.playSound(p.getLocation(), "minecraft:item.flintandsteel.use", 1f, 1f);
                }
                return true;
            }
            // not while the order shows (or waits to, after a mistake); a repeat press of the stone
            // just accepted (a held click, two players at once) is never the next one, so it is ignored
            boolean repeat = ev.runes.progress() > 0 && ev.runes.stone(ev.runes.progress() - 1) == i;
            if (ev.showStep < 0 && !repeat) {
                touch(ar, ev, i);
            }
            return true;
        }
        return false;
    }

    private void touch(ActiveRun ar, RoomEvent ev, int stone) {
        long now = clock.getAsLong();
        ev.lastTouchMs = now;
        note(ar, ev, stone);
        switch (ev.runes.press(stone)) {
            case RIGHT -> {
                set(ev.props.get(stone), LIT_STONE);
                ev.flash = stone;
            }
            case WRONG -> {
                for (Player p : runs.online(ar)) {
                    p.playSound(p.getLocation(), "minecraft:block.note_block.didgeridoo", 1f, 0.5f);
                }
                group(ar, ev, ar.encounters.get(ev.room), ar.kit.events().budget());
                show(ar, ev, "§cThe stones go dark, and the ice stirs. §7Watch the order again.");
                ev.pause = 4; // two seconds before it starts
            }
            default -> {
                ev.done = true;
                for (int[] b : ev.props) {
                    set(b, LIT_STONE);
                }
                for (Player p : runs.online(ar)) {
                    p.playSound(p.getLocation(), "minecraft:block.amethyst_block.resonate", 1f, 1.2f);
                }
                runs.tellRoom(ar, ev.room, "§bThe stones answer together. §7The way is open.");
                runs.event(ar, "room " + ev.room + " runes solved");
            }
        }
    }

    // ------------------------------------------------------------------ ending

    /** A room was cleared: its event is over. */
    void cleared(ActiveRun ar, int room) {
        RoomEvent ev = ar.events.remove(room);
        if (ev != null) {
            hideBar(ar, ev);
        }
    }

    /** A player left the run: the event bars go from their screen. */
    void left(ActiveRun ar, Player p) {
        for (RoomEvent ev : ar.events.values()) {
            if (ev.bar != null) {
                p.hideBossBar(ev.bar);
            }
        }
    }

    /** The run ends: every event's bar goes. */
    void ended(ActiveRun ar) {
        for (RoomEvent ev : ar.events.values()) {
            hideBar(ar, ev);
        }
    }

    private void hideBar(ActiveRun ar, RoomEvent ev) {
        if (ev.bar == null) {
            return;
        }
        for (Player p : runs.online(ar)) {
            p.hideBossBar(ev.bar);
        }
    }
}
