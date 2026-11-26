package net.tfminecraft.trialrooms.dungeon.run;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.tfminecraft.trialrooms.dungeon.generator.DungeonGenerator;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonRecipe;
import net.tfminecraft.trialrooms.dungeon.generator.GreyboxKitTest;
import net.tfminecraft.trialrooms.dungeon.load.DungeonKit;
import net.tfminecraft.trialrooms.dungeon.locker.LockerService;
import net.tfminecraft.trialrooms.dungeon.run.RunManager.ActiveRun;
import net.tfminecraft.trialrooms.dungeon.supply.Supply;
import net.tfminecraft.trialrooms.dungeon.supply.SupplyItems;
import net.tfminecraft.trialrooms.dungeon.supply.SupplySettings;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;
import net.tfminecraft.trialrooms.dungeon.world.LayoutPlacer;

class SuppliesTest {

  ServerMock server;
  World world;
  DungeonKit kit;
  DungeonLayout layout;
  RunGeometry geo;
  RunManager runs;
  LockerService lockers;
  AtomicLong now = new AtomicLong(1_000_000);
  Deque<Double> rolls = new ArrayDeque<>();
  SupplySettings settings;
  Supplies supplies;
  DungeonRun run;
  ActiveRun ar;
  List<PlayerMock> online = new ArrayList<>();
  PlayerMock ann;
  PlayerMock bob;

  static Supply supply(String id, Supply.Use use, double amount, int cooldown, List<String> kits) {
    return new Supply(id, "S " + id, new Supply.Look("SLIME_BALL", 0, null), 3, 1, use, amount, 2, cooldown, 4,
        Set.of("slowness"), Set.of("DS_Ice_Prison"), kits);
  }

  SupplySettings settings(Map<Integer, Double> campfire, double fight, double elite, double treasure, Supply... items) {
    Map<String, Supply> m = new LinkedHashMap<>();
    for (Supply s : items) {
      m.put(s.id(), s);
    }
    return new SupplySettings(1, 4, new TreeMap<>(campfire), 4, m, fight, elite, treasure);
  }

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    world = server.addSimpleWorld("dungeon");
    kit = GreyboxKitTest.kit();
    DungeonRecipe recipe = new DungeonRecipe("delve", 1, 1, Map.of(RoomType.COMBAT, 1), true, true, 1, 1, 1, 1,
        null, 20, 20000);
    layout = DungeonGenerator.generate(kit.catalog(), recipe, 4);
    geo = new RunGeometry(layout, 32, 4, 0, 64, 0);
    runs = mock(RunManager.class);
    lockers = mock(LockerService.class);
    when(runs.lockers()).thenReturn(lockers);
    when(runs.inDungeonWorld(any())).thenReturn(true);
    when(runs.online(any())).thenAnswer(i -> List.copyOf(online));
    settings = settings(Map.of(1, 1.0, 2, 0.5), 0.5, 0.5, 0.5,
        supply("potion", Supply.Use.HEALTH, 0.4, 20, List.of()),
        supply("mana", Supply.Use.MANA, 0.5, 0, List.of()),
        supply("bandage", Supply.Use.BANDAGE, 0.4, 0, List.of()),
        supply("salts", Supply.Use.REVIVE, 0, 0, List.of()),
        supply("draught", Supply.Use.WARD, 0, 0, List.of("elsewhere")));
    supplies = new Supplies(runs, new Bars(), now::get, () -> rolls.isEmpty() ? 0.0 : rolls.pop(), () -> settings);
    ar = activeRun(geo, 1);
    ann = player("Ann");
    bob = player("Bob");
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  ActiveRun activeRun(RunGeometry g, int tier) {
    run = new DungeonRun(UUID.randomUUID(), "delve", layout, 1, now.get());
    RunRules rules = new RunRules(1, 3, "m.key", 1, 1, 0.5, tier, null, 0, 0, 0, 0, 0);
    ActiveRun a = new ActiveRun(run, g, mock(LayoutPlacer.class), kit, rules, UUID.randomUUID(), "m.key",
        new Location(world, 0, 64, 0));
    when(runs.runOf(any())).thenReturn(a);
    for (PlayerMock p : online) {
      run.add(p.getUniqueId(), "c1");
    }
    return a;
  }

