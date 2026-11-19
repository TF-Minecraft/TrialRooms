package net.tfminecraft.trialrooms.dungeon.grid;

/**
 * One of the eight ways a room template can be placed: a clockwise quarter-turn count,
 * optionally after mirroring north and south. This matches vanilla structure placement,
 * which applies the mirror ({@code LEFT_RIGHT}) first and then the rotation.
 *
 * <p>Positions are normalised: an oriented footprint always starts at (0, 0), so a
 * template position maps straight into the cell or block box the room occupies.
 */
public enum Orientation {
    R0(0, false),
    R90(1, false),
    R180(2, false),
    R270(3, false),
    M0(0, true),
    M90(1, true),
    M180(2, true),
    M270(3, true);

    private final int quarterTurns;
    private final boolean mirrored;

    Orientation(int quarterTurns, boolean mirrored) {
        this.quarterTurns = quarterTurns;
        this.mirrored = mirrored;
    }

    public int quarterTurns() {
        return quarterTurns;
    }

    public boolean mirrored() {
        return mirrored;
    }

    /** Whether this orientation swaps the footprint's width and depth. */
    public boolean swapsAxes() {
        return quarterTurns % 2 == 1;
    }

    public int orientedWidth(int width, int depth) {
        return swapsAxes() ? depth : width;
    }

    public int orientedDepth(int width, int depth) {
        return swapsAxes() ? width : depth;
    }

    /**
     * Maps a template position inside a {@code width} x {@code depth} footprint to its
     * position inside the oriented footprint. Works for cells and for blocks alike.
     *
     * @return {@code {x, z}}
     */
    public int[] transform(int x, int z, int width, int depth) {
        int mz = mirrored ? depth - 1 - z : z;
        return switch (quarterTurns) {
            case 0 -> new int[] { x, mz };
            case 1 -> new int[] { depth - 1 - mz, x };
            case 2 -> new int[] { width - 1 - x, depth - 1 - mz };
            default -> new int[] { mz, width - 1 - x };
        };
    }

    /** The side a template side faces once the room is placed in this orientation. */
    public Side side(Side side) {
        return (mirrored ? side.mirrored() : side).clockwise(quarterTurns);
    }

    /** Looks up the orientation for a turn count and mirror flag; turns wrap modulo 4. */
    public static Orientation of(int quarterTurns, boolean mirrored) {
        return values()[Math.floorMod(quarterTurns, 4) + (mirrored ? 4 : 0)];
    }
}
