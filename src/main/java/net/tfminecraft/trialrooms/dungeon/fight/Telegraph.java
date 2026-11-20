package net.tfminecraft.trialrooms.dungeon.fight;

/**
 * A warned area that is hit when its time is up: a circle on the ground, fixed where it was
 * marked.
 *
 * @param kind    what made it
 * @param x       centre
 * @param y       ground height
 * @param z       centre
 * @param radius  reach
 * @param startMs when the warning began
 * @param endsMs  when it lands
 */
public record Telegraph(Kind kind, double x, double y, double z, double radius, long startMs, long endsMs) {

    public enum Kind {
        SLAM, SHOCKWAVE, BURST
    }

    /** How far above or below the marked ground a player can stand and still be hit. */
    public static final double BAND = 3;

    public boolean inside(double px, double py, double pz) {
        return Math.abs(py - y) <= BAND && Math.hypot(px - x, pz - z) <= radius;
    }

    public boolean due(long now) {
        return now >= endsMs;
    }

    /** Whole seconds left, rounded up. */
    public long secondsLeft(long now) {
        return Math.max(0, (endsMs - now + 999) / 1000);
    }

    /** A spot just past the edge, straight away from the centre: where to step to be clear. */
    public double[] wayOut(double px, double pz) {
        double dx = px - x;
        double dz = pz - z;
        double d = Math.hypot(dx, dz);
        if (d < 1e-6) {
            dx = 0;
            dz = 1;
            d = 1;
        }
        double scale = (radius + 1.5) / d;
        return new double[] { x + dx * scale, z + dz * scale };
    }
}
