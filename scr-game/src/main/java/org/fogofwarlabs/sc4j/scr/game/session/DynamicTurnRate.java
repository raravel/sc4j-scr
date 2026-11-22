package org.fogofwarlabs.sc4j.scr.game.session;

import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandIds;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Adjusts the turn rate and latency of a network game from its turn stalls, the dynamic turn rate of a game whose flags carry
 * {@link LiveGame#DYNAMIC_TURN_RATE_FLAG}.
 * <p>
 * A stall is the time a turn step waits for a missing turn ({@link #onTurnMissing} at its start, {@link #onTurnReceived} at its end); the
 * stalls of the last {@value #LAG_EVENT_RETENTION_SECONDS} seconds are kept. The game starts with a scan: {@value #INITIAL_SCAN_SECONDS}
 * seconds after the start every machine sends the Dynamic Turn Rate scan start, which sets {@value #SCAN_TURN_RATE} turns per second and
 * Low latency; the turn steps completed in the next {@value #SCAN_SECONDS} seconds measure the connection, and the lowest present player
 * sends the turn rate and latency of the table entry with the least delay that the measurement allows. Afterwards a stall start lowers the
 * turn rate one step when the stalls of the last {@value #LOWERING_WINDOW_SECONDS} seconds number more than {@value #LOWERING_STALL_LIMIT}
 * and last more than {@value #LOWERING_AVERAGE_MILLISECONDS} ms on average, and a completed turn step raises it one step once, after
 * {@value #RAISING_WINDOW_SECONDS} seconds without a change whose stalls number fewer than {@value #RAISING_STALL_LIMIT} and last less
 * than {@value #RAISING_AVERAGE_MILLISECONDS} ms on average. After a raise and two lowerings the turn rate stays.
 * <p>
 * Every machine runs the same rules on its own stalls; the turn rate changes only when a Turn Rate or Dynamic Turn Rate command executes
 * ({@link #onTurnRateChanged}, {@link #onScanResult}, {@link #onScanStart}). The clock is in nanoseconds.
 */
final class DynamicTurnRate {
    /**
     * The turn rates the rules step between, in turns per second.
     */
    static final int @NotNull [] TURN_RATES = {8, 10, 12, 14, 16, 20, 24};
    /**
     * The turn rate of the scan.
     */
    static final int SCAN_TURN_RATE = 24;
    /**
     * The seconds from the start of the game to the scan start.
     */
    static final int INITIAL_SCAN_SECONDS = 5;
    /**
     * The seconds the scan measures.
     */
    static final int SCAN_SECONDS = 5;
    /**
     * The seconds of stalls a lowering checks.
     */
    static final int LOWERING_WINDOW_SECONDS = 10;
    /**
     * The stalls a lowering needs more than.
     */
    static final int LOWERING_STALL_LIMIT = 4;
    /**
     * The average stall a lowering needs more than, in milliseconds.
     */
    static final int LOWERING_AVERAGE_MILLISECONDS = 40;
    /**
     * The seconds without a change before a raise, and the seconds of stalls it checks.
     */
    static final int RAISING_WINDOW_SECONDS = 30;
    /**
     * The stalls a raise needs fewer than.
     */
    static final int RAISING_STALL_LIMIT = 4;
    /**
     * The average stall a raise needs less than, in milliseconds.
     */
    static final int RAISING_AVERAGE_MILLISECONDS = 1000;
    /**
     * The seconds a stall is kept.
     */
    static final int LAG_EVENT_RETENTION_SECONDS = 60;
    /**
     * The milliseconds after an executed turn rate change before this machine sends another Turn Rate command.
     */
    static final int TURN_RATE_COMMAND_INTERVAL_MILLISECONDS = 5000;

    /**
     * The state that waits for the scan start ({@link #INITIAL_SCAN_SECONDS}).
     */
    static final int WAITING = 0;
    /**
     * The state after the scan start executed, before the next completed turn step.
     */
    static final int SCAN_STARTED = 1;
    /**
     * The state that counts the completed turn steps of the scan.
     */
    static final int SCANNING = 2;
    /**
     * The state that waits for the scan result.
     */
    static final int SCANNED = 3;
    /**
     * The state that lowers and raises the turn rate.
     */
    static final int ADJUSTING = 4;

    /**
     * The lock word bit of a raise.
     */
    private static final int RAISED = 1;
    /**
     * The lock word bit of a lowering.
     */
    private static final int LOWERED = 2;
    /**
     * The lock word bit of a lowering after a raise and a lowering, which ends the changes.
     */
    private static final int LOCKED = 4;
    private static final int ALL_CHANGES = RAISED | LOWERED | LOCKED;
    private static final int RAISED_AND_LOWERED = RAISED | LOWERED;

    /**
     * The lowest and highest turn rate a Turn Rate command sends.
     */
    private static final int MINIMUM_COMMAND_TURN_RATE = 4;
    private static final int MAXIMUM_COMMAND_TURN_RATE = 24;
    /**
     * The turn rate beyond which the scan table's required rate stays the same.
     */
    private static final int MAXIMUM_REQUIRED_TURN_RATE = 22;
    /**
     * The latency settings of the scan table, Low through Extra High.
     */
    private static final int LATENCY_COUNT = 3;
    /**
     * The Dynamic Turn Rate command's sub-command bytes.
     */
    private static final int SCAN_START = 0;
    private static final int SCAN_RESULT = 1;
    /**
     * The silent Low latency the scan start sets.
     */
    private static final int SILENT_LOW_LATENCY = 0x80;

    private static final long NANOSECONDS_PER_MILLISECOND = 1_000_000L;
    private static final long NANOSECONDS_PER_SECOND = 1_000_000_000L;
    private static final float MILLISECONDS_PER_SECOND = 1000.0f;

    /**
     * The game a {@link DynamicTurnRate} adjusts.
     */
    interface Session {
        /**
         * @return the game's turns per second
         */
        int getTurnRate();

        /**
         * @return the game's latency setting, {@code 0} Low through {@code 2} Extra High
         */
        int getLatency();

        /**
         * @return {@code true} if the local player is the present player of the lowest network id below {@code 8}, {@code false}
         * otherwise
         */
        boolean isLowestPresentPlayer();

        /**
         * Queues a command into the local outgoing turn.
         *
         * @param command the command bytes, type byte first
         */
        void queueCommand(byte @NotNull [] command);
    }

    /**
     * One stall: the time it started and its length, both in nanoseconds; the length is whole milliseconds.
     */
    private static final class LagEvent {
        private final long time;
        private final long duration;

        private LagEvent(long time, long duration) {
            this.time     = time;
            this.duration = duration;
        }
    }

    /**
     * One entry of the scan table: the measured turn steps per second and turn in transit it needs, its latency and turn rate, and the
     * delay of a command at that latency and turn rate in milliseconds.
     */
    private static final class ScanEntry {
        private final float requiredRate;
        private final int latency;
        private final int turnRate;
        private final float delay;

        private ScanEntry(float requiredRate, int latency, int turnRate, float delay) {
            this.requiredRate = requiredRate;
            this.latency      = latency;
            this.turnRate     = turnRate;
            this.delay        = delay;
        }
    }

    /**
     * The count and average length of the stalls of a window.
     */
    private static final class LagStatistics {
        private final int count;
        private final long average;

        private LagStatistics(int count, long average) {
            this.count   = count;
            this.average = average;
        }
    }

    /**
     * Creates the scan table: for each latency, the turn rates from the highest down, each with the turn steps per second and turn in
     * transit it needs (the turn rate, at most {@value #MAXIMUM_REQUIRED_TURN_RATE}, over the turns in transit) and its delay (a turn's
     * milliseconds times the turns in transit).
     *
     * @param providerTurnsInTransit the network's turns in transit
     * @return the entries, latency by latency
     */
    private static @NotNull List<ScanEntry> createScanTable(int providerTurnsInTransit) {
        List<ScanEntry> table = new ArrayList<>(LATENCY_COUNT * TURN_RATES.length);

        for (int latency = 0; latency < LATENCY_COUNT; latency++) {
            float turnsInTransit = Math.max(1, providerTurnsInTransit) + latency;

            for (int index = TURN_RATES.length - 1; index >= 0; index--) {
                int turnRate = TURN_RATES[index];

                table.add(new ScanEntry(Math.min(turnRate, MAXIMUM_REQUIRED_TURN_RATE) / turnsInTransit, latency, turnRate,
                                        MILLISECONDS_PER_SECOND / turnRate * turnsInTransit));
            }
        }

        return table;
    }

    private final @NotNull Session session;
    private final @NotNull LongSupplier clock;
    private final boolean enabled;
    private final int providerTurnsInTransit;
    private final @NotNull List<ScanEntry> scanTable;

    private boolean started;
    private int state;
    /**
     * The lock word: {@link #RAISED}, {@link #LOWERED} and {@link #LOCKED}.
     */
    private int changes;
    private long startTime;
    /**
     * The time of the last turn rate change, sent or executed, or of the scan end.
     */
    private long changeTime;

    private boolean stalled;
    private long stallTime;
    private final @NotNull List<LagEvent> lagEvents;

    private long scanTime;
    private int scanSteps;

    private boolean turnRateChanged;
    private long turnRateChangeTime;

    /**
     * Creates a {@link DynamicTurnRate} instance that does nothing until {@link #start()}.
     *
     * @param session                the game it adjusts
     * @param clock                  the time in nanoseconds
     * @param enabled                whether the game's flags carry {@link LiveGame#DYNAMIC_TURN_RATE_FLAG}; without it only the stalls
     *                               are kept
     * @param providerTurnsInTransit the network's turns in transit the latency adds to
     */
    DynamicTurnRate(@NotNull Session session, @NotNull LongSupplier clock, boolean enabled, int providerTurnsInTransit) {
        this.session                = session;
        this.clock                  = clock;
        this.enabled                = enabled;
        this.providerTurnsInTransit = providerTurnsInTransit;
        this.scanTable              = createScanTable(providerTurnsInTransit);

        this.started    = false;
        this.state      = ADJUSTING;
        this.changes    = 0;
        this.startTime  = 0L;
        this.changeTime = 0L;

        this.stalled   = false;
        this.stallTime = 0L;
        this.lagEvents = new ArrayList<>();

        this.scanTime  = 0L;
        this.scanSteps = 0;

        this.turnRateChanged    = false;
        this.turnRateChangeTime = 0L;
    }

    /**
     * @return the state: {@link #WAITING}, {@link #SCAN_STARTED}, {@link #SCANNING}, {@link #SCANNED} or {@link #ADJUSTING}
     */
    int getState() {
        return this.state;
    }

    /**
     * @return {@code true} if the dynamic turn rate is on and its scan runs (from the scan start to the scan result), {@code false}
     * otherwise
     */
    boolean isScanning() {
        return this.enabled && this.started && this.state >= SCAN_STARTED && this.state <= SCANNED;
    }


    /**
     * Starts the rules at the game start: the scan waits for its start when the dynamic turn rate is on, else the rules only keep the
     * stalls.
     */
    void start() {
        long now = this.clock.getAsLong();

        this.stalled    = false;
        this.changeTime = now;
        this.lagEvents.clear();

        this.started   = true;
        this.changes   = 0;
        this.startTime = now;
        this.state     = this.enabled ? WAITING : ADJUSTING;
    }

    /**
     * Opens a stall when a turn step misses a turn and none is open; at the opening of a stall while adjusting, lowers the turn rate one step
     * when the stalls of the last {@value #LOWERING_WINDOW_SECONDS} seconds, the open one included, number more than
     * {@value #LOWERING_STALL_LIMIT} and last more than {@value #LOWERING_AVERAGE_MILLISECONDS} ms on average.
     */
    void onTurnMissing() {
        if (!this.started || this.stalled) {
            return;
        }

        long now = this.clock.getAsLong();

        this.stalled   = true;
        this.stallTime = now;

        if (!this.enabled || this.state != ADJUSTING) {
            return;
        }

        LagStatistics statistics = this.measureLag(now, LOWERING_WINDOW_SECONDS * NANOSECONDS_PER_SECOND);

        if (Integer.compareUnsigned(statistics.count, LOWERING_STALL_LIMIT) <= 0 ||
            statistics.average <= LOWERING_AVERAGE_MILLISECONDS * NANOSECONDS_PER_MILLISECOND) {
            return;
        }

        int current = this.session.getTurnRate();
        int lower = TURN_RATES[0];

        if (current > lower) {
            for (int index = TURN_RATES.length - 1; index >= 0; index--) {
                if (TURN_RATES[index] < current) {
                    lower = TURN_RATES[index];

                    break;
                }
            }
        } else {
            lower = current;
        }

        if (this.requestTurnRate(lower)) {
            this.changes |= LOWERED;
        }
    }

    /**
     * Closes the open stall after a completed turn step and runs the state: the scan start once {@value #INITIAL_SCAN_SECONDS} seconds
     * passed, the scan's step count and result, or a raise.
     */
    void onTurnReceived() {
        if (!this.started) {
            return;
        }

        long now = this.clock.getAsLong();

        if (this.stalled) {
            this.pruneLagEvents(now);
            this.lagEvents.add(new LagEvent(this.stallTime, (now - this.stallTime) / NANOSECONDS_PER_MILLISECOND * NANOSECONDS_PER_MILLISECOND));

            this.stalled = false;
        }

        switch (this.state) {
            case WAITING -> {
                if (now - this.startTime > INITIAL_SCAN_SECONDS * NANOSECONDS_PER_SECOND) {
                    this.session.queueCommand(new byte[]{(byte) ReplayCommandIds.DYNAMIC_TURN_RATE, SCAN_START, 0, 0});
                }
            }
            case SCAN_STARTED -> {
                this.scanTime = now;
                this.state    = SCANNING;
            }
            case SCANNING -> this.countScanStep(now);
            case ADJUSTING -> this.raiseTurnRate(now);
            default -> {
            }
        }
    }

    private void countScanStep(long now) {
        this.scanSteps++;

        if (now - this.scanTime <= SCAN_SECONDS * NANOSECONDS_PER_SECOND) {
            return;
        }

        this.state      = SCANNED;
        this.changeTime = now;
        this.lagEvents.clear();

        if (!this.session.isLowestPresentPlayer()) {
            return;
        }

        float measured = (float) this.scanSteps / SCAN_SECONDS / (this.session.getLatency() + this.providerTurnsInTransit);
        ScanEntry best = this.scanTable.getLast();

        for (ScanEntry entry : this.scanTable) {
            if (measured >= entry.requiredRate && best.delay > entry.delay) {
                best = entry;
            }
        }

        this.changeTime = this.clock.getAsLong();
        this.lagEvents.clear();

        this.session.queueCommand(new byte[]{(byte) ReplayCommandIds.DYNAMIC_TURN_RATE, SCAN_RESULT, (byte) best.turnRate, (byte) best.latency});
    }

    private void raiseTurnRate(long now) {
        if (!this.enabled || (this.changes & RAISED) != 0 || now - this.changeTime <= RAISING_WINDOW_SECONDS * NANOSECONDS_PER_SECOND) {
            return;
        }

        LagStatistics statistics = this.measureLag(now, RAISING_WINDOW_SECONDS * NANOSECONDS_PER_SECOND);

        if (Integer.compareUnsigned(statistics.count, RAISING_STALL_LIMIT) >= 0 ||
            statistics.average >= RAISING_AVERAGE_MILLISECONDS * NANOSECONDS_PER_MILLISECOND) {
            return;
        }

        int current = this.session.getTurnRate();
        int higher = TURN_RATES[TURN_RATES.length - 1];

        if (current < higher) {
            for (int rate : TURN_RATES) {
                if (rate > current) {
                    higher = rate;

                    break;
                }
            }
        } else {
            higher = current;
        }

        if (this.requestTurnRate(higher)) {
            this.changes |= RAISED;
        }
    }

    /**
     * Executes the Dynamic Turn Rate scan start: when the dynamic turn rate is on and no scan counts or waits for its result, the scan
     * starts over, and the lowest present player sends {@value #SCAN_TURN_RATE} turns per second and a silent Low latency.
     */
    void onScanStart() {
        if (!this.enabled || !this.started || this.state == SCANNING || this.state == SCANNED) {
            return;
        }

        this.state     = SCAN_STARTED;
        this.scanTime  = this.clock.getAsLong();
        this.scanSteps = 0;

        if (this.session.isLowestPresentPlayer()) {
            this.sendTurnRate(SCAN_TURN_RATE, true);
            this.session.queueCommand(new byte[]{(byte) ReplayCommandIds.LATENCY, (byte) SILENT_LOW_LATENCY});
        }
    }

    /**
     * Executes the start of a Dynamic Turn Rate scan result: when the dynamic turn rate is on, the rules adjust from now on.
     */
    void onScanResult() {
        if (this.enabled && this.started) {
            this.state = ADJUSTING;
        }
    }

    /**
     * Notes an executed change of the turn rate: a scan waiting for its result adjusts from now on, the stalls are dropped, and this machine
     * sends no Turn Rate command for {@value #TURN_RATE_COMMAND_INTERVAL_MILLISECONDS} ms.
     */
    void onTurnRateChanged() {
        long now = this.clock.getAsLong();

        if (this.state == SCANNED) {
            this.state = ADJUSTING;
        }

        this.changeTime = now;
        this.lagEvents.clear();

        this.turnRateChanged    = true;
        this.turnRateChangeTime = now;
    }

    /**
     * Requests a turn rate when the changes are not locked and it differs from the game's: sends it as a Turn Rate command, locks the
     * changes on a lowering after a raise and a lowering, and drops the stalls.
     *
     * @param turnRate the requested turns per second
     * @return {@code true} if the turn rate was requested, {@code false} otherwise
     */
    private boolean requestTurnRate(int turnRate) {
        int current = this.session.getTurnRate();

        if ((this.changes & ALL_CHANGES) == ALL_CHANGES || current == turnRate) {
            return false;
        }

        this.sendTurnRate(Math.clamp(turnRate, MINIMUM_COMMAND_TURN_RATE, MAXIMUM_COMMAND_TURN_RATE), false);

        if ((this.changes & RAISED_AND_LOWERED) == RAISED_AND_LOWERED && turnRate < current) {
            this.changes |= LOCKED;
        }

        this.changeTime = this.clock.getAsLong();
        this.lagEvents.clear();

        return true;
    }

    /**
     * Sends a Turn Rate command, unless it is not forced and an executed turn rate change is less than
     * {@value #TURN_RATE_COMMAND_INTERVAL_MILLISECONDS} ms old.
     *
     * @param turnRate the turns per second
     * @param forced   whether the command goes regardless of the last change
     */
    private void sendTurnRate(int turnRate, boolean forced) {
        long elapsed = (this.clock.getAsLong() - this.turnRateChangeTime) / NANOSECONDS_PER_MILLISECOND;

        if (forced || !this.turnRateChanged || elapsed >= TURN_RATE_COMMAND_INTERVAL_MILLISECONDS) {
            this.session.queueCommand(new byte[]{(byte) ReplayCommandIds.TURN_RATE, (byte) turnRate});
        }
    }

    /**
     * Measures the stalls that started within a window before now: their count, plus one while a stall is open, and the average length of
     * the closed ones in whole milliseconds.
     *
     * @param now    the time
     * @param window the window length
     * @return the statistics
     */
    private @NotNull LagStatistics measureLag(long now, long window) {
        this.pruneLagEvents(now);

        int count = 0;
        long total = 0L;

        for (LagEvent event : this.lagEvents) {
            if (event.time > now - window) {
                count++;
                total += event.duration;
            }
        }

        long average = count == 0 ? 0L : total / NANOSECONDS_PER_MILLISECOND / count * NANOSECONDS_PER_MILLISECOND;

        return new LagStatistics(this.stalled ? count + 1 : count, average);
    }

    private void pruneLagEvents(long now) {
        this.lagEvents.removeIf(event -> event.time + LAG_EVENT_RETENTION_SECONDS * NANOSECONDS_PER_SECOND < now);
    }
}
