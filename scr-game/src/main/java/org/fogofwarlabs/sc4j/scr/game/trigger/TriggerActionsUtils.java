package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.trigger.TriggerRegistry;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.TriggerUnitGroups;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerPlayerPhase;
import org.fogofwarlabs.sc4j.scr.game.map.MapStringSection;
import org.fogofwarlabs.sc4j.scr.game.map.PreplacedUnitLoaderUtils;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerRelations;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerScores;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.sprite.SelectionImages;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Arrays;

/**
 * Executes trigger actions through the action registry of the context ({@link TriggerContext#getActionRegistry()}) and retains the action
 * cursor across waits.
 * Unit, location and score handlers live in {@link TriggerUnitActions}, {@link TriggerLocations} and {@link TriggerScoreActionsUtils}.
 */
final class TriggerActionsUtils {
    /**
     * The Set Deaths action; {@link RegionRecordRunnerUtils} runs it over the record bytes while it is registered.
     */
    static final @NotNull ScrTriggerAction SET_DEATHS = TriggerActionsUtils::executeSetDeaths;
    /**
     * The Preserve Trigger action; {@link RegionRecordRunnerUtils} runs it over the record bytes while it is registered.
     */
    static final @NotNull ScrTriggerAction PRESERVE_TRIGGER = TriggerActionsUtils::executePreserveTrigger;

    /**
     * The player types for which interface waits and pauses succeed at once (a BYTE mask): computers, rescuable and
     * neutral players.
     */
    private static final int NON_INTERACTIVE_PLAYER_TYPES = 1 << PlayerTypes.COMPUTER | 1 << PlayerTypes.RESCUE_PASSIVE |
                                                            1 << PlayerTypes.RESCUE_ACTIVE | 1 << PlayerTypes.NEUTRAL;

    /**
     * The number of player types {@link #NON_INTERACTIVE_PLAYER_TYPES} covers; higher types are interactive.
     */
    private static final int PLAYER_TYPE_MASK_SIZE = Byte.SIZE;
    /**
     * The wait duration that does not expire.
     */
    private static final int WAIT_WITHOUT_EXPIRY = -1;
    /**
     * The flash timer of the unit that speaks a transmission.
     */
    private static final int SPEAKER_FLASH_TIMER = Sprite.FLASH_DURATION;

    /**
     * The random source of a randomized Set Switch.
     */
    private static final int SWITCH_RANDOM_SOURCE = 57;
    /**
     * The bit of the random value that sets a randomized switch.
     */
    private static final int SWITCH_RANDOM_BIT = 0x80;

    /**
     * The unit count of a Kill, Remove or Move Unit action whose count is zero: all units.
     */
    private static final int ALL_UNITS = 999999;
    /**
     * The unit count of a Give Units or Modify Unit action whose count is zero: all units.
     */
    private static final int ALL_MODIFIED_UNITS = 0xFFFF;
    /**
     * The largest countdown, {@code 99} hours, {@code 59} minutes and {@code 59} seconds.
     */
    private static final int COUNTDOWN_MAXIMUM = 359999;

    /**
     * The Order action modifiers: move, patrol and attack.
     */
    private static final int ORDER_MOVE = 0;
    private static final int ORDER_PATROL = 1;
    private static final int ORDER_ATTACK = 2;

    /**
     * The Set Alliance Status values of an ally and of allied victory; any other value makes an enemy.
     */
    private static final int STATUS_ALLY = 1;
    private static final int STATUS_ALLIED_VICTORY = 2;

    /**
     * The computer script identifier {@code Rscu} that a neutral player may run.
     */
    private static final int RESCUE_SCRIPT = 0x75637352;
    /**
     * The computer script identifiers {@code EnBk}, {@code EnTr}, {@code ExTr}, {@code DWHe} and {@code PB3F} that human
     * players may start besides the shared vision scripts.
     */
    private static final int ENBK_SCRIPT = 0x6B426E45;
    private static final int ENTR_SCRIPT = 0x72546E45;
    private static final int EXTR_SCRIPT = 0x72547845;
    private static final int DWHE_SCRIPT = 0x65485744;
    private static final int PB3F_SCRIPT = 0x46334250;
    /**
     * The computer script identifiers {@code +Vi0} and {@code -Vi0} that turn shared vision on and off for player {@code 1}.
     */
    private static final int SHARED_VISION_ON_SCRIPT = 0x3069562B;
    private static final int SHARED_VISION_OFF_SCRIPT = 0x3069562D;
    /**
     * The mask that ignores the player digit {@code 0..7} of a shared vision script identifier.
     */
    private static final int SCRIPT_PLAYER_DIGIT_MASK = 0xF8FFFFFF;

    private static final int BYTE_MASK = 0xFF;
    private static final int WORD_MASK = 0xFFFF;

    /**
     * Executes actions from the retained cursor. A failed action or a stop request keeps the cursor for resumption; action
     * types without a handler succeed.
     *
     * @param context the trigger context
     * @param record  the running trigger
     */
    static void executeActions(@NotNull TriggerContext context, @NotNull RunningTrigger record) {
        record.executionFlags = startActions(context, record.executionFlags);

        while (record.currentActionIndex < TriggerRecordCodec.ACTION_COUNT) {
            TriggerDefinition.Action action = record.definition.getAction(record.currentActionIndex);

            if ((action.getFlags() & TriggerFlags.ACTION_DISABLED) != 0) {
                record.currentActionIndex++;

                continue;
            }

            int actionType = action.getType();

            if (actionType == TriggerActionIds.NO_ACTION) {
                record.currentActionIndex = TriggerRecordCodec.ACTION_COUNT;

                break;
            }

            if (context.executionCounter != null) {
                context.executionCounter.action();
            }

            boolean succeeded = actionType >= context.getActionRegistry().getCount() || executeAction(context, record, action);

            if (context.isEvaluationStopped() || !succeeded) {
                return;
            }

            record.currentActionIndex++;
        }

        if ((record.executionFlags & TriggerFlags.EXECUTION_PAUSED_GAME) != 0) {
            // Finished actions resume a game the trigger paused, as the Unpause Game handler without an action.
            if (canChangePauseState(context, record, false)) {
                resumeGame(context, record);
            }
        }

        int flags = record.executionFlags;
        int cursor = record.currentActionIndex;

        record.currentActionIndex = (flags & TriggerFlags.EXECUTION_PRESERVE) != 0 ? 0 : cursor;

        int completedFlags = record.executionFlags;

        record.executionFlags = (completedFlags & TriggerFlags.EXECUTION_PRESERVE) != 0
                                ? completedFlags & ~TriggerFlags.EXECUTION_CLEARED_ON_REWIND
                                : completedFlags | TriggerFlags.EXECUTION_DISABLED;
    }

    /**
     * Starts the actions of a trigger: the execution flags gain {@link TriggerFlags#EXECUTION_ACTIONS_RUNNING}, and
     * {@link TriggerFlags#EXECUTION_SETS_RESULT} secures the current player's result.
     *
     * @param context the trigger context
     * @param flags   the execution flags
     * @return the execution flags while the actions run
     */
    static int startActions(@NotNull TriggerContext context, int flags) {
        if ((flags & TriggerFlags.EXECUTION_SETS_RESULT) != 0) {
            context.setPlayerResult(context.currentPlayer, TriggerPlayerResults.SECURED);
        }

        return flags | TriggerFlags.EXECUTION_ACTIONS_RUNNING;
    }

