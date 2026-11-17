package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.trigger.TriggerSystem;
import org.fogofwarlabs.sc4j.scr.core.GameSettings;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkSection;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplaySettings;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayFrameRunner;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameCheats;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Runs the map triggers: the trigger cycle, the per-player record walk (the running-trigger lists, or the classic lists
 * of the EUD emulation), condition tests, action execution and result propagation.
 * <p>
 * This is the game's engine {@link TriggerSystem}: its frame phase is the native trigger step of the frame, which runs
 * {@link #update} through the frame caller ({@link ReplayFrameRunner#updateTriggers()}); the conditions and actions are
 * the SC:R conditions and actions in the engine registries of the {@link TriggerContext}.
 */
public final class ScrTriggerSystem implements TriggerSystem {
    private static final int BYTE_MASK = 0xFF;
    private static final int WORD_MASK = 0xFFFF;

    /**
     * The cheat flags that set the local player's pass result.
     */
    private static final int CHEAT_RESULTS = GameCheats.THERE_IS_NO_COW_LEVEL | GameCheats.GAME_OVER_MAN;

    /**
     * Resolves explicitly encoded extended-Deaths operands without a dependency on an address adapter.
     * Implementations must share this context's authoritative world state and reject unsupported addresses.
     */
    public interface ExtendedDeathsOperations {
        /**
         * Reads a DWORD selected by already encoded operands.
         *
         * @param playerOperand the encoded address index, not a player selector
         * @param unitOperand   the unsigned unit WORD
         * @return all bits of the selected DWORD
         */
        int readExtendedDeaths(int playerOperand, int unitOperand);

        /**
         * Sets a DWORD selected by already encoded operands using a supported modifier. Earlier action mutations remain
         * visible if this operation fails.
         *
         * @param playerOperand the encoded address index, not a player selector
         * @param unitOperand   the unsigned unit WORD
         * @param modifier      the encoded action modifier
         * @param amount        the action DWORD
         */
        void setExtendedDeaths(int playerOperand, int unitOperand, int modifier, int amount);
    }

    /**
     * Applies the map-summary {@code TRIG} section handler ({@link #usesExtendedDeaths(byte[])}) to every {@code TRIG} section of a map.
     * Every section payload is read and scanned, also after a section has already marked the map.
     *
     * @param map the map
     * @return {@code true} if a {@code TRIG} section marks the map as using EUD triggers, {@code false} otherwise
     */
    public static boolean usesExtendedDeaths(@NotNull ChkFile map) {
        boolean extendedDeaths = false;

        for (ChkSection section : map.getSections(ChkFile.TRIGGER_SECTION)) {
            extendedDeaths |= usesExtendedDeaths(section.getPayload());
        }

        return extendedDeaths;
    }

    /**
     * Applies the map-summary {@code TRIG} section handler: a section whose size is a whole number of records marks the
     * map as using EUD triggers when any record passes the EUD scan.
     *
     * @param section the {@code TRIG} section payload
     * @return {@code true} if the section marks the map as using EUD triggers, {@code false} otherwise
     */
    public static boolean usesExtendedDeaths(byte @NotNull [] section) {
        if (section.length % TriggerRecordCodec.RECORD_SIZE != 0) {
            return false;
        }

        for (int offset = 0; offset < section.length; offset += TriggerRecordCodec.RECORD_SIZE) {
            if (TriggerRecordCodec.scanExtendedDeaths(section, offset) != 0) {
                return true;
            }
        }

        return false;
    }

    /**
     * Applies trigger evaluation without the world that trigger results act on.
     *
     * @param context           the initialized trigger state
     * @param frameDuration     the duration of the frame in milliseconds
     * @param replayMode        the replay flag DWORD: nonzero while replaying, {@code 0} in a live game
     * @param phaseGate         the game-paused DWORD
     * @param enabledWhileGated the trigger pause BYTE
     * @throws UnsupportedOperationException if player records are not retained, the trigger records lack their bodies or evaluation
     *                                       controls, a trigger result addresses an observing or unrepresented local player, or an
     *                                       executed action or memory address is unsupported
     */
    public static void update(@NotNull TriggerContext context, int frameDuration, int replayMode, int phaseGate, int enabledWhileGated) {
        update(context, frameDuration, replayMode, phaseGate, enabledWhileGated, null, 0);
    }

    /**
     * Applies trigger evaluation with the world that trigger results act on.
     * <p>
     * Both modes share the scheduling and evaluation; a live game ({@code replayMode == 0}) first runs the victory countdown (see
     * {@link TriggerContext#consumeOutcomeDisplay()}), and the local player then receives the presentation output of Play WAV, which
     * is skipped while replaying. The presentation queue {@link TriggerContext#getEvents()} is cleared first. Earlier timer, action
     * and player mutations remain if an owner fails; evaluation does not roll them back.
     *
     * @param context           the initialized trigger state
     * @param frameDuration     the duration of the frame in milliseconds
     * @param replayMode        the replay flag DWORD: nonzero while replaying, {@code 0} in a live game
     * @param phaseGate         the game-paused DWORD
     * @param enabledWhileGated the trigger pause BYTE
     * @param world             the world whose players a trigger defeat removes, or {@code null} when unavailable
     * @param instructionLimit  the order and animation script budget for player removal
     * @throws UnsupportedOperationException if player records are not retained, the trigger records lack their bodies or evaluation
     *                                       controls, a trigger result addresses an observing or unrepresented local player, or an
     *                                       executed action or memory address is unsupported
     */
    public static void update(@NotNull TriggerContext context,
                              int frameDuration,
                              int replayMode,
                              int phaseGate, int enabledWhileGated,
                              @Nullable Game world,
                              int instructionLimit) {
        context.events.clear();

        context.phaseGate                 = phaseGate;
        context.triggersEnabledWhileGated = enabledWhileGated & BYTE_MASK;

        if (phaseGate != 0 && (enabledWhileGated & BYTE_MASK) == 0) {
            return;
        }

        if (replayMode == 0) {
            updateVictoryCountdown(context);
        }

        context.replayObservation = replayMode != 0;
        context.world             = world;
        context.instructionLimit  = instructionLimit;

        boolean evaluatedRecords = false;

        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
            int remaining = context.waitTimers[player];

            if (context.waitingPlayers[player] == 0 || remaining == -1) {
                continue;
            }

            if (Integer.compareUnsigned(remaining, frameDuration) > 0) {
                context.waitTimers[player] = remaining - frameDuration;
            } else {
                context.endWait(player);
            }
        }

        if (phaseGate == 0) {
            int previous = context.periodicCountdown;

            context.periodicCountdown = (previous - 1) & WORD_MASK;

            if (previous == 0) {
                context.periodicCountdown = TriggerContext.PERIODIC_COUNTDOWN_RELOAD;

                TriggerLeaderboardUtils.computeLeaderboard(context);
            }

            previous               = context.clockCountdown;
            context.clockCountdown = (previous - 1) & WORD_MASK;

            if (previous == 0) {
                context.elapsedClockTicks++;

                // An unpaused countdown decrements and, reaching zero, reloads the cycle WORD with 1. A running countdown
                // then shows its value in the timer text, so zero shows for one second; a stopped one hides it.
                if (context.countdownTimer != 0) {
                    if (context.countdownPaused == 0) {
                        context.countdownTimer--;

                        if (context.countdownTimer == 0) {
                            context.cycleCountdown = 1;
                        }
                    }

                    context.countdownDisplay = context.countdownTimer;
                } else {
                    context.countdownDisplay = TriggerContext.COUNTDOWN_HIDDEN;
                }

                context.clockCountdown = TriggerContext.CLOCK_COUNTDOWN_RELOAD;
            }
        }

        int previous = context.cycleCountdown;

        context.cycleCountdown = (previous - 1) & WORD_MASK;

        if (previous != 0) {
            return;
        }

        context.playerEvaluationFlags      = 0;
        context.cycleCountdown             = TriggerContext.CYCLE_COUNTDOWN_RELOAD;
        context.preparationFlag            = 0;
        context.evaluationStateInitialized = true;

        if (context.players == null || context.playerRecords == null) {
            throw Exceptions.newUnsupportedOperationException("Trigger evaluation requires retained player records.");
        }

        for (int player = context.getNextActivePlayer(TriggerContext.WALK_START); player < PlayerTable.PLAYABLE_PLAYER_SLOTS;
             player = context.getNextActivePlayer(player)) {
            context.currentPlayer            = player;
            context.currentPlayerInitialized = true;

            boolean hasRecords;

            if (context.extendedDeathsMode != 0) {
                ClassicTriggerMirror mirror = ClassicTriggerWalkUtils.getMirror(context);

                hasRecords = mirror == null ? context.playerRecords[player].length != 0 : !mirror.isEmpty(player);
            } else {
                hasRecords = context.playerRecords[player].length != 0;
            }

            if (hasRecords) {
                evaluatePlayer(context, player);

                evaluatedRecords = true;
            }
        }

        context.currentPlayer            = PlayerTable.PLAYABLE_PLAYER_SLOTS;
        context.currentPlayerInitialized = true;

        if (!evaluatedRecords) {
            return;
        }

        if ((context.multiplayerSession & BYTE_MASK) == 0) {
            // The cheat flags are read at the time of the pass; the Cheat command stores them, which the world keeps in
            // MapVisibility.
            int flags = world != null && world.getFrame() != null ? world.getFrame().getVisibility().getCheatFlags() : context.resultFlags;
            int localPlayer = context.localPlayer;

            if (localPlayer == PlayerTable.PLAYABLE_PLAYER_SLOTS && ((flags & CHEAT_RESULTS) != 0 || (flags & GameCheats.STAYING_ALIVE) != 0)) {
                // Replay observation (local player 8) writes the result byte without a bounds check, so it is the
                // waiting byte of player 0; only the staying-alive branch checks the index, after reading that byte.
                int value = applyResultCheats(flags, context.waitingPlayers[0] & BYTE_MASK);

                if ((flags & GameCheats.STAYING_ALIVE) != 0 && value >= TriggerPlayerResults.DEFEAT) {
                    throw Exceptions.newUnsupportedOperationException("Trigger result flags cannot clear a result for an observing local player.");
                }

                context.waitingPlayers[0] = (byte) value;
            } else if ((flags & CHEAT_RESULTS) != 0 || (flags & GameCheats.STAYING_ALIVE) != 0) {
                if (localPlayer < 0 || localPlayer >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                    throw Exceptions.newUnsupportedOperationException("Native trigger result flags address an unrepresented local player.");
                }

                int value = applyResultCheats(flags, context.getPlayerResult(localPlayer));

                if ((flags & GameCheats.STAYING_ALIVE) != 0 && value >= TriggerPlayerResults.DEFEAT) {
                    value = TriggerPlayerResults.NONE;
                }

                context.setPlayerResult(localPlayer, value);
            }
        }

        propagateResults(context, world, instructionLimit);

        // Unless the evaluation stopped, the emulation commits the regions a write modified, which makes a written
        // stat_txt copy the game's table.
        if (!context.isEvaluationStopped() && context.extendedDeathsMode != 0 &&
            context.world != null && context.extendedDeathsOperations instanceof ClassicAddressSpace addressSpace) {
            addressSpace.commitModifiedRegions(context.world);
        }
    }

    /**
     * Applies the result cheats to the local player's result: There Is No Cow Level makes it victory, otherwise Game Over
     * Man makes it defeat.
     *
     * @param flags  the cheat flags
     * @param result the local player's result
     * @return the result after the cheats
     */
    private static int applyResultCheats(int flags, int result) {
        if ((flags & GameCheats.THERE_IS_NO_COW_LEVEL) != 0) {
            return TriggerPlayerResults.VICTORY;
        }

        if ((flags & GameCheats.GAME_OVER_MAN) != 0) {
            return TriggerPlayerResults.DEFEAT;
        }

        return result;
    }

    /**
     * Applies the victory countdown of a live game: the countdown WORD decrements, and when it was zero it reloads and the
     * outcome screen of the local player becomes due. An observing local player sees the victory screen once every
     * initially active player has a victory state; otherwise defeat shows the defeat screen and victory or draw the
     * victory screen. The screens are presentation; the countdown only reads game state.
     *
     * @param context the trigger context
     */
    private static void updateVictoryCountdown(@NotNull TriggerContext context) {
        int previous = context.victoryCountdown;

        context.victoryCountdown = (previous - 1) & WORD_MASK;

        if (previous != 0) {
            return;
        }

        context.victoryCountdown = TriggerContext.VICTORY_COUNTDOWN_RELOAD;

        if (context.players == null) {
            return;
        }

        int localPlayer = context.localPlayer;

        if (localPlayer < 0 || localPlayer >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
                if (context.isInitiallyActive(player) && context.players[player].getVictoryState() == 0) {
                    return;
                }
            }

            context.outcomeDisplay = TriggerContext.OUTCOME_VICTORY_SCREEN;

            return;
        }

        int outcome = context.players[localPlayer].getVictoryState();

        if (outcome == TriggerPlayerResults.DEFEAT) {
            context.outcomeDisplay = TriggerContext.OUTCOME_DEFEAT_SCREEN;
        } else if (outcome == TriggerPlayerResults.VICTORY || outcome == TriggerPlayerResults.DRAW) {
            context.outcomeDisplay = TriggerContext.OUTCOME_VICTORY_SCREEN;
        }
    }

    /**
     * Propagates the pass results: a result of at least victory for an active player defeats the other active players
     * without a result (the local player also reloads the victory countdown WORD); when every initially active player won,
     * all draw. Results are then copied only into players without a victory state; a new defeat removes the player. In a
     * multiplayer session the running triggers of players with a result other than none or secured are discarded.
     *
     * @param context          the trigger context
     * @param world            the world whose players a defeat removes, or {@code null} when unavailable
     * @param instructionLimit the order and animation script budget for player removal
     * @throws UnsupportedOperationException if a player is defeated without a world
     */
    private static void propagateResults(@NotNull TriggerContext context, @Nullable Game world, int instructionLimit) {
        boolean winner = false;

        for (int player = context.getNextActivePlayer(TriggerContext.WALK_START); player < PlayerTable.PLAYABLE_PLAYER_SLOTS;
             player = context.getNextActivePlayer(player)) {
            if (context.getPlayerResult(player) >= TriggerPlayerResults.VICTORY) {
                winner = true;

                break;
            }
        }

        if (winner) {
            for (int player = context.getNextActivePlayer(TriggerContext.WALK_START); player < PlayerTable.PLAYABLE_PLAYER_SLOTS;
                 player = context.getNextActivePlayer(player)) {
                if (context.getPlayerResult(player) != TriggerPlayerResults.NONE) {
                    continue;
                }

                context.setPlayerResult(player, TriggerPlayerResults.DEFEAT);

                if (player == context.localPlayer) {
                    context.victoryCountdown = TriggerContext.VICTORY_COUNTDOWN_RELOAD;
                }
            }

            int initiallyActive = 0;
            int winners = 0;

            for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
                if (!context.isInitiallyActive(player)) {
                    continue;
                }

                initiallyActive++;

                if (context.getPlayerResult(player) >= TriggerPlayerResults.VICTORY) {
                    winners++;
                }
            }

            if (initiallyActive == winners) {
                for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
                    if (context.isInitiallyActive(player)) {
                        context.setPlayerResult(player, TriggerPlayerResults.DRAW);
                    }
                }
            }
        }

        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
            int result = context.getPlayerResult(player);

            if (result == TriggerPlayerResults.NONE || context.players[player].getVictoryState() != 0) {
                continue;
            }

            context.players[player].setVictoryState(result);

            if (result == TriggerPlayerResults.DEFEAT) {
                // The elimination message is only presentation; the removal follows.
                context.events.add(TriggerEvents.PLAYER_ELIMINATED, player, 0, 0);

                if (world == null) {
                    throw Exceptions.newUnsupportedOperationException("Trigger defeat elimination requires the world.");
                }

                world.getTriggers().writeElimination(player);
                world.getPlayerDeparture().removePlayer(player, instructionLimit);
            }
        }

        if ((context.multiplayerSession & BYTE_MASK) != 0) {
            // In a multiplayer session the running-trigger list of every player with a result other than none and secured
            // is emptied; in EUD mode the player's classic list is released first, so its triggers no longer run.
            for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
                if ((context.getPlayerResult(player) & ~TriggerPlayerResults.SECURED) == TriggerPlayerResults.NONE) {
                    continue;
                }

                ClassicTriggerMirror mirror = context.extendedDeathsMode != 0 ? ClassicTriggerWalkUtils.getMirror(context) : null;

                if (mirror != null) {
                    mirror.releaseList(player, (ClassicAddressSpace) ExtendedDeathsUtils.getExtendedDeathsOperations(context));
                }

                context.clearPlayerRecords(player);
            }
        }
    }

    /**
     * Runs the running triggers of one player in list order, or walks its classic list in EUD mode.
     *
     * @param context the trigger context
     * @param player  the player
     * @throws UnsupportedOperationException if the records or the evaluation controls are missing
     */
    private static void evaluatePlayer(@NotNull TriggerContext context, int player) {
        if (context.playerRecords == null || !context.evaluationControlsInitialized) {
            throw Exceptions.newUnsupportedOperationException("Native nonempty trigger records require their bodies and evaluation controls.");
        }

        ClassicTriggerMirror mirror = context.extendedDeathsMode != 0 ? ClassicTriggerWalkUtils.getMirror(context) : null;

        if (mirror != null) {
            ClassicTriggerWalkUtils.evaluateMirroredPlayer(context, mirror, player);

            return;
        }

        RunningTrigger[] records = context.playerRecords[player];

        for (int recordIndex = 0; recordIndex < records.length; recordIndex++) {
            if (context.isEvaluationStopped()) {
                return;
            }

            runRecord(context, records[recordIndex], player, recordIndex, null);
        }
    }

    /**
     * Evaluates one running trigger as the body of the trigger loop: a disabled record is skipped, a record with running
     * actions resumes them, otherwise the conditions are tested and the actions run from the first.
     *
     * @param context     the trigger context
     * @param record      the running trigger
     * @param player      the player whose walk runs the record
     * @param recordIndex the position of the record in the walk
     * @param conditions  the rows of the record's conditions when they are those of its definition, else {@code null}
     */
    static void runRecord(@NotNull TriggerContext context,
                          @NotNull RunningTrigger record,
                          int player, int recordIndex,
                          @Nullable TriggerConditions.ConditionRows conditions) {
        if ((record.executionFlags & TriggerFlags.EXECUTION_DISABLED) != 0) {
            return;
        }

        if (context.executionCounter != null) {
            context.executionCounter.record();
        }

        context.currentRecordPlayer      = player;
        context.currentRecordIndex       = recordIndex;
        context.currentRecordInitialized = true;

        if ((record.executionFlags & TriggerFlags.EXECUTION_ACTIONS_RUNNING) != 0) {
            TriggerActionsUtils.executeActions(context, record);

            return;
        }

        boolean passed = conditions != null ? conditions.test(context) : TriggerConditions.testConditions(context, record.definition);

        if (passed) {
            record.currentActionIndex = 0;

            TriggerActionsUtils.executeActions(context, record);
        }
    }

    /**
     * Returns the SC:R trigger system of a game.
     *
     * @param game the engine game
     * @return the game's trigger system
     * @throws IllegalStateException if the game's trigger system is not the SC:R one
     */
    public static @NotNull ScrTriggerSystem of(org.fogofwarlabs.sc4j.engine.game.@NotNull Game game) {
        if (!(game.getTriggers() instanceof ScrTriggerSystem triggers)) {
            throw Exceptions.newIllegalStateException("The game has no SC:R trigger system.");
        }

        return triggers;
    }

    /**
     * Returns the world of the running evaluation.
     *
     * @param context the trigger context
     * @param action  the name of the action that needs the world, for the failure message
     * @return the world
     * @throws UnsupportedOperationException if the evaluation runs without a world
     */
    static @NotNull Game requireWorld(@NotNull TriggerContext context, @NotNull String action) {
        if (context.world == null) {
            throw Exceptions.newUnsupportedOperationException("{0} requires the world state.", action);
        }

        return context.world;
    }


    private @Nullable Game game;

    /**
     * Creates a {@link ScrTriggerSystem} instance; it serves the SC:R game it finds when it is attached.
     */
    public ScrTriggerSystem() {
        this.game = null;
    }

    /**
     * Resolves what this system uses from the game. Called once, after every system exists.
     * <p>
     * Binds the SC:R game system of the game.
     *
     * @param game the game
     */
    @Override
    public void attach(org.fogofwarlabs.sc4j.engine.game.@NotNull Game game) {
        this.game = game.getModSystem(Game.SYSTEM_KEY);
    }

    /**
     * Evaluates the triggers of the current frame.
     * <p>
     * Runs the native trigger step of the frame, also while paused: {@link ReplayFrameRunner#updateTriggers()} of the game's frame caller.
     *
     * @param game the game
     * @throws UnsupportedOperationException if the frame caller is not established
     */
    @Override
    public void run(org.fogofwarlabs.sc4j.engine.game.@NotNull Game game) {
        this.requireGame().requireReplayFrameRunner().updateTriggers();
    }

    /**
     * Applies the unit creation wrapper shared by the trigger actions and aiscript {@code create_unit}; see
     * {@link TriggerUnitActions#createUnit(int, int, int, int, int)} for the represented body.
     *
     * @param unitType         the {@code units.dat} type
     * @param positionX        the center X
     * @param positionY        the center Y
     * @param owner            the owner
     * @param instructionLimit the iscript budget
     * @return the created {@link Unit} instance, or {@code null}
     */
    public @Nullable Unit createUnit(int unitType, int positionX, int positionY, int owner, int instructionLimit) {
        return this.requireGame().getTriggerUnitActions().createUnit(unitType, positionX, positionY, owner, instructionLimit);
    }

    /**
     * Writes the elimination message: none in a Use Map Settings game; a team game names the force (not represented);
     * otherwise {@link MessageLines#writeElimination} with the player's record type before its removal.
     *
     * @param player the eliminated player
     */
    private void writeElimination(int player) {
        Game world = this.requireGame();
        GameSettings settings = world.getGameSettings();

        if (settings != null && settings.usesMapSettings()) {
            return;
        }

        if (settings instanceof ReplaySettings replay && replay.getTeamsEnabled() != 0) {
            world.getInterfaceLink().getMessageLines().markUnrepresentedWrite("the team elimination message");

            return;
        }

        world.getInterfaceLink().getMessageLines().writeElimination(player,
                                                                    world.getPlayerTable().requirePlayer(player).getType(),
                                                                    world.isReplayPlayback());
    }

    private @NotNull Game requireGame() {
        Game attached = this.game;

        if (attached == null) {
            throw Exceptions.newIllegalStateException("The SC:R trigger system is not attached to its engine game yet.");
        }

        return attached;
    }
}
