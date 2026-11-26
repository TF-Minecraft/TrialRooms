package net.tfminecraft.trialrooms.dungeon.allies;

import java.util.function.Function;
import java.util.function.Predicate;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * No friendly fire of any kind inside dungeons, on either side: help cast by a player (or by something
 * a player summoned) never reaches a mob, and harm cast by a player never reaches another player or
 * another player's summon; harm cast by a run's enemy never reaches another enemy, and its help never
 * reaches a player or a player's summon. What a caster does to itself, what something that is neither
 * a player's nor a run's enemy casts, and everything outside the dungeon world, are left as they are.
 */
public final class AllyRules {

    private final Predicate<Entity> inDungeon;
    private final Function<Entity, Player> owner;
    private final Predicate<Entity> enemy;

    /** As below, with every entity that is no player and no player's summon counting as an enemy. */
    public AllyRules(Predicate<Entity> inDungeon, Function<Entity, Player> owner) {
        this(inDungeon, owner, e -> !(e instanceof Player) && owner.apply(e) == null);
    }

    /**
     * @param inDungeon whether an entity is in the dungeon world
     * @param owner     the player behind a caster (the player itself, or a summon's owner), or null
     * @param enemy     whether an entity is a run's enemy (never a player or a player's summon)
     */
    public AllyRules(Predicate<Entity> inDungeon, Function<Entity, Player> owner, Predicate<Entity> enemy) {
        this.inDungeon = inDungeon;
        this.owner = owner;
        this.enemy = enemy;
    }

    /** Whether an effect of this kind from {@code caster} may land on {@code target}. */
    public boolean allows(EffectKind kind, Entity caster, Entity target) {
        if (kind == EffectKind.NONE || caster == null || target == null || !inDungeon.test(target)) {
            return true;
        }
        Player player = owner.apply(caster);
        if (player == null) { // an enemy's: on itself anything, else harm for the players' side, help for enemies
            return !enemy.test(caster) || caster.equals(target) || (kind == EffectKind.SUPPORT) == enemy.test(target);
        }
        Player side = target instanceof Player p ? p : owner.apply(target);
        if (kind == EffectKind.SUPPORT) {
            return side != null;
        }
        return side == null || side.equals(player);
    }
}
