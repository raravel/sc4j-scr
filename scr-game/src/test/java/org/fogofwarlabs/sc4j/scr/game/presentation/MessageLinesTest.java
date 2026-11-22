package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines.TextLayout;
import org.fogofwarlabs.sc4j.scr.game.test.PlayerProgressTestUtils;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Checks message rotation, expiry, chat, elimination and departure formatting and observable line access.
 */
final class MessageLinesTest {
    /**
     * The layout that keeps every text on one line.
     */
    private static final @NotNull TextLayout SINGLE_LINE = text -> new int[]{text.length};

    /**
     * The first message line after a game-start clear.
     */
    private static final int FIRST_LINE = 10;
    /**
     * The line before {@link #FIRST_LINE}, which the last of eleven rotated messages uses.
     */
    private static final int LINE_BEFORE_FIRST = 9;
    /**
     * The line that the message after the last line wraps to.
     */
    private static final int WRAPPED_LINE = 0;
    /**
     * The line after {@link #WRAPPED_LINE}.
     */
    private static final int NEXT_WRAPPED_LINE = 1;
    /**
     * The messages of the rotation test: one more than the eleven rotated lines.
     */
    private static final int ROTATED_MESSAGE_COUNT = 12;
    /**
     * The length of a text longer than a line.
     */
    private static final int LONG_TEXT_LENGTH = 300;
    /**
     * The most text bytes of a line.
     */
    private static final int LINE_TEXT_LIMIT = 217;

    /**
     * The duration of a game message that asks for the default duration.
     */
    private static final int DEFAULT_DURATION = 0;
    /**
     * The default duration of a game message, in milliseconds.
     */
    private static final int DEFAULT_MESSAGE_DURATION = 7000;
    /**
     * The duration of the expiring trigger text, in milliseconds.
     */
    private static final int TRIGGER_TEXT_DURATION = 4000;
    /**
     * The shortest duration of a trigger string, in milliseconds.
     */
    private static final int MINIMUM_TRIGGER_STRING_DURATION = 4000;
    /**
     * The transmission duration of a trigger string that has none.
     */
    private static final int NO_TRANSMISSION_DURATION = 0;
    /**
     * The host clock of the rotation test, in milliseconds.
     */
    private static final int ROTATION_HOST_CLOCK = 1000;
    /**
     * The host clock of the elimination test, in milliseconds.
     */
    private static final int ELIMINATION_HOST_CLOCK = 100;
    /**
     * The host clock of the departure test, in milliseconds.
     */
    private static final int DEPARTURE_HOST_CLOCK = 100;
    /**
     * The length of the first piece of the expiring trigger text.
     */
    private static final int FIRST_PIECE_LENGTH = 2;

    /**
     * The first player slot.
     */
    private static final int FIRST_PLAYER = 0;
    /**
     * The second player slot.
     */
    private static final int SECOND_PLAYER = 1;
    /**
     * The fourth player slot, a computer player shown with its slot number.
     */
    private static final int COMPUTER_PLAYER = 3;
    /**
     * A player slot without a name.
     */
    private static final int UNNAMED_PLAYER = 9;
    /**
     * The screens that show a chat line: the sender's and a receiver's.
     */
    private static final int SCREEN_COUNT = 2;
    /**
     * The screen of the sender of a chat line.
     */
    private static final int SENDER_SCREEN = 0;

    /**
     * The custom sender color supplied by this fixture.
     */
    private static final int CUSTOM_COLOR = 0x88409CFF;
    /**
     * The sender color of every player but the first one.
     */
    private static final int OTHER_COLOR = 0x0C48CCFF;
    /**
     * The color of a line without a player color.
     */
    private static final int NO_PLAYER_COLOR = 0;

    /**
     * The style of a trigger text line.
     */
    private static final int TRIGGER_TEXT_STYLE = 2;
    /**
     * The style of a game message line.
     */
    private static final int GAME_MESSAGE_STYLE = 3;
    /**
     * The style and inline text code of a chat line from another player.
     */
    private static final int CHAT_TEXT_STYLE = 2;
    /**
     * The inline text code of the local player's own chat line.
     */
    private static final int OWN_CHAT_TEXT_STYLE = 7;
    /**
     * The style of a whisper line.
     */
    private static final int WHISPER_STYLE = 3;
    /**
     * The transmission sounds of one chat line or whisper.
     */
    private static final int ONE_SOUND = 1;

