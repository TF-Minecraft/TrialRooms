package net.tfminecraft.trialrooms.dungeon.run;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.tfminecraft.trialrooms.dungeon.locker.Locker;
import net.tfminecraft.trialrooms.dungeon.locker.LockerService;
import net.tfminecraft.trialrooms.dungeon.run.DungeonRun.Member;
import net.tfminecraft.trialrooms.dungeon.run.RunManager.ActiveRun;
import net.tfminecraft.trialrooms.dungeon.run.RunRewards.Find;

/**
 * What players find in runs that bank finds. A find is carried until a save point banks it; a fall
 * loses what is carried; everything comes home when the boss falls, and when the run fails, banked
 * finds come home by the difficulty's rule. What each player holds is kept on disk as it changes
 * (a crash loses nothing), every loss is logged for staff, and what is owed to a character (a
 * crashed run, finds settled while they were away, a loss staff gave back) is handed over when they
 * next play it outside a run. Also the ways out of treasure rooms, and insurance of a worn piece.
 */
final class RunFinds {

    /** A member's finds: what they carry, and what a save point banked. */
    static final class Held {
        final List<Find> carried = new ArrayList<>();
        final List<Find> banked = new ArrayList<>();

        boolean empty() {
            return carried.isEmpty() && banked.isEmpty();
        }
    }

    /**
     * What a way out lets a player take home: what comes home anyway ({@code safe}: Normal's banked
     * finds), and picks from what they would lose ({@code atRisk}) worth up to {@code allowance}:
     * half of its value, or its best find when that is worth more.
     */
    record Offer(List<Find> safe, List<Find> atRisk, int allowance) {

        /** A find's weight in a way out's sums: its value, at least 1 (a find without a value still counts). */
        static int weight(Find f) {
            return Math.max(1, f.value());
        }

        /** Whether picks (indexes into atRisk) are all there, each once, and within the allowance. */
        boolean allows(Collection<Integer> picks) {
            if (new HashSet<>(picks).size() != picks.size()) {
                return false;
            }
            int sum = 0;
            for (int i : picks) {
                if (i < 0 || i >= atRisk.size()) {
                    return false;
                }
                sum += weight(atRisk.get(i));
            }
            return sum <= allowance;
        }
    }

    /** A worn piece that can be insured, and what that costs. */
    record Insurable(String id, ItemStack item, int value, int cost) {
    }

    private final RunManager runs;
    private FindStore store;

    RunFinds(RunManager runs) {
        this.runs = runs;
    }

    void store(FindStore store) {
        this.store = store;
    }

    FindStore store() {
        return store;
    }

    static int value(List<Find> finds) {
        return finds.stream().mapToInt(Find::value).sum();
    }

    Held held(ActiveRun ar, UUID player) {
        return ar.held.computeIfAbsent(player, k -> new Held());
    }

    // ------------------------------------------------------------------ carrying, banking, falling

    /** A find a member now carries. */
    void carry(ActiveRun ar, Player p, Find f) {
        held(ar, p.getUniqueId()).carried.add(f);
        save(ar, p.getUniqueId());
        p.sendMessage(Component.text("You carry a find: ", NamedTextColor.GOLD)
                .append(Component.text(f.label(), NamedTextColor.WHITE))
                .append(Component.text(" (value " + f.value() + "). Bank it or win to bring it home. ", NamedTextColor.GRAY))
                .append(button()));
    }

    /** [Finds]: opens the finds screen. */
    static Component button() {
        return Component.text("[Finds]", NamedTextColor.GOLD).clickEvent(ClickEvent.runCommand("/tr dungeon finds"))
                .hoverEvent(HoverEvent.showText(Component.text("See what you carry and what is banked")));
    }

    /** Keeps what a member holds on disk (or clears it when they hold nothing). */
    void save(ActiveRun ar, UUID player) {
        if (store == null) {
            return;
        }
        Held h = ar.held.get(player); // (there is one: it was just changed)
        if (h.empty()) {
            store.clearHold(player);
            return;
        }
        store.hold(player, new FindStore.Pouch(ar.run.id(), ar.run.member(player).characterId(), ar.run.recipeId(),
                ar.rules.tier(), ar.boost, h.carried, h.banked));
    }

