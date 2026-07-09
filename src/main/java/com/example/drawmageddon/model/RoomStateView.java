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
                            Long phaseRemainingMillis,
                            MatchupView matchup,
                            List<BracketRoundView> bracket,
                            DrawingView champion) {

    /** `done` = has completed the current phase's action (submitted / voted). */
    public record PlayerView(String name, boolean host, boolean done) {}

    /** Live tally is public by design during prompt voting; authorship is not. */
    public record PromptView(String id, String text, int votes) {}

    public record DrawingView(String id, String artist, String imageData) {
        static DrawingView of(Drawing d) {
            return d == null ? null : new DrawingView(d.id(), d.ownerName(), d.imageData());
        }
    }

    /**
     * The matchup currently on stage. Tallies (votesA/votesB) and the winner
     * are null until the match is revealed — clients never see them early.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MatchupView(String matchId,
                              int round,
                              int matchIndex,
                              int matchCount,
                              DrawingView a,
                              DrawingView b,
                              int votesIn,
                              boolean revealed,
                              Integer votesA,
                              Integer votesB,
                              String winnerId,
                              Boolean tieBroken) {}

    /** Text-only bracket overview (no image payloads) for the tree display. */
    public record BracketMatchSummary(String aArtist, String bArtist, String winnerArtist) {}

    public record BracketRoundView(List<BracketMatchSummary> matches, String byeArtist) {}

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
                case BRACKET_VOTING -> {
                    BracketMatch match = room.getBracket() == null ? null : room.getBracket().currentMatch();
                    yield match != null && match.getVotes().containsKey(principal);
                }
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

        MatchupView matchup = null;
        List<BracketRoundView> bracketView = null;
        DrawingView champion = null;
        Bracket bracket = room.getBracket();
        if (bracket != null) {
            BracketMatch match = bracket.currentMatch();
            if (phase == GamePhase.BRACKET_VOTING && match != null) {
                boolean revealed = match.isRevealed();
                matchup = new MatchupView(
                        match.getId(),
                        bracket.getCurrentRoundIndex() + 1,
                        bracket.getCurrentMatchIndex() + 1,
                        bracket.currentRound().matches().size(),
                        DrawingView.of(match.getA()),
                        DrawingView.of(match.getB()),
                        match.getVotes().size(),
                        revealed,
                        revealed ? match.votesFor(match.getA()) : null,
                        revealed ? match.votesFor(match.getB()) : null,
                        revealed ? match.getWinner().id() : null,
                        revealed ? match.isTieBroken() : null);
            }

            bracketView = new ArrayList<>();
            for (BracketRound round : bracket.getRounds()) {
                List<BracketMatchSummary> summaries = new ArrayList<>();
                for (BracketMatch m : round.matches()) {
                    summaries.add(new BracketMatchSummary(
                            m.getA().ownerName(),
                            m.getB().ownerName(),
                            m.isRevealed() ? m.getWinner().ownerName() : null));
                }
                bracketView.add(new BracketRoundView(summaries,
                        round.bye() == null ? null : round.bye().ownerName()));
            }

            if (phase == GamePhase.RESULTS) {
                champion = DrawingView.of(bracket.getChampion());
            }
        }

        return new RoomStateView(room.getRoomCode(), phase, players, minPlayersToStart,
                                 prompts, winningPrompt, phaseRemainingMillis,
                                 matchup, bracketView, champion);
    }
}
