package net.tfminecraft.trialrooms.dungeon.allies;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a MythicMobs mechanic helps or harms its targets. Inside dungeons, help from players
 * never reaches mobs and harm from players never reaches other players.
 */
public enum EffectKind {
    /** Heals, shields, cleanses, buffs. */
    SUPPORT,
    /** Damage, crowd control, debuffs. */
    HARM,
    /** Anything else (variables, sounds, summons...): left alone. */
    NONE;

    static final Set<String> HARMFUL_POTIONS = Set.of("SLOW", "SLOWNESS", "SLOW_DIGGING", "MINING_FATIGUE", "HARM",
            "INSTANT_DAMAGE", "CONFUSION", "NAUSEA", "BLINDNESS", "HUNGER", "WEAKNESS", "POISON", "WITHER",
            "LEVITATION", "UNLUCK", "BAD_OMEN", "DARKNESS", "INFESTED", "OOZING", "WEAVING", "WIND_CHARGED");
    static final Set<String> HELPFUL_POTIONS = Set.of("SPEED", "FAST_DIGGING", "HASTE", "INCREASE_DAMAGE", "STRENGTH",
            "HEAL", "INSTANT_HEALTH", "JUMP", "JUMP_BOOST", "REGENERATION", "DAMAGE_RESISTANCE", "RESISTANCE",
            "FIRE_RESISTANCE", "WATER_BREATHING", "INVISIBILITY", "NIGHT_VISION", "HEALTH_BOOST", "ABSORPTION",
            "SATURATION", "LUCK", "SLOW_FALLING", "CONDUIT_POWER", "DOLPHINS_GRACE", "HERO_OF_THE_VILLAGE");
    /** Mechanics that only ever harm their targets. */
    static final Set<String> HARM_MECHANICS = Set.of("ThrowMechanic", "PullMechanic", "IgniteMechanic",
            "FreezeMechanic", "LightningMechanic");

    private static final Pattern TYPE = Pattern.compile("[{;]\\s*(?:type|t)\\s*=\\s*([A-Za-z_]+)");
    private static final Pattern AURA = Pattern.compile("[{;]\\s*(?:auraName|aura|name|n)\\s*=\\s*([A-Za-z0-9_]+)");

    /**
     * Classifies a mechanic from its class name and config line. Auras are classified by name
     * against the configured lists; potions by their effect.
     */
    public static EffectKind of(String mechanicClass, String line, Set<String> harmfulAuras, Set<String> helpfulAuras) {
        String l = line == null ? "" : line;
        if (mechanicClass.contains("Break") || mechanicClass.contains("Remove")) {
            return NONE; // shield breaks and aura removals cut both ways
        }
        if (mechanicClass.contains("Heal") || mechanicClass.contains("Shield") || mechanicClass.contains("Absorb")
                || mechanicClass.equals("PotionClearMechanic")) {
            return SUPPORT;
        }
        if (mechanicClass.contains("Damage") || HARM_MECHANICS.contains(mechanicClass)) {
            return HARM;
        }
        if (mechanicClass.equals("PotionMechanic")) {
            return potion(find(TYPE, l));
        }
        if (mechanicClass.contains("Aura")) {
            return aura(find(AURA, l), harmfulAuras, helpfulAuras);
        }
        return NONE;
    }

    /** A potion effect name as MythicMobs writes it (legacy names included). */
    public static EffectKind potion(String name) {
        String n = name == null ? "" : name.toUpperCase(Locale.ROOT);
        if (HARMFUL_POTIONS.contains(n)) {
            return HARM;
        }
        return HELPFUL_POTIONS.contains(n) ? SUPPORT : NONE;
    }

    /** An aura by name: harmful (a status) or helpful (a buff), matched ignoring case. */
    public static EffectKind aura(String name, Set<String> harmfulAuras, Set<String> helpfulAuras) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (harmfulAuras.contains(n)) {
            return HARM;
        }
        return helpfulAuras.contains(n) ? SUPPORT : NONE;
    }

    private static String find(Pattern p, String line) {
        Matcher m = p.matcher(line);
        return m.find() ? m.group(1) : null;
    }
}
