package com.example.drawmageddon.service;

import com.example.drawmageddon.model.GameEvent;
import com.example.drawmageddon.model.Room;
import com.example.drawmageddon.model.RoomStateView;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/** Single place that turns room state into outbound STOMP messages. */
@Service
public class GameEvents {

    private final SimpMessagingTemplate messaging;

    public GameEvents(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public RoomStateView view(Room room) {
        return RoomStateView.of(room, GameService.MIN_PLAYERS_TO_START);
    }

    /** Broadcast the current room state to everyone in the room. */
    public void broadcastState(Room room) {
        messaging.convertAndSend(topic(room.getRoomCode()), GameEvent.state(view(room)));
    }

    public void broadcast(String roomCode, GameEvent event) {
        messaging.convertAndSend(topic(roomCode), event);
    }

    public void sendPersonal(String principal, GameEvent event) {
        messaging.convertAndSendToUser(principal, "/queue/personal", event);
    }

    private String topic(String roomCode) {
        return "/topic/room/" + roomCode.toUpperCase();
    }
}
