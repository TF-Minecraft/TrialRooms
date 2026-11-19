package net.tfminecraft.trialrooms.dungeon.fight;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;

/**
 * The numbers behind a kit's fights: budgets, waves, elites and boss timings. Party size never
 * changes any of them; it only scales enemy health.
 *
 * @param budgetCombat       threat budget of a combat room
 * @param budgetElite        threat budget of an elite room (its elite included)
 * @param depthStep          extra budget per fight room already cleared, as a fraction
 * @param tierFactors        budget multiplier per tier, tier 1 first
 * @param waveSplit          a budget at least this big comes in two waves
 * @param thirdWave          a budget at least this big comes in three waves
 * @param waveRemain         the next wave is called when this share of the current one is left
 * @param waveTimeoutSeconds ...or after this long, if no more than {@code waveTimeoutAlive} of it is left
 * @param waveTimeoutAlive   share of a wave that may still stand for the timeout to call the next
 * @param waveWarningSeconds warning before a wave arrives
 * @param maxAlive           most enemies in one wave; the rest come in an extra wave
 * @param rangedShare        most ranged and caster enemies in a wave, as a share
 * @param eliteChance        chance that a combat room's strongest enemy is an elite
 * @param eliteMinDepth      fight rooms cleared before combat rooms may have an elite
 * @param eliteMinCost       cheapest roster cost an elite can be made from
 * @param eliteHealth        elite health multiplier
 * @param healthCap          most an enemy's health is multiplied by in all (party and elite)
 * @param stallSeconds       an enemy nobody has hit for this long is moved to where players are
 * @param slamSeconds        warning before a slam lands
 * @param shockwaveSeconds   warning before a shockwave
 * @param burstSeconds       warning before a volatile elite's remains burst
 * @param gapSeconds         quiet time after one mechanic before the next may start
 * @param floorSeconds       least time between the starts of two mechanics, enraged or not
 * @param slamRadius         slam mark radius
 * @param shockwaveRadius    shockwave reach around the boss
 * @param burstRadius        reach of a volatile burst
 * @param slamDamage         slam damage as a share of the victim's max health
 * @param shockwaveDamage    shockwave damage as a share of max health
 * @param burstDamage        burst damage as a share of max health
 * @param tierDamageStep     extra mechanic damage share per tier above 1
 * @param shieldReduction    damage a shielded elite ignores while its allies stand
 * @param rallyFactor        damage multiplier of rallied enemies
 * @param rallyEverySeconds  seconds between rallies
 * @param rallySeconds       how long a rally lasts
 * @param rallyRadius        reach of a rally
 * @param phaseImmuneSeconds a boss can't be hurt this long after a phase starts
 * @param rallyCallAdds      threat budget of a boss's call for aid
 * @param maxAdds            no call for aid while this many adds stand
 * @param enrageFactor       damage multiplier of an enraged boss
 * @param enrageAddsSeconds  an enraged boss calls aid this often
 */
