package net.tfminecraft.trialrooms.dungeon.gear;

import java.util.Locale;

/**
 * A set's weapon trait: a small effect that works whenever the weapon is held, stronger while its
 * wielder also wears pieces of the same armour set (resonance).
 *
 * @param value how much (a share: 0.1 is 10%), or seconds for the kinds that slow
 */
public record Trait(Kind kind, double value) {

    /** What traits do. */
    public enum Kind {
        /** More damage to an enemy at full health. */
        OPENING("%d%% more damage to unhurt enemies"),
        /** More damage to a badly hurt enemy. */
        EXECUTE("%d%% more damage to enemies below 40%% health"),
        /** More damage while not hit for a few seconds. */
        COMPOSED("%d%% more damage after 5 seconds without being hit"),
        /** More damage for hitting the same enemy again and again. */
        DUEL("up to %d%% more damage for repeated hits on one enemy"),
        /** Every few hits burn for a share of the hit. */
        BURN("every 4th hit burns for %d%% of its damage"),
        /** Every few hits also hurt the enemies around. */
        SHARDS("every 5th hit deals %d%% of its damage around it"),
        /** Hits slow enemies. */
        CHILL("every 3rd hit slows the enemy for %s seconds"),
        /** Being hit slows the attacker. */
        RIME("enemies that hit you are slowed for %s seconds"),
        /** Less damage for a few seconds after being hit. */
        WARD("%d%% less damage for 4 seconds after a hit"),
        /** Less damage near an ally, or when badly hurt. */
        BRACE("%d%% less damage near an ally, or below half health"),
        /** A kill heals. */
        MEND("a kill heals %d%% of your health"),
        /** Hits heal the most hurt ally nearby. */
        RADIANCE("hits heal the most hurt ally nearby by %d%%, every 3 seconds");

        private final String text;

        Kind(String text) {
            this.text = text;
        }

        boolean seconds() {
            return this == CHILL || this == RIME;
        }
    }

    /** How much stronger a trait is with its armour set. */
    public static final double RESONANCE = 1.5;
    /** The share of its trait an off-hand piece (shield, battle standard) gives. */
    public static final double OFF_HAND = 0.4;

    public Trait {
        if (!(value > 0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("a trait's value must be above 0");
        }
    }

    /** What players read, at a strength. */
    public String text(double strength) {
        double v = value * strength;
        return kind.seconds() ? String.format(Locale.ROOT, kind.text, trim(v))
                : String.format(Locale.ROOT, kind.text, Math.round(v * 100));
    }

    static String trim(double v) {
        double r = Math.round(v * 10) / 10.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }
}
