package net.tfminecraft.trialrooms.dungeon.loot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.tfminecraft.gunsandgadgets.api.DungeonGuns;
import net.tfminecraft.gunsandgadgets.guns.parts.GunPart;
import net.tfminecraft.gunsandgadgets.loader.PartLoader;
import net.tfminecraft.magic.api.DungeonWeapons;
import net.tfminecraft.trialrooms.dungeon.gear.GearSet;
import net.tfminecraft.trialrooms.dungeon.gear.GearTags;
import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Base;
import net.tfminecraft.trialrooms.dungeon.loot.LootKit.Rarity;

/** Guns and mage weapons as loot: their rolls, sets, names and part trading. */
class WeaponLootTest {

  static final NamespacedKey MARK = NamespacedKey.fromString("test:gun");

  /** Guns whose item remembers what it was built of. */
  static final class FakeGuns implements GunForge {
    final Map<String, List<String>> slots = new LinkedHashMap<>();
    final List<ItemStack> items = new ArrayList<>();
    final List<Gun> guns = new ArrayList<>();
    final List<Map<String, Integer>> colours = new ArrayList<>();
    final Set<String> refused = new HashSet<>();
    String asked;
    boolean bare;

    FakeGuns() {
      for (String s : List.of("barrel", "loader", "chamber", "action", "stock")) {
        slots.put(s, List.of(s + "1", s + "2"));
      }
    }

    @Override
    public Map<String, List<String>> slots(String type, String metal, String wood) {
      asked = type + " " + metal + " " + wood;
      return slots;
    }

    @Override
    public ItemStack build(String type, Map<String, String> parts, Map<String, Integer> c, double power, String marker) {
      if (parts.values().stream().anyMatch(refused::contains)) {
        return null;
      }
      ItemStack it = new ItemStack(Material.STONE_HOE);
      it.editMeta(m -> {
        m.getPersistentDataContainer().set(MARK, PersistentDataType.INTEGER, guns.size());
        m.lore(bare ? null : List.of(Component.text("Damage")));
      });
      guns.add(new Gun(type, new LinkedHashMap<>(parts), power, marker));
      colours.add(c);
      items.add(it);
      return it;
    }

    @Override
    public Gun read(ItemStack item) {
      Integer i = item.getItemMeta().getPersistentDataContainer().get(MARK, PersistentDataType.INTEGER);
      return i == null ? null : guns.get(i);
    }

    @Override
    public String partName(String part) {
      return part.toUpperCase();
    }
  }

  static final class FakeMage implements MageForge {
    final List<String> built = new ArrayList<>();
    final Map<String, List<String>> runes = new LinkedHashMap<>(Map.of("minor_rune", List.of("m.ice.a", "m.fire.b"),
        "lesser_rune", List.of("m.ice.c", "m.fire.d"), "greater_rune", List.of("m.ice.e"), "ascendant_rune", List.of()));
    double power;
    Map<String, Double> stats;
    List<String> lore;
    String name;
    boolean fail;

    @Override
    public List<String> archetypes() {
      return List.of("staff", "wand");
    }

    @Override
    public List<String> runes(String archetype, String slot) {
      return runes.get(slot);
    }

    @Override
    public ItemStack build(String archetype, List<String> r, double p, Map<String, Double> extra, String n, List<String> l) {
      built.clear();
      built.addAll(r);
      power = p;
      stats = extra;
      name = n;
      lore = l;
      return fail ? null : new ItemStack(Material.STICK);
    }
  }

  LootKit kit;
  FakeGuns guns = new FakeGuns();
  GunLoot loot = new GunLoot(guns, () -> kit);

  @BeforeEach
  void up() {
    MockBukkit.mock();
    kit = LootCoreTest.shipped();
  }

  @AfterEach
  void down() {
    MockBukkit.unmock();
  }

  static List<String> lore(ItemStack it) {
    return it.lore().stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
  }

  static String name(ItemStack it) {
    return PlainTextComponentSerializer.plainText().serialize(it.getItemMeta().displayName());
  }

