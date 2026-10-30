package net.tfminecraft.trialrooms.environment.spawner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.tfminecraft.trialrooms.TrialTestSupport;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;

class LabelCleanupTest extends TrialTestSupport {
  World world;
  World otherWorld;
  // Spawner block at (4126, 278, 2533): level label at y+1.0, status label at y+1.5.
  Location block;
  Location levelPos;
  Location statusPos;
  Hologram level;
  Hologram status;
  final int cx = 4126 >> 4;
  final int cz = 2533 >> 4;

  @BeforeEach
  void labels() {
    world = mock(World.class);
    otherWorld = mock(World.class);
    when(world.getName()).thenReturn("world");
    block = new Location(world, 4126, 278, 2533);
    levelPos = block.clone().add(.5, 1, .5);
    statusPos = block.clone().add(.5, 1.5, .5);
    // Same wiring as ActiveSpawner.
    level = new Hologram(() -> block, () -> "§7[§eLVL: 3§7]", LegacyLabelTexts::spawnerLevel);
    status = new Hologram(() -> block, () -> "§7Skeleton Wizard: §f2", LegacyLabelTexts::spawnerStatus);
    status.setYOffset(1.5);
  }

  @AfterEach
  void unloadWorld() {
    // Holograms stay registered until collected; make their world look unloaded to later tests.
    reset(world);
  }

  /** A display loaded from disk the way older versions saved it, showing {@code text}. */
  private TextDisplay legacy(Location at, String text) {
    var td = mock(TextDisplay.class);
    var pdc = mock(PersistentDataContainer.class);
    when(td.getUniqueId()).thenReturn(UUID.randomUUID());
    when(td.getPersistentDataContainer()).thenReturn(pdc);
    when(td.getLocation()).thenReturn(at.clone());
    when(td.isPersistent()).thenReturn(true);
    when(pdc.isEmpty()).thenReturn(true);
    when(td.getScoreboardTags()).thenReturn(Set.of());
    when(td.getBillboard()).thenReturn(Display.Billboard.CENTER);
    when(td.isShadowed()).thenReturn(true);
    when(td.text()).thenReturn(Component.text(text));
    return td;
  }

  private TextDisplay tagged(Location at) {
    var td = legacy(at, "anything");
    when(td.isPersistent()).thenReturn(false);
    when(td.getPersistentDataContainer().isEmpty()).thenReturn(false);
    when(td.getPersistentDataContainer().has(Hologram.LABEL_KEY, PersistentDataType.BYTE))
        .thenReturn(true);
    return td;
  }

  private Chunk chunk(int x, int z) {
    var chunk = mock(Chunk.class);
    when(chunk.getWorld()).thenReturn(world);
    when(chunk.getX()).thenReturn(x);
    when(chunk.getZ()).thenReturn(z);
    return chunk;
  }

  @Test
  void entitiesLoadRemovesEveryStackedLegacyCopyAtTheLabelPositions() {
    var copies = new ArrayList<Entity>();
    // Texts as found on Main: frozen when saved, so they differ from the current text.
    for (int i = 0; i < 659; i++) {
      copies.add(legacy(levelPos, "[LVL: 1]"));
      copies.add(legacy(statusPos, i % 2 == 0 ? "Cooldown 18m 10s" : "Skeleton Wizard: 2"));
    }
    copies.add(mock(Zombie.class));
    new LabelCleanup().onEntitiesLoad(new EntitiesLoadEvent(chunk(cx, cz), copies));
    for (var e : copies) if (e instanceof TextDisplay td) verify(td).remove();
  }

