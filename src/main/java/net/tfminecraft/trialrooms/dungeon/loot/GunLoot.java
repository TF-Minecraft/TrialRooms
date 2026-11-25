package net.tfminecraft.trialrooms.dungeon.loot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.tfminecraft.trialrooms.dungeon.gear.GearSet;
import net.tfminecraft.trialrooms.dungeon.gear.GearTags;
import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Base;
import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Rarity;

/**
 * Dungeon guns: forged guns whose every part rolls its own shape and its own set (and so its own
 * colour). The set with the most points is the gun's: each part gives its set's rarity (uncommon 1,
 * rare 2, ...) and a tie goes to the barrel. A set with more than half of the points gives its
 * whole trait, otherwise half of it. Two guns of a type can trade a part.
 */
public final class GunLoot implements WeaponMaker {

    /** A gun type, as a base's weapon. */
    static final String PREFIX = "gun.";
    /** How often a part is of the gun's leading set. */
    static final double LEAD = 0.6;
    /** A gun of too many sets: what its trait is worth. */
    static final double MIXED = 0.5;
    private static final int TRIES = 40;
    private static final int WHITE = 0xFFFFFF;

    /** What a gun remembers of its roll (GunsAndGadgets keeps it on the gun): the set id of each part, "" for none. */
    record Roll(String rarity, int tier, String suffix, Map<String, String> sets) {

        String encode() {
            List<String> parts = new ArrayList<>();
            sets.forEach((slot, set) -> parts.add(slot + "=" + set));
            return String.join("|", rarity, Integer.toString(tier), suffix, String.join(",", parts));
        }

        /** Null for a marker this plugin didn't write. */
        static Roll decode(String marker) {
            String[] f = marker == null ? new String[0] : marker.split("\\|", -1);
            if (f.length != 4 || !f[1].matches("\\d{1,4}") || !f[3].matches("\\w+=\\w*(,\\w+=\\w*)*")) {
                return null;
            }
            Map<String, String> sets = new LinkedHashMap<>();
            for (String part : f[3].split(",")) {
                String[] kv = part.split("=", 2);
                sets.put(kv[0], kv[1]);
            }
            return new Roll(f[0], Integer.parseInt(f[1]), f[2], sets);
        }
    }

    /** A gun's set and how much of its trait it gives. */
    record Main(GearSet set, double share) {
    }

    /** A slot two guns can trade: the part of each, named. */
    public record Trade(String slot, String own, String other) {
    }

    private final GunForge forge;
    private final Supplier<LootKit> kit;

    public GunLoot(GunForge forge, Supplier<LootKit> kit) {
        this.forge = forge;
        this.kit = kit;
    }

    private static String type(Base base) {
        return base.weapon() != null && base.weapon().startsWith(PREFIX) ? base.weapon().substring(PREFIX.length()) : null;
    }

    private Map<String, List<String>> slots(LootKit k, String type) {
        return forge.slots(type, k.standard().getOrDefault("gun-metal", ""), k.standard().getOrDefault("gun-wood", ""));
    }

    @Override
    public boolean makes(LootKit k, Base base) {
        String type = type(base);
        if (type == null) {
            return false;
        }
        Map<String, List<String>> slots = slots(k, type);
        return !slots.isEmpty() && slots.values().stream().noneMatch(List::isEmpty);
    }

    @Override
    public ItemStack make(LootKit k, Base base, int tier, Rarity rarity, String dungeon, Random rng) {
        String type = type(base);
        Map<String, List<String>> slots = slots(k, type);
        GearSet lead = LootRoller.set(k, rarity, dungeon, rng);
        Map<String, String> sets = new LinkedHashMap<>();
        slots.keySet().forEach(slot -> sets.put(slot, partSet(k, rarity, lead, dungeon, rng)));
        String suffix = k.rarities().indexOf(rarity) < 2 ? "" : k.suffixes().get(rng.nextInt(k.suffixes().size()));
        Roll roll = new Roll(rarity.id(), tier, suffix, sets);
        double power = k.tier(tier).power() * rarity.power() * (1 + IN_PLACE * (rarity.bonus() + rarity.sockets()));
        for (int i = 0; i < TRIES; i++) { // (some shapes of part don't work together)
            Map<String, String> parts = new LinkedHashMap<>();
            slots.forEach((slot, ids) -> parts.put(slot, ids.get(rng.nextInt(ids.size()))));
            ItemStack gun = build(k, base, type, parts, power, roll);
            if (gun != null) {
                return gun;
            }
        }
        return null;
    }

    /** A part's set: the lead, or another within a step of rarity of it. "" without sets. */
    private static String partSet(LootKit k, Rarity rarity, GearSet lead, String dungeon, Random rng) {
        if (lead == null) {
            return "";
        }
        if (rng.nextDouble() < LEAD) {
            return lead.id();
        }
        int step = k.rarities().indexOf(rarity);
        List<GearSet> near = k.sets().values().stream()
                .filter(s -> s.drops(dungeon) && Math.abs(points(k, s) - 1 - step) <= 1).toList();
        return near.get(rng.nextInt(near.size())).id(); // (never empty: the lead is there)
    }

    /** What a part of a set counts for: 1 for the most common rarity's sets, one more for each step up. */
    private static int points(LootKit k, GearSet set) {
        return k.rarities().indexOf(k.rarity(set.rarity())) + 1;
    }