  PlayerMock player(String name) {
    PlayerMock p = server.addPlayer(name);
    p.teleport(new Location(world, 0, 64, 0));
    run.add(p.getUniqueId(), "c1");
    online.add(p);
    return p;
  }

  int room(RoomType type) {
    for (int i = 0; i < layout.rooms().size(); i++) {
      if (layout.rooms().get(i).node().type() == type) {
        return i;
      }
    }
    throw new AssertionError(type);
  }

  void at(PlayerMock p, double[] spot) {
    p.teleport(new Location(world, spot[0], spot[1], spot[2]));
  }

  static String last(PlayerMock p) {
    String last = null;
    for (String m = p.nextMessage(); m != null; m = p.nextMessage()) {
      last = m;
    }
    return last;
  }

  ItemStack item(String id, int n) {
    return SupplyItems.make(settings.supply(id), n);
  }

  @Test
  void theDoorsHandOutEachPouchOnce() {
    PlayerMock cid = player("Cid");
    when(lockers.handOut(eq(ann), any())).thenAnswer(i -> {
      Function<String, Supply> catalog = i.getArgument(1);
      assertNotNull(catalog.apply("potion"));
      assertNull(catalog.apply("draught"), "not for this kit");
      assertNull(catalog.apply("nothing"));
      return 4;
    });
    when(lockers.handOut(eq(bob), any())).thenReturn(0);
    when(lockers.handOut(eq(cid), any())).thenReturn(-1);
    supplies.handOut(ar, List.of(ann, bob, cid));
    assertTrue(ann.nextMessage().contains("4 supplies"));
    assertTrue(bob.nextMessage().contains("pouch is empty"));
    assertTrue(cid.nextMessage().contains("could not be taken"));
    supplies.handOut(ar, List.of(ann));
    verify(lockers, times(1)).handOut(eq(ann), any());
    // no lockers, or no supplies at all: nothing
    when(runs.lockers()).thenReturn(null);
    supplies.handOut(activeRun(geo, 1), List.of(ann));
    when(runs.lockers()).thenReturn(lockers);
    settings = settings(Map.of(1, 1.0), 0, 0, 0);
    supplies.handOut(activeRun(geo, 1), List.of(ann));
    verify(lockers, times(1)).handOut(eq(ann), any());
  }

  @Test
  void suppliesWorkOnlyInARunAndWaitOutTheirCooldown() {
    ItemStack stray = new ItemStack(Material.SLIME_BALL);
    supplies.use(ann, stray);
    assertTrue(ann.nextMessage().contains("no longer used"));
    ItemStack potion = item("potion", 3);
    when(runs.runOf(ann)).thenReturn(null);
    supplies.use(ann, potion);
    assertTrue(ann.nextMessage().contains("only work in a dungeon run"));
    when(runs.runOf(ann)).thenReturn(ar);
    when(runs.inDungeonWorld(ann)).thenReturn(false);
    supplies.use(ann, potion);
    assertTrue(ann.nextMessage().contains("only work in a dungeon run"));
    when(runs.inDungeonWorld(ann)).thenReturn(true);
    run.state(DungeonRun.State.ENDED);
    supplies.use(ann, potion);
    assertTrue(ann.nextMessage().contains("only work in a dungeon run"));
    run.state(DungeonRun.State.RUNNING);
    run.down(ann.getUniqueId(), now.get());
    supplies.use(ann, potion);
    assertTrue(ann.nextMessage().contains("can't use supplies now"));
    run.addRevive(ann.getUniqueId(), 1, 1);
    supplies.use(ann, potion);
    assertTrue(ann.nextMessage().contains("not hurt"));
    assertEquals(3, potion.getAmount());
    ann.setHealth(5);
    supplies.use(ann, potion);
    assertEquals(13, ann.getHealth(), 1e-9);
    assertEquals(2, potion.getAmount());
    supplies.use(ann, potion);
    assertTrue(ann.nextMessage().contains("Wait 20s"));
    now.addAndGet(20_000);
    supplies.use(ann, potion);
    assertEquals(20, ann.getHealth(), 1e-9, "never past the max");
  }