    /**
     * Creates the frozen action registry with one handler per supported action type. Types {@code 60} and above have no
     * handler and succeed.
     *
     * @return the created {@link TriggerRegistry} instance
     */
    static @NotNull TriggerRegistry<ScrTriggerAction> createRegistry() {
        TriggerRegistry<ScrTriggerAction> registry = new TriggerRegistry<>(TriggerActionIds.FIRST_WITHOUT_HANDLER);

        registry.register(TriggerActionIds.VICTORY, TriggerActionsUtils::executeGameEnd);
        registry.register(TriggerActionIds.DEFEAT, TriggerActionsUtils::executeGameEnd);
        registry.register(TriggerActionIds.PRESERVE_TRIGGER, PRESERVE_TRIGGER);
        registry.register(TriggerActionIds.SET_DEATHS, SET_DEATHS);
        registry.register(TriggerActionIds.WAIT, TriggerActionsUtils::executeWait);
        registry.register(TriggerActionIds.PLAY_WAV, TriggerActionsUtils::executePlayWav);
        registry.register(TriggerActionIds.DISPLAY_TEXT_MESSAGE, TriggerActionsUtils::executeDisplayTextMessage);
        registry.register(TriggerActionIds.PAUSE_GAME, TriggerActionsUtils::executePauseGame);
        registry.register(TriggerActionIds.UNPAUSE_GAME, TriggerActionsUtils::executePauseGame);
        registry.register(TriggerActionIds.CENTER_VIEW, TriggerActionsUtils::executeCenterView);
        registry.register(TriggerActionIds.TRANSMISSION, TriggerActionsUtils::executeTransmission);
        registry.register(TriggerActionIds.SET_MISSION_OBJECTIVES, TriggerActionsUtils::executeSetMissionObjectives);
        registry.register(TriggerActionIds.SET_SWITCH, TriggerActionsUtils::executeSetSwitch);
        registry.register(TriggerActionIds.RUN_AI_SCRIPT, TriggerActionsUtils::executeRunAiScript);
        registry.register(TriggerActionIds.TALKING_PORTRAIT, TriggerActionsUtils::executeTalkingPortrait);
        registry.register(TriggerActionIds.MUTE_UNIT_SPEECH, TriggerActionsUtils::executeMuteUnitSpeech);
        registry.register(TriggerActionIds.UNMUTE_UNIT_SPEECH, TriggerActionsUtils::executeUnmuteUnitSpeech);
        registry.register(TriggerActionIds.RUN_AI_SCRIPT_AT_LOCATION, TriggerActionsUtils::executeRunAiScriptAtLocation);
        registry.register(TriggerActionIds.SET_RESOURCES, TriggerActionsUtils::executeSetResources);
        registry.register(TriggerActionIds.KILL_UNIT, TriggerActionsUtils::executeUnitRemoval);
        registry.register(TriggerActionIds.KILL_UNIT_AT_LOCATION, TriggerActionsUtils::executeUnitRemoval);
        registry.register(TriggerActionIds.REMOVE_UNIT, TriggerActionsUtils::executeUnitRemoval);
        registry.register(TriggerActionIds.REMOVE_UNIT_AT_LOCATION, TriggerActionsUtils::executeUnitRemoval);
        registry.register(TriggerActionIds.MOVE_LOCATION, TriggerActionsUtils::executeMoveLocation);
        registry.register(TriggerActionIds.SET_COUNTDOWN_TIMER, TriggerActionsUtils::executeSetCountdownTimer);
        registry.register(TriggerActionIds.PAUSE_TIMER, TriggerActionsUtils::executePauseTimer);
        registry.register(TriggerActionIds.UNPAUSE_TIMER, TriggerActionsUtils::executePauseTimer);
        registry.register(TriggerActionIds.MINIMAP_PING, TriggerActionsUtils::executeMinimapPing);
        registry.register(TriggerActionIds.SET_INVINCIBILITY, TriggerActionsUtils::executeSetInvincibility);
        registry.register(TriggerActionIds.DRAW, TriggerActionsUtils::executeDraw);
        registry.register(TriggerActionIds.SET_SCORE, TriggerActionsUtils::executeSetScore);
        registry.register(TriggerActionIds.LEADERBOARD_CONTROL, TriggerActionsUtils::executeLeaderboard);
        registry.register(TriggerActionIds.LEADERBOARD_CONTROL_AT_LOCATION, TriggerActionsUtils::executeLeaderboard);
        registry.register(TriggerActionIds.LEADERBOARD_RESOURCES, TriggerActionsUtils::executeLeaderboard);
        registry.register(TriggerActionIds.LEADERBOARD_KILLS, TriggerActionsUtils::executeLeaderboard);
        registry.register(TriggerActionIds.LEADERBOARD_POINTS, TriggerActionsUtils::executeLeaderboard);
        registry.register(TriggerActionIds.LEADERBOARD_GOAL_CONTROL, TriggerActionsUtils::executeLeaderboard);
        registry.register(TriggerActionIds.LEADERBOARD_GOAL_CONTROL_AT_LOCATION, TriggerActionsUtils::executeLeaderboard);
        registry.register(TriggerActionIds.LEADERBOARD_GOAL_RESOURCES, TriggerActionsUtils::executeLeaderboard);
        registry.register(TriggerActionIds.LEADERBOARD_GOAL_KILLS, TriggerActionsUtils::executeLeaderboard);
        registry.register(TriggerActionIds.LEADERBOARD_GOAL_POINTS, TriggerActionsUtils::executeLeaderboard);
        registry.register(TriggerActionIds.LEADERBOARD_GREED, TriggerActionsUtils::executeLeaderboard);
        registry.register(TriggerActionIds.CREATE_UNIT_WITH_PROPERTIES, TriggerActionsUtils::executeCreateUnit);
        registry.register(TriggerActionIds.CREATE_UNIT, TriggerActionsUtils::executeCreateUnit);
        registry.register(TriggerActionIds.ORDER, TriggerActionsUtils::executeOrder);
        registry.register(TriggerActionIds.SET_DOODAD_STATE, TriggerActionsUtils::executeSetDoodadState);
        registry.register(TriggerActionIds.GIVE_UNITS_TO_PLAYER, TriggerActionsUtils::executeUnitModification);
        registry.register(TriggerActionIds.MODIFY_UNIT_HIT_POINTS, TriggerActionsUtils::executeUnitModification);
        registry.register(TriggerActionIds.MODIFY_UNIT_ENERGY, TriggerActionsUtils::executeUnitModification);
        registry.register(TriggerActionIds.MODIFY_UNIT_SHIELD_POINTS, TriggerActionsUtils::executeUnitModification);
        registry.register(TriggerActionIds.MODIFY_UNIT_RESOURCE_AMOUNT, TriggerActionsUtils::executeUnitModification);
        registry.register(TriggerActionIds.MODIFY_UNIT_HANGAR_COUNT, TriggerActionsUtils::executeUnitModification);
        registry.register(TriggerActionIds.MOVE_UNIT, TriggerActionsUtils::executeMoveUnit);
        registry.register(TriggerActionIds.COMMENT, TriggerActionsUtils::executeComment);
        registry.register(TriggerActionIds.SET_ALLIANCE_STATUS, TriggerActionsUtils::executeSetAllianceStatus);
        registry.register(TriggerActionIds.LEADERBOARD_COMPUTER_PLAYERS, TriggerActionsUtils::executeLeaderboardComputerPlayers);
        registry.register(TriggerActionIds.SET_NEXT_SCENARIO, TriggerActionsUtils::executeSetNextScenario);
        registry.register(TriggerActionIds.DISABLE_DEBUG_MODE, TriggerActionsUtils::executeDebugMode);
        registry.register(TriggerActionIds.ENABLE_DEBUG_MODE, TriggerActionsUtils::executeDebugMode);
        registry.freeze();

        return registry;
    }

    /**
     * Tests whether a player type is in the non-interactive BYTE mask.
     *
     * @param playerType the player type
     * @return {@code true} if interface waits and pauses of the type succeed at once, {@code false} otherwise
     */
    private static boolean isNonInteractive(int playerType) {
        return playerType < PLAYER_TYPE_MASK_SIZE && (NON_INTERACTIVE_PLAYER_TYPES & 1 << playerType) != 0;
    }

    /**
     * Executes one action through the context's action registry.
     *
     * @throws IllegalStateException if the registry has no handler for the action type
     */
    private static boolean executeAction(@NotNull TriggerContext context, @NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action) {
        TriggerRegistry<ScrTriggerAction> registry = context.getActionRegistry();
        ScrTriggerAction handler = registry.find(action.getType());

        if (handler == null) {
            throw Exceptions.newIllegalStateException("Every action below {0} is dispatched above: {1}", registry.getCount(), action.getType());
        }

        return handler.execute(context, record, action);
    }

    /**
     * Executes Victory and Defeat: the current player's result and the victory countdown. Defeat fails against a victory
     * or secured result; only human and computer players receive the result.
     */
    private static boolean executeGameEnd(@NotNull TriggerContext context, @NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action) {
        int player = context.currentPlayer;

        if (action.getType() == TriggerActionIds.DEFEAT && hasSecuredResult(context, player)) {
            return false;
        }

        endGame(context, player, action.getType() == TriggerActionIds.VICTORY ? TriggerPlayerResults.VICTORY : TriggerPlayerResults.DEFEAT);

        return true;
    }

