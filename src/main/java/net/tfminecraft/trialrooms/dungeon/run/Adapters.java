package net.tfminecraft.trialrooms.dungeon.run;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.mobs.ActiveMob;
import net.tfminecraft.rpcharacters.managers.PlayerManager;
import net.tfminecraft.rpcharacters.objects.PlayerData;
import net.tfminecraft.rpcharacters.party.Party;
import net.tfminecraft.rpcharacters.party.PartyManager;
import net.tfminecraft.tlibs.TLibs;

/** The live implementations of {@link Ports}: RPCharacters, TLibs and MythicMobs. */
public final class Adapters {

    private Adapters() {
    }

    public static final class RpcCharacters implements Ports.Characters {
        private final Function<Player, PlayerData> players;
        private final Supplier<PartyManager> parties;

        public RpcCharacters() {
            this(PlayerManager::get, PartyManager::get);
        }

        RpcCharacters(Function<Player, PlayerData> players, Supplier<PartyManager> parties) {
            this.players = players;
            this.parties = parties;
        }

        @Override
        public String activeCharacterId(Player player) {
            PlayerData data = players.apply(player);
            return data != null && data.hasActiveCharacter() ? data.getActiveCharacter().getId() : null;
        }

        @Override
        public UUID partyLeader(Player player) {
            Party party = parties.get().getParty(player.getUniqueId());
            return party == null ? null : party.getLeaderId();
        }

        @Override
        public List<UUID> partyMembers(Player player) {
            Party party = parties.get().getParty(player.getUniqueId());
            return party == null ? List.of(player.getUniqueId()) : new ArrayList<>(party.getMemberIds());
        }
    }

    public static final class TLibsKeys implements Ports.Keys {
        @Override
        public boolean holds(Player player, String path) {
            return TLibs.getItemAPI().getChecker().checkItemWithPath(player.getInventory().getItemInMainHand(), path);
        }

        @Override
        public boolean take(Player player, String path) {
            ItemStack[] contents = player.getInventory().getContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack item = contents[i];
                if (item != null && TLibs.getItemAPI().getChecker().checkItemWithPath(item, path)) {
                    if (item.getAmount() > 1) {
                        item.setAmount(item.getAmount() - 1);
                    } else {
                        player.getInventory().setItem(i, null);
                    }
                    return true;
                }
            }
            return false;
        }

        @Override
        public void give(Player player, String path) {
            ItemStack item = TLibs.getItemAPI().getCreator().getItemFromPath(path);
            for (ItemStack left : player.getInventory().addItem(item).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }
    }

    /** Fight warnings as vanilla particles, kept few so a room of them stays cheap. */
    public static final class BukkitFx implements Ports.Fx {
        @Override
        public void ring(org.bukkit.World world, double x, double y, double z, double radius) {
            int points = Math.max(12, (int) (radius * 6));
            for (int i = 0; i < points; i++) {
                double a = 2 * Math.PI * i / points;
                world.spawnParticle(org.bukkit.Particle.FLAME, x + Math.cos(a) * radius, y + 0.15,
                        z + Math.sin(a) * radius, 1, 0, 0, 0, 0);
            }
        }

        @Override
        public void hurt(Player player, double amount, Entity source) {
            org.bukkit.damage.DamageSource.Builder b = org.bukkit.damage.DamageSource.builder(
                    org.bukkit.damage.DamageType.MAGIC);
            if (source != null) {
                b.withCausingEntity(source).withDirectEntity(source);
            }
            player.damage(amount, b.build());
        }

        @Override
        public void burst(Location at) {
            at.getWorld().spawnParticle(org.bukkit.Particle.ENCHANT, at.getX(), at.getY() + 1, at.getZ(), 8, 0.4, 0.6,
                    0.4, 0.2);
        }
    }

    public static final class MythicMobsAdapter implements Ports.Mobs {
        @Override
        public Entity spawn(String type, Location at, int level) {
            ActiveMob mob = MythicBukkit.inst().getMobManager().spawnMob(type, at, level);
            return mob == null ? null : mob.getEntity().getBukkitEntity();
        }

        @Override
        public void remove(Entity mob) {
            MythicBukkit.inst().getMobManager().getActiveMob(mob.getUniqueId()).ifPresent(ActiveMob::remove);
            mob.remove();
        }
    }
}
