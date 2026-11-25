package net.tfminecraft.trialrooms.dungeon.loot;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import net.tfminecraft.trialrooms.dungeon.gear.GearSet;
import net.tfminecraft.trialrooms.dungeon.gear.Trait;
import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Base;
import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Range;
import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Rarity;
import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Slot;
import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Tier;

public class LootCoreTest {

  static final String NL = String.valueOf((char) 10);

  /** The shipped loot.yml. */
  public static LootKit shipped() {
    YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(
        LootCoreTest.class.getResourceAsStream("/dungeons/loot.yml"), StandardCharsets.UTF_8));
    List<String> problems = new ArrayList<>();
    LootKit k = LootKit.load(y, problems);
    assertEquals(List.of(), problems);
    return k;
  }

  /** A Random whose doubles are all one value (and ints the lowest). */
  static Random fixed(double d) {
    return new Random() {
      @Override
      public double nextDouble() {
        return d;
      }

      @Override
      public int nextInt(int bound) {
        return 0;
      }
    };
  }

  @Test
  void theShippedLootLoads() {
    LootKit k = shipped();
    assertEquals("steel_ingot", k.standard().get("metal"));
    assertEquals(List.of("uncommon", "rare", "epic", "legendary"), k.rarities().stream().map(Rarity::id).toList());
    assertEquals(49, k.bases().size());
    assertEquals(List.of("Musketeer"), k.base("rifle").classes());
    assertEquals(2, k.base("staff").runes());
    assertTrue(k.sets().values().stream().allMatch(s -> s.tints() != null));
    assertEquals("Dungeon Gem", k.socket());
    assertEquals(Slot.RING, k.base("RING").slot());
    assertNull(k.base("nothing"));
    assertNull(k.rarity("mythic"));
    assertEquals(1.55, k.tier(3).power());
    assertEquals(1.9, k.tier(9).power(), "past the last tier: the last");
    assertEquals(1.0, k.tier(0).power(), "below the first: the first");
    assertTrue(k.unscaled().contains("movement_speed"));
    assertEquals("ia.tfmc_dungeons:", k.looks());
    assertEquals(List.of("heavy"), k.base("heavy_boots").looks());
    assertEquals(List.of("ring_1", "ring_2"), k.base("ring").looks());
    assertEquals(12, k.sets().size());
    for (GearSet s : k.sets().values()) {
      // every set is worth the same in all (damage counts once: a class deals one kind)
      assertEquals(9, units(s.armour()), 1e-9, s.id());
      assertEquals(6, units(s.jewellery()), 1e-9, s.id());
      assertEquals(Set.of(2, 4), s.armour().keySet());
      assertEquals(Set.of(2, 4), s.jewellery().keySet());
    }
    for (Rarity r : k.rarities()) {
      assertNotNull(LootRoller.set(k, r, null, new Random(1)), r.id() + " has sets anywhere");
    }
    GearSet royal = k.sets().get("royal");
    assertFalse(royal.drops(null));
    assertFalse(royal.drops("delve_fire"));
    assertTrue(royal.drops("warband_seithr"));
    assertEquals(new Trait(Trait.Kind.SHARDS, 0.4), royal.trait());
    assertEquals(List.of(65, 25, 7, 3), k.rarities().stream().map(Rarity::weight).toList());
    // every bonus stat is worth the same: its range over one unit of it is bonus-worth everywhere
    Map<String, Double> unit = Map.of("max_health", 0.25, "armor", 0.075, "physical_damage_reduction", 1.1,
        "physical_damage", 2.1, "lifesteal", 1.05, "magic_damage", 2.1);
    for (Map<String, Range> pool : k.pools().values()) {
      pool.forEach((stat, range) -> {
        if (unit.containsKey(stat)) {
          assertEquals(2, range.min() / unit.get(stat), 1e-9, stat);
          assertEquals(4, range.max() / unit.get(stat), 1e-9, stat);
        }
      });
    }
    assertEquals(0.5, k.pools().get("armour").get("max_health").min(), 1e-9);
  }

  private static double units(Map<Integer, Map<String, Double>> bonuses) {
    return bonuses.values().stream().mapToDouble(b -> b.entrySet().stream()
        .filter(e -> !List.of("projectile_damage", "magic_damage").contains(e.getKey()) || !b.containsKey("physical_damage"))
        .mapToDouble(Map.Entry::getValue).sum()).sum();
  }

  /** A kit with one rarity, pool and set of its own. */
  static LootKit kit(Rarity r, Map<String, Range> pool, Base base, Map<String, GearSet> sets) {
    return new LootKit(Map.of(), List.of(r), Map.of(1, new Tier(1, 0)), "s", Map.of("p", pool), List.of(base),
        List.of("Old"), List.of("of Rain"), "look:", sets, Map.of("armor", 0.5), Set.of());
  }

  static GearSet set(String id, String rarity, String... dungeons) {
    return new GearSet(id, id.toUpperCase(), rarity, List.of(dungeons), Map.of(2, Map.of("armor", 2.0)),
        Map.of(2, Map.of("armor", 1.0)), new Trait(Trait.Kind.WARD, 0.1));
  }

  @Test
  void badLootIsRefused() throws Exception {
    List<String> problems = new ArrayList<>();
    assertNull(LootKit.load(new YamlConfiguration(), problems));
    assertTrue(problems.getFirst().contains("no rarities section"), problems.toString());
    String head = "rarities: {a: {weight: 1, colour: \"#000000\"}}" + NL + "tiers: {1: {}}" + NL;
    String pool = "bonus-worth: [1, 2]" + NL + "worth: {hp: 1}" + NL;
    for (String[] bad : List.of(new String[] {"bonus: {p: [hp]}", "bonus-worth: give [min, max]"},
        new String[] {"bonus-worth: [1, 2]" + NL + "bonus: {p: [hp]}", "no worth section"},
        new String[] {pool + "bonus: {p: [mana]}", "bonus pool p: no worth for mana"},
        new String[] {pool + "bonus: {p: []}", "bonus pool p lists no stats"},
        new String[] {pool + "bonus: {p: [hp]}" + NL + "items: {b: {slot: weapon, recipe: r, pool: p, stats: {hp: [1]}}}",
            "hp: give [min, max]"},
        new String[] {pool + "bonus: {p: [hp]}" + NL + "items: {b: {slot: weapon, recipe: r, pool: p}}", "no sets section"},
        new String[] {pool + "bonus: {p: [hp]}" + NL + "items: {b: {slot: weapon, recipe: r, pool: p}}" + NL
            + "sets: {s: {rarity: a, armour: {2: {}}, trait: {kind: ward, value: 1}}}", "no jewellery section"},
        new String[] {pool + "bonus: {p: [hp]}" + NL + "items: {b: {slot: weapon, recipe: r, pool: p}}" + NL
            + "names: {prefixes: [x], suffixes: [y]}" + NL
            + "sets: {s: {rarity: z, armour: {2: {}}, jewellery: {2: {}}, trait: {kind: ward, value: 1}}}", "set s: no rarity z"},
        new String[] {pool + "bonus: {p: [hp]}" + NL + "items: {b: {slot: weapon, recipe: r, pool: p}}" + NL
            + "names: {prefixes: [x], suffixes: [y]}" + NL
            + "sets: {s: {rarity: a, armour: {2: {mana: 1}}, jewellery: {2: {}}, trait: {kind: ward, value: 1}}}",
            "set s: no worth for mana"})) {
      YamlConfiguration y = new YamlConfiguration();
      y.loadFromString(head + bad[0] + NL);
      problems.clear();
      assertNull(LootKit.load(y, problems), bad[0]);
      assertTrue(problems.getFirst().contains(bad[1]), problems + " for " + bad[0]);
    }

    Rarity r = new Rarity("a", "A", 1, 1, 0, 0, "#abcdef");
    Map<Integer, Tier> tiers = Map.of(1, new Tier(1, 0));
    Base b = new Base("b", "B", Slot.WEAPON, "x", null, "p", Map.of(), List.of());
    Map<String, Map<String, Range>> pools = Map.of("p", Map.of());
    assertThrows(IllegalArgumentException.class,
        () -> new LootKit(Map.of(), List.of(), tiers, "s", pools, List.of(b), List.of("x"), List.of("y"), "", Map.of(), Map.of(), Set.of()));
    assertThrows(IllegalArgumentException.class, () -> new LootKit(Map.of(), List.of(new Rarity("z", "Z", 0, 1, 0, 0,
        "#000000")), tiers, "s", pools, List.of(b), List.of("x"), List.of("y"), "", Map.of(), Map.of(), Set.of()), "weights add to 0");
    assertThrows(IllegalArgumentException.class, () -> new LootKit(Map.of(), List.of(r), tiers, "s", Map.of(),
        List.of(b), List.of("x"), List.of("y"), "", Map.of(), Map.of(), Set.of()), "an unknown pool");
    for (List<?> missing : List.of(List.of(1), List.of(2), List.of(3), List.of(4))) {
      int m = (Integer) missing.getFirst();
      assertThrows(IllegalArgumentException.class, () -> new LootKit(Map.of(), List.of(r), m == 1 ? Map.of() : tiers, "s",
          pools, m == 2 ? List.of() : List.of(b), m == 3 ? List.of() : List.of("x"), m == 4 ? List.of() : List.of("y"),
          "", Map.of(), Map.of(), Set.of()));
    }
    assertThrows(IllegalArgumentException.class, () -> new Rarity("a", "A", -1, 1, 0, 0, "#000000"));
    assertThrows(IllegalArgumentException.class, () -> new Rarity("a", "A", 1, 0, 0, 0, "#000000"));
    assertThrows(IllegalArgumentException.class, () -> new Rarity("a", "A", 1, 1, -1, 0, "#000000"));
    assertThrows(IllegalArgumentException.class, () -> new Rarity("a", "A", 1, 1, 0, -1, "#000000"));
    assertThrows(IllegalArgumentException.class, () -> new Rarity("a", "A", 1, 1, 0, 0, "red"));
    assertThrows(IllegalArgumentException.class, () -> new Tier(0, 0));
    assertThrows(IllegalArgumentException.class, () -> new Tier(1, -1));
    assertThrows(IllegalArgumentException.class, () -> new Range(2, 1));
    assertThrows(IllegalArgumentException.class, () -> new Range(1, Double.POSITIVE_INFINITY));
    assertThrows(IllegalArgumentException.class, () -> new Base("b", "B", Slot.WEAPON, "x", "y", "p", Map.of(), List.of()));
    assertThrows(IllegalArgumentException.class, () -> new Base("b", "B", Slot.WEAPON, null, null, "p", Map.of(), List.of()));
    assertTrue(Slot.BOOTS.armour());
    assertFalse(Slot.WEAPON.armour());
    assertEquals("chestplate", Slot.CHESTPLATE.piece());
    assertEquals(GearSet.Kind.ARMOUR, Slot.HELMET.kind());
    assertEquals(GearSet.Kind.WEAPON, Slot.WEAPON.kind());
    assertEquals(GearSet.Kind.OFF_HAND, Slot.OFF_HAND.kind());
    assertEquals(GearSet.Kind.JEWELLERY, Slot.ARTIFACT.kind());
  }

  @Test
  void rarerItemsComeWithLuck() {
    LootKit k = shipped();
    Map<String, Integer> t1 = new HashMap<>();
    Map<String, Integer> t3 = new HashMap<>();
    Random rng = new Random(3);
    for (int i = 0; i < 4000; i++) {
      t1.merge(LootRoller.rarity(k, 1, rng).id(), 1, Integer::sum);
      t3.merge(LootRoller.rarity(k, 3, rng).id(), 1, Integer::sum);
    }
    assertTrue(t1.get("uncommon") > 2000 && t1.get("legendary") < 150, t1.toString());
    assertTrue(t3.get("legendary") > 3 * t1.get("legendary"), t1 + " " + t3);
    assertEquals("legendary", LootRoller.rarity(k, 1, fixed(1.0)).id(), "the very top of the roll");
    Map<String, Integer> lucky = new HashMap<>();
    Random rng2 = new Random(3);
    for (int i = 0; i < 4000; i++) {
      lucky.merge(LootRoller.rarity(k, 1, 0.5, rng2).id(), 1, Integer::sum);
    }
    assertTrue(lucky.get("legendary") > t1.get("legendary"), "extra luck (a run under par): " + lucky);
    assertEquals("uncommon", LootRoller.rarity(k, 1, fixed(0)).id());
  }

  @Test
  void itemsComeOutStrongerNamedAndDressed() {
    LootKit k = shipped();
    Base helm = k.base("heavy_helmet");
    Rarity legendary = k.rarity("legendary");
    Map<String, Double> base = new java.util.LinkedHashMap<>();
    base.put("armor", 4.0);
    base.put("movement_speed", -0.2);
    LootRoller.Plan p = LootRoller.plan(k, helm, base, 3, legendary, k.sets().get("firstlight"), new Random(1));
    assertTrue(p.stats().get("armor") >= round(4.0 * 1.55 * 1.75), "scaled by tier and rarity (a bonus may add more)");
    assertEquals(-0.2, p.stats().get("movement_speed"), "never scaled");
    assertEquals(2 + 4, p.stats().size(), "four bonus stats from the armour pool, all different");
    assertEquals(3, p.sockets());
    assertTrue(p.name().startsWith("Heavy Firstlight Helmet of "), p.name());
    assertTrue(p.name().contains(" of "), "legendary names get a suffix: " + p.name());
    assertEquals("ia.tfmc_dungeons:firstlight_heavy_helmet", p.look());
    assertEquals(1.55 * 1.75, p.power(), 1e-9);
    assertEquals(List.of("Legendary dungeon item, tier 3", "Firstlight armour set", "2 pieces: +2 Max Health",
        "4 pieces: +2 Max Health, +17.1% Damage", "Stays in the dungeons."), p.lore());
    assertSame(helm, p.base());
    assertEquals(3, p.tier());

    // an uncommon weapon: no suffix, no look
    LootRoller.Plan w = LootRoller.plan(k, k.base("sword"), Map.of("attack_damage", 6.0), 1, k.rarity("uncommon"),
        k.sets().get("delver"), new Random(2));
    assertEquals("Delver Sword", w.name());
    assertEquals("ia.tfmc_dungeons:delver_sword", w.look());
    assertEquals("Delver weapon: 15% more damage to unhurt enemies.", w.lore().get(1));
    assertEquals(0, w.sockets());

    // jewellery rolls its own stats, and a bonus on a stat it already has adds to it
    LootRoller.Plan ring = LootRoller.plan(k, k.base("ring"), Map.of(), 1, k.rarity("rare"),
        k.sets().get("warden"), fixed(0));
    assertEquals("ia.tfmc_dungeons:warden_ring_1", ring.look());
    assertEquals(round(0.4 * 1.3 + 0.5 * 1.3), ring.stats().get("max_health"), ring.stats().toString());
    assertEquals(2, ring.stats().size());
  }

  @Test
  void gemsCarryOneScaledStat() {
    LootKit k = shipped();
    LootRoller.GemPlan g = LootRoller.gem(k, "melee", 3, k.rarity("legendary"), fixed(1.0));
    assertEquals("physical_damage", g.stat(), "the first of the pool with a lowest pick");
    assertEquals(round(2.1 * 4 * 1.55 * 1.75), g.value(), "the top of 2-4 basic gems' worth");
    assertEquals("Deepstone Delvestone", g.name());
    assertEquals(3, g.tier());
    assertEquals("Legendary dungeon gem, tier 3", g.lore().getFirst());
  }

  @Test
  void itemsWithoutASetAndSetsWithoutALook() {
    Rarity r = new Rarity("a", "A", 1, 1, 0, 0, "#abcdef");
    Base cap = new Base("cap", "Cap", Slot.HELMET, "x", null, "p", Map.of(), List.of("heavy"));
    LootKit k = kit(r, Map.of(), cap, Map.of());
    LootRoller.Plan p = LootRoller.plan(k, cap, Map.of("armor", 1.0), 1, r, null, new Random(1));
    assertEquals("Old Cap", p.name());
    assertNull(p.look(), "no set: the template's look");
    assertNull(p.set());
    assertEquals(List.of("A dungeon item, tier 1", "Stays in the dungeons."), p.lore());
    assertNull(LootRoller.set(k, r, "x", new Random(1)));
    GearSet set = set("s", "a");
    Base bare = new Base("cap", "Cap", Slot.HELMET, "x", null, "p", Map.of(), List.of());
    LootRoller.Plan q = LootRoller.plan(k, bare, Map.of(), 1, r, set, new Random(1));
    assertNull(q.look(), "no look: the template's");
    assertEquals("S Cap", q.name());
    assertSame(set, q.set());
    assertEquals(Map.of("armor", 1.0), p.stats(), "no bonus pool entries to draw");
    assertNull(LootRoller.base(k, b -> false, new Random(1)));
    assertSame(cap, LootRoller.base(k, b -> true, new Random(1)));
    assertEquals(1.23, LootRoller.round(1.234));
    // more bonus stats wanted than the pool holds: each stat once
    Rarity greedy = new Rarity("g", "G", 1, 1, 5, 0, "#abcdef");
    LootKit small = kit(greedy, Map.of("armor", new Range(1, 1)), cap, Map.of());
    assertEquals(Map.of("armor", 1.0), LootRoller.plan(small, cap, Map.of(), 1, greedy, null, new Random(1)).stats());
    // a dungeon's own sets come up there only
    LootKit two = kit(r, Map.of(), cap, Map.of("d", set("d", "A", "fire"), "z", set("z", "a", "ice")));
    assertEquals("d", LootRoller.set(two, r, "fire", new Random(1)).id());
    assertNull(LootRoller.set(two, r, null, new Random(1)));
  }

  static double round(double v) {
    return Math.round(v * 100) / 100.0;
  }
}