  @Test
  void manaComesFromMmoCore() {
    ItemStack mana = item("mana", 2);
    supplies.use(ann, mana);
    assertTrue(ann.nextMessage().contains("no mana"));
    Ports.Mana port = mock(Ports.Mana.class);
    when(port.max(ann)).thenReturn(40.0);
    when(port.mana(ann)).thenReturn(40.0, 10.0);
    supplies.mana = port;
    supplies.use(ann, mana);
    assertTrue(ann.nextMessage().contains("mana is full"));
    supplies.use(ann, mana);
    verify(port).add(ann, 20.0);
    assertEquals(1, mana.getAmount());
  }

  @Test
  void aBandageHealsOutOfFightsUntilABlowLands() {
    ItemStack bandage = item("bandage", 3);
    int fight = room(RoomType.COMBAT);
    run.member(ann.getUniqueId()).room(fight);
    run.member(bob.getUniqueId()).room(fight);
    run.stepRooms(0, 0);
    assertEquals(DungeonRun.Room.ACTIVE, run.room(fight));
    supplies.use(ann, bandage);
    assertTrue(ann.nextMessage().contains("no time to bind"));
    run.member(ann.getUniqueId()).room(-1);
    supplies.use(ann, bandage);
    assertTrue(ann.nextMessage().contains("not hurt"));
    run.clearRoom(fight);
    run.member(ann.getUniqueId()).room(fight);
    ann.setHealth(10);
    supplies.use(ann, bandage);
    assertTrue(ann.nextMessage().contains("bind your wounds"));
    supplies.use(ann, bandage); // (a held right-click repeats the use: it must not eat the stack)
    assertTrue(ann.nextMessage().contains("already at work"));
    supplies.step(ar, now.get()); // 2 s = 4 steps of 2 health
    assertEquals(12, ann.getHealth(), 1e-9);
    supplies.hurt(ann);
    assertTrue(ann.nextMessage().contains("tears your bandage"));
    supplies.hurt(ann); // none at work: nothing
    assertNull(ann.nextMessage());
    supplies.step(ar, now.get());
    assertEquals(12, ann.getHealth(), 1e-9);
    // runs out on time, and stops when its wearer goes down
    supplies.use(ann, bandage);
    now.addAndGet(2_000);
    supplies.step(ar, now.get());
    assertEquals(12, ann.getHealth(), 1e-9);
    supplies.use(ann, bandage);
    run.down(ann.getUniqueId(), now.get());
    supplies.step(ar, now.get());
    assertEquals(12, ann.getHealth(), 1e-9);
  }

  @Test
  void smellingSaltsReviveTheNearestDownedAlly() {
    ItemStack salts = item("salts", 2);
    supplies.use(ann, salts);
    assertTrue(ann.nextMessage().contains("No downed ally within 4"));
    PlayerMock cid = player("Cid");
    PlayerMock far = player("Far");
    PlayerMock away = player("Away");
    away.teleport(new Location(server.addSimpleWorld("elsewhere"), 0, 64, 0));
    bob.teleport(new Location(world, 3, 64, 0));
    cid.teleport(new Location(world, 1, 64, 0));
    far.teleport(new Location(world, 30, 64, 0));
    for (PlayerMock p : List.of(bob, cid, far, away, ann)) {
      run.down(p.getUniqueId(), now.get());
    }
    run.addRevive(ann.getUniqueId(), 1, 1);
    when(runs.reviveNow(eq(ar), eq(ann), any(), eq("S salts"))).thenReturn(true);
    supplies.use(ann, salts);
    verify(runs).reviveNow(ar, ann, cid, "S salts");
    assertEquals(1, salts.getAmount());
  }

