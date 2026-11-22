package org.fogofwarlabs.sc4j.scr.game.trigger.eud.region;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Checks the interface tick clock: ticks per second at several pass rates, the tick limit of one pass and the stamp it leaves.
 */
final class LocalInterfaceStateTest {
    /**
     * The most ticks one pass runs in the checks.
     */
    private static final int MAXIMUM_TICKS = 4;
    /**
     * The milliseconds of one interface tick on a millisecond clock.
     */
    private static final int TICK_MILLISECONDS = 11;
    /**
     * The checked span of host time in milliseconds and the ticks a millisecond-polled interface loop runs in it.
     */
    private static final int SPAN_MILLISECONDS = 10_000;
    private static final int SPAN_TICKS = SPAN_MILLISECONDS / TICK_MILLISECONDS;
    /**
     * The host clock of the tick start; it is near the wrap of a signed DWORD.
     */
    private static final int START_MILLISECONDS = Integer.MAX_VALUE - 1000;
    /**
     * A pause between two passes far longer than the tick limit.
     */
    private static final int PAUSE_MILLISECONDS = 500;
    private static final int MILLISECONDS_PER_SECOND = 1000;

    private static int countTicks(int passesPerSecond) {
        LocalInterfaceState state = new LocalInterfaceState();
        int passCount = SPAN_MILLISECONDS * passesPerSecond / MILLISECONDS_PER_SECOND;
        int ticks = 0;

        state.startInterfaceTick(START_MILLISECONDS);

        for (int pass = 1; pass <= passCount; pass++) {
            ticks += state.advanceInterfaceTicks(START_MILLISECONDS + (int) ((long) pass * MILLISECONDS_PER_SECOND / passesPerSecond), MAXIMUM_TICKS);
        }

        return ticks;
    }


    @ParameterizedTest
    @ValueSource(ints = {30, 60, 75, 120, 144, 165, 240, 300, 1000})
    void ticks_per_second_do_not_depend_on_the_pass_rate(int passesPerSecond) {
        Assertions.assertEquals(SPAN_TICKS, countTicks(passesPerSecond), 1, "ticks in " + SPAN_MILLISECONDS + " ms");
    }

    @Test
    void a_pass_within_the_tick_period_runs_no_tick() {
        LocalInterfaceState state = new LocalInterfaceState();

        state.startInterfaceTick(0);

        Assertions.assertEquals(0, state.advanceInterfaceTicks(TICK_MILLISECONDS - 1, MAXIMUM_TICKS));
        Assertions.assertEquals(1, state.advanceInterfaceTicks(TICK_MILLISECONDS, MAXIMUM_TICKS));
        Assertions.assertEquals(-TICK_MILLISECONDS, state.getInterfaceTick());
        Assertions.assertEquals(1, state.advanceInterfaceTicks(TICK_MILLISECONDS * 2 + 1, MAXIMUM_TICKS));
        Assertions.assertEquals(-TICK_MILLISECONDS * 2, state.getInterfaceTick());
    }

    @Test
    void a_long_pause_runs_the_tick_limit_and_renews_the_stamp() {
        LocalInterfaceState state = new LocalInterfaceState();

        state.startInterfaceTick(0);

        Assertions.assertEquals(MAXIMUM_TICKS, state.advanceInterfaceTicks(PAUSE_MILLISECONDS, MAXIMUM_TICKS));
        Assertions.assertEquals(-PAUSE_MILLISECONDS, state.getInterfaceTick());
        Assertions.assertEquals(0, state.advanceInterfaceTicks(PAUSE_MILLISECONDS + TICK_MILLISECONDS - 1, MAXIMUM_TICKS));
        Assertions.assertEquals(1, state.advanceInterfaceTicks(PAUSE_MILLISECONDS + TICK_MILLISECONDS, MAXIMUM_TICKS));
    }

    @Test
    void a_pass_with_the_tick_limit_due_keeps_the_stamp_of_its_last_tick() {
        LocalInterfaceState state = new LocalInterfaceState();
        int lastTick = TICK_MILLISECONDS * MAXIMUM_TICKS;

        state.startInterfaceTick(0);

        Assertions.assertEquals(MAXIMUM_TICKS, state.advanceInterfaceTicks(lastTick + TICK_MILLISECONDS - 1, MAXIMUM_TICKS));
        Assertions.assertEquals(-lastTick, state.getInterfaceTick());
    }

    @Test
    void the_chat_is_open_while_the_supplied_chat_mode_is_nonzero() {
        LocalInterfaceState state = new LocalInterfaceState();

        Assertions.assertFalse(state.isChatOpen(), "an unknown chat mode");

        state.setChatMode(1);

        Assertions.assertTrue(state.isChatOpen());

        state.setChatMode(0);

        Assertions.assertFalse(state.isChatOpen());
    }
}
