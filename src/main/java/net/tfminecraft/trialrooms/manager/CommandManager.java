package net.tfminecraft.trialrooms.manager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.tfminecraft.trialrooms.TrialRooms;
import net.tfminecraft.trialrooms.dungeon.DungeonCommand;
import net.tfminecraft.trialrooms.environment.spawner.Spawner;

public class CommandManager implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Dungeon staff tools also work from the console.
        if (args.length > 0 && args[0].equalsIgnoreCase("dungeon")) {
            boolean leave = args.length > 1 && args[1].equalsIgnoreCase("leave");
            if (!leave && !sender.hasPermission("trialrooms.admin")) {
                sender.sendMessage("§cYou don't have permission to use this command.");
                return true;
            }
            new DungeonCommand(TrialRooms.getInstance().getDungeons())
                    .run(sender, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        if (!player.hasPermission("trialrooms.admin")) {
            player.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            player.sendMessage("§cUsage: /tr resetcooldowns | dungeon");
            return true;
        }
        if(args[0].equalsIgnoreCase("resetcooldowns")) {
            SpawnerManager.resetCooldowns();
            player.sendMessage("§aReset all cooldowns");
            return true;
        }

        player.sendMessage("§cUnknown subcommand.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission("trialrooms.admin") || args.length == 0) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("resetcooldowns", "dungeon").stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args[0].equalsIgnoreCase("dungeon")) {
            return new DungeonCommand(TrialRooms.getInstance().getDungeons())
                    .complete(Arrays.copyOfRange(args, 1, args.length));
        }
        return Collections.emptyList();
    }
    
}
