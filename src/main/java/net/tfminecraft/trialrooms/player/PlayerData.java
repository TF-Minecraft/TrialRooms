package net.tfminecraft.trialrooms.player;

import java.util.UUID;

public final class PlayerData {
    private boolean inDungeon = false;
    private UUID lastEntranceId = null;   // optional (can be null)
    private long enteredAtMs = 0L;

    public boolean isInDungeon() { return inDungeon; }
    public UUID getLastEntranceId() { return lastEntranceId; }
    public long getEnteredAtMs() { return enteredAtMs; }

    public void enter(UUID entranceId) {
        this.inDungeon = true;
        this.lastEntranceId = entranceId;
        this.enteredAtMs = System.currentTimeMillis();
    }

    public void ensureInside() {
        if (!inDungeon) {
            this.inDungeon = true;
            this.enteredAtMs = System.currentTimeMillis();
        }
    }

    public void exit() {
        this.inDungeon = false;
        // keep lastEntranceId/enteredAt if you want; or clear:
        // this.lastEntranceId = null;
        // this.enteredAtMs = 0L;
    }
}
