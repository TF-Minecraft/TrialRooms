package net.tfminecraft.trialrooms.dungeon.gear;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.Server;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import net.tfminecraft.trialrooms.dungeon.gear.GearSet.Kind;
import net.tfminecraft.trialrooms.dungeon.gear.GearTags.Piece;
import net.tfminecraft.trialrooms.dungeon.locker.Accessories;

/**
 * Set bonuses and weapon traits at work, only inside dungeons: what each player there wears and holds
 * is looked at twice a second; their bonuses go on as stats and come off when they leave.
 */
public final class GearService implements Listener {

    /** Puts stats on a player (replacing the ones put on before; none: takes them off). */
    public interface Stats {
        void set(Player player, Map<String, Double> stats);
    }

    static final long COMPOSED_MS = 5000;
    static final long WARD_MS = 4000;
    static final long RADIANCE_MS = 3000;
    static final double NEAR = 8;

    /** What a player's traits remember. */
    private static final class State {
        GearBonuses.Result result = GearBonuses.Result.NONE;
        Map<String, Double> applied = Map.of();
        int hits;
        UUID target;
        int streak;
        long hurtAt;
        long wardUntil;
        long radianceAt;
    }

    private final Predicate<Player> inDungeon;
    private final Predicate<Player> up;
    private final Predicate<Entity> enemy;
    private final Supplier<Map<String, GearSet>> sets;
    private final Supplier<Map<String, Double>> worth;
    private final Stats stats;
    private final LongSupplier clock;
    private final Server server;
    private final Map<UUID, State> states = new HashMap<>();

    /**
     * @param inDungeon whether a player is inside a dungeon
     * @param up        whether a player can be healed (not downed)
     * @param enemy     whether an entity is a dungeon's enemy
     */
    public GearService(Server server, Predicate<Player> inDungeon, Predicate<Player> up, Predicate<Entity> enemy,
            Supplier<Map<String, GearSet>> sets, Supplier<Map<String, Double>> worth, Stats stats, LongSupplier clock) {
        this.server = server;
        this.inDungeon = inDungeon;
        this.up = up;
        this.enemy = enemy;
        this.sets = sets;
        this.worth = worth;
        this.stats = stats;
        this.clock = clock;
    }

    /** Looks at every player again (run twice a second). */
    public void tick() {
        for (Player p : server.getOnlinePlayers()) {
            if (inDungeon.test(p)) {
                update(p);
            } else {
                clear(p);
            }
        }
    }

    /** What a player's dungeon gear gives them now. */
    public GearBonuses.Result result(Player p) {
        State s = states.get(p.getUniqueId());
        return s == null ? GearBonuses.Result.NONE : s.result;
    }

    private void update(Player p) {
        GearBonuses.Result r = GearBonuses.of(worn(p), sets.get(), worth.get());
        if (r.traits().isEmpty() && r.stats().isEmpty() && !states.containsKey(p.getUniqueId())) {
            return;
        }
        State s = states.computeIfAbsent(p.getUniqueId(), id -> new State());
        s.result = r;
        if (!s.applied.equals(r.stats())) {
            s.applied = r.stats();
            stats.set(p, r.stats());
        }
    }

    private void clear(Player p) {
        State s = states.remove(p.getUniqueId());
        if (s != null && !s.applied.isEmpty()) {
            stats.set(p, Map.of());
        }
    }

    /** Takes everyone's bonuses off (the plugin stops). */
    public void stop() {
        server.getOnlinePlayers().forEach(this::clear);
        states.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    /** The set pieces a player wears and holds. Each counts only where it belongs. */
    static List<Piece> worn(Player p) {
        PlayerInventory inv = p.getInventory();
        List<Piece> out = new ArrayList<>();
        for (int slot = 36; slot <= 39; slot++) {
            add(out, inv.getItem(slot), Kind.ARMOUR);
        }
        for (int slot : Accessories.SLOTS) {
            add(out, inv.getItem(slot), Kind.JEWELLERY);
        }
        add(out, inv.getItemInMainHand(), Kind.WEAPON);
        add(out, inv.getItemInOffHand(), Kind.OFF_HAND);
        return out;
    }

    private static void add(List<Piece> out, ItemStack item, Kind kind) {
        Piece piece = GearTags.read(item);
        if (piece != null && piece.kind() == kind) {
            out.add(piece);
        }
    }

    // ---- traits

    private State active(Entity e) {
        return e instanceof Player p && inDungeon.test(p) ? states.get(p.getUniqueId()) : null;
    }

    private static Entity attacker(EntityDamageByEntityEvent event) {
        return event.getDamager() instanceof Projectile shot && shot.getShooter() instanceof Entity by ? by : event.getDamager();
    }

    /** Before a fight judges the hit (shielded elites, boss phases: HIGH), after its own say over enemies (LOWEST). */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity by = attacker(event);
        State out = active(by);
        if (out != null && event.getEntity() instanceof LivingEntity victim && enemy.test(victim)) {
            event.setDamage(event.getDamage() * dealt(out, victim));
            hit((Player) by, out, victim, event.getDamage());
        }
        State in = active(event.getEntity());
        if (in != null && enemy.test(by)) {
            event.setDamage(event.getDamage() * taken((Player) event.getEntity(), in));
            hurt(in, by);
        }
    }

