package net.tfminecraft.trialrooms.persist;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import net.tfminecraft.trialrooms.TrialRooms;
import net.tfminecraft.trialrooms.environment.chest.LootChest;
import net.tfminecraft.trialrooms.environment.door.DoorBlock;
import net.tfminecraft.trialrooms.environment.entrance.Entrance;
import net.tfminecraft.trialrooms.environment.spawner.ActiveSpawner;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

public final class Database {

    private static File DATA_DIR;
    private static File SPAWNERS_DIR;
    private static File CHESTS_DIR;

    private static Gson GSON;

    private Database() {}

    // ---------------------- Init ----------------------
    public static void init(TrialRooms plugin) {
        DATA_DIR     = new File(plugin.getDataFolder(), "data");
        SPAWNERS_DIR = new File(DATA_DIR, "spawners");
        CHESTS_DIR   = new File(DATA_DIR, "chests");

        SPAWNERS_DIR.mkdirs();
        CHESTS_DIR.mkdirs();

        GSON = new GsonBuilder()
                .registerTypeAdapter(SimpleLocation.class, new SimpleLocation.Adapter())
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
    }

    // ---------------------- Save ----------------------

    /** Saves each spawner to its own file: data/spawners/&lt;uuid&gt;.json */
    public static void saveSpawners(Collection<ActiveSpawner> spawners) {
        if (spawners == null) return;

        // Write current set
        Set<String> seen = new HashSet<>();
        for (ActiveSpawner s : spawners) {
            s.onUnloaded(TrialRooms.getInstance());
            SpawnerRecord rec = SpawnerRecord.from(s);
            String filename = rec.uuid + ".json";
            seen.add(filename);
            writeJson(new File(SPAWNERS_DIR, filename), rec);
        }

        // Optional: prune orphaned files
        pruneOthers(SPAWNERS_DIR, seen);
    }

    /** Saves each chest to its own file: data/chests/&lt;uuid&gt;.json */
    public static void saveChests(Collection<LootChest> chests) {
        if (chests == null) return;

        Set<String> seen = new HashSet<>();
        for (LootChest c : chests) {
            ChestRecord rec = ChestRecord.from(c);
            String filename = rec.id + ".json";
            seen.add(filename);
            writeJson(new File(CHESTS_DIR, filename), rec);
        }
        pruneOthers(CHESTS_DIR, seen);
    }

    // ---------------------- Load (DTOs) ----------------------

    /** Loads all spawner DTOs from disk. Caller should reconstruct ActiveSpawner instances. */
    public static List<SpawnerRecord> loadSpawners() {
        return readAll(SPAWNERS_DIR, SpawnerRecord.class);
    }

    /** Loads all chest DTOs from disk. Caller should reconstruct LootChest instances. */
    public static List<ChestRecord> loadChests() {
        return readAll(CHESTS_DIR, ChestRecord.class);
    }

    // ---------------------- IO helpers ----------------------

