package org.fogofwarlabs.sc4j.scr.client;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Collects the pacing of a live game for a report once a second: the frames simulated with their average and longest simulation time, the
 * presented frames, and the time the game waited for players' turns with its longest single wait. A wait lasts from the first step that found
 * a turn missing to the next step that runs a frame; a wait still in progress at a report counts up to the report, and its later part counts
 * in the next report. Times are {@link System#nanoTime()} readings supplied by the caller.
 */
final class LivePacingStatistics {
    private static final long NANOSECONDS_PER_SECOND = 1_000_000_000L;
    private static final double NANOSECONDS_PER_MILLISECOND = 1_000_000.0;
    private static final @NotNull String REPORT_FORMAT = "frames %d/s, step avg %.1f ms max %.1f ms, render %d fps, turn waits %d ms/s longest %d ms";

    private long periodStart;
    private int frames;
    private long simulationNanoseconds;
    private long longestSimulationNanoseconds;
    private int presentedFrames;
    private long waitNanoseconds;
    private long longestWaitNanoseconds;

    private boolean waiting;
    private long waitStart;
    /**
     * The start of the current wait's part in the current report period.
     */
    private long waitPeriodStart;

    /**
     * Creates a {@link LivePacingStatistics} instance whose first report period starts at the supplied time.
     *
     * @param now the current {@link System#nanoTime()} reading
     */
    LivePacingStatistics(long now) {
        this.periodStart                  = now;
        this.frames                       = 0;
        this.simulationNanoseconds        = 0;
        this.longestSimulationNanoseconds = 0;
        this.presentedFrames              = 0;
        this.waitNanoseconds              = 0;
        this.longestWaitNanoseconds       = 0;

        this.waiting         = false;
        this.waitStart       = 0;
        this.waitPeriodStart = 0;
    }

    /**
     * Records a simulated frame.
     *
     * @param simulationNanoseconds the time the frame's simulation took
     */
    void recordFrame(long simulationNanoseconds) {
        this.frames++;
        this.simulationNanoseconds += simulationNanoseconds;
        this.longestSimulationNanoseconds = Math.max(this.longestSimulationNanoseconds, simulationNanoseconds);
    }

    /**
     * Records a presented frame.
     */
    void recordPresentedFrame() {
        this.presentedFrames++;
    }

    /**
     * Starts a turn wait, unless one is in progress.
     *
     * @param now the current {@link System#nanoTime()} reading
     */
    void beginTurnWait(long now) {
        if (this.waiting) {
            return;
        }

        this.waiting         = true;
        this.waitStart       = now;
        this.waitPeriodStart = now;
    }

    /**
     * Ends the turn wait in progress, if any, and counts its time.
     *
     * @param now the current {@link System#nanoTime()} reading
     */
    void endTurnWait(long now) {
        if (!this.waiting) {
            return;
        }

        this.waitNanoseconds += now - this.waitPeriodStart;
        this.longestWaitNanoseconds = Math.max(this.longestWaitNanoseconds, now - this.waitStart);
        this.waiting                = false;
    }

    /**
     * Takes the report of the period since the previous report once it lasted at least a second, and starts the next period.
     *
     * @param now the current {@link System#nanoTime()} reading
     * @return the report, for example {@code frames 24/s, step avg 3.2 ms max 11.5 ms, render 58 fps, turn waits 140 ms/s longest 62 ms},
     * or {@code null} if the period lasted less than a second
     */
    @Nullable String takeReport(long now) {
        long period = now - this.periodStart;

        if (period < NANOSECONDS_PER_SECOND) {
            return null;
        }

        if (this.waiting) {
            this.waitNanoseconds += now - this.waitPeriodStart;
            this.longestWaitNanoseconds = Math.max(this.longestWaitNanoseconds, now - this.waitStart);
            this.waitPeriodStart        = now;
        }

        double seconds = period / (double) NANOSECONDS_PER_SECOND;
        double averageSimulation = this.frames == 0 ? 0 : this.simulationNanoseconds / NANOSECONDS_PER_MILLISECOND / this.frames;
        String report = String.format(Locale.ROOT, REPORT_FORMAT,
                                      Math.round(this.frames / seconds),
                                      averageSimulation, this.longestSimulationNanoseconds / NANOSECONDS_PER_MILLISECOND,
                                      Math.round(this.presentedFrames / seconds),
                                      Math.round(this.waitNanoseconds / NANOSECONDS_PER_MILLISECOND / seconds),
                                      Math.round(this.longestWaitNanoseconds / NANOSECONDS_PER_MILLISECOND));

        this.periodStart                  = now;
        this.frames                       = 0;
        this.simulationNanoseconds        = 0;
        this.longestSimulationNanoseconds = 0;
        this.presentedFrames              = 0;
        this.waitNanoseconds              = 0;
        this.longestWaitNanoseconds       = 0;

        return report;
    }
}
