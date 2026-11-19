package net.tfminecraft.trialrooms.dungeon.run;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.tfminecraft.trialrooms.dungeon.generator.DungeonLayout;
import net.tfminecraft.trialrooms.dungeon.template.RoomType;

/**
 * The state of one dungeon run: who is in it, their lives, and each room's progress. Pure Java;
 * {@link RunManager} turns its decisions into blocks, mobs and teleports.
 */
public final class DungeonRun {

    public enum State { RUNNING, COMPLETE, FAILED, ENDED }

    public enum Status { ALIVE, DOWNED, OUT, LEFT }

    public enum Room { CLEARED, IDLE, COUNTDOWN, ACTIVE }

    /** One player in the run, playing one character. */
    public static final class Member {
        private final UUID id;
        private final String characterId;
        private Status status = Status.ALIVE;
        private long downedAtMs;
        private long reviveMs;
        private boolean online = true;
        private long offlineSinceMs;
        private int room = -1;

        Member(UUID id, String characterId) {
            this.id = id;
            this.characterId = characterId;
        }

        public UUID id() {
            return id;
        }

        public String characterId() {
            return characterId;
        }

        public Status status() {
            return status;
        }

        public long downedAtMs() {
            return downedAtMs;
        }

        public long reviveMs() {
            return reviveMs;
        }

        public boolean online() {
            return online;
        }

        public long offlineSinceMs() {
            return offlineSinceMs;
        }

        public int room() {
            return room;
        }

        public void room(int room) {
            this.room = room;
        }

        /** Alive and online: able to fight, revive and trigger rooms. */
        public boolean playing() {
            return status == Status.ALIVE && online;
        }
    }

    /** Something the manager must do after a room step. */
    public sealed interface Action permits Countdown, Seal {
    }

    /** A room's fight starts in {@code stepsLeft} steps. */
    public record Countdown(int room, int stepsLeft) implements Action {
    }

    /** A room's fight starts now: seal its doors, pull everyone in, spawn its enemies. */
    public record Seal(int room) implements Action {
    }

    private final UUID id;
    private final String recipeId;
    private final DungeonLayout layout;
    private final Map<UUID, Member> members = new LinkedHashMap<>();
    private final Room[] rooms;
    private final int[] countdown;
    private final long startedMs;
    private State state = State.RUNNING;
    private int lives;
    private int checkpoint;
    private int downs;
    private int bleedOuts;
    private boolean bossDefeated;

    public DungeonRun(UUID id, String recipeId, DungeonLayout layout, int lives, long startedMs) {
        this.id = id;
        this.recipeId = recipeId;
        this.layout = layout;
        this.lives = lives;
        this.startedMs = startedMs;
        this.rooms = new Room[layout.rooms().size()];
        this.countdown = new int[rooms.length];
        for (int i = 0; i < rooms.length; i++) {
            rooms[i] = isFight(layout.rooms().get(i).node().type()) ? Room.IDLE : Room.CLEARED;
        }
    }

    /** Room types that hold a fight and keep their onward doors shut until cleared. */
    public static boolean isFight(RoomType type) {
        return type == RoomType.COMBAT || type == RoomType.ELITE || type == RoomType.BOSS;
    }

    public Member add(UUID player, String characterId) {
        Member m = new Member(player, characterId);
        members.put(player, m);
        return m;
    }

    public Member member(UUID player) {
        return members.get(player);
    }

    public Collection<Member> members() {
        return members.values();
    }

    /** Members still taking part (not left). */
    public List<Member> present() {
        List<Member> out = new ArrayList<>();
        for (Member m : members.values()) {
            if (m.status != Status.LEFT) {
                out.add(m);
            }
        }
        return out;
    }

    public int playing() {
        int n = 0;
        for (Member m : members.values()) {
            if (m.playing()) {
                n++;
            }
        }
        return n;
    }

    /**
     * True when the party is beaten: nobody is up and playing, and nobody downed can still come
     * back (a downed member with shared lives left wakes at the checkpoint when they bleed out).
     */
    public boolean wiped() {
        if (playing() > 0) {
            return false;
        }
        if (lives == 0) {
            return true;
        }
        for (Member m : members.values()) {
            if (m.status == Status.DOWNED && m.online) {
                return false;
            }
        }
        return true;
    }

