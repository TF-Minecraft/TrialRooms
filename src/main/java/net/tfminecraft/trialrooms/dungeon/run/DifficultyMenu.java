package net.tfminecraft.trialrooms.dungeon.run;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.tfminecraft.trialrooms.dungeon.load.RecipeLoader;
import net.tfminecraft.trialrooms.dungeon.locker.ClickMenu;

/**
 * The gate's difficulty choice: one button per difficulty, with its tier, how much tougher the
 * enemies are and what gear it is meant for. Locked ones say what opens them.
 */
public final class DifficultyMenu implements ClickMenu, InventoryHolder {

    private static final Material[] ICONS = {Material.IRON_SWORD, Material.DIAMOND_SWORD, Material.NETHERITE_SWORD};

    private final RunManager runs;
    private final Plugin plugin;
    private final Player player;
    private final GateStore.Gate gate;
    private final RecipeLoader.Entry entry;
    private final Inventory inventory;
    /** The hardcore toggle's slot (bottom row, middle). */
    static final int HARDCORE = 22;
    private boolean hardcore;

    public DifficultyMenu(RunManager runs, Plugin plugin, Player player, GateStore.Gate gate, RecipeLoader.Entry entry) {
        this.runs = runs;
        this.plugin = plugin;
        this.player = player;
        this.gate = gate;
        this.entry = entry;
        this.inventory = Bukkit.createInventory(this, 27, Component.text("Choose a difficulty"));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** The slot of the i-th difficulty: centred in the middle row, a gap between each. */
    int slot(int i) {
        int n = Math.min(entry.difficulties().size(), 5);
        return 13 - (n - 1) + 2 * i;
    }

    public void open() {
        List<Difficulty> ds = entry.difficulties();
        for (int i = 0; i < ds.size() && i < 5; i++) {
            Difficulty d = ds.get(i);
            boolean open = runs.unlocked(player, gate.recipe(), d);
            List<String> lore = new ArrayList<>();
            lore.add("Tier " + d.tier() + ": loot of tier " + d.tier());
            if (d.health() != 1 || d.damage() != 1) {
                lore.add("Enemies: x" + d.health() + " health, x" + d.damage() + " damage");
            }
            if (!d.advice().isBlank()) {
                lore.add(d.advice());
            }
            List<WeeklyAffix> weekly = runs.weeklyAffixes(gate.recipe(), d.tier());
            if (!weekly.isEmpty()) {
                lore.add("This week: " + String.join(", ", weekly.stream().map(WeeklyAffix::title).toList()));
            }
            double par = entry.rules().with(d).par();
            if (par > 0) {
                lore.add("Par: " + Records.time((long) (par * 60_000)) + " (beat it for a better vault)");
            }
            Records records = runs.records();
            var best = records == null ? java.util.Map.<Integer, Records.Clear>of() : records.best(gate.recipe(), d.id());
            if (!best.isEmpty()) {
                lore.add("Records: " + best.entrySet().stream()
                        .map(e -> Records.time(e.getValue().millis()) + " " + RunManager.size(e.getKey()))
                        .collect(java.util.stream.Collectors.joining(" · ")));
            }
            if (!open) {
                Difficulty needs = entry.difficulty(d.needs());
                lore.add("Opens once you have won " + (needs == null ? d.needs() : needs.name()) + " here.");
            }
            inventory.setItem(slot(i), button(open ? ICONS[Math.min(i, ICONS.length - 1)] : Material.BARRIER, d.name(), lore));
        }
        inventory.setItem(HARDCORE, button(hardcore ? Material.RED_DYE : Material.GRAY_DYE,
                "Hardcore: " + (hardcore ? "on" : "off"), List.of("What you find is yours only once you reach the vault.",
                        "Fall, or leave, and it is lost; it pays more for the risk.", "Click to turn it " + (hardcore ? "off." : "on."))));
        player.openInventory(inventory);
    }

    boolean hardcore() {
        return hardcore;
    }

    private static ItemStack button(Material type, String name, List<String> lore) {
        ItemStack it = new ItemStack(type);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>();
        lore.forEach(l -> lines.add(Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.lore(lines);
        it.setItemMeta(meta);
        return it;
    }

    /** Whether the player is still by the same gate (it may have been removed, or they carried off). */
    boolean stillAtGate() {
        var world = Bukkit.getWorld(gate.world());
        if (!player.isOnline() || world == null || !player.getWorld().equals(world)) {
            return false;
        }
        var block = world.getBlockAt(gate.x(), gate.y(), gate.z());
        return gate.equals(runs.gates().at(block))
                && player.getLocation().distance(block.getLocation().add(0.5, 0.5, 0.5)) <= REACH;
    }

    /** How far from the gate a choice still counts. */
    static final double REACH = 8;

    @Override
    public void click(int slot, boolean shift) {
        if (slot == HARDCORE) {
            hardcore = !hardcore;
            open(); // (redrawn in place)
            return;
        }
        List<Difficulty> ds = entry.difficulties();
        for (int i = 0; i < ds.size() && i < 5; i++) {
            if (slot(i) == slot) {
                Difficulty d = ds.get(i);
                if (!runs.unlocked(player, gate.recipe(), d)) {
                    player.sendMessage("§c" + d.name() + " is not open for you yet.");
                    return;
                }
                // (closing a screen inside its own click is left to the next tick)
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.closeInventory();
                    if (stillAtGate()) {
                        runs.useGate(player, gate, d.id(), hardcore);
                    } else if (player.isOnline()) {
                        player.sendMessage("§cYou are no longer at the gate.");
                    }
                });
                return;
            }
        }
    }
}