    /**
     * The most bytes of a name in the game's name table.
     */
    private static final int NAME_TABLE_LIMIT = 0x5F;
    /**
     * The most name bytes a live chat line keeps for its sender.
     */
    private static final int LIVE_CHAT_NAME_LIMIT = 0x4F;
    /**
     * The most name bytes a replay's chat line keeps for its sender.
     */
    private static final int REPLAY_CHAT_NAME_LIMIT = 0x50;
    /**
     * The colon, the color code and the space between a live chat line's name and its text.
     */
    private static final int LIVE_CHAT_SEPARATOR_SIZE = 3;
    /**
     * A name holding a color code, a tab, a line feed and another control byte between its letters.
     */
    private static final byte @NotNull [] CONTROL_NAME = {'A', 0x03, 'l', '\t', 'p', '\n', 'h', 0x1F, 'a'};

    /**
     * The index of the first map string.
     */
    private static final int FIRST_STRING = 1;
    /**
     * The index of the second map string.
     */
    private static final int SECOND_STRING = 2;
    /**
     * A map string index past the two-string table.
     */
    private static final int STRING_PAST_TABLE = 3;

    /**
     * The EUD address of the message lines.
     */
    private static final int MESSAGE_LINES_ADDRESS = 0x640B60;
    /**
     * The EUD address of the one-byte next-line region.
     */
    private static final int NEXT_LINE_ADDRESS = 0x640B58;
    /**
     * The unmapped EUD address between the next-line region and the message lines.
     */
    private static final int UNMAPPED_ADDRESS = 0x640B5C;
    /**
     * The line offset of the DWORD that spans the end of a line and the start of the next.
     */
    private static final int LINE_END_DWORD_OFFSET = 216;
    /**
     * The value of a cleared line byte.
     */
    private static final int CLEARED_BYTE = 0;
    /**
     * A DWORD of four zero bytes.
     */
    private static final int ZERO_DWORD = 0;

    /**
     * Creates a {@link MessageLines} instance with the fixture layout, clock, sender names and colors.
     *
     * @return the created {@link MessageLines} instance
     */
    private static @NotNull MessageLines createChatLines() {
        MessageLines lines = new MessageLines();

        lines.setLayout(SINGLE_LINE);
        lines.setHostClock(() -> 0);
        lines.setPlayerNames(encodeNames("Alpha", "Bravo"));
        lines.setNameColors(slot -> slot == FIRST_PLAYER ? CUSTOM_COLOR : OTHER_COLOR);

        return lines;
    }

    private static byte @NotNull [] @NotNull [] encodeNames(@NotNull String @NotNull ... names) {
        byte[][] encoded = new byte[names.length][];

        for (int index = 0; index < names.length; index++) {
            encoded[index] = names[index].getBytes(StandardCharsets.UTF_8);
        }

        return encoded;
    }


    @Test
    void new_message_lines_write_first_message_to_line_ten() {
        Assertions.assertEquals(FIRST_LINE, new MessageLines().getNextLine());
    }

    @Test
    void write_game_message_should_rotate_eleven_lines_and_truncate_to_217_bytes() {
        MessageLines lines = new MessageLines();

        lines.setLayout(SINGLE_LINE);
        lines.setHostClock(() -> ROTATION_HOST_CLOCK);

        for (int message = 0; message < ROTATED_MESSAGE_COUNT; message++) {
            lines.writeGameMessage(("m" + message).getBytes(StandardCharsets.US_ASCII), DEFAULT_DURATION);
        }

        Assertions.assertEquals("m1", new String(lines.getLine(WRAPPED_LINE), StandardCharsets.US_ASCII), "The second message wraps to line 0.");
        Assertions.assertEquals("m10", new String(lines.getLine(LINE_BEFORE_FIRST), StandardCharsets.US_ASCII));
        Assertions.assertEquals("m11", new String(lines.getLine(FIRST_LINE), StandardCharsets.US_ASCII), "The twelfth message is back on line 10.");
        Assertions.assertEquals(WRAPPED_LINE, lines.getNextLine());
        Assertions.assertEquals(ROTATION_HOST_CLOCK + DEFAULT_MESSAGE_DURATION, lines.getDeadline(FIRST_LINE),
                                "A zero duration means 7000 milliseconds.");

        byte[] longText = new byte[LONG_TEXT_LENGTH];

        Arrays.fill(longText, (byte) 'x');
        lines.writeGameMessage(longText, DEFAULT_DURATION);

        Assertions.assertEquals(LINE_TEXT_LIMIT, lines.getLine(WRAPPED_LINE).length);
    }

