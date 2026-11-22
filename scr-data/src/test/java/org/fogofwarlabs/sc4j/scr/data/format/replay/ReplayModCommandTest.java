package org.fogofwarlabs.sc4j.scr.data.format.replay;

import org.fogofwarlabs.sc4j.engine.command.CommandInput;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Tests the payload of the SC4J mod command: the length byte and the data, at most {@link ReplayModCommand#MAXIMUM_DATA_LENGTH} bytes.
 */
final class ReplayModCommandTest {
    @Test
    void codec_should_write_back_the_length_byte_and_the_data_it_read() throws IOException {
        byte[] payload = {3, 10, 20, 30};
        ReplayModCommand command = ReplayModCommand.CODEC.read(new CommandInput(payload));

        Assertions.assertEquals(3, command.getLength());
        Assertions.assertArrayEquals(new byte[]{10, 20, 30}, command.copyData());
        Assertions.assertArrayEquals(payload, ReplayCommandCodecs.encode(ReplayCommandCodecs.MOD_COMMAND, command));
        Assertions.assertSame(ReplayModCommand.CODEC, ReplayCommandCodecs.find(ReplayCommandIds.MOD_COMMAND));
    }

    @Test
    void codec_should_reject_a_length_byte_that_does_not_name_the_bytes_that_follow() {
        Assertions.assertThrows(IOException.class, () -> ReplayModCommand.CODEC.read(new CommandInput(new byte[]{2, 10, 20, 30})));
        Assertions.assertThrows(IOException.class, () -> ReplayModCommand.CODEC.read(new CommandInput(new byte[]{4, 10, 20, 30})));
    }

    @Test
    void constructor_should_reject_data_longer_than_the_maximum() {
        Assertions.assertEquals(ReplayModCommand.MAXIMUM_DATA_LENGTH, new ReplayModCommand(new byte[ReplayModCommand.MAXIMUM_DATA_LENGTH]).getLength());
        Assertions.assertThrows(IllegalArgumentException.class, () -> new ReplayModCommand(new byte[ReplayModCommand.MAXIMUM_DATA_LENGTH + 1]));
    }
}
