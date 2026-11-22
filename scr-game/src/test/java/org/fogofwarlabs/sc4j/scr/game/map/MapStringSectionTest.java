package org.fogofwarlabs.sc4j.scr.game.map;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Checks the string lookup of the map string section and its registered strings.
 */
final class MapStringSectionTest {
    /**
     * The string count of the fixture sections.
     */
    private static final int STRING_COUNT = 2;
    /**
     * The largest string count of a {@code STRx} section that leaves no WORD string index to register.
     */
    private static final int FULL_STRING_COUNT = 0xFFFF;

    /**
     * Creates a {@code STR } section with the strings {@code "ab"} and {@code "c"}.
     *
     * @return the section
     */
    private static @NotNull MapStringSection createSection() {
        ByteBuffer payload = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN);

        payload.putShort((short) STRING_COUNT);
        payload.putShort((short) 6);
        payload.putShort((short) 9);
        payload.put("ab\0c\0".getBytes(StandardCharsets.US_ASCII));

        return new MapStringSection(payload.array(), false);
    }

    private static byte @NotNull [] encode(@NotNull String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }


    @Test
    void register_string_should_take_the_indices_after_the_string_count() {
        MapStringSection section = createSection();

        Assertions.assertEquals(STRING_COUNT + 1, section.registerString(encode("first")));
        Assertions.assertEquals(STRING_COUNT + 2, section.registerString(encode("second")));
        Assertions.assertEquals(2, section.getRegisteredStringCount());
        Assertions.assertEquals("ab", section.getString(1));
        Assertions.assertEquals("c", section.getString(2));
        Assertions.assertEquals("first", section.getString(STRING_COUNT + 1));
        Assertions.assertArrayEquals(encode("second"), section.getStringBytes(STRING_COUNT + 2));
        Assertions.assertNull(section.getStringBytes(STRING_COUNT + 3));
    }

    @Test
    void register_string_should_reject_a_nul() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> createSection().registerString(new byte[]{'a', 0, 'b'}));
    }

    @Test
    void register_string_should_fail_when_no_word_index_is_left() {
        ByteBuffer payload = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);

        payload.putInt(FULL_STRING_COUNT);

        MapStringSection section = new MapStringSection(payload.array(), true);

        Assertions.assertThrows(IllegalStateException.class, () -> section.registerString(encode("text")));
    }

    @Test
    void compute_registered_string_hash_should_follow_the_registrations() {
        MapStringSection first = createSection();
        MapStringSection second = createSection();
        MapStringSection other = createSection();

        first.registerString(encode("text"));
        second.registerString(encode("text"));
        other.registerString(encode("other"));

        Assertions.assertEquals(first.computeRegisteredStringHash(0), second.computeRegisteredStringHash(0));
        Assertions.assertNotEquals(first.computeRegisteredStringHash(0), other.computeRegisteredStringHash(0));
    }
}
