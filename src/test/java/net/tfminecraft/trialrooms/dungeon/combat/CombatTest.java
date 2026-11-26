package net.tfminecraft.trialrooms.dungeon.combat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import net.tfminecraft.trialrooms.dungeon.combat.AttackSpec.Shape;
import net.tfminecraft.trialrooms.dungeon.combat.AttackState.Phase;
import net.tfminecraft.trialrooms.dungeon.combat.AttackState.Step;
import net.tfminecraft.trialrooms.dungeon.combat.CombatSettings.Cue;

class CombatTest {

  /** MythicMobs as a list of what was asked of it. */
  static final class FakeControl implements MobControl {
    final List<String> cast = new ArrayList<>();
    final Map<LivingEntity, Set<String>> auras = new HashMap<>();
    final List<Area> marks = new ArrayList<>();
    final List<Boolean> leave = new ArrayList<>();
    int level = 1;
    final Map<Entity, String> named = new HashMap<>();

    @Override
    public void cast(LivingEntity mob, String skill, Entity target) {
      cast.add(skill + (target == null ? "" : "@" + named.getOrDefault(target, "x")));
    }

    @Override
    public boolean hasAura(LivingEntity mob, String aura) {
      return auras.getOrDefault(mob, Set.of()).contains(aura);
    }

    @Override
    public int level(LivingEntity mob) {
      return level;
    }

    @Override
    public void mark(Area area, boolean leave) {
      marks.add(area);
      this.leave.add(leave);
    }
  }

  static AttackSpec spec(String options) {
    Map<String, String> o = new HashMap<>();
    for (String kv : options.split(";")) {
      if (!kv.isBlank()) {
        String[] p = kv.split("=", 2);
        o.put(p[0], p[1]);
      }
    }
    return AttackSpec.of(o::get);
  }

  ServerMock server;
  WorldMock world;
  FakeControl control = new FakeControl();
  CombatSettings settings = CombatSettings.DEFAULTS;
  Set<Entity> marked = new HashSet<>();
  Attacks attacks;

  @BeforeEach
  void up() {
    server = MockBukkit.mock();
    world = server.addSimpleWorld("d");
    attacks = new Attacks(control, () -> settings, marked::contains);
    Attacks.stopPath = m -> {
    };
  }

  @AfterEach
  void down() {
    Attacks.stopPath = Attacks.STOP_PATH;
    MockBukkit.unmock();
  }

  Zombie zombie() {
    Zombie z = world.spawn(new Location(world, 0, 5, 0, 90, 0), Zombie.class);
    return z;
  }

  void ticks(int n) {
    for (int i = 0; i < n; i++) {
      attacks.tick();
    }
  }

  @Test
  void specsReadTheirOptions() {
    AttackSpec s = spec("windup=13;recover=16;guard=0.25;poise=0.1;magic=true;ground=cone;radius=4;angle=60;strike=Hit;start=Go;"
        + "interrupted=Alt;root=false;forceground=true;id=cleave;chain=Next");
    assertEquals(new AttackSpec("cleave", 13, 16, 0.25, 0.1, true, Shape.CONE, 4, 60, false, false, true, "Hit", "Go", "Alt", "Next"), s);
    assertTrue(s.staggers());
    AttackSpec d = spec("strike=Hit");
    assertEquals(new AttackSpec("Hit", 13, 16, 0.5, 0, false, Shape.NONE, 3, 90, false, true, false, "Hit", null, null, null), d);
    assertFalse(d.staggers());
    assertTrue(spec("strike=Hit;ground=ring").leave(), "a ring is to be left");
    assertTrue(spec("strike=Hit;ground=target").leave());
    assertFalse(spec("strike=Hit;ground=ring;leave=false").leave());
    assertTrue(spec("strike=Hit;ground=line;leave=true").leave());
    assertNull(spec("strike=Hit;start= ").start());
    assertEquals("Hit", spec("strike=Hit;id= ").id());
    for (String bad : List.of("", "strike= ", "strike=H;windup=0", "strike=H;recover=-1", "strike=H;guard=2", "strike=H;guard=-0.1",
        "strike=H;poise=-1", "strike=H;radius=-1", "strike=H;angle=0", "strike=H;angle=400", "strike=H;windup=x",
        "strike=H;guard=x", "strike=H;ground=square")) {
      assertThrows(IllegalArgumentException.class, () -> spec(bad), bad);
    }
  }

