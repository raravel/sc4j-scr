package org.fogofwarlabs.sc4j.scr.client.glue;

import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceTimers;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Slides glue panels in after a delay on a {@code 25}-millisecond timer, moving one quarter of the remaining distance and
 * snapping below four pixels.
 * Controls following the highest listed panel identifier remain hidden until the panels lock.
 * Leaving doubles each panel's outward step each tick. Sounds play at the first move, lock and start of leaving.
 * <p>
 * Each panel moves by its own motion. Opening the screen sets every listed panel except the held ones entering; a panel stops once it is in
 * place or, leaving, after a step it took from outside the screen, and the lock sound plays when no entering panel moved in a tick.
 * Afterwards a listed panel slides in or out alone, from where it is, while the screen stays locked and the other panels keep their motion.
 */
final class GlueSwish {
    private static final int TICK_MILLISECONDS = 25;
    /**
     * The swish timer identifier of the screen dialog ({@code 7}).
     */
    private static final int SWISH_TIMER = 7;
    /**
     * The divisor of the remaining distance a panel moves per entering tick.
     */
    private static final int ENTERING_STEP_DIVISOR = 4;
    private static final int SNAP_DISTANCE = 4;
    private static final int FIRST_OUT_STEP = 1;
    private static final int ENTRY_SIZE = 2;
    private static final int OUT_STEP_MULTIPLIER = 2;

    /**
     * The direction a panel comes from and leaves to.
     */
    enum Direction {
        /**
         * The panel enters from and leaves to the left.
         */
        LEFT,
        /**
         * The panel enters from and leaves to the top.
         */
        UP,
        /**
         * The panel enters from and leaves to the right.
         */
        RIGHT,
        /**
         * The panel enters from and leaves to the bottom.
         */
        DOWN;

        static @NotNull Direction of(int code) {
            return values()[Math.floorMod(code, values().length)];
        }
    }

    /**
     * The phase of the screen's slide.
     */
    enum Phase {
        /**
         * No panel listed: the controls show at once.
         */
        NONE,
        /**
         * Waiting for the delay or moving in.
         */
        ENTERING,
        /**
         * In place; the controls show. One panel may slide in or out alone.
         */
        LOCKED,
        /**
         * Moving out.
         */
        LEAVING,
        /**
         * Out of the screen.
         */
        GONE
    }

    /**
     * The move of one panel.
     */
    private enum Motion {
        /**
         * The panel stands where it is.
         */
        NONE,
        /**
         * The panel moves toward its place.
         */
        ENTERING,
        /**
         * The panel moves out.
         */
        LEAVING
    }

    /**
     * One listed panel, its offset from its place and its move.
     */
    private static final class Panel {
        private final @NotNull DialogControl control;
        private final @NotNull Direction direction;

        private int offsetX;
        private int offsetY;
        private int outStep;
        private @NotNull Motion motion;

        private Panel(@NotNull DialogControl control, @NotNull Direction direction) {
            this.control   = control;
            this.direction = direction;

            this.offsetX = 0;
            this.offsetY = 0;
            this.outStep = FIRST_OUT_STEP;
            this.motion  = Motion.ENTERING;
        }
    }

    /**
     * Finds the step a panel moves toward its place in one entering tick: the whole offset below the snap distance, a quarter of it otherwise.
     *
     * @param offset the panel's offset from its place on one axis
     * @return the step to add to the offset
     */
    private static int findEnteringStep(int offset) {
        return Math.abs(-offset) < SNAP_DISTANCE ? -offset : -offset / ENTERING_STEP_DIVISOR;
    }

    private final @NotNull List<Panel> panels;
    /**
     * The listed panel each attached control moves with, by control identifier.
     */
    private final @NotNull Map<Integer, Integer> attachments;

    private int delayTicks;
    private int highestControl;
    private @NotNull Phase phase;
    private boolean entrySoundPending;

    private boolean armed;
    private int interfaceWidth;
    private @Nullable Consumer<GlueAudio.Sound> sounds;

