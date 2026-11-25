package net.tfminecraft.trialrooms.dungeon.combat;

/**
 * Where one attack is: winding up, standing open after the strike, or over. Plain Java; it counts
 * ticks as it is told to.
 */
public final class AttackState {

    /** Where it is. */
    public enum Phase {
        WINDUP, OPEN, OVER
    }

    /** What a tick brought. */
    public enum Step {
        NOTHING, STRIKE, OVER
    }

    private final AttackSpec spec;
    private final int staggerTicks;
    private Phase phase = Phase.WINDUP;
    private double wound;
    private int openLeft;
    private double poiseTaken;
    private boolean broken;

    /** @param staggerTicks how much longer a broken-off attack leaves the enemy open */
    public AttackState(AttackSpec spec, int staggerTicks) {
        this.spec = spec;
        this.staggerTicks = staggerTicks;
    }

    public AttackSpec spec() {
        return spec;
    }

    public Phase phase() {
        return phase;
    }

    /** Whether it was broken off (staggered or interrupted) rather than landed. */
    public boolean broken() {
        return broken;
    }

    /**
     * One tick. {@code slow} is how many times longer the windup takes now (1: as written); the
     * recovery always runs at its own pace.
     */
    public Step tick(double slow) {
        switch (phase) {
            case WINDUP -> {
                wound += 1 / Math.max(1, slow);
                if (wound >= spec.windup() - 1e-9) {
                    phase = spec.recover() > 0 ? Phase.OPEN : Phase.OVER;
                    openLeft = spec.recover();
                    return Step.STRIKE;
                }
                return Step.NOTHING;
            }
            case OPEN -> {
                if (--openLeft <= 0) {
                    phase = Phase.OVER;
                    return Step.OVER;
                }
                return Step.NOTHING;
            }
            default -> {
                return Step.NOTHING;
            }
        }
    }

    /** Damage while winding up; true when it breaks the attack off (a stagger). */
    public boolean hurt(double damage, double maxHealth) {
        if (phase != Phase.WINDUP || !spec.staggers() || !(maxHealth > 0)) {
            return false;
        }
        poiseTaken += damage;
        if (poiseTaken < spec.poise() * maxHealth) {
            return false;
        }
        breakOff();
        return true;
    }

    /** A silence or freeze; true when it breaks the attack off (only a spell's windup). */
    public boolean interrupt() {
        if (phase != Phase.WINDUP || !spec.magic()) {
            return false;
        }
        breakOff();
        return true;
    }

    private void breakOff() {
        broken = true;
        phase = Phase.OPEN;
        openLeft = spec.recover() + staggerTicks;
    }

    /** What share of a hit the enemy takes now: its guard while winding up, more while open. */
    public double taken(double openBonus) {
        return switch (phase) {
            case WINDUP -> spec.guard();
            case OPEN -> 1 + openBonus;
            default -> 1;
        };
    }
}
