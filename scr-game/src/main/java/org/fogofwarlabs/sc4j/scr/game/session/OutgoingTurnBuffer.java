package org.fogofwarlabs.sc4j.scr.game.session;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandIds;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Retains the local outgoing turn bytes and length, including commands queued through EUD writes.
 * <p>
 * Sending copies the used bytes, Keep Alive for an empty turn, and queues the next turn's Sync command. Bytes beyond the
 * length retain earlier turns. Once a game starts its Sync records ({@link #startSync}), the queued Sync command carries
 * the newest record of its ring at the time it is queued, and a sent turn's leading Sync command carries the newest record
 * at the time it is sent. EUD writes that change the Sync command stop explicitly.
 */
public final class OutgoingTurnBuffer {
    /**
     * The buffer size.
     */
    public static final int SIZE = 0x200;
    /**
     * The Sync command type.
     */
    public static final int SYNC_COMMAND = ReplayCommandIds.SYNC;
    /**
     * The length of the Sync command, type byte included.
     */
    public static final int SYNC_COMMAND_LENGTH = ReplayCommandUtils.SYNC_COMMAND_LENGTH;

    /**
     * The Keep Alive command type.
     */
    public static final int KEEP_ALIVE_COMMAND = ReplayCommandIds.KEEP_ALIVE;

    /**
     * The offset of the first Sync record byte, after the command type.
     */
    private static final int RECORD_START = 1;
    private static final int BYTE_MASK = 0xFF;
    /**
     * The upper-case hexadecimal digits of the byte values in the messages.
     */
    private static final @NotNull HexFormat MESSAGE_HEX_FORMAT = HexFormat.of().withUpperCase();

    private final byte @NotNull [] bytes;
    private final int maximumBytes;

    private int length;
    private boolean keepAlive;
    /**
     * Whether a send queues no Sync command for the next turn: an observer's game.
     */
    private boolean observer;
    /**
     * The Sync records of the game, or {@code null} before the game starts them: the Sync command then carries zero bytes.
     */
    private @Nullable SyncRing syncRing;

    /**
     * Creates an {@link OutgoingTurnBuffer} instance with the Sync command queued.
     *
     * @param maximumBytes the maximum bytes of one turn
     */
    public OutgoingTurnBuffer(int maximumBytes) {
        this.bytes        = new byte[SIZE];
        this.maximumBytes = maximumBytes;

        this.length    = 0;
        this.keepAlive = false;
        this.observer  = false;
        this.syncRing  = null;

        this.queueSync();
    }

    /**
     * @return the used buffer length
     */
    public int getLength() {
        return this.length;
    }

    /**
     * Sets the length DWORD as an EUD write does; the next send uses it.
     *
     * @param length the new length
     */
    public void setLength(int length) {
        this.length = length;
    }


    /**
     * Reads one buffer byte.
     *
     * @param offset the offset in the buffer
     * @return the unsigned byte value
     */
    public int readByte(int offset) {
        return this.bytes[offset] & BYTE_MASK;
    }

    /**
     * Writes one buffer byte.
     *
     * @param offset the offset in the buffer
     * @param value  the byte
     * @throws UnsupportedOperationException if the write changes a byte of the Sync command
     */
    public void writeByte(int offset, int value) {
        if (offset < SYNC_COMMAND_LENGTH && (this.bytes[offset] & BYTE_MASK) != (value & BYTE_MASK)) {
            throw Exceptions.newUnsupportedOperationException(
                    "An EUD write of 0x{0} to outgoing turn byte 0x{1} changes the queued Sync command, whose sending is then not represented.",
                    MESSAGE_HEX_FORMAT.toHexDigits((byte) value), Integer.toHexString(offset).toUpperCase(Locale.ROOT)
            );
        }

        this.bytes[offset] = (byte) value;
    }

    /**
     * @param commandLength the length of a command, type byte included
     * @return {@code true} if a command of the length fits in the turn after the queued bytes, {@code false} otherwise
     */
    public boolean hasRoom(int commandLength) {
        return this.length + commandLength <= this.maximumBytes;
    }

    /**
     * Queues one command at the current buffer length.
     *
     * @param command the command bytes, type byte first
     * @throws UnsupportedOperationException if the turn would exceed the configured maximum
     */
    public void queue(byte @NotNull [] command) {
        if (this.length + command.length > this.maximumBytes) {
            throw Exceptions.newUnsupportedOperationException(
                    "Queuing {0} command bytes on {1} passes the maximum turn bytes 0x{2}; the extra turn send is not represented.",
                    command.length, this.length, Integer.toHexString(this.maximumBytes).toUpperCase(Locale.ROOT)
            );
        }

        System.arraycopy(command, 0, this.bytes, this.length, command.length);

        this.length += command.length;
    }

    /**
     * Starts the Sync commands of the game from its Sync records, as the game start queues the Sync command of its first
     * record: the queued Sync command takes the newest record of the ring now, and each send takes it again for the sent
     * turn and queues the next turn's Sync command with the newest record.
     *
     * @param syncRing the game's Sync records
     */
    public void startSync(@NotNull SyncRing syncRing) {
        this.syncRing = syncRing;

        if (!this.keepAlive) {
            this.writeSyncRecord(this.bytes);
        }
    }

    /**
     * Sends the turn: an empty turn first becomes Keep Alive ({@code 05}) at the buffer start, then the bytes below the
     * length are taken, a leading Sync command takes the newest record of the game's Sync records, and the Sync command of
     * the next turn is queued; an observer's game ({@link #useObserverTurns()}) leaves the next turn empty instead.
     *
     * @return the turn bytes
     * @throws UnsupportedOperationException if the length DWORD lies outside the buffer
     */
    public byte @NotNull [] send() {
        if (this.length < 0 || this.length > SIZE) {
            throw Exceptions.newUnsupportedOperationException("The outgoing turn length 0x{0} lies outside the 0x{1}-byte turn buffer.",
                                                              Integer.toHexString(this.length).toUpperCase(Locale.ROOT),
                                                              Integer.toHexString(SIZE).toUpperCase(Locale.ROOT));
        }

        if (this.length == 0) {
            this.bytes[0] = (byte) KEEP_ALIVE_COMMAND;
            this.length   = 1;
        }

        byte[] turn = Arrays.copyOf(this.bytes, this.length);

        if (turn.length >= SYNC_COMMAND_LENGTH && turn[0] == SYNC_COMMAND) {
            this.writeSyncRecord(turn);
        }

        if (this.observer) {
            this.length = 0;
        } else {
            this.queueSync();
        }

        return turn;
    }

    /**
     * Queues no Sync command after a send, as an observer's game does: the turns after the queued one carry only the
     * commands queued for them, Keep Alive ({@code 05}) when none.
     */
    public void useObserverTurns() {
        this.observer = true;
    }

    /**
     * Starts every following turn with Keep Alive ({@code 05}) instead of the Sync command, and requeues the current one.
     * <p>
     * Keep Alive supplies the required turn command without a Sync record.
     */
    public void useKeepAlive() {
        this.keepAlive = true;
        this.queueSync();
    }

    private void queueSync() {
        if (this.keepAlive) {
            this.bytes[0] = (byte) KEEP_ALIVE_COMMAND;
            this.length   = 1;

            return;
        }

        this.bytes[0] = (byte) SYNC_COMMAND;
        this.writeSyncRecord(this.bytes);

        this.length = SYNC_COMMAND_LENGTH;
    }

    /**
     * Writes the record bytes of a Sync command at the start of a turn: those of the newest record of the game's Sync
     * records ({@link SyncRing#createCommand()}), or zero bytes before the game starts them.
     *
     * @param turn the turn whose leading Sync command takes the record
     */
    private void writeSyncRecord(byte @NotNull [] turn) {
        SyncRing ring = this.syncRing;

        if (ring == null) {
            Arrays.fill(turn, RECORD_START, SYNC_COMMAND_LENGTH, (byte) 0);

            return;
        }

        System.arraycopy(ring.createCommand(), RECORD_START, turn, RECORD_START, SYNC_COMMAND_LENGTH - RECORD_START);
    }
}
