package org.fogofwarlabs.sc4j.scr.data.format.replay;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.text.TerminatedStringUtils;
import org.jetbrains.annotations.NotNull;

/**
 * Splits command bytes into commands by the command lengths of the game.
 * <p>
 * Two length rules exist. The stored length ({@link #getStoredLength}) is the length table the replay loader splits the
 * recorded command stream with: a fixed length per command type, the handle count for the selections and the data length
 * for the SC4J mod command ({@link ReplayModCommand}). The command
 * length ({@link #getCommandLength}) is what the command dispatcher consumes when it runs a buffer of commands: the
 * stored length, except Sync ({@value #SYNC_COMMAND_LENGTH} bytes) and Save Game or Load Game (the time DWORD and a
 * terminated file name without control characters), and none for a command type without a dispatcher case.
 * <p>
 * {@link #walk} runs the dispatcher's walk over a buffer: a command that has no dispatcher case, or whose command length
 * exceeds the bytes left in the buffer, is not run and ends the walk. {@link #walkReplayTurn} runs its walk over a received
 * turn during replay playback, which runs only a few command types.
 */
public final class ReplayCommandUtils {
    /**
     * The length of Sync as the dispatcher consumes it, type byte included.
     */
    public static final int SYNC_COMMAND_LENGTH = 7;

    /**
     * The unit handle width of the selection and target commands before the {@code 1.21} command types.
     */
    static final int LEGACY_HANDLE_WIDTH = 2;
    /**
     * The unit handle width of the selection and target commands from the {@code 1.21} command types on.
     */
    static final int HANDLE_WIDTH_121 = 4;

