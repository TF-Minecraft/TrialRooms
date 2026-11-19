package net.tfminecraft.trialrooms.dungeon.run;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.tfminecraft.trialrooms.dungeon.fight.Affix;
import net.tfminecraft.trialrooms.dungeon.fight.BossDef;
import net.tfminecraft.trialrooms.dungeon.fight.BossFight;
import net.tfminecraft.trialrooms.dungeon.fight.Director;
import net.tfminecraft.trialrooms.dungeon.fight.FightKit;
import net.tfminecraft.trialrooms.dungeon.fight.FightTuning;
import net.tfminecraft.trialrooms.dungeon.fight.Mechanic;
import net.tfminecraft.trialrooms.dungeon.fight.Telegraph;
import net.tfminecraft.trialrooms.dungeon.run.DungeonRun.Member;
import net.tfminecraft.trialrooms.dungeon.run.RunManager.ActiveRun;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;

/**
 * Runs the fights of kits that have a roster: waves from the director, elites and their affixes,
 * bosses with phases and telegraphed mechanics, and the cues that let every player (blind
 * characters included) follow along by sound and text.
 */
final class Fights {

    /** One room's fight. */
    static final class Encounter {
        final int room;
        final Director.Plan plan;
        final long startedMs;
        final int downsAtStart;
        final Random rng;
        int wave = -1;
        int waveSize;
        long waveStartMs;
        long nextWaveAtMs = -1;
        long lastHitMs;
        long nextPingMs;
        boolean lastFew;
        final Set<Entity> alive = new LinkedHashSet<>();
        final Map<UUID, Affix> elites = new HashMap<>();
        final Map<UUID, Integer> waveOf = new HashMap<>();
        final Set<UUID> shieldDown = new HashSet<>();
        final Map<UUID, Long> nextRally = new HashMap<>();
        final Map<UUID, Long> ralliedUntil = new HashMap<>();
        final Set<Entity> extras = new HashSet<>();
        final List<Active> telegraphs = new ArrayList<>();
        BossFight boss;
        LivingEntity bossMob;
        BossBar bar;
        final Set<Entity> adds = new HashSet<>();
        UUID lastSlam;

        Encounter(int room, Director.Plan plan, long now, int downs, long seed) {
            this.room = room;
            this.plan = plan;
            this.startedMs = now;
            this.downsAtStart = downs;
            this.rng = new Random(seed);
            this.lastHitMs = now;
        }

        /** A wave fight is over: no enemy left, no wave to come (none called either), no burst still to land. */
        boolean done() {
            return alive.isEmpty() && wave >= plan.waves().size() - 1 && telegraphs.isEmpty();
        }
    }

    /** A telegraph being shown, with who stood inside it at the last look. */
    static final class Active {
        final Telegraph t;
        final Map<UUID, Boolean> inside = new HashMap<>();
        final Map<UUID, Long> warned = new HashMap<>();

        Active(Telegraph t) {
            this.t = t;
        }
    }

    private final RunManager runs;
    private final JavaPlugin plugin;
    private final Ports.Mobs mobs;
    private final Ports.Fx fx;
    private final LongSupplier clock;
    private final NamespacedKey scaleKey;
    private final NamespacedKey rootKey;
    private BukkitTask telegraphTask;

    Fights(RunManager runs, JavaPlugin plugin, Ports.Mobs mobs, Ports.Fx fx, LongSupplier clock) {
        this.runs = runs;
        this.plugin = plugin;
        this.mobs = mobs;
        this.fx = fx;
        this.clock = clock;
        this.scaleKey = new NamespacedKey(plugin, "dungeon_scale");
        this.rootKey = new NamespacedKey(plugin, "dungeon_root");
    }

    // ------------------------------------------------------------------ health

    /** Multiplies a mob's max health by {@code factor} (one keyed modifier, so it is never doubled) and fills it. */
    void scale(Entity mob, double factor) {
        if (!(mob instanceof LivingEntity living)) {
            return;
        }
        AttributeInstance max = living.getAttribute(Attribute.MAX_HEALTH);
        if (max == null) {
            return;
        }
        max.removeModifier(scaleKey);
        if (factor != 1) {
            max.addTransientModifier(new AttributeModifier(scaleKey, factor - 1,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
        living.setHealth(max.getValue());
        // MythicMobs may set health again once the mob is set up: fill it once more a tick later.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (living.isValid()) {
                living.setHealth(max.getValue());
            }
        });
    }