  @Test
  void anAttackWindsUpStrikesAndStandsOpen() {
    AttackState s = new AttackState(spec("windup=4;recover=2;guard=0;strike=H"), 30);
    assertEquals(0, s.taken(0.3));
    assertEquals(Step.NOTHING, s.tick(2)); // slowed: half a tick of windup
    assertEquals(Step.NOTHING, s.tick(1));
    assertEquals(Step.NOTHING, s.tick(0.5)); // (never faster than as written)
    assertEquals(Step.NOTHING, s.tick(1));
    assertEquals(Step.STRIKE, s.tick(1));
    assertEquals(Phase.OPEN, s.phase());
    assertEquals(1.3, s.taken(0.3), 1e-9);
    assertFalse(s.hurt(100, 10), "only a windup can be staggered");
    assertFalse(s.stagger());
    assertEquals(Step.NOTHING, s.tick(5));
    assertEquals(Step.OVER, s.tick(1));
    assertEquals(Phase.OVER, s.phase());
    assertEquals(1, s.taken(0.3));
    assertEquals(Step.NOTHING, s.tick(1));
    assertFalse(s.broken());
    AttackState quick = new AttackState(spec("windup=1;recover=0;strike=H"), 30);
    assertEquals(Step.STRIKE, quick.tick(1));
    assertEquals(Phase.OVER, quick.phase());
  }

  @Test
  void poiseAndSpellsBreakAttacksOff() {
    AttackState heavy = new AttackState(spec("windup=10;recover=4;poise=0.1;strike=H"), 30);
    assertFalse(heavy.hurt(5, 100));
    assertFalse(heavy.hurt(1, 0), "no max health: no stagger");
    assertTrue(heavy.hurt(5, 100));
    assertTrue(heavy.broken());
    assertEquals(Phase.OPEN, heavy.phase());
    for (int i = 0; i < 33; i++) {
      assertEquals(Step.NOTHING, heavy.tick(1));
    }
    assertEquals(Step.OVER, heavy.tick(1), "its recovery and the stagger's");
    AttackState steady = new AttackState(spec("windup=10;strike=H"), 30);
    assertFalse(steady.hurt(1000, 10), "no poise: never staggers");
    assertTrue(steady.stagger(), "a status breaks any windup off (which statuses: the settings)");
    assertTrue(steady.broken());
  }

