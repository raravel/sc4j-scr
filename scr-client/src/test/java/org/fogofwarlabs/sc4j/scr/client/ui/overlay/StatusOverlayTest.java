package org.fogofwarlabs.sc4j.scr.client.ui.overlay;

import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.client.input.KeyModifier;
import org.fogofwarlabs.sc4j.scr.client.configuration.InterfaceSettings;
import org.fogofwarlabs.sc4j.scr.client.ui.input.GameAccelerators;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks the status overlay's texts, their conditions and line positions, the frame rate count and the keys that turn
 * the texts over in each accelerator table.
 */
final class StatusOverlayTest {
    /**
     * The maximum glyph height of font8, the game set's small font.
     */
    private static final int SMALL_FONT_HEIGHT = 8;
    /**
     * The frame rate line: one small font height below the turn rate line at the interface top 10.
     */
    private static final int FRAME_RATE_TOP = 18;
    private static final int FRAMES_PER_SECOND = 60;
    /**
     * A frame rate above a thousand, printed without grouping.
     */
    private static final int HIGH_FRAMES_PER_SECOND = 1234;
    private static final int TURN_RATE = 24;
    private static final int EXTRA_HIGH_LATENCY = 2;
    private static final int UNKNOWN_LATENCY = 5;
    private static final long START_NANOSECONDS = 5_000_000_000L;
    /**
     * A present interval of 60 presents per second, rounded up: 60 intervals last a little more than one second.
     */
    private static final long PRESENT_NANOSECONDS = 16_666_667L;
    private static final long ONE_SECOND_NANOSECONDS = 1_000_000_000L;
    /**
     * A stall of three seconds without a present.
     */
    private static final long STALL_NANOSECONDS = 3_000_000_000L;
    /**
     * More presents in one second than the first capacity of the present times.
     */
    private static final int MANY_PRESENTS = 1000;
    /**
     * The accelerators that turn the frame rate and the turn rate over, and the identifier of no accelerator.
     */
    private static final int SHOW_FPS = 0x42e;
    private static final int SHOW_TURN_RATE = 0x42d;
    private static final int NO_ACCELERATOR = -1;

    @Test
    void create_frame_rate_text_should_format_the_count_only_while_shown() {
        InterfaceSettings settings = new InterfaceSettings();

        Assertions.assertNull(StatusOverlay.createFrameRateText(settings, FRAMES_PER_SECOND));

        settings.setFrameRateShown(true);

        Assertions.assertEquals("FPS: 60", StatusOverlay.createFrameRateText(settings, FRAMES_PER_SECOND));
        Assertions.assertEquals("FPS: 1234", StatusOverlay.createFrameRateText(settings, HIGH_FRAMES_PER_SECOND));
    }

    @Test
    void create_turn_rate_text_should_show_the_turn_rate_only_in_a_network_game() {
        InterfaceSettings settings = new InterfaceSettings();

        Assertions.assertNull(StatusOverlay.createTurnRateText(settings, true, TURN_RATE, "Low", ""));

        settings.setTurnRateShown(true);

        Assertions.assertEquals("TR: 24 | Low", StatusOverlay.createTurnRateText(settings, true, TURN_RATE, "Low", ""));
        Assertions.assertNull(StatusOverlay.createTurnRateText(settings, false, TURN_RATE, "Low", ""), "no turn rate outside a network game");
    }

    @Test
    void create_turn_rate_text_should_show_the_scan_text_instead_while_scanning() {
        InterfaceSettings settings = new InterfaceSettings();

        settings.setTurnRateShown(true);

        Assertions.assertEquals("Calculating TR...", StatusOverlay.createTurnRateText(settings, true, TURN_RATE, "Low", "Calculating TR..."));
        Assertions.assertEquals("bnet_dtr_scanInProgress", StatusOverlay.getScanKey());
    }

    @Test
    void get_latency_key_should_name_each_latency_and_use_the_highest_above_it() {
        Assertions.assertEquals("bnet_latency_low", StatusOverlay.getLatencyKey(0));
        Assertions.assertEquals("bnet_latency_high", StatusOverlay.getLatencyKey(1));
        Assertions.assertEquals("bnet_latency_extraHigh", StatusOverlay.getLatencyKey(EXTRA_HIGH_LATENCY));
        Assertions.assertEquals("bnet_latency_extraHigh", StatusOverlay.getLatencyKey(UNKNOWN_LATENCY));
    }