    /**
     * Tests whether a player's pending result is victory or secured, which Defeat and Draw do not replace.
     *
     * @param context the trigger context
     * @param player  the trigger player
     * @return {@code true} if the player's result is victory or secured, {@code false} otherwise
     */
    private static boolean hasSecuredResult(@NotNull TriggerContext context, int player) {
        int result = context.getPlayerResult(player);

        return result == TriggerPlayerResults.VICTORY || result == TriggerPlayerResults.SECURED;
    }

    /**
     * Ends the game for a player as Victory, Defeat and Draw do: a human or computer player receives the result, and the
     * victory countdown reloads ({@code 1} while triggers run during the phase gate).
     *
     * @param context the trigger context
     * @param player  the trigger player
     * @param result  the player result ({@link TriggerPlayerResults})
     */
    private static void endGame(@NotNull TriggerContext context, int player, int result) {
        int playerType = context.getPlayerType(player);

        if (playerType == PlayerTypes.HUMAN || playerType == PlayerTypes.COMPUTER) {
            context.setPlayerResult(player, result);
        }

        context.victoryCountdown = context.triggersEnabledWhileGated != 0 ? 1 : TriggerContext.VICTORY_COUNTDOWN_RELOAD;
    }

    /**
     * Executes Preserve Trigger.
     */
    private static boolean executePreserveTrigger(@NotNull TriggerContext context,
                                                  @NotNull RunningTrigger record,
                                                  TriggerDefinition.@NotNull Action action) {
        record.executionFlags |= TriggerFlags.EXECUTION_PRESERVE;

        return true;
    }

    /**
     * Executes Set Deaths through the player selector ({@link ExtendedDeathsUtils#setDeaths}).
     */
    private static boolean executeSetDeaths(@NotNull TriggerContext context, @NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action) {
        ExtendedDeathsUtils.setDeaths(context,
                                      action.getPlayerSelector(),
                                      action.getUnitType() & WORD_MASK,
                                      action.getModifier(), action.getSecondaryOperand(),
                                      action.isMasked() ? action.getLocation() : -1, action.isMasked());

        return true;
    }

    /**
     * Executes Wait: a player already waiting fails; a started wait resumes by clearing its started bit; a record that
     * ignores interface actions skips the wait; otherwise the wait starts and the action fails until it expires.
     */
    private static boolean executeWait(@NotNull TriggerContext context, @NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action) {
        int player = context.currentPlayer;
        long started = 1L << record.currentActionIndex;

        if (context.isWaiting(player)) {
            return false;
        }

        if ((record.startedActions & started) != 0) {
            record.startedActions &= ~started;

            return true;
        }

        if ((record.executionFlags & TriggerFlags.EXECUTION_IGNORE_INTERFACE_ACTIONS) != 0) {
            return true;
        }

        context.beginWait(player, action.getDuration());

        record.startedActions |= started;

        return false;
    }

    /**
     * Executes Play WAV: nothing while replaying; otherwise only the local player hears the sound file named by the map
     * string ({@link #readSoundPath}), unless the record ignores interface actions. It always succeeds.
     */
    private static boolean executePlayWav(@NotNull TriggerContext context, @NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action) {
        int player = context.currentPlayer;

        if (!context.replayObservation && player == context.localPlayer && action.getSoundIndex() != 0 &&
            (record.executionFlags & TriggerFlags.EXECUTION_IGNORE_INTERFACE_ACTIONS) == 0) {
            context.events.addSound(action.getSoundIndex(), readSoundPath(context, action.getSoundIndex()));
        }

        return true;
    }

    /**
     * Reads the sound file path of Play WAV and Transmission when the action runs: the map string of the index's low WORD as the strings hold
     * it then (an EUD write to the strings changes it), at most {@link TriggerEvents#SOUND_PATH_LIMIT} bytes. No string gives an empty path.
     *
     * @param context     the trigger context
     * @param stringIndex the action's sound string DWORD
     * @return the path bytes
     */
    private static byte @NotNull [] readSoundPath(@NotNull TriggerContext context, int stringIndex) {
        MapStringSection strings = context.world == null ? null : context.world.getInterfaceLink().getMessageLines().getMapStringSection();
        byte[] path = strings == null ? null : strings.getStringBytes(stringIndex & WORD_MASK);

        if (path == null) {
            return new byte[0];
        }

        return path.length > TriggerEvents.SOUND_PATH_LIMIT ? Arrays.copyOf(path, TriggerEvents.SOUND_PATH_LIMIT) : path;
    }

    /**
     * Executes Display Text Message: the map string shows for the local player when the subtitle option is on or the
     * action forces it, unless the record ignores interface actions. It always succeeds.
     */
    private static boolean executeDisplayTextMessage(@NotNull TriggerContext context,
                                                     @NotNull RunningTrigger record,
                                                     TriggerDefinition.@NotNull Action action) {
        int player = context.currentPlayer;

        if (player == context.localPlayer && (record.executionFlags & TriggerFlags.EXECUTION_IGNORE_INTERFACE_ACTIONS) == 0) {
            boolean alwaysDisplay = (action.getFlags() & TriggerFlags.ACTION_ALWAYS_DISPLAY) != 0;

            context.events.add(TriggerEvents.TEXT, action.getStringIndex(), alwaysDisplay ? 1 : 0, 0);

            if (context.world != null) {
                context.world.getInterfaceLink().getMessageLines().writeTriggerString(action.getStringIndex(), alwaysDisplay, 0);
            }
        }

        return true;
    }

    /**
     * Executes Pause Game and Unpause Game: they act only outside a multiplayer session, when the game is not yet (pause)
     * or is (unpause) paused by a trigger, and for an interactive owner; pause also skips a record that ignores interface
     * actions. Pausing sets the game-paused state and marks the record so that its completion resumes the game; menus and
     * screen state are presentation. Both always succeed.
     */
    private static boolean executePauseGame(@NotNull TriggerContext context, @NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action) {
        if (canChangePauseState(context, record, action.getType() == TriggerActionIds.PAUSE_GAME)) {
            if (action.getType() == TriggerActionIds.PAUSE_GAME) {
                context.phaseGate                 = 1;
                record.executionFlags |= TriggerFlags.EXECUTION_PAUSED_GAME;
                context.triggersEnabledWhileGated = 1;

                context.events.add(TriggerEvents.GAME_PAUSE, 1, 0, 0);
            } else {
                resumeGame(context, record);
            }
        }

        return true;
    }

    /**
     * Executes Center View: no location, a gated phase or a non-interactive owner succeed at once. In a multiplayer
     * session the local camera moves at once. Otherwise the action is a wait: a waiting player fails, a started action
     * clears its bit and finishes the camera scroll, a record that ignores interface actions skips it, and otherwise the
     * local player's camera scroll starts and every owner waits without expiry.
     */
    private static boolean executeCenterView(@NotNull TriggerContext context,
                                             @NotNull RunningTrigger record,
                                             TriggerDefinition.@NotNull Action action) {
        int player = context.currentPlayer;
        int playerType = context.getPlayerType(player);

        if ((action.getLocation() & BYTE_MASK) == 0 || context.phaseGate != 0 || isNonInteractive(playerType)) {
            return true;
        }

        int location = (action.getLocation() & BYTE_MASK) - 1;

        if ((context.multiplayerSession & BYTE_MASK) != 0) {
            // The local camera moves at once, so the rest of the pass reads the new screen position; without the local
            // display geometry the interface centers its camera from the event instead.
            if (player == context.localPlayer &&
                (context.world == null ||
                 !context.world.getInterfaceLink().getLocalView().centerScreen(context.locations[location].getCenterX(),
                                                                               context.locations[location].getCenterY()))) {
                context.events.add(TriggerEvents.CENTER_VIEW, context.locations[location].getCenterX(), context.locations[location].getCenterY(), 0);
            }

            return true;
        }

        long started = 1L << record.currentActionIndex;

        if (context.isWaiting(player)) {
            return false;
        }

        if ((record.startedActions & started) != 0) {
            record.startedActions &= ~started;

            context.events.add(TriggerEvents.CENTER_VIEW_FINISH, 0, 0, 0);

            return true;
        }

        if ((record.executionFlags & TriggerFlags.EXECUTION_IGNORE_INTERFACE_ACTIONS) != 0) {
            return true;
        }

        if (player == context.localPlayer) {
            // The scroll arrival completes the view (TriggerContext.completeCenterView).
            context.centerViewPending = true;

            context.events.add(TriggerEvents.CENTER_VIEW, context.locations[location].getCenterX(), context.locations[location].getCenterY(), 1);
        }

        record.startedActions |= started;

        context.beginWait(player, WAIT_WITHOUT_EXPIRY);

        return false;
    }

