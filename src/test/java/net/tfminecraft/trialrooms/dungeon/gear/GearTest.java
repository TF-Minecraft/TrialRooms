package net.tfminecraft.trialrooms.dungeon.gear;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import net.tfminecraft.trialrooms.dungeon.gear.GearSet.Kind;
import net.tfminecraft.trialrooms.dungeon.gear.GearTags.Piece;
import net.tfminecraft.trialrooms.dungeon.loot.LootCoreTest;
import net.tfminecraft.trialrooms.dungeon.loot.MmoLootForgeGearTest;

class GearTest {

  ServerMock server;
  WorldMock world;
  final Map<String, GearSet> sets = new HashMap<>();
  final Map<String, Double> worth = Map.of("armor", 0.5, "max_health", 0.25, "physical_damage", 2.0, "projectile_damage", 2.0,
      "magic_damage", 2.0);
  final Map<UUID, Map<String, Double>> put = new HashMap<>();
  final Set<UUID> inside = new HashSet<>();
  final Set<UUID> downed = new HashSet<>();
  final Set<UUID> friendly = new HashSet<>();
  long now = 100_000;
  GearService gear;

  static GearSet set(String id, Trait.Kind kind, double value) {
    return new GearSet(id, id.toUpperCase(), "rare", List.of(), Map.of(2, Map.of("armor", 2.0), 4, Map.of("max_health", 4.0)),
        Map.of(2, Map.of("armor", 1.0)), new Trait(kind, value));
  }

  @BeforeEach
  void up() {
    server = MockBukkit.mock();
    world = server.addSimpleWorld("d");
    for (Trait.Kind k : Trait.Kind.values()) {
      sets.put(k.name().toLowerCase(), set(k.name().toLowerCase(), k, k.seconds() ? 2 : 0.5));
    }
    gear = new GearService(server, p -> inside.contains(p.getUniqueId()), p -> !downed.contains(p.getUniqueId()),
        e -> e instanceof Zombie && !friendly.contains(e.getUniqueId()), () -> sets, () -> worth, (p, s) -> put.put(p.getUniqueId(), s),
        () -> now);
  }

  @AfterEach
  void down() {
    MockBukkit.unmock();
  }

  static ItemStack item(String set, Kind kind, double power) {
    ItemStack it = new ItemStack(Material.PAPER);
    GearTags.write(it, new Piece(set, kind, power));
    return it;
  }

  PlayerMock player(String name, String weapon) {
    PlayerMock p = server.addPlayer(name);
    p.teleport(new Location(world, 0, 5, 0));
    inside.add(p.getUniqueId());
    if (weapon != null) {
      p.getInventory().setItemInMainHand(item(weapon, Kind.WEAPON, 1));
    }
    gear.tick();
    return p;
  }

  Zombie zombie(double x) {
    return world.spawn(new Location(world, x, 5, 0), Zombie.class);
  }

  double hit(Entity by, LivingEntity victim, double damage) {
    EntityDamageByEntityEvent e = new EntityDamageByEntityEvent(by, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, damage);
    gear.onDamage(e);
    return e.getDamage();
  }

  @Test
  void traitsAndSetsReadWell() {
    assertEquals("50% more damage to unhurt enemies", new Trait(Trait.Kind.OPENING, 0.5).text(1));
    assertEquals("every 3rd hit slows the enemy for 1.2 seconds", new Trait(Trait.Kind.CHILL, 3).text(Trait.OFF_HAND));
    assertEquals("enemies that hit you are slowed for 3 seconds", new Trait(Trait.Kind.RIME, 2).text(Trait.RESONANCE));
    assertThrows(IllegalArgumentException.class, () -> new Trait(Trait.Kind.WARD, 0));
    assertThrows(IllegalArgumentException.class, () -> new Trait(Trait.Kind.WARD, Double.POSITIVE_INFINITY));
    GearSet s = set("ward", Trait.Kind.WARD, 0.1);
    assertEquals(List.of("WARD weapon: 10% less damage for 4 seconds after a hit.", "Stronger with 2 pieces of WARD armour."),
        s.lore(Kind.WEAPON, 1, worth));
    assertEquals(List.of("WARD off hand: 4% less damage for 4 seconds after a hit."), s.lore(Kind.OFF_HAND, 1, worth));
    assertEquals(List.of("WARD armour set", "2 pieces: +2 Armor", "4 pieces: +2 Max Health"), s.lore(Kind.ARMOUR, 2, worth));
    assertEquals(List.of("WARD jewellery set", "2 pieces: +0.5 Armor"), s.lore(Kind.JEWELLERY, 1, worth));
    assertEquals(Map.of(), s.bonuses(Kind.WEAPON));
    assertEquals("+2 Physical Damage", GearSet.text(Map.of("physical_damage", 1.0), 1, worth));
    // the shipped kit's sets all read
    LootCoreTest.shipped().sets().values().forEach(x -> {
      for (Kind k : Kind.values()) {
        assertFalse(x.lore(k, 1.5, LootCoreTest.shipped().worth()).isEmpty());
      }
    });
  }

