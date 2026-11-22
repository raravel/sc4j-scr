package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.scr.client.configuration.ClientOptions;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies the replay viewer's playback speeds, the frames due at a speed, the live frame pacing with its catch-up after a slow pass or a turn
 * wait, and its seek requests, seek options and Replay Seek command checks.
 */
final class ReplayPlaybackTest {
    /**
     * The Fastest frame duration in milliseconds.
     */
    private static final int FASTEST_FRAME_DURATION = 42;
    /**
     * Host times: one Fastest frame, slightly less than one, and one millisecond.
     */
    private static final double FRAME_SECONDS = 0.042;
    private static final double SHORT_FRAME_SECONDS = 0.041;
    private static final double MILLISECOND_SECONDS = 0.001;
    /**
     * Host times at a quarter speed: a part of a frame, and the rest that completes it.
     */
    private static final double QUARTER_SPEED_PART_SECONDS = 0.100;
    private static final double QUARTER_SPEED_REST_SECONDS = 0.068;
    /**
     * The frames one Fastest frame time holds at double speed and at the largest factor.
     */
    private static final int DOUBLE_SPEED_FRAMES = 2;
    private static final int LARGEST_FACTOR_FRAMES = 16;
    /**
     * The Slow Down presses that reach the slowest playback speed from unlimited playback.
     */
    private static final int SLOW_DOWN_PRESSES = 10;

    /**
     * Seek requests from a completed frame: a forward target and a negative target.
     */
    private static final int COMPLETED_FRAME = 100;
    private static final int FORWARD_SEEK_TARGET = 500;
    private static final int NEGATIVE_SEEK_TARGET = -5;
    /**
     * The seek frame of the command-line options.
     */
    private static final int OPTION_SEEK_FRAME = 1200;

    /**
     * Replay Seek checks from a completed frame of a replay: targets just under and at a whole second ahead at Fastest, an
     * earlier target, a target past the end, and a completed frame near the end.
     */
    private static final int SEEK_COMPLETED_FRAME = 1000;
    private static final int SEEK_TOTAL_FRAMES = 5000;
    private static final int UNDER_SECOND_SEEK_TARGET = 1047;
    private static final int SECOND_SEEK_TARGET = 1048;
    private static final int EARLIER_SEEK_TARGET = 100;
    private static final int PAST_END_SEEK_TARGET = 9000;
    private static final int NEAR_END_COMPLETED_FRAME = 4990;
    /**
     * The recorded game speeds of the Replay Seek checks: Fastest and Slowest.
     */
    private static final int FASTEST_SPEED = 6;
    private static final int SLOWEST_SPEED = 0;

    /**
     * A live game at {@code #x5}: one millisecond frames, the Fastest lag limit, a slow pass that owes some frames, and a pass beyond the limit.
     */
    private static final int SHORT_LIVE_FRAME_DURATION = 1;
    private static final int FASTEST_LAG_LIMIT = 126;
    private static final double SLOW_PASS_SECONDS = 0.020;
    private static final int SLOW_PASS_FRAMES = 20;
    private static final double LATE_PASS_SECONDS = 0.200;
    /**
     * The most live frames one pass runs.
     */
    private static final int MAXIMUM_LIVE_FRAMES_PER_PASS = 4;
    /**
     * Live passes at Fastest: one that owes two frames, one that owes three within the lag limit, and one beyond it.
     */
    private static final double TWO_FRAME_PASS_SECONDS = 0.100;
    private static final double THREE_FRAME_PASS_SECONDS = 0.150;
    private static final double LAGGING_PASS_SECONDS = 0.170;
    private static final int TWO_FRAMES = 2;
    private static final int THREE_FRAMES = 3;
    /**
     * A pass shorter than a Fastest frame.
     */
    private static final double SHORT_PASS_SECONDS = 0.010;
    /**
     * A live game drawn every 16 ms for a second, whose turns stop after frame 5 from 200 ms to 300 ms, shorter than the lag limit.
     */
    private static final int PASS_MILLISECONDS = 16;
    private static final int RUN_MILLISECONDS = 1000;
    private static final int WAIT_START_MILLISECONDS = 200;
    private static final int WAIT_END_MILLISECONDS = 300;
    private static final int FRAMES_BEFORE_WAIT = 5;
    private static final double MILLISECONDS_PER_SECOND = 1000;

