package net.tfminecraft.trialrooms.dungeon.run;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import net.tfminecraft.rpcharacters.lifecycle.CharacterActivatedEvent;
import net.tfminecraft.rpcharacters.lifecycle.CharacterSelectionRequestEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class RunListenerTest {
  ServerMock server;
  RunManager runs;
  RunListener l;
  Plugin plugin;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    plugin = MockBukkit.createMockPlugin();
    runs = mock(RunManager.class);
    l = new RunListener(plugin, runs);
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  static EntityDamageEvent damage(Entity victim, Entity damager, DamageCause cause, double amount) {
    DamageSource source = mock(DamageSource.class);
    when(source.getCausingEntity()).thenReturn(damager instanceof Player ? damager : null);
    if (damager == null) {
      return new EntityDamageEvent(victim, cause, source, amount);
    }
    return new EntityDamageByEntityEvent(damager, victim, cause, source, amount);
  }

  Player player() {
    Player p = mock(Player.class);
    when(p.getWorld()).thenReturn(mock(World.class));
    when(p.getServer()).thenReturn(server);
    return p;
  }

  @Test
  void findsTheAttackingPlayer() {
    Player p = player();
    LivingEntity victim = mock(LivingEntity.class);
    assertSame(p, RunListener.attacker(damage(victim, p, DamageCause.ENTITY_ATTACK, 1)));
    Arrow arrow = mock(Arrow.class);
    when(arrow.getShooter()).thenReturn(p);
    assertSame(p, RunListener.attacker(damage(victim, arrow, DamageCause.PROJECTILE, 1)));
    Arrow mobArrow = mock(Arrow.class);
    when(mobArrow.getShooter()).thenReturn(mock(ProjectileSource.class));
    assertNull(RunListener.attacker(damage(victim, mobArrow, DamageCause.PROJECTILE, 1)));
    assertNull(RunListener.attacker(damage(victim, mock(LivingEntity.class), DamageCause.ENTITY_ATTACK, 1)));
    assertNull(RunListener.attacker(damage(victim, null, DamageCause.FALL, 1)));
    // a player hit reported only through the damage source
    DamageSource s = mock(DamageSource.class);
    when(s.getCausingEntity()).thenReturn(p);
    assertSame(p, RunListener.attacker(new EntityDamageEvent(victim, DamageCause.MAGIC, s, 1)));
    // the damager is a player but the source names no one
    DamageSource none = mock(DamageSource.class);
    assertSame(p, RunListener.attacker(new EntityDamageByEntityEvent(p, victim, DamageCause.ENTITY_ATTACK, none, 1)));
  }

  @Test
  void enemiesNeverHurtEachOtherNorFallToTheirDeath() {
    LivingEntity a = mock(LivingEntity.class);
    LivingEntity b = mock(LivingEntity.class);
    LivingEntity summon = mock(LivingEntity.class);
    Player p = player();
    when(runs.inDungeonWorld(any())).thenReturn(true);
    l = new RunListener(plugin, runs, e -> e == a || e == b);
    EntityDamageEvent friendly = damage(b, a, DamageCause.ENTITY_ATTACK, 5);
    l.onAnyDamage(friendly);
    assertTrue(friendly.isCancelled(), "an enemy's blow on another enemy");
    Arrow arrow = mock(Arrow.class);
    when(arrow.getShooter()).thenReturn(a);
    EntityDamageEvent shot = damage(b, arrow, DamageCause.PROJECTILE, 5);
    l.onAnyDamage(shot);
    assertTrue(shot.isCancelled(), "or its arrow");
    for (DamageCause own : new DamageCause[] { DamageCause.FALL, DamageCause.CRAMMING }) {
      EntityDamageEvent e = damage(b, null, own, 5);
      l.onAnyDamage(e);
      assertTrue(e.isCancelled(), own + " doesn't hurt an enemy");
    }
    for (EntityDamageEvent e : List.of(damage(a, a, DamageCause.MAGIC, 5), damage(b, p, DamageCause.ENTITY_ATTACK, 5),
        damage(b, summon, DamageCause.ENTITY_ATTACK, 5), damage(b, null, DamageCause.FIRE_TICK, 5),
        damage(summon, a, DamageCause.ENTITY_ATTACK, 5))) {
      l.onAnyDamage(e);
      assertFalse(e.isCancelled(), "itself, players, their summons and fire still hurt; enemies still hurt summons");
    }
  }

  @Test
  void runEnemiesHitAndTakeHitsAsTheirFightSays() {
    Player p = player();
    LivingEntity mob = mock(LivingEntity.class);
    // the source of a hit: whoever caused it, the shooter, or the damager
    assertSame(mob, RunListener.source(damage(p, mob, DamageCause.ENTITY_ATTACK, 1)));
    Arrow arrow = mock(Arrow.class);
    when(arrow.getShooter()).thenReturn(mob);
    assertSame(mob, RunListener.source(damage(p, arrow, DamageCause.PROJECTILE, 1)));
    Arrow stray = mock(Arrow.class);
    when(stray.getShooter()).thenReturn(mock(ProjectileSource.class));
    assertSame(stray, RunListener.source(damage(p, stray, DamageCause.PROJECTILE, 1)));
    Arrow plain = mock(Arrow.class);
    assertSame(plain, RunListener.source(damage(p, plain, DamageCause.PROJECTILE, 1)));
    assertNull(RunListener.source(damage(p, null, DamageCause.FALL, 1)));
    DamageSource caused = mock(DamageSource.class);
    when(caused.getCausingEntity()).thenReturn(mob);
    assertSame(mob, RunListener.source(new EntityDamageEvent(p, DamageCause.MAGIC, caused, 1)));
    // outgoing: only on players in the dungeon world, scaled when the fight says so
    when(runs.inDungeonWorld(p)).thenReturn(true);
    when(runs.outgoingFactor(mob)).thenReturn(1.2);
    EntityDamageEvent rallied = damage(p, mob, DamageCause.ENTITY_ATTACK, 10);
    l.onEnemyHit(rallied);
    assertEquals(12, rallied.getDamage(), 1e-9);
    LivingEntity calm = mock(LivingEntity.class);
    when(runs.outgoingFactor(calm)).thenReturn(1.0);
    EntityDamageEvent normal = damage(p, calm, DamageCause.ENTITY_ATTACK, 10);
    l.onEnemyHit(normal);
    assertEquals(10, normal.getDamage());
    EntityDamageEvent fall = damage(p, null, DamageCause.FALL, 10);
    l.onEnemyHit(fall);
    assertEquals(10, fall.getDamage());
    EntityDamageEvent onMob = damage(mob, p, DamageCause.ENTITY_ATTACK, 10);
    l.onEnemyHit(onMob);
    Player outside = player();
    EntityDamageEvent elsewhere = damage(outside, mob, DamageCause.ENTITY_ATTACK, 10);
    l.onEnemyHit(elsewhere);
    assertEquals(10, elsewhere.getDamage());
    // incoming: not run mobs are left alone; immune bosses take nothing; shields soften; phases cap
    EntityDamageEvent stranger = damage(calm, p, DamageCause.ENTITY_ATTACK, 10);
    when(runs.inDungeonWorld(calm)).thenReturn(true);
    l.onEnemyStruck(stranger);
    l.onEnemyHurt(stranger);
    assertEquals(10, stranger.getDamage());
    when(runs.isRunMob(mob)).thenReturn(true);
    // outside the dungeon world nothing is looked at
    EntityDamageEvent away = damage(mob, p, DamageCause.ENTITY_ATTACK, 10);
    l.onEnemyStruck(away);
    l.onEnemyHurt(away);
    l.onEnemyHurtDone(away);
    verify(runs, never()).incomingFactor(mob);
    when(runs.inDungeonWorld(mob)).thenReturn(true);
    when(runs.incomingFactor(mob)).thenReturn(0.0);
    EntityDamageEvent immune = damage(mob, p, DamageCause.ENTITY_ATTACK, 10);
    l.onEnemyStruck(immune);
    assertTrue(immune.isCancelled());
    when(runs.incomingFactor(mob)).thenReturn(0.4);
    when(runs.damageCap(mob)).thenReturn(Double.POSITIVE_INFINITY);
    EntityDamageEvent shielded = damage(mob, p, DamageCause.ENTITY_ATTACK, 10);
    l.onEnemyStruck(shielded);
    l.onEnemyHurt(shielded);
    assertEquals(4, shielded.getDamage(), 1e-9);
    when(runs.incomingFactor(mob)).thenReturn(1.0);
    when(runs.damageCap(mob)).thenReturn(5.0);
    EntityDamageEvent capped = damage(mob, p, DamageCause.ENTITY_ATTACK, 10);
    l.onEnemyHurt(capped);
    assertEquals(5, capped.getDamage(), 1e-9);
    when(runs.damageCap(mob)).thenReturn(0.0);
    EntityDamageEvent none = damage(mob, p, DamageCause.ENTITY_ATTACK, 10);
    l.onEnemyStruck(none);
    assertTrue(none.isCancelled(), "a boss at its phase floor takes nothing (before MythicLib sees the hit)");
    none = damage(mob, p, DamageCause.ENTITY_ATTACK, 10);
    l.onEnemyHurt(none);
    assertTrue(none.isCancelled());
    // /kill always kills
    EntityDamageEvent kill = damage(mob, null, DamageCause.KILL, 1000);
    l.onEnemyStruck(kill);
    l.onEnemyHurt(kill);
    assertFalse(kill.isCancelled());
    assertEquals(1000, kill.getDamage());
    // what lands is counted; spawns in the dungeon world are reported
    EntityDamageEvent done = damage(mob, p, DamageCause.ENTITY_ATTACK, 3);
    l.onEnemyHurtDone(done);
    verify(runs).mobHurt(mob, p, 3);
    l.onEnemyHurtDone(damage(calm, p, DamageCause.ENTITY_ATTACK, 3));
    verify(runs, never()).mobHurt(eq(calm), any(), anyDouble());
    org.bukkit.event.entity.CreatureSpawnEvent spawn = mock(org.bukkit.event.entity.CreatureSpawnEvent.class);
    when(spawn.getEntity()).thenReturn(calm);
    when(runs.inDungeonWorld(calm)).thenReturn(false);
    l.onSpawn(spawn);
    verify(runs, never()).spawned(calm);
    when(spawn.getEntity()).thenReturn(mob);
    l.onSpawn(spawn);
    verify(runs).spawned(mob);
  }

  @Test
  void noFriendlyFireAndDownedPlayersAreUntouchable() {
    Player a = player();
    Player b = player();
    LivingEntity mob = mock(LivingEntity.class);
    when(runs.inDungeonWorld(any())).thenReturn(true);
    // outside the dungeon world nothing changes
    when(runs.inDungeonWorld(mob)).thenReturn(false);
    var outside = damage(mob, a, DamageCause.ENTITY_ATTACK, 5);
    l.onAnyDamage(outside);
    assertFalse(outside.isCancelled());
    when(runs.inDungeonWorld(mob)).thenReturn(true);
    // player on player: blocked
    var pvp = damage(b, a, DamageCause.ENTITY_ATTACK, 5);
    l.onAnyDamage(pvp);
    assertTrue(pvp.isCancelled());
    // a player hurting themselves is allowed; a mob hurting a player is allowed
    var self = damage(a, a, DamageCause.ENTITY_EXPLOSION, 5);
    l.onAnyDamage(self);
    assertFalse(self.isCancelled());
    var byMob = damage(a, mob, DamageCause.ENTITY_ATTACK, 5);
    l.onAnyDamage(byMob);
    assertFalse(byMob.isCancelled());
    // players hurting mobs is fine; downed players can't hurt anything
    var hit = damage(mob, a, DamageCause.ENTITY_ATTACK, 5);
    l.onAnyDamage(hit);
    assertFalse(hit.isCancelled());
    when(runs.isDowned(a)).thenReturn(true);
    var downedHit = damage(mob, a, DamageCause.ENTITY_ATTACK, 5);
    l.onAnyDamage(downedHit);
    assertTrue(downedHit.isCancelled());
    // nothing hurts a downed player; the void puts them back where they fell
    var onDowned = damage(a, mob, DamageCause.ENTITY_ATTACK, 5);
    l.onAnyDamage(onDowned);
    assertTrue(onDowned.isCancelled());
    Location spot = new Location(null, 1, 2, 3);
    when(runs.downedSpot(a)).thenReturn(spot);
    var voidHit = damage(a, null, DamageCause.VOID, 5);
    l.onAnyDamage(voidHit);
    assertTrue(voidHit.isCancelled());
    verify(a).teleport(spot);
  }

  @Test
  void lethalHitsDownAndTheVoidSendsBack() {
    Player p = player();
    when(p.getHealth()).thenReturn(4.0);
    var mobHit = mock(LivingEntity.class);
    // not in a run: nothing
    var free = damage(p, mobHit, DamageCause.ENTITY_ATTACK, 10);
    l.onLethal(free);
    assertFalse(free.isCancelled());
    l.onLethal(damage(mobHit, null, DamageCause.FALL, 10));
    RunManager.ActiveRun ar = mock(RunManager.ActiveRun.class);
    when(runs.runOf(p)).thenReturn(ar);
    var notThere = damage(p, mobHit, DamageCause.ENTITY_ATTACK, 10);
    l.onLethal(notThere);
    assertFalse(notThere.isCancelled());
    when(runs.inDungeonWorld(p)).thenReturn(true);
    var light = damage(p, mobHit, DamageCause.ENTITY_ATTACK, 1);
    l.onLethal(light);
    assertFalse(light.isCancelled());
    var lethal = damage(p, mobHit, DamageCause.ENTITY_ATTACK, 10);
    when(runs.down(p)).thenReturn(false);
    l.onLethal(lethal);
    assertFalse(lethal.isCancelled());
    when(runs.down(p)).thenReturn(true);
    l.onLethal(lethal);
    assertTrue(lethal.isCancelled());
    var unknown = damage(p, null, DamageCause.VOID, 4);
    l.onLethal(unknown);
    assertFalse(unknown.isCancelled(), "no rescue point: the void is left alone");
    Location back = new Location(null, 0, 65, 0);
    when(runs.rescuePoint(p)).thenReturn(back);
    var fall = damage(p, null, DamageCause.VOID, 4);
    l.onLethal(fall);
    assertTrue(fall.isCancelled());
    verify(p).teleport(back);
  }

  @Test
  void deathsKeepItemsAndRespawnInTheRun() {
    Player p = player();
    List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(org.bukkit.Material.STONE)));
    PlayerDeathEvent outside = new PlayerDeathEvent(p, mock(DamageSource.class), drops, 5, null);
    l.onDeath(outside);
    assertFalse(outside.getKeepInventory());
    when(runs.inDungeonWorld(p)).thenReturn(true);
    PlayerDeathEvent inside = new PlayerDeathEvent(p, mock(DamageSource.class), drops, 5, null);
    l.onDeath(inside);
    assertTrue(inside.getKeepInventory());
    assertTrue(inside.getKeepLevel());
    assertTrue(drops.isEmpty());
    assertEquals(0, inside.getDroppedExp());
    verify(runs).died(p);
    Location at = new Location(null, 1, 1, 1);
    PlayerRespawnEvent none = mock(PlayerRespawnEvent.class);
    when(none.getPlayer()).thenReturn(p);
    l.onRespawn(none);
    verify(none, never()).setRespawnLocation(any());
    when(runs.respawnPoint(p)).thenReturn(at);
    l.onRespawn(none);
    verify(none).setRespawnLocation(at);
    server.getScheduler().performTicks(2);
    verify(runs).respawned(p);
  }

  @Test
  void downedPlayersStayPutButMayLookAround() {
    Player p = player();
    World w = server.addSimpleWorld("w");
    Location from = new Location(w, 0, 64, 0, 0, 0);
    Location look = new Location(w, 0, 64, 0, 90, 10);
    PlayerMoveEvent turn = new PlayerMoveEvent(p, from, look);
    when(runs.isDowned(p)).thenReturn(true);
    l.onMove(turn);
    assertSame(look, turn.getTo());
    for (Location step : List.of(new Location(w, 1, 64, 0), new Location(w, 0, 65, 0), new Location(w, 0, 64, 1))) {
      PlayerMoveEvent move = new PlayerMoveEvent(p, from, step);
      l.onMove(move);
      assertEquals(0, move.getTo().getX());
      assertEquals(64, move.getTo().getY());
      assertEquals(0, move.getTo().getZ());
    }
    when(runs.isDowned(p)).thenReturn(false);
    PlayerMoveEvent free = new PlayerMoveEvent(p, from, new Location(w, 1, 64, 0));
    l.onMove(free);
    assertEquals(1, free.getTo().getX());
  }

  @Test
  void downedPlayersDoNotHeal() {
    Player p = player();
    var heal = new org.bukkit.event.entity.EntityRegainHealthEvent(p, 2, org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.SATIATED);
    l.onRegain(heal);
    assertFalse(heal.isCancelled());
    when(runs.isDowned(p)).thenReturn(true);
    l.onRegain(heal);
    assertTrue(heal.isCancelled());
    var mobHeal = new org.bukkit.event.entity.EntityRegainHealthEvent(mock(LivingEntity.class), 2, org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.MAGIC);
    l.onRegain(mobHeal);
    assertFalse(mobHeal.isCancelled());
  }

  @Test
  void runMobsDropNothing() {
    LivingEntity mob = mock(LivingEntity.class);
    List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(org.bukkit.Material.BONE)));
    EntityDeathEvent other = new EntityDeathEvent(mob, mock(DamageSource.class), drops, 3);
    l.onMobDeath(other);
    assertEquals(1, drops.size());
    when(runs.isRunMob(mob)).thenReturn(true);
    EntityDeathEvent run = new EntityDeathEvent(mob, mock(DamageSource.class), drops, 3);
    l.onMobDeath(run);
    assertTrue(drops.isEmpty());
    assertEquals(0, run.getDroppedExp());
    verify(runs).mobDied(mob);
  }

  @Test
  void joinsWorldChangesAndCharactersAreSwept() {
    Player p = player();
    l.onQuit(new PlayerQuitEvent(p, (net.kyori.adventure.text.Component) null,
        PlayerQuitEvent.QuitReason.DISCONNECTED));
    verify(runs).quit(p, "DISCONNECTED");
    l.onJoin(new PlayerJoinEvent(p, (net.kyori.adventure.text.Component) null));
    l.onWorldChange(new PlayerChangedWorldEvent(p, mock(World.class)));
    CharacterActivatedEvent activated = mock(CharacterActivatedEvent.class);
    when(activated.getOwner()).thenReturn(p);
    l.onCharacter(activated);
    server.getScheduler().performTicks(2);
    verify(runs, times(3)).sweep(p);
    verify(runs).giveRefunds(p);
    CharacterSelectionRequestEvent sw = new CharacterSelectionRequestEvent(p);
    l.onCharacterSwitch(sw);
    assertFalse(sw.isCancelled());
    when(runs.inRun(p)).thenReturn(true);
    l.onCharacterSwitch(sw);
    assertTrue(sw.isCancelled());
  }

  @Test
  void gatesAndExitsAreClicked() {
    Player p = player();
    Block block = mock(Block.class);
    GateStore gates = mock(GateStore.class);
    when(runs.gates()).thenReturn(gates);
    GateStore.Gate gate = new GateStore.Gate("w", 1, 2, 3, "r");
    PlayerInteractEvent offhand = new PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, null, block, null, EquipmentSlot.OFF_HAND);
    l.onInteract(offhand);
    verify(runs, never()).useGate(any(), any());
    when(gates.at(block)).thenReturn(gate);
    PlayerInteractEvent click = new PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, null, block, null, EquipmentSlot.HAND);
    l.onInteract(click);
    verify(runs).useGate(p, gate);
    assertTrue(click.useInteractedBlock() == org.bukkit.event.Event.Result.DENY);
    // not a gate: in the dungeon world a right-click may be the vault exit
    when(gates.at(block)).thenReturn(null);
    l.onInteract(new PlayerInteractEvent(p, Action.LEFT_CLICK_BLOCK, null, block, null, EquipmentSlot.HAND));
    l.onInteract(new PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, null, null, null, EquipmentSlot.HAND));
    l.onInteract(new PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, null, block, null, EquipmentSlot.HAND));
    verify(runs, never()).tryExit(p);
    when(runs.inDungeonWorld(p)).thenReturn(true);
    PlayerInteractEvent air = new PlayerInteractEvent(p, Action.RIGHT_CLICK_AIR, null, null, null, EquipmentSlot.HAND);
    l.onInteract(air);
    when(runs.tryExit(p)).thenReturn(true);
    PlayerInteractEvent exit = new PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, null, block, null, EquipmentSlot.HAND);
    l.onInteract(exit);
    verify(runs, times(2)).tryExit(p);
    assertTrue(exit.useInteractedBlock() == org.bukkit.event.Event.Result.DENY);
    // a room event's brazier or rune stone takes the click first
    when(runs.propClick(p, block)).thenReturn(true);
    PlayerInteractEvent prop = new PlayerInteractEvent(p, Action.RIGHT_CLICK_BLOCK, null, block, null, EquipmentSlot.HAND);
    l.onInteract(prop);
    assertTrue(prop.useInteractedBlock() == org.bukkit.event.Event.Result.DENY);
    verify(runs, times(2)).tryExit(p);
  }

  @Test
  void theDungeonWorldHasRules() {
    Player p = player();
    Player staff = player();
    when(runs.inDungeonWorld(p)).thenReturn(true);
    when(runs.inDungeonWorld(staff)).thenReturn(true);
    when(staff.hasPermission(RunListener.BUILD_PERMISSION)).thenReturn(true);
    Player away = player();
    for (Player who : List.of(p, staff, away)) {
      boolean blocked = who == p;
      Block b = mock(Block.class);
      BlockBreakEvent br = new BlockBreakEvent(b, who);
      l.onBreak(br);
      assertEquals(blocked, br.isCancelled());
      BlockPlaceEvent pl = new BlockPlaceEvent(b, mock(org.bukkit.block.BlockState.class), b, new ItemStack(org.bukkit.Material.STONE), who, true, EquipmentSlot.HAND);
      l.onPlace(pl);
      assertEquals(blocked, pl.isCancelled());
      PlayerBucketEmptyEvent bucket = mock(PlayerBucketEmptyEvent.class);
      when(bucket.getPlayer()).thenReturn(who);
      l.onBucket(bucket);
      verify(bucket, times(blocked ? 1 : 0)).setCancelled(true);
      PlayerTeleportEvent pearl = new PlayerTeleportEvent(who, new Location(null, 0, 0, 0), new Location(null, 1, 0, 0), PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
      l.onTeleport(pearl);
      assertEquals(blocked, pearl.isCancelled());
      PlayerTeleportEvent chorus = new PlayerTeleportEvent(who, new Location(null, 0, 0, 0), new Location(null, 1, 0, 0), PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT);
      l.onTeleport(chorus);
      assertEquals(blocked, chorus.isCancelled());
      PlayerTeleportEvent spectate = new PlayerTeleportEvent(who, new Location(null, 0, 0, 0), new Location(null, 1, 0, 0), PlayerTeleportEvent.TeleportCause.SPECTATE);
      l.onTeleport(spectate);
      assertEquals(blocked, spectate.isCancelled());
      PlayerTeleportEvent plugin = new PlayerTeleportEvent(who, new Location(null, 0, 0, 0), new Location(null, 1, 0, 0), PlayerTeleportEvent.TeleportCause.PLUGIN);
      l.onTeleport(plugin);
      assertFalse(plugin.isCancelled());
      EntityMountEvent mount = new EntityMountEvent(who, mock(Entity.class));
      l.onMount(mount);
      assertEquals(blocked, mount.isCancelled());
      InventoryView view = mock(InventoryView.class);
      Inventory ender = mock(Inventory.class);
      when(ender.getType()).thenReturn(InventoryType.ENDER_CHEST);
      when(view.getTopInventory()).thenReturn(ender);
      when(view.getPlayer()).thenReturn(who);
      InventoryOpenEvent open = new InventoryOpenEvent(view);
      l.onOpen(open);
      assertEquals(blocked, open.isCancelled());
      when(runs.blocksCommand("/home")).thenReturn(true);
      PlayerCommandPreprocessEvent home = new PlayerCommandPreprocessEvent(who, "/home");
      l.onCommand(home);
      assertEquals(blocked, home.isCancelled());
      PlayerCommandPreprocessEvent ok = new PlayerCommandPreprocessEvent(who, "/tr dungeon leave");
      l.onCommand(ok);
      assertFalse(ok.isCancelled());
    }
    // a mob mounting, a chest opening and a non-player opening are left alone
    EntityMountEvent mobMount = new EntityMountEvent(mock(LivingEntity.class), mock(Entity.class));
    l.onMount(mobMount);
    assertFalse(mobMount.isCancelled());
    InventoryView chestView = mock(InventoryView.class);
    Inventory chest = mock(Inventory.class);
    when(chest.getType()).thenReturn(InventoryType.CHEST);
    when(chestView.getTopInventory()).thenReturn(chest);
    when(chestView.getPlayer()).thenReturn(p);
    InventoryOpenEvent chestOpen = new InventoryOpenEvent(chestView);
    l.onOpen(chestOpen);
    assertFalse(chestOpen.isCancelled());
    InventoryView otherView = mock(InventoryView.class);
    Inventory ender2 = mock(Inventory.class);
    when(ender2.getType()).thenReturn(InventoryType.ENDER_CHEST);
    when(otherView.getTopInventory()).thenReturn(ender2);
    when(otherView.getPlayer()).thenReturn(mock(org.bukkit.entity.HumanEntity.class));
    InventoryOpenEvent npc = new InventoryOpenEvent(otherView);
    l.onOpen(npc);
    assertFalse(npc.isCancelled());
  }

  static ItemStack supplyItem() {
    return net.tfminecraft.trialrooms.dungeon.supply.SupplyItems.make(new net.tfminecraft.trialrooms.dungeon.supply.Supply(
        "potion", "Health Potion", new net.tfminecraft.trialrooms.dungeon.supply.Supply.Look("SLIME_BALL", 1, null), 3, 2,
        net.tfminecraft.trialrooms.dungeon.supply.Supply.Use.HEALTH, 0.4, 0, 0, 0, java.util.Set.of(), java.util.Set.of(),
        List.of()), 1);
  }

  @Test
  void nobodyInARunHealsNaturally() {
    Player p = player();
    var regen = new org.bukkit.event.entity.EntityRegainHealthEvent(p, 1,
        org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.REGEN);
    var magic = new org.bukkit.event.entity.EntityRegainHealthEvent(p, 1,
        org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.MAGIC);
    l.onRegain(regen);
    assertFalse(regen.isCancelled(), "not in a run");
    when(runs.playingHere(p)).thenReturn(true);
    l.onRegain(magic);
    assertFalse(magic.isCancelled(), "a healer's heal");
    l.onRegain(regen);
    assertTrue(regen.isCancelled());
    var fed = new org.bukkit.event.entity.EntityRegainHealthEvent(p, 1,
        org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.SATIATED);
    l.onRegain(fed);
    assertTrue(fed.isCancelled());
  }

  org.bukkit.event.player.PlayerInteractEvent click(Player p, org.bukkit.event.block.Action action, ItemStack item) {
    var e = mock(org.bukkit.event.player.PlayerInteractEvent.class);
    when(e.getPlayer()).thenReturn(p);
    when(e.getAction()).thenReturn(action);
    when(e.getItem()).thenReturn(item);
    return e;
  }

  @Test
  void suppliesAreUsedAndOutsidePotionsRefusedInARun() {
    Player p = player();
    ItemStack supply = supplyItem();
    var left = click(p, org.bukkit.event.block.Action.LEFT_CLICK_AIR, supply);
    l.onUse(left);
    verify(runs, never()).useSupply(any(), any());
    var use = click(p, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, supply);
    l.onUse(use);
    verify(use).setCancelled(true);
    verify(use).setUseItemInHand(org.bukkit.event.Event.Result.DENY);
    verify(runs).useSupply(p, supply);
    var taken = click(p, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, supply);
    when(taken.useItemInHand()).thenReturn(org.bukkit.event.Event.Result.DENY); // a gate or prop took it
    l.onUse(taken);
    verify(runs, times(1)).useSupply(any(), any());
    ItemStack potion = new ItemStack(org.bukkit.Material.POTION);
    var drink = click(p, org.bukkit.event.block.Action.RIGHT_CLICK_AIR, potion);
    when(drink.useItemInHand()).thenReturn(org.bukkit.event.Event.Result.DENY); // MythicLibRules got there first
    l.onUse(drink);
    verify(drink, never()).setCancelled(anyBoolean()); // outside a run
    when(runs.playingHere(p)).thenReturn(true);
    l.onUse(drink);
    verify(drink).setCancelled(true);
    verify(p).sendMessage("§7Only dungeon supplies work in a run.");
    var bread = click(p, org.bukkit.event.block.Action.RIGHT_CLICK_AIR, new ItemStack(org.bukkit.Material.BREAD));
    l.onUse(bread);
    verify(bread, never()).setCancelled(anyBoolean());
    var empty = click(p, org.bukkit.event.block.Action.RIGHT_CLICK_AIR, null);
    l.onUse(empty);
    verify(empty, never()).setCancelled(anyBoolean());
    // eating: golden apples refused, bread fine; MMOItems potions when told about them
    var apple = new org.bukkit.event.player.PlayerItemConsumeEvent(p, new ItemStack(org.bukkit.Material.GOLDEN_APPLE),
        org.bukkit.inventory.EquipmentSlot.HAND);
    l.onConsume(apple);
    assertTrue(apple.isCancelled());
    var loaf = new org.bukkit.event.player.PlayerItemConsumeEvent(p, new ItemStack(org.bukkit.Material.BREAD),
        org.bukkit.inventory.EquipmentSlot.HAND);
    l.onConsume(loaf);
    assertFalse(loaf.isCancelled());
    ItemStack mmo = new ItemStack(org.bukkit.Material.SLIME_BALL);
    assertSame(l, l.outsideHeals(it -> it.getType() == org.bukkit.Material.SLIME_BALL));
    assertTrue(l.outsideHeal(new ItemStack(org.bukkit.Material.SLIME_BALL)));
    assertFalse(l.outsideHeal(new ItemStack(org.bukkit.Material.APPLE)));
    var mmoUse = new org.bukkit.event.player.PlayerItemConsumeEvent(p, mmo, org.bukkit.inventory.EquipmentSlot.HAND);
    l.onConsume(mmoUse);
    assertTrue(mmoUse.isCancelled());
    var stillBread = new org.bukkit.event.player.PlayerItemConsumeEvent(p, new ItemStack(org.bukkit.Material.BREAD),
        org.bukkit.inventory.EquipmentSlot.HAND);
    l.onConsume(stillBread);
    assertFalse(stillBread.isCancelled());
    var stillApple = new org.bukkit.event.player.PlayerItemConsumeEvent(p, new ItemStack(org.bukkit.Material.GOLDEN_APPLE),
        org.bukkit.inventory.EquipmentSlot.HAND);
    l.onConsume(stillApple);
    assertTrue(stillApple.isCancelled());
    when(runs.playingHere(p)).thenReturn(false);
    var outside = new org.bukkit.event.player.PlayerItemConsumeEvent(p, new ItemStack(org.bukkit.Material.GOLDEN_APPLE),
        org.bukkit.inventory.EquipmentSlot.HAND);
    l.onConsume(outside);
    assertFalse(outside.isCancelled());
    for (var m : List.of(org.bukkit.Material.POTION, org.bukkit.Material.SPLASH_POTION, org.bukkit.Material.LINGERING_POTION,
        org.bukkit.Material.GOLDEN_APPLE, org.bukkit.Material.ENCHANTED_GOLDEN_APPLE)) {
      assertTrue(RunListener.vanillaHeal(new ItemStack(m)), m.name());
    }
    assertFalse(RunListener.vanillaHeal(null));
    assertFalse(RunListener.vanillaHeal(supply));
    assertFalse(RunListener.vanillaHeal(new ItemStack(org.bukkit.Material.APPLE)));
  }

  @Test
  void aLandedBlowReachesTheRun() {
    Player p = player();
    EntityDamageEvent zero = mock(EntityDamageEvent.class);
    when(zero.getEntity()).thenReturn(p);
    l.onPlayerHurt(zero);
    EntityDamageEvent hit = mock(EntityDamageEvent.class);
    when(hit.getEntity()).thenReturn(p);
    when(hit.getFinalDamage()).thenReturn(3.0);
    l.onPlayerHurt(hit);
    verify(runs, never()).hurt(any());
    when(runs.inDungeonWorld(p)).thenReturn(true);
    l.onPlayerHurt(hit);
    verify(runs).hurt(p);
    EntityDamageEvent hunger = mock(EntityDamageEvent.class);
    when(hunger.getEntity()).thenReturn(p);
    when(hunger.getFinalDamage()).thenReturn(1.0);
    when(hunger.getCause()).thenReturn(EntityDamageEvent.DamageCause.STARVATION);
    l.onPlayerHurt(hunger);
    verify(runs, times(1)).hurt(p); // hunger tears no bandage loose
    EntityDamageEvent mobHit = mock(EntityDamageEvent.class);
    when(mobHit.getEntity()).thenReturn(mock(LivingEntity.class));
    l.onPlayerHurt(mobHit);
    verify(runs, times(1)).hurt(any());
  }

  org.bukkit.event.entity.EntityPotionEffectEvent effect(Entity who, org.bukkit.potion.PotionEffect neu,
      org.bukkit.event.entity.EntityPotionEffectEvent.Action action) {
    var e = mock(org.bukkit.event.entity.EntityPotionEffectEvent.class);
    when(e.getEntity()).thenReturn(who);
    when(e.getNewEffect()).thenReturn(neu);
    when(e.getAction()).thenReturn(action);
    return e;
  }

  @Test
  void aWardStopsTheSlowItIsFor() {
    Player p = player();
    var slow = new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 40, 1);
    var added = org.bukkit.event.entity.EntityPotionEffectEvent.Action.ADDED;
    when(runs.playingHere(p)).thenReturn(true);
    when(runs.wardsPotion(p, "slowness")).thenReturn(true);
    var stopped = effect(p, slow, added);
    l.onPotion(stopped);
    verify(stopped).setCancelled(true);
    for (var e : List.of(effect(mock(LivingEntity.class), slow, added), effect(p, null, added),
        effect(p, slow, org.bukkit.event.entity.EntityPotionEffectEvent.Action.REMOVED))) {
      l.onPotion(e);
      verify(e, never()).setCancelled(anyBoolean());
    }
    when(runs.isDowned(p)).thenReturn(true);
    var downed = effect(p, slow, added);
    l.onPotion(downed);
    verify(downed, never()).setCancelled(anyBoolean());
    when(runs.isDowned(p)).thenReturn(false);
    when(runs.playingHere(p)).thenReturn(false);
    var away = effect(p, slow, added);
    l.onPotion(away);
    verify(away, never()).setCancelled(anyBoolean());
    when(runs.playingHere(p)).thenReturn(true);
    when(runs.wardsPotion(p, "slowness")).thenReturn(false);
    var unwarded = effect(p, slow, org.bukkit.event.entity.EntityPotionEffectEvent.Action.CHANGED);
    l.onPotion(unwarded);
    verify(unwarded, never()).setCancelled(anyBoolean());
  }
}