  /** A gun with these sets, barrel first. */
  ItemStack gun(String... sets) {
    Map<String, String> bySlot = new LinkedHashMap<>();
    Map<String, String> parts = new LinkedHashMap<>();
    int i = 0;
    for (String slot : guns.slots.keySet()) {
      bySlot.put(slot, sets[i++]);
      parts.put(slot, slot + (sets[0].equals("royal") ? "1" : "2"));
    }
    return guns.build("rifle", parts, Map.of(), 2, new GunLoot.Roll("epic", 3, "of Old Oaths", bySlot).encode());
  }

  @Test
  void gunsRollPartsOfTheirOwnSets() {
    Base rifle = kit.base("rifle");
    assertTrue(loot.makes(kit, rifle));
    assertEquals("rifle steel maplewood", guns.asked);
    assertFalse(loot.makes(kit, kit.base("staff")), "another plugin's");
    assertFalse(loot.makes(kit, kit.base("sword")));
    Random rng = new Random(3);
    Set<String> sets = new HashSet<>();
    int mixed = 0;
    int whole = 0;
    for (int i = 0; i < 300; i++) {
      ItemStack it = loot.make(kit, rifle, 2, kit.rarity("epic"), "delve_seithr", rng);
      GunLoot.Roll roll = GunLoot.Roll.decode(guns.guns.getLast().marker());
      assertEquals(List.copyOf(guns.slots.keySet()), List.copyOf(roll.sets().keySet()));
      sets.addAll(roll.sets().values());
      GunLoot.Main main = GunLoot.main(kit, roll.sets());
      GearTags.Piece piece = GearTags.read(it);
      assertEquals(new GearTags.Piece(main.set().id(), GearSet.Kind.WEAPON, guns.guns.getLast().power(), main.share()), piece);
      assertTrue(name(it).startsWith(main.set().name() + " Rifle of "), name(it));
      assertEquals("Damage", lore(it).getFirst(), "the gun's own lines stay");
      assertTrue(lore(it).contains("Epic dungeon item, tier 2"));
      assertEquals("Stays in the dungeons.", lore(it).getLast());
      assertEquals(main.share() < 1, lore(it).contains("Its parts are too mixed for the whole trait."));
      if (main.share() < 1) {
        mixed++;
      } else {
        whole++;
      }
      roll.sets().forEach((slot, id) -> assertEquals(kit.sets().get(id).tints().of(slot), guns.colours.getLast().get(slot)));
    }
    // one step either side of epic, general and the dungeon's own
    assertEquals(Set.of("warden", "gravebound", "court", "ember", "duskwoven", "marquis", "drowned", "firstlight", "royal"), sets);
    assertTrue(mixed > 10 && whole > 100, mixed + " mixed, " + whole + " whole");
    // 3 bonus stats and 2 sockets go into the power
    assertEquals(1.25 * 1.5 * 1.25, guns.guns.getLast().power(), 1e-9);
    ItemStack plain = loot.make(kit, rifle, 1, kit.rarity("uncommon"), null, rng);
    assertTrue(name(plain).endsWith(" Rifle"), "no suffix on the common ones");
  }

  @Test
  void theMainSetHasTheMostPoints() {
    Map<String, String> sets = new LinkedHashMap<>();
    assertNull(GunLoot.main(kit, sets));
    sets.put("barrel", "");
    assertNull(GunLoot.main(kit, sets));
    sets.put("barrel", "delver"); // 1
    sets.put("loader", "delver"); // 1
    sets.put("chamber", "royal"); // 4
    assertEquals(new GunLoot.Main(kit.sets().get("royal"), 1), GunLoot.main(kit, sets), "a rarer part beats two common ones");
    sets.put("chamber", "court"); // 2: a tie
    assertEquals(new GunLoot.Main(kit.sets().get("delver"), GunLoot.MIXED), GunLoot.main(kit, sets), "a tie goes to the barrel");
    assertEquals(List.of(1, 2, 3, 3, 1), List.of("barrel", "stock", "action", "loader", "chamber").stream()
        .map(new GearSet.Tints(1, 2, 3)::of).toList());
    for (List<String> bad : List.of(List.of("#ffffff"), List.of("#ffffff", "x", "#000000"))) {
      assertThrows(IllegalArgumentException.class, () -> GearSet.Tints.of("s", bad));
    }
  }

