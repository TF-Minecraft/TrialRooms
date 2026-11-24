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
import net.tfminecraft.trialrooms.dungeon.locker.ClickMenu;
import net.tfminecraft.trialrooms.dungeon.run.RunRewards.Find;

/**
 * The finds screens. Every click is the screen's own; nothing on it can be taken.
 * <ul>
 * <li>{@link Mode#VIEW}: what a player carries (lost if they fall) and what is banked, each with
 * its value, and the run's loot rules;</li>
 * <li>{@link Mode#EXIT}: a treasure room's way out: what comes home anyway, and the finds at risk to
 * pick from, up to what the way out allows; then leave;</li>
 * <li>{@link Mode#INSURE}: in the lobby of a run with gear at stake, the worn pieces and what
 * insuring each costs (one piece; picked again, it is cancelled and the marks come back).</li>
 * </ul>
 */
public final class FindsMenu implements ClickMenu, InventoryHolder {

    public enum Mode { VIEW, EXIT, INSURE }

    static final int INFO = 4;
    static final int FIRST = 9;
    static final int SHOWN = 36;
    static final int CANCEL = 45;
    static final int CONFIRM = 53;

    private final RunManager runs;
    private final Plugin plugin;
    private final Player player;
    private final Mode mode;
    private final Inventory inventory;
    /** Picked finds at risk (EXIT), by index, in the order picked. */
    private final List<Integer> picks = new ArrayList<>();
    private RunFinds.Offer offer;
    private List<RunFinds.Insurable> pieces = List.of();

