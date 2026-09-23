package net.tfminecraft.trialrooms.environment.spawner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Particle.DustOptions;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.subapi.StringFormatter;
import net.tfminecraft.trialrooms.TrialRooms;
import net.tfminecraft.trialrooms.cache.Cache;
import net.tfminecraft.trialrooms.loader.TableLoader;
import net.tfminecraft.trialrooms.loot.LootTable;

/** Handles unlocking visuals, key rarity, encoding loot onto the key, and dropping it. */
class KeyService {

    private final ActiveSpawner spawner;

    // --- DEBUG flag ---
    private boolean debugKeys = Cache.debug;
    public void setDebugKeys(boolean debug) { this.debugKeys = debug; }
    public boolean isDebugKeys() { return debugKeys; }

    KeyService(ActiveSpawner spawner) { this.spawner = spawner; }

    // Neutral color for "Spawner Key"
    private static final String KEY_COLOR_HEX = "#eaeaea";

    // --- Mob-key drop tuning ---
    private static final double MOB_KEY_BASE_CHANCE = Cache.MOB_KEY_BASE_CHANCE;  // 2% base
    private static final double MOB_KEY_CHANCE_PER_LEVEL = Cache.MOB_KEY_CHANCE_PER_LEVEL; // +0.15% per level
    private static final double MOB_KEY_MAX_CHANCE = Cache.MOB_KEY_MAX_CHANCE;  // hard cap 15%

    enum Rarity {
        COMMON    ("#9e9e9e", "[Common]",    () -> Cache.commonScore),
        UNCOMMON  ("#55ff55", "[Uncommon]",  () -> Cache.uncommonScore),
        RARE      ("#5599ff", "[Rare]",      () -> Cache.rareScore),
        EPIC      ("#d15cff", "[Epic]",      () -> Cache.epicScore),
        LEGENDARY ("#ffaa00", "[Legendary]", () -> Cache.legendaryScore);

        final String rarityHex;
        final String word;
        private final DoubleSupplier scoreSupplier; // dynamic + double

        Rarity(String rarityHex, String word, DoubleSupplier scoreSupplier) {
            this.rarityHex = rarityHex;
            this.word = word;
            this.scoreSupplier = scoreSupplier;
        }

        // read the (possibly config-loaded) score
        double score() { return scoreSupplier.getAsDouble(); }

        String displayNameHex() { return displayNameHex("Spawner Key"); }
        String displayNameHex(String label) {
            return rarityHex + "§l" + word + " " + KEY_COLOR_HEX + label;
        }
    }

    // Bias and jitter
    private static final double KEY_RARITY_BIAS_PER_STEP = 0.18;
    private static final double JITTER_MAX = 0.06;

    // PDC keys for encoded loot
    private static final NamespacedKey PDC_ENCODED_LOOT =
            new NamespacedKey(TrialRooms.getInstance(), "encodedLoot");
    private static final NamespacedKey PDC_ENCODED_VER  =
            new NamespacedKey(TrialRooms.getInstance(), "encodedLootVer");