    GlueSwish() {
        this.panels      = new ArrayList<>();
        this.attachments = new HashMap<>();

        this.delayTicks        = 0;
        this.highestControl    = Integer.MIN_VALUE;
        this.phase             = Phase.NONE;
        this.entrySoundPending = true;

        this.armed          = false;
        this.interfaceWidth = 0;
        this.sounds         = null;
    }

    /**
     * @return the phase
     */
    @NotNull Phase getPhase() {
        return this.phase;
    }

    /**
     * @param control a control identifier
     * @return the control's horizontal offset from its place: the offset of the listed panel it is or is attached to
     */
    int getOffsetX(int control) {
        Panel panel = this.findPanel(this.attachments.getOrDefault(control, control));

        return panel == null ? 0 : panel.offsetX;
    }

    /**
     * @param control a control identifier
     * @return the control's vertical offset from its place: the offset of the listed panel it is or is attached to
     */
    int getOffsetY(int control) {
        Panel panel = this.findPanel(this.attachments.getOrDefault(control, control));

        return panel == null ? 0 : panel.offsetY;
    }

    /**
     * @param dialog       the dialog
     * @param controlIndex a child index of the dialog
     * @return {@code true} if the control is hidden by the slide: it comes after the listed panel with the highest identifier
     * and the panels are not locked, {@code false} otherwise
     */
    boolean isControlHidden(@NotNull DialogDefinition dialog, int controlIndex) {
        if (this.phase == Phase.NONE || this.phase == Phase.LOCKED) {
            return false;
        }

        List<DialogControl> controls = dialog.getControls();

        for (int index = 0; index < controlIndex; index++) {
            if (controls.get(index).getId() == this.highestControl) {
                return true;
            }
        }

        return false;
    }

    /**
     * @param control a listed panel's identifier
     * @return {@code true} if the panel is listed and at least partly inside the screen, {@code false} otherwise
     */
    boolean isPanelOnScreen(int control) {
        Panel panel = this.findPanel(control);

        return panel != null && !this.isOut(panel);
    }

    /**
     * @return {@code true} if a panel is listed, {@code false} otherwise
     */
    boolean hasPanels() {
        return !this.panels.isEmpty();
    }

    /**
     * @return {@code true} if the timer runs: the screen enters or a panel moves, {@code false} otherwise
     */
    boolean isMoving() {
        return this.phase == Phase.ENTERING || this.hasMotion(Motion.ENTERING) || this.hasMotion(Motion.LEAVING);
    }

    /**
     * @param control a listed panel's identifier
     * @return {@code true} if the panel is listed and moves, {@code false} otherwise
     */
    boolean isPanelMoving(int control) {
        Panel panel = this.findPanel(control);

        return panel != null && panel.motion != Motion.NONE;
    }

    private boolean hasMotion(@NotNull Motion motion) {
        for (Panel panel : this.panels) {
            if (panel.motion == motion) {
                return true;
            }
        }

        return false;
    }

    /**
     * Finds the first listed panel of a control.
     *
     * @param control a control identifier
     * @return the panel, or {@code null} if the control is not listed
     */
    private @Nullable Panel findPanel(int control) {
        for (Panel panel : this.panels) {
            if (panel.control.getId() == control) {
                return panel;
            }
        }

        return null;
    }

    /**
     * Tests whether a panel is out of the screen in its direction: its far edge is past the screen's edge.
     *
     * @param panel the panel
     * @return {@code true} if the panel is out, {@code false} otherwise
     */
    private boolean isOut(@NotNull Panel panel) {
        DialogControl control = panel.control;
        int left = control.getX() + panel.offsetX;
        int top = control.getY() + panel.offsetY;

        return switch (panel.direction) {
            case LEFT -> left + control.getWidth() - 1 <= 0;
            case UP -> top + control.getHeight() - 1 <= 0;
            case RIGHT -> left >= this.interfaceWidth;
            case DOWN -> top >= GlueCanvas.INTERFACE_HEIGHT;
        };
    }


