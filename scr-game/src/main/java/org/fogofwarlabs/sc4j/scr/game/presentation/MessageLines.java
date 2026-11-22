package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.text.TerminatedStringUtils;
import org.fogofwarlabs.sc4j.scr.game.map.MapStringSection;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;

/**
 * Holds the displayed message lines, their expiry ticks, styles and local text inputs.
 */
public final class MessageLines {
    /**
     * The number of lines, including the two lines outside the rotation.
     */
    public static final int LINE_COUNT = 13;
    /**
     * The bytes per line.
     */
    public static final int LINE_SIZE = 218;
    /**
     * The lines written in turn by ordinary messages.
     */
    public static final int ROTATING_LINES = 11;
    /**
     * The index of messages without a sender.
     */
    public static final int SYSTEM_LINE = 12;

    /**
     * The style code of trigger text.
     */
    public static final int STYLE_TRIGGER_TEXT = 2;
    /**
     * The style code of game messages.
     */
    public static final int STYLE_GAME_MESSAGE = 3;
    /**
     * The color code of chat text.
     */
    public static final int STYLE_CHAT_TEXT = 2;
    /**
     * The color code of the local player's own chat text.
     */
    public static final int STYLE_OWN_CHAT_TEXT = 7;
    /**
     * The color code of a whisper line (yellow).
     */
    public static final int STYLE_WHISPER = 3;
    /**
     * The style code of the system line.
     */
    public static final int STYLE_SYSTEM_LINE = 4;

    /**
     * The rotating line the first message of a game writes.
     */
    private static final int INITIAL_NEXT_LINE = 10;

    /**
     * The display duration of an ordinary message in milliseconds.
     */
    private static final int DEFAULT_DURATION_MILLISECONDS = 7000;
    /**
     * The shortest display duration of trigger text in milliseconds.
     */
    private static final int MINIMUM_TRIGGER_DURATION_MILLISECONDS = 4000;
    /**
     * The display duration of trigger text per byte in milliseconds.
     */
    private static final int TRIGGER_CHARACTER_DURATION_MILLISECONDS = 50;

    /**
     * The most bytes a departure message keeps.
     */
    private static final int DEPARTURE_TEXT_LIMIT = 0x7F;
    /**
     * The most bytes a formatted elimination message or placement warning keeps.
     */
    private static final int FORMATTED_TEXT_LIMIT = 0xFF;
    /**
     * The size of the buffer a live game's chat line copies its sender's name into, terminator included.
     */
    private static final int CHAT_NAME_SIZE = 0x50;
    /**
     * The most name bytes a replay's chat line copies for its sender.
     */
    private static final int REPLAY_CHAT_NAME_LIMIT = 0x50;

    /**
     * The size of the colon and space after the sender's name of a replay's chat line.
     */
    private static final int REPLAY_CHAT_PREFIX_SIZE = 2;
    /**
     * The offset of the space from the colon of a replay's chat line.
     */
    private static final int REPLAY_CHAT_SPACE_OFFSET = 1;
    /**
     * The size of the colon, style byte and space after the sender's name of a live game's chat line.
     */
    private static final int CHAT_PREFIX_SIZE = 3;
    /**
     * The offset of the style byte from the colon of a live game's chat line.
     */
    private static final int CHAT_STYLE_OFFSET = 1;
    /**
     * The offset of the space from the colon of a live game's chat line.
     */
    private static final int CHAT_SPACE_OFFSET = 2;
    /**
     * The size of the space between a whisper or cheat message prefix and its text.
     */
    private static final int SEPARATOR_SIZE = 1;

    /**
     * The index of the first map string.
     */
    private static final int FIRST_MAP_STRING_INDEX = 1;
    /**
     * The offset from a player slot to the player number an elimination message shows.
     */
    private static final int PLAYER_NUMBER_OFFSET = 1;
    /**
     * The offset from a unit type to the 1-based {@code stat_txt} id of its default name.
     */
    private static final int UNIT_NAME_TEXT_OFFSET = 1;
    /**
     * The trigger flag of a line that trigger text wrote.
     */
    private static final int TRIGGER_TEXT_FLAG = 1;
    /**
     * The mask of an unsigned byte.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * The first color code of the first color code range.
     */
    private static final int FIRST_COLOR_RANGE_START = 0x01;
    /**
     * The last color code of the first color code range.
     */
    private static final int FIRST_COLOR_RANGE_END = 0x08;
    /**
     * The first color code of the second color code range.
     */
    private static final int SECOND_COLOR_RANGE_START = 0x0E;
    /**
     * The last color code of the second color code range.
     */
    private static final int SECOND_COLOR_RANGE_END = 0x11;
    /**
     * The first color code of the third color code range.
     */
    private static final int THIRD_COLOR_RANGE_START = 0x15;
    /**
     * The last color code of the third color code range.
     */
    private static final int THIRD_COLOR_RANGE_END = 0x19;
    /**
     * The first color code of the fourth color code range.
     */
    private static final int FOURTH_COLOR_RANGE_START = 0x1B;
    /**
     * The last color code of the fourth color code range.
     */
    private static final int FOURTH_COLOR_RANGE_END = 0x1F;