    /**
     * Executes Transmission: no location or a non-interactive owner succeeds at once; a waiting player fails; a started
     * action clears its bit and succeeds; a record that ignores interface actions succeeds. Otherwise the duration is
     * set, added or subtracted (clamped at zero) by the modifier and amount, the action is marked started, the player
     * waits for that duration in a multiplayer session and else without expiry, and the action fails. For the local
     * player the sound plays (not while replaying), the speaking unit found at the location flashes and is pinged on the
     * minimap, and the portrait and text show for the duration.
     *
     * @throws UnsupportedOperationException if the transmission plays for the local player and the evaluation runs without a world
     * @throws IllegalStateException         if the speaking unit has no sprite
     */
    private static boolean executeTransmission(@NotNull TriggerContext context,
                                               @NotNull RunningTrigger record,
                                               TriggerDefinition.@NotNull Action action) {
        int player = context.currentPlayer;

        if ((action.getLocation() & BYTE_MASK) == 0) {
            return true;
        }

        int playerType = context.getPlayerType(player);

        if (isNonInteractive(playerType)) {
            return true;
        }

        if (context.isWaiting(player)) {
            return false;
        }

        long started = 1L << record.currentActionIndex;

        if ((record.startedActions & started) != 0) {
            record.startedActions &= ~started;

            return true;
        }

        if ((record.executionFlags & TriggerFlags.EXECUTION_IGNORE_INTERFACE_ACTIONS) != 0) {
            return true;
        }

        int duration = PlayerScores.modify(action.getDuration(), action.getModifier() & BYTE_MASK, action.getSecondaryOperand());

        record.startedActions |= started;

        context.beginWait(player, (context.multiplayerSession & BYTE_MASK) != 0 ? duration : WAIT_WITHOUT_EXPIRY);

        if (player == context.localPlayer) {
            if (!context.replayObservation && action.getSoundIndex() != 0) {
                context.events.addSound(action.getSoundIndex(), readSoundPath(context, action.getSoundIndex()));
            }

            Game world = ScrTriggerSystem.requireWorld(context, "Transmission");
            Unit speaker = world.getTriggerLocations().findLocationUnit(context,
                                                                        action.getUnitType(),
                                                                        TriggerPlayerSelectors.ALL_PLAYERS,
                                                                        (action.getLocation() & BYTE_MASK) - 1);
            int speakerX = -1;
            int speakerY = -1;

            if (speaker != null) {
                Sprite sprite = speaker.requireSprite();

                sprite.setFlashTimer(SPEAKER_FLASH_TIMER);

                speakerX = sprite.getX();
                speakerY = sprite.getY();

                context.events.add(TriggerEvents.MINIMAP_PING, speakerX, speakerY, 0);
            }

            context.events.add(TriggerEvents.TRANSMISSION, action.getUnitType() & WORD_MASK, speakerX, speakerY, duration);

            boolean alwaysDisplay = (action.getFlags() & TriggerFlags.ACTION_ALWAYS_DISPLAY) != 0;

            context.events.add(TriggerEvents.TEXT, action.getStringIndex(), alwaysDisplay ? 1 : 0, duration);
            world.getInterfaceLink().getMessageLines().writeTriggerString(action.getStringIndex(), alwaysDisplay, duration);
        }

        return false;
    }

    /**
     * Executes Set Mission Objectives: the string index is stored for the current player; for a current player past player 11 it is written to
     * the player's mission objectives DWORD of the game data through the EUD address space.
     *
     * @throws UnsupportedOperationException if the address space does not represent the DWORD of a player past 11
     */
    private static boolean executeSetMissionObjectives(@NotNull TriggerContext context,
                                                       @NotNull RunningTrigger record,
                                                       TriggerDefinition.@NotNull Action action) {
        int player = context.currentPlayer;

        if (Integer.compareUnsigned(player, PlayerTable.PLAYER_SLOTS) < 0) {
            context.setMissionObjectivesStringIndex(player, action.getStringIndex());
        } else {
            ExtendedDeathsUtils.writeGameDataDword(context, TriggerContext.MISSION_OBJECTIVES_OFFSET + Integer.BYTES * player, action.getStringIndex());
        }

        return true;
    }

    /**
     * Executes Set Switch: switch indices past the last switch are ignored; the modifier sets, clears, toggles or
     * randomizes the switch. A randomized switch draws one random value whose bit {@code 7} sets the switch, else it
     * clears.
     *
     * @throws UnsupportedOperationException if the switch is randomized and the evaluation runs without a world
     */
    private static boolean executeSetSwitch(@NotNull TriggerContext context, @NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action) {
        int index = action.getSecondaryOperand();

        if (Integer.compareUnsigned(index, TriggerContext.SWITCH_COUNT) >= 0) {
            return true;
        }

        int bit = 1 << (index & TriggerContext.SWITCH_BIT_MASK);

        switch (action.getModifier()) {
            case TriggerModifiers.SET -> context.switches[index >>> TriggerContext.SWITCH_DWORD_SHIFT] |= bit;
            case TriggerModifiers.CLEAR -> context.switches[index >>> TriggerContext.SWITCH_DWORD_SHIFT] &= ~bit;
            case TriggerModifiers.TOGGLE -> context.switches[index >>> TriggerContext.SWITCH_DWORD_SHIFT] ^= bit;
            case TriggerModifiers.RANDOMIZE -> {
                Game world = ScrTriggerSystem.requireWorld(context, "Randomized Set Switch");

                if ((world.getRandom().next(SWITCH_RANDOM_SOURCE) & SWITCH_RANDOM_BIT) != 0) {
                    context.switches[index >>> TriggerContext.SWITCH_DWORD_SHIFT] |= bit;
                } else {
                    context.switches[index >>> TriggerContext.SWITCH_DWORD_SHIFT] &= ~bit;
                }
            }
            default -> {
            }
        }

        return true;
    }

    /**
     * Executes Run AI Script: scripts outside the list human players may start run only for computer players (and the
     * rescue script for neutral players); otherwise the action succeeds without a script.
     *
     * @throws UnsupportedOperationException if the script runs without the shared computer script pool
     */
    private static boolean executeRunAiScript(@NotNull TriggerContext context,
                                              @NotNull RunningTrigger record,
                                              TriggerDefinition.@NotNull Action action) {
        int player = context.currentPlayer;
        int script = action.getSecondaryOperand();
        if (!canRunScript(context, script, player)) {
            return true;
        }

        ComputerPlayerPhase computerPhase = context.computerPhase;

        if (computerPhase == null) {
            throw Exceptions.newUnsupportedOperationException("Run AI Script requires the shared computer script pool: {0}", formatScript(script));
        }

        computerPhase.runScript(script, player, null);

        return true;
    }

    /**
     * Executes Talking Portrait: for the local player's record that does not ignore interface actions the unit type's
     * portrait talks for the action's time as a Transmission's portrait, without a speaker position; its timer ends the
     * local wait as a Transmission's does. It always succeeds.
     */
    private static boolean executeTalkingPortrait(@NotNull TriggerContext context,
                                                  @NotNull RunningTrigger record,
                                                  TriggerDefinition.@NotNull Action action) {
        int player = context.currentPlayer;

        if (player == context.localPlayer && (record.executionFlags & TriggerFlags.EXECUTION_IGNORE_INTERFACE_ACTIONS) == 0) {
            context.events.add(TriggerEvents.TRANSMISSION,
                               action.getUnitType() & WORD_MASK,
                               TriggerEvents.NO_POSITION, TriggerEvents.NO_POSITION,
                               action.getDuration());
        }

        return true;
    }

    /**
     * Executes Mute Unit Speech: a record that does not ignore interface actions mutes unit speech on the local player's
     * machine; the event consumer applies the local-player test.
     */
    private static boolean executeMuteUnitSpeech(@NotNull TriggerContext context,
                                                 @NotNull RunningTrigger record,
                                                 TriggerDefinition.@NotNull Action action) {
        int player = context.currentPlayer;

        if ((record.executionFlags & TriggerFlags.EXECUTION_IGNORE_INTERFACE_ACTIONS) == 0) {
            context.events.add(TriggerEvents.UNIT_SPEECH_MUTE, player, 0, 0);

            if (context.world != null && player == context.getLocalPlayer()) {
                context.world.getInterfaceLink().getLocalInterface().setUnitSpeechMuted(1);
            }
        }

        return true;
    }