    /**
     * Makes a control move with a listed panel: it is drawn at the panel's offset.
     *
     * @param control the control identifier
     * @param panel   the listed panel's identifier
     */
    void attach(int control, int panel) {
        this.attachments.put(control, panel);
    }

    /**
     * Lists the panels, moves them off-screen and sets them all entering.
     *
     * @param dialog         the dialog
     * @param entries        pairs of control identifier and direction code
     * @param delay          the delay in milliseconds before the panels move
     * @param dialogLeft     the dialog's left edge
     * @param interfaceWidth the interface width
     */
    void startEntering(@NotNull DialogDefinition dialog, int @NotNull [] entries, int delay, int dialogLeft, int interfaceWidth) {
        this.panels.clear();
        this.interfaceWidth = interfaceWidth;

        for (int index = 0; index + 1 < entries.length; index += ENTRY_SIZE) {
            DialogControl control = ScrDialogControlUtils.findControl(dialog, entries[index]);

            if (control == null) {
                continue;
            }

            Panel panel = new Panel(control, Direction.of(entries[index + 1]));
            int right = control.getX() + control.getWidth() - 1;
            int bottom = control.getY() + control.getHeight() - 1;

            switch (panel.direction) {
                case LEFT -> panel.offsetX = -(dialogLeft + 1 + right);
                case UP -> panel.offsetY = -(bottom + 1);
                case RIGHT -> panel.offsetX = interfaceWidth - control.getX();
                case DOWN -> panel.offsetY = GlueCanvas.INTERFACE_HEIGHT - control.getY();
            }

            this.panels.add(panel);
            this.highestControl = Math.max(this.highestControl, control.getId());
        }

        this.delayTicks = delay / TICK_MILLISECONDS;
        this.phase      = this.panels.isEmpty() ? Phase.NONE : Phase.ENTERING;
    }

    /**
     * Keeps a listed panel out while the other panels enter: it stays off-screen until {@link #startPanelEntering} brings it in.
     *
     * @param control the listed panel's identifier
     */
    void holdPanel(int control) {
        Panel panel = this.findPanel(control);

        if (panel != null) {
            panel.motion = Motion.NONE;
        }
    }

    /**
     * Starts sliding one listed panel in from where it is. A panel in place that is not leaving stays as it is.
     *
     * @param control the listed panel's identifier
     */
    void startPanelEntering(int control) {
        Panel panel = this.findPanel(control);

        if (panel == null || this.phase == Phase.LEAVING || this.phase == Phase.GONE) {
            return;
        }

        if (panel.motion != Motion.LEAVING && panel.offsetX == 0 && panel.offsetY == 0) {
            return;
        }

        panel.motion = Motion.ENTERING;
    }

    /**
     * Starts sliding one listed panel out from where it is.
     *
     * @param control the listed panel's identifier
     * @param sounds  plays a glue sound
     * @return {@code true} if the panel slides out, {@code false} if it is not listed or the screen leaves
     */
    boolean startPanelLeaving(int control, @NotNull Consumer<GlueAudio.Sound> sounds) {
        Panel panel = this.findPanel(control);

        if (panel == null || this.phase == Phase.LEAVING || this.phase == Phase.GONE) {
            return false;
        }

        panel.motion  = Motion.LEAVING;
        panel.outStep = FIRST_OUT_STEP;
        sounds.accept(GlueAudio.Sound.SWISH_OUT);

        return true;
    }

    /**
     * Starts leaving: every listed panel slides out.
     *
     * @param sounds plays a glue sound
     * @return {@code true} if there are panels to slide, {@code false} otherwise
     */
    boolean startLeaving(@NotNull Consumer<GlueAudio.Sound> sounds) {
        if (this.panels.isEmpty()) {
            this.phase = Phase.GONE;

            return false;
        }

        for (Panel panel : this.panels) {
            panel.motion  = Motion.LEAVING;
            panel.outStep = FIRST_OUT_STEP;
        }

        sounds.accept(GlueAudio.Sound.SWISH_OUT);
        this.phase = Phase.LEAVING;

        return true;
    }

