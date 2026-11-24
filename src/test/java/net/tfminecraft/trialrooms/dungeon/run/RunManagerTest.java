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
    // These tests cover a kit without a roster: one enemy per spawn marker (FightsTest covers rosters).
    DungeonKit bundled = GreyboxKitTest.kit();
    kit = new DungeonKit(bundled.id(), bundled.catalog(), bundled.structures(), bundled.doorHeight(),
        bundled.sealBlock(), bundled.doorBlock(), Map.of("melee", "dSkeleton_footman_em", "ranged",
        "dSkeleton_Archer_em", "elite", "dSkeleton_Halberdier_em", "boss", "dSkeleton_Tank_em"));
    DungeonRecipe recipe = new DungeonRecipe("delve", 1, 1, Map.of(RoomType.COMBAT, 1), true, true, 1, 1, 1, 1,
        null, 20, 20000);
    layout = DungeonGenerator.generate(kit.catalog(), recipe, 4);
    geo = new RunGeometry(layout, 32, 4, 0, 64, 0);
    module = mock(DungeonModule.class);
    when(module.world()).thenReturn(dungeon);
    when(module.settings()).thenAnswer(i -> settings);
    when(module.recipes()).thenAnswer(i -> recipesNow != null ? recipesNow
        : Map.of("delve", new RecipeLoader.Entry(recipe, "greybox", rules, difficulties)));
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
    p.teleport(at(middle(room(type))));
  }

  RunManager.ActiveRun only() {
    assertEquals(1, runs.runs().size());
    return runs.runs().getFirst();
  }

  List<Difficulty> difficulties = List.of();
  /** When set, the recipes a reload left (else the test's own). */
  Map<String, RecipeLoader.Entry> recipesNow;

  @Test
  void dungeonsWithDifficultiesAreChosenAtTheGateAndOpenUpByWins() {
    difficulties = List.of(new Difficulty("normal", "Normal", 1, 1, 1, null, "own gear"),
        new Difficulty("hard", "Hard", 2, 1.5, 1.25, "normal", ""));
    var lockers = new net.tfminecraft.trialrooms.dungeon.locker.LockerService(plugin,
        new net.tfminecraft.trialrooms.dungeon.locker.LockerStore(temp.resolve("lockers"), Logger.getLogger("t")),
        new ListCodec(), chars::activeCharacterId, p -> { });
    PlayerMock a = player("Ann");
    GateStore.Gate gate = gates.add(main.getBlockAt(10, 69, 10), "delve");
    assertFalse(runs.unlocked(a, "delve", difficulties.get(1)), "no lockers: nothing won");
    runs.startRun(a, "delve", 9, "normal"); // won without lockers: nothing to record
    into(a, RoomType.BOSS);
    ticks(14);
    killAll();
    ticks(4);
    into(a, RoomType.VAULT);
    ticks(2);
    runs.runs().forEach(r -> runs.end(r, false, null));
    runs.lockers(lockers);
    runs.useGate(a, gate);
    assertInstanceOf(DifficultyMenu.class, a.getOpenInventory().getTopInventory().getHolder(), "the gate asks which first");
    assertTrue(runs.runs().isEmpty());
    runs.useGate(a, gate, "hard");
    assertTrue(nextMatching(a, "opens once you have won Normal"));
    runs.useGate(a, gate, "mythic");
    assertTrue(nextMatching(a, "no difficulty mythic"));
    runs.useGate(a, gate, "normal");
    RunManager.ActiveRun ar = only();
    assertEquals("normal", ar.difficulty().id());
    assertTrue(nextMatching(a, "Difficulty: §fNormal"));
    runs.useGate(a, gate); // in a run: no menu, the usual refusal
    // the vault: Normal won, Hard opens
    into(a, RoomType.BOSS);
    ticks(14);
    killAll();
    ticks(4);
    into(a, RoomType.VAULT);
    ticks(2);
    assertEquals(java.util.Set.of("normal"), lockers.locker(a).cleared("delve"));
    assertTrue(nextMatching(a, "Hard is now open for you"));
    runs.runs().forEach(r -> runs.end(r, false, null));
    // Hard: tier 2, tougher enemies
    runs.useGate(a, gate, "hard");
    ar = only();
    assertEquals(2, ar.rules.tier());
    assertEquals(1.0, ar.healthFactor, "the party's factor stays the party's");
    assertEquals(1.5, ar.difficultyHealth);
    assertEquals(1.25, ar.damageFactor);
    Entity mob = mob(at(geo.entry(0)));
    runs.register(ar, mob, new double[] {0, 0, 0});
    assertEquals(1.25, runs.outgoingFactor(mob), 1e-9);
    runs.fights().mechanic(true);
    assertEquals(1, runs.outgoingFactor(mob), 1e-9, "boss mechanics grow with the tier, not the damage multiplier");
    assertEquals(1.25, runs.outgoingFactor(mob), 1e-9, "a hit the mechanic sets off is an ordinary one");
    into(a, RoomType.BOSS);
    ticks(14);
    killAll();
    ticks(4);
    recipesNow = Map.of(); // a reload dropped the recipe: the win still counts, with nothing to announce
    into(a, RoomType.VAULT);
    ticks(2);
    recipesNow = null;
    assertEquals(java.util.Set.of("normal", "hard"), lockers.locker(a).cleared("delve"));
    runs.runs().forEach(r -> runs.end(r, false, null));
    // a second Normal win is no news; staff start any difficulty without having won it
    runs.startRun(a, "delve", 3, "hard");
    assertEquals("hard", only().difficulty().id());
    runs.runs().forEach(r -> runs.end(r, false, null));
    runs.startRun(a, "delve", 3);
    assertEquals("normal", only().difficulty().id(), "the first difficulty by default");
    into(a, RoomType.BOSS);
    ticks(14);
    killAll();
    ticks(4);
    into(a, RoomType.VAULT);
    ticks(2);
    runs.runs().forEach(r -> runs.end(r, false, null));
    // a member on another character is not credited
    PlayerMock b = player("Bo");
    when(chars.partyLeader(b)).thenReturn(b.getUniqueId());
    when(chars.partyMembers(b)).thenReturn(List.of(b.getUniqueId()));
    runs.startRun(b, "delve", 4);
    characterOf.put("Bo", "char-other");
    into(b, RoomType.BOSS);
    ticks(14);
    killAll();
    ticks(4);
    into(b, RoomType.VAULT);
    ticks(2);
    characterOf.put("Bo", "char-Bo");
    assertEquals(java.util.Set.of(), lockers.locker(b).cleared("delve"));
    runs.runs().forEach(r -> runs.end(r, false, null));
    // a win whose save fails is not announced
    PlayerMock c = player("Cy");
    when(chars.partyLeader(c)).thenReturn(c.getUniqueId());
    when(chars.partyMembers(c)).thenReturn(List.of(c.getUniqueId()));
    lockers.locker(c);
    try {
      Files.createDirectories(temp.resolve("lockers").resolve(c.getUniqueId().toString()).resolve("char-Cy.locker.tmp"));
    } catch (java.io.IOException e) {
      fail(e);
    }
    runs.startRun(c, "delve", 5);
    into(c, RoomType.BOSS);
    ticks(14);
    killAll();
    ticks(4);
    into(c, RoomType.VAULT);
    ticks(2);
    assertTrue(nextMatching(c, "could not be saved"), "the player is told");
    assertEquals(java.util.Set.of(), lockers.locker(c).cleared("delve"), "taken back, so a later win counts");
    runs.runs().forEach(r -> runs.end(r, false, null));
  }

  /** Whether one of the player's messages so far holds the text (and drains them). */
  static boolean nextMatching(PlayerMock p, String text) {
    String m;
    boolean found = false;
    while ((m = p.nextMessage()) != null) {
      found |= m.contains(text);
    }
    return found;
  }

  RunSettings withLobby(int seconds) {
    return new RunSettings(4, 4, 5, 30, 3, 2.5, 8, 300, 60, List.of(), 60, java.util.Set.of(), java.util.Set.of(),
        5, 10, 60, seconds);
  }

  /** Items by index in a list: MockBukkit has no item bytes. */
  static final class ListCodec implements net.tfminecraft.trialrooms.dungeon.locker.ItemCodec {
    final List<org.bukkit.inventory.ItemStack> saved = new ArrayList<>();

    @Override
    public byte[] encode(org.bukkit.inventory.ItemStack item) {
      saved.add(item.clone());
      return java.nio.ByteBuffer.allocate(4).putInt(saved.size() - 1).array();
    }

    @Override
    public org.bukkit.inventory.ItemStack decode(byte[] data) {
      return saved.get(java.nio.ByteBuffer.wrap(data).getInt()).clone();
    }
  }

  @Test
  void clearedFightRoomsPayTheirShareOfTheRunsSpoils() {
    rules = new RunRules(1, 3, "m.key", 1, 1, 0.5, 1, "", 0, 0, 0, 30);
    List<String> paid = new ArrayList<>();
    runs.rewards(new RunRewards() {
      @Override
      public void reward(org.bukkit.entity.Player p, String recipe, int tier, RunRewards.Moment moment, java.util.UUID run) {
      }

      @Override
      public void spoils(org.bukkit.entity.Player p, String recipe, double hours, java.util.UUID run) {
        paid.add(p.getName() + " " + recipe + " " + hours);
      }
    });
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    PlayerMock c = player("Cy");
    when(chars.partyLeader(a)).thenReturn(a.getUniqueId());
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId(), c.getUniqueId()));
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"));
    RunManager.ActiveRun ar = only();
    long fights = layout.rooms().stream().filter(r -> DungeonRun.isFight(r.node().type())).count();
    // cleared at once: no time played, so nothing (a run never pays more than its time)
    into(a, RoomType.COMBAT);
    ticks(12);
    killAll();
    ticks(4);
    assertEquals(List.of(), paid);
    // an hour later the boss: its share, to each member still in the fight
    ar.run.bleedOut(b.getUniqueId());
    ar.run.bleedOut(b.getUniqueId()); // out of lives: out
    now.addAndGet(3_600_000);
    into(a, RoomType.BOSS);
    into(c, RoomType.BOSS);
    ticks(14);
    into(b, RoomType.BOSS);
    ticks(1);
    killAll();
    ticks(4);
    assertEquals(2, fights);
    assertEquals(List.of("Ann delve 0.5", "Cy delve 0.5"), paid,
        "both rooms' half hour (time saved in the first counts here), to each member still in the fight (Bo is out)");
    into(a, RoomType.VAULT);
    ticks(2);
    assertEquals(2, paid.size(), "all of the run's length is paid: the vault adds nothing");
    runs.runs().forEach(r -> runs.end(r, false, null));
    // played at a proper party's pace: each room as time allows, the rest when the run is won
    paid.clear();
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId()));
    runs.useGate(a, gates.add(main.getBlockAt(14, 69, 10), "delve"));
    now.addAndGet(600_000);
    into(a, RoomType.COMBAT);
    ticks(12);
    killAll();
    ticks(4);
    now.addAndGet(300_000);
    into(a, RoomType.BOSS);
    ticks(14);
    killAll();
    ticks(4);
    now.addAndGet(900_000);
    into(a, RoomType.VAULT);
    ticks(2);
    List<Double> hours = paid.stream().map(s -> Double.parseDouble(s.substring(s.lastIndexOf(' ') + 1))).toList();
    assertEquals(3, hours.size(), paid.toString());
    assertEquals(10 / 60.0, hours.get(0), 1e-9, "10 minutes played: 10 minutes paid");
    assertEquals(5 / 60.0, hours.get(1), 1e-9, "15 minutes played: 5 more");
    assertEquals(15 / 60.0, hours.get(2), 1e-9, "won at 30 minutes: the rest of the run's half hour");
    runs.runs().forEach(r -> runs.end(r, false, null));
    // a run given no length pays no spoils
    rules = new RunRules(1, 3, "m.key", 1, 1, 0.5);
    paid.clear();
    runs.useGate(a, gates.add(main.getBlockAt(12, 69, 10), "delve"));
    into(a, RoomType.COMBAT);
    ticks(12);
    killAll();
    ticks(4);
    assertEquals(List.of(), paid);
    runs.runs().forEach(r -> runs.end(r, false, null));
    RunRewards itemsOnly = (p, recipe, tier, moment, run) -> { };
    itemsOnly.spoils(a, "delve", 1, null); // rewards without spoils pay none
  }

  /** Plays a run from where it starts to the vault, taking {@code millis} after the doors open. */
  void win(PlayerMock a, long millis) {
    now.addAndGet(millis);
    into(a, RoomType.BOSS);
    ticks(14);
    killAll();
    ticks(4);
    into(a, RoomType.VAULT);
    ticks(2);
    runs.runs().forEach(r -> runs.end(r, false, null));
  }

  @Test
  void wonGateRunsGoOnTheLeaderboardsAndFeatsAreTold() {
    Records records = new Records(temp.resolve("records.yml").toFile(), Logger.getLogger("t"));
    assertNull(runs.records(), "off until set");
    runs.records(records);
    assertSame(records, runs.records());
    List<String> feats = new ArrayList<>();
    runs.rewards(new RunRewards() {
      @Override
      public void reward(org.bukkit.entity.Player p, String recipe, int tier, RunRewards.Moment moment, java.util.UUID run) {
      }

      @Override
      public void feat(org.bukkit.entity.Player p, String recipe, String difficulty, RunRewards.Moment moment,
          java.util.UUID run, List<String> party) {
        feats.add(p.getName() + " " + recipe + " [" + difficulty + "] " + moment + " " + party);
      }
    });
    PlayerMock a = player("Ann");
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"));
    win(a, 65_000);
    assertEquals(List.of("Ann delve [] BOSS [Ann]", "Ann delve [] VAULT [Ann]"), feats);
    assertEquals(65_000, records.top("delve", null, 1).getFirst().millis());
    assertTrue(nextMatching(a, "A new record: §f1:05 §7(solo)"));
    runs.useGate(a, gates.add(main.getBlockAt(12, 69, 10), "delve"));
    win(a, 70_000);
    assertTrue(nextMatching(a, "Your time: §f1:10§7, place 2 on the board (solo)"));
    // a staff start chooses its layout: it never goes on the board
    runs.startRun(a, "delve", 3);
    win(a, 1_000);
    assertEquals(2, records.top("delve", null, 1).size());
    // a full board of faster clears: just the time
    for (int i = 0; i < Records.KEEP; i++) {
      records.add("delve", null, 1, new Records.Clear(1_000, List.of("X"), 1));
    }
    runs.useGate(a, gates.add(main.getBlockAt(14, 69, 10), "delve"));
    win(a, 80_000);
    assertTrue(nextMatching(a, "Your time: 1:20 (solo)"));
    // at a difficulty: its own board, and the feats name it
    difficulties = List.of(new Difficulty("normal", "Normal", 1, 1, 1, null, ""));
    runs.useGate(a, gates.add(main.getBlockAt(16, 69, 10), "delve"), "normal");
    win(a, 90_000);
    assertTrue(nextMatching(a, "A new record: §f1:30 §7(Normal, solo)"));
    assertEquals(1, records.top("delve", "normal", 1).size());
    assertTrue(feats.contains("Ann delve [normal] VAULT [Ann]"), feats.toString());
    // a member who left is no part of the record; one out of the fight is, but rolls no cosmetics
    difficulties = List.of();
    feats.clear();
    PlayerMock b = player("Bo");
    PlayerMock c = player("Cy");
    when(chars.partyLeader(a)).thenReturn(a.getUniqueId());
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId(), c.getUniqueId()));
    runs.useGate(a, gates.add(main.getBlockAt(11, 69, 10), "delve"));
    RunManager.ActiveRun ar = only();
    runs.leave(b);
    ar.run.bleedOut(c.getUniqueId());
    ar.run.bleedOut(c.getUniqueId());
    win(a, 100_000);
    assertEquals(List.of("Ann delve [] BOSS [Ann, Cy]", "Ann delve [] VAULT [Ann, Cy]"), feats);
    assertEquals(List.of("Ann", "Cy"), records.top("delve", null, 2).getFirst().names());
    assertEquals("party of 3", RunManager.size(3));
    assertEquals("solo", RunManager.size(1));
  }

  @Test
  void affixesNeedASectionAndAKitWithFights() {
    assertEquals(List.of(), runs.weeklyAffixes("delve", 1), "no affixes section: none");
    when(module.affixes()).thenReturn(new Affixes(List.of(WeeklyAffix.FORTIFIED), Map.of(1, 1), 1.25, 1.3, 1.15, 0.3,
        1.25, 0.1, 12));
    assertEquals(List.of(), runs.weeklyAffixes("delve", 1), "this kit has no fight roster: nothing for them to change");
    assertEquals(List.of(), runs.weeklyAffixes("nowhere", 1));
    when(module.kits()).thenReturn(Map.of());
    assertEquals(List.of(), runs.weeklyAffixes("delve", 1), "the recipe's kit is gone");
  }

  @Test
  void reachingTheVaultUnderParGivesABetterVault() {
    rules = new RunRules(1, 3, "m.key", 1, 1, 0.5, 1, "", 0, 0, 0, 0, 10);
    List<String> vaults = new ArrayList<>();
    runs.rewards(new RunRewards() {
      @Override
      public void reward(org.bukkit.entity.Player p, String recipe, int tier, RunRewards.Moment moment, java.util.UUID run) {
      }

      @Override
      public void reward(org.bukkit.entity.Player p, String recipe, int tier, RunRewards.Moment moment, java.util.UUID run,
          boolean underPar) {
        if (moment == RunRewards.Moment.VAULT) {
          vaults.add(p.getName() + " " + underPar);
        }
      }
    });
    PlayerMock a = player("Ann");
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"));
    win(a, 300_000);
    assertTrue(nextMatching(a, "Under par (10:00)!"));
    runs.useGate(a, gates.add(main.getBlockAt(12, 69, 10), "delve"));
    win(a, 900_000);
    assertTrue(nextMatching(a, "Par was 10:00."));
    assertEquals(List.of("Ann true", "Ann false"), vaults);
  }

  @Test
  void whatWaitsIsClaimedOutsideTheDungeon() {
    PlayerMock a = player("Ann");
    runs.claim(a);
    assertEquals("§7Nothing is waiting for you.", a.nextMessage(), "no lockers");
    var lockers = new net.tfminecraft.trialrooms.dungeon.locker.LockerService(plugin,
        new net.tfminecraft.trialrooms.dungeon.locker.LockerStore(temp.resolve("lockers"), Logger.getLogger("t")),
        new ListCodec(), chars::activeCharacterId, p -> { });
    runs.lockers(lockers);
    runs.claim(a);
    assertEquals("§7Nothing is waiting for you.", a.nextMessage());
    PlayerMock nobody = player("Nobody");
    characterOf.remove("Nobody");
    runs.claim(nobody);
    assertEquals("§7Nothing is waiting for you.", nobody.nextMessage(), "no character, no locker");
    lockers.owe(lockers.locker(a), new org.bukkit.inventory.ItemStack(org.bukkit.Material.GOLD_INGOT, 3));
    a.teleport(new Location(dungeon, 0, 70, 0));
    runs.claim(a);
    assertEquals("§7What waits for you comes once you are out of the dungeon.", a.nextMessage());
    a.teleport(new Location(main, 10, 70, 10));
    runs.claim(a);
    assertTrue(a.getInventory().contains(org.bukkit.Material.GOLD_INGOT, 3));
    assertTrue(lockers.locker(a).owed().isEmpty());
  }

  /** Rewards that write down what is paid, to whom, and how. */
  RunRewards recording(List<String> got) {
    return new RunRewards() {
      @Override
      public void reward(org.bukkit.entity.Player p, String recipe, int tier, RunRewards.Moment moment, java.util.UUID run) {
      }

      @Override
      public void reward(org.bukkit.entity.Player p, String recipe, int tier, RunRewards.Moment moment, java.util.UUID run,
          boolean underPar, double boost) {
        if (moment == RunRewards.Moment.EVENT && p.getName().equals("Cy")) {
          throw new IllegalStateException("a broken item"); // one failing roll never takes the rest
        }
        got.add(p.getName() + " " + moment + (boost > 1 ? " x" + boost : ""));
      }

      @Override
      public void spoils(org.bukkit.entity.Player p, String recipe, double hours, java.util.UUID run) {
        got.add(p.getName() + " spoils");
      }

      @Override
      public RunRewards.Find find(org.bukkit.entity.Player p, String recipe, int tier, RunRewards.Moment moment,
          java.util.UUID run, double boost) {
        return new RunRewards.Find(moment, 0, moment.name().toLowerCase() + " find", 10, 0, false, List.of(), List.of(),
            false);
      }

      @Override
      public RunRewards.Find spoilsFind(org.bukkit.entity.Player p, String recipe, double hours, java.util.UUID run) {
        return new RunRewards.Find(null, hours, "spoils", 30, 0, false, List.of(), List.of(), false);
      }

      @Override
      public void deliver(org.bukkit.entity.Player p, RunRewards.Find f, String recipe, int tier, java.util.UUID run,
          double boost, boolean restore) {
        if (restore) {
          got.add(p.getName() + " restore");
        }
        RunRewards.super.deliver(p, f, recipe, tier, run, boost, restore);
      }

      @Override
      public void feat(org.bukkit.entity.Player p, String recipe, String difficulty, RunRewards.Moment moment,
          java.util.UUID run, List<String> party) {
        got.add(p.getName() + " feat " + moment);
      }
    };
  }

  /** Clears the run's one combat room (with a room event, so it is an EVENT moment). */
  void clearCombat(RunManager.ActiveRun ar, PlayerMock... ps) {
    int combat = room(RoomType.COMBAT);
    ar.planned = Map.of(combat, net.tfminecraft.trialrooms.dungeon.event.EventKind.ENDURE);
    now.addAndGet(300_000);
    for (PlayerMock p : ps) {
      into(p, RoomType.COMBAT);
    }
    ticks(12);
    killAll();
    ticks(4);
  }

  @Test
  void normalBanksAtSavePointsAndAFailedRunKeepsWhatWasBanked() {
    rules = new RunRules(1, 3, "m.key", 3, 1, 0.5, 1, "", 0, 0, 0, 30);
    difficulties = List.of(new Difficulty("normal", "Normal", 1, 1, 1, null, "", 0, 0, new Difficulty.Banking(1, true, 1),
        false, 1));
    List<String> got = new ArrayList<>();
    runs.rewards(recording(got));
    PlayerMock a = player("Ann");
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"), "normal");
    RunManager.ActiveRun ar = only();
    assertTrue(nextMatching(a, "What you find is banked every 1 cleared fight rooms and at the rest room."));
    clearCombat(ar, a);
    assertEquals(List.of(), got, "carried, then banked: nothing paid yet");
    assertTrue(nextMatching(a, "Saved:"));
    runs.end(ar, false, null); // the run fails
    assertEquals(List.of("Ann EVENT", "Ann spoils"), got, "Normal keeps all it banked");
    assertTrue(nextMatching(a, "Your finds come home."));
    // the rest room banks too; leaving keeps what was banked
    got.clear();
    difficulties = List.of(new Difficulty("normal", "Normal", 1, 1, 1, null, "", 0, 0, new Difficulty.Banking(0, true, 1),
        false, 1));
    runs.useGate(a, gates.add(main.getBlockAt(12, 69, 10), "delve"), "normal");
    ar = only();
    clearCombat(ar, a);
    into(a, RoomType.REST);
    ticks(2);
    assertTrue(nextMatching(a, "Saved:"));
    runs.leave(a);
    assertEquals(List.of("Ann EVENT", "Ann spoils"), got);
    difficulties = List.of();
  }

  @Test
  void hardKeepsAShareOfWhatWasBankedWhenTheRunFails() {
    rules = new RunRules(1, 3, "m.key", 3, 1, 0.5, 1, "", 0, 0, 0, 30);
    difficulties = List.of(new Difficulty("hard", "Hard", 1, 1, 1, null, "", 0, 0, new Difficulty.Banking(0, true, 0.5),
        false, 1));
    List<String> got = new ArrayList<>();
    runs.rewards(recording(got));
    PlayerMock a = player("Ann");
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"), "hard");
    assertTrue(nextMatching(a, "each banked find has a 50% chance to come home"));
    RunManager.ActiveRun ar = only();
    clearCombat(ar, a);
    into(a, RoomType.REST);
    ticks(2);
    runs.keepRoll = () -> 0.8; // over 50%: this one stays behind
    runs.end(ar, false, null);
    assertEquals(List.of(), got, "each banked find (the event's, the room's spoils) stayed behind");
    assertTrue(nextMatching(a, "0 of 2 banked finds come home."));
    got.clear();
    runs.useGate(a, gates.add(main.getBlockAt(12, 69, 10), "delve"), "hard");
    ar = only();
    clearCombat(ar, a);
    into(a, RoomType.REST);
    ticks(2);
    runs.keepRoll = () -> 0.2;
    runs.end(ar, false, null);
    assertEquals(List.of("Ann EVENT", "Ann spoils"), got);
    // a stop (a fault) loses nothing: banked and carried both come home
    got.clear();
    runs.useGate(a, gates.add(main.getBlockAt(14, 69, 10), "delve"), "hard");
    ar = only();
    clearCombat(ar, a);
    runs.end(ar, true, true, "§cThe dungeon fades.");
    assertEquals(List.of("Ann EVENT", "Ann spoils"), got, "a server stop: carried, but not the players' fault");
    // a staff end is a failed run: what was carried stays behind
    got.clear();
    runs.useGate(a, gates.add(main.getBlockAt(18, 69, 10), "delve"), "hard");
    ar = only();
    clearCombat(ar, a);
    runs.end(ar, true, "§cA keeper of the dungeons has closed this run.");
    assertEquals(List.of(), got);
    // failed before the rest room: nothing banked, so nothing comes home
    got.clear();
    runs.useGate(a, gates.add(main.getBlockAt(16, 69, 10), "delve"), "hard");
    ar = only();
    clearCombat(ar, a);
    runs.end(ar, false, null);
    assertEquals(List.of(), got);
    difficulties = List.of();
  }

  @Test
  void nightmarePaysOnlyWhenTheBossFallsAndAFallLosesWhatIsCarried() {
    rules = new RunRules(1, 3, "m.key", 3, 1, 0.5, 1, "", 0, 0, 0, 30);
    difficulties = List.of(new Difficulty("nightmare", "Nightmare", 1, 1, 1, null, "", 0, 0,
        new Difficulty.Banking(0, false, 0), false, 2));
    List<String> got = new ArrayList<>();
    runs.rewards(recording(got));
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    PlayerMock c = player("Cy");
    when(chars.partyLeader(a)).thenReturn(a.getUniqueId());
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId(), c.getUniqueId()));
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"), "nightmare");
    RunManager.ActiveRun ar = only();
    assertTrue(nextMatching(a, "What you find comes home only if the boss falls."));
    assertTrue(nextMatching(b, "Item chances and marks x2 for the risk."));
    clearCombat(ar, a, b, c);
    assertTrue(nextMatching(a, "You carry a find"));
    into(a, RoomType.REST);
    ticks(2);
    assertFalse(nextMatching(a, "Saved:"), "no save points on Nightmare");
    // Bo falls: what he carries is lost (a life brings him back)
    assertTrue(runs.down(b));
    now.addAndGet(settings.bleedOutSeconds() * 1000L + 1);
    ticks(1);
    assertTrue(nextMatching(b, "You fell: what you carried is lost."));
    into(a, RoomType.BOSS);
    into(b, RoomType.BOSS);
    into(c, RoomType.BOSS);
    ticks(14);
    killAll();
    ticks(4);
    assertEquals(List.of(), got);
    into(a, RoomType.VAULT);
    ticks(2);
    assertTrue(got.containsAll(List.of("Ann EVENT x2.0", "Ann BOSS x2.0", "Ann feat BOSS", "Bo BOSS x2.0",
        "Cy BOSS x2.0", "Ann VAULT x2.0", "Bo VAULT x2.0")), got.toString());
    assertFalse(got.contains("Bo EVENT x2.0"), "Bo fell carrying it");
    assertTrue(nextMatching(c, "Some of your finds could not be paid. Tell staff."), "Cy's event roll failed");
    runs.runs().forEach(r -> runs.end(r, false, null));
    difficulties = List.of();
  }

  @Test
  void outMembersFindNothingAndAFailedRunBeforeAnySaveKeepsNothing() {
    rules = new RunRules(1, 3, "m.key", 3, 1, 0.5); // (no spoils)
    difficulties = List.of(new Difficulty("normal", "Normal", 1, 1, 1, null, "", 0, 0, new Difficulty.Banking(2, false, 1),
        false, 1));
    List<String> got = new ArrayList<>();
    runs.rewards(recording(got));
    PlayerMock a = player("Ann");
    PlayerMock c = player("Cy");
    when(chars.partyLeader(a)).thenReturn(a.getUniqueId());
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), c.getUniqueId()));
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"), "normal");
    RunManager.ActiveRun ar = only();
    for (int i = 0; i < 4; i++) {
      ar.run.bleedOut(c.getUniqueId()); // Cy is out: nothing to find, and no vault
    }
    clearCombat(ar, a, c);
    assertFalse(nextMatching(a, "Saved:"), "a save point every 2 fight rooms: not yet");
    assertFalse(nextMatching(c, "You carry a find"));
    runs.end(ar, false, null);
    assertEquals(List.of(), got, "nothing was banked, and what was carried stays behind");
    // the same rules, won: the second fight (the boss) banks, then everything comes home
    runs.useGate(a, gates.add(main.getBlockAt(12, 69, 10), "delve"), "normal");
    ar = only();
    for (int i = 0; i < 4; i++) {
      ar.run.bleedOut(c.getUniqueId());
    }
    clearCombat(ar, a, c);
    into(a, RoomType.BOSS);
    into(c, RoomType.BOSS);
    ticks(14);
    killAll();
    ticks(4);
    assertTrue(nextMatching(a, "Saved:"));
    into(a, RoomType.VAULT);
    ticks(2);
    assertTrue(got.contains("Ann VAULT") && !got.contains("Cy VAULT"), got.toString());
    runs.runs().forEach(r -> runs.end(r, false, null));
    // banked at the boss, then failed before the vault: the trophy names the party all the same
    got.clear();
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId()));
    runs.useGate(a, gates.add(main.getBlockAt(13, 69, 10), "delve"), "normal");
    ar = only();
    clearCombat(ar, a);
    into(a, RoomType.BOSS);
    ticks(14);
    killAll();
    ticks(4);
    runs.end(ar, false, null);
    assertTrue(got.contains("Ann feat BOSS"), got.toString());
    // a fall after a save loses nothing; with half kept and no rewards to pay with, nothing is paid
    got.clear();
    difficulties = List.of(new Difficulty("normal", "Normal", 1, 1, 1, null, "", 0, 0, new Difficulty.Banking(1, true, 0.5),
        false, 1));
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId()));
    runs.useGate(a, gates.add(main.getBlockAt(14, 69, 10), "delve"), "normal");
    ar = only();
    clearCombat(ar, a);
    into(a, RoomType.REST);
    ticks(2);
    assertTrue(runs.down(a));
    now.addAndGet(settings.bleedOutSeconds() * 1000L + 1);
    ticks(1);
    assertFalse(nextMatching(a, "You fell: what you carried is lost."), "it was all banked");
    runs.end(ar, false, null); // (a real roll: one of one, or none)
    assertTrue(nextMatching(a, "of 1 banked finds come home."));
    runs.useGate(a, gates.add(main.getBlockAt(16, 69, 10), "delve"), "normal");
    ar = only();
    clearCombat(ar, a);
    runs.rewards(null);
    runs.end(ar, false, null);
    difficulties = List.of();
  }

  @Test
  void mythicDestroysTheWornGearOfAPlayerWhoFalls() {
    difficulties = List.of(new Difficulty("mythic", "Mythic", 1, 1, 1, null, "", 0, 0, new Difficulty.Banking(0, false, 0),
        true, 2));
    var lockers = mock(net.tfminecraft.trialrooms.dungeon.locker.LockerService.class);
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    PlayerMock c = player("Cy");
    when(chars.partyLeader(a)).thenReturn(a.getUniqueId());
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId(), c.getUniqueId()));
    when(lockers.forfeitWorn(b, null)).thenReturn(List.of(new byte[] {1}));
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"), "mythic");
    assertTrue(nextMatching(a, "Your worn dungeon gear is at stake"));
    into(a, RoomType.COMBAT);
    into(b, RoomType.COMBAT);
    into(c, RoomType.COMBAT);
    ticks(12);
    assertTrue(runs.down(c)); // no lockers yet: nothing to destroy
    now.addAndGet(settings.bleedOutSeconds() * 1000L + 1);
    ticks(1);
    runs.lockers(lockers);
    assertTrue(runs.down(b));
    now.addAndGet(settings.bleedOutSeconds() * 1000L + 1);
    ticks(1);
    verify(lockers).forfeitWorn(b, null);
    assertTrue(nextMatching(b, "Your worn dungeon gear is destroyed."));
    RunManager.ActiveRun ar = only();
    assertTrue(runs.down(a));
    now.addAndGet(settings.bleedOutSeconds() * 1000L + 1);
    ticks(1);
    assertFalse(nextMatching(a, "is destroyed"), "nothing worn: nothing destroyed");
    ar.run.state(DungeonRun.State.COMPLETE); // after the win, a death destroys nothing
    runs.died(b);
    verify(lockers, times(1)).forfeitWorn(b, null);
    runs.runs().forEach(r -> runs.end(r, false, null));
    // a wipe: whoever is still down falls with it; leaving while down is a fall too
    rules = new RunRules(1, 3, "m.key", 0, 1, 0.5);
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    runs.useGate(a, gates.add(main.getBlockAt(12, 69, 10), "delve"), "mythic");
    into(a, RoomType.COMBAT);
    into(b, RoomType.COMBAT);
    ticks(12);
    assertTrue(runs.down(a));
    now.addAndGet(settings.bleedOutSeconds() * 1000L + 1);
    ticks(1); // no lives: Ann is out
    assertTrue(runs.down(b));
    b.setSneaking(true); // alone, Bo gets himself up once in this room
    ticks(settings.selfReviveSeconds() * 2 + 2);
    b.setSneaking(false);
    assertFalse(runs.isDowned(b));
    assertTrue(runs.down(b));
    ticks(1); // everyone down or out, and no more getting up: the party wipes
    verify(lockers, times(2)).forfeitWorn(b, null);
    runs.runs().forEach(r -> runs.end(r, false, null));
    runs.useGate(a, gates.add(main.getBlockAt(14, 69, 10), "delve"), "mythic");
    into(a, RoomType.COMBAT);
    into(b, RoomType.COMBAT);
    ticks(12);
    int lives = only().run.lives();
    assertTrue(runs.down(b));
    runs.leave(b);
    verify(lockers, times(3)).forfeitWorn(b, null);
    assertEquals(lives, only().run.lives(), "leaving while down costs the party no life");
    runs.runs().forEach(r -> runs.end(r, false, null));
    // leaving alive in the middle of a fight, with gear at stake, is no way out either
    runs.useGate(a, gates.add(main.getBlockAt(16, 69, 10), "delve"), "mythic");
    into(a, RoomType.COMBAT);
    into(b, RoomType.COMBAT);
    ticks(12);
    runs.leave(b);
    verify(lockers, times(4)).forfeitWorn(b, null);
    runs.runs().forEach(r -> runs.end(r, false, null));
    runs.useGate(a, gates.add(main.getBlockAt(11, 69, 11), "delve"), "mythic");
    runs.leave(b); // alive, and no fight going on: leaving costs nothing
    verify(lockers, times(4)).forfeitWorn(b, null);
    runs.runs().forEach(r -> runs.end(r, false, null));
    difficulties = List.of();
  }

  @Test
  void runsTellTheirRewardMoments() {
    List<String> told = new ArrayList<>();
    runs.rewards((p, recipe, tier, moment, run) -> told.add(p.getName() + " " + recipe + " " + tier + " " + moment));
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    when(chars.partyLeader(a)).thenReturn(a.getUniqueId());
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"));
    RunManager.ActiveRun ar = only();
    characterOf.put("Bo", "char-other"); // back on another character: not told
    int combat = room(RoomType.COMBAT);
    ar.planned = Map.of(combat, net.tfminecraft.trialrooms.dungeon.event.EventKind.ENDURE);
    into(a, RoomType.COMBAT);
    ticks(12);
    killAll();
    ticks(4);
    assertTrue(told.contains("Ann delve 1 EVENT"), told.toString());
    assertFalse(told.contains("Bo delve 1 EVENT"), "not on the character that came in");
    characterOf.put("Bo", "char-Bo");
    into(b, RoomType.BOSS);
    into(a, RoomType.BOSS);
    ticks(14);
    killAll();
    ticks(4);
    assertTrue(told.contains("Ann delve 1 BOSS"), told.toString());
    // a reward that throws for one player stops neither the others' nor the run
    runs.rewards((p, recipe, tier, moment, run) -> {
      told.add(p.getName() + " " + moment);
      if (p.getName().equals("Ann")) {
        throw new IllegalStateException("broken item plugin");
      }
      throw new NoClassDefFoundError("a soft dependency is missing");
    });
    into(a, RoomType.VAULT);
    ticks(2);
    assertTrue(told.contains("Ann VAULT") && told.contains("Bo VAULT"), told.toString());
    assertTrue(ar.finishAtMs > 0, "the run still ends in its own time");
    assertTrue(told.contains("Bo delve 1 BOSS"), told.toString());
    runs.runs().forEach(r -> runs.end(r, false, null));
    assertEquals(RunRewards.Moment.ELITE, RunManager.clearedMoment(RoomType.ELITE, true));
    assertEquals(RunRewards.Moment.BOSS, RunManager.clearedMoment(RoomType.BOSS, true));
    assertEquals(RunRewards.Moment.EVENT, RunManager.clearedMoment(RoomType.COMBAT, true));
    assertNull(RunManager.clearedMoment(RoomType.COMBAT, false));
  }

  @Test
  void aLobbyHoldsThePartyUntilEveryoneIsReady() {
    settings = withLobby(60);
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    PlayerMock c = player("Cy");
    assertFalse(runs.ready(c), "not in a run");
    assertFalse(runs.inLobby(c));
    when(chars.partyLeader(a)).thenReturn(a.getUniqueId());
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"));
    RunManager.ActiveRun ar = only();
    assertEquals(geo.roomAt(geo.entry(0)[0], geo.entry(0)[2]), ar.lobbyRoom);
    assertTrue(runs.inLobby(a));
    String said;
    boolean buttons = false;
    while ((said = a.nextMessage()) != null) {
      buttons |= said.contains("Get ready in this room");
    }
    assertTrue(buttons, "the locker and ready buttons");
    characterOf.put("Bo", "char-other");
    ticks(1);
    assertTrue(ar.lobbyRoom >= 0);
    assertFalse(runs.inLobby(b), "not on the character that came in");
    characterOf.put("Bo", "char-Bo");
    assertTrue(runs.ready(a));
    assertTrue(runs.ready(a), "saying it twice changes nothing");
    ticks(1);
    assertTrue(ar.lobbyRoom >= 0, "Bo is not ready yet");
    assertEquals(ar.progressMs, now.get(), "a party getting ready is not stuck");
    into(b, RoomType.COMBAT);
    assertFalse(runs.inLobby(b), "only in the entrance room");
    b.teleport(new Location(main, 0, 70, 0)); // away from the dungeon: only those inside count
    assertFalse(runs.inLobby(b));
    ticks(1);
    assertEquals(-1, ar.lobbyRoom, "everyone inside is ready: the doors open");
    assertFalse(runs.inLobby(a));
    assertFalse(runs.ready(a), "nothing to get ready for any more");
  }

  @Test
  void aLobbyOpensWhenTimeRunsOut() {
    settings = withLobby(10);
    PlayerMock a = player("Ann");
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"));
    RunManager.ActiveRun ar = only();
    now.addAndGet(9_000);
    ticks(1);
    assertTrue(ar.lobbyRoom >= 0);
    a.teleport(new Location(main, 0, 70, 0)); // nobody inside: still waits for the time
    ticks(1);
    assertTrue(ar.lobbyRoom >= 0);
    now.addAndGet(1_000);
    ticks(1);
    assertEquals(-1, ar.lobbyRoom);
  }

  @Test
  void loadoutsComeOffWheneverAPlayerLeavesTheDungeonWorld() {
    settings = withLobby(60);
    var lockers = new net.tfminecraft.trialrooms.dungeon.locker.LockerService(plugin,
        new net.tfminecraft.trialrooms.dungeon.locker.LockerStore(temp.resolve("lockers"), Logger.getLogger("t")),
        new ListCodec(), chars::activeCharacterId, p -> { });
    runs.lockers(lockers);
    assertSame(lockers, runs.lockers());
    PlayerMock a = player("Ann");
    a.getInventory().setItem(0, new org.bukkit.inventory.ItemStack(org.bukkit.Material.IRON_SWORD));
    String id = lockers.add(a, new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND_SWORD));
    lockers.locker(a).assign(0, 0, id);
    GateStore.Gate gate = gates.add(main.getBlockAt(10, 69, 10), "delve");

    runs.useGate(a, gate);
    assertEquals(net.tfminecraft.trialrooms.dungeon.locker.LockerService.Result.DONE, lockers.wear(a, 0));
    runs.sweep(a); // inside, playing: it stays on
    assertEquals(org.bukkit.Material.DIAMOND_SWORD, a.getInventory().getItem(0).getType());
    assertTrue(runs.leave(a));
    assertEquals(org.bukkit.Material.IRON_SWORD, a.getInventory().getItem(0).getType(), "own sword back");
    assertEquals(-1, lockers.locker(a).worn());

    // out of the dungeon world some other way: the next sweep takes it off
    runs.useGate(a, gate);
    lockers.wear(a, 0);
    a.teleport(new Location(main, 0, 70, 0));
    runs.sweep(a);
    assertEquals(org.bukkit.Material.IRON_SWORD, a.getInventory().getItem(0).getType());
    runs.runs().forEach(r -> runs.end(r, false, null));

    // someone outside any run, and a member back on another character: nothing worn stays
    PlayerMock c = player("Cy");
    runs.sweep(c);
    runs.useGate(a, gate);
    characterOf.put("Ann", "char-other");
    runs.sweep(a);
    assertFalse(runs.inRun(a));
    runs.runs().forEach(r -> runs.end(r, false, null));
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
    settings = new RunSettings(1, 4, 5, 30, 3, 2.5, 8, 300, 60, List.of(), 60, java.util.Set.of(), java.util.Set.of(), 5, 10, 60);
    runs.useGate(b, gate);
    assertEquals(1, runs.runs().size());
    runs.useGate(a, gate);
    assertEquals(1, runs.runs().size());
    runs.useGate(b, gate);
    verify(keys, times(1)).take(any(), any());
    // generation that fails (for Ann, first in line): the dungeon stays held for her
    runs.runs().forEach(r -> runs.end(r, false, null));
    ticks(1);
    when(module.generate(eq("delve"), anyLong())).thenThrow(new IllegalStateException("no layout"));
    runs.useGate(a, gate);
    assertTrue(runs.runs().isEmpty());
    assertTrue(a.nextMessage() != null);
    assertTrue(runs.queued(a));
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
    runs.runs().forEach(r -> runs.end(r, false, null));
    assertNull(runs.respawnPoint(a));
  }

  @Test
  void aBeatenPartyFails() {
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    rules = new RunRules(1, 3, "", 0, 1, 0.5);
    runs.startRun(a, "delve", 1);
    DungeonRun run = only().run();
    runs.down(a);
    ticks(1);
    assertEquals(DungeonRun.State.RUNNING, run.state(), "Bo is still up");
    runs.down(b);
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
    when(plain.getLocation()).thenReturn(at(middle(room(RoomType.BOSS))));
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
    when(bare.getLocation()).thenReturn(at(middle(0)));
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
    a.teleport(at(middle(room(RoomType.COMBAT))).add(10, 0, 10));
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
  void aClearedRoomWithTwoWaysOnNamesTheNearerDoor() {
    // a layout where the fight room leads on through two doorways (the trunk and a branch)
    DungeonRecipe branchy = new DungeonRecipe("delve", 1, 1, Map.of(RoomType.COMBAT, 1), true, true, 1, 1, 1, 1,
        null, 20, 20000);
    DungeonLayout found = null;
    int fight = -1;
    for (long seed = 0; seed < 200 && found == null; seed++) {
      DungeonLayout l = DungeonGenerator.generate(kit.catalog(), branchy, seed);
      for (int i = 0; i < l.rooms().size(); i++) {
        int from = i;
        if (l.rooms().get(i).node().type() == RoomType.COMBAT
            && l.connections().stream().filter(c -> c.roomA() == from).count() >= 2) {
          found = l;
          fight = i;
        }
      }
    }
    assertNotNull(found);
    layout = found;
    geo = new RunGeometry(layout, 32, 4, 0, 64, 0);
    when(module.generate(eq("delve"), anyLong())).thenReturn(layout);
    placed = CompletableFuture.completedFuture(at(geo.entry(0)));
    PlayerMock a = player("Ann");
    runs.startRun(a, "delve", 1);
    a.teleport(at(middle(fight)));
    ticks(2);
    assertEquals(DungeonRun.Room.ACTIVE, only().run().room(fight));
    killAll();
    ticks(1);
    assertEquals(DungeonRun.Room.CLEARED, only().run().room(fight));
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
    when(bare.getLocation()).thenReturn(at(middle(1)));
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
    b.teleport(at(middle(0)));
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
    a.teleport(at(middle(0)));
    runs.sweep(a);
    assertEquals(room(RoomType.COMBAT), geo.roomAt(a.getLocation().getX(), a.getLocation().getZ()));
    // moved out of the dungeon world by staff: she counts as away, and a sweep there leaves her be
    a.teleport(new Location(main, 5, 70, 5));
    ticks(1);
    assertFalse(run.member(a.getUniqueId()).online());
    runs.sweep(a);
    assertFalse(run.member(a.getUniqueId()).online());
    assertNull(runs.rescuePoint(a), "no dungeon rescue outside the dungeon world");
    a.teleport(at(middle(room(RoomType.COMBAT))));
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
    a.teleport(at(middle(0)));
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

  @Test
  void aFullGateQueuesPartiesAndHoldsTheNextDungeon() {
    settings = new RunSettings(1, 4, 5, 30, 3, 2.5, 8, 300, 60, List.of(), 30, java.util.Set.of(), java.util.Set.of(), 5, 10, 60);
    rules = new RunRules(1, 3, "", 1, 1, 0.5);
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    PlayerMock c = player("Cy");
    PlayerMock d = player("Di");
    runs.startRun(a, "delve", 1);
    // full: Bo, Cy and Di join the line; asking again keeps their place
    runs.startRun(b, "delve", 2);
    runs.startRun(c, "delve", 3);
    runs.startRun(d, "delve", 4);
    runs.startRun(b, "delve", 2);
    assertTrue(runs.queued(b));
    assertTrue(runs.queued(c));
    assertFalse(runs.queued(a));
    // Cy stops waiting; Di moves up
    assertTrue(runs.unqueue(c));
    assertFalse(runs.unqueue(c));
    // Ann's run ends: the dungeon is held for Bo, nobody else may take it
    runs.end(runs.runOf(a), false, null);
    ticks(1);
    assertTrue(runs.queued(b));
    runs.startRun(d, "delve", 4);
    assertTrue(runs.runs().isEmpty(), "held for Bo");
    // Bo is too slow: the hold passes to Di
    now.addAndGet(31_000);
    ticks(1);
    assertFalse(runs.queued(b));
    assertTrue(runs.queued(d));
    runs.startRun(d, "delve", 4);
    assertEquals(1, runs.runs().size());
    assertFalse(runs.queued(d));
    // a leader who logs off leaves the line; a held leader who gives up passes the hold on
    runs.startRun(b, "delve", 2);
    runs.startRun(c, "delve", 3);
    runs.quit(b);
    assertFalse(runs.queued(b));
    c.disconnect();
    PlayerMock e = player("Ed");
    runs.startRun(e, "delve", 5);
    runs.end(runs.runs().getFirst(), false, null);
    ticks(1);
    assertTrue(runs.queued(e), "Cy is offline, so Ed gets the hold");
    // a newcomer may not take a dungeon held for someone else, and waits behind
    PlayerMock ny = player("Ny");
    runs.startRun(ny, "delve", 11);
    assertTrue(runs.queued(ny));
    assertTrue(runs.runs().isEmpty());
    assertTrue(runs.unqueue(e));
    assertFalse(runs.queued(e));
    assertTrue(runs.queued(ny), "the hold passed to Ny");
    assertTrue(runs.unqueue(ny));
    // while every dungeon is busy, the line just waits; a newcomer joins the line even when a
    // dungeon has just freed up, since those already waiting go first
    PlayerMock h = player("Hal");
    runs.startRun(h, "delve", 8);
    assertFalse(runs.queued(h), "a dungeon was free: Hal is playing");
    PlayerMock j = player("Jo");
    runs.startRun(j, "delve", 10);
    ticks(1);
    assertTrue(runs.queued(j));
    // an offline leader in line is skipped when places are announced
    PlayerMock gone = player("Gone");
    runs.startRun(gone, "delve", 9);
    gone.disconnect();
    runs.unqueue(j);
    // Hal's run ends; the next online leader in line (nobody: Gone is offline) gets nothing held
    runs.end(runs.runOf(h), false, null);
    ticks(1);
    // ticks during a hold keep it; a held leader who logs off passes it on
    PlayerMock f = player("Fi");
    runs.startRun(f, "delve", 6);
    runs.startRun(player("Gi"), "delve", 7);
    runs.end(runs.runs().getFirst(), false, null);
    ticks(1);
    ticks(1);
    f.disconnect();
    now.addAndGet(31_000);
    ticks(1);
  }

  @Test
  void aSplitPartyFightsOneRoomAtATime() {
    DungeonRecipe two = new DungeonRecipe("delve", 2, 2, Map.of(RoomType.COMBAT, 1), true, true, 0, 0, 1, 1,
        null, 20, 20000);
    layout = DungeonGenerator.generate(kit.catalog(), two, 4);
    geo = new RunGeometry(layout, 32, 4, 0, 64, 0);
    when(module.generate(eq("delve"), anyLong())).thenReturn(layout);
    placed = CompletableFuture.completedFuture(at(geo.entry(0)));
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    runs.startRun(a, "delve", 1);
    DungeonRun run = only().run();
    List<Integer> fights = new java.util.ArrayList<>();
    for (int i = 0; i < layout.rooms().size(); i++) {
      if (layout.rooms().get(i).node().type() == RoomType.COMBAT) {
        fights.add(i);
      }
    }
    assertTrue(fights.size() >= 2, "the test layout has two fight rooms");
    int first = fights.get(0);
    int second = fights.get(1);
    a.teleport(at(middle(first)));
    b.teleport(at(middle(second)));
    ticks(settings.countdownSeconds() * 2 + 2);
    assertEquals(DungeonRun.Room.ACTIVE, run.room(first));
    assertEquals(DungeonRun.Room.ACTIVE, run.room(second));
    // both are held in the first room, and stay there while it lasts
    ticks(2);
    assertEquals(first, geo.roomAt(b.getLocation().getX(), b.getLocation().getZ()));
    assertEquals(first, geo.roomAt(a.getLocation().getX(), a.getLocation().getZ()));
  }

  @Test
  void fightsHoldEveryoneInside() {
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    runs.startRun(a, "delve", 1);
    DungeonRun run = only().run();
    int fight = room(RoomType.COMBAT);
    // Bo stands in the fight room's doorway when it seals: he is moved inside before the doors close
    into(a, RoomType.COMBAT);
    double[] door = geo.insideDoor(fight, 0);
    var dw = layout.rooms().get(fight).doorways().getFirst();
    double[] edge = switch (dw.side()) {
      case NORTH -> new double[] {door[0], door[1], door[2] - 3};
      case SOUTH -> new double[] {door[0], door[1], door[2] + 3};
      case WEST -> new double[] {door[0] - 3, door[1], door[2]};
      default -> new double[] {door[0] + 3, door[1], door[2]};
    };
    b.teleport(at(edge));
    assertFalse(geo.safelyInside(fight, b.getLocation().getX(), b.getLocation().getZ(), 2));
    ticks(settings.countdownSeconds() * 2 + 2);
    assertEquals(DungeonRun.Room.ACTIVE, run.room(fight));
    assertTrue(geo.safelyInside(fight, b.getLocation().getX(), b.getLocation().getZ(), 2));
    // pushed out (lag, a stray teleport): the next step brings him back, down or not
    b.teleport(at(middle(0)));
    ticks(1);
    assertEquals(fight, geo.roomAt(b.getLocation().getX(), b.getLocation().getZ()));
    // a downed player moved out after going down (inside) is brought back, and so is their downed spot
    runs.down(b);
    b.teleport(at(middle(0)));
    ticks(1);
    assertEquals(fight, geo.roomAt(b.getLocation().getX(), b.getLocation().getZ()));
    assertEquals(fight, geo.roomAt(runs.downedSpot(b).getX(), runs.downedSpot(b).getZ()));
    // someone outside the dungeon world is left where they are
    b.teleport(new Location(main, 1, 70, 1));
    ticks(1);
    assertEquals(main, b.getWorld());
    // a member who is out, or offline, is not pulled in; the room clears with them elsewhere
    b.teleport(at(middle(0)));
    runs.sweep(b);
    run.member(b.getUniqueId()).room(0);
    PlayerMock c = player("Cy");
    run.add(c.getUniqueId(), "char-Cy");
    run.down(c.getUniqueId(), 0);
    run.bleedOut(c.getUniqueId());
    while (run.lives() > 0) {
      run.down(c.getUniqueId(), 0);
      run.bleedOut(c.getUniqueId());
    }
    run.down(c.getUniqueId(), 0);
    run.bleedOut(c.getUniqueId());
    assertEquals(DungeonRun.Status.OUT, run.member(c.getUniqueId()).status());
    c.teleport(at(middle(0)));
    UUID ghost = UUID.randomUUID();
    run.add(ghost, "ghost");
    run.offline(ghost, 0);
    ticks(1);
    assertEquals(0, geo.roomAt(c.getLocation().getX(), c.getLocation().getZ()));
    killAll();
    ticks(1);
    assertEquals(DungeonRun.Room.CLEARED, run.room(fight));
  }

  static boolean lost(PlayerMock p) {
    return drain(p).stream().anyMatch(m -> m.startsWith("§7Lost? The way on is "));
  }

  @Test
  void aPartyThatGetsNowhereIsToldTheWayOnNowAndThen() {
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    when(chars.partyLeader(a)).thenReturn(a.getUniqueId());
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"));
    RunManager.ActiveRun ar = only();
    DungeonRun run = ar.run();
    ticks(1);
    drain(a);
    drain(b);
    now.addAndGet(59_000);
    ticks(1);
    assertFalse(lost(a), "not yet");
    now.addAndGet(2_000);
    ticks(1);
    assertTrue(lost(a));
    assertTrue(lost(b));
    assertNotNull(ar.hintAt.get(a.getUniqueId()));
    ticks(2); // the doorway stays marked for a few seconds
    now.addAndGet(60_000);
    ticks(1);
    assertFalse(lost(a), "the next hint waits twice as long");
    // Ann waits in the boss room (it doesn't wake without Bo); Bo strays out of every room
    a.teleport(at(middle(room(RoomType.BOSS))));
    b.teleport(new Location(dungeon, 5000, 65, 5000));
    now.addAndGet(1_000);
    ticks(1);
    now.addAndGet(61_000);
    ticks(1);
    assertEquals(DungeonRun.Room.IDLE, run.room(room(RoomType.BOSS)));
    assertFalse(lost(a), "she already stands in the room still to do");
    assertFalse(lost(b), "outside every room there is no doorway to name");
    // nobody was shown a way, so the hint is not used up: Bo, back in the start room, gets it next step
    b.teleport(at(geo.entry(0)));
    ticks(1);
    assertTrue(lost(b));
    assertFalse(lost(a));
    // every fight done: the way on is the vault, and only the vault
    for (int i = 0; i < layout.rooms().size(); i++) {
      if (DungeonRun.isFight(layout.rooms().get(i).node().type())) {
        run.clearRoom(i);
      }
    }
    now.addAndGet(119_000);
    ticks(1);
    assertFalse(lost(b), "the second hint since the last progress waits twice as long");
    now.addAndGet(2_000);
    ticks(1);
    assertTrue(lost(a));
    assertTrue(lost(b));
    now.addAndGet(239_000);
    ticks(1);
    assertFalse(lost(a), "then four times as long");
    now.addAndGet(2_000);
    ticks(1);
    assertTrue(lost(a));
    // getting somewhere (a room not seen before) starts it over: the next stall gets a hint after a minute
    now.addAndGet(1_000);
    a.teleport(at(middle(room(RoomType.REST))));
    ticks(1);
    drain(a);
    now.addAndGet(61_000);
    ticks(1);
    assertTrue(lost(a));
  }

  @Test
  void noHintWhenNothingIsLeftToDo() {
    DungeonRecipe noVault = new DungeonRecipe("delve", 0, 0, null, false, false, 0, 0, 1, 1, null, 20, 20000);
    layout = DungeonGenerator.generate(kit.catalog(), noVault, 2);
    geo = new RunGeometry(layout, 32, 4, 0, 64, 0);
    when(module.generate(eq("delve"), anyLong())).thenReturn(layout);
    placed = CompletableFuture.completedFuture(at(geo.entry(0)));
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    when(chars.partyLeader(a)).thenReturn(a.getUniqueId());
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"));
    DungeonRun run = only().run();
    for (int i = 0; i < layout.rooms().size(); i++) {
      if (DungeonRun.isFight(layout.rooms().get(i).node().type())) {
        run.clearRoom(i);
      }
    }
    assertTrue(runs.down(b));
    ticks(1);
    drain(a);
    drain(b);
    now.addAndGet(61_000);
    ticks(1);
    assertFalse(lost(a), "no fight left and no vault");
    assertFalse(lost(b), "a downed player gets no hint");
  }

  static List<String> drain(PlayerMock p) {
    List<String> out = new ArrayList<>();
    for (String m = p.nextMessage(); m != null; m = p.nextMessage()) {
      out.add(m);
    }
    return out;
  }

  @Test
  void aSoloPlayerGetsUpAloneOncePerFight() {
    PlayerMock a = player("Ann");
    runs.startRun(a, "delve", 1);
    DungeonRun run = only().run();
    drain(a);
    // outside a fight: crouching gets them up, with no limit
    assertTrue(runs.down(a));
    assertTrue(drain(a).stream().anyMatch(m -> m.endsWith("crouch for " + settings.selfReviveSeconds()
        + " seconds to get back up.")), "outside a fight there is no once-per-fight limit");
    a.setSneaking(true);
    ticks(settings.selfReviveSeconds() * 2);
    assertFalse(runs.isDowned(a));
    assertTrue(drain(a).contains("§aYou get back up."));
    // in a fight: once, then a shorter wait for a life
    into(a, RoomType.COMBAT);
    ticks(1);
    int fight = room(RoomType.COMBAT);
    assertEquals(DungeonRun.Room.ACTIVE, run.room(fight));
    a.setSneaking(false);
    runs.down(a);
    ticks(3);
    assertTrue(runs.isDowned(a), "lying still waits");
    a.setSneaking(true);
    ticks(settings.selfReviveSeconds() * 2 - 1);
    assertTrue(runs.isDowned(a), "not yet");
    ticks(1);
    assertFalse(runs.isDowned(a));
    assertTrue(drain(a).stream().anyMatch(m -> m.contains("you can't get up on your own")));
    runs.down(a);
    assertTrue(drain(a).stream().anyMatch(m -> m.contains("already got up once")));
    ticks(settings.selfReviveSeconds() * 2 + 2);
    assertTrue(runs.isDowned(a), "crouching does nothing now");
    now.addAndGet(settings.aloneBleedOutSeconds() * 1000L + 1);
    ticks(1);
    assertFalse(runs.isDowned(a));
    assertEquals(0, run.lives(), "a life brought them back");
    assertEquals(DungeonRun.State.RUNNING, run.state());
    // with the get-up spent and no lives left, the next down is the end, and they are told so
    runs.down(a);
    assertTrue(drain(a).stream().anyMatch(m -> m.endsWith("with no lives left.")));
    ticks(1);
    assertEquals(DungeonRun.State.FAILED, run.state());
  }

  /** The middle of a room's footprint box at standing height (the tests' rooms are rectangles). */
  // ------------------------------------------------------------------ finds kept, losses logged, ways out, insurance

  FindStore store() {
    FindStore store = new FindStore(temp.resolve("finds"), Logger.getLogger("t"));
    runs.findStore(store);
    return store;
  }

  Difficulty banking(String id, Difficulty.Banking b, boolean gear) {
    return new Difficulty(id, id, 1, 1, 1, null, "", 0, 0, b, gear, 1);
  }

  @Test
  void findsAreKeptOnDiskAndARunACrashCutOffHandsThemOver() {
    rules = new RunRules(1, 3, "m.key", 3, 1, 0.5, 1, "", 0, 0, 0, 30);
    difficulties = List.of(banking("normal", new Difficulty.Banking(1, true, 1), false));
    FindStore store = store();
    List<String> got = new ArrayList<>();
    runs.rewards(recording(got));
    PlayerMock a = player("Ann");
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"), "normal");
    RunManager.ActiveRun ar = only();
    clearCombat(ar, a);
    assertEquals(2, store.pouch(a.getUniqueId()).banked().size(), "banked, and on disk");
    // the server dies with the run going: the next start hands everything over
    RunManager next = new RunManager(plugin, module, chars, keys, mobs, files, gates, now::get);
    next.findStore(store);
    next.rewards(recording(got));
    var lockers = mock(net.tfminecraft.trialrooms.dungeon.locker.LockerService.class);
    next.lockers(lockers);
    next.start();
    assertNull(store.pouch(a.getUniqueId()));
    a.teleport(new Location(main, 10, 70, 10));
    next.sweep(a);
    assertEquals(List.of("Ann EVENT", "Ann spoils"), got);
    assertTrue(nextMatching(a, "Finds came home: a stop cut your run short."));
    verify(lockers, times(2)).settle(a, false); // (once more after the hand-over: spoils among them come now)
    next.stop();
    // without rewards, nothing is handed over (and nothing taken)
    RunManager bare = new RunManager(plugin, module, chars, keys, mobs, files, gates, now::get);
    bare.findStore(store);
    bare.sweep(a);
  }

  @Test
  void loggingOutCostsWhatAFallWouldAndStaffCanGiveItBack() {
    rules = new RunRules(1, 3, "m.key", 3, 1, 0.5, 1, "", 0, 0, 0, 30);
    difficulties = List.of(banking("nightmare", new Difficulty.Banking(0, false, 0), false));
    FindStore store = store();
    List<String> got = new ArrayList<>();
    runs.rewards(recording(got));
    PlayerMock a = player("Ann");
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"), "nightmare");
    RunManager.ActiveRun ar = only();
    clearCombat(ar, a);
    runs.quit(a, "DISCONNECTED"); // no fight going on: logging out costs nothing
    assertEquals(List.of(), runs.losses(a.getUniqueId()));
    runs.sweep(a);
    into(a, RoomType.BOSS);
    ticks(14);
    assertTrue(ar.run.activeRoom() >= 0, "the boss fight is on");
    Location back = a.getLocation();
    a.teleport(new Location(main, 10, 70, 10));
    runs.quit(a, "DISCONNECTED"); // away from the dungeon: not in the fight, nothing to lose
    assertEquals(List.of(), runs.losses(a.getUniqueId()));
    a.teleport(back);
    runs.sweep(a);
    runs.quit(a, "TIMED_OUT");
    List<FindStore.Loss> losses = runs.losses(a.getUniqueId());
    assertEquals(1, losses.size());
    FindStore.Loss l = losses.get(0);
    assertEquals("logged out in the middle of a fight", l.why());
    assertTrue(l.detail().contains("TIMED_OUT") && l.detail().contains("enemies left in the fight"), l.detail());
    assertEquals(40, l.value());
    assertNull(runs.restoreLoss(a.getUniqueId(), 99));
    assertNotNull(runs.restoreLoss(a.getUniqueId(), l.id()), "in the dungeon: it waits");
    assertEquals(List.of(), got);
    assertNull(runs.restoreLoss(a.getUniqueId(), l.id()), "given back once");
    runs.end(ar, false, null);
    assertEquals(List.of(), got, "nothing else came home: Nightmare keeps nothing of a failed run");
    a.teleport(new Location(main, 10, 70, 10));
    runs.sweep(a);
    assertTrue(got.containsAll(List.of("Ann restore", "Ann EVENT", "Ann spoils")), got.toString());
    assertTrue(nextMatching(a, "staff gave back what you lost"));
    assertTrue(runs.losses(a.getUniqueId()).get(0).restored());
    // logging out while down is a fall
    got.clear();
    runs.useGate(a, gates.add(main.getBlockAt(12, 69, 10), "delve"), "nightmare");
    ar = only();
    clearCombat(ar, a);
    assertTrue(runs.down(a));
    runs.quit(a);
    assertEquals("logged out while down", runs.losses(a.getUniqueId()).get(1).why());
    runs.end(ar, false, null);
    // online outside when staff give it back: handed over at once
    a.teleport(new Location(main, 10, 70, 10));
    runs.sweep(a);
    runs.restoreLoss(a.getUniqueId(), 2);
    assertTrue(got.contains("Ann restore"));
    // no store: no losses
    RunManager bare = new RunManager(plugin, module, chars, keys, mobs, files, gates, now::get);
    assertEquals(List.of(), bare.losses(a.getUniqueId()));
    assertNull(bare.restoreLoss(a.getUniqueId(), 1));
  }

  @Test
  void membersAwayGetWhatComesHomeByTheSameRulesLater() {
    rules = new RunRules(1, 3, "m.key", 3, 1, 0.5, 1, "", 0, 0, 0, 30);
    difficulties = List.of(banking("normal", new Difficulty.Banking(1, true, 1), false));
    store();
    List<String> got = new ArrayList<>();
    runs.rewards(recording(got));
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    when(chars.partyLeader(a)).thenReturn(a.getUniqueId());
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"), "normal");
    RunManager.ActiveRun ar = only();
    clearCombat(ar, a, b);
    b.teleport(new Location(main, 10, 70, 10)); // online, outside
    ticks(1);
    got.clear();
    runs.end(ar, false, null);
    assertTrue(got.containsAll(List.of("Bo EVENT", "Bo spoils")), got.toString());
    assertTrue(nextMatching(b, "you were away when the run ended"));
    // away too long: as if they had left (and handed over when they are back)
    got.clear();
    runs.useGate(a, gates.add(main.getBlockAt(12, 69, 10), "delve"), "normal");
    ar = only();
    clearCombat(ar, a, b);
    runs.quit(b);
    now.addAndGet(settings.graceSeconds() * 1000L + 1);
    ticks(1);
    assertFalse(got.contains("Bo EVENT"), "not handed over while offline");
    runs.sweep(b);
    assertTrue(got.contains("Bo EVENT"), got.toString());
    assertTrue(nextMatching(b, "you were away too long and left the run"));
    runs.runs().forEach(r -> runs.end(r, false, null));
  }

  @Test
  void treasureRoomsBankAndSomeHoldAWayOut() {
    rules = new RunRules(1, 3, "m.key", 3, 1, 0.5, 1, "", 0, 0, 0, 30);
    difficulties = List.of(banking("hard", new Difficulty.Banking(0, false, 0.5, true, 1), false));
    FindStore store = store();
    List<String> got = new ArrayList<>();
    runs.rewards(recording(got));
    runs.exitRoll = () -> 0;
    PlayerMock a = player("Ann");
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"), "hard");
    RunManager.ActiveRun ar = only();
    int t = room(RoomType.TREASURE);
    assertEquals(java.util.Set.of(t), ar.exits);
    clearCombat(ar, a);
    runs.openExit(a);
    assertTrue(nextMatching(a, "Stand by a way out in a treasure room"), "not shown yet");
    into(a, RoomType.TREASURE);
    ticks(2);
    assertTrue(nextMatching(a, "A way out glimmers"));
    assertEquals(2, store.pouch(a.getUniqueId()).banked().size(), "the treasure room banked");
    double[] c = ar.geometry().markers(t, "chest").get(0);
    var exit = mock(org.bukkit.block.Block.class);
    when(exit.getX()).thenReturn((int) Math.floor(c[0]));
    when(exit.getY()).thenReturn((int) Math.floor(c[1]));
    when(exit.getZ()).thenReturn((int) Math.floor(c[2]));
    var other = mock(org.bukkit.block.Block.class);
    when(other.getX()).thenReturn((int) Math.floor(c[0]));
    when(other.getY()).thenReturn((int) Math.floor(c[1]) + 1);
    when(other.getZ()).thenReturn((int) Math.floor(c[2]));
    assertFalse(runs.propClick(a, other));
    when(other.getY()).thenReturn((int) Math.floor(c[1]));
    when(other.getZ()).thenReturn((int) Math.floor(c[2]) + 1);
    assertFalse(runs.propClick(a, other));
    when(other.getX()).thenReturn((int) Math.floor(c[0]) + 1);
    assertFalse(runs.propClick(a, other));
    assertTrue(runs.propClick(a, exit));
    assertInstanceOf(FindsMenu.class, a.getOpenInventory().getTopInventory().getHolder());
    RunFinds.Offer o = runs.exitOffer(a);
    assertEquals(List.of(), o.safe(), "Hard: banked finds are at risk too");
    assertEquals(2, o.atRisk().size());
    assertEquals(30, o.allowance(), "the best find is worth more than half");
    assertTrue(runs.down(a));
    assertEquals("§cOnly someone on their feet can take the way out.", runs.exitWith(a, List.of(1)));
    assertNull(runs.exitOffer(a));
    ar.run.addRevive(a.getUniqueId(), 60_000, 1);
    assertEquals("§cThose finds are worth more than the way out lets you take.", runs.exitWith(a, List.of(0, 1)));
    assertNull(runs.exitWith(a, List.of(1)));
    assertEquals(List.of("Ann spoils"), got);
    assertTrue(nextMatching(a, "You take the way out."));
    FindStore.Loss lost = runs.losses(a.getUniqueId()).get(0);
    assertEquals("left through a way out (finds not taken)", lost.why());
    assertEquals(10, lost.value());
    ticks(1);
    assertTrue(runs.runs().isEmpty(), "the party (Ann alone) left");
    assertEquals("§cThere is no way out for you here.", runs.exitWith(a, List.of()));
    runs.openExit(a);
    assertTrue(nextMatching(a, "There is no way out for you here."));
    // Normal: banked finds come home on top of the picks; nothing picked, nothing held
    got.clear();
    difficulties = List.of(banking("normal", new Difficulty.Banking(0, false, 1, true, 1), false));
    runs.useGate(a, gates.add(main.getBlockAt(12, 69, 10), "delve"), "normal");
    ar = only();
    clearCombat(ar, a);
    into(a, RoomType.TREASURE);
    ticks(2);
    assertTrue(nextMatching(a, "its cache keeps"));
    into(a, RoomType.COMBAT);
    ticks(1);
    into(a, RoomType.TREASURE);
    ticks(1);
    assertFalse(nextMatching(a, "its cache keeps"), "each treasure room banks once");
    o = runs.exitOffer(a);
    assertEquals(2, o.safe().size());
    assertEquals(0, o.allowance());
    assertNull(runs.exitWith(a, List.of()));
    assertEquals(List.of("Ann EVENT", "Ann spoils"), got);
    assertTrue(nextMatching(a, "You take 2 finds home."));
    ticks(1);
    // a difficulty that doesn't bank finds has no way out
    difficulties = List.of(new Difficulty("plain", "Plain", 1, 1, 1, null, ""));
    runs.useGate(a, gates.add(main.getBlockAt(14, 69, 10), "delve"), "plain");
    assertTrue(only().exits.isEmpty());
    into(a, RoomType.TREASURE);
    ticks(2);
    assertNull(runs.exitOffer(a));
    runs.openFinds(a);
    assertTrue(nextMatching(a, "You hold no finds"));
    runs.runs().forEach(r -> runs.end(r, false, null));
  }

  @Test
  void theFindsScreenOpensInABankingRun() {
    difficulties = List.of(banking("normal", new Difficulty.Banking(1, true, 1), false));
    PlayerMock a = player("Ann");
    runs.openFinds(a);
    assertTrue(nextMatching(a, "You hold no finds"));
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"), "normal");
    runs.openFinds(a);
    assertInstanceOf(FindsMenu.class, a.getOpenInventory().getTopInventory().getHolder());
    assertEquals(difficulties.get(0).lootRules(), runs.lootRules(a));
    assertEquals(org.bukkit.Material.PAPER, runs.icon(RunRewards.Find.of(RunRewards.Moment.EVENT)).getType());
    runs.rewards(recording(new ArrayList<>()));
    assertEquals(org.bukkit.Material.BUNDLE, runs.icon(RunRewards.Find.spoils(1)).getType());
    assertEquals(-1, runs.marksOf(a));
    runs.rewards(null);
    assertEquals(-1, runs.marksOf(a));
    runs.runs().forEach(r -> runs.end(r, false, null));
    assertEquals(List.of(), runs.lootRules(a));
    difficulties = List.of(new Difficulty("plain", "Plain", 1, 1, 1, null, ""));
    runs.useGate(a, gates.add(main.getBlockAt(12, 69, 10), "delve"), "plain");
    assertEquals(List.of(), runs.lootRules(a));
    runs.runs().forEach(r -> runs.end(r, false, null));
  }

  @Test
  void mythicInsuranceSparesOneWornPiece() {
    settings = withLobby(60);
    difficulties = List.of(banking("mythic", new Difficulty.Banking(0, false, 0), true));
    var lockers = mock(net.tfminecraft.trialrooms.dungeon.locker.LockerService.class);
    runs.lockers(lockers);
    var locker = new net.tfminecraft.trialrooms.dungeon.locker.Locker();
    locker.put("x", new byte[] {1});
    locker.put("y", new byte[] {2});
    locker.assign(0, 39, "x");
    locker.assign(0, 38, "y");
    locker.wear(0, Map.of(), 1);
    PlayerMock a = player("Ann");
    when(lockers.locker(a)).thenReturn(locker);
    when(lockers.show(locker, "x")).thenReturn(new org.bukkit.inventory.ItemStack(org.bukkit.Material.IRON_HELMET));
    when(lockers.show(locker, "y")).thenReturn(new org.bukkit.inventory.ItemStack(org.bukkit.Material.IRON_CHESTPLATE));
    int[] marks = {100};
    runs.openInsurance(a);
    assertTrue(nextMatching(a, "Insurance is bought in the lobby"));
    assertTrue(runs.insure(a, "x").startsWith("§cInsurance is bought"), "in no run");
    assertEquals(List.of(), runs.insurable(a), "no rewards: nothing to pay with");
    runs.rewards(new RunRewards() {
      @Override
      public void reward(org.bukkit.entity.Player p, String recipe, int tier, Moment moment, UUID run) {
      }

      @Override
      public int value(org.bukkit.inventory.ItemStack item) {
        return 50;
      }

      @Override
      public int marks(org.bukkit.entity.Player p) {
        return marks[0];
      }

      @Override
      public boolean spend(org.bukkit.entity.Player p, int n) {
        if (marks[0] < n) {
          return false;
        }
        marks[0] -= n;
        return true;
      }

      @Override
      public int insurance(int value) {
        return value / 5;
      }
    });
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"), "mythic");
    assertTrue(nextMatching(a, "[Insure a piece]"));
    runs.openInsurance(a);
    assertInstanceOf(FindsMenu.class, a.getOpenInventory().getTopInventory().getHolder());
    assertEquals(2, runs.insurable(a).size());
    assertEquals(10, runs.insurable(a).get(0).cost());
    assertEquals("§aInsured for 10 marks: if you fall, this piece is spared.", runs.insure(a, "x"));
    assertEquals(90, marks[0]);
    runs.insure(a, "y"); // one piece: the helmet is let go
    assertEquals(90, marks[0]);
    assertEquals("y", runs.insured(a));
    assertEquals("§7Insurance cancelled: your marks are back.", runs.insure(a, "y"));
    assertEquals(100, marks[0]);
    assertEquals("§cWear that piece first: put on its loadout in the locker.", runs.insure(a, "z"));
    marks[0] = 5;
    assertEquals("§cInsuring it costs 10 marks; you have 5.", runs.insure(a, "x"));
    marks[0] = 100;
    runs.insure(a, "x");
    runs.ready(a);
    ticks(1); // the doors open: still worn, still insured
    assertEquals("x", runs.insured(a));
    assertEquals("§cInsurance is bought in the lobby of a run where your worn gear is at stake.", runs.insure(a, "y"));
    runs.openInsurance(a);
    assertTrue(nextMatching(a, "Insurance is bought in the lobby"));
    into(a, RoomType.COMBAT);
    ticks(12);
    assertTrue(runs.down(a));
    now.addAndGet(settings.bleedOutSeconds() * 1000L + 1);
    ticks(1);
    verify(lockers).forfeitWorn(a, "x");
    assertTrue(nextMatching(a, "Your insured piece was spared"));
    runs.runs().forEach(r -> runs.end(r, false, null));
    assertNull(runs.insured(a));
    assertEquals(90, marks[0], "insurance that was used stays paid");
    marks[0] = 100;
    // leaving before the doors open gives the marks back; so does a run that ends there
    runs.useGate(a, gates.add(main.getBlockAt(12, 69, 10), "delve"), "mythic");
    runs.insure(a, "x");
    assertEquals(90, marks[0]);
    runs.leave(a);
    assertEquals(100, marks[0]);
    runs.useGate(a, gates.add(main.getBlockAt(14, 69, 10), "delve"), "mythic");
    runs.insure(a, "x");
    runs.runs().forEach(r -> runs.end(r, false, null));
    assertEquals(100, marks[0]);
    // the insured piece taken off before the doors open: refunded
    runs.useGate(a, gates.add(main.getBlockAt(16, 69, 10), "delve"), "mythic");
    runs.insure(a, "x");
    locker.takeOff();
    runs.ready(a);
    ticks(1);
    assertEquals(100, marks[0]);
    assertTrue(nextMatching(a, "Your insurance was refunded"));
    assertEquals(List.of(), runs.insurable(a), "nothing worn");
    runs.runs().forEach(r -> runs.end(r, false, null));
    when(lockers.locker(a)).thenReturn(null);
    assertEquals(List.of(), runs.insurable(a));
    runs.lockers(null);
    assertEquals(List.of(), runs.insurable(a));
    difficulties = List.of();
  }

  @Test
  void findsThatComeToNothingAreNotCarried() {
    rules = new RunRules(1, 3, "m.key", 3, 1, 0.5, 1, "", 0, 0, 0, 30);
    difficulties = List.of(banking("normal", new Difficulty.Banking(0, false, 1), false));
    runs.exitRoll = () -> 1;
    runs.rewards(new RunRewards() {
      @Override
      public void reward(org.bukkit.entity.Player p, String recipe, int tier, Moment moment, UUID run) {
      }

      @Override
      public Find find(org.bukkit.entity.Player p, String recipe, int tier, Moment moment, UUID run, double boost) {
        return null;
      }

      @Override
      public Find spoilsFind(org.bukkit.entity.Player p, String recipe, double hours, UUID run) {
        return null;
      }
    });
    PlayerMock a = player("Ann");
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"), "normal");
    RunManager.ActiveRun ar = only();
    clearCombat(ar, a); // (an event and spoils, both rolled to nothing)
    assertEquals(List.of(), runs.heldBy(a).carried);
    into(a, RoomType.TREASURE); // a banking run's treasure room without a way out
    ticks(1);
    assertTrue(ar.exitsShown.isEmpty());
    assertFalse(nextMatching(a, "You carry a find"));
    runs.runs().forEach(r -> runs.end(r, false, null));
  }

  @Test
  void awayMembersAreSettledByTheRunsRules() {
    rules = new RunRules(1, 3, "m.key", 3, 1, 0.5, 1, "", 0, 0, 0, 30);
    difficulties = List.of(banking("normal", new Difficulty.Banking(1, true, 1), false),
        banking("nightmare", new Difficulty.Banking(0, false, 0), false));
    List<String> got = new ArrayList<>();
    runs.rewards(recording(got));
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    when(chars.partyLeader(a)).thenReturn(a.getUniqueId());
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    // no store: nothing can wait for them
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"), "normal");
    RunManager.ActiveRun ar = only();
    clearCombat(ar, a, b);
    b.teleport(new Location(main, 10, 70, 10));
    got.clear();
    runs.end(ar, false, null);
    assertFalse(got.contains("Bo EVENT"));
    FindStore store = store();
    // Nightmare: nothing comes home for someone away, and it is logged
    runs.useGate(a, gates.add(main.getBlockAt(12, 69, 10), "delve"), "nightmare");
    ar = only();
    clearCombat(ar, a, b);
    b.teleport(new Location(main, 10, 70, 10));
    runs.end(ar, false, null);
    assertEquals("you were away when the run ended", runs.losses(b.getUniqueId()).get(0).why());
    assertEquals(List.of(), store.takePending(b.getUniqueId(), "char-Bo"));
    // a stop: everything comes home for someone away, carried too
    got.clear();
    runs.useGate(a, gates.add(main.getBlockAt(14, 69, 10), "delve"), "nightmare");
    ar = only();
    clearCombat(ar, a, b);
    b.teleport(new Location(main, 10, 70, 10));
    runs.stop();
    assertTrue(got.containsAll(List.of("Bo EVENT", "Bo spoils")), got.toString());
    assertTrue(nextMatching(b, "a stop cut your run short"));
    // away too long after the win: banked finds come home in full
    got.clear();
    settings = new RunSettings(4, 4, 5, 30, 3, 2.5, 8, 1, 60, List.of(), 60, java.util.Set.of(), java.util.Set.of(),
        5, 10, 60);
    runs.useGate(a, gates.add(main.getBlockAt(16, 69, 10), "delve"), "normal");
    ar = only();
    clearCombat(ar, a, b);
    runs.quit(b);
    into(a, RoomType.BOSS);
    ticks(14);
    killAll();
    ticks(4);
    into(a, RoomType.VAULT);
    ticks(2);
    assertEquals(DungeonRun.State.COMPLETE, ar.run.state());
    now.addAndGet(2000);
    ticks(1);
    runs.sweep(b);
    assertTrue(got.containsAll(List.of("Bo EVENT", "Bo spoils")), got.toString());
    runs.runs().forEach(r -> runs.end(r, false, null));
  }

  @Test
  void thereIsNoWayOutAfterTheWinOrFromOutside() {
    rules = new RunRules(1, 3, "m.key", 3, 1, 0.5, 1, "", 0, 0, 0, 30);
    difficulties = List.of(banking("hard", new Difficulty.Banking(0, false, 0.5, false, 1), false));
    store();
    List<String> got = new ArrayList<>();
    runs.rewards(recording(got));
    runs.exitRoll = () -> 0;
    PlayerMock a = player("Ann");
    PlayerMock c = player("Cy");
    when(chars.partyLeader(c)).thenReturn(c.getUniqueId());
    when(chars.partyMembers(c)).thenReturn(List.of(c.getUniqueId(), a.getUniqueId()));
    runs.useGate(c, gates.add(main.getBlockAt(10, 69, 10), "delve"), "hard");
    RunManager.ActiveRun ar = only();
    clearCombat(ar, a, c);
    into(c, RoomType.TREASURE);
    into(a, RoomType.TREASURE);
    ticks(2);
    assertFalse(nextMatching(c, "its cache keeps"), "Hard here banks nowhere in treasure rooms");
    into(c, RoomType.COMBAT);
    ticks(1);
    into(c, RoomType.TREASURE);
    ticks(1);
    assertFalse(nextMatching(c, "A way out glimmers"), "shown once");
    // the exit fails to pay one of Cy's finds: Cy hears so, and leaves all the same
    assertNull(runs.exitWith(c, List.of(0)));
    assertTrue(nextMatching(c, "Some of your finds could not be paid"));
    // Ann takes nothing: nothing to say
    a.teleport(new Location(main, 10, 70, 10));
    assertNull(runs.exitOffer(a), "from outside the dungeon");
    assertNull(runs.heldBy(a));
    into(a, RoomType.TREASURE);
    runs.sweep(a);
    assertNull(runs.exitWith(a, List.of()));
    assertFalse(nextMatching(a, "finds home"));
    ticks(1);
    // after the win there is no way out (the vault's exit is the way)
    runs.useGate(c, gates.add(main.getBlockAt(12, 69, 10), "delve"), "hard");
    ar = only();
    into(c, RoomType.TREASURE);
    into(c, RoomType.BOSS);
    ticks(14);
    killAll();
    ticks(4);
    into(c, RoomType.VAULT);
    ticks(2);
    into(c, RoomType.TREASURE);
    assertNull(runs.exitOffer(c));
    runs.runs().forEach(r -> runs.end(r, false, null));
    // a run without difficulties has no loot rules; a run without gear at stake has no insurance
    difficulties = List.of();
    settings = withLobby(60);
    runs.startRun(c, "delve", 4);
    assertEquals(List.of(), runs.lootRules(c));
    runs.openInsurance(c);
    assertTrue(nextMatching(c, "Insurance is bought in the lobby"));
    assertTrue(runs.insure(c, "x").startsWith("§cInsurance is bought"));
    runs.runs().forEach(r -> runs.end(r, false, null));
  }

  @Test
  void lossesOfStakedGearAreLoggedAndGivenBack() {
    difficulties = List.of(banking("mythic", new Difficulty.Banking(0, false, 0), true));
    FindStore store = store();
    List<String> got = new ArrayList<>();
    runs.rewards(recording(got));
    var lockers = mock(net.tfminecraft.trialrooms.dungeon.locker.LockerService.class);
    runs.lockers(lockers);
    PlayerMock c = player("Cy");
    when(lockers.forfeitWorn(c, null)).thenReturn(List.of(new byte[] {4}));
    runs.useGate(c, gates.add(main.getBlockAt(10, 69, 10), "delve"), "mythic");
    RunManager.ActiveRun ar = only();
    into(c, RoomType.COMBAT);
    ticks(12);
    c.teleport(new Location(dungeon, -5000, 65, -5000)); // somewhere between rooms
    assertTrue(runs.down(c));
    runs.quit(c, "KICKED");
    FindStore.Loss l = runs.losses(c.getUniqueId()).get(0);
    assertEquals("logged out while down", l.why());
    assertTrue(l.detail().contains("KICKED") && l.detail().contains("between rooms"), l.detail());
    assertEquals(1, l.gear().size());
    runs.end(ar, false, null);
    // given back: the gear waits for a locker that can take it
    runs.lockers(null);
    runs.restoreLoss(c.getUniqueId(), l.id());
    c.teleport(new Location(main, 10, 70, 10));
    runs.sweep(c);
    assertTrue(nextMatching(c, "could not be handed over yet"));
    runs.lockers(lockers);
    runs.sweep(c);
    assertTrue(nextMatching(c, "could not be handed over yet"), "the locker can't take it yet");
    when(lockers.restore(eq(c), any())).thenReturn(true);
    runs.sweep(c);
    assertTrue(nextMatching(c, "Finds came home: staff gave back what you lost."));
    verify(lockers, times(2)).restore(eq(c), any());
    // an owed find that can't be handed over waits too
    store.addPending(c.getUniqueId(), new FindStore.Pending("char-Cy", "delve", UUID.randomUUID(), 1, 1, false, "x",
        List.of(RunRewards.Find.of(RunRewards.Moment.EVENT)), List.of()));
    runs.sweep(c);
    assertTrue(nextMatching(c, "could not be handed over yet"), "Cy's event finds break in these tests");
    assertEquals(1, store.takePending(c.getUniqueId(), "char-Cy").size());
    // an offline player's loss given back waits for them
    UUID gone = UUID.randomUUID();
    int id = store.logLoss(gone, l);
    assertNotNull(runs.restoreLoss(gone, id));
    difficulties = List.of();
  }

  @Test
  void insuranceOnALockerThatCantBeReadIsRefunded() {
    settings = withLobby(60);
    difficulties = List.of(banking("mythic", new Difficulty.Banking(0, false, 0), true));
    var lockers = mock(net.tfminecraft.trialrooms.dungeon.locker.LockerService.class);
    runs.lockers(lockers);
    var locker = new net.tfminecraft.trialrooms.dungeon.locker.Locker();
    locker.put("x", new byte[] {1});
    locker.assign(0, 39, "x");
    locker.wear(0, Map.of(), 1);
    PlayerMock a = player("Ann");
    PlayerMock b = player("Bo");
    when(chars.partyLeader(a)).thenReturn(a.getUniqueId());
    when(chars.partyMembers(a)).thenReturn(List.of(a.getUniqueId(), b.getUniqueId()));
    when(lockers.locker(a)).thenReturn(locker);
    when(lockers.show(locker, "x")).thenReturn(new org.bukkit.inventory.ItemStack(org.bukkit.Material.IRON_HELMET));
    int[] marks = {100};
    runs.rewards(new RunRewards() {
      @Override
      public void reward(org.bukkit.entity.Player p, String recipe, int tier, Moment moment, UUID run) {
      }

      @Override
      public boolean spend(org.bukkit.entity.Player p, int n) {
        marks[0] -= n;
        return true;
      }
    });
    runs.useGate(a, gates.add(main.getBlockAt(10, 69, 10), "delve"), "mythic");
    runs.insure(a, "x");
    assertEquals(99, marks[0], "at least a mark");
    when(lockers.locker(a)).thenReturn(null);
    runs.ready(a);
    runs.ready(b);
    ticks(1);
    assertEquals(100, marks[0]);
    runs.runs().forEach(r -> runs.end(r, false, null));
    difficulties = List.of();
  }

  double[] middle(int room) {
    var r = layout.rooms().get(room);
    return new double[] {(r.originX() + r.widthCells() / 2.0) * 32, 65, (r.originZ() + r.depthCells() / 2.0) * 32};
  }
}
