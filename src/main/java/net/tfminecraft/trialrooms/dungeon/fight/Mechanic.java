package net.tfminecraft.trialrooms.dungeon.fight;

import java.util.Locale;

/** Boss mechanics, each announced by sound and text with time to react. */
public enum Mechanic {
    /** Marks the ground under one player; after the warning, everyone still in the mark is hit. */
    SLAM,
    /** The boss stands still and charges; after the warning, everyone close to it is hit. */
    SHOCKWAVE,
    /** The boss calls a few adds; nothing to dodge. */
    RALLY_CALL;

    public static Mechanic parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("missing mechanic");
        }
        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown mechanic: " + text, e);
        }
    }
}
