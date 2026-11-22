package org.fogofwarlabs.sc4j.scr.game.session.setup;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.text.TerminatedStringUtils;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkValidatorUtils;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayHeaderBuilder;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.trigger.ScrTriggerSystem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;

/**
 * Describes a lobby and resolves it into the game header records at game start.
 */
public final class MeleeGameSetup {
    /**
     * The lobby type of a human player.
     */
    public static final int TYPE_HUMAN = PlayerTypes.HUMAN;
    /**
     * The lobby type of a computer player.
     */
    public static final int TYPE_COMPUTER = LobbyResolution.TYPE_COMPUTER;
    /**
     * The lobby type of a closed slot.
     */
    public static final int TYPE_CLOSED = LobbyResolution.TYPE_CLOSED;

    private static final int MAXIMUM_GAME_SPEED = 6;
    private static final int SCENARIO_VERSION_MASK = 7;
    private static final int ORIGINAL_SCENARIO_VERSION_MAXIMUM = 4;
    private static final int FORCE_FLAGS_OFFSET = 16;
    private static final int FORCE_DATA_SIZE = FORCE_FLAGS_OFFSET + PlayerTable.FORCE_COUNT;
    private static final int SAVED_SPEED = 0x3A;
    private static final int SAVED_TEMPLATE = 0x81;
    private static final int SAVED_PLAYER_SLOT = 0;
    /**
     * The name bytes of a saved header's player record, terminator included.
     */
    private static final int SAVED_PLAYER_NAME_SIZE = 25;

    private static final @NotNull String RAW_SCENARIO_EXTENSION = ".chk";
    private static final @NotNull String COMPUTER_NAME = "Computer";

    /**
     * One resolved {@code 36}-byte player record.
     * <p>
     * The record holds the name as bytes: the UTF-8 bytes of a lobby name, or the bytes a saved header's record holds, which
     * the saved game's replay writes again as they are.
     */
    public static final class PlayerRecord {
        private final int playerId;
        private final int type;
        private final int race;
        private final int team;
        private final byte @NotNull [] name;

