package com.example.drawmageddon.service;

import com.example.drawmageddon.model.Bracket;
import com.example.drawmageddon.model.BracketMatch;
import com.example.drawmageddon.model.Drawing;
import com.example.drawmageddon.model.GameEvent;
import com.example.drawmageddon.model.GamePhase;
import com.example.drawmageddon.model.Prompt;
import com.example.drawmageddon.model.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

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
    public static final String DRAWING_DATA_PREFIX = "data:image/png;base64,";
    public static final int MAX_DRAWING_DATA_LENGTH = 500_000;

    /** Clients auto-submit at the deadline; the server closes the phase this much later. */
    static final Duration DRAWING_GRACE = Duration.ofSeconds(3);

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    private final RoomManager roomManager;
    private final SessionRegistry sessionRegistry;
    private final GameEvents events;
    private final TaskScheduler gameScheduler;
    private final Duration drawingDuration;
    private final Duration revealDuration;
    private final Random random = new Random();

    // roomCode → pending scheduled task (drawing force-close or matchup advance)
    private final ConcurrentHashMap<String, ScheduledFuture<?>> phaseTimers = new ConcurrentHashMap<>();

    public GameService(RoomManager roomManager,
                       SessionRegistry sessionRegistry,
                       GameEvents events,
                       @Qualifier("gameScheduler") TaskScheduler gameScheduler,
                       @Value("${game.drawing-seconds:90}") int drawingSeconds,
                       @Value("${game.reveal-seconds:6}") int revealSeconds) {
        this.roomManager = roomManager;
        this.sessionRegistry = sessionRegistry;
        this.events = events;
        this.gameScheduler = gameScheduler;
        this.drawingDuration = Duration.ofSeconds(drawingSeconds);
        this.revealDuration = Duration.ofSeconds(revealSeconds);
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

    // --- Drawing ---

    public void submitDrawing(String roomCode, String principal, String imageData) {
        Room room = roomManager.findRoom(roomCode).orElse(null);
        if (room == null) {
            events.sendPersonal(principal, GameEvent.error("ROOM_NOT_FOUND"));
            return;
        }

        synchronized (room) {
            if (room.getPhase() != GamePhase.DRAWING) {
                events.sendPersonal(principal, GameEvent.error("WRONG_PHASE"));
                return;
            }
            if (!room.getActiveNames().containsKey(principal)) {
                events.sendPersonal(principal, GameEvent.error("NOT_IN_ROOM"));
                return;
            }
            if (imageData == null || !imageData.startsWith(DRAWING_DATA_PREFIX)
                    || imageData.length() > MAX_DRAWING_DATA_LENGTH) {
                events.sendPersonal(principal, GameEvent.error("INVALID_DRAWING"));
                return;
            }
            Drawing drawing = new Drawing(UUID.randomUUID().toString(), principal,
                    room.getActiveNames().get(principal), imageData);
            if (room.getDrawings().putIfAbsent(principal, drawing) != null) {
                events.sendPersonal(principal, GameEvent.error("ALREADY_SUBMITTED"));
                return;
            }
            maybeCloseDrawing(room);
        }
        events.broadcastState(room);
    }

    /** Deadline fallback: whoever hasn't submitted is simply left out of the bracket. */
    void forceCloseDrawing(Room room) {
        synchronized (room) {
            if (room.getPhase() != GamePhase.DRAWING) return;
            log.debug("Room {}: drawing deadline hit with {}/{} drawings in",
                    room.getRoomCode(), room.getDrawings().size(), room.presenceCount());
            closeDrawingLocked(room);
        }
        events.broadcastState(room);
    }

    // --- Bracket voting ---

    public void voteMatch(String roomCode, String principal, String drawingId) {
        Room room = roomManager.findRoom(roomCode).orElse(null);
        if (room == null) {
            events.sendPersonal(principal, GameEvent.error("ROOM_NOT_FOUND"));
            return;
        }

        synchronized (room) {
            if (room.getPhase() != GamePhase.BRACKET_VOTING) {
                events.sendPersonal(principal, GameEvent.error("WRONG_PHASE"));
                return;
            }
            if (!room.getActiveNames().containsKey(principal)) {
                events.sendPersonal(principal, GameEvent.error("NOT_IN_ROOM"));
                return;
            }
            Bracket bracket = room.getBracket();
            BracketMatch match = bracket == null ? null : bracket.currentMatch();
            if (match == null || match.isRevealed()) {
                events.sendPersonal(principal, GameEvent.error("MATCH_CLOSED"));
                return;
            }
            if (drawingId == null || !match.contains(drawingId)) {
                events.sendPersonal(principal, GameEvent.error("INVALID_VOTE"));
                return;
            }
            Drawing chosen = match.getA().id().equals(drawingId) ? match.getA() : match.getB();
            if (principal.equals(chosen.ownerId())) {
                events.sendPersonal(principal, GameEvent.error("SELF_VOTE"));
                return;
            }
            if (match.getVotes().putIfAbsent(principal, drawingId) != null) {
                events.sendPersonal(principal, GameEvent.error("ALREADY_VOTED"));
                return;
            }
            maybeCloseMatch(room);
        }
        // Pre-reveal broadcasts carry only the votes-in count, never the tally
        events.broadcastState(room);
    }

    public void playAgain(String roomCode, String principal) {
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
            if (room.getPhase() != GamePhase.RESULTS) {
                events.sendPersonal(principal, GameEvent.error("WRONG_PHASE"));
                return;
            }
            cancelTimer(room);
            room.resetForNewGame();
        }
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
            maybeCloseDrawing(room);
            maybeCloseMatch(room);
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
        openDrawing(room);
        log.debug("Room {}: prompt vote closed, winner '{}' ({} vote(s), {} tied)",
                room.getRoomCode(), winner.text(), max, tied.size());
    }

    private void openDrawing(Room room) {
        room.setPhase(GamePhase.DRAWING);
        Instant deadline = Instant.now().plus(drawingDuration);
        room.setPhaseDeadline(deadline);
        ScheduledFuture<?> timer = gameScheduler.schedule(
                () -> forceCloseDrawing(room), deadline.plus(DRAWING_GRACE));
        ScheduledFuture<?> previous = phaseTimers.put(room.getRoomCode(), timer);
        if (previous != null) previous.cancel(false);
    }

    private void maybeCloseDrawing(Room room) {
        if (room.getPhase() != GamePhase.DRAWING) return;
        if (room.presenceCount() == 0) return;
        boolean allSubmitted = room.getActiveNames().keySet().stream()
                .allMatch(room.getDrawings()::containsKey);
        if (!allSubmitted) return;
        closeDrawingLocked(room);
    }

    private void closeDrawingLocked(Room room) {
        room.setPhaseDeadline(null);
        cancelTimer(room);

        Bracket bracket = new Bracket(new ArrayList<>(room.getDrawings().values()), random);
        room.setBracket(bracket);
        // Fewer than 2 drawings: nothing to vote on, straight to results
        room.setPhase(bracket.isFinished() ? GamePhase.RESULTS : GamePhase.BRACKET_VOTING);
        log.debug("Room {}: drawing phase closed with {} drawing(s), bracket {}",
                room.getRoomCode(), room.getDrawings().size(),
                bracket.isFinished() ? "skipped" : "seeded");
    }

    // --- Bracket transitions (call only while synchronized on the room) ---

    private void maybeCloseMatch(Room room) {
        if (room.getPhase() != GamePhase.BRACKET_VOTING) return;
        if (room.presenceCount() == 0) return;
        Bracket bracket = room.getBracket();
        BracketMatch match = bracket == null ? null : bracket.currentMatch();
        if (match == null || match.isRevealed()) return;
        boolean allVoted = room.getActiveNames().keySet().stream()
                .allMatch(match.getVotes()::containsKey);
        if (!allVoted) return;

        int votesA = match.votesFor(match.getA());
        int votesB = match.votesFor(match.getB());
        Drawing winner;
        if (votesA != votesB) {
            winner = votesA > votesB ? match.getA() : match.getB();
        } else {
            winner = random.nextBoolean() ? match.getA() : match.getB();
            match.setTieBroken(true);
        }
        match.setWinner(winner);
        match.setRevealed(true);
        log.debug("Room {}: match revealed, {} beats {} ({}–{}{})", room.getRoomCode(),
                winner.ownerName(),
                (winner == match.getA() ? match.getB() : match.getA()).ownerName(),
                Math.max(votesA, votesB), Math.min(votesA, votesB),
                match.isTieBroken() ? ", tie broken randomly" : "");

        // Let everyone soak in the reveal, then move the bracket along
        ScheduledFuture<?> timer = gameScheduler.schedule(
                () -> advanceBracket(room), Instant.now().plus(revealDuration));
        ScheduledFuture<?> previous = phaseTimers.put(room.getRoomCode(), timer);
        if (previous != null) previous.cancel(false);
    }

    void advanceBracket(Room room) {
        synchronized (room) {
            if (room.getPhase() != GamePhase.BRACKET_VOTING) return;
            Bracket bracket = room.getBracket();
            BracketMatch match = bracket == null ? null : bracket.currentMatch();
            if (match == null || !match.isRevealed()) return;

            bracket.advance();
            if (bracket.isFinished()) {
                room.setPhase(GamePhase.RESULTS);
                log.debug("Room {}: bracket complete, champion {}", room.getRoomCode(),
                        bracket.getChampion() == null ? "none" : bracket.getChampion().ownerName());
            }
        }
        events.broadcastState(room);
    }

    private void cancelTimer(Room room) {
        ScheduledFuture<?> timer = phaseTimers.remove(room.getRoomCode());
        if (timer != null) timer.cancel(false);
    }
}