    // ---------- Public entry: called when ACTIVE finishes ----------
    void startUnlockSequence() {
        if (!spawner.isLoaded() || spawner.isDestroyed()) return;
        Location base = spawner.getLoc();
        if (base == null || base.getWorld() == null) return;

        final World w = base.getWorld();
        final Location center = base.clone().add(0.5, 1.0, 0.5);
        final DustOptions green = new DustOptions(Color.fromRGB(0x55, 0xFF, 0x55), 1.4f);

        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (!spawner.isLoaded() || spawner.isDestroyed() || spawner.getState() != ActiveSpawner.State.UNLOCKING) { cancel(); return; }

                double phase = (ticks % 20) / 20.0;
                double radius = 0.6 + 0.4 * Math.sin(phase * Math.PI);

                int points = 16;
                for (int i = 0; i < points; i++) {
                    double a = (Math.PI * 2 * i) / points;
                    double x = center.getX() + Math.cos(a) * radius;
                    double z = center.getZ() + Math.sin(a) * radius;
                    w.spawnParticle(Particle.DUST, x, center.getY(), z, 1, 0, 0, 0, 0, green);
                }
                w.spawnParticle(Particle.HAPPY_VILLAGER, center, 4, 0.4, 0.1, 0.4, 0.0);

                if (ticks % 6 == 0) w.playSound(center, Sound.BLOCK_CHAIN_PLACE, 1.0f, 1.4f);
                if (ticks == 34)     w.playSound(center, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 1.0f, 0.8f);

                ticks++;
                if (ticks >= 40) {
                    cancel();
                    windupAndDropKey();
                }
            }
        }.runTaskTimer(TrialRooms.getInstance(), 0L, 1L);
    }

    private void windupAndDropKey() {
        if (!spawner.isLoaded() || spawner.isDestroyed() || spawner.getState() != ActiveSpawner.State.UNLOCKING) return;
        Location base = spawner.getLoc();
        if (base == null || base.getWorld() == null) return;

        final World w = base.getWorld();
        final Location c = base.clone().add(0.5, 1.1, 0.5);

        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (!spawner.isLoaded() || spawner.isDestroyed() || spawner.getState() != ActiveSpawner.State.UNLOCKING) { cancel(); return; }

                double radius = 0.8 - 0.07 * t;
                double y = c.getY() + 0.02 * t;
                int arms = 2;
                for (int arm = 0; arm < arms; arm++) {
                    double base = (t * 0.6) + (arm * Math.PI);
                    for (int p = 0; p < 6; p++) {
                        double a = base + (p * Math.PI / 6);
                        double x = c.getX() + Math.cos(a) * radius;
                        double z = c.getZ() + Math.sin(a) * radius;
                        w.spawnParticle(Particle.END_ROD, x, y, z, 1, 0, 0, 0, 0);
                    }
                }
                w.playSound(c, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.2f, 1.1f + (t * 0.05f));

                t++;
                if (t >= 10) {
                    cancel();

                    // DEBUG: simulate 100 virtual keys instead of dropping a real one
                    if (debugKeys) {
                        simulateKeyRarity(10000);
                    } else {
                        dropKeyNow(); // normal single drop
                    }

                    spawner.beginCooldownFromUnlock();
                }
            }
        }.runTaskTimer(TrialRooms.getInstance(), 0L, 1L);
    }

    private void simulateKeyRarity(int trials) {
        int[] counts = new int[Rarity.values().length];
        for (int i = 0; i < trials; i++) {
            Rarity r = rollRarity(spawner.getLevel());
            counts[r.ordinal()]++;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("§e[Key Debug] §7Simulated §f").append(trials)
        .append(" §7keys (lvl §f").append(spawner.getLevel()).append("§7): ");

        // color each rarity name with its hex (StringFormatter will translate hex)
        sb.append(Rarity.COMMON.rarityHex).append("Common§7=").append(counts[Rarity.COMMON.ordinal()]).append("  ");
        sb.append(Rarity.UNCOMMON.rarityHex).append("Uncommon§7=").append(counts[Rarity.UNCOMMON.ordinal()]).append("  ");
        sb.append(Rarity.RARE.rarityHex).append("Rare§7=").append(counts[Rarity.RARE.ordinal()]).append("  ");
        sb.append(Rarity.EPIC.rarityHex).append("Epic§7=").append(counts[Rarity.EPIC.ordinal()]).append("  ");
        sb.append(Rarity.LEGENDARY.rarityHex).append("Legendary§7=").append(counts[Rarity.LEGENDARY.ordinal()]);

        Bukkit.broadcastMessage(StringFormatter.formatHex(sb.toString()));
    }


    private void dropKeyNow() {
        Location base = spawner.getLoc();
        if (!spawner.isLoaded() || spawner.isDestroyed() || base == null || base.getWorld() == null) return;

        ItemStack stack = TLibs.getItemAPI().getCreator().getItemFromPath(Cache.spawnerKey);
        if (stack == null || stack.getType() == Material.AIR) return;
        stack.setAmount(1);

        Rarity rarity = rollRarity(spawner.getLevel());
        long salt = ThreadLocalRandom.current().nextLong();

        applyKeyStyling(stack, rarity, salt, "Spawner Key", spawner.getLootTable()); // NEW

        Location drop = base.clone().add(0.5, 1.1, 0.5);
        World w = drop.getWorld();

        w.spawnParticle(Particle.CLOUD, drop, 28, 0.40, 0.40, 0.40, 0.0);
        w.spawnParticle(Particle.LARGE_SMOKE, drop, 20, 0.30, 0.30, 0.30, 0.0);
        w.playSound(drop, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1.15f);
        w.playSound(drop, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.6f);

        org.bukkit.entity.Item item = w.dropItem(drop, stack);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double vx = randomSigned(rng, 0.05, 0.10);
        double vz = randomSigned(rng, 0.05, 0.10);
        double vy = rng.nextDouble(0.30, 0.80);
        item.setVelocity(new Vector(vx, vy, vz));

        String name = stack.getItemMeta().getDisplayName();
        item.setCustomName("§f1x " + name);
        item.setCustomNameVisible(true);

        startCritTrail(item, 200);
    }

    // ---------- Rarity ----------
    private Rarity rollRarity(int level) {
        // Base weights from Cache (already loaded from YAML)
        double[] base = {
            Cache.commonChance,
            Cache.uncommonChance,
            Cache.rareChance,
            Cache.epicChance,
            Cache.legendaryChance
        };

        Rarity[] tiers = Rarity.values();
        double total = 0.0;
        double[] w = new double[tiers.length];

        for (int i = 0; i < tiers.length; i++) {
            double score = tiers[i].score(); // double now
            double mult;

            switch (Cache.rarityWeightModel) { // "LINEAR", "EXP", "LOGISTIC"
                case "EXP": {
                    // explosive growth: base * exp(level * expGrowthPerLevel * score)
                    double m = Math.exp(Math.max(0, level) * Cache.rarityExpGrowthPerLevel * score);
                    mult = clamp(m, Cache.rarityMultiplierMin, Cache.rarityMultiplierMax);
                    break;
                }
                case "LOGISTIC": {
                    // S-shaped growth around a midlevel; great for “hits hard by level ~30”
                    // score scales steepness so high tiers curve harder
                    double k  = Cache.rarityLogisticSlope * Math.max(0.0001, score); // avoid 0 slope on non-positive score
                    double x  = Math.max(0, level) - Cache.rarityLogisticMidLevel;
                    double s  = 1.0 / (1.0 + Math.exp(-k * x)); // 0..1
                    double lo = Cache.rarityLogisticLowMult;    // multiplier near L=0
                    double hi = Cache.rarityLogisticHighMult;   // multiplier near L>>mid
                    mult = clamp(lo + s * (hi - lo), Cache.rarityMultiplierMin, Cache.rarityMultiplierMax);
                    break;
                }
                default: // "LINEAR" — your existing behavior (backward compatible)
                case "LINEAR": {
                    double lin = 1.0 + Math.max(0, level) * Cache.rarityBiasPerLevel * score;
                    if (lin <= 0.0 && Cache.rarityBiasExponent != 1.0) {
                        mult = 0.0;
                    } else {
                        mult = Math.pow(Math.max(0.0, lin), Cache.rarityBiasExponent);
                    }
                    mult = clamp(mult, Cache.rarityMultiplierMin, Cache.rarityMultiplierMax);
                    break;
                }
            }

            // apply jitter multiplicatively
            double j = 1.0;
            if (Cache.jitterMax > 0.0) {
                double r = ThreadLocalRandom.current().nextDouble(-Cache.jitterMax, Cache.jitterMax);
                j = 1.0 + clamp(r, -0.99, 0.99); // keep positive
            }

            w[i] = Math.max(0.0, base[i] * mult * j);
            total += w[i];
        }

        if (total <= 0.0) {
            // fallback if all zero after tuning
            return Rarity.COMMON;
        }

        double r = ThreadLocalRandom.current().nextDouble(total);
        double acc = 0.0;
        for (int i = 0; i < tiers.length; i++) {
            acc += w[i];
            if (r <= acc) return tiers[i];
        }
        return tiers[0];
    }





    // ---------- Styling + encoding ----------

    private void applyKeyStyling(ItemStack stack, Rarity rarity, long salt, String label, String tableId) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;

        String pretty = StringFormatter.formatHex(rarity.displayNameHex(label));
        meta.setDisplayName(pretty);
        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        // Use the provided tableId (spawner vs mob table)
        LootTable table = TableLoader.getByString(tableId);
        LootTable.Range range = (table != null) ? table.getAmountRangeForKeyRarityName(rarity.name()) : null;

        int rollsMin = (range != null) ? range.min : 2;
        int rollsMax = (range != null) ? range.max : 4;

        List<EncodedEntry> enc = encodeLootForKey(tableId, rarity, spawner.getLevel(), salt, Cache.lootAmount);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!enc.isEmpty()) saveEncodedLoot(pdc, enc);
        pdc.set(new NamespacedKey(TrialRooms.getInstance(), "keyRarity"),   PersistentDataType.STRING, rarity.name());
        pdc.set(new NamespacedKey(TrialRooms.getInstance(), "spawnerUUID"), PersistentDataType.STRING, spawner.getUUID());
        pdc.set(new NamespacedKey(TrialRooms.getInstance(), "keySalt"),     PersistentDataType.LONG,   salt);
        pdc.set(new NamespacedKey(TrialRooms.getInstance(), "keyRollsMin"), PersistentDataType.INTEGER, rollsMin);
        pdc.set(new NamespacedKey(TrialRooms.getInstance(), "keyRollsMax"), PersistentDataType.INTEGER, rollsMax);

        List<String> lore = new ArrayList<>();
        String rangeText = (rollsMin == rollsMax)
                ? ("§7Drops §f" + rollsMin + " §7of the following:")
                : ("§7Drops §f" + rollsMin + "–" + rollsMax + " §7of the following:");
        lore.add(rangeText);
        lore.addAll(buildEncodedLore(enc));
        meta.setLore(lore);

        stack.setItemMeta(meta);
    }



    private List<EncodedEntry> encodeLootForKey(String tableId, Rarity keyRarity, int lvl, long salt, int maxPicks) {
        List<EncodedEntry> out = new ArrayList<>();
        if (tableId == null || tableId.isEmpty() || maxPicks <= 0) return out;

        LootTable table = TableLoader.getByString(tableId);
        if (table == null || table.isEmpty()) return out;

        List<LootTable.Entry> entries = table.getEntries();
        if (entries.isEmpty()) return out;

        // 6 buckets: 0=BAD,1=COMMON,2=UNCOMMON,3=RARE,4=EPIC,5=LEGENDARY
        record Cand(LootTable.Entry e, double w, int tierIdx) {}
        List<Cand> cands = new ArrayList<>(entries.size());

        for (LootTable.Entry e : entries) {
            // map LootTable tier -> index (BAD..LEGENDARY)
            int tierIdx = switch (e.tier) {
                case BAD -> 0;
                case COMMON -> 1;
                case UNCOMMON -> 2;
                case RARE -> 3;
                case EPIC -> 4;
                case LEGENDARY -> 5;
            };

            // Level bias (kept), key-rarity bias, deterministic jitter
            double lvlMult = clamp(1.0 + Math.max(0, lvl) * table.getBiasPerLevel() * e.tier.score, 0.05, 50.0);
            double rarityMult = clamp(1.0 + KEY_RARITY_BIAS_PER_STEP * keyRarity.score() * e.tier.score, 0.05, 50.0);
            double jitter = jitterFor(salt, e.type) * (1.0 + 0.25 * Math.abs(e.tier.score));
            double jitterMult = 1.0 + clamp(jitter, -JITTER_MAX, JITTER_MAX);

            double w = Math.max(0.0, e.baseWeight * lvlMult * rarityMult * jitterMult);
            if (w > 0) cands.add(new Cand(e, w, tierIdx));
        }
        if (cands.isEmpty()) return out;

        int[] tierMax = tierMaxFor(keyRarity, maxPicks); // length 6
        int[] used    = new int[6];

        // Select without replacement respecting caps
        for (int pick = 0; pick < maxPicks && !cands.isEmpty(); pick++) {
            double total = 0.0;
            for (Cand c : cands) if (used[c.tierIdx] < tierMax[c.tierIdx]) total += c.w;
            if (total <= 0.0) break;

            double r = ThreadLocalRandom.current().nextDouble(total);
            double acc = 0.0;
            Cand chosen = null;
            for (Cand c : cands) {
                if (used[c.tierIdx] >= tierMax[c.tierIdx]) continue;
                acc += c.w;
                if (r <= acc) { chosen = c; break; }
            }
            if (chosen == null) break;

            used[chosen.tierIdx]++;
            int wUnits = (int) Math.max(1, Math.round(chosen.w * 1000.0));
            LootTable.Entry e = chosen.e;
            out.add(new EncodedEntry(e.type, e.minAmount, e.maxAmount, wUnits));
            cands.remove(chosen);
        }
        return out;
    }


    private int[] tierMaxFor(Rarity rarity, int picks) {
        int big = 999; // effectively "no cap"
        // order: 0=BAD, 1=COMMON, 2=UNCOMMON, 3=RARE, 4=EPIC, 5=LEGENDARY
        switch (rarity) {
            case COMMON:
                return new int[]{big, big, big, 1, 1, 0};
            case UNCOMMON:
                return new int[]{big, big, big, 2, 1, 0};
            case RARE:
                return new int[]{big, big, big, big, 2, 1};
            case EPIC:
                return new int[]{big, big, big, big, 3, 1};
            case LEGENDARY:
                return new int[]{big, big, big, big, 3, Math.min(2, Math.max(1, picks / 3))};
            default:
                return new int[]{big, big, big, 1, 1, 0}; // safe default
        }
    }


    private double jitterFor(long salt, String key) {
        long seed = salt ^ (key == null ? 0 : key.hashCode());
        seed ^= (seed << 21); seed ^= (seed >>> 35); seed ^= (seed << 4);
        double v = (seed & 0xFFFFFFFFL) / (double)(1L << 32);
        return (v * 2.0) - 1.0; // -1..1
    }

    private void saveEncodedLoot(PersistentDataContainer pdc, List<EncodedEntry> enc) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < enc.size(); i++) {
            EncodedEntry e = enc.get(i);
            if (i > 0) sb.append(';');
            sb.append(e.type).append('|').append(e.min).append('|').append(e.max).append('|').append(e.w);
        }
        pdc.set(PDC_ENCODED_LOOT, PersistentDataType.STRING, sb.toString());
        pdc.set(PDC_ENCODED_VER,  PersistentDataType.INTEGER, 1);
    }

    private List<String> buildEncodedLore(List<EncodedEntry> enc) {
        if (enc.isEmpty()) return Collections.emptyList();
        List<String> lore = new ArrayList<>();

        long total = 0; for (EncodedEntry e : enc) total += e.w; if (total <= 0) total = 1;
        enc.sort((a,b) -> Integer.compare(b.w, a.w));

        for (EncodedEntry e : enc) {
            String name = resolveDisplayNameFromPath(e.type);
            String amt  = (e.min == 1 && e.max == 1) ? "" :
                    (e.min == e.max ? " §7(" + e.min + ")" : " §7(" + e.min + "-" + e.max + ")");
            String pct  = formatPct((e.w * 100.0) / total);
            lore.add("§8• §f" + name + amt + " §f" + pct);
        }
        return lore;
    }

    // ---------- Small helpers ----------
    private static class EncodedEntry {
        final String type; final int min, max; final int w;
        EncodedEntry(String t, int mi, int ma, int w) { this.type = t; this.min = mi; this.max = ma; this.w = Math.max(1, w); }
    }

    private String resolveDisplayNameFromPath(String path) {
        try {
            ItemStack it = TLibs.getItemAPI().getCreator().getItemFromPath(path);
            if (it != null && it.getType() != Material.AIR) {
                ItemMeta im = it.getItemMeta();
                if (im != null && im.hasDisplayName()) return im.getDisplayName();
            }
        } catch (Exception ignored) {}
        String token = path == null ? "Item" : path.substring(path.lastIndexOf('.') + 1);
        token = token.replace('_', ' ');
        return Character.toUpperCase(token.charAt(0)) + token.substring(1).toLowerCase();
    }

    private String formatPct(double p) {
        if (p >= 9.95) return Math.round(p) + "%";
        double one = Math.round(p * 10.0) / 10.0;
        return (one == Math.rint(one)) ? ((int) one) + "%" : one + "%";
    }

    private double randomSigned(ThreadLocalRandom rng, double min, double max) {
        double v = rng.nextDouble(min, max);
        return rng.nextBoolean() ? v : -v;
    }

    private double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(v, hi)); }

    private void startCritTrail(Entity entity, int maxTicks) {
        new BukkitRunnable() {
            int t = 0;
            @Override public void run() {
                if (entity == null || !entity.isValid() || entity.isDead() || t++ >= maxTicks) { cancel(); return; }
                Location p = entity.getLocation().add(0, 0.1, 0);
                p.getWorld().spawnParticle(Particle.CRIT, p, 4, 0.05, 0.05, 0.05, 0.0);
            }
        }.runTaskTimer(TrialRooms.getInstance(), 0L, 2L);
    }

    private double mobKeyChanceForLevel(int lvl) {
        double p = MOB_KEY_BASE_CHANCE + Math.max(0, lvl) * MOB_KEY_CHANCE_PER_LEVEL;
        if (p > MOB_KEY_MAX_CHANCE) p = MOB_KEY_MAX_CHANCE;
        if (p < 0) p = 0;
        return p;
    }

    public void maybeDropMobKeyAt(Location at) {
        if (at == null || at.getWorld() == null) return;

        // Only drop if the mob loot table is set & valid
        String mobTable = spawner.getMobLootTable();
        if (mobTable == null || TableLoader.getByString(mobTable) == null) return;

        double chance = mobKeyChanceForLevel(spawner.getLevel());
        if (ThreadLocalRandom.current().nextDouble() <= chance) {
            dropMobKeyNow(at, mobTable);
        }
    }

    private void dropMobKeyNow(Location where, String mobTableId) {
        World w = where.getWorld();
        if (w == null) return;

        ItemStack stack = TLibs.getItemAPI().getCreator().getItemFromPath(Cache.mobKey);
        if (stack == null || stack.getType() == Material.AIR) return;
        stack.setAmount(1);

        Rarity rarity = rollRarity(spawner.getLevel());
        long salt = ThreadLocalRandom.current().nextLong();

        applyKeyStyling(stack, rarity, salt, "Mob Key", mobTableId);

        Location drop = where.clone().add(0, 0.25, 0);
        w.spawnParticle(Particle.CLOUD, drop, 12, 0.25, 0.25, 0.25, 0.0);
        w.spawnParticle(Particle.END_ROD, drop, 8, 0.20, 0.20, 0.20, 0.0);
        w.playSound(drop, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);

        org.bukkit.entity.Item item = w.dropItem(drop, stack);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double vx = randomSigned(rng, 0.03, 0.08);
        double vz = randomSigned(rng, 0.03, 0.08);
        double vy = rng.nextDouble(0.25, 0.55);
        item.setVelocity(new Vector(vx, vy, vz));

        String name = stack.getItemMeta().getDisplayName();
        item.setCustomName("§f1x " + name);
        item.setCustomNameVisible(true);
        startCritTrail(item, 140);
    }


}
