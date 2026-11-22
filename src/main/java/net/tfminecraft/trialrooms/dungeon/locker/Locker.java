package net.tfminecraft.trialrooms.dungeon.locker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One character's dungeon locker: the dungeon items it owns, its saved loadouts, and, while a
 * loadout is worn, the character's own items that loadout stood in for.
 *
 * <p>The locker is the only owner of dungeon items. A worn loadout puts copies in the player's
 * inventory; when it comes off, those copies are taken back (their changes kept) and anything
 * else carrying a dungeon item's id is thrown away. So a copy that strays (dropped, lost, carried
 * off) can never outlive the wear, and an item that goes missing while worn is still in the locker.
 *
 * <p>Item data is opaque bytes (the server's own item format), so the locker is plain Java.
 */
public final class Locker {

    /** Saved loadouts per character. */
    public static final int LOADOUTS = 3;

    /**
     * Inventory slots a loadout can fill, by the server's player inventory numbering: hotbar 0-8,
     * boots 36, leggings 37, chestplate 38, helmet 39, off hand 40.
     */
    public static final List<Integer> SLOTS = List.of(39, 38, 37, 36, 40, 0, 1, 2, 3, 4, 5, 6, 7, 8);

    private final Map<String, byte[]> items = new LinkedHashMap<>();
    private final List<Map<Integer, String>> loadouts = new ArrayList<>();
    private int worn = -1;
    private long wornAt = -1;
    private final Map<Integer, String> wornSlots = new LinkedHashMap<>();
    private final Map<Integer, byte[]> displaced = new LinkedHashMap<>();
    /** Own items that found no room when a loadout came off: handed over later. */
    private final List<byte[]> owed = new ArrayList<>();
    private long revision;

    public Locker() {
        for (int i = 0; i < LOADOUTS; i++) {
            loadouts.add(new LinkedHashMap<>());
        }
    }

    // ---- items

    /** The dungeon items, by id, oldest first. */
    public Map<String, byte[]> items() {
        return Collections.unmodifiableMap(items);
    }

    public byte[] item(String id) {
        return items.get(id);
    }

    public void put(String id, byte[] data) {
        items.put(Objects.requireNonNull(id), Objects.requireNonNull(data));
    }

    /** Takes an item out of the locker and every loadout. Refused while it is worn. */
    public boolean remove(String id) {
        if (!items.containsKey(id) || wornSlots.containsValue(id)) {
            return false;
        }
        items.remove(id);
        for (Map<Integer, String> l : loadouts) {
            l.values().removeIf(id::equals);
        }
        return true;
    }

    // ---- loadouts

    /** A loadout's slots: inventory slot to item id. */
    public Map<Integer, String> loadout(int index) {
        return Collections.unmodifiableMap(loadouts.get(index));
    }

    /**
     * Puts an item in a loadout slot (one item may sit in several loadouts). Refused for an unknown
     * slot or item, or while a loadout is worn.
     */
    public boolean assign(int loadout, int slot, String id) {
        if (worn >= 0 || !SLOTS.contains(slot) || !items.containsKey(id)) {
            return false;
        }
        Map<Integer, String> l = loadouts.get(loadout);
        l.values().removeIf(id::equals); // one item fills one slot of a loadout
        l.put(slot, id);
        return true;
    }

    /** Empties a loadout slot. Refused while a loadout is worn. */
    public boolean unassign(int loadout, int slot) {
        return worn < 0 && loadouts.get(loadout).remove(slot) != null;
    }

    // ---- wearing

    /** The worn loadout, or -1. */
    public int worn() {
        return worn;
    }

    /** What the worn loadout put where: inventory slot to item id. */
    public Map<Integer, String> wornSlots() {
        return Collections.unmodifiableMap(wornSlots);
    }

    /** The character's own items the worn loadout stood in for, by inventory slot. */
    public Map<Integer, byte[]> displaced() {
        return Collections.unmodifiableMap(displaced);
    }

    /** The revision the worn loadout went on at (its copies carry it), or -1. */
    public long wornAt() {
        return wornAt;
    }

    /**
     * Marks a loadout worn at a revision, keeping the own items it replaces. Later saves (an item
     * added mid-run) move the locker's revision on; the wear keeps its own.
     */
    public void wear(int loadout, Map<Integer, byte[]> ownItems, long at) {
        if (worn >= 0) {
            throw new IllegalStateException("a loadout is already worn");
        }
        if (loadouts.get(loadout).isEmpty()) {
            throw new IllegalArgumentException("loadout " + loadout + " is empty");
        }
        worn = loadout;
        wornAt = at;
        wornSlots.putAll(loadouts.get(loadout));
        displaced.putAll(ownItems);
    }

    /** Marks the worn loadout off; the own items it replaced are the caller's to hand back. */
    public void takeOff() {
        worn = -1;
        wornAt = -1;
        wornSlots.clear();
        displaced.clear();
    }

    // ---- owed items

    public List<byte[]> owed() {
        return Collections.unmodifiableList(owed);
    }

    public void owe(byte[] data) {
        owed.add(Objects.requireNonNull(data));
    }

    public void clearOwed() {
        owed.clear();
    }

    // ---- revision

    /** Bumped by every save. */
    public long revision() {
        return revision;
    }

    void revision(long r) {
        revision = r;
    }

    /** Restores the worn state as saved (for the store). */
    void restoreWorn(int loadout, long at, Map<Integer, String> slots, Map<Integer, byte[]> own) {
        worn = loadout;
        wornAt = at;
        wornSlots.putAll(slots);
        displaced.putAll(own);
    }

    /** Restores one saved loadout slot as it was, without the checks (for the store). */
    void restoreSlot(int loadout, int slot, String id) {
        loadouts.get(loadout).put(slot, id);
    }
}
