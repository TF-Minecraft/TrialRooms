package net.tfminecraft.trialrooms.dungeon.allies;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Set;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class AllyRulesTest {

  static final Set<String> HARMFUL = Set.of("slowed", "wounded");
  static final Set<String> HELPFUL = Set.of("guardian_angel");

  @Test
  void mechanicsAreSortedIntoHelpHarmAndNeither() {
    assertEquals(EffectKind.SUPPORT, EffectKind.of("HealMechanic", "heal{amount=5}", HARMFUL, HELPFUL));
    assertEquals(EffectKind.SUPPORT, EffectKind.of("HealPercentMechanic", null, HARMFUL, HELPFUL));
    assertEquals(EffectKind.SUPPORT, EffectKind.of("ShieldMechanic", "shield{}", HARMFUL, HELPFUL));
    assertEquals(EffectKind.SUPPORT, EffectKind.of("AbsorbMechanic", "", HARMFUL, HELPFUL));
    assertEquals(EffectKind.SUPPORT, EffectKind.of("PotionClearMechanic", "potionclear", HARMFUL, HELPFUL));
    assertEquals(EffectKind.NONE, EffectKind.of("ShieldBreakMechanic", "", HARMFUL, HELPFUL));
    assertEquals(EffectKind.NONE, EffectKind.of("AuraRemoveMechanic", "auraremove{aura=slowed}", HARMFUL, HELPFUL));
    assertEquals(EffectKind.HARM, EffectKind.of("DamageMechanic", "damage{a=5}", HARMFUL, HELPFUL));
    assertEquals(EffectKind.HARM, EffectKind.of("MMODamageMechanic", "mmodamage{a=5}", HARMFUL, HELPFUL));
    for (String m : EffectKind.HARM_MECHANICS) {
      assertEquals(EffectKind.HARM, EffectKind.of(m, "x", HARMFUL, HELPFUL), m);
    }
    assertEquals(EffectKind.HARM, EffectKind.of("PotionMechanic", "potion{type=SLOW;duration=20}", HARMFUL, HELPFUL));
    assertEquals(EffectKind.SUPPORT, EffectKind.of("PotionMechanic", "potion{duration=20; t=speed}", HARMFUL, HELPFUL));
    assertEquals(EffectKind.NONE, EffectKind.of("PotionMechanic", "potion{type=GLOWING}", HARMFUL, HELPFUL));
    assertEquals(EffectKind.NONE, EffectKind.of("PotionMechanic", "potion{duration=20}", HARMFUL, HELPFUL));
    assertEquals(EffectKind.HARM, EffectKind.of("AuraMechanic", "Aura{auraName=Slowed;d=20}", HARMFUL, HELPFUL));
    assertEquals(EffectKind.SUPPORT, EffectKind.of("AuraMechanic", "aura{d=10;name=Guardian_Angel}", HARMFUL, HELPFUL));
    assertEquals(EffectKind.NONE, EffectKind.of("AuraMechanic", "aura{auraName=Hawk_Eye}", HARMFUL, HELPFUL));
    assertEquals(EffectKind.NONE, EffectKind.of("SetVariableMechanic", "setvariable{var=x}", HARMFUL, HELPFUL));
    assertEquals(EffectKind.NONE, EffectKind.potion(null));
    assertEquals(EffectKind.NONE, EffectKind.aura(null, HARMFUL, HELPFUL));
  }

  @Test
  void playersNeverHelpMobsOrHarmEachOtherInDungeons() {
    Player caster = mock(Player.class);
    Player ally = mock(Player.class);
    LivingEntity mob = mock(LivingEntity.class);
    Entity totem = mock(Entity.class);
    Entity outsider = mock(Entity.class);
    AllyRules rules = new AllyRules(e -> e != outsider, e -> e == caster ? caster : e == totem ? caster : null);
    // help from a player: allies yes, mobs no
    assertTrue(rules.allows(EffectKind.SUPPORT, caster, ally));
    assertFalse(rules.allows(EffectKind.SUPPORT, caster, mob));
    // harm from a player: mobs and the caster themselves yes, allies no
    assertTrue(rules.allows(EffectKind.HARM, caster, mob));
    assertTrue(rules.allows(EffectKind.HARM, caster, caster));
    assertFalse(rules.allows(EffectKind.HARM, caster, ally));
    // a player's summon counts as the player
    assertFalse(rules.allows(EffectKind.HARM, totem, ally));
    assertFalse(rules.allows(EffectKind.SUPPORT, totem, mob));
    // a mob's skills harm players, help mobs, and do anything to the mob itself
    LivingEntity other = mock(LivingEntity.class);
    assertTrue(rules.allows(EffectKind.HARM, mob, ally));
    assertTrue(rules.allows(EffectKind.HARM, mob, totem), "a player's summon is the player's side");
    assertFalse(rules.allows(EffectKind.HARM, mob, other), "no harm to another mob");
    assertTrue(rules.allows(EffectKind.SUPPORT, mob, other));
    assertFalse(rules.allows(EffectKind.SUPPORT, mob, ally), "no help for a player");
    assertTrue(rules.allows(EffectKind.HARM, mob, mob), "on itself (a rise that holds it still)");
    // only a run's enemy is held to the enemy side: an orphaned summon or a stray is left alone
    Entity orphan = mock(Entity.class);
    AllyRules sided = new AllyRules(e -> true, e -> e == caster ? caster : null, e -> e == mob || e == other);
    assertTrue(sided.allows(EffectKind.HARM, orphan, other));
    assertTrue(sided.allows(EffectKind.SUPPORT, orphan, ally));
    assertTrue(sided.allows(EffectKind.HARM, mob, orphan), "not an enemy, so fair game");
    assertFalse(sided.allows(EffectKind.SUPPORT, mob, orphan));
    assertFalse(sided.allows(EffectKind.HARM, mob, other));
    assertTrue(sided.allows(EffectKind.SUPPORT, mob, other));
    // unclassified effects and the world outside are left alone
    assertTrue(rules.allows(EffectKind.NONE, caster, ally));
    assertTrue(rules.allows(EffectKind.HARM, caster, outsider));
    assertTrue(rules.allows(EffectKind.HARM, null, ally));
    assertTrue(rules.allows(EffectKind.HARM, caster, null));
  }
}
