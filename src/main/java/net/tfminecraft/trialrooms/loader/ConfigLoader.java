package net.tfminecraft.trialrooms.loader;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.trialrooms.cache.Cache;
import net.tfminecraft.trialrooms.environment.entrance.Conversion;

public class ConfigLoader {
    public void loadConfig(File configFile) {
		FileConfiguration config = new YamlConfiguration();
        try {
        	config.load(configFile);
        } catch (IOException | InvalidConfigurationException e) {
            e.printStackTrace();
        }
        Cache.debug = config.getBoolean("debug", false);

        Cache.editTool = config.getString("edit-tool", "v.blaze_rod");

        Cache.rarityWeightModel      = config.getString("rarity-weight-model", "EXP").toUpperCase(Locale.ROOT);

        // linear (legacy) defaults (kept)
        Cache.rarityBiasPerLevel     = config.getDouble("rarity-bias-per-level", 0.06);
        Cache.rarityBiasExponent     = config.getDouble("rarity-bias-exponent", 1.4);
        Cache.rarityMultiplierMin    = config.getDouble("rarity-multiplier-min", 0.0);
        double rawMax = config.getDouble("rarity-multiplier-max", 1000.0);
        Cache.rarityMultiplierMax = (rawMax <= 0.0) ? Double.POSITIVE_INFINITY : rawMax;

        // exponential
        Cache.rarityExpGrowthPerLevel = config.getDouble("rarity-exp-growth-per-level", 0.06);

        // logistic
        Cache.rarityLogisticMidLevel  = config.getDouble("rarity-logistic-mid-level", 30.0);
        Cache.rarityLogisticSlope     = config.getDouble("rarity-logistic-slope", 0.12);
        Cache.rarityLogisticLowMult   = config.getDouble("rarity-logistic-low-mult", 0.2);
        Cache.rarityLogisticHighMult  = config.getDouble("rarity-logistic-high-mult", 200.0);

        // jitter
        Cache.jitterMax = Math.max(0.0, config.getDouble("jitter-max", 0.02));

        Cache.commonChance = config.getDouble("common-chance", 70.0);
        Cache.uncommonChance = config.getDouble("uncommon-chance", 20.0);
        Cache.rareChance = config.getDouble("rare-chance", 7.0);
        Cache.epicChance = config.getDouble("epic-chance", 2.8);
        Cache.legendaryChance = config.getDouble("legendary-chance", 0.2);

        Cache.spawnerKey = config.getString("spawner-key", "v.tripwire_hook");
        Cache.mobKey = config.getString("mob-key", "v.tripwire_hook");

        Cache.lootAmount = config.getInt("max-loot", 24);

        Cache.damagePerLevel = config.getDouble("damage-per-level", 0.5);
        Cache.healthPerLevel = config.getDouble("health-per-level", 10.0);

        Cache.MOB_KEY_BASE_CHANCE = config.getDouble("mob-key-base-chance", 0.02);
        Cache.MOB_KEY_CHANCE_PER_LEVEL = config.getDouble("mob-key-chance-per-level", 0.0015);
        Cache.MOB_KEY_MAX_CHANCE = config.getDouble("mob-key-max-chance", 0.25);

        if(config.contains("conversions")) {
            for(String s : config.getStringList("conversions")) {
                Cache.conversions.add(new Conversion(s));
            }
        }
	}
}
