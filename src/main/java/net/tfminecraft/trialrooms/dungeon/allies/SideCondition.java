package net.tfminecraft.trialrooms.dungeon.allies;

import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.api.skills.conditions.ISkillMetaComparisonCondition;
import io.lumine.mythic.core.skills.SkillCondition;

/**
 * A target condition TrialRooms adds to MythicMobs mechanics: it drops targets that
 * {@link AllyRules} keeps out of reach of the mechanic's effect.
 */
public final class SideCondition extends SkillCondition implements ISkillMetaComparisonCondition {

    private final EffectKind kind;
    private final AllyRules rules;

    public SideCondition(EffectKind kind, AllyRules rules) {
        super("trialrooms_dungeon_side");
        this.kind = kind;
        this.rules = rules;
    }

    public EffectKind kind() {
        return kind;
    }

    @Override
    public boolean check(SkillMetadata meta, AbstractEntity target) {
        return rules.allows(kind, meta.getCaster().getEntity().getBukkitEntity(), target.getBukkitEntity());
    }
}