    // ------------------------------------------------------------------ starting

    /** The doors of a fight room just shut: plan the fight and bring the first wave (or the boss). */
    void start(ActiveRun ar, int room) {
        long now = clock.getAsLong();
        FightKit kit = ar.kit.fights();
        RoomType type = ar.run.layout().rooms().get(room).node().type();
        // Mixed, so neighbouring rooms and seeds don't get look-alike fights.
        long seed = new java.util.SplittableRandom(ar.run.layout().seed() * 31 + room).nextLong();
        Random rng = new Random(seed);
        List<String> tags = ar.geo.markerTags(room, "spawn");
        int depth = ar.run.roomsCleared();
        BossDef def = type == RoomType.BOSS ? kit.boss(ar.rules.boss()) : null;
        Director.Plan plan = def != null ? new Director.Plan(0, "boss", List.of())
                : Director.plan(kit, type == RoomType.BOSS ? RoomType.ELITE : type, depth, ar.rules.tier(), tags, rng);
        Encounter enc = new Encounter(room, plan, now, ar.run.downs(), seed);
        ar.encounters.put(room, enc);
        if (def != null && startBoss(ar, enc, def, now)) {
            runs.event(ar, "room " + room + " boss " + def.id() + " awakens");
            return;
        }
        if (def != null) {
            // The boss's mob is missing: fight a strong pack instead of an empty room.
            enc = new Encounter(room, Director.plan(kit, RoomType.ELITE, depth, ar.rules.tier(), tags, rng), now,
                    ar.run.downs(), seed);
            ar.encounters.put(room, enc);
        }
        runs.event(ar, "room " + room + " " + type + ": budget " + String.format("%.1f", enc.plan.budget()) + ", pack "
                + enc.plan.pack() + ", " + enc.plan.waves().size() + " wave(s), " + enc.plan.enemies() + " enemies");
        int waves = enc.plan.waves().size();
        int first = startWave(ar, enc, 0, now);
        for (Player p : inRoom(ar, room)) {
            p.sendMessage("§cThe doors seal. §7" + (waves > 1 ? "Wave 1 of " + waves + ": " : "Enemies: ") + first
                    + ".");
        }
    }

    /** Spawns a wave; returns how many came. */
    private int startWave(ActiveRun ar, Encounter enc, int w, long now) {
        enc.wave = w;
        enc.waveStartMs = now;
        enc.lastHitMs = now;
        enc.nextWaveAtMs = -1;
        if (w >= enc.plan.waves().size()) {
            return 0;
        }
        List<double[]> points = ar.geo.markers(enc.room, "spawn");
        int n = 0;
        for (Director.Spawn s : enc.plan.waves().get(w)) {
            Location at = spawnPoint(ar, enc.room, points, s);
            Entity mob = spawn(ar, enc, s.entry().mob(), at);
            if (mob == null) {
                continue;
            }
            n++;
            enc.alive.add(mob);
            enc.waveOf.put(mob.getUniqueId(), w);
            double factor = ar.healthFactor;
            if (s.elite()) {
                factor *= ar.kit.fights().tuning().eliteHealth();
                enc.elites.put(mob.getUniqueId(), s.affix());
                String name = s.affix().title() + " " + baseName(mob);
                mob.customName(Component.text(name, NamedTextColor.GOLD));
                mob.setCustomNameVisible(true);
                runs.tellRoom(ar, enc.room, "§6A " + name + " leads this pack: §7it " + s.affix().hint() + ".");
            }
            scale(mob, Math.min(factor, ar.kit.fights().tuning().healthCap()));
        }
        enc.waveSize = n;
        if (w > 0) {
            runs.tellRoom(ar, enc.room, "§cWave " + (w + 1) + " of " + enc.plan.waves().size() + ": §7" + n
                    + " enemies.");
        }
        return n;
    }

    static String baseName(Entity mob) {
        Component name = mob.customName();
        String plain = name == null ? null
                : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(name);
        return plain == null || plain.isBlank() ? mob.getName() : plain;
    }

