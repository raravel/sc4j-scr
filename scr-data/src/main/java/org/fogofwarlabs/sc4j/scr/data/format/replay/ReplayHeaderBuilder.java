package org.fogofwarlabs.sc4j.scr.data.format.replay;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.text.TerminatedStringUtils;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Builds replay header sections with player records, colors and game setup fields.
 */
public final class ReplayHeaderBuilder {
    /**
     * The size of the header section.
     */
    public static final int HEADER_SIZE = 0x279;
    /**
     * The number of player records.
     */
    public static final int PLAYER_COUNT = 12;

    /**
     * The offset of the engine BYTE ({@code 1} for Brood War).
     */
    public static final int ENGINE_OFFSET = 0;
    /**
     * The offset of the frame count DWORD.
     */
    public static final int FRAMES_OFFSET = 1;
    /**
     * The offset of the campaign mission WORD.
     */
    public static final int CAMPAIGN_MISSION_OFFSET = 5;
    /**
     * The offset of the seed DWORD.
     */
    public static final int SEED_OFFSET = 8;
    private static final int HOST_NAME_OFFSET = 0x18;
    /**
     * The host name buffer, terminator included.
     */
    static final int HOST_NAME_SIZE = 24;
    private static final int MAP_WIDTH_OFFSET = 0x34;
    private static final int MAP_HEIGHT_OFFSET = 0x36;
    private static final int ACTIVE_PLAYERS_OFFSET = 0x38;
    private static final int SLOT_COUNT_OFFSET = 0x39;
    private static final int SPEED_OFFSET = 0x3A;
    private static final int GAME_STATE_OFFSET = 0x3B;
    /**
     * The offset of the game type WORD.
     */
    public static final int GAME_TYPE_OFFSET = 0x3C;
    /**
     * The offset of the game subtype WORD.
     */
    public static final int GAME_SUBTYPE_OFFSET = 0x3E;
    private static final int TURN_RATE_OFFSET = 0x40;
    private static final int TILESET_OFFSET = 0x44;
    private static final int SAVED_FLAG_OFFSET = 0x46;
    private static final int COMPUTER_COUNT_OFFSET = 0x47;
    private static final int GAME_NAME_OFFSET = 0x48;
    private static final int GAME_NAME_SIZE = 25;
    private static final int MAP_NAME_OFFSET = 0x61;
    private static final int MAP_NAME_SIZE = 32;
    /**
     * The offset of the game options block; it starts with the type and subtype WORDs.
     */
    private static final int GAME_OPTIONS_OFFSET = 0x81;
    /**
     * The size of the game options block.
     */
    private static final int GAME_OPTIONS_SIZE = 32;
    private static final int GAME_OPTIONS_SUBTYPE_OFFSET = GAME_OPTIONS_OFFSET + Short.BYTES;

    /**
     * The offset of the twelve player records.
     */
    public static final int PLAYERS_OFFSET = 0xA1;
    /**
     * The size of a player record.
     */
    public static final int PLAYER_RECORD_SIZE = 36;
    /**
     * The offset of the network id DWORD in a player record.
     */
    public static final int PLAYER_ID_OFFSET = 4;
    /**
     * The offset of the player type BYTE in a player record.
     */
    public static final int PLAYER_TYPE_OFFSET = 8;
    /**
     * The offset of the race BYTE in a player record.
     */
    public static final int PLAYER_RACE_OFFSET = 9;
    /**
     * The offset of the force BYTE in a player record.
     */
    public static final int PLAYER_TEAM_OFFSET = 10;
    /**
     * The offset of the name bytes in a player record.
     */
    public static final int PLAYER_NAME_OFFSET = 11;
    /**
     * The name bytes of a player record before its terminating zero.
     */
    private static final int PLAYER_NAME_CAPACITY = 24;
    /**
     * The name buffer of a player record, terminator included.
     */
    static final int PLAYER_NAME_SIZE = PLAYER_NAME_CAPACITY + 1;
    /**
     * The first code point that the single-byte name conversion writes as {@code '?'}.
     */
    private static final int FIRST_NON_ASCII = 0x80;
    /**
     * The character that the single-byte name conversion writes for a code point from {@link #FIRST_NON_ASCII} on.
     */
    private static final char NON_ASCII_REPLACEMENT = '?';

