package net.tfminecraft.trialrooms.dungeon.grid;

/** A horizontal side of a cell. Minecraft axes: x grows east, z grows south. */
public enum Side {
    NORTH(0, -1),
    EAST(1, 0),
    SOUTH(0, 1),
    WEST(-1, 0);

    private final int dx;
    private final int dz;

    Side(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    public int dx() {
        return dx;
    }

    public int dz() {
        return dz;
    }

    public Side opposite() {
        return values()[(ordinal() + 2) % 4];
    }

    /** The side after turning clockwise by the given number of quarter turns. */
    public Side clockwise(int quarterTurns) {
        return values()[Math.floorMod(ordinal() + quarterTurns, 4)];
    }

    /** The side after mirroring north and south (vanilla {@code LEFT_RIGHT}). */
    public Side mirrored() {
        return this == NORTH || this == SOUTH ? opposite() : this;
    }

    /** Parses a side name such as {@code north} or {@code N}, ignoring case. */
    public static Side parse(String text) {
        if (text != null) {
            String key = text.trim().toUpperCase(java.util.Locale.ROOT);
            for (Side side : values()) {
                if (side.name().equals(key) || side.name().substring(0, 1).equals(key)) {
                    return side;
                }
            }
        }
        throw new IllegalArgumentException("Unknown side: " + text);
    }
}
