package net.tfminecraft.trialrooms.dungeon.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;

/** Decides which fight rooms of a dungeon get an event, from its seed: the same seed, the same events. */
public final class EventPlanner {

    private EventPlanner() {
    }

    /**
     * @param chance chance for each plain fight room (elite and boss rooms never get one)
     * @param most   the most events in one dungeon
     * @return the event of each room that gets one, by room index
     */
    public static Map<Integer, EventKind> plan(DungeonLayout layout, EventKit kit, double chance, int most) {
        Map<Integer, EventKind> out = new LinkedHashMap<>();
        if (kit == null) {
            return out;
        }
        Random rng = new Random(layout.seed() * 131 + 7);
        EventKind last = null;
        for (int i = 0; i < layout.rooms().size() && out.size() < most; i++) {
            if (layout.rooms().get(i).node().type() != RoomType.COMBAT || rng.nextDouble() >= chance) {
                continue;
            }
            EventKind k = kit.pick(rng);
            if (k == last) {
                k = kit.pick(rng); // one more roll against the same event twice in a row
            }
            out.put(i, k);
            last = k;
        }
        return out;
    }
}
