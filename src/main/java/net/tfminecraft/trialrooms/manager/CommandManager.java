package net.tfminecraft.trialrooms.manager;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.tfminecraft.trialrooms.environment.spawner.Spawner;

public class CommandManager implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        if (!player.hasPermission("trialrooms.admin")) {
            player.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if(args[0].equalsIgnoreCase("resetcooldowns")) {
            SpawnerManager.resetCooldowns();
            player.sendMessage("§aReset all cooldowns");
        }

        player.sendMessage("§cUnknown subcommand.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return Collections.emptyList();

        return Collections.emptyList();
    }
    
}