    /** The gun's set; null when no part has one. */
    static Main main(LootKit k, Map<String, String> sets) {
        Map<GearSet, Integer> points = new LinkedHashMap<>();
        // slots go barrel first, and a tie keeps the first
        sets.values().stream().map(k.sets()::get).filter(java.util.Objects::nonNull)
                .forEach(s -> points.merge(s, points(k, s), Integer::sum));
        GearSet best = null;
        for (GearSet s : points.keySet()) {
            best = best == null || points.get(s) > points.get(best) ? s : best;
        }
        int all = points.values().stream().mapToInt(Integer::intValue).sum();
        return best == null ? null : new Main(best, points.get(best) * 2 > all ? 1 : MIXED);
    }

    private ItemStack build(LootKit k, Base base, String type, Map<String, String> parts, double power, Roll roll) {
        Map<String, Integer> colours = new LinkedHashMap<>();
        roll.sets().forEach((slot, id) -> {
            GearSet s = k.sets().get(id);
            colours.put(slot, s == null || s.tints() == null ? WHITE : s.tints().of(slot));
        });
        ItemStack gun = forge.build(type, parts, colours, power, roll.encode());
        return gun == null ? null : dress(k, base, gun, roll, power);
    }

    /** Puts the dungeon's name and lines on a gun, and the set it counts as. */
    private static ItemStack dress(LootKit k, Base base, ItemStack gun, Roll roll, double power) {
        Rarity rarity = k.rarity(roll.rarity());
        Main main = main(k, roll.sets());
        List<String> lines = new ArrayList<>(List.of(rarity.name() + " dungeon item, tier " + roll.tier()));
        if (main != null) {
            lines.addAll(main.set().lore(GearSet.Kind.WEAPON, power, k.worth(), main.share()));
            lines.add("Parts: " + parts(k, roll.sets()) + ".");
            if (main.share() < 1) {
                lines.add("Its parts are too mixed for the whole trait.");
            }
        }
        lines.add("Stays in the dungeons.");
        String name = base.name().replace("%material%", main == null ? k.prefixes().getFirst() : main.set().name())
                + (roll.suffix().isEmpty() ? "" : " " + roll.suffix());
        ItemMeta meta = gun.getItemMeta();
        meta.displayName(Component.text(name, TextColor.fromHexString(rarity.colour())).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lines.forEach(l -> lore.add(Component.text(l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.lore(lore);
        gun.setItemMeta(meta);
        if (main != null) {
            GearTags.write(gun, new GearTags.Piece(main.set().id(), GearSet.Kind.WEAPON, power, main.share()));
        }
        return gun;
    }

    /** "3 Seithr Royal, 2 Delver". */
    private static String parts(LootKit k, Map<String, String> sets) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        sets.values().forEach(id -> counts.merge(setName(k, id), 1, Integer::sum));
        return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> e.getValue() + " " + e.getKey()).collect(java.util.stream.Collectors.joining(", "));
    }

    private static String setName(LootKit k, String id) {
        GearSet s = k.sets().get(id);
        return s == null ? "plain" : s.name();
    }

    /** A gun GunsAndGadgets rebuilt with its current stats: its dungeon lines go back on. */
    public ItemStack refreshed(ItemStack old, ItemStack rebuilt) {
        Taken gun = take(rebuilt);
        return gun == null ? rebuilt : dress(gun.kit, gun.base, rebuilt, gun.roll, gun.gun.power());
    }

    /** A dungeon gun and its roll. */
    private record Taken(LootKit kit, Base base, GunForge.Gun gun, Roll roll) {
    }

    private Taken take(ItemStack item) {
        LootKit k = kit.get();
        GunForge.Gun gun = k == null ? null : forge.read(item);
        Roll roll = gun == null ? null : Roll.decode(gun.marker());
        if (roll == null || k.rarity(roll.rarity()) == null) {
            return null;
        }
        return k.bases().stream().filter(b -> gun.type().equals(type(b))).findFirst()
                .map(b -> new Taken(k, b, gun, roll)).orElse(null);
    }

    /** The slots two guns can trade parts in; none unless both are dungeon guns of a type. */
    public List<Trade> trades(ItemStack own, ItemStack other) {
        Taken a = take(own);
        Taken b = take(other);
        if (a == null || b == null || !a.gun.type().equals(b.gun.type())) {
            return List.of();
        }
        return a.gun.parts().keySet().stream().map(slot -> new Trade(slot, part(a, slot), part(b, slot))).toList();
    }

    private String part(Taken t, String slot) {
        return forge.partName(t.gun.parts().get(slot)) + " (" + setName(t.kit, t.roll.sets().getOrDefault(slot, "")) + ")";
    }

    /** Both guns with a part traded, each as strong as it was; null when the parts don't fit. */
    public ItemStack[] trade(ItemStack own, ItemStack other, String slot) {
        if (trades(own, other).stream().noneMatch(t -> t.slot().equals(slot))) {
            return null;
        }
        Taken a = take(own);
        Taken b = take(other);
        ItemStack first = with(a, b, slot);
        ItemStack second = with(b, a, slot);
        return first == null || second == null ? null : new ItemStack[] {first, second};
    }

    private ItemStack with(Taken host, Taken from, String slot) {
        Map<String, String> parts = new LinkedHashMap<>(host.gun.parts());
        parts.put(slot, from.gun.parts().get(slot));
        Map<String, String> sets = new LinkedHashMap<>(host.roll.sets());
        sets.put(slot, from.roll.sets().getOrDefault(slot, ""));
        Roll roll = new Roll(host.roll.rarity(), host.roll.tier(), host.roll.suffix(), sets);
        return build(host.kit, host.base, host.gun.type(), parts, host.gun.power(), roll);
    }
}
