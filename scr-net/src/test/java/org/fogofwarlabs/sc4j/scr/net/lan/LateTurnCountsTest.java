package org.fogofwarlabs.sc4j.scr.net.lan;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a participant's turn counts late once however many receives miss it, separately for each participant, until it is taken.
 */
final class LateTurnCountsTest {
    private static final int IDENTIFIER_COUNT = 0x100;
    private static final int FIRST_PLAYER = 0;
    private static final int SECOND_PLAYER = 1;
    private static final int OBSERVER = 0x80;
    private static final int FIRST_TURN = 0xFFFF;
    private static final int SECOND_TURN = 0;
    private static final int RECEIVES_PER_TURN = 3;

    @Test
    void record_missing_should_count_each_missed_turn_once_per_participant() {
        LateTurnCounts counts = new LateTurnCounts(IDENTIFIER_COUNT);

        Assertions.assertEquals(0, counts.take(FIRST_PLAYER));

        for (int receive = 0; receive < RECEIVES_PER_TURN; receive++) {
            counts.recordMissing(FIRST_PLAYER, FIRST_TURN);
            counts.recordMissing(OBSERVER, FIRST_TURN);
        }

        counts.recordMissing(FIRST_PLAYER, SECOND_TURN);
        counts.recordMissing(SECOND_PLAYER, SECOND_TURN);

        Assertions.assertEquals(2, counts.take(FIRST_PLAYER));
        Assertions.assertEquals(1, counts.take(SECOND_PLAYER));
        Assertions.assertEquals(1, counts.take(OBSERVER));
        Assertions.assertEquals(0, counts.take(FIRST_PLAYER), "taking clears the count");

        counts.recordMissing(FIRST_PLAYER, SECOND_TURN);

        Assertions.assertEquals(0, counts.take(FIRST_PLAYER), "a turn counted before the take does not count again");
    }
}
