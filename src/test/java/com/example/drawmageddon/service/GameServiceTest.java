package com.example.drawmageddon.service;

import com.example.drawmageddon.model.GameEvent;
import com.example.drawmageddon.model.GamePhase;
import com.example.drawmageddon.model.Prompt;
import com.example.drawmageddon.model.Room;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GameServiceTest {

    /** Records outbound messages instead of sending them (Mockito can't instrument on this JDK). */
    static class RecordingMessaging extends SimpMessagingTemplate {
        record Personal(String user, Object payload) {}

        final List<Personal> personals = new ArrayList<>();

        RecordingMessaging() {
            super((message, timeout) -> true);
        }

        @Override
        public void convertAndSend(String destination, Object payload) {
            // room broadcasts are asserted via room state, not captured here
        }

        @Override
        public void convertAndSendToUser(String user, String destination, Object payload) {
            personals.add(new Personal(user, payload));
        }
    }

    private static final String TINY_PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    private RoomManager roomManager;
    private RecordingMessaging messaging;
    private GameService service;
    private ThreadPoolTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        roomManager = new RoomManager();
        messaging = new RecordingMessaging();
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        // 90s timers: far enough out that only explicit force-close/advance fires in tests
        service = new GameService(roomManager, new SessionRegistry(), new GameEvents(messaging),
                scheduler, 90, 90);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    private Room roomWithPlayers(String... names) {
        Room room = roomManager.createRoom();
        for (int i = 0; i < names.length; i++) {
            service.join(room.getRoomCode(), "p" + (i + 1), names[i]);
        }
        return room;
    }

    private Room startedGame(String... names) {
        Room room = roomWithPlayers(names);
        service.start(room.getRoomCode(), "p1"); // p1 joined first → host
        return room;
    }

    /** Map each player's principal to their own prompt id (after voting opened). */
    private Map<String, String> ownPromptIds(Room room) {
        return room.getPrompts().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().id()));
    }

    private void expectPersonalError(String principal, String code) {
        boolean found = messaging.personals.stream()
                .anyMatch(p -> p.user().equals(principal)
                        && p.payload() instanceof GameEvent e
                        && e.type() == GameEvent.Type.ERROR
                        && code.equals(e.code()));
        assertTrue(found, "expected ERROR " + code + " sent to " + principal);
    }

    // --- Lobby ---

    @Test
    void firstJoinerIsHostAndStartNeedsThreePlayers() {
        Room room = roomWithPlayers("Alice", "Bob");
        assertEquals("p1", room.getHostId());

        service.start(room.getRoomCode(), "p2");
        expectPersonalError("p2", "NOT_HOST");

        service.start(room.getRoomCode(), "p1");
        expectPersonalError("p1", "NOT_ENOUGH_PLAYERS");
        assertEquals(GamePhase.LOBBY, room.getPhase());

        service.join(room.getRoomCode(), "p3", "Carol");
        service.start(room.getRoomCode(), "p1");
        assertEquals(GamePhase.PROMPT_SUBMISSION, room.getPhase());
    }

    @Test
    void duplicateNameRejectedCaseInsensitively() {
        Room room = roomWithPlayers("Alice");
        service.join(room.getRoomCode(), "p2", "alice");
        expectPersonalError("p2", "NAME_TAKEN");
        assertEquals(1, room.presenceCount());
    }

    // --- Prompt submission ---

    @Test
    void votingOpensOnceEveryPlayerHasSubmitted() {
        Room room = startedGame("Alice", "Bob", "Carol");

        service.submitPrompt(room.getRoomCode(), "p1", "a haunted vending machine");
        service.submitPrompt(room.getRoomCode(), "p2", "octopus barista");
        assertEquals(GamePhase.PROMPT_SUBMISSION, room.getPhase());

        service.submitPrompt(room.getRoomCode(), "p3", "the moon but angry");
        assertEquals(GamePhase.PROMPT_VOTING, room.getPhase());
        assertEquals(3, room.getPromptBallot().size());
    }

    @Test
    void secondSubmissionFromSamePlayerIsRejected() {
        Room room = startedGame("Alice", "Bob", "Carol");
        service.submitPrompt(room.getRoomCode(), "p1", "first idea");
        service.submitPrompt(room.getRoomCode(), "p1", "second idea");

        expectPersonalError("p1", "ALREADY_SUBMITTED");
        assertEquals("first idea", room.getPrompts().get("p1").text());
    }

    @Test
    void blankOrOverlongPromptIsRejected() {
        Room room = startedGame("Alice", "Bob", "Carol");
        service.submitPrompt(room.getRoomCode(), "p1", "   ");
        expectPersonalError("p1", "INVALID_PROMPT");
        service.submitPrompt(room.getRoomCode(), "p1", "x".repeat(GameService.MAX_PROMPT_LENGTH + 1));
        assertTrue(room.getPrompts().isEmpty());
    }

    // --- Prompt voting ---

    @Test
    void clearMajorityWinsAndPhaseMovesToDrawing() {
        Room room = startedGame("Alice", "Bob", "Carol");
        service.submitPrompt(room.getRoomCode(), "p1", "prompt A");
        service.submitPrompt(room.getRoomCode(), "p2", "prompt B");
        service.submitPrompt(room.getRoomCode(), "p3", "prompt C");

        String promptA = ownPromptIds(room).get("p1");
        service.votePrompt(room.getRoomCode(), "p1", promptA); // self-vote is allowed
        service.votePrompt(room.getRoomCode(), "p2", promptA);
        assertEquals(GamePhase.PROMPT_VOTING, room.getPhase()); // not everyone voted yet

        service.votePrompt(room.getRoomCode(), "p3", ownPromptIds(room).get("p2"));
        assertEquals(GamePhase.DRAWING, room.getPhase());
        assertEquals("prompt A", room.getWinningPrompt().text());
    }

    @Test
    void tieIsBrokenRandomlyAmongTiedPromptsOnly() {
        Room room = startedGame("Alice", "Bob", "Carol", "Dave");
        service.submitPrompt(room.getRoomCode(), "p1", "prompt A");
        service.submitPrompt(room.getRoomCode(), "p2", "prompt B");
        service.submitPrompt(room.getRoomCode(), "p3", "prompt C");
        service.submitPrompt(room.getRoomCode(), "p4", "prompt D");

        Map<String, String> ids = ownPromptIds(room);
        // A and B tie with 2 votes each; C and D get none
        service.votePrompt(room.getRoomCode(), "p1", ids.get("p1"));
        service.votePrompt(room.getRoomCode(), "p2", ids.get("p1"));
        service.votePrompt(room.getRoomCode(), "p3", ids.get("p2"));
        service.votePrompt(room.getRoomCode(), "p4", ids.get("p2"));

        assertEquals(GamePhase.DRAWING, room.getPhase());
        assertTrue(List.of("prompt A", "prompt B").contains(room.getWinningPrompt().text()),
                "winner must be one of the tied prompts, got: " + room.getWinningPrompt().text());
    }

    @Test
    void votesAreFinalAndMustReferenceTheBallot() {
        Room room = startedGame("Alice", "Bob", "Carol");
        service.submitPrompt(room.getRoomCode(), "p1", "prompt A");
        service.submitPrompt(room.getRoomCode(), "p2", "prompt B");
        service.submitPrompt(room.getRoomCode(), "p3", "prompt C");

        service.votePrompt(room.getRoomCode(), "p1", "not-a-real-id");
        expectPersonalError("p1", "INVALID_VOTE");

        Map<String, String> ids = ownPromptIds(room);
        service.votePrompt(room.getRoomCode(), "p1", ids.get("p2"));
        service.votePrompt(room.getRoomCode(), "p1", ids.get("p3"));
        expectPersonalError("p1", "ALREADY_VOTED");
        assertEquals(ids.get("p2"), room.getPromptVotes().get("p1"));
    }

    @Test
    void votingBeforeVotingPhaseIsRejected() {
        Room room = startedGame("Alice", "Bob", "Carol");
        service.votePrompt(room.getRoomCode(), "p1", "anything");
        expectPersonalError("p1", "WRONG_PHASE");
    }

    // --- Drawing phase ---

    /** Drive a 3-player game to the DRAWING phase; p1's prompt wins. */
    private Room gameInDrawingPhase() {
        Room room = startedGame("Alice", "Bob", "Carol");
        service.submitPrompt(room.getRoomCode(), "p1", "prompt A");
        service.submitPrompt(room.getRoomCode(), "p2", "prompt B");
        service.submitPrompt(room.getRoomCode(), "p3", "prompt C");
        String promptA = ownPromptIds(room).get("p1");
        service.votePrompt(room.getRoomCode(), "p1", promptA);
        service.votePrompt(room.getRoomCode(), "p2", promptA);
        service.votePrompt(room.getRoomCode(), "p3", promptA);
        assertEquals(GamePhase.DRAWING, room.getPhase());
        return room;
    }

    @Test
    void drawingPhaseHasADeadlineAndCollectsAllDrawings() {
        Room room = gameInDrawingPhase();
        assertNotNull(room.getPhaseDeadline(), "drawing phase must be timer-bound");

        service.submitDrawing(room.getRoomCode(), "p1", TINY_PNG);
        service.submitDrawing(room.getRoomCode(), "p2", TINY_PNG);
        assertEquals(GamePhase.DRAWING, room.getPhase());

        service.submitDrawing(room.getRoomCode(), "p3", TINY_PNG);
        assertEquals(GamePhase.BRACKET_VOTING, room.getPhase());
        assertEquals(3, room.getDrawings().size());
        assertNull(room.getPhaseDeadline(), "deadline cleared once the phase closes");
    }

    @Test
    void invalidOrDuplicateDrawingsAreRejected() {
        Room room = gameInDrawingPhase();

        service.submitDrawing(room.getRoomCode(), "p1", "data:image/jpeg;base64,xxxx");
        expectPersonalError("p1", "INVALID_DRAWING");

        service.submitDrawing(room.getRoomCode(), "p1", TINY_PNG);
        service.submitDrawing(room.getRoomCode(), "p1", TINY_PNG);
        expectPersonalError("p1", "ALREADY_SUBMITTED");
        assertEquals(1, room.getDrawings().size());
    }

    @Test
    void drawingBeforeDrawingPhaseIsRejected() {
        Room room = startedGame("Alice", "Bob", "Carol");
        service.submitDrawing(room.getRoomCode(), "p1", TINY_PNG);
        expectPersonalError("p1", "WRONG_PHASE");
        assertTrue(room.getDrawings().isEmpty());
    }

    @Test
    void forceCloseAdvancesWithoutTheStragglers() {
        Room room = gameInDrawingPhase();
        service.submitDrawing(room.getRoomCode(), "p1", TINY_PNG);

        service.forceCloseDrawing(room);
        // A single surviving drawing wins unopposed — no bracket, straight to results
        assertEquals(GamePhase.RESULTS, room.getPhase());
        assertEquals(1, room.getDrawings().size());
        assertEquals("p1", room.getBracket().getChampion().ownerId());

        // Late submission after the phase closed is rejected
        service.submitDrawing(room.getRoomCode(), "p2", TINY_PNG);
        expectPersonalError("p2", "WRONG_PHASE");
    }

    @Test
    void drawingPhaseClosesWhenTheLastHoldoutDisconnects() {
        Room room = gameInDrawingPhase();
        service.submitDrawing(room.getRoomCode(), "p1", TINY_PNG);
        service.submitDrawing(room.getRoomCode(), "p2", TINY_PNG);

        room.getSessions().remove("p3");
        room.getActiveNames().remove("p3");
        room.getClaimedNames().remove("carol");
        service.playerLeft(room);

        assertEquals(GamePhase.BRACKET_VOTING, room.getPhase());
        assertEquals(2, room.getDrawings().size());
    }

    // --- Bracket phase ---

    /** Drive an n-player game all the way into the bracket (everyone draws). */
    private Room gameInBracketPhase(String... names) {
        Room room = startedGame(names);
        for (int i = 1; i <= names.length; i++) {
            service.submitPrompt(room.getRoomCode(), "p" + i, "prompt " + i);
        }
        String target = ownPromptIds(room).get("p1");
        for (int i = 1; i <= names.length; i++) {
            service.votePrompt(room.getRoomCode(), "p" + i, target);
        }
        assertEquals(GamePhase.DRAWING, room.getPhase());
        for (int i = 1; i <= names.length; i++) {
            service.submitDrawing(room.getRoomCode(), "p" + i, TINY_PNG);
        }
        return room;
    }

    private com.example.drawmageddon.model.BracketMatch currentMatch(Room room) {
        return room.getBracket().currentMatch();
    }

    /** Everyone who legally can votes for the given drawing; artists of it vote the opponent. */
    private void allVoteFor(Room room, com.example.drawmageddon.model.Drawing choice) {
        var match = currentMatch(room);
        var other = match.getA().id().equals(choice.id()) ? match.getB() : match.getA();
        for (String principal : room.getActiveNames().keySet()) {
            String vote = principal.equals(choice.ownerId()) ? other.id() : choice.id();
            service.voteMatch(room.getRoomCode(), principal, vote);
        }
    }

    @Test
    void evenFieldSeedsWithoutAByeOddFieldGetsOne() {
        Room even = gameInBracketPhase("A", "B", "C", "D");
        assertEquals(GamePhase.BRACKET_VOTING, even.getPhase());
        assertEquals(2, even.getBracket().getRounds().get(0).matches().size());
        assertNull(even.getBracket().getRounds().get(0).bye());

        Room odd = gameInBracketPhase("Alice", "Bob", "Carol");
        assertEquals(1, odd.getBracket().getRounds().get(0).matches().size());
        assertNotNull(odd.getBracket().getRounds().get(0).bye());
    }

    @Test
    void selfVoteIsRejected() {
        Room room = gameInBracketPhase("Alice", "Bob", "Carol");
        var match = currentMatch(room);
        String artistA = match.getA().ownerId();

        service.voteMatch(room.getRoomCode(), artistA, match.getA().id());
        expectPersonalError(artistA, "SELF_VOTE");
        assertTrue(match.getVotes().isEmpty());
    }

    @Test
    void talliesStayHiddenUntilTheMatchCloses() {
        Room room = gameInBracketPhase("Alice", "Bob", "Carol");
        var match = currentMatch(room);
        // The player whose drawing got the bye is a neutral voter
        String neutral = room.getBracket().getRounds().get(0).bye().ownerId();

        service.voteMatch(room.getRoomCode(), neutral, match.getA().id());
        var midVoteView = com.example.drawmageddon.model.RoomStateView.of(room, 3);
        assertFalse(midVoteView.matchup().revealed());
        assertNull(midVoteView.matchup().votesA(), "tally must be hidden while the match is open");
        assertNull(midVoteView.matchup().winnerId());
        assertEquals(1, midVoteView.matchup().votesIn());

        allVoteFor(room, match.getA());
        var revealedView = com.example.drawmageddon.model.RoomStateView.of(room, 3);
        assertTrue(revealedView.matchup().revealed());
        assertNotNull(revealedView.matchup().votesA());
        assertEquals(match.getA().id(), revealedView.matchup().winnerId());
    }

    @Test
    void majorityWinsTheMatchAndTieIsBrokenRandomly() {
        Room majority = gameInBracketPhase("A", "B", "C", "D");
        var match = currentMatch(majority);
        allVoteFor(majority, match.getA());
        assertEquals(match.getA().id(), match.getWinner().id());
        assertFalse(match.isTieBroken());

        Room tied = gameInBracketPhase("E", "F", "G", "H");
        var tiedMatch = currentMatch(tied);
        // Both artists must vote for the opponent; split the two neutrals 1–1 → 2–2 tie
        List<String> neutrals = tied.getActiveNames().keySet().stream()
                .filter(p -> !p.equals(tiedMatch.getA().ownerId()) && !p.equals(tiedMatch.getB().ownerId()))
                .toList();
        service.voteMatch(tied.getRoomCode(), tiedMatch.getA().ownerId(), tiedMatch.getB().id());
        service.voteMatch(tied.getRoomCode(), tiedMatch.getB().ownerId(), tiedMatch.getA().id());
        service.voteMatch(tied.getRoomCode(), neutrals.get(0), tiedMatch.getA().id());
        service.voteMatch(tied.getRoomCode(), neutrals.get(1), tiedMatch.getB().id());

        assertTrue(tiedMatch.isRevealed());
        assertTrue(tiedMatch.isTieBroken());
        assertTrue(tiedMatch.getWinner() == tiedMatch.getA() || tiedMatch.getWinner() == tiedMatch.getB());
    }

    @Test
    void voteAfterRevealIsRejected() {
        Room room = gameInBracketPhase("Alice", "Bob", "Carol");
        var match = currentMatch(room);
        allVoteFor(room, match.getA());
        assertTrue(match.isRevealed());

        String neutral = room.getBracket().getRounds().get(0).bye().ownerId();
        service.voteMatch(room.getRoomCode(), neutral, match.getB().id());
        expectPersonalError(neutral, "MATCH_CLOSED");
    }

    @Test
    void bracketAdvancesThroughRoundsToAChampionAndResults() {
        Room room = gameInBracketPhase("Alice", "Bob", "Carol");

        // Round 1: one match + a bye
        var match1 = currentMatch(room);
        allVoteFor(room, match1.getA());
        service.advanceBracket(room);

        // Round 2: match winner vs the bye
        assertEquals(GamePhase.BRACKET_VOTING, room.getPhase());
        assertEquals(2, room.getBracket().getRounds().size());
        var match2 = currentMatch(room);
        allVoteFor(room, match2.getB());
        service.advanceBracket(room);

        assertEquals(GamePhase.RESULTS, room.getPhase());
        assertEquals(match2.getB().id(), room.getBracket().getChampion().id());
    }

    @Test
    void playAgainIsHostOnlyAndResetsTheRoom() {
        Room room = gameInBracketPhase("Alice", "Bob", "Carol");
        allVoteFor(room, currentMatch(room).getA());
        service.advanceBracket(room);
        allVoteFor(room, currentMatch(room).getA());
        service.advanceBracket(room);
        assertEquals(GamePhase.RESULTS, room.getPhase());

        service.playAgain(room.getRoomCode(), "p2");
        expectPersonalError("p2", "NOT_HOST");
        assertEquals(GamePhase.RESULTS, room.getPhase());

        service.playAgain(room.getRoomCode(), "p1");
        assertEquals(GamePhase.LOBBY, room.getPhase());
        assertEquals(3, room.presenceCount(), "players stay connected across games");
        assertTrue(room.getPrompts().isEmpty());
        assertTrue(room.getDrawings().isEmpty());
        assertNull(room.getBracket());
        assertNull(room.getWinningPrompt());
    }

    // --- Disconnects mid-phase ---

    @Test
    void phaseCompletesWhenTheLastHoldoutDisconnects() {
        Room room = startedGame("Alice", "Bob", "Carol");
        service.submitPrompt(room.getRoomCode(), "p1", "prompt A");
        service.submitPrompt(room.getRoomCode(), "p2", "prompt B");

        // Carol leaves without submitting (mirrors RoomEventListener's cleanup)
        room.getSessions().remove("p3");
        room.getActiveNames().remove("p3");
        room.getClaimedNames().remove("carol");
        service.playerLeft(room);

        assertEquals(GamePhase.PROMPT_VOTING, room.getPhase());
        assertEquals(2, room.getPromptBallot().size());
    }

    @Test
    void departedPlayersPromptStaysOnTheBallot() {
        Room room = startedGame("Alice", "Bob", "Carol");
        service.submitPrompt(room.getRoomCode(), "p1", "prompt A");
        service.submitPrompt(room.getRoomCode(), "p2", "prompt B");
        service.submitPrompt(room.getRoomCode(), "p3", "prompt C");
        assertEquals(GamePhase.PROMPT_VOTING, room.getPhase());

        // Carol leaves after voting opened — her prompt remains votable
        room.getSessions().remove("p3");
        room.getActiveNames().remove("p3");
        room.getClaimedNames().remove("carol");
        service.playerLeft(room);

        assertEquals(3, room.getPromptBallot().size());

        // Remaining two players can still finish the vote
        Map<String, String> ids = ownPromptIds(room);
        service.votePrompt(room.getRoomCode(), "p1", ids.get("p3"));
        service.votePrompt(room.getRoomCode(), "p2", ids.get("p3"));
        assertEquals(GamePhase.DRAWING, room.getPhase());
        assertEquals("prompt C", room.getWinningPrompt().text());
    }

    @Test
    void ballotOrderHidesSubmissionOrderButKeepsAllPrompts() {
        Room room = startedGame("Alice", "Bob", "Carol");
        service.submitPrompt(room.getRoomCode(), "p1", "prompt A");
        service.submitPrompt(room.getRoomCode(), "p2", "prompt B");
        service.submitPrompt(room.getRoomCode(), "p3", "prompt C");

        List<String> ballotTexts = room.getPromptBallot().stream().map(Prompt::text).toList();
        assertEquals(3, ballotTexts.size());
        assertTrue(ballotTexts.containsAll(List.of("prompt A", "prompt B", "prompt C")));
    }
}