    @Test
    void expire_should_clear_only_first_byte_at_deadline() {
        MessageLines lines = new MessageLines();

        lines.setLayout(text -> new int[]{FIRST_PIECE_LENGTH, text.length});
        lines.setHostClock(() -> 0);
        lines.writeTriggerText("abcd".getBytes(StandardCharsets.US_ASCII), TRIGGER_TEXT_DURATION);

        Assertions.assertEquals("ab", new String(lines.getLine(FIRST_LINE), StandardCharsets.US_ASCII));
        Assertions.assertEquals("cd", new String(lines.getLine(WRAPPED_LINE), StandardCharsets.US_ASCII), "The second piece wraps to line 0.");
        Assertions.assertTrue(lines.isTriggerText(WRAPPED_LINE));
        Assertions.assertEquals(TRIGGER_TEXT_STYLE, lines.getStyle(FIRST_LINE));

        lines.expire(TRIGGER_TEXT_DURATION - 1);

        Assertions.assertEquals('a', lines.readByte(FIRST_LINE * MessageLines.LINE_SIZE));

        lines.expire(TRIGGER_TEXT_DURATION);

        Assertions.assertEquals(CLEARED_BYTE, lines.readByte(FIRST_LINE * MessageLines.LINE_SIZE));
        Assertions.assertEquals('b', lines.readByte(FIRST_LINE * MessageLines.LINE_SIZE + 1), "The rest of an expired line stays.");
    }

    @Test
    void write_chat_should_prefix_name_and_color_code_for_named_players() {
        MessageLines lines = new MessageLines();

        lines.setLayout(SINGLE_LINE);
        lines.setHostClock(() -> 0);
        lines.setPlayerNames(encodeNames("Alpha"));

        lines.writeChat(FIRST_PLAYER, "hi".getBytes(StandardCharsets.US_ASCII));

        Assertions.assertArrayEquals(new byte[]{'A', 'l', 'p', 'h', 'a', ':', CHAT_TEXT_STYLE, ' ', 'h', 'i'}, lines.getLine(FIRST_LINE));

        lines.writeChat(UNNAMED_PLAYER, "raw".getBytes(StandardCharsets.US_ASCII));

        Assertions.assertArrayEquals("raw".getBytes(StandardCharsets.US_ASCII), lines.getLine(WRAPPED_LINE));
    }

    /**
     * Checks sender colors on both screens, with local text code {@code 7} and remote text code {@code 2}.
     */
    @Test
    void write_chat_should_show_name_in_player_color_on_both_ends() {
        for (int end = 0; end < SCREEN_COUNT; end++) {
            MessageLines lines = createChatLines();
            boolean senderScreen = end == SENDER_SCREEN;

            lines.setLocalPlayer(senderScreen ? FIRST_PLAYER : SECOND_PLAYER);

            lines.writeChat(FIRST_PLAYER, "gg".getBytes(StandardCharsets.UTF_8));

            Assertions.assertArrayEquals(new byte[]{'A', 'l', 'p', 'h', 'a', ':',
                                                    (byte) (senderScreen ? OWN_CHAT_TEXT_STYLE : CHAT_TEXT_STYLE), ' ', 'g', 'g'},
                                         lines.getLine(FIRST_LINE));
            Assertions.assertEquals(CUSTOM_COLOR, lines.getColor(FIRST_LINE), "The name starts in the sender's player colour.");
            Assertions.assertEquals(CHAT_TEXT_STYLE, lines.getStyle(FIRST_LINE));
            Assertions.assertEquals(ONE_SOUND, lines.takeTransmissionSounds());
        }
    }

