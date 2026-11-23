package net.tfminecraft.trialrooms.dungeon.run;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Dungeon leaderboards: the fastest won runs, one board for each dungeon, difficulty and party
 * size, so a solo player never races a full party. Only gate runs count (staff starts choose their
 * layout). Kept in records.yml.
 */
public final class Records {

    /** How many clears each board keeps. */
    public static final int KEEP = 10;

    /**
     * A won run.
     *
     * @param millis how long it took, from the doors opening to the vault
     * @param names  who played it
     * @param at     when it was won
     */
    public record Clear(long millis, List<String> names, long at) {
        public Clear {
            names = List.copyOf(names);
        }
    }

    private final File file;
    private final Logger log;
    /** Board key (recipe/difficulty/size) to its clears, fastest first. */
    private final Map<String, List<Clear>> boards = new TreeMap<>();

    public Records(File file, Logger log) {
        this.file = file;
        this.log = log;
        YamlConfiguration all = new YamlConfiguration();
        if (file.isFile()) {
            try {
                all.load(file);
            } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
                // kept aside, so the next save can't wipe the boards
                try {
                    Files.copy(file.toPath(), file.toPath().resolveSibling(file.getName() + ".broken"),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException copy) {
                    log.severe("[dungeons] could not keep the broken records.yml aside: " + copy.getMessage());
                }
                log.severe("[dungeons] records.yml could not be read (kept as records.yml.broken): " + e.getMessage());
            }
        }
        ConfigurationSection y = all.getConfigurationSection("boards");
        if (y == null) {
            return;
        }
        for (String key : y.getKeys(false)) {
            if (!key.matches(".+/.+/\\d{1,3}")) {
                log.warning("[dungeons] skipped a board with a broken name in records.yml: " + key);
                continue;
            }
            List<Clear> list = new ArrayList<>();
            for (Map<?, ?> m : y.getMapList(key)) {
                if (m.get("millis") instanceof Number ms && m.get("names") instanceof List<?> names
                        && m.get("at") instanceof Number at) {
                    list.add(new Clear(ms.longValue(), names.stream().map(String::valueOf).toList(), at.longValue()));
                } else {
                    log.warning("[dungeons] skipped a broken record in records.yml: " + m);
                }
            }
            list.sort(Comparator.comparingLong(Clear::millis));
            boards.put(key, list);
        }
    }

    static String key(String recipe, String difficulty, int size) {
        return recipe + "/" + (difficulty == null ? "-" : difficulty) + "/" + size;
    }

    /** Adds a won run; its place on the board (1 = fastest), or 0 when it is not fast enough to stay. */
    public int add(String recipe, String difficulty, int size, Clear clear) {
        List<Clear> board = boards.computeIfAbsent(key(recipe, difficulty, size), k -> new ArrayList<>());
        int place = 0;
        while (place < board.size() && board.get(place).millis() <= clear.millis()) {
            place++;
        }
        if (place >= KEEP) {
            return 0;
        }
        board.add(place, clear);
        if (board.size() > KEEP) {
            board.removeLast();
        }
        save();
        return place + 1;
    }

    /** A board's clears, fastest first. */
    public List<Clear> top(String recipe, String difficulty, int size) {
        return List.copyOf(boards.getOrDefault(key(recipe, difficulty, size), List.of()));
    }

    /** The fastest clear for each party size at a dungeon's difficulty, by size. */
    public Map<Integer, Clear> best(String recipe, String difficulty) {
        Map<Integer, Clear> out = new TreeMap<>();
        String prefix = key(recipe, difficulty, 0);
        prefix = prefix.substring(0, prefix.length() - 1);
        for (Map.Entry<String, List<Clear>> e : boards.entrySet()) {
            if (e.getKey().startsWith(prefix) && !e.getValue().isEmpty()) {
                out.put(Integer.parseInt(e.getKey().substring(prefix.length())), e.getValue().getFirst());
            }
        }
        return out;
    }

    /** A time as m:ss. */
    public static String time(long millis) {
        long s = millis / 1000;
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, List<Clear>> e : boards.entrySet()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Clear c : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("millis", c.millis());
                m.put("names", c.names());
                m.put("at", c.at());
                list.add(m);
            }
            y.set("boards." + e.getKey(), list);
        }
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Path tmp = file.toPath().resolveSibling(file.getName() + ".tmp");
            Files.writeString(tmp, y.saveToString(), StandardCharsets.UTF_8);
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.severe("[dungeons] could not save records.yml: " + e.getMessage());
        }
    }
}
