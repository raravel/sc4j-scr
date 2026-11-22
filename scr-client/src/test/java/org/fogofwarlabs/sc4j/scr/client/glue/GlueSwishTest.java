package org.fogofwarlabs.sc4j.scr.client.glue;

import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceTimers;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks the panel slide of the glue screens: a panel held while the screen opens stays out and later slides in alone, a panel slides out
 * alone with a doubling step and back in from where it is, and the controls attached to a panel move with it while the other panels and the
 * screen's lock stay as they are. Each panel keeps its own motion: a panel that slid out stays out while another slides, and an entering
 * panel keeps entering while another slides out.
 */
final class GlueSwishTest {
    private static final int INTERFACE_WIDTH = 640;
    /**
     * A pump interval that fires the 25-millisecond timer once.
     */
    private static final int PUMP_MILLISECONDS = 26;
    private static final int TICK_LIMIT = 100;

    private static final int FRAME_PANEL = 1;
    private static final int PANE_PANEL = 11;
    private static final int PANE_CONTENT = 17;
    private static final int PANE_X = 10;
    private static final int PANE_WIDTH = 300;
    /**
     * The panels and their directions: the frame from the right, the pane from the left.
     */
    private static final int @NotNull [] ENTRIES = {FRAME_PANEL, 2, PANE_PANEL, 0};
    /**
     * The pane's offset off-screen to the left of a dialog at the interface's left edge.
     */
    private static final int PANE_OUT_OFFSET = -(PANE_X + PANE_WIDTH);
    /**
     * The pane's offsets after each tick of its slide out: the step starts at {@code 1} and doubles.
     */
    private static final int @NotNull [] LEAVING_OFFSETS = {-1, -3, -7, -15, -31, -63, -127, -255, -511, -1023};
    /**
     * The pane's offsets after each tick of its slide in from {@link #PANE_OUT_OFFSET}: a quarter of the remaining distance, all of it below four.
     */
    private static final int @NotNull [] ENTERING_OFFSETS = {-233, -175, -132, -99, -75, -57, -43, -33, -25, -19, -15, -12, -9, -7, -6, -5, -4, -3, 0};
    /**
     * The frame's offsets after each tick of its slide out to the right; it stops after the step it takes from outside the screen.
     */
    private static final int @NotNull [] FRAME_LEAVING_OFFSETS = {1, 3, 7, 15, 31, 63, 127, 255, 511, 511};

    private static @NotNull DialogDefinition createDialog() {
        Set<String> flags = Set.of("WS_VISIBLE");

        return new DialogDefinition("test", 0, 0, INTERFACE_WIDTH, GlueCanvas.INTERFACE_HEIGHT, List.of(
                ScrDialogControlUtils.createControl(ScrControlType.RECT, FRAME_PANEL, 400, 20, 200, 300, flags, "", List.of()),
                ScrDialogControlUtils.createControl(ScrControlType.RECT, PANE_PANEL, PANE_X, 250, PANE_WIDTH, 200, flags, "", List.of()),
                ScrDialogControlUtils.createControl(ScrControlType.RECT, PANE_CONTENT, 20, 260, 280, 100, flags, "", List.of())
        ));
    }

    private final @NotNull GlueSwish swish;
    private final @NotNull InterfaceTimers timers;
    private final @NotNull List<GlueAudio.Sound> sounds;
    private int time;

    GlueSwishTest() {
        this.swish  = new GlueSwish();
        this.timers = new InterfaceTimers();
        this.sounds = new ArrayList<>();
        this.time   = 0;
    }

    /**
     * Runs one tick of the swish timer.
     */
    private void tick() {
        this.swish.update(this.time, INTERFACE_WIDTH, this.sounds::add, this.timers);
        this.time += PUMP_MILLISECONDS;
        this.timers.pump(this.time);
    }

    /**
     * Runs ticks until the timer stops.
     */
    private void settle() {
        for (int count = 0; count < TICK_LIMIT && this.swish.isMoving(); count++) {
            this.tick();
        }

        Assertions.assertFalse(this.swish.isMoving());
    }

    /**
     * Opens the screen with the pane held and slides it in once the frame locked.
     */
    private void openHeld() {
        this.swish.attach(PANE_CONTENT, PANE_PANEL);
        this.swish.startEntering(createDialog(), ENTRIES, 0, 0, INTERFACE_WIDTH);
        this.swish.holdPanel(PANE_PANEL);
        this.settle();
    }

    @Test
    void a_panel_held_at_open_stays_out_and_then_slides_in_alone() {
        this.openHeld();

        Assertions.assertEquals(GlueSwish.Phase.LOCKED, this.swish.getPhase());
        Assertions.assertEquals(0, this.swish.getOffsetX(FRAME_PANEL));
        Assertions.assertEquals(PANE_OUT_OFFSET, this.swish.getOffsetX(PANE_PANEL));
        Assertions.assertFalse(this.swish.isPanelOnScreen(PANE_PANEL));
        Assertions.assertEquals(List.of(GlueAudio.Sound.SWISH_IN, GlueAudio.Sound.SWISH_LOCK), this.sounds);

        this.sounds.clear();
        this.swish.startPanelEntering(PANE_PANEL);

        for (int offset : ENTERING_OFFSETS) {
            this.tick();

            Assertions.assertEquals(offset, this.swish.getOffsetX(PANE_PANEL));
            Assertions.assertEquals(offset, this.swish.getOffsetX(PANE_CONTENT));
            Assertions.assertEquals(0, this.swish.getOffsetY(PANE_CONTENT));
            Assertions.assertEquals(0, this.swish.getOffsetX(FRAME_PANEL));
            Assertions.assertEquals(GlueSwish.Phase.LOCKED, this.swish.getPhase());
        }

        this.settle();

        Assertions.assertTrue(this.swish.isPanelOnScreen(PANE_PANEL));
        Assertions.assertEquals(List.of(GlueAudio.Sound.SWISH_IN, GlueAudio.Sound.SWISH_LOCK), this.sounds);
    }

