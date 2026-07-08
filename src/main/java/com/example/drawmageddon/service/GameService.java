package com.example.drawmageddon.service;

import com.example.drawmageddon.model.GameEvent;
import com.example.drawmageddon.model.GamePhase;
import com.example.drawmageddon.model.Prompt;
import com.example.drawmageddon.model.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Server-authoritative game logic. Clients only ever send intents (join,
 * start, submit, vote); all tallying, tie-breaking, and phase transitions
 * happen here. Phase-mutating methods synchronize on the room so completion
 * checks can't race.
 */
@Service
public class GameService {

    public static final int MIN_PLAYERS_TO_START = 3;
    public static final int MAX_PROMPT_LENGTH = 140;

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    private final RoomManager roomManager;
    private final SessionRegistry sessionRegistry;
    private final GameEvents events;
    private final Random random = new Random();

    public GameService(RoomManager roomManager,
                       SessionRegistry sessionRegistry,
                       GameEvents events) {
        this.roomManager = roomManager;
        this.sessionRegistry = sessionRegistry;
        this.events = events;
    }

    // --- Lobby ---

    public void join(String roomCode, String principal, String rawName) {
        Room room = roomManager.findRoom(roomCode).orElse(null);
        if (room == null) {
            events.sendPersonal(principal, GameEvent.error("ROOM_NOT_FOUND"));
            return;
        }

        if (room.getPhase() != GamePhase.LOBBY) {
            events.sendPersonal(principal, GameEvent.error("GAME_IN_PROGRESS"));
            return;
        }

        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > 32) {
            events.sendPersonal(principal, GameEvent.error("INVALID_NAME"));
            return;
        }

        String existingHolder = room.getClaimedNames().putIfAbsent(name.toLowerCase(), principal);
        if (existingHolder != null) {
            events.sendPersonal(principal, GameEvent.error("NAME_TAKEN"));
            return;
        }

        room.getActiveNames().put(principal, name);
        room.getSessions().add(principal);
        room.setLastEmptiedAt(null);
        // First joiner becomes host; reassigned in RoomEventListener if they leave
        if (room.getHostId() == null) {
            room.setHostId(principal);
        }
        sessionRegistry.register(principal, room.getRoomCode(), name);