        /**
         * Creates a {@link PlayerRecord} instance whose name is given as text.
         *
         * @param playerId the network player id, {@code -1} for none
         * @param type     the player type byte
         * @param race     the race byte
         * @param team     the force byte
         * @param name     the player name
         */
        public PlayerRecord(int playerId, int type, int race, int team, @NotNull String name) {
            this(playerId, type, race, team, name.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Creates a {@link PlayerRecord} instance whose name is given as bytes.
         *
         * @param playerId the network player id, {@code -1} for none
         * @param type     the player type byte
         * @param race     the race byte
         * @param team     the force byte
         * @param name     the name bytes without terminator, retained
         */
        public PlayerRecord(int playerId, int type, int race, int team, byte @NotNull [] name) {
            this.playerId = playerId;
            this.type     = type;
            this.race     = race;
            this.team     = team;
            this.name     = name;
        }

        /**
         * @return the network player identifier, or {@code -1} when none is assigned
         */
        public int getPlayerId() {
            return this.playerId;
        }

        /**
         * @return the player type byte
         */
        public int getType() {
            return this.type;
        }

        /**
         * @return the race byte
         */
        public int getRace() {
            return this.race;
        }

        /**
         * @return the force byte
         */
        public int getTeam() {
            return this.team;
        }

        /**
         * @return the player name read from its bytes for display and comparison ({@link TerminatedStringUtils#readText})
         */
        public @NotNull String getName() {
            return TerminatedStringUtils.readText(this.name);
        }

        /**
         * @return a copy of the name bytes without terminator
         */
        public byte @NotNull [] getNameBytes() {
            return this.name.clone();
        }
    }

    /**
     * Parses a race name.
     *
     * @param name {@code zerg}, {@code terran}, {@code protoss} or {@code random} (case-insensitive, first letter suffices)
     * @return the race byte
     * @throws IllegalArgumentException if the name is not a race
     */
    public static int parseRace(@NotNull String name) {
        String value = name.trim().toLowerCase(Locale.ROOT);

        if (value.isEmpty()) {
            throw Exceptions.newIllegalArgumentException("Empty race name.");
        }

        return switch (value.charAt(0)) {
            case 'z' -> ScrRaceIds.ZERG;
            case 't' -> ScrRaceIds.TERRAN;
            case 'p' -> ScrRaceIds.PROTOSS;
            case 'r' -> ScrRaceIds.RANDOM;
            default -> throw Exceptions.newIllegalArgumentException("Unknown race: {0} (use terran, zerg, protoss or random).", name);
        };
    }

    /**
     * Creates a {@link MeleeGameSetup} instance from command-line style values.
     *
     * @param mapPath       a {@code .scm}/{@code .scx} map archive or a raw {@code .chk} scenario
     * @param humanName     the local player name
     * @param humanRace     the human race name ({@link #parseRace})
     * @param computerRaces the comma-separated computer race names, in lobby order
     * @param humanSlot     the human's index among the map's start slots
     * @param speed         the game speed, {@code 0} (Slowest) through {@code 6} (Fastest)
     * @return the created {@link MeleeGameSetup} instance
     * @throws IOException              if the map cannot be read
     * @throws IllegalArgumentException if a value is invalid for the map
     */
    public static @NotNull MeleeGameSetup load(@NotNull Path mapPath,
                                               @NotNull String humanName, @NotNull String humanRace,
                                               @NotNull String computerRaces,
                                               int humanSlot,
                                               int speed) throws IOException {
        return load(mapPath, humanName, humanRace, computerRaces, humanSlot, speed, false);
    }

    /**
     * Creates a {@link MeleeGameSetup} instance from command-line style values, optionally in the map compatibility mode.
     *
     * @param mapPath          a {@code .scm}/{@code .scx} map archive or a raw {@code .chk} scenario
     * @param humanName        the local player name
     * @param humanRace        the human race name ({@link #parseRace})
     * @param computerRaces    the comma-separated computer race names, in lobby order
     * @param humanSlot        the human's index among the map's start slots
     * @param speed            the game speed, {@code 0} (Slowest) through {@code 6} (Fastest)
     * @param mapCompatibility whether the map loads with the 1.16-era relaxed rules
     *                         ({@link ChkValidatorUtils#validate(ChkFile, boolean, boolean, boolean)}) instead of the strict rules
     * @return the created {@link MeleeGameSetup} instance
     * @throws IOException              if the map cannot be read or the rules reject it
     * @throws IllegalArgumentException if a value is invalid for the map
     */
    public static @NotNull MeleeGameSetup load(@NotNull Path mapPath,
                                               @NotNull String humanName, @NotNull String humanRace,
                                               @NotNull String computerRaces,
                                               int humanSlot,
                                               int speed,
                                               boolean mapCompatibility) throws IOException {
        MpqArchive mapArchive = openMapArchive(mapPath, mapCompatibility);
        byte[] mapData = readScenario(mapPath, mapArchive);
        String[] names = computerRaces.split(",");
        int[] races = new int[names.length];

        for (int index = 0; index < names.length; index++) {
            races[index] = parseRace(names[index]);
        }

        MeleeGameSetup setup = new MeleeGameSetup(mapData,
                                                  humanName, parseRace(humanRace), humanSlot,
                                                  races,
                                                  speed,
                                                  false,
                                                  0,
                                                  true,
                                                  mapCompatibility);

        setup.mapPath    = mapPath;
        setup.mapArchive = mapArchive;

        return setup;
    }

    /**
     * Creates a {@link MeleeGameSetup} instance for a single-player Use Map Settings game from command-line style values.
     *
     * @param mapPath   a {@code .scm}/{@code .scx} map archive or a raw {@code .chk} scenario
     * @param humanName the local player name
     * @param humanRace the human race name ({@link #parseRace}), used for a selectable slot race
     * @param humanSlot the human's index among the map's human slots
     * @param speed     the game speed, {@code 0} (Slowest) through {@code 6} (Fastest)
     * @return the created {@link MeleeGameSetup} instance
     * @throws IOException              if the map cannot be read
     * @throws IllegalArgumentException if a value is invalid for the map
     */
    public static @NotNull MeleeGameSetup loadUseMapSettings(@NotNull Path mapPath,
                                                             @NotNull String humanName, @NotNull String humanRace, int humanSlot,
                                                             int speed) throws IOException {
        return loadUseMapSettings(mapPath, humanName, humanRace, humanSlot, speed, false);
    }

    /**
     * Creates a {@link MeleeGameSetup} instance for a single-player Use Map Settings game, optionally in the map
     * compatibility mode.
     *
     * @param mapPath          a {@code .scm}/{@code .scx} map archive or a raw {@code .chk} scenario
     * @param humanName        the local player name
     * @param humanRace        the human race name ({@link #parseRace}), used for a selectable slot race
     * @param humanSlot        the human's index among the map's human slots
     * @param speed            the game speed, {@code 0} (Slowest) through {@code 6} (Fastest)
     * @param mapCompatibility whether the map loads with the 1.16-era relaxed rules instead of the strict rules
     * @return the created {@link MeleeGameSetup} instance
     * @throws IOException              if the map cannot be read or the rules reject it
     * @throws IllegalArgumentException if a value is invalid for the map
     */
    public static @NotNull MeleeGameSetup loadUseMapSettings(@NotNull Path mapPath,
                                                             @NotNull String humanName, @NotNull String humanRace, int humanSlot,
                                                             int speed,
                                                             boolean mapCompatibility) throws IOException {
        MpqArchive mapArchive = openMapArchive(mapPath, mapCompatibility);
        MeleeGameSetup setup = new MeleeGameSetup(readScenario(mapPath, mapArchive),
                                                  humanName, parseRace(humanRace), humanSlot,
                                                  new int[0],
                                                  speed,
                                                  true,
                                                  0,
                                                  true,
                                                  mapCompatibility);

        setup.mapPath    = mapPath;
        setup.mapArchive = mapArchive;

        return setup;
    }

    /**
     * Creates a {@link MeleeGameSetup} instance for a single-player Use Map Settings game: the map fixes the computer
     * slots ({@code OWNR} {@code 5}), their races ({@code SIDE}; a selectable race {@code 5} becomes random, inferred) and
     * the forces ({@code FORC}); the human takes one of the human slots ({@code OWNR} {@code 6}), the other human slots are
     * closed.
     *
     * @param mapData        the map's scenario bytes
     * @param humanName      the local player name
     * @param humanRace      the human race byte, used when the slot's race is selectable
     * @param humanLobbySlot the index of the human among the map's human slots
     * @param speed          the game speed, {@code 0} (Slowest) through {@code 6} (Fastest)
     * @return the created {@link MeleeGameSetup} instance
     * @throws IOException              if the map data is not a valid scenario
     * @throws IllegalArgumentException if the lobby does not fit the map
     */
    public static @NotNull MeleeGameSetup createUseMapSettings(byte @NotNull [] mapData,
                                                               @NotNull String humanName, int humanRace, int humanLobbySlot,
                                                               int speed) throws IOException {
        return new MeleeGameSetup(mapData, humanName, humanRace, humanLobbySlot, new int[0], speed, true, 0, true, false);
    }

    /**
     * Creates a {@link MeleeGameSetup} instance for a campaign mission.
     *
     * @param mapData   the mission scenario ({@link CampaignMissions#readScenario})
     * @param humanName the local player name
     * @param mission   the campaign mission index
     * @param speed     the game speed, {@code 0} (Slowest) through {@code 6} (Fastest)
     * @param expansion whether the mission is played as Brood War (the expansion campaigns) rather than StarCraft
     * @return the created {@link MeleeGameSetup} instance
     * @throws IOException if the scenario is invalid or needs the expansion that is not selected
     */
    public static @NotNull MeleeGameSetup createCampaign(byte @NotNull [] mapData,
                                                         @NotNull String humanName,
                                                         int mission,
                                                         int speed, boolean expansion) throws IOException {
        ChkFile scenario = ChkFile.parseTolerant(mapData);
        byte[] version = scenario.getSection(ChkFile.VERSION_SECTION);
        int value = version == null || version.length < Short.BYTES ? 0 : LittleEndianUtils.readUnsigned16(version, 0);

        if (!expansion && (value & SCENARIO_VERSION_MASK) > ORIGINAL_SCENARIO_VERSION_MAXIMUM) {
            throw Exceptions.newIOException("Campaign mission {0} (VER {1}) requires the expansion.", mission, value);
        }

        return new MeleeGameSetup(mapData, humanName, ScrRaceIds.TERRAN, 0, new int[0], speed, true, mission, expansion, false);
    }

    /**
     * Creates a {@link MeleeGameSetup} instance for a network game: the lobby table and options come from the lobby
     * (every client builds the same table from the lobby commands), the host's name heads the header.
     *
     * @param mapData   the map's scenario bytes
     * @param hostName  the host's name (header host name)
     * @param table     the lobby table at game start
     * @param options   the resolution options
     * @param template  the game options of the lobby
     * @param speed     the game speed, {@code 0} (Slowest) through {@code 6} (Fastest)
     * @param expansion whether the game is played as Brood War
     * @return the created {@link MeleeGameSetup} instance
     * @throws IOException if the map data is not a valid scenario
     */
    public static @NotNull MeleeGameSetup createNetwork(byte @NotNull [] mapData,
                                                        @NotNull String hostName,
                                                        LobbyResolution.@NotNull Table table,
                                                        LobbyResolution.@NotNull Options options, @NotNull GameTemplate template,
                                                        int speed, boolean expansion) throws IOException {
        MeleeGameSetup setup = new MeleeGameSetup(mapData,
                                                  hostName, ScrRaceIds.TERRAN, 0,
                                                  new int[]{ScrRaceIds.TERRAN},
                                                  speed,
                                                  options.isUseMapSettings(),
                                                  -1,
                                                  expansion,
                                                  false);

        setup.networkTable   = table;
        setup.networkOptions = options;
        setup.template       = template;

        return setup;
    }

    /**
     * Creates a {@link MeleeGameSetup} instance from a saved header and scenario.
     *
     * @param mapData       the scenario built from the saved game
     * @param header        the saved game's replay header
     * @param network       whether the saved game is a network game ({@code .mlx})
     * @param lobbyTypes    the twelve lobby types of the game structure
     * @param lobbyRaces    the twelve lobby races of the game structure
     * @param classicLimits whether the saved game ran with the classic resource limits
     * @param mapPath       the map file the saved game names, or {@code null} for none
     * @return the created {@link MeleeGameSetup} instance
     * @throws IOException if the scenario is not valid
     */
    public static @NotNull MeleeGameSetup createSaved(byte @NotNull [] mapData, byte @NotNull [] header,
                                                      boolean network,
                                                      byte @NotNull [] lobbyTypes, byte @NotNull [] lobbyRaces,
                                                      boolean classicLimits,
                                                      @Nullable Path mapPath) throws IOException {
        MeleeGameSetup setup = new MeleeGameSetup(mapData, header);

        setup.classicLimits = classicLimits;
        setup.mapPath       = mapPath;

        if (network) {
            LobbyResolution.Table table = new LobbyResolution.Table();
            int[] slotRaces = new int[PlayerTable.PLAYER_SLOTS];

            for (int slot = 0; slot < PlayerTable.PLAYER_SLOTS; slot++) {
                PlayerRecord record = readSavedRecord(header, slot);

                table.playerId[slot]     = record.getPlayerId();
                table.type[slot]         = Byte.toUnsignedInt(lobbyTypes[slot]);
                table.race[slot]         = Byte.toUnsignedInt(lobbyRaces[slot]);
                table.team[slot]         = record.getTeam();
                table.name[slot]         = record.getName();
                table.templateType[slot] = Byte.toUnsignedInt(lobbyTypes[slot]);
                table.templateRace[slot] = Byte.toUnsignedInt(lobbyRaces[slot]);
                table.savedType[slot]    = Byte.toUnsignedInt(lobbyTypes[slot]);
                slotRaces[slot]          = Byte.toUnsignedInt(lobbyRaces[slot]);
            }

            table.tileset           = setup.map.getTileset();
            setup.resolvedSlotRaces = slotRaces;
            setup.networkTable      = table;
            setup.networkOptions    = new LobbyResolution.Options(true, setup.useMapSettings, 0, false, false);
        }

        return setup;
    }

    /**
     * Resolves the local player's chosen race before game initialization.
     *
     * @param random the global generator
     * @param race   the chosen race byte
     * @return the resolved race byte
     */
    public static int resolveLobbyRace(@NotNull LcgRandom random, int race) {
        int[] type = new int[PlayerTable.PLAYER_SLOTS];
        int[] races = new int[PlayerTable.PLAYER_SLOTS];

        type[0]  = TYPE_HUMAN;
        races[0] = race;
        random.setEnabled(1);

        try {
            LobbyResolution.resolveRandomRaces(random, type, races, new int[PlayerTable.PLAYER_SLOTS], false);
        } finally {
            random.setEnabled(0);
        }

        return races[0];
    }

    /**
     * Opens a map file as a map archive unless it is a raw scenario file ({@code .chk}).
     *
     * @param mapPath          the map file
     * @param mapCompatibility whether the archive opens in the map compatibility mode
     * @return the opened archive, or {@code null} for a raw scenario file
     * @throws IOException if the archive cannot be read
     */
    private static @Nullable MpqArchive openMapArchive(@NotNull Path mapPath, boolean mapCompatibility) throws IOException {
        String fileName = mapPath.getFileName().toString().toLowerCase(Locale.ROOT);

        return fileName.endsWith(RAW_SCENARIO_EXTENSION) ? null : MpqArchive.open(mapPath, mapCompatibility);
    }

    /**
     * Reads the scenario of a map file: the archive's {@code staredit\scenario.chk}, or the raw scenario file.
     *
     * @param mapPath    the map file
     * @param mapArchive the map file opened as an archive, or {@code null} for a raw scenario file
     * @return the scenario bytes
     * @throws IOException if the scenario cannot be read
     */
    private static byte @NotNull [] readScenario(@NotNull Path mapPath, @Nullable MpqArchive mapArchive) throws IOException {
        return mapArchive == null ? Files.readAllBytes(mapPath) : mapArchive.readFile(ChkFile.ARCHIVE_ENTRY);
    }

    /**
     * Reads a saved header's player record; the name keeps the record's bytes before their terminator.
     *
     * @param header the saved game's replay header
     * @param slot   the record slot
     * @return the player record
     */
    private static @NotNull PlayerRecord readSavedRecord(byte @NotNull [] header, int slot) {
        int offset = ReplayHeaderBuilder.PLAYERS_OFFSET + slot * ReplayHeaderBuilder.PLAYER_RECORD_SIZE;
        int nameOffset = offset + ReplayHeaderBuilder.PLAYER_NAME_OFFSET;
        ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        byte[] name = Arrays.copyOfRange(header, nameOffset, nameOffset + SAVED_PLAYER_NAME_SIZE);

        return new PlayerRecord(fields.getInt(offset + ReplayHeaderBuilder.PLAYER_ID_OFFSET),
                                Byte.toUnsignedInt(header[offset + ReplayHeaderBuilder.PLAYER_TYPE_OFFSET]),
                                Byte.toUnsignedInt(header[offset + ReplayHeaderBuilder.PLAYER_RACE_OFFSET]),
                                Byte.toUnsignedInt(header[offset + ReplayHeaderBuilder.PLAYER_TEAM_OFFSET]),
                                Arrays.copyOf(name, TerminatedStringUtils.getTextLength(name)));
    }

    private static void checkRace(int race) {
        if (race != ScrRaceIds.ZERG && race != ScrRaceIds.TERRAN && race != ScrRaceIds.PROTOSS && race != ScrRaceIds.RANDOM) {
            throw Exceptions.newIllegalArgumentException("Unsupported race byte: {0}", race);
        }
    }

    private static @NotNull PlayerRecord @NotNull [] clearUnplayable(@NotNull PlayerRecord @NotNull [] records) {
        PlayerRecord[] cleared = records.clone();

        for (int slot = PlayerTable.PLAYABLE_PLAYER_SLOTS; slot < PlayerTable.PLAYER_SLOTS; slot++) {
            cleared[slot] = new PlayerRecord(-1, 0, 0, 0, "");
        }

        return cleared;
    }

    /**
     * The custom colors left by game start ({@link #getResolvedCustomColors}).
     */
    private int @NotNull [] resolvedCustomColors;
    /**
     * The lobby color bytes left by game start ({@link #getResolvedLobbyColors}).
     */
    private final byte @NotNull [] resolvedLobbyColors;
    private int @NotNull [] resolvedCommandSlots;
    private int @Nullable [] resolvedSlotRaces;

    /**
     * The game type template the header records.
     */
    private @NotNull GameTemplate template;
    /**
     * The map file the game was loaded from, or {@code null} for scenario bytes without a file.
     */
    private @Nullable Path mapPath;
    /**
     * The archive of the map file a live game starts from, or {@code null} for none.
     */
    private @Nullable MpqArchive mapArchive;
    /**
     * Whether the lobby option selects the classic resource limits (unit capacity {@code 1700}) instead of the extended ones.
     */
    private boolean classicLimits;
    /**
     * A network game's lobby table, or {@code null} for single player.
     */
    private LobbyResolution.@Nullable Table networkTable;
    private LobbyResolution.@Nullable Options networkOptions;
    /**
     * The network game information's name, {@code null} until supplied.
     */
    private @Nullable String networkGameName;
    /**
     * The network game's turns per second.
     */
    private int networkTurnRate;
    /**
     * The speed override selector of the network game's name, {@code 0} for none.
     */
    private int speedOverrideIndex;
    private @Nullable BiFunction<String, Integer, String> nationNames;

    private final boolean expansion;
    private final boolean mapCompatibility;
    private final boolean useMapSettings;
    private final int campaignMission;
    private final byte @NotNull [] mapData;
    private final @NotNull ChkFile map;
    /**
     * Whether the map's triggers use extended deaths, which force the classic limits.
     */
    private final boolean extendedDeathsTriggers;

    private final @NotNull String humanName;
    private final int humanRace;
    private final int @NotNull [] computerRaces;
    private final int humanLobbySlot;
    private final int speed;

    /**
     * The replay header of a game loaded from a saved game ({@link #createSaved}), whose player records and colors the
     * resolution returns as they are, or {@code null} for a lobby.
     */
    private final byte @Nullable [] savedHeader;

    /**
     * Creates a {@link MeleeGameSetup} instance.
     *
     * @param mapData        the map's {@code staredit\scenario.chk} bytes, embedded unchanged in the replay
     * @param humanName      the local player name
     * @param humanRace      the human race byte ({@link #parseRace})
     * @param humanLobbySlot the index of the human among the map's start slots, {@code 0} for the first
     * @param computerRaces  the computer race bytes, in lobby order after the human
     * @param speed          the game speed, {@code 0} (Slowest) through {@code 6} (Fastest)
     * @throws IOException              if the map data is not a valid scenario
     * @throws IllegalArgumentException if the lobby does not fit the map
     */
    public MeleeGameSetup(byte @NotNull [] mapData,
                          @NotNull String humanName, int humanRace, int humanLobbySlot,
                          int @NotNull [] computerRaces,
                          int speed) throws IOException {
        this(mapData, humanName, humanRace, humanLobbySlot, computerRaces, speed, false, 0, true, false);
    }

    private MeleeGameSetup(byte @NotNull [] mapData, byte @NotNull [] header) throws IOException {
        this.resolvedCustomColors = new int[0];
        this.resolvedLobbyColors  = new byte[PlayerTable.PLAYER_SLOTS];
        this.resolvedCommandSlots = new int[0];
        this.resolvedSlotRaces    = null;

        this.template           = GameTemplate.parse(Arrays.copyOfRange(header, SAVED_TEMPLATE, SAVED_TEMPLATE + GameTemplate.SIZE));
        this.mapPath            = null;
        this.mapArchive         = null;
        this.classicLimits      = false;
        this.networkTable       = null;
        this.networkOptions     = null;
        this.networkGameName    = null;
        this.networkTurnRate    = 0;
        this.speedOverrideIndex = 0;
        this.nationNames        = null;

        this.expansion        = header[ReplayHeaderBuilder.ENGINE_OFFSET] != 0;
        this.mapCompatibility = false;
        this.useMapSettings   = this.template.isUseMapSettings();
        this.campaignMission  = LittleEndianUtils.readUnsigned16(header, ReplayHeaderBuilder.CAMPAIGN_MISSION_OFFSET);
        this.mapData          = mapData.clone();
        this.map              = ChkFile.parseTolerant(this.mapData);

        ChkValidatorUtils.validate(this.map, this.useMapSettings, this.expansion, false);

        this.extendedDeathsTriggers = false;

        int human = -1;

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS && human < 0; slot++) {
            if (readSavedRecord(header, slot).getType() == TYPE_HUMAN) {
                human = slot;
            }
        }

        this.humanName      = human < 0 ? "" : readSavedRecord(header, human).getName();
        this.humanRace      = human < 0 ? ScrRaceIds.TERRAN : readSavedRecord(header, human).getRace();
        this.computerRaces  = new int[0];
        this.humanLobbySlot = 0;
        this.speed          = Byte.toUnsignedInt(header[SAVED_SPEED]);

        this.savedHeader = header.clone();
    }