    /** Spawns one mob of the fight and registers it with the run. */
    private Entity spawn(ActiveRun ar, Encounter enc, String type, Location at) {
        Entity mob = mobs.spawn(type, at, ar.rules.mobLevel());
        if (mob == null) {
            plugin.getLogger().warning("[dungeons] unknown mob " + type + " in kit " + ar.kit.id());
            return null;
        }
        // Its spawn event may have filed it as a stray; it is one of ours.
        enc.extras.remove(mob);
        ar.extras.remove(mob);
        runs.register(ar, mob, new double[] { at.getX(), at.getY(), at.getZ() });
        return mob;
    }

    /**
     * Where an enemy appears: its marker, or for the second and later enemy on a marker a free
     * spot around it (floor below, room to stand, still inside the room), else the marker itself.
     */
    private Location spawnPoint(ActiveRun ar, int room, List<double[]> points, Director.Spawn s) {
        World w = runs.world();
        double[] base = s.marker() >= 0 ? points.get(s.marker()) : ar.geo.centre(room);
        if (s.reuse() > 0) {
            for (int k = 0; k < 8; k++) {
                double angle = (s.reuse() * 3 + k) * Math.PI / 4;
                double x = base[0] + Math.cos(angle) * 1.5;
                double z = base[2] + Math.sin(angle) * 1.5;
                if (standable(w, x, base[1], z)) {
                    return new Location(w, x, base[1], z);
                }
            }
        }
        return new Location(w, base[0], base[1], base[2]);
    }

    private static boolean standable(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        Block feet = w.getBlockAt(bx, by, bz);
        return feet.isPassable() && w.getBlockAt(bx, by + 1, bz).isPassable() && !w.getBlockAt(bx, by - 1, bz).isPassable();
    }

    /** Spawns a boss at its room's boss marker (or the room's centre); false when its mob can't be had. */
    boolean startBoss(ActiveRun ar, Encounter enc, BossDef def, long now) {
        List<String> tags = ar.geo.markerTags(enc.room, "spawn");
        int marker = tags.indexOf("boss");
        double[] at = marker < 0 ? ar.geo.centre(enc.room) : ar.geo.markers(enc.room, "spawn").get(marker);
        Entity mob = spawn(ar, enc, def.mob(), new Location(runs.world(), at[0], at[1], at[2]));
        if (!(mob instanceof LivingEntity living)) {
            if (mob != null) {
                runs.forget(mob);
                mobs.remove(mob);
            }
            return false;
        }
        AttributeInstance max = living.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) {
            max.setBaseValue(def.health());
        }
        scale(living, Math.min(ar.healthFactor, ar.kit.fights().tuning().healthCap()));
        living.customName(Component.text(def.name(), NamedTextColor.DARK_RED));
        living.setCustomNameVisible(true);
        enc.boss = new BossFight(def, ar.kit.fights().tuning(), now);
        enc.bossMob = living;
        enc.bar = BossBar.bossBar(Component.text(def.name()), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
        for (Player p : runs.online(ar)) {
            p.showBossBar(enc.bar);
            p.playSound(living.getLocation(), "minecraft:entity.wither.spawn", 0.6f, 1f);
        }
        runs.tell(ar, "§4" + def.name() + " awakens.");
        if (def.phases().getFirst().adds() > 0) {
            spawnAdds(ar, enc, def.phases().getFirst().adds());
        }
        return true;
    }

    private void spawnAdds(ActiveRun ar, Encounter enc, double budget) {
        List<double[]> points = ar.geo.markers(enc.room, "spawn");
        List<String> tags = ar.geo.markerTags(enc.room, "spawn");
        for (Director.Spawn s : Director.adds(ar.kit.fights(), budget, tags, enc.rng)) {
            Entity mob = spawn(ar, enc, s.entry().mob(), spawnPoint(ar, enc.room, points, s));
            if (mob != null) {
                enc.adds.add(mob);
                scale(mob, Math.min(ar.healthFactor, ar.kit.fights().tuning().healthCap()));
            }
        }
        for (Player p : inRoom(ar, enc.room)) {
            p.playSound(p.getLocation(), "minecraft:item.goat_horn.sound.1", 1f, 1f);
        }
    }

    // ------------------------------------------------------------------ the half-second step

    /** One step of a room's fight. True when it is over and the room can open. */
    boolean step(ActiveRun ar, int room) {
        Encounter enc = ar.encounters.get(room);
        if (enc == null) {
            return true;
        }
        long now = clock.getAsLong();
        prune(enc.alive);
        prune(enc.adds);
        prune(enc.extras);
        if (enc.boss != null) {
            return stepBoss(ar, enc, now);
        }
        affixes(ar, enc, now);
        waves(ar, enc, now);
        stall(ar, enc, now);
        lastFew(ar, enc, now);
        return enc.done();
    }

