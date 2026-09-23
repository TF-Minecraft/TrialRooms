package net.tfminecraft.trialrooms.cache;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.trialrooms.environment.entrance.Conversion;

public class Cache {
    public static String editTool;


    public static double commonChance;
    public static double uncommonChance;
    public static double rareChance;
    public static double epicChance;
    public static double legendaryChance;

    public static double commonScore    = -1.0;
    public static double uncommonScore  =  0.0;
    public static double rareScore      =  1.0;
    public static double epicScore      =  2.0;
    public static double legendaryScore =  3.0;

    // model selection
    public static String rarityWeightModel; // "LINEAR", "EXP", or "LOGISTIC"

    // linear (existing) — keep for backwards compatibility
    public static double rarityBiasPerLevel;
    public static double rarityBiasExponent;
    public static double rarityMultiplierMin;
    public static double rarityMultiplierMax;

    // exponential model
    public static double rarityExpGrowthPerLevel; // per-level growth factor

    // logistic model
    public static double rarityLogisticMidLevel;  // mid point (e.g., 30.0)
    public static double rarityLogisticSlope;     // base slope, scaled by score
    public static double rarityLogisticLowMult;   // multiplier near level 0
    public static double rarityLogisticHighMult;  // multiplier at high level

    // jitter
    public static double jitterMax;


    public static boolean debug;

    public static String spawnerKey;
    public static String mobKey;

    public static int lootAmount;

    public static double damagePerLevel;
    public static double healthPerLevel;

    public static List<Conversion> conversions = new ArrayList<>();

    public static double getConversionAmount(ItemStack i) {
        for(Conversion c : conversions) {
            if(!c.match(i)) continue;
            return c.getAmount();
        }
        return 0.0;
    }

    public static double MOB_KEY_BASE_CHANCE;
    public static double MOB_KEY_CHANCE_PER_LEVEL;
    public static double MOB_KEY_MAX_CHANCE;
}