    /**
     * The localized text key of the placement warning format.
     */
    private static final @NotNull String CANT_PLACE_TEXT_KEY = "errCANTPLACE_sdd";
    /**
     * The localized text key of the enabled cheat message.
     */
    private static final @NotNull String CHEAT_ON_TEXT_KEY = "strCHEATON";
    /**
     * The localized text key of the disabled cheat message.
     */
    private static final @NotNull String CHEAT_OFF_TEXT_KEY = "strCHEATOFF";

    /**
     * Breaks a message into the pieces placed on separate display lines.
     */
    public interface TextLayout {
        /**
         * Breaks a zero-free message into ascending exclusive piece ends, ending with its length.
         *
         * @param text the message bytes
         * @return the ascending piece ends
         */
        int @NotNull [] breakLines(byte @NotNull [] text);
    }

    /**
     * Receives every chat and whisper line as it is written.
     */
    @FunctionalInterface
    public interface ChatListener {
        /**
         * Called when a formatted chat or whisper line is written.
         *
         * @param line  the formatted line bytes
         * @param style the line's color code ({@link #STYLE_CHAT_TEXT} or {@link #STYLE_WHISPER})
         * @param color the packed {@code 0xRRGGBBAA} starting color (the sender's player color), or {@code 0}
         */
        void onChatWritten(byte @NotNull [] line, int style, int color);
    }

    /**
     * Supplies localized keyed and numbered status-line texts.
     */
    public interface StatusTexts {
        /**
         * Resolves a localized keyed text.
         *
         * @param key the string key
         * @return the localized text bytes, or {@code null} when the key is unknown
         */
        byte @Nullable [] resolveKeyedText(@NotNull String key);

        /**
         * Resolves a localized numbered text.
         *
         * @param textIdentifier the 1-based {@code stat_txt} id
         * @return the localized text bytes, or {@code null} when the id is unknown
         */
        byte @Nullable [] resolveNumberedText(int textIdentifier);
    }

    /**
     * Returns the display duration of trigger text: a duration per character, at least the trigger text minimum and the supplied minimum.
     *
     * @param text                the text bytes
     * @param minimumMilliseconds the least duration, or {@code 0}
     * @return the duration in milliseconds
     */
    private static int getTriggerTextDuration(byte @NotNull [] text, int minimumMilliseconds) {
        return Math.max(Math.max(text.length * TRIGGER_CHARACTER_DURATION_MILLISECONDS, MINIMUM_TRIGGER_DURATION_MILLISECONDS), minimumMilliseconds);
    }

    private static int advanceLine(int line) {
        return line + 1 >= ROTATING_LINES ? 0 : line + 1;
    }

    private static byte @NotNull [] joinWithSeparator(byte @NotNull [] prefix, byte @NotNull [] text) {
        byte[] line = new byte[prefix.length + SEPARATOR_SIZE + text.length];

        System.arraycopy(prefix, 0, line, 0, prefix.length);
        line[prefix.length] = ' ';
        System.arraycopy(text, 0, line, prefix.length + SEPARATOR_SIZE, text.length);

        return line;
    }

    private final byte @NotNull [] lines;
    private final int @NotNull [] deadlines;
    private final int @NotNull [] styles;
    private final int @NotNull [] colors;
    private final byte @NotNull [] triggerFlags;
    private final byte @NotNull [] @NotNull [] playerNames;
    private byte @Nullable [] @NotNull [] mapStrings;

    private int nextLine;

    private @Nullable StatusTexts statusTexts;
    private int localPlayer;
    private boolean multiplayerSession;
    private @Nullable IntUnaryOperator nameColors;
    private @Nullable ChatListener chatListener;
    private @Nullable IntFunction<@Nullable String> teamForceNames;
    private boolean replayPlayback;

    private @Nullable TextLayout layout;
    private @Nullable IntSupplier hostClock;
    private @Nullable Boolean subtitles;
    private int transmissionSounds;

    private @Nullable MapStringSection mapStringSection;
    private byte @Nullable [] leftFormat;
    private byte @Nullable [] droppedFormat;
    private byte @Nullable [] eliminatedNameFormat;
    private byte @Nullable [] eliminatedNumberFormat;

    private boolean unknownContent;
    private @NotNull String unknownReason;

