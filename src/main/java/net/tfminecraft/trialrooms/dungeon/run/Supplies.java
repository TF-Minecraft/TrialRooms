package net.tfminecraft.trialrooms.dungeon.run;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.bossbar.BossBar;
import net.tfminecraft.trialrooms.dungeon.locker.LockerService;
import net.tfminecraft.trialrooms.dungeon.run.DungeonRun.Member;
import net.tfminecraft.trialrooms.dungeon.run.DungeonRun.Status;
import net.tfminecraft.trialrooms.dungeon.run.RunManager.ActiveRun;
import net.tfminecraft.trialrooms.dungeon.supply.Supply;
import net.tfminecraft.trialrooms.dungeon.supply.SupplyItems;
import net.tfminecraft.trialrooms.dungeon.supply.SupplySettings;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;

/**
 * Keeping going between fights: the supplies a run takes along and what using them does, the rest
 * room's campfire, and supplies found on the way. Inside runs nothing heals on its own (see
 * {@link RunListener}), so healers, lifesteal, supplies and the campfire are what bring health back.
 */
final class Supplies {

    /** A bandage at work: health it heals each step, until when. */
    record Bandage(double perStep, long untilMs) {
    }

    /** A drunk ward: what it stops (once), until the next fight its drinker is in is over. */
    record Ward(String name, Set<String> potions, Set<String> auras) {
    }

    private final RunManager runs;
    private final Bars bars;
    private final LongSupplier clock;
    private final DoubleSupplier random;
    private final Supplier<SupplySettings> settings;
    /** Mana from MMOCore, or null without it. */
    Ports.Mana mana;
    private final Map<UUID, Map<Supply.Use, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Bandage> bandages = new HashMap<>();
    private final Map<UUID, Ward> wards = new HashMap<>();
    /** Crouched by the campfire since (step time added up). */
    private final Map<UUID, Long> resting = new HashMap<>();

    Supplies(RunManager runs, Bars bars, LongSupplier clock, DoubleSupplier random, Supplier<SupplySettings> settings) {
        this.runs = runs;
        this.bars = bars;
        this.clock = clock;
        this.random = random;
        this.settings = settings;
    }

    private SupplySettings settings() {
        SupplySettings s = settings.get();
        return s == null ? SupplySettings.DEFAULTS : s;
    }

    private LockerService lockers() {
        return runs.lockers();
    }

    // ------------------------------------------------------------------ the pouch

    /** The doors open: everyone playing gets their packed supplies (once per run). */
    void handOut(ActiveRun ar, List<Player> here) {
        if (lockers() == null || settings().items().isEmpty()) {
            return;
        }
        for (Player p : here) {
            if (ar.supplied.contains(p.getUniqueId())) {
                continue;
            }
            int n = lockers().handOut(p, id -> {
                Supply s = settings().supply(id);
                return s != null && s.forKit(ar.kit.id()) ? s : null;
            });
            if (n >= 0) {
                ar.supplied.add(p.getUniqueId());
            }
            if (n > 0) {
                p.sendMessage("§6Your supply pouch: " + n + " supplies. §7Right-click one to use it.");
            } else if (n == 0) {
                p.sendMessage("§7Your supply pouch is empty. Nothing heals on its own here: buy and pack supplies"
                        + " in the locker (/tr dungeon supplies).");
            } else {
                p.sendMessage("§cYour supplies could not be taken out of the locker; tell staff if this keeps happening.");
            }
        }
    }

    // ------------------------------------------------------------------ using one

    /** A player right-clicks a supply item. Its use is spent from the item when it works. */
    void use(Player p, ItemStack item) {
        Supply s = settings().supply(SupplyItems.idOf(item));
        ActiveRun ar = runs.runOf(p);
        if (s == null) {
            p.sendMessage("§7This supply is no longer used in dungeons.");
            return;
        }
        if (ar == null || !runs.inDungeonWorld(p) || ar.run.state() != DungeonRun.State.RUNNING) {
            p.sendMessage("§7Supplies only work in a dungeon run.");
            return;
        }
        Member m = ar.run.member(p.getUniqueId());
        if (m.status() != Status.ALIVE) {
            p.sendMessage("§7You can't use supplies now.");
            return;
        }
        long now = clock.getAsLong();
        long free = cooldowns.getOrDefault(p.getUniqueId(), Map.of()).getOrDefault(s.use(), 0L);
        if (now < free) {
            p.sendMessage("§7Wait " + (free - now + 999) / 1000 + "s before using another like it.");
            return;
        }
        if (!apply(ar, p, s, now)) {
            return;
        }
        item.setAmount(item.getAmount() - 1);
        if (s.cooldown() > 0) {
            cooldowns.computeIfAbsent(p.getUniqueId(), k -> new EnumMap<>(Supply.Use.class))
                    .put(s.use(), now + s.cooldown() * 1000L);
        }
    }