    /**
     * The offset of the eight player color DWORDs.
     */
    public static final int COLORS_OFFSET = 0x251;
    /**
     * The number of player color DWORDs.
     */
    static final int COLOR_COUNT = 8;
    /**
     * The offset of the eight melee unit player flag BYTEs.
     */
    public static final int MELEE_UNIT_FLAGS_OFFSET = 0x271;
    /**
     * The number of melee unit player flag BYTEs, one per player slot.
     */
    static final int MELEE_UNIT_FLAG_COUNT = 8;

    /**
     * Puts a text's UTF-8 bytes into a header field as the game header's writers fill it: at most {@code size - 1} bytes
     * and the terminator ({@link TerminatedStringUtils#putText}).
     *
     * @param data   the header bytes
     * @param offset the offset of the field
     * @param size   the field size, terminator included
     * @param text   the text
     */
    private static void putText(byte @NotNull [] data, int offset, int size, @NotNull String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        TerminatedStringUtils.putText(bytes, 0, bytes.length, data, offset, size);
    }


    private final byte @NotNull [] data;

    /**
     * Creates a {@link ReplayHeaderBuilder} instance with an all-zero header.
     */
    public ReplayHeaderBuilder() {
        this.data = new byte[HEADER_SIZE];
    }

    /**
     * Sets the engine byte ({@code 1} for Brood War).
     *
     * @param engine the engine byte
     * @return this instance for chaining
     */
    public @NotNull ReplayHeaderBuilder setEngine(int engine) {
        this.data[ENGINE_OFFSET] = (byte) engine;

        return this;
    }

    /**
     * Sets the recorded frame count.
     *
     * @param frames the number of frames
     * @return this instance for chaining
     */
    public @NotNull ReplayHeaderBuilder setFrames(long frames) {
        LittleEndianUtils.writeInt32(this.data, FRAMES_OFFSET, (int) frames);

        return this;
    }

    /**
     * Sets the campaign mission WORD at offset {@code 5} (the {@code arr\mapdata.dat} index; zero outside campaigns).
     *
     * @param mission the campaign mission index
     * @return this instance for chaining
     */
    public @NotNull ReplayHeaderBuilder setCampaignMission(int mission) {
        LittleEndianUtils.writeInt16(this.data, CAMPAIGN_MISSION_OFFSET, mission);

        return this;
    }

    /**
     * Sets the shared random seed.
     *
     * @param seed the seed DWORD
     * @return this instance for chaining
     */
    public @NotNull ReplayHeaderBuilder setSeed(int seed) {
        LittleEndianUtils.writeInt32(this.data, SEED_OFFSET, seed);

        return this;
    }

    /**
     * Sets the host player name at offset {@code 0x18} ({@code 24} bytes, UTF-8).
     *
     * @param name the host name
     * @return this instance for chaining
     */
    public @NotNull ReplayHeaderBuilder setHostName(@NotNull String name) {
        putText(this.data, HOST_NAME_OFFSET, HOST_NAME_SIZE, name);

        return this;
    }

    /**
     * Sets the map dimensions in tiles.
     *
     * @param width  the width in tiles
     * @param height the height in tiles
     * @return this instance for chaining
     */
    public @NotNull ReplayHeaderBuilder setMapSize(int width, int height) {
        LittleEndianUtils.writeInt16(this.data, MAP_WIDTH_OFFSET, width);
        LittleEndianUtils.writeInt16(this.data, MAP_HEIGHT_OFFSET, height);

        return this;
    }

    /**
     * Sets the lobby counters at {@code 0x38..0x3b}.
     *
     * @param activePlayers the number of human players
     * @param slotCount     the number of map slots
     * @param speed         the game speed, {@code 0} (Slowest) through {@code 6} (Fastest)
     * @param gameState     the game state byte
     * @return this instance for chaining
     */
    public @NotNull ReplayHeaderBuilder setCounts(int activePlayers, int slotCount, int speed, int gameState) {
        this.data[ACTIVE_PLAYERS_OFFSET] = (byte) activePlayers;
        this.data[SLOT_COUNT_OFFSET]     = (byte) slotCount;
        this.data[SPEED_OFFSET]          = (byte) speed;
        this.data[GAME_STATE_OFFSET]     = (byte) gameState;

        return this;
    }

    /**
     * Sets the network turns per second DWORD at {@code 0x40} (zero in single player).
     *
     * @param turnRate the turns per second
     * @return this instance for chaining
     */
    public @NotNull ReplayHeaderBuilder setTurnRate(int turnRate) {
        LittleEndianUtils.writeInt32(this.data, TURN_RATE_OFFSET, turnRate);

        return this;
    }

