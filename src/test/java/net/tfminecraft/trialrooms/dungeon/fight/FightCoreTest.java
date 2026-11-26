package net.tfminecraft.trialrooms.dungeon.fight;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class FightCoreTest {

  static final String KIT = """
      roster:
        footman: {mob: foot, role: grunt, cost: 1, weight: 3}
        archer: {mob: arch, role: ranged, cost: 2.5}
        tank: {mob: tank, role: elite, cost: 5}
        broken: {role: grunt}
        flat: 3
      packs:
        patrol: {slots: [grunt, grunt, ranged], weight: 2, min-depth: 1}
        bad: {slots: [dragon]}
        flat: 4
      bosses:
        warden:
          mob: tank
          name: The Warden
          health: 300
          enrage-seconds: 500
          phases:
            - {at: 100, mechanics: [slam], every: 12}
            - {at: 50, adds: 4, mechanics: [shockwave, rally-call]}
            - {at: 20}
        nophase: {mob: tank, health: 10, phases: [{at: 50}]}
        badat: {mob: tank, health: 10, phases: [{at: high}]}
        flat: 5
      fights:
        budget-combat: 7
        tier-factors: [1, 2]
        slam-seconds: 1
      """;

  static YamlConfiguration yml(String text) throws Exception {
    YamlConfiguration y = new YamlConfiguration();
    y.loadFromString(text);
    return y;
  }

  @Test
  void enumsParseAndDescribe() {
    assertEquals(Role.CASTER, Role.parse(" caster "));
    assertThrows(IllegalArgumentException.class, () -> Role.parse("dragon"));
    assertThrows(IllegalArgumentException.class, () -> Role.parse(null));
    assertTrue(Role.RANGED.ranged());
    assertTrue(Role.CASTER.ranged());
    assertFalse(Role.TANK.ranged());
    assertEquals("ranged", Role.CASTER.markerTag());
    assertEquals("melee", Role.ELITE.markerTag());
    assertEquals(Mechanic.RALLY_CALL, Mechanic.parse("rally-call"));
    assertThrows(IllegalArgumentException.class, () -> Mechanic.parse("nap"));
    assertThrows(IllegalArgumentException.class, () -> Mechanic.parse(null));
    assertEquals("Shielded", Affix.SHIELDED.title());
    assertTrue(Affix.VOLATILE.hint().contains("step away"));
  }

  @Test
  void recordsCheckTheirValues() {
    assertThrows(IllegalArgumentException.class, () -> new RosterEntry("a", null, Role.GRUNT, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new RosterEntry("a", " ", Role.GRUNT, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new RosterEntry("a", "m", Role.GRUNT, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new RosterEntry("a", "m", Role.GRUNT, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> new PackTemplate("p", null, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> new PackTemplate("p", List.of(), 1, 0));
    assertThrows(IllegalArgumentException.class, () -> new PackTemplate("p", List.of(Role.GRUNT), 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new PackTemplate("p", List.of(Role.GRUNT), 1, -1));
    var full = new BossDef.Phase(100, 0, List.of(), 10);
    assertThrows(IllegalArgumentException.class, () -> new BossDef.Phase(0, 0, List.of(), 10));
    assertThrows(IllegalArgumentException.class, () -> new BossDef.Phase(101, 0, List.of(), 10));
    assertThrows(IllegalArgumentException.class, () -> new BossDef.Phase(50, -1, List.of(), 10));
    assertThrows(IllegalArgumentException.class, () -> new BossDef.Phase(50, 0, List.of(), 0));
    assertThrows(IllegalArgumentException.class, () -> new BossDef("b", null, "", 10, List.of(full), 0));
    assertThrows(IllegalArgumentException.class, () -> new BossDef("b", " ", "", 10, List.of(full), 0));
    assertThrows(IllegalArgumentException.class, () -> new BossDef("b", "m", "", 0, List.of(full), 0));
    assertThrows(IllegalArgumentException.class, () -> new BossDef("b", "m", "", 10, List.of(full), -1));
    assertThrows(IllegalArgumentException.class, () -> new BossDef("b", "m", "", 10, List.of(), 0));
    assertThrows(IllegalArgumentException.class,
        () -> new BossDef("b", "m", "", 10, List.of(new BossDef.Phase(50, 0, List.of(), 1)), 0));
    assertThrows(IllegalArgumentException.class,
        () -> new BossDef("b", "m", "", 10, List.of(full, new BossDef.Phase(100, 0, List.of(), 1)), 0));
    BossDef b = new BossDef("b", "m", null, 10, List.of(new BossDef.Phase(40, 0, List.of(), 1), full), 0);
    assertEquals("b", b.name());
    assertEquals(100, b.phases().getFirst().at());
    assertEquals("b", new BossDef("b", "m", " ", 10, List.of(full), 0).name());
    assertEquals("Boss", new BossDef("b", "m", "Boss", 10, List.of(full), 0).name());
  }

  @Test
  void tuningClampsAndReads() throws Exception {
    FightTuning d = FightTuning.DEFAULT;
    assertEquals(1.0, d.tierFactor(0));
    assertEquals(1.8, d.tierFactor(9));
    assertEquals(0.45, d.damage(0.35, 3), 1e-9);
    assertEquals(0.35, d.damage(0.35, 0), 1e-9);
    assertSame(d, FightTuning.from(null));
    FightTuning t = FightTuning.from(yml(KIT).getConfigurationSection("fights"));
    assertEquals(7, t.budgetCombat());
    assertEquals(List.of(1.0, 2.0), t.tierFactors());
    assertEquals(FightTuning.MIN_WARNING, t.slamSeconds());
    FightTuning low = new FightTuning(1, 1, 0, List.of(), 1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0, 0,
        1, 1, 1, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 1, 1);
    assertEquals(List.of(1.0), low.tierFactors());
    assertEquals(3, low.waveWarningSeconds());
    assertEquals(4, low.floorSeconds());
    assertEquals(1, low.maxAlive());
    assertEquals(1, low.healthCap());
    assertEquals(FightTuning.MIN_WARNING, low.shockwaveSeconds());
    assertEquals(FightTuning.MIN_WARNING, low.burstSeconds());
  }

  @Test
  void kitsLoadAndReportBrokenEntries() throws Exception {
    List<String> problems = new ArrayList<>();
    FightKit kit = FightKit.load(yml(KIT), "k", problems);
    assertEquals(List.of("footman", "archer", "tank"), List.copyOf(kit.roster().keySet()));
    assertEquals(1, kit.packs().size());
    assertEquals(List.of("warden"), List.copyOf(kit.bosses().keySet()));
    assertEquals(7, problems.size(), problems.toString());
    BossDef w = kit.boss("");
    assertEquals("The Warden", w.name());
    assertEquals(3, w.phases().size());
    assertEquals(List.of(Mechanic.SHOCKWAVE, Mechanic.RALLY_CALL), w.phases().get(1).mechanics());
    assertEquals(12, w.phases().get(2).everySeconds());
    assertSame(w, kit.boss(null));
    assertSame(w, kit.boss("warden"));
    assertNull(kit.boss("nope"));
    assertEquals(List.of(kit.roster().get("archer")), kit.ofRole(Role.RANGED));
    assertEquals(7, kit.tuning().budgetCombat());
    // no roster: not a fight kit; a roster with nothing usable is reported
    assertNull(FightKit.load(yml("mobs: {melee: x}"), "k", problems));
    problems.clear();
    assertNull(FightKit.load(yml("roster: {a: {role: grunt}}"), "k", problems));
    assertTrue(problems.getLast().contains("no usable enemies"));
    // a kit with only a roster: no packs, no bosses, default numbers
    FightKit bare = FightKit.load(yml("roster: {a: {mob: m}}"), "k", problems);
    assertNull(bare.boss(""));
    assertSame(FightTuning.DEFAULT, bare.tuning());
    assertThrows(IllegalArgumentException.class, () -> new FightKit(Map.of(), List.of(), Map.of(), FightTuning.DEFAULT));
  }

  @Test
  void difficultiesBringTheirOwnEnemiesAndBosses() throws Exception {
    assertEquals(Tiers.ALL, Tiers.parse(null));
    assertEquals(new Tiers(3, 3), Tiers.parse(3));
    assertEquals(new Tiers(2, 4), Tiers.parse("2-4"));
    assertEquals(new Tiers(2, Integer.MAX_VALUE), Tiers.parse("2+"));
    assertTrue(Tiers.parse("2+").contains(9));
    assertFalse(Tiers.parse("2-3").contains(1));
    assertFalse(Tiers.parse("2-3").contains(4));
    for (String bad : List.of("x", "0", "3-2", "a+", "1-b")) {
      assertThrows(IllegalArgumentException.class, () -> Tiers.parse(bad), bad);
    }
    List<String> problems = new ArrayList<>();
    FightKit kit = FightKit.load(yml("""
        roster:
          grunt: {mob: g, role: grunt}
          late: {mob: l, role: grunt, tiers: 3+}
          leader: {mob: b, role: elite, cost: 6, tiers: 2-3, leader: true}
          odd: {mob: o, tiers: nope}
        packs:
          early: {slots: [grunt], tiers: 1}
          any: {slots: [grunt]}
        bosses:
          golem: {mob: g, health: 10, tiers: 1, phases: [{at: 100}]}
          brood: {mob: b, health: 10, tiers: 2-3, phases: [{at: 100}]}
          mage: {mob: m, health: 10, tiers: 2-3, phases: [{at: 100}]}
          general: {mob: v, health: 10, tiers: 4, phases: [{at: 100}]}
        """), "k", problems);
    assertEquals(List.of("k: roster odd: bad tiers: nope"), problems);
    assertTrue(kit.roster().get("leader").leader());
    assertEquals(List.of("grunt"), List.copyOf(kit.atTier(1).roster().keySet()));
    assertEquals(List.of("early", "any"), kit.atTier(1).packs().stream().map(PackTemplate::id).toList());
    assertEquals(List.of("grunt", "late", "leader"), List.copyOf(kit.atTier(3).roster().keySet()));
    assertEquals(List.of("any"), kit.atTier(3).packs().stream().map(PackTemplate::id).toList());
    FightKit lateOnly = FightKit.load(yml("roster: {late: {mob: l, tiers: 3}}"), "k", problems);
    assertSame(lateOnly, lateOnly.atTier(1), "a tier with no enemies of its own keeps them all");
    assertEquals("general", kit.bossFor(null, 4, 1).id(), "Mythic: always the general");
    assertEquals("golem", kit.bossFor("", 1, 7).id());
    java.util.Set<String> pool = new java.util.HashSet<>();
    for (long seed = 0; seed < 40; seed++) {
      pool.add(kit.bossFor(null, 2, seed).id());
      assertEquals(kit.bossFor(null, 2, seed), kit.bossFor(null, 2, seed), "the same seed, the same boss");
    }
    assertEquals(java.util.Set.of("brood", "mage"), pool);
    assertEquals("brood", kit.bossFor("brood", 1, 1).id(), "a recipe may fix one");
    assertEquals("golem", kit.bossFor(null, 9, 1).id(), "no boss of the tier: the kit's first");
    assertNull(FightKit.load(yml("roster: {a: {mob: m}}"), "k", problems).bossFor(null, 1, 1));
    assertEquals(Tiers.ALL, new RosterEntry("a", "m", Role.GRUNT, 1, 1).tiers());
    assertEquals(Tiers.ALL, new PackTemplate("p", List.of(Role.GRUNT), 1, 0).tiers());
  }

  @Test
  void telegraphsKnowWhoIsInside() {
    Telegraph t = new Telegraph(Telegraph.Kind.SLAM, 10, 64, 10, 4, 1000, 4000);
    assertTrue(t.inside(12, 64, 12));
    assertFalse(t.inside(15, 64, 10));
    assertTrue(t.inside(12, 67, 12), "a step up is still hit");
    assertFalse(t.inside(12, 68, 12), "a balcony above is not");
    assertFalse(t.inside(12, 60, 12), "nor a pit below");
    assertFalse(t.due(3999));
    assertTrue(t.due(4000));
    assertEquals(3, t.secondsLeft(1000));
    assertEquals(0, t.secondsLeft(9000));
    double[] out = t.wayOut(12, 10);
    assertEquals(15.5, out[0], 1e-9);
    assertEquals(10, out[1], 1e-9);
    double[] centre = t.wayOut(10, 10);
    assertEquals(10, centre[0], 1e-9);
    assertEquals(15.5, centre[1], 1e-9);
  }

  @Test
  void bossFightsKeepTheirRhythm() {
    FightTuning t = FightTuning.DEFAULT;
    BossDef def = new BossDef("b", "m", "Boss", 100, List.of(new BossDef.Phase(100, 0, List.of(Mechanic.SLAM), 12),
        new BossDef.Phase(50, 3, List.of(Mechanic.RALLY_CALL, Mechanic.SHOCKWAVE), 2),
        new BossDef.Phase(20, 0, List.of(), 10)), 60);
    BossFight f = new BossFight(def, t, 0);
    assertSame(def, f.def());
    assertEquals(0, f.startMs());
    assertEquals(0.5, f.floorShare());
    // nothing until the first interval has passed
    assertNull(f.next(11_000, false, 0));
    assertEquals(Mechanic.SLAM, f.next(12_000, false, 0));
    // one at a time, then a quiet gap
    assertNull(f.next(30_000, true, 0));
    f.resolved(30_000);
    assertNull(f.next(31_000, false, 0));
    assertEquals(Mechanic.SLAM, f.next(32_000, false, 0));
    // phases
    assertNull(f.crossed(60, 100, 33_000));
    assertSame(def.phases().get(1), f.crossed(50, 100, 33_000));
    assertEquals(1, f.phase());
    assertEquals(33_000, f.phaseStartMs(1));
    assertEquals(0, f.phaseStartMs(2));
    assertTrue(f.immune(34_000));
    assertFalse(f.immune(35_000));
    assertEquals(0.2, f.floorShare());
    // the interval is floored at 8 s; a call for aid is skipped while adds are many
    assertEquals(Mechanic.SHOCKWAVE, f.next(44_000, false, 9));
    assertNull(f.next(45_000, false, 0), "floored at 8 s");
    assertEquals(Mechanic.RALLY_CALL, f.next(52_000, false, 0));
    assertSame(def.phases().get(2), f.crossed(10, 100, 53_000));
    assertNull(f.crossed(0, 100, 54_000), "no phase after the last");
    // health is a float: a hit capped at a threshold lands on the nearest float, which still counts
    BossDef thirds = new BossDef("t", "m", "T", 10, List.of(new BossDef.Phase(100, 0, List.of(), 8),
        new BossDef.Phase(66, 0, List.of(), 8)), 0);
    assertNotNull(new BossFight(thirds, t, 0).crossed((float) (720 * 0.66), 720, 0));
    assertNotNull(new BossFight(thirds, t, 0).crossed((float) (1040 * 0.66), 1040, 0));
    assertNull(new BossFight(thirds, t, 0).crossed(720 * 0.66 + 1, 720, 0));
    assertEquals(0, f.floorShare());
    assertNull(f.next(70_000, false, 0), "a phase without mechanics");
    // enrage once, after its time; aid every 30 s unless adds are many
    assertFalse(f.enrage(59_000));
    assertFalse(f.enrageAdds(59_000, 0));
    assertEquals(1, f.damageFactor());
    assertTrue(f.enrage(60_000));
    assertTrue(f.enraged());
    assertFalse(f.enrage(61_000));
    assertEquals(1.25, f.damageFactor());
    assertFalse(f.enrageAdds(61_000, 0));
    assertFalse(f.enrageAdds(90_000, 9));
    assertTrue(f.enrageAdds(120_000, 0));
    f.hit();
    assertEquals(1, f.mechanicHits());
    // a boss that never enrages; a phase of nothing but calls for aid with too many adds
    BossDef calm = new BossDef("c", "m", "Calm", 10, List.of(new BossDef.Phase(100, 0, List.of(Mechanic.RALLY_CALL), 8)), 0);
    BossFight g = new BossFight(calm, t, 0);
    assertFalse(g.enrage(10_000_000));
    assertNull(g.next(8_000, false, 9));
    assertSame(calm.phases().getFirst(), g.current());
  }
}
