package org.fogofwarlabs.sc4j.scr.game.session;

import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandIds;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Checks the dynamic turn rate's scan, lowering, raising and lock decisions and the commands they send.
 */
final class DynamicTurnRateTest {
    private static final long MILLISECOND = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;
    private static final int PROVIDER_TURNS_IN_TRANSIT = 2;

    private static final int FASTEST_RATE = 24;
    private static final int SECOND_RATE = 20;
    private static final int THIRD_RATE = 16;
    private static final long STALL_LENGTH = 50 * MILLISECOND;
    private static final long STALL_GAP = 100 * MILLISECOND;
    private static final int STALLS_TO_LOWER = 5;

    /**
     * A game whose turn rate, latency and lowest present player the test sets, and which keeps the queued commands.
     */
    private static final class GameSession implements DynamicTurnRate.Session {
        private final @NotNull List<String> commands;

        private int turnRate;
        private int latency;
        private boolean lowestPresentPlayer;

        private GameSession(int turnRate, boolean lowestPresentPlayer) {
            this.commands = new ArrayList<>();

            this.turnRate            = turnRate;
            this.latency             = 0;
            this.lowestPresentPlayer = lowestPresentPlayer;
        }

        @Override
        public int getTurnRate() {
            return this.turnRate;
        }

        @Override
        public int getLatency() {
            return this.latency;
        }

        @Override
        public boolean isLowestPresentPlayer() {
            return this.lowestPresentPlayer;
        }

        @Override
        public void queueCommand(byte @NotNull [] command) {
            this.commands.add(HexFormat.of().formatHex(command));
        }

        private @NotNull List<String> takeCommands() {
            List<String> taken = List.copyOf(this.commands);

            this.commands.clear();

            return taken;
        }
    }

    /**
     * A clock the test advances.
     */
    private static final class TestClock {
        private long time;

        private long getTime() {
            return this.time;
        }
    }

    private static @NotNull String encode(int... bytes) {
        byte[] command = new byte[bytes.length];

        for (int index = 0; index < bytes.length; index++) {
            command[index] = (byte) bytes[index];
        }

        return HexFormat.of().formatHex(command);
    }

    /**
     * Runs stalls of {@link #STALL_LENGTH}, {@link #STALL_GAP} apart, from a time.
     *
     * @return the time after the last stall
     */
    private static long runStalls(@NotNull DynamicTurnRate rules, @NotNull TestClock clock, long start, int count) {
        long time = start;

        for (int stall = 0; stall < count; stall++) {
            clock.time = time;
            rules.onTurnMissing();

            clock.time = time + STALL_LENGTH;
            rules.onTurnReceived();

            time += STALL_GAP;
        }

        return time;
    }

    /**
     * Starts the scan of a started game and counts turn steps of a period until the scan ends.
     *
     * @return the commands the scan end queued
     */
    private static @NotNull List<String> runScan(@NotNull DynamicTurnRate rules, @NotNull GameSession game, @NotNull TestClock clock, long period) {
        clock.time = 6 * SECOND;
        rules.onScanStart();
        game.takeCommands();

        rules.onTurnReceived();

        Assertions.assertEquals(DynamicTurnRate.SCANNING, rules.getState());

        long scanStart = clock.time;

        while (rules.getState() == DynamicTurnRate.SCANNING) {
            Assertions.assertTrue(clock.time - scanStart < DynamicTurnRate.SCAN_SECONDS * SECOND + period, "the scan ends after its seconds");

            clock.time += period;
            rules.onTurnReceived();
        }

        return game.takeCommands();
    }

    @Test
    void dynamic_turn_rate_sends_the_scan_start_after_the_initial_wait() {
        TestClock clock = new TestClock();
        GameSession game = new GameSession(FASTEST_RATE, false);
        DynamicTurnRate rules = new DynamicTurnRate(game, clock::getTime, true, PROVIDER_TURNS_IN_TRANSIT);

        rules.start();

        Assertions.assertEquals(DynamicTurnRate.WAITING, rules.getState());

        clock.time = DynamicTurnRate.INITIAL_SCAN_SECONDS * SECOND;
        rules.onTurnReceived();

        Assertions.assertTrue(game.takeCommands().isEmpty(), "no scan start before the wait passed");

        clock.time += MILLISECOND;
        rules.onTurnReceived();

        Assertions.assertEquals(List.of(encode(ReplayCommandIds.DYNAMIC_TURN_RATE, 0, 0, 0)), game.takeCommands());
    }

