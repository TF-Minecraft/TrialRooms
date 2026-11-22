package net.tfminecraft.trialrooms.dungeon.locker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Puts lockers to work on players: wearing a loadout, taking it off, and putting things right after
 * a crash or a quit.
 *
 * <p>Wearing writes the locker first (worn, with the own items it replaces), then swaps the items,
 * marks the player and saves the player's file. Taking off does the reverse: the copies come out
 * (their changes kept), the own items go back, the mark goes, the player's file is saved, and then
 * the locker. After a crash the two files are compared: the wear counts as done when the player's
 * mark or a worn copy in their inventory names the locker's revision, and it is undone the normal
 * way; otherwise it never reached the player's file, and the own items it would have replaced are
 * still in their inventory, so the locker's copies of them go to quarantine, never to the player.
 */
public final class LockerService {

    /** Why a wear or take-off did not happen. */
    public enum Result {
        DONE, NO_CHARACTER, BROKEN, ALREADY_WORN, NOT_WORN, EMPTY, CURSOR, SAVE_FAILED
    }

    private record Open(String character, Locker locker) {
    }

    private final LockerStore store;
    private final ItemCodec codec;
    private final Function<Player, String> characters;
    private final Consumer<Player> saver;
    private final Logger log;
    private final NamespacedKey itemKey;
    private final NamespacedKey wornKey;
    private final NamespacedKey playerKey;
    private final Map<UUID, Open> open = new HashMap<>();

    /**
     * @param characters the id of the character a player is playing, or null
     * @param saver      saves a player's file to disk now (the server's {@code Player#saveData})
     */
    public LockerService(Plugin plugin, LockerStore store, ItemCodec codec, Function<Player, String> characters,
            Consumer<Player> saver) {
        this.store = store;
        this.codec = codec;
        this.characters = characters;
        this.saver = saver;
        this.log = plugin.getLogger();
        this.itemKey = new NamespacedKey(plugin, "dungeon_item");
        this.wornKey = new NamespacedKey(plugin, "dungeon_worn");
        this.playerKey = new NamespacedKey(plugin, "dungeon_locker");
    }

    // ---- lockers

    /** The locker of the character a player is playing; null with no character or a broken file. */
    public Locker locker(Player p) {
        String character = characters.apply(p);
        if (character == null) {
            return null;
        }
        Open o = open.get(p.getUniqueId());
        if (o != null && o.character().equals(character)) {
            return o.locker();
        }
        try {
            Locker l = store.read(p.getUniqueId(), character);
            open.put(p.getUniqueId(), new Open(character, l));
            return l;
        } catch (LockerStore.BrokenLocker e) {
            log.severe("[dungeons] " + e.getMessage());
            return null;
        }
    }

    /** Forgets a player's cached locker (every change is already on disk). */
    public void forget(Player p) {
        open.remove(p.getUniqueId());
    }

    private boolean save(Player p, Locker l) {
        return store.write(p.getUniqueId(), characters.apply(p), l);
    }

    /** Saves a locker after a loadout change; tells the player when it failed. */
    public boolean saveLocker(Player p, Locker l) {
        if (save(p, l)) {
            return true;
        }
        p.sendMessage("§cThe locker could not be saved. Tell staff if this keeps happening.");
        return false;
    }

    // ---- dungeon items

    /** The dungeon item id an item carries, or null for anything else. */
    public String idOf(ItemStack item) {
        if (empty(item)) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }

    public boolean isDungeonItem(ItemStack item) {
        return idOf(item) != null;
    }

    private long wornRevision(ItemStack item) {
        Long r = item.getItemMeta().getPersistentDataContainer().get(wornKey, PersistentDataType.LONG);
        return r == null ? -1 : r;
    }