    public FindsMenu(RunManager runs, Plugin plugin, Player player, Mode mode) {
        this.runs = runs;
        this.plugin = plugin;
        this.player = player;
        this.mode = mode;
        this.inventory = Bukkit.createInventory(this, 54, Component.text(mode == Mode.VIEW ? "Your finds"
                : mode == Mode.EXIT ? "The way out: pick your finds" : "Insure a worn piece"));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    List<Integer> picks() {
        return picks;
    }

    public void open() {
        render();
        player.openInventory(inventory);
    }

    void render() {
        inventory.clear();
        if (mode == Mode.VIEW) {
            renderView();
        } else if (mode == Mode.EXIT) {
            renderExit();
        } else {
            renderInsure();
        }
    }

    private void renderView() {
        RunFinds.Held h = runs.heldBy(player);
        if (h == null) {
            return;
        }
        List<String> info = new ArrayList<>(runs.lootRules(player));
        info.add("Carried: value " + RunFinds.value(h.carried) + " · Banked: value " + RunFinds.value(h.banked));
        inventory.setItem(INFO, button(Material.BOOK, "Your finds", info));
        int slot = FIRST;
        for (Find f : h.carried) {
            if (slot < FIRST + SHOWN) {
                inventory.setItem(slot++, find(f, List.of("§cCarried: lost if you fall"), false));
            }
        }
        for (Find f : h.banked) {
            if (slot < FIRST + SHOWN) {
                inventory.setItem(slot++, find(f, List.of("§aBanked"), false));
            }
        }
    }

    private void renderExit() {
        offer = runs.exitOffer(player);
        if (offer == null) {
            return;
        }
        int size = offer.atRisk().size();
        picks.removeIf(i -> i >= size); // (a save point since may have banked some of them away)
        int picked = picks.stream().mapToInt(i -> RunFinds.Offer.weight(offer.atRisk().get(i))).sum();
        inventory.setItem(INFO, button(Material.ENDER_EYE, "The way out", List.of(
                "Leave alone, now. You take home:",
                offer.safe().isEmpty() ? "· only the finds you pick" : "· your banked finds, and the finds you pick",
                "Pick finds worth up to " + offer.allowance() + " (half of what you would",
                "lose, or your best find). The rest stays behind.")));
        int slot = FIRST;
        for (Find f : offer.safe()) {
            if (slot < FIRST + SHOWN) {
                inventory.setItem(slot++, find(f, List.of("§aComes home"), false));
            }
        }
        for (int i = 0; i < offer.atRisk().size() && slot < FIRST + SHOWN; i++) {
            boolean on = picks.contains(i);
            inventory.setItem(slot++, find(offer.atRisk().get(i),
                    List.of(on ? "§bPicked: click to put it back" : "§7Click to take it"), on));
        }
        inventory.setItem(CANCEL, button(Material.BARRIER, "Stay", List.of("Close this and stay in the run")));
        inventory.setItem(CONFIRM, button(Material.LIME_DYE, "Leave now", List.of(
                "Picked: value " + picked + " of " + offer.allowance())));
    }

    private void renderInsure() {
        pieces = runs.insurable(player);
        String insured = runs.insured(player);
        inventory.setItem(INFO, button(Material.SHIELD, "Insurance", List.of(
                "Your worn dungeon gear is at stake here.",
                "Insure one piece: if you fall, it is spared.",
                "Pick it again to cancel (the marks come back).",
                "Your marks: " + Math.max(0, runs.marksOf(player)))));
        if (pieces.isEmpty()) {
            inventory.setItem(FIRST + 4, button(Material.GRAY_DYE, "Nothing worn",
                    List.of("Put on a loadout in the locker first.")));
        }
        int slot = FIRST;
        for (RunFinds.Insurable i : pieces) {
            if (slot < FIRST + SHOWN) {
                boolean on = i.id().equals(insured);
                inventory.setItem(slot++, lines(i.item().clone(), List.of("§7Value " + i.value(),
                        on ? "§bInsured: click to cancel" : "§eInsure for " + i.cost() + " marks"), on));
            }
        }
    }

    @Override
    public void click(int slot, boolean shift) {
        if (mode == Mode.EXIT) {
            clickExit(slot);
        } else if (mode == Mode.INSURE) {
            clickInsure(slot);
        } // (the view is a screen to look at)
    }

    private void clickExit(int slot) {
        if (offer == null) {
            return;
        }
        if (slot == CANCEL) {
            later(player::closeInventory);
            return;
        }
        if (slot == CONFIRM) {
            List<Integer> chosen = List.copyOf(picks);
            later(() -> {
                player.closeInventory();
                String no = runs.exitWith(player, chosen);
                if (no != null) {
                    player.sendMessage(no);
                }
            });
            return;
        }
        int i = slot - FIRST - offer.safe().size();
        if (i < 0 || i >= offer.atRisk().size()) {
            return;
        }
        if (picks.contains(i)) {
            picks.remove(Integer.valueOf(i));
        } else {
            List<Integer> more = new ArrayList<>(picks);
            more.add(i);
            if (!offer.allows(more)) {
                player.sendMessage("§cThat goes past what the way out lets you take (" + offer.allowance() + ").");
                return;
            }
            picks.add(i);
        }
        render();
    }

    private void clickInsure(int slot) {
        int i = slot - FIRST;
        if (i < 0 || i >= pieces.size()) {
            return;
        }
        player.sendMessage(runs.insure(player, pieces.get(i).id()));
        render();
    }

    /** Closing (or acting on) a screen inside its own click is left to the next tick. */
    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    private ItemStack find(Find f, List<String> extra, boolean glint) {
        ItemStack it = runs.icon(f).clone();
        List<String> lore = new ArrayList<>();
        if (f.moment() == null || it.getType() == Material.EMERALD || it.getType() == Material.PAPER) {
            lore.add("§f" + f.label());
        }
        lore.add("§7Value " + f.value());
        lore.addAll(extra);
        return lines(it, lore, glint);
    }

    /** An item with lines added under its own lore. */
    private static ItemStack lines(ItemStack it, List<String> add, boolean glint) {
        ItemMeta meta = it.getItemMeta();
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        add.forEach(l -> lore.add(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .deserialize(l).decoration(TextDecoration.ITALIC, false)));
        meta.lore(lore);
        if (glint) {
            meta.setEnchantmentGlintOverride(true);
        }
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack button(Material type, String name, List<String> lore) {
        ItemStack it = new ItemStack(type);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(l -> (Component) Component.text(l, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)).toList());
        it.setItemMeta(meta);
        return it;
    }
}
