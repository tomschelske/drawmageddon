package com.example.drawmageddon.model;

import java.util.ArrayList;
import java.util.List;

/** Client-facing snapshot of a room, rebuilt and broadcast on every change. */
public record RoomStateView(String roomCode,
                            GamePhase phase,
                            List<PlayerView> players,
                            int minPlayersToStart) {

    public record PlayerView(String name, boolean host) {}

    public static RoomStateView of(Room room, int minPlayersToStart) {
        List<PlayerView> players = new ArrayList<>();
        String hostId = room.getHostId();
        // sessions preserves join order; activeNames holds display names
        for (String principal : room.getSessions()) {
            String name = room.getActiveNames().get(principal);
            if (name != null) {
                players.add(new PlayerView(name, principal.equals(hostId)));
            }
        }
        return new RoomStateView(room.getRoomCode(), room.getPhase(), players, minPlayersToStart);
    }
}
