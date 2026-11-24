package net.tfminecraft.trialrooms.dungeon.run;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Finds kept on disk, one file per player, so none are lost to a crash or a logout:
 * <ul>
 * <li>the pouch: what a player holds in the run they are in (carried and banked), written at each
 * change; a pouch left from a crash comes home in full (not the players' fault);</li>
 * <li>pending: finds owed to a character, handed over when they next play it outside a run (a
 * crashed run's pouch, what came home while they were offline, what staff gave back);</li>
 * <li>losses: every find and piece of gear a player lost in a run, with why, so staff can look into
 * a ticket and give it back.</li>
 * </ul>
 */
public final class FindStore {

    /** How many losses each player's file keeps (the oldest go first). */
    static final int KEEP_LOSSES = 50;

    /** What a player holds in a run. */
    public record Pouch(UUID run, String character, String recipe, int tier, double boost,
            List<RunRewards.Find> carried, List<RunRewards.Find> banked) {
    }

    /** Finds owed to a character, and the gear given back with them. */
    public record Pending(String character, String recipe, UUID run, int tier, double boost, boolean restore,
            String why, List<RunRewards.Find> finds, List<byte[]> gear) {
    }

    /**
     * What a player lost, and why.
     *
     * @param detail what staff need to judge a ticket (how they quit, the fight, their health)
     */
    public record Loss(int id, long at, String character, UUID run, String recipe, String difficulty, int tier,
            String why, String detail, List<RunRewards.Find> finds, List<byte[]> gear, boolean restored) {

        /** The value of what was lost (finds only: worn gear has its own). */
        public int value() {
            return finds.stream().mapToInt(RunRewards.Find::value).sum();
        }
    }

    private final Path root;
    private final Logger log;

    public FindStore(Path root, Logger log) {
        this.root = root;
        this.log = log;
    }

    // ---- the pouch

    public void hold(UUID player, Pouch p) {
        YamlConfiguration y = read(player);
        ConfigurationSection s = y.createSection("pouch");
        s.set("run", p.run().toString());
        s.set("character", p.character());
        s.set("recipe", p.recipe());
        s.set("tier", p.tier());
        s.set("boost", p.boost());
        s.set("carried", finds(p.carried()));
        s.set("banked", finds(p.banked()));
        write(player, y);
    }

    public void clearHold(UUID player) {
        YamlConfiguration y = read(player);
        if (y.contains("pouch")) {
            y.set("pouch", null);
            write(player, y);
        }
    }

    /** The pouch on disk, or null. */
    public Pouch pouch(UUID player) {
        ConfigurationSection s = read(player).getConfigurationSection("pouch");
        return s == null ? null : new Pouch(UUID.fromString(s.getString("run")), s.getString("character"),
                s.getString("recipe"), s.getInt("tier"), s.getDouble("boost", 1), unfinds(s.getMapList("carried")),
                unfinds(s.getMapList("banked")));
    }

    /**
     * At startup: a pouch of a run a stop cut off ({@code cutOff}) comes home in full (not the
     * players' fault). A pouch of any other run was never settled, so it goes to the loss log for
     * staff to look at. One file that can't be read never stops the rest. Returns how many pouches
     * there were.
     */
    public int recover(java.util.Set<UUID> cutOff) {
        File[] files = root.toFile().listFiles((d, n) -> n.endsWith(".yml"));
        int n = 0;
        for (File f : files == null ? new File[0] : files) {
            try {
                UUID player = UUID.fromString(f.getName().substring(0, f.getName().length() - 4));
                Pouch p = pouch(player);
                if (p == null) {
                    continue;
                }
                List<RunRewards.Find> all = new ArrayList<>(p.banked());
                all.addAll(p.carried());
                if (cutOff.contains(p.run())) {
                    if (!all.isEmpty()) {
                        addPending(player, new Pending(p.character(), p.recipe(), p.run(), p.tier(), p.boost(), false,
                                "a stop cut your run short", all, List.of()));
                    }
                } else if (!all.isEmpty()) {
                    logLoss(player, new Loss(0, System.currentTimeMillis(), p.character(), p.run(), p.recipe(), "",
                            p.tier(), "finds of a run that ended without settling them", "found at startup", all,
                            List.of(), false));
                }
                clearHold(player);
                n++;
            } catch (RuntimeException e) {
                log.warning("[dungeons] could not recover the finds in " + f.getName() + ": " + e);
            }
        }
        return n;
    }

    // ---- pending

    public void addPending(UUID player, Pending p) {
        YamlConfiguration y = read(player);
        List<Map<?, ?>> list = new ArrayList<>(y.getMapList("pending"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("character", p.character());
        m.put("recipe", p.recipe());
        m.put("run", p.run().toString());
        m.put("tier", p.tier());
        m.put("boost", p.boost());
        m.put("restore", p.restore());
        m.put("why", p.why());
        m.put("finds", finds(p.finds()));
        m.put("gear", bytes(p.gear()));
        list.add(m);
        y.set("pending", list);
        write(player, y);
    }

    /** What is owed to one of a player's characters (still on disk until taken). */
    public List<Pending> pending(UUID player, String character) {
        List<Pending> out = new ArrayList<>();
        for (Map<?, ?> m : read(player).getMapList("pending")) {
            if (character.equals(m.get("character"))) {
                out.add(new Pending(character, (String) m.get("recipe"), UUID.fromString((String) m.get("run")),
                        ((Number) m.get("tier")).intValue(), ((Number) m.get("boost")).doubleValue(),
                        Boolean.TRUE.equals(m.get("restore")), (String) m.get("why"), unfinds(list(m.get("finds"))),
                        unbytes(m.get("gear"))));
            }
        }
        return out;
    }

    /** Takes what is owed to one of a player's characters off the disk, and returns it. */
    public List<Pending> takePending(UUID player, String character) {
        List<Pending> out = pending(player, character);
        if (!out.isEmpty()) {
            YamlConfiguration y = read(player);
            y.set("pending", y.getMapList("pending").stream().filter(m -> !character.equals(m.get("character"))).toList());
            write(player, y);
        }
        return out;
    }

    // ---- losses

    /** Logs a loss (its id and restored flag are the store's); returns its id. */
    public int logLoss(UUID player, Loss l) {
        YamlConfiguration y = read(player);
        int id = y.getInt("next-loss", 1);
        List<Map<?, ?>> list = new ArrayList<>(y.getMapList("losses"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("at", l.at());
        m.put("character", l.character());
        m.put("run", l.run().toString());
        m.put("recipe", l.recipe());
        m.put("difficulty", l.difficulty());
        m.put("tier", l.tier());
        m.put("why", l.why());
        m.put("detail", l.detail());
        m.put("finds", finds(l.finds()));
        m.put("gear", bytes(l.gear()));
        m.put("restored", false);
        list.add(m);
        while (list.size() > KEEP_LOSSES) {
            list.remove(0);
        }
        y.set("losses", list);
        y.set("next-loss", id + 1);
        write(player, y);
        return id;
    }

    /** A player's losses, oldest first. */
    public List<Loss> losses(UUID player) {
        List<Loss> out = new ArrayList<>();
        for (Map<?, ?> m : read(player).getMapList("losses")) {
            out.add(new Loss(((Number) m.get("id")).intValue(), ((Number) m.get("at")).longValue(),
                    (String) m.get("character"), UUID.fromString((String) m.get("run")), (String) m.get("recipe"),
                    (String) m.get("difficulty"), ((Number) m.get("tier")).intValue(), (String) m.get("why"),
                    (String) m.get("detail"), unfinds(list(m.get("finds"))), unbytes(m.get("gear")),
                    Boolean.TRUE.equals(m.get("restored"))));
        }
        return out;
    }

    /**
     * Gives a loss back: its finds and gear become pending for the character that lost them (handed
     * over when they next play it outside a run). Null when there is no such loss or it was given
     * back already.
     */
    public Loss restore(UUID player, int id) {
        YamlConfiguration y = read(player);
        List<Map<?, ?>> list = new ArrayList<>(y.getMapList("losses"));
        for (int i = 0; i < list.size(); i++) {
            if (((Number) list.get(i).get("id")).intValue() == id && !Boolean.TRUE.equals(list.get(i).get("restored"))) {
                Loss l = losses(player).get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                list.get(i).forEach((k, v) -> m.put(String.valueOf(k), v));
                m.put("restored", true);
                list.set(i, m);
                y.set("losses", list);
                write(player, y);
                addPending(player, new Pending(l.character(), l.recipe(), l.run(), l.tier(), 1, true,
                        "staff gave back what you lost", l.finds(), l.gear()));
                return l;
            }
        }
        return null;
    }

    // ---- encoding

    private static List<Map<String, Object>> finds(List<RunRewards.Find> finds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RunRewards.Find f : finds) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("moment", f.moment() == null ? "SPOILS" : f.moment().name());
            m.put("hours", f.hours());
            m.put("label", f.label());
            m.put("value", f.value());
            m.put("marks", f.marks());
            m.put("counts", f.counts());
            m.put("rolled", f.rolled());
            m.put("gear", bytes(f.gear()));
            m.put("spoils", bytes(f.spoils()));
            out.add(m);
        }
        return out;
    }

    private static List<RunRewards.Find> unfinds(List<Map<?, ?>> list) {
        List<RunRewards.Find> out = new ArrayList<>();
        for (Map<?, ?> m : list) {
            String moment = (String) m.get("moment");
            out.add(new RunRewards.Find("SPOILS".equals(moment) ? null : RunRewards.Moment.valueOf(moment),
                    ((Number) m.get("hours")).doubleValue(), (String) m.get("label"), ((Number) m.get("value")).intValue(),
                    ((Number) m.get("marks")).intValue(), Boolean.TRUE.equals(m.get("counts")), unbytes(m.get("gear")),
                    unbytes(m.get("spoils")), Boolean.TRUE.equals(m.get("rolled"))));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> list(Object o) {
        return (List<Map<?, ?>>) o;
    }

    private static List<String> bytes(List<byte[]> items) {
        return items.stream().map(b -> Base64.getEncoder().encodeToString(b)).toList();
    }

    private static List<byte[]> unbytes(Object o) {
        List<byte[]> out = new ArrayList<>();
        ((List<?>) o).forEach(s -> out.add(Base64.getDecoder().decode(String.valueOf(s))));
        return out;
    }

    // ---- files

    private File file(UUID player) {
        return root.resolve(player + ".yml").toFile();
    }

    /**
     * A player's file. One that can't be parsed is moved aside (never overwritten by the next write,
     * so staff can still read what it held) and the player starts a new one.
     */
    private YamlConfiguration read(UUID player) {
        File f = file(player);
        YamlConfiguration y = new YamlConfiguration();
        if (!f.exists()) {
            return y;
        }
        try {
            y.load(f);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            File aside = new File(f.getPath() + ".broken-" + System.currentTimeMillis());
            f.renameTo(aside); // (if even that fails, the log line below is what is left of it)
            log.severe("[dungeons] the finds of " + player + " could not be read (" + e.getMessage() + "); moved to "
                    + aside.getName());
            return new YamlConfiguration();
        }
        return y;
    }

    /** Writes beside the file and renames over it, so a crash mid-write never leaves half a file. */
    private void write(UUID player, YamlConfiguration y) {
        File f = file(player);
        try {
            Files.createDirectories(root);
            Path tmp = f.toPath().resolveSibling(f.getName() + ".tmp");
            Files.writeString(tmp, y.saveToString(), StandardCharsets.UTF_8);
            Files.move(tmp, f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.severe("[dungeons] could not save the finds of " + player + ": " + e.getMessage());
        }
    }
}
