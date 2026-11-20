package net.tfminecraft.trialrooms.dungeon.fight;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import net.tfminecraft.trialrooms.dungeon.template.RoomType;

class DirectorTest {

  static final List<String> MARKERS = List.of("melee", "melee", "melee", "melee", "ranged", "ranged", "boss");

  static RosterEntry e(String id, Role role, double cost, int weight) {
    return new RosterEntry(id, "mob_" + id, role, cost, weight);
  }

  static FightKit kit(FightTuning t, List<PackTemplate> packs, RosterEntry... entries) {
    Map<String, RosterEntry> roster = new LinkedHashMap<>();
    for (RosterEntry x : entries) {
      roster.put(x.id(), x);
    }
    return new FightKit(roster, packs, Map.of(), t);
  }

  static FightKit skeletons(FightTuning t) {
    return kit(t, List.of(new PackTemplate("patrol", List.of(Role.GRUNT, Role.GRUNT, Role.GRUNT, Role.RANGED), 3, 0),
        new PackTemplate("wall", List.of(Role.TANK, Role.GRUNT, Role.RANGED), 2, 1),
        new PackTemplate("coven", List.of(Role.CASTER, Role.BRUISER, Role.BRUISER), 1, 2)),
        e("foot", Role.GRUNT, 1, 3), e("sword", Role.GRUNT, 1.5, 2), e("halb", Role.BRUISER, 2, 1),
        e("war", Role.TANK, 2.5, 1), e("arch", Role.RANGED, 2.5, 1), e("wiz", Role.CASTER, 3, 1),
        e("tank", Role.ELITE, 5, 1));
  }

  static FightTuning tuning(double eliteChance, int maxAlive) {
    FightTuning d = FightTuning.DEFAULT;
    return new FightTuning(d.budgetCombat(), d.budgetElite(), d.depthStep(), d.tierFactors(), d.waveSplit(),
        d.thirdWave(), d.waveRemain(), d.waveTimeoutSeconds(), d.waveTimeoutAlive(), d.waveWarningSeconds(), maxAlive,
        d.rangedShare(), eliteChance, d.eliteMinDepth(), d.eliteMinCost(), d.eliteHealth(), d.healthCap(),
        d.stallSeconds(), d.slamSeconds(), d.shockwaveSeconds(), d.burstSeconds(), d.gapSeconds(), d.floorSeconds(),
        d.slamRadius(), d.shockwaveRadius(), d.burstRadius(), d.slamDamage(), d.shockwaveDamage(), d.burstDamage(),
        d.tierDamageStep(), d.shieldReduction(), d.rallyFactor(), d.rallyEverySeconds(), d.rallySeconds(),
        d.rallyRadius(), d.phaseImmuneSeconds(), d.rallyCallAdds(), d.maxAdds(), d.enrageFactor(),
        d.enrageAddsSeconds());
  }

  static double cost(Director.Plan p) {
    double c = 0;
    for (List<Director.Spawn> w : p.waves()) {
      for (Director.Spawn s : w) {
        c += s.entry().cost();
      }
    }
    return c;
  }

  @Test
  void budgetsGrowWithDepthAndTier() {
    FightTuning t = FightTuning.DEFAULT;
    assertEquals(6, Director.budget(t, RoomType.COMBAT, 0, 1), 1e-9);
    assertEquals(6, Director.budget(t, RoomType.COMBAT, -3, 1), 1e-9);
    assertEquals(8 * 1.35 * 1.3, Director.budget(t, RoomType.ELITE, 2, 2), 1e-9);
  }

  @Test
  void combatRoomsAreRepeatableAndStayInBudget() {
    FightKit kit = skeletons(tuning(0, 24));
    Director.Plan a = Director.plan(kit, RoomType.COMBAT, 0, 1, MARKERS, new Random(7));
    Director.Plan b = Director.plan(kit, RoomType.COMBAT, 0, 1, MARKERS, new Random(7));
    assertEquals(a, b);
    assertEquals(1, a.waves().size());
    assertTrue(cost(a) <= a.budget() + 1e-9);
    assertTrue(a.enemies() >= 3);
    // a bigger party gets a bigger budget, so more enemies
    Director.Plan trio = Director.plan(kit, RoomType.COMBAT, 0, 1, 1.5, MARKERS, new Random(7));
    assertEquals(a.budget() * 1.5, trio.budget(), 1e-9);
    assertTrue(cost(trio) <= trio.budget() + 1e-9);
    assertTrue(trio.enemies() > a.enemies());
    for (Director.Spawn s : a.waves().getFirst()) {
      assertFalse(s.elite());
      assertNotEquals(6, s.marker(), "boss markers are left for the boss");
      if (s.entry().role().ranged() && s.reuse() == 0) {
        assertEquals("ranged", MARKERS.get(s.marker()));
      }
    }
  }