    /**
     * Runs the timer up to a time.
     *
     * @param time           the time in milliseconds
     * @param interfaceWidth the interface width
     * @param sounds         plays a glue sound
     * @param timers         the client's timer list
     */
    void update(long time, int interfaceWidth, @NotNull Consumer<GlueAudio.Sound> sounds, @NotNull InterfaceTimers timers) {
        this.interfaceWidth = interfaceWidth;
        this.sounds         = sounds;

        boolean running = this.isMoving();

        if (running && !this.armed) {
            // The 25 ms swish timer 7 in the client's timer list; the list's pump fires it at most once per frame.
            timers.arm(this, SWISH_TIMER, TICK_MILLISECONDS, (int) time, now -> this.tick(timers));
            this.armed = true;
        } else if (!running && this.armed) {
            timers.kill(this, SWISH_TIMER);
            this.armed = false;
        }
    }

    private void tick(@NotNull InterfaceTimers timers) {
        Consumer<GlueAudio.Sound> output = this.sounds;

        if (output == null) {
            return;
        }

        if (this.phase == Phase.ENTERING || this.hasMotion(Motion.ENTERING)) {
            this.advanceEntering(output);
        }

        if (this.hasMotion(Motion.LEAVING)) {
            this.advanceLeaving();
        }

        if (!this.isMoving()) {
            timers.kill(this, SWISH_TIMER);
            this.armed = false;
        }
    }


    /**
     * Moves the entering panels a step toward their places after the delay; a panel in place stops. A tick in which no entering panel
     * moved plays the lock sound and locks an entering screen.
     *
     * @param sounds plays a glue sound
     */
    private void advanceEntering(@NotNull Consumer<GlueAudio.Sound> sounds) {
        if (this.delayTicks > 0) {
            this.delayTicks--;

            return;
        }

        if (this.entrySoundPending) {
            this.entrySoundPending = false;
            sounds.accept(GlueAudio.Sound.SWISH_IN);
        }

        boolean moving = false;

        for (Panel panel : this.panels) {
            if (panel.motion != Motion.ENTERING) {
                continue;
            }

            if (panel.offsetX == 0 && panel.offsetY == 0) {
                panel.motion = Motion.NONE;

                continue;
            }

            moving = true;
            panel.offsetX += findEnteringStep(panel.offsetX);
            panel.offsetY += findEnteringStep(panel.offsetY);
        }

        if (moving) {
            return;
        }

        sounds.accept(GlueAudio.Sound.SWISH_LOCK);
        this.entrySoundPending = true;

        if (this.phase == Phase.ENTERING) {
            this.phase = Phase.LOCKED;
        }
    }

    /**
     * Moves the leaving panels out with their doubling steps; a panel that was out of the screen before its step stops after it. A leaving
     * screen is gone once no panel leaves.
     */
    private void advanceLeaving() {
        for (Panel panel : this.panels) {
            if (panel.motion != Motion.LEAVING) {
                continue;
            }

            boolean out = this.isOut(panel);

            switch (panel.direction) {
                case LEFT -> panel.offsetX -= panel.outStep;
                case UP -> panel.offsetY -= panel.outStep;
                case RIGHT -> panel.offsetX += panel.outStep;
                case DOWN -> panel.offsetY += panel.outStep;
            }

            panel.outStep *= OUT_STEP_MULTIPLIER;

            if (out) {
                panel.motion = Motion.NONE;
            }
        }

        if (this.phase == Phase.LEAVING && !this.hasMotion(Motion.LEAVING)) {
            this.phase = Phase.GONE;
        }
    }


    /**
     * Removes the swish timer (the screen closes).
     *
     * @param timers the client's timer list
     */
    void close(@NotNull InterfaceTimers timers) {
        timers.killAll(this);
        this.armed = false;
    }
}