    /** Files a copy of an item in a player's locker as a new dungeon item. Returns its id, or null. */
    public String add(Player p, ItemStack item) {
        Locker l = locker(p);
        if (l == null || item == null || item.getType() == Material.AIR) {
            return null;
        }
        String id = UUID.randomUUID().toString();
        ItemStack copy = item.clone();
        ItemMeta meta = copy.getItemMeta();
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, id);
        meta.getPersistentDataContainer().remove(wornKey);
        copy.setItemMeta(meta);
        l.put(id, codec.encode(copy));
        if (!save(p, l)) {
            l.remove(id);
            return null;
        }
        return id;
    }

    /** A locker item as an item, for showing. */
    public ItemStack show(Locker l, String id) {
        return codec.decode(l.item(id));
    }

    // ---- wearing

    /** Swaps a loadout in for the own items in its slots. */
    public Result wear(Player p, int loadout) {
        Locker l = locker(p);
        if (l == null) {
            return characters.apply(p) == null ? Result.NO_CHARACTER : Result.BROKEN;
        }
        if (l.worn() >= 0) {
            return Result.ALREADY_WORN;
        }
        if (l.loadout(loadout).isEmpty()) {
            return Result.EMPTY;
        }
        if (!empty(p.getItemOnCursor())) {
            return Result.CURSOR;
        }
        collect(p, l, false); // nothing is worn: any dungeon item in hand is a stray copy
        PlayerInventory inv = p.getInventory();
        Map<Integer, byte[]> own = new LinkedHashMap<>();
        for (int slot : l.loadout(loadout).keySet()) {
            ItemStack it = inv.getItem(slot);
            if (!empty(it)) {
                own.put(slot, codec.encode(it));
            }
        }
        l.wear(loadout, own, l.revision() + 1); // the revision this save writes
        if (!save(p, l)) {
            l.takeOff();
            return Result.SAVE_FAILED;
        }
        long rev = l.wornAt();
        for (Map.Entry<Integer, String> e : l.wornSlots().entrySet()) {
            ItemStack copy = codec.decode(l.item(e.getValue()));
            ItemMeta meta = copy.getItemMeta();
            meta.getPersistentDataContainer().set(wornKey, PersistentDataType.LONG, rev);
            copy.setItemMeta(meta);
            inv.setItem(e.getKey(), copy);
        }
        p.getPersistentDataContainer().set(playerKey, PersistentDataType.STRING, mark(p, rev));
        saver.accept(p);
        return Result.DONE;
    }

    private String mark(Player p, long rev) {
        return characters.apply(p) + ";" + rev;
    }

    /** Takes the worn loadout off: dungeon items back to the locker, own items back where they were. */
    public Result takeOff(Player p) {
        Locker l = locker(p);
        if (l == null) {
            return characters.apply(p) == null ? Result.NO_CHARACTER : Result.BROKEN;
        }
        if (l.worn() < 0) {
            return Result.NOT_WORN;
        }
        collect(p, l, true);
        PlayerInventory inv = p.getInventory();
        for (Map.Entry<Integer, byte[]> e : l.displaced().entrySet()) {
            ItemStack own = codec.decode(e.getValue());
            if (empty(inv.getItem(e.getKey()))) {
                inv.setItem(e.getKey(), own);
            } else {
                inv.addItem(own).values().forEach(left -> l.owe(codec.encode(left)));
            }
        }
        l.takeOff();
        p.getPersistentDataContainer().remove(playerKey);
        saver.accept(p);
        if (!save(p, l)) {
            // The player's file is right; the locker still says worn on disk, and the next settle
            // quarantines its copies of the own items instead of handing them out twice.
            return Result.SAVE_FAILED;
        }
        if (!l.owed().isEmpty()) {
            p.sendMessage("§eSome of your own items found no room; make space and they will come back.");
        }
        return Result.DONE;
    }

    /**
     * Takes every dungeon item out of a player's hands: inventory, cursor and crafting grid. With
     * {@code keepChanges}, the locker's worn copies take their state (wear, sockets) from what was
     * found; copies the locker did not hand out this wear are thrown away.
     */
    private void collect(Player p, Locker l, boolean keepChanges) {
        PlayerInventory inv = p.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack it = inv.getItem(slot);
            if (isDungeonItem(it)) {
                take(p, l, it, keepChanges);
                inv.setItem(slot, null);
            }
        }
        if (isDungeonItem(p.getItemOnCursor())) {
            take(p, l, p.getItemOnCursor(), keepChanges);
            p.setItemOnCursor(null);
        }
        InventoryView view = p.getOpenInventory();
        Inventory top = view.getTopInventory();
        if (top != null && top.getType() == InventoryType.CRAFTING) {
            for (int slot = 0; slot < top.getSize(); slot++) {
                if (isDungeonItem(top.getItem(slot))) {
                    take(p, l, top.getItem(slot), keepChanges);
                    top.setItem(slot, null);
                }
            }
        }
    }

    private void take(Player p, Locker l, ItemStack it, boolean keepChanges) {
        String id = idOf(it);
        if (keepChanges && l.wornSlots().containsValue(id) && wornRevision(it) == l.wornAt()) {
            ItemStack back = it.clone();
            ItemMeta meta = back.getItemMeta();
            meta.getPersistentDataContainer().remove(wornKey);
            back.setItemMeta(meta);
            l.put(id, codec.encode(back));
        } else {
            log.warning("[dungeons] took a stray dungeon item copy (" + id + ") from " + p.getName());
        }
    }

    /**
     * Puts a player's locker right. While {@code playing} (in a run on this character, inside the
     * dungeon world) a worn loadout stays on; only stray dungeon items go. Otherwise a worn loadout
     * comes off (or, if the wear never reached the player's file, is undone), every dungeon item
     * leaves their inventory, and own items owed to them come back where there is room.
     */
    public void settle(Player p, boolean playing) {
        Locker l = locker(p);
        if (l == null) {
            return;
        }
        if (playing) {
            if (l.worn() < 0) {
                collect(p, l, false);
            } else {
                strays(p, l);
            }
            return;
        }
        if (l.worn() >= 0) {
            if (committed(p, l)) {
                takeOff(p);
            } else {
                List<byte[]> own = new ArrayList<>(l.displaced().values());
                if (!own.isEmpty()
                        && !store.quarantine(p.getUniqueId(), characters.apply(p), "a wear that never reached the player", own)) {
                    return; // keep the locker as it is until the copies are safe somewhere
                }
                l.takeOff();
                save(p, l);
                log.warning("[dungeons] undid a loadout wear for " + p.getName() + " that a stop cut off");
            }
        }
        collect(p, l, false);
        deliver(p, l);
    }

    /** Whether the worn loadout reached the player's file: their mark or a worn copy says so. */
    private boolean committed(Player p, Locker l) {
        if (mark(p, l.wornAt()).equals(
                p.getPersistentDataContainer().get(playerKey, PersistentDataType.STRING))) {
            return true;
        }
        for (ItemStack it : p.getInventory().getContents()) {
            if (isDungeonItem(it) && l.wornSlots().containsValue(idOf(it)) && wornRevision(it) == l.wornAt()) {
                return true;
            }
        }
        return false;
    }

    /** While worn: dungeon items the locker did not hand out this wear are thrown away. */
    private void strays(Player p, Locker l) {
        PlayerInventory inv = p.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack it = inv.getItem(slot);
            if (isDungeonItem(it)
                    && !(l.wornSlots().containsValue(idOf(it)) && wornRevision(it) == l.wornAt())) {
                take(p, l, it, false);
                inv.setItem(slot, null);
            }
        }
    }

    private void deliver(Player p, Locker l) {
        if (l.owed().isEmpty()) {
            return;
        }
        List<byte[]> still = new ArrayList<>();
        for (byte[] data : l.owed()) {
            p.getInventory().addItem(codec.decode(data)).values().forEach(left -> still.add(codec.encode(left)));
        }
        l.clearOwed();
        still.forEach(l::owe);
        save(p, l);
        if (still.isEmpty()) {
            p.sendMessage("§aYour own items that found no room earlier are back.");
        }
    }

    /** Whether a player's mark says they wear a loadout (for tests and staff). */
    boolean marked(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        return pdc.has(playerKey, PersistentDataType.STRING);
    }

    private static boolean empty(ItemStack it) {
        return it == null || it.getType() == Material.AIR;
    }
}
