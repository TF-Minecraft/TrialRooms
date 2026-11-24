package net.tfminecraft.trialrooms.dungeon.run;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.trialrooms.dungeon.run.RunRewards.Find;

class FindsMenuTest {

  ServerMock server;
  Plugin plugin;
  RunManager runs;
  PlayerMock ann;
  Find event = new Find(RunRewards.Moment.EVENT, 0, "8 dungeon marks", 10, 8, false, List.of(), List.of(), true);
  Find spoils = new Find(null, 0.5, "Spoils: 9 Ignitium", 30, 0, false, List.of(), List.of(), true);
  Find ring = new Find(RunRewards.Moment.BOSS, 0, "Rimebound Ring", 50, 0, false, List.of(), List.of(), true);

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
    plugin = MockBukkit.createMockPlugin();
    runs = mock(RunManager.class);
    ann = server.addPlayer("Ann");
    when(runs.icon(any())).thenAnswer(i -> {
      Find f = i.getArgument(0);
      return new ItemStack(f == ring ? Material.GOLD_NUGGET : f.moment() == null ? Material.BUNDLE : Material.EMERALD);
    });
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  static List<String> lore(ItemStack it) {
    List<String> out = new ArrayList<>();
    if (it != null && it.hasItemMeta() && it.getItemMeta().hasLore()) {
      it.getItemMeta().lore().forEach(c -> out.add(PlainTextComponentSerializer.plainText().serialize(c)));
    }
    return out;
  }

  @Test
  void anItemKeepsItsOwnLoreAboveTheScreensLines() {
    ItemStack named = new ItemStack(Material.DIAMOND_SWORD);
    named.editMeta(m -> m.lore(List.of(net.kyori.adventure.text.Component.text("Damage +12"))));
    when(runs.icon(ring)).thenReturn(named);
    RunFinds.Held h = new RunFinds.Held();
    h.banked.add(ring);
    when(runs.heldBy(ann)).thenReturn(h);
    FindsMenu m = new FindsMenu(runs, plugin, ann, FindsMenu.Mode.VIEW);
    m.open();
    assertEquals(List.of("Damage +12", "Value 50", "Banked"), lore(m.getInventory().getItem(9)));
    assertEquals(List.of("Damage +12"), lore(named), "the icon itself is left alone");
  }

  @Test
  void theViewShowsWhatIsCarriedAndWhatIsBanked() {
    RunFinds.Held h = new RunFinds.Held();
    h.carried.add(event);
    h.banked.add(spoils);
    h.banked.add(ring);
    when(runs.heldBy(ann)).thenReturn(h);
    when(runs.lootRules(ann)).thenReturn(List.of("Fall, and what you carry is lost."));
    FindsMenu m = new FindsMenu(runs, plugin, ann, FindsMenu.Mode.VIEW);
    m.open();
    assertSame(m, ann.getOpenInventory().getTopInventory().getHolder());
    assertTrue(lore(m.getInventory().getItem(FindsMenu.INFO)).contains("Carried: value 10 · Banked: value 80"));
    assertEquals(List.of("8 dungeon marks", "Value 10", "Carried: lost if you fall"), lore(m.getInventory().getItem(9)));
    assertEquals(List.of("Spoils: 9 Ignitium", "Value 30", "Banked"), lore(m.getInventory().getItem(10)));
    assertEquals(List.of("Value 50", "Banked"), lore(m.getInventory().getItem(11)), "an item names itself");
    m.click(9, false); // a screen to look at
    for (int i = 0; i < 40; i++) {
      h.carried.add(event);
    }
    m.render();
    assertNotNull(m.getInventory().getItem(FindsMenu.FIRST + FindsMenu.SHOWN - 1));
    assertNull(m.getInventory().getItem(FindsMenu.FIRST + FindsMenu.SHOWN), "as many as fit");
    h.carried.clear();
    for (int i = 0; i < 40; i++) {
      h.banked.add(spoils);
    }
    m.render();
    assertNull(m.getInventory().getItem(FindsMenu.FIRST + FindsMenu.SHOWN));
    when(runs.heldBy(ann)).thenReturn(null);
    m.render();
    assertTrue(m.getInventory().isEmpty(), "out of the run: nothing to show");
  }

