package net.tfminecraft.trialrooms.dungeon.template;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** All room templates of one dungeon kit, checked against the kit's cell size. */
public final class RoomCatalog {

    private final int cellSize;
    private final int cellHeight;
    private final int maxRise;
    private final List<RoomTemplate> templates;
    private final Map<RoomType, List<RoomTemplate>> byType = new EnumMap<>(RoomType.class);

    /**
     * @param cellSize   cell width and depth in blocks; even, so doorways stay centred
     * @param cellHeight room height in blocks
     */
    public RoomCatalog(int cellSize, int cellHeight, List<RoomTemplate> templates) {
        this(cellSize, cellHeight, 24, templates);
    }

    /**
     * @param cellSize   cell width and depth in blocks; even, so doorways stay centred
     * @param cellHeight most a room may be tall, in blocks
     * @param maxRise    furthest a room's base may sit above or below the start room's, in blocks
     */
    public RoomCatalog(int cellSize, int cellHeight, int maxRise, List<RoomTemplate> templates) {
        if (cellSize < 8 || cellSize % 2 != 0) {
            throw new IllegalArgumentException("Cell size must be even and at least 8: " + cellSize);
        }
        if (cellHeight < 4) {
            throw new IllegalArgumentException("Cell height must be at least 4: " + cellHeight);
        }
        if (maxRise < 0) {
            throw new IllegalArgumentException("max-rise cannot be negative: " + maxRise);
        }
        this.cellSize = cellSize;
        this.cellHeight = cellHeight;
        this.maxRise = maxRise;
        this.templates = List.copyOf(templates);
        Set<String> ids = new HashSet<>();
        for (RoomTemplate t : this.templates) {
            if (!ids.add(t.id())) {
                throw new IllegalArgumentException("Duplicate template id: " + t.id());
            }
            check(t, cellSize, cellHeight);
            byType.computeIfAbsent(t.type(), k -> new ArrayList<>()).add(t);
        }
    }

    /** Checks one template against a kit's cell size: doorway widths, heights and marker positions. */
    public static void check(RoomTemplate t, int cellSize, int cellHeight) {
        check(t, cellSize, cellHeight, 0);
    }

    /** As above, with the kit's door height for doorways that set none. */
    public static void check(RoomTemplate t, int cellSize, int cellHeight, int doorHeight) {
        for (Doorway d : t.doorways()) {
            if (d.width() < 2 || d.width() % 2 != 0 || d.width() > cellSize - 2) {
                throw new IllegalArgumentException(t.id() + ": doorway width must be even and between 2 and "
                        + (cellSize - 2) + ": " + d.width());
            }
            if (d.floor() + d.heightOr(doorHeight) >= cellHeight) {
                throw new IllegalArgumentException(t.id() + ": doorway " + d + " reaches above the room height "
                        + cellHeight);
            }
        }
        for (Marker m : t.markers()) {
            if (m.x() < 0 || m.z() < 0 || m.y() < 0 || m.x() >= t.widthCells() * cellSize
                    || m.z() >= t.depthCells() * cellSize || m.y() >= cellHeight
                    || !t.occupies(m.x() / cellSize, m.z() / cellSize)) {
                throw new IllegalArgumentException(t.id() + ": marker outside the room: " + m);
            }
        }
    }

    public int cellSize() {
        return cellSize;
    }

    public int cellHeight() {
        return cellHeight;
    }

    public int maxRise() {
        return maxRise;
    }

    public List<RoomTemplate> templates() {
        return templates;
    }

    /** Templates of one type, in catalog order; empty when the kit has none. */
    public List<RoomTemplate> ofType(RoomType type) {
        return Collections.unmodifiableList(byType.getOrDefault(type, List.of()));
    }
}
