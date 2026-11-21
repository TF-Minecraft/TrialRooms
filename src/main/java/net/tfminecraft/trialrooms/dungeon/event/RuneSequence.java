package net.tfminecraft.trialrooms.dungeon.event;

import java.util.Random;

/** The order of a rune room's stones, and how far the players have got through it. */
public final class RuneSequence {

    public enum Press {
        /** The right stone; more to go. */
        RIGHT,
        /** The wrong stone: back to the start. */
        WRONG,
        /** The last stone of the order (or a touch after it was solved). */
        SOLVED
    }

    private final int[] order;
    private int next;
    private boolean solved;

    /** An order of {@code length} touches over {@code stones} stones, never the same stone twice in a row. */
    public RuneSequence(int stones, int length, Random rng) {
        if (stones < 2 || length < 1) {
            throw new IllegalArgumentException("a rune order needs at least 2 stones and 1 touch");
        }
        order = new int[length];
        for (int i = 0; i < length; i++) {
            int s = rng.nextInt(i == 0 ? stones : stones - 1);
            if (i > 0 && s >= order[i - 1]) {
                s++;
            }
            order[i] = s;
        }
    }

    public Press press(int stone) {
        if (solved) {
            return Press.SOLVED;
        }
        if (order[next] != stone) {
            next = 0;
            return Press.WRONG;
        }
        next++;
        solved = next == order.length;
        return solved ? Press.SOLVED : Press.RIGHT;
    }

    /** Back to the first touch, as the order is shown again. */
    public void restart() {
        next = 0;
    }

    public int length() {
        return order.length;
    }

    public int stone(int step) {
        return order[step];
    }

    public int progress() {
        return next;
    }

    public boolean solved() {
        return solved;
    }
}
