package net.tfminecraft.trialrooms.dungeon.fight;

import java.util.List;

/**
 * A boss fight's rhythm: phases by health, one mechanic at a time with quiet gaps, and enrage.
 * It decides what happens and when; the run carries it out in the world.
 */
public final class BossFight {

    private final BossDef def;
    private final FightTuning tuning;
    private final long startMs;
    private int phase;
    private int turn;
    private long nextMechanicMs;
    private long lockedUntilMs;
    private long immuneUntilMs;
    private boolean enraged;
    private long nextEnrageAddsMs;
    private int mechanicHits;
    private final long[] phaseStartMs;

    public BossFight(BossDef def, FightTuning tuning, long now) {
        this.def = def;
        this.tuning = tuning;
        this.startMs = now;
        this.phaseStartMs = new long[def.phases().size()];
        this.phaseStartMs[0] = now;
        this.nextMechanicMs = now + interval(def.phases().getFirst());
    }

    public BossDef def() {
        return def;
    }

    /** The current phase's index, 0 for the first. */
    public int phase() {
        return phase;
    }

    public BossDef.Phase current() {
        return def.phases().get(phase);
    }

    /** When each phase started (0 for phases not reached). */
    public long phaseStartMs(int index) {
        return phaseStartMs[index];
    }

    public long startMs() {
        return startMs;
    }

    /**
     * The boss's health is at {@code share} of its max. When that reaches the next phase's start,
     * the phase begins and is returned: the boss is held at that health and can't be hurt for a
     * moment. Otherwise null.
     */
    public BossDef.Phase crossed(double share, long now) {
        if (phase + 1 >= def.phases().size()) {
            return null;
        }
        BossDef.Phase next = def.phases().get(phase + 1);
        if (share * 100 > next.at() + 1e-6) {
            return null;
        }
        phase++;
        phaseStartMs[phase] = now;
        immuneUntilMs = now + (long) (tuning.phaseImmuneSeconds() * 1000);
        return next;
    }

    /** The share of max health a hit may take the boss down to: the next phase's start, or 0. */
    public double floorShare() {
        return phase + 1 < def.phases().size() ? def.phases().get(phase + 1).at() / 100.0 : 0;
    }

    public boolean immune(long now) {
        return now < immuneUntilMs;
    }

    /** True once, when the boss enrages. */
    public boolean enrage(long now) {
        if (enraged || def.enrageSeconds() == 0 || now - startMs < def.enrageSeconds() * 1000L) {
            return false;
        }
        enraged = true;
        nextEnrageAddsMs = now + tuning.enrageAddsSeconds() * 1000L;
        return true;
    }

    public boolean enraged() {
        return enraged;
    }

    /** Whether an enraged boss calls aid now (not while too many adds stand). */
    public boolean enrageAdds(long now, int addsAlive) {
        if (!enraged || now < nextEnrageAddsMs) {
            return false;
        }
        nextEnrageAddsMs = now + tuning.enrageAddsSeconds() * 1000L;
        return addsAlive < tuning.maxAdds();
    }

    /**
     * The mechanic to start now, or null. Only one runs at a time ({@code busy} while one does),
     * a quiet gap follows each, and starts are never closer than the floor. A call for aid is
     * skipped while too many adds stand.
     */
    public Mechanic next(long now, boolean busy, int addsAlive) {
        if (busy || now < lockedUntilMs || now < nextMechanicMs) {
            return null;
        }
        List<Mechanic> mechanics = current().mechanics();
        nextMechanicMs = now + interval(current());
        for (int i = 0; i < mechanics.size(); i++) {
            Mechanic m = mechanics.get(Math.floorMod(turn++, mechanics.size()));
            if (m != Mechanic.RALLY_CALL || addsAlive < tuning.maxAdds()) {
                return m;
            }
        }
        return null;
    }

    /** A mechanic finished: the quiet gap starts. */
    public void resolved(long now) {
        lockedUntilMs = now + (long) (tuning.gapSeconds() * 1000);
    }

    /** A player was caught by a mechanic. */
    public void hit() {
        mechanicHits++;
    }

    public int mechanicHits() {
        return mechanicHits;
    }

    /** How hard the boss hits: harder once enraged. */
    public double damageFactor() {
        return enraged ? tuning.enrageFactor() : 1;
    }

    private long interval(BossDef.Phase p) {
        return (long) (Math.max(tuning.floorSeconds(), p.everySeconds()) * 1000);
    }
}
