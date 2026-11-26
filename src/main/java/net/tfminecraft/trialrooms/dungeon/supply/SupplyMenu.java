package net.tfminecraft.trialrooms.dungeon.supply;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.tfminecraft.trialrooms.dungeon.locker.ClickMenu;
import net.tfminecraft.trialrooms.dungeon.locker.Locker;
import net.tfminecraft.trialrooms.dungeon.locker.LockerService;

/**
 * The supply screen: buy supplies with dungeon marks (second row) and choose what the pouch takes
 * into the next run (fourth row). The pouch has a few slots; each holds one stack of one supply.
 */
public final class SupplyMenu implements InventoryHolder, ClickMenu {

    static final int INFO = 4;
    static final int SHOP = 9;
    static final int POUCH = 27;
    static final int ROW = 9;

    private final LockerService lockers;
    private final Player player;
    private final Supplier<SupplySettings> settings;
    private final Inventory inventory;
    private List<Supply> shown = List.of();

    public SupplyMenu(LockerService lockers, Player player, Supplier<SupplySettings> settings) {
        this.lockers = lockers;
        this.player = player;
        this.settings = settings;
        this.inventory = Bukkit.createInventory(this, 54, Component.text("Dungeon supplies"));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Pouch slots the packed supplies take: one per stack started. */
    public static int slots(Locker l, SupplySettings s) {
        int used = 0;
        for (var e : l.packed().entrySet()) {
            Supply supply = s.supply(e.getKey());
            used += supply == null ? 0 : (e.getValue() + supply.stack() - 1) / supply.stack();
        }
        return used;
    }

    /** Opens it; false when the player has no usable locker. */
    public boolean open() {
        if (lockers.locker(player) == null) {
            player.sendMessage("§cYour dungeon locker can't be opened right now"
                    + " (play a character, or ask staff if this keeps happening).");
            return false;
        }
        render();
        player.openInventory(inventory);
        return true;
    }

    void render() {
        inventory.clear();
        Locker l = lockers.locker(player);
        if (l == null) {
            return;
        }
        SupplySettings s = settings.get();
        shown = new ArrayList<>(s.items().values()).subList(0, Math.min(ROW, s.items().size()));
        inventory.setItem(INFO, button(Material.BUNDLE, "Supply pouch: " + slots(l, s) + "/" + s.pouch() + " slots",
                "Dungeon marks: " + l.marks(),
                "Packed supplies go with you into your next run;",
                "what you don't use comes back here after it.",
                "Nothing heals on its own in a run: healers, supplies and the rest room's fire do."));
        for (int i = 0; i < shown.size(); i++) {
            Supply supply = shown.get(i);
            ItemStack buy = SupplyItems.make(supply, 1);
            List<Component> lore = new ArrayList<>(buy.lore());
            lore.add(line("Owned: " + l.supplies(supply.id()) + (l.out(supply.id()) > 0
                    ? " (" + l.out(supply.id()) + " in your run)" : "")));
            lore.add(line("Click: buy one for " + supply.price() + " mark" + (supply.price() == 1 ? "" : "s")));
            lore.add(line("Shift-click: buy five"));
            if (!supply.kits().isEmpty()) {
                lore.add(line("Sold for: " + String.join(", ", supply.kits())));
            }
            buy.lore(lore);
            inventory.setItem(SHOP + i, buy);
            int packed = l.packed(supply.id());
            ItemStack pack = packed > 0 ? SupplyItems.make(supply, Math.min(packed, supply.stack()))
                    : button(Material.GRAY_STAINED_GLASS_PANE, supply.name() + ": not packed");
            ItemMeta meta = pack.getItemMeta();
            meta.setMaxStackSize(Math.max(1, Math.min(99, packed)));
            pack.setItemMeta(meta);
            pack.setAmount(Math.max(1, Math.min(99, packed)));
            pack.lore(List.of(line("Packed: " + packed + " of " + l.supplies(supply.id())),
                    line("Click: pack one more"), line("Shift-click: take one out")));
            inventory.setItem(POUCH + i, pack);
        }
    }

    @Override
    public void click(int slot, boolean shift) {
        Locker l = lockers.locker(player);
        if (l == null) {
            return;
        }
        if (slot >= SHOP && slot < SHOP + shown.size()) {
            buy(l, shown.get(slot - SHOP), shift ? 5 : 1);
        } else if (slot >= POUCH && slot < POUCH + shown.size()) {
            pack(l, shown.get(slot - POUCH), shift ? -1 : 1);
        }
        render();
    }

    private void buy(Locker l, Supply s, int count) {
        int cost = s.price() * count;
        if (l.marks() < cost) {
            player.sendMessage("§c" + count + " " + s.name() + " cost " + cost + " marks; you have " + l.marks() + ".");
            return;
        }
        l.marks(l.marks() - cost);
        l.supplies(s.id(), l.supplies(s.id()) + count);
        int before = l.packed(s.id());
        while (l.packed(s.id()) < l.supplies(s.id()) && fits(l, s)) { // bought supplies are packed while there is room
            l.packed(s.id(), l.packed(s.id()) + 1);
        }
        if (lockers.saveLocker(player, l)) {
            int packed = l.packed(s.id()) - before;
            player.sendMessage("§aBought " + count + " " + s.name() + " for " + cost + " marks"
                    + (packed > 0 ? " (" + packed + " packed)." : "."));
        } else {
            l.marks(l.marks() + cost);
            l.supplies(s.id(), l.supplies(s.id()) - count);
            l.packed(s.id(), before);
        }
    }

    /** Whether one more of a supply still fits the pouch. */
    private boolean fits(Locker l, Supply s) {
        int now = l.packed(s.id());
        boolean newStack = now % s.stack() == 0;
        return !newStack || slots(l, settings.get()) < settings.get().pouch();
    }

    private void pack(Locker l, Supply s, int delta) {
        if (!l.out().isEmpty()) {
            player.sendMessage("§cYour supplies are out in a run; pack again once it is over.");
            return;
        }
        int now = l.packed(s.id());
        if (delta > 0 && now >= l.supplies(s.id())) {
            player.sendMessage("§7You have no more " + s.name() + " to pack; buy some first.");
            return;
        }
        if (delta > 0 && !fits(l, s)) {
            player.sendMessage("§7The pouch is full: take something out first.");
            return;
        }
        l.packed(s.id(), now + delta);
        if (!lockers.saveLocker(player, l)) {
            l.packed(s.id(), now);
        }
    }

    private static Component line(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack button(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>();
        for (String s : lore) {
            lines.add(line(s));
        }
        meta.lore(lines);
        it.setItemMeta(meta);
        return it;
    }
}
