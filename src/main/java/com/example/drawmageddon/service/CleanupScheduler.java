package com.example.drawmageddon.service;

import com.example.drawmageddon.model.GameEvent;
import com.example.drawmageddon.model.Room;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
@EnableScheduling
public class CleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(CleanupScheduler.class);

    private final RoomManager roomManager;
    private final GameEvents events;

    public CleanupScheduler(RoomManager roomManager, GameEvents events) {
        this.roomManager = roomManager;
        this.events = events;
    }

    @Scheduled(fixedDelay = 120_000)
    public void purgeExpiredRooms() {
        Instant cutoff = Instant.now().minus(RoomManager.EXPIRY);
        List<String> toRemove = new ArrayList<>();

        // Collect expired rooms without modifying the map during iteration
        for (Room room : roomManager.allRooms()) {
            Instant emptied = room.getLastEmptiedAt();
            if (emptied != null && emptied.isBefore(cutoff)) {
                toRemove.add(room.getRoomCode());
            }
        }

        for (String code : toRemove) {
            events.broadcast(code, GameEvent.system("ROOM_EXPIRED"));
            roomManager.removeRoom(code);
            log.info("Purged expired room: {}", code);
        }
    }
}