  @Test
  void aWardStopsOneSlowOrFreeze() {
    assertFalse(supplies.wardsPotion(ann, "SLOWNESS"), "none drunk");
    ItemStack draught = item("draught", 2);
    supplies.use(ann, draught);
    assertTrue(ann.nextMessage().contains("You drink the S draught"));
    supplies.use(ann, draught);
    assertTrue(ann.nextMessage().contains("already warded"));
    assertFalse(supplies.wardsPotion(ann, "poison"));
    assertFalse(supplies.wardsAura(ann, null));
    assertTrue(supplies.wardsAura(ann, "DS_Ice_Prison"));
    assertTrue(ann.nextMessage().contains("wards it off"));
    assertFalse(supplies.wardsPotion(ann, "SLOWNESS"), "used up");
    supplies.use(ann, draught);
    assertTrue(supplies.wardsPotion(ann, "SLOWNESS"));
  }

  @Test
  void theCampfireRestoresOncePerRun() {
    int rest = room(RoomType.REST);
    double[] fire = geo.markers(rest, "rest").get(0);
    at(ann, fire);
    run.member(ann.getUniqueId()).room(rest);
    Ports.Mana port = mock(Ports.Mana.class);
    when(port.max(ann)).thenReturn(30.0);
    supplies.mana = port;
    ann.setHealth(4);
    supplies.step(ar, now.get()); // not crouching
    ann.setSneaking(true);
    supplies.step(ar, now.get());
    assertEquals(4, ann.getHealth(), 1e-9, "half a second of the one needed");
    supplies.step(ar, now.get());
    assertEquals(20, ann.getHealth(), 1e-9);
    verify(port).add(ann, 30.0);
    assertTrue(ann.nextMessage().contains("Health and mana restored"));
    ann.setHealth(4);
    supplies.step(ar, now.get());
    supplies.step(ar, now.get());
    assertEquals(4, ann.getHealth(), 1e-9, "once per run");
    // elsewhere in the room, in another room, outside rooms, or downed: no rest
    run.member(bob.getUniqueId()).room(rest);
    bob.setSneaking(true);
    bob.setHealth(4);
    at(bob, new double[] {fire[0] + 10, fire[1], fire[2]});
    supplies.step(ar, now.get());
    supplies.step(ar, now.get());
    at(bob, new double[] {fire[0], fire[1], fire[2] + 10});
    supplies.step(ar, now.get());
    supplies.step(ar, now.get());
    at(bob, fire);
    run.member(bob.getUniqueId()).room(room(RoomType.COMBAT));
    supplies.step(ar, now.get());
    supplies.step(ar, now.get());
    run.member(bob.getUniqueId()).room(-1);
    supplies.step(ar, now.get());
    supplies.step(ar, now.get());
    run.member(bob.getUniqueId()).room(rest);
    run.down(bob.getUniqueId(), now.get());
    supplies.step(ar, now.get());
    supplies.step(ar, now.get());
    assertEquals(4, bob.getHealth(), 1e-9);
  }

  @Test
  void harderRunsRestHalfAndWithoutAMarkerTheCentreCounts() {
    RunGeometry spy = spy(geo);
    int rest = room(RoomType.REST);
    doReturn(List.of()).when(spy).markers(rest, "rest");
    ActiveRun hard = activeRun(spy, 2);
    supplies.mana = null;
    at(ann, geo.centre(rest));
    run.member(ann.getUniqueId()).room(rest);
    ann.setSneaking(true);
    ann.setHealth(4);
    supplies.step(hard, now.get());
    supplies.step(hard, now.get());
    assertEquals(14, ann.getHealth(), 1e-9);
    assertTrue(ann.nextMessage().contains("50% of your health"));
    // a tier under the lowest listed: no rest at all
    settings = settings(Map.of(3, 1.0), 0, 0, 0);
    ActiveRun easy = activeRun(spy, 1);
    run.member(ann.getUniqueId()).room(rest);
    ann.setHealth(4);
    supplies.step(easy, now.get());
    supplies.step(easy, now.get());
    assertEquals(4, ann.getHealth(), 1e-9);
  }

