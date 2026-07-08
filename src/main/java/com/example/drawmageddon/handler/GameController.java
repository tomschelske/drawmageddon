package com.example.drawmageddon.handler;

import com.example.drawmageddon.model.GameEvent;
import com.example.drawmageddon.model.GamePhase;
import com.example.drawmageddon.model.Room;
import com.example.drawmageddon.service.GameEvents;
import com.example.drawmageddon.service.RoomManager;
import com.example.drawmageddon.service.SessionRegistry;
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

@Controller
public class GameController {

    public record JoinRequest(String name) {}

    private final RoomManager roomManager;
    private final SessionRegistry sessionRegistry;
    private final GameEvents events;

    public GameController(RoomManager roomManager,
                          SessionRegistry sessionRegistry,
                          GameEvents events) {
        this.roomManager = roomManager;
        this.sessionRegistry = sessionRegistry;
        this.events = events;
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

    // --- WebSocket: join room ---

    @MessageMapping("/room/{roomCode}/join")
    public void joinRoom(@DestinationVariable String roomCode,
                         JoinRequest request,
                         SimpMessageHeaderAccessor headerAccessor) {

        String principal = headerAccessor.getUser().getName();

        Room room = roomManager.findRoom(roomCode).orElse(null);
        if (room == null) {
            events.sendPersonal(principal, GameEvent.error("ROOM_NOT_FOUND"));
            return;
        }

        if (room.getPhase() != GamePhase.LOBBY) {
            events.sendPersonal(principal, GameEvent.error("GAME_IN_PROGRESS"));
            return;
        }

        String name = request.name() == null ? "" : request.name().trim();
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

    // --- WebSocket: host starts the game ---

    @MessageMapping("/room/{roomCode}/start")
    public void startGame(@DestinationVariable String roomCode,
                          SimpMessageHeaderAccessor headerAccessor) {

        String principal = headerAccessor.getUser().getName();

        Room room = roomManager.findRoom(roomCode).orElse(null);
        if (room == null) {
            events.sendPersonal(principal, GameEvent.error("ROOM_NOT_FOUND"));
            return;
        }

        if (!principal.equals(room.getHostId())) {
            events.sendPersonal(principal, GameEvent.error("NOT_HOST"));
            return;
        }

        if (room.getPhase() != GamePhase.LOBBY) {
            events.sendPersonal(principal, GameEvent.error("ALREADY_STARTED"));
            return;
        }

        if (room.presenceCount() < GameEvents.MIN_PLAYERS_TO_START) {
            events.sendPersonal(principal, GameEvent.error("NOT_ENOUGH_PLAYERS"));
            return;
        }

        room.setPhase(GamePhase.PROMPT_SUBMISSION);
        events.broadcastState(room);
    }
}