    /**
     * Creates a {@link MessageLines} instance with empty lines and the game-start rotation index.
     */
    public MessageLines() {
        this.lines        = new byte[LINE_COUNT * LINE_SIZE];
        this.deadlines    = new int[LINE_COUNT];
        this.styles       = new int[LINE_COUNT];
        this.colors       = new int[LINE_COUNT];
        this.triggerFlags = new byte[LINE_COUNT];
        this.playerNames  = new byte[PlayerTable.PLAYER_SLOTS][0];
        this.mapStrings   = new byte[0][];

        this.nextLine = INITIAL_NEXT_LINE;

        this.statusTexts        = null;
        this.localPlayer        = -1;
        this.multiplayerSession = false;
        this.nameColors         = null;
        this.chatListener       = null;
        this.teamForceNames     = null;
        this.replayPlayback     = false;

        this.layout             = null;
        this.hostClock          = null;
        this.subtitles          = null;
        this.transmissionSounds = 0;

        this.mapStringSection       = null;
        this.leftFormat             = null;
        this.droppedFormat          = null;
        this.eliminatedNameFormat   = null;
        this.eliminatedNumberFormat = null;

        this.unknownContent = false;
        this.unknownReason  = "";
    }

    /**
     * @return the live map string section, or {@code null}
     */
    public @Nullable MapStringSection getMapStringSection() {
        return this.mapStringSection;
    }

    /**
     * @param stringIndex the 1-based map string index
     * @return the string, empty for an index without a string, or {@code null} when the table is unreadable
     */
    public @Nullable String getMapString(int stringIndex) {
        if (this.mapStringSection != null) {
            return this.mapStringSection.getString(stringIndex);
        }

        if (this.mapStrings == null) {
            return null;
        }

        if (stringIndex < FIRST_MAP_STRING_INDEX || stringIndex > this.mapStrings.length) {
            return "";
        }

        return new String(this.mapStrings[stringIndex - FIRST_MAP_STRING_INDEX], StandardCharsets.UTF_8);
    }

    /**
     * @param line the line index
     * @return the style code the line was written with
     */
    public int getStyle(int line) {
        return this.styles[line];
    }

    /**
     * @param line the line index
     * @return the packed {@code 0xRRGGBBAA} starting color of the line, or {@code 0} when it starts with its style code
     */
    public int getColor(int line) {
        return this.colors[line];
    }

    /**
     * @param line the line index
     * @return {@code true} if trigger text wrote the line, {@code false} otherwise
     */
    public boolean isTriggerText(int line) {
        return this.triggerFlags[line] != 0;
    }

    /**
     * @param line the line index
     * @return the host tick at which the line expires
     */
    public int getDeadline(int line) {
        return this.deadlines[line];
    }

    /**
     * @return {@code true} if a local text layout has been supplied, {@code false} otherwise
     */
    public boolean hasLayout() {
        return this.layout != null;
    }

    /**
     * @return the unsigned next-line byte
     */
    public int getNextLine() {
        return this.nextLine & BYTE_MASK;
    }

    /**
     * @param line the line index
     * @return the line bytes up to the first zero
     */
    public byte @NotNull [] getLine(int line) {
        int base = line * LINE_SIZE;
        int end = base;

        while (end < base + LINE_SIZE && this.lines[end] != 0) {
            end++;
        }

        return Arrays.copyOfRange(this.lines, base, end);
    }


    /**
     * Sets the live map string section used to resolve trigger text.
     *
     * @param section the section the loader keeps, or {@code null}
     */
    public void setMapStringSection(@Nullable MapStringSection section) {
        this.mapStringSection = section;
    }

