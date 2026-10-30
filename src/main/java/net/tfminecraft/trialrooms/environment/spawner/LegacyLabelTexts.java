package net.tfminecraft.trialrooms.environment.spawner;

import java.util.regex.Pattern;

/**
 * Plain texts that old saved copies of a label may show. Their text was frozen when they
 * were saved (for example an old cooldown or level), so they cannot be compared with the
 * label's current text.
 */
public final class LegacyLabelTexts {
    private LegacyLabelTexts() {}

    private static final Pattern SPAWNER_LEVEL = Pattern.compile("\\[LVL: \\d+]");
    private static final Pattern SPAWNER_STATUS = Pattern.compile(
            "Cooldown .+|Unlocking.*|Enemies Alive: \\d+.*|Spawning.*|.+: \\d+");

    /** Spawner level label, e.g. "[LVL: 1]". */
    public static boolean spawnerLevel(String plain) {
        return SPAWNER_LEVEL.matcher(plain).matches();
    }

    /** Spawner status label in any state, e.g. "Skeleton Wizard: 2" or "Cooldown 18m 10s". */
    public static boolean spawnerStatus(String plain) {
        return SPAWNER_STATUS.matcher(plain).matches();
    }

    /** Entrance label for any key, e.g. "Right-Click with a Key\nto enter". */
    public static boolean entrance(String plain) {
        return plain.startsWith("Right-Click with a ") && plain.endsWith("to enter");
    }
}
