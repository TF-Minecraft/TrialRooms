package net.tfminecraft.trialrooms.dungeon.run;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;

/** Dungeon gates: blocks that start a run of a recipe, kept in {@code dungeons/gates.yml}. */
public final class GateStore {

    /** A gate block and the recipe it opens. */
    public record Gate(String world, int x, int y, int z, String recipe) {
        String key() {
            return world + "," + x + "," + y + "," + z;
        }
    }

    private final File file;
    private final Logger log;
    private final Map<String, Gate> gates = new LinkedHashMap<>();

    public GateStore(File file, Logger log) {
        this.file = file;
        this.log = log;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : y.getMapList("gates")) {
            try {
                Gate g = new Gate(String.valueOf(m.get("world")), num(m.get("x")), num(m.get("y")), num(m.get("z")),
                        String.valueOf(m.get("recipe")));
                gates.put(g.key(), g);
            } catch (RuntimeException e) {
                log.warning("[dungeons] skipped a broken gate in gates.yml: " + m);
            }
        }
    }

    private static int num(Object o) {
        return Integer.parseInt(String.valueOf(o));
    }

    public Gate at(Block block) {
        return gates.get(block.getWorld().getName() + "," + block.getX() + "," + block.getY() + "," + block.getZ());
    }

    public Gate add(Block block, String recipe) {
        Gate g = new Gate(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), recipe);
        gates.put(g.key(), g);
        save();
        return g;
    }

    public boolean remove(Block block) {
        Gate g = at(block);
        if (g == null) {
            return false;
        }
        gates.remove(g.key());
        save();
        return true;
    }

    public List<Gate> all() {
        return new ArrayList<>(gates.values());
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Gate g : gates.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", g.world());
            m.put("x", g.x());
            m.put("y", g.y());
            m.put("z", g.z());
            m.put("recipe", g.recipe());
            list.add(m);
        }
        y.set("gates", list);
        try {
            Files.createDirectories(file.getParentFile().toPath());
            Path tmp = file.toPath().resolveSibling(file.getName() + ".tmp");
            Files.writeString(tmp, y.saveToString(), StandardCharsets.UTF_8);
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.severe("[dungeons] could not save gates.yml: " + e.getMessage());
        }
    }
}
