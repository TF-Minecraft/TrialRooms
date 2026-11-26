package net.tfminecraft.trialrooms.dungeon.combat;

import java.util.List;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import io.lumine.mythic.api.skills.INoTargetSkill;
import io.lumine.mythic.api.skills.ITargetedEntitySkill;
import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.api.skills.SkillResult;
import io.lumine.mythic.api.skills.ThreadSafetyLevel;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.api.skills.conditions.IEntityCondition;
import io.lumine.mythic.bukkit.events.MythicConditionLoadEvent;
import io.lumine.mythic.bukkit.events.MythicMechanicLoadEvent;
import io.lumine.mythic.core.mobs.ActiveMob;

/**
 * Enemy attacks on MythicMobs: skills cast, auras read, areas drawn in dust, and the
 * {@code dungeonattack} mechanic that skills use to start an attack, and the {@code dungeonbroken} condition
 * that keeps the rest of a broken-off attack's start skill from playing.
 */
public final class MythicControl implements MobControl, Listener {

    /** The mechanic's name in skill files. */
    public static final String MECHANIC = "dungeonattack";
    /** The condition's name in skill files: true when the caster's latest attack was broken off. */
    public static final String CONDITION = "dungeonbroken";
    static final Color RED = Color.fromRGB(0xE0, 0x30, 0x30);
    static final Color ORANGE = Color.fromRGB(0xFF, 0xA0, 0x20);

    private final java.util.function.Supplier<Attacks> attacks;
    private final java.util.logging.Logger log;

    public MythicControl(java.util.function.Supplier<Attacks> attacks, java.util.logging.Logger log) {
        this.attacks = attacks;
        this.log = log;
    }

    @Override
    public void cast(LivingEntity mob, String skill, Entity target) {
        List<Entity> targets = target == null ? List.of() : List.of(target);
        MythicBukkit.inst().getAPIHelper().castSkill(mob, skill, target, mob.getLocation(), targets, List.of(), 1f);
    }

    @Override
    public boolean hasAura(LivingEntity mob, String aura) {
        return MythicBukkit.inst().getSkillManager().getAuraManager().getHasAura(BukkitAdapter.adapt(mob), aura);
    }

    @Override
    public int level(LivingEntity mob) {
        ActiveMob am = MythicBukkit.inst().getAPIHelper().getMythicMobInstance(mob);
        return am == null ? 1 : Math.max(1, (int) am.getLevel());
    }

    @Override
    public void mark(Area a, boolean leave) {
        dust(a, new Particle.DustOptions(leave ? RED : ORANGE, 1.4f));
    }

    /** The outline of an area, a point every half block. */
    static void dust(Area a, Particle.DustOptions dust) {
        World w = a.world();
        double y = a.y() + 0.15;
        for (double[] p : outline(a)) {
            w.spawnParticle(Particle.DUST, p[0], y, p[1], 1, 0, 0, 0, 0, dust);
        }
    }

    /** Points (x, z) along an area's edge. */
    static List<double[]> outline(Area a) {
        List<double[]> out = new java.util.ArrayList<>();
        double facing = Math.toRadians(a.yaw());
        // Minecraft's yaw: 0 faces +z, 90 faces -x
        double fx = -Math.sin(facing);
        double fz = Math.cos(facing);
        switch (a.shape()) {
            case RING, TARGET -> arc(out, a, 0, 2 * Math.PI);
            case CONE -> {
                double half = Math.toRadians(a.angle() / 2);
                double mid = Math.atan2(fz, fx);
                arc(out, a, mid - half, mid + half);
                if (a.angle() < 360) {
                    line(out, a.x(), a.z(), mid - half, a.radius());
                    line(out, a.x(), a.z(), mid + half, a.radius());
                }
            }
            case LINE -> {
                double sx = -fz;
                double sz = fx;
                for (double d = 0; d <= a.radius(); d += 0.5) {
                    out.add(new double[] { a.x() + fx * d + sx, a.z() + fz * d + sz });
                    out.add(new double[] { a.x() + fx * d - sx, a.z() + fz * d - sz });
                }
            }
            default -> {
            }
        }
        return out;
    }

    private static void arc(List<double[]> out, Area a, double from, double to) {
        int n = Math.max(8, (int) Math.ceil((to - from) * a.radius() / 0.5));
        for (int i = 0; i <= n; i++) {
            double t = from + (to - from) * i / n;
            out.add(new double[] { a.x() + Math.cos(t) * a.radius(), a.z() + Math.sin(t) * a.radius() });
        }
    }

    private static void line(List<double[]> out, double x, double z, double angle, double length) {
        for (double d = 0.5; d < length; d += 0.5) {
            out.add(new double[] { x + Math.cos(angle) * d, z + Math.sin(angle) * d });
        }
    }

    /** Registers {@code dungeonattack} as MythicMobs loads skills. */
    @EventHandler
    public void onMechanicLoad(MythicMechanicLoadEvent event) {
        if (!MECHANIC.equalsIgnoreCase(event.getMechanicName())) {
            return;
        }
        try {
            AttackSpec spec = AttackSpec.of(key -> event.getConfig().getString(new String[] { key }, null));
            event.register(new Mechanic(spec, attacks));
        } catch (IllegalArgumentException e) {
            log.warning("[dungeons] bad " + MECHANIC + " line (" + event.getConfig().getLine() + "): " + e.getMessage());
        }
    }

    /** Registers {@code dungeonbroken} as MythicMobs loads conditions. */
    @EventHandler
    public void onConditionLoad(MythicConditionLoadEvent event) {
        if (CONDITION.equalsIgnoreCase(event.getConditionName())) {
            event.register(new Broken(attacks));
        }
    }

    /** The condition: whether the entity's latest attack was broken off (false with no engine). */
    record Broken(java.util.function.Supplier<Attacks> attacks) implements IEntityCondition {

        @Override
        public boolean check(io.lumine.mythic.api.adapters.AbstractEntity entity) {
            Attacks a = attacks.get();
            return a != null && entity != null && a.brokenOff(entity.getBukkitEntity());
        }
    }

    /** The mechanic: starts its attack from the caster at the skill's target (or the caster's own target). */
    record Mechanic(AttackSpec spec, java.util.function.Supplier<Attacks> attacks) implements ITargetedEntitySkill, INoTargetSkill {

        @Override
        public ThreadSafetyLevel getThreadSafetyLevel() {
            return ThreadSafetyLevel.SYNC_ONLY;
        }

        @Override
        public SkillResult castAtEntity(SkillMetadata data, io.lumine.mythic.api.adapters.AbstractEntity target) {
            return start(data, target == null ? null : target.getBukkitEntity());
        }

        @Override
        public SkillResult cast(SkillMetadata data) {
            Entity caster = data.getCaster().getEntity().getBukkitEntity();
            return start(data, caster instanceof org.bukkit.entity.Mob m ? m.getTarget() : null);
        }

        private SkillResult start(SkillMetadata data, Entity target) {
            Attacks a = attacks.get();
            Entity caster = data.getCaster().getEntity().getBukkitEntity();
            if (a == null || !(caster instanceof LivingEntity mob)) {
                return SkillResult.INVALID_TARGET;
            }
            return a.start(mob, target, spec) ? SkillResult.SUCCESS : SkillResult.CONDITION_FAILED;
        }
    }
}