    private MeleeGameSetup(byte @NotNull [] mapData,
                           @NotNull String humanName, int humanRace, int humanLobbySlot,
                           int @NotNull [] computerRaces,
                           int speed,
                           boolean useMapSettings,
                           int campaignMission,
                           boolean expansion,
                           boolean mapCompatibility) throws IOException {
        this.resolvedCustomColors = new int[0];
        this.resolvedLobbyColors  = new byte[PlayerTable.PLAYER_SLOTS];
        this.resolvedCommandSlots = new int[0];
        this.resolvedSlotRaces    = null;

        this.template           = useMapSettings ? GameTemplate.USE_MAP_SETTINGS : GameTemplate.MELEE;
        this.mapPath            = null;
        this.mapArchive         = null;
        this.classicLimits      = false;
        this.networkTable       = null;
        this.networkOptions     = null;
        this.networkGameName    = null;
        this.networkTurnRate    = 0;
        this.speedOverrideIndex = 0;
        this.nationNames        = null;

        this.expansion        = expansion;
        this.mapCompatibility = mapCompatibility;
        this.useMapSettings   = useMapSettings;
        this.campaignMission  = Math.max(campaignMission, 0);
        this.mapData          = mapData.clone();
        this.map              = ChkFile.parseTolerant(this.mapData);

        ChkValidatorUtils.validate(this.map, useMapSettings, expansion, mapCompatibility);

        this.extendedDeathsTriggers = campaignMission <= 0 && ScrTriggerSystem.usesExtendedDeaths(this.map);

        this.humanName      = humanName;
        this.humanRace      = humanRace;
        this.computerRaces  = computerRaces.clone();
        this.humanLobbySlot = humanLobbySlot;
        this.speed          = speed;

        this.savedHeader = null;

        if (speed < 0 || speed > MAXIMUM_GAME_SPEED) {
            throw Exceptions.newIllegalArgumentException("Game speed must be 0 (Slowest) through 6 (Fastest).");
        }

        checkRace(humanRace);

        boolean network = campaignMission < 0;

        if (network) {
            return;
        }

        if (useMapSettings) {
            int humanSlots = this.getSlotsOfType(LobbyResolution.TYPE_OPEN).size();

            if (humanLobbySlot < 0 || humanLobbySlot >= humanSlots) {
                throw Exceptions.newIllegalArgumentException("The map has {0} human slots; the human slot must be 0 through {1}.", humanSlots,
                                                             (humanSlots - 1));
            }

            return;
        }

        int startSlots = this.getStartSlots().size();

        if (computerRaces.length == 0) {
            throw Exceptions.newIllegalArgumentException("A melee game needs at least one computer opponent.");
        }

        if (1 + computerRaces.length > startSlots) {
            throw Exceptions.newIllegalArgumentException("The map has {0} start slots; {1} players requested.", startSlots,
                                                         (1 + computerRaces.length));
        }

        if (humanLobbySlot < 0 || humanLobbySlot >= startSlots) {
            throw Exceptions.newIllegalArgumentException("The human slot must be 0 through {0}.", (startSlots - 1));
        }

        for (int race : computerRaces) {
            checkRace(race);
        }
    }