  @Test
  void settingsReadAndFallBack() {
    assertSame(CombatSettings.DEFAULTS, CombatSettings.from(null));
    YamlConfiguration y = new YamlConfiguration();
    y.set("open-bonus", List.of(0.5, 0.2));
    y.set("stagger-ticks", 10);
    y.set("interrupt-auras", List.of("Stun"));
    y.set("stagger-auras", List.of("Frozen"));
    y.set("slow-auras.Chilled", 2.0);
    y.set("slowness-step", 0.1);
    y.set("max-slow", 3.0);
    y.set("cues.open", "My_Open");
    y.set("cues.clear", "");
    CombatSettings s = CombatSettings.from(y);
    assertEquals(List.of(0.5, 0.2), s.openBonus());
    assertEquals(0.5, s.openBonus(0));
    assertEquals(0.2, s.openBonus(7), "past the list: the last");
    assertEquals(10, s.staggerTicks());
    assertEquals(List.of("Stun"), s.interruptAuras());
    assertEquals(List.of("Frozen"), s.staggerAuras());
    assertEquals(3.0, s.maxSlow());
    assertNull(s.cues().get(Cue.CLEAR), "a blank cue: none");
    assertEquals(Map.of("Chilled", 2.0), s.slowAuras());
    assertEquals("My_Open", s.cues().get(Cue.OPEN));
    assertEquals("TR_Cue_Dodge", s.cues().get(Cue.DODGE));
    CombatSettings blank = CombatSettings.from(new YamlConfiguration());
    assertEquals(CombatSettings.DEFAULTS, blank);
    YamlConfiguration bad = new YamlConfiguration();
    bad.set("open-bonus", List.of());
    assertThrows(IllegalArgumentException.class, () -> CombatSettings.from(bad));
    assertThrows(IllegalArgumentException.class, () -> new CombatSettings(List.of(0.1), -1, List.of(), List.of(), Map.of(), 0, 2, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> new CombatSettings(List.of(0.1), 0, List.of(), List.of(), Map.of(), -1, 2, Map.of()));
    assertThrows(IllegalArgumentException.class, () -> new CombatSettings(List.of(0.1), 0, List.of(), List.of(), Map.of(), 0, 0.5, Map.of()));
  }

  @Test
  void attacksCueStrikeAndOpen() {
    Zombie z = zombie();
    Zombie victim = zombie();
    control.named.put(victim, "Ann");
    assertTrue(attacks.start(z, victim, spec("windup=3;recover=2;guard=0.5;strike=Hit;start=Go")));
    assertTrue(attacks.busy(z));
    var speed = z.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
    assertNotNull(speed.getModifier(Attacks.ROOT), "it stops walking at once");
    assertTrue(z.isAware(), "and stops thinking a tick later");
    assertFalse(attacks.start(z, victim, spec("strike=Hit")), "busy");
    assertEquals(List.of("TR_Cue_Dodge", "Go@Ann"), control.cast);
    assertEquals(0.5, attacks.taken(z));
    assertEquals(Phase.WINDUP, attacks.phase(z));
    assertTrue(control.marks.isEmpty(), "not a boss: nothing on the ground");
    ticks(1);
    assertTrue(z.isAware(), "one AI tick at speed 0 first");
    ticks(1);
    assertFalse(z.isAware());
    ticks(1);
    assertEquals(List.of("TR_Cue_Dodge", "Go@Ann", "Hit@Ann", "TR_Cue_Open"), control.cast);
    assertEquals(1 + 0.4, attacks.taken(z), 1e-9);
    control.level = 4;
    assertEquals(1 + 0.25, attacks.taken(z), 1e-9, "a Mythic enemy stands less open");
    assertEquals(List.of(z.getName() + ": Hit OPEN"), attacks.describe());
    ticks(2);
    assertFalse(attacks.busy(z));
    assertNull(attacks.phase(z));
    assertTrue(z.isAware());
    assertNull(speed.getModifier(Attacks.ROOT));
    assertEquals("TR_Cue_Clear", control.cast.getLast());
    assertEquals(1, attacks.taken(z));
    // no recovery: over at the strike
    control.cast.clear();
    attacks.start(z, null, spec("windup=1;recover=0;root=false;strike=Hit"));
    assertTrue(z.isAware(), "a moving attack");
    ticks(1);
    assertFalse(attacks.busy(z));
    assertEquals(List.of("TR_Cue_Dodge", "Hit", "TR_Cue_Clear"), control.cast, "no opening: no white flash");
    // a cue left out of the settings casts nothing
    settings = new CombatSettings(List.of(0.3), 30, List.of(), List.of(), Map.of(), 0.2, 2, Map.of());
    control.cast.clear();
    attacks.start(z, null, spec("windup=1;recover=0;strike=Hit"));
    ticks(1);
    assertEquals(List.of("Hit"), control.cast);
  }

  @Test
  void bossesAndLeadersMarkTheGround() {
    Zombie boss = zombie();
    Zombie target = zombie();
    target.teleport(new Location(world, 6, 5, 2));
    marked.add(boss);
    attacks.start(boss, target, spec("windup=9;ground=target;radius=3;strike=Hit"));
    assertEquals(1, control.marks.size());
    MobControl.Area a = control.marks.getFirst();
    assertEquals(Shape.TARGET, a.shape());
    assertEquals(6, a.x());
    assertEquals(2, a.z());
    assertTrue(control.leave.getFirst(), "red: get out");
    ticks(8);
    assertEquals(1 + 8 / Attacks.MARK_EVERY, control.marks.size(), "drawn again while it winds up");
    ticks(5);
    assertEquals(3, control.marks.size(), "not after the strike");
    // an enemy with a cone, forced; and a target shape without a target is at the enemy
    Zombie grunt = zombie();
    control.marks.clear();
    attacks.start(grunt, null, spec("windup=2;ground=cone;forceground=true;strike=Hit"));
    assertEquals(Shape.CONE, control.marks.getFirst().shape());
    assertEquals(90f, control.marks.getFirst().yaw());
    assertFalse(control.leave.getLast());
    Zombie other = zombie();
    marked.add(other);
    attacks.start(other, null, spec("windup=2;ground=target;strike=Hit"));
    assertEquals(0, control.marks.getLast().x());
    // an enemy that is neither marked nor forced marks nothing
    control.marks.clear();
    attacks.start(zombie(), null, spec("windup=2;ground=cone;strike=Hit"));
    assertTrue(control.marks.isEmpty());
    // a shape of NONE marks nothing even on a boss
    control.marks.clear();
    Zombie plain = zombie();
    marked.add(plain);
    attacks.start(plain, null, spec("windup=2;strike=Hit"));
    assertTrue(control.marks.isEmpty());
  }

  @Test
  void spellsBreakOffAndSlowsStretchWindups() {
    Zombie mage = zombie();
    attacks.start(mage, null, spec("windup=10;recover=2;magic=true;strike=Bolt;interrupted=Blink"));
    assertTrue(control.cast.contains("TR_Cue_Interrupt"));
    ticks(2);
    control.auras.put(mage, Set.of("Silenced"));
    control.cast.clear();
    ticks(1);
    assertEquals(List.of("TR_Cue_Clear", "TR_Cue_Break", "TR_Cue_Open", "Blink"), control.cast, "its alternative");
    assertEquals(Phase.OPEN, attacks.phase(mage));
    assertTrue(attacks.brokenOff(mage), "the rest of its start skill must not play");
    ticks(40);
    assertFalse(attacks.busy(mage));
    assertTrue(attacks.brokenOff(mage), "still, until its next attack");
    // without an alternative it only stands open
    control.cast.clear();
    attacks.start(mage, null, spec("windup=10;magic=true;strike=Bolt"));
    assertFalse(attacks.brokenOff(mage), "a new attack starts unbroken");
    ticks(1);
    assertFalse(control.cast.contains("Bolt"));
    ticks(60);
    // a swing ignores a silence, but a freeze or stun breaks it off
    control.cast.clear();
    attacks.start(mage, null, spec("windup=2;recover=0;strike=Swing"));
    ticks(2);
    assertTrue(control.cast.contains("Swing"));
    control.auras.put(mage, Set.of("Stun_Aura"));
    attacks.start(mage, null, spec("windup=4;recover=0;strike=Swing2"));
    ticks(1);
    assertEquals(Phase.OPEN, attacks.phase(mage), "stunned out of its swing");
    ticks(40);
    // a spell also breaks on an aura listed only among the stagger auras
    settings = new CombatSettings(List.of(0.3), 30, List.of("Silenced"), List.of("Frozen"), Map.of(), 0.2, 2, CombatSettings.DEFAULTS.cues());
    control.auras.put(mage, Set.of("Frozen"));
    attacks.start(mage, null, spec("windup=4;recover=0;magic=true;strike=Bolt2"));
    ticks(1);
    assertEquals(Phase.OPEN, attacks.phase(mage), "frozen out of its spell");
    ticks(40);
    // slows: Slowness III (x1.6) and a slow aura (x1.3), at most twice as long
    settings = new CombatSettings(List.of(0.3), 30, List.of(), List.of(), Map.of("Slowed", 1.3), 0.2, 2, CombatSettings.DEFAULTS.cues());
    control.auras.put(mage, Set.of("Slowed"));
    mage.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 200, 2));
    assertEquals(2, attacks.slow(mage), 1e-9, "capped: a freeze never makes it tougher for long");
    settings = new CombatSettings(List.of(0.3), 30, List.of(), List.of(), Map.of("Slowed", 1.3), 0.2, 3, CombatSettings.DEFAULTS.cues());
    assertEquals(1.6 * 1.3, attacks.slow(mage), 1e-9);
    control.cast.clear();
    attacks.start(mage, null, spec("windup=4;recover=0;strike=Swing"));
    ticks(8);
    assertFalse(control.cast.contains("Swing"), "4 ticks of windup take more than 8");
    ticks(1);
    assertTrue(control.cast.contains("Swing"));
  }

  @Test
  void brokenOffIsForgottenWithTheMob() {
    Zombie a = zombie();
    Zombie b = zombie();
    attacks.start(a, null, spec("windup=10;strike=Hit"));
    attacks.start(b, null, spec("windup=10;strike=Hit"));
    control.auras.put(a, Set.of("Frozen"));
    control.auras.put(b, Set.of("Frozen"));
    ticks(1);
    assertTrue(attacks.brokenOff(a));
    assertTrue(attacks.brokenOff(b));
    a.remove();
    ticks(1);
    assertFalse(attacks.brokenOff(a), "gone: forgotten");
    assertTrue(attacks.brokenOff(b));
    attacks.stop();
    assertFalse(attacks.brokenOff(b), "the plugin stops: forgotten");
  }

  @Test
  void hitsFollowTheGuardOpeningAndPoise() {
    Zombie z = zombie();
    Zombie by = zombie();
    attacks.start(z, null, spec("windup=10;recover=4;guard=0;poise=0.2;strike=Hit"));
    attacks.onHurtDone(new EntityDamageEvent(z, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 30));
    assertEquals(Phase.OPEN, attacks.phase(z), "staggered, no fallback");
    attacks.stop();
    attacks.start(z, null, spec("windup=10;recover=4;guard=0;poise=0.2;strike=Hit"));
    EntityDamageEvent e = new EntityDamageEvent(z, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 4);
    attacks.onStruck(e);
    assertTrue(e.isCancelled(), "immune in this windup");
    EntityDamageEvent kill = new EntityDamageEvent(z, EntityDamageEvent.DamageCause.KILL, 4);
    attacks.onStruck(kill);
    attacks.onHurt(kill);
    assertFalse(kill.isCancelled(), "staff's /kill always kills");
    assertEquals(4, kill.getDamage());
    // a braced one: half, and poise counts what landed
    Zombie b = zombie();
    attacks.start(b, null, spec("windup=10;recover=4;guard=0.5;poise=0.2;strike=Hit;interrupted=Free"));
    EntityDamageEvent half = new EntityDamageEvent(b, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 4);
    attacks.onStruck(half);
    attacks.onHurt(half);
    assertEquals(2, half.getDamage());
    attacks.onHurtDone(half);
    assertEquals(Phase.WINDUP, attacks.phase(b));
    control.cast.clear();
    attacks.onHurtDone(new EntityDamageEvent(b, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 3));
    assertEquals(Phase.OPEN, attacks.phase(b), "20% of 20 health: staggered");
    assertEquals(List.of("TR_Cue_Clear", "TR_Cue_Break", "TR_Cue_Open", "Free"), control.cast, "its fallback, on a stagger too");
    EntityDamageEvent open = new EntityDamageEvent(b, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 10);
    attacks.onHurt(open);
    assertEquals(14, open.getDamage(), 1e-9);
    // anything else is left alone
    EntityDamageEvent other = new EntityDamageEvent(by, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 3);
    attacks.onStruck(other);
    attacks.onHurt(other);
    attacks.onHurtDone(other);
    assertEquals(3, other.getDamage());
    assertFalse(other.isCancelled());
  }

  @Test
  void deathsAndStopsEndAttacks() {
    Zombie z = zombie();
    attacks.start(z, null, spec("windup=10;strike=Hit"));
    attacks.onDeath(new EntityDeathEvent(zombie(), org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(), List.of()));
    assertTrue(attacks.busy(z), "another's death");
    z.setHealth(0);
    control.cast.clear();
    attacks.onDeath(new EntityDeathEvent(z, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(), List.of()));
    assertFalse(attacks.busy(z));
    assertTrue(control.cast.isEmpty(), "no cue on the dead");
    assertFalse(attacks.start(z, null, spec("strike=Hit")), "dead: no attack");
    // a mob killed mid-attack (before its death event) is dropped on the next tick, and stays still
    Zombie dying = zombie();
    attacks.start(dying, null, spec("windup=10;strike=Hit"));
    dying.setHealth(0);
    ticks(1);
    assertFalse(attacks.busy(dying));
    // a mob gone mid-attack is dropped on the next tick
    LivingEntity unloaded = mock(LivingEntity.class);
    when(unloaded.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
    when(unloaded.getLocation()).thenReturn(new Location(world, 0, 5, 0));
    attacks.start(unloaded, null, spec("windup=10;strike=Hit"));
    ticks(1);
    assertFalse(attacks.busy(unloaded), "no longer valid (its chunk unloaded)");
    Zombie gone = zombie();
    attacks.start(gone, null, spec("windup=10;strike=Hit"));
    gone.remove();
    ticks(1);
    assertFalse(attacks.busy(gone));
    // stop: everyone moves again
    Zombie a = zombie();
    attacks.start(a, null, spec("windup=10;strike=Hit"));
    attacks.stop();
    assertFalse(attacks.busy(a));
    assertTrue(a.isAware());
  }

  @Test
  void combosChainAndKillingBlowsSkipTheFallback() {
    Zombie z = zombie();
    attacks.start(z, null, spec("windup=2;recover=0;strike=Swing1;chain=Swing2_TRY"));
    ticks(2);
    assertEquals(List.of("TR_Cue_Dodge", "Swing1", "TR_Cue_Clear", "Swing2_TRY"), control.cast, "the next part in the same tick");
    // broken off: no next part
    control.cast.clear();
    attacks.start(z, null, spec("windup=4;recover=0;poise=0.1;strike=Swing1;chain=Swing2_TRY"));
    attacks.onHurtDone(new EntityDamageEvent(z, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5));
    ticks(60);
    assertFalse(control.cast.contains("Swing2_TRY"));
    // a stagger by the killing blow: no fallback from the dying
    Zombie b = zombie();
    b.setHealth(3);
    control.cast.clear();
    attacks.start(b, null, spec("windup=4;poise=0.1;strike=Hit;interrupted=Alt"));
    attacks.onHurtDone(new EntityDamageEvent(b, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5));
    assertFalse(control.cast.contains("Alt"));
    // a mob with no speed to take away is still rooted
    org.bukkit.entity.Mob odd = mock(org.bukkit.entity.Mob.class, RETURNS_DEEP_STUBS);
    when(odd.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
    when(odd.getLocation()).thenReturn(new Location(world, 0, 5, 0));
    when(odd.isAware()).thenReturn(true);
    when(odd.isValid()).thenReturn(true);
    when(odd.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED)).thenReturn(null);
    Attacks.stopPath = Attacks.STOP_PATH;
    attacks.start(odd, null, spec("windup=1;recover=0;strike=Hit"));
    verify(odd.getPathfinder()).stopPathfinding();
    Attacks.stopPath = m -> {
    };
    ticks(2); // (its deep-stubbed Slowness slows it a little)
    verify(odd).setAware(false);
    verify(odd).setAware(true);
    // a speed already held back isn't held back twice
    Zombie twice = zombie();
    var speed = twice.getAttribute(org.bukkit.attribute.Attribute.MOVEMENT_SPEED);
    speed.addTransientModifier(new org.bukkit.attribute.AttributeModifier(Attacks.ROOT, -1,
        org.bukkit.attribute.AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    attacks.start(twice, null, spec("windup=1;recover=0;strike=Hit"));
    assertEquals(1, speed.getModifiers().size());
    attacks.stop();
    // stopped mid-combo: no next part
    control.cast.clear();
    attacks.start(z, null, spec("windup=4;recover=0;strike=Swing1;chain=Swing2_TRY"));
    attacks.stop();
    assertFalse(control.cast.contains("Swing2_TRY"));
    // a slow aura the enemy doesn't carry changes nothing
    settings = new CombatSettings(List.of(0.3), 30, List.of(), List.of(), Map.of("Chilled", 1.5), 0.2, 2, Map.of());
    assertEquals(1, attacks.slow(zombie()));
  }

  @Test
  void enemiesThatCantStandStillOrHaveNoHealthAttribute() {
    LivingEntity thing = mock(LivingEntity.class);
    when(thing.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
    when(thing.getLocation()).thenReturn(new Location(world, 0, 5, 0));
    when(thing.isValid()).thenReturn(true);
    when(thing.getName()).thenReturn("thing");
    assertTrue(attacks.start(thing, null, spec("windup=4;poise=0.1;strike=Hit")), "not a Mob: it isn't rooted");
    attacks.onHurtDone(new EntityDamageEvent(thing, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 3));
    assertEquals(Phase.WINDUP, attacks.phase(thing), "no max health: no stagger");
    attacks.stop();
    // a mob already standing still stays so after its attack
    Zombie z = zombie();
    z.setAware(false);
    attacks.start(z, null, spec("windup=1;recover=0;strike=Hit"));
    ticks(1);
    assertFalse(z.isAware());
  }
}
