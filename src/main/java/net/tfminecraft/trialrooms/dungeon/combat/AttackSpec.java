package net.tfminecraft.trialrooms.dungeon.combat;

import java.util.Locale;

/**
 * One enemy attack, as its MythicMobs skill describes it to the dungeon:
 * {@code dungeonattack{windup=13;recover=16;guard=0.5;poise=0.08;magic=true;ground=cone;radius=4;angle=90;
 * strike=Seithr_Cleave_HIT;start=Seithr_Cleave_START;interrupted=Seithr_Mage_BLINK}}.
 *
 * <p>The windup runs from the attack's start to the animation's hit frame; the strike skill does the
 * damage there; then the enemy stands open for the recovery. Slows stretch the windup.
 *
 * @param id          what logs call it
 * @param windup      ticks from the start to the strike (the animation's hit frame)
 * @param recover     ticks the enemy stands open after the strike
 * @param guard       share of damage the enemy takes while winding up (0 immune, 1 all of it)
 * @param poise       damage during the windup, as a share of its max health, that staggers it (0: never)
 * @param magic       a spell: silences and freezes break it off
 * @param ground      the area marked on the ground (bosses and leaders only, unless forced)
 * @param radius      the area's reach
 * @param angle       a cone's width in degrees
 * @param leave       a wide hit to get out of (red) rather than a swing to dodge (orange)
 * @param root        the enemy stands still from the start to the end of its recovery
 * @param forceGround mark the area for any enemy, not only bosses and leaders
 * @param strike      the MythicMobs skill that lands the hit
 * @param start       the skill that starts it (animation and sound), or null
 * @param chain       the skill cast the moment it ends unbroken (the next part of a combo), or null
 * @param interrupted the skill it falls back on when broken off, by a silence, a freeze or a stagger (its
 *                    alternative), or null
 */
public record AttackSpec(String id, int windup, int recover, double guard, double poise, boolean magic, Shape ground,
        double radius, double angle, boolean leave, boolean root, boolean forceGround, String strike, String start,
        String interrupted, String chain) {

    /** What is marked on the ground while it winds up. */
    public enum Shape {
        /** Nothing. */
        NONE,
        /** In front of the enemy. */
        CONE,
        /** Around the enemy. */
        RING,
        /** Around its target, where the target stood when it began. */
        TARGET,
        /** A straight line ahead. */
        LINE;

        static Shape parse(String text) {
            try {
                return valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown ground: " + text, e);
            }
        }
    }

    public AttackSpec {
        if (windup < 1 || recover < 0 || !(guard >= 0 && guard <= 1) || !(poise >= 0) || !(radius >= 0)
                || !(angle > 0 && angle <= 360)) {
            throw new IllegalArgumentException("attack " + id + ": windup at least 1, recover not negative, guard 0-1,"
                    + " poise and radius not negative, angle 1-360");
        }
        if (strike == null || strike.isBlank()) {
            throw new IllegalArgumentException("attack " + id + ": needs a strike skill");
        }
        start = blank(start);
        interrupted = blank(interrupted);
        chain = blank(chain);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Whether it can be broken off by damage. */
    public boolean staggers() {
        return poise > 0;
    }

    /** Reads it from a skill line's options (lower-case keys). */
    public static AttackSpec of(java.util.function.Function<String, String> option) {
        String ground = option.apply("ground");
        Shape shape = ground == null ? Shape.NONE : Shape.parse(ground);
        String leave = option.apply("leave");
        return new AttackSpec(or(option.apply("id"), or(option.apply("strike"), "attack")), integer(option, "windup", 13),
                integer(option, "recover", 16), number(option, "guard", 0.5), number(option, "poise", 0),
                bool(option, "magic", false), shape, number(option, "radius", 3), number(option, "angle", 90),
                leave == null ? shape == Shape.RING || shape == Shape.TARGET : Boolean.parseBoolean(leave),
                bool(option, "root", true), bool(option, "forceground", false), option.apply("strike"),
                option.apply("start"), option.apply("interrupted"), option.apply("chain"));
    }

    private static String or(String a, String b) {
        return a == null || a.isBlank() ? b : a;
    }

    private static int integer(java.util.function.Function<String, String> o, String key, int fallback) {
        String v = o.apply(key);
        try {
            return v == null ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad " + key + ": " + v, e);
        }
    }

    private static double number(java.util.function.Function<String, String> o, String key, double fallback) {
        String v = o.apply(key);
        try {
            return v == null ? fallback : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad " + key + ": " + v, e);
        }
    }

    private static boolean bool(java.util.function.Function<String, String> o, String key, boolean fallback) {
        String v = o.apply(key);
        return v == null ? fallback : Boolean.parseBoolean(v.trim());
    }
}