    /**
     * Executes Unmute Unit Speech: the local player unmutes unit speech.
     */
    private static boolean executeUnmuteUnitSpeech(@NotNull TriggerContext context,
                                                   @NotNull RunningTrigger record,
                                                   TriggerDefinition.@NotNull Action action) {
        int player = context.currentPlayer;

        context.events.add(TriggerEvents.UNIT_SPEECH_UNMUTE, player, 0, 0);

        if (context.world != null && player == context.getLocalPlayer()) {
            context.world.getInterfaceLink().getLocalInterface().setUnitSpeechMuted(0);
        }

        return true;
    }

    /**
     * Executes Run AI Script At Location: location {@code 0} does nothing; otherwise the script receives the location's
     * bounds, with the owner gate of Run AI Script.
     *
     * @throws UnsupportedOperationException if the script runs without the shared computer script pool
     */
    private static boolean executeRunAiScriptAtLocation(@NotNull TriggerContext context,
                                                        @NotNull RunningTrigger record,
                                                        TriggerDefinition.@NotNull Action action) {
        int player = context.currentPlayer;
        int locationByte = action.getLocation() & BYTE_MASK;

        if (locationByte == 0) {
            return true;
        }

        int script = action.getSecondaryOperand();
        if (!canRunScript(context, script, player)) {
            return true;
        }

        ComputerPlayerPhase computerPhase = context.computerPhase;

        if (computerPhase == null) {
            throw Exceptions.newUnsupportedOperationException("Run AI Script At Location requires the shared computer script pool: {0}",
                                                              formatScript(script));
        }

        int location = locationByte - 1;
        TriggerLocation area = context.locations[location];

        computerPhase.runScript(script, player, new int[]{area.getLeft(), area.getTop(), area.getRight(), area.getBottom()});

        return true;
    }

    /**
     * Executes Set Resources: the set, add and subtract modifiers through the player selector; other modifiers do
     * nothing.
     */
    private static boolean executeSetResources(@NotNull TriggerContext context,
                                               @NotNull RunningTrigger record,
                                               TriggerDefinition.@NotNull Action action) {
        int modifier = action.getModifier();

        if (!TriggerModifiers.isNumericModifier(modifier)) {
            return true;
        }

        int selected = TriggerPlayersUtils.resolveSinglePlayer(context, action.getPlayerSelector());

        if (selected >= 0) {
            TriggerScoreActionsUtils.setResources(context, selected, modifier, action.getUnitType(), action.getSecondaryOperand());

            return true;
        }

        for (selected = TriggerPlayersUtils.getNextSelectedPlayer(context, action.getPlayerSelector(), TriggerContext.WALK_START);
             selected < PlayerTable.PLAYER_SLOTS;
             selected = TriggerPlayersUtils.getNextSelectedPlayer(context, action.getPlayerSelector(), selected)) {
            TriggerScoreActionsUtils.setResources(context, selected, modifier, action.getUnitType(), action.getSecondaryOperand());
        }

        return true;
    }

    /**
     * Executes Kill Unit, Kill Unit At Location, Remove Unit and Remove Unit At Location through the player selector: a
     * location action with location {@code 0} does nothing and a zero count means all units; the count cache mode is
     * cleared afterwards.
     *
     * @throws UnsupportedOperationException if the evaluation runs without a world
     */
    private static boolean executeUnitRemoval(@NotNull TriggerContext context,
                                              @NotNull RunningTrigger record,
                                              TriggerDefinition.@NotNull Action action) {
        boolean remove = action.getType() >= TriggerActionIds.REMOVE_UNIT;
        boolean atLocation = action.getType() == TriggerActionIds.KILL_UNIT_AT_LOCATION ||
                             action.getType() == TriggerActionIds.REMOVE_UNIT_AT_LOCATION;
        int location = TriggerUnitActions.NO_LOCATION;
        int limit = ALL_UNITS;

        if (atLocation) {
            int locationByte = action.getLocation() & BYTE_MASK;

            if (locationByte == 0) {
                return true;
            }

            location = (locationByte - 1) & BYTE_MASK;

            if (action.getModifier() != 0) {
                limit = action.getModifier();
            }
        }

        Game world = ScrTriggerSystem.requireWorld(context, "Kill and Remove Unit");
        int selector = action.getPlayerSelector();
        int selected = TriggerPlayersUtils.resolveSinglePlayer(context, selector);

        if (selected >= 0) {
            world.getTriggerUnitActions().removeUnits(context, selected, action.getUnitType(), location, limit, remove);
        } else {
            for (selected = TriggerPlayersUtils.getNextSelectedPlayer(context, selector, TriggerContext.WALK_START);
                 selected < PlayerTable.PLAYER_SLOTS;
                 selected = TriggerPlayersUtils.getNextSelectedPlayer(context, selector, selected)) {
                world.getTriggerUnitActions().removeUnits(context, selected, action.getUnitType(), location, limit, remove);
            }
        }

        context.preparationFlag = 0;

        return true;
    }

    /**
     * Executes Move Location: a source location {@code 0} or a destination outside the locations does nothing; the
     * destination is centered on the first matching unit of the source or on the source center, then pushed back inside
     * the map; the count cache mode is cleared afterwards.
     *
     * @throws UnsupportedOperationException if the evaluation runs without a world
     */
    private static boolean executeMoveLocation(@NotNull TriggerContext context,
                                               @NotNull RunningTrigger record,
                                               TriggerDefinition.@NotNull Action action) {
        int sourceByte = action.getLocation() & BYTE_MASK;
        int destination = action.getSecondaryOperand();

        if (sourceByte == 0 || Integer.compareUnsigned(destination - 1, TriggerContext.LOCATION_COUNT - 1) > 0) {
            return true;
        }

        int source = sourceByte - 1;
        Game world = ScrTriggerSystem.requireWorld(context, "Move Location");
        Unit found = world.getTriggerLocations().findLocationUnit(context, action.getUnitType(), action.getPlayerSelector(), source);
        int centerX;
        int centerY;

        if (found != null) {
            centerX = found.getPositionX();
            centerY = found.getPositionY();
        } else {
            centerX = context.locations[source].getCenterX();
            centerY = context.locations[source].getCenterY();
        }

        world.getTriggerLocations().moveLocation(context, destination - 1, centerX, centerY);

        context.preparationFlag = 0;

        return true;
    }

    /**
     * Executes Set Countdown Timer: the set, add and subtract modifiers, then the value is clamped to
     * {@code 0..}{@link #COUNTDOWN_MAXIMUM}.
     */
    private static boolean executeSetCountdownTimer(@NotNull TriggerContext context,
                                                    @NotNull RunningTrigger record,
                                                    TriggerDefinition.@NotNull Action action) {
        int value = context.countdownTimer;

        switch (action.getModifier()) {
            case TriggerModifiers.SET_TO -> value = action.getDuration();
            case TriggerModifiers.ADD -> value += action.getDuration();
            case TriggerModifiers.SUBTRACT -> value -= action.getDuration();
            default -> {
            }
        }

        context.countdownTimer = Math.min(Math.max(value, 0), COUNTDOWN_MAXIMUM);

        return true;
    }

    /**
     * Executes Pause Timer and Unpause Timer: the countdown pause state.
     */
    private static boolean executePauseTimer(@NotNull TriggerContext context,
                                             @NotNull RunningTrigger record,
                                             TriggerDefinition.@NotNull Action action) {
        context.countdownPaused = action.getType() == TriggerActionIds.PAUSE_TIMER ? 1 : 0;

        return true;
    }

    /**
     * Executes Minimap Ping: a ping at the location center for the local player, unless the record ignores interface
     * actions. It always succeeds.
     */
    private static boolean executeMinimapPing(@NotNull TriggerContext context,
                                              @NotNull RunningTrigger record,
                                              TriggerDefinition.@NotNull Action action) {
        int player = context.currentPlayer;

        if ((action.getLocation() & BYTE_MASK) == 0 ||
            player != context.localPlayer ||
            (record.executionFlags & TriggerFlags.EXECUTION_IGNORE_INTERFACE_ACTIONS) != 0) {
            return true;
        }

        int location = (action.getLocation() & BYTE_MASK) - 1;

        context.events.add(TriggerEvents.MINIMAP_PING, context.locations[location].getCenterX(), context.locations[location].getCenterY(), 0);

        return true;
    }

