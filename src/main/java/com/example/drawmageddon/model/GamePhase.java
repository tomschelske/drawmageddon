package com.example.drawmageddon.model;

/**
 * Server-authoritative game state machine. The server alone decides when a
 * phase is complete and broadcasts every transition to all clients.
 */
public enum GamePhase {
    LOBBY,
    PROMPT_SUBMISSION,
    PROMPT_VOTING,
    DRAWING,
    BRACKET_VOTING,
    RESULTS
}
