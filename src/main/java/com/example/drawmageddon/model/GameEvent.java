package com.example.drawmageddon.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Envelope for every message the server sends to clients, whether broadcast
 * to the room topic or sent to one player's personal queue.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GameEvent(Type type, String code, RoomStateView state) {

    public enum Type {
        STATE,    // full room state snapshot; sent on every change
        JOIN_OK,  // personal: your join was accepted
        SYSTEM,   // room-wide notice, details in `code` (e.g. ROOM_EXPIRED)
        ERROR     // personal: request rejected, reason in `code`
    }

    public static GameEvent state(RoomStateView state) {
        return new GameEvent(Type.STATE, null, state);
    }

    public static GameEvent joinOk(RoomStateView state) {
        return new GameEvent(Type.JOIN_OK, null, state);
    }

    public static GameEvent system(String code) {
        return new GameEvent(Type.SYSTEM, code, null);
    }

    public static GameEvent error(String code) {
        return new GameEvent(Type.ERROR, code, null);
    }
}
