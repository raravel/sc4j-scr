package org.fogofwarlabs.sc4j.scr.game.session.setup;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.state.VictoryCondition;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;

/**
 * Holds a game type template and its options.
 */
public final class GameTemplate {
    /**
     * The size of the game options.
     */
    public static final int SIZE = 32;
    /**
     * The number of option bytes.
     */
    public static final int OPTION_COUNT = 11;
    /**
     * The type id of no game type.
     */
    public static final int TYPE_NONE = 0;
    /**
     * The type id of Melee.
     */
    public static final int TYPE_MELEE = 2;
    /**
     * The type id of Free For All.
     */
    public static final int TYPE_FREE_FOR_ALL = 3;
    /**
     * The type id of One on One.
     */
    public static final int TYPE_ONE_ON_ONE = 4;
    /**
     * The type id of Capture The Flag.
     */
    public static final int TYPE_CAPTURE_THE_FLAG = 5;
    /**
     * The type id of Greed.
     */
    public static final int TYPE_GREED = 6;
    /**
     * The type id of Slaughter.
     */
    public static final int TYPE_SLAUGHTER = 7;
    /**
     * The type id of Sudden Death.
     */
    public static final int TYPE_SUDDEN_DEATH = 8;
    /**
     * The type id of Use Map Settings.
     */
    public static final int TYPE_USE_MAP_SETTINGS = 10;
    /**
     * The type id of Team Melee.
     */
    public static final int TYPE_TEAM_MELEE = 11;
    /**
     * The type id of Team Free For All.
     */
    public static final int TYPE_TEAM_FREE_FOR_ALL = 12;
    /**
     * The type id of Team Capture The Flag.
     */
    public static final int TYPE_TEAM_CAPTURE_THE_FLAG = 13;
    /**
     * The type id of Top vs Bottom.
     */
    public static final int TYPE_TOP_VS_BOTTOM = 15;
    /**
     * The SDK type of a game type that has no entry in the SDK type table.
     */
    public static final int NO_SDK_TYPE = 0;
    /**
     * The option index of the victory condition.
     */
    public static final int VICTORY = 0;
    /**
     * The option index of the resources type.
     */
    public static final int RESOURCES = 1;
    /**
     * The option index of the starting units.
     */
    public static final int STARTING_UNITS = 4;
    /**
     * The option index of the fixed start positions.
     */
    public static final int START_POSITIONS = 5;
    /**
     * The option index of the players option.
     */
    public static final int PLAYERS = 6;
    /**
     * The option index of the allies option.
     */
    public static final int ALLIES = 7;
    /**
     * The option index of the team count.
     */
    public static final int TEAMS = 8;
    /**
     * The option index of the tournament mode.
     */
    public static final int TOURNAMENT = 10;
    /**
     * The Melee template, the game options of {@code Templates\Melee(1).got}.
     */
    public static final @NotNull GameTemplate MELEE = parse(HexFormat.of().parseHex("0200010000000000010101020200030100010000000000320000000000000000"));
    /**
     * The Free For All template, the game options of {@code Templates\Free For All(1).got}.
     */
    public static final @NotNull GameTemplate FREE_FOR_ALL = parse(HexFormat.of().parseHex("0300010000000000010101020200030000010000000000320000000000000000"));
    /**
     * The Use Map Settings template, the game options of {@code Templates\Use Map Settings(1).got}.
     */
    public static final @NotNull GameTemplate USE_MAP_SETTINGS = parse(HexFormat.of().parseHex("0a00010000000000000000020001030100010000000000000000000000000000"));
    /**
     * The masks of an unsigned BYTE and WORD field.
     */
    private static final int BYTE_MASK = 0xFF;
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The SDK type of each game type from {@link #TYPE_MELEE}; {@link #NO_SDK_TYPE} where the table has no entry.
     */
    private static final int @NotNull [] SDK_TYPES = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, NO_SDK_TYPE, 13};
    /**
     * The SDK type of {@link #TYPE_TOP_VS_BOTTOM}, the highest SDK type.
     */
    private static final int SDK_TOP_VS_BOTTOM = 13;

    /**
     * Creates a {@link GameTemplate} instance from the 32-byte game options.
     *
     * @param data the game options
     * @return the created {@link GameTemplate} instance
     * @throws IllegalArgumentException if the data is shorter than {@link #SIZE}
     */
    public static @NotNull GameTemplate parse(byte @NotNull [] data) {
        if (data.length < SIZE) {
            throw Exceptions.newIllegalArgumentException("The game options must hold at least {0} bytes, not {1}.", SIZE, data.length);
        }

        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int typeId = buffer.get() & BYTE_MASK;
        int leagueId = buffer.get() & BYTE_MASK;
        int subTypeId = buffer.getShort() & WORD_MASK;
        int subTypeValue = buffer.getInt();
        byte[] options = new byte[OPTION_COUNT];

        buffer.get(options);

        return new GameTemplate(typeId, leagueId, subTypeId, subTypeValue, options, buffer.getInt(), buffer.getInt());
    }

