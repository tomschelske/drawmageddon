package com.example.drawmageddon.model;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One head-to-head matchup. Votes are held here and deliberately never
 * broadcast until the match is revealed — hiding the tally is what prevents
 * bandwagon voting (the deliberate contrast with live-tallied prompt voting).
 *
 * Mutable state is guarded by the owning room's lock.
 */
public class BracketMatch {

    private final String id = UUID.randomUUID().toString();
    private final Drawing a;
    private final Drawing b;

    // voterId → drawingId; hidden until the match closes
    private final Map<String, String> votes = new HashMap<>();

    private volatile boolean revealed;
    private volatile Drawing winner;
    private volatile boolean tieBroken;

    public BracketMatch(Drawing a, Drawing b) {
        this.a = a;
        this.b = b;
    }

    public String getId() { return id; }
    public Drawing getA() { return a; }
    public Drawing getB() { return b; }
    public Map<String, String> getVotes() { return votes; }
    public boolean isRevealed() { return revealed; }
    public void setRevealed(boolean revealed) { this.revealed = revealed; }
    public Drawing getWinner() { return winner; }
    public void setWinner(Drawing winner) { this.winner = winner; }
    public boolean isTieBroken() { return tieBroken; }
    public void setTieBroken(boolean tieBroken) { this.tieBroken = tieBroken; }

    public int votesFor(Drawing drawing) {
        return (int) votes.values().stream().filter(id -> id.equals(drawing.id())).count();
    }

    public boolean contains(String drawingId) {
        return a.id().equals(drawingId) || b.id().equals(drawingId);
    }
}