    @Test
    void scan_start_sends_the_fastest_rate_and_silent_low_latency_from_the_lowest_player_only() {
        TestClock clock = new TestClock();
        GameSession lowest = new GameSession(SECOND_RATE, true);
        GameSession other = new GameSession(SECOND_RATE, false);
        DynamicTurnRate lowestRules = new DynamicTurnRate(lowest, clock::getTime, true, PROVIDER_TURNS_IN_TRANSIT);
        DynamicTurnRate otherRules = new DynamicTurnRate(other, clock::getTime, true, PROVIDER_TURNS_IN_TRANSIT);

        lowestRules.start();
        otherRules.start();
        lowestRules.onScanStart();
        otherRules.onScanStart();

        Assertions.assertEquals(List.of(encode(ReplayCommandIds.TURN_RATE, FASTEST_RATE), encode(ReplayCommandIds.LATENCY, 0x80)),
                                lowest.takeCommands());
        Assertions.assertTrue(other.takeCommands().isEmpty());
        Assertions.assertTrue(lowestRules.isScanning());
        Assertions.assertTrue(otherRules.isScanning());
    }

    @Test
    void scan_picks_the_least_delay_the_measured_turn_steps_allow() {
        TestClock clock = new TestClock();
        GameSession game = new GameSession(FASTEST_RATE, true);
        DynamicTurnRate rules = new DynamicTurnRate(game, clock::getTime, true, PROVIDER_TURNS_IN_TRANSIT);

        rules.start();

        // Twenty-four steps a second over two turns in transit allow 24 turns per second at Low latency.
        Assertions.assertEquals(List.of(encode(ReplayCommandIds.DYNAMIC_TURN_RATE, 1, FASTEST_RATE, 0)),
                                runScan(rules, game, clock, SECOND / FASTEST_RATE));
        Assertions.assertEquals(DynamicTurnRate.SCANNED, rules.getState());

        rules.onScanResult();

        Assertions.assertEquals(DynamicTurnRate.ADJUSTING, rules.getState());
        Assertions.assertFalse(rules.isScanning());

        DynamicTurnRate slower = new DynamicTurnRate(game, clock::getTime, true, PROVIDER_TURNS_IN_TRANSIT);

        clock.time = 0L;
        slower.start();

        // Twenty steps a second allow ten per turn in transit: 20 turns per second at Low latency.
        Assertions.assertEquals(List.of(encode(ReplayCommandIds.DYNAMIC_TURN_RATE, 1, SECOND_RATE, 0)),
                                runScan(slower, game, clock, 50 * MILLISECOND));
    }

    @Test
    void scan_result_comes_only_from_the_lowest_present_player() {
        TestClock clock = new TestClock();
        GameSession game = new GameSession(FASTEST_RATE, false);
        DynamicTurnRate rules = new DynamicTurnRate(game, clock::getTime, true, PROVIDER_TURNS_IN_TRANSIT);

        rules.start();

        Assertions.assertTrue(runScan(rules, game, clock, SECOND / FASTEST_RATE).isEmpty());
        Assertions.assertEquals(DynamicTurnRate.SCANNED, rules.getState());

        rules.onTurnRateChanged();

        Assertions.assertEquals(DynamicTurnRate.ADJUSTING, rules.getState(), "an executed turn rate change ends a waiting scan");
    }

    @Test
    void stalls_lower_the_turn_rate_when_more_than_the_limit_last_longer_than_the_average() {
        TestClock clock = new TestClock();
        GameSession game = new GameSession(FASTEST_RATE, false);
        DynamicTurnRate rules = new DynamicTurnRate(game, clock::getTime, true, PROVIDER_TURNS_IN_TRANSIT);

        rules.start();
        rules.onScanResult();

        long time = runStalls(rules, clock, SECOND, STALLS_TO_LOWER - 1);

        Assertions.assertTrue(game.takeCommands().isEmpty(), "four stalls are no more than the limit");

        runStalls(rules, clock, time, 1);

        Assertions.assertEquals(List.of(encode(ReplayCommandIds.TURN_RATE, SECOND_RATE)), game.takeCommands());

        GameSession shortStalls = new GameSession(FASTEST_RATE, false);
        DynamicTurnRate shortRules = new DynamicTurnRate(shortStalls, clock::getTime, true, PROVIDER_TURNS_IN_TRANSIT);

        clock.time = 0L;
        shortRules.start();
        shortRules.onScanResult();

        for (int stall = 0; stall < STALLS_TO_LOWER * 2; stall++) {
            clock.time = SECOND + stall * STALL_GAP;
            shortRules.onTurnMissing();

            clock.time += DynamicTurnRate.LOWERING_AVERAGE_MILLISECONDS * MILLISECOND;
            shortRules.onTurnReceived();
        }

        Assertions.assertTrue(shortStalls.takeCommands().isEmpty(), "stalls of the average itself do not lower");
    }

