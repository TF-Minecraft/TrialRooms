package net.tfminecraft.trialrooms.dungeon;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.tfminecraft.trialrooms.TrialRooms;
import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout;
import net.tfminecraft.trialrooms.manager.CommandManager;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.MockedStatic;

class DungeonModuleTest {
  @TempDir Path temp;
  ServerMock server;
  JavaPlugin plugin;
  Server srv;
  World world;
  StructureManager structures;
  Path data;

  @BeforeEach
  void setUp() throws IOException {
    server = MockBukkit.mock();
    data = temp.resolve("data");
    Files.createDirectories(temp.resolve("worlds"));
    srv = mock(Server.class);
    structures = mock(StructureManager.class);
    when(srv.getScheduler()).thenReturn(server.getScheduler());
    when(srv.createBlockData(anyString())).thenReturn(mock(BlockData.class));
    when(srv.getWorldContainer()).thenReturn(temp.resolve("worlds").toFile());
    when(srv.getStructureManager()).thenReturn(structures);
    when(structures.loadStructure(any(File.class))).thenAnswer(i -> {
      Structure s = mock(Structure.class);
      File f = i.getArgument(0);
      String n = f.getName();
      int width = n.startsWith("combat_ell") ? 64 : 32;
      int depth = n.startsWith("combat_long") || n.startsWith("boss") || n.startsWith("combat_ell") ? 64 : 32;
      when(s.getSize()).thenReturn(new BlockVector(width, 12, depth));
      return s;
    });
    plugin = mock(JavaPlugin.class);
    when(plugin.getServer()).thenReturn(srv);
    when(plugin.isEnabled()).thenReturn(true);
    when(plugin.getDataFolder()).thenReturn(data.toFile());
    when(plugin.getLogger()).thenReturn(Logger.getLogger("dungeon-test"));
    when(plugin.getResource(anyString()))
        .thenAnswer(i -> DungeonModule.class.getClassLoader().getResourceAsStream(i.getArgument(0)));
    world = mock(World.class);
    when(world.getMaxHeight()).thenReturn(256);
    when(world.getName()).thenReturn(DungeonModule.WORLD_NAME);
    when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(mock(Block.class));
    when(world.getChunkAtAsync(anyInt(), anyInt(), eq(true)))
        .thenAnswer(i -> CompletableFuture.completedFuture(mock(Chunk.class)));
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  DungeonModule enabled() {
    DungeonModule m = new DungeonModule(plugin, c -> world);
    m.enable();
    return m;
  }

  @Test
  void installsDefaultsLoadsAndPreviews() throws Exception {
    DungeonModule m = enabled();
    assertTrue(Files.isRegularFile(data.resolve("dungeons/kits/greybox/rooms/boss_arena.nbt")));
    assertEquals(java.util.Set.of("greybox", "seithr"), m.kits().keySet());
    assertEquals(java.util.Set.of("delve_greybox", "delve_seithr", "dungeon_seithr", "warband_seithr"), m.recipes().keySet());
    assertEquals(List.of(), m.problems());
    // big parties: longer runs of roomier fight rooms only, and a tougher boss
    for (String big : List.of("dungeon_seithr", "warband_seithr")) {
      var rules = m.recipes().get(big).rules();
      int made = 0;
      for (long seed = 1; seed <= 12; seed++) {
        try {
          var layout = m.generate(big, seed);
          made++;
          int[] b = layout.bounds(); // {minX, minZ, maxX, maxZ} in cells
          int reach = Math.max(Math.max(Math.abs(b[0]), Math.abs(b[2])), Math.max(Math.abs(b[1]), Math.abs(b[3]))) + 1;
          assertTrue(reach * 16 <= DungeonModule.SLOT_SPACING / 2, big + " seed " + seed + " reaches " + reach + " cells");
          for (var room : layout.rooms()) {
            var type = room.node().type();
            if (type == net.tfminecraft.trialrooms.dungeon.template.RoomType.COMBAT
                || type == net.tfminecraft.trialrooms.dungeon.template.RoomType.ELITE) {
              assertTrue(room.template().cellList().size() >= rules.minRoomCells(), big + " " + room.template().id());
            }
          }
        } catch (RuntimeException e) {
          // a seed the generator gives up on; the gate tries another
        }
      }
      assertTrue(made >= 10, big + ": " + made + " of 12 seeds make a layout");
    }
    assertEquals(4, m.recipes().get("warband_seithr").rules().minRoomCells());
    assertEquals(2.5, m.recipes().get("warband_seithr").rules().bossHealth());
    assertEquals(6, m.recipes().get("warband_seithr").rules().minPlayers());
    assertEquals(10, m.recipes().get("warband_seithr").rules().healthCap());
    assertEquals(5, m.recipes().get("warband_seithr").rules().maxDepth());
    assertEquals(1, m.cosmetics().trophies("warband_seithr", "nightmare").size(), "the bigger parties have trophies too");
    assertEquals(1, m.cosmetics().skins("dungeon_seithr", "hard").size());
    assertSame(world, m.world());
    assertNotNull(m.lootKit(), "the bundled loot.yml");
    assertEquals(49, m.lootKit().bases().size());
    assertSame(m.lootKit().sets(), m.sets());
    assertSame(m.lootKit().worth(), m.worth());
    assertNotNull(m.rewardRules(), "and its rewards");
    assertEquals(18.9, m.spoils().perHour().get("m.materials.ignitium"), "and its spoils");
    assertEquals(12, m.cosmetics().trophies().size(), "and its cosmetics");
    assertEquals(5, m.affixes().pool().size(), "and the weekly affixes from settings.yml");
    assertEquals(List.of(0.4, 0.35, 0.3, 0.25), m.combat().openBonus(), "and the combat section");
    assertEquals(List.of("Silenced", "Frozen", "Stun_Aura"), m.combat().interruptAuras());
    assertEquals(20, m.recipes().get("delve_seithr").rules().minutes());
    // a second start keeps edited files
    Files.writeString(data.resolve("dungeons/recipes.yml"), "x: 1\n");
    Files.delete(data.resolve("dungeons/kits/greybox/rooms/rest_camp.yml"));
    m.installDefaults();
    assertEquals("x: 1\n", Files.readString(data.resolve("dungeons/recipes.yml")));
    // a bundled kit installs once: a room staff deleted stays deleted
    assertFalse(Files.exists(data.resolve("dungeons/kits/greybox/rooms/rest_camp.yml")));
    Files.copy(DungeonModule.class.getClassLoader().getResourceAsStream(
        "dungeons/kits/greybox/rooms/rest_camp.yml"), data.resolve("dungeons/kits/greybox/rooms/rest_camp.yml"));

    Files.delete(data.resolve("dungeons/recipes.yml"));
    DungeonModule fresh = enabled();
    DungeonLayout layout = fresh.generate("delve_greybox", 11);
    CompletableFuture<Location> at = fresh.preview(layout);
    server.getScheduler().performTicks(layout.rooms().size() + 3);
    assertEquals(DungeonModule.SLOT_START_X, at.join().getBlockX() / 1024 * 1024);
    // structures are loaded once per room template
    fresh.preview(layout);
    server.getScheduler().performTicks(layout.rooms().size() + 3);
    assertEquals(2, fresh.clearPreviews());
    fresh.disable();
    assertThrows(IllegalArgumentException.class, () -> fresh.generate("nope", 1));
  }

  @Test
  void reportsProblemsAndRefusesWhatItCannotDo() throws Exception {
    // a foreign world folder keeps dungeons off; recipes for unknown kits are dropped
    Files.createDirectories(temp.resolve("worlds/" + DungeonModule.WORLD_NAME));
    Files.createDirectories(data.resolve("dungeons"));
    Files.writeString(data.resolve("dungeons/recipes.yml"), "lost:\n  kit: nope\n  trunk-pool: {combat: 1}\n");
    DungeonModule m = enabled();
    assertNull(m.world());
    assertTrue(m.problems().stream().anyMatch(p -> p.contains("unknown kit nope")));
    assertTrue(m.recipes().isEmpty());
    // a boss the kit doesn't have is reported, and the recipe still loads
    Files.writeString(data.resolve("dungeons/recipes.yml"), "typo:\n  kit: greybox\n  boss: bone_wardn\n  trunk-pool: {combat: 1}\n");
    m.reload();
    assertTrue(m.problems().stream().anyMatch(p -> p.contains("has no boss 'bone_wardn'")), m.problems().toString());
    // a recipe asking for bigger fight rooms than its kit has is refused
    Files.writeString(data.resolve("dungeons/recipes.yml"), "typo:\n  kit: greybox\n  boss: bone_wardn\n"
        + "  trunk-pool: {combat: 1}\n"
        + "big:\n  kit: greybox\n  trunk-pool: {combat: 1}\n  min-room-cells: 99\n"
        + "wide:\n  kit: greybox\n  trunk-pool: {corridor: 1}\n  branches: 1\n  branch-length: 2\n"
        + "  branch-pool: {elite: 1}\n  min-room-cells: 99\n"
        + "unused:\n  kit: greybox\n  trunk-pool: {corridor: 1}\n  branches: 0\n  branch-pool: {elite: 1}\n"
        + "  min-room-cells: 99\n");
    m.reload();
    assertTrue(m.problems().stream().anyMatch(p -> p.contains("big: kit greybox has no combat room of at least 99 cells")),
        m.problems().toString());
    assertTrue(m.problems().stream().anyMatch(p -> p.contains("wide: kit greybox has no elite room of at least 99 cells")),
        m.problems().toString());
    assertFalse(m.recipes().containsKey("big"));
    assertFalse(m.recipes().containsKey("wide"));
    assertTrue(m.recipes().containsKey("unused"), "a pool the generator never uses is no reason to refuse it");
    Files.writeString(data.resolve("dungeons/settings.yml"), "combat:\n  open-bonus: []\n");
    m.reload();
    assertTrue(m.problems().stream().anyMatch(p -> p.startsWith("settings.yml combat: ")), m.problems().toString());
    assertSame(net.tfminecraft.trialrooms.dungeon.combat.CombatSettings.DEFAULTS, m.combat());
    Files.delete(data.resolve("dungeons/settings.yml"));
    m.reload();
    assertSame(net.tfminecraft.trialrooms.dungeon.combat.CombatSettings.DEFAULTS, m.combat(), "no settings.yml: defaults");
    Files.delete(data.resolve("dungeons/loot.yml"));
    m.reload();
    assertNull(m.lootKit(), "no loot.yml: no loot");
    assertTrue(m.recipes().containsKey("typo"));
    // a kit that can only grow in a straight line: 20 corridors reach 700 blocks north
    Path line = data.resolve("dungeons/kits/line");
    Files.createDirectories(line.resolve("rooms"));
    Files.writeString(line.resolve("kit.yml"), "cell-size: 32\n");
    String[][] rooms = {{"s", "start", "north"}, {"c", "corridor", "north", "south"}, {"b", "boss", "south"}};
    for (String[] r : rooms) {
      StringBuilder y = new StringBuilder("type: " + r[1] + "\ndoorways:\n");
      for (int i = 2; i < r.length; i++) {
        y.append("  - {cell: '0,0', side: ").append(r[i]).append("}\n");
      }
      if (r[1].equals("boss")) {
        y.append("markers:\n  - {kind: spawn, tag: boss, at: '16,1,16'}\n");
      }
      Files.writeString(line.resolve("rooms/" + r[0] + ".yml"), y.toString());
      Files.write(line.resolve("rooms/" + r[0] + ".nbt"), new byte[] {1});
    }
    Files.writeString(data.resolve("dungeons/recipes.yml"),
        "big:\n  kit: line\n  trunk: 20\n  trunk-pool: {corridor: 1}\n  rest-before-boss: false\n  vault: false\n");
    m.reload();
    DungeonLayout big = m.generate("big", 1);
    assertTrue(m.preview(big).isCompletedExceptionally());
    // with the world open, a layout too large for a slot is refused
    Path owned = temp.resolve("worlds/" + DungeonModule.WORLD_NAME + "/trialrooms-dungeons.owner");
    Files.writeString(owned, "x");
    DungeonModule open = enabled();
    var tooBig = open.preview(open.generate("big", 1));
    assertTrue(tooBig.isCompletedExceptionally());
    // a structure that cannot be read
    when(structures.loadStructure(any(File.class))).thenThrow(new IOException("broken"));
    var kit = open.kits().get("greybox");
    assertThrows(java.io.UncheckedIOException.class, () -> open.structure(kit, "start_hall"));
    // a world that blows up while being created only turns dungeons off
    DungeonModule broken = new DungeonModule(plugin, c -> {
      throw new IllegalStateException("session lock");
    });
    broken.enable();
    assertNull(broken.world());
    // re-enabled while the world is still loaded: reuse it, never wipe or recreate it
    when(srv.getWorld(DungeonModule.WORLD_NAME)).thenReturn(world);
    DungeonModule again = new DungeonModule(plugin, c -> {
      throw new AssertionError("must not create");
    });
    again.enable();
    assertSame(world, again.world());
  }

  @Test
  void survivesMissingAndBrokenBundledFiles() throws IOException {
    when(plugin.getResource(anyString())).thenReturn(null);
    DungeonModule empty = new DungeonModule(plugin, c -> world);
    empty.installDefaults();
    assertFalse(Files.exists(data.resolve("dungeons/recipes.yml")));
    // no kits folder and no recipes.yml, then also a kit folder without kit.yml
    assertEquals(1, empty.reload().size());
    Files.createDirectories(data.resolve("dungeons/kits/broken"));
    assertEquals(2, empty.reload().size());
    // a stream that breaks mid-copy leaves no partial file behind
    when(plugin.getResource(anyString())).thenAnswer(i -> {
      String path = i.getArgument(0);
      return path.endsWith("files.txt") ? new InputStream() {
        @Override
        public int read() throws IOException {
          throw new IOException("boom");
        }
      } : new InputStream() {
        @Override
        public int read() throws IOException {
          throw new IOException("boom");
        }
      };
    });
    new DungeonModule(plugin, c -> world).installDefaults();
    assertFalse(Files.exists(data.resolve("dungeons/recipes.yml")));
    assertFalse(Files.exists(data.resolve("dungeons/recipes.yml.tmp")));
    // a listed file missing from the jar
    when(plugin.getResource(anyString())).thenAnswer(i -> {
      String path = i.getArgument(0);
      return path.endsWith(".nbt") ? null : DungeonModule.class.getClassLoader().getResourceAsStream(path);
    });
    new DungeonModule(plugin, c -> world).installDefaults();
    assertTrue(Files.exists(data.resolve("dungeons/kits/greybox/kit.yml")));
    assertFalse(Files.exists(data.resolve("dungeons/kits/greybox/rooms/boss_arena.nbt")));
    // a data folder whose "dungeons" path is a file: nothing can be written under it
    Path other = temp.resolve("other");
    Files.createDirectories(other);
    Files.writeString(other.resolve("dungeons"), "file");
    when(plugin.getDataFolder()).thenReturn(other.toFile());
    when(plugin.getResource(anyString()))
        .thenAnswer(i -> DungeonModule.class.getClassLoader().getResourceAsStream(i.getArgument(0)));
    new DungeonModule(plugin, c -> world).installDefaults();
    assertTrue(Files.isRegularFile(other.resolve("dungeons")));
  }

  @Test
  void commandsDrivePreviewsAndReloads() {
    DungeonModule m = enabled();
    DungeonCommand c = new DungeonCommand(m);
    CommandSender console = mock(CommandSender.class);
    c.run(console, new String[0]);
    verify(console).sendMessage(DungeonCommand.USAGE);
    c.run(console, new String[] {"map"});
    c.run(console, new String[] {"map", "delve_greybox", "x"});
    verify(console).sendMessage("§cThe seed must be a whole number.");
    c.run(console, new String[] {"map", "nope"});
    verify(console).sendMessage("§cUnknown recipe: nope");
    c.run(console, new String[] {"MAP", "delve_greybox", "4"});
    verify(console).sendMessage(startsWith("§adelve_greybox seed 4: "));
    c.run(console, new String[] {"map", "delve_greybox"});
    c.run(console, new String[] {"preview", "delve_greybox", "4"});
    server.getScheduler().performTicks(40);
    verify(console).sendMessage(startsWith("§aPreview built at "));
    Player player = mock(Player.class);
    c.run(player, new String[] {"preview", "delve_greybox", "5"});
    server.getScheduler().performTicks(40);
    verify(player).teleport(any(Location.class));
    c.run(console, new String[] {"clear"});
    verify(console).sendMessage("§aCleared 2 dungeon previews.");
    // a preview that fails (structures can no longer be read)
    m.reload();
    try {
      when(structures.loadStructure(any(File.class))).thenThrow(new IOException("gone"));
    } catch (IOException e) {
      fail(e);
    }
    c.run(console, new String[] {"preview", "delve_greybox", "6"});
    server.getScheduler().performTicks(5);
    verify(console).sendMessage(startsWith("§cPreview failed: "));
    c.run(console, new String[] {"reload"});
    verify(console).sendMessage(startsWith("§aDungeons reloaded: 2 kits, 4 recipes, 0 problems."));
    try {
      Files.writeString(data.resolve("dungeons/recipes.yml"), "bad:\n  trunk: x\n");
    } catch (IOException e) {
      fail(e);
    }
    c.run(console, new String[] {"reload"});
    verify(console).sendMessage(startsWith("§c- recipe bad"));
    assertEquals(List.of("map", "marks", "mobtest"), c.complete(new String[] {"M"}));
    assertEquals(List.of(), c.complete(new String[] {"map", "z"}));
    assertEquals(List.of(), c.complete(new String[] {"clear", ""}));
    assertEquals(List.of(), c.complete(new String[] {"map", "", ""}));
    try {
      Files.delete(data.resolve("dungeons/recipes.yml"));
    } catch (IOException e) {
      fail(e);
    }
    m.installDefaults();
    m.reload();
    assertEquals(java.util.Set.of("delve_greybox", "delve_seithr", "dungeon_seithr"),
        java.util.Set.copyOf(c.complete(new String[] {"preview", "d"})));
  }

  @Test
  void trCommandRoutesDungeonForStaffOnly() {
    DungeonModule m = enabled();
    TrialRooms tr = mock(TrialRooms.class);
    when(tr.getDungeons()).thenReturn(m);
    try (MockedStatic<TrialRooms> s = mockStatic(TrialRooms.class)) {
      s.when(TrialRooms::getInstance).thenReturn(tr);
      CommandManager cm = new CommandManager();
      Command cmd = mock(Command.class);
      CommandSender noPerm = mock(CommandSender.class);
      assertTrue(cm.onCommand(noPerm, cmd, "tr", new String[] {"dungeon", "clear"}));
      verify(noPerm).sendMessage("§cYou don't have permission to use this command.");
      CommandSender staff = mock(CommandSender.class);
      when(staff.hasPermission("trialrooms.admin")).thenReturn(true);
      assertTrue(cm.onCommand(staff, cmd, "tr", new String[] {"DUNGEON", "clear"}));
      verify(staff).sendMessage("§aCleared 0 dungeon previews.");
      var p = server.addPlayer();
      p.setOp(true);
      assertEquals(List.of("dungeon"), cm.onTabComplete(p, cmd, "tr", new String[] {"d"}));
      assertEquals(List.of("preview"), cm.onTabComplete(p, cmd, "tr", new String[] {"dungeon", "p"}));
    }
  }
}
