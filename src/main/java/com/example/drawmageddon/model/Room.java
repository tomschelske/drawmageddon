package com.example.drawmageddon.model;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory room state. Rooms are ephemeral: they live only as long as the
 * server process and are purged after sitting empty past the expiry window.
 */
public class Room {

    private final String roomCode;
    private final Instant createdAt;

    // principalId → displayName (source of truth for who is in the room)
    private final ConcurrentHashMap<String, String> activeNames = new ConcurrentHashMap<>();

    // name.toLowerCase() → principalId (used for atomic name-uniqueness claim via putIfAbsent)
    private final ConcurrentHashMap<String, String> claimedNames = new ConcurrentHashMap<>();

    // ordered list of connected principal IDs (safe for concurrent iteration during broadcast)
    private final CopyOnWriteArrayList<String> sessions = new CopyOnWriteArrayList<>();

    private volatile GamePhase phase = GamePhase.LOBBY;

    // principalId of the current host (first joiner; reassigned on disconnect)
    private volatile String hostId;

    private volatile Instant lastEmptiedAt;

    public Room(String roomCode) {
        this.roomCode = roomCode;
        this.createdAt = Instant.now();
    }

    public String getRoomCode() { return roomCode; }
    public Instant getCreatedAt() { return createdAt; }
    public ConcurrentHashMap<String, String> getActiveNames() { return activeNames; }
    public ConcurrentHashMap<String, String> getClaimedNames() { return claimedNames; }
    public CopyOnWriteArrayList<String> getSessions() { return sessions; }
    public GamePhase getPhase() { return phase; }
    public void setPhase(GamePhase phase) { this.phase = phase; }
    public String getHostId() { return hostId; }
    public void setHostId(String hostId) { this.hostId = hostId; }
    public Instant getLastEmptiedAt() { return lastEmptiedAt; }
    public void setLastEmptiedAt(Instant t) { this.lastEmptiedAt = t; }

    public int presenceCount() { return activeNames.size(); }
}
