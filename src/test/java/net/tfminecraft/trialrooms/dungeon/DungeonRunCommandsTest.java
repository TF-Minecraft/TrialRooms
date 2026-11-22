package net.tfminecraft.trialrooms.dungeon;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import net.tfminecraft.trialrooms.TrialRooms;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout;
import net.tfminecraft.trialrooms.dungeon.load.RecipeLoader;
import net.tfminecraft.trialrooms.dungeon.run.DungeonRun;
import net.tfminecraft.trialrooms.dungeon.run.GateStore;
import net.tfminecraft.trialrooms.dungeon.run.Ports;
import net.tfminecraft.trialrooms.dungeon.run.RunGeometry;
import net.tfminecraft.trialrooms.dungeon.run.RunManager;
import net.tfminecraft.trialrooms.dungeon.run.RunSettings;
import net.tfminecraft.trialrooms.manager.CommandManager;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.MockedStatic;

class DungeonRunCommandsTest {
  @TempDir Path temp;
  ServerMock server;
  DungeonModule module;
  RunManager runs;
  GateStore gates;
  DungeonCommand c;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    module = mock(DungeonModule.class);
    runs = mock(RunManager.class);
    gates = mock(GateStore.class);
    when(module.runs()).thenReturn(runs);
    when(runs.gates()).thenReturn(gates);
    when(module.recipes()).thenReturn(Map.of("delve", mock(RecipeLoader.Entry.class)));
    c = new DungeonCommand(module);
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  RunManager.ActiveRun run(String id) {
    RunManager.ActiveRun ar = mock(RunManager.ActiveRun.class);
    DungeonRun r = mock(DungeonRun.class);
    DungeonLayout layout = net.tfminecraft.trialrooms.dungeon.run.RunCoreTest.line();
    when(r.id()).thenReturn(UUID.fromString(id));
    when(r.recipeId()).thenReturn("delve");
    when(r.state()).thenReturn(DungeonRun.State.RUNNING);
    when(r.layout()).thenReturn(layout);
    when(r.room(anyInt())).thenReturn(DungeonRun.Room.IDLE);
    DungeonRun real = new DungeonRun(UUID.randomUUID(), "delve", layout, 1, 0);
    UUID away = UUID.randomUUID();
    real.add(UUID.randomUUID(), "c1");
    real.add(away, "c2");
    real.offline(away, 0);
    when(r.members()).thenReturn(real.members());
    when(ar.run()).thenReturn(r);
    when(ar.geometry()).thenReturn(new RunGeometry(layout, 32, 4, 0, 64, 0));
    return ar;
  }

  @Test
  void staffRunTools() {
    CommandSender console = mock(CommandSender.class);
    Player p = mock(Player.class);
    c.run(console, new String[] {"start", "delve"});
    c.run(p, new String[] {"start"});
    verify(runs, never()).startRun(any(), any(), anyLong(), any());
    c.run(p, new String[] {"start", "delve", "x"});
    verify(p).sendMessage("§cThe seed must be a whole number.");
    c.run(p, new String[] {"start", "delve", "5"});
    verify(runs).startRun(p, "delve", 5, null);
    c.run(p, new String[] {"start", "delve", "6", "hard"});
    verify(runs).startRun(p, "delve", 6, "hard");
    c.run(p, new String[] {"start", "delve"});
    verify(runs, times(2)).startRun(eq(p), eq("delve"), anyLong(), isNull());

    RunManager.ActiveRun ar = run("12345678-0000-0000-0000-000000000000");
    when(runs.runs()).thenReturn(List.of(ar));
    c.run(console, new String[] {"runs"});
    verify(console).sendMessage(startsWith("§f12345678 §7delve RUNNING"));
    verify(console).sendMessage(endsWith("ALIVE online room -1"));
    verify(console).sendMessage(endsWith("ALIVE away room -1"));
    c.run(console, new String[] {"end"});
    c.run(console, new String[] {"end", "ffff"});
    verify(console).sendMessage("§cNo run starts with ffff.");
    c.run(console, new String[] {"end", "1234"});
    verify(runs).end(eq(ar), eq(true), anyString());
    c.run(console, new String[] {"rooms"});
    c.run(console, new String[] {"rooms", "ffff"});
    when(ar.event(1)).thenReturn(net.tfminecraft.trialrooms.dungeon.event.EventKind.RUNES);
    c.run(console, new String[] {"rooms", "1234"});
    verify(console).sendMessage(startsWith("§7room 0 START IDLE at "));
    verify(console).sendMessage(endsWith(" [runes]"));
  }

