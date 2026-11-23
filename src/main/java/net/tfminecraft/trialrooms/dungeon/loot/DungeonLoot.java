package net.tfminecraft.trialrooms.dungeon.loot;

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

    /** An item, or why there is none. */
    public record Rolled(ItemStack item, String problem) {
    }

    /** Where a base's item comes from: its template, starting stats and the stats it owns. */
    private record Source(String template, Map<String, Double> stats, Set<String> managed) {
    }

    private final LootForge forge;
    private final Supplier<LootKit> kit;
    private final Random rng;

    public DungeonLoot(LootForge forge, Supplier<LootKit> kit, Random rng) {
        this.forge = forge;
        this.kit = kit;
        this.rng = rng;
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
            if (base == null || source(k, base, sources).isEmpty()) {
                return new Rolled(null, "There is no item " + baseId + " that can be made.");
            }
        } else {
            base = LootRoller.base(k, b -> usable(k, b, cls, sources), rng);
            if (base == null) {
                return new Rolled(null, "No dungeon item fits " + p.getName() + "'s class (" + cls + ").");
            }
        }
        Source s = source(k, base, sources).orElseThrow();
        ItemStack item = forge.make(LootRoller.plan(k, base, s.stats(), tier, rarity, rng), s.template(), s.managed(),
                k.socket());
        return item == null ? new Rolled(null, "The item " + base.id() + " could not be built.") : new Rolled(item, null);
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
        return gem == null ? new Rolled(null, "The gem template " + template + " is missing.") : new Rolled(gem, null);
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

    private Optional<Source> source(LootKit k, Base b, Map<String, Optional<Source>> cache) {
        return cache.computeIfAbsent(b.id(), id -> {
            if (b.template() != null) {
                return Optional.of(new Source(b.template(), Map.of(), Set.of()));
            }
            LootForge.Recipe r = forge.recipe(b.recipe(), k.standard());
            return r == null ? Optional.empty() : Optional.of(new Source(r.template(), r.stats(), r.managed()));
        });
    }

    private boolean usable(LootKit k, Base b, String cls, Map<String, Optional<Source>> cache) {
        Optional<Source> s = source(k, b, cache);
        if (s.isEmpty()) {
            return false;
        }
        List<String> classes = forge.classes(s.get().template());
        return classes != null && (classes.isEmpty() || cls != null && classes.stream().anyMatch(cls::equalsIgnoreCase));
    }
}