    private void prune(Set<Entity> set) {
        Iterator<Entity> it = set.iterator();
        while (it.hasNext()) {
            Entity e = it.next();
            if (!e.isValid() || e.isDead()) {
                it.remove();
                runs.forget(e);
            }
        }
    }

    private void waves(ActiveRun ar, Encounter enc, long now) {
        FightTuning t = ar.kit.fights().tuning();
        if (enc.nextWaveAtMs >= 0) {
            if (now >= enc.nextWaveAtMs) {
                startWave(ar, enc, enc.wave + 1, now);
            }
            return;
        }
        if (enc.wave + 1 >= enc.plan.waves().size()) {
            return;
        }
        int left = enc.alive.size();
        boolean low = left <= Math.ceil(t.waveRemain() * enc.waveSize);
        boolean slow = now - enc.waveStartMs >= t.waveTimeoutSeconds() * 1000L && left <= t.waveTimeoutAlive() * enc.waveSize;
        if (!low && !slow) {
            return;
        }
        enc.nextWaveAtMs = now + t.waveWarningSeconds() * 1000L;
        List<double[]> points = ar.geo.markers(enc.room, "spawn");
        Director.Spawn first = enc.plan.waves().get(enc.wave + 1).getFirst();
        double[] from = first.marker() >= 0 ? points.get(first.marker()) : ar.geo.centre(enc.room);
        Location at = new Location(runs.world(), from[0], from[1], from[2]);
        for (Player p : inRoom(ar, enc.room)) {
            Location l = p.getLocation();
            p.sendMessage("§eMore enemies come in " + t.waveWarningSeconds() + " seconds, "
                    + RunGeometry.direction(l.getX(), l.getZ(), l.getYaw(), from[0], from[2]) + ".");
            p.playSound(at, "minecraft:item.goat_horn.sound.0", 1f, 1f);
        }
    }

    private void affixes(ActiveRun ar, Encounter enc, long now) {
        FightTuning t = ar.kit.fights().tuning();
        for (Entity e : enc.alive) {
            Affix a = enc.elites.get(e.getUniqueId());
            if (a == Affix.SHIELDED) {
                if (shieldUp(enc, e)) {
                    fx.burst(e.getLocation());
                } else if (enc.shieldDown.add(e.getUniqueId())) {
                    runs.tellRoom(ar, enc.room, "§aThe " + baseName(e) + "'s shield falls.");
                }
            } else if (a == Affix.RALLYING && now >= enc.nextRally.getOrDefault(e.getUniqueId(), 0L)) {
                enc.nextRally.put(e.getUniqueId(), now + t.rallyEverySeconds() * 1000L);
                Integer wave = enc.waveOf.get(e.getUniqueId());
                for (Entity mate : enc.alive) {
                    if (Integer.valueOf(enc.waveOf.getOrDefault(mate.getUniqueId(), -1)).equals(wave)
                            && mate.getLocation().distance(e.getLocation()) <= t.rallyRadius()) {
                        enc.ralliedUntil.put(mate.getUniqueId(), now + t.rallySeconds() * 1000L);
                    }
                }
                fx.burst(e.getLocation());
                for (Player p : inRoom(ar, enc.room)) {
                    p.playSound(e.getLocation(), "minecraft:entity.evoker.prepare_summon", 1f, 1.2f);
                    runs.bars.send(p, ActionBars.Level.INFO, "The " + baseName(e) + " rallies its allies: they hit"
                            + " harder for " + t.rallySeconds() + " seconds.", now, 2000);
                }
            }
        }
    }

    /** A shielded elite's shield holds while any other enemy of its wave stands. */
    private static boolean shieldUp(Encounter enc, Entity elite) {
        Integer wave = enc.waveOf.get(elite.getUniqueId());
        for (Entity e : enc.alive) {
            if (!e.equals(elite) && wave.equals(enc.waveOf.get(e.getUniqueId()))) {
                return true;
            }
        }
        return false;
    }