    /** A save point: what each member carries is banked. */
    void bank(ActiveRun ar) {
        for (var e : ar.held.entrySet()) {
            Held h = e.getValue();
            if (!h.carried.isEmpty()) {
                h.banked.addAll(h.carried);
                h.carried.clear();
                save(ar, e.getKey());
                java.util.Optional.ofNullable(org.bukkit.Bukkit.getPlayer(e.getKey()))
                        .ifPresent(p -> p.sendMessage("§a✦ Saved: §7what you carried is banked."));
            }
        }
    }

    /**
     * What a fall costs (no life is spent here): what they carry, and where gear is at stake, the
     * dungeon gear they wear (bar an insured piece). Both are logged for staff, with why.
     */
    void lose(ActiveRun ar, Player p, String why, String detail) {
        UUID id = p.getUniqueId();
        Held h = ar.held.get(id);
        List<Find> lost = new ArrayList<>();
        if (h != null && !h.carried.isEmpty()) {
            lost.addAll(h.carried);
            h.carried.clear();
            save(ar, id);
            p.sendMessage("§cYou fell: what you carried is lost. §7Banked finds are safe.");
        }
        List<byte[]> gear = List.of();
        LockerService lockers = runs.lockers();
        if (ar.gearAtRisk && ar.run.state() == DungeonRun.State.RUNNING && lockers != null) {
            // (an insured piece is worn: insurance on one not worn is refunded as the doors open)
            String spare = ar.insured.get(id);
            boolean wore = spare != null && lockers.wears(p, spare);
            gear = lockers.forfeitWorn(p, spare);
            if (!gear.isEmpty()) {
                p.sendMessage("§4Your worn dungeon gear is destroyed.");
            }
            if (wore) {
                p.sendMessage("§aYour insured piece was spared: it is back in your locker.");
            }
        }
        log(ar, id, why, detail, lost, gear);
    }

    // ------------------------------------------------------------------ coming home

    /** What comes home, how many of those were banked, and what is lost. */
    private record Parts(List<Find> pay, int bankedHome, List<Find> lost) {
    }

    /** Splits finds into what comes home (banked with the chance keep, carried with carried) and what is lost. */
    private Parts split(Held h, double keep, boolean carried) {
        List<Find> pay = new ArrayList<>();
        List<Find> lost = new ArrayList<>();
        for (Find f : h.banked) {
            (keep >= 1 || runs.keepRoll.getAsDouble() < keep ? pay : lost).add(f);
        }
        int bankedHome = pay.size();
        (carried ? pay : lost).addAll(h.carried);
        return new Parts(pay, bankedHome, lost);
    }

    /** Forgets what a player holds on disk. */
    private void clear(UUID player) {
        if (store != null) {
            store.clearHold(player);
        }
    }

    /**
     * Pays what a member here found: banked finds each kept with the chance {@code keep}, and with
     * {@code carried} what they carry too. What stays behind is logged with {@code why}.
     */
    void payout(ActiveRun ar, Player p, double keep, boolean carried, String why) {
        UUID id = p.getUniqueId();
        Held h = ar.held.remove(id);
        if (h == null) {
            return;
        }
        Parts parts = split(h, keep, carried);
        List<Find> pay = parts.pay();
        boolean ok = deliver(ar, p, pay); // (finds are only ever held with rewards to hand them over)
        clear(id); // (after the hand-over: a crash in between hands it over twice rather than never)
        log(ar, id, why, "", parts.lost(), List.of());
        int banked = h.banked.size();
        if (!ok) {
            p.sendMessage("§cSome of your finds could not be handed over yet: they wait for you. Tell staff if this keeps happening.");
        } else if (keep < 1 && banked > 0) {
            p.sendMessage("§e" + parts.bankedHome() + " of " + banked + " banked finds come home.");
        } else if (!pay.isEmpty()) {
            p.sendMessage("§a✦ Your finds come home.");
        }
    }