    @Test
    void parse_speed_should_accept_speeds_beyond_fastest_and_reject_unknown_values() {
        Assertions.assertEquals("x1/4", new ReplayPlayback(ReplayPlayback.parseSpeed("x1/4")).getSpeedLabel());
        Assertions.assertEquals("x2", new ReplayPlayback(ReplayPlayback.parseSpeed("2")).getSpeedLabel());
        Assertions.assertEquals("x16", new ReplayPlayback(ReplayPlayback.parseSpeed("X16")).getSpeedLabel());
        Assertions.assertTrue(new ReplayPlayback(ReplayPlayback.parseSpeed("max")).isUnlimited());
        Assertions.assertThrows(IllegalArgumentException.class, () -> ReplayPlayback.parseSpeed("x3"));
    }

    @Test
    void parse_speed_should_reject_unlimited_label_without_its_first_letter() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> ReplayPlayback.parseSpeed("ax"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ReplayPlayback.parseSpeed("xax"));
        Assertions.assertTrue(new ReplayPlayback(ReplayPlayback.parseSpeed("MAX")).isUnlimited());
    }

    @Test
    void take_due_frames_should_convert_host_time_at_selected_speed() {
        ReplayPlayback playback = new ReplayPlayback(ReplayPlayback.getNormalSpeedIndex());

        Assertions.assertEquals(1, playback.takeDueFrames(FRAME_SECONDS, FASTEST_FRAME_DURATION));
        Assertions.assertEquals(0, playback.takeDueFrames(SHORT_FRAME_SECONDS, FASTEST_FRAME_DURATION));
        Assertions.assertEquals(1, playback.takeDueFrames(MILLISECOND_SECONDS, FASTEST_FRAME_DURATION));

        playback.increaseSpeed();

        Assertions.assertEquals("x2", playback.getSpeedLabel());
        Assertions.assertEquals(DOUBLE_SPEED_FRAMES, playback.takeDueFrames(FRAME_SECONDS, FASTEST_FRAME_DURATION));

        playback.increaseSpeed();
        playback.increaseSpeed();
        playback.increaseSpeed();

        Assertions.assertEquals(LARGEST_FACTOR_FRAMES, playback.takeDueFrames(FRAME_SECONDS, FASTEST_FRAME_DURATION));

        playback.increaseSpeed();
        playback.increaseSpeed();

        Assertions.assertTrue(playback.isUnlimited());
        Assertions.assertEquals(Integer.MAX_VALUE, playback.takeDueFrames(MILLISECOND_SECONDS, FASTEST_FRAME_DURATION));

        for (int step = 0; step < SLOW_DOWN_PRESSES; step++) {
            playback.decreaseSpeed();
        }

        Assertions.assertEquals("x1/4", playback.getSpeedLabel());
        Assertions.assertEquals(0, playback.takeDueFrames(QUARTER_SPEED_PART_SECONDS, FASTEST_FRAME_DURATION));
        Assertions.assertEquals(1, playback.takeDueFrames(QUARTER_SPEED_REST_SECONDS, FASTEST_FRAME_DURATION));
    }

    @Test
    void take_live_due_frames_should_run_owed_frames_in_one_pass_and_restart_the_schedule_only_beyond_the_lag_limit() {
        ReplayPlayback playback = new ReplayPlayback(ReplayPlayback.getNormalSpeedIndex());

        Assertions.assertEquals(TWO_FRAMES, playback.takeLiveDueFrames(TWO_FRAME_PASS_SECONDS, FASTEST_FRAME_DURATION, FASTEST_LAG_LIMIT));
        // 16 ms are left over: 166 ms owe three frames, 124 ms behind, within the lag limit.
        Assertions.assertEquals(THREE_FRAMES, playback.takeLiveDueFrames(THREE_FRAME_PASS_SECONDS, FASTEST_FRAME_DURATION, FASTEST_LAG_LIMIT));
        // 40 ms are left over: 210 ms are 168 ms behind, beyond the lag limit; the schedule restarts with one frame due.
        Assertions.assertEquals(1, playback.takeLiveDueFrames(LAGGING_PASS_SECONDS, FASTEST_FRAME_DURATION, FASTEST_LAG_LIMIT));
        Assertions.assertEquals(0, playback.takeLiveDueFrames(0, FASTEST_FRAME_DURATION, FASTEST_LAG_LIMIT));
    }

    @Test
    void take_live_due_frames_should_run_at_most_the_bound_per_pass_and_keep_the_rest_owed() {
        ReplayPlayback playback = new ReplayPlayback(ReplayPlayback.getNormalSpeedIndex());
        int frames = 0;
        int passes = 0;

        // A slow pass owes twenty short frames, within the lag limit of the speed setting: the passes after it run them four at a time.
        for (double elapsed = SLOW_PASS_SECONDS; ; elapsed = 0) {
            int dueFrames = playback.takeLiveDueFrames(elapsed, SHORT_LIVE_FRAME_DURATION, FASTEST_LAG_LIMIT);

            if (dueFrames == 0) {
                break;
            }

            Assertions.assertTrue(dueFrames <= MAXIMUM_LIVE_FRAMES_PER_PASS, "frames of one pass: " + dueFrames);

            frames += dueFrames;
            passes++;
        }

        Assertions.assertTrue(frames >= SLOW_PASS_FRAMES - 1 && frames <= SLOW_PASS_FRAMES, "frames run after the slow pass: " + frames);
        Assertions.assertTrue(passes <= SLOW_PASS_FRAMES / MAXIMUM_LIVE_FRAMES_PER_PASS + 1, "passes: " + passes);

        // A pass beyond the lag limit restarts the schedule with one frame due.
        Assertions.assertEquals(1, playback.takeLiveDueFrames(LATE_PASS_SECONDS, SHORT_LIVE_FRAME_DURATION, FASTEST_LAG_LIMIT));
        Assertions.assertEquals(0, playback.takeLiveDueFrames(0, SHORT_LIVE_FRAME_DURATION, FASTEST_LAG_LIMIT));
    }

    @Test
    void refund_live_frames_should_keep_the_owed_time_of_frames_that_did_not_run() {
        ReplayPlayback playback = new ReplayPlayback(ReplayPlayback.getNormalSpeedIndex());

        Assertions.assertEquals(1, playback.takeLiveDueFrames(FRAME_SECONDS, FASTEST_FRAME_DURATION, FASTEST_LAG_LIMIT));

        // The frame waited for a turn: it is due again in the next pass, however short.
        playback.refundLiveFrames(1);

        Assertions.assertEquals(1, playback.takeLiveDueFrames(SHORT_PASS_SECONDS, FASTEST_FRAME_DURATION, FASTEST_LAG_LIMIT));
        Assertions.assertEquals(0, playback.takeLiveDueFrames(SHORT_PASS_SECONDS, FASTEST_FRAME_DURATION, FASTEST_LAG_LIMIT));

        // A wait beyond the lag limit restarts the schedule with one frame due.
        playback.refundLiveFrames(1);

        Assertions.assertEquals(1, playback.takeLiveDueFrames(LATE_PASS_SECONDS, FASTEST_FRAME_DURATION, FASTEST_LAG_LIMIT));
    }

    @Test
    void live_pacing_catches_up_after_a_short_turn_wait_without_losing_frames() {
        int[] waiting = this.runLivePasses(true);
        int[] steady = this.runLivePasses(false);

        Assertions.assertEquals(FRAMES_BEFORE_WAIT, waiting[1], "frames when the wait ends");
        Assertions.assertTrue(waiting[2] >= TWO_FRAMES, "frames of the pass after the wait: " + waiting[2]);
        Assertions.assertEquals(steady[0], waiting[0], "a wait shorter than the lag limit loses no frame");
    }

    /**
     * Runs a second of live passes as the client does: each pass takes its due frames and runs those whose turn arrived, giving the time of the
     * others back.
     *
     * @param wait whether the turns after frame {@link #FRAMES_BEFORE_WAIT} arrive only at {@link #WAIT_END_MILLISECONDS}
     * @return the frames run in the second, the frames run before the wait ended, and the frames of the first pass after it
     */
    private int @NotNull [] runLivePasses(boolean wait) {
        ReplayPlayback playback = new ReplayPlayback(ReplayPlayback.getNormalSpeedIndex());
        int frames = 0;
        int framesBeforeWaitEnd = 0;
        int firstPassAfterWait = 0;

        for (int now = PASS_MILLISECONDS; now <= RUN_MILLISECONDS; now += PASS_MILLISECONDS) {
            boolean turnsHeld = wait && now >= WAIT_START_MILLISECONDS && now < WAIT_END_MILLISECONDS;
            int dueFrames = playback.takeLiveDueFrames(PASS_MILLISECONDS / MILLISECONDS_PER_SECOND, FASTEST_FRAME_DURATION, FASTEST_LAG_LIMIT);
            int ran = 0;

            while (ran < dueFrames && (!turnsHeld || frames < FRAMES_BEFORE_WAIT)) {
                frames++;
                ran++;
            }

            playback.refundLiveFrames(dueFrames - ran);

            if (now < WAIT_END_MILLISECONDS) {
                framesBeforeWaitEnd = frames;
            } else if (now - PASS_MILLISECONDS < WAIT_END_MILLISECONDS) {
                firstPassAfterWait = ran;
            }
        }

        return new int[]{frames, framesBeforeWaitEnd, firstPassAfterWait};
    }

    @Test
    void request_seek_should_restart_only_for_backward_seeks() {
        ReplayPlayback playback = new ReplayPlayback(ReplayPlayback.getNormalSpeedIndex());

        Assertions.assertEquals(-1, playback.getSeekTarget());

        playback.requestSeek(FORWARD_SEEK_TARGET, COMPLETED_FRAME);

        Assertions.assertEquals(FORWARD_SEEK_TARGET, playback.getSeekTarget());
        Assertions.assertFalse(playback.takeSeekRestart());

        playback.requestSeek(NEGATIVE_SEEK_TARGET, COMPLETED_FRAME);

        Assertions.assertEquals(0, playback.getSeekTarget());
        Assertions.assertTrue(playback.takeSeekRestart());
        Assertions.assertFalse(playback.takeSeekRestart());

        playback.finishSeek();

        Assertions.assertEquals(-1, playback.getSeekTarget());
    }

    @Test
    void parse_should_read_speed_and_seek_options_and_reject_invalid_seek() {
        ClientOptions options = ClientOptions.parse(new String[]{"--replay", "a.rep", "--speed", "x8", "--seek", "1200"});

        Assertions.assertEquals(OPTION_SEEK_FRAME, options.getSeekFrame());
        Assertions.assertEquals("x8", new ReplayPlayback(options.getSpeedIndex()).getSpeedLabel());
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--seek", "5"}));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ClientOptions.parse(new String[]{"--replay", "a.rep", "--seek", "-1"}));
    }

    @Test
    void can_send_replay_seek_should_accept_only_beyond_one_second_and_after_previous_seek() {
        ReplayPlayback playback = new ReplayPlayback(ReplayPlayback.getNormalSpeedIndex());

        // At Fastest (42 ms): 47 frames are 1974 ms, one whole second; 48 frames are 2016 ms.
        Assertions.assertFalse(playback.canSendReplaySeek(UNDER_SECOND_SEEK_TARGET, SEEK_COMPLETED_FRAME, SEEK_TOTAL_FRAMES, FASTEST_SPEED));
        Assertions.assertTrue(playback.canSendReplaySeek(SECOND_SEEK_TARGET, SEEK_COMPLETED_FRAME, SEEK_TOTAL_FRAMES, FASTEST_SPEED));
        Assertions.assertEquals(-1, playback.getSeekTarget(), "The command's run requests the seek.");
        Assertions.assertTrue(playback.canSendReplaySeek(EARLIER_SEEK_TARGET, SEEK_COMPLETED_FRAME, SEEK_TOTAL_FRAMES, FASTEST_SPEED),
                              "An earlier frame is sent too.");

        // The target is clamped to the frame count: 10 frames at Fastest are less than a second.
        Assertions.assertFalse(playback.canSendReplaySeek(PAST_END_SEEK_TARGET, NEAR_END_COMPLETED_FRAME, SEEK_TOTAL_FRAMES, FASTEST_SPEED));
        Assertions.assertTrue(playback.canSendReplaySeek(PAST_END_SEEK_TARGET, SEEK_COMPLETED_FRAME, SEEK_TOTAL_FRAMES, SLOWEST_SPEED));

        playback.requestSeek(SECOND_SEEK_TARGET, SEEK_COMPLETED_FRAME);

        // The previous target is not reached yet: no second seek.
        Assertions.assertFalse(playback.canSendReplaySeek(EARLIER_SEEK_TARGET, SEEK_COMPLETED_FRAME, SEEK_TOTAL_FRAMES, FASTEST_SPEED));

        playback.finishSeek();

        Assertions.assertTrue(playback.canSendReplaySeek(EARLIER_SEEK_TARGET, SEEK_COMPLETED_FRAME, SEEK_TOTAL_FRAMES, FASTEST_SPEED));
    }
}
