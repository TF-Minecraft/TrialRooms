package net.tfminecraft.trialrooms.dungeon.allies;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicAuraStartEvent;
import io.lumine.mythic.bukkit.events.MythicHealMechanicEvent;
import io.lumine.mythic.bukkit.events.MythicReloadedEvent;
import io.lumine.mythic.core.mobs.ActiveMob;
import io.lumine.mythic.core.skills.AbstractSkill;
import io.lumine.mythic.core.skills.SkillCondition;
import io.lumine.mythic.core.skills.SkillMechanic;

/**
 * Keeps class skills from helping mobs or harming allies inside dungeons.
 *
 * <p>Class skills are MythicMobs skills with no ally filter (positioning is the counter in PvP).
 * In the dungeon world this adds a {@link SideCondition} to every MythicMobs mechanic that helps
 * or harms (heals, shields, cleanses, buff and debuff potions, damage, throws, pulls, ignites,
 * and auras named in the settings), and filters auras and heals through MythicMobs' own events.
 * Nothing changes outside the dungeon world or for skills cast by mobs.
 */
public final class AllyFilter implements Listener {

    private final AllyRules rules;
    private final Supplier<Set<String>> harmfulAuras;
    private final Supplier<Set<String>> helpfulAuras;
    private final Supplier<Collection<?>> mechanics;
    private final Logger log;
    private boolean warned;

    /**
     * @param mechanics every MythicMobs mechanic; {@link #mechanicCache()} on a server
     */
    public AllyFilter(AllyRules rules, Supplier<Set<String>> harmfulAuras, Supplier<Set<String>> helpfulAuras,
            Supplier<Collection<?>> mechanics, Logger log) {
        this.rules = rules;
        this.harmfulAuras = harmfulAuras;
        this.helpfulAuras = helpfulAuras;
        this.mechanics = mechanics;
        this.log = log;
    }

    /**
     * Adds the side condition to every helping or harming mechanic that lacks it. Safe to call
     * again (after a MythicMobs reload, or to catch mechanics parsed later). Returns how many it
     * added; when MythicMobs' internals can't be reached it warns once and the event filters stay.
     */
    public int attach() {
        int added = 0;
        try {
            Field targets = field(AbstractSkill.class, "conditionsTarget");
            for (Object o : mechanics.get()) {
                if (o instanceof SkillMechanic m) {
                    added += attach(m, targets);
                }
            }
        } catch (RuntimeException e) {
            if (!warned) {
                warned = true;
                log.warning("[dungeons] the ally filter could not reach MythicMobs mechanics (" + e
                        + "); only auras and heals are filtered");
            }
        }
        return added;
    }

    /** For staff: how many mechanics there are, how they were sorted, and how many carry the filter. */
    public String report() {
        int support = 0;
        int harm = 0;
        int filtered = 0;
        Collection<?> all = mechanics.get();
        Field targets = field(AbstractSkill.class, "conditionsTarget");
        for (Object o : all) {
            SkillMechanic m = (SkillMechanic) o;
            EffectKind kind = EffectKind.of(m.getClass().getSimpleName(), m.getConfigLine(), harmfulAuras.get(),
                    helpfulAuras.get());
            support += kind == EffectKind.SUPPORT ? 1 : 0;
            harm += kind == EffectKind.HARM ? 1 : 0;
            List<?> list = (List<?>) read(targets, m);
            filtered += list != null && list.stream().anyMatch(SideCondition.class::isInstance) ? 1 : 0;
        }
        return all.size() + " mechanics: " + support + " help, " + harm + " harm, " + filtered + " filtered";
    }

    @SuppressWarnings("unchecked")
    private int attach(SkillMechanic m, Field targets) {
        EffectKind kind = EffectKind.of(m.getClass().getSimpleName(), m.getConfigLine(), harmfulAuras.get(),
                helpfulAuras.get());
        if (kind == EffectKind.NONE) {
            return 0;
        }
        List<SkillCondition> list = (List<SkillCondition>) read(targets, m);
        if (list == null) {
            list = new ArrayList<>();
            write(targets, m, list);
        }
        for (SkillCondition c : list) {
            if (c instanceof SideCondition) {
                return 0;
            }
        }
        list.add(new SideCondition(kind, rules));
        return 1;
    }

