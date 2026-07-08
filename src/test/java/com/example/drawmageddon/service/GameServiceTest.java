package com.example.drawmageddon.service;

import com.example.drawmageddon.model.GameEvent;
import com.example.drawmageddon.model.GamePhase;
import com.example.drawmageddon.model.Prompt;
import com.example.drawmageddon.model.Room;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

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

    private RoomManager roomManager;
    private RecordingMessaging messaging;
    private GameService service;

    @BeforeEach
    void setUp() {
        roomManager = new RoomManager();
        messaging = new RecordingMessaging();
        service = new GameService(roomManager, new SessionRegistry(), new GameEvents(messaging));
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
