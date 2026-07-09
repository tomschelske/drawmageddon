package com.example.drawmageddon.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Single-elimination bracket over submitted drawings. Matchups are played one
 * at a time; each round is randomly paired from the previous round's winners,
 * with a random bye when the entrant count is odd.
 *
 * All mutation happens under the owning room's lock.
 */
public class Bracket {

    private final List<BracketRound> rounds = new ArrayList<>();
    private final Random random;

    private int currentRoundIndex;
    private int currentMatchIndex;
    private Drawing champion;
    private boolean finished;

    public Bracket(List<Drawing> entrants, Random random) {
        this.random = random;
        if (entrants.size() <= 1) {
            // 0 drawings: no champion; 1 drawing: wins unopposed
            champion = entrants.isEmpty() ? null : entrants.get(0);
            finished = true;
        } else {
            rounds.add(buildRound(entrants));
        }
    }

    private BracketRound buildRound(List<Drawing> entrants) {
        List<Drawing> pool = new ArrayList<>(entrants);
        Collections.shuffle(pool, random);
        // After the shuffle, dropping the last entrant is a uniformly random bye
        Drawing bye = (pool.size() % 2 == 1) ? pool.remove(pool.size() - 1) : null;
        List<BracketMatch> matches = new ArrayList<>();
        for (int i = 0; i < pool.size(); i += 2) {
            matches.add(new BracketMatch(pool.get(i), pool.get(i + 1)));
        }
        return new BracketRound(matches, bye);
    }

    /** The matchup currently open (or in reveal); null once the bracket is finished. */
    public BracketMatch currentMatch() {
        if (finished) return null;
        return rounds.get(currentRoundIndex).matches().get(currentMatchIndex);
    }

    public BracketRound currentRound() {
        return finished ? null : rounds.get(currentRoundIndex);
    }

    /** Move past the current (revealed) match: next match, next round, or champion. */
    public void advance() {
        if (finished) return;
        BracketRound round = rounds.get(currentRoundIndex);

        if (currentMatchIndex + 1 < round.matches().size()) {
            currentMatchIndex++;
            return;
        }

        List<Drawing> winners = new ArrayList<>();
        for (BracketMatch match : round.matches()) {
            winners.add(match.getWinner());
        }
        if (round.bye() != null) {
            winners.add(round.bye());
        }

        if (winners.size() == 1) {
            champion = winners.get(0);
            finished = true;
            return;
        }

        rounds.add(buildRound(winners));
        currentRoundIndex++;
        currentMatchIndex = 0;
    }

    public List<BracketRound> getRounds() { return rounds; }
    public int getCurrentRoundIndex() { return currentRoundIndex; }
    public int getCurrentMatchIndex() { return currentMatchIndex; }
    public Drawing getChampion() { return champion; }
    public boolean isFinished() { return finished; }
}
