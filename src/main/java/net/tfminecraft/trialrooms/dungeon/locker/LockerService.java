package net.tfminecraft.trialrooms.dungeon.locker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Puts lockers to work on players: wearing a loadout, taking it off, and putting things right after
 * a crash or a quit.
 *
 * <p>Wearing writes the locker first (worn, with the own items it replaces), then swaps the items,
 * marks the player for that character and saves the player's file. Taking off does the reverse:
 * the copies come out (their changes kept), the own items go back, the mark goes, the player's
 * file is saved, and then the locker. After a crash the two files are compared: the wear counts as
 * done when the character's mark or a worn copy the player holds names the wear's revision, and it
 * is undone the normal way; otherwise it never reached the player's file, and the own items it
 * would have replaced are still in their inventory, so the locker's copies of them go to
 * quarantine, never to the player.
 *
 * <p>"Holds" means anywhere a player keeps items: the inventory, the cursor, the crafting grid,
 * and inside bundles in any of those.
 */
public final class LockerService {

    /** Why a wear or take-off did not happen. */
    public enum Result {
        DONE, NO_CHARACTER, BROKEN, ALREADY_WORN, NOT_WORN, EMPTY, CURSOR, SAVE_FAILED
    }

    private record Open(String character, Locker locker) {
    }

    private final Plugin plugin;
    private final LockerStore store;
    private final ItemCodec codec;
    private final Function<Player, String> characters;
    private final Consumer<Player> saver;
    private final Logger log;
    private final NamespacedKey itemKey;
    private final NamespacedKey wornKey;
    private final Map<UUID, Open> open = new HashMap<>();

    /**
     * @param characters the id of the character a player is playing, or null
     * @param saver      saves a player's file to disk now (the server's {@code Player#saveData})
     */
    public LockerService(Plugin plugin, LockerStore store, ItemCodec codec, Function<Player, String> characters,
            Consumer<Player> saver) {
        this.plugin = plugin;
        this.store = store;
        this.codec = codec;
        this.characters = characters;
        this.saver = saver;
        this.log = plugin.getLogger();
        this.itemKey = new NamespacedKey(plugin, "dungeon_item");
        this.wornKey = new NamespacedKey(plugin, "dungeon_worn");
    }

    /**
     * The player mark for one character's wear: one key per character, so characters sharing an
     * inventory never overwrite each other's mark.
     */
    NamespacedKey markKey(String character) {
        // a hash, so ids differing only by case or punctuation never share a key
        return new NamespacedKey(plugin, "dungeon_locker_" + LockerStore.sha256(character).substring(0, 24));
    }

    // ---- lockers