    /**
     * Hands finds to a player here; each on its own, so one that fails never takes the rest with it.
     * One that fails waits for them (handed over when they next play the character outside a run).
     */
    private boolean deliver(ActiveRun ar, Player p, List<Find> pay) {
        RunRewards rewards = runs.rewards();
        String d = ar.difficulty.id(); // (only a difficulty banks finds)
        List<Find> failed = new ArrayList<>();
        for (Find f : pay) {
            if (!runs.safely(p, "a found " + f.label(),
                    () -> rewards.deliver(p, f, ar.run.recipeId(), ar.rules.tier(), ar.run.id(), ar.boost, false))) {
                failed.add(f);
            }
            if (f.moment() == RunRewards.Moment.BOSS) {
                List<String> party = ar.securedParty.isEmpty() ? RunManager.stayed(ar) : ar.securedParty;
                runs.safely(p, "a trophy roll", () -> rewards.feat(p, ar.run.recipeId(), d, f.moment(), ar.run.id(), party));
            }
        }
        if (!failed.isEmpty() && store != null) {
            store.addPending(p.getUniqueId(), new FindStore.Pending(ar.run.member(p.getUniqueId()).characterId(),
                    ar.run.recipeId(), ar.run.id(), ar.rules.tier(), ar.boost, false, "finds that could not be handed over",
                    failed, List.of()));
        }
        return failed.isEmpty();
    }

    /**
     * Settles a member who is not here (offline, or away from the dungeon): what comes home by the
     * same rules waits for them, handed over when they next play that character.
     */
    void settleAway(ActiveRun ar, Member m, double keep, boolean carried, String why) {
        Held h = ar.held.remove(m.id());
        if (h == null || store == null) {
            return;
        }
        Parts parts = split(h, keep, carried);
        if (!parts.pay().isEmpty()) {
            store.addPending(m.id(), new FindStore.Pending(m.characterId(), ar.run.recipeId(), ar.run.id(), ar.rules.tier(),
                    ar.boost, false, why, parts.pay(), List.of()));
        }
        store.clearHold(m.id());
        log(ar, m.id(), why, "", parts.lost(), List.of());
    }

    /** Hands over what is owed to the character a player plays; true when anything was. */
    boolean deliverPending(Player p, String character) {
        RunRewards rewards = runs.rewards();
        if (store == null || rewards == null) {
            return false;
        }
        List<FindStore.Pending> owed = store.pending(p.getUniqueId(), character);
        if (!owed.isEmpty()) {
            store.takePending(p.getUniqueId(), character); // (taken after it was read: what fails is put back)
        }
        for (FindStore.Pending o : owed) {
            List<Find> failed = new ArrayList<>();
            for (Find f : o.finds()) {
                if (!runs.safely(p, "an owed find", () -> rewards.deliver(p, f, o.recipe(), o.tier(), o.run(), o.boost(), o.restore()))) {
                    failed.add(f);
                }
            }
            LockerService lockers = runs.lockers();
            List<byte[]> gear = o.gear().isEmpty() || lockers != null && lockers.restore(p, o.gear()) ? List.of() : o.gear();
            if (failed.isEmpty() && gear.isEmpty()) {
                p.sendMessage("§a✦ Finds came home: " + o.why() + ".");
            } else {
                // kept for the next time (a locker that can't be read or saved right now)
                store.addPending(p.getUniqueId(), new FindStore.Pending(character, o.recipe(), o.run(), o.tier(), o.boost(),
                        o.restore(), o.why(), failed, gear));
                p.sendMessage("§cSome finds owed to you could not be handed over yet. Tell staff if this keeps happening.");
            }
        }
        return !owed.isEmpty();
    }

    private void log(ActiveRun ar, UUID player, String why, String detail, List<Find> lost, List<byte[]> gear) {
        if (store == null || lost.isEmpty() && gear.isEmpty()) {
            return;
        }
        store.logLoss(player, new FindStore.Loss(0, runs.now(), ar.run.member(player).characterId(), ar.run.id(),
                ar.run.recipeId(), ar.difficulty.id(), ar.rules.tier(), why, detail, lost, // (only difficulties hold finds or stake gear)
                gear, false));
    }

    // ------------------------------------------------------------------ ways out

    /** What a way out would let a player take. */
    Offer offer(ActiveRun ar, Player p) {
        Held h = ar.held.getOrDefault(p.getUniqueId(), new Held());
        boolean bankedSafe = ar.banking.keep() >= 1;
        List<Find> risk = new ArrayList<>(bankedSafe ? List.of() : h.banked);
        risk.addAll(h.carried);
        int best = risk.stream().mapToInt(Offer::weight).max().orElse(0);
        int total = risk.stream().mapToInt(Offer::weight).sum();
        return new Offer(List.copyOf(bankedSafe ? h.banked : List.of()), List.copyOf(risk), Math.max((total + 1) / 2, best));
    }