    /**
     * Checks that a whisper uses the sender name and local text code {@code 7}.
     */
    @Test
    void write_chat_should_show_whisper_sender_its_name_with_local_text_code() {
        MessageLines lines = createChatLines();

        lines.setLocalPlayer(SECOND_PLAYER);

        lines.writeChat(SECOND_PLAYER, "psst".getBytes(StandardCharsets.UTF_8));

        Assertions.assertArrayEquals(new byte[]{'B', 'r', 'a', 'v', 'o', ':', OWN_CHAT_TEXT_STYLE, ' ', 'p', 's', 's', 't'},
                                     lines.getLine(FIRST_LINE));
        Assertions.assertEquals(OTHER_COLOR, lines.getColor(FIRST_LINE));
    }

    /**
     * Checks that a received whisper shows {@code strWHISPER_FROM} with the name, a space and the text, all yellow.
     */
    @Test
    void write_whisper_should_show_bracketed_sender_name_in_yellow() {
        MessageLines lines = createChatLines();

        lines.writeWhisper("<%s>", SECOND_PLAYER, "psst".getBytes(StandardCharsets.UTF_8));

        Assertions.assertEquals("<Bravo> psst", new String(lines.getLine(FIRST_LINE), StandardCharsets.UTF_8));
        Assertions.assertEquals(WHISPER_STYLE, lines.getStyle(FIRST_LINE));
        Assertions.assertEquals(NO_PLAYER_COLOR, lines.getColor(FIRST_LINE), "No player colour: the whole line is yellow.");
        Assertions.assertEquals(ONE_SOUND, lines.takeTransmissionSounds());
    }

    /**
     * Checks that a whisper with a named player shows the formatted prefix, a space and the text, all yellow, and reaches the chat listener.
     */
    @Test
    void write_whisper_should_show_named_prefix_in_yellow() {
        MessageLines lines = createChatLines();
        List<String> written = new ArrayList<>();

        lines.setChatListener((line, style, color) -> written.add(new String(line, StandardCharsets.UTF_8)));

        lines.writeWhisper("<To: %s>", "Zeratul#abcd12", "en taro".getBytes(StandardCharsets.UTF_8));

        Assertions.assertEquals("<To: Zeratul#abcd12> en taro", new String(lines.getLine(FIRST_LINE), StandardCharsets.UTF_8));
        Assertions.assertEquals(WHISPER_STYLE, lines.getStyle(FIRST_LINE));
        Assertions.assertEquals(NO_PLAYER_COLOR, lines.getColor(FIRST_LINE));
        Assertions.assertEquals(ONE_SOUND, lines.takeTransmissionSounds());
        Assertions.assertEquals(List.of("<To: Zeratul#abcd12> en taro"), written);
    }

    /**
     * Checks that team chat prefixes the sender's force.
     */
    @Test
    void write_chat_should_prefix_force_name_in_team_chat() {
        MessageLines lines = createChatLines();

        lines.setTeamForceNames(slot -> "1팀");

        lines.writeChat(FIRST_PLAYER, "go".getBytes(StandardCharsets.UTF_8));

        Assertions.assertEquals("[1팀]Alpha: go", new String(lines.getLine(FIRST_LINE), StandardCharsets.UTF_8));
    }

    /**
     * Checks that replay chat omits inline color codes and player colors.
     */
    @Test
    void write_chat_should_omit_color_code_in_replay_playback() {
        MessageLines lines = createChatLines();

        lines.setReplayPlayback(true);

        lines.writeChat(FIRST_PLAYER, "gg".getBytes(StandardCharsets.UTF_8));

        Assertions.assertEquals("Alpha: gg", new String(lines.getLine(FIRST_LINE), StandardCharsets.UTF_8));
        Assertions.assertEquals(NO_PLAYER_COLOR, lines.getColor(FIRST_LINE));
    }

    @Test
    void write_chat_should_copy_the_sender_name_without_control_bytes_but_tab_line_feed_and_carriage_return() {
        MessageLines lines = createChatLines();

        lines.setPlayerNames(new byte[][]{CONTROL_NAME, "x".repeat(NAME_TABLE_LIMIT).getBytes(StandardCharsets.US_ASCII)});
        lines.setLocalPlayer(SECOND_PLAYER);

        lines.writeChat(FIRST_PLAYER, "gg".getBytes(StandardCharsets.US_ASCII));
        lines.writeChat(SECOND_PLAYER, "gg".getBytes(StandardCharsets.US_ASCII));

        Assertions.assertArrayEquals(new byte[]{'A', 'l', '\t', 'p', '\n', 'h', 'a', ':', CHAT_TEXT_STYLE, ' ', 'g', 'g'},
                                     lines.getLine(FIRST_LINE));
        Assertions.assertEquals(LIVE_CHAT_NAME_LIMIT + LIVE_CHAT_SEPARATOR_SIZE + "gg".length(), lines.getLine(WRAPPED_LINE).length,
                                "The name buffer keeps 0x4F bytes.");
    }

