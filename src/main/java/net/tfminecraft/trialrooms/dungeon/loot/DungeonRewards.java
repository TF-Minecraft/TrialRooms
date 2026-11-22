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

    public DungeonRewards(DungeonLoot loot, LockerService lockers, Supplier<RewardRules> rules, Random rng,
            LongSupplier clock, ZoneId zone) {
        this.loot = loot;
        this.lockers = lockers;
        this.rules = rules;
        this.rng = rng;
        this.clock = clock;
        this.zone = zone;
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
        lockers.saveLocker(p, l);
        if (found != null) {
            if (lockers.add(p, found) == null) {
                p.sendMessage("§cYou found something, but your locker could not keep it. Tell staff.");
                return;
            }
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
        if (lockers.add(p, rolled.item()) == null) {
            return "§cThe locker could not keep it, so no marks were spent. Tell staff if this keeps happening.";
        }
        l.marks(l.marks() - price);
        lockers.saveLocker(p, l);
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
