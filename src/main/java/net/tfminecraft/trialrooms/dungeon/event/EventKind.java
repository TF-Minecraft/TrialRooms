package net.tfminecraft.trialrooms.dungeon.event;

import java.util.Locale;

/** What happens in a fight room besides (or instead of) the fight. */
public enum EventKind {
    /** Cold braziers: enemies keep coming until the party lights every brazier. */
    KINDLE,
    /** Hold out: the doors stay shut for a set time while enemies keep coming. */
    ENDURE,
    /** Rune stones: touch them in the order they lit up; a mistake brings enemies. No fight otherwise. */
    RUNES;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static EventKind parse(String s) {
        if (s == null) {
            throw new IllegalArgumentException("missing event kind");
        }
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown event " + s + " (kindle, endure, runes)");
        }
    }
}
