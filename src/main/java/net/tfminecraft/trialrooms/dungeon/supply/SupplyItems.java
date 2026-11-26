package net.tfminecraft.trialrooms.dungeon.supply;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Supply items: made from a supply's look, told apart by the supply id they carry. */
public final class SupplyItems {

    /** Every supply item carries its supply id under this key. */
    public static final NamespacedKey KEY = NamespacedKey.fromString("trialrooms:dungeon_supply");

    /** Sets an item model (swapped in tests: MockBukkit has none). */
    static java.util.function.BiConsumer<ItemMeta, NamespacedKey> itemModel = ItemMeta::setItemModel;

    private SupplyItems() {
    }

    /** The supply id an item carries, or null for anything else. */
    public static String idOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
    }

    /** A stack of a supply (amount clamped to 1..its stack size). */
    public static ItemStack make(Supply s, int amount) {
        Material m = Material.matchMaterial(s.look().material().toUpperCase(Locale.ROOT));
        ItemStack it = new ItemStack(m == null || m.isAir() || !m.isItem() ? Material.PAPER : m,
                Math.max(1, Math.min(amount, s.stack())));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(s.name(), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore(s).stream().map(l -> (Component) Component.text(l, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)).toList());
        if (s.look().modelData() > 0) {
            meta.setCustomModelData(s.look().modelData());
        }
        NamespacedKey model = s.look().itemModel() == null ? null : NamespacedKey.fromString(s.look().itemModel());
        if (model != null) {
            itemModel.accept(meta, model);
        }
        meta.setMaxStackSize(s.stack());
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, s.id());
        it.setItemMeta(meta);
        return it;
    }

    /** What a supply does, as players read it. */
    public static List<String> lore(Supply s) {
        List<String> out = new ArrayList<>();
        int pct = (int) Math.round(s.amount() * 100);
        switch (s.use()) {
            case HEALTH -> out.add("Heals " + pct + "% of your health at once.");
            case MANA -> out.add("Restores " + pct + "% of your mana at once.");
            case BANDAGE -> {
                out.add("Heals " + pct + "% of your health over " + s.seconds() + " seconds.");
                out.add("Only out of a fight; taking damage stops it.");
            }
            case REVIVE -> out.add("Brings a downed ally within " + fmt(s.radius()) + " blocks back up at once.");
            default -> out.add( // WARD
                    "Stops the next slowing or freezing effect, until your next fight is over.");
        }
        if (s.cooldown() > 0) {
            out.add("Another like it waits " + s.cooldown() + " seconds.");
        }
        out.add("Dungeon supply: works only in dungeon runs.");
        return out;
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
