package net.tfminecraft.trialrooms.dungeon.fight;

/** What makes an elite different, each with something players can do about it. */
public enum Affix {
    /** Takes far less damage while any other enemy of its wave stands: kill the pack first. */
    SHIELDED("Shielded", "takes far less damage while its allies stand"),
    /** Bursts a few seconds after it dies: step away from its remains. */
    VOLATILE("Volatile", "bursts soon after it dies: step away from its remains"),
    /** Every few seconds, nearby allies hit harder for a while: kill it first or spread the pack. */
    RALLYING("Rallying", "makes nearby allies hit harder every few seconds");

    private final String title;
    private final String hint;

    Affix(String title, String hint) {
        this.title = title;
        this.hint = hint;
    }

    /** The word in front of the elite's name. */
    public String title() {
        return title;
    }

    /** What players are told about it when the elite appears. */
    public String hint() {
        return hint;
    }
}