    /** What a player's traits make their hit worth. */
    private double dealt(State s, LivingEntity victim) {
        GearBonuses.Result r = s.result;
        double share = victim.getHealth() / max(victim);
        double more = 0;
        if (share >= 0.99) {
            more += r.value(Trait.Kind.OPENING);
        }
        if (share < 0.4) {
            more += r.value(Trait.Kind.EXECUTE);
        }
        if (clock.getAsLong() - s.hurtAt >= COMPOSED_MS) {
            more += r.value(Trait.Kind.COMPOSED);
        }
        s.streak = victim.getUniqueId().equals(s.target) ? Math.min(4, s.streak + 1) : 0;
        s.target = victim.getUniqueId();
        return 1 + more + r.value(Trait.Kind.DUEL) * s.streak / 4;
    }

    /** What a hit sets off. */
    private void hit(Player by, State s, LivingEntity victim, double damage) {
        GearBonuses.Result r = s.result;
        s.hits++;
        double burn = r.value(Trait.Kind.BURN);
        if (burn > 0 && s.hits % 4 == 0) {
            victim.setFireTicks(Math.max(victim.getFireTicks(), 40));
            harm(victim, damage * burn);
        }
        double shards = r.value(Trait.Kind.SHARDS);
        if (shards > 0 && s.hits % 5 == 0) {
            for (Entity e : victim.getNearbyEntities(3, 2, 3)) {
                if (e instanceof LivingEntity near && enemy.test(near)) {
                    harm(near, damage * shards);
                }
            }
        }
        double chill = r.value(Trait.Kind.CHILL);
        if (chill > 0 && s.hits % 3 == 0) {
            slow(victim, chill);
        }
        double radiance = r.value(Trait.Kind.RADIANCE);
        long now = clock.getAsLong();
        if (radiance > 0 && now - s.radianceAt >= RADIANCE_MS) {
            s.radianceAt = now;
            heal(mostHurt(by), radiance);
        }
    }

    /** What a player's traits leave of a hit on them. */
    private double taken(Player p, State s) {
        GearBonuses.Result r = s.result;
        double less = clock.getAsLong() < s.wardUntil ? r.value(Trait.Kind.WARD) : 0;
        if (p.getHealth() < max(p) / 2 || allies(p).size() > 1) {
            less += r.value(Trait.Kind.BRACE);
        }
        return Math.max(0.2, 1 - less);
    }

    private void hurt(State s, Entity by) {
        long now = clock.getAsLong();
        s.hurtAt = now;
        if (now >= s.wardUntil + WARD_MS) { // (as long without it as with it)
            s.wardUntil = now + WARD_MS;
        }
        double rime = s.result.value(Trait.Kind.RIME);
        if (rime > 0 && by instanceof LivingEntity foe) {
            slow(foe, rime);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        State s = killer == null ? null : active(killer);
        double mend = s == null ? 0 : s.result.value(Trait.Kind.MEND);
        if (mend > 0 && enemy.test(event.getEntity())) {
            heal(killer, mend);
        }
    }

    /** Damage with no attacker, so it never sets off traits again. */
    private static void harm(LivingEntity e, double damage) {
        e.setNoDamageTicks(0);
        e.damage(damage);
    }

    private static void slow(LivingEntity e, double seconds) {
        e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int) (seconds * 20), 0, false, false));
    }

    private void heal(Player p, double share) {
        if (up.test(p) && !p.isDead()) {
            p.setHealth(Math.min(max(p), p.getHealth() + max(p) * share));
        }
    }

    /** Dungeon players near one, themselves included. */
    private List<Player> allies(Player p) {
        List<Player> out = new ArrayList<>(List.of(p));
        for (Entity e : p.getNearbyEntities(NEAR, NEAR, NEAR)) {
            if (e instanceof Player other && inDungeon.test(other)) {
                out.add(other);
            }
        }
        return out;
    }

    private Player mostHurt(Player p) {
        return allies(p).stream().filter(up.or(a -> a == p)).min(java.util.Comparator.comparingDouble(a -> a.getHealth() / max(a))).orElseThrow();
    }

    private static double max(LivingEntity e) {
        return e.getAttribute(Attribute.MAX_HEALTH).getValue();
    }
}