  @Test
  void deepRoomsComeInWavesWithElitesFirst() {
    FightKit kit = skeletons(tuning(1, 24));
    // tier 3 at depth 4: 6 x 1.8 x 1.6 = 17.3, three waves
    Director.Plan p = Director.plan(kit, RoomType.COMBAT, 4, 3, MARKERS, new Random(3));
    assertEquals(3, p.waves().size());
    assertTrue(p.waves().getFirst().getFirst().elite(), "the elite comes first");
    assertTrue(p.waves().getFirst().getFirst().entry().cost() >= 2);
    // a cap on enemies per wave pushes the rest into extra waves
    Director.Plan capped = Director.plan(skeletons(tuning(0, 2)), RoomType.COMBAT, 4, 3, MARKERS, new Random(3));
    assertTrue(capped.waves().size() > 3);
    for (List<Director.Spawn> w : capped.waves()) {
      assertTrue(w.size() <= 2);
    }
    // two waves at a middling budget
    Director.Plan two = Director.plan(kit, RoomType.COMBAT, 4, 1, MARKERS, new Random(3));
    assertEquals(2, two.waves().size());
  }

  @Test
  void eliteRoomsAlwaysHaveAnElite() {
    FightKit kit = skeletons(tuning(0, 24));
    Director.Plan p = Director.plan(kit, RoomType.ELITE, 0, 1, MARKERS, new Random(1));
    Director.Spawn first = p.waves().getFirst().getFirst();
    assertTrue(first.elite());
    assertEquals(Role.ELITE, first.entry().role());
    // without an elite role, the strongest enemy that may be one is used, even a ranged one
    FightKit noElite = kit(tuning(0, 24), List.of(), e("foot", Role.GRUNT, 1, 1), e("wiz", Role.CASTER, 3, 1));
    Director.Plan q = Director.plan(noElite, RoomType.ELITE, 0, 1, List.of(), new Random(1));
    assertEquals("wiz", q.waves().getFirst().getFirst().entry().id());
    assertTrue(q.waves().getFirst().getFirst().elite());
    assertEquals(-1, q.waves().getFirst().getFirst().marker(), "a room without markers");
    // nothing strong enough: no elite
    FightKit weak = kit(tuning(0, 24), List.of(), e("foot", Role.GRUNT, 1, 1));
    for (Director.Spawn s : Director.plan(weak, RoomType.ELITE, 0, 1, MARKERS, new Random(1)).waves().getFirst()) {
      assertFalse(s.elite());
    }
    // a combat room past the elite depth with nothing costly enough is not promoted
    for (List<Director.Spawn> w : Director.plan(weak, RoomType.COMBAT, 5, 1, MARKERS, new Random(1)).waves()) {
      for (Director.Spawn s : w) {
        assertFalse(s.elite());
      }
    }
  }

  @Test
  void fillersAndCapsCoverOddRosters() {
    // no pack fits: filler; only ranged enemies: the grunt pick falls back to them, then the cap keeps one
    FightKit ranged = kit(tuning(0, 24), List.of(new PackTemplate("big", List.of(Role.TANK), 1, 0)),
        e("arch", Role.RANGED, 1, 1));
    Director.Plan p = Director.plan(ranged, RoomType.COMBAT, 0, 1, MARKERS, new Random(2));
    assertEquals("filler", p.pack());
    assertEquals(2, p.enemies(), "six archers fit; 40% of them stay, with no grunt to swap in");
    // only tanks: the cheapest non-elite fills
    FightKit tanks = kit(tuning(0, 24), List.of(), e("war", Role.TANK, 2, 1), e("boss", Role.ELITE, 1, 1));
    Director.Plan q = Director.plan(tanks, RoomType.COMBAT, 0, 1, MARKERS, new Random(2));
    assertEquals(3, q.enemies());
    // only elites: nothing to fill with, so no waves at all
    FightKit elites = kit(tuning(0, 24), List.of(), e("boss", Role.ELITE, 9, 1));
    assertEquals(List.of(), Director.plan(elites, RoomType.COMBAT, 0, 1, MARKERS, new Random(2)).waves());
    // too many ranged from a pack: the extras turn into grunts
    FightKit volley = kit(tuning(0, 24), List.of(new PackTemplate("volley",
        List.of(Role.RANGED, Role.RANGED, Role.RANGED, Role.GRUNT), 1, 0)), e("foot", Role.GRUNT, 1, 1),
        e("arch", Role.RANGED, 1, 1));
    int r = 0;
    Director.Plan v = Director.plan(volley, RoomType.COMBAT, 0, 1, MARKERS, new Random(5));
    for (Director.Spawn s : v.waves().getFirst()) {
      r += s.entry().role().ranged() ? 1 : 0;
    }
    assertTrue(r <= Math.max(1, Math.floor(0.4 * v.enemies())), "ranged " + r + " of " + v.enemies());
    // the cheapest fill of a pack needs every role in the roster
    assertEquals(Double.POSITIVE_INFINITY, Director.cheapest(volley, new PackTemplate("x", List.of(Role.SWARM), 1, 0)));
  }

