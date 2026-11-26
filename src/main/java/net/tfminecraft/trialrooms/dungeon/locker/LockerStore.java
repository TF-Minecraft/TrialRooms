package net.tfminecraft.trialrooms.dungeon.locker;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Lockers on disk, one file per character: {@code lockers/<player>/<character>.locker}.
 *
 * <p>Each file starts with a line naming the format and a SHA-256 of the rest, so a torn or edited
 * file is noticed instead of read wrong. A save writes a temporary file, forces it to disk and
 * moves it over the old one in one step; the copy it replaces is kept as {@code .prev}. A file that
 * fails its check is read from {@code .prev}; when both fail the locker is refused (and left
 * alone for staff) rather than started empty. Items that cannot safely go anywhere are written to
 * {@code lockers/quarantine/} for staff.
 */
public final class LockerStore {

    static final String MAGIC = "TRLOCKER1";

    /** A locker whose file and backup both failed their check. */
    public static final class BrokenLocker extends IOException {
        private static final long serialVersionUID = 1L;

        BrokenLocker(String message) {
            super(message);
        }
    }

    private final Path root;
    private final Logger log;

    public LockerStore(Path root, Logger log) {
        this.root = root;
        this.log = log;
    }

    Path file(UUID player, String character) {
        return root.resolve(player.toString()).resolve(safe(character) + ".locker");
    }

    private static Path prev(Path f) {
        return f.resolveSibling(f.getFileName() + ".prev");
    }

