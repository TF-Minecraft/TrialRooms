package net.tfminecraft.trialrooms.dungeon.gear;

import java.util.Objects;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.trialrooms.dungeon.gear.GearSet.Kind;

/** What a dungeon item remembers about its set: which, what it counts as, and how strong it is. */
public final class GearTags {

    private static final NamespacedKey SET = key("dungeon_set");
    private static final NamespacedKey KIND = key("dungeon_kind");
    private static final NamespacedKey POWER = key("dungeon_power");
    private static final NamespacedKey SHARE = key("dungeon_share");

    /** A piece as it counts: its set, kind, power (tier times rarity) and its share of the set's trait. */
    public record Piece(String set, Kind kind, double power, double share) {

        public Piece(String set, Kind kind, double power) {
            this(set, kind, power, 1);
        }
    }

    private GearTags() {
    }

    private static NamespacedKey key(String k) {
        return Objects.requireNonNull(NamespacedKey.fromString("trialrooms:" + k));
    }

    public static void write(ItemStack item, Piece piece) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer c = meta.getPersistentDataContainer();
        c.set(SET, PersistentDataType.STRING, piece.set());
        c.set(KIND, PersistentDataType.STRING, piece.kind().name());
        c.set(POWER, PersistentDataType.DOUBLE, piece.power());
        c.set(SHARE, PersistentDataType.DOUBLE, piece.share());
        item.setItemMeta(meta);
    }

    /** The piece an item is, or null for anything that belongs to no set. */
    public static Piece read(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer c = item.getItemMeta().getPersistentDataContainer();
        String set = c.get(SET, PersistentDataType.STRING);
        return set == null ? null : new Piece(set, Kind.valueOf(c.getOrDefault(KIND, PersistentDataType.STRING, "WEAPON")),
                c.getOrDefault(POWER, PersistentDataType.DOUBLE, 1.0),
                c.getOrDefault(SHARE, PersistentDataType.DOUBLE, 1.0));
    }
}
