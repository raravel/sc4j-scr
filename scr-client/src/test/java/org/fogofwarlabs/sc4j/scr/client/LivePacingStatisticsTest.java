package org.fogofwarlabs.sc4j.scr.client;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies the live pacing report: frames, simulation times and presented frames per second, and the turn waits per second with the longest
 * single wait, a wait in progress counted up to the report and its rest in the next one.
 */
final class LivePacingStatisticsTest {
    private static final long MILLISECOND = 1_000_000L;
    private static final long PERIOD_START = 5_000 * MILLISECOND;
    /**
     * The frames of a two-second period, at 24 frames per second.
     */
    private static final int TWO_SECOND_FRAMES = 48;

    @Test
    void take_report_should_count_frames_presents_and_turn_waits_of_each_second() {
        LivePacingStatistics statistics = new LivePacingStatistics(PERIOD_START);

        statistics.recordFrame(2 * MILLISECOND);
        statistics.recordFrame(4 * MILLISECOND);
        statistics.recordPresentedFrame();
        statistics.recordPresentedFrame();
        statistics.recordPresentedFrame();

        // A wait of 60 ms; a second begin while waiting changes nothing.
        statistics.beginTurnWait(PERIOD_START + 100 * MILLISECOND);
        statistics.beginTurnWait(PERIOD_START + 150 * MILLISECOND);
        statistics.endTurnWait(PERIOD_START + 160 * MILLISECOND);
        statistics.endTurnWait(PERIOD_START + 170 * MILLISECOND);

        // A wait still in progress at the report.
        statistics.beginTurnWait(PERIOD_START + 900 * MILLISECOND);

        Assertions.assertNull(statistics.takeReport(PERIOD_START + 999 * MILLISECOND));
        Assertions.assertEquals("frames 2/s, step avg 3.0 ms max 4.0 ms, render 3 fps, turn waits 160 ms/s longest 100 ms",
                                statistics.takeReport(PERIOD_START + 1000 * MILLISECOND));

        statistics.endTurnWait(PERIOD_START + 1050 * MILLISECOND);

        Assertions.assertEquals("frames 0/s, step avg 0.0 ms max 0.0 ms, render 0 fps, turn waits 50 ms/s longest 150 ms",
                                statistics.takeReport(PERIOD_START + 2000 * MILLISECOND));
    }

    @Test
    void take_report_should_scale_counts_of_a_longer_period_to_one_second() {
        LivePacingStatistics statistics = new LivePacingStatistics(PERIOD_START);

        for (int frame = 0; frame < TWO_SECOND_FRAMES; frame++) {
            statistics.recordFrame(MILLISECOND);
        }

        statistics.beginTurnWait(PERIOD_START);
        statistics.endTurnWait(PERIOD_START + 400 * MILLISECOND);

        Assertions.assertEquals("frames 24/s, step avg 1.0 ms max 1.0 ms, render 0 fps, turn waits 200 ms/s longest 400 ms",
                                statistics.takeReport(PERIOD_START + 2000 * MILLISECOND));
    }
}
