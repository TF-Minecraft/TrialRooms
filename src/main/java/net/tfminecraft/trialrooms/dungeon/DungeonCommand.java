package net.tfminecraft.trialrooms.dungeon;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout;
import net.tfminecraft.trialrooms.dungeon.generator.LayoutMap;
import net.tfminecraft.trialrooms.dungeon.locker.LockerMenu;
import net.tfminecraft.trialrooms.dungeon.locker.LockerService;
import net.tfminecraft.trialrooms.dungeon.loot.DungeonLoot;
import net.tfminecraft.trialrooms.dungeon.run.DungeonRun;
import net.tfminecraft.trialrooms.dungeon.run.GateStore;
import net.tfminecraft.trialrooms.dungeon.run.RunManager;

/**
 * Commands under {@code /tr dungeon}. Staff: {@code map}, {@code preview}, {@code clear},
 * {@code reload} and the run tools; they work from the console too, except that only a player is
 * teleported to a preview. Players: {@code leave}, {@code ready} and {@code locker}.
 */
public final class DungeonCommand {

    static final String USAGE = "§eUsage: /tr dungeon <map|preview|start> <recipe> [seed] | clear | reload | runs"
            + " | end <run> | rooms <run> | allies | gate <add <recipe>|remove|list> | leave | ready"
            + " | locker [give <player>] | loot <player> [tier] [rarity|any] [item|gem] | marks <player> <amount>";
    static final List<String> SUBCOMMANDS = List.of("map", "preview", "start", "clear", "reload", "runs", "end",
            "rooms", "allies", "gate", "leave", "ready", "locker", "loot", "marks");
    /** What players without the staff permission may use. */
    public static final List<String> PLAYER_SUBCOMMANDS = List.of("leave", "ready", "locker");

    private final DungeonModule module;

    public DungeonCommand(DungeonModule module) {
        this.module = module;
    }