    /**
     * Pays a player leaving through a way out: what comes home anyway, and their picks; the rest is
     * lost (and logged). False when the picks go past what the way out allows (nothing changes).
     */
    boolean exit(ActiveRun ar, Player p, List<Integer> picks) {
        Offer o = offer(ar, p);
        if (!o.allows(picks)) {
            return false;
        }
        UUID id = p.getUniqueId();
        ar.held.remove(id);
        List<Find> pay = new ArrayList<>(o.safe());
        List<Find> lost = new ArrayList<>();
        for (int i = 0; i < o.atRisk().size(); i++) {
            (picks.contains(i) ? pay : lost).add(o.atRisk().get(i));
        }
        boolean ok = deliver(ar, p, pay);
        clear(id);
        log(ar, id, "left through a way out (finds not taken)", "", lost, List.of());
        if (!ok) {
            p.sendMessage("§cSome of your finds could not be handed over yet: they wait for you. Tell staff if this keeps happening.");
        } else if (!pay.isEmpty()) {
            p.sendMessage("§a✦ You take " + pay.size() + " find" + (pay.size() == 1 ? "" : "s") + " home.");
        }
        return true;
    }

    // ------------------------------------------------------------------ insurance

    /** The pieces a player wears that can be insured. */
    List<Insurable> insurable(Player p) {
        LockerService lockers = runs.lockers();
        RunRewards rewards = runs.rewards();
        Locker l = lockers == null || rewards == null ? null : lockers.locker(p);
        List<Insurable> out = new ArrayList<>();
        if (l == null) {
            return out;
        }
        for (String id : new LinkedHashSet<>(l.wornSlots().values())) {
            ItemStack it = lockers.show(l, id);
            int value = rewards.value(it);
            out.add(new Insurable(id, it, value, Math.max(1, rewards.insurance(value))));
        }
        return out;
    }

    /** Insures a worn piece (or, picked again, cancels its insurance); returns what to tell the player. */
    String insure(ActiveRun ar, Player p, String id) {
        UUID u = p.getUniqueId();
        if (id.equals(ar.insured.get(u))) {
            refund(ar, u);
            return "§7Insurance cancelled: your marks are back.";
        }
        Insurable pick = insurable(p).stream().filter(i -> i.id().equals(id)).findFirst().orElse(null);
        if (pick == null) {
            return "§cWear that piece first: put on its loadout in the locker.";
        }
        if (!runs.rewards().spend(p, pick.cost())) {
            return "§cInsuring it costs " + pick.cost() + " marks; you have " + Math.max(0, runs.rewards().marks(p)) + ".";
        }
        refund(ar, u); // one piece: the one insured before is let go (paid for the new one first)
        ar.insured.put(u, id);
        ar.insurePaid.put(u, pick.cost());
        return "§aInsured for " + pick.cost() + " marks: if you fall, this piece is spared.";
    }

    /**
     * Gives back what a member paid for insurance (leaving before the doors open, or cancelling): to
     * them at once when they play the run here, otherwise it waits for the character that paid.
     */
    void refund(ActiveRun ar, UUID member) {
        ar.insured.remove(member);
        Integer paid = ar.insurePaid.remove(member);
        if (paid == null) {
            return;
        }
        Player p = org.bukkit.Bukkit.getPlayer(member);
        if (p != null && runs.here(ar, p) && runs.rewards().spend(p, -paid)) { // (paid with the rewards' marks)
            return;
        }
        if (store != null) {
            store.addPending(member, new FindStore.Pending(ar.run.member(member).characterId(), ar.run.recipeId(),
                    ar.run.id(), ar.rules.tier(), 1, true, "your insurance was refunded", List.of(new Find(null, 0,
                            paid + " dungeon marks (insurance refund)", 0, paid, false, List.of(), List.of(), true)),
                    List.of()));
        }
    }

    /** As the doors open: insurance on a piece no longer worn is refunded. */
    void doorsOpen(ActiveRun ar, List<Player> here) {
        for (Player p : here) {
            String id = ar.insured.get(p.getUniqueId());
            if (id == null) {
                continue;
            }
            Locker l = runs.lockers().locker(p); // (insured from the lockers, so they are there)
            if (l == null || !l.wornSlots().containsValue(id)) {
                refund(ar, p.getUniqueId());
                p.sendMessage("§eYour insurance was refunded: you are not wearing that piece.");
            }
        }
    }
}
