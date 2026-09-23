package net.tfminecraft.trialrooms.environment.spawner;

import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.subapi.BlockChecker;

public class Spawner {
    private String id;
    private String block;

    public Spawner(String key, ConfigurationSection config) {
        id = key;
        block = config.getString("block");
    }

    public String getId() {
        return id;
    }
    public String getBlock() {
        return block;
    }

    public boolean checkBlock(Block b) {
        BlockChecker checker = TLibs.getBlockAPI().getChecker();
        return checker.checkBlock(b, block);
    }
}