        events.sendPersonal(principal, GameEvent.joinOk(events.view(room)));
        events.broadcastState(room);
    }

    public void start(String roomCode, String principal) {
        Room room = roomManager.findRoom(roomCode).orElse(null);
        if (room == null) {
            events.sendPersonal(principal, GameEvent.error("ROOM_NOT_FOUND"));
            return;
        }

        synchronized (room) {
            if (!principal.equals(room.getHostId())) {
                events.sendPersonal(principal, GameEvent.error("NOT_HOST"));
                return;
            }
            if (room.getPhase() != GamePhase.LOBBY) {
                events.sendPersonal(principal, GameEvent.error("ALREADY_STARTED"));
                return;
            }
            if (room.presenceCount() < MIN_PLAYERS_TO_START) {
                events.sendPersonal(principal, GameEvent.error("NOT_ENOUGH_PLAYERS"));
                return;
            }
            room.setPhase(GamePhase.PROMPT_SUBMISSION);
        }
        events.broadcastState(room);
    }

    // --- Prompt submission ---

    public void submitPrompt(String roomCode, String principal, String rawText) {
        Room room = roomManager.findRoom(roomCode).orElse(null);
        if (room == null) {
            events.sendPersonal(principal, GameEvent.error("ROOM_NOT_FOUND"));
            return;
        }

        synchronized (room) {
            if (room.getPhase() != GamePhase.PROMPT_SUBMISSION) {
                events.sendPersonal(principal, GameEvent.error("WRONG_PHASE"));
                return;
            }
            if (!room.getActiveNames().containsKey(principal)) {
                events.sendPersonal(principal, GameEvent.error("NOT_IN_ROOM"));
                return;
            }
            String text = rawText == null ? "" : rawText.trim();
            if (text.isEmpty() || text.length() > MAX_PROMPT_LENGTH) {
                events.sendPersonal(principal, GameEvent.error("INVALID_PROMPT"));
                return;
            }
            Prompt prompt = new Prompt(UUID.randomUUID().toString(), principal, text);
            if (room.getPrompts().putIfAbsent(principal, prompt) != null) {
                events.sendPersonal(principal, GameEvent.error("ALREADY_SUBMITTED"));
                return;
            }
            maybeOpenVoting(room);
        }
        events.broadcastState(room);
    }

    // --- Prompt voting ---

    public void votePrompt(String roomCode, String principal, String promptId) {
        Room room = roomManager.findRoom(roomCode).orElse(null);
        if (room == null) {
            events.sendPersonal(principal, GameEvent.error("ROOM_NOT_FOUND"));
            return;
        }

        synchronized (room) {
            if (room.getPhase() != GamePhase.PROMPT_VOTING) {
                events.sendPersonal(principal, GameEvent.error("WRONG_PHASE"));
                return;
            }
            if (!room.getActiveNames().containsKey(principal)) {
                events.sendPersonal(principal, GameEvent.error("NOT_IN_ROOM"));
                return;
            }
            List<Prompt> ballot = room.getPromptBallot();
            boolean valid = promptId != null && ballot != null
                    && ballot.stream().anyMatch(p -> p.id().equals(promptId));
            if (!valid) {
                events.sendPersonal(principal, GameEvent.error("INVALID_VOTE"));
                return;
            }
            // Voting for your own prompt is allowed here (unlike bracket voting)
            if (room.getPromptVotes().putIfAbsent(principal, promptId) != null) {
                events.sendPersonal(principal, GameEvent.error("ALREADY_VOTED"));
                return;
            }
            maybeCloseVoting(room);
        }
        // Broadcasting outside the lock is what makes the tally live
        events.broadcastState(room);
    }

    // --- Disconnect handling ---

    /**
     * Called after a departed player has been removed from the room. Their
     * submitted prompt and cast vote (if any) stay in play; we only re-check
     * whether the current phase is now complete without them.
     */
    public void playerLeft(Room room) {
        synchronized (room) {
            if (room.presenceCount() == 0) return; // room will expire via CleanupScheduler
            maybeOpenVoting(room);
            maybeCloseVoting(room);
        }
        events.broadcastState(room);
    }

    // --- Phase transitions (call only while synchronized on the room) ---

    private void maybeOpenVoting(Room room) {
        if (room.getPhase() != GamePhase.PROMPT_SUBMISSION) return;
        if (room.presenceCount() == 0) return;
        boolean allSubmitted = room.getActiveNames().keySet().stream()
                .allMatch(room.getPrompts()::containsKey);
        if (!allSubmitted) return;

        // Fix a shuffled ballot so display order doesn't leak who submitted what
        List<Prompt> ballot = new ArrayList<>(room.getPrompts().values());
        Collections.shuffle(ballot, random);
        room.setPromptBallot(List.copyOf(ballot));
        room.setPhase(GamePhase.PROMPT_VOTING);
        log.debug("Room {}: voting opened with {} prompts", room.getRoomCode(), ballot.size());
    }

    private void maybeCloseVoting(Room room) {
        if (room.getPhase() != GamePhase.PROMPT_VOTING) return;
        if (room.presenceCount() == 0) return;
        boolean allVoted = room.getActiveNames().keySet().stream()
                .allMatch(room.getPromptVotes()::containsKey);
        if (!allVoted) return;

        Map<String, Integer> tally = new HashMap<>();
        for (String promptId : room.getPromptVotes().values()) {
            tally.merge(promptId, 1, Integer::sum);
        }
        int max = tally.values().stream().max(Integer::compare).orElse(0);
        List<Prompt> tied = room.getPromptBallot().stream()
                .filter(p -> tally.getOrDefault(p.id(), 0) == max)
                .toList();
        // Random tie-break among the leaders, per the spec
        Prompt winner = tied.get(random.nextInt(tied.size()));

        room.setWinningPrompt(winner);
        room.setPhase(GamePhase.DRAWING);
        log.debug("Room {}: prompt vote closed, winner '{}' ({} vote(s), {} tied)",
                room.getRoomCode(), winner.text(), max, tied.size());
    }
}
