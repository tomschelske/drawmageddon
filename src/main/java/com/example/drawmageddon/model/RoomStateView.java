package com.example.drawmageddon.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Client-facing snapshot of a room, rebuilt and broadcast on every change. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoomStateView(String roomCode,
                            GamePhase phase,
                            List<PlayerView> players,
                            int minPlayersToStart,
                            List<PromptView> prompts,
                            String winningPrompt,
                            Long phaseRemainingMillis) {

    /** `done` = has completed the current phase's action (submitted / voted). */
    public record PlayerView(String name, boolean host, boolean done) {}

    /** Live tally is public by design during prompt voting; authorship is not. */
    public record PromptView(String id, String text, int votes) {}

    public static RoomStateView of(Room room, int minPlayersToStart) {
        GamePhase phase = room.getPhase();
        String hostId = room.getHostId();

        List<PlayerView> players = new ArrayList<>();
        // sessions preserves join order; activeNames holds display names
        for (String principal : room.getSessions()) {
            String name = room.getActiveNames().get(principal);
            if (name == null) continue;
            boolean done = switch (phase) {
                case PROMPT_SUBMISSION -> room.getPrompts().containsKey(principal);
                case PROMPT_VOTING -> room.getPromptVotes().containsKey(principal);
                case DRAWING -> room.getDrawings().containsKey(principal);
                default -> false;
            };
            players.add(new PlayerView(name, principal.equals(hostId), done));
        }

        List<PromptView> prompts = null;
        List<Prompt> ballot = room.getPromptBallot();
        if (phase == GamePhase.PROMPT_VOTING && ballot != null) {
            Map<String, Integer> tally = new HashMap<>();
            for (String promptId : room.getPromptVotes().values()) {
                tally.merge(promptId, 1, Integer::sum);
            }
            prompts = new ArrayList<>();
            for (Prompt p : ballot) {
                prompts.add(new PromptView(p.id(), p.text(), tally.getOrDefault(p.id(), 0)));
            }
        }

        Prompt winner = room.getWinningPrompt();
        String winningPrompt = winner == null ? null : winner.text();

        // Clients count down locally from remaining-at-broadcast, so clock skew is irrelevant
        Long phaseRemainingMillis = null;
        Instant deadline = room.getPhaseDeadline();
        if (deadline != null) {
            phaseRemainingMillis = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
        }

        return new RoomStateView(room.getRoomCode(), phase, players, minPlayersToStart,
                                 prompts, winningPrompt, phaseRemainingMillis);
    }
}
