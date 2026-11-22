package org.fogofwarlabs.sc4j.scr.game.replay;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.game.frame.FrameRunner;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.frame.GameExecutionScope;
import org.fogofwarlabs.sc4j.scr.game.frame.GameFrame;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.frame.UnitPhase;
import org.fogofwarlabs.sc4j.scr.game.map.MapTileChanges;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.ScrTriggerSystem;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.jetbrains.annotations.NotNull;

/**
 * Executes the established regular replay caller after its recorded-command scope: one engine frame
 * ({@link FrameRunner#runFrame()}) whose phases are the game's frame sequence; the caller supplies the controls of the
 * steps after the world step. Startup phases and host scheduling are supplied by the caller.
 */
public final class ReplayFrameRunner {
    private static final int VIEWPORT_VALUE_COUNT = 4;
    private static final int MILLISECONDS_PER_SECOND = 1000;
    private static final int HASH_MULTIPLIER = 31;
    private static final int BYTE_MASK = 0xFF;

    private final @NotNull Game game;
    private final @NotNull GameFrame frame;
    private final UnitPhase.@NotNull Context unitContext;
    private final @NotNull TriggerContext triggerContext;

    private final @NotNull GameSpeed timing;
    private final int recordedFrameDuration;

    private final boolean buildingRefreshEnabled;
    private final boolean statusRefreshEnabled;
    private final boolean foliageEnabled;

    private final int @NotNull [] viewport;

    private final int instructionLimit;
    private final int triggerPhaseGate;
    private final int triggersEnabledWhileGated;

    private boolean replayFlag;
    private int gameSpeedOption;

    private int randomGateBeforeFrame;
    private int phaseGate;
    private int enabledWhileGated;
    private int displaySeconds;

    /**
     * Creates a {@link ReplayFrameRunner} instance for established unpaused offline replay state.
     *
     * @param game                      the initialized world after both startup phases and following calls
     * @param frame                     the retained phase contexts
     * @param unitContext               the same unit context retained by the frame
     * @param triggerContext            the initialized trigger scheduler
     * @param timing                    the established profile speed calculation
     * @param recordedSpeed             the recorded replay header speed
     * @param buildingRefreshEnabled    whether buildings refresh
     * @param statusRefreshEnabled      whether status refreshes
     * @param foliageEnabled            the established ShowFoliage setting
     * @param viewport                  the tile coordinates and pixel dimensions
     * @param instructionLimit          the movement and script invocation budget
     * @param triggerPhaseGate          the established trigger phase gate
     * @param triggersEnabledWhileGated the retained trigger enable BYTE
     * @param initialDisplaySeconds     the established display clock in seconds
     * @throws IllegalArgumentException if the recorded speed, the viewport length or the instruction budget is out of range
     */
    public ReplayFrameRunner(@NotNull Game game,
                             @NotNull GameFrame frame,
                             UnitPhase.@NotNull Context unitContext,
                             @NotNull TriggerContext triggerContext,
                             @NotNull GameSpeed timing, int recordedSpeed,
                             boolean buildingRefreshEnabled,
                             boolean statusRefreshEnabled,
                             boolean foliageEnabled,
                             int @NotNull [] viewport,
                             int instructionLimit,
                             int triggerPhaseGate, int triggersEnabledWhileGated,
                             int initialDisplaySeconds) {
        if (recordedSpeed < 0 || recordedSpeed > GameSpeed.FASTEST || viewport.length != VIEWPORT_VALUE_COUNT || instructionLimit <= 0) {
            throw Exceptions.newIllegalArgumentException("Native replay caller controls are outside their represented ranges.");
        }

        this.game           = game;
        this.frame          = frame;
        this.unitContext    = unitContext;
        this.triggerContext = triggerContext;

        this.timing                = timing;
        this.recordedFrameDuration = GameSpeed.getBaseDuration(recordedSpeed);

        this.buildingRefreshEnabled = buildingRefreshEnabled;
        this.statusRefreshEnabled   = statusRefreshEnabled;
        this.foliageEnabled         = foliageEnabled;

        this.viewport = viewport.clone();

        this.instructionLimit          = instructionLimit;
        this.triggerPhaseGate          = triggerPhaseGate;
        this.triggersEnabledWhileGated = triggersEnabledWhileGated & BYTE_MASK;

        this.replayFlag      = true;
        this.gameSpeedOption = -1;

        this.randomGateBeforeFrame = 0;
        this.phaseGate             = 0;
        this.enabledWhileGated     = 0;
        this.displaySeconds        = initialDisplaySeconds;

        game.setTriggerContext(triggerContext);
        game.setReplayFrameRunner(this);
        triggerContext.setFrameTiming(timing);
    }