  @Test
  void otherPluginsDisplaysAndTheCurrentLabelAreNeverRemoved() {
    var player = mock(Player.class);
    when(player.getWorld()).thenReturn(world);
    when(player.getLocation()).thenReturn(block);
    when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
    var current = tagged(levelPos);
    var currentId = UUID.randomUUID();
    when(current.getUniqueId()).thenReturn(currentId);
    when(world.spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class)))
        .thenAnswer(
            a -> {
              ((Consumer<TextDisplay>) a.getArgument(2)).accept(current);
              return current;
            });
    try (var b = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
      b.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
      b.when(() -> Bukkit.getEntity(currentId)).thenReturn(current);
      level.tick();
    }
    assertTrue(Hologram.ownedDisplayIds().contains(currentId));

    var keep = new ArrayList<TextDisplay>();
    keep.add(current);
    var withOtherPdc = legacy(levelPos, "[LVL: 1]");
    when(withOtherPdc.getPersistentDataContainer().isEmpty()).thenReturn(false);
    keep.add(withOtherPdc);
    var withTag = legacy(levelPos, "[LVL: 1]");
    when(withTag.getScoreboardTags()).thenReturn(Set.of("speech"));
    keep.add(withTag);
    var runtimeOnly = legacy(levelPos, "[LVL: 1]");
    when(runtimeOnly.isPersistent()).thenReturn(false);
    keep.add(runtimeOnly);
    var fixed = legacy(levelPos, "[LVL: 1]");
    when(fixed.getBillboard()).thenReturn(Display.Billboard.FIXED);
    keep.add(fixed);
    var unshadowed = legacy(levelPos, "[LVL: 1]");
    when(unshadowed.isShadowed()).thenReturn(false);
    keep.add(unshadowed);
    var seeThrough = legacy(levelPos, "[LVL: 1]");
    when(seeThrough.isSeeThrough()).thenReturn(true);
    keep.add(seeThrough);
    var background = legacy(levelPos, "[LVL: 1]");
    when(background.isDefaultBackground()).thenReturn(true);
    keep.add(background);
    // Exactly like an old label, at the label position, but showing someone else's text.
    keep.add(legacy(levelPos, "Welcome to the dungeon"));
    keep.add(legacy(statusPos, "Speech: hello"));
    var noText = legacy(levelPos, "");
    when(noText.text()).thenReturn(null);
    keep.add(noText);
    var riding = legacy(levelPos, "[LVL: 1]");
    when(riding.getVehicle()).thenReturn(mock(Player.class));
    keep.add(riding);
    var dead = legacy(levelPos, "[LVL: 1]");
    when(dead.isDead()).thenReturn(true);
    keep.add(dead);
    keep.add(legacy(levelPos.clone().add(.01, 0, 0), "[LVL: 1]"));
    keep.add(legacy(levelPos.clone().add(0, .01, 0), "[LVL: 1]"));
    keep.add(legacy(levelPos.clone().add(0, 0, .01), "[LVL: 1]"));
    keep.add(legacy(levelPos.clone().add(0, .25, 0), "[LVL: 1]"));
    keep.add(legacy(new Location(otherWorld, 4126.5, 279, 2533.5), "[LVL: 1]"));
    keep.add(legacy(new Location(null, 4126.5, 279, 2533.5), "[LVL: 1]"));

    assertEquals(0, LabelCleanup.removeLeftovers(world, cx, cz, keep));
    for (var e : keep) verify(e, never()).remove();

    // Tiny floating point noise still counts as the label position.
    var noisy = legacy(levelPos.clone().add(1e-6, -1e-6, 1e-6), "[LVL: 1]");
    var lostTagged = tagged(statusPos);
    assertEquals(2, LabelCleanup.removeLeftovers(world, cx, cz, List.of(current, noisy, lostTagged)));
    verify(noisy).remove();
    verify(lostTagged).remove();
    verify(current, never()).remove();
  }

  @Test
  void disposedLabelsNoLongerClaimTheirPosition() {
    var gone = legacy(levelPos, "[LVL: 1]");
    level.dispose(plugin);
    assertEquals(0, LabelCleanup.removeLeftovers(world, cx, cz, List.of(gone)));
    verify(gone, never()).remove();
  }

  @Test
  void chunksWithoutLabelsOrDisplaysAreSkippedCheaply() {
    var copy = legacy(levelPos, "[LVL: 1]");
    assertEquals(0, LabelCleanup.removeLeftovers(world, cx, cz, List.of(mock(Zombie.class))));
    assertEquals(0, LabelCleanup.removeLeftovers(world, cx, cz, List.of()));
    // Same entity list, but the event's chunk/world holds no TrialRooms label.
    assertEquals(0, LabelCleanup.removeLeftovers(world, cx + 1, cz, List.of(copy)));
    assertEquals(0, LabelCleanup.removeLeftovers(world, cx, cz + 1, List.of(copy)));
    assertEquals(0, LabelCleanup.removeLeftovers(otherWorld, cx, cz, List.of(copy)));
    verify(copy, never()).remove();
    verify(copy, never()).getPersistentDataContainer();
  }

  @Test
  void enableSweepCleansOnlyLoadedLabelChunksWithLoadedEntities() {
    var farBlock = new Location(world, 100, 64, 100);
    var far = new Hologram(() -> farBlock, () -> "far");
    var waitingBlock = new Location(world, 200, 64, 200);
    var waiting = new Hologram(() -> waitingBlock, () -> "waiting");
    var loaded = chunk(cx, cz);
    var stillLoading = chunk(200 >> 4, 200 >> 4);
    var emptyBlock = new Location(world, 300, 64, 300);
    var empty = new Hologram(() -> emptyBlock, () -> "empty");
    var noDisplays = chunk(300 >> 4, 300 >> 4);
    var copy = legacy(levelPos, "[LVL: 1]");
    var copy2 = legacy(statusPos, "Cooldown 18m 10s");
    when(loaded.isEntitiesLoaded()).thenReturn(true);
    when(loaded.getEntities()).thenReturn(new Entity[] {copy, copy2});
    when(noDisplays.isEntitiesLoaded()).thenReturn(true);
    when(noDisplays.getEntities()).thenReturn(new Entity[] {mock(Zombie.class)});
    when(world.isChunkLoaded(cx, cz)).thenReturn(true);
    when(world.isChunkLoaded(200 >> 4, 200 >> 4)).thenReturn(true);
    when(world.isChunkLoaded(300 >> 4, 300 >> 4)).thenReturn(true);
    when(world.getChunkAt(cx, cz)).thenReturn(loaded);
    when(world.getChunkAt(200 >> 4, 200 >> 4)).thenReturn(stillLoading);
    when(world.getChunkAt(300 >> 4, 300 >> 4)).thenReturn(noDisplays);

    assertEquals(2, LabelCleanup.sweepLoadedChunks());
    verify(copy).remove();
    verify(copy2).remove();
    // Both labels share one chunk: it is read once.
    verify(loaded, times(1)).getEntities();
    verify(stillLoading, never()).getEntities();
    verify(world, never()).getChunkAt(100 >> 4, 100 >> 4);
    assertNotNull(far);
    assertNotNull(waiting);
    assertNotNull(empty);
  }

  @Test
  void legacyTextsCoverEveryTextTheOwnersProduce() {
    assertTrue(LegacyLabelTexts.spawnerLevel("[LVL: 1]"));
    assertTrue(LegacyLabelTexts.spawnerLevel("[LVL: 100]"));
    assertFalse(LegacyLabelTexts.spawnerLevel("[LVL: ]"));
    assertFalse(LegacyLabelTexts.spawnerLevel("Level 1"));
    for (var s :
        List.of(
            "Skeleton Wizard: 2",
            "rpg_skeleton: 1",
            "Unlocking…",
            "Cooldown 18m 10s",
            "Cooldown 0s",
            "Enemies Alive: 3 (Queued: 0)",
            "Enemies Alive: 0",
            "Spawning… (Queued: 2)"))
      assertTrue(LegacyLabelTexts.spawnerStatus(s), s);
    assertFalse(LegacyLabelTexts.spawnerStatus("Welcome"));
    assertFalse(LegacyLabelTexts.spawnerStatus("Cooldown"));
    assertTrue(LegacyLabelTexts.entrance("Right-Click with a Key\nto enter"));
    assertFalse(LegacyLabelTexts.entrance("Right-Click with a Key"));
    assertFalse(LegacyLabelTexts.entrance("Click to enter"));
  }

  @Test
  void samePositionNeedsTheSameWorld() {
    assertTrue(LabelCleanup.samePosition(levelPos, levelPos.clone()));
    assertFalse(LabelCleanup.samePosition(levelPos, new Location(otherWorld, 4126.5, 279, 2533.5)));
    assertFalse(LabelCleanup.samePosition(new Location(null, 4126.5, 279, 2533.5), levelPos));
  }
}