    private static final int BYTE_MASK = 0xFF;
    /**
     * The number of command type bytes.
     */
    private static final int TYPE_COUNT = 1 << Byte.SIZE;
    /**
     * The length result of a command type without a length.
     */
    private static final int NO_LENGTH = -1;
    /**
     * The stored length of each command type, type byte included, or {@code -1} for a type without one; a type past the
     * table has none. The selection entries hold the length of twelve handles; the loader computes the selections from
     * their handle count instead.
     */
    private static final int @NotNull [] STORED_LENGTHS = {
            -1, -1, -1, -1, -1, 1, 33, 33, 1, 26, 26, 26, 8, 3, 5, 2,
            1, 1, 5, 3, 10, 11, -1, -1, 1, 1, 2, 1, 1, 1, 2, 3,
            3, 2, 2, 3, 1, 2, 2, 1, 2, 3, 1, 2, 2, 2, 1, 5,
            2, 1, 2, 1, 1, 3, 1, 1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, 2, 10, 2, 5, -1, 1, -1, 82, 5, -1, 2,
            12, 13, 5, 50, 50, 50, 4, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1
    };
    /**
     * The command types the dispatcher has a case for; every other type ends the dispatcher's walk.
     */
    private static final int @NotNull [] DISPATCHED_TYPES = {
            ReplayCommandIds.KEEP_ALIVE, ReplayCommandIds.SAVE_GAME, ReplayCommandIds.LOAD_GAME, ReplayCommandIds.RESTART_GAME,
            ReplayCommandIds.SELECT, ReplayCommandIds.SELECT_ADD, ReplayCommandIds.SELECT_REMOVE, ReplayCommandIds.BUILD,
            ReplayCommandIds.VISION, ReplayCommandIds.ALLIANCE, ReplayCommandIds.GAME_SPEED, ReplayCommandIds.PAUSE, ReplayCommandIds.RESUME,
            ReplayCommandIds.CHEAT, ReplayCommandIds.HOTKEY, ReplayCommandIds.RIGHT_CLICK, ReplayCommandIds.TARGETED_ORDER,
            ReplayCommandIds.CANCEL_BUILD, ReplayCommandIds.CANCEL_MORPH, ReplayCommandIds.STOP, ReplayCommandIds.CARRIER_STOP,
            ReplayCommandIds.REAVER_STOP, ReplayCommandIds.ORDER_NOTHING, ReplayCommandIds.RETURN_CARGO, ReplayCommandIds.TRAIN,
            ReplayCommandIds.CANCEL_TRAIN, ReplayCommandIds.CLOAK, ReplayCommandIds.DECLOAK, ReplayCommandIds.UNIT_MORPH,
            ReplayCommandIds.UNSIEGE, ReplayCommandIds.SIEGE, ReplayCommandIds.TRAIN_FIGHTER, ReplayCommandIds.UNLOAD_ALL,
            ReplayCommandIds.UNLOAD, ReplayCommandIds.MERGE_ARCHON, ReplayCommandIds.HOLD_POSITION, ReplayCommandIds.BURROW,
            ReplayCommandIds.UNBURROW, ReplayCommandIds.CANCEL_NUKE, ReplayCommandIds.LIFT_OFF, ReplayCommandIds.TECH,
            ReplayCommandIds.CANCEL_TECH, ReplayCommandIds.UPGRADE, ReplayCommandIds.CANCEL_UPGRADE, ReplayCommandIds.CANCEL_ADDON,
            ReplayCommandIds.BUILDING_MORPH, ReplayCommandIds.STIM, ReplayCommandIds.SYNC, ReplayCommandIds.LATENCY,
            ReplayCommandIds.REPLAY_SPEED, ReplayCommandIds.LEAVE_GAME, ReplayCommandIds.MINIMAP_PING, ReplayCommandIds.MERGE_DARK_ARCHON,
            ReplayCommandIds.CHAT, ReplayCommandIds.REPLAY_SEEK, ReplayCommandIds.TURN_RATE, ReplayCommandIds.RIGHT_CLICK_121,
            ReplayCommandIds.TARGETED_ORDER_121, ReplayCommandIds.UNLOAD_121, ReplayCommandIds.SELECT_121, ReplayCommandIds.SELECT_ADD_121,
            ReplayCommandIds.SELECT_REMOVE_121, ReplayCommandIds.DYNAMIC_TURN_RATE, ReplayCommandIds.MOD_COMMAND
    };
    /**
     * Whether the dispatcher has a case for a command type, indexed by the type.
     */
    private static final boolean @NotNull [] DISPATCHED = new boolean[TYPE_COUNT];
    /**
     * The command types the dispatcher runs from a received turn during replay playback.
     */
    private static final int @NotNull [] REPLAY_TURN_TYPES = {
            ReplayCommandIds.KEEP_ALIVE, ReplayCommandIds.RESTART_GAME, ReplayCommandIds.PAUSE, ReplayCommandIds.RESUME,
            ReplayCommandIds.SYNC, ReplayCommandIds.LATENCY, ReplayCommandIds.REPLAY_SPEED, ReplayCommandIds.REPLAY_SEEK
    };
    /**
     * Whether the dispatcher runs a command type from a received turn during replay playback, indexed by the type.
     */
    private static final boolean @NotNull [] REPLAY_TURN = new boolean[TYPE_COUNT];

    /**
     * The bytes that give a selection's length: the type byte and the handle count.
     */
    private static final int SELECTION_HEADER_SIZE = 2;
    /**
     * The offset of a Save Game or Load Game command's file name: after the type byte and the time DWORD.
     */
    private static final int SAVED_GAME_NAME_OFFSET = 1 + Integer.BYTES;

    /**
     * Receives the commands of a walk.
     */
    public interface CommandVisitor {
        /**
         * Receives one command of a walk.
         *
         * @param data   the walked bytes
         * @param offset the offset of the command's type byte
         * @param length the command length, type byte included
         */
        void visit(byte @NotNull [] data, int offset, int length);
    }

    static {
        for (int typeIdentifier : DISPATCHED_TYPES) {
            DISPATCHED[typeIdentifier] = true;
        }

        for (int typeIdentifier : REPLAY_TURN_TYPES) {
            REPLAY_TURN[typeIdentifier] = true;
        }
    }

