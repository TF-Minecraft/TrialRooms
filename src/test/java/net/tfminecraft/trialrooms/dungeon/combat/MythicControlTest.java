package net.tfminecraft.trialrooms.dungeon.combat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.config.MythicLineConfig;
import io.lumine.mythic.api.skills.SkillCaster;
import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.api.skills.SkillResult;
import io.lumine.mythic.api.skills.ThreadSafetyLevel;
import io.lumine.mythic.bukkit.BukkitAPIHelper;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicMechanicLoadEvent;
import io.lumine.mythic.core.mobs.ActiveMob;
import net.tfminecraft.trialrooms.dungeon.combat.AttackSpec.Shape;

class MythicControlTest {

  Attacks attacks = mock(Attacks.class);
  Logger log = mock(Logger.class);
  MythicControl control = new MythicControl(() -> attacks, log);

  @Test
  void castsReadsAurasAndLevels() {
    MythicBukkit mythic = mock(MythicBukkit.class, RETURNS_DEEP_STUBS);
    BukkitAPIHelper api = mock(BukkitAPIHelper.class);
    LivingEntity mob = mock(LivingEntity.class);
    Entity target = mock(Entity.class);
    Location at = new Location(null, 1, 2, 3);
    when(mob.getLocation()).thenReturn(at);
    AbstractEntity abs = mock(AbstractEntity.class);
    try (MockedStatic<MythicBukkit> mb = mockStatic(MythicBukkit.class); MockedStatic<BukkitAdapter> ad = mockStatic(BukkitAdapter.class)) {
      mb.when(MythicBukkit::inst).thenReturn(mythic);
      ad.when(() -> BukkitAdapter.adapt(mob)).thenReturn(abs);
      when(mythic.getAPIHelper()).thenReturn(api);
      control.cast(mob, "Hit", target);
      verify(api).castSkill(mob, "Hit", target, at, List.of(target), List.of(), 1f);
      control.cast(mob, "Cue", null);
      verify(api).castSkill(mob, "Cue", null, at, List.of(), List.of(), 1f);
      when(mythic.getSkillManager().getAuraManager().getHasAura(abs, "Silenced")).thenReturn(true);
      assertTrue(control.hasAura(mob, "Silenced"));
      assertFalse(control.hasAura(mob, "Slowed"));
      assertEquals(1, control.level(mob), "not a MythicMobs mob");
      ActiveMob am = mock(ActiveMob.class);
      when(api.getMythicMobInstance(mob)).thenReturn(am);
      when(am.getLevel()).thenReturn(3.0);
      assertEquals(3, control.level(mob));
      when(am.getLevel()).thenReturn(0.0);
      assertEquals(1, control.level(mob));
    }
  }

  @Test
  void areasAreDrawnAsOutlines() {
    World w = mock(World.class);
    MobControl.Area ring = new MobControl.Area(Shape.RING, w, 0, 64, 0, 0, 2, 90);
    control.mark(ring, true);
    verify(w, atLeast(16)).spawnParticle(eq(Particle.DUST), anyDouble(), eq(64.15), anyDouble(), eq(1), eq(0.0), eq(0.0),
        eq(0.0), eq(0.0), any(Particle.DustOptions.class));
    for (double[] p : MythicControl.outline(ring)) {
      assertEquals(2, Math.hypot(p[0], p[1]), 1e-6);
    }
    // a cone facing south (yaw 0 is +z): its edge points all lie south of the enemy
    List<double[]> cone = MythicControl.outline(new MobControl.Area(Shape.CONE, w, 0, 64, 0, 0, 4, 90));
    assertTrue(cone.stream().allMatch(p -> p[1] >= -1e-6), "all in front");
    assertTrue(cone.stream().anyMatch(p -> Math.abs(p[0]) < 0.5 && p[1] > 3.9), "its middle reaches 4 ahead");
    List<double[]> full = MythicControl.outline(new MobControl.Area(Shape.CONE, w, 0, 64, 0, 0, 3, 360));
    assertTrue(full.stream().allMatch(p -> Math.abs(Math.hypot(p[0], p[1]) - 3) < 1e-6), "a full cone has no edges");
    // a line facing west (yaw 90 is -x): two rails 1 block either side
    List<double[]> line = MythicControl.outline(new MobControl.Area(Shape.LINE, w, 0, 64, 0, 90, 4, 90));
    assertTrue(line.stream().allMatch(p -> p[0] <= 1e-6 && Math.abs(Math.abs(p[1]) - 1) < 1e-6), "west, a block aside");
    assertEquals(MythicControl.outline(new MobControl.Area(Shape.TARGET, w, 0, 64, 0, 0, 2, 90)).size(),
        MythicControl.outline(ring).size());
    assertTrue(MythicControl.outline(new MobControl.Area(Shape.NONE, w, 0, 64, 0, 0, 2, 90)).isEmpty());
    control.mark(new MobControl.Area(Shape.NONE, w, 0, 64, 0, 0, 2, 90), false);
  }