    /**
     * Converts a game type id to its SDK type, the numbering of the LAN game records and of the localized game type names:
     * {@code 2..13} become {@code 1..12} and Top vs Bottom becomes {@code 13}.
     *
     * @param typeId the game type id
     * @return the SDK type, or {@link #NO_SDK_TYPE} if the table has no entry for the game type
     */
    public static int toSdkType(int typeId) {
        int index = typeId - TYPE_MELEE;

        return index < 0 || index >= SDK_TYPES.length ? NO_SDK_TYPE : SDK_TYPES[index];
    }

    /**
     * Converts an SDK type to its game type id, the inverse of {@link #toSdkType}.
     *
     * @param sdkType the SDK type
     * @return the game type id, or {@link #TYPE_NONE} for a value outside {@code 1..13}
     */
    public static int fromSdkType(int sdkType) {
        if (sdkType < 1 || sdkType > SDK_TOP_VS_BOTTOM) {
            return TYPE_NONE;
        }

        return sdkType == SDK_TOP_VS_BOTTOM ? TYPE_TOP_VS_BOTTOM : sdkType + 1;
    }

    private final int typeId;
    private final int leagueId;

    private final int subTypeId;
    private final int subTypeValue;

    private final byte @NotNull [] options;

    private final int victoryValue;
    private final int resourcesValue;

    /**
     * Creates a {@link GameTemplate} instance.
     *
     * @param typeId         the type id
     * @param leagueId       the league id
     * @param subTypeId      the subtype id
     * @param subTypeValue   the subtype value
     * @param options        the eleven option bytes
     * @param victoryValue   the victory value
     * @param resourcesValue the resources value
     * @throws IllegalArgumentException if there are not eleven option bytes
     */
    public GameTemplate(int typeId, int leagueId, int subTypeId, int subTypeValue, byte @NotNull [] options, int victoryValue, int resourcesValue) {
        if (options.length != OPTION_COUNT) {
            throw Exceptions.newIllegalArgumentException("A game template must have {0} option bytes, not {1}.", OPTION_COUNT, options.length);
        }

        this.typeId   = typeId;
        this.leagueId = leagueId;

        this.subTypeId    = subTypeId;
        this.subTypeValue = subTypeValue;

        this.options = options.clone();

        this.victoryValue   = victoryValue;
        this.resourcesValue = resourcesValue;
    }

    /**
     * @return the type id
     */
    public int getTypeId() {
        return this.typeId;
    }

    /**
     * @return the subtype id
     */
    public int getSubTypeId() {
        return this.subTypeId;
    }

    /**
     * @return the subtype value: Greed resources, Slaughter minutes, team count, top slots
     */
    public int getSubTypeValue() {
        return this.subTypeValue;
    }

    /**
     * @return a copy of the eleven option bytes, unsigned
     */
    public int @NotNull [] getOptions() {
        int[] values = new int[OPTION_COUNT];

        for (int index = 0; index < OPTION_COUNT; index++) {
            values[index] = this.options[index] & BYTE_MASK;
        }

        return values;
    }

    /**
     * @param index the option index ({@link #VICTORY} .. {@link #TOURNAMENT})
     * @return the unsigned option byte
     */
    public int getOption(int index) {
        return this.options[index] & BYTE_MASK;
    }

    /**
     * @return the victory value
     */
    public int getVictoryValue() {
        return this.victoryValue;
    }

    /**
     * @return the resources value (the starting minerals)
     */
    public int getResourcesValue() {
        return this.resourcesValue;
    }

    /**
     * @return the victory condition of the template
     */
    public @NotNull VictoryCondition getVictoryCondition() {
        return VictoryCondition.of(this.getOption(VICTORY));
    }

    /**
     * @return {@code true} if the template is Use Map Settings, {@code false} otherwise
     */
    public boolean isUseMapSettings() {
        return this.getOption(VICTORY) == 0 && this.getOption(STARTING_UNITS) == 0 && this.getOption(TOURNAMENT) == 0;
    }

    /**
     * @return {@code true} if the template is Top vs Bottom, {@code false} otherwise
     */
    public boolean isTopVsBottom() {
        return this.typeId == TYPE_TOP_VS_BOTTOM;
    }

    /**
     * @return {@code true} if the teams option is set, {@code false} otherwise
     */
    public boolean hasTeams() {
        return this.getOption(TEAMS) != 0;
    }


    /**
     * Tests whether a map has enough player slots for the template's teams.
     *
     * @param mapPlayerSlots the map's player slots
     * @return {@code true} if the map supports the template, {@code false} otherwise
     */
    public boolean isSupportedBy(int mapPlayerSlots) {
        int teams = this.getOption(TEAMS);

        return teams == 0 || teams <= mapPlayerSlots;
    }

    /**
     * Encodes the game options.
     *
     * @return the 32-byte game options
     */
    public byte @NotNull [] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);

        buffer.put((byte) this.typeId).put((byte) this.leagueId).putShort((short) this.subTypeId).putInt(this.subTypeValue).put(this.options)
              .putInt(this.victoryValue).putInt(this.resourcesValue);

        return buffer.array();
    }
}