    private static <T> List<T> readAll(File dir, Class<T> type) {
        if (dir == null || !dir.isDirectory()) return Collections.emptyList();
        List<T> out = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return out;

        for (File f : files) {
            try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                T obj = GSON.fromJson(r, type);
                if (obj != null) out.add(obj);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static void writeJson(File file, Object data) {
        try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            GSON.toJson(data, w);
        } catch (Exception ignored) {}
    }

    private static void pruneOthers(File dir, Set<String> keepFilenames) {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return;
        for (File f : files) {
            if (!keepFilenames.contains(f.getName())) {
                try { Files.deleteIfExists(f.toPath()); } catch (IOException ignored) {}
            }
        }
    }

    // =========================================================
    // =======================  DTOs  ==========================
    // =========================================================

    /** Minimal serializable location. */
    public static final class SimpleLocation {
        @SerializedName("world_uuid") public UUID worldUUID;
        @SerializedName("world_name") public String worldName; // fallback if UUID not found
        public int x, y, z;
        public Float yaw, pitch; // optional

        public static SimpleLocation of(Location loc) {
            if (loc == null || loc.getWorld() == null) return null;
            SimpleLocation s = new SimpleLocation();
            s.worldUUID = loc.getWorld().getUID();
            s.worldName = loc.getWorld().getName();
            s.x = loc.getBlockX();
            s.y = loc.getBlockY();
            s.z = loc.getBlockZ();
            if (loc.getYaw() != 0f || loc.getPitch() != 0f) {
                s.yaw = loc.getYaw();
                s.pitch = loc.getPitch();
            }
            return s;
        }

        public Location toBukkit() {
            World w = worldUUID != null ? Bukkit.getWorld(worldUUID) : null;
            if (w == null && worldName != null) w = Bukkit.getWorld(worldName);
            if (w == null) return null;
            Location l = new Location(w, x, y, z);
            if (yaw != null) l.setYaw(yaw);
            if (pitch != null) l.setPitch(pitch);
            return l;
        }

        /** GSON adapter to keep nulls compact if needed. */
        static final class Adapter implements JsonSerializer<SimpleLocation>, JsonDeserializer<SimpleLocation> {
            @Override public JsonElement serialize(SimpleLocation src, Type typeOfSrc, JsonSerializationContext ctx) {
                if (src == null) return JsonNull.INSTANCE;
                JsonObject o = new JsonObject();
                if (src.worldUUID != null) o.addProperty("world_uuid", src.worldUUID.toString());
                if (src.worldName != null) o.addProperty("world_name", src.worldName);
                o.addProperty("x", src.x); o.addProperty("y", src.y); o.addProperty("z", src.z);
                if (src.yaw != null) o.addProperty("yaw", src.yaw);
                if (src.pitch != null) o.addProperty("pitch", src.pitch);
                return o;
            }
            @Override public SimpleLocation deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext ctx) throws JsonParseException {
                if (json == null || json.isJsonNull()) return null;
                JsonObject o = json.getAsJsonObject();
                SimpleLocation s = new SimpleLocation();
                if (o.has("world_uuid")) try { s.worldUUID = UUID.fromString(o.get("world_uuid").getAsString()); } catch (Exception ignored) {}
                if (o.has("world_name")) s.worldName = o.get("world_name").getAsString();
                s.x = o.get("x").getAsInt();
                s.y = o.get("y").getAsInt();
                s.z = o.get("z").getAsInt();
                if (o.has("yaw")) s.yaw = o.get("yaw").getAsFloat();
                if (o.has("pitch")) s.pitch = o.get("pitch").getAsFloat();
                return s;
            }
        }
    }

    /** One door entry (location + optional faces). */
    public static final class DoorRecord {
        public SimpleLocation loc; // block coords
        public String material;    // e.g. "IRON_BARS"
        public String facing;      // e.g. "NORTH" (optional; for Directional)
        public java.util.List<String> faces; // e.g. ["NORTH","WEST"] (for MultipleFacing)

        public static DoorRecord of(SimpleLocation loc, String material, String facing, java.util.Collection<String> faces) {
            DoorRecord r = new DoorRecord();
            r.loc = loc;
            r.material = material;
            r.facing = facing;
            r.faces = (faces == null) ? java.util.Collections.emptyList() : new java.util.ArrayList<>(faces);
            return r;
        }
    }

    /** Serialized spawner. */
    public static final class SpawnerRecord {
        public String id;
        public String uuid;
        public String block;
        public SimpleLocation loc;

        public String lootTable;
        public String mob;
        public int amount;
        public int level;
        public int spawnRadius;
        public int activateRadius;

        public String mobLootTable; // optional

        public int cooldownSeconds;
        public int cooldownRemaining;

        public String state; // ActiveSpawner.State name

        public List<DoorRecord> doors = new ArrayList<>();

        public static SpawnerRecord from(ActiveSpawner s) {
            SpawnerRecord r = new SpawnerRecord();
            r.id = s.getId();
            r.uuid = s.getUUID();
            r.block = s.getBlock();
            r.loc = SimpleLocation.of(s.getLoc());

            r.lootTable = s.getLootTable();
            r.mob = s.getMob();
            r.amount = s.getAmount();
            r.level = s.getLevel();
            r.spawnRadius = s.getSpawnRadius();
            r.activateRadius = s.getActivateRadius();
            r.mobLootTable = s.getMobLootTable();

            r.cooldownSeconds = s.getCooldownSeconds();
            r.cooldownRemaining = s.getCooldownRemaining();

            r.state = s.getState().name();

            // Doors: try a best-effort snapshot via reflection-friendly accessors
            // Provide a tiny adapter hook on DoorBlock if you can
            // (see note below on adding to DoorBlock)
            try {
                {
                    for (DoorBlock door : s.getDoorBlocks()) {
                        r.doors.add(DoorRecord.of(
                            SimpleLocation.of(door.getLocation()),
                            door.getMaterial().name(),
                            door.getFacing().name(),
                            door.getFaces().stream().map(Enum::name).collect(Collectors.toList())
                        ));
                    }
                }
            } catch (Throwable ignored) {}
            return r;
        }
    }