    /**
     * Sets the game type and subtype, stored both in the lobby block ({@code 0x3c}) and the game options block
     * ({@code 0x81}); {@link #setGameOptions} replaces the latter with the whole template.
     *
     * @param type    the game type WORD
     * @param subType the game subtype WORD
     * @return this instance for chaining
     */
    public @NotNull ReplayHeaderBuilder setGameType(int type, int subType) {
        LittleEndianUtils.writeInt16(this.data, GAME_TYPE_OFFSET, type);
        LittleEndianUtils.writeInt16(this.data, GAME_SUBTYPE_OFFSET, subType);
        LittleEndianUtils.writeInt16(this.data, GAME_OPTIONS_OFFSET, type);
        LittleEndianUtils.writeInt16(this.data, GAME_OPTIONS_SUBTYPE_OFFSET, subType);

        return this;
    }

    /**
     * Sets the tileset WORD and the two following bytes.
     *
     * @param tileset       the map tileset
     * @param savedFlag     the byte at {@code 0x46}
     * @param computerCount the byte at {@code 0x47}
     * @return this instance for chaining
     */
    public @NotNull ReplayHeaderBuilder setTileset(int tileset, int savedFlag, int computerCount) {
        LittleEndianUtils.writeInt16(this.data, TILESET_OFFSET, tileset);
        this.data[SAVED_FLAG_OFFSET]     = (byte) savedFlag;
        this.data[COMPUTER_COUNT_OFFSET] = (byte) computerCount;

        return this;
    }

    /**
     * Sets the game name at {@code 0x48} ({@code 25} bytes) and the map name at {@code 0x61} ({@code 32} bytes), both UTF-8.
     *
     * @param gameName the game name
     * @param mapName  the map name
     * @return this instance for chaining
     */
    public @NotNull ReplayHeaderBuilder setNames(@NotNull String gameName, @NotNull String mapName) {
        putText(this.data, GAME_NAME_OFFSET, GAME_NAME_SIZE, gameName);
        putText(this.data, MAP_NAME_OFFSET, MAP_NAME_SIZE, mapName);

        return this;
    }

    /**
     * Sets the game options block at {@code 0x81}: the {@code 32} bytes of the game type template (type, league, subtype identifier,
     * subtype value, the eleven option bytes, victory value, resources value and padding).
     *
     * @param options the {@code 32} option bytes
     * @return this instance for chaining
     * @throws IllegalArgumentException if the block does not hold {@code 32} bytes
     */
    public @NotNull ReplayHeaderBuilder setGameOptions(byte @NotNull [] options) {
        if (options.length != GAME_OPTIONS_SIZE) {
            throw Exceptions.newIllegalArgumentException("The game options block has {0} bytes, not {1}.", GAME_OPTIONS_SIZE, options.length);
        }

        System.arraycopy(options, 0, this.data, GAME_OPTIONS_OFFSET, GAME_OPTIONS_SIZE);

        return this;
    }

    /**
     * Sets one {@code 36}-byte player record at {@code 0xa1 + index * 36}.
     * <p>
     * The record keeps UTF-8 name bytes, and the single-byte conversion then overwrites the start with the converted name (non-ASCII
     * characters become {@code '?'}) and a terminating zero, leaving the UTF-8 tail after it.
     *
     * @param index    the record index, {@code 0} through {@code 11}
     * @param slotId   the stored slot DWORD
     * @param playerId the network player DWORD, {@code -1} for computers and empty slots
     * @param type     the player type byte
     * @param race     the race byte
     * @param team     the force byte
     * @param name     the player name
     * @return this instance for chaining
     * @throws IllegalArgumentException if the record index is outside the twelve records
     */
    public @NotNull ReplayHeaderBuilder setPlayer(int index, int slotId, int playerId, int type, int race, int team, @NotNull String name) {
        if (index < 0 || index >= PLAYER_COUNT) {
            throw Exceptions.newIllegalArgumentException("Player record index out of range: {0}", index);
        }

        int offset = PLAYERS_OFFSET + index * PLAYER_RECORD_SIZE;

        LittleEndianUtils.writeInt32(this.data, offset, slotId);
        LittleEndianUtils.writeInt32(this.data, offset + PLAYER_ID_OFFSET, playerId);
        this.data[offset + PLAYER_TYPE_OFFSET] = (byte) type;
        this.data[offset + PLAYER_RACE_OFFSET] = (byte) race;
        this.data[offset + PLAYER_TEAM_OFFSET] = (byte) team;

        byte[] utf8Bytes = name.getBytes(StandardCharsets.UTF_8);
        int utf8ByteCount = Math.min(utf8Bytes.length, PLAYER_NAME_CAPACITY);
        int position = 0;

        System.arraycopy(utf8Bytes, 0, this.data, offset + PLAYER_NAME_OFFSET, utf8ByteCount);

        for (int characterIndex = 0; characterIndex < name.length() && position < PLAYER_NAME_CAPACITY; ) {
            int codePoint = name.codePointAt(characterIndex);

            this.data[offset + PLAYER_NAME_OFFSET + position++] = (byte) (codePoint < FIRST_NON_ASCII ? codePoint : NON_ASCII_REPLACEMENT);
            characterIndex += Character.charCount(codePoint);
        }

        this.data[offset + PLAYER_NAME_OFFSET + position] = 0;

        return this;
    }

