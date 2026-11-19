package net.tfminecraft.trialrooms.dungeon.generator;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.tfminecraft.trialrooms.dungeon.template.RoomCatalog;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;

/**
 * Builds a dungeon layout from a recipe and a seed. Pure Java: safe to run off the main thread,
 * and the same catalog, recipe and seed always give the same layout.
 */
public final class DungeonGenerator {

    private DungeonGenerator() {
    }

    /**
     * @throws IllegalArgumentException when the catalog lacks a room type the recipe needs
     * @throws GenerationException      when no attempt found a layout
     */
    public static DungeonLayout generate(RoomCatalog catalog, DungeonRecipe recipe, long seed) {
        Set<RoomType> missing = EnumSet.noneOf(RoomType.class);
        for (RoomType type : neededTypes(recipe)) {
            if (catalog.ofType(type).isEmpty()) {
                missing.add(type);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(recipe.id() + ": the room kit has no " + missing + " rooms");
        }
        for (int attempt = 0; attempt < recipe.maxAttempts(); attempt++) {
            Random random = new Random(attemptSeed(seed, attempt));
            List<MissionNode> plan = MissionPlanner.plan(recipe, random);
            LayoutEmbedder embedder = new LayoutEmbedder(catalog, plan, random, recipe.stepBudget());
            if (embedder.embed()) {
                return new DungeonLayout(recipe.id(), seed, attempt, embedder.rooms(), embedder.connections());
            }
        }
        throw new GenerationException(recipe.id() + ": no layout found in " + recipe.maxAttempts()
                + " attempts (seed " + seed + ")");
    }

    static Set<RoomType> neededTypes(DungeonRecipe recipe) {
        Set<RoomType> types = EnumSet.of(RoomType.START, RoomType.BOSS);
        if (recipe.trunkMax() > 0) {
            types.addAll(recipe.trunkPool().keySet());
        }
        if (recipe.restBeforeBoss()) {
            types.add(RoomType.REST);
        }
        if (recipe.vault()) {
            types.add(RoomType.VAULT);
        }
        if (recipe.branchesMax() > 0) {
            types.add(RoomType.TREASURE);
            if (recipe.branchMax() > 1) {
                types.addAll(recipe.branchPool().keySet());
            }
        }
        return types;
    }

    /** Spreads attempts over unrelated seeds (SplitMix64 finaliser). */
    static long attemptSeed(long seed, int attempt) {
        long z = seed + 0x9E3779B97F4A7C15L * (attempt + 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** No layout could be found for a seed within the recipe's attempts. */
    public static final class GenerationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public GenerationException(String message) {
            super(message);
        }
    }
}
