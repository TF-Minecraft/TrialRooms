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
    private final List<RoomTemplate> templates;
    private final Map<RoomType, List<RoomTemplate>> byType = new EnumMap<>(RoomType.class);

    /**
     * @param cellSize   cell width and depth in blocks; even, so doorways stay centred
     * @param cellHeight room height in blocks
     */
    public RoomCatalog(int cellSize, int cellHeight, List<RoomTemplate> templates) {
        if (cellSize < 8 || cellSize % 2 != 0) {
            throw new IllegalArgumentException("Cell size must be even and at least 8: " + cellSize);
        }
        if (cellHeight < 4) {
            throw new IllegalArgumentException("Cell height must be at least 4: " + cellHeight);
        }
        this.cellSize = cellSize;
        this.cellHeight = cellHeight;
        this.templates = List.copyOf(templates);
        Set<String> ids = new HashSet<>();
        for (RoomTemplate t : this.templates) {
            if (!ids.add(t.id())) {
                throw new IllegalArgumentException("Duplicate template id: " + t.id());
            }
            for (Doorway d : t.doorways()) {
                if (d.width() < 2 || d.width() % 2 != 0 || d.width() > cellSize - 2) {
                    throw new IllegalArgumentException(t.id() + ": doorway width must be even and between 2 and "
                            + (cellSize - 2) + ": " + d.width());
                }
            }
            for (Marker m : t.markers()) {
                if (m.x() < 0 || m.z() < 0 || m.y() < 0 || m.x() >= t.widthCells() * cellSize
                        || m.z() >= t.depthCells() * cellSize || m.y() >= cellHeight) {
                    throw new IllegalArgumentException(t.id() + ": marker outside the room: " + m);
                }
            }
            byType.computeIfAbsent(t.type(), k -> new ArrayList<>()).add(t);
        }
    }

    public int cellSize() {
        return cellSize;
    }

    public int cellHeight() {
        return cellHeight;
    }

    public List<RoomTemplate> templates() {
        return templates;
    }

    /** Templates of one type, in catalog order; empty when the kit has none. */
    public List<RoomTemplate> ofType(RoomType type) {
        return Collections.unmodifiableList(byType.getOrDefault(type, List.of()));
    }
}
