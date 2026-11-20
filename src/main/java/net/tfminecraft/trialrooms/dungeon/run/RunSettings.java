package net.tfminecraft.trialrooms.dungeon.run;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Server-wide dungeon run settings from {@code dungeons/settings.yml}.
 *
 * @param maxRuns            most runs at once; gates refuse more
 * @param countdownSeconds   seconds between entering a fight room and its doors sealing (parties)
 * @param bossCountdownSeconds seconds before a boss fight once everyone is inside
 * @param bleedOutSeconds    seconds a downed player waits for a revive
 * @param reviveSeconds      seconds an ally crouches beside a downed player to revive them
 * @param reviveRadius       blocks within which crouching revives
 * @param gateRadius         blocks around a gate within which party members join the run
 * @param graceSeconds       seconds a disconnected member keeps their place
 * @param finishSeconds      seconds after a run ends before everyone still inside is sent back
 * @param blockedCommands    command names players can't use inside the dungeon world
 * @param holdSeconds        seconds a freed dungeon is held for the next party in line
 * @param harmfulAuras       MythicMobs auras that harm (statuses): players never put them on allies here
 * @param helpfulAuras       MythicMobs auras that help (buffs): players never put them on mobs here
 * @param selfReviveSeconds  seconds a downed player with nobody to revive them crouches to get up (once per fight)
 * @param aloneBleedOutSeconds seconds such a player waits once their own revive is spent
 */
public record RunSettings(int maxRuns, int countdownSeconds, int bossCountdownSeconds, int bleedOutSeconds,
        int reviveSeconds, double reviveRadius, double gateRadius, int graceSeconds, int finishSeconds,
        List<String> blockedCommands, int holdSeconds, Set<String> harmfulAuras, Set<String> helpfulAuras,
        int selfReviveSeconds, int aloneBleedOutSeconds) {

    static final List<String> DEFAULT_BLOCKED = List.of("home", "homes", "sethome", "spawn", "warp", "tpa",
            "tpahere", "tpaccept", "back", "rtp");
    static final List<String> DEFAULT_HARMFUL = List.of("Slowed", "Weakened", "Wounded", "Silenced", "Silence_Struck",
            "Burnt", "Frozen", "Stun_Aura", "Vine_Wave_Stun", "TFMC_Guard_Vibrative_Strike_STUN", "Sanguine_Chain_Lock",
            "Glacial_Quills_Stack");
    static final List<String> DEFAULT_HELPFUL = List.of("guardian_angel", "Rhapsody_aura", "Angelic_Serenade_aura",
            "TFMC_Guard_Restoration", "Guardian_Angel_Ward",
            "TFMC_Guard_Shield_Wall_Ward");

    public RunSettings {
        blockedCommands = blockedCommands.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        harmfulAuras = lower(harmfulAuras);
        helpfulAuras = lower(helpfulAuras);
    }

    private static Set<String> lower(Set<String> names) {
        return names.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    public static RunSettings defaults() {
        return new RunSettings(4, 4, 5, 30, 3, 2.5, 8, 300, 60, DEFAULT_BLOCKED, 60, Set.copyOf(DEFAULT_HARMFUL),
                Set.copyOf(DEFAULT_HELPFUL), 5, 10);
    }

    public static RunSettings from(ConfigurationSection s) {
        RunSettings d = defaults();
        List<String> blocked = s.isList("blocked-commands") ? s.getStringList("blocked-commands") : d.blockedCommands();
        List<String> harmful = s.isList("harmful-auras") ? s.getStringList("harmful-auras") : DEFAULT_HARMFUL;
        List<String> helpful = s.isList("helpful-auras") ? s.getStringList("helpful-auras") : DEFAULT_HELPFUL;
        return new RunSettings(Math.max(1, s.getInt("max-runs", d.maxRuns())),
                Math.max(0, s.getInt("countdown-seconds", d.countdownSeconds())),
                Math.max(0, s.getInt("boss-countdown-seconds", d.bossCountdownSeconds())),
                Math.max(1, s.getInt("bleed-out-seconds", d.bleedOutSeconds())),
                Math.max(1, s.getInt("revive-seconds", d.reviveSeconds())),
                Math.max(1, s.getDouble("revive-radius", d.reviveRadius())),
                Math.max(1, s.getDouble("gate-radius", d.gateRadius())),
                Math.max(0, s.getInt("disconnect-grace-seconds", d.graceSeconds())),
                Math.max(5, s.getInt("finish-seconds", d.finishSeconds())), blocked,
                Math.max(10, s.getInt("queue-hold-seconds", d.holdSeconds())), Set.copyOf(harmful), Set.copyOf(helpful),
                Math.max(1, s.getInt("self-revive-seconds", d.selfReviveSeconds())),
                Math.max(1, s.getInt("alone-bleed-out-seconds", d.aloneBleedOutSeconds())));
    }

    /** Whether a typed command line ("/home x") starts with a blocked command, with or without a namespace. */
    public boolean blocks(String commandLine) {
        String first = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        int space = first.indexOf(' ');
        String name = (space < 0 ? first : first.substring(0, space)).toLowerCase(Locale.ROOT);
        int colon = name.indexOf(':');
        return blockedCommands.contains(colon < 0 ? name : name.substring(colon + 1));
    }
}
