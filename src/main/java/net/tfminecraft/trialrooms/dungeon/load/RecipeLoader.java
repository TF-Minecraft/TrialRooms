package net.tfminecraft.trialrooms.dungeon.load;

import java.io.File;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.trialrooms.dungeon.generator.DungeonRecipe;
import net.tfminecraft.trialrooms.dungeon.run.RunRules;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;

/** Loads {@code recipes.yml}. A broken recipe is skipped and reported. */
public final class RecipeLoader {

    private RecipeLoader() {
    }

    /** A recipe, the kit it builds from, and how it is played. */
    public record Entry(DungeonRecipe recipe, String kit, RunRules rules) {
    }

    public static Map<String, Entry> load(File file, List<String> problems) {
        Map<String, Entry> out = new LinkedHashMap<>();
        YamlConfiguration yml;
        try {
            yml = KitLoader.read(file);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
            return out;
        }
        for (String id : yml.getKeys(false)) {
            ConfigurationSection s = yml.getConfigurationSection(id);
            try {
                if (s == null) {
                    throw new IllegalArgumentException("not a section");
                }
                out.put(id, new Entry(recipe(id, s), s.getString("kit", "greybox"), rules(s)));
            } catch (IllegalArgumentException e) {
                problems.add("recipe " + id + ": " + e.getMessage());
            }
        }
        return out;
    }

    static DungeonRecipe recipe(String id, ConfigurationSection s) {
        int[] trunk = range(s.getString("trunk", "3-5"), "trunk");
        int[] branches = range(s.getString("branches", "0"), "branches");
        int[] length = range(s.getString("branch-length", "1"), "branch-length");
        return new DungeonRecipe(id, trunk[0], trunk[1], pool(s.getConfigurationSection("trunk-pool")),
                s.getBoolean("rest-before-boss", true), s.getBoolean("vault", true), branches[0], branches[1],
                length[0], length[1], pool(s.getConfigurationSection("branch-pool")), s.getInt("attempts", 20),
                s.getInt("step-budget", 20000));
    }

    static RunRules rules(ConfigurationSection s) {
        int[] players = range(s.getString("players", "1-3"), "players");
        return new RunRules(players[0], players[1], s.getString("key", "m.loot.trial_key"), s.getInt("lives", 3),
                s.getInt("mob-level", 1), s.getDouble("health-per-extra-player", 0.5));
    }

    private static Map<RoomType, Integer> pool(ConfigurationSection s) {
        Map<RoomType, Integer> pool = new EnumMap<>(RoomType.class);
        if (s != null) {
            for (String key : s.getKeys(false)) {
                pool.put(RoomType.parse(key), s.getInt(key));
            }
        }
        return pool;
    }

    /** Parses {@code 3} or {@code 3-5}. */
    static int[] range(String text, String what) {
        String t = text.trim();
        int dash = t.indexOf('-', 1);
        try {
            if (dash < 0) {
                int v = Integer.parseInt(t);
                return new int[] { v, v };
            }
            return new int[] { Integer.parseInt(t.substring(0, dash).trim()),
                Integer.parseInt(t.substring(dash + 1).trim()) };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad " + what + ": " + text, e);
        }
    }
}
