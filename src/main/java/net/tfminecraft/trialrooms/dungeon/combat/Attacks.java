package net.tfminecraft.trialrooms.dungeon.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import net.tfminecraft.trialrooms.dungeon.combat.AttackState.Phase;
import net.tfminecraft.trialrooms.dungeon.combat.AttackState.Step;
import net.tfminecraft.trialrooms.dungeon.combat.CombatSettings.Cue;

/**
 * Enemy attacks at work, in any dungeon and for any MythicMobs enemy whose skills use
 * {@code dungeonattack}: the windup with its colour cue (and, for bosses and leaders, its area marked
 * on the ground), the strike on the animation's hit frame, then the enemy standing open. Damage in the
 * windup counts against its poise; a silence or freeze breaks a spell off, and the enemy turns to its
 * alternative. Run every tick.
 */
public final class Attacks implements Listener {

    /** How often areas are drawn again, in ticks. */
    static final int MARK_EVERY = 4;

    private static final class Active {
        final LivingEntity mob;
        final Entity target;
        final AttackState state;
        final MobControl.Area area;
        boolean rooted;
        int age;

        Active(LivingEntity mob, Entity target, AttackState state, MobControl.Area area) {
            this.mob = mob;
            this.target = target;
            this.state = state;
            this.area = area;
        }
    }

    private final MobControl control;
    private final Supplier<CombatSettings> settings;
    private final Predicate<Entity> marked;
    private final Map<UUID, Active> active = new HashMap<>();

    /** @param marked enemies whose attack areas are marked on the ground (bosses and leaders) */
    public Attacks(MobControl control, Supplier<CombatSettings> settings, Predicate<Entity> marked) {
        this.control = control;
        this.settings = settings;
        this.marked = marked;
    }

    /** Whether an enemy is in the middle of an attack. */
    public boolean busy(Entity mob) {
        return active.containsKey(mob.getUniqueId());
    }

    /** Where an enemy's attack is, or null. */
    public Phase phase(Entity mob) {
        Active a = active.get(mob.getUniqueId());
        return a == null ? null : a.state.phase();
    }

    /** Starts an attack; false when the enemy is already attacking (or dead). */
    public boolean start(LivingEntity mob, Entity target, AttackSpec spec) {
        if (mob.isDead() || busy(mob)) {
            return false;
        }
        MobControl.Area area = spec.ground() == AttackSpec.Shape.NONE || !(spec.forceGround() || marked.test(mob)) ? null
                : area(mob, target, spec);
        Active a = new Active(mob, target, new AttackState(spec, settings.get().staggerTicks()), area);
        active.put(mob.getUniqueId(), a);
        if (spec.root() && mob instanceof Mob m && m.isAware()) {
            m.setAware(false);
            a.rooted = true;
        }
        cue(mob, spec.leave() ? Cue.LEAVE : Cue.DODGE);
        if (spec.magic()) {
            cue(mob, Cue.INTERRUPT);
        }
        if (spec.start() != null) {
            control.cast(mob, spec.start(), target);
        }
        if (area != null) {
            control.mark(area, spec.leave());
        }
        return true;
    }

    private static MobControl.Area area(LivingEntity mob, Entity target, AttackSpec spec) {
        Location at = spec.ground() == AttackSpec.Shape.TARGET && target != null ? target.getLocation() : mob.getLocation();
        return new MobControl.Area(spec.ground(), at.getWorld(), at.getX(), at.getY(), at.getZ(), mob.getLocation().getYaw(),
                spec.radius(), spec.angle());
    }

    private void cue(LivingEntity mob, Cue cue) {
        String skill = settings.get().cues().get(cue);
        if (skill != null) {
            control.cast(mob, skill, null);
        }
    }

    /** One tick for every attack under way. */
    public void tick() {
        for (Active a : new ArrayList<>(active.values())) {
            if (a.mob.isDead() || !a.mob.isValid()) {
                end(a);
                continue;
            }
            AttackSpec spec = a.state.spec();
            if (a.state.phase() == Phase.WINDUP && interrupted(a.mob) && a.state.interrupt()) {
                brokeOff(a);
                if (spec.interrupted() != null) {
                    control.cast(a.mob, spec.interrupted(), a.target);
                }
                continue;
            }
            Step step = a.state.tick(slow(a.mob));
            if (step == Step.STRIKE) {
                control.cast(a.mob, spec.strike(), a.target);
                cue(a.mob, Cue.OPEN);
            } else if (step == Step.OVER) {
                end(a);
                continue;
            }
            if (step == Step.STRIKE && a.state.phase() == Phase.OVER) {
                end(a);
                continue;
            }
            if (a.area != null && a.state.phase() == Phase.WINDUP && ++a.age % MARK_EVERY == 0) {
                control.mark(a.area, spec.leave());
            }
        }
    }

    private boolean interrupted(LivingEntity mob) {
        return settings.get().interruptAuras().stream().anyMatch(aura -> control.hasAura(mob, aura));
    }

    /** How many times longer a windup takes on this enemy now. */
    double slow(LivingEntity mob) {
        CombatSettings s = settings.get();
        double factor = 1;
        PotionEffect slowness = mob.getPotionEffect(PotionEffectType.SLOWNESS);
        if (slowness != null) {
            factor *= 1 + s.slownessStep() * (slowness.getAmplifier() + 1);
        }
        for (Map.Entry<String, Double> e : s.slowAuras().entrySet()) {
            if (control.hasAura(mob, e.getKey())) {
                factor *= e.getValue();
            }
        }
        return factor;
    }

    private void brokeOff(Active a) {
        cue(a.mob, Cue.CLEAR);
        cue(a.mob, Cue.OPEN);
    }

    private void end(Active a) {
        active.remove(a.mob.getUniqueId());
        if (a.rooted && !a.mob.isDead()) {
            ((Mob) a.mob).setAware(true); // (only a Mob is rooted)
        }
        if (!a.mob.isDead()) {
            cue(a.mob, Cue.CLEAR);
        }
    }

    /** Ends every attack (the plugin stops): enemies move again. */
    public void stop() {
        new ArrayList<>(active.values()).forEach(this::end);
    }

    /** What share of a hit an enemy takes now (1 outside its attacks). */
    public double taken(Entity mob) {
        Active a = active.get(mob.getUniqueId());
        return a == null ? 1 : a.state.taken(settings.get().openBonus(control.level(a.mob)));
    }

    /** A hit on an enemy that can't be hurt in this windup lands nothing (before anything sets off on-hit effects). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStruck(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.KILL && taken(event.getEntity()) <= 0) {
            event.setCancelled(true);
        }
    }

    /** Its guard or its opening, after MythicLib has set the hit's damage (HIGHEST, its listener is older). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        double f = taken(event.getEntity());
        if (f != 1 && event.getCause() != EntityDamageEvent.DamageCause.KILL) {
            event.setDamage(event.getDamage() * f);
        }
    }

    /** What landed in a windup counts against the enemy's poise. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurtDone(EntityDamageEvent event) {
        Active a = active.get(event.getEntity().getUniqueId());
        if (a != null && a.state.hurt(event.getFinalDamage(), maxHealth(a.mob))) {
            brokeOff(a);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        Active a = active.get(event.getEntity().getUniqueId());
        if (a != null) {
            end(a);
        }
    }

    private static double maxHealth(LivingEntity mob) {
        var max = mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        return max == null ? 0 : max.getValue();
    }

    /** Attacks under way, for staff tools. */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        active.values().forEach(a -> out.add(a.mob.getName() + ": " + a.state.spec().id() + " " + a.state.phase()));
        return out;
    }
}