  @Test
  void theWayOutTakesPicksWithinWhatItAllows() {
    RunFinds.Offer offer = new RunFinds.Offer(List.of(ring), List.of(event, spoils), 30);
    when(runs.exitOffer(ann)).thenReturn(offer);
    FindsMenu m = new FindsMenu(runs, plugin, ann, FindsMenu.Mode.EXIT);
    m.open();
    assertTrue(lore(m.getInventory().getItem(FindsMenu.INFO)).contains("· your banked finds, and the finds you pick"));
    assertEquals(List.of("Value 50", "Comes home"), lore(m.getInventory().getItem(9)));
    m.click(9, false); // what comes home anyway can't be picked
    m.click(10, false);
    assertEquals(List.of(0), m.picks());
    assertTrue(m.getInventory().getItem(10).getItemMeta().getEnchantmentGlintOverride());
    m.click(11, false);
    assertEquals(List.of(0), m.picks(), "10 + 30 is past 30");
    assertTrue(ann.nextMessage().contains("goes past what the way out lets you take (30)"));
    m.click(10, false);
    assertEquals(List.of(), m.picks(), "put back");
    m.click(11, false);
    assertEquals(List.of(1), m.picks());
    assertTrue(lore(m.getInventory().getItem(FindsMenu.CONFIRM)).contains("Picked: value 30 of 30"));
    m.click(30, false);
    when(runs.exitOffer(ann)).thenReturn(new RunFinds.Offer(List.of(ring), List.of(event), 30));
    m.render();
    assertEquals(List.of(), m.picks(), "a pick no longer at risk is dropped");
    when(runs.exitOffer(ann)).thenReturn(offer);
    m.render();
    m.click(11, false);
    when(runs.exitWith(ann, List.of(1))).thenReturn("§cno");
    m.click(FindsMenu.CONFIRM, false);
    server.getScheduler().performOneTick();
    verify(runs).exitWith(ann, List.of(1));
    assertEquals("§cno", ann.nextMessage());
    when(runs.exitWith(ann, List.of(1))).thenReturn(null);
    m.click(FindsMenu.CONFIRM, false);
    server.getScheduler().performOneTick();
    assertNull(ann.nextMessage());
    m.open();
    m.click(FindsMenu.CANCEL, false);
    server.getScheduler().performOneTick();
    // only picks when nothing is safe; more finds than fit
    List<Find> many = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      many.add(event);
    }
    when(runs.exitOffer(ann)).thenReturn(new RunFinds.Offer(List.of(), many, 400));
    FindsMenu n = new FindsMenu(runs, plugin, ann, FindsMenu.Mode.EXIT);
    n.open();
    assertTrue(lore(n.getInventory().getItem(FindsMenu.INFO)).contains("· only the finds you pick"));
    assertNotNull(n.getInventory().getItem(FindsMenu.FIRST + FindsMenu.SHOWN - 1));
    assertEquals(Material.BARRIER, n.getInventory().getItem(FindsMenu.CANCEL).getType(), "as many as fit");
    when(runs.exitOffer(ann)).thenReturn(new RunFinds.Offer(many, List.of(), 0));
    n.render();
    assertEquals(Material.BARRIER, n.getInventory().getItem(FindsMenu.CANCEL).getType());
    when(runs.exitOffer(ann)).thenReturn(null);
    n.render();
    assertTrue(n.getInventory().isEmpty());
    n.click(10, false); // no way out any more: nothing happens
  }

  @Test
  void insuranceListsTheWornPiecesAndTheirCost() {
    ItemStack helm = new ItemStack(Material.IRON_HELMET);
    when(runs.insurable(ann)).thenReturn(List.of(new RunFinds.Insurable("x", helm, 50, 10),
        new RunFinds.Insurable("y", new ItemStack(Material.IRON_BOOTS), 30, 6)));
    when(runs.insured(ann)).thenReturn("x");
    when(runs.marksOf(ann)).thenReturn(-1);
    when(runs.insure(ann, "y")).thenReturn("§aInsured");
    FindsMenu m = new FindsMenu(runs, plugin, ann, FindsMenu.Mode.INSURE);
    m.open();
    assertTrue(lore(m.getInventory().getItem(FindsMenu.INFO)).contains("Your marks: 0"));
    assertEquals(List.of("Value 50", "Insured: click to cancel"), lore(m.getInventory().getItem(9)));
    assertEquals(List.of("Value 30", "Insure for 6 marks"), lore(m.getInventory().getItem(10)));
    m.click(10, false);
    assertEquals("§aInsured", ann.nextMessage());
    m.click(8, false);
    m.click(11, false);
    assertNull(ann.nextMessage());
    when(runs.insurable(ann)).thenReturn(List.of());
    m.render();
    assertEquals(Material.GRAY_DYE, m.getInventory().getItem(FindsMenu.FIRST + 4).getType());
    List<RunFinds.Insurable> many = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      many.add(new RunFinds.Insurable("p" + i, new ItemStack(Material.STICK), 1, 1));
    }
    when(runs.insurable(ann)).thenReturn(many);
    m.render();
    assertNull(m.getInventory().getItem(FindsMenu.FIRST + FindsMenu.SHOWN));
  }
}
