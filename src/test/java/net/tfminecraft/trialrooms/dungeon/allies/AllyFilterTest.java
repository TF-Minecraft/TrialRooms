package net.tfminecraft.trialrooms.dungeon.allies;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.skills.SkillCaster;
import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicAuraStartEvent;
import io.lumine.mythic.bukkit.events.MythicHealMechanicEvent;
import io.lumine.mythic.bukkit.events.MythicReloadedEvent;
import io.lumine.mythic.bukkit.utils.serialize.Optl;
import io.lumine.mythic.core.mobs.ActiveMob;
import io.lumine.mythic.core.mobs.MobExecutor;
import io.lumine.mythic.core.skills.AbstractSkill;
import io.lumine.mythic.core.skills.MetaSkill;
import io.lumine.mythic.core.skills.SkillCondition;
import io.lumine.mythic.core.skills.SkillExecutor;
import io.lumine.mythic.core.skills.SkillMechanic;
import io.lumine.mythic.core.skills.mechanics.CustomMechanic;
import io.lumine.mythic.core.skills.mechanics.DamageMechanic;
import io.lumine.mythic.core.skills.mechanics.DamageModMechanic;
import io.lumine.mythic.core.skills.mechanics.HealMechanic;
import io.lumine.mythic.core.skills.mechanics.OnDamagedMechanic;
import io.lumine.mythic.core.skills.mechanics.PotionMechanic;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class AllyFilterTest {
  ServerMock server;
  final Set<String> harmful = Set.of("slowed");
  final Set<String> helpful = Set.of("guardian_angel");
  final Logger log = Logger.getLogger("ally-test");

  org.mockito.MockedStatic<MythicBukkit> mythicStatic;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
  }

  @AfterEach
  void tearDown() {
    if (mythicStatic != null) {
      mythicStatic.close();
    }
    MockBukkit.unmock();
  }

  /** Creating a MythicMobs condition reaches for MythicMobs' skill manager. */
  void mythic() {
    MythicBukkit plugin = mock(MythicBukkit.class);
    when(plugin.getSkillManager()).thenReturn(mock(SkillExecutor.class));
    mythicStatic = mockStatic(MythicBukkit.class);
    mythicStatic.when(MythicBukkit::inst).thenReturn(plugin);
  }

  static <T extends SkillMechanic> T mechanic(Class<T> type, String line) {
    T m = mock(type);
    when(m.getConfigLine()).thenReturn(line);
    return m;
  }

  static List<?> targets(SkillMechanic m) throws Exception {
    Field f = AbstractSkill.class.getDeclaredField("conditionsTarget");
    f.setAccessible(true);
    return (List<?>) f.get(m);
  }

  @Test
  void attachesOnceToHelpingAndHarmingMechanics() throws Exception {
    mythic();
    HealMechanic heal = mechanic(HealMechanic.class, "heal{a=5}");
    PotionMechanic slow = mechanic(PotionMechanic.class, "potion{type=SLOW}");
    PotionMechanic glow = mechanic(PotionMechanic.class, "potion{type=GLOWING}");
    // a mechanic that already has target conditions keeps them
    Field f = AbstractSkill.class.getDeclaredField("conditionsTarget");
    f.setAccessible(true);
    List<SkillCondition> existing = new ArrayList<>(List.of(mock(SkillCondition.class)));
    f.set(slow, existing);
    AllyRules rules = new AllyRules(e -> true, e -> null);
    List<Object> all = List.of(heal, slow, glow, "not a mechanic");
    AllyFilter filter = new AllyFilter(rules, () -> harmful, () -> helpful, () -> all, log);
    assertEquals(2, filter.attach());
    assertEquals(EffectKind.SUPPORT, ((SideCondition) targets(heal).getFirst()).kind());
    assertEquals(2, targets(slow).size());
    assertNull(targets(glow));
    assertEquals(0, filter.attach(), "attaching again adds nothing");
    HealMechanic other = mechanic(HealMechanic.class, "heal{}");
    f.set(other, new ArrayList<>(List.of(mock(SkillCondition.class))));
    assertEquals("4 mechanics: 2 help, 1 harm, 2 filtered", new AllyFilter(rules, () -> harmful, () -> helpful,
        () -> List.of(heal, slow, glow, other), log).report());
    filter.onReload(mock(MythicReloadedEvent.class));
  }

  @Test
  void wrappedWardAndUntargetedMechanicsAreSortedByWhatTheyDo() throws Exception {
    mythic();
    AllyFilter filter = new AllyFilter(new AllyRules(e -> true, e -> null), () -> harmful,
        () -> Set.of("guardian_angel_ward"), List::of, log);
    // MythicLib's mmodamage arrives wrapped: sorted (and filtered) by what it wraps
    CustomMechanic wrapped = mechanic(CustomMechanic.class, "mmodamage{amount=5}");
    DamageMechanic inner = mechanic(DamageMechanic.class, "damage{}");
    when(wrapped.getMechanic()).thenReturn(Optional.of(inner));
    assertEquals(EffectKind.HARM, filter.kindOf(wrapped));
    CustomMechanic unloaded = mechanic(CustomMechanic.class, "mystery{}");
    when(unloaded.getMechanic()).thenReturn(Optional.empty());
    assertEquals(EffectKind.NONE, filter.kindOf(unloaded));
    // a ward is an aura, sorted by its name, never as damage
    OnDamagedMechanic ward = mechanic(OnDamagedMechanic.class, "onDamaged{auraName=Guardian_Angel_Ward;d=20}");
    assertEquals(EffectKind.SUPPORT, filter.kindOf(ward));
    // a mechanic without targets would stop working with a target condition: left alone
    assertEquals(EffectKind.NONE, filter.kindOf(mechanic(DamageModMechanic.class, "modifyDamage{amount=2}")));
    SkillMechanic both = mock(DamageModMechanic.class,
        withSettings().extraInterfaces(io.lumine.mythic.api.skills.ITargetedEntitySkill.class));
    when(both.getConfigLine()).thenReturn("damage{}");
    // it takes entity targets too, so it is sorted by its class like any other
    assertEquals(EffectKind.of(both.getClass().getSimpleName(), false, "damage{}", harmful, Set.of()),
        filter.kindOf(both));
    // the walker stops at the wrapper: only its conditions count
    List<SkillMechanic> found = AllyFilter.mechanicsIn(List.of(wrapped));
    assertEquals(List.of(wrapped), found);
    assertEquals(1, new AllyFilter(new AllyRules(e -> true, e -> null), () -> harmful, () -> helpful,
        () -> List.of(wrapped), log).attach());
  }

  @Test
  void aBrokenMechanicListOnlyWarnsOnce() {
    AllyFilter filter = new AllyFilter(new AllyRules(e -> true, e -> null), () -> harmful, () -> helpful, () -> {
      throw new IllegalStateException("gone");
    }, log);
    assertEquals(0, filter.attach());
    assertEquals(0, filter.attach());
  }

  @Test
  void theConditionAsksTheRules() {
    mythic();
    Player caster = mock(Player.class);
    LivingEntity mob = mock(LivingEntity.class);
    SideCondition c = new SideCondition(EffectKind.SUPPORT, new AllyRules(e -> true, e -> e == caster ? caster : null));
    SkillMetadata meta = mock(SkillMetadata.class, RETURNS_DEEP_STUBS);
    when(meta.getCaster().getEntity().getBukkitEntity()).thenReturn(caster);
    AbstractEntity target = mock(AbstractEntity.class);
    when(target.getBukkitEntity()).thenReturn(mob);
    assertFalse(c.check(meta, target));
    when(target.getBukkitEntity()).thenReturn(mock(Player.class));
    assertTrue(c.check(meta, target));
  }

  @Test
  void aurasAndHealsAreFilteredByEvent() {
    Player caster = mock(Player.class);
    Player ally = mock(Player.class);
    LivingEntity mob = mock(LivingEntity.class);
    AllyRules rules = new AllyRules(e -> true, e -> e == caster ? caster : null);
    AllyFilter filter = new AllyFilter(rules, () -> harmful, () -> helpful, List::of, log);
    SkillCaster sc = mock(SkillCaster.class, RETURNS_DEEP_STUBS);
    when(sc.getEntity().getBukkitEntity()).thenReturn(caster);
    AbstractEntity allyEntity = mock(AbstractEntity.class);
    when(allyEntity.getBukkitEntity()).thenReturn(ally);
    MythicAuraStartEvent slowAlly = mock(MythicAuraStartEvent.class);
    when(slowAlly.getAuraName()).thenReturn("Slowed");
    when(slowAlly.getCaster()).thenReturn(sc);
    when(slowAlly.getTargetEntity()).thenReturn(Optional.of(allyEntity));
    filter.onAura(slowAlly);
    verify(slowAlly).setCancelled(true);
    MythicAuraStartEvent noCaster = mock(MythicAuraStartEvent.class);
    when(noCaster.getAuraName()).thenReturn("Slowed");
    when(noCaster.getTargetEntity()).thenReturn(Optional.empty());
    filter.onAura(noCaster);
    verify(noCaster, never()).setCancelled(true);
    // a drunk ward stops the aura it is for (dungeon supplies)
    SkillCaster mobCaster = mock(SkillCaster.class, RETURNS_DEEP_STUBS);
    when(mobCaster.getEntity().getBukkitEntity()).thenReturn(mob);
    MythicAuraStartEvent slowed = mock(MythicAuraStartEvent.class);
    when(slowed.getAuraName()).thenReturn("Slowed");
    when(slowed.getCaster()).thenReturn(mobCaster);
    when(slowed.getTargetEntity()).thenReturn(Optional.of(allyEntity));
    filter.onAura(slowed);
    verify(slowed, never()).setCancelled(true);
    filter.wards((t, aura) -> t == ally && aura.equals("Frozen"));
    MythicAuraStartEvent frozen = mock(MythicAuraStartEvent.class);
    when(frozen.getAuraName()).thenReturn("Frozen");
    when(frozen.getCaster()).thenReturn(mobCaster);
    when(frozen.getTargetEntity()).thenReturn(Optional.of(allyEntity));
    filter.onAura(frozen);
    verify(frozen).setCancelled(true);
    filter.onAura(slowed);
    verify(slowed, never()).setCancelled(true);
    MythicHealMechanicEvent healMob = mock(MythicHealMechanicEvent.class, RETURNS_DEEP_STUBS);
    when(healMob.getMetadata().getCaster().getEntity().getBukkitEntity()).thenReturn(caster);
    when(healMob.getTarget()).thenReturn(mob);
    filter.onHeal(healMob);
    verify(healMob).setCancelled(true);
    MythicHealMechanicEvent healAlly = mock(MythicHealMechanicEvent.class, RETURNS_DEEP_STUBS);
    when(healAlly.getMetadata().getCaster().getEntity().getBukkitEntity()).thenReturn(caster);
    when(healAlly.getTarget()).thenReturn(ally);
    filter.onHeal(healAlly);
    verify(healAlly, never()).setCancelled(true);
  }

  @SuppressWarnings("unchecked")
  @Test
  void ownersAndMechanicsComeFromMythicMobs() {
    Player owner = server.addPlayer();
    MythicBukkit mythic = mock(MythicBukkit.class);
    MobExecutor mobs = mock(MobExecutor.class);
    SkillExecutor skills = mock(SkillExecutor.class);
    when(mythic.getMobManager()).thenReturn(mobs);
    when(mythic.getSkillManager()).thenReturn(skills);
    Entity summon = mock(Entity.class);
    UUID id = UUID.randomUUID();
    when(summon.getUniqueId()).thenReturn(id);
    ActiveMob active = mock(ActiveMob.class);
    Optl<UUID> none = mock(Optl.class);
    Optl<UUID> some = mock(Optl.class);
    when(some.isPresent()).thenReturn(true);
    when(some.get()).thenReturn(owner.getUniqueId());
    HealMechanic heal = mechanic(HealMechanic.class, "heal{}");
    when(skills.getSkills()).thenReturn(List.of());
    try (var mb = mockStatic(MythicBukkit.class)) {
      mb.when(MythicBukkit::inst).thenReturn(mythic);
      assertSame(owner, AllyFilter.ownerOf(owner));
      when(mobs.getActiveMob(id)).thenReturn(Optional.empty());
      assertNull(AllyFilter.ownerOf(summon));
      when(mobs.getActiveMob(id)).thenReturn(Optional.of(active));
      when(active.getOwnerUUID()).thenReturn(some);
      assertSame(owner, AllyFilter.ownerOf(summon));
      when(active.getOwnerUUID()).thenReturn(none);
      when(active.getParentUUID()).thenReturn(some);
      assertSame(owner, AllyFilter.ownerOf(summon));
      when(active.getParentUUID()).thenReturn(none);
      assertNull(AllyFilter.ownerOf(summon));
      assertTrue(AllyFilter.mechanicCache().isEmpty());
    }
    // the walker finds mechanics through collections, maps, optionals, arrays and skill objects
    MetaSkill.SkillAction action = new MetaSkill.SkillAction("a");
    action.addMechanic(heal);
    PotionMechanic potion = mechanic(PotionMechanic.class, "potion{type=SPEED}");
    Object[] array = {potion, null};
    List<Object> roots = new ArrayList<>(List.of(Map.of("k", Optional.of(array)), action, Optional.empty(), "x"));
    roots.add(roots);
    SkillExecutor executor = mock(SkillExecutor.class);
    roots.add(executor);
    List<SkillMechanic> found = AllyFilter.mechanicsIn(roots);
    assertTrue(found.contains(heal));
    assertTrue(found.contains(potion));
    verify(executor, never()).getSkills();
    // reflection helpers: a missing field, and fields that can't be read or written
    assertThrows(IllegalStateException.class, () -> AllyFilter.field(String.class, "nope"));
    assertNull(AllyFilter.read(AllyFilter.field(AllyFilterTest.class, "harmful"), "not a test"));
    Field fin;
    try {
      fin = Integer.class.getDeclaredField("value");
    } catch (NoSuchFieldException e) {
      throw new IllegalStateException(e);
    }
    Field frozen = fin;
    assertNull(AllyFilter.read(frozen, 5));
    assertThrows(IllegalStateException.class, () -> AllyFilter.write(frozen, 5, 6));
  }
}
