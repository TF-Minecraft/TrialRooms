package net.tfminecraft.trialrooms;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.api.TaxLeg;
import net.tfminecraft.denareconomy.api.TaxPolicy;
import net.tfminecraft.denareconomy.ledger.AccountIds;
import net.tfminecraft.denareconomy.ledger.Ledger;
import net.tfminecraft.tlibs.character.CharacterIdentity;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterStatus;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.mockbukkit.mockbukkit.MockBukkit;

/** A real DenarEconomy ledger in a temporary folder and a character provider for tests. */
public final class TestMoney implements CharacterIdentity {
    private final Map<UUID, CharacterSession> sessions = new HashMap<>();
    private final Map<UUID, CharacterInfo> characters = new HashMap<>();
    private long nextSession;

    /** Opens the ledger and registers the provider with the running MockBukkit server. */
    public static TestMoney open(Path folder) {
        DenarEconomy.setLedger(Ledger.open(folder.resolve("ledger.db").toFile(),
                Logger.getLogger("TrialRoomsTest"), AccountIds::frozen));
        TestMoney money = new TestMoney();
        Bukkit.getServicesManager().register(CharacterIdentity.class, money,
                MockBukkit.createMockPlugin(), ServicePriority.Normal);
        return money;
    }

    /** Closes the ledger. Call before MockBukkit.unmock(). */
    public static void close() {
        DenarEconomy.getLedger().close();
        DenarEconomy.setLedger(Ledger.unavailable());
    }

    /** Taxes every earning at this percentage into a guild's holding account. */
    public static String taxAt(int percent) {
        String account = AccountIds.guildTax(UUID.randomUUID());
        TaxPolicy policy = (earner, gross) -> Optional.of(new TaxLeg(account, gross * percent / 100));
        Bukkit.getServicesManager().register(TaxPolicy.class, policy,
                MockBukkit.createMockPlugin(), ServicePriority.Normal);
        return account;
    }

    /** Gives the player a new living character and makes it current. */
    public CharacterRef play(Player player) {
        CharacterRef ref = new CharacterRef(player.getUniqueId(), UUID.randomUUID());
        characters.put(ref.characterId(), new CharacterInfo(ref, "Char", CharacterStatus.ALIVE, null,
                Set.of(), 0, null));
        sessions.put(player.getUniqueId(), new CharacterSession(ref, ++nextSession));
        return ref;
    }

    public void stop(Player player) {
        sessions.remove(player.getUniqueId());
    }

    @Override
    public Optional<CharacterSession> currentSession(Player player) {
        return Optional.ofNullable(sessions.get(player.getUniqueId()));
    }

    @Override
    public boolean isCurrent(CharacterSession session) {
        return sessions.containsValue(session);
    }

    @Override
    public Optional<CharacterInfo> info(UUID characterId) {
        return Optional.ofNullable(characters.get(characterId));
    }

    @Override
    public Optional<List<CharacterInfo>> characters(UUID ownerId) {
        return Optional.of(characters.values().stream()
                .filter(c -> c.ref().ownerId().equals(ownerId)).toList());
    }

    @Override
    public Optional<CharacterInfo> lastPlayed(UUID ownerId) {
        return Optional.empty();
    }

    @Override
    public String displayName(Player player) {
        return player.getName();
    }
}
