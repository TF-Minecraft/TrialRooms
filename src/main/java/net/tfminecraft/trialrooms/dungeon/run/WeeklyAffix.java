package net.tfminecraft.trialrooms.dungeon.run;

/** The weekly affixes: one modifier on Hard, two on Nightmare, the same for everyone each week. */
public enum WeeklyAffix {
    /** Ordinary enemies have more health. */
    FORTIFIED("Fortified", "ordinary enemies are tougher"),
    /** Bosses have more health and hit harder. */
    TYRANNICAL("Tyrannical", "bosses are tougher and hit harder"),
    /** Every enemy bursts soon after it dies. */
    VOLATILE("Volatile", "every enemy bursts soon after it dies: step away from the remains"),
    /** Wounded enemies hit harder. */
    RAGING("Raging", "badly wounded enemies hit much harder: finish them fast"),
    /** Each death makes the enemies nearby stronger. */
    BOLSTERING("Bolstering", "each enemy that dies makes those near it stronger: kill them together");

    private final String title;
    private final String hint;

    WeeklyAffix(String title, String hint) {
        this.title = title;
        this.hint = hint;
    }

    public String title() {
        return title;
    }

    public String hint() {
        return hint;
    }
}