  @Test
  void itemsRememberTheirSet() {
    assertNull(GearTags.read(null));
    assertNull(GearTags.read(new ItemStack(Material.PAPER)));
    ItemStack named = new ItemStack(Material.PAPER);
    named.editMeta(m -> m.setUnbreakable(true));
    assertNull(GearTags.read(named));
    assertEquals(new Piece("ward", Kind.ARMOUR, 1.5), GearTags.read(item("ward", Kind.ARMOUR, 1.5)));
    MmoLootForgeGearTest.tags();
  }

  @Test
  void bonusesComeFromPiecesAndTheirStrength() {
    Map<String, Double> w = worth;
    Piece a = new Piece("ward", Kind.ARMOUR, 1);
    Piece b = new Piece("ward", Kind.ARMOUR, 2);
    GearBonuses.Result two = GearBonuses.of(List.of(a, b, new Piece("gone", Kind.ARMOUR, 1), new Piece("gone", Kind.WEAPON, 1)), sets, w);
    assertEquals(Map.of("armor", 2.0 * 0.5 * 1.5), two.stats(), "the average strength of the pieces");
    assertEquals(List.of("WARD armour: 2 of 4", "  2 pieces: +1.5 Armor"), two.lines());
    GearBonuses.Result all = GearBonuses.of(List.of(a, a, a, a, new Piece("ward", Kind.JEWELLERY, 1), new Piece("ward", Kind.JEWELLERY, 1),
        new Piece("ward", Kind.WEAPON, 1), new Piece("burn", Kind.OFF_HAND, 1), new Piece("ward", Kind.OFF_HAND, 1)), sets, w);
    assertEquals(Map.of("armor", 1.5, "max_health", 1.0), all.stats());
    assertEquals(0.5 * 1.5 + 0.5 * 0.4 * 1.5, all.value(Trait.Kind.WARD), 1e-9, "resonating, and a share in the off hand");
    assertEquals(0.5 * 0.4, all.value(Trait.Kind.BURN), 1e-9);
    assertEquals(0, all.value(Trait.Kind.MEND));
    assertTrue(all.lines().contains("WARD weapon (resonating): 75% less damage for 4 seconds after a hit"), all.lines().toString());
    assertTrue(all.lines().contains("BURN off hand: every 4th hit burns for 20% of its damage"), all.lines().toString());
    assertTrue(all.lines().contains("WARD jewellery: 2 of 4"));
  }