    @Test
    void get_frame_rate_top_should_be_one_font_height_below_the_turn_rate_line() {
        Assertions.assertEquals(FRAME_RATE_TOP, StatusOverlay.getFrameRateTop(SMALL_FONT_HEIGHT));
    }

    @Test
    void record_present_should_count_the_presents_of_the_trailing_second_on_every_present() {
        StatusOverlay overlay = new StatusOverlay();
        long time = START_NANOSECONDS;

        Assertions.assertEquals(0, overlay.getFramesPerSecond());

        overlay.recordPresent(time);

        Assertions.assertEquals(1, overlay.getFramesPerSecond(), "the present just recorded counts");

        for (int present = 1; present < FRAMES_PER_SECOND; present++) {
            time += PRESENT_NANOSECONDS;
            overlay.recordPresent(time);

            Assertions.assertEquals(present + 1, overlay.getFramesPerSecond(), "updated on every present");
        }

        time += PRESENT_NANOSECONDS;
        overlay.recordPresent(time);

        Assertions.assertEquals(FRAMES_PER_SECOND, overlay.getFramesPerSecond(), "the first present is more than one second old");

        time += STALL_NANOSECONDS;
        overlay.recordPresent(time);

        Assertions.assertEquals(1, overlay.getFramesPerSecond(), "a stall drops every older present");
    }

    @Test
    void record_present_should_keep_a_present_exactly_one_second_old() {
        StatusOverlay overlay = new StatusOverlay();

        overlay.recordPresent(START_NANOSECONDS);
        overlay.recordPresent(START_NANOSECONDS + ONE_SECOND_NANOSECONDS);

        Assertions.assertEquals(2, overlay.getFramesPerSecond());

        overlay.recordPresent(START_NANOSECONDS + ONE_SECOND_NANOSECONDS + 1);

        Assertions.assertEquals(2, overlay.getFramesPerSecond());
    }

    @Test
    void record_present_should_count_more_presents_than_the_first_capacity() {
        StatusOverlay overlay = new StatusOverlay();
        long interval = ONE_SECOND_NANOSECONDS / MANY_PRESENTS;

        for (int present = 0; present <= MANY_PRESENTS; present++) {
            overlay.recordPresent(START_NANOSECONDS + present * interval);
        }

        Assertions.assertEquals(MANY_PRESENTS + 1, overlay.getFramesPerSecond());

        overlay.recordPresent(START_NANOSECONDS + (MANY_PRESENTS + 1) * interval);

        Assertions.assertEquals(MANY_PRESENTS + 1, overlay.getFramesPerSecond(), "the oldest present left the second");
    }

    @Test
    void find_toggle_should_match_control_alt_f_and_control_alt_t_in_the_play_and_chat_tables() {
        int controlAlt = KeyModifier.CONTROL.getMask() | KeyModifier.ALT.getMask();

        for (GameAccelerators.Mode mode : new GameAccelerators.Mode[]{GameAccelerators.Mode.PLAY, GameAccelerators.Mode.CHAT}) {
            Assertions.assertEquals(SHOW_FPS, StatusOverlay.findToggle(mode, Key.F, controlAlt, false), mode.name());
            Assertions.assertEquals(SHOW_TURN_RATE, StatusOverlay.findToggle(mode, Key.T, controlAlt, false), mode.name());
            Assertions.assertEquals(NO_ACCELERATOR, StatusOverlay.findToggle(mode, Key.F, controlAlt, true), "no repeat");
            Assertions.assertEquals(NO_ACCELERATOR, StatusOverlay.findToggle(mode, Key.F, KeyModifier.CONTROL.getMask(), false));
            Assertions.assertEquals(NO_ACCELERATOR, StatusOverlay.findToggle(mode, Key.M, KeyModifier.CONTROL.getMask(), false),
                                    "other accelerators stay with the screens");
        }
    }

    @Test
    void find_toggle_should_match_only_control_alt_f_in_the_popup_table() {
        int controlAlt = KeyModifier.CONTROL.getMask() | KeyModifier.ALT.getMask();

        Assertions.assertEquals(SHOW_FPS, StatusOverlay.findToggle(GameAccelerators.Mode.POPUP, Key.F, controlAlt, false));
        Assertions.assertEquals(NO_ACCELERATOR, StatusOverlay.findToggle(GameAccelerators.Mode.POPUP, Key.T, controlAlt, false),
                                "a game popup or a trigger pause ignores Ctrl+Alt+T");
    }
}
