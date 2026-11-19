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
    verify(runs, never()).startRun(any(), any(), anyLong());
    c.run(p, new String[] {"start", "delve", "x"});
    verify(p).sendMessage("§cThe seed must be a whole number.");
    c.run(p, new String[] {"start", "delve", "5"});
    verify(runs).startRun(p, "delve", 5);
    c.run(p, new String[] {"start", "delve"});
    verify(runs, times(2)).startRun(eq(p), eq("delve"), anyLong());

    RunManager.ActiveRun ar = run("12345678-0000-0000-0000-000000000000");
    when(runs.runs()).thenReturn(List.of(ar));
    c.run(console, new String[] {"runs"});
    verify(console).sendMessage(startsWith("§f12345678 §7delve RUNNING"));
    c.run(console, new String[] {"end"});
    c.run(console, new String[] {"end", "ffff"});
    verify(console).sendMessage("§cNo run starts with ffff.");
    c.run(console, new String[] {"end", "1234"});
    verify(runs).end(eq(ar), eq(true), anyString());
    c.run(console, new String[] {"rooms"});
    c.run(console, new String[] {"rooms", "ffff"});
    c.run(console, new String[] {"rooms", "1234"});
    verify(console).sendMessage(startsWith("§7room 0 START IDLE at "));
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
    }
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
    verify(pm).registerEvents(any(), eq(plugin));
    m.disable();
  }
}
