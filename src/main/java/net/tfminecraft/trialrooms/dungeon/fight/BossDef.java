package net.tfminecraft.trialrooms.dungeon.fight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A boss: its mob, its phases and when it enrages.
 *
 * @param id            name in the kit
 * @param mob           MythicMobs type
 * @param name          what players see and read
 * @param health        base max health, before party scaling
 * @param phases        phases from full health down; the first starts at 100
 * @param enrageSeconds after this long the boss hits harder and calls adds now and then
 */
public record BossDef(String id, String mob, String name, double health, List<Phase> phases, int enrageSeconds) {

    /**
     * One phase.
     *
     * @param at           health percent at which it starts
     * @param adds         threat budget of the adds that come when it starts
     * @param mechanics    mechanics it uses, in turn
     * @param everySeconds seconds between the start of one mechanic and the next
     */
    public record Phase(int at, double adds, List<Mechanic> mechanics, int everySeconds) {

        public Phase {
            if (at < 1 || at > 100 || adds < 0 || everySeconds < 1) {
                throw new IllegalArgumentException("phase at " + at + ": at must be 1-100, adds not negative,"
                        + " every at least 1");
            }
            mechanics = List.copyOf(mechanics);
        }
    }

    public BossDef {
        if (mob == null || mob.isBlank() || health <= 0 || enrageSeconds < 0) {
            throw new IllegalArgumentException(id + ": a boss needs a mob, health above 0 and enrage not negative");
        }
        List<Phase> sorted = new ArrayList<>(phases);
        sorted.sort(Comparator.comparingInt(Phase::at).reversed());
        if (sorted.isEmpty() || sorted.getFirst().at() != 100) {
            throw new IllegalArgumentException(id + ": the first phase must start at 100");
        }
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).at() == sorted.get(i - 1).at()) {
                throw new IllegalArgumentException(id + ": two phases start at " + sorted.get(i).at());
            }
        }
        phases = List.copyOf(sorted);
        name = name == null || name.isBlank() ? id : name;
    }
}