    /**
     * @return a copy of the replay header of a game loaded from a saved game, or {@code null} for a lobby
     */
    public byte @Nullable [] getSavedHeader() {
        return this.savedHeader == null ? null : this.savedHeader.clone();
    }

    /**
     * @return the network game's name ({@link #setNetworkGameInformation}), {@code null} when none was supplied
     */
    public @Nullable String getNetworkGameName() {
        return this.networkGameName;
    }

    /**
     * @return the network game's turns per second ({@link #setNetworkGameInformation}), {@code 0} when none was supplied
     */
    public int getNetworkTurnRate() {
        return this.networkTurnRate;
    }

    /**
     * @return the speed override selector of the network game's name ({@link GameSpeed#parseOverrideIndex}), {@code 0} for none or when no name
     * was supplied
     */
    public int getSpeedOverrideIndex() {
        return this.speedOverrideIndex;
    }

    /**
     * @return {@code true} if the game has a network lobby, {@code false} otherwise
     */
    public boolean isNetwork() {
        return this.networkTable != null;
    }

    /**
     * @return the game sub type the header records: the template's subtype id ({@link GameTemplate#getSubTypeId})
     */
    public int getSubType() {
        return this.template.getSubTypeId();
    }

    /**
     * @return a copy of the lobby slots' races of the last network resolution, or {@code null} before one
     */
    public int @Nullable [] getResolvedSlotRaces() {
        return this.resolvedSlotRaces == null ? null : this.resolvedSlotRaces.clone();
    }