    /** Enemies nobody has hit for a while (stuck or hiding) are moved to the marker nearest a player. */
    private void stall(ActiveRun ar, Encounter enc, long now) {
        FightTuning t = ar.kit.fights().tuning();
        if (enc.alive.isEmpty() || now - enc.lastHitMs < t.stallSeconds() * 1000L || !enc.telegraphs.isEmpty()) {
            return;
        }
        List<Player> here = inRoom(ar, enc.room);
        List<double[]> points = ar.geo.markers(enc.room, "spawn");
        enc.lastHitMs = now;
        if (here.isEmpty() || points.isEmpty()) {
            return;
        }
        double[] best = null;
        double bestD = Double.MAX_VALUE;
        for (double[] m : points) {
            for (Player p : here) {
                double d = Math.hypot(m[0] - p.getLocation().getX(), m[2] - p.getLocation().getZ());
                if (d < bestD) {
                    bestD = d;
                    best = m;
                }
            }
        }
        for (Entity e : enc.alive) {
            e.teleport(new Location(runs.world(), best[0], best[1], best[2]));
        }
        runs.event(ar, "room " + enc.room + ": " + enc.alive.size() + " idle enemies moved to the players");
    }

    /** With two or fewer enemies left in the last wave: they glow, and players are told where they are. */
    private void lastFew(ActiveRun ar, Encounter enc, long now) {
        if (enc.alive.isEmpty() || enc.alive.size() > 2 || enc.wave < enc.plan.waves().size() - 1) {
            return;
        }
        if (!enc.lastFew) {
            enc.lastFew = true;
            for (Entity e : enc.alive) {
                if (e instanceof LivingEntity l) {
                    l.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, PotionEffect.INFINITE_DURATION, 0,
                            false, false));
                }
            }
            runs.tellRoom(ar, enc.room, "§7" + (enc.alive.size() == 1 ? "One enemy left." : "Two enemies left."));
        }
        if (now < enc.nextPingMs) {
            return;
        }
        enc.nextPingMs = now + 3000;
        for (Player p : inRoom(ar, enc.room)) {
            Location l = p.getLocation();
            Entity near = null;
            for (Entity e : enc.alive) {
                if (near == null || e.getLocation().distance(l) < near.getLocation().distance(l)) {
                    near = e;
                }
            }
            runs.bars.send(p, ActionBars.Level.INFO, "Last enemy: " + RunGeometry.direction(l.getX(), l.getZ(),
                    l.getYaw(), near.getLocation().getX(), near.getLocation().getZ()), now, 2500);
        }
    }

    // ------------------------------------------------------------------ bosses

    private boolean stepBoss(ActiveRun ar, Encounter enc, long now) {
        BossFight boss = enc.boss;
        LivingEntity mob = enc.bossMob;
        String name = boss.def().name();
        if (!mob.isValid() || mob.isDead()) {
            runs.event(ar, "boss " + boss.def().id() + " defeated after " + (now - boss.startMs()) / 1000 + "s, phase "
                    + (boss.phase() + 1) + ", mechanic hits " + boss.mechanicHits() + (boss.enraged() ? ", enraged" : ""));
            removeAll(enc.adds);
            enc.telegraphs.clear();
            hideBar(ar, enc);
            enc.boss = null;
            enc.bossMob = null;
            runs.forget(mob);
            return true;
        }
        double full = maxHealth(mob);
        BossDef.Phase phase = boss.crossed(mob.getHealth() / full, now);
        if (phase != null) {
            mob.setHealth(Math.min(full, Math.max(1, full * phase.at() / 100.0)));
            int n = boss.phase() + 1;
            enc.bar.name(Component.text(name + " - phase " + n));
            runs.event(ar, "boss " + boss.def().id() + " phase " + n + " at " + (now - boss.startMs()) / 1000 + "s");
            for (Player p : inRoom(ar, enc.room)) {
                p.sendMessage("§6" + name + " staggers, then fights on harder. §7(phase " + n + " of "
                        + boss.def().phases().size() + ")");
                p.playSound(mob.getLocation(), "minecraft:entity.ravager.roar", 1f, 0.8f);
            }
            if (phase.adds() > 0) {
                spawnAdds(ar, enc, phase.adds());
            }
        }
        enc.bar.progress((float) Math.max(0, Math.min(1, mob.getHealth() / full)));
        for (Player p : runs.online(ar)) {
            p.showBossBar(enc.bar);
        }
        FightTuning t = ar.kit.fights().tuning();
        if (boss.enrage(now)) {
            runs.tell(ar, "§4" + name + " is enraged: it hits harder and calls for aid.");
            runs.event(ar, "boss " + boss.def().id() + " enraged");
        }
        if (boss.enrageAdds(now, enc.adds.size())) {
            spawnAdds(ar, enc, t.rallyCallAdds());
        }
        Mechanic m = boss.next(now, !enc.telegraphs.isEmpty(), enc.adds.size());
        if (m != null) {
            mechanic(ar, enc, m, now);
        }
        return false;
    }

    private void mechanic(ActiveRun ar, Encounter enc, Mechanic m, long now) {
        FightTuning t = ar.kit.fights().tuning();
        String name = enc.boss.def().name();
        LivingEntity mob = enc.bossMob;
        switch (m) {
            case SLAM -> {
                Player target = slamTarget(ar, enc);
                if (target == null) {
                    enc.boss.resolved(now);
                    return;
                }
                enc.lastSlam = target.getUniqueId();
                Location l = target.getLocation();
                telegraph(enc, new Telegraph(Telegraph.Kind.SLAM, l.getX(), l.getY(), l.getZ(), t.slamRadius(), now,
                        now + (long) (t.slamSeconds() * 1000)));
                runs.tellRoom(ar, enc.room, "§c" + name + " raises its weapon over " + target.getName() + "!");
                target.sendMessage("§cIt slams where you stand in " + (int) Math.ceil(t.slamSeconds())
                        + " seconds: move away!");
            }
            case SHOCKWAVE -> {
                Location l = mob.getLocation();
                AttributeInstance speed = mob.getAttribute(Attribute.MOVEMENT_SPEED);
                if (speed != null) {
                    speed.removeModifier(rootKey);
                    speed.addTransientModifier(new AttributeModifier(rootKey, -1,
                            AttributeModifier.Operation.MULTIPLY_SCALAR_1));
                }
                telegraph(enc, new Telegraph(Telegraph.Kind.SHOCKWAVE, l.getX(), l.getY(), l.getZ(),
                        t.shockwaveRadius(), now, now + (long) (t.shockwaveSeconds() * 1000)));
                runs.tellRoom(ar, enc.room, "§c" + name + " gathers its strength: get away from it!");
                for (Player p : inRoom(ar, enc.room)) {
                    p.playSound(l, "minecraft:block.beacon.power_select", 1f, 0.6f);
                }
            }
            default -> {
                spawnAdds(ar, enc, t.rallyCallAdds());
                runs.tellRoom(ar, enc.room, "§6" + name + " calls for aid.");
                enc.boss.resolved(now);
            }
        }
    }

    /**
     * Who a slam marks: the boss's own target when it can be, else another player; never someone
     * downed, out, reviving a friend, or (with anyone else to pick) the last player slammed.
     */
    private Player slamTarget(ActiveRun ar, Encounter enc) {
        List<Player> candidates = new ArrayList<>();
        double radius = runs.settings().reviveRadius();
        for (Player p : inRoom(ar, enc.room)) {
            Member m = ar.run.member(p.getUniqueId());
            if (!m.playing() || reviving(ar, p, radius)) {
                continue;
            }
            candidates.add(p);
        }
        if (candidates.size() > 1) {
            candidates.removeIf(p -> p.getUniqueId().equals(enc.lastSlam));
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (enc.bossMob instanceof Mob mob && mob.getTarget() instanceof Player p && candidates.contains(p)) {
            return p;
        }
        return candidates.get(enc.rng.nextInt(candidates.size()));
    }

    private boolean reviving(ActiveRun ar, Player p, double radius) {
        if (!p.isSneaking()) {
            return false;
        }
        for (Member m : ar.run.present()) {
            Location at = ar.downedAt.get(m.id());
            if (m.status() == DungeonRun.Status.DOWNED && at.distance(p.getLocation()) <= radius) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ telegraphs

    private void telegraph(Encounter enc, Telegraph t) {
        enc.telegraphs.add(new Active(t));
        if (telegraphTask == null) {
            telegraphTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::telegraphTick, 2L, 2L);
        }
    }

    /** Every two ticks while any warning shows: draw it, cue the players in it, and land it when due. */
    void telegraphTick() {
        long now = clock.getAsLong();
        boolean any = false;
        for (ActiveRun ar : runs.runs()) {
            for (Encounter enc : ar.encounters.values()) {
                Iterator<Active> it = enc.telegraphs.iterator();
                while (it.hasNext()) {
                    Active a = it.next();
                    cue(ar, enc, a, now);
                    if (a.t.due(now)) {
                        it.remove();
                        land(ar, enc, a.t, now);
                    }
                }
                any |= !enc.telegraphs.isEmpty();
            }
        }
        if (!any && telegraphTask != null) {
            telegraphTask.cancel();
            telegraphTask = null;
        }
    }

    private void cue(ActiveRun ar, Encounter enc, Active a, long now) {
        Telegraph t = a.t;
        fx.ring(runs.world(), t.x(), t.y(), t.z(), t.radius());
        for (Player p : inRoom(ar, enc.room)) {
            if (!ar.run.member(p.getUniqueId()).playing()) {
                continue;
            }
            Location l = p.getLocation();
            boolean in = t.inside(l.getX(), l.getZ());
            Boolean before = a.inside.put(p.getUniqueId(), in);
            if (in) {
                if (now - a.warned.getOrDefault(p.getUniqueId(), Long.MIN_VALUE / 2) >= 1000) {
                    a.warned.put(p.getUniqueId(), now);
                    double[] out = t.wayOut(l.getX(), l.getZ());
                    p.playSound(l, "minecraft:block.note_block.bass", 1f, 0.5f);
                    runs.bars.send(p, ActionBars.Level.WARNING, "Danger in " + t.secondsLeft(now) + "s: move "
                            + RunGeometry.direction(l.getX(), l.getZ(), l.getYaw(), out[0], out[1]), now, 1200);
                }
            } else if (Boolean.TRUE.equals(before)) {
                p.playSound(l, "minecraft:block.note_block.chime", 1f, 1.5f);
                runs.bars.send(p, ActionBars.Level.WARNING, "You're clear.", now, 1500);
            }
        }
    }

    private void land(ActiveRun ar, Encounter enc, Telegraph t, long now) {
        FightTuning tuning = ar.kit.fights().tuning();
        double share = switch (t.kind()) {
            case SLAM -> tuning.slamDamage();
            case SHOCKWAVE -> tuning.shockwaveDamage();
            default -> tuning.burstDamage();
        };
        share = tuning.damage(share, ar.rules.tier());
        Entity source = t.kind() == Telegraph.Kind.BURST ? null : enc.bossMob;
        for (Player p : inRoom(ar, enc.room)) {
            Location l = p.getLocation();
            if (!ar.run.member(p.getUniqueId()).playing() || !t.inside(l.getX(), l.getZ())) {
                continue;
            }
            fx.hurt(p, share * maxHealth(p), source);
            if (t.kind() != Telegraph.Kind.BURST) {
                enc.boss.hit();
            }
        }
        for (Player p : inRoom(ar, enc.room)) {
            p.playSound(new Location(runs.world(), t.x(), t.y(), t.z()), "minecraft:entity.generic.explode", 0.8f, 1f);
        }
        if (t.kind() == Telegraph.Kind.SHOCKWAVE) {
            AttributeInstance speed = enc.bossMob.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) {
                speed.removeModifier(rootKey);
            }
        }
        if (t.kind() != Telegraph.Kind.BURST) {
            enc.boss.resolved(now);
        }
    }

    // ------------------------------------------------------------------ damage hooks

    /** How hard a run mob hits now: rallied, enraged, or not at all while charging a shockwave. */
    double outgoing(ActiveRun ar, Entity source) {
        long now = clock.getAsLong();
        for (Encounter enc : ar.encounters.values()) {
            if (source.equals(enc.bossMob)) {
                for (Active a : enc.telegraphs) {
                    if (a.t.kind() == Telegraph.Kind.SHOCKWAVE) {
                        return 0;
                    }
                }
                return enc.boss.damageFactor();
            }
            Long until = enc.ralliedUntil.get(source.getUniqueId());
            if (until != null && until > now) {
                return ar.kit.fights().tuning().rallyFactor();
            }
        }
        return 1;
    }

    /** How much of a hit a run mob takes: none from a boss between phases, less from a shielded elite. */
    double incoming(ActiveRun ar, Entity mob) {
        long now = clock.getAsLong();
        for (Encounter enc : ar.encounters.values()) {
            if (mob.equals(enc.bossMob) && enc.boss.immune(now)) {
                return 0;
            }
            if (enc.elites.get(mob.getUniqueId()) == Affix.SHIELDED && shieldUp(enc, mob)) {
                return 1 - ar.kit.fights().tuning().shieldReduction();
            }
        }
        return 1;
    }

    /** Most damage a hit may deal to a boss: no lower than its next phase's start. Infinite for other mobs. */
    double cap(ActiveRun ar, Entity mob) {
        for (Encounter enc : ar.encounters.values()) {
            if (mob.equals(enc.bossMob)) {
                return Math.max(0, enc.bossMob.getHealth() - enc.boss.floorShare() * maxHealth(enc.bossMob));
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    /** A player hit a run mob: the fight is not stalled. */
    void hit(ActiveRun ar, Entity mob) {
        for (Encounter enc : ar.encounters.values()) {
            if (enc.alive.contains(mob) || mob.equals(enc.bossMob) || enc.adds.contains(mob)) {
                enc.lastHitMs = clock.getAsLong();
            }
        }
    }

    /** A run mob died: a volatile elite's remains start to burst. */
    void died(ActiveRun ar, Entity mob) {
        for (Encounter enc : ar.encounters.values()) {
            if (enc.elites.get(mob.getUniqueId()) == Affix.VOLATILE) {
                FightTuning t = ar.kit.fights().tuning();
                long now = clock.getAsLong();
                Location l = mob.getLocation();
                telegraph(enc, new Telegraph(Telegraph.Kind.BURST, l.getX(), l.getY(), l.getZ(), t.burstRadius(), now,
                        now + (long) (t.burstSeconds() * 1000)));
                runs.tellRoom(ar, enc.room, "§cThe " + baseName(mob) + "'s remains crackle: step away!");
            }
        }
    }

    /** Something spawned inside a run (a mob's summons): it belongs to the room and goes with it. */
    void adopt(ActiveRun ar, Entity e) {
        int room = ar.geo.roomAt(e.getLocation().getX(), e.getLocation().getZ());
        Encounter enc = ar.encounters.get(room);
        if (enc != null) {
            enc.extras.add(e);
        } else {
            ar.extras.add(e);
        }
    }

    // ------------------------------------------------------------------ ending

    /** A room's fight is over: what is left of it goes, and the run log gets a line. */
    void cleared(ActiveRun ar, int room) {
        Encounter enc = ar.encounters.remove(room);
        if (enc == null) {
            return;
        }
        long now = clock.getAsLong();
        runs.event(ar, "room " + room + " cleared in " + (now - enc.startedMs) / 1000 + "s, downs "
                + (ar.run.downs() - enc.downsAtStart) + ", elites " + enc.elites.values());
        removeAll(enc.alive);
        removeAll(enc.adds);
        removeAll(enc.extras);
        hideBar(ar, enc);
    }

    /** The run ends: every fight's mobs and strays go. */
    void ended(ActiveRun ar) {
        for (Encounter enc : ar.encounters.values()) {
            removeAll(enc.alive);
            removeAll(enc.adds);
            removeAll(enc.extras);
            if (enc.bossMob != null) {
                runs.forget(enc.bossMob);
                mobs.remove(enc.bossMob);
            }
            hideBar(ar, enc);
        }
        ar.encounters.clear();
        removeAll(ar.extras);
    }

    /** A player left the run: their boss bars go. */
    void left(ActiveRun ar, Player p) {
        for (Encounter enc : ar.encounters.values()) {
            if (enc.bar != null) {
                p.hideBossBar(enc.bar);
            }
        }
    }

    private void hideBar(ActiveRun ar, Encounter enc) {
        if (enc.bar == null) {
            return;
        }
        for (Member m : ar.run.members()) {
            Player p = org.bukkit.Bukkit.getPlayer(m.id());
            if (p != null) {
                p.hideBossBar(enc.bar);
            }
        }
    }

    private void removeAll(Set<Entity> set) {
        for (Entity e : set) {
            runs.forget(e);
            mobs.remove(e);
        }
        set.clear();
    }

    /** Max health, or current health for the odd entity without the attribute. */
    static double maxHealth(LivingEntity e) {
        AttributeInstance max = e.getAttribute(Attribute.MAX_HEALTH);
        return max == null ? Math.max(1, e.getHealth()) : max.getValue();
    }

    private List<Player> inRoom(ActiveRun ar, int room) {
        List<Player> out = new ArrayList<>();
        for (Player p : runs.online(ar)) {
            if (ar.run.member(p.getUniqueId()).room() == room) {
                out.add(p);
            }
        }
        return out;
    }
}
