package net.tfminecraft.trialrooms.dungeon.load;

import java.io.File;
import java.util.Map;

import net.tfminecraft.trialrooms.dungeon.event.EventKit;
import net.tfminecraft.trialrooms.dungeon.fight.FightKit;
import net.tfminecraft.trialrooms.dungeon.template.RoomCatalog;

/**
 * A loaded room kit: its templates and where each template's structure file is.
 *
 * @param id         kit folder name
 * @param catalog    the kit's room templates
 * @param structures structure file per template id
 * @param doorHeight doorway height in blocks, from one block above the floor
 * @param sealBlock  block data used to wall up unused doorways
 * @param doorBlock  block data that shuts a doorway while a fight runs or until a room is cleared
 * @param mobs       MythicMobs type per spawn marker tag (such as melee, ranged, boss), for kits
 *                   without a roster: one enemy per marker
 * @param fights     roster, packs, bosses and fight numbers; null for a kit that only has {@code mobs}
 * @param events     the kit's room events and their numbers; null for none
 */
public record DungeonKit(String id, RoomCatalog catalog, Map<String, File> structures, int doorHeight,
        String sealBlock, String doorBlock, Map<String, String> mobs, FightKit fights, EventKit events) {

    public DungeonKit(String id, RoomCatalog catalog, Map<String, File> structures, int doorHeight, String sealBlock,
            String doorBlock, Map<String, String> mobs, FightKit fights) {
        this(id, catalog, structures, doorHeight, sealBlock, doorBlock, mobs, fights, null);
    }

    public DungeonKit(String id, RoomCatalog catalog, Map<String, File> structures, int doorHeight, String sealBlock,
            String doorBlock, Map<String, String> mobs) {
        this(id, catalog, structures, doorHeight, sealBlock, doorBlock, mobs, null);
    }

    public DungeonKit {
        structures = Map.copyOf(structures);
        mobs = Map.copyOf(mobs);
    }

    /** The mob for a spawn marker tag, falling back to the kit's melee mob; null when neither is set. */
    public String mobFor(String tag) {
        return mobs.getOrDefault(tag, mobs.get("melee"));
    }
}
