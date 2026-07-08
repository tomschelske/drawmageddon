package com.example.drawmageddon.handler;

import com.example.drawmageddon.model.Room;
import com.example.drawmageddon.service.GameEvents;
import com.example.drawmageddon.service.RoomManager;
import com.example.drawmageddon.service.SessionRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.time.Instant;

@Component
public class RoomEventListener {

    private final RoomManager roomManager;
    private final SessionRegistry sessionRegistry;
    private final GameEvents events;

    public RoomEventListener(RoomManager roomManager,
                             SessionRegistry sessionRegistry,
                             GameEvents events) {
        this.roomManager = roomManager;
        this.sessionRegistry = sessionRegistry;
        this.events = events;
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        if (event.getUser() == null) return;
        String principal = event.getUser().getName();

        SessionRegistry.SessionInfo info = sessionRegistry.remove(principal).orElse(null);
        if (info == null) return;

        Room room = roomManager.findRoom(info.roomCode()).orElse(null);
        if (room == null) return;

        room.getSessions().remove(principal);
        String displayName = room.getActiveNames().remove(principal);
        if (displayName != null) {
            room.getClaimedNames().remove(displayName.toLowerCase());
        }

        // Hand host duties to the longest-connected remaining player
        if (principal.equals(room.getHostId())) {
            room.setHostId(room.getSessions().isEmpty() ? null : room.getSessions().get(0));
        }

        if (room.getSessions().isEmpty()) {
            room.setLastEmptiedAt(Instant.now());
        }

        events.broadcastState(room);
    }
}