    @Test
    void quiet_play_raises_the_turn_rate_once() {
        TestClock clock = new TestClock();
        GameSession game = new GameSession(SECOND_RATE, false);
        DynamicTurnRate rules = new DynamicTurnRate(game, clock::getTime, true, PROVIDER_TURNS_IN_TRANSIT);

        rules.start();
        rules.onScanResult();

        clock.time = DynamicTurnRate.RAISING_WINDOW_SECONDS * SECOND;
        rules.onTurnReceived();

        Assertions.assertTrue(game.takeCommands().isEmpty(), "no raise before the window passed");

        clock.time += MILLISECOND;
        rules.onTurnReceived();

        Assertions.assertEquals(List.of(encode(ReplayCommandIds.TURN_RATE, FASTEST_RATE)), game.takeCommands());

        game.turnRate = THIRD_RATE;
        clock.time += 2 * DynamicTurnRate.RAISING_WINDOW_SECONDS * SECOND;
        rules.onTurnReceived();

        Assertions.assertTrue(game.takeCommands().isEmpty(), "the turn rate rises only once");
    }

    @Test
    void a_raise_and_two_lowerings_lock_the_turn_rate() {
        TestClock clock = new TestClock();
        GameSession game = new GameSession(SECOND_RATE, false);
        DynamicTurnRate rules = new DynamicTurnRate(game, clock::getTime, true, PROVIDER_TURNS_IN_TRANSIT);

        rules.start();
        rules.onScanResult();

        clock.time = DynamicTurnRate.RAISING_WINDOW_SECONDS * SECOND + MILLISECOND;
        rules.onTurnReceived();

        Assertions.assertEquals(List.of(encode(ReplayCommandIds.TURN_RATE, FASTEST_RATE)), game.takeCommands());

        game.turnRate = FASTEST_RATE;

        long time = runStalls(rules, clock, clock.time + SECOND, STALLS_TO_LOWER);

        Assertions.assertEquals(List.of(encode(ReplayCommandIds.TURN_RATE, SECOND_RATE)), game.takeCommands());

        game.turnRate = SECOND_RATE;
        time          = runStalls(rules, clock, time + SECOND, STALLS_TO_LOWER);

        Assertions.assertEquals(List.of(encode(ReplayCommandIds.TURN_RATE, THIRD_RATE)), game.takeCommands());

        game.turnRate = THIRD_RATE;
        runStalls(rules, clock, time + SECOND, STALLS_TO_LOWER);

        Assertions.assertTrue(game.takeCommands().isEmpty(), "the turn rate stays after a raise and two lowerings");
    }

    @Test
    void turn_rate_command_waits_five_seconds_after_an_executed_change() {
        TestClock clock = new TestClock();
        GameSession game = new GameSession(FASTEST_RATE, false);
        DynamicTurnRate rules = new DynamicTurnRate(game, clock::getTime, true, PROVIDER_TURNS_IN_TRANSIT);

        rules.start();
        rules.onScanResult();

        clock.time = SECOND;
        rules.onTurnRateChanged();

        long time = runStalls(rules, clock, clock.time + STALL_GAP, STALLS_TO_LOWER);

        Assertions.assertTrue(time - SECOND < DynamicTurnRate.TURN_RATE_COMMAND_INTERVAL_MILLISECONDS * MILLISECOND);
        Assertions.assertTrue(game.takeCommands().isEmpty(), "no Turn Rate command within five seconds of a change");

        runStalls(rules, clock, SECOND + DynamicTurnRate.TURN_RATE_COMMAND_INTERVAL_MILLISECONDS * MILLISECOND, STALLS_TO_LOWER);

        Assertions.assertEquals(List.of(encode(ReplayCommandIds.TURN_RATE, SECOND_RATE)), game.takeCommands(),
                                "the lowering before did not send, but the game kept its rate, so the next lowering asks again");
    }

    @Test
    void rules_without_the_flag_neither_scan_nor_change_the_turn_rate() {
        TestClock clock = new TestClock();
        GameSession game = new GameSession(SECOND_RATE, true);
        DynamicTurnRate rules = new DynamicTurnRate(game, clock::getTime, false, PROVIDER_TURNS_IN_TRANSIT);

        rules.start();

        clock.time = 2 * DynamicTurnRate.RAISING_WINDOW_SECONDS * SECOND;
        rules.onTurnReceived();
        rules.onScanStart();
        runStalls(rules, clock, clock.time, STALLS_TO_LOWER * 2);

        Assertions.assertTrue(game.takeCommands().isEmpty());
        Assertions.assertFalse(rules.isScanning());
    }
}
