package org.fogofwarlabs.sc4j.scr.game.replay;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.command.CommandInput;
import org.fogofwarlabs.sc4j.engine.command.CommandRegistry;
import org.fogofwarlabs.sc4j.engine.player.Player;
import org.fogofwarlabs.sc4j.scr.data.format.replay.*;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.PauseNotice;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameEnd;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes recorded commands at their frame boundary: the native command dispatcher. A command runs as the engine command type of its type byte
 * ({@link ScrCommandType}, created by {@link NativeCommandTable} and found in an engine {@link CommandRegistry}), for the record its issuer
 * resolves to. Selection requires established pool and context state; malformed payloads remain command barriers. Live turn validation is
 * outside this executor.
 */
public final class ReplayCommandExecutor implements ReplayCommandHandler {
    /**
     * The offset of the Chat command's text in its payload, after the sender byte.
     */
    static final int CHAT_TEXT_OFFSET = 1;
    /**
     * The default instruction budget per script invocation.
     */
    private static final int DEFAULT_INSTRUCTION_LIMIT = 100000;
    /**
     * The size of the name length DWORD that starts a Save Game or Load Game payload.
     */
    private static final int SAVE_NAME_LENGTH_SIZE = Integer.BYTES;

    /**
     * Controls the replay viewer playback paused by Pause and Resume commands and sought by Replay Seek commands, recorded
     * or of the local player's received turns, and paused and continued by the Replay Speed commands of the received turns
     * ({@link ReplaySession}).
     */
    public interface PlaybackControl {
        /**
         * @return {@code true} if the viewer's playback is paused, {@code false} otherwise
         */
        boolean isPlaybackPaused();

        /**
         * Sets whether the viewer's playback is paused.
         *
         * @param paused whether playback pauses
         */
        void setPlaybackPaused(boolean paused);

        /**
         * Seeks the viewer's playback to a frame: a frame before the current one restarts the replay first.
         *
         * @param targetFrame the frame to reach, the replay's frame count at most
         */
        void seekReplay(int targetFrame);
    }

    /**
     * The replay's frame count, the largest frame a Replay Seek command seeks to (an unsigned DWORD).
     */
    private final int replayFrames;

    private @Nullable PlaybackControl playbackControl;
    /**
     * The playback state retained by Pause and restored by Resume.
     */
    private boolean playbackPausedAtPause;
    /**
     * The pauses and resumes executed since the interface last took them ({@link #takePauseNotices}).
     */
    private final @NotNull List<PauseNotice> pauseNotices;

    /**
     * The native command types of this dispatcher by type byte ({@link NativeCommandTable}), and the input their payloads are read with.
     */
    private final @NotNull CommandRegistry<ScrCommandType<?>> commandTypes;
    private final @NotNull CommandInput input;

    /**
     * Creates a {@link ReplayCommandExecutor} instance with the default script budget and the replay frame count {@code 0}.
     */
    public ReplayCommandExecutor() {
        this(DEFAULT_INSTRUCTION_LIMIT);
    }

    /**
     * Creates a {@link ReplayCommandExecutor} instance using the session's script budget and the replay frame count {@code 0}.
     *
     * @param instructionLimit the positive instruction budget per script invocation
     * @throws IllegalArgumentException if the budget is not positive
     */
    public ReplayCommandExecutor(int instructionLimit) {
        this(instructionLimit, 0);
    }

    /**
     * Creates a {@link ReplayCommandExecutor} instance using the session's script budget and the replay's frame count.
     *
     * @param instructionLimit the positive instruction budget per script invocation
     * @param replayFrames     the replay's frame count (an unsigned DWORD), the largest frame a Replay Seek command seeks to
     * @throws IllegalArgumentException if the budget is not positive
     */
    public ReplayCommandExecutor(int instructionLimit, int replayFrames) {
        if (instructionLimit <= 0) {
            throw Exceptions.newIllegalArgumentException("The instruction budget must be positive: {0}", instructionLimit);
        }

        this.replayFrames = replayFrames;

        this.playbackControl       = null;
        this.playbackPausedAtPause = false;
        this.pauseNotices          = new ArrayList<>();

        this.commandTypes = new NativeCommandTable(this, instructionLimit).createTypes();
        this.input        = new CommandInput();
    }

