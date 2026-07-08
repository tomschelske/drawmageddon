package com.example.drawmageddon.service;

import com.example.drawmageddon.model.Room;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory room registry, carried over from the Ephemeral Chat
 * App (minus its SQLite persistence — game rooms can't survive a restart
 * mid-game, so there's nothing worth persisting).
 */
@Service
public class RoomManager {

    // No 0/O/1/I to keep codes easy to read out loud
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 6;
    public static final Duration EXPIRY = Duration.ofMinutes(10);

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Room> rooms = new ConcurrentHashMap<>();

    public Room createRoom() {
        String code;
        Room room;
        do {
            code = generateCode();
            room = new Room(code);
        } while (rooms.putIfAbsent(code, room) != null);
        return room;
    }

    public Optional<Room> findRoom(String roomCode) {
        return Optional.ofNullable(rooms.get(roomCode.toUpperCase()));
    }

    public boolean removeRoom(String roomCode) {
        return rooms.remove(roomCode.toUpperCase()) != null;
    }

    public Iterable<Room> allRooms() {
        return rooms.values();
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }
}
