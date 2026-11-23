package net.tfminecraft.trialrooms.dungeon.loot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Weekly limits per player account, across all their characters: cosmetics (trophies, wardrobe
 * skins) roll on only so many runs of each dungeon a week, however many characters run it. Kept in
 * accounts.yml.
 */
public final class AccountLimits {

    private static final class Week {
        String week = "";
        String lastRun = "";
        boolean lastRunCounts;
        final Map<String, Integer> runs = new HashMap<>();
    }

    private final File file;
    private final Logger log;
    private final Map<UUID, Week> accounts = new HashMap<>();

    public AccountLimits(File file, Logger log) {
        this.file = file;
        this.log = log;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String key : y.getKeys(false)) {
            ConfigurationSection s = y.getConfigurationSection(key);
            try {
                Week w = new Week();
                w.week = s.getString("week", "");
                w.lastRun = s.getString("last-run", "");
                w.lastRunCounts = s.getBoolean("last-run-counts");
                ConfigurationSection r = s.getConfigurationSection("runs");
                if (r != null) {
                    r.getKeys(false).forEach(k -> w.runs.put(k, r.getInt(k)));
                }
                accounts.put(UUID.fromString(key), w);
            } catch (RuntimeException e) {
                log.warning("[dungeons] skipped a broken account in accounts.yml: " + key);
            }
        }
    }

    /**
     * Whether a run rolls cosmetics for an account: the first time a run asks, it counts towards
     * that week's runs of its dungeon if any are left; later asks of the same run get the same
     * answer.
     */
    public boolean cosmetics(UUID account, String recipe, String run, String week, int perWeek) {
        Week w = accounts.computeIfAbsent(account, k -> new Week());
        if (!week.equals(w.week)) {
            w.week = week;
            w.runs.clear();
            w.lastRun = "";
        }
        if (w.lastRun.equals(run)) {
            return w.lastRunCounts;
        }
        int done = w.runs.getOrDefault(recipe, 0);
        w.lastRun = run;
        w.lastRunCounts = done < perWeek;
        if (w.lastRunCounts) {
            w.runs.put(recipe, done + 1);
        }
        save();
        return w.lastRunCounts;
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Week> e : accounts.entrySet()) {
            Week w = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("week", w.week);
            m.put("last-run", w.lastRun);
            m.put("last-run-counts", w.lastRunCounts);
            m.put("runs", new LinkedHashMap<>(w.runs));
            y.createSection(e.getKey().toString(), m);
        }
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Path tmp = file.toPath().resolveSibling(file.getName() + ".tmp");
            Files.writeString(tmp, y.saveToString(), StandardCharsets.UTF_8);
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.severe("[dungeons] could not save accounts.yml: " + e.getMessage());
        }
    }
}
