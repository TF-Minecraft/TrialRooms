package net.tfminecraft.trialrooms.dungeon.generator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.ToIntFunction;

/** Deterministic weighted picks and orderings from a seeded {@link Random}. */
final class Weighted {

    private Weighted() {
    }

    /** Picks one key with chance proportional to its weight. The map must not be empty. */
    static <K> K pick(Map<K, Integer> weights, Random random) {
        int total = 0;
        for (int w : weights.values()) {
            total += w;
        }
        List<Map.Entry<K, Integer>> entries = new ArrayList<>(weights.entrySet());
        int roll = random.nextInt(total);
        int i = 0;
        while (roll >= entries.get(i).getValue()) {
            roll -= entries.get(i).getValue();
            i++;
        }
        return entries.get(i).getKey();
    }

    /**
     * Orders items so heavier ones tend to come first (weighted random sampling without
     * replacement, Efraimidis-Spirakis keys).
     */
    static <T> List<T> order(List<T> items, ToIntFunction<T> weight, Random random) {
        List<Keyed<T>> keyed = new ArrayList<>(items.size());
        for (T item : items) {
            double u = 1.0 - random.nextDouble(); // (0, 1]
            keyed.add(new Keyed<>(item, -Math.log(u) / weight.applyAsInt(item)));
        }
        keyed.sort(Comparator.comparingDouble(Keyed::key));
        List<T> out = new ArrayList<>(keyed.size());
        for (Keyed<T> k : keyed) {
            out.add(k.item());
        }
        return out;
    }

    /** A uniformly shuffled copy. */
    static <T> List<T> shuffled(List<T> items, Random random) {
        return order(items, item -> 1, random);
    }

    private record Keyed<T>(T item, double key) {
    }
}
