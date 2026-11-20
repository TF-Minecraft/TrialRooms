package net.tfminecraft.trialrooms.dungeon.fight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;

/**
 * A kit's enemies and how its fights are put together.
 *
 * @param roster enemies by id, in kit order
 * @param packs  pack shapes
 * @param bosses bosses by id, in kit order (the first is the default)
 * @param tuning the numbers
 */
public record FightKit(Map<String, RosterEntry> roster, List<PackTemplate> packs, Map<String, BossDef> bosses,
        FightTuning tuning) {

    public FightKit {
        if (roster.isEmpty()) {
            throw new IllegalArgumentException("a fight kit needs a roster");
        }
        roster = Collections.unmodifiableMap(new LinkedHashMap<>(roster));
        packs = List.copyOf(packs);
        bosses = Collections.unmodifiableMap(new LinkedHashMap<>(bosses));
    }

    /** Roster entries of a role, in kit order. */
    public List<RosterEntry> ofRole(Role role) {
        List<RosterEntry> out = new ArrayList<>();
        for (RosterEntry e : roster.values()) {
            if (e.role() == role) {
                out.add(e);
            }
        }
        return out;
    }

    /** A boss by id; a blank id means the kit's first boss. Null when there is none. */
    public BossDef boss(String id) {
        if (id == null || id.isBlank()) {
            return bosses.isEmpty() ? null : bosses.values().iterator().next();
        }
        return bosses.get(id);
    }

    /**
     * Reads the fight sections of a kit.yml. Broken entries are skipped and reported.
     *
     * @return the fight kit, or null when the kit has no usable roster
     */
    public static FightKit load(ConfigurationSection kit, String kitId, List<String> problems) {
        ConfigurationSection rs = kit.getConfigurationSection("roster");
        if (rs == null) {
            return null;
        }
        Map<String, RosterEntry> roster = new LinkedHashMap<>();
        for (String id : rs.getKeys(false)) {
            try {
                ConfigurationSection e = section(rs, id);
                roster.put(id, new RosterEntry(id, e.getString("mob"), Role.parse(e.getString("role", "grunt")),
                        e.getDouble("cost", 1), e.getInt("weight", 1)));
            } catch (IllegalArgumentException ex) {
                problems.add(kitId + ": roster " + id + ": " + ex.getMessage());
            }
        }
        if (roster.isEmpty()) {
            problems.add(kitId + ": the roster has no usable enemies");
            return null;
        }
        List<PackTemplate> packs = new ArrayList<>();
        ConfigurationSection ps = kit.getConfigurationSection("packs");
        for (String id : ps == null ? List.<String>of() : ps.getKeys(false)) {
            try {
                ConfigurationSection p = section(ps, id);
                List<Role> slots = new ArrayList<>();
                for (String r : p.getStringList("slots")) {
                    slots.add(Role.parse(r));
                }
                packs.add(new PackTemplate(id, slots, p.getInt("weight", 1), p.getInt("min-depth", 0)));
            } catch (IllegalArgumentException ex) {
                problems.add(kitId + ": pack " + id + ": " + ex.getMessage());
            }
        }
        Map<String, BossDef> bosses = new LinkedHashMap<>();
        ConfigurationSection bs = kit.getConfigurationSection("bosses");
        for (String id : bs == null ? List.<String>of() : bs.getKeys(false)) {
            try {
                bosses.put(id, boss(id, section(bs, id)));
            } catch (IllegalArgumentException ex) {
                problems.add(kitId + ": boss " + id + ": " + ex.getMessage());
            }
        }
        return new FightKit(roster, packs, bosses, FightTuning.from(kit.getConfigurationSection("fights")));
    }

    private static BossDef boss(String id, ConfigurationSection b) {
        List<BossDef.Phase> phases = new ArrayList<>();
        for (Map<?, ?> p : b.getMapList("phases")) {
            List<Mechanic> mechanics = new ArrayList<>();
            Object list = p.get("mechanics");
            if (list instanceof List<?> l) {
                for (Object m : l) {
                    mechanics.add(Mechanic.parse(String.valueOf(m)));
                }
            }
            phases.add(new BossDef.Phase(number(p, "at", 100).intValue(), number(p, "adds", 0).doubleValue(),
                    mechanics, number(p, "every", 12).intValue()));
        }
        return new BossDef(id, b.getString("mob"), b.getString("name"), b.getDouble("health", 0), phases,
                b.getInt("enrage-seconds", 600));
    }

    private static Number number(Map<?, ?> map, String key, Number fallback) {
        Object v = map.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Number n) {
            return n;
        }
        throw new IllegalArgumentException("bad " + key + ": " + v);
    }

    private static ConfigurationSection section(ConfigurationSection parent, String id) {
        ConfigurationSection s = parent.getConfigurationSection(id);
        if (s == null) {
            throw new IllegalArgumentException("not a section");
        }
        return s;
    }
}
