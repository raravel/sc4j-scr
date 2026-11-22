package org.fogofwarlabs.sc4j.scr.net.lan;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Counts per participant the turns that arrived after the turn step that takes them was due: a turn counts once, at the first receive that
 * misses it, however many receives wait for it.
 */
final class LateTurnCounts {
    /**
     * The turn of no receive, below every unsigned 16-bit turn.
     */
    private static final int NO_TURN = -1;

    /**
     * Per participant identifier, the late turns since the last {@link #take(int)}.
     */
    private final int @NotNull [] counts;
    /**
     * Per participant identifier, the last turn counted late, or {@link #NO_TURN}.
     */
    private final int @NotNull [] lastTurns;

    /**
     * Creates a {@link LateTurnCounts} instance without late turns.
     *
     * @param identifierCount the number of participant identifiers
     */
    LateTurnCounts(int identifierCount) {
        this.counts    = new int[identifierCount];
        this.lastTurns = new int[identifierCount];

        Arrays.fill(this.lastTurns, NO_TURN);
    }

    /**
     * Records a receive that missed a participant's turn, counting the turn late unless an earlier receive already missed it.
     *
     * @param identifier the participant identifier
     * @param turn       the missed turn
     */
    void recordMissing(int identifier, int turn) {
        if (this.lastTurns[identifier] == turn) {
            return;
        }

        this.lastTurns[identifier] = turn;
        this.counts[identifier]++;
    }

    /**
     * Takes a participant's late turns since the previous call.
     *
     * @param identifier the participant identifier
     * @return the number of late turns
     */
    int take(int identifier) {
        int count = this.counts[identifier];

        this.counts[identifier] = 0;

        return count;
    }
}
