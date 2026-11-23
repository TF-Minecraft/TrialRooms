package net.tfminecraft.trialrooms.dungeon.loot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Cosmetic rewards that leave dungeons: trophies (placeable furniture, from the boss) and wardrobe
 * skins (ArmourShop looks for normal gear, unlocked at the vault). Both are rare, roll only on runs
 * that still roll items this week, and are set per dungeon and difficulty.
 *
 * @param unlockCommand the console command that unlocks a skin, with {player} and {permission}
 * @param trophies      the trophies bosses may drop
 * @param skins         the skins vaults may unlock
 */
public record Cosmetics(String unlockCommand, List<Trophy> trophies, List<Skin> skins) {

    /**
     * A trophy: an item (a TLibs path, ItemsAdder furniture) named for the feat.
     *
     * @param difficulty the difficulty it drops on, or blank for a dungeon without difficulties
     */
    public record Trophy(String recipe, String difficulty, double chance, String item, String name) {
        public Trophy {
            check(chance, recipe);
        }
    }

    /**
     * A wardrobe skin: the ArmourShop permission that opens its set.
     *
     * @param difficulty the difficulty it unlocks on, or blank for a dungeon without difficulties
     */
    public record Skin(String recipe, String difficulty, double chance, String permission, String name) {
        public Skin {
            check(chance, recipe);
        }
    }

    private static void check(double chance, String recipe) {
        if (!(chance >= 0 && chance <= 1)) {
            throw new IllegalArgumentException(recipe + ": a chance between 0 and 1");
        }
    }

    public Cosmetics {
        trophies = List.copyOf(trophies);
        skins = List.copyOf(skins);
        if (!skins.isEmpty() && (!unlockCommand.contains("{player}") || !unlockCommand.contains("{permission}"))) {
            throw new IllegalArgumentException("unlock-command needs {player} and {permission}");
        }
    }

    /** Runs a command on the server console (how a skin unlock grants its permission); false when it failed. */
    public static boolean runOnConsole(String command) {
        return org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), command);
    }

    /** The trophies of a dungeon at a difficulty (blank for none). */
    public List<Trophy> trophies(String recipe, String difficulty) {
        return trophies.stream().filter(t -> t.recipe().equals(recipe) && t.difficulty().equals(difficulty)).toList();
    }

    /** The skins of a dungeon at a difficulty (blank for none). */
    public List<Skin> skins(String recipe, String difficulty) {
        return skins.stream().filter(s -> s.recipe().equals(recipe) && s.difficulty().equals(difficulty)).toList();
    }

    /** Reads loot.yml's cosmetics section; null (with a problem) when it is wrong, null alone when there is none. */
    public static Cosmetics load(ConfigurationSection y, List<String> problems) {
        ConfigurationSection s = y.getConfigurationSection("cosmetics");
        if (s == null) {
            return null;
        }
        try {
            List<Trophy> trophies = new ArrayList<>();
            for (Map<?, ?> m : s.getMapList("trophies")) {
                trophies.add(new Trophy(text(m, "recipe"), optional(m, "difficulty"), number(m, "chance"), text(m, "item"),
                        text(m, "name")));
            }
            List<Skin> skins = new ArrayList<>();
            for (Map<?, ?> m : s.getMapList("skins")) {
                skins.add(new Skin(text(m, "recipe"), optional(m, "difficulty"), number(m, "chance"),
                        text(m, "permission"), text(m, "name")));
            }
            return new Cosmetics(s.getString("unlock-command", ""), trophies, skins);
        } catch (RuntimeException e) {
            problems.add("loot.yml cosmetics: " + e.getMessage() + ", so runs give no trophies or skins");
            return null;
        }
    }

    private static String text(Map<?, ?> m, String key) {
        if (!(m.get(key) instanceof String v) || v.isBlank()) {
            throw new IllegalArgumentException("an entry without " + key);
        }
        return v.trim();
    }

    private static String optional(Map<?, ?> m, String key) {
        return m.get(key) instanceof String v ? v.trim() : "";
    }

    private static double number(Map<?, ?> m, String key) {
        if (!(m.get(key) instanceof Number v)) {
            throw new IllegalArgumentException(m.get("recipe") + ": no " + key);
        }
        return v.doubleValue();
    }
}
