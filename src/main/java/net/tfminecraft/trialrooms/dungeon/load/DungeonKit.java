package net.tfminecraft.trialrooms.dungeon.load;

import java.io.File;
import java.util.Map;

import net.tfminecraft.trialrooms.dungeon.template.RoomCatalog;

/**
 * A loaded room kit: its templates and where each template's structure file is.
 *
 * @param id         kit folder name
 * @param catalog    the kit's room templates
 * @param structures structure file per template id
 * @param doorHeight doorway height in blocks, from one block above the floor
 * @param sealBlock  block data used to wall up unused doorways
 */
public record DungeonKit(String id, RoomCatalog catalog, Map<String, File> structures, int doorHeight,
        String sealBlock) {

    public DungeonKit {
        structures = Map.copyOf(structures);
    }
}
