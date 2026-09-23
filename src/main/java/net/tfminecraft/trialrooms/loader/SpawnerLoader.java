package net.tfminecraft.trialrooms.loader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.bukkit.block.Block;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import me.Plugins.TLibs.Interface.LoaderInterface;
import net.tfminecraft.trialrooms.environment.spawner.Spawner;

public class SpawnerLoader implements LoaderInterface{
	static HashMap<String, Spawner> oList = new HashMap<>();
	public static void clear() {
		oList.clear();
	}
	public static HashMap<String, Spawner> get() {
		return oList;
	}
	public static Spawner getByString(String id) {
		if(oList.containsKey(id)) return oList.get(id);
		return null;
	}
    public static Spawner getByBlock(Block b) {
        for(Spawner s : oList.values()){
            if(s.checkBlock(b)) return s;
        }
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
        Set<String> set = config.getKeys(false);

		List<String> list = new ArrayList<String>(set);
		
		for(String key : list) {
			Spawner o = new Spawner(key, config.getConfigurationSection(key));
			oList.put(key, o);
		}
	}
}