    @Test
    void write_chat_should_copy_only_the_printable_sender_name_bytes_in_replay_playback() {
        MessageLines lines = createChatLines();

        lines.setPlayerNames(new byte[][]{CONTROL_NAME, "x".repeat(NAME_TABLE_LIMIT).getBytes(StandardCharsets.US_ASCII)});
        lines.setReplayPlayback(true);

        lines.writeChat(FIRST_PLAYER, "gg".getBytes(StandardCharsets.US_ASCII));
        lines.writeChat(SECOND_PLAYER, "gg".getBytes(StandardCharsets.US_ASCII));

        Assertions.assertEquals("Alpha: gg", new String(lines.getLine(FIRST_LINE), StandardCharsets.US_ASCII));
        Assertions.assertEquals("x".repeat(REPLAY_CHAT_NAME_LIMIT) + ": gg", new String(lines.getLine(WRAPPED_LINE), StandardCharsets.US_ASCII));
    }

    @Test
    void require_observable_should_fail_after_write_without_local_inputs() {
        MessageLines lines = new MessageLines();

        lines.requireObservable();
        lines.writeGameMessage("x".getBytes(StandardCharsets.US_ASCII), DEFAULT_DURATION);

        Assertions.assertThrows(UnsupportedOperationException.class, lines::requireObservable);
    }

    @Test
    void write_trigger_string_should_skip_unforced_text_without_subtitles() {
        MessageLines lines = new MessageLines();

        lines.setLayout(SINGLE_LINE);
        lines.setHostClock(() -> 0);
        lines.setMapStrings(List.of("abc"));
        lines.setSubtitles(false);

        lines.writeTriggerString(FIRST_STRING, false, NO_TRANSMISSION_DURATION);

        Assertions.assertEquals(CLEARED_BYTE, lines.readByte(FIRST_LINE * MessageLines.LINE_SIZE));
        Assertions.assertEquals(FIRST_LINE, lines.getNextLine(), "Unforced text without subtitles writes nothing.");

        lines.writeTriggerString(FIRST_STRING, true, NO_TRANSMISSION_DURATION);

        Assertions.assertEquals("abc", new String(lines.getLine(FIRST_LINE), StandardCharsets.US_ASCII));
        Assertions.assertEquals(MINIMUM_TRIGGER_STRING_DURATION, lines.getDeadline(FIRST_LINE));

        lines.setSubtitles(null);

        lines.writeTriggerString(FIRST_STRING, false, NO_TRANSMISSION_DURATION);

        Assertions.assertThrows(UnsupportedOperationException.class, lines::requireObservable);
    }

