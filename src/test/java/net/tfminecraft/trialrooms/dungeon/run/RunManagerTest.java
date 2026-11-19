package net.tfminecraft.trialrooms.dungeon.run;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import net.tfminecraft.trialrooms.dungeon.DungeonModule;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonGenerator;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonRecipe;
import net.tfminecraft.trialrooms.dungeon.generator.GreyboxKitTest;
import net.tfminecraft.trialrooms.dungeon.load.DungeonKit;
import net.tfminecraft.trialrooms.dungeon.load.RecipeLoader;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;
import net.tfminecraft.trialrooms.dungeon.world.LayoutPlacer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class RunManagerTest {
  @TempDir Path temp;
  ServerMock server;
  World main;
  World dungeon;
  JavaPlugin plugin;
  DungeonModule module;
  DungeonKit kit;
  DungeonLayout layout;
  RunGeometry geo;
  Ports.Characters chars;
  Ports.Keys keys;
  Ports.Mobs mobs;
  RunFiles files;
  GateStore gates;
  AtomicLong now = new AtomicLong(1_000_000);
  RunManager runs;
  final boolean[] blockBo = {false};
  final String[] blockFor = {""};
  Map<String, String> characterOf = new HashMap<>();
  List<Entity> spawned = new ArrayList<>();
  RunSettings settings = RunSettings.defaults();
  RunRules rules = new RunRules(1, 3, "m.key", 1, 1, 0.5);
  LayoutPlacer placer;
  CompletableFuture<Location> placed;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    main = server.addSimpleWorld("main");
    dungeon = spy(server.addSimpleWorld(DungeonModule.WORLD_NAME));
    var block = mock(org.mockbukkit.mockbukkit.block.BlockMock.class);
    doReturn(block).when(dungeon).getBlockAt(anyInt(), anyInt(), anyInt());
    Server srv = mock(Server.class);
    when(srv.getScheduler()).thenReturn(server.getScheduler());
    when(srv.createBlockData(anyString())).thenReturn(mock(BlockData.class));
    plugin = mock(JavaPlugin.class);
    when(plugin.getServer()).thenReturn(srv);
    when(plugin.getName()).thenReturn("TrialRooms");
    when(plugin.namespace()).thenReturn("trialrooms");
    when(plugin.isEnabled()).thenReturn(true);
    when(plugin.getLogger()).thenReturn(Logger.getLogger("runs-test"));
    kit = GreyboxKitTest.kit();
    DungeonRecipe recipe = new DungeonRecipe("delve", 1, 1, Map.of(RoomType.COMBAT, 1), true, true, 1, 1, 1, 1,
        null, 20, 20000);
    layout = DungeonGenerator.generate(kit.catalog(), recipe, 4);
    geo = new RunGeometry(layout, 32, 4, 0, 64, 0);
    module = mock(DungeonModule.class);
    when(module.world()).thenReturn(dungeon);
    when(module.settings()).thenAnswer(i -> settings);
    when(module.recipes()).thenAnswer(i -> Map.of("delve", new RecipeLoader.Entry(recipe, "greybox", rules)));
    when(module.kits()).thenReturn(Map.of("greybox", kit));
    when(module.generate(eq("delve"), anyLong())).thenReturn(layout);
    placer = mock(LayoutPlacer.class);
    placed = CompletableFuture.completedFuture(at(geo.entry(0)));
    when(placer.place()).thenAnswer(i -> placed);
    when(module.placer(any())).thenReturn(placer);
    chars = mock(Ports.Characters.class);
    when(chars.activeCharacterId(any())).thenAnswer(i -> characterOf.get(((PlayerMock) i.getArgument(0)).getName()));
    when(chars.partyMembers(any())).thenAnswer(i -> List.of(((PlayerMock) i.getArgument(0)).getUniqueId()));
    keys = mock(Ports.Keys.class);
    when(keys.holds(any(), any())).thenReturn(true);
    when(keys.take(any(), any())).thenReturn(true);
    mobs = mock(Ports.Mobs.class);
    when(mobs.spawn(anyString(), any(), anyInt())).thenAnswer(i -> mob(i.getArgument(1)));
    files = new RunFiles(temp, Logger.getLogger("runs-test"));
    gates = new GateStore(temp.resolve("gates.yml").toFile(), Logger.getLogger("runs-test"));
    runs = new RunManager(plugin, module, chars, keys, mobs, files, gates, now::get);
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  Location at(double[] p) {
    return new Location(dungeon, p[0], p[1], p[2]);
  }

  Entity mob(Location at) {
    LivingEntity e = mock(LivingEntity.class);
    UUID id = UUID.randomUUID();
    when(e.getUniqueId()).thenReturn(id);
    when(e.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
    when(e.isValid()).thenReturn(true);
    when(e.getLocation()).thenReturn(at.clone());
    AttributeInstance max = mock(AttributeInstance.class);
    when(max.getBaseValue()).thenReturn(20.0);
    when(max.getValue()).thenReturn(30.0);
    when(e.getAttribute(Attribute.MAX_HEALTH)).thenReturn(max);
    spawned.add(e);
    return e;
  }

  void killAll() {
    for (Entity e : spawned) {
      when(e.isValid()).thenReturn(false);
    }
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

  void into(PlayerMock p, RoomType type) {
    p.teleport(at(geo.centre(room(type))));
  }

  RunManager.ActiveRun only() {
    assertEquals(1, runs.runs().size());
    return runs.runs().getFirst();
  }

  @Test
  void aPartyPlaysAWholeRun() throws Exception {
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    when(chars.partyLeader(a)).thenReturn(a.getUniqueId());
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    GateStore.Gate gate = gates.add(main.getBlockAt(10, 69, 10), "delve");
    runs.useGate(a, gate);
    DungeonRun run = only().run();
    assertTrue(runs.inDungeonWorld(a));
    assertTrue(runs.inDungeonWorld(b));
    assertTrue(runs.inRun(a));
    verify(keys).take(a, "m.key");
    assertTrue(Files.exists(temp.resolve("active/" + run.id() + ".yml")));
    assertEquals(10, files.returnPoint(a.getUniqueId(), "char-Ann").getX());
    assertSame(gates, runs.gates());
    assertEquals(run, runs.runOf(a).run());
    assertNotNull(runs.runOf(a).geometry());

    // a fight: Ann walks in, Bo is pulled in when the doors seal
    into(a, RoomType.COMBAT);
    ticks(1);
    assertEquals(DungeonRun.Room.COUNTDOWN, run.room(room(RoomType.COMBAT)));
    ticks(settings.countdownSeconds() * 2 + 1);
    assertEquals(DungeonRun.Room.ACTIVE, run.room(room(RoomType.COMBAT)));
    assertEquals(room(RoomType.COMBAT), geo.roomAt(b.getLocation().getX(), b.getLocation().getZ()));
    assertFalse(spawned.isEmpty());
    assertTrue(runs.isRunMob(spawned.getFirst()));
    assertFalse(runs.isRunMob(mock(Entity.class, RETURNS_DEEP_STUBS)));
    // a mob that wanders out is brought back
    Entity stray = spawned.getFirst();
    when(stray.getLocation()).thenReturn(new Location(dungeon, 5000, 65, 5000));
    ticks(1);
    verify(stray).teleport(any(Location.class));
    runs.mobDied(stray);
    killAll();
    ticks(1);
    assertEquals(DungeonRun.Room.CLEARED, run.room(room(RoomType.COMBAT)));

    // Bo goes down; Ann crouches beside him
    assertTrue(runs.down(b));
    assertFalse(runs.down(b));
    assertTrue(runs.isDowned(b));
    assertNotNull(runs.downedSpot(b));
    ticks(1);
    a.teleport(b.getLocation());
    a.setSneaking(true);
    ticks(settings.reviveSeconds() * 2);
    assertFalse(runs.isDowned(b));
    a.setSneaking(false);

    // down again and bleed out: a life is spent
    runs.down(b);
    ticks(1);
    now.addAndGet(settings.bleedOutSeconds() * 1000L + 1);
    ticks(1);
    assertEquals(0, run.lives());
    assertFalse(runs.isDowned(b));
    // with no lives left, the next bleed-out puts him out: he watches as a spectator
    runs.down(b);
    now.addAndGet(settings.bleedOutSeconds() * 1000L + 1);
    ticks(1);
    assertEquals(GameMode.SPECTATOR, b.getGameMode());
    assertEquals(DungeonRun.Status.OUT, run.member(b.getUniqueId()).status());
    runs.sweep(b);
    assertEquals(GameMode.SPECTATOR, b.getGameMode());

    // a rest room becomes the checkpoint
    into(a, RoomType.REST);
    ticks(1);
    assertEquals(room(RoomType.REST), run.checkpoint());
    assertEquals(room(RoomType.REST), geo.roomAt(runs.rescuePoint(a).getX(), runs.rescuePoint(a).getZ()));
    into(a, RoomType.TREASURE);
    ticks(1);

    // the boss waits for everyone playing (Bo is out), then the vault completes the run
    into(a, RoomType.BOSS);
    ticks(settings.bossCountdownSeconds() * 2 + 2);
    assertEquals(DungeonRun.Room.ACTIVE, run.room(room(RoomType.BOSS)));
    killAll();
    ticks(1);
    assertTrue(run.bossDefeated());
    assertFalse(runs.tryExit(a));
    into(a, RoomType.VAULT);
    ticks(1);
    assertEquals(DungeonRun.State.COMPLETE, run.state());
    a.teleport(at(geo.markers(room(RoomType.VAULT), "exit").getFirst()));
    assertTrue(runs.tryExit(a));
    assertFalse(runs.inDungeonWorld(a));
    assertEquals(GameMode.SURVIVAL, a.getGameMode());
    assertFalse(runs.tryExit(a));
    assertTrue(runs.leave(b));
    assertFalse(runs.leave(b));
    assertEquals(GameMode.SURVIVAL, b.getGameMode());
    ticks(1);
    assertTrue(runs.runs().isEmpty());
    String log = Files.readString(temp.resolve("runs.log"));
    assertTrue(log.contains("\"outcome\":\"COMPLETE\""), log);
    assertTrue(log.contains("\"boss\":true"), log);
    assertFalse(Files.exists(temp.resolve("active/" + run.id() + ".yml")));
    assertNull(files.returnPoint(a.getUniqueId(), "char-Ann"));
  }

  @Test
  void gatesRefuseWhatTheyShould() {
    PlayerMock a = player("Ann");
    GateStore.Gate gate = gates.add(main.getBlockAt(10, 69, 10), "delve");
    runs.useGate(a, new GateStore.Gate("main", 1, 1, 1, "nope"));
    characterOf.remove("Ann");
    runs.useGate(a, gate);
    characterOf.put("Ann", "char-Ann");
    when(chars.partyLeader(a)).thenReturn(UUID.randomUUID());
    runs.useGate(a, gate);
    when(chars.partyLeader(a)).thenReturn(null);
    when(keys.holds(a, "m.key")).thenReturn(false);
    runs.useGate(a, gate);
    when(keys.holds(a, "m.key")).thenReturn(true);
    // party members far away, without a character or offline don't count; too many is refused
    PlayerMock far = player("Far");
    far.teleport(new Location(main, 500, 70, 500));
    PlayerMock nochar = player("NoChar");
    characterOf.remove("NoChar");
    PlayerMock elsewhere = player("Elsewhere");
    elsewhere.teleport(new Location(dungeon, 10, 70, 10));
    PlayerMock b = player("Bo");
    PlayerMock c = player("Cy");
    PlayerMock d = player("Di");
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), UUID.randomUUID(), far.getUniqueId(),
        nochar.getUniqueId(), elsewhere.getUniqueId(), b.getUniqueId(), c.getUniqueId(), d.getUniqueId()));
    runs.useGate(a, gate);
    assertTrue(runs.runs().isEmpty());
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId()));
    // too few
    rules = new RunRules(2, 3, "m.key", 1, 1, 0.5);
    runs.useGate(a, gate);
    rules = new RunRules(1, 3, "m.key", 1, 1, 0.5);
    // every dungeon taken
    settings = new RunSettings(1, 4, 5, 30, 3, 2.5, 8, 300, 60, List.of());
    runs.useGate(b, gate);
    assertEquals(1, runs.runs().size());
    runs.useGate(a, gate);
    assertEquals(1, runs.runs().size());
    runs.useGate(b, gate);
    verify(keys, times(1)).take(any(), any());
    // generation that fails
    runs.end(only(), false, null);
    when(module.generate(eq("delve"), anyLong())).thenThrow(new IllegalStateException("no layout"));
    runs.useGate(a, gate);
    assertTrue(runs.runs().isEmpty());
    assertTrue(a.nextMessage() != null);
  }

  @Test
  void buildsThatFailOrLoseTheKeyOpenNothing() {
    PlayerMock a = player("Ann");
    GateStore.Gate gate = gates.add(main.getBlockAt(10, 69, 10), "delve");
    placed = CompletableFuture.failedFuture(new IllegalStateException("broken room"));
    runs.useGate(a, gate);
    assertTrue(runs.runs().isEmpty());
    verify(placer).release();
    placed = CompletableFuture.completedFuture(at(geo.entry(0)));
    when(keys.take(a, "m.key")).thenReturn(false);
    runs.useGate(a, gate);
    assertTrue(runs.runs().isEmpty());
    verify(placer, times(2)).release();
    verify(keys, never()).give(any(), any());
    // the leader went offline while it was being built
    when(keys.take(a, "m.key")).thenReturn(true);
    CompletableFuture<Location> slow = new CompletableFuture<>();
    placed = slow;
    runs.useGate(a, gate);
    assertTrue(runs.inRun(a));
    a.disconnect();
    slow.complete(at(geo.entry(0)));
    assertTrue(runs.runs().isEmpty());
    // a staff start needs no key; a member who changed character while it was built stays out
    PlayerMock s = player("Staff");
    CompletableFuture<Location> later = new CompletableFuture<>();
    placed = later;
    runs.startRun(s, "delve", 7);
    characterOf.put("Staff", "other");
    later.complete(at(geo.entry(0)));
    assertTrue(runs.runs().isEmpty(), "nobody came through: the run ends");
    assertTrue(Files.exists(temp.resolve("runs.log")));
  }

  @Test
  void stopsSweepsAndRefunds() throws Exception {
    PlayerMock a = player("Ann");
    GateStore.Gate gate = gates.add(main.getBlockAt(10, 69, 10), "delve");
    runs.start();
    runs.useGate(a, gate);
    RunManager.ActiveRun ar = only();
    runs.stop();
    assertTrue(runs.runs().isEmpty());
    assertFalse(runs.inDungeonWorld(a));
    verify(keys).give(a, "m.key");
    // a stray player in the dungeon world is sent back; staff may stay; a key owed is given on sweep
    PlayerMock stray = player("Stray");
    files.saveReturn(stray.getUniqueId(), "char-Stray", new Location(main, 1, 70, 1));
    files.addRefund(stray.getUniqueId(), "m.key");
    stray.teleport(new Location(dungeon, 0, 65, 0));
    runs.sweep(stray);
    assertFalse(runs.inDungeonWorld(stray));
    assertEquals(1, stray.getLocation().getX());
    runs.giveRefunds(stray);
    verify(keys).give(stray, "m.key");
    PlayerMock staff = player("Staff");
    staff.setOp(true);
    staff.teleport(new Location(dungeon, 0, 65, 0));
    runs.sweep(staff);
    assertTrue(runs.inDungeonWorld(staff));
    // someone without a character yet is left alone; once playing, with no return point, they go to spawn
    PlayerMock lost = player("Lost");
    characterOf.remove("Lost");
    lost.teleport(new Location(dungeon, 0, 65, 0));
    lost.setGameMode(GameMode.SPECTATOR);
    runs.sweep(lost);
    assertEquals(dungeon, lost.getWorld());
    characterOf.put("Lost", "char-Lost");
    runs.sweep(lost);
    assertEquals(main, lost.getWorld());
    assertEquals(GameMode.SURVIVAL, lost.getGameMode());
    assertNotNull(ar);
    // an empty leftover run file at start is reported and refunded
    files.saveActive(UUID.randomUUID(), a.getUniqueId(), "m.key");
    runs.start();
  }

  @Test
  void disconnectsDeathsAndCharacterChanges() {
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    runs.startRun(a, "delve", 1);
    DungeonRun run = only().run();
    // Bo drops while down: that counts as a bleed-out; he keeps his place within the grace time
    runs.down(b);
    runs.quit(b);
    assertEquals(0, run.lives());
    assertFalse(run.member(b.getUniqueId()).online());
    runs.sweep(b);
    assertTrue(run.member(b.getUniqueId()).online());
    runs.quit(player("Nobody"));
    // a death in the run counts as a bleed-out; with no lives he respawns watching
    runs.died(b);
    assertEquals(DungeonRun.Status.OUT, run.member(b.getUniqueId()).status());
    runs.respawned(b);
    assertEquals(GameMode.SPECTATOR, b.getGameMode());
    runs.respawned(a);
    runs.died(player("Nobody2"));
    assertNull(runs.respawnPoint(player("Nobody3")));
    // coming back on another character: he leaves the run and goes back
    characterOf.put("Bo", "char-Bo2");
    runs.sweep(b);
    assertEquals(DungeonRun.Status.LEFT, run.member(b.getUniqueId()).status());
    // Ann logs off; after the grace time she is out of the run and it ends
    runs.quit(a);
    now.addAndGet(settings.graceSeconds() * 1000L + 1);
    ticks(1);
    assertTrue(runs.runs().isEmpty());
    // a later sweep sends her back
    runs.sweep(a);
    assertFalse(runs.inDungeonWorld(a));
  }

  @Test
  void aDeathWhoseRunEndsBeforeTheRespawnRespawnsAsUsual() {
    PlayerMock a = player("Ann");
    runs.startRun(a, "delve", 1);
    runs.died(a);
    runs.end(only(), false, null);
    assertNull(runs.respawnPoint(a));
  }

  @Test
  void aBeatenPartyFails() {
    PlayerMock a = player("Ann");
    rules = new RunRules(1, 3, "", 0, 1, 0.5);
    runs.startRun(a, "delve", 1);
    DungeonRun run = only().run();
    runs.down(a);
    ticks(1);
    assertEquals(DungeonRun.State.FAILED, run.state());
    assertFalse(runs.down(a), "no downs once the run is over");
    runs.died(a);
    ticks(1);
    assertEquals(1, runs.runs().size());
    now.addAndGet(5001);
    ticks(1);
    assertTrue(runs.runs().isEmpty());
    assertTrue(runs.respawnPoint(a) == null);
  }

  @Test
  void spawnsSkipUnknownMobsAndEmptyRoomsClearAtOnce() {
    PlayerMock a = player("Ann");
    doReturn(null).when(mobs).spawn(anyString(), any(), anyInt());
    runs.startRun(a, "delve", 1);
    DungeonRun run = only().run();
    into(a, RoomType.COMBAT);
    ticks(2);
    assertEquals(DungeonRun.Room.CLEARED, run.room(room(RoomType.COMBAT)));
    // a mob that isn't a living entity still counts; a kit without mobs spawns nothing
    Entity plain = mock(Entity.class);
    when(plain.getUniqueId()).thenReturn(UUID.randomUUID());
    when(plain.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
    when(plain.isValid()).thenReturn(true);
    when(plain.getLocation()).thenReturn(at(geo.centre(room(RoomType.BOSS))));
    doReturn(plain).when(mobs).spawn(anyString(), any(), anyInt());
    into(a, RoomType.BOSS);
    ticks(settings.bossCountdownSeconds() * 2 + 2);
    assertEquals(DungeonRun.Room.ACTIVE, run.room(room(RoomType.BOSS)));
    when(plain.isDead()).thenReturn(true);
    ticks(1);
    assertTrue(run.bossDefeated());
    // a living mob without a max health attribute is left as it is
    LivingEntity bare = mock(LivingEntity.class);
    when(bare.getUniqueId()).thenReturn(UUID.randomUUID());
    when(bare.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
    when(bare.isValid()).thenReturn(true);
    when(bare.getLocation()).thenReturn(at(geo.centre(0)));
    assertNull(bare.getAttribute(Attribute.MAX_HEALTH));
    runs.end(only(), false, "bye");
    assertTrue(RunManager.json(new RunManager.ActiveRun(run, geo, placer, kit, rules, a.getUniqueId(), "", null),
        "X").contains("\"outcome\":\"X\""));
    DungeonKit noMobs = new DungeonKit("k", kit.catalog(), Map.of(), 4, "s", "d", Map.of());
    assertNull(noMobs.mobFor("melee"));
    assertEquals(EntityDamageEvent.DamageCause.VOID, EntityDamageEvent.DamageCause.valueOf("VOID"));
  }

  @Test
  void edgePathsBehave() {
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    PlayerMock c = player("Cy");
    // stopping before starting, and queries for strangers
    runs.stop();
    assertFalse(runs.isDowned(a));
    assertNull(runs.downedSpot(a));
    assertFalse(runs.down(a));
    runs.respawned(a);
    when(module.world()).thenReturn(null);
    assertFalse(runs.inDungeonWorld(a));
    when(module.world()).thenReturn(dungeon);
    assertTrue(runs.blocksCommand("/home"));
    Entity tagged = mock(Entity.class);
    PersistentDataContainer pdc = mock(PersistentDataContainer.class);
    when(tagged.getPersistentDataContainer()).thenReturn(pdc);
    when(tagged.getUniqueId()).thenReturn(UUID.randomUUID());
    when(pdc.has(any(org.bukkit.NamespacedKey.class), any())).thenReturn(true);
    assertTrue(runs.isRunMob(tagged));
    // a gate without a key, opened by a leader whose party member is already in another run
    rules = new RunRules(1, 3, "", 2, 1, 0.5);
    runs.startRun(c, "delve", 2);
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId(), c.getUniqueId()));
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"));
    assertEquals(2, runs.runs().size());
    RunManager.ActiveRun ar = runs.runOf(a);
    DungeonRun run = ar.run();
    assertNull(run.member(c.getUniqueId()));
    verify(keys, never()).take(any(), any());
    // a member with no player online is skipped by the step; a ghost downed member too
    UUID ghost = UUID.randomUUID();
    run.add(ghost, "ghost");
    UUID ghost2 = UUID.randomUUID();
    run.add(ghost2, "ghost2");
    run.down(ghost2, now.get());
    // someone outside every room; the vault before the boss; the rest room twice
    a.teleport(new Location(dungeon, 9000, 65, 9000));
    ticks(1);
    into(a, RoomType.VAULT);
    ticks(1);
    assertEquals(DungeonRun.State.RUNNING, run.state());
    into(a, RoomType.REST);
    ticks(1);
    a.teleport(new Location(dungeon, 9000, 65, 9000));
    ticks(1);
    into(a, RoomType.REST);
    ticks(1);
    // the checkpoint can be a room without a rest marker
    run.checkpoint(room(RoomType.TREASURE));
    assertEquals(room(RoomType.TREASURE), geo.roomAt(runs.rescuePoint(a).getX(), runs.rescuePoint(a).getZ()));
    // Bo offline within the grace time; Bo down before a seal is pulled in down
    runs.quit(b);
    ticks(1);
    runs.sweep(b);
    runs.down(b);
    a.setSneaking(true);
    into(a, RoomType.COMBAT);
    ticks(settings.countdownSeconds() * 2 + 2);
    assertEquals(DungeonRun.Room.ACTIVE, run.room(room(RoomType.COMBAT)));
    assertEquals(room(RoomType.COMBAT), geo.roomAt(runs.downedSpot(b).getX(), runs.downedSpot(b).getZ()));
    // a crouching member too far away doesn't revive
    a.teleport(at(geo.centre(room(RoomType.COMBAT))).add(10, 0, 10));
    ticks(1);
    assertTrue(runs.isDowned(b));
    // a member who died in the run respawns inside it, once; not on another character
    runs.died(a);
    assertNotNull(runs.respawnPoint(a));
    assertNull(runs.respawnPoint(a));
    runs.died(a);
    characterOf.put("Ann", "char-Other");
    assertNull(runs.respawnPoint(a));
    characterOf.put("Ann", "char-Ann");
    assertNull(runs.rescuePoint(player("Stranger")));
    // a run member dies outright, with and without a known cause
    b.setLastDamageCause(new EntityDamageEvent(b, EntityDamageEvent.DamageCause.VOID, mock(org.bukkit.damage.DamageSource.class), 5));
    runs.died(b);
    b.setLastDamageCause(null);
    runs.died(b);
    // the run ends with enemies still standing: they are removed
    runs.end(ar, true, null);
    verify(mobs, atLeastOnce()).remove(any());
    verify(keys, never()).give(any(), any());
    // a died() after the end does nothing
    runs.died(a);
    // tryExit far from the exit of a finished run
    runs.end(runs.runs().getFirst(), false, null);
    assertTrue(runs.runs().isEmpty());
  }

  @Test
  void lastRoomsAndRefundsForOfflineLeaders() {
    // a run without a vault: the boss room is a dead end, so clearing it names no next door
    DungeonRecipe noVault = new DungeonRecipe("delve", 0, 0, null, false, false, 0, 0, 1, 1, null, 20, 20000);
    layout = DungeonGenerator.generate(kit.catalog(), noVault, 2);
    geo = new RunGeometry(layout, 32, 4, 0, 64, 0);
    when(module.generate(eq("delve"), anyLong())).thenReturn(layout);
    placed = CompletableFuture.completedFuture(at(geo.entry(0)));
    // a kit without mobs spawns nothing; a living mob without max health is left alone
    DungeonKit bareKit = new DungeonKit("greybox", kit.catalog(), Map.of(), 4, "s", "minecraft:iron_bars", Map.of());
    when(module.kits()).thenReturn(Map.of("greybox", bareKit));
    PlayerMock a = player("Ann");
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"));
    DungeonRun run = only().run();
    into(a, RoomType.BOSS);
    ticks(settings.bossCountdownSeconds() * 2 + 2);
    assertTrue(run.bossDefeated());
    // the leader logs off; the run is ended as a fault: the refund waits for their next join
    runs.quit(a);
    a.disconnect();
    runs.end(only(), true, "x");
    assertEquals(List.of("m.key"), files.takeRefunds(a.getUniqueId()));
    // a mob kit present again: a living mob without max health
    when(module.kits()).thenReturn(Map.of("greybox", kit));
    LivingEntity bare = mock(LivingEntity.class);
    when(bare.getUniqueId()).thenReturn(UUID.randomUUID());
    when(bare.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
    when(bare.isValid()).thenReturn(true);
    when(bare.getLocation()).thenReturn(at(geo.centre(1)));
    doReturn(bare).when(mobs).spawn(anyString(), any(), anyInt());
    PlayerMock b = player("Bo");
    runs.startRun(b, "delve", 3);
    into(b, RoomType.BOSS);
    ticks(settings.bossCountdownSeconds() * 2 + 2);
    assertEquals(DungeonRun.Room.ACTIVE, only().run().room(1));
    // leaving from a run still in progress, then the end with the member gone
    assertTrue(runs.leave(b));
    ticks(1);
    assertTrue(runs.runs().isEmpty());
    // the run log escapes text and copes with a member without a character id
    DungeonRun r = new DungeonRun(UUID.randomUUID(), "a\\\"b", layout, 1, 0);
    r.add(UUID.randomUUID(), null);
    String json = RunManager.json(new RunManager.ActiveRun(r, geo, placer, kit, rules, UUID.randomUUID(), "", null), "OK");
    assertTrue(json.contains("\"character\":\"\""), json);
    assertTrue(json.contains("a\\\\\\\"b"), json);
  }

  @Test
  void aWholeOfflinePartyIsNotAWipeUntilTheGraceEnds() {
    PlayerMock a = player("Ann");
    rules = new RunRules(1, 3, "", 0, 1, 0.5);
    runs.startRun(a, "delve", 1);
    DungeonRun run = only().run();
    runs.quit(a);
    ticks(1);
    assertEquals(DungeonRun.State.RUNNING, run.state());
    // a finished run waits for its timer while someone is still inside
    run.state(DungeonRun.State.COMPLETE);
    only().finishAtMs = now.get() + 100_000;
    runs.sweep(a);
    ticks(1);
    assertEquals(1, runs.runs().size());
    assertFalse(runs.tryExit(a));
  }

  @Test
  void membersAwayOfflineOrStillStarting() {
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    PlayerMock c = player("Cy");
    PlayerMock d = player("Di");
    rules = new RunRules(1, 4, "", 2, 1, 0.5);
    // Cy is still waiting for her own run to be built, standing at the same gate
    CompletableFuture<Location> slow = new CompletableFuture<>();
    placed = slow;
    runs.startRun(c, "delve", 9);
    placed = CompletableFuture.completedFuture(at(geo.entry(0)));
    // Di disconnects while Ann's run is built
    CompletableFuture<Location> building = new CompletableFuture<>();
    placed = building;
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId(), c.getUniqueId(), d.getUniqueId()));
    runs.startRun(a, "delve", 1);
    d.disconnect();
    building.complete(at(geo.entry(0)));
    DungeonRun run = runs.runOf(a).run();
    assertNull(run.member(c.getUniqueId()));
    assertNull(run.member(d.getUniqueId()));
    assertNotNull(run.member(b.getUniqueId()));
    // Bo is offline while a fight seals and while others are told things
    runs.quit(b);
    into(a, RoomType.COMBAT);
    ticks(2);
    assertEquals(DungeonRun.Room.ACTIVE, run.room(room(RoomType.COMBAT)));
    runs.sweep(b);
    // Bo waits in another room when the fight is cleared
    b.teleport(at(geo.centre(0)));
    ticks(1);
    killAll();
    ticks(1);
    assertEquals(DungeonRun.Room.CLEARED, run.room(room(RoomType.COMBAT)));
    // a member a staff member moved out of the dungeon world stays where they are at the end
    b.teleport(new Location(main, 50, 70, 50));
    runs.end(runs.runOf(a), false, null);
    assertEquals(50, b.getLocation().getX());
    slow.complete(at(geo.entry(0)));
  }

  @Test
  void reviewFixesHold() {
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    rules = new RunRules(1, 3, "m.key", 3, 1, 0.5);
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    // the teleport in for Bo is refused by another plugin: he stays out and keeps no return point
    var blocker = new org.bukkit.event.Listener() {
      @org.bukkit.event.EventHandler
      public void on(org.bukkit.event.player.PlayerTeleportEvent e) {
        if (e.getPlayer().getName().equals("Bo") && blockBo[0] || e.getPlayer().getName().equals(blockFor[0])) {
          e.setCancelled(true);
        }
      }
    };
    server.getPluginManager().registerEvents(blocker, MockBukkit.createMockPlugin());
    blockBo[0] = true;
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"));
    blockBo[0] = false;
    DungeonRun run = runs.runOf(a).run();
    assertEquals(DungeonRun.Status.LEFT, run.member(b.getUniqueId()).status());
    assertNull(files.returnPoint(b.getUniqueId(), "char-Bo"));
    assertNull(runs.runOf(b));
    // a bleed-out during a fight wakes Ann inside the fight, not at the sealed-off checkpoint
    into(a, RoomType.COMBAT);
    ticks(2);
    assertEquals(DungeonRun.Room.ACTIVE, run.room(room(RoomType.COMBAT)));
    runs.down(a);
    now.addAndGet(settings.bleedOutSeconds() * 1000L + 1);
    ticks(1);
    assertEquals(room(RoomType.COMBAT), geo.roomAt(a.getLocation().getX(), a.getLocation().getZ()));
    assertEquals(room(RoomType.COMBAT), geo.roomAt(runs.rescuePoint(a).getX(), runs.rescuePoint(a).getZ()));
    // coming back to the dungeon world outside the fight pulls her into it
    a.teleport(at(geo.centre(0)));
    runs.sweep(a);
    assertEquals(room(RoomType.COMBAT), geo.roomAt(a.getLocation().getX(), a.getLocation().getZ()));
    // moved out of the dungeon world by staff: she counts as away, and a sweep there leaves her be
    a.teleport(new Location(main, 5, 70, 5));
    ticks(1);
    assertFalse(run.member(a.getUniqueId()).online());
    runs.sweep(a);
    assertFalse(run.member(a.getUniqueId()).online());
    assertNull(runs.rescuePoint(a), "no dungeon rescue outside the dungeon world");
    a.teleport(at(geo.centre(room(RoomType.COMBAT))));
    runs.sweep(a);
    assertTrue(run.member(a.getUniqueId()).online());
    killAll();
    ticks(1);
    // on another character she is no member here: no dungeon respawn, deaths do not count, leave does not move her
    characterOf.put("Ann", "char-Ann2");
    assertNull(runs.rescuePoint(a));
    assertNull(runs.respawnPoint(a));
    int lives = run.lives();
    runs.died(a);
    assertEquals(lives, run.lives());
    Location here = a.getLocation();
    assertTrue(runs.leave(a));
    assertEquals(here, a.getLocation());
    characterOf.put("Ann", "char-Ann");
    // a dungeon spectator found outside any run gets survival back; others are left alone
    PlayerMock s = player("Spec");
    s.setGameMode(GameMode.SPECTATOR);
    s.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "dungeon_spectator"),
        org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
    runs.sweep(s);
    assertEquals(GameMode.SURVIVAL, s.getGameMode());
    PlayerMock plain = player("Plain");
    runs.sweep(plain);
    // a tagged player who already switched game mode keeps it
    PlayerMock back = player("Back");
    back.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "dungeon_spectator"),
        org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
    back.setGameMode(GameMode.CREATIVE);
    runs.sweep(back);
    assertEquals(GameMode.CREATIVE, back.getGameMode());
    // leaving while a staff member has moved her out: nothing moves her
    PlayerMock e = player("Ed");
    runs.startRun(e, "delve", 6);
    e.teleport(new Location(main, 9, 70, 9));
    e.setGameMode(GameMode.SPECTATOR);
    assertTrue(runs.leave(e));
    assertEquals(9, e.getLocation().getX());
    assertEquals(GameMode.SURVIVAL, e.getGameMode());
    // a refused teleport back keeps the return point for the next try
    PlayerMock f = player("Bo2");
    runs.startRun(f, "delve", 8);
    blockFor[0] = "Bo2";
    assertTrue(runs.leave(f));
    blockFor[0] = "";
    assertNotNull(files.returnPoint(f.getUniqueId(), "char-Bo2"));
    // a member on another character found outside the dungeon world leaves without being moved
    PlayerMock c = player("Cy");
    runs.startRun(c, "delve", 5);
    DungeonRun cr = runs.runOf(c).run();
    c.teleport(new Location(main, 7, 70, 7));
    characterOf.put("Cy", "char-Cy2");
    runs.sweep(c);
    assertEquals(DungeonRun.Status.LEFT, cr.member(c.getUniqueId()).status());
    assertEquals(7, c.getLocation().getX());
  }

  @Test
  void theFinishWindowKeepsGoingAndRefundsWaitForACharacter() {
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"));
    DungeonRun run = runs.runOf(a).run();
    into(a, RoomType.BOSS);
    into(b, RoomType.BOSS);
    ticks(settings.bossCountdownSeconds() * 2 + 2);
    killAll();
    ticks(1);
    into(a, RoomType.VAULT);
    ticks(1);
    assertEquals(DungeonRun.State.COMPLETE, run.state());
    // Bo can still go down while the run is won; the vault again changes nothing
    assertTrue(runs.down(b));
    runs.died(b);
    a.teleport(at(geo.centre(0)));
    ticks(1);
    into(a, RoomType.VAULT);
    ticks(1);
    assertEquals(DungeonRun.State.COMPLETE, run.state());
    // an OUT member coming back is a spectator again and is not pulled anywhere
    runs.sweep(b);
    // the leader is in character selection (no active character) when a stop refunds the key
    characterOf.remove("Ann");
    runs.end(runs.runOf(a), true, null);
    verify(keys, never()).give(any(), any());
    assertEquals(List.of("m.key"), files.takeRefunds(a.getUniqueId()));
  }
}