public record FightTuning(double budgetCombat, double budgetElite, double depthStep, List<Double> tierFactors,
        double waveSplit, double thirdWave, double waveRemain, int waveTimeoutSeconds, double waveTimeoutAlive,
        int waveWarningSeconds, int maxAlive, double rangedShare, double eliteChance, int eliteMinDepth,
        double eliteMinCost, double eliteHealth, double healthCap, int stallSeconds, double slamSeconds,
        double shockwaveSeconds, double burstSeconds, double gapSeconds, double floorSeconds, double slamRadius,
        double shockwaveRadius, double burstRadius, double slamDamage, double shockwaveDamage, double burstDamage,
        double tierDamageStep, double shieldReduction, double rallyFactor, int rallyEverySeconds, int rallySeconds,
        double rallyRadius, double phaseImmuneSeconds, double rallyCallAdds, int maxAdds, double enrageFactor,
        int enrageAddsSeconds) {

    /** Warnings are never shorter than this, whatever a kit says. */
    public static final double MIN_WARNING = 2.5;

    public static final FightTuning DEFAULT = new FightTuning(6, 8, 0.15, List.of(1.0, 1.35, 1.8), 9, 16, 0.25, 75,
            0.5, 5, 24, 0.4, 0.35, 2, 2, 2.0, 4.0, 20, 3, 4, 3, 2, 8, 4, 6, 4, 0.35, 0.45, 0.30, 0.05, 0.6, 1.2, 10, 5,
            8, 2, 3, 4, 1.25, 30);

    public FightTuning {
        tierFactors = List.copyOf(tierFactors.isEmpty() ? List.of(1.0) : tierFactors);
        slamSeconds = Math.max(MIN_WARNING, slamSeconds);
        shockwaveSeconds = Math.max(MIN_WARNING, shockwaveSeconds);
        burstSeconds = Math.max(MIN_WARNING, burstSeconds);
        waveWarningSeconds = Math.max(3, waveWarningSeconds);
        floorSeconds = Math.max(4, floorSeconds);
        maxAlive = Math.max(1, maxAlive);
        healthCap = Math.max(1, healthCap);
    }

    /** Budget multiplier for a tier (1 and up); tiers past the list use its last value. */
    public double tierFactor(int tier) {
        return tierFactors.get(Math.min(Math.max(tier, 1), tierFactors.size()) - 1);
    }

    /** Mechanic damage share for a tier. */
    public double damage(double base, int tier) {
        return base + tierDamageStep * Math.max(0, tier - 1);
    }

    /** Reads a kit's {@code fights:} section; anything missing keeps its default. */
    public static FightTuning from(ConfigurationSection s) {
        FightTuning d = DEFAULT;
        if (s == null) {
            return d;
        }
        List<Double> tiers = new ArrayList<>();
        for (Object o : s.getList("tier-factors", d.tierFactors())) {
            tiers.add(((Number) o).doubleValue());
        }
        return new FightTuning(s.getDouble("budget-combat", d.budgetCombat()), s.getDouble("budget-elite",
                d.budgetElite()), s.getDouble("depth-step", d.depthStep()), tiers, s.getDouble("wave-split",
                d.waveSplit()), s.getDouble("third-wave", d.thirdWave()), s.getDouble("wave-remain", d.waveRemain()),
                s.getInt("wave-timeout-seconds", d.waveTimeoutSeconds()), s.getDouble("wave-timeout-alive",
                d.waveTimeoutAlive()), s.getInt("wave-warning-seconds", d.waveWarningSeconds()), s.getInt(
                "max-alive", d.maxAlive()), s.getDouble("ranged-share", d.rangedShare()), s.getDouble(
                "elite-chance", d.eliteChance()), s.getInt("elite-min-depth", d.eliteMinDepth()), s.getDouble(
                "elite-min-cost", d.eliteMinCost()), s.getDouble("elite-health", d.eliteHealth()), s.getDouble(
                "health-cap", d.healthCap()), s.getInt("stall-seconds", d.stallSeconds()), s.getDouble(
                "slam-seconds", d.slamSeconds()), s.getDouble("shockwave-seconds", d.shockwaveSeconds()),
                s.getDouble("burst-seconds", d.burstSeconds()), s.getDouble("gap-seconds", d.gapSeconds()),
                s.getDouble("floor-seconds", d.floorSeconds()), s.getDouble("slam-radius", d.slamRadius()),
                s.getDouble("shockwave-radius", d.shockwaveRadius()), s.getDouble("burst-radius", d.burstRadius()),
                s.getDouble("slam-damage", d.slamDamage()), s.getDouble("shockwave-damage", d.shockwaveDamage()),
                s.getDouble("burst-damage", d.burstDamage()), s.getDouble("tier-damage-step", d.tierDamageStep()),
                s.getDouble("shield-reduction", d.shieldReduction()), s.getDouble("rally-factor", d.rallyFactor()),
                s.getInt("rally-every-seconds", d.rallyEverySeconds()), s.getInt("rally-seconds", d.rallySeconds()),
                s.getDouble("rally-radius", d.rallyRadius()), s.getDouble("phase-immune-seconds",
                d.phaseImmuneSeconds()), s.getDouble("rally-call-adds", d.rallyCallAdds()), s.getInt("max-adds",
                d.maxAdds()), s.getDouble("enrage-factor", d.enrageFactor()), s.getInt("enrage-adds-seconds",
                d.enrageAddsSeconds()));
    }
}
