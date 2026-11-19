package net.tfminecraft.trialrooms.dungeon.allies;

import java.util.function.Function;
import java.util.function.Predicate;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * No friendly fire of any kind inside dungeons: help cast by a player (or by something a player
 * summoned) never reaches a mob, and harm cast by a player never reaches another player. Mob
 * skills and everything outside the dungeon world are left as they are.
 */
public final class AllyRules {

    private final Predicate<Entity> inDungeon;
    private final Function<Entity, Player> owner;

    /**
     * @param inDungeon whether an entity is in the dungeon world
     * @param owner     the player behind a caster (the player itself, or a summon's owner), or null
     */
    public AllyRules(Predicate<Entity> inDungeon, Function<Entity, Player> owner) {
        this.inDungeon = inDungeon;
        this.owner = owner;
    }

    /** Whether an effect of this kind from {@code caster} may land on {@code target}. */
    public boolean allows(EffectKind kind, Entity caster, Entity target) {
        if (kind == EffectKind.NONE || caster == null || target == null || !inDungeon.test(target)) {
            return true;
        }
        Player player = owner.apply(caster);
        if (player == null) {
            return true;
        }
        if (kind == EffectKind.SUPPORT) {
            return target instanceof Player;
        }
        return !(target instanceof Player) || target.equals(player);
    }
}