    /**
     * @return the native command types of this dispatcher by type byte: their codecs encode and decode the command payloads, their cases run
     * with this dispatcher's script budget and playback
     */
    public @NotNull CommandRegistry<ScrCommandType<?>> getCommandTypes() {
        return this.commandTypes;
    }

    /**
     * Sets the replay viewer's playback that Pause and Resume commands pause and continue and Replay Seek commands seek.
     * Without one (a live game, a headless run) Pause and Resume only change the game-paused state and Replay Seek does
     * nothing.
     *
     * @param playbackControl the viewer's playback, or {@code null} for none
     */
    public void setPlaybackControl(@Nullable PlaybackControl playbackControl) {
        this.playbackControl = playbackControl;
    }


    /**
     * Checks whether the command and its current represented issuer are supported.
     *
     * @param game    the simulation state
     * @param command the replay command
     * @return {@code true} if dispatch is supported with a resolved issuer, {@code false} otherwise; production
     * requirements are checked during execution
     */
    public boolean canExecute(@NotNull Game game, @NotNull ReplayCommand command) {
        if (command.getPlayerId() < 0 || command.getPlayerId() > ReplayCommandIssuer.LAST_ISSUER) {
            return false;
        }

        ReplayCommandIssuer issuer = ReplayCommandIssuer.resolve(game, command.getPlayerId());

        if (issuer.getStatus() != ReplayCommandIssuer.Status.RESOLVED) {
            return false;
        }

        int typeId = command.getTypeId();
        int payloadLength = command.getPayload().length;
        int commandPlayer = issuer.getCommandPlayerIndex();
        boolean playableIssuer = commandPlayer >= 0 && commandPlayer < PlayerTable.PLAYABLE_PLAYER_SLOTS;

        if (typeId == ReplayCommandIds.KEEP_ALIVE) {
            return payloadLength == 0;
        }

        if (!ReplayCommandUtils.isDispatched(typeId)) {
            return true;
        }

        if (typeId == ReplayCommandIds.SYNC || typeId == ReplayCommandIds.LATENCY || typeId == ReplayCommandIds.REPLAY_SPEED ||
            typeId == ReplayCommandIds.REPLAY_SEEK || typeId == ReplayCommandIds.TURN_RATE || typeId == ReplayCommandIds.DYNAMIC_TURN_RATE) {
            return payloadLength == ReplayCommandUtils.getFixedLength(typeId) - 1;
        }

        if (typeId == ReplayCommandIds.SAVE_GAME || typeId == ReplayCommandIds.LOAD_GAME) {
            return payloadLength >= SAVE_NAME_LENGTH_SIZE;
        }

        if (typeId == ReplayCommandIds.RESTART_GAME) {
            return payloadLength == 0 && game.getTriggerContext() != null;
        }

        if (typeId == ReplayCommandIds.PAUSE || typeId == ReplayCommandIds.RESUME) {
            return payloadLength == 0 && game.getTriggerContext() != null;
        }

        if (typeId == ReplayCommandIds.MOD_COMMAND) {
            int dataLength = payloadLength - Byte.BYTES;

            return dataLength >= 0 && dataLength <= ReplayModCommand.MAXIMUM_DATA_LENGTH &&
                   Byte.toUnsignedInt(command.getPayload()[0]) == dataLength &&
                   commandPlayer >= 0 && commandPlayer < PlayerTable.PLAYER_SLOTS;
        }

        UnitSelection selection = game.getSelection();

        if (!game.hasInitializedWorldState() || selection == null) {
            return false;
        }

        if (typeId == ReplayCommandIds.CHAT) {
            return payloadLength == CHAT_TEXT_OFFSET + ReplayFile.CHAT_MESSAGE_SIZE;
        }

        if (typeId == ReplayCommandIds.CHEAT) {
            return payloadLength == Integer.BYTES;
        }

        if (typeId == ReplayCommandIds.GAME_SPEED) {
            return payloadLength == Byte.BYTES;
        }

        if (typeId == ReplayCommandIds.BUILD) {
            return payloadLength == ReplayFile.BUILD_PAYLOAD && playableIssuer;
        }

        if (typeId == ReplayCommandIds.VISION || typeId == ReplayCommandIds.ALLIANCE || typeId == ReplayCommandIds.LEAVE_GAME) {
            int expectedLength = typeId == ReplayCommandIds.VISION ? Short.BYTES : typeId == ReplayCommandIds.ALLIANCE ? Integer.BYTES : Byte.BYTES;

            return payloadLength == expectedLength && commandPlayer >= 0 && commandPlayer < PlayerTable.PLAYER_SLOTS;
        }

        if (typeId == ReplayCommandIds.MERGE_ARCHON || typeId == ReplayCommandIds.MERGE_DARK_ARCHON) {
            return payloadLength == 0 && playableIssuer;
        }

        if (typeId == ReplayCommandIds.CANCEL_TRAIN || typeId == ReplayCommandIds.MINIMAP_PING) {
            return payloadLength == (typeId == ReplayCommandIds.CANCEL_TRAIN ? Short.BYTES : Integer.BYTES) && playableIssuer;
        }

        if (typeId == ReplayCommandIds.BURROW || typeId == ReplayCommandIds.UNBURROW ||
            typeId == ReplayCommandIds.CLOAK || typeId == ReplayCommandIds.DECLOAK ||
            typeId == ReplayCommandIds.UNSIEGE || typeId == ReplayCommandIds.SIEGE) {
            return payloadLength == Byte.BYTES && playableIssuer;
        }

        if (typeId == ReplayCommandIds.STIM || typeId == ReplayCommandIds.CANCEL_NUKE || typeId == ReplayCommandIds.ORDER_NOTHING) {
            return payloadLength == 0 && playableIssuer;
        }

        if (typeId == ReplayCommandIds.UNLOAD_ALL) {
            return payloadLength == Byte.BYTES && playableIssuer;
        }

        if (typeId == ReplayCommandIds.UNLOAD || typeId == ReplayCommandIds.UNLOAD_121) {
            return payloadLength == (typeId == ReplayCommandIds.UNLOAD ? Short.BYTES : Integer.BYTES) && playableIssuer;
        }

        if (typeId == ReplayCommandIds.CARRIER_STOP || typeId == ReplayCommandIds.REAVER_STOP || typeId == ReplayCommandIds.TRAIN_FIGHTER) {
            return payloadLength == 0 && playableIssuer;
        }

        if (typeId == ReplayCommandIds.STOP || typeId == ReplayCommandIds.RETURN_CARGO || typeId == ReplayCommandIds.HOLD_POSITION) {
            return payloadLength == Byte.BYTES && playableIssuer;
        }

        if (typeId == ReplayCommandIds.TECH || typeId == ReplayCommandIds.UPGRADE) {
            return payloadLength == Byte.BYTES && playableIssuer;
        }

        if (typeId == ReplayCommandIds.CANCEL_BUILD ||
            typeId == ReplayCommandIds.CANCEL_MORPH ||
            typeId == ReplayCommandIds.CANCEL_TECH ||
            typeId == ReplayCommandIds.CANCEL_UPGRADE) {
            return payloadLength == 0 && playableIssuer;
        }

        if (typeId == ReplayCommandIds.HOTKEY ||
            typeId == ReplayCommandIds.TRAIN ||
            typeId == ReplayCommandIds.UNIT_MORPH ||
            typeId == ReplayCommandIds.BUILDING_MORPH) {
            return payloadLength == Short.BYTES && playableIssuer;
        }

        if (typeId == ReplayCommandIds.LIFT_OFF || typeId == ReplayCommandIds.CANCEL_ADDON) {
            return payloadLength == (typeId == ReplayCommandIds.LIFT_OFF ? Integer.BYTES : 0) && playableIssuer;
        }

        if (ReplayTargetCommand.supports(typeId)) {
            if (!playableIssuer) {
                return false;
            }

            try {
                ReplayTargetCommand.parse(command);

                return true;
            } catch (IOException exception) {
                return false;
            }
        }

        if (!ReplaySelectionCommand.supports(typeId)) {
            return false;
        }

        try {
            SelectionRequest request = ReplaySelectionCommand.parse(command);

            selection.beginCommand(commandPlayer, issuer.getRecordIndex());

            try {
                return selection.canExecute(game, request, commandPlayer);
            } finally {
                selection.endCommand();
            }
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * Takes and clears the executed pause and resume notices for interface messages and palette changes.
     *
     * @return the notices in execution order
     */
    public @NotNull List<PauseNotice> takePauseNotices() {
        List<PauseNotice> taken = List.copyOf(this.pauseNotices);

        this.pauseNotices.clear();

        return taken;
    }

    /**
     * Executes the command before the simulation advances the current frame.
     *
     * @param game    the simulation state
     * @param command the replay command
     * @throws UnsupportedOperationException if the command or its issuer is unsupported, or the unit pool is not initialized
     */
    @Override
    public void execute(@NotNull Game game, @NotNull ReplayCommand command) {
        if (command.getPlayerId() >= 0 && command.getPlayerId() <= ReplayCommandIssuer.LAST_ISSUER &&
            ReplayCommandIssuer.resolve(game, command.getPlayerId()).getStatus() == ReplayCommandIssuer.Status.UNRESOLVED_EXTENDED_RECORDS) {
            return;
        }

        if (!this.canExecute(game, command)) {
            throw Exceptions.newUnsupportedOperationException("The replay command 0x{0} or its issuer {1} is unsupported.",
                                                              Integer.toHexString(command.getTypeId()),
                                                              command.getPlayerId());
        }

        ScrCommandType<?> type = this.commandTypes.find(command.getTypeId());

        if (type == null) {
            game.setCommandDispatchMarkerCount(0);

            return;
        }

        ReplayCommandIssuer issuer = ReplayCommandIssuer.resolve(game, command.getPlayerId());
        Player player = game.getEngineGame().getPlayer(issuer.getRecordIndex());

        CommandInput input = this.input.reset(command.getPayload(), 0, command.getPayload().length);

        this.dispatchRecorded(game, player, issuer.getCommandPlayerIndex(), type, input);
    }

    /**
     * Creates the engine command sink of this dispatcher for one game: a submitted command runs at once, as a recorded command of the same type
     * and payload whose issuer resolves to the submitting player runs.
     *
     * @param game the SC:R game the commands run in
     * @return the created {@link ScrCommandDispatchSink} instance
     */
    public @NotNull ScrCommandDispatchSink createSink(@NotNull Game game) {
        return new ScrCommandDispatchSink(this, game);
    }

    /**
     * Runs a decoded command of a player in the native dispatch of its type: a marker or session command runs alone; a selection command runs
     * between the begin and the end of the player's selection command. The marker sets the dispatch marker count to {@code 1}, every other type
     * resets it.
     *
     * @param <C>           the command class
     * @param game          the SC:R game
     * @param player        the engine player of the issuing record
     * @param commandPlayer the command player of the issuing record
     * @param type          the command type, one of this dispatcher's types
     * @param command       the command
     */
    <C> void dispatch(@NotNull Game game, @NotNull Player player, int commandPlayer, @NotNull ScrCommandType<C> type, @NotNull C command) {
        org.fogofwarlabs.sc4j.engine.game.Game engineGame = game.getEngineGame();

        if (type.getDispatch() != ScrCommandType.Dispatch.SELECTION) {
            type.execute(engineGame, player, command);
            game.setCommandDispatchMarkerCount(type.getDispatch() == ScrCommandType.Dispatch.MARKER ? 1 : 0);

            return;
        }

        UnitSelection commandSelection = game.getSelection();

        if (commandSelection != null) {
            commandSelection.beginCommand(commandPlayer, player.getIndex());
        }

        try {
            type.execute(engineGame, player, command);
            game.setCommandDispatchMarkerCount(0);
        } finally {
            if (commandSelection != null) {
                commandSelection.endCommand();
            }
        }
    }

    private <C> void dispatchRecorded(@NotNull Game game,
                                      @NotNull Player player,
                                      int commandPlayer,
                                      @NotNull ScrCommandType<C> type,
                                      @NotNull CommandInput input) {
        C command;

        try {
            command = type.read(input);
        } catch (IOException exception) {
            throw Exceptions.newUnsupportedOperationException(exception, "The recorded command payload is unsupported.");
        }

        this.dispatch(game, player, commandPlayer, type, command);
    }

    /**
     * Runs Restart Game: outside a network session it ends the game ({@link GameEnd#RESTART}); in a network session it
     * does nothing.
     *
     * @param game the simulation state
     * @throws IllegalStateException if the game has no trigger context, which holds the network session BYTE
     */
    void restartGame(@NotNull Game game) {
        TriggerContext triggers = game.getTriggerContext();

        if (triggers == null) {
            throw Exceptions.newIllegalStateException("Restart Game requires the trigger context.");
        }

        if (triggers.getMultiplayerSession() == 0) {
            game.end(GameEnd.RESTART);
        }
    }

    /**
     * Runs Replay Seek: during replay playback the viewer's playback seeks to the frame, the replay's frame count at most
     * (an unsigned comparison); otherwise, or without a viewer, it does nothing.
     *
     * @param game  the simulation state
     * @param frame the command's frame DWORD
     */
    void seekReplay(@NotNull Game game, int frame) {
        PlaybackControl playback = this.playbackControl;

        if (game.isReplayPlayback() && playback != null) {
            playback.seekReplay(Integer.compareUnsigned(frame, this.replayFrames) < 0 ? frame : this.replayFrames);
        }
    }

    /**
     * Applies Pause or Resume outside multiplayer, retaining the playback state across the pair. Each command adds a notice
     * without decrementing the initial pause count.
     *
     * @param game  the simulation state
     * @param slot  the issuing player slot
     * @param pause whether the command pauses or resumes playback
     * @throws IllegalStateException         if the game has no trigger context
     * @throws UnsupportedOperationException if the game is a multiplayer session
     */
    void pauseOrResume(@NotNull Game game, int slot, boolean pause) {
        TriggerContext triggers = game.getTriggerContext();

        if (triggers == null) {
            throw Exceptions.newIllegalStateException("Pause commands require the trigger context.");
        }

        if (triggers.getMultiplayerSession() != 0) {
            throw Exceptions.newUnsupportedOperationException("Multiplayer pause counts are not represented.");
        }

        PlaybackControl playback = this.playbackControl;

        if (pause) {
            if (!triggers.pauseGame()) {
                return;
            }

            this.playbackPausedAtPause = playback != null && playback.isPlaybackPaused();

            if (playback != null) {
                playback.setPlaybackPaused(true);
            }

            this.pauseNotices.add(new PauseNotice(slot, true, LiveGame.INITIAL_PAUSES));

            return;
        }

        if (!triggers.resumeGame()) {
            return;
        }

        if (playback != null && !this.playbackPausedAtPause) {
            playback.setPlaybackPaused(false);
        }

        this.pauseNotices.add(new PauseNotice(slot, false, LiveGame.INITIAL_PAUSES));
    }
}
