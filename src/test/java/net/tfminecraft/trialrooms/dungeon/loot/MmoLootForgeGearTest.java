package net.tfminecraft.trialrooms.dungeon.loot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.trialrooms.dungeon.gear.GearSet;
import net.tfminecraft.trialrooms.dungeon.gear.GearTags;

/** The forge's set marks (needs a mock server: called from the gear tests). */
public final class MmoLootForgeGearTest {

  private MmoLootForgeGearTest() {
  }

  public static void tags() {
    LootKit k = LootCoreTest.shipped();
    ItemStack it = new ItemStack(Material.PAPER);
    LootRoller.Plan none = new LootRoller.Plan(k.base("sword"), k.rarity("epic"), 1, "x", Map.of(), 0, null, List.of(), null, 1);
    assertNull(GearTags.read(MmoLootForge.tagged(none, it)));
    LootRoller.Plan ember = new LootRoller.Plan(k.base("shield"), k.rarity("epic"), 1, "x", Map.of(), 0, null, List.of(),
        k.sets().get("ember"), 1.5);
    assertEquals(new GearTags.Piece("ember", GearSet.Kind.OFF_HAND, 1.5), GearTags.read(MmoLootForge.tagged(ember, it)));
  }
}