    /** Serialized chest. */
    public static final class ChestRecord {
        public String id; // chest UUID
        public SimpleLocation loc;
        public String spawnerUUID; // nullable
        public String facing; // BlockFace name (nullable -> default NORTH)

        public static ChestRecord from(LootChest c) {
            ChestRecord r = new ChestRecord();
            r.id = c.getId().toString();
            r.loc = SimpleLocation.of(c.getLocation());

            // We don’t have direct accessors for spawner UUID / facing in your snippet,
            // so expose them in LootChest (getSpawnerUUID(), getFacing()) or fill here if available.
            r.spawnerUUID = (c.getSpawner() != null) ? c.getSpawner().getUUID() : null;

            // If you can, add a getter in LootChest: getFacing() returns BlockFace
            try {
                // reflection-less if you add `public BlockFace getFacing()`
                java.lang.reflect.Field f = LootChest.class.getDeclaredField("facing");
                f.setAccessible(true);
                BlockFace bf = (BlockFace) f.get(c);
                r.facing = (bf != null ? bf.name() : BlockFace.NORTH.name());
            } catch (Throwable ex) {
                r.facing = BlockFace.NORTH.name();
            }
            return r;
        }
    }

    // Database.java (additions)

    public static final class EntranceRecord {
        public String id;                    // optional (not used for lookup yet)
        public SimpleLocation entrance;      // lodestone block location (normalized)
        public SimpleLocation destination;   // exact (double coords allowed)
        public SimpleLocation exit;          // lodestone block location (normalized)
        public String keyPath;               // TLibs path (nullable)

        public static EntranceRecord of(String id,
                                        SimpleLocation entrance,
                                        SimpleLocation destination,
                                        SimpleLocation exit,
                                        String keyPath) {
            EntranceRecord r = new EntranceRecord();
            r.id = id;
            r.entrance = entrance;
            r.destination = destination;
            r.exit = exit;
            r.keyPath = keyPath;
            return r;
        }
    }

    // ---- entrances IO ----
    private static File entrancesDir(JavaPlugin plugin) {
        return new File(new File(plugin.getDataFolder(), "data"), "entrances");
    }

    private static File fileForEntrance(JavaPlugin plugin, Location entranceLoc) {
        if (entranceLoc == null || entranceLoc.getWorld() == null) {
            return new File(entrancesDir(plugin), "entrance_INVALID.json");
        }
        String w = entranceLoc.getWorld().getName().replaceAll("[^a-zA-Z0-9_\\-\\.]", "_");
        int x = entranceLoc.getBlockX(), y = entranceLoc.getBlockY(), z = entranceLoc.getBlockZ();
        String name = String.format("entrance_%s_%d_%d_%d.json", w, x, y, z);
        return new File(entrancesDir(plugin), name);
    }

    public static List<EntranceRecord> loadEntrances(JavaPlugin plugin, Gson gson) {
        File dir = entrancesDir(plugin);
        if (!dir.exists()) return Collections.emptyList();

        List<EntranceRecord> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;

        for (File f : files) {
            try (Reader r = new BufferedReader(new FileReader(f, StandardCharsets.UTF_8))) {
                EntranceRecord rec = gson.fromJson(r, EntranceRecord.class);
                if (rec != null && rec.entrance != null) out.add(rec);
            } catch (Exception ignored) {}
        }
        return out;
    }

    public static void saveEntrances(JavaPlugin plugin, Gson gson, Collection<Entrance> entrances) {
        File dir = entrancesDir(plugin);
        if (!dir.exists()) dir.mkdirs();

        // Write one file per entrance
        for (Entrance e : entrances) {
            File f = fileForEntrance(plugin, e.getEntranceLoc());
            EntranceRecord rec = e.toRecord();
            try (Writer w = new BufferedWriter(new FileWriter(f, StandardCharsets.UTF_8))) {
                gson.toJson(rec, w);
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to save entrance " + f.getName() + ": " + ex.getMessage());
            }
        }

        // Optional: cleanup orphan files no longer present
        Set<String> keep = entrances.stream()
                .map(en -> fileForEntrance(plugin, en.getEntranceLoc()).getName())
                .collect(Collectors.toSet());
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                if (!keep.contains(f.getName())) try { f.delete(); } catch (Exception ignored) {}
            }
        }
    }

}
