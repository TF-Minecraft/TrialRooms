package net.tfminecraft.trialrooms.dungeon.supply;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import org.bukkit.configuration.ConfigurationSection;

/**
 * How players keep going between fights, from settings.yml's {@code campfire:} and {@code supplies:}.
 * Inside runs nothing heals on its own: healers, lifesteal, the rest room's campfire and supplies do.
 *
 * @param campfireSeconds seconds a player crouches by the rest room's fire to rest there
 * @param campfireRadius  blocks from the rest room's marker that count as by the fire
 * @param campfire        share of max health and mana resting restores, by difficulty tier (the highest
 *                        tier listed at or under the run's counts); once per player and run
 * @param pouch           slots in the supply pouch (each holds one stack of one supply)
 * @param items           the supplies, in the order the locker shows them
 * @param fightDrop       the chance each player finds a supply when an ordinary fight room is cleared
 * @param eliteDrop       ... an elite room, an event or the boss
 * @param treasureDrop    ... on first stepping into a treasure room
 * @param heldSkills      MythicLib skills (upper case ids) that don't run for players in a run: the
 *                        out-of-combat regen every class has (TFMC_RECOVERY)
 */
public record SupplySettings(int campfireSeconds, double campfireRadius, NavigableMap<Integer, Double> campfire,
        int pouch, Map<String, Supply> items, double fightDrop, double eliteDrop, double treasureDrop,
        Set<String> heldSkills) {

    /** The out-of-combat regen passive every class has. */
    static final Set<String> DEFAULT_HELD = Set.of("TFMC_RECOVERY");

    public static final SupplySettings DEFAULTS = new SupplySettings(3, 4, new TreeMap<>(Map.of(1, 1.0, 2, 0.5)),
            4, Map.of(), 0, 0, 0);

    /** Holding back the default skills. */
    public SupplySettings(int campfireSeconds, double campfireRadius, NavigableMap<Integer, Double> campfire,
            int pouch, Map<String, Supply> items, double fightDrop, double eliteDrop, double treasureDrop) {
        this(campfireSeconds, campfireRadius, campfire, pouch, items, fightDrop, eliteDrop, treasureDrop, DEFAULT_HELD);
    }

    public SupplySettings {
        if (campfireSeconds < 1 || !(campfireRadius > 0) || pouch < 0 || !chance(fightDrop) || !chance(eliteDrop)
                || !chance(treasureDrop) || campfire.values().stream().anyMatch(v -> !chance(v))) {
            throw new IllegalArgumentException("campfire seconds and radius above 0, pouch not negative,"
                    + " shares and chances 0 to 1");
        }
        campfire = new TreeMap<>(campfire);
        items = new LinkedHashMap<>(items);
        heldSkills = Set.copyOf(heldSkills.stream().map(s -> s.toUpperCase(Locale.ROOT)).toList());
    }

    private static boolean chance(double d) {
        return d >= 0 && d <= 1;
    }

    /** The share resting restores at a difficulty tier (nothing under the lowest tier listed). */
    public double campfireShare(int tier) {
        Map.Entry<Integer, Double> e = campfire.floorEntry(tier);
        return e == null ? 0 : e.getValue();
    }

    /** A supply by id, or null. */
    public Supply supply(String id) {
        return id == null ? null : items.get(id);
    }

    /** Whether a MythicLib skill (its id) is held back for players in a run. */
    public boolean holds(String skill) {
        return skill != null && heldSkills.contains(skill.toUpperCase(Locale.ROOT));
    }

    /** As below, with settings.yml's no-regen-skills (the default list when it says nothing). */
    public static SupplySettings from(ConfigurationSection campfire, ConfigurationSection supplies, java.util.List<String> held) {
        SupplySettings s = from(campfire, supplies);
        return held == null ? s : new SupplySettings(s.campfireSeconds(), s.campfireRadius(), s.campfire(), s.pouch(),
                s.items(), s.fightDrop(), s.eliteDrop(), s.treasureDrop(), Set.copyOf(held));
    }

    /** The two sections of settings.yml (either may be null: its defaults apply). */
    public static SupplySettings from(ConfigurationSection campfire, ConfigurationSection supplies) {
        SupplySettings d = DEFAULTS;
        NavigableMap<Integer, Double> shares = new TreeMap<>(d.campfire());
        int seconds = d.campfireSeconds();
        double radius = d.campfireRadius();
        if (campfire != null) {
            seconds = campfire.getInt("seconds", seconds);
            radius = campfire.getDouble("radius", radius);
            ConfigurationSection r = campfire.getConfigurationSection("restore");
            if (r != null) {
                shares.clear();
                for (String tier : r.getKeys(false)) {
                    shares.put(tierOf(tier), r.getDouble(tier));
                }
            }
        }
        if (supplies == null) {
            return new SupplySettings(seconds, radius, shares, d.pouch(), Map.of(), 0, 0, 0);
        }
        Map<String, Supply> items = new LinkedHashMap<>();
        ConfigurationSection list = supplies.getConfigurationSection("items");
        for (String id : list == null ? Set.<String>of() : list.getKeys(false)) {
            items.put(id, supply(id, list.getConfigurationSection(id)));
        }
        ConfigurationSection drops = supplies.getConfigurationSection("drops");
        return new SupplySettings(seconds, radius, shares, supplies.getInt("pouch", d.pouch()), items,
                drops == null ? 0 : drops.getDouble("fight"), drops == null ? 0 : drops.getDouble("elite"),
                drops == null ? 0 : drops.getDouble("treasure"));
    }

    private static int tierOf(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("campfire restore: " + key + " is no tier number");
        }
    }

    static Supply supply(String id, ConfigurationSection s) {
        if (s == null) {
            throw new IllegalArgumentException("supply " + id + ": not a section");
        }
        Supply.Use use;
        try {
            use = Supply.Use.valueOf(s.getString("use", "").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("supply " + id + ": use must be one of health, mana, bandage, revive, ward");
        }
        ConfigurationSection look = s.getConfigurationSection("look");
        Supply.Look l = look == null ? new Supply.Look("PAPER", 0, null)
                : new Supply.Look(look.getString("material", "PAPER"), look.getInt("model-data"), look.getString("item-model"));
        return new Supply(id, s.getString("name", id), l, s.getInt("stack", 1), s.getInt("price"), use,
                s.getDouble("amount"), s.getInt("seconds"), s.getInt("cooldown"), s.getDouble("radius"),
                Set.copyOf(s.getStringList("potions")), Set.copyOf(s.getStringList("auras")),
                List.copyOf(s.getStringList("kits")));
    }
}
