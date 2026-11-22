package net.tfminecraft.trialrooms.dungeon.locker;

/** A screen whose every click is its own: nothing can be picked up or carried out of it. */
public interface ClickMenu {

    /** A click on the screen's own slots (raw slot), plain or with shift held. */
    void click(int slot, boolean shift);
}