  @Test
  void gatesAndLeaving() {
    CommandSender console = mock(CommandSender.class);
    Player p = mock(Player.class);
    Block block = mock(Block.class);
    when(gates.all()).thenReturn(List.of(new GateStore.Gate("w", 1, 2, 3, "delve")));
    c.run(console, new String[] {"gate", "list"});
    verify(console).sendMessage("§f" + "w 1 2 3" + " §7-> delve");
    c.run(console, new String[] {"gate"});
    c.run(console, new String[] {"gate", "add", "delve"});
    c.run(p, new String[] {"gate", "add"});
    c.run(p, new String[] {"gate", "other"});
    c.run(p, new String[] {"gate", "remove"});
    verify(p).sendMessage("§cLook at the gate block (within 6 blocks).");
    when(p.getTargetBlockExact(6)).thenReturn(block);
    when(gates.remove(block)).thenReturn(true, false);
    c.run(p, new String[] {"gate", "remove"});
    c.run(p, new String[] {"gate", "remove"});
    verify(p).sendMessage("§aGate removed.");
    verify(p).sendMessage("§cThat block is no gate.");
    c.run(p, new String[] {"gate", "add", "nope"});
    verify(p).sendMessage("§cUnknown recipe: nope");
    c.run(p, new String[] {"gate", "add", "delve"});
    verify(gates).add(block, "delve");
    // leaving
    c.run(console, new String[] {"leave"});
    verify(console).sendMessage("§cYou are not in a dungeon.");
    c.run(p, new String[] {"leave"});
    when(runs.unqueue(p)).thenReturn(true);
    c.run(p, new String[] {"leave"});
    verify(p).sendMessage("§7Your party stops waiting for a dungeon.");
    when(runs.leave(p)).thenReturn(true);
    c.run(p, new String[] {"leave"});
    verify(p).sendMessage("§7You leave the dungeon.");
    // completion
    assertEquals(List.of("add"), c.complete(new String[] {"gate", "a"}));
    assertEquals(List.of("delve"), c.complete(new String[] {"gate", "add", "d"}));
    assertEquals(List.of(), c.complete(new String[] {"gate", "remove", "d"}));
    assertEquals(List.of(), c.complete(new String[] {"runs", "x", "y"}));
    assertEquals(List.of("delve"), c.complete(new String[] {"start", "d"}));
    assertEquals(List.of(), c.complete(new String[] {"end", "d"}));
  }

  @Test
  void anyPlayerMayLeaveButOnlyStaffUseTheRest() {
    TrialRooms tr = mock(TrialRooms.class);
    when(tr.getDungeons()).thenReturn(module);
    try (MockedStatic<TrialRooms> s = mockStatic(TrialRooms.class)) {
      s.when(TrialRooms::getInstance).thenReturn(tr);
      CommandManager cm = new CommandManager();
      Command cmd = mock(Command.class);
      Player p = mock(Player.class);
      assertTrue(cm.onCommand(p, cmd, "tr", new String[] {"dungeon", "leave"}));
      verify(p).sendMessage("§cYou are not in a dungeon.");
      assertTrue(cm.onCommand(p, cmd, "tr", new String[] {"dungeon"}));
      verify(p).sendMessage("§cYou don't have permission to use this command.");
      assertTrue(cm.onCommand(p, cmd, "tr", new String[] {"dungeon", "READY"}));
      verify(p).sendMessage(startsWith("§cThere is nothing to get ready for"));
      assertTrue(cm.onCommand(p, cmd, "tr", new String[] {"dungeon", "locker"}));
      verify(p).sendMessage("§cDungeon lockers are off.");
    }
  }