    private boolean apply(ActiveRun ar, Player p, Supply s, long now) {
        double max = maxHealth(p);
        switch (s.use()) {
            case HEALTH -> {
                if (p.getHealth() >= max) {
                    p.sendMessage("§7You are not hurt.");
                    return false;
                }
                p.setHealth(Math.min(max, p.getHealth() + max * s.amount()));
                p.playSound(p.getLocation(), "minecraft:entity.generic.drink", 1f, 1f);
                return true;
            }
            case MANA -> {
                if (mana == null) {
                    p.sendMessage("§7There is no mana to restore here.");
                    return false;
                }
                double full = mana.max(p);
                if (mana.mana(p) >= full) {
                    p.sendMessage("§7Your mana is full.");
                    return false;
                }
                mana.add(p, full * s.amount());
                p.playSound(p.getLocation(), "minecraft:entity.generic.drink", 1f, 1.2f);
                return true;
            }
            case BANDAGE -> {
                int room = ar.run.member(p.getUniqueId()).room();
                if (bandages.containsKey(p.getUniqueId())) {
                    p.sendMessage("§7Your bandage is already at work.");
                    return false;
                }
                if (room >= 0 && ar.run.room(room) == DungeonRun.Room.ACTIVE) {
                    p.sendMessage("§7There is no time to bind wounds in a fight.");
                    return false;
                }
                if (p.getHealth() >= max) {
                    p.sendMessage("§7You are not hurt.");
                    return false;
                }
                long steps = Math.max(1, s.seconds() * 1000L / RunManager.STEP_MS);
                bandages.put(p.getUniqueId(), new Bandage(max * s.amount() / steps, now + steps * RunManager.STEP_MS));
                p.sendMessage("§aYou bind your wounds. §7Taking damage stops it.");
                return true;
            }
            case REVIVE -> {
                Player down = nearestDowned(ar, p, s.radius());
                if (down == null) {
                    p.sendMessage("§7No downed ally within " + (int) s.radius() + " blocks.");
                    return false;
                }
                return runs.reviveNow(ar, p, down, s.name());
            }
            default -> { // WARD
                if (wards.containsKey(p.getUniqueId())) {
                    p.sendMessage("§7You are already warded.");
                    return false;
                }
                wards.put(p.getUniqueId(), new Ward(s.name(), s.potions(), s.auras()));
                p.sendMessage("§bYou drink the " + s.name() + ". §7It stops the next slowing or freezing effect"
                        + " until your next fight is over.");
                return true;
            }
        }
    }

    private Player nearestDowned(ActiveRun ar, Player p, double radius) {
        Player best = null;
        double bestD = radius;
        for (Player o : runs.online(ar)) {
            Member m = ar.run.member(o.getUniqueId());
            if (o.equals(p) || m.status() != Status.DOWNED || !o.getWorld().equals(p.getWorld())) {
                continue;
            }
            double d = o.getLocation().distance(p.getLocation());
            if (d <= bestD) {
                best = o;
                bestD = d;
            }
        }
        return best;
    }

    private static double maxHealth(Player p) {
        return p.getAttribute(Attribute.MAX_HEALTH).getValue();
    }

    // ------------------------------------------------------------------ over time

    /** A step of a run: bandages heal, and players crouched by the rest room's fire rest. */
    void step(ActiveRun ar, long now) {
        for (Player p : runs.online(ar)) {
            UUID id = p.getUniqueId();
            Member m = ar.run.member(id);
            Bandage b = bandages.get(id);
            if (b != null) {
                if (now >= b.untilMs() || m.status() != Status.ALIVE) {
                    bandages.remove(id);
                } else {
                    p.setHealth(Math.min(maxHealth(p), p.getHealth() + b.perStep()));
                }
            }
            campfire(ar, p, m, now);
        }
    }