    /** File-safe form of a character id. */
    static String safe(String character) {
        return character.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /** A character's locker; a new one when none was ever saved. */
    public Locker read(UUID player, String character) throws BrokenLocker {
        Path f = file(player, character);
        if (!Files.exists(f) && !Files.exists(prev(f))) {
            return new Locker();
        }
        try {
            return parse(Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) {
            log.warning("[dungeons] locker " + f + " is unreadable (" + e.getMessage() + "); trying its backup");
        }
        try {
            Locker l = parse(Files.readString(prev(f), StandardCharsets.UTF_8));
            l.fromBackup = true;
            log.warning("[dungeons] locker " + f + " was read from its backup");
            return l;
        } catch (IOException | IllegalArgumentException e) {
            throw new BrokenLocker("locker " + f + " and its backup are unreadable: " + e.getMessage());
        }
    }

    /** Saves a locker under its next revision. False (and the locker unchanged) when the write failed. */
    public boolean write(UUID player, String character, Locker locker) {
        Path f = file(player, character);
        long next = locker.revision() + 1;
        String body = body(locker, next);
        String text = MAGIC + " " + sha256(body) + "\n" + body;
        try {
            Files.createDirectories(f.getParent());
            if (Files.exists(f)) {
                Files.copy(f, prev(f), StandardCopyOption.REPLACE_EXISTING);
            }
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
                ch.force(true);
            }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.severe("[dungeons] could not save locker " + f + ": " + e);
            return false;
        }
        locker.revision(next);
        return true;
    }

    /** Writes items nobody can safely own for staff to sort out. False when that failed too. */
    public boolean quarantine(UUID player, String character, String reason, List<byte[]> items) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("player", player.toString());
        y.set("character", character);
        y.set("reason", reason);
        y.set("items", items.stream().map(b -> Base64.getEncoder().encodeToString(b)).toList());
        Path f = root.resolve("quarantine")
                .resolve(player + "-" + safe(character) + "-" + System.nanoTime() + ".yml");
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, y.saveToString(), StandardCharsets.UTF_8);
            log.warning("[dungeons] " + items.size() + " item(s) quarantined to " + f + ": " + reason);
            return true;
        } catch (IOException e) {
            log.severe("[dungeons] could not quarantine items for " + player + "/" + character + ": " + e);
            return false;
        }
    }

    // ---- format

    static String body(Locker l, long revision) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("revision", revision);
        Map<String, String> items = new LinkedHashMap<>();
        l.items().forEach((id, data) -> items.put(id, encode(data)));
        y.createSection("items", items);
        for (int i = 0; i < Locker.LOADOUTS; i++) {
            Map<String, String> slots = new LinkedHashMap<>();
            l.loadout(i).forEach((slot, id) -> slots.put(String.valueOf(slot), id));
            y.createSection("loadouts." + i, slots);
        }
        y.set("worn", l.worn());
        y.set("worn-at", l.wornAt());
        Map<String, String> worn = new LinkedHashMap<>();
        l.wornSlots().forEach((slot, id) -> worn.put(String.valueOf(slot), id));
        y.createSection("worn-slots", worn);
        Map<String, String> own = new LinkedHashMap<>();
        l.displaced().forEach((slot, data) -> own.put(String.valueOf(slot), encode(data)));
        y.createSection("displaced", own);
        y.set("owed", l.owed().stream().map(LockerStore::encode).toList());
        y.set("marks", l.marks());
        y.set("pity", l.pity());
        y.set("week", l.week());
        y.createSection("week-runs", l.weekRuns());
        y.set("week-marks", l.weekMarks());
        y.set("last-run", l.lastRun());
        y.set("last-run-rewarded", l.lastRunRewarded());
        l.cleared().forEach((recipe, ds) -> y.set("cleared." + recipe, List.copyOf(ds)));
        y.createSection("supplies", l.supplies());
        y.createSection("packed", l.packed());
        y.createSection("supplies-out", l.out());
        return y.saveToString();
    }

    static Locker parse(String text) {
        int nl = text.indexOf('\n');
        String head = nl < 0 ? text : text.substring(0, nl);
        String[] parts = head.split(" ");
        if (parts.length != 2 || !parts[0].equals(MAGIC)) {
            throw new IllegalArgumentException("not a locker file");
        }
        String body = text.substring(nl + 1);
        if (!sha256(body).equals(parts[1])) {
            throw new IllegalArgumentException("checksum mismatch");
        }
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(body);
        } catch (InvalidConfigurationException e) {
            throw new IllegalArgumentException("bad yaml: " + e.getMessage());
        }
        Locker l = new Locker();
        ConfigurationSection items = section(y, "items");
        for (String id : items.getKeys(false)) {
            l.put(id, decode(items.getString(id)));
        }
        for (int i = 0; i < Locker.LOADOUTS; i++) {
            ConfigurationSection s = section(y, "loadouts." + i);
            for (String slot : s.getKeys(false)) {
                l.restoreSlot(i, Integer.parseInt(slot), s.getString(slot));
            }
        }
        int worn = y.getInt("worn", -1);
        if (worn >= 0) {
            Map<Integer, String> slots = new LinkedHashMap<>();
            ConfigurationSection ws = section(y, "worn-slots");
            ws.getKeys(false).forEach(k -> slots.put(Integer.parseInt(k), ws.getString(k)));
            Map<Integer, byte[]> own = new LinkedHashMap<>();
            ConfigurationSection ds = section(y, "displaced");
            ds.getKeys(false).forEach(k -> own.put(Integer.parseInt(k), decode(ds.getString(k))));
            l.restoreWorn(worn, y.getLong("worn-at", -1), slots, own);
        }
        y.getStringList("owed").forEach(s -> l.owe(decode(s)));
        l.marks(y.getInt("marks"));
        l.pity(y.getDouble("pity"));
        l.newWeek(y.getString("week", ""));
        ConfigurationSection runs = section(y, "week-runs");
        runs.getKeys(false).forEach(k -> l.weekRun(k, runs.getInt(k)));
        l.weekMarks(y.getInt("week-marks"));
        l.lastRun(y.getString("last-run", ""), y.getBoolean("last-run-rewarded"));
        ConfigurationSection won = section(y, "cleared");
        won.getKeys(false).forEach(r -> won.getStringList(r).forEach(d -> l.clear(r, d)));
        ConfigurationSection owned = section(y, "supplies");
        owned.getKeys(false).forEach(k -> l.supplies(k, owned.getInt(k)));
        ConfigurationSection pack = section(y, "packed");
        pack.getKeys(false).forEach(k -> l.packed(k, pack.getInt(k)));
        ConfigurationSection away = section(y, "supplies-out");
        away.getKeys(false).forEach(k -> l.out(k, away.getInt(k)));
        l.revision(y.getLong("revision"));
        return l;
    }

    private static ConfigurationSection section(YamlConfiguration y, String path) {
        ConfigurationSection s = y.getConfigurationSection(path);
        return s == null ? new YamlConfiguration() : s;
    }

    static String encode(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    static byte[] decode(String s) {
        return Base64.getDecoder().decode(s);
    }

    static String sha256(String s) {
        return digest("SHA-256", s);
    }

    static String digest(String algorithm, String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every Java has SHA-256
        }
    }
}
