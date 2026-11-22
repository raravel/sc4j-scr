package org.fogofwarlabs.sc4j.scr.data.format.replay;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.command.CommandInput;
import org.fogofwarlabs.sc4j.engine.command.CommandOutput;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Arrays;

/**
 * Represents the SC4J mod command ({@link ReplayCommandIds#MOD_COMMAND}): data that the simulation plugins of a mod send through the command
 * stream. The payload is the data length BYTE followed by the data bytes; the data is at most {@link #MAXIMUM_DATA_LENGTH} bytes, so the whole
 * command (type byte, length byte and data) fits in one command block of a replay.
 */
public final class ReplayModCommand {
    /**
     * The largest data length: a replay command block holds less than {@code 255} bytes with the player byte of each command, so a command
     * has at most {@code 253} bytes, its type byte and its length byte included.
     */
    public static final int MAXIMUM_DATA_LENGTH = 251;
    /**
     * The bytes before the data in a command: the type byte and the length byte.
     */
    public static final int HEADER_SIZE = 2;

    /**
     * The codec of the command: a read takes the length byte and exactly that many data bytes.
     */
    public static final @NotNull ReplayCommandCodec<ReplayModCommand> CODEC = new ReplayCommandCodec<>() {
        @Override
        public @NotNull ReplayModCommand read(@NotNull CommandInput input) throws IOException {
            int length = input.readUnsignedByte();

            if (length > MAXIMUM_DATA_LENGTH || length != input.getRemaining()) {
                throw Exceptions.newIOException("The mod command names {0} data bytes, but {1} follow.", length, input.getRemaining());
            }

            return new ReplayModCommand(input.readBytes(length));
        }

        @Override
        public void write(@NotNull ReplayModCommand command, @NotNull CommandOutput output) {
            output.writeByte(command.data.length).writeBytes(command.data);
        }
    };

    private final byte @NotNull [] data;

    /**
     * Creates a {@link ReplayModCommand} instance.
     *
     * @param data the data, owned by the command
     * @throws IllegalArgumentException if the data is longer than {@link #MAXIMUM_DATA_LENGTH} bytes
     */
    public ReplayModCommand(byte @NotNull [] data) {
        if (data.length > MAXIMUM_DATA_LENGTH) {
            throw Exceptions.newIllegalArgumentException("A mod command holds at most {0} data bytes, not {1}.", MAXIMUM_DATA_LENGTH, data.length);
        }

        this.data = data;
    }

    /**
     * @return the number of data bytes
     */
    public int getLength() {
        return this.data.length;
    }

    /**
     * Copies the data.
     *
     * @return the data bytes
     */
    public byte @NotNull [] copyData() {
        return Arrays.copyOf(this.data, this.data.length);
    }
}
