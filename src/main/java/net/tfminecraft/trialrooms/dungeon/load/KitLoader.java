package net.tfminecraft.trialrooms.dungeon.load;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.trialrooms.dungeon.grid.Side;
import net.tfminecraft.trialrooms.dungeon.fight.FightKit;
import net.tfminecraft.trialrooms.dungeon.template.Doorway;
import net.tfminecraft.trialrooms.dungeon.template.Marker;
import net.tfminecraft.trialrooms.dungeon.template.RoomCatalog;
import net.tfminecraft.trialrooms.dungeon.template.RoomTemplate;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;

/**
 * Loads a kit folder: {@code kit.yml} plus {@code rooms/<id>.yml} sidecars, each next to its
 * {@code rooms/<id>.nbt} structure. A broken room is skipped and reported; the rest still load.
 */
public final class KitLoader {

    private KitLoader() {
    }

    /**
     * @param problems receives one line per skipped room or kit setting
     * @return the kit, or {@code null} when it has no usable rooms or a broken {@code kit.yml}
     */
    public static DungeonKit load(File kitDir, List<String> problems) {
        String id = kitDir.getName();
        File kitFile = new File(kitDir, "kit.yml");
        if (!kitFile.isFile()) {
            problems.add(id + ": missing kit.yml");
            return null;
        }
        YamlConfiguration kit;
        int cellSize;
        int cellHeight;
        int doorHeight;
        try {
            kit = read(kitFile);
            cellSize = kit.getInt("cell-size", 32);
            cellHeight = kit.getInt("cell-height", 24);
            new RoomCatalog(cellSize, cellHeight, List.of()); // checks the sizes
            doorHeight = kit.getInt("door-height", 4);
            if (doorHeight < 2 || doorHeight >= cellHeight) {
                throw new IllegalArgumentException("door-height must be at least 2 and below cell-height");
            }
        } catch (IllegalArgumentException e) {
            problems.add(id + ": " + e.getMessage());
            return null;
        }
        File[] files = new File(kitDir, "rooms").listFiles((dir, name) -> name.endsWith(".yml"));
        List<File> sidecars = new ArrayList<>(files == null ? List.of() : Arrays.asList(files));
        sidecars.sort(null);
        List<RoomTemplate> templates = new ArrayList<>();
        Map<String, File> structures = new LinkedHashMap<>();
        for (File sidecar : sidecars) {
            String roomId = sidecar.getName().substring(0, sidecar.getName().length() - 4);
            File nbt = new File(sidecar.getParentFile(), roomId + ".nbt");
            try {
                if (!nbt.isFile()) {
                    throw new IllegalArgumentException("missing " + nbt.getName());
                }
                RoomTemplate template = room(roomId, read(sidecar));
                RoomCatalog.check(template, cellSize, cellHeight);
                templates.add(template);
                structures.put(roomId, nbt);
            } catch (RuntimeException e) {
                problems.add(id + "/" + roomId + ": " + e.getMessage());
            }
        }
        if (templates.isEmpty()) {
            problems.add(id + ": no usable rooms");
            return null;
        }
        Map<String, String> mobs = new LinkedHashMap<>();
        var mobSection = kit.getConfigurationSection("mobs");
        if (mobSection != null) {
            for (String tag : mobSection.getKeys(false)) {
                mobs.put(tag, mobSection.getString(tag));
            }
        }
        FightKit fights = FightKit.load(kit, id, problems);
        if (fights == null && mobs.isEmpty()) {
            problems.add(id + ": kit.yml has no roster or mobs section, so its fight rooms stay empty");
        }
        return new DungeonKit(id, new RoomCatalog(cellSize, cellHeight, templates), structures, doorHeight,
                kit.getString("seal-block", "minecraft:stone_bricks"), kit.getString("door-block", "minecraft:iron_bars"),
                mobs, fights);
    }

    /** Reads a YAML file, turning syntax and read errors into a reportable problem. */
    static YamlConfiguration read(File file) {
        YamlConfiguration yml = new YamlConfiguration();
        try {
            yml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalArgumentException("cannot read " + file.getName() + ": " + e.getMessage(), e);
        }
        return yml;
    }

    static RoomTemplate room(String id, YamlConfiguration yml) {
        int[] footprint = ints(yml.getString("footprint", "1x1"), "x", 2, "footprint");
        List<Doorway> doorways = new ArrayList<>();
        for (Map<?, ?> d : yml.getMapList("doorways")) {
            int[] cell = ints(text(d, "cell", "0,0"), ",", 2, "doorway cell");
            doorways.add(new Doorway(cell[0], cell[1], Side.parse(text(d, "side", null)),
                    Integer.parseInt(text(d, "width", "4"))));
        }
        List<Marker> markers = new ArrayList<>();
        for (Map<?, ?> m : yml.getMapList("markers")) {
            int[] at = ints(text(m, "at", null), ",", 3, "marker position");
            String facing = text(m, "facing", null);
            markers.add(new Marker(text(m, "kind", null), text(m, "tag", ""), at[0], at[1], at[2],
                    facing == null ? null : Side.parse(facing)));
        }
        return new RoomTemplate(id, RoomType.parse(yml.getString("type")), footprint[0], footprint[1], doorways,
                markers, yml.getInt("weight", 1), yml.getInt("min-depth", 0), yml.getInt("max-depth", 0),
                yml.getInt("max-per-run", 0), new HashSet<>(yml.getStringList("tags")));
    }

    private static String text(Map<?, ?> map, String key, String fallback) {
        Object v = map.get(key);
        return v == null ? fallback : v.toString();
    }

    /** Parses {@code count} integers joined by {@code separator}, such as {@code 1x2} or {@code 3,1,4}. */
    static int[] ints(String text, String separator, int count, String what) {
        if (text == null) {
            throw new IllegalArgumentException("missing " + what);
        }
        String[] parts = text.trim().split(java.util.regex.Pattern.quote(separator));
        if (parts.length != count) {
            throw new IllegalArgumentException("bad " + what + ": " + text);
        }
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad " + what + ": " + text, e);
            }
        }
        return out;
    }
}