  @Test
  void gunsWithoutSetsOrParts() {
    Rarity r = new Rarity("a", "A", 1, 1, 0, 0, "#abcdef");
    Base rifle = new Base("rifle", "%material% Rifle", LootKit.Slot.WEAPON, null, null, "", Map.of(), List.of(), "gun.rifle",
        List.of(), 0);
    LootKit bare = LootCoreTest.kit(r, Map.of(), rifle, Map.of());
    kit = bare;
    ItemStack it = loot.make(bare, rifle, 1, r, null, new Random(1));
    assertEquals("rifle  ", guns.asked);
    assertEquals("Old Rifle", name(it));
    assertEquals(List.of("Damage", "A dungeon item, tier 1", "Stays in the dungeons."), lore(it));
    assertNull(GearTags.read(it));
    assertEquals(0xFFFFFF, guns.colours.getLast().get("barrel"));
    assertTrue(loot.trades(it, it).getFirst().own().matches("BARREL[12] \\(plain\\)"));
    guns.bare = true;
    assertEquals(2, lore(loot.make(bare, rifle, 1, r, null, new Random(1))).size(), "a gun without lines of its own");
    // a set without gun colours
    LootKit dull = LootCoreTest.kit(r, Map.of(), rifle, Map.of("s", LootCoreTest.set("s", "a")));
    loot.make(dull, rifle, 1, r, null, new Random(1));
    assertEquals(0xFFFFFF, guns.colours.getLast().get("stock"));
    // parts that never fit, a slot nothing fits, an unknown type
    guns.refused.addAll(List.of("stock1", "stock2"));
    assertNull(loot.make(bare, rifle, 1, r, null, new Random(1)));
    guns.slots.put("stock", List.of());
    assertFalse(loot.makes(bare, rifle));
    guns.slots.clear();
    assertFalse(loot.makes(bare, rifle));
  }

  @Test
  void aPartThatDoesntFitIsRolledAgain() {
    guns.refused.add("barrel1");
    for (int i = 0; i < 10; i++) {
      assertNotNull(loot.make(kit, kit.base("rifle"), 1, kit.rarity("rare"), null, new Random(i)));
      assertEquals("barrel2", guns.guns.getLast().parts().get("barrel"));
    }
  }

  @Test
  void refreshedGunsGetTheirLinesBack() {
    ItemStack it = gun("royal", "royal", "royal", "delver", "delver");
    ItemStack dressed = loot.refreshed(null, it);
    assertSame(it, dressed);
    assertEquals("Seithr Royal Rifle of Old Oaths", name(it));
    assertTrue(lore(it).contains("Parts: 3 Seithr Royal, 2 Delver."));
    ItemStack other = new ItemStack(Material.STONE);
    other.editMeta(m -> m.setUnbreakable(true));
    assertSame(other, loot.refreshed(null, other));
    for (String bad : new String[] {null, "", "epic|x||a=b", "epic|1||ab", "gone|1||a=b", "epic|1|"}) {
      ItemStack odd = guns.build("rifle", Map.of(), Map.of(), 1, bad);
      loot.refreshed(null, odd);
      assertEquals(List.of("Damage"), lore(odd), String.valueOf(bad));
    }
    ItemStack unknown = guns.build("cannon", Map.of(), Map.of(), 1, "epic|1||a=b");
    assertSame(unknown, loot.refreshed(null, unknown));
    assertEquals(List.of("Damage"), lore(unknown));
    kit = null;
    ItemStack off = gun("royal", "royal", "royal", "delver", "delver");
    loot.refreshed(null, off);
    assertEquals(List.of("Damage"), lore(off), "loot is off");
  }

