package net.tfminecraft.trialrooms.dungeon.loot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import io.lumine.mythic.lib.api.item.NBTItem;
import net.Indyuce.mmoitems.ItemStats;
import net.Indyuce.mmoitems.MMOItems;
import net.Indyuce.mmoitems.api.item.mmoitem.LiveMMOItem;
import net.Indyuce.mmoitems.api.item.mmoitem.MMOItem;
import net.Indyuce.mmoitems.api.player.PlayerData;
import net.Indyuce.mmoitems.stat.data.BooleanData;
import net.Indyuce.mmoitems.stat.data.DoubleData;
import net.Indyuce.mmoitems.stat.data.GemSocketsData;
import net.Indyuce.mmoitems.stat.type.NameData;
import net.Indyuce.mmoitems.stat.data.StringListData;
import net.Indyuce.mmoitems.stat.type.ItemStat;
import net.Indyuce.mmoitems.stat.type.StatHistory;
import net.tfminecraft.advancedcrafting.loaders.IngredientLoader;
import net.tfminecraft.advancedcrafting.loaders.RecipeLoader;
import net.tfminecraft.advancedcrafting.objects.crafting.CraftingRecipe;
import net.tfminecraft.advancedcrafting.objects.ingredients.Ingredient;
import net.tfminecraft.advancedcrafting.objects.stats.StatModifier;
import net.tfminecraft.advancedcrafting.utils.CraftStatCalculator;
import net.tfminecraft.tlibs.TLibs;

/**
 * Loot on the server's item plugins. Stats come from AdvancedCrafting's own calculator, so a
 * dungeon item starts exactly where a piece crafted of the act's standard material does and follows
 * every change to crafting. Items are written the way AdvancedCrafting writes crafted ones (an
 * external stat layer over a zeroed template), so MMOItems template updates keep them; they carry
 * no crafting record, so AdvancedCrafting never recalculates them.
 */
public final class MmoLootForge implements LootForge {

    private final Logger log;

    public MmoLootForge(Logger log) {
        this.log = log;
    }

    @Override
    public Recipe recipe(String id, Map<String, String> standard) {
        CraftingRecipe r = RecipeLoader.getByString(id);
        if (r == null) {
            return null;
        }
        String main = standard.get(r.getMainType().toLowerCase(Locale.ROOT));
        if (main == null) {
            return null;
        }
        Map<String, Integer> materials = new HashMap<>();
        for (Map.Entry<String, Integer> e : r.getRecipe().entrySet()) {
            String key = e.getKey().equalsIgnoreCase(r.getMainType()) ? "ingredient." + main : filler(e.getKey());
            if (key == null) {
                return null;
            }
            materials.put(key, e.getValue());
        }
        Map<String, Double> stats = new LinkedHashMap<>();
        for (StatModifier m : CraftStatCalculator.compute(r, materials).getModifiers()) {
            String stat = m.getType().toLowerCase(Locale.ROOT);
            if (!durability(stat)) { // dungeon items never break
                stats.put(stat, m.getAmount());
            }
        }
        return new Recipe(r.getTemplate(), stats, CraftStatCalculator.collectManagedStatIds(r));
    }

    /** The lowest-tier ingredient of a type, as crafting's staff tools fill a recipe. */
    private static String filler(String type) {
        return IngredientLoader.get().stream()
                .filter(i -> i.getIngredientData().getType().getId().equalsIgnoreCase(type))
                .min(Comparator.comparingInt((Ingredient i) -> i.getIngredientData().getTier()).thenComparing(Ingredient::getId))
                .map(i -> "ingredient." + i.getId()).orElse(null);
    }

    static boolean durability(String stat) {
        return stat.equals("max_item_damage") || stat.equals("durability");
    }

    private static MMOItem template(String template) {
        ItemStack it = TLibs.getItemAPI().getCreator().getItemFromPath("m." + template);
        return it == null || it.getType().isAir() ? null : new LiveMMOItem(NBTItem.get(it));
    }

    @Override
    public List<String> classes(String template) {
        MMOItem mmo = template(template);
        if (mmo == null) {
            return null;
        }
        return mmo.getData(ItemStats.REQUIRED_CLASS) instanceof StringListData d ? new ArrayList<>(d.getList()) : List.of();
    }

    @Override
    public String playerClass(Player player) {
        return PlayerData.get(player).getRPG().getClassName();
    }

    @Override
    public ItemStack make(LootRoller.Plan plan, String template, Set<String> managed, String socket) {
        MMOItem mmo = template(template);
        if (mmo == null) {
            return null;
        }
        for (String stat : managed) {
            if (!durability(stat)) {
                set(mmo, stat, 0, true);
            }
        }
        plan.stats().forEach((stat, v) -> set(mmo, stat, v, false));
        String name = colour(plan.rarity().colour()) + plan.name();
        mmo.setData(ItemStats.NAME, new NameData(name));
        StatHistory names = mmo.computeStatHistory(ItemStats.NAME);
        if (names != null && names.getOriginalData() instanceof NameData og) {
            og.setString(name); // so a template update keeps the name
            mmo.setStatHistory(ItemStats.NAME, names);
        }
        List<String> lore = new ArrayList<>();
        plan.lore().forEach(line -> lore.add("§7" + line));
        mmo.setData(ItemStats.LORE, new StringListData(lore));
        mmo.setData(ItemStats.GEM_SOCKETS, new GemSocketsData(new ArrayList<>(Collections.nCopies(plan.sockets(), socket))));
        mmo.setData(ItemStats.UNBREAKABLE, new BooleanData(true));
        ItemStack built = builder.apply(mmo);
        return plan.look() == null ? built : TLibs.getItemAPI().getArmorMerger().merge(built, Optional.empty(), plan.look());
    }

    /** A stat on the external layer, as crafting writes them; {@code zero} also zeroes the template's own. */
    @SuppressWarnings("deprecation")
    private void set(MMOItem mmo, String stat, double value, boolean zero) {
        ItemStat<?, ?> type = MMOItems.plugin.getStats().get(stat.toUpperCase(Locale.ROOT));
        if (type == null) {
            log.warning("[dungeons] loot: unknown MMOItems stat " + stat);
            return;
        }
        DoubleData data = new DoubleData(value);
        mmo.setData(type, data);
        StatHistory hist = mmo.computeStatHistory(type);
        if (hist == null) {
            return;
        }
        if (zero) {
            hist.clearExternalData();
            if (hist.getOriginalData() instanceof DoubleData og) {
                og.setValue(0);
            }
        } else {
            hist.registerExternalData(data);
        }
        mmo.setStatHistory(type, hist);
    }

    /** Builds an MMOItem into an item (MMOItems' builder needs a running server to even load). */
    static java.util.function.Function<MMOItem, ItemStack> builder = MmoLootForge::build;

    static ItemStack build(MMOItem mmo) {
        return mmo.newBuilder().build();
    }

    /** §x§r§r§g§g§b§b for a #rrggbb colour. */
    static String colour(String hex) {
        StringBuilder b = new StringBuilder("§x");
        for (char c : hex.substring(1).toCharArray()) {
            b.append('§').append(c);
        }
        return b.toString();
    }
}