    /**
     * Reads the lobby slot of a network player: the slot of the record the player holds among the eight player records of
     * the lobby table ({@link PlayerSlots#getSlot}).
     *
     * @param playerId the network id
     * @return the lobby record index {@code 0..7} of the player, or {@link PlayerSlots#NO_SLOT} outside a network game or for an observer
     */
    public int getLobbySlot(int playerId) {
        LobbyResolution.Table table = this.networkTable;

        if (table == null) {
            return PlayerSlots.NO_SLOT;
        }

        PlayerRecord[] records = new PlayerRecord[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        PlayerSlots slots = new PlayerSlots();

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            records[slot] = new PlayerRecord(table.playerId[slot], table.type[slot], table.race[slot], table.team[slot], table.name[slot]);
        }

        slots.update(records, PlayerTable.PLAYABLE_PLAYER_SLOTS);

        return slots.getSlot(playerId);
    }

    /**
     * Reads the observer slot of a network player: the slot of the observer record the player holds in the lobby table, a later
     * record winning.
     *
     * @param playerId the network id
     * @return the observer slot from {@link PlayerTable#FIRST_OBSERVER_SLOT}, or {@link PlayerSlots#NO_SLOT} outside a network game
     * or for a player who holds no observer record
     */
    public int getObserverSlot(int playerId) {
        LobbyResolution.Table table = this.networkTable;
        int slot = PlayerSlots.NO_SLOT;

        if (table == null || playerId < 0) {
            return slot;
        }

        for (int observer = 0; observer < PlayerTable.OBSERVER_SLOTS; observer++) {
            if (table.observerId[observer] == playerId) {
                slot = PlayerTable.FIRST_OBSERVER_SLOT + observer;
            }
        }

        return slot;
    }

