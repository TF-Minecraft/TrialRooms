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

    /** Where a dungeon item keeps its value (how strong it is: what insuring it costs). */
    public static final org.bukkit.NamespacedKey VALUE = java.util.Objects.requireNonNull(
            org.bukkit.NamespacedKey.fromString("trialrooms:dungeon_value"));

    private final DungeonLoot loot;
    private final LockerService lockers;
    private final Supplier<RewardRules> rules;
    private final Random rng;
    private final LongSupplier clock;
    private final ZoneId zone;
    private final Supplier<Spoils> spoils;
    private final java.util.logging.Logger log;
    private Supplier<Cosmetics> cosmetics = () -> null;
    private java.util.function.Predicate<String> console; // (only used once cosmetics are set)

    private AccountLimits accounts;

    /** Limits cosmetics per player account too (all their characters together), not only per character. */
    public DungeonRewards accounts(AccountLimits accounts) {
        this.accounts = accounts;
        return this;
    }

    /** Turns on cosmetics: what they are, and how a console command runs (skin unlocks). */
    public DungeonRewards cosmetics(Supplier<Cosmetics> cosmetics, java.util.function.Predicate<String> console) {
        this.cosmetics = cosmetics;
        this.console = console;
        return this;
    }

    /**
     * Cosmetics: the boss may drop a trophy (it waits in the locker, like spoils, and is the
     * player's outside), and the vault may unlock a wardrobe skin the player doesn't have. Both roll
     * only on runs that still roll items this week, so they follow the weekly limit like gear.
     */
    @Override
    public void feat(Player p, String recipe, String difficulty, Moment moment, UUID run, List<String> party) {
        Cosmetics c = cosmetics.get();
        Locker l = c == null ? null : lockers.locker(p);
        if (l == null || !l.lastRun().equals(run.toString()) || !l.lastRunRewarded()) {
            return;
        }
        RewardRules r = rules.get();
        if (accounts != null && r != null && !accounts.cosmetics(p.getUniqueId(), recipe, run.toString(),
                week(clock.getAsLong(), zone), r.weeklyRuns())) {
            return; // (this player's characters together have had this week's cosmetic runs here)
        }
        if (moment == Moment.BOSS) {
            for (Cosmetics.Trophy t : c.trophies(recipe, difficulty)) {
                if (rng.nextDouble() < t.chance()) {
                    trophy(p, l, t, party);
                    return;
                }
            }
            return;
        }
        for (Cosmetics.Skin s : c.skins(recipe, difficulty)) {
            if (!p.hasPermission(s.permission()) && rng.nextDouble() < s.chance()) {
                String command = c.unlockCommand().replace("{player}", p.getName()).replace("{permission}", s.permission());
                if (!console.test(command)) {
                    log.warning("[dungeons] the skin unlock failed: " + command);
                    p.sendMessage("§cYou earned the " + s.name() + " skin, but it could not be unlocked. Tell staff.");
                    return;
                }
                p.sendMessage("§d✦ You unlocked the " + s.name() + " skin! §7Use it on your own gear in /armourshop.");
                p.playSound(p.getLocation(), "minecraft:ui.toast.challenge_complete", 1f, 1f);
                return;
            }
        }
    }

    private void trophy(Player p, Locker l, Cosmetics.Trophy t, List<String> party) {
        ItemStack it;
        try {
            it = loot.item(t.item());
        } catch (RuntimeException | LinkageError ex) {
            it = null;
        }
        if (it == null) {
            log.warning("[dungeons] trophy: no item " + t.item());
            return;
        }
        String day = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH)
                .format(Instant.ofEpochMilli(clock.getAsLong()).atZone(zone));
        lockers.owe(l, dress(it, t.name(), List.of("Won on " + day, "By " + String.join(", ", party))));
        if (!lockers.saveLocker(p, l)) {
            p.sendMessage("§cYour trophy could not be saved yet. Tell staff.");
            return;
        }
        p.sendMessage("§6✦ A trophy: " + t.name() + "! §7It waits for you outside the dungeon.");
        p.playSound(p.getLocation(), "minecraft:ui.toast.challenge_complete", 1f, 1.2f);
    }

    /** One of an item, named and with lore lines (a trophy). */
    static ItemStack dress(ItemStack item, String name, List<String> lore) {
        ItemStack it = item.clone();
        it.setAmount(1);
        it.editMeta(meta -> {
            meta.displayName(net.kyori.adventure.text.Component.text(name, net.kyori.adventure.text.format.NamedTextColor.GOLD)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(lore.stream().map(line -> (net.kyori.adventure.text.Component) net.kyori.adventure.text.Component
                    .text(line, net.kyori.adventure.text.format.NamedTextColor.GRAY)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)).toList());
        });
        return it;
    }

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
        rollSpoils(s, hours, got).forEach(stack -> lockers.owe(l, stack));
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

    /** Spoils for some hours of play, in stacks; what they are is added to {@code got} as players read it. */
    private List<ItemStack> rollSpoils(Spoils s, double hours, List<String> got) {
        List<ItemStack> out = new java.util.ArrayList<>();
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
                out.add(stack);
            }
            got.add(e.getValue() + " " + name(it));
        }
        return out;
    }

    // ---- finds of banking runs: rolled when found, handed over once they come home

    /**
     * A find rolled now: its item chance (with pity) and marks, held until it comes home. As with
     * finds paid at once, the run's first find decides whether it counts towards this week's item
     * runs (and it counts even if what it finds is lost later: no fishing for a drop for free).
     */
    @Override
    public Find find(Player p, String recipe, int tier, Moment moment, UUID run, double boost) {
        RewardRules r = rules.get();
        Locker l = lockers.locker(p);
        if (r == null || l == null) {
            return null;
        }
        week(l);
        if (!l.lastRun().equals(run.toString())) {
            boolean rolls = l.weekRuns(recipe) < r.weeklyRuns();
            if (rolls) {
                l.weekRun(recipe, l.weekRuns(recipe) + 1);
            } else {
                p.sendMessage("§7You have had this week's loot from this dungeon: it pays marks only until the week turns.");
            }
            l.lastRun(run.toString(), rolls);
        }
        boolean rolls = l.lastRunRewarded();
        int marks = (int) Math.round(r.marks(moment) * boost);
        ItemStack found = null;
        if (rolls) {
            double chance = Math.min(1, r.chance(moment) * boost);
            boolean win = chance >= 1 || chance > 0 && rng.nextDouble() < chance + l.pity();
            if (chance > 0 && chance < 1) {
                l.pity(win ? 0 : l.pity() + r.pity());
            }
            if (win) {
                found = drop(p, r, tier, 0);
            }
        }
        // the week's count and the pity, in one write (one that isn't saved only costs a little luck)
        lockers.saveLocker(p, l);
        if (found == null && marks <= 0) {
            return null;
        }
        int value = value(found) + (int) Math.round(marks * r.values().mark());
        String label = found == null ? marks + " dungeon marks" : name(found) + (marks > 0 ? " and " + marks + " marks" : "");
        return new Find(moment, 0, label, value, marks, rolls, found == null ? List.of() : List.of(lockers.encode(found)),
                List.of(), true);
    }

    /** A cleared room's spoils, rolled now and held until they come home. */
    @Override
    public Find spoilsFind(Player p, String recipe, double hours, UUID run) {
        Spoils s = spoils.get();
        if (s == null) {
            return null;
        }
        List<String> got = new java.util.ArrayList<>();
        List<byte[]> items = rollSpoils(s, hours, got).stream().map(lockers::encode).toList();
        if (items.isEmpty()) {
            return null;
        }
        RewardRules r = rules.get();
        int value = r == null ? 0 : (int) Math.round(hours * r.values().spoils());
        return new Find(null, hours, "Spoils: " + String.join(", ", got), value, 0, false, List.of(), items, true);
    }

    /**
     * Hands a find over: its items to the locker, its spoils to wait outside, its marks inside the
     * weekly cap (a find given back by staff skips the cap).
     */
    @Override
    public void deliver(Player p, Find f, String recipe, int tier, UUID run, double boost, boolean restore) {
        if (!f.rolled()) {
            RunRewards.super.deliver(p, f, recipe, tier, run, boost, restore);
            return;
        }
        Locker l = lockers.locker(p);
        if (l == null) {
            throw new IllegalStateException("no locker to hand finds to");
        }
        week(l);
        int pay = f.marks();
        if (!restore) {
            RewardRules r = rules.get();
            pay = Math.min(pay, Math.max(0, (r == null ? 0 : r.weeklyMarks()) - l.weekMarks()));
            l.weekMarks(l.weekMarks() + pay);
        }
        l.marks(l.marks() + pay);
        f.gear().forEach(g -> lockers.file(l, lockers.decode(g)));
        f.spoils().forEach(s -> lockers.owe(l, lockers.decode(s)));
        if (!lockers.saveLocker(p, l)) {
            // all of it stays in the locker in memory, for the next save that works
            p.sendMessage("§cYour finds could not be saved yet. Tell staff.");
            return;
        }
        if (pay > 0) {
            p.sendMessage("§b+" + pay + " dungeon marks §7(" + l.marks() + " in your locker)");
        }
    }

    @Override
    public ItemStack icon(Find f) {
        if (!f.gear().isEmpty()) {
            return lockers.decode(f.gear().get(0));
        }
        if (!f.spoils().isEmpty()) {
            return lockers.decode(f.spoils().get(0));
        }
        return f.rolled() ? new ItemStack(org.bukkit.Material.EMERALD, Math.max(1, Math.min(64, f.marks())))
                : RunRewards.super.icon(f);
    }

    /** The value an item carries (0 for none). */
    @Override
    public int value(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0;
        }
        Integer v = item.getItemMeta().getPersistentDataContainer().get(VALUE, org.bukkit.persistence.PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    @Override
    public int marks(Player p) {
        Locker l = lockers.locker(p);
        return l == null ? -1 : l.marks();
    }

    @Override
    public boolean spend(Player p, int marks) {
        Locker l = lockers.locker(p);
        if (l == null || l.marks() < marks) {
            return false;
        }
        l.marks(l.marks() - marks);
        if (!lockers.saveLocker(p, l)) {
            l.marks(l.marks() + marks);
            return false;
        }
        return true;
    }

    /** What insuring costs; a piece without a value (from before values) costs as the best item does. */
    @Override
    public int insurance(int value) {
        RewardRules r = rules.get();
        if (r == null) {
            return 0;
        }
        double v = value > 0 ? value
                : r.values().rarities().values().stream().mapToInt(Integer::intValue).max().orElse(0) * loot.power(Integer.MAX_VALUE);
        return (int) Math.ceil(v * r.values().insure());
    }

    /** An item tagged with its value by the rules (left as it is without rules or a rarity). */
    public static ItemStack valued(ItemStack item, RewardRules rules, double power, String rarity, boolean gem) {
        return rules == null || rarity == null ? item : valued(item, rules.values().item(rarity, power, gem));
    }

    /** Tags an item with its value (none for no value). */
    static ItemStack valued(ItemStack item, int value) {
        if (item != null && value > 0) {
            item.editMeta(m -> m.getPersistentDataContainer().set(VALUE, org.bukkit.persistence.PersistentDataType.INTEGER, value));
        }
        return item;
    }

    private void week(Locker l) {
        String week = week(clock.getAsLong(), zone);
        if (!week.equals(l.week())) {
            l.newWeek(week);
        }
    }

    /** The ISO week a moment falls in, as 2026-W47. */
    static String week(long millis, ZoneId zone) {
        LocalDate d = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
        return d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    @Override
    public void reward(Player p, String recipe, int tier, Moment moment, UUID run) {
        reward(p, recipe, tier, moment, run, false);
    }

    /** Under par, the vault pays extra marks (inside the weekly cap) and rolls with extra luck. */
    @Override
    public void reward(Player p, String recipe, int tier, Moment moment, UUID run, boolean underPar) {
        reward(p, recipe, tier, moment, run, underPar, 1);
    }

    /** A riskier difficulty's rewards have their item chance and marks multiplied by {@code boost}. */
    @Override
    public void reward(Player p, String recipe, int tier, Moment moment, UUID run, boolean underPar, double boost) {
        boolean par = underPar && moment == Moment.VAULT; // (par only ever betters the vault)
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
        int pay = Math.min((int) Math.round(r.marks(moment) * boost) + (par ? r.parMarks() : 0),
                Math.max(0, r.weeklyMarks() - l.weekMarks()));
        if (pay > 0) {
            l.marks(l.marks() + pay);
            l.weekMarks(l.weekMarks() + pay);
            p.sendMessage("§b+" + pay + " dungeon marks §7(" + l.marks() + " in your locker)");
        }
        ItemStack found = null;
        if (l.lastRunRewarded()) {
            double chance = Math.min(1, r.chance(moment) * boost);
            boolean win = chance >= 1 || chance > 0 && rng.nextDouble() < chance + l.pity();
            if (chance > 0 && chance < 1) {
                l.pity(win ? 0 : l.pity() + r.pity());
            }
            if (win) {
                found = drop(p, r, tier, par ? r.parLuck() : 0);
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

    /** An item for the player's class, or a dungeon gem (always a gem when no item fits them), with its value. */
    private ItemStack drop(Player p, RewardRules r, int tier, double luck) {
        boolean gem = rng.nextDouble() < r.gemChance();
        DungeonLoot.Rolled item = gem ? null : loot.roll(p, tier, luck, null, null);
        if (item == null || item.item() == null) {
            item = loot.gem(tier, luck, null, r.gemPool(), r.gemTemplate());
            gem = true;
        }
        return valued(item.item(), r, loot.power(tier), item.rarity(), gem);
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
        String id = lockers.file(l, valued(rolled.item(), r, loot.power(r.shopTier()), rolled.rarity(), false));
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
                // (MMOItems rebuilds the item: its value comes along)
                lockers.replace(l, item, valued(s.item(), value(lockers.show(l, item))));
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
