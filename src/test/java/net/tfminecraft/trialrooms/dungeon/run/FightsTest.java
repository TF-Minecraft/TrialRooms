package net.tfminecraft.trialrooms.dungeon.run;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.kyori.adventure.text.Component;
import net.tfminecraft.trialrooms.dungeon.DungeonModule;
import net.tfminecraft.trialrooms.dungeon.fight.Affix;
import net.tfminecraft.trialrooms.dungeon.fight.BossDef;
import net.tfminecraft.trialrooms.dungeon.fight.FightKit;
import net.tfminecraft.trialrooms.dungeon.fight.FightTuning;
import net.tfminecraft.trialrooms.dungeon.fight.Mechanic;
import net.tfminecraft.trialrooms.dungeon.fight.PackTemplate;
import net.tfminecraft.trialrooms.dungeon.fight.Role;
import net.tfminecraft.trialrooms.dungeon.fight.RosterEntry;
import net.tfminecraft.trialrooms.dungeon.fight.Telegraph;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonGenerator;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonRecipe;
import net.tfminecraft.trialrooms.dungeon.generator.GreyboxKitTest;
import net.tfminecraft.trialrooms.dungeon.load.DungeonKit;
import net.tfminecraft.trialrooms.dungeon.load.RecipeLoader;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;
import net.tfminecraft.trialrooms.dungeon.world.LayoutPlacer;

class FightsTest {
  @TempDir Path temp;
  ServerMock server;
  World main;
  World dungeon;
  org.mockbukkit.mockbukkit.block.BlockMock block;
  JavaPlugin plugin;
  DungeonModule module;
  DungeonKit kit;
  DungeonLayout layout;
  RunGeometry geo;
  Ports.Characters chars;
  Ports.Mobs mobs;
  Ports.Fx fx;
  AtomicLong now = new AtomicLong(1_000_000);
  RunManager runs;
  Map<String, String> characterOf = new HashMap<>();
  List<Entity> spawned = new ArrayList<>();
  RunSettings settings = RunSettings.defaults();
  RunRules rules = new RunRules(1, 3, "", 1, 1, 0.5, 1, "warden");
  CompletableFuture<Location> placed;
  /** What the next spawn of a mob type returns, when set. */
  Map<String, Entity> nextOf = new HashMap<>();

  static RosterEntry e(String id, Role role, double cost) {
    return new RosterEntry(id, "mob_" + id, role, cost, 1);
  }

  static FightTuning tuning(double budget, double eliteChance) {
    FightTuning d = FightTuning.DEFAULT;
    return new FightTuning(budget, budget, 0, d.tierFactors(), d.waveSplit(), d.thirdWave(), d.waveRemain(),
        d.waveTimeoutSeconds(), d.waveTimeoutAlive(), d.waveWarningSeconds(), d.maxAlive(), d.rangedShare(),
        eliteChance, 0, d.eliteMinCost(), d.eliteHealth(), d.healthCap(), d.stallSeconds(), d.slamSeconds(),
        d.shockwaveSeconds(), d.burstSeconds(), d.gapSeconds(), d.floorSeconds(), d.slamRadius(), d.shockwaveRadius(),
        d.burstRadius(), d.slamDamage(), d.shockwaveDamage(), d.burstDamage(), d.tierDamageStep(), d.shieldReduction(),
        d.rallyFactor(), d.rallyEverySeconds(), d.rallySeconds(), d.rallyRadius(), d.phaseImmuneSeconds(),
        d.rallyCallAdds(), d.maxAdds(), d.enrageFactor(), d.enrageAddsSeconds());
  }

  static BossDef warden() {
    return new BossDef("warden", "mob_boss", "The Warden", 300, List.of(
        new BossDef.Phase(100, 2, List.of(Mechanic.SLAM), 8),
        new BossDef.Phase(60, 2, List.of(Mechanic.SHOCKWAVE, Mechanic.RALLY_CALL), 8),
        new BossDef.Phase(30, 0, List.of(), 8)), 600);
  }

  static FightKit fightKit(FightTuning t, BossDef... bosses) {
    Map<String, RosterEntry> roster = new LinkedHashMap<>();
    for (RosterEntry x : List.of(e("foot", Role.GRUNT, 1), e("arch", Role.RANGED, 2), e("halb", Role.BRUISER, 2))) {
      roster.put(x.id(), x);
    }
    Map<String, BossDef> b = new LinkedHashMap<>();
    for (BossDef d : bosses) {
      b.put(d.id(), d);
    }
    return new FightKit(roster, List.of(new PackTemplate("p", List.of(Role.BRUISER, Role.GRUNT, Role.GRUNT), 1, 0)),
        b, t);
  }