    /**
     * @param observerSlot the observer slot ({@link #getObserverSlot})
     * @return the name of the lobby table's observer record of the slot
     * @throws IllegalStateException outside a network game
     */
    public @NotNull String getObserverName(int observerSlot) {
        LobbyResolution.Table table = this.networkTable;

        if (table == null) {
            throw Exceptions.newIllegalStateException("Only a network game has observers.");
        }

        return table.observerName[observerSlot - PlayerTable.FIRST_OBSERVER_SLOT];
    }

    /**
     * @return a copy of a network game's lobby types before normalization, or {@code null} in single player
     */
    public int @Nullable [] getLobbyTypes() {
        LobbyResolution.Table table = this.networkTable;

        return table == null ? null : table.type.clone();
    }

    /**
     * @return per final slot the slot whose units its commands act on; a team game's humans command their leader, otherwise each slot commands itself
     */
    public int @NotNull [] getResolvedCommandSlots() {
        if (this.resolvedCommandSlots.length == 0) {
            int[] identity = new int[PlayerTable.PLAYER_SLOTS];

            for (int slot = 0; slot < identity.length; slot++) {
                identity[slot] = slot;
            }

            return identity;
        }

        return this.resolvedCommandSlots.clone();
    }

    /**
     * Returns the custom colors of the final slots from the last {@link #resolve} (the replay's {@code CCLR}): a network game that keeps its
     * custom colors ({@link LobbyResolution#keepsCustomColors}) the slots' network or map colors ({@link LobbyResolution.Result#getCustomColors}),
     * every other game none.
     *
     * @return the packed {@code 0xRRGGBB} custom colors, {@code -1} where none; empty before the first resolution
     */
    public int @NotNull [] getResolvedCustomColors() {
        return this.resolvedCustomColors.clone();
    }

    /**
     * @return a copy of the twelve lobby color bytes, zero before the first resolution
     */
    public byte @NotNull [] getResolvedLobbyColors() {
        return this.resolvedLobbyColors.clone();
    }

    /**
     * @return the map file the game was loaded from, or {@code null} for scenario bytes without a file
     */
    public @Nullable Path getMapPath() {
        return this.mapPath;
    }

    /**
     * Returns the archive of the map file the game starts from, which holds this setup's scenario: the map a single-player game loaded
     * ({@link #load}, {@link #loadUseMapSettings}) or the one a network game set ({@link #setMapArchive}).
     *
     * @return the archive, or {@code null} for a raw scenario file, a campaign mission, a saved game or scenario bytes without an archive
     */
    public @Nullable MpqArchive getMapArchive() {
        return this.mapArchive;
    }

    /**
     * @return {@code true} if the game uses the classic resource limits, {@code false} otherwise
     */
    public boolean isClassicLimits() {
        return this.classicLimits || this.extendedDeathsTriggers;
    }

    /**
     * @return the game options the header records
     */
    public @NotNull GameTemplate getTemplate() {
        return this.template;
    }

    /**
     * @return the game type the header records: the template's type id
     */
    public int getGameType() {
        return this.template.getTypeId();
    }

    /**
     * @return a copy of the eleven option bytes of the game type's template
     */
    public int @NotNull [] getTemplateOptions() {
        return this.template.getOptions();
    }

    /**
     * @return {@code true} if the game is played as Brood War, {@code false} otherwise
     */
    public boolean isExpansion() {
        return this.expansion;
    }

    /**
     * @return {@code true} if the map was loaded with the 1.16-era relaxed rules, {@code false} otherwise
     */
    public boolean isMapCompatibility() {
        return this.mapCompatibility;
    }

    /**
     * @return the campaign mission index, {@code 0} outside campaigns
     */
    public int getCampaignMission() {
        return this.campaignMission;
    }

    /**
     * @return a copy of the scenario bytes
     */
    public byte @NotNull [] getMapData() {
        return this.mapData.clone();
    }

    /**
     * @return the parsed scenario
     */
    public @NotNull ChkFile getMap() {
        return this.map;
    }

    /**
     * @return the local player name
     */
    public @NotNull String getHumanName() {
        return this.humanName;
    }

    /**
     * @return the game speed, {@code 0} through {@code 6}
     */
    public int getSpeed() {
        return this.speed;
    }

    /**
     * @return in ascending order the map's start slots whose {@code OWNR} type is human or computer
     */
    public @NotNull List<Integer> getStartSlots() {
        byte[] owners = this.map.getSection(ChkFile.OWNER_SECTION);
        List<Integer> slots = new ArrayList<>();

        for (int slot = 0; owners != null && slot < PlayerTable.PLAYABLE_PLAYER_SLOTS && slot < owners.length; slot++) {
            int type = Byte.toUnsignedInt(owners[slot]);

            if (type == LobbyResolution.TYPE_COMPUTER || type == LobbyResolution.TYPE_OPEN) {
                slots.add(slot);
            }
        }

        return slots;
    }

    private @NotNull List<Integer> getSlotsOfType(int ownerType) {
        byte[] owners = this.map.getSection(ChkFile.OWNER_SECTION);
        List<Integer> slots = new ArrayList<>();

        for (int slot = 0; owners != null && slot < PlayerTable.PLAYABLE_PLAYER_SLOTS && slot < owners.length; slot++) {
            if (Byte.toUnsignedInt(owners[slot]) == ownerType) {
                slots.add(slot);
            }
        }

        return slots;
    }