  @Test
  void gunsOfATypeTradeParts() {
    ItemStack a = gun("royal", "court", "court", "court", "court");
    ItemStack b = gun("ember", "ember", "delver", "delver", "royal");
    List<GunLoot.Trade> trades = loot.trades(a, b);
    assertEquals(5, trades.size());
    assertTrue(trades.contains(new GunLoot.Trade("stock", "STOCK1 (Seithr Court)", "STOCK2 (Seithr Royal)")), trades.toString());
    ItemStack[] both = loot.trade(a, b, "stock");
    GunForge.Gun first = guns.read(both[0]);
    GunForge.Gun second = guns.read(both[1]);
    assertEquals("stock2", first.parts().get("stock"));
    assertEquals("stock1", second.parts().get("stock"));
    assertEquals("barrel1", first.parts().get("barrel"));
    assertEquals(2, first.power());
    assertEquals("Seithr Royal Rifle of Old Oaths", name(both[0]));
    assertEquals(new GearTags.Piece("royal", GearSet.Kind.WEAPON, 2, 1), GearTags.read(both[0]), "8 points of 14");
    assertNull(loot.trade(a, b, "scope"));
    guns.refused.add("loader2");
    assertNull(loot.trade(a, b, "loader"), "the parts don't work together");
    guns.refused.clear();
    guns.refused.add("loader1");
    assertNull(loot.trade(a, b, "loader"));
    ItemStack pistol = guns.build("pistol", Map.of(), Map.of(), 1, "epic|1||a=b");
    assertEquals(List.of(), loot.trades(a, pistol));
    ItemStack paper = new ItemStack(Material.PAPER);
    paper.editMeta(m -> m.setUnbreakable(true));
    assertEquals(List.of(), loot.trades(a, paper));
    assertEquals(List.of(), loot.trades(paper, a));
    // a part the roll doesn't know
    ItemStack odd = guns.build("rifle", Map.of("barrel", "barrel1"), Map.of(), 1, "epic|1||stock=royal");
    assertEquals(List.of(new GunLoot.Trade("barrel", "BARREL1 (plain)", "BARREL1 (plain)")), loot.trades(odd, odd));
    assertNotNull(loot.trade(odd, odd, "barrel"));
  }

  @Test
  void mageWeaponsComeWithTheirRunes() {
    FakeMage mage = new FakeMage();
    List<String> looks = new ArrayList<>();
    MageLoot m = new MageLoot(mage, (it, look) -> {
      looks.add(look);
      return it;
    });
    assertTrue(m.makes(kit, kit.base("staff")));
    assertFalse(m.makes(kit, kit.base("mage_blade")), "not an archetype here");
    assertFalse(m.makes(kit, kit.base("rifle")));
    assertFalse(m.makes(kit, kit.base("sword")));
    Random rng = new Random(2);
    ItemStack it = m.make(kit, kit.base("staff"), 1, kit.rarity("uncommon"), null, rng);
    assertEquals(2, mage.built.size(), "as many as a crafted steel staff");
    assertEquals(1.15, mage.power, 1e-9);
    assertTrue(looks.getFirst().matches("ia\\.tfmc_dungeons:(delver|sellsword)_staff"), looks.toString());
    assertTrue(mage.name.matches("§x.*(Delver|Sellsword) Mage Staff"), mage.name);
    assertEquals("§7Uncommon dungeon item, tier 1", mage.lore.getFirst());
    assertEquals(1, mage.stats.size());
    assertEquals(GearSet.Kind.WEAPON, GearTags.read(it).kind());
    // rarer: more runes instead of gem sockets, and power for the one with no room; no rune fits the last slot
    m.make(kit, kit.base("staff"), 1, kit.rarity("legendary"), null, rng);
    assertEquals(3, mage.built.size());
    assertEquals(1.75 * 1.05, mage.power, 1e-9);
    // mostly of one school
    int same = 0;
    for (int i = 0; i < 200; i++) {
      m.make(kit, kit.base("wand"), 1, kit.rarity("rare"), null, rng);
      same += mage.built.get(0).split("\\.")[1].equals(mage.built.get(1).split("\\.")[1]) ? 1 : 0;
    }
    assertTrue(same > 110 && same < 195, same + " of 200");
    mage.runes.put("lesser_rune", List.of("m.water.z"));
    m.make(kit, kit.base("wand"), 1, kit.rarity("rare"), null, rng);
    assertEquals("m.water.z", mage.built.get(1), "a school without a rune of the size");
    mage.fail = true;
    assertNull(m.make(kit, kit.base("wand"), 1, kit.rarity("rare"), null, rng));
    // no sets: the weapon's own look
    mage.fail = false;
    looks.clear();
    LootKit bare = LootCoreTest.kit(new Rarity("a", "A", 1, 1, 0, 0, "#abcdef"), Map.of(), new Base("w", "Wand",
        LootKit.Slot.WEAPON, null, null, "p", Map.of(), List.of("wand"), "magic.wand", List.of(), 1), Map.of());
    assertNotNull(m.make(bare, bare.bases().getFirst(), 1, bare.rarities().getFirst(), null, rng));
    assertEquals(List.of(), looks);
  }