  void useKit(FightKit fights) {
    DungeonKit bundled = GreyboxKitTest.kit();
    kit = new DungeonKit(bundled.id(), bundled.catalog(), bundled.structures(), bundled.doorHeight(),
        bundled.sealBlock(), bundled.doorBlock(), Map.of(), fights);
  }

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    main = server.addSimpleWorld("main");
    dungeon = spy(server.addSimpleWorld(DungeonModule.WORLD_NAME));
    block = mock(org.mockbukkit.mockbukkit.block.BlockMock.class);
    doReturn(block).when(dungeon).getBlockAt(anyInt(), anyInt(), anyInt());
    Server srv = mock(Server.class);
    when(srv.getScheduler()).thenReturn(server.getScheduler());
    when(srv.createBlockData(anyString())).thenReturn(mock(BlockData.class));
    plugin = mock(JavaPlugin.class);
    when(plugin.getServer()).thenReturn(srv);
    when(plugin.getName()).thenReturn("TrialRooms");
    when(plugin.namespace()).thenReturn("trialrooms");
    when(plugin.isEnabled()).thenReturn(true);
    when(plugin.getLogger()).thenReturn(Logger.getLogger("fights-test"));
    useKit(fightKit(tuning(6, 0), warden()));
    DungeonRecipe recipe = new DungeonRecipe("delve", 1, 1, Map.of(RoomType.COMBAT, 1), true, true, 0, 0, 1, 1,
        null, 20, 20000);
    layout = DungeonGenerator.generate(kit.catalog(), recipe, 4);
    geo = new RunGeometry(layout, 32, 4, 0, 64, 0);
    module = mock(DungeonModule.class);
    when(module.world()).thenReturn(dungeon);
    when(module.settings()).thenAnswer(i -> settings);
    when(module.recipes()).thenAnswer(i -> Map.of("delve", new RecipeLoader.Entry(recipe, "greybox", rules)));
    when(module.kits()).thenAnswer(i -> Map.of("greybox", kit));
    when(module.generate(eq("delve"), anyLong())).thenAnswer(i -> layout);
    LayoutPlacer placer = mock(LayoutPlacer.class);
    placed = CompletableFuture.completedFuture(at(geo.entry(0)));
    when(placer.place()).thenAnswer(i -> placed);
    when(module.placer(any())).thenReturn(placer);
    chars = mock(Ports.Characters.class);
    when(chars.activeCharacterId(any())).thenAnswer(i -> characterOf.get(((PlayerMock) i.getArgument(0)).getName()));
    when(chars.partyMembers(any())).thenAnswer(i -> List.of(((PlayerMock) i.getArgument(0)).getUniqueId()));
    Ports.Keys keys = mock(Ports.Keys.class);
    mobs = mock(Ports.Mobs.class);
    when(mobs.spawn(anyString(), any(), anyInt())).thenAnswer(i -> {
      Entity next = nextOf.remove((String) i.getArgument(0));
      return next != null ? next : mob(i.getArgument(1), "Skeleton");
    });
    fx = mock(Ports.Fx.class);
    runs = new RunManager(plugin, module, chars, keys, mobs, fx, new RunFiles(temp, Logger.getLogger("t")),
        new GateStore(temp.resolve("gates.yml").toFile(), Logger.getLogger("t")), now::get);
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  Location at(double[] p) {
    return new Location(dungeon, p[0], p[1], p[2]);
  }