    @Test
    void eud_region_reads_line_bytes_and_rejects_unmapped_dwords() {
        MessageLines lines = new MessageLines();

        lines.setLayout(SINGLE_LINE);
        lines.setHostClock(() -> 0);
        lines.writeGameMessage("Map Hack: x".getBytes(StandardCharsets.US_ASCII), DEFAULT_DURATION);

        ClassicAddressSpace space = ClassicAddressSpace.create(PlayerProgressTestUtils.createPlayerScores(), null, null, lines);
        int firstLineAddress = MESSAGE_LINES_ADDRESS + FIRST_LINE * MessageLines.LINE_SIZE;

        Assertions.assertEquals(ByteBuffer.wrap("Map ".getBytes(StandardCharsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).getInt(),
                                space.readDword(firstLineAddress));
        Assertions.assertEquals(ZERO_DWORD, space.readDword(firstLineAddress + LINE_END_DWORD_OFFSET),
                                "Line 10 bytes 216..217 and line 11 bytes 0..1 are zero.");
        Assertions.assertEquals(lines.getNextLine(), space.readDword(NEXT_LINE_ADDRESS),
                                "A DWORD at the one-byte next-line region reads zero bytes past it.");
        Assertions.assertThrows(UnsupportedOperationException.class, () -> space.readDword(UNMAPPED_ADDRESS));

        space.writeDword(firstLineAddress, ZERO_DWORD);

        Assertions.assertEquals(CLEARED_BYTE, lines.readByte(FIRST_LINE * MessageLines.LINE_SIZE), "EUD writes are stored into the line buffer.");
    }

    @Test
    void get_map_string_should_resolve_one_based_names_and_empty_past_table() {
        MessageLines lines = new MessageLines();

        lines.setMapStrings(null);

        Assertions.assertNull(lines.getMapString(FIRST_STRING), "An unreadable table has no strings.");

        lines.setMapStrings(List.of("Hero", ""));

        Assertions.assertEquals("Hero", lines.getMapString(FIRST_STRING));
        Assertions.assertEquals("", lines.getMapString(SECOND_STRING));
        Assertions.assertEquals("", lines.getMapString(STRING_PAST_TABLE), "An index past the table names nothing.");
    }

    @Test
    void write_elimination_should_name_humans_and_number_computers() {
        MessageLines lines = new MessageLines();

        lines.setLayout(SINGLE_LINE);
        lines.setHostClock(() -> ELIMINATION_HOST_CLOCK);
        lines.setPlayerNames(encodeNames("Alpha", "Beta"));
        lines.setEliminationFormats("%s was eliminated.", "Player %d was eliminated.");

        // Human players use names; computer players use their slot numbers.
        lines.writeElimination(SECOND_PLAYER, PlayerTypes.HUMAN, false);

        Assertions.assertEquals("Beta was eliminated.", new String(lines.getLine(FIRST_LINE), StandardCharsets.US_ASCII));
        Assertions.assertEquals(GAME_MESSAGE_STYLE, lines.getStyle(FIRST_LINE));
        Assertions.assertEquals(ELIMINATION_HOST_CLOCK + DEFAULT_MESSAGE_DURATION, lines.getDeadline(FIRST_LINE));

        lines.writeElimination(COMPUTER_PLAYER, PlayerTypes.COMPUTER, false);

        Assertions.assertEquals("Player 4 was eliminated.", new String(lines.getLine(WRAPPED_LINE), StandardCharsets.US_ASCII));

        lines.writeElimination(FIRST_PLAYER, PlayerTypes.RESCUE_PASSIVE, false);

        Assertions.assertEquals(0, lines.getLine(NEXT_WRAPPED_LINE).length);

        // A replay names every player.
        lines.writeElimination(FIRST_PLAYER, PlayerTypes.COMPUTER, true);

        Assertions.assertEquals("Alpha was eliminated.", new String(lines.getLine(NEXT_WRAPPED_LINE), StandardCharsets.US_ASCII));
    }

    @Test
    void write_departure_should_use_the_localized_formats() {
        MessageLines lines = new MessageLines();

        lines.setLayout(SINGLE_LINE);
        lines.setHostClock(() -> DEPARTURE_HOST_CLOCK);
        lines.setPlayerNames(encodeNames("Alpha", "Beta"));
        lines.setDepartureFormats("%s has left the game.", "%s was dropped from the game.");

        lines.writeDeparture(SECOND_PLAYER, true);

        Assertions.assertEquals("Beta was dropped from the game.", new String(lines.getLine(FIRST_LINE), StandardCharsets.US_ASCII));
        Assertions.assertEquals(GAME_MESSAGE_STYLE, lines.getStyle(FIRST_LINE));
        Assertions.assertEquals(DEPARTURE_HOST_CLOCK + DEFAULT_MESSAGE_DURATION, lines.getDeadline(FIRST_LINE));

        lines.writeDeparture(FIRST_PLAYER, false);

        Assertions.assertEquals("Alpha has left the game.", new String(lines.getLine(WRAPPED_LINE), StandardCharsets.US_ASCII));
    }

    @Test
    void write_departure_should_make_lines_unknown_when_formats_are_not_set() {
        MessageLines lines = new MessageLines();

        lines.setLayout(SINGLE_LINE);
        lines.setHostClock(() -> 0);

        lines.writeDeparture(FIRST_PLAYER, false);

        Assertions.assertThrows(UnsupportedOperationException.class, lines::requireObservable);
    }
}