    /**
     * @param typeIdentifier the command type byte
     * @return {@code true} if the dispatcher has a case for the type, {@code false} otherwise
     */
    public static boolean isDispatched(int typeIdentifier) {
        return typeIdentifier >= 0 && typeIdentifier < DISPATCHED.length && DISPATCHED[typeIdentifier];
    }

    /**
     * @param typeIdentifier the command type byte
     * @return {@code true} if the dispatcher runs the type from a received turn during replay playback, {@code false} otherwise
     */
    public static boolean isReplayTurnType(int typeIdentifier) {
        return typeIdentifier >= 0 && typeIdentifier < REPLAY_TURN.length && REPLAY_TURN[typeIdentifier];
    }

    /**
     * Returns the stored length of a command: the length the replay loader splits the recorded command stream with. A type without a stored
     * length, a selection whose handle count lies past the bytes and a mod command whose length byte lies past the bytes or names more than
     * {@link ReplayModCommand#MAXIMUM_DATA_LENGTH} data bytes have none.
     *
     * @param data   the command bytes
     * @param offset the offset of the command's type byte
     * @return the length, type byte included, or {@code -1} for none
     */
    public static int getStoredLength(byte @NotNull [] data, int offset) {
        int typeIdentifier = data[offset] & BYTE_MASK;

        if (typeIdentifier == ReplayCommandIds.MOD_COMMAND) {
            if (offset + 1 >= data.length) {
                return NO_LENGTH;
            }

            int dataLength = data[offset + 1] & BYTE_MASK;

            return dataLength > ReplayModCommand.MAXIMUM_DATA_LENGTH ? NO_LENGTH : ReplayModCommand.HEADER_SIZE + dataLength;
        }

        if (ReplaySelectionCommand.supports(typeIdentifier)) {
            if (offset + 1 >= data.length) {
                return NO_LENGTH;
            }

            int handleWidth = typeIdentifier < ReplayCommandIds.SELECT_121 ? LEGACY_HANDLE_WIDTH : HANDLE_WIDTH_121;

            return SELECTION_HEADER_SIZE + (data[offset + 1] & BYTE_MASK) * handleWidth;
        }

        return typeIdentifier < STORED_LENGTHS.length ? STORED_LENGTHS[typeIdentifier] : NO_LENGTH;
    }

    /**
     * Returns the command length of a command the dispatcher would run from a buffer.
     *
     * @param data      the command bytes
     * @param offset    the offset of the command's type byte
     * @param remaining the bytes left in the buffer from the type byte on, at most {@code data.length - offset}
     * @return the length, type byte included, or {@code -1} if the type has no dispatcher case, the command does not fit in
     * the remaining bytes, or a Save Game or Load Game file name holds a control character before its terminator
     */
    public static int getCommandLength(byte @NotNull [] data, int offset, int remaining) {
        int typeIdentifier = data[offset] & BYTE_MASK;
        int length;

        if (!isDispatched(typeIdentifier)) {
            return NO_LENGTH;
        }

        if (typeIdentifier == ReplayCommandIds.SYNC) {
            length = SYNC_COMMAND_LENGTH;
        } else if (typeIdentifier == ReplayCommandIds.SAVE_GAME || typeIdentifier == ReplayCommandIds.LOAD_GAME) {
            length = getSavedGameLength(data, offset, remaining);
        } else {
            length = getStoredLength(data, offset);
        }

        return length > remaining ? NO_LENGTH : length;
    }

    /**
     * Returns the command length of a command type whose length does not depend on its bytes.
     *
     * @param typeIdentifier the command type byte
     * @return the length, type byte included, or {@code -1} for an undispatched type, a selection, Save Game, Load Game and the mod command
     */
    public static int getFixedLength(int typeIdentifier) {
        if (!isDispatched(typeIdentifier) || typeIdentifier == ReplayCommandIds.SAVE_GAME || typeIdentifier == ReplayCommandIds.LOAD_GAME ||
            typeIdentifier == ReplayCommandIds.MOD_COMMAND || ReplaySelectionCommand.supports(typeIdentifier)) {
            return NO_LENGTH;
        }

        return typeIdentifier == ReplayCommandIds.SYNC ? SYNC_COMMAND_LENGTH : STORED_LENGTHS[typeIdentifier];
    }

