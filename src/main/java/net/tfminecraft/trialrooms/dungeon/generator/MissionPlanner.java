package net.tfminecraft.trialrooms.dungeon.generator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.tfminecraft.trialrooms.dungeon.template.RoomType;

/**
 * Turns a recipe into an abstract room plan. Rooms are listed depth first: each trunk room is
 * followed by the branches that grow from it, then the next trunk room. Placing a branch right
 * after its room keeps the search's backtracking local when a branch does not fit.
 */
final class MissionPlanner {

    private MissionPlanner() {
    }

    static List<MissionNode> plan(DungeonRecipe recipe, Random random) {
        List<RoomType> trunk = new ArrayList<>();
        trunk.add(RoomType.START);
        int middle = between(recipe.trunkMin(), recipe.trunkMax(), random);
        List<Integer> anchors = new ArrayList<>();
        for (int i = 0; i < middle; i++) {
            RoomType type = Weighted.pick(recipe.trunkPool(), random);
            if (type != RoomType.CORRIDOR) {
                anchors.add(trunk.size()); // branches grow from rooms, never from corridors
            }
            trunk.add(type);
        }
        if (recipe.restBeforeBoss()) {
            trunk.add(RoomType.REST);
        }
        trunk.add(RoomType.BOSS);
        if (recipe.vault()) {
            trunk.add(RoomType.VAULT);
        }
        List<List<List<RoomType>>> branchesAt = new ArrayList<>();
        for (int i = 0; i < trunk.size(); i++) {
            branchesAt.add(new ArrayList<>());
        }
        int branches = anchors.isEmpty() ? 0 : between(recipe.branchesMin(), recipe.branchesMax(), random);
        for (int b = 0; b < branches; b++) {
            int anchor = anchors.get(random.nextInt(anchors.size()));
            int length = between(recipe.branchMin(), recipe.branchMax(), random);
            List<RoomType> branch = new ArrayList<>();
            for (int i = 1; i < length; i++) {
                branch.add(Weighted.pick(recipe.branchPool(), random));
            }
            branch.add(RoomType.TREASURE);
            branchesAt.get(anchor).add(branch);
        }
        List<MissionNode> nodes = new ArrayList<>();
        int previous = -1;
        for (int pos = 0; pos < trunk.size(); pos++) {
            int index = emit(nodes, trunk.get(pos), previous, pos, true);
            for (List<RoomType> branch : branchesAt.get(pos)) {
                int parent = index;
                int depth = pos;
                for (RoomType type : branch) {
                    parent = emit(nodes, type, parent, ++depth, false);
                }
            }
            previous = index;
        }
        return nodes;
    }

    private static int emit(List<MissionNode> nodes, RoomType type, int parent, int depth, boolean trunk) {
        int index = nodes.size();
        nodes.add(new MissionNode(index, type, parent, depth, trunk));
        return index;
    }

    private static int between(int min, int max, Random random) {
        return min + random.nextInt(max - min + 1);
    }
}
