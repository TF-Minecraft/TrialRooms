package net.tfminecraft.trialrooms.dungeon.run;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import io.lumine.mythic.core.mobs.MobExecutor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.rpcharacters.party.Party;
import net.tfminecraft.rpcharacters.party.PartyManager;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.objects.api.subapi.ItemChecker;
import net.tfminecraft.tlibs.objects.api.subapi.ItemCreator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class AdaptersTest {
  ServerMock server;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  @Test
  void charactersAndPartiesComeFromRpCharacters() {
    PlayerMock p = server.addPlayer();
    UUID other = UUID.randomUUID();
    PlayerData data = mock(PlayerData.class);
    RPCharacter character = mock(RPCharacter.class);
    when(character.getId()).thenReturn("c1");
    PartyManager parties = mock(PartyManager.class);
    Party party = mock(Party.class);
    when(party.getLeaderId()).thenReturn(other);
    when(party.getMemberIds()).thenReturn(Set.of(other));
    java.util.concurrent.atomic.AtomicReference<PlayerData> current = new java.util.concurrent.atomic.AtomicReference<>();
    var c = new Adapters.RpcCharacters(x -> current.get(), () -> parties);
    assertNotNull(new Adapters.RpcCharacters());
    assertNull(c.activeCharacterId(p));
    current.set(data);
    assertNull(c.activeCharacterId(p));
    when(data.hasActiveCharacter()).thenReturn(true);
    when(data.getActiveCharacter()).thenReturn(character);
    assertEquals("c1", c.activeCharacterId(p));
    assertNull(c.partyLeader(p));
    assertEquals(List.of(p.getUniqueId()), c.partyMembers(p));
    when(parties.getParty(p.getUniqueId())).thenReturn(party);
    assertEquals(other, c.partyLeader(p));
    assertEquals(List.of(other), c.partyMembers(p));
  }

  @Test
  void keysAreCheckedTakenAndGivenThroughTLibs() {
    PlayerMock p = server.addPlayer();
    ItemAPI api = mock(ItemAPI.class);
    ItemChecker checker = mock(ItemChecker.class);
    ItemCreator creator = mock(ItemCreator.class);
    when(api.getChecker()).thenReturn(checker);
    when(api.getCreator()).thenReturn(creator);
    ItemStack key = new ItemStack(Material.TRIPWIRE_HOOK, 2);
    when(checker.checkItemWithPath(any(), eq("k"))).thenAnswer(i -> {
      ItemStack it = i.getArgument(0);
      return it != null && it.getType() == Material.TRIPWIRE_HOOK;
    });
    when(creator.getItemFromPath("k")).thenAnswer(i -> new ItemStack(Material.TRIPWIRE_HOOK));
    try (var t = mockStatic(TLibs.class)) {
      t.when(TLibs::getItemAPI).thenReturn(api);
      var keys = new Adapters.TLibsKeys();
      assertFalse(keys.holds(p, "k"));
      assertFalse(keys.take(p, "k"));
      p.getInventory().setItem(3, new ItemStack(Material.STONE));
      p.getInventory().setItem(5, key);
      p.getInventory().setHeldItemSlot(5);
      assertTrue(keys.holds(p, "k"));
      assertTrue(keys.take(p, "k"));
      assertEquals(1, p.getInventory().getItem(5).getAmount());
      assertTrue(keys.take(p, "k"));
      assertNull(p.getInventory().getItem(5));
      keys.give(p, "k");
      assertTrue(p.getInventory().contains(Material.TRIPWIRE_HOOK));
      // a full inventory drops the key at the player's feet
      org.bukkit.entity.Player full = mock(org.bukkit.entity.Player.class);
      org.bukkit.inventory.PlayerInventory inv = mock(org.bukkit.inventory.PlayerInventory.class);
      org.bukkit.World world = mock(org.bukkit.World.class);
      when(full.getInventory()).thenReturn(inv);
      when(full.getWorld()).thenReturn(world);
      when(inv.addItem(any(ItemStack[].class))).thenAnswer(i -> new java.util.HashMap<>(Map.of(0, (ItemStack) i.getArgument(0))));
      keys.give(full, "k");
      verify(world).dropItemNaturally(any(), any(ItemStack.class));
    }
  }

  @Test
  void fightEffectsAreParticlesAndMagicDamage() {
    org.bukkit.World w = mock(org.bukkit.World.class);
    var fx = new Adapters.BukkitFx();
    fx.ring(w, 0, 64, 0, 4);
    verify(w, times(24)).spawnParticle(eq(org.bukkit.Particle.FLAME), anyDouble(), anyDouble(), anyDouble(), eq(1),
        eq(0.0), eq(0.0), eq(0.0), eq(0.0));
    fx.ring(w, 0, 64, 0, 1);
    verify(w, times(24 + 12)).spawnParticle(eq(org.bukkit.Particle.FLAME), anyDouble(), anyDouble(), anyDouble(),
        eq(1), eq(0.0), eq(0.0), eq(0.0), eq(0.0));
    fx.burst(new Location(w, 1, 2, 3));
    verify(w).spawnParticle(eq(org.bukkit.Particle.ENCHANT), eq(1.0), eq(3.0), eq(3.0), eq(8), anyDouble(),
        anyDouble(), anyDouble(), anyDouble());
    org.bukkit.damage.DamageSource.Builder b = mock(org.bukkit.damage.DamageSource.Builder.class);
    when(b.withCausingEntity(any())).thenReturn(b);
    when(b.withDirectEntity(any())).thenReturn(b);
    org.bukkit.damage.DamageSource built = mock(org.bukkit.damage.DamageSource.class);
    when(b.build()).thenReturn(built);
    org.bukkit.entity.Player p = mock(org.bukkit.entity.Player.class);
    Entity boss = mock(Entity.class);
    try (var ds = mockStatic(org.bukkit.damage.DamageSource.class)) {
      ds.when(() -> org.bukkit.damage.DamageSource.builder(any())).thenReturn(b);
      fx.hurt(p, 5, boss);
      fx.hurt(p, 3, null);
    }
    verify(b).withCausingEntity(boss);
    verify(p).damage(5, built);
    verify(p).damage(3, built);
    // the do-nothing effects: no particles, plain damage
    Ports.Fx.NONE.ring(w, 0, 0, 0, 1);
    Ports.Fx.NONE.burst(new Location(w, 0, 0, 0));
    Ports.Fx.NONE.hurt(p, 2, boss);
    Ports.Fx.NONE.hurt(p, 1, null);
    verify(p).damage(2, boss);
    verify(p).damage(1);
  }

  @Test
  void mobsSpawnAndDespawnThroughMythicMobs() {
    MythicBukkit mythic = mock(MythicBukkit.class);
    MobExecutor exec = mock(MobExecutor.class);
    when(mythic.getMobManager()).thenReturn(exec);
    Entity bukkit = mock(Entity.class);
    UUID id = UUID.randomUUID();
    when(bukkit.getUniqueId()).thenReturn(id);
    ActiveMob am = mock(ActiveMob.class);
    AbstractEntity abs = mock(AbstractEntity.class);
    when(abs.getBukkitEntity()).thenReturn(bukkit);
    when(am.getEntity()).thenReturn(abs);
    Location at = new Location(server.addSimpleWorld("w"), 0, 0, 0);
    when(exec.spawnMob("skel", at, 2.0)).thenReturn(am);
    when(exec.getActiveMob(id)).thenReturn(Optional.of(am));
    try (var mb = mockStatic(MythicBukkit.class)) {
      mb.when(MythicBukkit::inst).thenReturn(mythic);
      var mobs = new Adapters.MythicMobsAdapter();
      assertSame(bukkit, mobs.spawn("skel", at, 2));
      assertNull(mobs.spawn("nope", at, 2));
      mobs.remove(bukkit);
      verify(am).remove();
      verify(bukkit).remove();
      when(exec.getActiveMob(id)).thenReturn(Optional.empty());
      mobs.remove(bukkit);
      verify(bukkit, times(2)).remove();
    }
    assertNotNull(Map.of());
  }
}
