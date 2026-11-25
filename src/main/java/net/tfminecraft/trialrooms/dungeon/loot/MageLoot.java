package net.tfminecraft.trialrooms.dungeon.loot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.trialrooms.dungeon.gear.GearSet;
import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Base;
import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Rarity;

/**
 * Dungeon mage weapons: a staff, wand or blade with its runes already in it, mostly of one school.
 * It has as many as the one crafted of the act's standard metal, and a rarity's gem sockets give
 * more runes instead (power for those that find no room).
 */
public final class MageLoot implements WeaponMaker {

    /** A mage weapon archetype, as a base's weapon. */
    static final String PREFIX = "magic.";
    /** How often a rune is of the weapon's leading school. */
    static final double LEAD = 0.75;

    /** The socket slots, smallest rune first. */
    static final List<String> SLOTS = List.of("minor_rune", "lesser_rune", "greater_rune", "ascendant_rune");

    private final MageForge forge;
    private final BiFunction<ItemStack, String, ItemStack> look;

    /** @param look puts a look (a TLibs item path) on an item */
    public MageLoot(MageForge forge, BiFunction<ItemStack, String, ItemStack> look) {
        this.forge = forge;
        this.look = look;
    }

    private static String archetype(Base base) {
        return base.weapon() != null && base.weapon().startsWith(PREFIX) ? base.weapon().substring(PREFIX.length()) : null;
    }

    @Override
    public boolean makes(LootKit k, Base base) {
        String archetype = archetype(base);
        return archetype != null && forge.archetypes().contains(archetype);
    }

    @Override
    public ItemStack make(LootKit k, Base base, int tier, Rarity rarity, String dungeon, Random rng) {
        GearSet set = LootRoller.set(k, rarity, dungeon, rng);
        LootRoller.Plan plan = LootRoller.plan(k, base, Map.of(), tier, rarity, set, rng);
        int wanted = base.runes() + rarity.sockets();
        int sockets = Math.min(wanted, SLOTS.size());
        ItemStack item = forge.build(archetype(base), runes(archetype(base), sockets, rng),
                plan.power() * (1 + IN_PLACE * (wanted - sockets)), plan.stats(),
                MmoLootForge.colour(rarity.colour()) + plan.name(), plan.lore().stream().map(l -> "§7" + l).toList());
        return item == null ? null : MmoLootForge.tagged(plan, plan.look() == null ? item : look.apply(item, plan.look()));
    }

    /** A rune for each socket, each mostly of the same school. */
    private List<String> runes(String archetype, int sockets, Random rng) {
        List<String> out = new ArrayList<>();
        String lead = null;
        for (String slot : SLOTS.subList(0, sockets)) {
            List<String> fits = forge.runes(archetype, slot);
            if (fits.isEmpty()) {
                continue;
            }
            if (lead == null) {
                List<String> schools = fits.stream().map(MageLoot::school).distinct().toList();
                lead = schools.get(rng.nextInt(schools.size()));
            }
            String school = lead;
            List<String> of = fits.stream().filter(r -> school(r).equals(school)).toList();
            List<String> from = of.isEmpty() || rng.nextDouble() >= LEAD ? fits : of;
            out.add(from.get(rng.nextInt(from.size())));
        }
        return out;
    }

    /** m.SCHOOL.rune. */
    private static String school(String path) {
        return path.split("\\.")[1];
    }
}
