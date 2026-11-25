package net.tfminecraft.trialrooms.dungeon.combat;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/** What enemy attacks need from MythicMobs, behind an interface they can be tested without. */
public interface MobControl {

    /** Casts a MythicMobs skill from an enemy at its target (may be null). */
    void cast(LivingEntity mob, String skill, Entity target);

    /** Whether an enemy carries a MythicMobs aura now. */
    boolean hasAura(LivingEntity mob, String aura);

    /** An enemy's MythicMobs level (1 for anything else). */
    int level(LivingEntity mob);

    /** Particles marking an area on the ground, red for a hit to leave, orange for one to dodge. */
    void mark(Area area, boolean leave);

    /**
     * An area on the ground.
     *
     * @param yaw    which way a cone or line points (degrees, Minecraft's)
     * @param radius reach
     * @param angle  a cone's width in degrees
     */
    record Area(AttackSpec.Shape shape, org.bukkit.World world, double x, double y, double z, float yaw, double radius,
            double angle) {
    }
}