    @Test
    void a_panel_slides_out_alone_with_a_doubling_step_and_back_in_from_where_it_is() {
        this.openHeld();
        this.swish.startPanelEntering(PANE_PANEL);
        this.settle();
        this.sounds.clear();

        Assertions.assertTrue(this.swish.startPanelLeaving(PANE_PANEL, this.sounds::add));
        Assertions.assertEquals(List.of(GlueAudio.Sound.SWISH_OUT), this.sounds);

        for (int offset : LEAVING_OFFSETS) {
            Assertions.assertTrue(this.swish.isMoving());

            this.tick();

            Assertions.assertEquals(offset, this.swish.getOffsetX(PANE_PANEL));
            Assertions.assertEquals(offset, this.swish.getOffsetX(PANE_CONTENT));
            Assertions.assertEquals(0, this.swish.getOffsetX(FRAME_PANEL));
            Assertions.assertEquals(GlueSwish.Phase.LOCKED, this.swish.getPhase());
        }

        Assertions.assertFalse(this.swish.isMoving());
        Assertions.assertFalse(this.swish.isPanelOnScreen(PANE_PANEL));

        // Leave again and turn back after three ticks: the pane enters from where it is.
        this.swish.startPanelEntering(PANE_PANEL);
        this.settle();
        this.swish.startPanelLeaving(PANE_PANEL, this.sounds::add);
        this.tick();
        this.tick();
        this.tick();

        Assertions.assertEquals(LEAVING_OFFSETS[2], this.swish.getOffsetX(PANE_PANEL));

        this.swish.startPanelEntering(PANE_PANEL);
        this.tick();

        Assertions.assertEquals(-6, this.swish.getOffsetX(PANE_PANEL));

        this.settle();

        Assertions.assertEquals(0, this.swish.getOffsetX(PANE_PANEL));
        Assertions.assertEquals(GlueSwish.Phase.LOCKED, this.swish.getPhase());
    }

    @Test
    void a_panel_in_place_stays_when_it_is_brought_in_again() {
        this.openHeld();
        this.swish.startPanelEntering(PANE_PANEL);
        this.settle();
        this.sounds.clear();

        this.swish.startPanelEntering(PANE_PANEL);

        Assertions.assertFalse(this.swish.isMoving());
        Assertions.assertEquals(List.of(), this.sounds);
    }

    @Test
    void a_panel_that_slid_out_stays_out_while_another_panel_slides() {
        this.openHeld();
        this.swish.startPanelEntering(PANE_PANEL);
        this.settle();
        this.swish.startPanelLeaving(PANE_PANEL, this.sounds::add);
        this.settle();

        int paneOut = this.swish.getOffsetX(PANE_PANEL);

        Assertions.assertEquals(LEAVING_OFFSETS[LEAVING_OFFSETS.length - 1], paneOut);

        this.swish.startPanelLeaving(FRAME_PANEL, this.sounds::add);
        this.settle();

        Assertions.assertFalse(this.swish.isPanelOnScreen(FRAME_PANEL));

        this.swish.startPanelEntering(FRAME_PANEL);
        this.settle();

        Assertions.assertEquals(0, this.swish.getOffsetX(FRAME_PANEL));
        Assertions.assertEquals(paneOut, this.swish.getOffsetX(PANE_PANEL));
        Assertions.assertFalse(this.swish.isPanelOnScreen(PANE_PANEL));
        Assertions.assertEquals(GlueSwish.Phase.LOCKED, this.swish.getPhase());
    }

    @Test
    void an_entering_panel_keeps_entering_while_another_panel_slides_out() {
        this.openHeld();
        this.sounds.clear();
        this.swish.startPanelEntering(PANE_PANEL);
        this.swish.startPanelLeaving(FRAME_PANEL, this.sounds::add);

        for (int tick = 0; tick < ENTERING_OFFSETS.length; tick++) {
            this.tick();

            Assertions.assertEquals(ENTERING_OFFSETS[tick], this.swish.getOffsetX(PANE_PANEL));
            Assertions.assertEquals(FRAME_LEAVING_OFFSETS[Math.min(tick, FRAME_LEAVING_OFFSETS.length - 1)], this.swish.getOffsetX(FRAME_PANEL));
        }

        this.settle();

        Assertions.assertTrue(this.swish.isPanelOnScreen(PANE_PANEL));
        Assertions.assertFalse(this.swish.isPanelOnScreen(FRAME_PANEL));
        Assertions.assertFalse(this.swish.isPanelMoving(FRAME_PANEL));
        Assertions.assertEquals(List.of(GlueAudio.Sound.SWISH_OUT, GlueAudio.Sound.SWISH_IN, GlueAudio.Sound.SWISH_LOCK), this.sounds);
    }
}
