package net.tfminecraft.trialrooms;

import java.io.File;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.trialrooms.environment.entrance.ui.EntranceEditor;
import net.tfminecraft.trialrooms.environment.spawner.ui.ActiveSpawnerEditor;
import net.tfminecraft.trialrooms.loader.ConfigLoader;
import net.tfminecraft.trialrooms.loader.SpawnerLoader;
import net.tfminecraft.trialrooms.loader.TableLoader;
import net.tfminecraft.trialrooms.manager.ChestManager;
import net.tfminecraft.trialrooms.manager.CommandManager;
import net.tfminecraft.trialrooms.manager.ConversionDeathGuard;
import net.tfminecraft.trialrooms.manager.EntranceManager;
import net.tfminecraft.trialrooms.manager.PlayerManager;
import net.tfminecraft.trialrooms.manager.SpawnerManager;
import net.tfminecraft.trialrooms.persist.Database;

public class TrialRooms extends JavaPlugin{
    private static TrialRooms instance;
    private SpawnerManager spawnerManager;
    private final ConfigLoader configLoader = new ConfigLoader();
    private final SpawnerLoader spawnerLoader = new SpawnerLoader();
    private final TableLoader tableLoader = new TableLoader();

    @Override
    public void onEnable() {
        instance = this;
        createConfigs();
        loadConfigs();
        spawnerManager = new SpawnerManager(this);
        getCommand("tr").setExecutor(new CommandManager());
        getCommand("tr").setTabCompleter(new CommandManager());
        Database.init(this);

        // Register events
        getServer().getPluginManager().registerEvents(spawnerManager, this);
        getServer().getPluginManager().registerEvents(new ActiveSpawnerEditor(), this);
        Bukkit.getPluginManager().registerEvents(new EntranceEditor(), this);
        Bukkit.getPluginManager().registerEvents(ChestManager.get(), this);
        Bukkit.getPluginManager().registerEvents(EntranceManager.get(), this);
        Bukkit.getPluginManager().registerEvents(new ConversionDeathGuard(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerManager(TrialRooms.getInstance()), this);
        

        // Load DTOs
        EntranceManager.get().loadAllFromDisk();   // <-- load entrances
        List<Database.SpawnerRecord> spawners = Database.loadSpawners();
        List<Database.ChestRecord>   chests    = Database.loadChests();
        spawnerManager.hydrateFrom(spawners);
        ChestManager.get().hydrateFrom(chests);
        spawnerManager.start();
        getLogger().info("TrialRooms Plugin Enabled!");
        }

    @Override
    public void onDisable() {
        // Dump to disk
        Database.saveSpawners(spawnerManager.allActiveSpawners()); // implement: Collection<ActiveSpawner>
        Database.saveChests(ChestManager.allChests());   
        EntranceManager.get().saveAllNow();

        if (spawnerManager != null) {
            spawnerManager.removeAllHolograms();
        }
        if (ChestManager.get() != null) {
            ChestManager.get().removeAllHolograms();
        }
        if (EntranceManager.get() != null) {
            EntranceManager.get().removeAllHolograms();
            // optional if you added it:
            // EntranceManager.get().shutdown();
        }
        getLogger().info("TrialRooms Plugin Disabled!");
    }


    public static TrialRooms getInstance() {
        return instance;
    }

    public SpawnerManager getSpawnerManager() {
        return spawnerManager;
    }

    public void createConfigs() {
		String[] files = {
				"config.yml",
                "spawners.yml",
                "loot-tables.yml"
				};
		for(String s : files) {
			File newConfigFile = new File(getDataFolder(), s);
	        if (!newConfigFile.exists()) {
	        	newConfigFile.getParentFile().mkdirs();
	            saveResource(s, false);
	        }
		}
	}

    public void loadConfigs() {
		configLoader.loadConfig(new File(getDataFolder(), "config.yml"));
        spawnerLoader.load(new File(getDataFolder(), "spawners.yml"));
        tableLoader.load(new File(getDataFolder(), "loot-tables.yml"));
	}
}
