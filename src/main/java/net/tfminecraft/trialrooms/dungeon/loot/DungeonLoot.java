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
        LootKit k = kit.get();
        if (k == null) {
            return new Rolled(null, "Dungeon loot is off: loot.yml has problems (see /tr dungeon reload).");
        }
        Rarity rarity = rarityId == null ? LootRoller.rarity(k, tier, rng) : k.rarity(rarityId);
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
