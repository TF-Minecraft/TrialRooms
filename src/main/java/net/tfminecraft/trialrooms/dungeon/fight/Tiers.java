package net.tfminecraft.trialrooms.dungeon.fight;

/**
 * The difficulty tiers something appears on (1 = Normal ... 4 = Mythic), from {@code min} to {@code max}.
 */
public record Tiers(int min, int max) {

    /** Every tier. */
    public static final Tiers ALL = new Tiers(1, Integer.MAX_VALUE);

    public Tiers {
        if (min < 1 || max < min) {
            throw new IllegalArgumentException("tiers: from 1 up, the first no higher than the last");
        }
    }

    public boolean contains(int tier) {
        return tier >= min && tier <= max;
    }

    /** "3", "2-4", "2+" or nothing (every tier). */
    public static Tiers parse(Object value) {
        if (value == null) {
            return ALL;
        }
        String s = String.valueOf(value).trim();
        try {
            if (s.endsWith("+")) {
                return new Tiers(Integer.parseInt(s.substring(0, s.length() - 1).trim()), Integer.MAX_VALUE);
            }
            int dash = s.indexOf('-');
            if (dash > 0) {
                return new Tiers(Integer.parseInt(s.substring(0, dash).trim()), Integer.parseInt(s.substring(dash + 1).trim()));
            }
            int one = Integer.parseInt(s);
            return new Tiers(one, one);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad tiers: " + s, e);
        }
    }
}