    /**
     * The locker of the character a player is playing; null with no character or a broken file. A
     * locker read from its backup is refused too when the player holds proof of a newer wear (the
     * backup would not know the own items that wear put away).
     */
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
            if (l.fromBackup && newerWear(p, character, l.revision())) {
                log.severe("[dungeons] the locker of " + p.getName() + "/" + character + " was read from its backup,"
                        + " but they wear a newer loadout: refused for staff (own items are in the damaged file)");
                return null;
            }
            open.put(p.getUniqueId(), new Open(character, l));
            return l;
        } catch (LockerStore.BrokenLocker e) {
            log.severe("[dungeons] " + e.getMessage());
            return null;
        }
    }

    private boolean newerWear(Player p, String character, long revision) {
        Long mark = p.getPersistentDataContainer().get(markKey(character), PersistentDataType.LONG);
        return mark != null && mark > revision
                || held(p).stream().anyMatch(it -> isDungeonItem(it) && wornRevision(it) > revision);
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
        if (l == null || empty(item)) {
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

    // ---- where players hold items

    /** Every item a player holds, bundle contents included. */
    private List<ItemStack> held(Player p) {
        List<ItemStack> out = new ArrayList<>();
        p.getInventory().forEach(it -> hold(it, out));
        hold(p.getItemOnCursor(), out);
        Inventory grid = grid(p);
        if (grid != null) {
            for (int slot = 0; slot < grid.getSize(); slot++) {
                hold(grid.getItem(slot), out);
            }
        }
        return out;
    }

    /** An item and, for a bundle, everything inside it (bundles in bundles too). */
    private static void hold(ItemStack it, List<ItemStack> out) {
        if (!empty(it)) {
            out.add(it);
            if (it.getItemMeta() instanceof BundleMeta bundle) {
                bundle.getItems().forEach(inner -> hold(inner, out));
            }
        }
    }

    /** The crafting grid, when the player's own inventory screen is the one open. */
    private static Inventory grid(Player p) {
        Inventory top = p.getOpenInventory().getTopInventory();
        return top != null && top.getType() == InventoryType.CRAFTING ? top : null;
    }

    /** Takes every held item that matches out of the player's hands (bundles too); returns them. */
    private List<ItemStack> remove(Player p, Predicate<ItemStack> which) {
        List<ItemStack> out = new ArrayList<>();
        PlayerInventory inv = p.getInventory();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack it = inv.getItem(slot);
            if (pick(it, which, out)) {
                inv.setItem(slot, null);
            } else if (unbundle(it, which, out)) {
                inv.setItem(slot, it);
            }
        }
        ItemStack cursor = p.getItemOnCursor();
        if (pick(cursor, which, out)) {
            p.setItemOnCursor(null);
        } else if (unbundle(cursor, which, out)) {
            p.setItemOnCursor(cursor);
        }
        Inventory grid = grid(p);
        if (grid != null) {
            for (int slot = 0; slot < grid.getSize(); slot++) {
                ItemStack it = grid.getItem(slot);
                if (pick(it, which, out)) {
                    grid.setItem(slot, null);
                } else if (unbundle(it, which, out)) {
                    grid.setItem(slot, it);
                }
            }
        }
        return out;
    }

    private static boolean pick(ItemStack it, Predicate<ItemStack> which, List<ItemStack> out) {
        if (empty(it) || !which.test(it)) {
            return false;
        }
        out.add(it);
        return true;
    }

    /** Takes matching items out of a bundle; true when it changed. */
    private static boolean unbundle(ItemStack it, Predicate<ItemStack> which, List<ItemStack> out) {
        if (empty(it) || !(it.getItemMeta() instanceof BundleMeta bundle)) {
            return false;
        }
        List<ItemStack> keep = new ArrayList<>();
        int before = out.size();
        for (ItemStack inner : bundle.getItems()) {
            if (!pick(inner, which, out)) {
                unbundle(inner, which, out); // a bundle in the bundle
                keep.add(inner);
            }
        }
        if (out.size() == before) {
            return false;
        }
        bundle.setItems(keep);
        it.setItemMeta(bundle);
        return true;
    }

    /** With no usable locker: every dungeon item still goes (the locker, or staff, has them). */
    private void strip(Player p) {
        int n = remove(p, this::isDungeonItem).size();
        Accessories.refill.accept(p);
        if (n > 0) {
            log.warning("[dungeons] took " + n + " dungeon item(s) from " + p.getName() + " whose locker can't be read");
        }
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
        collect(p, l, false); // nothing is worn: any dungeon item held is a stray copy
        long at = l.revision() + 1; // the revision the save below writes
        // Everything that can fail is done before anything is saved or moved.
        Map<Integer, ItemStack> copies = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : l.loadout(loadout).entrySet()) {
            ItemStack copy = codec.decode(l.item(e.getValue()));
            ItemMeta meta = copy.getItemMeta();
            meta.getPersistentDataContainer().set(wornKey, PersistentDataType.LONG, at);
            copy.setItemMeta(meta);
            copies.put(e.getKey(), copy);
        }
        PlayerInventory inv = p.getInventory();
        Map<Integer, byte[]> own = new LinkedHashMap<>();
        for (int slot : copies.keySet()) {
            ItemStack it = inv.getItem(slot);
            if (!vacant(slot, it)) {
                own.put(slot, codec.encode(it));
            }
        }
        l.wear(loadout, own, at);
        if (!save(p, l)) {
            l.takeOff();
            return Result.SAVE_FAILED;
        }
        copies.forEach(inv::setItem);
        p.getPersistentDataContainer().set(markKey(characters.apply(p)), PersistentDataType.LONG, at);
        saver.accept(p);
        return Result.DONE;
    }

    /** Takes the worn loadout off: dungeon items back to the locker, own items back where they were. */
    public Result takeOff(Player p) {
        Locker l = locker(p);
        if (l == null) {
            if (characters.apply(p) == null) {
                return Result.NO_CHARACTER;
            }
            strip(p);
            return Result.BROKEN;
        }
        if (l.worn() < 0) {
            return Result.NOT_WORN;
        }
        // Decode the own items first: if one can't be read, nothing has moved yet.
        Map<Integer, ItemStack> back = new LinkedHashMap<>();
        l.displaced().forEach((slot, data) -> back.put(slot, codec.decode(data)));
        collect(p, l, true);
        // MMOInventory's placeholders go back first, so no own item lands in an accessory slot by chance
        Accessories.refill.accept(p);
        PlayerInventory inv = p.getInventory();
        List<ItemStack> left = new ArrayList<>();
        back.forEach((slot, own) -> {
            if (Accessories.placeholder(own)) {
                return; // kept by an older version: never a player's item
            }
            if (vacant(slot, inv.getItem(slot))) {
                inv.setItem(slot, own);
            } else {
                left.addAll(inv.addItem(own).values());
            }
        });
        left.forEach(it -> l.owe(codec.encode(it)));
        String character = characters.apply(p);
        l.takeOff();
        p.getPersistentDataContainer().remove(markKey(character));
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
     * Takes every dungeon item out of a player's hands. With {@code keepChanges}, the locker's worn
     * copies take their state (wear, sockets) from what was found; copies the locker did not hand
     * out this wear are thrown away.
     */
    private void collect(Player p, Locker l, boolean keepChanges) {
        for (ItemStack it : remove(p, this::isDungeonItem)) {
            take(p, l, it, keepChanges);
        }
    }

    private boolean thisWear(Locker l, ItemStack it) {
        return l.wornSlots().containsValue(idOf(it)) && wornRevision(it) == l.wornAt();
    }

    private void take(Player p, Locker l, ItemStack it, boolean keepChanges) {
        if (keepChanges && thisWear(l, it)) {
            ItemStack back = it.clone();
            ItemMeta meta = back.getItemMeta();
            meta.getPersistentDataContainer().remove(wornKey);
            back.setItemMeta(meta);
            l.put(idOf(it), codec.encode(back));
        } else {
            log.warning("[dungeons] took a stray dungeon item copy (" + idOf(it) + ") from " + p.getName());
        }
    }

    /**
     * Puts a player's locker right. While {@code playing} (in a run on this character, inside the
     * dungeon world) a worn loadout stays on; only stray dungeon items go. Otherwise a worn loadout
     * comes off (or, if the wear never reached the player's file, is undone), every dungeon item
     * leaves their hands, and own items owed to them come back where there is room.
     */
    public void settle(Player p, boolean playing) {
        Locker l = locker(p);
        if (l == null) {
            if (characters.apply(p) != null) {
                strip(p);
            }
            return;
        }
        if (playing) {
            if (l.worn() < 0) {
                collect(p, l, false);
            } else {
                remove(p, it -> isDungeonItem(it) && !thisWear(l, it))
                        .forEach(it -> take(p, l, it, false));
            }
            Accessories.refill.accept(p);
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
        if (l.fromBackup && !l.owed().isEmpty()) {
            // The backup is one save behind: what it owes may have been handed over already.
            if (!store.quarantine(p.getUniqueId(), characters.apply(p), "owed items in a locker read from its backup",
                    new ArrayList<>(l.owed()))) {
                return;
            }
            l.clearOwed();
            save(p, l);
        }
        Accessories.refill.accept(p);
        deliver(p, l);
    }

    /** Whether the worn loadout reached the player's file: the character's mark or a worn copy says so. */
    private boolean committed(Player p, Locker l) {
        Long mark = p.getPersistentDataContainer().get(markKey(characters.apply(p)), PersistentDataType.LONG);
        return mark != null && mark == l.wornAt() || held(p).stream().anyMatch(it -> isDungeonItem(it) && thisWear(l, it));
    }

    private void deliver(Player p, Locker l) {
        if (l.owed().isEmpty()) {
            return;
        }
        List<byte[]> still = new ArrayList<>();
        for (byte[] data : l.owed()) {
            ItemStack own = codec.decode(data);
            if (!Accessories.placeholder(own)) { // (an older version could owe a placeholder)
                p.getInventory().addItem(own).values().forEach(left -> still.add(codec.encode(left)));
            }
        }
        l.clearOwed();
        still.forEach(l::owe);
        save(p, l);
        if (still.isEmpty()) {
            p.sendMessage("§aYour own items that found no room earlier are back.");
        }
    }

    /** Whether the player carries the mark of a wear for their current character (for tests and staff). */
    boolean marked(Player p) {
        String character = characters.apply(p);
        return character != null && p.getPersistentDataContainer().has(markKey(character), PersistentDataType.LONG);
    }

    private static boolean empty(ItemStack it) {
        return it == null || it.getType() == Material.AIR;
    }

    /** Whether a slot holds nothing of the player's: empty, or an accessory slot holding MMOInventory's placeholder. */
    static boolean vacant(int slot, ItemStack it) {
        return empty(it) || Accessories.slot(slot) && Accessories.placeholder(it);
    }
}
