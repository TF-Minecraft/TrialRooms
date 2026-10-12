package net.tfminecraft.trialrooms.environment.entrance;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.trialrooms.cache.Cache;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** A saved earning intent; interrupted inventories require staff reconciliation, not a new payment. */
public final class LootConversion {
    private final String opId = "trialrooms:" + UUID.randomUUID();
    private final Player player;
    private final CharacterRef earner;
    private final Map<Integer, ItemStack> items;
    private final long cents;
    private final Path intent;
    private boolean recorded;
    private boolean taken;

    private LootConversion(Player player, CharacterRef earner, Map<Integer, ItemStack> items,
            long cents, Path folder) {
        this.player = player;
        this.earner = earner;
        this.items = items;
        this.cents = cents;
        this.intent = folder.resolve(earner.ownerId() + ".properties");
    }

    public static LootConversion plan(Player player, Path folder) {
        Map<Integer, ItemStack> items = new LinkedHashMap<>();
        double total = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) continue;
            double perItem = Cache.getConversionAmount(item);
            if (perItem <= 0 || !Double.isFinite(perItem)) continue;
            total += perItem * item.getAmount();
            items.put(slot, item.clone());
        }
        long cents = Math.round(total * 100);
        if (cents <= 0) return null;
        var character = Denars.active(player);
        if (character.isEmpty()) {
            player.sendMessage("§cChoose a character before converting loot.");
            return null;
        }
        return new LootConversion(player, character.get(), items, cents, folder);
    }

    public int count() {
        return items.values().stream().mapToInt(ItemStack::getAmount).sum();
    }

    public long cents() {
        return cents;
    }

    public Result settle() throws IOException {
        if (taken) return Result.APPLIED;
        if (!recorded) record();
        Result result = Denars.status(opId).orElseGet(
                () -> Denars.earn(opId, earner, Denars.pouch(earner), cents, true));
        if (result == Result.APPLIED) {
            for (var item : items.entrySet()) {
                if (!item.getValue().equals(player.getInventory().getItem(item.getKey()))) {
                    throw new IOException("Paid loot changed before removal: " + opId);
                }
            }
            for (int slot : items.keySet()) player.getInventory().setItem(slot, null);
            player.updateInventory();
            taken = true;
        }
        if (result == Result.APPLIED || result == Result.REFUSED) {
            Files.deleteIfExists(intent);
            syncDirectory();
        }
        return result;
    }

    private void record() throws IOException {
        Files.createDirectories(intent.getParent());
        try (FileChannel parent = FileChannel.open(intent.getParent().getParent(), StandardOpenOption.READ)) {
            parent.force(true);
        }
        Properties data = new Properties();
        data.setProperty("operation", opId);
        data.setProperty("owner", earner.ownerId().toString());
        data.setProperty("character", earner.characterId().toString());
        data.setProperty("grossCents", Long.toString(cents));
        data.setProperty("items", items.toString());
        try (FileChannel channel = FileChannel.open(intent,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            data.store(Channels.newOutputStream(channel), "Pending loot conversion; reconcile before removing");
            channel.force(true);
        }
        syncDirectory();
        recorded = true;
    }

    private void syncDirectory() throws IOException {
        try (FileChannel directory = FileChannel.open(intent.getParent(), StandardOpenOption.READ)) {
            directory.force(true);
        }
    }
}