    /** Handles the arguments after {@code dungeon}. */
    public void run(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "map", "preview" -> generate(sender, sub.equals("preview"), args);
            case "start" -> start(sender, args);
            case "runs" -> runs(sender);
            case "end" -> end(sender, args);
            case "rooms" -> rooms(sender, args);
            case "allies" -> sender.sendMessage(module.allyFilter() == null ? "§cThe ally filter is not running."
                    : "§aAlly filter: " + module.allyFilter().report());
            case "gate" -> gate(sender, args);
            case "leave" -> leave(sender);
            case "ready" -> ready(sender);
            case "locker" -> locker(sender, args);
            case "loot" -> loot(sender, args);
            case "marks" -> marks(sender, args);
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

    private void start(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p) || args.length < 2) {
            sender.sendMessage("§eUsage (in game): /tr dungeon start <recipe> [seed] [difficulty]");
            return;
        }
        try {
            long seed = args.length > 2 ? Long.parseLong(args[2]) : ThreadLocalRandom.current().nextLong();
            module.runs().startRun(p, args[1], seed, args.length > 3 ? args[3] : null);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cThe seed must be a whole number.");
        }
    }

    private void runs(CommandSender sender) {
        List<RunManager.ActiveRun> all = module.runs().runs();
        sender.sendMessage("§a" + all.size() + " dungeon runs.");
        for (RunManager.ActiveRun ar : all) {
            DungeonRun r = ar.run();
            sender.sendMessage("§f" + r.id().toString().substring(0, 8) + " §7" + r.recipeId() + " " + r.state()
                    + ", players " + r.present().size() + ", lives " + r.lives() + ", rooms cleared " + r.roomsCleared());
            for (DungeonRun.Member m : r.members()) {
                sender.sendMessage("§7  member " + m.id() + " " + m.status() + (m.online() ? " online" : " away")
                        + " room " + m.room());
            }
        }
    }

    private void end(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§eUsage: /tr dungeon end <run id start>");
            return;
        }
        for (RunManager.ActiveRun ar : module.runs().runs()) {
            if (ar.run().id().toString().startsWith(args[1])) {
                module.runs().end(ar, true, "§cA keeper of the dungeons has closed this run. Your key is returned.");
                sender.sendMessage("§aEnded run " + args[1] + ".");
                return;
            }
        }
        sender.sendMessage("§cNo run starts with " + args[1] + ".");
    }

    /** Each room of a run: type, state, entry and template, for staff and tests. */
    private void rooms(CommandSender sender, String[] args) {
        for (RunManager.ActiveRun ar : module.runs().runs()) {
            if (args.length > 1 && ar.run().id().toString().startsWith(args[1])) {
                DungeonRun r = ar.run();
                for (int i = 0; i < r.layout().rooms().size(); i++) {
                    double[] c = ar.geometry().centre(i);
                    sender.sendMessage("§7room " + i + " " + r.layout().rooms().get(i).node().type() + " " + r.room(i)
                            + " at " + (int) c[0] + " " + (int) c[1] + " " + (int) c[2] + " ("
                            + r.layout().rooms().get(i).template().id() + ")"
                            + (ar.event(i) == null ? "" : " [" + ar.event(i).id() + "]"));
                }
                return;
            }
        }
        sender.sendMessage("§eUsage: /tr dungeon rooms <run id start>");
    }

    private void gate(CommandSender sender, String[] args) {
        String what = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        if (what.equals("list")) {
            List<GateStore.Gate> gates = module.runs().gates().all();
            sender.sendMessage("§a" + gates.size() + " dungeon gates.");
            gates.forEach(g -> sender.sendMessage("§f" + g.world() + " " + g.x() + " " + g.y() + " " + g.z()
                    + " §7-> " + g.recipe()));
            return;
        }
        if (!(sender instanceof Player p) || !(what.equals("add") && args.length > 2 || what.equals("remove"))) {
            sender.sendMessage("§eUsage (in game, looking at a block): /tr dungeon gate add <recipe> | remove | list");
            return;
        }
        Block block = p.getTargetBlockExact(6);
        if (block == null) {
            sender.sendMessage("§cLook at the gate block (within 6 blocks).");
        } else if (what.equals("remove")) {
            sender.sendMessage(module.runs().gates().remove(block) ? "§aGate removed." : "§cThat block is no gate.");
        } else if (!module.recipes().containsKey(args[2])) {
            sender.sendMessage("§cUnknown recipe: " + args[2]);
        } else {
            module.runs().gates().add(block, args[2]);
            sender.sendMessage("§aThat block is now a gate to " + args[2] + ".");
        }
    }

    private void leave(CommandSender sender) {
        if (sender instanceof Player p && module.runs().leave(p)) {
            p.sendMessage("§7You leave the dungeon.");
        } else if (sender instanceof Player p && module.runs().unqueue(p)) {
            p.sendMessage("§7Your party stops waiting for a dungeon.");
        } else {
            sender.sendMessage("§cYou are not in a dungeon.");
        }
    }

    private void ready(CommandSender sender) {
        if (sender instanceof Player p && module.runs().ready(p)) {
            p.sendMessage("§aYou are ready.");
        } else {
            sender.sendMessage("§cThere is nothing to get ready for: the doors are already open, or you are not in a dungeon.");
        }
    }

    private void locker(CommandSender sender, String[] args) {
        LockerService lockers = module.runs().lockers();
        if (lockers == null) {
            sender.sendMessage("§cDungeon lockers are off.");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("give")) {
            give(sender, lockers, args);
            return;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("§cOnly a player has a locker.");
            return;
        }
        new LockerMenu(lockers, p, module.runs()::inLobby, module.rewards()).open();
    }

    /** Staff: files a copy of the item in hand in a player's locker as a dungeon item (until loot drops them). */
    private void give(CommandSender sender, LockerService lockers, String[] args) {
        if (!sender.hasPermission(RunManager.STAFF_PERMISSION)) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return;
        }
        if (!(sender instanceof Player staff) || staff.getInventory().getItemInMainHand().getType() == Material.AIR) {
            sender.sendMessage("§cHold the item to give.");
            return;
        }
        Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : staff;
        if (target == null) {
            sender.sendMessage("§cNo player " + args[2] + " is online.");
            return;
        }
        String id = lockers.add(target, staff.getInventory().getItemInMainHand());
        sender.sendMessage(id == null ? "§c" + target.getName() + " has no usable locker (no character played?)."
                : "§aA copy is in " + target.getName() + "'s dungeon locker.");
    }

    /** Staff: rolls a dungeon item for a player's class and files it in their locker (until drops come). */
    private void loot(CommandSender sender, String[] args) {
        DungeonLoot loot = module.loot();
        LockerService lockers = module.runs().lockers();
        if (loot == null || lockers == null) {
            sender.sendMessage("§cDungeon loot is off (it needs MMOItems and AdvancedCrafting).");
            return;
        }
        Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
        if (target == null) {
            sender.sendMessage("§cUsage: /tr dungeon loot <player> [tier] [rarity|any] [item]");
            return;
        }
        int tier;
        try {
            tier = args.length >= 3 ? Integer.parseInt(args[2]) : 1;
        } catch (NumberFormatException e) {
            sender.sendMessage("§cThe tier must be a whole number.");
            return;
        }
        String rarity = args.length >= 4 && !args[3].equalsIgnoreCase("any") ? args[3] : null;
        boolean gem = args.length >= 5 && args[4].equalsIgnoreCase("gem");
        var rules = module.rewardRules();
        DungeonLoot.Rolled r = gem && rules != null ? loot.gem(tier, rarity, rules.gemPool(), rules.gemTemplate())
                : loot.roll(target, tier, rarity, args.length >= 5 ? args[4] : null);
        if (r.item() == null) {
            sender.sendMessage("§c" + r.problem());
            return;
        }
        String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(r.item().effectiveName());
        sender.sendMessage(lockers.add(target, r.item()) == null
                ? "§c" + target.getName() + " has no usable locker (no character played?)."
                : "§a" + name + " is in " + target.getName() + "'s dungeon locker.");
    }

    /** Staff: gives (or with a negative amount takes) dungeon marks, for testing the shop. */
    private void marks(CommandSender sender, String[] args) {
        LockerService lockers = module.runs().lockers();
        Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[1]) : null;
        var locker = target == null || lockers == null ? null : lockers.locker(target);
        if (locker == null) {
            sender.sendMessage("§cUsage: /tr dungeon marks <player> <amount> (the player needs a usable locker)");
            return;
        }
        try {
            locker.marks(locker.marks() + Integer.parseInt(args[2]));
        } catch (NumberFormatException e) {
            sender.sendMessage("§cThe amount must be a whole number.");
            return;
        }
        lockers.saveLocker(target, locker);
        sender.sendMessage("§a" + target.getName() + " has " + locker.marks() + " dungeon marks.");
    }

    /** Tab completion for the arguments after {@code dungeon}. */
    public List<String> complete(String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("locker")) {
            return "give".startsWith(args[1]) ? List.of("give") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("gate")) {
            return List.of("add", "remove", "list").stream().filter(s -> s.startsWith(args[1])).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("gate") && args[1].equalsIgnoreCase("add")) {
            return module.recipes().keySet().stream().filter(s -> s.startsWith(args[2])).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("map") || args[0].equalsIgnoreCase("preview")
                || args[0].equalsIgnoreCase("start"))) {
            return module.recipes().keySet().stream().filter(s -> s.startsWith(args[1])).toList();
        }
        return List.of();
    }
}
