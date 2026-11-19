package net.tfminecraft.trialrooms.dungeon;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout;
import net.tfminecraft.trialrooms.dungeon.generator.LayoutMap;

/**
 * Staff commands under {@code /tr dungeon}: {@code map}, {@code preview}, {@code clear} and
 * {@code reload}. Works from the console too, except that only a player is teleported to a preview.
 */
public final class DungeonCommand {

    static final String USAGE = "§eUsage: /tr dungeon <map|preview> <recipe> [seed] | clear | reload";
    static final List<String> SUBCOMMANDS = List.of("map", "preview", "clear", "reload");

    private final DungeonModule module;

    public DungeonCommand(DungeonModule module) {
        this.module = module;
    }

    /** Handles the arguments after {@code dungeon}. */
    public void run(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "map", "preview" -> generate(sender, sub.equals("preview"), args);
            case "clear" -> sender.sendMessage("§aCleared " + module.clearPreviews() + " dungeon previews.");
            case "reload" -> {
                List<String> problems = module.reload();
                sender.sendMessage("§aDungeons reloaded: " + module.kits().size() + " kits, "
                        + module.recipes().size() + " recipes, " + problems.size() + " problems.");
                problems.forEach(p -> sender.sendMessage("§c- " + p));
            }
            default -> sender.sendMessage(USAGE);
        }
    }

    private void generate(CommandSender sender, boolean preview, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(USAGE + " §7(recipes: " + String.join(", ", module.recipes().keySet()) + ")");
            return;
        }
        long seed;
        try {
            seed = args.length > 2 ? Long.parseLong(args[2]) : ThreadLocalRandom.current().nextLong();
        } catch (NumberFormatException e) {
            sender.sendMessage("§cThe seed must be a whole number.");
            return;
        }
        DungeonLayout layout;
        try {
            layout = module.generate(args[1], seed);
        } catch (RuntimeException e) {
            sender.sendMessage("§c" + e.getMessage());
            return;
        }
        sender.sendMessage("§a" + layout.recipeId() + " seed " + seed + ": " + layout.rooms().size()
                + " rooms (attempt " + (layout.attempt() + 1) + ")");
        for (String line : LayoutMap.render(layout)) {
            sender.sendMessage("§f" + line);
        }
        if (!preview) {
            return;
        }
        sender.sendMessage("§7Building the preview...");
        module.preview(layout).whenComplete((arrival, error) -> {
            if (error != null) {
                sender.sendMessage("§cPreview failed: " + error);
                return;
            }
            sender.sendMessage("§aPreview built at " + arrival.getBlockX() + " " + arrival.getBlockY() + " "
                    + arrival.getBlockZ() + " in " + arrival.getWorld().getName() + ".");
            if (sender instanceof Player player) {
                player.teleport(arrival);
            }
        });
    }

    /** Tab completion for the arguments after {@code dungeon}. */
    public List<String> complete(String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("map") || args[0].equalsIgnoreCase("preview"))) {
            return module.recipes().keySet().stream().filter(s -> s.startsWith(args[1])).toList();
        }
        return List.of();
    }
}