  @Test
  void theMechanicLoadsAndStartsAttacks() {
    MythicMechanicLoadEvent other = mock(MythicMechanicLoadEvent.class);
    when(other.getMechanicName()).thenReturn("damage");
    control.onMechanicLoad(other);
    verify(other, never()).register(any());
    MythicMechanicLoadEvent event = mock(MythicMechanicLoadEvent.class);
    MythicLineConfig mlc = mock(MythicLineConfig.class);
    when(event.getMechanicName()).thenReturn("DungeonAttack");
    when(event.getConfig()).thenReturn(mlc);
    when(mlc.getString(new String[] { "strike" }, null)).thenReturn("Hit");
    when(mlc.getString(new String[] { "windup" }, null)).thenReturn("7");
    control.onMechanicLoad(event);
    var captor = org.mockito.ArgumentCaptor.forClass(io.lumine.mythic.api.skills.ISkillMechanic.class);
    verify(event).register(captor.capture());
    MythicControl.Mechanic mech = (MythicControl.Mechanic) captor.getValue();
    assertEquals(7, mech.spec().windup());
    assertEquals(ThreadSafetyLevel.SYNC_ONLY, mech.getThreadSafetyLevel());
    // a bad line is reported, not registered
    MythicMechanicLoadEvent bad = mock(MythicMechanicLoadEvent.class);
    MythicLineConfig badCfg = mock(MythicLineConfig.class);
    when(bad.getMechanicName()).thenReturn("dungeonattack");
    when(bad.getConfig()).thenReturn(badCfg);
    when(badCfg.getLine()).thenReturn("dungeonattack{}");
    control.onMechanicLoad(bad);
    verify(bad, never()).register(any());
    verify(log).warning(contains("bad dungeonattack line (dungeonattack{})"));

    // casting it
    SkillMetadata data = mock(SkillMetadata.class);
    SkillCaster caster = mock(SkillCaster.class);
    AbstractEntity casterEntity = mock(AbstractEntity.class);
    Zombie mob = mock(Zombie.class);
    LivingEntity player = mock(LivingEntity.class);
    AbstractEntity target = mock(AbstractEntity.class);
    when(data.getCaster()).thenReturn(caster);
    when(caster.getEntity()).thenReturn(casterEntity);
    when(casterEntity.getBukkitEntity()).thenReturn(mob);
    when(target.getBukkitEntity()).thenReturn(player);
    when(attacks.start(mob, player, mech.spec())).thenReturn(true);
    assertEquals(SkillResult.SUCCESS, mech.castAtEntity(data, target));
    assertEquals(SkillResult.CONDITION_FAILED, mech.castAtEntity(data, null), "no such attack started (mocked)");
    when(mob.getTarget()).thenReturn(player);
    assertEquals(SkillResult.SUCCESS, mech.cast(data), "at the caster's own target");
    Entity notAlive = mock(Entity.class);
    when(casterEntity.getBukkitEntity()).thenReturn(notAlive);
    assertEquals(SkillResult.INVALID_TARGET, mech.cast(data));
    MythicControl.Mechanic noEngine = new MythicControl.Mechanic(mech.spec(), () -> null);
    when(casterEntity.getBukkitEntity()).thenReturn(mob);
    assertEquals(SkillResult.INVALID_TARGET, noEngine.castAtEntity(data, target));
    assertEquals(Optional.empty(), Optional.empty());
  }
}
