package net.tfminecraft.trialrooms.dungeon.template;

import java.util.Locale;

/** What a room is for. The generator places rooms by type; the run decides what each type does. */
public enum RoomType {
    /** Where the party arrives. Safe. */
    START,
    /** A connector between rooms. */
    CORRIDOR,
    /** A standard fight. */
    COMBAT,
    /** A harder fight around a stronger enemy. */
    ELITE,
    /** A breather: no fight, revive and eat. */
    REST,
    /** A reward at the end of a branch. */
    TREASURE,
    /** A puzzle, choice or other non-combat event. */
    EVENT,
    /** The final fight. */
    BOSS,
    /** The reward room after the boss, with the way out. */
    VAULT;

    /** Parses a type name, ignoring case and surrounding spaces. */
    public static RoomType parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Missing room type");
        }
        try {
            return valueOf(text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown room type: " + text, e);
        }
    }
}
