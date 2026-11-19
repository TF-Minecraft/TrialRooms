package net.tfminecraft.trialrooms.dungeon.fight;

import java.util.Locale;

/** What an enemy does in a fight, for building packs and choosing where it spawns. */
public enum Role {
    /** Plain melee. */
    GRUNT,
    /** Harder-hitting melee. */
    BRUISER,
    /** A sturdy front-liner. */
    TANK,
    /** Shoots from range. */
    RANGED,
    /** Casts from range. */
    CASTER,
    /** Many weak enemies. */
    SWARM,
    /** A strong enemy that leads a pack. */
    ELITE;

    /** Ranged and caster enemies stand back; there are never too many of them in one wave. */
    public boolean ranged() {
        return this == RANGED || this == CASTER;
    }

    /** The spawn marker tag this role prefers. */
    public String markerTag() {
        return ranged() ? "ranged" : "melee";
    }

    public static Role parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("missing role");
        }
        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown role: " + text, e);
        }
    }
}