  @Test
  void theirClassesDecideWhoFindsThem() {
    DungeonLootTest.FakeForge forge = new DungeonLootTest.FakeForge();
    forge.playerClass = "Musketeer";
    Player ann = mock(Player.class);
    DungeonLoot without = new DungeonLoot(forge, () -> kit, new Random(1));
    assertNull(without.guns());
    assertEquals(List.of(), without.usable(ann));
    assertEquals("There is no item rifle that can be made.", without.roll(ann, 1, "rare", "rifle").problem());
    DungeonLoot with = new DungeonLoot(forge, () -> kit, new Random(1)).guns(loot)
        .maker(new MageLoot(new FakeMage(), (it, look) -> it));
    assertSame(loot, with.guns());
    assertEquals(List.of("rifle", "pistol", "shotgun", "launcher"), with.usable(ann).stream().map(Base::id).toList());
    assertEquals("rare", with.roll(ann, 1, "rare", null).rarity());
    guns.refused.addAll(List.of("stock1", "stock2"));
    assertEquals("The item rifle could not be built.", with.roll(ann, 1, "rare", "rifle").problem());
    forge.playerClass = "Invoker";
    assertEquals(List.of("staff"), with.usable(ann).stream().map(Base::id).toList());
  }

  @Test
  void theForgesAreTheOtherPlugins() {
    GgGunForge gg = new GgGunForge();
    ItemStack it = new ItemStack(Material.STONE_HOE);
    try (var d = mockStatic(DungeonGuns.class); var p = mockStatic(PartLoader.class)) {
      d.when(() -> DungeonGuns.slots("rifle", "steel", "maplewood")).thenReturn(Map.of("barrel", List.of("a")));
      assertEquals(Map.of("barrel", List.of("a")), gg.slots("rifle", "steel", "maplewood"));
      d.when(() -> DungeonGuns.build("rifle", Map.of(), Map.of(), 2, "m")).thenReturn(it);
      assertSame(it, gg.build("rifle", Map.of(), Map.of(), 2, "m"));
      d.when(() -> DungeonGuns.read(it)).thenReturn(Optional.empty(),
          Optional.of(new DungeonGuns.Parts("rifle", Map.of("barrel", "a"), Map.of(), 2, "m")));
      assertNull(gg.read(it));
      assertEquals(new GunForge.Gun("rifle", Map.of("barrel", "a"), 2, "m"), gg.read(it));
      GunPart part = mock(GunPart.class);
      when(part.getName()).thenReturn("§7Long Barrel");
      p.when(() -> PartLoader.getByString("a")).thenReturn(part);
      assertEquals("Long Barrel", gg.partName("a"));
    }
    MagicForge magic = new MagicForge();
    try (var d = mockStatic(DungeonWeapons.class)) {
      d.when(DungeonWeapons::archetypes).thenReturn(List.of("staff"));
      d.when(() -> DungeonWeapons.runes("staff", "minor_rune")).thenReturn(List.of("r"));
      d.when(() -> DungeonWeapons.build("staff", List.of(), 1, Map.of(), "n", List.of())).thenReturn(it);
      assertEquals(List.of("staff"), magic.archetypes());
      assertEquals(List.of("r"), magic.runes("staff", "minor_rune"));
      assertSame(it, magic.build("staff", List.of(), 1, Map.of(), "n", List.of()));
    }
  }
}