  /** A living mob whose health, name and position behave. */
  <T extends LivingEntity> T living(Class<T> type, Location at, String name) {
    T e = mock(type);
    UUID id = UUID.randomUUID();
    when(e.getUniqueId()).thenReturn(id);
    when(e.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
    when(e.isValid()).thenReturn(true);
    Location[] where = {at.clone()};
    when(e.getLocation()).thenAnswer(i -> where[0].clone());
    when(e.teleport(any(Location.class))).thenAnswer(i -> {
      where[0] = ((Location) i.getArgument(0)).clone();
      return true;
    });
    when(e.getName()).thenReturn(name);
    double[] health = {30};
    when(e.getHealth()).thenAnswer(i -> health[0]);
    doAnswer(i -> {
      health[0] = i.getArgument(0);
      return null;
    }).when(e).setHealth(anyDouble());
    AttributeInstance max = mock(AttributeInstance.class);
    when(max.getValue()).thenReturn(30.0);
    when(e.getAttribute(Attribute.MAX_HEALTH)).thenReturn(max);
    spawned.add(e);
    return e;
  }

  Entity mob(Location at, String name) {
    return living(LivingEntity.class, at, name);
  }

  PlayerMock player(String name) {
    PlayerMock p = server.addPlayer(name);
    p.teleport(new Location(main, 10, 70, 10));
    characterOf.put(name, "char-" + name);
    return p;
  }

  int room(RoomType type) {
    for (int i = 0; i < layout.rooms().size(); i++) {
      if (layout.rooms().get(i).node().type() == type) {
        return i;
      }
    }
    throw new IllegalStateException(type.name());
  }

  void ticks(int n) {
    for (int i = 0; i < n; i++) {
      runs.tick();
    }
  }

  RunManager.ActiveRun only() {
    assertEquals(1, runs.runs().size());
    return runs.runs().getFirst();
  }

  void kill(Entity e) {
    when(e.isValid()).thenReturn(false);
    when(e.isDead()).thenReturn(true);
  }

  List<String> messages(PlayerMock p) {
    List<String> out = new ArrayList<>();
    String m;
    while ((m = p.nextMessage()) != null) {
      out.add(m);
    }
    return out;
  }

  boolean said(PlayerMock p, String part) {
    return messages(p).stream().anyMatch(m -> m.contains(part));
  }

  /** Starts a solo run and walks into a room; a combat room seals at once for one player. */
  RunManager.ActiveRun enter(PlayerMock a, int room) {
    runs.startRun(a, "delve", 1);
    RunManager.ActiveRun ar = only();
    a.teleport(at(middle(room)));
    ticks(1);
    return ar;
  }

  @Test
  void wavesComeWithWarningsAndTheRoomClears() {
    useKit(fightKit(tuning(12, 0), warden()));
    PlayerMock a = player("Ann");
    int fight = room(RoomType.COMBAT);
    RunManager.ActiveRun ar = enter(a, fight);
    Fights.Encounter enc = ar.encounters.get(fight);
    assertNotNull(enc);
    assertEquals(2, enc.plan.waves().size());
    assertTrue(said(a, "Wave 1 of 2"));
    int first = enc.alive.size();
    assertEquals(enc.plan.waves().getFirst().size(), first);
    assertFalse(enc.done(), "more waves to come");
    // a quarter left: the next wave is announced with a direction, then comes
    List<Entity> wave1 = new ArrayList<>(enc.alive);
    for (int i = 0; i < wave1.size() - 1; i++) {
      kill(wave1.get(i));
    }
    ticks(1);
    assertTrue(enc.nextWaveAtMs > 0);
    List<String> warned = messages(a);
    assertTrue(warned.stream().anyMatch(m -> m.contains("More enemies come in 5 seconds") && m.contains("block")),
        warned.toString());
    ticks(1);
    assertEquals(0, enc.wave, "still waiting for the warning to run out");
    now.addAndGet(5000);
    ticks(1);
    assertEquals(1, enc.wave);
    assertTrue(said(a, "Wave 2 of 2"));
    // the last few glow and are pointed out
    List<Entity> wave2 = new ArrayList<>(enc.alive);
    kill(wave1.getLast());
    for (int i = 0; i < wave2.size() - 1; i++) {
      kill(wave2.get(i));
    }
    ticks(1);
    verify((LivingEntity) wave2.getLast()).addPotionEffect(any(PotionEffect.class));
    assertTrue(said(a, "One enemy left."));
    ticks(1);
    now.addAndGet(3000);
    ticks(1);
    // a stray enemy is sent back into the room
    wave2.getLast().teleport(at(middle(0)));
    ticks(1);
    assertEquals(fight, geo.roomAt(wave2.getLast().getLocation().getX(), wave2.getLast().getLocation().getZ()));
    kill(wave2.getLast());
    ticks(1);
    assertEquals(DungeonRun.Room.CLEARED, ar.run.room(fight));
    assertNull(ar.encounters.get(fight));
    assertTrue(runs.fights().step(ar, fight), "no fight left in the room");
  }

  @Test
  void slowWavesAndIdleEnemiesMoveOn() {
    useKit(fightKit(tuning(12, 0), warden()));
    PlayerMock a = player("Ann");
    int fight = room(RoomType.COMBAT);
    RunManager.ActiveRun ar = enter(a, fight);
    Fights.Encounter enc = ar.encounters.get(fight);
    List<Entity> wave1 = new ArrayList<>(enc.alive);
    // half the wave gone and 75 s passed: the next wave is called; nobody hit anything for 20 s: they come to you
    // 75 s in with most of the wave standing: no early call
    long started = enc.waveStartMs;
    enc.waveStartMs = now.get() - 75_000;
    ticks(1);
    assertEquals(-1, enc.nextWaveAtMs);
    enc.waveStartMs = started;
    enc.waveSize = wave1.size() * 2; // as if half the wave were gone already
    now.addAndGet(20_000);
    a.teleport(at(middle(fight)).add(8, 0, 8));
    ticks(1);
    assertEquals(now.get(), enc.lastHitMs);
    assertEquals(-1, enc.nextWaveAtMs);
    runs.mobHurt(wave1.getLast(), a, 3);
    now.addAndGet(55_000);
    ticks(1);
    assertTrue(enc.nextWaveAtMs > 0);
    // no players in the room: idle enemies stay where they are
    Location before = wave1.getLast().getLocation();
    enc.nextWaveAtMs = -1;
    enc.wave = enc.plan.waves().size() - 1;
    a.teleport(at(middle(0)));
    ar.run.member(a.getUniqueId()).room(0);
    now.addAndGet(25_000);
    runs.fights().step(ar, fight);
    assertEquals(before, wave1.getLast().getLocation());
  }

  @Test
  void elitesShieldRallyAndBurst() {
    useKit(fightKit(tuning(6, 1), warden()));
    PlayerMock a = player("Ann");
    int fight = room(RoomType.COMBAT);
    RunManager.ActiveRun ar = enter(a, fight);
    Fights.Encounter enc = ar.encounters.get(fight);
    assertEquals(1, enc.elites.size());
    List<String> seen = messages(a);
    assertTrue(seen.stream().anyMatch(m -> m.contains("leads this pack")), seen.toString());
    Entity elite = ar.encounters.get(fight).alive.stream().filter(m -> enc.elites.containsKey(m.getUniqueId()))
        .findFirst().orElseThrow();
    verify(elite, atLeastOnce()).customName(any(Component.class));
    // MythicMobs puts its own name back: the fight's name is set again while the mob settles
    Component given = enc.names.get(elite);
    when(elite.customName()).thenReturn(given);
    clearInvocations(elite);
    ticks(1);
    verify(elite, never()).customName(any(Component.class));
    List<Entity> others = new ArrayList<>(enc.alive);
    others.remove(elite);
    // shielded: takes less while its wave stands; the shield falls with the last of them
    enc.elites.put(elite.getUniqueId(), Affix.SHIELDED);
    assertEquals(0.4, runs.incomingFactor(elite), 1e-9);
    assertEquals(1, runs.incomingFactor(others.getFirst()));
    ticks(1);
    verify(fx, atLeastOnce()).burst(any());
    others.forEach(this::kill);
    ticks(1);
    assertTrue(said(a, "shield falls"));
    assertEquals(1, runs.incomingFactor(elite));
    ticks(1);
    // rallying: nearby allies of its wave hit harder for a while
    Entity mate = mob(elite.getLocation(), "Mate");
    Entity far = mob(elite.getLocation().add(30, 0, 0), "Far");
    for (Entity m : List.of(mate, far)) {
      runs.register(ar, m, new double[] {0, 64, 0});
      enc.alive.add(m);
      enc.waveOf.put(m.getUniqueId(), 0);
    }
    Entity other = mob(elite.getLocation(), "Other");
    runs.register(ar, other, new double[] {0, 64, 0});
    enc.alive.add(other);
    enc.waveOf.put(other.getUniqueId(), 5);
    enc.elites.put(elite.getUniqueId(), Affix.RALLYING);
    ticks(1);
    assertEquals(1.2, runs.outgoingFactor(mate), 1e-9);
    assertEquals(1, runs.outgoingFactor(far));
    assertEquals(1, runs.outgoingFactor(other));
    ticks(1);
    now.addAndGet(6000);
    assertEquals(1, runs.outgoingFactor(mate));
    // volatile: its remains burst a few seconds after it dies; standing in it hurts, stepping out is confirmed
    enc.elites.put(elite.getUniqueId(), Affix.VOLATILE);
    kill(elite);
    runs.mobDied(elite);
    assertEquals(1, enc.telegraphs.size());
    assertTrue(said(a, "remains crackle"));
    a.teleport(elite.getLocation());
    server.getScheduler().performTicks(2);
    verify(fx, atLeastOnce()).ring(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    a.teleport(elite.getLocation().add(10, 0, 0));
    server.getScheduler().performTicks(2);
    a.teleport(elite.getLocation());
    now.addAndGet(3000);
    server.getScheduler().performTicks(2);
    verify(fx).hurt(eq(a), anyDouble(), isNull());
    assertTrue(enc.telegraphs.isEmpty());
    server.getScheduler().performTicks(4);
    // two bursts at once share the drawing task
    Entity v1 = mob(elite.getLocation(), "V1");
    Entity v2 = mob(elite.getLocation(), "V2");
    enc.elites.put(v1.getUniqueId(), Affix.VOLATILE);
    enc.elites.put(v2.getUniqueId(), Affix.VOLATILE);
    runs.fights().died(ar, v1);
    runs.fights().died(ar, v2);
    assertEquals(2, enc.telegraphs.size());
    now.addAndGet(3000);
    server.getScheduler().performTicks(2);
    // the room waits for the burst before it opens, then clears
    List.of(mate, far, other).forEach(this::kill);
    ticks(1);
    assertEquals(DungeonRun.Room.CLEARED, ar.run.room(fight));
  }

  @Test
  void aBossFightsInPhasesWithTelegraphs() {
    PlayerMock a = player("Ann");
    int boss = room(RoomType.BOSS);
    Mob mob = living(Mob.class, at(middle(boss)), "Warden");
    AttributeInstance speed = mock(AttributeInstance.class);
    when(mob.getAttribute(Attribute.MOVEMENT_SPEED)).thenReturn(speed);
    when(mob.getTarget()).thenReturn(a);
    nextOf.put("mob_boss", mob);
    RunManager.ActiveRun ar = enter(a, boss);
    ticks(settings.bossCountdownSeconds() * 2 + 2);
    Fights.Encounter enc = ar.encounters.get(boss);
    assertNotNull(enc.boss);
    assertTrue(said(a, "The Warden awakens."));
    assertFalse(enc.adds.isEmpty(), "the first phase brings adds");
    verify(mob.getAttribute(Attribute.MAX_HEALTH), atLeastOnce()).setBaseValue(300);
    // while it wakes it can't be hurt; its own plugin resetting its base health is undone
    assertEquals(0, runs.incomingFactor(mob));
    when(mob.getAttribute(Attribute.MAX_HEALTH).getBaseValue()).thenReturn(300.0);
    clearInvocations(mob.getAttribute(Attribute.MAX_HEALTH));
    ticks(1);
    verify(mob.getAttribute(Attribute.MAX_HEALTH), never()).setBaseValue(anyDouble());
    // damage while an add settles is kept when its health is set again; damage past its health isn't undone
    LivingEntity fresh = (LivingEntity) enc.adds.iterator().next();
    runs.mobHurt(fresh, null, 12);
    runs.mobHurt(fresh, a, 3);
    ticks(1);
    assertEquals(30 - 15, fresh.getHealth(), 1e-9);
    runs.mobHurt(fresh, null, 100);
    fresh.setHealth(1);
    ticks(1);
    assertEquals(1, fresh.getHealth(), 1e-9);
    enc.taken.clear();
    fresh.setHealth(30);
    runs.fights().hurt(ar, mock(Entity.class), 5);
    now.addAndGet(Fights.SETTLE_MS + 1);
    ticks(1);
    assertTrue(enc.settling.isEmpty(), "settled once the time is up");
    // a slam marks the boss's target after the first interval
    now.addAndGet(8000);
    ticks(1);
    assertEquals(1, enc.telegraphs.size());
    assertEquals(Telegraph.Kind.SLAM, enc.telegraphs.getFirst().t.kind());
    assertTrue(said(a, "slams where you stand"));
    ticks(1);
    assertEquals(1, enc.telegraphs.size(), "no second mechanic while one shows");
    assertEquals(1, runs.incomingFactor(mob));
    runs.fights().hit(ar, enc.adds.iterator().next());
    server.getScheduler().performTicks(2);
    now.addAndGet(3000);
    server.getScheduler().performTicks(2);
    verify(fx).hurt(eq(a), anyDouble(), eq(mob));
    assertEquals(1, enc.boss.mechanicHits());
    // the next phase: held at its start and briefly immune; a hit can't take it past the phase after
    mob.setHealth(10);
    ticks(1);
    assertEquals(1, enc.boss.phase());
    assertEquals(18, mob.getHealth(), 1e-9);
    assertEquals(0, runs.incomingFactor(mob));
    assertEquals(18 - 9, runs.damageCap(mob), 1e-9);
    assertTrue(said(a, "fights on harder"));
    // shockwave: rooted, and its own hits do nothing while it charges
    now.addAndGet(8000);
    ticks(1);
    assertEquals(Telegraph.Kind.SHOCKWAVE, enc.telegraphs.getFirst().t.kind());
    verify(speed).addTransientModifier(any());
    assertEquals(0, runs.outgoingFactor(mob));
    a.teleport(mob.getLocation().add(20, 0, 0));
    now.addAndGet(4000);
    server.getScheduler().performTicks(2);
    verify(speed, atLeastOnce()).removeModifier(any(net.kyori.adventure.key.Key.class));
    assertEquals(1, runs.outgoingFactor(mob));
    // a call for aid (skipped while many adds stand), then enrage: harder hits and aid now and then
    enc.adds.forEach(this::kill);
    ticks(1);
    assertTrue(enc.adds.isEmpty());
    now.addAndGet(10_000);
    ticks(1);
    assertFalse(enc.adds.isEmpty());
    assertTrue(said(a, "calls for aid"));
    enc.adds.forEach(this::kill);
    now.addAndGet(600_000);
    ticks(1);
    assertTrue(enc.boss.enraged());
    enc.telegraphs.clear();
    assertEquals(1.25, runs.outgoingFactor(mob), 1e-9);
    now.addAndGet(30_000);
    ticks(1);
    assertFalse(enc.adds.isEmpty());
    // idle for long: the boss and its adds are brought to the players
    long idleHit = enc.lastHitMs;
    enc.telegraphs.clear();
    now.addAndGet(25_000);
    ticks(1);
    assertNotEquals(idleHit, enc.lastHitMs);
    // a warning still showing when the boss falls lands on nobody
    enc.telegraphs.clear();
    enc.telegraphs.add(new Fights.Active(new Telegraph(Telegraph.Kind.SLAM, a.getLocation().getX(), 65,
        a.getLocation().getZ(), 4, now.get(), now.get())));
    when(mob.isDead()).thenReturn(true);
    clearInvocations(fx);
    runs.fights().telegraphTick();
    assertTrue(enc.telegraphs.isEmpty());
    verify(fx, never()).hurt(any(), anyDouble(), any());
    when(mob.isDead()).thenReturn(false);
    when(mob.isValid()).thenReturn(false);
    enc.telegraphs.add(new Fights.Active(new Telegraph(Telegraph.Kind.SLAM, a.getLocation().getX(), 65,
        a.getLocation().getZ(), 4, now.get(), now.get())));
    runs.fights().telegraphTick();
    assertTrue(enc.telegraphs.isEmpty());
    verify(fx, never()).hurt(any(), anyDouble(), any());
    when(mob.isValid()).thenReturn(true);
    // the boss falls: its adds go with it and the way opens
    kill(mob);
    ticks(1);
    assertTrue(ar.run.bossDefeated());
    for (Entity add : spawned) {
      if (add != mob) {
        verify(mobs, atLeastOnce()).remove(any());
      }
    }
  }

  @Test
  void slamsPickFairTargets() {
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    int boss = room(RoomType.BOSS);
    LivingEntity mob = living(LivingEntity.class, at(middle(boss)), "Warden");
    nextOf.put("mob_boss", mob);
    runs.startRun(a, "delve", 1);
    RunManager.ActiveRun ar = only();
    a.teleport(at(middle(boss)));
    b.teleport(at(middle(boss)));
    ticks(settings.bossCountdownSeconds() * 2 + 2);
    Fights.Encounter enc = ar.encounters.get(boss);
    // not the same player twice running
    enc.lastSlam = a.getUniqueId();
    now.addAndGet(8000);
    ticks(1);
    assertEquals(b.getUniqueId(), enc.lastSlam);
    enc.telegraphs.clear();
    enc.boss.resolved(now.get());
    // sneaking with nobody down nearby is fine to slam
    a.setSneaking(true);
    now.addAndGet(10_000);
    ticks(1);
    assertFalse(enc.telegraphs.isEmpty());
    enc.telegraphs.clear();
    enc.boss.resolved(now.get());
    a.setSneaking(false);
    // someone reviving a downed friend is left alone; with nobody to pick, the slam is skipped
    runs.down(b);
    a.setSneaking(true);
    now.addAndGet(10_000);
    ticks(1);
    assertTrue(enc.telegraphs.isEmpty());
    a.setSneaking(false);
    runs.down(a);
    now.addAndGet(10_000);
    ticks(1);
    assertTrue(enc.telegraphs.isEmpty());
    // the boss falls before it ever enrages
    kill(mob);
    ticks(1);
    assertTrue(ar.run.bossDefeated());
  }

  @Test
  void theBossesTargetMayNotBeFairGame() {
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    int boss = room(RoomType.BOSS);
    Mob mob = living(Mob.class, at(middle(boss)), "Warden");
    when(mob.getTarget()).thenReturn(b);
    nextOf.put("mob_boss", mob);
    runs.startRun(a, "delve", 1);
    RunManager.ActiveRun ar = only();
    a.teleport(at(middle(boss)));
    b.teleport(at(middle(boss)));
    ticks(settings.bossCountdownSeconds() * 2 + 2);
    Fights.Encounter enc = ar.encounters.get(boss);
    runs.down(b);
    a.teleport(at(middle(boss)).add(6, 0, 0));
    a.setSneaking(true);
    now.addAndGet(8000);
    ticks(1);
    assertEquals(a.getUniqueId(), enc.lastSlam, "Bo is down, so not Bo; Ann sneaks too far from Bo to be reviving");
    // names: a mob's own name, else its type name
    Entity named = mock(Entity.class);
    when(named.getName()).thenReturn("Skeleton");
    assertEquals("Skeleton", Fights.baseName(named));
    when(named.customName()).thenReturn(Component.text(" "));
    assertEquals("Skeleton", Fights.baseName(named));
    when(named.customName()).thenReturn(Component.text("Bones"));
    assertEquals("Bones", Fights.baseName(named));
  }

  @Test
  void bossesThatCantSpawnBecomeAStrongPack() {
    PlayerMock a = player("Ann");
    int boss = room(RoomType.BOSS);
    // a boss mob that is not alive is taken back; an unknown one gives nothing; both leave a pack to fight
    Entity plain = mock(Entity.class);
    when(plain.getUniqueId()).thenReturn(UUID.randomUUID());
    when(plain.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
    nextOf.put("mob_boss", plain);
    RunManager.ActiveRun ar = enter(a, boss);
    ticks(settings.bossCountdownSeconds() * 2 + 2);
    Fights.Encounter enc = ar.encounters.get(boss);
    assertNull(enc.boss);
    assertFalse(enc.alive.isEmpty());
    verify(mobs).remove(plain);
    runs.end(ar, false, null);
    doReturn(null).when(mobs).spawn(eq("mob_boss"), any(), anyInt());
    RunManager.ActiveRun ar2 = enter(a, boss);
    ticks(settings.bossCountdownSeconds() * 2 + 2);
    assertNull(ar2.encounters.get(boss).boss);
    runs.end(ar2, false, null);
    // a kit without bosses fights a pack in the boss room too
    useKit(fightKit(tuning(6, 0)));
    RunManager.ActiveRun ar3 = enter(a, boss);
    ticks(settings.bossCountdownSeconds() * 2 + 2);
    assertFalse(ar3.encounters.get(boss).alive.isEmpty());
  }

  @Test
  void strayaSpawnsDamageAndCleanup() {
    PlayerMock a = player("Ann");
    int fight = room(RoomType.COMBAT);
    RunManager.ActiveRun ar = enter(a, fight);
    Fights.Encounter enc = ar.encounters.get(fight);
    // summons inside a fight belong to it; elsewhere in the run to the run; outside any run to nobody
    Entity summon = mob(at(middle(fight)), "Summon");
    runs.spawned(summon);
    assertTrue(enc.extras.contains(summon));
    verify(summon.getPersistentDataContainer()).set(any(), any(), any());
    verify((LivingEntity) enc.alive.iterator().next()).setRemoveWhenFarAway(false);
    Entity loose = mob(at(middle(0)), "Loose");
    runs.spawned(loose);
    assertTrue(ar.extras.contains(loose));
    Entity away = mob(new Location(dungeon, 900_000, 64, 900_000), "Away");
    runs.spawned(away);
    runs.spawned(enc.alive.iterator().next());
    assertEquals(1, enc.extras.size());
    // damage dealt is counted per player; hits from nobody or on strangers are not
    Entity target = enc.alive.iterator().next();
    runs.mobHurt(target, a, 4.4);
    runs.mobHurt(target, null, 9);
    runs.mobHurt(summon, a, 9);
    assertEquals(4.4, ar.damage.get(a.getUniqueId()), 1e-9);
    assertTrue(RunManager.json(ar, "X").contains("\"damage\":4"));
    // strangers to the run hit and take hits as usual
    assertEquals(1, runs.outgoingFactor(summon));
    assertEquals(1, runs.incomingFactor(summon));
    assertEquals(Double.POSITIVE_INFINITY, runs.damageCap(summon));
    assertEquals(Double.POSITIVE_INFINITY, runs.damageCap(target));
    runs.mobDied(summon);
    // clearing takes the summons too; the run's end takes the rest
    enc.alive.forEach(this::kill);
    ticks(1);
    verify(mobs).remove(summon);
    runs.end(ar, false, null);
    verify(mobs).remove(loose);
  }

  @Test
  void bossBarsLeaveWithPlayersAndRunsEnd() {
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    int boss = room(RoomType.BOSS);
    LivingEntity mob = living(LivingEntity.class, at(middle(boss)), "Warden");
    when(mob.getAttribute(Attribute.MAX_HEALTH)).thenReturn(null);
    nextOf.put("mob_boss", mob);
    runs.startRun(a, "delve", 1);
    RunManager.ActiveRun ar = only();
    a.teleport(at(middle(boss)));
    b.teleport(at(middle(boss)));
    ticks(settings.bossCountdownSeconds() * 2 + 2);
    Fights.Encounter enc = ar.encounters.get(boss);
    assertNotNull(enc.bar);
    // a boss without a max health attribute still fights; its cap uses its health
    assertEquals(30 - 30 * 0.6, runs.damageCap(mob), 1e-9);
    ticks(1);
    // a shockwave on a boss that can't be rooted
    enc.boss.crossed(15, 30, now.get());
    now.addAndGet(20_000);
    ticks(1);
    runs.leave(b);
    b.disconnect();
    runs.end(ar, false, null);
    verify(mobs).remove(mob);
  }

  @Test
  void healthScalesOnceAndRoomsWithoutMarkersStillFight() {
    PlayerMock a = player("Ann");
    runs.startRun(a, "delve", 1);
    RunManager.ActiveRun ar = only();
    // a mob that isn't alive, and one without max health, are left as they are
    Fights f = runs.fights();
    f.scale(mock(Entity.class), 2);
    LivingEntity bare = mock(LivingEntity.class);
    f.scale(bare, 2);
    verify(bare, never()).setHealth(anyDouble());
    LivingEntity normal = (LivingEntity) mob(at(middle(0)), "N");
    f.scale(normal, 1);
    verify(normal.getAttribute(Attribute.MAX_HEALTH), never()).addTransientModifier(any());
    f.scale(normal, 2);
    verify(normal.getAttribute(Attribute.MAX_HEALTH)).addTransientModifier(any());
    when(normal.isValid()).thenReturn(false);
    server.getScheduler().performTicks(1);
    // a room without spawn markers: enemies come at its centre
    int corridor = room(RoomType.REST);
    useKit(fightKit(tuning(12, 0), warden()));
    RunManager.ActiveRun fresh = new RunManager.ActiveRun(ar.run, ar.geo, ar.placer, kit, ar.rules, ar.leader,
        ar.keyPath, ar.arrival);
    ar.run.member(a.getUniqueId()).room(corridor);
    // an enemy that isn't alive still settles without a health to fill
    Entity statue = mock(Entity.class);
    when(statue.getUniqueId()).thenReturn(UUID.randomUUID());
    when(statue.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
    when(statue.isValid()).thenReturn(true);
    when(statue.getLocation()).thenReturn(at(middle(corridor)));
    nextOf.put("mob_halb", statue);
    f.start(fresh, corridor);
    f.step(fresh, corridor);
    Fights.Encounter enc = fresh.encounters.get(corridor);
    assertFalse(enc.alive.isEmpty());
    // a second enemy on a marker stands beside it when there is floor and room
    when(block.isPassable()).thenReturn(true, true, false);
    int fight = room(RoomType.COMBAT);
    useKit(fightKit(tuning(30, 0), warden()));
    RunManager.ActiveRun wide = new RunManager.ActiveRun(ar.run, ar.geo, ar.placer, kit, ar.rules, ar.leader,
        ar.keyPath, ar.arrival);
    ar.run.member(a.getUniqueId()).room(fight);
    f.start(wide, fight);
    // the corridor fight's next wave comes from its centre
    List<Entity> first = new ArrayList<>(enc.alive);
    first.forEach(this::kill);
    f.step(fresh, corridor);
    assertTrue(enc.nextWaveAtMs > 0);
    f.left(fresh, a);
  }

  @Test
  void edgesOfWaveFights() {
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    int fight = room(RoomType.COMBAT);
    // an enemy that won't spawn is skipped; names come from the mob's own name when it has one
    doReturn(null).when(mobs).spawn(eq("mob_foot"), any(), anyInt());
    LivingEntity blank = living(LivingEntity.class, at(middle(fight)), "Archer");
    when(blank.customName()).thenReturn(Component.text(""));
    nextOf.put("mob_arch", blank);
    LivingEntity captain = living(LivingEntity.class, at(middle(fight)), "Halberdier");
    when(captain.customName()).thenReturn(Component.text("Captain"));
    nextOf.put("mob_halb", captain);
    useKit(fightKit(tuning(6, 1), warden()));
    runs.startRun(a, "delve", 1);
    RunManager.ActiveRun ar = only();
    a.teleport(at(middle(fight)));
    b.teleport(at(middle(0)));
    ticks(settings.countdownSeconds() * 2 + 2);
    Fights.Encounter enc = ar.encounters.get(fight);
    assertNotNull(enc);
    assertFalse(spawned.stream().anyMatch(m -> "Skeleton".equals(m.getName()) && false));
    // Bo is held in the fight; tell only those in the room
    runs.tellRoom(ar, 0, "nobody here");
    // a mob found dead but still valid is dropped; an enemy without a body to glow, two left, the nearest named
    List<Entity> all = new ArrayList<>(enc.alive);
    Entity plain = mock(Entity.class);
    when(plain.getUniqueId()).thenReturn(UUID.randomUUID());
    when(plain.isValid()).thenReturn(true);
    when(plain.getLocation()).thenReturn(at(middle(fight)).add(5, 0, 0));
    enc.alive.add(plain);
    enc.waveOf.put(plain.getUniqueId(), 0);
    for (int i = 0; i < all.size() - 1; i++) {
      when(all.get(i).isDead()).thenReturn(true);
    }
    enc.wave = enc.plan.waves().size() - 1;
    enc.nextWaveAtMs = -1;
    enc.lastFew = false;
    enc.nextPingMs = 0;
    Entity ghost = mock(Entity.class);
    when(ghost.getUniqueId()).thenReturn(UUID.randomUUID());
    when(ghost.isValid()).thenReturn(true);
    when(ghost.isDead()).thenReturn(true);
    enc.extras.add(ghost);
    messages(a);
    ticks(1);
    assertTrue(said(a, "Two enemies left."));
    now.addAndGet(3000);
    a.teleport(at(middle(fight)).add(6, 0, 0));
    ticks(1);
    // a shielded elite in another wave than the rest holds no shield
    enc.elites.put(all.getLast().getUniqueId(), Affix.SHIELDED);
    enc.waveOf.put(all.getLast().getUniqueId(), 3);
    assertEquals(1, runs.incomingFactor(all.getLast()));
    // hits on adds, the boss or strangers; deaths of normal enemies
    runs.fights().hit(ar, plain);
    runs.fights().hit(ar, mock(Entity.class));
    runs.fights().died(ar, plain);
    // stalls wait for a warning to land
    enc.telegraphs.add(new Fights.Active(new Telegraph(Telegraph.Kind.BURST, 0, 64, 0, 1, now.get(), now.get() + 99_000)));
    long hit = enc.lastHitMs;
    now.addAndGet(30_000);
    runs.fights().step(ar, fight);
    assertEquals(hit, enc.lastHitMs);
    // every enemy down, but a burst still to land: the room waits
    when(plain.isValid()).thenReturn(false);
    when(all.getLast().isValid()).thenReturn(false);
    assertFalse(runs.fights().step(ar, fight));
    enc.telegraphs.clear();
    ticks(1);
    assertEquals(DungeonRun.Room.CLEARED, ar.run.room(fight));
    // with nothing on the telegraph task, a tick does nothing
    runs.fights().telegraphTick();
  }

  @Test
  void aRosterOfElitesOnlyLeavesARoomEmpty() {
    Map<String, RosterEntry> roster = new LinkedHashMap<>();
    roster.put("boss", e("boss", Role.ELITE, 99));
    useKit(new FightKit(roster, List.of(), Map.of(), tuning(6, 0)));
    PlayerMock a = player("Ann");
    int fight = room(RoomType.COMBAT);
    RunManager.ActiveRun ar = enter(a, fight);
    ticks(1);
    assertEquals(DungeonRun.Room.CLEARED, ar.run.room(fight));
  }

  @Test
  void edgesOfBossFights() {
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    int boss = room(RoomType.BOSS);
    BossDef calm = new BossDef("warden", "mob_boss", "The Warden", 300, List.of(
        new BossDef.Phase(100, 0, List.of(Mechanic.SLAM), 8), new BossDef.Phase(50, 0, List.of(Mechanic.SHOCKWAVE), 8)),
        60);
    useKit(fightKit(tuning(6, 0), calm));
    Mob mob = living(Mob.class, at(middle(boss)), "Warden");
    when(mob.getTarget()).thenReturn(mock(LivingEntity.class));
    nextOf.put("mob_boss", mob);
    runs.startRun(a, "delve", 1);
    RunManager.ActiveRun ar = only();
    a.teleport(at(middle(boss)));
    b.teleport(at(middle(boss)));
    ticks(settings.bossCountdownSeconds() * 2 + 2);
    Fights.Encounter enc = ar.encounters.get(boss);
    assertTrue(enc.adds.isEmpty(), "no adds in the first phase");
    // the boss's target isn't a player: someone else is picked; a downed player watches the warning
    now.addAndGet(8000);
    ticks(1);
    assertEquals(1, enc.telegraphs.size());
    runs.down(b);
    a.teleport(at(new double[] {enc.telegraphs.getFirst().t.x(), 65, enc.telegraphs.getFirst().t.z()}));
    server.getScheduler().performTicks(2);
    server.getScheduler().performTicks(2);
    // a second warning at once shares the drawing task; outgoing hits during a slam are normal
    enc.telegraphs.add(new Fights.Active(new Telegraph(Telegraph.Kind.BURST, 0, 64, 0, 1, now.get(), now.get() + 1)));
    assertEquals(1, runs.outgoingFactor(mob));
    runs.fights().hit(ar, mob);
    now.addAndGet(3000);
    server.getScheduler().performTicks(2);
    assertTrue(enc.telegraphs.isEmpty());
    // the next phase brings no adds; its shockwave lands on a boss that can't be rooted
    mob.setHealth(1);
    ticks(1);
    assertTrue(enc.adds.isEmpty());
    now.addAndGet(8000);
    ticks(1);
    now.addAndGet(4000);
    server.getScheduler().performTicks(2);
    // a sneaking player away from any downed friend can still be slammed; one target that isn't a candidate
    when(mob.getTarget()).thenReturn(b);
    // enraged, then gone without dying: the log says so and the bar goes
    now.addAndGet(60_000);
    ticks(1);
    assertTrue(enc.boss.enraged());
    when(mob.isDead()).thenReturn(true);
    ticks(1);
    assertTrue(ar.run.bossDefeated());
    // a boss placed in a room without a boss marker stands at its centre
    int rest = room(RoomType.REST);
    Fights.Encounter other = new Fights.Encounter(rest, new net.tfminecraft.trialrooms.dungeon.fight.Director.Plan(0,
        "boss", List.of()), now.get(), 0, 1);
    doReturn(null).when(mobs).spawn(eq("mob_foot"), any(), anyInt());
    nextOf.put("mob_boss", living(LivingEntity.class, at(middle(rest)), "Other"));
    assertTrue(runs.fights().startBoss(ar, other, warden(), now.get()));
  }

  @Test
  void spawnSpotsAndWardsOnSneakers() {
    PlayerMock a = player("Ann");
    int fight = room(RoomType.COMBAT);
    // the first free spot around a used marker: feet free but head blocked, then a good one
    when(block.isPassable()).thenReturn(true, false, true, true, true, true, true, false);
    useKit(fightKit(tuning(30, 0), warden()));
    RunManager.ActiveRun ar = enter(a, fight);
    assertFalse(ar.encounters.get(fight).alive.isEmpty());
    // a rest room fight (no markers): idle enemies can't be moved to one
    int rest = room(RoomType.REST);
    ar.run.member(a.getUniqueId()).room(rest);
    runs.fights().start(ar, rest);
    Fights.Encounter enc = ar.encounters.get(rest);
    now.addAndGet(25_000);
    runs.fights().step(ar, rest);
    // a sneaking player with no downed friend near is not reviving
    a.setSneaking(true);
    ar.run.member(a.getUniqueId()).room(fight);
    assertNotNull(enc);
  }

  /** The middle of a room's footprint box at standing height (the tests' rooms are rectangles). */
  double[] middle(int room) {
    var r = layout.rooms().get(room);
    return new double[] {(r.originX() + r.widthCells() / 2.0) * 32, 65, (r.originZ() + r.depthCells() / 2.0) * 32};
  }
}