    /**
     * @return the eight per-player melee unit flags, all {@code false} for a melee setup
     */
    public boolean @NotNull [] getUseMapSettingsMeleeUnitPlayers() {
        boolean[] melee = new boolean[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        byte[] sides = this.map.getSection(ChkFile.RACE_SECTION);

        for (int slot = 0; this.useMapSettings && sides != null && slot < melee.length && slot < sides.length; slot++) {
            melee[slot] = Byte.toUnsignedInt(sides[slot]) == ScrRaceIds.USER_SELECTABLE;
        }

        return melee;
    }

    /**
     * @return {@code true} if the game uses map settings, {@code false} otherwise
     */
    public boolean isUseMapSettings() {
        return this.useMapSettings;
    }

    /**
     * @return the scenario name from {@code SPRP} that the header stores, or an empty string without the section
     */
    public @NotNull String getScenarioName() {
        byte[] properties = this.map.getSection(ChkFile.SCENARIO_PROPERTIES_SECTION);

        if (properties == null || properties.length < Short.BYTES) {
            return "";
        }

        int index = LittleEndianUtils.readUnsigned16(properties, 0);
        List<String> strings = this.map.getStrings();

        return index >= 1 && index <= strings.size() ? strings.get(index - 1) : "";
    }

    /**
     * Sets the network game's name and turns per second, and the speed override selector parsed from the name
     * ({@link GameSpeed#parseOverrideIndex}): every player of the game parses the same name.
     *
     * @param gameName the game's full name
     * @param turnRate the turns per second
     */
    public void setNetworkGameInformation(@NotNull String gameName, int turnRate) {
        this.networkGameName    = gameName;
        this.networkTurnRate    = turnRate;
        this.speedOverrideIndex = GameSpeed.parseOverrideIndex(gameName);
    }

    /**
     * Sets the resource limits of the game.
     *
     * @param classicLimits whether the classic limits apply
     */
    public void setClassicLimits(boolean classicLimits) {
        this.classicLimits = classicLimits;
    }

    /**
     * Sets the archive of the map file the game starts from, which the game start offers to the mod's extensions
     * ({@link org.fogofwarlabs.sc4j.scr.game.state.GameSetup#getMapArchive()}).
     *
     * @param mapArchive the opened map archive, which holds this setup's scenario; shared, not copied
     */
    public void setMapArchive(@NotNull MpqArchive mapArchive) {
        this.mapArchive = mapArchive;
    }

    /**
     * Sets the game type template of a single-player melee setup.
     *
     * @param template the game options
     * @throws IllegalArgumentException if the template is a Use Map Settings, team or Top vs Bottom one, or the setup uses the map settings
     */
    public void setTemplate(@NotNull GameTemplate template) {
        if (template.isUseMapSettings() || template.hasTeams() || template.isTopVsBottom()) {
            throw Exceptions.newIllegalArgumentException("A single-player melee setup takes no template of type {0}.", template.getTypeId());
        }

        if (this.useMapSettings) {
            throw Exceptions.newIllegalArgumentException("A Use Map Settings setup keeps its game type.");
        }

        this.template = template;
    }

    /**
     * Sets the localized nation names of computer players ({@code strNATION_<RACE>_<n>}).
     *
     * @param nationNames the name of a race ({@code ZERG}, {@code TERRAN}, {@code PROTOSS}) and index, {@code null} when
     *                    unknown; {@code null} keeps the records' names
     */
    public void setNationNames(@Nullable BiFunction<String, Integer, String> nationNames) {
        this.nationNames = nationNames;
    }


    /**
     * Resolves the lobby for a seed.
     *
     * @param seed   the game seed
     * @param colors receives the eight color indices in final slot order
     * @return the twelve final player records
     */
    public @NotNull PlayerRecord @NotNull [] resolve(int seed, int @NotNull [] colors) {
        return this.nameComputers(this.resolveRecords(seed, colors), colors);
    }

    private @NotNull PlayerRecord @NotNull [] nameComputers(@NotNull PlayerRecord @NotNull [] records, int @NotNull [] colors) {
        BiFunction<String, Integer, String> names = this.nationNames;

        if (names == null) {
            return records;
        }

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS && slot < records.length; slot++) {
            PlayerRecord record = records[slot];
            int type = record.getType();

            if (type != PlayerTypes.COMPUTER && type != PlayerTypes.RESCUE_PASSIVE && type != PlayerTypes.RESCUE_ACTIVE &&
                type != PlayerTypes.NEUTRAL) {
                continue;
            }

            String race = record.getRace() == ScrRaceIds.ZERG ? "ZERG" : record.getRace() == ScrRaceIds.PROTOSS ? "PROTOSS" : "TERRAN";
            String name = names.apply(race, colors[slot]);

            if (name == null) {
                continue;
            }

            // The record keeps the whole name; the header record and the name section cut it to their own buffers.
            records[slot] = new PlayerRecord(record.getPlayerId(), type, record.getRace(), record.getTeam(), name);
        }

        return records;
    }