    public boolean down(UUID player, long nowMs) {
        Member m = members.get(player);
        if (m == null || m.status != Status.ALIVE) {
            return false;
        }
        m.status = Status.DOWNED;
        m.downedAtMs = nowMs;
        m.reviveMs = 0;
        downs++;
        return true;
    }

    /** Adds revive progress; true when it reaches {@code neededMs} and the member is back up. */
    public boolean addRevive(UUID player, long stepMs, long neededMs) {
        Member m = members.get(player);
        if (m == null || m.status != Status.DOWNED) {
            return false;
        }
        m.reviveMs += stepMs;
        if (m.reviveMs < neededMs) {
            return false;
        }
        m.status = Status.ALIVE;
        m.reviveMs = 0;
        return true;
    }

    public void resetRevive(UUID player) {
        Member m = members.get(player);
        if (m != null) {
            m.reviveMs = 0;
        }
    }

    /**
     * A downed member's time ran out (or they died outright). Spends a shared life to bring them
     * back at the checkpoint; with none left they are out. Returns true when they come back.
     */
    public boolean bleedOut(UUID player) {
        Member m = members.get(player);
        if (m == null || m.status == Status.OUT || m.status == Status.LEFT) {
            return false;
        }
        bleedOuts++;
        if (lives > 0) {
            lives--;
            m.status = Status.ALIVE;
            return true;
        }
        m.status = Status.OUT;
        return false;
    }

    public void leave(UUID player) {
        Member m = members.get(player);
        if (m != null) {
            m.status = Status.LEFT;
        }
    }

    public void offline(UUID player, long nowMs) {
        Member m = members.get(player);
        if (m != null) {
            m.online = false;
            m.offlineSinceMs = nowMs;
        }
    }

    public void online(UUID player) {
        Member m = members.get(player);
        if (m != null) {
            m.online = true;
        }
    }

    /**
     * Advances every room one step from the members' current rooms. A fight room arms when a
     * playing member enters (a boss room only once every playing member is inside), counts down,
     * and then seals. A countdown with nobody inside any more goes back to idle.
     */
    public List<Action> stepRooms(int countdownSteps, int bossCountdownSteps) {
        List<Action> actions = new ArrayList<>();
        int playing = playing();
        for (int i = 0; i < rooms.length; i++) {
            int inside = 0;
            for (Member m : members.values()) {
                if (m.playing() && m.room == i) {
                    inside++;
                }
            }
            boolean boss = layout.rooms().get(i).node().type() == RoomType.BOSS;
            if (rooms[i] == Room.IDLE && inside > 0 && (!boss || inside == playing)) {
                rooms[i] = Room.COUNTDOWN;
                countdown[i] = boss ? bossCountdownSteps : (playing == 1 ? 0 : countdownSteps);
            } else if (rooms[i] == Room.COUNTDOWN && inside == 0) {
                rooms[i] = Room.IDLE;
                continue;
            } else if (rooms[i] != Room.COUNTDOWN) {
                continue;
            }
            if (countdown[i] <= 0) {
                rooms[i] = Room.ACTIVE;
                actions.add(new Seal(i));
            } else {
                actions.add(new Countdown(i, countdown[i]));
                countdown[i]--;
            }
        }
        return actions;
    }

    /** A fight room's enemies are all dead. */
    public void clearRoom(int room) {
        rooms[room] = Room.CLEARED;
        if (layout.rooms().get(room).node().type() == RoomType.BOSS) {
            bossDefeated = true;
        }
    }

    public Room room(int room) {
        return rooms[room];
    }

    public UUID id() {
        return id;
    }

    public String recipeId() {
        return recipeId;
    }

    public DungeonLayout layout() {
        return layout;
    }

    public State state() {
        return state;
    }

    public void state(State state) {
        this.state = state;
    }

    public int lives() {
        return lives;
    }

    public int checkpoint() {
        return checkpoint;
    }

    public void checkpoint(int room) {
        checkpoint = room;
    }

    public int downs() {
        return downs;
    }

    public int bleedOuts() {
        return bleedOuts;
    }

    public boolean bossDefeated() {
        return bossDefeated;
    }

    public long startedMs() {
        return startedMs;
    }

    public int roomsCleared() {
        int n = 0;
        for (int i = 0; i < rooms.length; i++) {
            if (rooms[i] == Room.CLEARED && isFight(layout.rooms().get(i).node().type())) {
                n++;
            }
        }
        return n;
    }
}
