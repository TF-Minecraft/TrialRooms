package net.tfminecraft.trialrooms.environment.spawner;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

class SpawnerParticles {
    private final ActiveSpawner spawner;

    // idle/active ring
    private double particleAngle = 0.0;
    private final double particleSpeed  = Math.PI / 16.0;
    private final double particleRadius = 0.8;

    // orb
    private double orbAngle = 0.0;
    private final double orbRadius = 0.35;
    private final double orbHeight = 3.0;
    private final double orbSpeed  = Math.PI / 24.0;

    SpawnerParticles(ActiveSpawner spawner) {
        this.spawner = spawner;
    }

    void animateIdleActiveRing() {
        Location loc = spawner.getLoc();
        if (loc == null) return;
        World world = loc.getWorld();
        if (world == null) return;

        double cx = loc.getX() + 0.5;
        double cz = loc.getZ() + 0.5;

        double xOffA = Math.cos(particleAngle) * particleRadius;
        double zOffA = Math.sin(particleAngle) * particleRadius;
        double xOffB = -xOffA;
        double zOffB = -zOffA;

        Particle type = (spawner.getState() == ActiveSpawner.State.ACTIVE) ? Particle.FLAME : Particle.FIREWORKS_SPARK;

        world.spawnParticle(type, cx + xOffA, loc.getY() + 0.2, cz + zOffA, 1, 0, 0, 0, 0);
        world.spawnParticle(type, cx + xOffB, loc.getY() + 0.8, cz + zOffB, 1, 0, 0, 0, 0);

        particleAngle += particleSpeed;
        if (particleAngle >= Math.PI * 2) particleAngle -= Math.PI * 2;
    }

    void animateOrb() {
        Location c = orbCenter();
        World w = c.getWorld();
        if (w == null) return;

        w.spawnParticle(Particle.END_ROD, c.getX(), c.getY(), c.getZ(), 4, 0.06, 0.06, 0.06, 0.0);

        int points = 8;
        for (int i = 0; i < points; i++) {
            double a = orbAngle + (i * (Math.PI * 2 / points));
            double x = c.getX() + Math.cos(a) * orbRadius;
            double z = c.getZ() + Math.sin(a) * orbRadius;
            w.spawnParticle(Particle.CRIT_MAGIC, x, c.getY(), z, 1, 0, 0, 0, 0);
        }

        orbAngle += orbSpeed;
        if (orbAngle >= Math.PI * 2) orbAngle -= Math.PI * 2;
    }

    void traceFromOrbTo(Location target) {
        Location from = orbCenter();
        if (from.getWorld() == null || target.getWorld() == null) return;
        if (!from.getWorld().equals(target.getWorld())) return;

        double dist = from.distance(target);
        int steps = Math.max(10, (int) Math.ceil(dist * 12.0));
        double dx = (target.getX() - from.getX()) / steps;
        double dy = (target.getY() - from.getY()) / steps;
        double dz = (target.getZ() - from.getZ()) / steps;

        double x = from.getX(), y = from.getY(), z = from.getZ();
        for (int i = 0; i <= steps; i++) {
            from.getWorld().spawnParticle(Particle.SMOKE_NORMAL, x, y, z, 1, 0, 0, 0, 0);
            x += dx; y += dy; z += dz;
        }
    }

    private Location orbCenter() {
        Location base = spawner.getLoc();
        return new Location(base.getWorld(), base.getX() + 0.5, base.getY() + orbHeight, base.getZ() + 0.5);
    }
}