    private void campfire(ActiveRun ar, Player p, Member m, long now) {
        UUID id = p.getUniqueId();
        int room = m.room();
        double share = settings().campfireShare(ar.rules.tier());
        if (room < 0 || share <= 0 || m.status() != Status.ALIVE || ar.rested.contains(id) || !p.isSneaking()
                || ar.run.layout().rooms().get(room).node().type() != RoomType.REST || !byFire(ar, room, p)) {
            resting.remove(id);
            return;
        }
        long needed = settings().campfireSeconds() * 1000L;
        long done = resting.merge(id, RunManager.STEP_MS, Long::sum);
        if (done < needed) {
            bars.show(p, "Resting by the fire", (double) done / needed, BossBar.Color.YELLOW, now);
            return;
        }
        resting.remove(id);
        ar.rested.add(id);
        double max = maxHealth(p);
        p.setHealth(Math.min(max, p.getHealth() + max * share));
        if (mana != null) {
            mana.add(p, mana.max(p) * share);
        }
        p.sendMessage("§6You rest by the fire. §7" + (share >= 1 ? "Health and mana restored."
                : (int) Math.round(share * 100) + "% of your health and mana restored.") + " The fire won't warm you again this run.");
    }

    private boolean byFire(ActiveRun ar, int room, Player p) {
        List<double[]> rest = ar.geo.markers(room, "rest");
        double[] at = rest.isEmpty() ? ar.geo.centre(room) : rest.get(0);
        double dx = p.getLocation().getX() - at[0];
        double dz = p.getLocation().getZ() - at[2];
        double r = settings().campfireRadius();
        return dx * dx + dz * dz <= r * r;
    }

    /** A player took damage: a bandage at work stops. */
    void hurt(Player p) {
        if (bandages.remove(p.getUniqueId()) != null) {
            p.sendMessage("§cThe blow tears your bandage loose.");
        }
    }

    /** Whether a ward stops this potion effect type (lower case key) on a player; using it up. */
    boolean wardsPotion(Player p, String type) {
        return wards(p, w -> w.potions().contains(type.toLowerCase(Locale.ROOT)));
    }

    /** Whether a ward stops this MythicMobs aura on a player; using it up. */
    boolean wardsAura(Player p, String aura) {
        return aura != null && wards(p, w -> w.auras().contains(aura.toLowerCase(Locale.ROOT)));
    }

    private boolean wards(Player p, Predicate<Ward> stops) {
        Ward w = wards.get(p.getUniqueId());
        if (w == null || !stops.test(w)) {
            return false;
        }
        wards.remove(p.getUniqueId());
        p.sendMessage("§bYour " + w.name() + " wards it off.");
        return true;
    }

    // ------------------------------------------------------------------ found on the way

    /** A room's fight is over: wards of those in it end, and each player may find a supply. */
    void cleared(ActiveRun ar, int room, RoomType type, boolean big) {
        for (Player p : runs.online(ar)) {
            if (ar.run.member(p.getUniqueId()).room() == room) {
                wards.remove(p.getUniqueId());
            }
        }
        if (type != RoomType.BOSS) {
            drop(ar, big ? settings().eliteDrop() : settings().fightDrop(), "in the fight's aftermath");
        }
    }

    /** A player steps into a treasure room for the first time: they may find a supply there. */
    void treasure(ActiveRun ar, Player p, int room) {
        if (ar.treasureSearched.add(room + ":" + p.getUniqueId())) {
            drop(ar, List.of(p), settings().treasureDrop(), "in the treasure room");
        }
    }

    private void drop(ActiveRun ar, double chance, String where) {
        drop(ar, runs.online(ar), chance, where);
    }

    private void drop(ActiveRun ar, List<Player> players, double chance, String where) {
        List<Supply> pool = new ArrayList<>();
        for (Supply s : settings().items().values()) {
            if (s.forKit(ar.kit.id())) {
                pool.add(s);
            }
        }
        if (pool.isEmpty() || chance <= 0 || lockers() == null) {
            return;
        }
        for (Player p : players) {
            Member m = ar.run.member(p.getUniqueId());
            if (m.status() == Status.OUT || random.getAsDouble() >= chance) {
                continue;
            }
            Supply s = pool.get(Math.min(pool.size() - 1, (int) (random.getAsDouble() * pool.size())));
            lockers().giveSupply(p, s, 1);
            p.sendMessage("§6You find a " + s.name() + " " + where + ".");
        }
    }

    /** Forgets players no longer in a run. */
    void prune(Predicate<UUID> inRun) {
        cooldowns.keySet().removeIf(inRun.negate());
        bandages.keySet().removeIf(inRun.negate());
        wards.keySet().removeIf(inRun.negate());
        resting.keySet().removeIf(inRun.negate());
    }
}
