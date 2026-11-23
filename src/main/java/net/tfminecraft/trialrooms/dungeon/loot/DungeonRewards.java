package net.tfminecraft.trialrooms.dungeon.loot;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.trialrooms.dungeon.locker.Locker;
import net.tfminecraft.trialrooms.dungeon.locker.LockerExtras;
import net.tfminecraft.trialrooms.dungeon.locker.LockerService;
import net.tfminecraft.trialrooms.dungeon.run.RunRewards;

/**
 * What runs hand out. Personal loot: each player rolls their own, for their own class, straight
 * into their locker; nobody can take another's drop.
 *
 * <ul>
 * <li>the vault, the boss, elite rooms and room events each give a chance of an item (or a dungeon
 * gem); each roll that gives nothing makes the next one likelier, and a win starts that over;</li>
 * <li>each also pays dungeon marks, to buy an item of the player's choice in the locker;</li>
 * <li>a character rolls items from only so many runs of each dungeon a week; later runs pay marks
 * only, and marks have a weekly cap too, so nobody gains by running dungeons all day.</li>
 * </ul>
 */
public final class DungeonRewards implements RunRewards, LockerExtras {

    private final DungeonLoot loot;
    private final LockerService lockers;
    private final Supplier<RewardRules> rules;
    private final Random rng;
    private final LongSupplier clock;
    private final ZoneId zone;
    private final Supplier<Spoils> spoils;
    private final java.util.logging.Logger log;

    public DungeonRewards(DungeonLoot loot, LockerService lockers, Supplier<RewardRules> rules, Random rng,
            LongSupplier clock, ZoneId zone) {
        this(loot, lockers, rules, rng, clock, zone, () -> null, java.util.logging.Logger.getLogger("TrialRooms"));
    }

    public DungeonRewards(DungeonLoot loot, LockerService lockers, Supplier<RewardRules> rules, Random rng,
            LongSupplier clock, ZoneId zone, Supplier<Spoils> spoils, java.util.logging.Logger log) {
        this.loot = loot;
        this.lockers = lockers;
        this.rules = rules;
        this.rng = rng;
        this.clock = clock;
        this.zone = zone;
        this.spoils = spoils;
        this.log = log;
    }

    /**
     * Spoils: act materials and rare items for {@code hours} of proper play. They wait in the
     * locker (saved with it) and are the player's once they are back outside the dungeon.
     */
    @Override
    public void spoils(Player p, String recipe, double hours, UUID run) {
        Spoils s = spoils.get();
        Locker l = s == null ? null : lockers.locker(p);
        if (l == null) {
            return;
        }
        List<String> got = new java.util.ArrayList<>();
        for (Map.Entry<String, Integer> e : s.roll(hours, rng).entrySet()) {
            ItemStack it;
            try {
                it = loot.item(e.getKey());
            } catch (RuntimeException | LinkageError ex) {
                it = null; // one broken item never stops the rest
            }
            if (it == null) {
                log.warning("[dungeons] spoils: no item " + e.getKey());
                continue;
            }
            for (int left = e.getValue(); left > 0; ) {
                ItemStack stack = it.clone();
                stack.setAmount(Math.min(left, it.getMaxStackSize()));
                left -= stack.getAmount();
                lockers.owe(l, stack);
            }
            got.add(e.getValue() + " " + name(it));
        }
        if (got.isEmpty()) {
            return;
        }
        if (!lockers.saveLocker(p, l)) {
            // they stay in the locker in memory, for the next save that works
            p.sendMessage("§cYour spoils could not be saved yet. Tell staff.");
            return;
        }
        p.sendMessage("§eSpoils: §f" + String.join(", ", got) + " §7(yours when you leave the dungeon)");
    }