    /**
     * Sets decoded map strings used when no live string section is supplied.
     *
     * @param strings the strings in table order, index {@code 1} first, or {@code null} when the table is unreadable
     */
    public void setMapStrings(@Nullable List<String> strings) {
        if (strings == null) {
            this.mapStrings = null;

            return;
        }

        this.mapStrings = new byte[strings.size()][];

        for (int index = 0; index < strings.size(); index++) {
            this.mapStrings[index] = strings.get(index).getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Sets the localized departure message formats.
     *
     * @param left    the format of a player who left, or {@code null} when unknown
     * @param dropped the format of a dropped player, or {@code null} when unknown
     */
    public void setDepartureFormats(@Nullable String left, @Nullable String dropped) {
        this.leftFormat    = left == null ? null : left.getBytes(StandardCharsets.UTF_8);
        this.droppedFormat = dropped == null ? null : dropped.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Sets the localized elimination message formats.
     *
     * @param named    the format with the player's name, or {@code null} when unknown
     * @param numbered the format with the player's number, or {@code null} when unknown
     */
    public void setEliminationFormats(@Nullable String named, @Nullable String numbered) {
        this.eliminatedNameFormat   = named == null ? null : named.getBytes(StandardCharsets.UTF_8);
        this.eliminatedNumberFormat = numbered == null ? null : numbered.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Sets the local subtitle option tested by trigger text.
     *
     * @param subtitles the option, or {@code null} when unknown
     */
    public void setSubtitles(@Nullable Boolean subtitles) {
        this.subtitles = subtitles;
    }

    /**
     * Sets the local host tick source used to calculate message deadlines.
     *
     * @param hostClock the local host tick source, or {@code null} when none is available
     */
    public void setHostClock(@Nullable IntSupplier hostClock) {
        this.hostClock = hostClock;
    }

    /**
     * Sets the names of the game's name table, which the messages name the players with. A chat line copies its sender's name without the control
     * bytes ({@link #writeChat}); the other messages take the names as they are.
     *
     * @param names the name bytes in slot order, without terminator
     */
    public void setPlayerNames(byte @NotNull [] @NotNull [] names) {
        for (int player = 0; player < PlayerTable.PLAYER_SLOTS && player < names.length; player++) {
            this.playerNames[player] = names[player].clone();
        }
    }

    /**
     * Sets whether cheat messages include the sender name.
     *
     * @param multiplayerSession whether the session is a multiplayer one
     */
    public void setMultiplayerSession(boolean multiplayerSession) {
        this.multiplayerSession = multiplayerSession;
    }

    /**
     * Sets the receiver of chat and whisper line events.
     *
     * @param chatListener the receiver, or {@code null}
     */
    public void setChatListener(@Nullable ChatListener chatListener) {
        this.chatListener = chatListener;
    }

    /**
     * Sets the source of player colors for chat names.
     *
     * @param nameColors the packed {@code 0xRRGGBBAA} color of a slot, or {@code null}
     */
    public void setNameColors(@Nullable IntUnaryOperator nameColors) {
        this.nameColors = nameColors;
    }

    /**
     * Sets the source of team force names for chat prefixes.
     *
     * @param teamForceNames the force name of a slot ({@code strTEAM1..4}; {@code null} outside team games), or {@code null}
     */
    public void setTeamForceNames(@Nullable IntFunction<@Nullable String> teamForceNames) {
        this.teamForceNames = teamForceNames;
    }

    /**
     * Sets the local commanded player used to select the own-chat style.
     *
     * @param localPlayer the local commanded player, or {@code -1} for none (replay playback)
     */
    public void setLocalPlayer(int localPlayer) {
        this.localPlayer = localPlayer;
    }

    /**
     * Sets whether chat uses the replay playback format.
     *
     * @param replayPlayback whether the game is a replay's playback
     */
    public void setReplayPlayback(boolean replayPlayback) {
        this.replayPlayback = replayPlayback;
    }

    /**
     * Sets the local text layout used by message writers.
     *
     * @param layout the layout, or {@code null} when none is available
     */
    public void setLayout(@Nullable TextLayout layout) {
        this.layout = layout;
    }

    /**
     * Sets the localized status texts used by the local display.
     *
     * @param statusTexts the texts, or {@code null} when not supplied
     */
    public void setStatusTexts(@Nullable StatusTexts statusTexts) {
        this.statusTexts = statusTexts;
    }

    /**
     * Sets the next rotating line from an unsigned byte value.
     *
     * @param value the value, of which only the low byte is kept
     */
    public void setNextLine(int value) {
        this.nextLine = value & BYTE_MASK;
    }


    /**
     * Writes a departure message with the player name and localized departure format.
     *
     * @param player  the departing player
     * @param dropped whether the departure is a drop
     */
    public void writeDeparture(int player, boolean dropped) {
        byte[] format = dropped ? this.droppedFormat : this.leftFormat;

        if (format == null || player < 0 || player >= PlayerTable.PLAYER_SLOTS) {
            this.markUnknown("the localized departure message");

            return;
        }

        byte[] name = this.playerNames[player];
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        boolean substituted = false;

        for (int index = 0; index < format.length; index++) {
            if (!substituted && format[index] == '%' && index + 1 < format.length && format[index + 1] == 's') {
                text.writeBytes(name);
                substituted = true;
                index++;
            } else {
                text.write(format[index]);
            }
        }

        byte[] formatted = text.toByteArray();

        this.writeGameMessage(formatted.length > DEPARTURE_TEXT_LIMIT ? Arrays.copyOf(formatted, DEPARTURE_TEXT_LIMIT) : formatted, 0);
    }

    /**
     * Writes an elimination message using a human or replay player name, or a computer player number.
     *
     * @param player the eliminated player
     * @param type   the player's record type before the removal
     * @param replay whether a replay plays
     */
    public void writeElimination(int player, int type, boolean replay) {
        byte[] format;
        byte[] argument;

        if (type == PlayerTypes.HUMAN || replay) {
            format   = this.eliminatedNameFormat;
            argument = player >= 0 && player < PlayerTable.PLAYER_SLOTS ? this.playerNames[player] : new byte[0];
        } else if (type == PlayerTypes.COMPUTER) {
            format   = this.eliminatedNumberFormat;
            argument = Integer.toString(player + PLAYER_NUMBER_OFFSET).getBytes(StandardCharsets.US_ASCII);
        } else {
            return;
        }

        if (format == null) {
            this.markUnknown("the localized elimination message");

            return;
        }

        ByteArrayOutputStream text = new ByteArrayOutputStream();
        boolean substituted = false;

        for (int index = 0; index < format.length; index++) {
            if (!substituted && format[index] == '%' && index + 1 < format.length && (format[index + 1] == 's' || format[index + 1] == 'd')) {
                text.writeBytes(argument);
                substituted = true;
                index++;
            } else {
                text.write(format[index]);
            }
        }

        byte[] formatted = text.toByteArray();

        this.writeGameMessage(formatted.length > FORMATTED_TEXT_LIMIT ? Arrays.copyOf(formatted, FORMATTED_TEXT_LIMIT) : formatted, 0);
    }

    /**
     * Writes resolved trigger text when subtitles are enabled or the action forces display ({@link #writeTriggerText(byte[], boolean, int)}).
     *
     * @param stringIndex         the map string index
     * @param forced              whether the action forces display
     * @param minimumMilliseconds the transmission duration, or {@code 0}
     */
    public void writeTriggerString(int stringIndex, boolean forced, int minimumMilliseconds) {
        if (!this.acceptsTriggerText(forced)) {
            return;
        }

        byte[] text;

        if (this.mapStringSection != null) {
            String decoded = this.mapStringSection.getString(stringIndex);

            if (decoded.isEmpty()) {
                return;
            }

            text = decoded.getBytes(StandardCharsets.UTF_8);
        } else {
            if (this.mapStrings == null) {
                this.markUnknown("trigger text without a readable string table");

                return;
            }

            if (stringIndex < FIRST_MAP_STRING_INDEX || stringIndex > this.mapStrings.length ||
                this.mapStrings[stringIndex - FIRST_MAP_STRING_INDEX].length == 0) {
                return;
            }

            text = this.mapStrings[stringIndex - FIRST_MAP_STRING_INDEX];
        }

        this.writeTriggerText(text, getTriggerTextDuration(text, minimumMilliseconds));
    }

    /**
     * Writes trigger text as Display Text Message writes a map string: when subtitles are enabled or the display is forced, nonempty text shows
     * for a duration that grows with its length, at least the minimum.
     *
     * @param text                the UTF-8 text bytes
     * @param forced              whether the display is forced
     * @param minimumMilliseconds the least duration, or {@code 0}
     */
    public void writeTriggerText(byte @NotNull [] text, boolean forced, int minimumMilliseconds) {
        if (!this.acceptsTriggerText(forced) || text.length == 0) {
            return;
        }

        this.writeTriggerText(text, getTriggerTextDuration(text, minimumMilliseconds));
    }

    /**
     * Tests whether trigger text shows: forced text always, other text when the subtitle option is on; an unknown subtitle option marks the
     * lines unknown.
     *
     * @param forced whether the display is forced
     * @return {@code true} if the text shows, {@code false} otherwise
     */
    private boolean acceptsTriggerText(boolean forced) {
        if (forced) {
            return true;
        }

        if (this.subtitles == null) {
            this.markUnknown("trigger text without the subtitle option");

            return false;
        }

        return this.subtitles;
    }

    /**
     * Writes trigger text with the supplied duration and trigger flag.
     *
     * @param text                 the resolved text bytes
     * @param durationMilliseconds the display duration in milliseconds
     */
    public void writeTriggerText(byte @NotNull [] text, int durationMilliseconds) {
        this.writeMessage(text, durationMilliseconds, STYLE_TRIGGER_TEXT, TRIGGER_TEXT_FLAG, 0);
    }

    /**
     * Writes a game message, using the default duration when the supplied duration is {@code 0}.
     *
     * @param text                 the resolved text bytes
     * @param durationMilliseconds the display duration in milliseconds, or {@code 0}
     */
    public void writeGameMessage(byte @NotNull [] text, int durationMilliseconds) {
        this.writeMessage(text, durationMilliseconds == 0 ? DEFAULT_DURATION_MILLISECONDS : durationMilliseconds, STYLE_GAME_MESSAGE, 0, 0);
    }

    /**
     * Writes an error message with the default display duration.
     *
     * @param text the message bytes without a terminator
     */
    public void writeErrorMessage(byte @NotNull [] text) {
        this.writeMessage(text, DEFAULT_DURATION_MILLISECONDS, STYLE_GAME_MESSAGE, 0, 0);
    }

    /**
     * Writes the localized placement warning with the unit name and pixel position.
     *
     * @param mapName   the map's name of the type ({@link Game#getUnitDisplayName}), or {@code null} for its default {@code stat_txt} name
     * @param unitType  the unit type
     * @param positionX the horizontal position the format receives
     * @param positionY the vertical position the format receives
     * @throws UnsupportedOperationException if the localized format has a conversion other than {@code %%}, the first {@code %s} and the first two
     *                                       {@code %d}
     */
    public void writeUnplaceable(@Nullable String mapName, int unitType, int positionX, int positionY) {
        StatusTexts texts = this.statusTexts;
        byte[] format = texts == null ? null : texts.resolveKeyedText(CANT_PLACE_TEXT_KEY);
        byte[] name = mapName != null ? mapName.getBytes(StandardCharsets.UTF_8)
                                      : texts == null ? null : texts.resolveNumberedText(unitType + UNIT_NAME_TEXT_OFFSET);

        if (format == null || name == null) {
            this.markUnknown("the placement warning errCANTPLACE_sdd without the local display's localized texts");

            return;
        }

        byte[][] strings = {Arrays.copyOf(name, TerminatedStringUtils.getTextLength(name))};
        int[] numbers = {positionX, positionY};
        ByteArrayOutputStream text = new ByteArrayOutputStream();
        int nextString = 0;
        int nextNumber = 0;

        for (int index = 0; index < format.length; index++) {
            if (format[index] != '%' || index + 1 >= format.length) {
                text.write(format[index]);

                continue;
            }

            byte conversion = format[++index];

            if (conversion == 's' && nextString < strings.length) {
                text.writeBytes(strings[nextString++]);
            } else if (conversion == 'd' && nextNumber < numbers.length) {
                text.writeBytes(Integer.toString(numbers[nextNumber++]).getBytes(StandardCharsets.US_ASCII));
            } else if (conversion == '%') {
                text.write('%');
            } else {
                throw Exceptions.newUnsupportedOperationException("The localized errCANTPLACE_sdd format has an unexpected conversion %{0}.",
                                                                  (char) conversion);
            }
        }

        byte[] formatted = text.toByteArray();

        this.writeErrorMessage(formatted.length > FORMATTED_TEXT_LIMIT ? Arrays.copyOf(formatted, FORMATTED_TEXT_LIMIT) : formatted);
    }

    /**
     * Writes a chat line with the sender prefix, local text style and player color. The prefix copies the sender's name without its control bytes: in
     * a replay's playback every byte below {@code 0x20} goes and at most {@code 0x50} bytes stay ({@link TerminatedStringUtils#copyPrintableText});
     * in a live game tab, line feed and carriage return stay and the name is cut to a {@code 0x50}-byte buffer
     * ({@link TerminatedStringUtils#cutTextWithoutControlBytes}).
     * <p>
     * A live game's line starts with the sender's bracketed team force name when one is supplied. A sender at or above
     * {@link PlayerTable#PLAYABLE_PLAYER_SLOTS} writes the text without a prefix, and a sender at or above {@link PlayerTable#PLAYER_SLOTS} marks the
     * line content unknown.
     *
     * @param sender the sender slot
     * @param text   the text bytes up to the first zero
     */
    public void writeChat(int sender, byte @NotNull [] text) {
        if (sender >= PlayerTable.PLAYER_SLOTS) {
            this.markUnknown("chat from observer sender " + sender);

            return;
        }

        if (sender >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            this.writeChatLine(text, STYLE_CHAT_TEXT, 0);

            return;
        }

        byte[] name = this.replayPlayback ? TerminatedStringUtils.copyPrintableText(this.playerNames[sender], REPLAY_CHAT_NAME_LIMIT)
                                          : TerminatedStringUtils.cutTextWithoutControlBytes(this.playerNames[sender], CHAT_NAME_SIZE);

        if (this.replayPlayback) {
            byte[] line = new byte[name.length + REPLAY_CHAT_PREFIX_SIZE + text.length];

            System.arraycopy(name, 0, line, 0, name.length);
            line[name.length]                            = ':';
            line[name.length + REPLAY_CHAT_SPACE_OFFSET] = ' ';
            System.arraycopy(text, 0, line, name.length + REPLAY_CHAT_PREFIX_SIZE, text.length);

            this.writeChatLine(line, STYLE_CHAT_TEXT, 0);

            return;
        }

        IntFunction<String> forceNames = this.teamForceNames;
        String force = forceNames == null ? null : forceNames.apply(sender);
        byte[] prefix = force == null ? new byte[0] : ("[" + force + "]").getBytes(StandardCharsets.UTF_8);
        byte[] line = new byte[prefix.length + name.length + CHAT_PREFIX_SIZE + text.length];

        System.arraycopy(prefix, 0, line, 0, prefix.length);
        System.arraycopy(name, 0, line, prefix.length, name.length);

        int textOffset = prefix.length + name.length;

        line[textOffset]                     = ':';
        line[textOffset + CHAT_STYLE_OFFSET] = (byte) (sender == this.localPlayer ? STYLE_OWN_CHAT_TEXT : STYLE_CHAT_TEXT);
        line[textOffset + CHAT_SPACE_OFFSET] = ' ';
        System.arraycopy(text, 0, line, textOffset + CHAT_PREFIX_SIZE, text.length);

        IntUnaryOperator colorSource = this.nameColors;

        this.writeChatLine(line, STYLE_CHAT_TEXT, colorSource == null ? 0 : colorSource.applyAsInt(sender));
    }

    /**
     * Writes a received whisper with the localized sender prefix and whisper style.
     *
     * @param format the localized {@code strWHISPER_FROM} ({@code "<%s>"}, enUS {@code "<From: %s>"})
     * @param sender the sender slot
     * @param text   the text bytes up to the first zero
     */
    public void writeWhisper(@NotNull String format, int sender, byte @NotNull [] text) {
        byte[] name = sender >= 0 && sender < this.playerNames.length ? this.playerNames[sender] : new byte[0];

        this.writeWhisper(format, new String(name, StandardCharsets.UTF_8), text);
    }

    /**
     * Writes a whisper line with a localized prefix that names the other player, a space and the text, all in the whisper style.
     *
     * @param format the localized prefix format with one {@code %s} for the name, for example {@code "<From: %s>"} or {@code "<To: %s>"}
     * @param name   the other player's name
     * @param text   the text bytes
     */
    public void writeWhisper(@NotNull String format, @NotNull String name, byte @NotNull [] text) {
        byte[] prefix = format.replace("%s", name).getBytes(StandardCharsets.UTF_8);

        this.writeChatLine(joinWithSeparator(prefix, text), STYLE_WHISPER, 0);
    }

    /**
     * Writes the localized cheat change message to the system line.
     *
     * @param sender  the sender's player slot
     * @param enabled whether the stored flags are not below the previous ones
     */
    public void writeCheatChange(int sender, boolean enabled) {
        StatusTexts texts = this.statusTexts;
        byte[] text = texts == null ? null : texts.resolveKeyedText(enabled ? CHEAT_ON_TEXT_KEY : CHEAT_OFF_TEXT_KEY);

        if (text == null) {
            this.markUnknown("the cheat message without the localized texts");

            return;
        }

        if (this.multiplayerSession && sender >= 0 && sender < this.playerNames.length) {
            text = joinWithSeparator(this.playerNames[sender], text);
        }

        if (this.hostClock == null) {
            this.markUnknown("the cheat message without the host clock");

            return;
        }

        this.writeSystemLine(text, this.hostClock.getAsInt());
    }

    /**
     * Writes a formatted chat or whisper line and reports its transmission sound and listener event.
     *
     * @param line  the line bytes
     * @param style the color code of the line ({@link #STYLE_CHAT_TEXT} or {@link #STYLE_WHISPER})
     * @param color the packed {@code 0xRRGGBBAA} starting color, or {@code 0}
     */
    private void writeChatLine(byte @NotNull [] line, int style, int color) {
        this.writeMessage(line, DEFAULT_DURATION_MILLISECONDS, style, 0, color);
        this.transmissionSounds++;

        ChatListener listener = this.chatListener;

        if (listener != null) {
            listener.onChatWritten(line, style, color);
        }
    }

    /**
     * Takes and clears the number of pending chat transmission sounds.
     *
     * @return the chat lines written since the last call, each of which plays {@code SND_TRANSMISSION}
     */
    public int takeTransmissionSounds() {
        int count = this.transmissionSounds;

        this.transmissionSounds = 0;

        return count;
    }

    /**
     * Marks line content unknown after an unrepresented writer.
     *
     * @param writer the writer that was not applied
     */
    public void markUnrepresentedWrite(@NotNull String writer) {
        this.markUnknown(writer);
    }

    /**
     * Requires the line content to be known before an observable read.
     *
     * @throws UnsupportedOperationException if a message could not be written because a local input or its writer is missing
     */
    public void requireObservable() {
        if (this.unknownContent) {
            throw Exceptions.newUnsupportedOperationException("The message lines are unknown after {0}.", this.unknownReason);
        }
    }

    /**
     * Writes each text piece to the rotating lines, truncating it to the line capacity and advancing the next line.
     *
     * @param text     the message bytes without a terminator
     * @param deadline the host tick at which the lines expire
     * @throws UnsupportedOperationException if no text layout has been supplied
     */
    public void write(byte @NotNull [] text, int deadline) {
        if (this.layout == null) {
            throw Exceptions.newUnsupportedOperationException("Message lines need the local text layout.");
        }

        int[] ends = this.layout.breakLines(text);
        int start = 0;

        for (int end : ends) {
            int current = this.nextLine & BYTE_MASK;
            int line = current < ROTATING_LINES ? current : 0;

            this.nextLine = advanceLine(current);

            TerminatedStringUtils.putText(text, start, end - start, this.lines, line * LINE_SIZE, LINE_SIZE);

            this.deadlines[line] = deadline;

            start = end;
        }
    }

    /**
     * Writes a localized status text to the system line, marking missing local inputs unknown.
     *
     * @param key            the string key, or {@code null} for a numbered text
     * @param textIdentifier the 1-based {@code stat_txt} id when {@code key} is {@code null}
     */
    public void writeStatus(@Nullable String key, int textIdentifier) {
        StatusTexts texts = this.statusTexts;
        byte[] text = texts == null ? null : key != null ? texts.resolveKeyedText(key) : texts.resolveNumberedText(textIdentifier);

        if (text == null || this.hostClock == null) {
            this.markUnknown("the status line without the local display's localized texts");

            return;
        }

        this.writeSystemLine(text, this.hostClock.getAsInt());
    }

    /**
     * Writes text to the system line with the default expiry duration.
     *
     * @param text      the message bytes without a terminator
     * @param hostTicks the host tick of the write
     */
    public void writeSystemLine(byte @NotNull [] text, int hostTicks) {
        TerminatedStringUtils.putText(text, 0, text.length, this.lines, SYSTEM_LINE * LINE_SIZE, LINE_SIZE);

        this.deadlines[SYSTEM_LINE] = hostTicks + DEFAULT_DURATION_MILLISECONDS;
        this.styles[SYSTEM_LINE]    = STYLE_SYSTEM_LINE;
        this.colors[SYSTEM_LINE]    = 0;
    }

    /**
     * Clears the first byte of every nonempty trigger-text line.
     */
    public void clearTriggerText() {
        for (int line = 0; line < LINE_COUNT; line++) {
            if (this.lines[line * LINE_SIZE] != 0 && (this.triggerFlags[line] & TRIGGER_TEXT_FLAG) != 0) {
                this.lines[line * LINE_SIZE] = 0;
            }
        }
    }

    /**
     * Expires nonempty lines whose deadlines have passed.
     *
     * @param hostTicks the host clock
     */
    public void expire(int hostTicks) {
        for (int line = 0; line < LINE_COUNT; line++) {
            if (this.lines[line * LINE_SIZE] != 0 && hostTicks - this.deadlines[line] >= 0) {
                this.lines[line * LINE_SIZE] = 0;
            }
        }
    }

    /**
     * Reads an unsigned message byte.
     *
     * @param offset the byte offset from the first line, below {@link #LINE_COUNT} times {@link #LINE_SIZE}
     * @return the unsigned byte
     */
    public int readByte(int offset) {
        return this.lines[offset] & BYTE_MASK;
    }

    /**
     * Stores one message byte without changing its deadline or style.
     *
     * @param offset the byte offset from the first line, below {@link #LINE_COUNT} times {@link #LINE_SIZE}
     * @param value  the byte
     */
    public void writeByte(int offset, int value) {
        this.lines[offset] = (byte) value;
    }

    private void markUnknown(@NotNull String reason) {
        if (!this.unknownContent) {
            this.unknownReason = reason;
        }

        this.unknownContent = true;
    }

    /**
     * Writes an ordinary message with an optional starting color, retaining it until a color code changes it.
     *
     * @param text                 the message bytes without a terminator
     * @param durationMilliseconds the display duration in milliseconds
     * @param style                the style code of the first piece
     * @param triggerFlag          the trigger flag of every written line
     * @param color                the packed {@code 0xRRGGBBAA} starting color, or {@code 0}
     */
    private void writeMessage(byte @NotNull [] text, int durationMilliseconds, int style, int triggerFlag, int color) {
        if (text.length == 0) {
            return;
        }

        if (this.layout == null || this.hostClock == null) {
            this.markUnknown("a message without the local text layout or host clock");

            return;
        }

        int deadline = this.hostClock.getAsInt() + durationMilliseconds;
        int firstLine = this.nextLine & BYTE_MASK;

        this.write(text, deadline);

        int line = firstLine < ROTATING_LINES ? firstLine : 0;
        int[] ends = this.layout.breakLines(text);
        int pieceStyle = style;
        int pieceColor = color;
        int start = 0;

        for (int end : ends) {
            this.styles[line]       = pieceStyle;
            this.colors[line]       = pieceColor;
            this.triggerFlags[line] = (byte) triggerFlag;

            line = advanceLine(line);

            int nextStyle = pieceStyle;

            for (int colorIndex = end - 1; colorIndex >= start; colorIndex--) {
                int code = text[colorIndex] & BYTE_MASK;

                if (code >= FIRST_COLOR_RANGE_START && code <= FIRST_COLOR_RANGE_END ||
                    code >= SECOND_COLOR_RANGE_START && code <= SECOND_COLOR_RANGE_END ||
                    code >= THIRD_COLOR_RANGE_START && code <= THIRD_COLOR_RANGE_END ||
                    code >= FOURTH_COLOR_RANGE_START && code <= FOURTH_COLOR_RANGE_END) {
                    nextStyle = code;

                    break;
                }
            }

            if (nextStyle != pieceStyle) {
                pieceColor = 0;
            }

            pieceStyle = nextStyle;
            start      = end;
        }
    }
}
