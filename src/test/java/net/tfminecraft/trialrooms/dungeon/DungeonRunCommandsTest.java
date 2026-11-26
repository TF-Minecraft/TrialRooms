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
    c.run(p, new String[] {"claim"});
    verify(runs).claim(p);
    // leaderboards
    c.run(p, new String[] {"top"});
    verify(p).sendMessage("§cDungeon leaderboards are off.");
    net.tfminecraft.trialrooms.dungeon.run.Records records = new net.tfminecraft.trialrooms.dungeon.run.Records(
        temp.resolve("records.yml").toFile(), java.util.logging.Logger.getLogger("t"));
    when(runs.records()).thenReturn(records);
    var d = new net.tfminecraft.trialrooms.dungeon.run.Difficulty("hard", "Hard", 2, 1, 1, null, "");
    RecipeLoader.Entry laddered = mock(RecipeLoader.Entry.class);
    when(laddered.difficulties()).thenReturn(List.of(d));
    when(laddered.difficulty("hard")).thenReturn(d);
    RecipeLoader.Entry plain = mock(RecipeLoader.Entry.class);
    when(plain.difficulties()).thenReturn(List.of());
    when(module.recipes()).thenReturn(new java.util.TreeMap<>(Map.of("delve", laddered, "plain", plain)));
    records.add("delve", "hard", 2, new net.tfminecraft.trialrooms.dungeon.run.Records.Clear(90_000, List.of("Ann", "Bo"), 0));
    c.run(p, new String[] {"top"});
    verify(p).sendMessage(startsWith("§eUsage: /tr dungeon top <dungeon> [difficulty] [party size]. §7Dungeons: delve, plain"));
    c.run(p, new String[] {"top", "nowhere"});
    verify(p).sendMessage("§cNo dungeon nowhere.");
    c.run(p, new String[] {"top", "delve"});
    verify(p).sendMessage("§fHard: §71:30 party of 2");
    c.run(p, new String[] {"top", "plain"});
    verify(p).sendMessage("§fBest: §7no clears yet");
    c.run(p, new String[] {"top", "delve", "easy"});
    verify(p).sendMessage("§cdelve has no difficulty easy.");
    c.run(p, new String[] {"top", "delve", "hard", "two"});
    verify(p).sendMessage("§cA party size is a number.");
    c.run(p, new String[] {"top", "delve", "hard", "2"});
    verify(p).sendMessage("§6delve, Hard, party of 2");
    verify(p).sendMessage(startsWith("§f1. 1:30 §7Ann, Bo ("));
    c.run(p, new String[] {"top", "delve", "hard"});
    verify(p).sendMessage("§6delve, Hard, solo: §7no clears yet");
    c.run(p, new String[] {"top", "plain", "any"});
    verify(p).sendMessage("§6plain, solo: §7no clears yet");
    c.run(console, new String[] {"claim"});
    verify(console).sendMessage("§cOnly players have things waiting for them.");

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
  void mmoCoreAndMythicLibRulesStartWithTheirPlugins() {
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
    when(pm.isPluginEnabled("MMOCore")).thenReturn(true);
    when(pm.isPluginEnabled("MythicLib")).thenReturn(true);
    DungeonModule m = new DungeonModule(plugin, x -> null);
    m.startRuns(mock(Ports.Characters.class), mock(Ports.Keys.class), mock(Ports.Mobs.class));
    verify(pm).registerEvents(any(net.tfminecraft.trialrooms.dungeon.supply.MmoCoreRules.class), eq(plugin));
    verify(pm).registerEvents(any(net.tfminecraft.trialrooms.dungeon.supply.MythicLibRules.class), eq(plugin));
    server.getScheduler().performTicks(21); // the regen hold looks at who is online each second
    verify(srv, atLeastOnce()).getOnlinePlayers();
    m.disable(); // (gives everyone held their regen back)
  }

  @Test
  void theModuleStartsAndStopsRuns() throws Exception {
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
    assertNull(m.mobTest("x", null, 1, true), "no runs yet: no mobs to spawn");
    assertEquals(RunSettings.defaults(), m.settings());
    Ports.Mobs mobs = mock(Ports.Mobs.class);
    m.startRuns(mock(Ports.Characters.class), mock(Ports.Keys.class), mobs);
    assertNotNull(m.runs());
    verify(pm, times(4)).registerEvents(any(), eq(plugin)); // runs, lockers, the attack mechanic and attacks
    assertNotNull(m.attacks());
    assertSame(net.tfminecraft.trialrooms.dungeon.combat.CombatSettings.DEFAULTS, m.combat());
    // staff watch an enemy's attacks
    var spot = new org.bukkit.Location(null, 1, 2, 3);
    org.bukkit.entity.Zombie z = mock(org.bukkit.entity.Zombie.class);
    when(z.getUniqueId()).thenReturn(UUID.randomUUID());
    when(z.getLocation()).thenReturn(spot);
    when(mobs.spawn("Dungeon_Seithr_Soldier", spot, 3)).thenReturn(z);
    assertSame(z, m.mobTest("Dungeon_Seithr_Soldier", spot, 3, true));
    assertTrue(m.marked(z), "a staff test leader");
    when(srv.getEntity(z.getUniqueId())).thenReturn(z);
    org.bukkit.entity.Zombie plain = mock(org.bukkit.entity.Zombie.class);
    when(plain.getUniqueId()).thenReturn(UUID.randomUUID());
    when(mobs.spawn("Dungeon_Seithr_Soldier", spot, 1)).thenReturn(plain);
    assertSame(plain, m.mobTest("Dungeon_Seithr_Soldier", spot, 1, false));
    assertFalse(m.marked(plain));
    assertTrue(m.marked(z), "still there");
    when(srv.getEntity(z.getUniqueId())).thenReturn(null);
    m.mobTest("Nope", spot, 1, false);
    assertFalse(m.marked(z), "gone: forgotten");
    m.startCombat(mock(net.tfminecraft.trialrooms.dungeon.combat.MobControl.class));
    verify(pm, times(5)).registerEvents(any(), eq(plugin)); // a control that listens to nothing: only the attacks
    assertNull(m.mobTest("Nope", spot, 0, false));
    m.disable();
    assertTrue(m.attacks().describe().isEmpty(), "stopped with the module");
    assertNotNull(m.runs().lockers());
    assertNull(m.loot(), "no item plugins here");
    when(pm.isPluginEnabled("MMOItems")).thenReturn(true);
    m.startRuns(mock(Ports.Characters.class), mock(Ports.Keys.class), mock(Ports.Mobs.class));
    assertNull(m.loot(), "MMOItems alone is not enough");
    when(pm.isPluginEnabled("AdvancedCrafting")).thenReturn(true);
    m.startRuns(mock(Ports.Characters.class), mock(Ports.Keys.class), mock(Ports.Mobs.class));
    assertNotNull(m.loot());
    assertNull(m.loot().guns(), "no GunsAndGadgets here");
    assertNotNull(m.gear());
    assertNull(m.wayOut(), "nothing loaded in this module yet");
    assertEquals(Map.of(), m.sets());
    assertEquals(Map.of(), m.worth());
    assertFalse(m.enemy(mock(org.bukkit.entity.Entity.class, org.mockito.Mockito.RETURNS_DEEP_STUBS)), "no run's");
    var tagged = mock(org.bukkit.entity.Entity.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    var data = tagged.getPersistentDataContainer();
    when(data.has(any(), any())).thenAnswer(i -> !DungeonModule.ALLY.equals(i.getArgument(0)));
    m.owner = e -> null;
    assertTrue(m.enemy(tagged));
    m.owner = e -> mock(Player.class);
    assertFalse(m.enemy(tagged), "a player's summon");
    verify(data).set(DungeonModule.ALLY, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
    when(data.has(any(), any())).thenReturn(true);
    m.owner = e -> null;
    assertFalse(m.enemy(tagged), "still the players' once its owner logged off");
    boolean[] ran = {false};
    m.later(() -> ran[0] = true);
    server.getScheduler().performTicks(1);
    assertTrue(ran[0]);
    when(plugin.isEnabled()).thenReturn(false);
    m.later(() -> fail("stopping"));
    server.getScheduler().performTicks(1);
    when(plugin.isEnabled()).thenReturn(true);
    m.optional("x", () -> {
      throw new NoClassDefFoundError("api");
    });
    assertNotNull(m.rewards());
    when(pm.isPluginEnabled("Magic")).thenReturn(true);
    when(pm.isPluginEnabled("GunsAndGadgets")).thenReturn(true);
    m.startRuns(mock(Ports.Characters.class), mock(Ports.Keys.class), mock(Ports.Mobs.class));
    assertNotNull(m.loot().guns());
    assertNull(m.rewardRules(), "nothing loaded in this module yet");
    Path files = temp.resolve("dungeons");
    java.nio.file.Files.createDirectories(files);
    try (var in = DungeonModule.class.getResourceAsStream("/dungeons/loot.yml")) {
      java.nio.file.Files.copy(in, files.resolve("loot.yml"));
    }
    m.reload();
    var api = mock(net.tfminecraft.tlibs.objects.api.ItemAPI.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    try (var libs = mockStatic(net.tfminecraft.tlibs.TLibs.class)) {
      libs.when(net.tfminecraft.tlibs.TLibs::getItemAPI).thenReturn(api);
      when(api.getCreator().getItemFromPath("ia.tfmc_dungeons:way_out"))
          .thenReturn(new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER));
      assertNotNull(m.wayOut());
    }
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
    // a drunk ward stops an aura on a player playing a run
    RunManager real = m.runs;
    m.runs = mock(RunManager.class);
    Player warded = mock(Player.class);
    assertFalse(m.wardsAura(mock(org.bukkit.entity.Zombie.class), "Frozen"));
    assertFalse(m.wardsAura(warded, "Frozen"), "not playing here");
    when(m.runs.playingHere(warded)).thenReturn(true);
    assertFalse(m.wardsAura(warded, "Frozen"), "no ward");
    when(m.runs.wardsAura(warded, "Frozen")).thenReturn(true);
    assertTrue(m.wardsAura(warded, "Frozen"));
    m.runs = real;
    m.disable();
  }

  @Test
  void suppliesOpenTheirScreenAndStaffGiveThem() {
    var potion = new net.tfminecraft.trialrooms.dungeon.supply.Supply("potion", "Health Potion",
        new net.tfminecraft.trialrooms.dungeon.supply.Supply.Look("SLIME_BALL", 1, null), 3, 2,
        net.tfminecraft.trialrooms.dungeon.supply.Supply.Use.HEALTH, 0.4, 0, 0, 0, java.util.Set.of(), java.util.Set.of(),
        java.util.List.of());
    when(module.supplies()).thenReturn(new net.tfminecraft.trialrooms.dungeon.supply.SupplySettings(3, 4,
        new java.util.TreeMap<>(Map.of(1, 1.0)), 4, Map.of("potion", potion), 0, 0, 0));
    CommandSender console = mock(CommandSender.class);
    c.run(console, new String[] {"supplies"});
    verify(console).sendMessage("§cDungeon lockers are off.");
    var lockers = mock(net.tfminecraft.trialrooms.dungeon.locker.LockerService.class);
    when(runs.lockers()).thenReturn(lockers);
    c.run(console, new String[] {"supplies"});
    verify(console).sendMessage("§cOnly a player has supplies.");
    var ann = server.addPlayer("Ann");
    server.addPlayer("Bob");
    var l = new net.tfminecraft.trialrooms.dungeon.locker.Locker();
    when(lockers.locker(ann)).thenReturn(l);
    c.run(ann, new String[] {"supplies", "please"});
    assertInstanceOf(net.tfminecraft.trialrooms.dungeon.supply.SupplyMenu.class,
        ann.getOpenInventory().getTopInventory().getHolder());
    c.run(console, new String[] {"supplies", "give", "Ann", "potion"});
    verify(console).sendMessage("§cYou don't have permission to use this command.");
    when(console.hasPermission(RunManager.STAFF_PERMISSION)).thenReturn(true);
    c.run(console, new String[] {"supplies", "give", "Ann"});
    verify(console).sendMessage("§cUsage: /tr dungeon supplies give <player> <supply> [amount]");
    for (String[] bad : java.util.List.of(new String[] {"supplies", "give", "Nobody", "potion"},
        new String[] {"supplies", "give", "Bob", "potion"}, new String[] {"supplies", "give", "Ann", "nothing"},
        new String[] {"supplies", "give", "Ann", "potion", "0"})) {
      c.run(console, bad);
    }
    verify(console, times(4)).sendMessage("§cNo such online player with a locker, or no such supply (potion).");
    c.run(console, new String[] {"supplies", "give", "Ann", "potion", "lots"});
    verify(console).sendMessage("§cThe locker could not be saved; nothing changed.");
    assertEquals(1, l.supplies("potion"), "one when the amount is no number");
    when(lockers.saveLocker(ann, l)).thenReturn(true);
    c.run(console, new String[] {"supplies", "give", "Ann", "potion", "3"});
    verify(console).sendMessage("§aGave Ann 3 Health Potion (in their locker, not packed).");
    assertEquals(4, l.supplies("potion"));
    assertEquals(java.util.List.of("give"), c.complete(new String[] {"supplies", "g"}));
    assertEquals(java.util.List.of(), c.complete(new String[] {"supplies", "x"}));
    assertEquals(java.util.List.of("potion"), c.complete(new String[] {"supplies", "give", "Ann", "po"}));
    assertFalse(c.complete(new String[] {"rooms", "give", "Ann", "po"}).contains("potion"));
  }

  @Test
  void staffWatchEnemyAttacks() {
    CommandSender console = mock(CommandSender.class);
    c.run(console, new String[] {"mobtest", "x"});
    verify(console).sendMessage("§eUsage (in game): /tr dungeon mobtest <mob> [tier 1-4] [leader]");
    Player p = mock(Player.class);
    when(p.getLocation()).thenReturn(new org.bukkit.Location(null, 0, 64, 0, 0, 0));
    c.run(p, new String[] {"mobtest"});
    verify(p).sendMessage("§eUsage (in game): /tr dungeon mobtest <mob> [tier 1-4] [leader]");
    c.run(p, new String[] {"mobtest", "m", "x"});
    verify(p).sendMessage("§cThe tier must be a whole number.");
    c.run(p, new String[] {"mobtest", "Nope"});
    verify(p).sendMessage("§cThere is no mob Nope.");
    when(module.mobTest(eq("Dungeon_Seithr_Soldier"), any(), eq(3), eq(true))).thenReturn(mock(org.bukkit.entity.Zombie.class));
    c.run(p, new String[] {"mobtest", "Dungeon_Seithr_Soldier", "3", "leader"});
    verify(p).sendMessage("§aDungeon_Seithr_Soldier at level 3 as a leader. §7Its attacks show with /tr dungeon attacks.");
    when(module.mobTest(eq("Dungeon_Seithr_Soldier"), any(), eq(0), eq(false))).thenReturn(mock(org.bukkit.entity.Zombie.class));
    c.run(p, new String[] {"mobtest", "Dungeon_Seithr_Soldier", "0", "x"});
    verify(p).sendMessage("§aDungeon_Seithr_Soldier at level 1. §7Its attacks show with /tr dungeon attacks.");
    c.run(console, new String[] {"attacks"});
    verify(console).sendMessage("§a0 enemy attacks under way.");
    var attacks = mock(net.tfminecraft.trialrooms.dungeon.combat.Attacks.class);
    when(attacks.describe()).thenReturn(List.of("Soldier: cleave WINDUP"));
    when(module.attacks()).thenReturn(attacks);
    c.run(console, new String[] {"attacks"});
    verify(console).sendMessage("§a1 enemy attacks under way.");
    verify(console).sendMessage("§7Soldier: cleave WINDUP");
  }

  @Test
  void playersSeeTheirSetBonuses() {
    CommandSender console = mock(CommandSender.class);
    c.run(console, new String[] {"sets"});
    verify(console).sendMessage("§cOnly players wear dungeon gear.");
    Player p = mock(Player.class);
    c.run(p, new String[] {"sets"});
    var gear = mock(net.tfminecraft.trialrooms.dungeon.gear.GearService.class);
    when(module.gear()).thenReturn(gear);
    when(gear.result(p)).thenReturn(net.tfminecraft.trialrooms.dungeon.gear.GearBonuses.Result.NONE);
    c.run(p, new String[] {"sets"});
    verify(p, times(2)).sendMessage("§7No set bonuses: wear dungeon gear in a dungeon.");
    when(gear.result(p)).thenReturn(new net.tfminecraft.trialrooms.dungeon.gear.GearBonuses.Result(Map.of(), List.of(),
        List.of("Delver armour: 2 of 4")));
    c.run(p, new String[] {"sets"});
    verify(p).sendMessage("§7Delver armour: 2 of 4");
  }

  @Test
  void playersSeeTheirFindsAndStaffGiveLossesBack() {
    CommandSender console = mock(CommandSender.class);
    Player p = mock(Player.class);
    c.run(p, new String[] {"finds"});
    verify(runs).openFinds(p);
    c.run(p, new String[] {"insure"});
    verify(runs).openInsurance(p);
    c.run(console, new String[] {"finds"});
    verify(console).sendMessage("§cOnly players hold finds.");
    c.run(console, new String[] {"losses"});
    verify(console).sendMessage("§cUsage: /tr dungeon losses <player>");
    c.run(console, new String[] {"losses", "Nobody"});
    verify(console, times(2)).sendMessage("§cUsage: /tr dungeon losses <player>");
    var ann = server.addPlayer("Ann");
    c.run(console, new String[] {"losses", "Ann"});
    verify(console).sendMessage("§7Ann has lost nothing in dungeons (that is still logged).");
    var find = new net.tfminecraft.trialrooms.dungeon.run.RunRewards.Find(
        net.tfminecraft.trialrooms.dungeon.run.RunRewards.Moment.BOSS, 0, "Rimebound Ring", 52, 0, false, List.of(),
        List.of(), true);
    List<net.tfminecraft.trialrooms.dungeon.run.FindStore.Loss> losses = new java.util.ArrayList<>();
    for (int i = 1; i <= 12; i++) {
      losses.add(new net.tfminecraft.trialrooms.dungeon.run.FindStore.Loss(i, 1_790_000_000_000L, "c1", UUID.randomUUID(),
          "delve", i % 2 == 0 ? "" : "mythic", 4, "fell", i % 3 == 0 ? "" : "with 2/20 health", List.of(find),
          i % 2 == 0 ? List.of() : List.of(new byte[] {1}), i == 12));
    }
    when(runs.losses(ann.getUniqueId())).thenReturn(losses);
    var player = server.addPlayer("Staff");
    c.run(player, new String[] {"losses", "Ann"});
    String first = player.nextMessage();
    assertTrue(first.startsWith("§6Dungeon losses of Ann"), first);
    int lines = 0;
    String last = null;
    for (String m; (m = player.nextMessage()) != null; lines++) {
      last = m;
    }
    assertEquals(10, lines, "the latest ten");
    assertTrue(last.contains("#12") && last.contains("(given back)"), last);
    // giving one back
    c.run(console, new String[] {"restore", "Ann"});
    verify(console).sendMessage(startsWith("§cUsage: /tr dungeon restore"));
    c.run(console, new String[] {"restore", "Ann", "x"});
    c.run(console, new String[] {"restore", "Nobody", "3"});
    verify(console, times(3)).sendMessage(startsWith("§cUsage: /tr dungeon restore"));
    c.run(console, new String[] {"restore", "Ann", "3"});
    verify(console).sendMessage("§cNo loss #3 to give back (wrong id, or given back already).");
    when(runs.restoreLoss(ann.getUniqueId(), 3)).thenReturn(losses.get(2));
    c.run(console, new String[] {"restore", "Ann", "3"});
    verify(console).sendMessage(startsWith("§aLoss #3 given back"));
    ann.disconnect();
    c.run(console, new String[] {"restore", "Ann", "3"});
    verify(console, times(2)).sendMessage(startsWith("§aLoss #3 given back"));
  }
}
