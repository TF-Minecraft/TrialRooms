package net.tfminecraft.trialrooms.dungeon.loot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Base;
import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Rarity;

/**
 * Rolls dungeon items for players: only items their class can use (the item's template decides,
 * as it does for crafted gear), of a rarity favoured by the dungeon's tier.
 */
public final class DungeonLoot {

    /** An item (and its rarity id), or why there is none. */
    public record Rolled(ItemStack item, String problem, String rarity) {
        public Rolled(ItemStack item, String problem) {
            this(item, problem, null);
        }
    }

    /** How much stronger a tier makes items (1 with loot off). */
    public double power(int tier) {
        LootKit k = kit.get();
        return k == null ? 1 : k.tier(tier).power();
    }

    /** Where a base's item comes from: its template, starting stats and the stats it owns. */
    private record Source(String template, Map<String, Double> stats, Set<String> managed) {
    }

    private final LootForge forge;
    private final Supplier<LootKit> kit;
    private final Random rng;
    private final List<WeaponMaker> makers = new ArrayList<>();
    private GunLoot guns;

    public DungeonLoot(LootForge forge, Supplier<LootKit> kit, Random rng) {
        this.forge = forge;
        this.kit = kit;
        this.rng = rng;
    }

    /** Guns drop too (GunsAndGadgets is there). */
    public DungeonLoot guns(GunLoot guns) {
        this.guns = guns;
        return maker(guns);
    }

    /** Guns, or null without them. */
    public GunLoot guns() {
        return guns;
    }

    /** Another plugin's weapons drop too. */
    public DungeonLoot maker(WeaponMaker maker) {
        makers.add(maker);
        return this;
    }

    /**
     * Rolls an item for a player. {@code rarityId} and {@code baseId} may force the rarity or the
     * item (staff tools); null leaves them to chance.
     */
    public Rolled roll(Player p, int tier, String rarityId, String baseId) {
        return roll(p, tier, 0, rarityId, baseId);
    }

    /** An item rolled with extra luck for its rarity (a run under par). */
    public Rolled roll(Player p, int tier, double luck, String rarityId, String baseId) {
        return roll(p, tier, luck, rarityId, baseId, null);
    }

    /** An item rolled in a dungeon (its recipe id): that dungeon's sets come up too, beside the general ones. */
    public Rolled roll(Player p, int tier, double luck, String rarityId, String baseId, String dungeon) {
        LootKit k = kit.get();
        if (k == null) {
            return new Rolled(null, "Dungeon loot is off: loot.yml has problems (see /tr dungeon reload).");
        }
        Rarity rarity = rarityId == null ? LootRoller.rarity(k, tier, luck, rng) : k.rarity(rarityId);
        if (rarity == null) {
            return new Rolled(null, "There is no rarity " + rarityId + ".");
        }
        Map<String, Optional<Source>> sources = new HashMap<>();
        String cls = forge.playerClass(p);
        Base base;
        if (baseId != null) {
            base = k.base(baseId);
            if (base == null || (base.weapon() == null ? source(k, base, sources).isEmpty() : maker(k, base) == null)) {
                return new Rolled(null, "There is no item " + baseId + " that can be made.");
            }
        } else {
            base = LootRoller.base(k, b -> usable(k, b, cls, sources), rng);
            if (base == null) {
                return new Rolled(null, "No dungeon item fits " + p.getName() + "'s class (" + cls + ").");
            }
        }
        ItemStack item;
        if (base.weapon() != null) {
            item = maker(k, base).make(k, base, tier, rarity, dungeon, rng);
        } else {
            Source s = source(k, base, sources).orElseThrow();
            item = forge.make(LootRoller.plan(k, base, s.stats(), tier, rarity,
                    LootRoller.set(k, rarity, dungeon, rng), rng), s.template(), s.managed(), k.socket());
        }
        return item == null ? new Rolled(null, "The item " + base.id() + " could not be built.")
                : new Rolled(item, null, rarity.id());
    }

    /** A dungeon gem of a rarity (null: by chance at the tier) with one stat from a pool. */
    public Rolled gem(int tier, String rarityId, String pool, String template) {
        return gem(tier, 0, rarityId, pool, template);
    }

    /** A dungeon gem rolled with extra luck for its rarity (a run under par). */
    public Rolled gem(int tier, double luck, String rarityId, String pool, String template) {
        LootKit k = kit.get();
        if (k == null) {
            return new Rolled(null, "Dungeon loot is off: loot.yml has problems (see /tr dungeon reload).");
        }
        Rarity rarity = rarityId == null ? LootRoller.rarity(k, tier, luck, rng) : k.rarity(rarityId);
        if (rarity == null) {
            return new Rolled(null, "There is no rarity " + rarityId + ".");
        }
        ItemStack gem = forge.makeGem(LootRoller.gem(k, pool, tier, rarity, rng), template, k.socket());
        return gem == null ? new Rolled(null, "The gem template " + template + " is missing.")
                : new Rolled(gem, null, rarity.id());
    }

    /** The items a player's class can use. */
    public List<Base> usable(Player p) {
        LootKit k = kit.get();
        if (k == null) {
            return List.of();
        }
        Map<String, Optional<Source>> sources = new HashMap<>();
        String cls = forge.playerClass(p);
        return k.bases().stream().filter(b -> usable(k, b, cls, sources)).toList();
    }

    /** Puts a gem in an item (MMOItems' own socketing). */
    public LootForge.Socketed socket(Player p, ItemStack item, ItemStack gem) {
        return forge.socket(p, item, gem);
    }

    /** One of an ordinary item (a spoil) by its TLibs item path; null when it doesn't exist. */
    public ItemStack item(String path) {
        return forge.item(path);
    }

    /** An item whose model is wanted, or null when it doesn't exist (TLibs gives dirt for what it can't find). */
    public ItemStack look(String path) {
        ItemStack it = forge.item(path);
        return it == null || it.getType() == org.bukkit.Material.DIRT ? null : it;
    }

    private Optional<Source> source(LootKit k, Base b, Map<String, Optional<Source>> cache) {
        return cache.computeIfAbsent(b.id(), id -> {
            if (b.template() != null) {
                return Optional.of(new Source(b.template(), Map.of(), Set.of()));
            }
            LootForge.Recipe r = forge.recipe(b.recipe(), k.standard());
            return r == null ? Optional.empty() : Optional.of(new Source(r.template(), r.stats(), r.managed()));
        });
    }

    private WeaponMaker maker(LootKit k, Base b) {
        return makers.stream().filter(m -> m.makes(k, b)).findFirst().orElse(null);
    }

    private boolean usable(LootKit k, Base b, String cls, Map<String, Optional<Source>> cache) {
        List<String> classes;
        if (b.weapon() != null) {
            classes = maker(k, b) == null ? null : b.classes();
        } else {
            classes = source(k, b, cache).map(s -> forge.classes(s.template())).orElse(null);
        }
        return classes != null && (classes.isEmpty() || cls != null && classes.stream().anyMatch(cls::equalsIgnoreCase));
    }
}