    /** The ISO week a moment falls in, as 2026-W47. */
    static String week(long millis, ZoneId zone) {
        LocalDate d = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
        return d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    @Override
    public void reward(Player p, String recipe, int tier, Moment moment, UUID run) {
        RewardRules r = rules.get();
        Locker l = lockers.locker(p);
        if (r == null || l == null) {
            return;
        }
        String week = week(clock.getAsLong(), zone);
        if (!week.equals(l.week())) {
            l.newWeek(week);
        }
        if (!l.lastRun().equals(run.toString())) {
            // The first reward of a run decides whether it counts towards this week's item runs.
            boolean rolls = l.weekRuns(recipe) < r.weeklyRuns();
            if (rolls) {
                l.weekRun(recipe, l.weekRuns(recipe) + 1);
            } else {
                p.sendMessage("§7You have had this week's loot from this dungeon: it pays marks only until the week turns.");
            }
            l.lastRun(run.toString(), rolls);
        }
        int pay = Math.min(r.marks(moment), Math.max(0, r.weeklyMarks() - l.weekMarks()));
        if (pay > 0) {
            l.marks(l.marks() + pay);
            l.weekMarks(l.weekMarks() + pay);
            p.sendMessage("§b+" + pay + " dungeon marks §7(" + l.marks() + " in your locker)");
        }
        ItemStack found = null;
        if (l.lastRunRewarded()) {
            double chance = r.chance(moment);
            boolean win = chance >= 1 || chance > 0 && rng.nextDouble() < chance + l.pity();
            if (chance > 0 && chance < 1) {
                l.pity(win ? 0 : l.pity() + r.pity());
            }
            if (win) {
                found = drop(p, r, tier);
            }
        }
        // The find, the marks and the counters go to disk in one write: none can be kept without the rest.
        String id = found == null ? null : lockers.file(l, found);
        if (!lockers.saveLocker(p, l)) {
            // Everything stays in the locker as it is in memory (the find with its marks and counters):
            // the next save that works writes it all together.
            if (id != null) {
                p.sendMessage("§cYou found " + name(found) + "§c, but your locker could not be saved yet. Tell staff.");
            }
            return;
        }
        if (found != null) {
            p.sendMessage("§6You found " + name(found) + "§6! §7It is in your dungeon locker.");
            p.playSound(p.getLocation(), "minecraft:entity.player.levelup", 1f, 1.4f);
        }
    }

    /** An item for the player's class, or a dungeon gem (always a gem when no item fits them). */
    private ItemStack drop(Player p, RewardRules r, int tier) {
        DungeonLoot.Rolled item = rng.nextDouble() < r.gemChance() ? null : loot.roll(p, tier, null, null);
        if (item == null || item.item() == null) {
            item = loot.gem(tier, null, r.gemPool(), r.gemTemplate());
        }
        return item.item();
    }

    private static String name(ItemStack item) {
        return PlainTextComponentSerializer.plainText().serialize(item.effectiveName());
    }

    // ---- the locker's shop and socketing

    @Override
    public List<Offer> offers(Player p) {
        RewardRules r = rules.get();
        if (r == null || r.prices().isEmpty()) {
            return List.of();
        }
        Map<String, Integer> prices = new LinkedHashMap<>(r.prices());
        return loot.usable(p).stream()
                .map(b -> new Offer(b.id(), b.name().replace("%material%", "").trim().replaceAll("\\s+", " "), prices))
                .toList();
    }

    @Override
    public String buy(Player p, String item, String rarity) {
        RewardRules r = rules.get();
        Locker l = lockers.locker(p);
        Integer price = r == null ? null : r.prices().get(rarity);
        if (price == null || l == null) {
            return "§cThat can't be bought.";
        }
        if (l.marks() < price) {
            return "§cThat costs " + price + " marks; you have " + l.marks() + ".";
        }
        DungeonLoot.Rolled rolled = loot.roll(p, r.shopTier(), rarity, item);
        if (rolled.item() == null) {
            return "§c" + rolled.problem();
        }
        // The item and the price go to disk in one write.
        l.marks(l.marks() - price);
        String id = lockers.file(l, rolled.item());
        if (!lockers.saveLocker(p, l)) {
            l.remove(id);
            l.marks(l.marks() + price);
            return "§cThe locker could not keep it, so no marks were spent. Tell staff if this keeps happening.";
        }
        return "§aBought " + name(rolled.item()) + "§a for " + price + " marks.";
    }

    @Override
    public String socket(Player p, String gem, String item) {
        Locker l = lockers.locker(p);
        if (l == null || l.item(gem) == null || l.item(item) == null) {
            return "§cThat gem or item is gone.";
        }
        if (l.wornSlots().containsValue(item)) {
            return "§cTake the worn loadout off first.";
        }
        LootForge.Socketed s = loot.socket(p, lockers.show(l, item), lockers.show(l, gem));
        switch (s.result()) {
            case DONE -> {
                lockers.replace(l, item, s.item());
                l.remove(gem);
                lockers.saveLocker(p, l);
                return "§aThe gem settles into its socket.";
            }
            case BROKE -> {
                l.remove(gem);
                lockers.saveLocker(p, l);
                return "§cThe gem shattered.";
            }
            default -> {
                return "§cIt doesn't fit: the item needs a free dungeon gem socket.";
            }
        }
    }
}