    /**
     * Walks a buffer of commands as the dispatcher does: each command that has a dispatcher case and fits in the bytes
     * left goes to the visitor in order; the first one that does not ends the walk.
     *
     * @param data    the command bytes
     * @param offset  the offset of the buffer's first command
     * @param length  the buffer length; a length of zero or less walks nothing
     * @param visitor the receiver of the walked commands
     * @return the number of bytes walked: {@code length} if every command ran, less if the walk ended early
     * @throws IndexOutOfBoundsException if the buffer extends past the bytes
     */
    public static int walk(byte @NotNull [] data, int offset, int length, @NotNull CommandVisitor visitor) {
        checkBuffer(data, offset, length);

        int position = offset;
        int remaining = length;

        while (remaining > 0) {
            int commandLength = getCommandLength(data, position, remaining);

            if (commandLength < 0) {
                break;
            }

            visitor.visit(data, position, commandLength);
            position += commandLength;
            remaining -= commandLength;
        }

        return position - offset;
    }

    /**
     * Walks a received turn as the dispatcher does during replay playback: the leading commands of the types it runs
     * there ({@link #isReplayTurnType}) that fit in the bytes left go to the visitor in order. The first other command ends
     * the walk: the dispatcher consumes the rest of the turn by that command's stored length without moving past it, so no
     * later command runs.
     *
     * @param data    the turn bytes
     * @param offset  the offset of the turn's first command
     * @param length  the turn length; a length of zero or less walks nothing
     * @param visitor the receiver of the walked commands
     * @return the number of bytes of the commands that ran
     * @throws IndexOutOfBoundsException if the turn extends past the bytes
     */
    public static int walkReplayTurn(byte @NotNull [] data, int offset, int length, @NotNull CommandVisitor visitor) {
        checkBuffer(data, offset, length);

        int position = offset;
        int remaining = length;

        while (remaining > 0 && isReplayTurnType(data[position] & BYTE_MASK)) {
            int commandLength = getCommandLength(data, position, remaining);

            if (commandLength < 0) {
                break;
            }

            visitor.visit(data, position, commandLength);
            position += commandLength;
            remaining -= commandLength;
        }

        return position - offset;
    }

    private static void checkBuffer(byte @NotNull [] data, int offset, int length) {
        if (length > 0 && (offset < 0 || offset + length > data.length)) {
            throw Exceptions.newIndexOutOfBoundsException("The {0}-byte command buffer at offset {1} extends past {2} bytes.",
                                                          length, offset, data.length);
        }
    }

    /**
     * Returns the length of a Save Game or Load Game command: the type byte, the time DWORD and the file name up to its
     * terminator, which the dispatcher searches in the first {@link ReplayFile#SAVED_GAME_NAME_SIZE} name bytes
     * ({@link TerminatedStringUtils#getTerminatedLength}). A control character before the terminator ends the search.
     *
     * @param data      the command bytes
     * @param offset    the offset of the command's type byte
     * @param remaining the bytes left in the buffer from the type byte on
     * @return the length, or {@code -1} without a terminator in the searched bytes or with a control character before it
     */
    private static int getSavedGameLength(byte @NotNull [] data, int offset, int remaining) {
        int nameLimit = Math.min(remaining - SAVED_GAME_NAME_OFFSET, ReplayFile.SAVED_GAME_NAME_SIZE);
        int nameLength = TerminatedStringUtils.getTerminatedLength(data, offset + SAVED_GAME_NAME_OFFSET, nameLimit);

        return nameLength == 0 ? NO_LENGTH : SAVED_GAME_NAME_OFFSET + nameLength;
    }


    private ReplayCommandUtils() {
    }
}