    /** Every mechanic of every MythicMobs skill, inline sub-skills included. */
    public static Collection<?> mechanicCache() {
        return mechanicsIn(MythicBukkit.inst().getSkillManager().getSkills());
    }

    /** Only objects from MythicMobs' skill packages are searched field by field. */
    static final String SKILL_PACKAGE = "io.lumine.mythic.core.skills";

    /**
     * Finds every {@link SkillMechanic} reachable from {@code roots}: through collections, maps,
     * arrays and optionals, and through the fields of MythicMobs skill objects (skills, their
     * action lists and internal queues, and inline sub-skills). Executors and managers are not
     * entered, so the search stays within the skills themselves.
     */
    public static List<SkillMechanic> mechanicsIn(Object roots) {
        List<SkillMechanic> found = new ArrayList<>();
        walk(roots, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), found);
        return found;
    }

    private static void walk(Object o, Set<Object> seen, List<SkillMechanic> found) {
        if (o == null || !seen.add(o)) {
            return;
        }
        if (o instanceof SkillMechanic m) {
            found.add(m);
        }
        if (o instanceof Collection<?> c) {
            for (Object x : c) {
                walk(x, seen, found);
            }
        } else if (o instanceof Map<?, ?> map) {
            for (Object x : map.values()) {
                walk(x, seen, found);
            }
        } else if (o instanceof java.util.Optional<?> opt) {
            walk(opt.orElse(null), seen, found);
        } else if (o instanceof Object[] array) {
            for (Object x : array) {
                walk(x, seen, found);
            }
        } else if (o.getClass().getName().startsWith(SKILL_PACKAGE) && !o.getClass().getName().contains("Executor")) {
            for (Class<?> c = o.getClass(); c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) && !f.getType().isPrimitive()) {
                        walk(read(f, o), seen, found);
                    }
                }
            }
        }
    }

    /** A declared field, made accessible. */
    static Field field(Class<?> type, String name) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(type.getSimpleName() + " has no field " + name, e);
        }
    }

    /** A field's value, or null when it can't be read. */
    static Object read(Field f, Object o) {
        try {
            f.setAccessible(true);
            return f.get(o);
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    static void write(Field f, Object o, Object value) {
        try {
            f.set(o, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The player behind a caster: the player, or the owner or parent of a summoned mob. */
    public static Player ownerOf(Entity caster) {
        if (caster instanceof Player p) {
            return p;
        }
        ActiveMob mob = MythicBukkit.inst().getMobManager().getActiveMob(caster.getUniqueId()).orElse(null);
        if (mob == null) {
            return null;
        }
        UUID id = mob.getOwnerUUID().isPresent() ? mob.getOwnerUUID().get()
                : mob.getParentUUID().isPresent() ? mob.getParentUUID().get() : null;
        return id == null ? null : Bukkit.getPlayer(id);
    }

    @EventHandler
    public void onReload(MythicReloadedEvent event) {
        int added = attach();
        log.info("[dungeons] ally filter on " + added + " MythicMobs mechanics after a reload");
    }

    @EventHandler(ignoreCancelled = true)
    public void onAura(MythicAuraStartEvent event) {
        EffectKind kind = EffectKind.aura(event.getAuraName(), harmfulAuras.get(), helpfulAuras.get());
        Entity target = event.getTargetEntity().map(e -> e.getBukkitEntity()).orElse(null);
        Entity caster = event.getCaster() == null ? null : event.getCaster().getEntity().getBukkitEntity();
        if (!rules.allows(kind, caster, target)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHeal(MythicHealMechanicEvent event) {
        Entity caster = event.getMetadata().getCaster().getEntity().getBukkitEntity();
        if (!rules.allows(EffectKind.SUPPORT, caster, event.getTarget())) {
            event.setCancelled(true);
        }
    }
}
