package net.tfminecraft.trialrooms;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.trialrooms.cache.Cache;
import net.tfminecraft.trialrooms.environment.entrance.LootConversion;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.MockedStatic;

class LootConversionTest extends TrialTestSupport {
  TestMoney money;
  PlayerMock player;
  MockedStatic<Cache> cache;

  @BeforeEach
  void prepare() {
    money = TestMoney.open(temp);
    player = server.addPlayer();
    player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
    cache = mockStatic(Cache.class);
    cache.when(() -> Cache.getConversionAmount(any())).thenReturn(1.25);
  }

  @AfterEach
  void close() {
    cache.close();
    TestMoney.close();
  }

  LootConversion plan() {
    return LootConversion.plan(player, temp.resolve("pending"));
  }

  @Test
  void paysCapturedCharacterAndTaxOnceThenConsumesOnlyPlannedLoot() throws Exception {
    var ref = money.play(player);
    String taxes = TestMoney.taxAt(20);
    var conversion = plan();
    assertEquals(3, conversion.count());
    assertEquals(375, conversion.cents());
    assertEquals(3, player.getInventory().getItem(0).getAmount());
    assertEquals(Result.APPLIED, conversion.settle());
    assertEquals(Result.APPLIED, conversion.settle());
    assertEquals(300, Denars.balance(Denars.pouch(ref)));
    assertEquals(75, Denars.balance(taxes));
    assertNull(player.getInventory().getItem(0));
    assertFalse(Files.exists(temp.resolve("pending/" + player.getUniqueId() + ".properties")));
  }

  @Test
  void noSessionOrNoPayableValueKeepsItems() {
    assertNull(plan());
    assertEquals("§cChoose a character before converting loot.", player.nextMessage());
    money.play(player);
    for (double value : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, 0.0001}) {
      cache.when(() -> Cache.getConversionAmount(any())).thenReturn(value);
      assertNull(plan());
      assertEquals(3, player.getInventory().getItem(0).getAmount());
    }
  }

  @Test
  void unavailablePaymentIsSavedBeforePostingAndRetryUsesTheSameOperation() throws Exception {
    var ref = money.play(player);
    var conversion = plan();
    try (var denars = mockStatic(Denars.class, CALLS_REAL_METHODS)) {
      denars.when(() -> Denars.earn(anyString(), any(), anyString(), anyLong(), eq(true)))
          .thenReturn(Result.UNAVAILABLE);
      assertEquals(Result.UNAVAILABLE, conversion.settle());
    }
    var file = temp.resolve("pending/" + player.getUniqueId() + ".properties");
    Properties intent = new Properties();
    try (var stream = Files.newInputStream(file)) { intent.load(stream); }
    assertEquals(ref.characterId().toString(), intent.getProperty("character"));
    assertEquals("375", intent.getProperty("grossCents"));
    assertThrows(IOException.class, plan()::settle);
    assertEquals(Result.APPLIED, conversion.settle());
    assertEquals(375, Denars.balance(Denars.pouch(ref)));
    assertEquals(Result.APPLIED, Denars.status(intent.getProperty("operation")).orElseThrow());
    assertNull(player.getInventory().getItem(0));
  }

  @Test
  void refusedAndConflictingPaymentsNeverTakeItems() throws Exception {
    var ref = money.play(player);
    Denars.transfer("fund", "staff:test", Denars.MINT, Denars.pouch(ref), Long.MAX_VALUE - 1);
    var refused = plan();
    assertEquals(Result.REFUSED, refused.settle());
    assertEquals(Result.REFUSED, refused.settle());
    assertEquals(3, player.getInventory().getItem(0).getAmount());
    var conflict = plan();
    try (var denars = mockStatic(Denars.class, CALLS_REAL_METHODS)) {
      denars.when(() -> Denars.earn(anyString(), any(), anyString(), anyLong(), eq(true)))
          .thenReturn(Result.CONFLICT);
      assertEquals(Result.CONFLICT, conflict.settle());
    }
    assertEquals(3, player.getInventory().getItem(0).getAmount());
    assertTrue(Files.exists(temp.resolve("pending/" + player.getUniqueId() + ".properties")));
  }

  @Test
  void paidButInterruptedConversionCannotPayAgainAfterInventoryOrTaxChanges() throws Exception {
    var ref = money.play(player);
    var conversion = plan();
    try (var denars = mockStatic(Denars.class, CALLS_REAL_METHODS)) {
      denars.when(() -> Denars.earn(anyString(), any(), anyString(), anyLong(), eq(true)))
          .thenAnswer(call -> {
            assertTrue(Files.readString(temp.resolve("pending/" + player.getUniqueId() + ".properties"))
                .contains("grossCents=375"));
            call.callRealMethod();
            throw new IllegalStateException("interrupted after posting");
          });
      assertThrows(IllegalStateException.class, conversion::settle);
    }
    TestMoney.taxAt(20);
    player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 2));
    assertThrows(IOException.class, conversion::settle);
    assertThrows(IOException.class, plan()::settle);
    assertEquals(375, Denars.balance(Denars.pouch(ref)));
    player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
    assertEquals(Result.APPLIED, conversion.settle());
    assertEquals(375, Denars.balance(Denars.pouch(ref)));
    assertNull(player.getInventory().getItem(0));
  }

  @Test
  void intentWriteFailureCannotCreditOrRemoveLoot() throws Exception {
    var ref = money.play(player);
    Files.writeString(temp.resolve("pending"), "unwritable directory");
    assertThrows(IOException.class, plan()::settle);
    assertEquals(0, Denars.balance(Denars.pouch(ref)));
    assertEquals(3, player.getInventory().getItem(0).getAmount());
  }

  @Test void waitingForFactionTaxDoesNotCreateAnAmbiguousPayment() throws Exception {
    money.play(player);
    org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin("SimpleFactions");
    var conversion=plan();
    assertEquals(Result.UNAVAILABLE,conversion.settle());
    assertFalse(Files.exists(temp.resolve("pending")));
    assertEquals(3,player.getInventory().getItem(0).getAmount());
    TestMoney.taxAt(20);
    assertEquals(Result.APPLIED,conversion.settle());
  }
}
