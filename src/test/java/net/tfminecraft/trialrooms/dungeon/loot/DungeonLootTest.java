package net.tfminecraft.trialrooms.dungeon.loot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class DungeonLootTest {

  /** A forge with recipes, template classes and a log of what was made. */
  static final class FakeForge implements LootForge {
    final Map<String, Recipe> recipes = new HashMap<>();
    final Map<String, List<String>> classes = new HashMap<>();
    final List<LootRoller.Plan> made = new ArrayList<>();
    String playerClass = "Juggernaut";
    boolean fail;
    int recipeLookups;

    @Override
    public Recipe recipe(String recipe, Map<String, String> standard) {
      recipeLookups++;
      return recipes.get(recipe);
    }

    @Override
    public List<String> classes(String template) {
      return classes.get(template);
    }

    @Override
    public String playerClass(Player player) {
      return playerClass;
    }

    @Override
    public ItemStack make(LootRoller.Plan plan, String template, Set<String> managed, String socket) {
      made.add(plan);
      return fail ? null : mock(ItemStack.class);
    }
  }

  LootKit kit = LootCoreTest.shipped();
  FakeForge forge = new FakeForge();
  Player ann = mock(Player.class);

  DungeonLoot loot() {
    return new DungeonLoot(forge, () -> kit, new Random(5));
  }

  @Test
  void onlyItemsTheClassCanUseDrop() {
    when(ann.getName()).thenReturn("Ann");
    forge.recipes.put("heavy_helmet", new LootForge.Recipe("helmets.heavy_custom_helmet", Map.of("armor", 4.0), Set.of("armor")));
    forge.recipes.put("light_helmet", new LootForge.Recipe("helmets.light_custom_helmet", Map.of("armor", 1.0), Set.of("armor")));
    forge.classes.put("helmets.heavy_custom_helmet", List.of("juggernaut", "Warden"));
    forge.classes.put("helmets.light_custom_helmet", List.of("Cleric"));
    // the jewellery templates are missing from this server: they never drop
    DungeonLoot many = loot(); // one random stream for all the rolls
    for (int i = 0; i < 60; i++) {
      DungeonLoot.Rolled r = many.roll(ann, 1, null, null);
      assertNotNull(r.item(), r.problem());
    }
    assertTrue(forge.made.stream().allMatch(p -> p.base().id().equals("heavy_helmet")));
    assertEquals(4.0 * 1.15, forge.made.stream().filter(p -> p.rarity().id().equals("uncommon")).findFirst().orElseThrow()
        .stats().get("armor"), 0.6, "the recipe's stats, scaled");

    forge.classes.put("ring.fine_jeweled_ring", List.of()); // no class limit: anyone
    forge.made.clear();
    for (int i = 0; i < 40; i++) {
      many.roll(ann, 2, "rare", null);
    }
    assertTrue(forge.made.stream().anyMatch(p -> p.base().id().equals("ring")));
    assertTrue(forge.made.stream().allMatch(p -> p.rarity().id().equals("rare")));

    forge.playerClass = "Cleric";
    forge.classes.remove("ring.fine_jeweled_ring");
    forge.made.clear();
    loot().roll(ann, 1, null, null);
    assertEquals("light_helmet", forge.made.getFirst().base().id());
    forge.playerClass = null; // no class: only items for anyone
    assertEquals("No dungeon item fits Ann's class (null).", loot().roll(ann, 1, null, null).problem());
  }

  @Test
  void staffCanForceTheRarityAndTheItem() {
    forge.recipes.put("sword", new LootForge.Recipe("sword.custom_sword", Map.of("attack_damage", 6.0), Set.of()));
    DungeonLoot.Rolled r = loot().roll(ann, 1, "epic", "sword");
    assertNotNull(r.item());
    assertEquals("epic", forge.made.getFirst().rarity().id());
    assertEquals("There is no rarity mythic.", loot().roll(ann, 1, "mythic", null).problem());
    assertEquals("There is no item axe that can be made.", loot().roll(ann, 1, null, "axe").problem());
    assertEquals("There is no item dagger that can be made.", loot().roll(ann, 1, null, "dagger").problem());
    assertNotNull(loot().roll(ann, 1, null, "amulet").item(), "a template item needs no recipe");
    forge.fail = true;
    assertEquals("The item sword could not be built.", loot().roll(ann, 1, null, "sword").problem());
    kit = null;
    assertTrue(loot().roll(ann, 1, null, null).problem().startsWith("Dungeon loot is off"));
  }

  @Test
  void eachRecipeIsLookedUpOncePerRoll() {
    forge.recipes.put("sword", new LootForge.Recipe("sword.custom_sword", Map.of(), Set.of()));
    forge.classes.put("sword.custom_sword", List.of("Juggernaut"));
    loot().roll(ann, 1, null, null);
    assertEquals(28, forge.recipeLookups, "every recipe base once, the chosen one from the cache");
  }
}
