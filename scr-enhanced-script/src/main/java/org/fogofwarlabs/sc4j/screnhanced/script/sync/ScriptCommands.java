package org.fogofwarlabs.sc4j.screnhanced.script.sync;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayModCommand;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splits the encoded values of script commands into mod commands and joins the mod commands of each player back into values.
 * <p>
 * The data of a command is the tag {@value #TAG}, the flags (bit {@code 0}: more commands of the value follow; bit {@code 1}: the command
 * continues the value of the previous one) and a part of the encoded value, at most {@value #MAXIMUM_PART_LENGTH} bytes. The commands of a
 * value follow each other in the sending player's command stream, since the sender submits its commands in order; a value is complete with
 * the command without the more flag. A command with another tag is no script command; a first command while a value is open drops that value,
 * and a continuing command without an open value is dropped.
 */
public final class ScriptCommands {
    /**
     * The first byte of a script command.
     */
    public static final int TAG = 0xE3;
    /**
     * The bytes before the value part: the tag and the flags.
     */
    public static final int HEADER_LENGTH = 2;
    /**
     * The most value bytes one command carries.
     */
    public static final int MAXIMUM_PART_LENGTH = ReplayModCommand.MAXIMUM_DATA_LENGTH - HEADER_LENGTH;

    private static final int BYTE_MASK = 0xFF;
    private static final int TAG_OFFSET = 0;
    private static final int FLAGS_OFFSET = 1;
    private static final int MORE_FLAG = 1;
    private static final int CONTINUATION_FLAG = 2;
    private static final int KNOWN_FLAGS = MORE_FLAG | CONTINUATION_FLAG;

    /**
     * Splits an encoded value into the data of its commands, in sending order.
     *
     * @param value the encoded value, not empty
     * @return the data of each command
     * @throws IllegalArgumentException if the value is empty
     */
    public static @NotNull List<byte @NotNull []> split(byte @NotNull [] value) {
        if (value.length == 0) {
            throw Exceptions.newIllegalArgumentException("A script command has a value.");
        }

        List<byte[]> commands = new ArrayList<>();

        for (int offset = 0; offset < value.length; offset += MAXIMUM_PART_LENGTH) {
            int length = Math.min(MAXIMUM_PART_LENGTH, value.length - offset);
            byte[] data = new byte[HEADER_LENGTH + length];
            int more = offset + length < value.length ? MORE_FLAG : 0;
            int continuation = offset > 0 ? CONTINUATION_FLAG : 0;

            data[TAG_OFFSET]   = (byte) TAG;
            data[FLAGS_OFFSET] = (byte) (more | continuation);

            System.arraycopy(value, offset, data, HEADER_LENGTH, length);
            commands.add(data);
        }

        return commands;
    }


    private final @Nullable ByteArrayOutputStream @NotNull [] openValues;

    /**
     * Creates a {@link ScriptCommands} instance without open values.
     */
    public ScriptCommands() {
        this.openValues = new ByteArrayOutputStream[PlayerTable.PLAYER_SLOTS];
    }

    /**
     * Reads a mod command of a player: a script command adds its part to the player's open value.
     *
     * @param player the slot id of the issuing player
     * @param data   the command data
     * @return the value the command completed, or {@code null} if the data is no script command, the player is out of range or the value continues
     */
    public @Nullable ScriptCommandMessage read(int player, byte @NotNull [] data) {
        if (player < 0 || player >= PlayerTable.PLAYER_SLOTS || data.length < HEADER_LENGTH || (data[TAG_OFFSET] & BYTE_MASK) != TAG ||
            (data[FLAGS_OFFSET] & ~KNOWN_FLAGS) != 0) {
            return null;
        }

        boolean more = (data[FLAGS_OFFSET] & MORE_FLAG) != 0;
        boolean continuation = (data[FLAGS_OFFSET] & CONTINUATION_FLAG) != 0;
        ByteArrayOutputStream open = this.openValues[player];

        if (continuation != (open != null)) {
            this.openValues[player] = null;

            if (continuation) {
                return null;
            }

            open = null;
        }

        if (open == null) {
            if (!more) {
                return new ScriptCommandMessage(player, Arrays.copyOfRange(data, HEADER_LENGTH, data.length));
            }

            open = new ByteArrayOutputStream();

            this.openValues[player] = open;
        }

        open.write(data, HEADER_LENGTH, data.length - HEADER_LENGTH);

        if (more) {
            return null;
        }

        this.openValues[player] = null;

        return new ScriptCommandMessage(player, open.toByteArray());
    }
}