  @Test
  void addsAndMarkers() {
    FightKit kit = skeletons(tuning(0, 24));
    List<Director.Spawn> adds = Director.adds(kit, 5, MARKERS, new Random(4));
    double c = 0;
    for (Director.Spawn s : adds) {
      c += s.entry().cost();
      assertFalse(s.elite());
    }
    assertTrue(c <= 5 + 1e-9 && !adds.isEmpty());
    // markers: preferred tag first, then any other, then reuse; boss markers only when there is nothing else
    RosterEntry foot = e("foot", Role.GRUNT, 1, 1);
    RosterEntry arch = e("arch", Role.RANGED, 1, 1);
    List<Affix> none = new ArrayList<>(List.of());
    List<RosterEntry> four = List.of(foot, foot, arch, arch);
    for (int i = 0; i < 4; i++) {
      none.add(null);
    }
    List<Director.Spawn> s = Director.assign(four, none, List.of("melee", "ranged"));
    assertEquals(List.of(0, 1, 1, 0), s.stream().map(Director.Spawn::marker).toList());
    assertEquals(List.of(0, 0, 1, 1), s.stream().map(Director.Spawn::reuse).toList());
    List<Director.Spawn> bossOnly = Director.assign(List.of(foot), java.util.Arrays.asList((Affix) null), List.of("boss"));
    assertEquals(0, bossOnly.getFirst().marker());
    // weighted picks reach the last entry too
    FightKit two = kit(tuning(0, 24), List.of(new PackTemplate("a", List.of(Role.GRUNT), 1, 0),
        new PackTemplate("b", List.of(Role.GRUNT), 1, 0)), foot, e("sword", Role.GRUNT, 1, 1));
    java.util.Set<String> seen = new java.util.HashSet<>();
    java.util.Set<String> packs = new java.util.HashSet<>();
    Random rolls = new Random(1);
    for (int i = 0; i < 40; i++) {
      seen.add(Director.pick(two, Role.GRUNT, 5, rolls).id());
      packs.add(Director.pack(two, 0, 5, rolls).id());
    }
    assertEquals(java.util.Set.of("foot", "sword"), seen);
    assertEquals(java.util.Set.of("a", "b"), packs);
    assertNull(Director.pick(two, Role.TANK, 5, new Random(1)));
    assertNull(Director.pack(two, 0, 0.5, new Random(1)));
    assertNull(Director.strongest(two, 2, 10));
  }

  @Test
  void helpersPickCarefully() {
    // the strongest that may be an elite: at or above the minimum, within budget, the costliest
    FightKit k = kit(tuning(0, 24), List.of(), e("b", Role.TANK, 2.5, 1), e("a", Role.TANK, 3, 1),
        e("c", Role.TANK, 20, 1), e("d", Role.GRUNT, 1, 1));
    assertEquals("a", Director.strongest(k, 2, 10).id());
    // only tanks and an elite: the cheapest non-elite that fits fills, until none fits
    FightKit tanks = kit(tuning(0, 24), List.of(), e("war", Role.TANK, 2, 1), e("tower", Role.TANK, 3, 1),
        e("bulwark", Role.TANK, 1.5, 1), e("boss", Role.ELITE, 1, 1));
    Director.Plan p = Director.plan(tanks, RoomType.COMBAT, 0, 1, MARKERS, new Random(1));
    for (Director.Spawn s : p.waves().getFirst()) {
      assertEquals("bulwark", s.entry().id());
    }
    // the ranged cap skips melee and elites, last first
    RosterEntry arch = e("arch", Role.RANGED, 1, 1);
    RosterEntry foot = e("foot", Role.GRUNT, 1, 1);
    List<RosterEntry> picks = new ArrayList<>(List.of(arch, arch, foot));
    List<Affix> affixes = new ArrayList<>(java.util.Arrays.asList(null, Affix.SHIELDED, null));
    double[] left = {1};
    Director.capRanged(kit(tuning(0, 24), List.of(), foot, arch), picks, affixes, left, new Random(1), 0.4);
    assertEquals(List.of(foot, arch, foot), picks);
    // promotion: the costliest normal enemy at or above the minimum; nobody when none qualifies
    RosterEntry big = e("big", Role.TANK, 3, 1);
    RosterEntry mid = e("mid", Role.TANK, 2, 1);
    List<RosterEntry> group = List.of(mid, big, mid, foot, big);
    List<Affix> marks = new ArrayList<>(java.util.Arrays.asList(null, Affix.RALLYING, null, null, null));
    Director.promote(group, marks, 2, new Random(1));
    assertNotNull(marks.get(4), "the second big one: the first is already an elite");
    assertNull(marks.get(0));
    List<Affix> few = new ArrayList<>(java.util.Arrays.asList((Affix) null));
    Director.promote(List.of(foot), few, 2, new Random(1));
    assertNull(few.getFirst());
  }
}
