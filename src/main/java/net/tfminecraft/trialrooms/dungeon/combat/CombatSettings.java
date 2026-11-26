package net.tfminecraft.trialrooms.dungeon.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;

/**
 * How enemy attacks play in every dungeon (settings.yml {@code combat:}): one colour code everywhere,
 * how open an enemy stands after its strike, what breaks a spell off and what slows a windup.
 *
 * @param openBonus     extra damage an enemy takes while open after its strike, by its level (1 = Normal);
 *                      levels past the list use the last
 * @param staggerTicks  how much longer an attack broken off leaves the enemy open
 * @param interruptAuras MythicMobs auras that break a spell's windup off (a silence, a freeze, a stun)
 * @param staggerAuras  MythicMobs auras that break any windup off (a freeze, a stun)
 * @param slowAuras     MythicMobs auras that stretch a windup, by how many times longer it takes (on top of
 *                      Slowness; leave out an aura that already gives Slowness)
 * @param maxSlow       the most times longer a windup can take, however slowed
 * @param slownessStep  how much longer each level of Slowness makes a windup (0.2: level I takes 1.2 times)
 * @param cues          the MythicMobs skill each cue casts on the enemy (glow colour and sound), by cue
 */
public record CombatSettings(List<Double> openBonus, int staggerTicks, List<String> interruptAuras,
        List<String> staggerAuras, Map<String, Double> slowAuras, double slownessStep, double maxSlow, Map<Cue, String> cues) {

    /** The colour code: the same in every dungeon. */
    public enum Cue {
        /** Orange: a swing to dodge. */
        DODGE,
        /** Red: a wide hit to get out of. */
        LEAVE,
        /** Blue: a spell a silence or freeze can break off. */
        INTERRUPT,
        /** White flash: it stands open, hit it now. */
        OPEN,
        /** Back to normal. */
        CLEAR
    }

    public static final CombatSettings DEFAULTS = new CombatSettings(List.of(0.4, 0.35, 0.3, 0.25), 30,
            List.of("Silenced", "Frozen", "Stun_Aura"), List.of("Frozen", "Stun_Aura"), Map.of(), 0.2, 2, defaultCues());

    private static Map<Cue, String> defaultCues() {
        Map<Cue, String> m = new LinkedHashMap<>();
        m.put(Cue.DODGE, "TR_Cue_Dodge");
        m.put(Cue.LEAVE, "TR_Cue_Leave");
        m.put(Cue.INTERRUPT, "TR_Cue_Interrupt");
        m.put(Cue.OPEN, "TR_Cue_Open");
        m.put(Cue.CLEAR, "TR_Cue_Clear");
        return m;
    }

    public CombatSettings {
        if (openBonus.isEmpty() || staggerTicks < 0 || !(slownessStep >= 0) || !(maxSlow >= 1)) {
            throw new IllegalArgumentException("combat: open-bonus needs a value, stagger-ticks and slowness-step not"
                    + " negative, max-slow at least 1");
        }
        openBonus = List.copyOf(openBonus);
        interruptAuras = List.copyOf(interruptAuras);
        staggerAuras = List.copyOf(staggerAuras);
        slowAuras = Map.copyOf(slowAuras);
        cues = Map.copyOf(cues);
    }

    /** The open bonus at an enemy level. */
    public double openBonus(int level) {
        return openBonus.get(Math.max(0, Math.min(openBonus.size() - 1, level - 1)));
    }

    /** Reads the {@code combat:} section; anything left out keeps its default. */
    public static CombatSettings from(ConfigurationSection s) {
        if (s == null) {
            return DEFAULTS;
        }
        List<Double> open = s.isList("open-bonus") ? s.getDoubleList("open-bonus") : DEFAULTS.openBonus();
        List<String> interrupt = s.isList("interrupt-auras") ? s.getStringList("interrupt-auras") : DEFAULTS.interruptAuras();
        List<String> stagger = s.isList("stagger-auras") ? s.getStringList("stagger-auras") : DEFAULTS.staggerAuras();
        Map<String, Double> slow = new LinkedHashMap<>();
        ConfigurationSection ss = s.getConfigurationSection("slow-auras");
        if (ss == null) {
            slow.putAll(DEFAULTS.slowAuras());
        } else {
            ss.getKeys(false).forEach(k -> slow.put(k, ss.getDouble(k, 1)));
        }
        Map<Cue, String> cues = new LinkedHashMap<>(DEFAULTS.cues());
        ConfigurationSection cs = s.getConfigurationSection("cues");
        for (String k : cs == null ? List.<String>of() : new ArrayList<>(cs.getKeys(false))) {
            String skill = cs.getString(k, "");
            Cue cue = Cue.valueOf(k.toUpperCase(java.util.Locale.ROOT));
            if (skill.isBlank()) {
                cues.remove(cue); // (no cue)
            } else {
                cues.put(cue, skill);
            }
        }
        return new CombatSettings(open, s.getInt("stagger-ticks", DEFAULTS.staggerTicks()), interrupt, stagger, slow,
                s.getDouble("slowness-step", DEFAULTS.slownessStep()), s.getDouble("max-slow", DEFAULTS.maxSlow()), cues);
    }
}