  @Test
  void playersGetReadyAndOpenTheirLocker() throws Exception {
    CommandSender console = mock(CommandSender.class);
    Player p = mock(Player.class);
    when(runs.ready(p)).thenReturn(true);
    c.run(p, new String[] {"ready"});
    verify(p).sendMessage("§aYou are ready.");
    c.run(console, new String[] {"ready"});
    verify(console).sendMessage(startsWith("§cThere is nothing to get ready for"));

    c.run(p, new String[] {"locker"});
    verify(p).sendMessage("§cDungeon lockers are off.");
    net.tfminecraft.trialrooms.dungeon.locker.LockerService lockers =
        mock(net.tfminecraft.trialrooms.dungeon.locker.LockerService.class);
    when(runs.lockers()).thenReturn(lockers);
    c.run(console, new String[] {"locker"});
    verify(console).sendMessage("§cOnly a player has a locker.");
    org.mockbukkit.mockbukkit.entity.PlayerMock ann = server.addPlayer("Ann");
    c.run(ann, new String[] {"locker"});
    assertTrue(ann.nextMessage().contains("can't be opened"), "no locker for a player without a character");
    net.tfminecraft.trialrooms.dungeon.locker.Locker l = new net.tfminecraft.trialrooms.dungeon.locker.Locker();
    when(lockers.locker(ann)).thenReturn(l);
    c.run(ann, new String[] {"locker", "please"});
    assertInstanceOf(net.tfminecraft.trialrooms.dungeon.locker.LockerMenu.class,
        ann.getOpenInventory().getTopInventory().getHolder());

    // staff hand out test items until loot drops them
    c.run(ann, new String[] {"locker", "give"});
    assertEquals("§cYou don't have permission to use this command.", ann.nextMessage());
    ann.addAttachment(MockBukkit.createMockPlugin(), RunManager.STAFF_PERMISSION, true);
    c.run(console, new String[] {"locker", "give", "Ann"});
    verify(console).sendMessage(startsWith("§cYou don't have permission"));
    when(console.hasPermission(RunManager.STAFF_PERMISSION)).thenReturn(true);
    c.run(console, new String[] {"locker", "give", "Ann"});
    verify(console).sendMessage("§cHold the item to give.");
    c.run(ann, new String[] {"locker", "give"});
    assertEquals("§cHold the item to give.", ann.nextMessage());
    ann.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND_SWORD));
    c.run(ann, new String[] {"locker", "give", "Nobody"});
    assertEquals("§cNo player Nobody is online.", ann.nextMessage());
    c.run(ann, new String[] {"locker", "give"});
    assertTrue(ann.nextMessage().contains("has no usable locker"));
    when(lockers.add(eq(ann), any())).thenReturn("id");
    c.run(ann, new String[] {"locker", "give", "Ann"});
    assertEquals("§aA copy is in Ann's dungeon locker.", ann.nextMessage());

    // loot: rolled for the player's class and filed in their locker
    c.run(console, new String[] {"loot", "Ann"});
    verify(console).sendMessage("§cDungeon loot is off (it needs MMOItems and AdvancedCrafting).");
    net.tfminecraft.trialrooms.dungeon.loot.DungeonLoot loot = mock(net.tfminecraft.trialrooms.dungeon.loot.DungeonLoot.class);
    when(module.loot()).thenReturn(loot);
    when(runs.lockers()).thenReturn(null);
    c.run(console, new String[] {"loot", "Ann"});
    verify(console, times(2)).sendMessage("§cDungeon loot is off (it needs MMOItems and AdvancedCrafting).");
    when(runs.lockers()).thenReturn(lockers);
    c.run(console, new String[] {"loot"});
    c.run(console, new String[] {"loot", "Nobody"});
    verify(console, times(2)).sendMessage("§cUsage: /tr dungeon loot <player> [tier] [rarity|any] [item]");
    c.run(console, new String[] {"loot", "Ann", "two"});
    verify(console).sendMessage("§cThe tier must be a whole number.");
    when(loot.roll(ann, 1, null, null)).thenReturn(new net.tfminecraft.trialrooms.dungeon.loot.DungeonLoot.Rolled(null, "nope"));
    c.run(console, new String[] {"loot", "Ann"});
    verify(console).sendMessage("§cnope");
    org.bukkit.inventory.ItemStack rolled = mock(org.bukkit.inventory.ItemStack.class); // (MockBukkit has no effectiveName)
    when(rolled.effectiveName()).thenReturn(net.kyori.adventure.text.Component.text("Rimebound Sword"));
    when(loot.roll(ann, 3, "epic", "sword")).thenReturn(new net.tfminecraft.trialrooms.dungeon.loot.DungeonLoot.Rolled(rolled, null));
    when(lockers.add(ann, rolled)).thenReturn(null);
    c.run(console, new String[] {"loot", "Ann", "3", "epic", "sword"});
    verify(console).sendMessage("§cAnn has no usable locker (no character played?).");
    when(lockers.add(ann, rolled)).thenReturn("id");
    c.run(console, new String[] {"loot", "Ann", "3", "epic", "sword"});
    verify(console).sendMessage("§aRimebound Sword is in Ann's dungeon locker.");
    when(loot.roll(ann, 2, null, null)).thenReturn(new net.tfminecraft.trialrooms.dungeon.loot.DungeonLoot.Rolled(rolled, null));
    c.run(console, new String[] {"loot", "Ann", "2", "ANY"});
    verify(console, times(2)).sendMessage("§aRimebound Sword is in Ann's dungeon locker.");
    assertTrue(c.complete(new String[] {"lo"}).containsAll(List.of("locker", "loot")));
    // a gem, when the rewards say how gems are made
    when(module.rewardRules()).thenReturn(net.tfminecraft.trialrooms.dungeon.loot.RewardsTest.shippedRules());
    when(loot.gem(1, "rare", "jewellery", "gemstones.larimar"))
        .thenReturn(new net.tfminecraft.trialrooms.dungeon.loot.DungeonLoot.Rolled(rolled, null));
    c.run(console, new String[] {"loot", "Ann", "1", "rare", "gem"});
    verify(console, times(3)).sendMessage("§aRimebound Sword is in Ann's dungeon locker.");
    when(module.rewardRules()).thenReturn(null);
    when(loot.roll(ann, 1, null, "gem")).thenReturn(new net.tfminecraft.trialrooms.dungeon.loot.DungeonLoot.Rolled(null, "no gem rules"));
    c.run(console, new String[] {"loot", "Ann", "1", "any", "gem"});
    verify(console).sendMessage("§cno gem rules");
    // marks, for trying the shop
    net.tfminecraft.trialrooms.dungeon.locker.Locker annLocker = new net.tfminecraft.trialrooms.dungeon.locker.Locker();
    when(lockers.locker(ann)).thenReturn(annLocker);
    c.run(console, new String[] {"marks", "Ann", "40"});
    assertEquals(40, annLocker.marks());
    verify(console).sendMessage("§aAnn has 40 dungeon marks.");
    c.run(console, new String[] {"marks", "Ann", "lots"});
    verify(console).sendMessage("§cThe amount must be a whole number.");
    c.run(console, new String[] {"marks", "Ann"});
    c.run(console, new String[] {"marks", "Nobody", "5"});
    verify(console, times(2)).sendMessage(startsWith("§cUsage: /tr dungeon marks"));
    when(runs.lockers()).thenReturn(null);
    c.run(console, new String[] {"marks", "Ann", "5"});
    verify(console, times(3)).sendMessage(startsWith("§cUsage: /tr dungeon marks"));
    when(runs.lockers()).thenReturn(lockers);

    assertEquals(List.of("give"), c.complete(new String[] {"locker", "g"}));
    assertEquals(List.of(), c.complete(new String[] {"locker", "x"}));
    assertTrue(c.complete(new String[] {"rea"}).contains("ready"));
  }

  @Test
  void theModuleStartsAndStopsRuns() {
    JavaPlugin plugin = mock(JavaPlugin.class);
    Server srv = mock(Server.class);
    PluginManager pm = mock(PluginManager.class);
    when(plugin.getServer()).thenReturn(srv);
    when(srv.getPluginManager()).thenReturn(pm);
    when(srv.getScheduler()).thenReturn(server.getScheduler());
    when(plugin.getDataFolder()).thenReturn(temp.toFile());
    when(plugin.getLogger()).thenReturn(Logger.getLogger("t"));
    when(plugin.getName()).thenReturn("TrialRooms");
    when(plugin.namespace()).thenReturn("trialrooms");
    when(plugin.isEnabled()).thenReturn(true);
    DungeonModule m = new DungeonModule(plugin, x -> null);
    assertNull(m.runs());
    assertEquals(RunSettings.defaults(), m.settings());
    m.startRuns(mock(Ports.Characters.class), mock(Ports.Keys.class), mock(Ports.Mobs.class));
    assertNotNull(m.runs());
    verify(pm, times(2)).registerEvents(any(), eq(plugin)); // runs and lockers
    assertNotNull(m.runs().lockers());
    assertNull(m.loot(), "no item plugins here");
    when(pm.isPluginEnabled("MMOItems")).thenReturn(true);
    m.startRuns(mock(Ports.Characters.class), mock(Ports.Keys.class), mock(Ports.Mobs.class));
    assertNull(m.loot(), "MMOItems alone is not enough");
    when(pm.isPluginEnabled("AdvancedCrafting")).thenReturn(true);
    m.startRuns(mock(Ports.Characters.class), mock(Ports.Keys.class), mock(Ports.Mobs.class));
    assertNotNull(m.loot());
    assertNotNull(m.rewards());
    assertNull(m.rewardRules(), "nothing loaded in this module yet");
    // the ally filter and its staff report
    DungeonCommand dc = new DungeonCommand(m);
    CommandSender staff = mock(CommandSender.class);
    dc.run(staff, new String[] {"allies"});
    verify(staff).sendMessage("§cThe ally filter is not running.");
    var heal = mock(io.lumine.mythic.core.skills.mechanics.HealMechanic.class);
    when(heal.getConfigLine()).thenReturn("heal{}");
    io.lumine.mythic.bukkit.MythicBukkit mythic = mock(io.lumine.mythic.bukkit.MythicBukkit.class);
    when(mythic.getSkillManager()).thenReturn(mock(io.lumine.mythic.core.skills.SkillExecutor.class));
    try (var mb = mockStatic(io.lumine.mythic.bukkit.MythicBukkit.class)) {
      mb.when(io.lumine.mythic.bukkit.MythicBukkit::inst).thenReturn(mythic);
      assertNotNull(m.startAllyFilter(() -> java.util.List.of(heal)));
      assertNotNull(m.allyFilter());
      // the first attach comes 100 ticks after start, then every minute (adding nothing more)
      server.getScheduler().performTicks(101);
      server.getScheduler().performTicks(1200);
      dc.run(staff, new String[] {"allies"});
      verify(staff).sendMessage("§aAlly filter: 1 mechanics: 1 help, 0 harm, 1 filtered");
    }
    m.disable();
  }
}