    /**
     * @return the world owned by this caller
     */
    public @NotNull Game getGame() {
        return this.game;
    }

    /**
     * @return the game's speed configuration, timeouts and turn-wait budget
     */
    public @NotNull GameSpeed getFrameTiming() {
        return this.timing;
    }

    /**
     * @return {@code true} if commands and triggers run without advancing a simulation frame, {@code false} otherwise
     */
    public boolean isPaused() {
        return this.triggerContext.getPhaseGate() != 0;
    }

    /**
     * Sets whether the replay rules of the trigger phase and its speed apply.
     *
     * @param replayFlag whether the session plays a replay
     */
    public void setReplayFlag(boolean replayFlag) {
        this.replayFlag = replayFlag;
    }

    /**
     * Sets the viewer speed used by replay trigger timers, or the profile speed when unset.
     *
     * @param speed the speed option, or {@code -1} for the profile speed
     * @throws IllegalArgumentException if the speed is outside the supported options
     */
    public void setGameSpeedOption(int speed) {
        if (speed < -1 || speed > GameSpeed.FASTEST) {
            throw Exceptions.newIllegalArgumentException("The game speed option must be -1 or 0..6: {0}", speed);
        }

        this.gameSpeedOption = speed;
    }


    /**
     * Checks the established contexts before any pending command is consumed.
     *
     * @throws IllegalStateException         if an earlier frame stopped part way
     * @throws UnsupportedOperationException if the world, selection, execution scope, production statistics or foliage state
     *                                       is not established
     */
    public void prepareCommands() {
        if (this.game.getEngineGame().getFrameRunner().isFailed()) {
            throw Exceptions.newIllegalStateException("The native replay caller stopped during a partially applied frame.");
        }

        if (!this.game.hasInitializedWorldState() || this.game.getSelection() == null ||
            this.game.getExecutionScope() == null || this.game.getPlayerTable().getProductionStatistics() == null ||
            this.game.getPlayerTable().getProductionStatistics().getSampleCount() == 0 ||
            (this.foliageEnabled && this.game.getFoliage() == null)) {
            throw Exceptions.newUnsupportedOperationException("Native replay execution requires established world, selection and accounting state.");
        }
    }

    /**
     * Advances the represented frame and trigger phases in their established order: one engine frame, paused while the trigger phase gate
     * is set, whose steps ({@link org.fogofwarlabs.sc4j.scr.game.frame.FrameSequence}) use this caller's controls. A failed partial frame
     * fails the engine frame runner and cannot be retried as though it had completed.
     *
     * @param elapsedHostTicks the measured DWORD difference supplied to the unit phase
     * @throws UnsupportedOperationException if the production statistics, the foliage or the execution scope is not initialized
     */
    public void advance(int elapsedHostTicks) {
        this.prepareCommands();

        this.randomGateBeforeFrame = this.game.getRandom().getEnabled();
        this.phaseGate             = this.triggerContext.getPhaseGate() | this.triggerPhaseGate;
        this.enabledWhileGated     = this.triggerContext.getTriggersEnabledWhileGated() | this.triggersEnabledWhileGated;

        this.game.getEngineGame().getClock().setPaused(this.triggerContext.getPhaseGate() != 0);
        this.frame.prepare(elapsedHostTicks, this.buildingRefreshEnabled, this.statusRefreshEnabled, this.viewport, this.instructionLimit);
        this.game.getEngineGame().getFrameRunner().runFrame();
    }

