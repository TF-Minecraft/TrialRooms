package net.tfminecraft.trialrooms.dungeon.run;

import java.util.List;
import java.util.Locale;

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
 */
public record RunSettings(int maxRuns, int countdownSeconds, int bossCountdownSeconds, int bleedOutSeconds,
        int reviveSeconds, double reviveRadius, double gateRadius, int graceSeconds, int finishSeconds,
        List<String> blockedCommands) {

    static final List<String> DEFAULT_BLOCKED = List.of("home", "homes", "sethome", "spawn", "warp", "tpa",
            "tpahere", "tpaccept", "back", "rtp");

    public RunSettings {
        blockedCommands = blockedCommands.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    public static RunSettings defaults() {
        return new RunSettings(4, 4, 5, 30, 3, 2.5, 8, 300, 60, DEFAULT_BLOCKED);
    }

    public static RunSettings from(ConfigurationSection s) {
        RunSettings d = defaults();
        List<String> blocked = s.isList("blocked-commands") ? s.getStringList("blocked-commands") : d.blockedCommands();
        return new RunSettings(Math.max(1, s.getInt("max-runs", d.maxRuns())),
                Math.max(0, s.getInt("countdown-seconds", d.countdownSeconds())),
                Math.max(0, s.getInt("boss-countdown-seconds", d.bossCountdownSeconds())),
                Math.max(1, s.getInt("bleed-out-seconds", d.bleedOutSeconds())),
                Math.max(1, s.getInt("revive-seconds", d.reviveSeconds())),
                Math.max(1, s.getDouble("revive-radius", d.reviveRadius())),
                Math.max(1, s.getDouble("gate-radius", d.gateRadius())),
                Math.max(0, s.getInt("disconnect-grace-seconds", d.graceSeconds())),
                Math.max(5, s.getInt("finish-seconds", d.finishSeconds())), blocked);
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