    /**
     * Sets the name of a player record as the game's player table holds a computer's: the name bytes cut to the record's
     * {@code 24} name bytes and a terminator, without the single-byte conversion of {@link #setPlayer}.
     *
     * @param index the record index, {@code 0} through {@code 11}
     * @param name  the name bytes, without terminator
     * @return this instance for chaining
     * @throws IllegalArgumentException if the record index is outside the twelve records
     */
    public @NotNull ReplayHeaderBuilder setPlayerTableName(int index, byte @NotNull [] name) {
        if (index < 0 || index >= PLAYER_COUNT) {
            throw Exceptions.newIllegalArgumentException("Player record index out of range: {0}", index);
        }

        int nameOffset = PLAYERS_OFFSET + index * PLAYER_RECORD_SIZE + PLAYER_NAME_OFFSET;

        TerminatedStringUtils.putText(name, 0, name.length, this.data, nameOffset, PLAYER_NAME_SIZE);

        return this;
    }

    /**
     * Sets the name of a player record as the game's player table holds a network player's: the record's name bytes cleared,
     * then the UTF-8 name bytes cut to the record's {@code 24} name bytes on whole characters and a terminator
     * ({@link TerminatedStringUtils#putWholeCharacters}), without the single-byte conversion of {@link #setPlayer}.
     *
     * @param index the record index, {@code 0} through {@code 11}
     * @param name  the name bytes, without terminator
     * @return this instance for chaining
     * @throws IllegalArgumentException if the record index is outside the twelve records
     */
    public @NotNull ReplayHeaderBuilder setPlayerTableNetworkName(int index, byte @NotNull [] name) {
        if (index < 0 || index >= PLAYER_COUNT) {
            throw Exceptions.newIllegalArgumentException("Player record index out of range: {0}", index);
        }

        int nameOffset = PLAYERS_OFFSET + index * PLAYER_RECORD_SIZE + PLAYER_NAME_OFFSET;

        Arrays.fill(this.data, nameOffset, nameOffset + PLAYER_NAME_SIZE, (byte) 0);
        TerminatedStringUtils.putWholeCharacters(name, 0, name.length, this.data, nameOffset, PLAYER_NAME_SIZE);

        return this;
    }

    /**
     * Sets the eight player colors at {@code 0x251}.
     *
     * @param colors the eight color DWORDs in player slot order
     * @return this instance for chaining
     * @throws IllegalArgumentException if the array does not hold eight colors
     */
    public @NotNull ReplayHeaderBuilder setColors(int @NotNull [] colors) {
        if (colors.length != COLOR_COUNT) {
            throw Exceptions.newIllegalArgumentException("The header stores eight player colors.");
        }

        for (int index = 0; index < COLOR_COUNT; index++) {
            LittleEndianUtils.writeInt32(this.data, COLORS_OFFSET + index * Integer.BYTES, colors[index]);
        }

        return this;
    }

    /**
     * Sets the eight per-player melee unit bytes at {@code 0x271}.
     *
     * @param values the eight bytes
     * @return this instance for chaining
     * @throws IllegalArgumentException if the array does not hold eight values
     */
    public @NotNull ReplayHeaderBuilder setMeleeUnitPlayers(boolean @NotNull [] values) {
        if (values.length != MELEE_UNIT_FLAG_COUNT) {
            throw Exceptions.newIllegalArgumentException("The header stores eight melee unit bytes.");
        }

        for (int index = 0; index < MELEE_UNIT_FLAG_COUNT; index++) {
            this.data[MELEE_UNIT_FLAGS_OFFSET + index] = (byte) (values[index] ? 1 : 0);
        }

        return this;
    }


    /**
     * Builds a copy of the header section bytes.
     *
     * @return the {@code 0x279} header bytes
     */
    public byte @NotNull [] build() {
        return this.data.clone();
    }
}