    /**
     * Runs the frame start step: restores the random gate the frame started with, which the engine frame opened, and finishes the advance of
     * the frame counter ({@link Game#finishFrameAdvance()}).
     */
    public void startFrame() {
        this.game.getRandom().setEnabled(this.randomGateBeforeFrame);
        this.game.finishFrameAdvance();
    }

    /**
     * Runs the production statistics step: advances the production statistics by the recorded frame duration.
     */
    public void advanceProductionStatistics() {
        this.game.getPlayerTable().requireProductionStatistics().advanceReplayFrame(this.recordedFrameDuration);
    }

    /**
     * Runs the foliage step: updates the inactive foliage animations when foliage is shown.
     */
    public void updateFoliage() {
        if (this.foliageEnabled) {
            this.game.requireFoliage().updateInactiveAnimations();
        }
    }

    /**
     * Runs the trigger step, also while paused, for the game's trigger system ({@link ScrTriggerSystem#run}): the trigger evaluation with the
     * phase gate of this frame in the frame phase of the execution scope, with the random generator enabled, which it leaves disabled. The
     * simulation plugins start the frame first, in the same scope ({@link ScrSimulationPlugins#startFrame()}), then the queued map tile changes
     * apply ({@link MapTileChanges#applyChanges}).
     */
    public void updateTriggers() {
        this.game.getRandom().setEnabled(1);

        GameExecutionScope scope = this.game.requireExecutionScope();

        scope.beginFramePhase(this.frame.getPhaseIndex());

        try {
            ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

            if (simulation != null) {
                simulation.startFrame();
            }

            this.game.getMapTileChanges().applyChanges(this.instructionLimit);

            int requestedSpeed = this.replayFlag ? this.gameSpeedOption : this.game.getRequestedGameSpeed();
            int triggerDuration = requestedSpeed >= 0 ? this.timing.getDuration(requestedSpeed) : this.timing.getFrameDuration();

            ScrTriggerSystem.update(this.triggerContext,
                                    triggerDuration,
                                    this.replayFlag ? 1 : 0,
                                    this.phaseGate, this.enabledWhileGated,
                                    this.game,
                                    this.instructionLimit);
        } finally {
            scope.endFramePhase();
        }

        this.game.getRandom().setEnabled(0);
    }

    /**
     * Runs the display clock step, also while paused: refreshes the display state when the second of the display clock changes.
     */
    public void updateDisplayClock() {
        int seconds = Integer.divideUnsigned(this.game.getCurrentFrame() * this.recordedFrameDuration, MILLISECONDS_PER_SECOND);

        if (seconds != this.displaySeconds) {
            this.displaySeconds = seconds;
            this.unitContext.getDisplayState().refreshDisplayState();
        }
    }

    /**
     * Computes a hash of the retained caller controls and display clock.
     *
     * @param hash the preceding diagnostic hash
     * @return the hash including caller controls and retained display clock
     */
    public int computeStateHash(int hash) {
        hash = HASH_MULTIPLIER * hash + this.recordedFrameDuration;
        hash = HASH_MULTIPLIER * hash + this.displaySeconds;
        hash = HASH_MULTIPLIER * hash + (this.game.getEngineGame().getFrameRunner().isFailed() ? 1 : 0);
        hash = HASH_MULTIPLIER * hash + (this.buildingRefreshEnabled ? 1 : 0);
        hash = HASH_MULTIPLIER * hash + (this.statusRefreshEnabled ? 1 : 0);
        hash = HASH_MULTIPLIER * hash + (this.foliageEnabled ? 1 : 0);
        hash = HASH_MULTIPLIER * hash + this.instructionLimit;
        hash = HASH_MULTIPLIER * hash + this.triggerPhaseGate;
        hash = HASH_MULTIPLIER * hash + this.triggersEnabledWhileGated;

        for (int value : this.viewport) {
            hash = HASH_MULTIPLIER * hash + value;
        }

        return this.triggerContext.computeStateHash(hash);
    }
}