    /**
     * Executes Set Invincibility over the units of the location rectangle ({@link TriggerUnitActions#setInvincibility}).
     *
     * @throws UnsupportedOperationException if the evaluation runs without a world, or the world has no unit spatial index, or the unit pool is not
     *                                       initialized
     * @throws IllegalStateException         if the terrain miniature flags cannot be loaded
     * @throws NullPointerException          if the world was built without game data
     */
    private static boolean executeSetInvincibility(@NotNull TriggerContext context,
                                                   @NotNull RunningTrigger record,
                                                   TriggerDefinition.@NotNull Action action) {
        int locationByte = action.getLocation() & BYTE_MASK;

        if (locationByte == 0) {
            return true;
        }

        Game world = ScrTriggerSystem.requireWorld(context, "Set Invincibility");
        int location = locationByte - 1;
        ScrUnitTypeView units = world.requireGameData().getUnitTypes();
        TerrainTileset tileset;

        try {
            tileset = world.requireGameData().getTerrainTileset(world.getMap().getTileset());
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Set Invincibility requires the terrain miniature flags.");
        }

        UnitSpatialIndex spatial = world.getUnitSystem().getUnitSpatialIndex();

        if (spatial == null) {
            throw Exceptions.newUnsupportedOperationException("Set Invincibility requires the unit spatial index.");
        }

        int[] found = TriggerLocations.findUnitsIn(spatial, units, context.locations[location]);

        for (int poolIndex : found) {
            world.getTriggerUnitActions().setInvincibility(context,
                                                           units,
                                                           tileset,
                                                           world.getUnitSystem().requireUnitPool().getUnit(poolIndex),
                                                           action.getUnitType(),
                                                           action.getPlayerSelector(),
                                                           context.locations[location].getElevationFlags(), true,
                                                           action.getModifier());
        }

        return true;
    }

    /**
     * Executes Draw: fails against a victory or secured result; otherwise human and computer players receive the draw
     * result and the victory countdown is set as for Victory.
     */
    private static boolean executeDraw(@NotNull TriggerContext context, @NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action) {
        int player = context.currentPlayer;

        if (hasSecuredResult(context, player)) {
            return false;
        }

        endGame(context, player, TriggerPlayerResults.DRAW);

        return true;
    }

    /**
     * Executes Set Score: the set, add and subtract modifiers through the player selector for players below {@code 8} and
     * the score types; other modifiers do nothing.
     */
    private static boolean executeSetScore(@NotNull TriggerContext context, @NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action) {
        int modifier = action.getModifier();

        if (!TriggerModifiers.isNumericModifier(modifier)) {
            return true;
        }

        int scoreType = action.getUnitType() & WORD_MASK;
        int selector = action.getPlayerSelector();

        for (int selected = TriggerPlayersUtils.getNextSelectedPlayer(context, selector, TriggerContext.WALK_START);
             selected < PlayerTable.PLAYER_SLOTS;
             selected = TriggerPlayersUtils.getNextSelectedPlayer(context, selector, selected)) {
            if (selected < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                TriggerScoreActionsUtils.modifyScore(context.requireAccounting(), selected, scoreType, modifier, action.getSecondaryOperand());
            }
        }

        return true;
    }

    /**
     * Executes the leaderboard actions: the display calculation is scheduled for the next frame; Control At Location and
     * its goal form without a location store nothing; Greed stores its type, the goal and both resources as unit WORD;
     * the others store type, location, unit WORD, goal and string.
     */
    private static boolean executeLeaderboard(@NotNull TriggerContext context,
                                              @NotNull RunningTrigger record,
                                              TriggerDefinition.@NotNull Action action) {
        context.periodicCountdown = 1;

        int type = action.getType();

        if (type == TriggerActionIds.LEADERBOARD_GREED) {
            context.leaderboardType     = TriggerActionIds.LEADERBOARD_GREED;
            context.leaderboardGoal     = action.getSecondaryOperand();
            context.leaderboardUnitType = TriggerScoreActionsUtils.RESOURCE_BOTH;

            return true;
        }

        if (type == TriggerActionIds.LEADERBOARD_CONTROL_AT_LOCATION || type == TriggerActionIds.LEADERBOARD_GOAL_CONTROL_AT_LOCATION) {
            int location = action.getLocation() & BYTE_MASK;

            if (location == 0) {
                return true;
            }

            context.leaderboardLocation = location - 1;
        }

        context.leaderboardType     = type;
        context.leaderboardUnitType = action.getUnitType() & WORD_MASK;
        context.leaderboardGoal     = action.getSecondaryOperand();
        context.leaderboardString   = action.getStringIndex();

        return true;
    }

    /**
     * Executes Create Unit and Create Unit with Properties: location {@code 0} does nothing; a zero count creates one
     * unit; each round visits the selected players below {@code 8}, resource types are created for the neutral player, and
     * the unit is created at the location center. A created unit with a property index {@code 1..64} receives that unit
     * property record.
     *
     * @throws UnsupportedOperationException if the evaluation runs without a world
     */
    private static boolean executeCreateUnit(@NotNull TriggerContext context,
                                             @NotNull RunningTrigger record,
                                             TriggerDefinition.@NotNull Action action) {
        int locationByte = action.getLocation() & BYTE_MASK;

        if (locationByte == 0) {
            return true;
        }

        Game world = ScrTriggerSystem.requireWorld(context, "Create Unit");
        int location = locationByte - 1;
        int count = action.getModifier() & BYTE_MASK;

        if (count == 0) {
            count = 1;
        }

        int unitType = action.getUnitType() & WORD_MASK;
        int selector = action.getPlayerSelector();
        int centerX = context.locations[location].getCenterX();
        int centerY = context.locations[location].getCenterY();
        int properties = action.getSecondaryOperand();

        for (int round = 0; round < count; round++) {
            for (int selected = TriggerPlayersUtils.getNextSelectedPlayer(context, selector, TriggerContext.WALK_START);
                 selected < PlayerTable.PLAYER_SLOTS;
                 selected = TriggerPlayersUtils.getNextSelectedPlayer(context, selector, selected)) {
                if (selected >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                    continue;
                }

                int owner = world.requireGameData().getUnitTypes().isMineralField(unitType) || unitType == ScrUnitIds.RESOURCE_VESPENE_GEYSER
                            ? PlayerTable.NEUTRAL_PLAYER_ID
                            : selected;
                Unit created = world.getTriggerUnitActions().createUnit(unitType, centerX, centerY, owner, context.instructionLimit);

                if (created != null && properties - 1 >= 0 && properties - 1 < TriggerUnitActions.PROPERTY_RECORD_COUNT) {
                    PreplacedUnitLoaderUtils.applyProperties(world,
                                                             created,
                                                             TriggerUnitActions.readUnitPropertyRecord(context, properties - 1),
                                                             context.instructionLimit);
                }
            }
        }

        return true;
    }

    /**
     * Executes Order: a source or destination location {@code 0} does nothing; the units of the source rectangle that
     * pass the elevation filter and the owner selector receive the move, patrol or attack order to the destination
     * center ({@link TriggerUnitActions#issueUnitOrder}).
     *
     * @throws UnsupportedOperationException if the evaluation runs without a world or the unit pool is not initialized
     * @throws IllegalStateException         if the terrain tileset cannot be read
     * @throws NullPointerException          if the world was built without game data
     */
    private static boolean executeOrder(@NotNull TriggerContext context, @NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action) {
        int sourceByte = action.getLocation() & BYTE_MASK;
        int destination = action.getSecondaryOperand();

        if (sourceByte == 0 || destination == 0) {
            return true;
        }

        Game world = ScrTriggerSystem.requireWorld(context, "Order");
        int source = sourceByte - 1;
        int target = (destination - 1) & BYTE_MASK;
        int targetX = context.locations[target].getCenterX();
        int targetY = context.locations[target].getCenterY();
        int order = switch (action.getModifier() & BYTE_MASK) {
            case ORDER_MOVE -> ScrOrderIds.MOVE;
            case ORDER_PATROL -> ScrOrderIds.PATROL;
            case ORDER_ATTACK -> ScrOrderIds.ATTACK_MOVE;
            default -> ScrOrderIds.NOTHING;
        };
        ScrUnitTypeView units = world.requireGameData().getUnitTypes();
        TerrainTileset tileset = world.getTriggerLocations().requireTileset();

        for (int poolIndex : world.getTriggerLocations().findLocationUnits(context, source)) {
            Unit unit = world.getUnitSystem().requireUnitPool().getUnit(poolIndex);

            if (world.getTriggerLocations().isExcludedByElevation(tileset, unit, context.locations[source].getElevationFlags()) ||
                !TriggerPlayersUtils.isOwnerSelected(context, action.getPlayerSelector(), unit.getOwnerIndex())) {
                continue;
            }

            world.getTriggerUnitActions().issueUnitOrder(context, units, unit, action.getUnitType() & WORD_MASK, order, targetX, targetY);
        }

        return true;
    }

