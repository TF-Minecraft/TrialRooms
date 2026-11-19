package net.tfminecraft.trialrooms.dungeon.run;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * What a run keeps on disk so a crash or restart never strands a character or eats a key:
 * <ul>
 * <li>{@code returns/<player>.yml}: where each character goes back to, written before entering;</li>
 * <li>{@code active/<run>.yml}: the leader and key of a running run; a leftover at start means
 * the server stopped mid-run, and the key is refunded;</li>
 * <li>{@code refunds.yml}: keys owed to players, given on their next join;</li>
 * <li>{@code runs.log}: one JSON line per finished run, for later balance work.</li>
 * </ul>
 */
public final class RunFiles {

    private final Path root;
    private final Logger log;

    public RunFiles(Path root, Logger log) {
        this.root = root;
        this.log = log;
    }

    // ---- return points ----

    public void saveReturn(UUID player, String characterId, Location at) {
        File f = returnFile(player);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        String k = section(characterId);
        y.set(k + ".world", at.getWorld().getName());
        y.set(k + ".x", at.getX());
        y.set(k + ".y", at.getY());
        y.set(k + ".z", at.getZ());
        y.set(k + ".yaw", (double) at.getYaw());
        y.set(k + ".pitch", (double) at.getPitch());
        save(y, f);
    }

    /** A character's return point, or null when none is saved or its world is gone. */
    public Location returnPoint(UUID player, String characterId) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(returnFile(player));
        String k = section(characterId);
        World world = Bukkit.getWorld(y.getString(k + ".world", ""));
        if (world == null) {
            return null;
        }
        return new Location(world, y.getDouble(k + ".x"), y.getDouble(k + ".y"), y.getDouble(k + ".z"),
                (float) y.getDouble(k + ".yaw"), (float) y.getDouble(k + ".pitch"));
    }

    public void clearReturn(UUID player, String characterId) {
        File f = returnFile(player);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        y.set(section(characterId), null);
        if (y.getKeys(false).isEmpty()) {
            f.delete();
        } else {
            save(y, f);
        }
    }

    private File returnFile(UUID player) {
        return root.resolve("returns").resolve(player + ".yml").toFile();
    }

    /** YAML keys can't hold dots; character ids are plain, but stay safe. */
    private static String section(String characterId) {
        return "c_" + (characterId == null ? "none" : characterId.replace('.', '_'));
    }

    // ---- active runs and refunds ----

    public void saveActive(UUID run, UUID leader, String keyPath) {
        File f = root.resolve("active").resolve(run + ".yml").toFile();
        YamlConfiguration y = new YamlConfiguration();
        y.set("leader", leader.toString());
        y.set("key", keyPath);
        save(y, f);
    }

    public void clearActive(UUID run) {
        root.resolve("active").resolve(run + ".yml").toFile().delete();
    }

    /** Turns runs left from a stopped server into refunds. Returns how many were found. */
    public int recoverActive() {
        File[] files = root.resolve("active").toFile().listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) {
            return 0;
        }
        for (File f : files) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            String leader = y.getString("leader");
            String key = y.getString("key", "");
            if (leader != null && !key.isBlank()) {
                addRefund(UUID.fromString(leader), key);
            }
            f.delete();
        }
        return files.length;
    }

    public void addRefund(UUID player, String keyPath) {
        File f = root.resolve("refunds.yml").toFile();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        List<String> keys = new ArrayList<>(y.getStringList(player.toString()));
        keys.add(keyPath);
        y.set(player.toString(), keys);
        save(y, f);
    }

    /** Takes and clears the keys owed to a player. */
    public List<String> takeRefunds(UUID player) {
        File f = root.resolve("refunds.yml").toFile();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        List<String> keys = y.getStringList(player.toString());
        if (!keys.isEmpty()) {
            y.set(player.toString(), null);
            save(y, f);
        }
        return keys;
    }

    // ---- run log ----

    public void logRun(String json) {
        Path p = root.resolve("runs.log");
        try {
            Files.createDirectories(root);
            Files.writeString(p, json + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warning("[dungeons] could not write the run log: " + e.getMessage());
        }
    }

    /** Writes beside the file and renames over it, so a crash mid-write never leaves half a file. */
    private void save(YamlConfiguration y, File f) {
        try {
            Files.createDirectories(f.getParentFile().toPath());
            Path tmp = f.toPath().resolveSibling(f.getName() + ".tmp");
            Files.writeString(tmp, y.saveToString(), StandardCharsets.UTF_8);
            Files.move(tmp, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.severe("[dungeons] could not save " + f.getName() + ": " + e.getMessage());
        }
    }
}
