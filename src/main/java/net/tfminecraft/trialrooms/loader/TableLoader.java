package net.tfminecraft.trialrooms.loader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.trialrooms.cache.Cache;
import net.tfminecraft.trialrooms.loot.LootTable;

public class TableLoader implements LoaderInterface{
	static HashMap<String, LootTable> oList = new HashMap<>();
	public static void clear() {
		oList.clear();
	}
	public static HashMap<String, LootTable> get() {
		return oList;
	}
	public static LootTable getByString(String id) {
		if(oList.containsKey(id)) return oList.get(id);
		return null;
	}
	public void load(File configFile) {
		clear();
		FileConfiguration config = new YamlConfiguration();
        try {
        	config.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            e.printStackTrace();
        }

		for (String key : config.getKeys(false)) {
			ConfigurationSection sec = config.getConfigurationSection(key);
			if (sec == null) {
				// fallback: legacy tables were lists directly under the key
				LootTable o = new LootTable(key, config.getStringList(key), Cache.rarityBiasPerLevel);
				oList.put(key, o);
				continue;
			}
			LootTable o = LootTable.fromSection(key, sec, Cache.rarityBiasPerLevel);
			if (o != null) oList.put(key, o);
		}

	}
}