    /**
     * Executes Set Doodad State: the first matching unit of the location is disabled (clear modifier: DisableDoodad
     * unless already disabled), enabled (set modifier: when disabled, the disabled status is cleared and EnableDoodad
     * issued) or toggled (modifier {@code 0} and toggle) through the empty-queue order path.
     *
     * @throws UnsupportedOperationException if the evaluation runs without a world
     */
    private static boolean executeSetDoodadState(@NotNull TriggerContext context,
                                                 @NotNull RunningTrigger record,
                                                 TriggerDefinition.@NotNull Action action) {
        int locationByte = action.getLocation() & BYTE_MASK;

        if (locationByte == 0) {
            return true;
        }

        Game world = ScrTriggerSystem.requireWorld(context, "Set Doodad State");
        Unit found = world.getTriggerLocations().findLocationUnit(context, action.getUnitType(), action.getPlayerSelector(), locationByte - 1);

        if (found == null) {
            return true;
        }

        int modifier = action.getModifier() & BYTE_MASK;
        boolean disabled = UnitOrderEligibility.isDisabled(found);

        if ((modifier == TriggerModifiers.CLEAR || modifier == 0 || modifier == TriggerModifiers.TOGGLE) && !disabled) {
            world.getOrders().issueUntargeted(found, ScrOrderIds.DISABLE_DOODAD, context.instructionLimit);
        } else if ((modifier == TriggerModifiers.SET || modifier == 0 || modifier == TriggerModifiers.TOGGLE) && disabled) {
            found.setStatus(found.getStatus() & ~UnitStatusFlags.DISABLED);
            world.getOrders().issueUntargeted(found, ScrOrderIds.ENABLE_DOODAD, context.instructionLimit);
        }

        return true;
    }

    /**
     * Executes Give Units to Player and the Modify Unit actions: the units of the location rectangle pass the count (zero
     * meaning all), the elevation filter, the owner selector and the unit type; each accepted unit passes to the
     * action's handler, and a handler result of {@code true} decrements the count.
     *
     * @throws UnsupportedOperationException if the evaluation runs without a world or the unit pool is not initialized
     * @throws IllegalStateException         if the terrain tileset cannot be read
     * @throws NullPointerException          if the world was built without game data
     */
    private static boolean executeUnitModification(@NotNull TriggerContext context,
                                                   @NotNull RunningTrigger record,
                                                   TriggerDefinition.@NotNull Action action) {
        int locationByte = action.getLocation() & BYTE_MASK;

        if (locationByte == 0) {
            return true;
        }

        Game world = ScrTriggerSystem.requireWorld(context, "Give and Modify Units");
        int location = locationByte - 1;
        int remaining = action.getModifier() & BYTE_MASK;

        if (remaining == 0) {
            remaining = ALL_MODIFIED_UNITS;
        }

        ScrUnitTypeView units = world.requireGameData().getUnitTypes();
        TerrainTileset tileset = world.getTriggerLocations().requireTileset();
        // Modify Unit Resource Amount accepts any unit through the unit-type filter.
        int unitType = action.getType() == TriggerActionIds.MODIFY_UNIT_RESOURCE_AMOUNT
                       ? TriggerUnitGroups.ANY_UNIT
                       : action.getUnitType() & WORD_MASK;

        for (int poolIndex : world.getTriggerLocations().findLocationUnits(context, location)) {
            if (remaining == 0) {
                break;
            }

            Unit unit = world.getUnitSystem().requireUnitPool().getUnit(poolIndex);

            if (world.getTriggerLocations().isExcludedByElevation(tileset, unit, context.locations[location].getElevationFlags()) ||
                !TriggerPlayersUtils.isOwnerSelected(context, action.getPlayerSelector(), unit.getOwnerIndex()) ||
                !TriggerLocations.matchesGroupType(units, unit.getUnitId(), unitType)) {
                continue;
            }

            boolean counted = switch (action.getType()) {
                case TriggerActionIds.GIVE_UNITS_TO_PLAYER -> world.getTriggerUnitActions().giveUnit(context, unit, action.getSecondaryOperand());
                case TriggerActionIds.MODIFY_UNIT_HIT_POINTS -> world.getTriggerUnitActions().modifyHitPoints(units, unit, action.getSecondaryOperand(), context.instructionLimit);
                case TriggerActionIds.MODIFY_UNIT_ENERGY -> world.getTriggerUnitActions().modifyEnergy(units, unit, action.getSecondaryOperand());
                case TriggerActionIds.MODIFY_UNIT_SHIELD_POINTS -> TriggerUnitActions.modifyShields(units, unit, action.getSecondaryOperand());
                case TriggerActionIds.MODIFY_UNIT_RESOURCE_AMOUNT -> world.getTriggerUnitActions().modifyResources(units, unit, action.getSecondaryOperand(), context.instructionLimit);
                default -> world.getTriggerUnitActions().modifyHangar(context, unit, action.getSecondaryOperand());
            };

            if (counted) {
                remaining--;
            }
        }

        return true;
    }