    /**
     * Resolves the final player records and colors of a seed: a saved game returns its header's records, command slots and
     * colors as they are; a network game resolves its lobby table; a single-player game builds the lobby table from the
     * map's {@code OWNR}, {@code SIDE} and {@code FORC} sections and resolves it.
     *
     * @param seed   the game seed
     * @param colors receives the eight color indices in final slot order
     * @return the twelve final player records
     * @throws IllegalArgumentException if {@code colors} does not hold eight entries
     * @throws IllegalStateException    if a single-player game's map has no {@code OWNR} section
     */
    private @NotNull PlayerRecord @NotNull [] resolveRecords(int seed, int @NotNull [] colors) {
        if (colors.length != PlayerTable.PLAYABLE_PLAYER_SLOTS) {
            throw Exceptions.newIllegalArgumentException("Eight colors are resolved.");
        }

        Arrays.fill(this.resolvedLobbyColors, (byte) LobbyResolution.COLOR_NONE);

        if (this.savedHeader != null) {
            ByteBuffer fields = ByteBuffer.wrap(this.savedHeader).order(ByteOrder.LITTLE_ENDIAN);
            PlayerRecord[] records = new PlayerRecord[PlayerTable.PLAYER_SLOTS];

            this.resolvedCommandSlots = new int[PlayerTable.PLAYER_SLOTS];

            for (int slot = 0; slot < PlayerTable.PLAYER_SLOTS; slot++) {
                records[slot]                   = readSavedRecord(this.savedHeader, slot);
                this.resolvedCommandSlots[slot] = fields.getInt(ReplayHeaderBuilder.PLAYERS_OFFSET + slot * ReplayHeaderBuilder.PLAYER_RECORD_SIZE +
                                                                SAVED_PLAYER_SLOT);
            }

            for (int slot = 0; slot < colors.length; slot++) {
                colors[slot] = fields.getInt(ReplayHeaderBuilder.COLORS_OFFSET + slot * Integer.BYTES);
            }

            return records;
        }

        if (this.networkTable != null && this.networkOptions != null) {
            LobbyResolution.Result result = LobbyResolution.resolve(this.networkTable, this.networkOptions, seed);

            System.arraycopy(result.getColors(), 0, colors, 0, PlayerTable.PLAYABLE_PLAYER_SLOTS);

            boolean customColors = LobbyResolution.keepsCustomColors(true, this.template.getTypeId(), this.networkTable.customColorFlag);
            int[] resolvedColors = result.getCustomColors();

            if (customColors) {
                for (int slot = 0; slot < result.getLobbyColors().length; slot++) {
                    this.resolvedLobbyColors[slot] = (byte) result.getLobbyColors()[slot];
                }
            } else {
                Arrays.fill(resolvedColors, -1);
            }

            this.resolvedCustomColors = resolvedColors;
            this.resolvedCommandSlots = result.getCommandSlots();
            this.resolvedSlotRaces    = result.getSlotRaces();

            return clearUnplayable(result.getRecords());
        }

        byte[] owners = this.map.requireSection(ChkFile.OWNER_SECTION);
        byte[] sides = this.map.getSection(ChkFile.RACE_SECTION);
        int[] templateType = new int[PlayerTable.PLAYER_SLOTS];
        int[] templateRace = new int[PlayerTable.PLAYER_SLOTS];

        for (int slot = 0; slot < PlayerTable.PLAYER_SLOTS; slot++) {
            templateType[slot] = slot < owners.length ? Byte.toUnsignedInt(owners[slot]) : 0;
            templateRace[slot] = sides != null && slot < sides.length ? Byte.toUnsignedInt(sides[slot]) : 0;
        }

        int[] playerId = new int[PlayerTable.PLAYER_SLOTS];
        int[] type = new int[PlayerTable.PLAYER_SLOTS];
        int[] race = new int[PlayerTable.PLAYER_SLOTS];
        int[] team = new int[PlayerTable.PLAYER_SLOTS];
        String[] name = new String[PlayerTable.PLAYER_SLOTS];

        Arrays.fill(playerId, -1);
        Arrays.fill(name, "");

        List<Integer> startSlots = this.getStartSlots();

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            type[slot] = templateType[slot];
            race[slot] = templateRace[slot];
        }

        int[] forces = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];
        byte[] forceData = this.map.getSection(ChkFile.FORCE_SECTION);

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            forces[slot] = forceData != null && forceData.length >= PlayerTable.PLAYABLE_PLAYER_SLOTS ? Byte.toUnsignedInt(forceData[slot]) : 0;
        }

        if (this.useMapSettings) {
            for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                team[slot] = forces[slot] + 1;
            }

            for (int slot : this.getSlotsOfType(LobbyResolution.TYPE_COMPUTER)) {
                type[slot] = TYPE_COMPUTER;
                race[slot] = templateRace[slot] == ScrRaceIds.USER_SELECTABLE ? ScrRaceIds.RANDOM : templateRace[slot];
                team[slot] = forces[slot] + 1;
                name[slot] = COMPUTER_NAME;
            }

            List<Integer> humanSlots = this.getSlotsOfType(LobbyResolution.TYPE_OPEN);

            for (int index = 0; index < humanSlots.size(); index++) {
                int slot = humanSlots.get(index);

                if (index == this.humanLobbySlot) {
                    playerId[slot] = 0;
                    type[slot]     = TYPE_HUMAN;
                    race[slot]     = templateRace[slot] == ScrRaceIds.USER_SELECTABLE ? this.humanRace : templateRace[slot];
                    team[slot]     = forces[slot] + 1;
                    name[slot]     = this.humanName;
                } else {
                    type[slot] = LobbyResolution.TYPE_OPEN;
                }
            }
        }

        // The single-player melee lobby shows every start slot: the human, the chosen computers after it, the rest closed.
        int computer = 0;

        for (int index = 0; !this.useMapSettings && index < startSlots.size(); index++) {
            int slot = startSlots.get(index);

            if (index == this.humanLobbySlot) {
                playerId[slot] = 0;
                type[slot]     = TYPE_HUMAN;
                race[slot]     = this.humanRace;
                team[slot]     = 1;
                name[slot]     = this.humanName;
            } else if (computer < this.computerRaces.length) {
                type[slot] = TYPE_COMPUTER;
                race[slot] = this.computerRaces[computer++];
                team[slot] = 1;
                name[slot] = COMPUTER_NAME;
            } else {
                type[slot] = TYPE_CLOSED;
            }
        }

        LobbyResolution.Table table = new LobbyResolution.Table();

        for (int slot = 0; slot < PlayerTable.PLAYER_SLOTS; slot++) {
            table.playerId[slot]     = playerId[slot];
            table.type[slot]         = type[slot];
            table.race[slot]         = race[slot];
            table.team[slot]         = team[slot];
            table.name[slot]         = name[slot];
            table.templateType[slot] = templateType[slot];
            table.templateRace[slot] = templateRace[slot];
            table.savedType[slot]    = templateType[slot];
        }

        boolean[] selectable = this.getUseMapSettingsMeleeUnitPlayers();

        System.arraycopy(selectable, 0, table.selectableRace, 0, PlayerTable.PLAYABLE_PLAYER_SLOTS);

        for (int force = 0; force < PlayerTable.FORCE_COUNT; force++) {
            table.forceFlags[force] = forceData != null && forceData.length >= FORCE_DATA_SIZE
                                      ? Byte.toUnsignedInt(forceData[FORCE_FLAGS_OFFSET + force])
                                      : 0;
        }

        table.tileset = this.map.getTileset();

        LobbyResolution.Result result = LobbyResolution.resolve(
                table,
                new LobbyResolution.Options(false, this.useMapSettings, 0,
                                            this.template.getOption(GameTemplate.START_POSITIONS) != 0,
                                            this.template.getOption(GameTemplate.TOURNAMENT) != 0),
                seed
        );

        System.arraycopy(result.getColors(), 0, colors, 0, PlayerTable.PLAYABLE_PLAYER_SLOTS);

        this.resolvedCustomColors = new int[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        Arrays.fill(this.resolvedCustomColors, -1);

        return clearUnplayable(result.getRecords());
    }
}