  @Test
  void clearedRoomsEndWardsThereAndMayDropSupplies() {
    int fight = room(RoomType.COMBAT);
    ItemStack draught = item("draught", 2);
    supplies.use(ann, draught);
    supplies.use(bob, draught);
    run.member(ann.getUniqueId()).room(fight);
    run.down(bob.getUniqueId(), now.get());
    run.bleedOut(bob.getUniqueId()); // a life spent: back up
    run.down(bob.getUniqueId(), now.get());
    run.bleedOut(bob.getUniqueId()); // no lives: out
    rolls.addAll(List.of(0.1, 0.0)); // Ann finds the first supply; Bob (out) is skipped
    supplies.cleared(ar, fight, RoomType.COMBAT, false);
    verify(lockers).giveSupply(eq(ann), argThat(s -> s.id().equals("potion")), eq(1));
    verify(lockers, never()).giveSupply(eq(bob), any(), anyInt());
    assertFalse(supplies.wardsPotion(ann, "slowness"), "the fight is over: the ward with it");
    assertTrue(supplies.wardsPotion(bob, "slowness"), "not in that room");
    rolls.addAll(List.of(0.6)); // over the chance
    supplies.cleared(ar, fight, RoomType.ELITE, true);
    supplies.cleared(ar, room(RoomType.BOSS), RoomType.BOSS, true); // the boss drops none
    verify(lockers, times(1)).giveSupply(any(), any(), anyInt());
    // treasure rooms: once per player and room
    rolls.addAll(List.of(0.2, 0.6));
    supplies.treasure(ar, ann, 5);
    supplies.treasure(ar, ann, 5);
    verify(lockers).giveSupply(eq(ann), argThat(s -> s.id().equals("bandage")), eq(1));
    assertTrue(last(ann).contains("in the treasure room"));
    // nothing to find: no chance, no lockers, or no supply for this kit
    settings = settings(Map.of(1, 1.0), 0, 0, 0, supply("potion", Supply.Use.HEALTH, 0.4, 0, List.of()));
    supplies.cleared(ar, fight, RoomType.COMBAT, false);
    settings = settings(Map.of(1, 1.0), 1, 1, 1, supply("draught", Supply.Use.WARD, 0, 0, List.of("elsewhere")));
    supplies.cleared(ar, fight, RoomType.COMBAT, false);
    settings = settings(Map.of(1, 1.0), 1, 1, 1, supply("potion", Supply.Use.HEALTH, 0.4, 0, List.of()));
    when(runs.lockers()).thenReturn(null);
    supplies.cleared(ar, fight, RoomType.COMBAT, false);
    verify(lockers, times(2)).giveSupply(any(), any(), anyInt());
  }

  @Test
  void playersOutOfRunsAreForgotten() {
    ann.setHealth(5);
    supplies.use(ann, item("potion", 1));
    supplies.use(ann, item("draught", 1));
    ann.setHealth(5);
    supplies.use(ann, item("bandage", 1));
    run.member(bob.getUniqueId()).room(room(RoomType.REST));
    bob.setSneaking(true);
    bob.setHealth(5);
    at(bob, geo.markers(room(RoomType.REST), "rest").get(0));
    supplies.step(ar, now.get()); // Bob starts resting
    supplies.prune(id -> false);
    supplies.step(ar, now.get());
    assertEquals(5, bob.getHealth(), 1e-9, "his rest starts over");
    assertFalse(supplies.wardsPotion(ann, "slowness"));
    ItemStack potion = item("potion", 1);
    ann.setHealth(5);
    supplies.use(ann, potion);
    assertEquals(0, potion.getAmount(), "no cooldown kept");
    supplies.prune(id -> true);
  }
}