  @Test
  void bonusesFollowWhatIsWornAndEndOutside() {
    PlayerMock p = player("Ann", null);
    assertEquals(Map.of(), put, "nothing to put on");
    assertEquals(GearBonuses.Result.NONE, gear.result(p));
    p.getInventory().setItem(36, item("ward", Kind.ARMOUR, 1));
    p.getInventory().setItem(37, item("ward", Kind.ARMOUR, 1));
    p.getInventory().setItem(39, item("ward", Kind.WEAPON, 1)); // (not a helmet: it doesn't count there)
    p.getInventory().setItem(11, item("ward", Kind.JEWELLERY, 1));
    p.getInventory().setItem(12, item("ward", Kind.JEWELLERY, 1));
    gear.tick();
    assertEquals(Map.of("armor", 1.5), put.get(p.getUniqueId()));
    put.clear();
    gear.tick();
    assertEquals(Map.of(), put, "unchanged: nothing to do");
    inside.clear();
    gear.tick();
    assertEquals(Map.of(), put.get(p.getUniqueId()), "taken off outside");
    put.clear();
    gear.tick();
    gear.onQuit(new PlayerQuitEvent(p, net.kyori.adventure.text.Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
    assertEquals(Map.of(), put);
    inside.add(p.getUniqueId());
    gear.tick();
    gear.stop();
    assertEquals(Map.of(), put.get(p.getUniqueId()));
    // a trait alone puts no stats on, and takes none off
    put.clear();
    PlayerMock q = player("Bo", "mend");
    p.getInventory().clear();
    gear.tick();
    gear.stop();
    assertFalse(put.containsKey(q.getUniqueId()));
  }

  @Test
  void damageTraits() {
    PlayerMock opening = player("A", "opening");
    Zombie z = zombie(2);
    assertEquals(15, hit(opening, z, 10));
    z.setHealth(10);
    assertEquals(10, hit(opening, z, 10));
    PlayerMock execute = player("B", "execute");
    assertEquals(10, hit(execute, z, 10));
    z.setHealth(5);
    assertEquals(15, hit(execute, z, 10));
    Arrow arrow = world.spawn(z.getLocation(), Arrow.class);
    arrow.setShooter(execute);
    assertEquals(15, hit(arrow, z, 10), "arrows too");
    Arrow stray = world.spawn(z.getLocation(), Arrow.class);
    assertEquals(10, hit(stray, z, 10));
    friendly.add(z.getUniqueId());
    assertEquals(10, hit(execute, z, 10), "only enemies");
    friendly.clear();
    PlayerMock composed = player("C", "composed");
    z.setHealth(10);
    assertEquals(15, hit(composed, z, 10));
    assertEquals(4, hit(z, composed, 4));
    assertEquals(10, hit(composed, z, 10));
    now += 5000;
    assertEquals(15, hit(composed, z, 10));
    PlayerMock duel = player("D", "duel");
    assertEquals(10, hit(duel, z, 10));
    assertEquals(11.25, hit(duel, z, 10));
    for (int i = 0; i < 5; i++) {
      hit(duel, z, 10);
    }
    assertEquals(15, hit(duel, z, 10));
    assertEquals(10, hit(duel, zombie(4), 10), "a new target starts over");
    PlayerMock none = player("E", null);
    assertEquals(10, hit(none, z, 10));
    inside.remove(execute.getUniqueId());
    z.setHealth(5);
    assertEquals(10, hit(execute, z, 10), "only inside");
    assertEquals(3, hit(z, z, 3));
  }

  @Test
  void hitsSetThingsOff() {
    PlayerMock burn = player("A", "burn");
    Zombie z = zombie(2);
    z.getAttribute(Attribute.MAX_HEALTH).setBaseValue(100);
    z.setHealth(90);
    for (int i = 0; i < 4; i++) {
      hit(burn, z, 10);
    }
    assertEquals(85, z.getHealth(), 1e-6, "half of the fourth hit");
    assertTrue(z.getFireTicks() >= 40);
    PlayerMock shards = player("B", "shards");
    Zombie near = zombie(3);
    Zombie tame = zombie(3);
    friendly.add(tame.getUniqueId());
    PlayerMock by = player("F", null);
    by.teleport(near.getLocation());
    near.getAttribute(Attribute.MAX_HEALTH).setBaseValue(100);
    near.setHealth(50);
    for (int i = 0; i < 5; i++) {
      hit(shards, z, 10);
    }
    assertEquals(45, near.getHealth(), 1e-6);
    PlayerMock chill = player("C", "chill");
    hit(chill, z, 1);
    hit(chill, z, 1);
    assertNull(z.getPotionEffect(PotionEffectType.SLOWNESS));
    hit(chill, z, 1);
    assertEquals(40, z.getPotionEffect(PotionEffectType.SLOWNESS).getDuration());
    PlayerMock rime = player("D", "rime");
    hit(near, rime, 1);
    assertNotNull(near.getPotionEffect(PotionEffectType.SLOWNESS));
  }

  @Test
  void alliesAreHealed() {
    PlayerMock me = player("A", "radiance");
    PlayerMock friend = player("B", null);
    PlayerMock outsider = player("C", null);
    inside.remove(outsider.getUniqueId());
    outsider.setHealth(1);
    friend.setHealth(5);
    Zombie z = zombie(2);
    now += 10_000;
    hit(me, z, 1);
    assertEquals(15, friend.getHealth());
    assertEquals(1, outsider.getHealth());
    hit(me, z, 1);
    assertEquals(15, friend.getHealth(), "not again yet");
    now += 3000;
    downed.add(friend.getUniqueId());
    me.setHealth(19);
    hit(me, z, 1);
    assertEquals(15, friend.getHealth(), "the downed wait for a revive");
    assertEquals(20, me.getHealth());
    now += 3000;
    downed.add(me.getUniqueId());
    me.setHealth(2);
    hit(me, z, 1);
    assertEquals(2, me.getHealth());
    // a kill
    PlayerMock mend = player("D", "mend");
    mend.setHealth(4);
    Zombie dead = zombie(2);
    dead.setKiller(mend);
    gear.onDeath(new org.bukkit.event.entity.EntityDeathEvent(dead, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(), List.of()));
    assertEquals(14, mend.getHealth());
    friendly.add(dead.getUniqueId());
    gear.onDeath(new org.bukkit.event.entity.EntityDeathEvent(dead, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(), List.of()));
    assertEquals(14, mend.getHealth());
    dead.setKiller(friend);
    gear.onDeath(new org.bukkit.event.entity.EntityDeathEvent(dead, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(), List.of()));
    dead.setKiller(null);
    gear.onDeath(new org.bukkit.event.entity.EntityDeathEvent(dead, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build(), List.of()));
    assertEquals(14, mend.getHealth());
  }

  @Test
  void wardsAndBraces() {
    PlayerMock ward = player("A", "ward");
    Zombie z = zombie(2);
    assertEquals(10, hit(z, ward, 10), "the first hit sets it off");
    assertEquals(5, hit(z, ward, 10));
    now += 4000;
    assertEquals(10, hit(z, ward, 10), "as long without it as with it");
    assertEquals(10, hit(z, ward, 10));
    now += 4000;
    assertEquals(10, hit(z, ward, 10));
    assertEquals(5, hit(z, ward, 10));
    ward.teleport(new Location(world, 100, 5, 0));
    PlayerMock brace = player("B", "brace");
    assertEquals(10, hit(z, brace, 10), "alone and hale");
    brace.setHealth(8);
    assertEquals(5, hit(z, brace, 10));
    brace.setHealth(20);
    player("C", null);
    assertEquals(5, hit(z, brace, 10), "an ally is near");
    friendly.add(z.getUniqueId());
    assertEquals(10, hit(z, brace, 10), "only enemies' hits");
  }
}
