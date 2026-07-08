package com.example.drawmageddon.handler;

import com.example.drawmageddon.model.Room;
import com.example.drawmageddon.service.GameService;
import com.example.drawmageddon.service.RoomManager;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

/** Thin transport layer: parses messages and delegates to GameService. */
@Controller
public class GameController {

    public record JoinRequest(String name) {}
    public record PromptSubmission(String text) {}
    public record VoteRequest(String promptId) {}

    private final RoomManager roomManager;
    private final GameService gameService;

    public GameController(RoomManager roomManager, GameService gameService) {
        this.roomManager = roomManager;
        this.gameService = gameService;
    }

    // --- HTTP: room management ---

    @PostMapping("/api/rooms")
    @ResponseBody
    public ResponseEntity<Map<String, String>> createRoom() {
        Room room = roomManager.createRoom();
        return ResponseEntity.ok(Map.of("roomCode", room.getRoomCode()));
    }

    @GetMapping("/api/rooms/{roomCode}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getRoom(@PathVariable String roomCode) {
        return roomManager.findRoom(roomCode)
            .map(r -> ResponseEntity.ok(Map.<String, Object>of(
                "roomCode", r.getRoomCode(),
                "phase", r.getPhase(),
                "presenceCount", r.presenceCount()
            )))
            .orElse(ResponseEntity.notFound().build());
    }

    // --- WebSocket: game intents ---

    @MessageMapping("/room/{roomCode}/join")
    public void joinRoom(@DestinationVariable String roomCode,
                         JoinRequest request,
                         SimpMessageHeaderAccessor headerAccessor) {
        gameService.join(roomCode, principal(headerAccessor), request.name());
    }

    @MessageMapping("/room/{roomCode}/start")
    public void startGame(@DestinationVariable String roomCode,
                          SimpMessageHeaderAccessor headerAccessor) {
        gameService.start(roomCode, principal(headerAccessor));
    }

    @MessageMapping("/room/{roomCode}/prompt")
    public void submitPrompt(@DestinationVariable String roomCode,
                             PromptSubmission submission,
                             SimpMessageHeaderAccessor headerAccessor) {
        gameService.submitPrompt(roomCode, principal(headerAccessor), submission.text());
    }

    @MessageMapping("/room/{roomCode}/vote")
    public void votePrompt(@DestinationVariable String roomCode,
                           VoteRequest vote,
                           SimpMessageHeaderAccessor headerAccessor) {
        gameService.votePrompt(roomCode, principal(headerAccessor), vote.promptId());
    }

    private String principal(SimpMessageHeaderAccessor headerAccessor) {
        return headerAccessor.getUser().getName();
    }
}