    /**
     * Executes Move Unit: a source or destination location {@code 0} does nothing; a zero count means all units; each
     * unit of the source rectangle that Move Unit accepts (moved or not) decrements the count. A grounded building moves
     * through {@link UnitBuildingMove}, another unit is relocated and, when completed, finishes its order. The count cache
     * mode is cleared afterwards.
     *
     * @throws UnsupportedOperationException if the evaluation runs without a world or the unit pool is not initialized
     * @throws IllegalStateException         if the terrain tileset cannot be read
     * @throws NullPointerException          if the world was built without game data
     */
    private static boolean executeMoveUnit(@NotNull TriggerContext context, @NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action) {
        int sourceByte = action.getLocation() & BYTE_MASK;
        int destination = action.getSecondaryOperand();

        if (sourceByte == 0 || destination == 0) {
            return true;
        }

        Game world = ScrTriggerSystem.requireWorld(context, "Move Unit");
        int source = sourceByte - 1;
        int target = (destination - 1) & BYTE_MASK;
        int targetX = context.locations[target].getCenterX();
        int targetY = context.locations[target].getCenterY();
        int remaining = action.getModifier() & BYTE_MASK;

        if (remaining == 0) {
            remaining = ALL_UNITS;
        }

        ScrUnitTypeView units = world.requireGameData().getUnitTypes();
        TerrainTileset tileset = world.getTriggerLocations().requireTileset();

        for (int poolIndex : world.getTriggerLocations().findLocationUnits(context, source)) {
            Unit unit = world.getUnitSystem().requireUnitPool().getUnit(poolIndex);

            if (!world.getTriggerUnitActions().acceptsMoveUnit(context,
                                                               units,
                                                               tileset,
                                                               unit,
                                                               action.getPlayerSelector(),
                                                               action.getUnitType() & WORD_MASK,
                                                               context.locations[source].getElevationFlags())) {
                continue;
            }

            if (UnitQueries.isGroundedBuilding(unit)) {
                world.getUnitBuildingMove().move(unit, targetX, targetY, context.instructionLimit);

                if (--remaining == 0) {
                    break;
                }

                continue;
            }

            boolean moved = world.getUnitRelocation().relocate(unit, targetX, targetY);

            // A pending queue activates, otherwise the unit returns to its computer or idle order.
            if (moved && (unit.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
                world.getOrders().finishOrder(unit, context.instructionLimit);
            }

            if (--remaining == 0) {
                break;
            }
        }

        context.preparationFlag = 0;

        return true;
    }

    /**
     * Executes Comment, which always succeeds.
     */
    private static boolean executeComment(@NotNull TriggerContext context, @NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action) {
        return true;
    }

    /**
     * Executes Set Alliance Status through the player selector, where selectors {@code 0..12} name one player ({@code 12}
     * included) and the groups go through the group table; then the current player's units drop targets that became
     * allied. When the current player is the local player, the local relation table and the selection circles follow
     * ({@link SelectionImages#updateLocalRelations}).
     *
     * @throws UnsupportedOperationException if the evaluation runs without a world
     */
    private static boolean executeSetAllianceStatus(@NotNull TriggerContext context,
                                                    @NotNull RunningTrigger record,
                                                    TriggerDefinition.@NotNull Action action) {
        int player = context.currentPlayer;
        Game world = ScrTriggerSystem.requireWorld(context, "Set Alliance Status");
        int status = action.getUnitType();
        int selector = action.getPlayerSelector();
        int selected = Integer.compareUnsigned(selector, TriggerPlayerSelectors.COUNT) >= 0
                       ? -1
                       : selector == TriggerPlayerSelectors.NONE
                         ? TriggerPlayerSelectors.NONE
                         : TriggerPlayersUtils.resolveSinglePlayer(context, selector);

        if (selected >= 0) {
            setAllianceStatus(context, selected, status);
        } else if (Integer.compareUnsigned(selector, TriggerPlayerSelectors.COUNT) < 0) {
            for (selected = TriggerPlayersUtils.getNextSelectedPlayer(context, selector, TriggerContext.WALK_START);
                 selected < PlayerTable.PLAYER_SLOTS;
                 selected = TriggerPlayersUtils.getNextSelectedPlayer(context, selector, selected)) {
                setAllianceStatus(context, selected, status);
            }
        }

        world.getTriggerUnitActions().clearAlliedOrderTargets(player);

        if (player == context.localPlayer) {
            world.getSelectionImages().updateLocalRelations();
        }

        return true;
    }

    /**
     * Executes Leaderboard Computer Players: the set modifier includes computers in the leaderboard, the clear modifier
     * excludes them and the toggle modifier toggles; other modifiers do nothing.
     */
    private static boolean executeLeaderboardComputerPlayers(@NotNull TriggerContext context,
                                                             @NotNull RunningTrigger record,
                                                             TriggerDefinition.@NotNull Action action) {
        switch (action.getModifier()) {
            case TriggerModifiers.SET -> context.leaderboardComputers = 1;
            case TriggerModifiers.CLEAR -> context.leaderboardComputers = 0;
            case TriggerModifiers.TOGGLE -> context.leaderboardComputers ^= 1;
            default -> {
            }
        }

        return true;
    }

    /**
     * Executes Set Next Scenario: a nonzero string index names the scenario that the campaign flow loads after victory.
     */
    private static boolean executeSetNextScenario(@NotNull TriggerContext context,
                                                  @NotNull RunningTrigger record,
                                                  TriggerDefinition.@NotNull Action action) {
        if ((action.getStringIndex() & WORD_MASK) != 0) {
            context.nextScenarioString = action.getStringIndex() & WORD_MASK;
        }

        return true;
    }

    /**
     * Executes Disable Debug Mode and Enable Debug Mode: disable sets {@link TriggerFlags#EXECUTION_WAIT_SKIPPING_DISABLED}
     * on the current record, enable clears it.
     */
    private static boolean executeDebugMode(@NotNull TriggerContext context, @NotNull RunningTrigger record, TriggerDefinition.@NotNull Action action) {
        if (action.getType() == TriggerActionIds.DISABLE_DEBUG_MODE) {
            record.executionFlags |= TriggerFlags.EXECUTION_WAIT_SKIPPING_DISABLED;
        } else {
            record.executionFlags &= ~TriggerFlags.EXECUTION_WAIT_SKIPPING_DISABLED;
        }

        return true;
    }

    /**
     * Applies the checks of Pause Game (or Unpause Game) before they change the game loop: a multiplayer session, a game
     * already paused (not paused) by a trigger, a non-interactive owner and, for pause, a record that ignores interface
     * actions make the handler succeed without effect.
     *
     * @param context the trigger context
     * @param record  the running trigger
     * @param pause   {@code true} for Pause Game, {@code false} for Unpause Game
     * @return {@code true} if the handler would pause or resume the game loop, {@code false} otherwise
     */
    private static boolean canChangePauseState(@NotNull TriggerContext context, @NotNull RunningTrigger record, boolean pause) {
        if ((context.multiplayerSession & BYTE_MASK) != 0 || (context.triggersEnabledWhileGated != 0) == pause) {
            return false;
        }

        int playerType = context.getPlayerType(context.currentPlayer);

        if (isNonInteractive(playerType)) {
            return false;
        }

        return !pause || (record.executionFlags & TriggerFlags.EXECUTION_IGNORE_INTERFACE_ACTIONS) == 0;
    }

    /**
     * Applies the resuming half of Unpause Game: the current record loses {@link TriggerFlags#EXECUTION_PAUSED_GAME}, the
     * trigger pause and the game-paused state clear; the camera recentering and menu restore are presentation.
     */
    private static void resumeGame(@NotNull TriggerContext context, @NotNull RunningTrigger record) {
        record.executionFlags &= ~TriggerFlags.EXECUTION_PAUSED_GAME;
        context.triggersEnabledWhileGated = 0;
        context.phaseGate                 = 0;

        context.events.add(TriggerEvents.GAME_PAUSE, 0, 0, 0);
    }

    /**
     * Applies Set Alliance Status to one player: the ally status becomes allied, the allied victory status allied
     * victory, anything else enemy; the current player's own entry is never selected. A nonzero value different from
     * the current player's diagonal then replaces every nonzero entry of the current player's row, including the
     * diagonal and the neutral player. The bytes are addressed unchecked ({@link TriggerPlayersUtils#readAlliance}): player 12 is column 0 of
     * the next row, and the row of a current player past player 11 lies in the game data after the alliance table.
     *
     * @param context the trigger context
     * @param target  the target player, {@code 0} through {@code 12}
     * @param status  the action's status WORD
     * @throws UnsupportedOperationException if the EUD address space does not represent a byte outside the table
     */
    static void setAllianceStatus(@NotNull TriggerContext context, int target, int status) {
        int current = context.currentPlayer;

        if (target == current) {
            return;
        }

        int value = status == STATUS_ALLY ? PlayerRelations.ALLIED
                                          : status == STATUS_ALLIED_VICTORY ? PlayerRelations.ALLIED_VICTORY : PlayerRelations.ENEMY;

        TriggerPlayersUtils.writeAlliance(context, current, target, value);

        if (value != PlayerRelations.ENEMY && value != TriggerPlayersUtils.readAlliance(context, current, current)) {
            for (int player = 0; player < PlayerTable.PLAYER_SLOTS; player++) {
                if (TriggerPlayersUtils.readAlliance(context, current, player) != PlayerRelations.ENEMY) {
                    TriggerPlayersUtils.writeAlliance(context, current, player, value);
                }
            }
        }
    }

    /**
     * Tests the owner gate of Run AI Script and Run AI Script At Location: the scripts human players may start run for
     * every player, other scripts only for computer players, and the rescue script also for neutral players. The player
     * record is read only for the other scripts ({@link TriggerContext#getPlayerType}).
     *
     * @param context the trigger context
     * @param script  the script identifier
     * @param player  the current player
     * @return {@code true} if the script runs for the player, {@code false} otherwise
     * @throws UnsupportedOperationException if the record of the player lies past the sixteen player records
     */
    static boolean canRunScript(@NotNull TriggerContext context, int script, int player) {
        if (isHumanScript(script)) {
            return true;
        }

        int playerType = context.getPlayerType(player);

        return playerType == PlayerTypes.COMPUTER || playerType == PlayerTypes.NEUTRAL && script == RESCUE_SCRIPT;
    }

    /**
     * Tests whether human players may start a computer script.
     *
     * @param script the script identifier
     * @return {@code true} if human players may start the script, {@code false} otherwise
     */
    private static boolean isHumanScript(int script) {
        return switch (script) {
            case ENBK_SCRIPT, ENTR_SCRIPT, EXTR_SCRIPT, DWHE_SCRIPT, PB3F_SCRIPT -> true;
            default -> (script & SCRIPT_PLAYER_DIGIT_MASK) == SHARED_VISION_ON_SCRIPT ||
                       (script & SCRIPT_PLAYER_DIGIT_MASK) == SHARED_VISION_OFF_SCRIPT;
        };
    }

    private static @NotNull String formatScript(int script) {
        StringBuilder name = new StringBuilder();

        for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
            name.append((char) (script >>> shift & BYTE_MASK));
        }

        return name.toString();
    }


    private TriggerActionsUtils() {
    }
}
