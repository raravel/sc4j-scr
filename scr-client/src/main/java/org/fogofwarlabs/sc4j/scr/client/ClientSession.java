package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.io.AtomicFileUtils;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.base.text.TerminatedStringUtils;
import org.fogofwarlabs.sc4j.engine.client.audio.PlayingSound;
import org.fogofwarlabs.sc4j.engine.client.game.CommandPoint;
import org.fogofwarlabs.sc4j.engine.client.game.GameSession;
import org.fogofwarlabs.sc4j.engine.client.game.GameSessionFactory;
import org.fogofwarlabs.sc4j.engine.client.game.SimulationWatchdog;
import org.fogofwarlabs.sc4j.engine.client.host.FrameTiming;
import org.fogofwarlabs.sc4j.engine.command.CommandSink;
import org.fogofwarlabs.sc4j.engine.controller.PlayerController;
import org.fogofwarlabs.sc4j.engine.lockstep.LockstepTransport;
import org.fogofwarlabs.sc4j.scr.client.audio.ClientAudio;
import org.fogofwarlabs.sc4j.scr.client.audio.ClientSoundPlayback;
import org.fogofwarlabs.sc4j.scr.client.glue.lobby.FileBrowserScreen;
import org.fogofwarlabs.sc4j.scr.client.glue.lobby.GameTemplates;
import org.fogofwarlabs.sc4j.scr.client.glue.popup.GlueSavePopup;
import org.fogofwarlabs.sc4j.scr.client.ui.command.InterfaceSelection;
import org.fogofwarlabs.sc4j.scr.client.ui.console.MessageAreaUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.console.UiClockUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.text.ForceNamesUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.text.GluText;
import org.fogofwarlabs.sc4j.scr.client.world.ClientCamera;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.client.world.ScrPresentation;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.FileNameUtils;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.casc.GameLocaleUtils;
import org.fogofwarlabs.sc4j.scr.data.format.casc.SoundPathResolver;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkUnit;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqCryptoUtils;
import org.fogofwarlabs.sc4j.scr.data.format.replay.*;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.format.text.LegacyTextUtils;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.player.*;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.presentation.MusicRotation;
import org.fogofwarlabs.sc4j.scr.game.presentation.PaletteAnimation;
import org.fogofwarlabs.sc4j.scr.game.presentation.StartScreenUtils;
import org.fogofwarlabs.sc4j.scr.game.replay.*;
import org.fogofwarlabs.sc4j.scr.game.save.*;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.LocalCommandSink;
import org.fogofwarlabs.sc4j.scr.game.session.PauseNotice;
import org.fogofwarlabs.sc4j.scr.game.session.setup.CampaignMissions;
import org.fogofwarlabs.sc4j.scr.game.session.setup.GameTemplate;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.session.setup.SavedGamePlayers;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.state.GameEnd;
import org.fogofwarlabs.sc4j.scr.game.state.GameLoader;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.NativeCodeConstants;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalInterfaceState;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalView;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.visibility.LocalVision;
import org.fogofwarlabs.sc4j.scr.net.lan.LanClient;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderPeer;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.fogofwarlabs.sc4j.scr.render.OverlayPass;
import org.fogofwarlabs.sc4j.scr.render.RenderAssets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Owns replay assets, the common runtime, camera and separate map inspection selection. Map records never stand in for initialized game units.
 */
public final class ClientSession implements AutoCloseable {
    /**
     * The locales with their own unit voice dub.
     */
    private static final @NotNull Set<String> DUBBED_LOCALES = Set.of("enUS", "deDE", "esES", "frFR", "itIT");

    /**
     * The locale of the original unit voices.
     */
    private static final @NotNull String ORIGINAL_VOICE_LOCALE = "enUS";

    /**
     * The network text table the localized computer nation names are read from.
     */
    private static final @NotNull String NETWORK_TEXT_FILE = "network.json";

    /**
     * The network text key prefix of a computer nation name, followed by the race and the name index.
     */
    private static final @NotNull String NATION_NAME_KEY_PREFIX = "strNATION_";

    /**
     * The separator between the race and the name index of a computer nation name key.
     */
    private static final @NotNull String NATION_NAME_KEY_SEPARATOR = "_";

    /**
     * The network text key of the received whisper format.
     */
    private static final @NotNull String WHISPER_FROM_KEY = "strWHISPER_FROM";

    /**
     * The placeholder of the recipient name in the chat label format.
     */
    private static final @NotNull String CHAT_LABEL_PLACEHOLDER = "%s";

    /**
     * The separator between the campaign directory and a map file name.
     */
    private static final @NotNull String CAMPAIGN_PATH_SEPARATOR = "\\";

    /**
     * The line format of a campaign mission listing: index, launch name and directory.
     */
    private static final @NotNull String MISSION_LINE_FORMAT = "%2d  %-16s %s";

    /**
     * The runtime phase reported before the runtime exists.
     */
    private static final @NotNull String CONSTRUCTION_PHASE = "construction";

    /**
     * The ordinary chat message kind byte.
     */
    private static final int CHAT_KIND = 0;

    /**
     * The whisper message kind byte.
     */
    private static final int WHISPER_KIND = 1;

    /**
     * All sixteen chat recipient bits.
     */
    private static final int CHAT_ALL_RECIPIENTS_MASK = 0xFFFF;

    /**
     * The chat mask bit selecting observer recipients.
     */
    private static final int CHAT_OBSERVER_RECIPIENT_MASK = 0x8000;

    /**
     * The player identifier nibble used for chat recipient bits.
     */
    private static final int CHAT_PLAYER_IDENTIFIER_MASK = 0xF;

    /**
     * The two prefix bytes preceding network chat text.
     */
    private static final int CHAT_TEXT_OFFSET = 2;

    /**
     * The prefix plus one zero terminator in a chat packet.
     */
    private static final int CHAT_PACKET_OVERHEAD = CHAT_TEXT_OFFSET + 1;

    /**
     * The two little-endian WORD coordinates in a minimap ping.
     */
    private static final int MINIMAP_PING_PAYLOAD_BYTES = 4;

    /**
     * The payload offset of the vertical WORD coordinate in a minimap ping.
     */
    private static final int MINIMAP_PING_VERTICAL_OFFSET = Short.BYTES;

    /**
     * The exclusive ordinary player recipient identifier limit.
     */
    private static final int CHAT_PLAYER_RECIPIENT_LIMIT = 15;

    /**
     * The broadcast target passed to the provider for chat.
     */
    private static final int CHAT_BROADCAST_RECIPIENT = -2;

    /**
     * The receive text byte limit, including its terminator.
     */
    private static final int CHAT_RECEIVE_LIMIT = 0x50;

    /**
     * The script, movement and local animation invocation budget.
     */
    private static final int INSTRUCTION_LIMIT = 100000;

    /**
     * The inspection pointer padding in screen pixels.
     */
    private static final float INSPECTION_PADDING_PIXELS = 4.0f;

    /**
     * The offset subtracted from the host clock at interface startup.
     */
    private static final int STALL_STAMP_OFFSET_MILLISECONDS = 120000;

    /**
     * The minimum host time between network save requests.
     */
    private static final long MULTIPLAYER_SAVE_INTERVAL_MILLISECONDS = 120000;

    /**
     * The host tick of no save sent yet.
     */
    private static final long NO_SAVE_TICK = Long.MIN_VALUE;

    /**
     * The milliseconds of a second, for the {@code time(0)} DWORDs of the saved game commands.
     */
    private static final long MILLISECONDS_PER_SECOND = 1000L;

    /**
     * The speed configuration mode byte of a playing replay.
     */
    private static final int PLAYING_TIMING_MODE = 0;

    /**
     * The speed configuration mode byte of a paused replay.
     */
    private static final int PAUSED_TIMING_MODE = 1;

    /**
     * The speed configuration factor a replay starts with.
     */
    private static final int START_TIMING_FACTOR = 1;

    /**
     * The unsigned 16-bit shared vision mask bits.
     */
    private static final int SHARED_VISION_MASK = 0xFFFF;

    private static final @NotNull Logger LOGGER = Logger.of(ClientSession.class);

    /**
     * Executes the Save Game and Load Game commands of the live session's turns.
     */
    private final class SavedGameCommands implements LiveGame.SavedGameHandler {
        /**
         * Writes the running game to a saved game on every machine executing the Save Game command.
         *
         * @param time the command's time DWORD, {@code time(0)} of the issuing machine
         * @param name the command's name bytes without terminator, as received: the file name without extension
         */
        @Override
        public void saveGame(int time, byte @NotNull [] name) {
            ClientSession.this.writeSavedGame(name, time);
        }

        /**
         * Ends the running single-player game and starts the saved game.
         *
         * @param time the command's time DWORD
         * @param name the command's name bytes without terminator, as received: the file name without extension
         */
        @Override
        public void loadGame(int time, byte @NotNull [] name) {
            Path directory = ClientSession.this.saveDirectory;

            if (directory == null || !ClientSession.this.isValidSavedGameName(name)) {
                return;
            }

            String fileName = FileNameUtils.readFileName(name);

            if (fileName == null) {
                return;
            }

            Path file = directory.resolve(fileName + ClientSession.this.getSaveGameKind().getExtension());

            if (Files.isRegularFile(file)) {
                ClientSession.this.requestedLoad = file;
            }
        }
    }

    /**
     * Lists the built-in campaign missions for {@code --campaign list}.
     *
     * @param gameDirectory the game installation directory
     * @return one line per mission: index, launch name and directory
     * @throws IOException if the mission tables cannot be read
     */
    public static @NotNull List<String> listCampaignMissions(@NotNull Path gameDirectory) throws IOException {
        try (CascStorage storage = CascStorage.open(gameDirectory)) {
            CampaignMissions missions = CampaignMissions.load(storage);
            List<String> lines = new ArrayList<>();

            for (int mission = 1; mission < CampaignMissions.MISSION_COUNT; mission++) {
                if (!missions.getDirectory(mission).isEmpty()) {
                    lines.add(String.format(Locale.ROOT, MISSION_LINE_FORMAT, mission, missions.getName(mission), missions.getDirectory(mission)));
                }
            }

            return lines;
        }
    }


    /**
     * Creates a {@link ClientSession} instance for a replay and its embedded map from a local game installation.
     *
     * @param gameDirectory the game installation directory
     * @param replayPath    the local replay file
     * @return the created {@link ClientSession} instance
     * @throws IOException if the assets, replay, or embedded map cannot be loaded
     */
    public static @NotNull ClientSession load(@NotNull Path gameDirectory, @NotNull Path replayPath) throws IOException {
        return load(gameDirectory, replayPath, null);
    }

    /**
     * Creates a {@link ClientSession} instance for a replay and its embedded map in a game locale.
     *
     * @param gameDirectory the game installation directory
     * @param replayPath    the local replay file
     * @param locale        the requested locale such as {@code koKR}, or {@code null} for the installed locale ({@link GameLocaleUtils#select})
     * @return the created {@link ClientSession} instance
     * @throws IOException if the assets, replay, or embedded map cannot be loaded
     */
    public static @NotNull ClientSession load(@NotNull Path gameDirectory, @NotNull Path replayPath, @Nullable String locale) throws IOException {
        CascStorage storage = CascStorage.open(gameDirectory);

        try {
            ClientSession session = load(gameDirectory, storage, GameData.load(storage), replayPath, locale);

            session.ownsData = true;

            return session;
        } catch (IOException | RuntimeException | Error exception) {
            storage.close();

            throw exception;
        }
    }

    /**
     * Creates a {@link ClientSession} instance on borrowed storage and game data.
     * The session does not close them; each game copies the data tables it writes.
     *
     * @param gameDirectory the game installation directory
     * @param storage       the client's storage
     * @param gameData      the client's game data, loaded from {@code storage}
     * @param replayPath    the local replay file
     * @param locale        the requested locale such as {@code koKR}, or {@code null} for the installed locale
     * @return the created {@link ClientSession} instance
     * @throws IOException if the replay or its embedded map cannot be loaded
     */
    public static @NotNull ClientSession load(@NotNull Path gameDirectory,
                                              @NotNull CascStorage storage, @NotNull GameData gameData,
                                              @NotNull Path replayPath,
                                              @Nullable String locale) throws IOException {
        ReplayFile replay = ReplayFile.parse(Files.readAllBytes(replayPath));
        ClientSession session = new ClientSession(storage, gameData, replay, replayPath.toAbsolutePath().normalize());

        session.applyLocale(locale, gameDirectory);

        int mission = replay.getHeader().getCampaignMission();

        if (mission != 0) {
            String directory = CampaignMissions.load(storage).getDirectory(mission);

            session.campaignDirectory = directory.isEmpty() ? null : directory;
        }

        return session;
    }

    /**
     * Creates a {@link ClientSession} instance for a single-player melee game in a game locale through the common replay runtime.
     * The caller sizes the viewport and calls {@link #startReplay()}; executed commands can be saved as a replay.
     * The startup seed is read from the clock after localized computer names are prepared.
     *
     * @param gameDirectory the game installation directory
     * @param mapPath       the map file, used as the session path
     * @param setup         the lobby
     * @param locale        the requested locale such as {@code koKR}, or {@code null} for the installed locale
     * @return the created {@link ClientSession} instance, not yet started
     * @throws IOException if the assets or the map cannot be loaded
     */
    public static @NotNull ClientSession startMelee(@NotNull Path gameDirectory,
                                                    @NotNull Path mapPath, @NotNull MeleeGameSetup setup,
                                                    @Nullable String locale) throws IOException {
        return startMelee(gameDirectory, mapPath, setup, false, 0, locale);
    }

    /**
     * Creates a {@link ClientSession} instance for a single-player melee game with the supplied startup seed.
     * The seed initializes lobby resolution and the replay header before the caller starts the runtime.
     * This session owns its loaded game data; every {@code int} seed, including {@code 0}, is used as supplied.
     *
     * @param gameDirectory the game installation directory
     * @param mapPath       the map file, used as the session path
     * @param setup         the lobby
     * @param gameSeed      the startup game seed and replay-header DWORD bits
     * @param locale        the requested locale, or {@code null} for the installed locale
     * @return the created {@link ClientSession} instance, not yet started
     * @throws IOException if the installation or embedded map assets cannot be loaded
     */
    public static @NotNull ClientSession startMelee(@NotNull Path gameDirectory,
                                                    @NotNull Path mapPath, @NotNull MeleeGameSetup setup, int gameSeed,
                                                    @Nullable String locale) throws IOException {
        return startMelee(gameDirectory, mapPath, setup, true, gameSeed, locale);
    }

    /**
     * Creates a {@link ClientSession} instance on the client's storage and game data (see {@link #load(Path, CascStorage, GameData, Path, String)});
     * the session does not close them.
     *
     * @param gameDirectory the game installation directory
     * @param storage       the client's storage
     * @param gameData      the client's game data, loaded from {@code storage}
     * @param mapPath       the map file, used as the session path
     * @param setup         the lobby
     * @param locale        the requested locale such as {@code koKR}, or {@code null} for the installed locale
     * @return the created {@link ClientSession} instance, not yet started
     * @throws IOException if the map cannot be loaded
     */
    public static @NotNull ClientSession startMelee(@NotNull Path gameDirectory,
                                                    @NotNull CascStorage storage, @NotNull GameData gameData,
                                                    @NotNull Path mapPath, @NotNull MeleeGameSetup setup,
                                                    @Nullable String locale) throws IOException {
        return startMelee(gameDirectory, storage, gameData, mapPath, setup, false, 0, locale);
    }

    /**
     * Creates a {@link ClientSession} instance for a single-player melee game with borrowed data and the supplied startup seed.
     * The seed initializes lobby resolution and the replay header; the session does not close borrowed data.
     * Every {@code int} seed, including {@code 0}, is used as supplied.
     *
     * @param gameDirectory the game installation directory
     * @param storage       the client's storage
     * @param gameData      the client's game data, loaded from {@code storage}
     * @param mapPath       the map file, used as the session path
     * @param setup         the lobby
     * @param gameSeed      the startup game seed and replay-header DWORD bits
     * @param locale        the requested locale, or {@code null} for the installed locale
     * @return the created {@link ClientSession} instance, not yet started
     * @throws IOException if installed assets or the embedded map cannot be loaded
     */
    public static @NotNull ClientSession startMelee(@NotNull Path gameDirectory,
                                                    @NotNull CascStorage storage, @NotNull GameData gameData,
                                                    @NotNull Path mapPath, @NotNull MeleeGameSetup setup, int gameSeed,
                                                    @Nullable String locale) throws IOException {
        return startMelee(gameDirectory, storage, gameData, mapPath, setup, true, gameSeed, locale);
    }

    private static @NotNull ClientSession startMelee(@NotNull Path gameDirectory,
                                                     @NotNull Path mapPath, @NotNull MeleeGameSetup setup,
                                                     boolean hasGameSeed, int gameSeed, @Nullable String locale) throws IOException {
        CascStorage storage = CascStorage.open(gameDirectory);

        try {
            ClientSession session = startMelee(gameDirectory, storage, GameData.load(storage), mapPath, setup, hasGameSeed, gameSeed, locale);

            session.ownsData = true;

            return session;
        } catch (IOException | RuntimeException | Error exception) {
            storage.close();

            throw exception;
        }
    }

    private static @NotNull ClientSession startMelee(@NotNull Path gameDirectory,
                                                     @NotNull CascStorage storage, @NotNull GameData gameData,
                                                     @NotNull Path mapPath, @NotNull MeleeGameSetup setup,
                                                     boolean hasGameSeed, int gameSeed, @Nullable String locale) throws IOException {
        ClientSession session = startLive(storage, gameData, gameDirectory, mapPath, setup, hasGameSeed, gameSeed, locale);

        if (isMapArchive(mapPath)) {
            session.mapArchive       = mapPath.toAbsolutePath().normalize();
            session.mapCompatibility = setup.isMapCompatibility();
            session.openedMapArchive = setup.getMapArchive();
        }

        return session;
    }

    /**
     * Creates a {@link ClientSession} instance for a campaign mission with the supplied startup seed.
     * The seed initializes lobby resolution and the replay header before the caller starts the runtime.
     * This session owns its loaded game data; every {@code int} seed, including {@code 0}, is used as supplied.
     *
     * @param gameDirectory the game installation directory
     * @param mission       the mission index, launch name or directory ({@link CampaignMissions#resolve})
     * @param humanName     the human player's name
     * @param speed         the game speed from {@code 0} through {@link GameSpeed#FASTEST}
     * @param gameSeed      the startup game seed and replay-header DWORD bits
     * @param locale        the requested locale, or {@code null} for the installed locale
     * @return the created {@link ClientSession} instance, not yet started
     * @throws IOException if the mission is unknown or its assets cannot be loaded
     */
    public static @NotNull ClientSession startCampaign(@NotNull Path gameDirectory,
                                                       @NotNull String mission, @NotNull String humanName, int speed, int gameSeed,
                                                       @Nullable String locale) throws IOException {
        return startCampaign(gameDirectory, mission, humanName, speed, true, gameSeed, locale);
    }

    /**
     * Creates a {@link ClientSession} instance for a campaign mission using borrowed storage and game data.
     * The clock seed is read after localized computer names are prepared. The session does not close borrowed data.
     *
     * @param gameDirectory the game installation directory
     * @param storage       the client's storage
     * @param gameData      the client's game data, loaded from {@code storage}
     * @param mission       the mission index, launch name or directory ({@link CampaignMissions#resolve})
     * @param humanName     the human player's name
     * @param speed         the game speed from {@code 0} through {@link GameSpeed#FASTEST}
     * @param locale        the requested locale, or {@code null} for the installed locale
     * @return the created {@link ClientSession} instance, not yet started
     * @throws IOException if the mission is unknown or its scenario cannot be loaded
     */
    public static @NotNull ClientSession startCampaign(@NotNull Path gameDirectory,
                                                       @NotNull CascStorage storage, @NotNull GameData gameData,
                                                       @NotNull String mission, @NotNull String humanName, int speed,
                                                       @Nullable String locale) throws IOException {
        return startCampaign(gameDirectory, storage, gameData, mission, humanName, speed, false, 0, locale);
    }

    private static @NotNull ClientSession startCampaign(@NotNull Path gameDirectory,
                                                        @NotNull String mission, @NotNull String humanName, int speed,
                                                        boolean hasGameSeed, int gameSeed, @Nullable String locale) throws IOException {
        CascStorage storage = CascStorage.open(gameDirectory);

        try {
            ClientSession session = startCampaign(gameDirectory,
                                                  storage, GameData.load(storage),
                                                  mission, humanName, speed,
                                                  hasGameSeed, gameSeed, locale);

            session.ownsData = true;

            return session;
        } catch (IOException | RuntimeException | Error exception) {
            storage.close();

            throw exception;
        }
    }

    private static @NotNull ClientSession startCampaign(@NotNull Path gameDirectory,
                                                        @NotNull CascStorage storage, @NotNull GameData gameData,
                                                        @NotNull String mission, @NotNull String humanName, int speed,
                                                        boolean hasGameSeed, int gameSeed, @Nullable String locale) throws IOException {
        CampaignMissions missions = CampaignMissions.load(storage);
        int index = missions.resolve(mission);

        if (index < 0) {
            throw Exceptions.newIOException("Unknown campaign mission \"{0}\"; --campaign list shows the missions.", mission);
        }

        byte[] scenario = missions.readScenario(storage, index, GameLocaleUtils.select(locale, gameDirectory, storage));
        MeleeGameSetup setup = MeleeGameSetup.createCampaign(scenario, humanName, index, speed, missions.isExpansion(index));
        ClientSession session = startLive(storage, gameData, gameDirectory, Path.of(missions.getName(index)), setup, hasGameSeed, gameSeed, locale);

        session.campaignDirectory = missions.getDirectory(index);

        return session;
    }

    /**
     * Creates a {@link ClientSession} instance for a saved single-player game on borrowed storage and game data.
     * The caller sizes the viewport and calls {@link #startReplay()}, which restores the saved parts before the first frame.
     *
     * @param gameDirectory the game installation directory
     * @param storage       the client's storage
     * @param gameData      the client's game data, loaded from {@code storage}
     * @param file          the saved game file
     * @param locale        the requested locale such as {@code koKR}, or {@code null} for the installed locale
     * @return the created {@link ClientSession} instance, not yet started
     * @throws IOException if the file is not a supported saved game or holds a network game
     */
    public static @NotNull ClientSession startSavedGame(@NotNull Path gameDirectory,
                                                        @NotNull CascStorage storage, @NotNull GameData gameData,
                                                        @NotNull Path file,
                                                        @Nullable String locale) throws IOException {
        SaveGameFile saved = SaveGameFile.parse(Files.readAllBytes(file));
        SaveGameSession savedSession = SaveGameSession.read(saved);
        MeleeGameSetup setup = savedSession.getSetup();

        if (setup.isNetwork()) {
            throw Exceptions.newIOException("The saved game {0} is a network game, which loads from a multiplayer lobby.", file.getFileName());
        }

        nameComputers(setup, storage, gameDirectory, locale);

        ClientSession session = startRestored(storage, gameData, gameDirectory, file, saved, savedSession.createLiveGame(), locale);

        if (setup.getCampaignMission() > 0) {
            CampaignMissions missions = CampaignMissions.load(storage);

            session.campaignDirectory = missions.getDirectory(setup.getCampaignMission());
        }

        return session;
    }

    /**
     * Creates a {@link ClientSession} instance from the local saved game and the lobby's player assignments.
     * Turns use the supplied transport, and startup restores the saved parts before the first frame.
     *
     * @param gameDirectory         the game installation directory
     * @param storage               the client's storage
     * @param gameData              the client's game data
     * @param file                  the local player's own saved game file
     * @param players               the new game's players of the saved slots
     * @param gameName              the full name of the lobby's game, whose speed override the game takes
     * @param turnRate              the turns per second of the lobby's game information
     * @param localPlayerIdentifier the local player's network player identifier in the new game
     * @param transport             the turn stream, continuing the lobby's
     * @param turnsInTransit        the own turns kept in transit
     * @param keepAlive             debug switch: Keep Alive instead of Sync
     * @param locale                the requested locale, or {@code null}
     * @return the created {@link ClientSession} instance, not yet started
     * @throws IOException if the file is not a network saved game of this client
     */
    public static @NotNull ClientSession startNetworkSavedGame(@NotNull Path gameDirectory,
                                                               @NotNull CascStorage storage, @NotNull GameData gameData,
                                                               @NotNull Path file, @NotNull SavedGamePlayers players,
                                                               @NotNull String gameName, int turnRate,
                                                               int localPlayerIdentifier,
                                                               @NotNull LockstepTransport transport,
                                                               int turnsInTransit, boolean keepAlive,
                                                               @Nullable String locale) throws IOException {
        SaveGameFile saved = SaveGameFile.parse(Files.readAllBytes(file));
        SaveGameSession savedSession = SaveGameSession.read(saved);
        MeleeGameSetup setup = savedSession.getSetup();

        if (!setup.isNetwork()) {
            throw Exceptions.newIOException("The saved game {0} is not a network game.", file.getFileName());
        }

        nameComputers(setup, storage, gameDirectory, locale);
        setup.setNetworkGameInformation(gameName, turnRate);

        LiveGame liveGame = savedSession.createLiveGame(players, localPlayerIdentifier);

        liveGame.attachNetwork(transport, turnsInTransit, keepAlive);
        LOGGER.info("LAN SAVED GAME RESOLVED: " + file.getFileName() + ", local slot " + liveGame.getLocalPlayer() + ", " + players);

        return startRestored(storage, gameData, gameDirectory, file, saved, liveGame, locale);
    }

    /**
     * Creates a {@link ClientSession} instance for a saved game: the map archive, the parts to restore once the runtime started, and the file's
     * folder as the save folder.
     *
     * @param storage       the client's storage
     * @param gameData      the client's game data
     * @param gameDirectory the game installation directory
     * @param file          the saved game file, used as the session path when the setup has no map path
     * @param saved         the parsed saved game, restored once the runtime started
     * @param liveGame      the live game created from the saved game
     * @param locale        the requested locale, or {@code null} for the installed locale
     * @return the created {@link ClientSession} instance, not yet started
     * @throws IOException if the live game's replay has no embedded map or it cannot be parsed
     */
    private static @NotNull ClientSession startRestored(@NotNull CascStorage storage, @NotNull GameData gameData,
                                                        @NotNull Path gameDirectory, @NotNull Path file,
                                                        @NotNull SaveGameFile saved, @NotNull LiveGame liveGame,
                                                        @Nullable String locale) throws IOException {
        MeleeGameSetup setup = liveGame.getSetup();
        Path mapPath = setup.getMapPath();
        ClientSession session = startLive(storage, gameData, gameDirectory, mapPath == null ? file : mapPath, liveGame, locale);

        if (mapPath != null && isMapArchive(mapPath)) {
            session.mapArchive       = mapPath.toAbsolutePath().normalize();
            session.mapCompatibility = setup.isMapCompatibility();
        }

        session.restoredGame  = saved;
        session.savedGame     = true;
        session.saveDirectory = file.toAbsolutePath().normalize().getParent();

        return session;
    }

    /**
     * Creates a {@link ClientSession} instance from the lobby table ({@link MeleeGameSetup#createNetwork}); the seed is supplied by the host, the
     * local player plays its own record, and every frame exchanges turns over the transport ({@link LiveGame#attachNetwork}). As for a melee game,
     * the caller sizes the camera viewport and calls {@link #startReplay()}.
     *
     * @param gameDirectory         the game installation directory
     * @param storage               the client's storage
     * @param gameData              the client's game data
     * @param mapPath               the local copy of the game's map
     * @param setup                 the network setup
     * @param seed                  the game seed
     * @param localPlayerIdentifier the local player's network player identifier
     * @param transport             the turn stream, continuing the lobby's
     * @param turnsInTransit        the own turns kept in transit
     * @param keepAlive             debug switch: Keep Alive instead of Sync
     *                              ({@link org.fogofwarlabs.sc4j.scr.game.session.OutgoingTurnBuffer#useKeepAlive()})
     * @param locale                the requested locale, or {@code null}
     * @return the created {@link ClientSession} instance, not yet started
     * @throws IOException if the header cannot be decoded
     */
    public static @NotNull ClientSession startNetwork(@NotNull Path gameDirectory,
                                                      @NotNull CascStorage storage, @NotNull GameData gameData,
                                                      @NotNull Path mapPath, @NotNull MeleeGameSetup setup, int seed,
                                                      int localPlayerIdentifier,
                                                      @NotNull LockstepTransport transport,
                                                      int turnsInTransit, boolean keepAlive,
                                                      @Nullable String locale) throws IOException {
        nameComputers(setup, storage, gameDirectory, locale);

        LiveGame liveGame = new LiveGame(setup, seed, localPlayerIdentifier);

        liveGame.attachNetwork(transport, turnsInTransit, keepAlive);

        StringBuilder records = new StringBuilder();
        ReplayHeader header = liveGame.getReplay().getHeader();

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS && slot < header.getPlayers().size(); slot++) {
            ReplayHeader.ReplayPlayer player = header.getPlayers().get(slot);

            if (player.getType() != PlayerTypes.NONE) {
                records.append(String.format(" [slot %d id %d type %d race %d team %d]",
                                             slot, player.getId(), player.getType(), player.getRace(), player.getTeam()));
            }
        }

        LOGGER.info("LAN GAME RESOLVED: local slot " + liveGame.getLocalPlayer() + ", colours " + Arrays.toString(header.getColors()) + records);

        ClientSession session = startLive(storage, gameData, gameDirectory, mapPath, liveGame, locale);

        session.mapArchive       = mapPath.toAbsolutePath().normalize();
        session.mapCompatibility = setup.isMapCompatibility();
        session.openedMapArchive = setup.getMapArchive();

        return session;
    }

    private static @NotNull ClientSession startLive(@NotNull CascStorage storage, @NotNull GameData gameData,
                                                    @NotNull Path gameDirectory, @NotNull Path sessionPath,
                                                    @NotNull MeleeGameSetup setup, boolean hasGameSeed, int gameSeed,
                                                    @Nullable String locale) throws IOException {
        nameComputers(setup, storage, gameDirectory, locale);

        return startLive(storage, gameData, gameDirectory, sessionPath, new LiveGame(setup, hasGameSeed ? gameSeed : readUnixTime()), locale);
    }

    private static @NotNull ClientSession startLive(@NotNull CascStorage storage, @NotNull GameData gameData,
                                                    @NotNull Path gameDirectory, @NotNull Path sessionPath,
                                                    @NotNull LiveGame liveGame,
                                                    @Nullable String locale) throws IOException {
        ClientSession session = new ClientSession(storage, gameData, liveGame.getReplay(), sessionPath.toAbsolutePath().normalize());

        session.applyLocale(locale, gameDirectory);
        session.liveGame = liveGame;
        liveGame.setSavedGameHandler(session.new SavedGameCommands());

        return session;
    }

    /**
     * Gives computer players localized nation names from the session's network text table.
     *
     * @param setup         the lobby whose computer players are named
     * @param storage       the client's storage
     * @param gameDirectory the game installation directory
     * @param locale        the requested locale, or {@code null} for the installed locale
     */
    private static void nameComputers(@NotNull MeleeGameSetup setup,
                                      @NotNull CascStorage storage, @NotNull Path gameDirectory,
                                      @Nullable String locale) {
        try {
            GluText network = GluText.load(storage, GameLocaleUtils.select(locale, gameDirectory, storage), NETWORK_TEXT_FILE);

            setup.setNationNames((race, index) -> {
                String name = network.get(NATION_NAME_KEY_PREFIX + race + NATION_NAME_KEY_SEPARATOR + index);

                return name.isEmpty() ? null : name;
            });
        } catch (IOException | RuntimeException exception) {
            LOGGER.warning("NATION NAMES UNAVAILABLE: " + exception.getMessage());
        }
    }

    /**
     * Describes the innermost cause of a stopping failure and logs its whole stack.
     *
     * @param failure the stopping failure
     * @return the exception type, throwing method and message of the innermost cause
     */
    private static @NotNull String describeBoundary(@NotNull Throwable failure) {
        Throwable cause = failure;

        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }

        StackTraceElement[] trace = cause.getStackTrace();
        String location = trace.length == 0 ? "" : " at " + trace[0].getClassName().substring(trace[0].getClassName().lastIndexOf('.') + 1) +
                                                   "." + trace[0].getMethodName();
        // The whole stack, so that a stop names the routine that reached the boundary (the boundary line names only the top).
        StringBuilder stack = new StringBuilder();

        for (StackTraceElement element : trace) {
            stack.append(System.lineSeparator()).append("    at ").append(element);
        }

        LOGGER.warning("SIMULATION STOP: " + cause + stack);

        return cause.getClass().getSimpleName() + location + ": " + cause.getMessage();
    }

    /**
     * Checks whether a map file is an original or expansion map archive by its lower-case file name extension.
     *
     * @param mapPath the map file
     * @return {@code true} if the file name ends with a map archive extension, {@code false} otherwise
     */
    private static boolean isMapArchive(@NotNull Path mapPath) {
        String name = mapPath.getFileName() == null ? "" : mapPath.getFileName().toString().toLowerCase(Locale.ROOT);

        return name.endsWith(FileBrowserScreen.ORIGINAL_MAP_EXTENSION) || name.endsWith(FileBrowserScreen.EXPANSION_MAP_EXTENSION);
    }

    /**
     * Reads the host clock in whole seconds, as {@code time(0)} returns it for the game seed, the cheat input's C runtime reseed
     * and the saved game commands.
     *
     * @return the current Unix time in seconds, truncated to an {@code int}
     */
    private static int readUnixTime() {
        return (int) (System.currentTimeMillis() / MILLISECONDS_PER_SECOND);
    }

    private final @NotNull CascStorage storage;
    private final @NotNull GameData gameData;
    /**
     * Whether the session opened its storage and game data itself (headless runs) and closes them.
     */
    private boolean ownsData;

    private final @NotNull ReplayFile replay;
    private final @NotNull Path replayPath;
    private final @NotNull ChkFile mapFile;
    private final @NotNull List<ChkUnit> placements;
    private final @NotNull List<GameLoader.InitializationRequest> initializationRequests;

    private @NotNull Game state;
    private @NotNull ScrGameView view;
    private @NotNull ScrPresentation presentation;
    private final @NotNull ClientCamera camera;
    private @Nullable ChkUnit selectedPlacement;

    private boolean closed;
    private @Nullable ReplayRuntime runtime;
    private @Nullable WindowsHostTiming hostTiming;
    /**
     * The client's watchdog of the simulation frames, or {@code null} when the frames are not watched.
     */
    private @Nullable SimulationWatchdog watchdog;
    private @Nullable String playbackFailure;
    private @Nullable String failureBoundary;
    private boolean playbackRequested;
    private @Nullable LiveGame liveGame;
    private boolean interactiveReplay;
    /**
     * The options' speed setting that the interactive viewer starts a replay at.
     */
    private int replaySpeedSetting;
    private @NotNull MusicRotation musicRotation;
    private int screenWidth;
    private int screenHeight;
    private boolean replayPaused;
    private int pausedMode;
    private boolean waitingForTurns;
    /**
     * The pacing of the live game's frames and turn waits, which a network game reports once a second ({@link #reportLivePacing()}).
     */
    private final @NotNull LivePacingStatistics pacingStatistics;
    /**
     * Test harness: called with the frame about to exchange its turn ({@link #setTurnListener}), or {@code null}.
     */
    private @Nullable LongConsumer turnListener;

    private @NotNull String locale;
    private @NotNull SoundPathResolver.AudioSet audioSet;
    /**
     * Original Unit Voice Overs: the game's sounds come from {@code enUS} ({@link #setOriginalUnitVoices}).
     */
    private boolean originalUnitVoices;
    private boolean soundEffectsEnabled;
    private boolean gameSubtitles;
    /**
     * The client audio the session plays on ({@link #setAudio}); without it the session plays no sound.
     */
    private @Nullable ClientAudio audio;
    private @Nullable ClientSoundPlayback soundPlayback;
    private MessageLines.@Nullable TextLayout messageLayout;
    private @Nullable TextTables textTables;
    private @Nullable NativeCodeConstants codeConstants;

    private @Nullable Path mapArchive;
    private boolean mapCompatibility;
    private @Nullable String campaignDirectory;
    /**
     * The opened map archive of {@link #mapArchive}: the archive a live game's setup opened ({@link MeleeGameSetup#getMapArchive()}), else read
     * once by the first map file read ({@link #readMapFile}), or {@code null} before it.
     */
    private @Nullable MpqArchive openedMapArchive;
    /**
     * The lookup of map files in the storage under the session locale and audio set, replaced when either changes.
     */
    private @NotNull SoundPathResolver mapFilePaths;

    /**
     * The saved game this session restores once its runtime started ({@link #startSavedGame}), or {@code null}.
     */
    private @Nullable SaveGameFile restoredGame;
    /**
     * Whether the session started from a saved game ({@link #startSavedGame}).
     */
    private boolean savedGame;
    /**
     * The folder the saved games of the player's ID are written into and listed from, or {@code null} without one.
     */
    private @Nullable Path saveDirectory;
    /**
     * The saved game an executed Load Game command asked for, until the client takes it ({@link #takeRequestedLoad}).
     */
    private @Nullable Path requestedLoad;
    /**
     * The {@code network.json} key of the last failed Save Game command, until the interface takes it.
     */
    private @Nullable String saveFailure;
    /**
     * The host tick of the last Save Game request.
     */
    private long lastSaveTick;

    private @Nullable LanClient networkClient;
    private @Nullable GameCommunityChat communityChat;
    private int gameFlags;
    /**
     * The recipient slot, force or special chat target.
     */
    private int chatRecipient;
    /**
     * The formatted chat recipient label.
     */
    private @NotNull String chatLabel;
    private final @NotNull List<int[]> minimapPings;

    private @Nullable ReplayCommandIssuer pendingCommandIssuer;
    private @Nullable ReplayCommand pendingCommand;
    private @Nullable ReplaySelectionCommand pendingSelection;
    private @Nullable ReplayTargetCommand pendingTarget;
    private @Nullable String pendingCommandError;

    /**
     * Creates a {@link ClientSession} instance for the replay and embedded map.
     *
     * @param storage    the shared asset storage
     * @param gameData   the shared game data
     * @param replay     the replay or live game command stream
     * @param replayPath the absolute session path
     * @throws IOException if the replay has no embedded map or it cannot be parsed
     */
    private ClientSession(@NotNull CascStorage storage, @NotNull GameData gameData, @NotNull ReplayFile replay, @NotNull Path replayPath) throws IOException {
        byte[] mapData = replay.getMapData();

        if (mapData == null || mapData.length == 0) {
            throw Exceptions.newIOException("The replay does not contain map data.");
        }

        this.storage  = storage;
        this.gameData = gameData;
        this.ownsData = false;

        this.replay                 = replay;
        this.replayPath             = replayPath;
        this.mapFile                = ChkFile.parseTolerant(mapData);
        this.placements             = List.copyOf(this.mapFile.getUnits());
        this.initializationRequests = GameLoader.createInitializationLedger(replay.getHeader().getSettings(), this.mapFile);

        this.state             = ReplayLoaderUtils.createReplayInspectionState(replay, this.mapFile, gameData);
        this.view              = new ScrGameView(this.state);
        this.presentation      = new ScrPresentation(this.state);
        this.camera            = new ClientCamera(this.state.getMap().getWidth() * TerrainTileset.TILE_SIZE,
                                                  this.state.getMap().getHeight() * TerrainTileset.TILE_SIZE);
        this.selectedPlacement = null;

        this.closed             = false;
        this.runtime            = null;
        this.hostTiming         = null;
        this.watchdog           = null;
        this.playbackFailure    = null;
        this.failureBoundary    = null;
        this.playbackRequested  = false;
        this.liveGame           = null;
        this.interactiveReplay  = false;
        this.replaySpeedSetting = GameSpeed.PRISTINE_SPEED;
        this.musicRotation      = new MusicRotation();
        this.screenWidth        = StartScreenUtils.DEFAULT_SCREEN_WIDTH;
        this.screenHeight       = StartScreenUtils.DEFAULT_SCREEN_HEIGHT;
        this.replayPaused       = false;
        this.pausedMode         = PLAYING_TIMING_MODE;
        this.waitingForTurns    = false;
        this.pacingStatistics   = new LivePacingStatistics(System.nanoTime());
        this.turnListener       = null;

        this.locale              = GameLocaleUtils.FALLBACK;
        this.audioSet            = SoundPathResolver.AudioSet.REMASTERED;
        this.originalUnitVoices  = false;
        this.soundEffectsEnabled = true;
        this.gameSubtitles       = true;
        this.audio               = null;
        this.soundPlayback       = null;
        this.messageLayout       = null;
        this.textTables          = null;
        this.codeConstants       = null;

        this.mapArchive        = null;
        this.mapCompatibility  = false;
        this.campaignDirectory = null;
        this.openedMapArchive  = null;
        this.mapFilePaths      = new SoundPathResolver(this.storage, this.locale, this.audioSet);

        this.restoredGame  = null;
        this.savedGame     = false;
        this.saveDirectory = null;
        this.requestedLoad = null;
        this.saveFailure   = null;
        this.lastSaveTick  = NO_SAVE_TICK;

        this.networkClient = null;
        this.communityChat = null;
        this.gameFlags     = 0;
        this.chatRecipient = ChatPrompt.RECIPIENT_ALL;
        this.chatLabel     = "";
        this.minimapPings  = new ArrayList<>();

        this.pendingCommandIssuer = null;
        this.pendingCommand       = null;
        this.pendingSelection     = null;
        this.pendingTarget        = null;
        this.pendingCommandError  = null;

        this.refreshPendingCommand();
    }

    /**
     * @return the asset storage; owned data closes with this session, while borrowed data remains open
     */
    public @NotNull CascStorage getStorage() {
        return this.storage;
    }

    /**
     * @return the shared game data, valid until this session is closed
     */
    public @NotNull GameData getGameData() {
        return this.gameData;
    }

    /**
     * @return the session locale driving interface text and speech, for example {@code koKR}
     */
    public @NotNull String getLocale() {
        return this.locale;
    }

    /**
     * @return the selected audio set
     */
    public @NotNull SoundPathResolver.AudioSet getAudioSet() {
        return this.audioSet;
    }

    /**
     * Sets the sound set independently of the graphics mode.
     * Changing the setting invalidates uploaded sounds so subsequent plays load from the new set.
     *
     * @param audioSet the audio set
     */
    public void setAudioSet(@NotNull SoundPathResolver.AudioSet audioSet) {
        if (audioSet == this.audioSet) {
            return;
        }

        this.audioSet     = audioSet;
        this.mapFilePaths = new SoundPathResolver(this.storage, this.locale, audioSet);
        this.gameData.setSoundPaths(new SoundPathResolver(this.storage, this.getSoundLocale(), audioSet));

        if (this.soundPlayback != null) {
            this.soundPlayback.invalidate();
        }
    }

    /**
     * Sets whether locales without their own dub use the original English unit voices. Changing the setting invalidates uploaded sounds.
     *
     * @param originalUnitVoices whether the unit voices are the original English ones
     */
    public void setOriginalUnitVoices(boolean originalUnitVoices) {
        if (originalUnitVoices == this.originalUnitVoices) {
            return;
        }

        this.originalUnitVoices = originalUnitVoices;
        this.gameData.setSoundPaths(new SoundPathResolver(this.storage, this.getSoundLocale(), this.audioSet));

        if (this.soundPlayback != null) {
            this.soundPlayback.invalidate();
        }
    }

    /**
     * @return the locale the game's sounds are looked up under ({@link #setOriginalUnitVoices})
     */
    private @NotNull String getSoundLocale() {
        return this.originalUnitVoices && !DUBBED_LOCALES.contains(this.locale) ? ORIGINAL_VOICE_LOCALE : this.locale;
    }

    /**
     * Sets whether game, interface and map sound effects start.
     *
     * @param enabled whether sound effects start
     */
    public void setSoundEffectsEnabled(boolean enabled) {
        this.soundEffectsEnabled = enabled;

        if (this.soundPlayback != null) {
            this.soundPlayback.setEnabled(enabled);
        }
    }

    /**
     * Sets whether trigger text without the always-display flag is shown, including transmission text. Applies to the running game immediately.
     *
     * @param gameSubtitles whether trigger text without the always-display flag shows
     */
    public void setGameSubtitles(boolean gameSubtitles) {
        this.gameSubtitles = gameSubtitles;

        this.state.getInterfaceLink().getMessageLines().setSubtitles(gameSubtitles);
    }

    /**
     * @param soundIdentifier the sfxdata index
     * @return the sound length in milliseconds from its decoded WAV data, or {@code 0} if unavailable
     */
    public int getSoundLength(int soundIdentifier) {
        return this.soundPlayback == null ? 0 : this.soundPlayback.getLength(this.gameData, soundIdentifier);
    }

    /**
     * Sets the local message text layout for every state this session creates before the first frame.
     *
     * @param messageLayout the layout, or {@code null} when no display exists (headless runs)
     */
    public void setMessageLayout(MessageLines.@Nullable TextLayout messageLayout) {
        this.messageLayout = messageLayout;

        if (messageLayout != null) {
            this.state.getInterfaceLink().getMessageLines().setLayout(messageLayout);
        }
    }

    /**
     * Sets the client's localized text tables ({@link ClientContext#getTextTables}), loaded once for the client run; they are installed into every
     * game state this session creates, before the first frame.
     *
     * @param textTables the tables, or {@code null} without a client (the message lines then stay unknown after a localized write)
     */
    public void setTextTables(@Nullable TextTables textTables) {
        this.textTables = textTables;

        if (textTables != null) {
            textTables.install(this.state.getInterfaceLink().getMessageLines());
        }
    }


    /**
     * @return the parsed replay
     */
    public @NotNull ReplayFile getReplay() {
        return this.replay;
    }

    /**
     * @return the absolute local replay path
     */
    public @NotNull Path getReplayPath() {
        return this.replayPath;
    }

    /**
     * Returns immutable scenario initialization inputs in THG2-before-UNIT order.
     * These records do not establish constructor outcomes, allocations or RNG state.
     *
     * @return the initialization input ledger
     */
    public @NotNull List<GameLoader.InitializationRequest> getInitializationRequests() {
        return this.initializationRequests;
    }

    /**
     * @return immutable CHK UNIT inputs, including records which may not create a unit in this game mode
     */
    public @NotNull List<ChkUnit> getPlacements() {
        return this.placements;
    }

    /**
     * Returns a map string of the game's scenario as trigger text and sound events reference it: through the live string section of the
     * presentation's game ({@link MessageLines#getMapString}), which includes its registered strings, or else the scenario's {@code STR } or
     * {@code STRx} section.
     *
     * @param index the one-based string index ({@code 0} is no string)
     * @return the decoded string, or an empty string for {@code 0} or an index outside the table
     */
    public @NotNull String getMapString(int index) {
        if (index <= 0) {
            return "";
        }

        String resolved = this.presentation.getInterfaceLink().getMessageLines().getMapString(index);

        if (resolved != null) {
            return resolved;
        }

        List<String> strings = this.mapFile.getStrings();

        return index <= strings.size() ? strings.get(index - 1) : "";
    }

    /**
     * Returns the scenario's force name for the requested force.
     *
     * @param force the force index from {@code 0} through {@code 3}
     * @return the map name, or {@code null} when no name is recorded
     */
    public @Nullable String getForceName(int force) {
        return ForceNamesUtils.getMapForceName(this.mapFile, force + 1);
    }

    /**
     * @param force a force from {@code 1} through {@code 4}
     * @return the force's name ({@link ForceNamesUtils}): {@code strTEAM1} through {@code strTEAM4} in a team game, else the map's {@code FORC}
     * string or {@code strFORCE1} through {@code strFORCE4}
     */
    public @NotNull String getForceDisplayName(int force) {
        return ForceNamesUtils.resolveForceName(key -> {
            TextTables tables = this.textTables;

            return tables == null ? "" : tables.getNetwork().get(key);
        }, this.isTeamGame(), this.getForceName(force - 1), force);
    }

    /**
     * @return the authoritative runtime world when constructed, otherwise the map inspection state
     */
    public @NotNull Game getGame() {
        return this.state;
    }

    /**
     * @return the read-only view of {@link #getGame()} the client's interface and world view read: one view per game state, replaced
     * when the session's game state changes
     */
    public @NotNull ScrGameView getView() {
        return this.view;
    }

    /**
     * @return the SC:R presentation state of {@link #getView()}'s game that the client writes (interface link, local interface effects, the
     * presentation's C runtime generator): one per game state, replaced with the view
     */
    public @NotNull ScrPresentation getPresentation() {
        return this.presentation;
    }

    /**
     * Resolves a recorded replay command issuer to the player record of the session's game.
     *
     * @param recordedIssuer the recorded issuer byte
     * @return the resolution
     * @throws IllegalArgumentException if the issuer is outside the unsigned byte range
     */
    public @NotNull ReplayCommandIssuer resolveCommandIssuer(int recordedIssuer) {
        return ReplayCommandIssuer.resolve(this.state, recordedIssuer);
    }

    /**
     * @return the client inspection camera
     */
    public @NotNull ClientCamera getCamera() {
        return this.camera;
    }

    /**
     * @return the selected map placement for read-only inspection, or {@code null} if nothing is selected
     */
    public @Nullable ChkUnit getSelectedPlacement() {
        return this.selectedPlacement;
    }


    /**
     * Returns the next decoded command without consuming or applying it.
     * <p>
     * If the parser skipped unknown command blocks, this may not be the first recorded command.
     * Explicit map inspection never consumes commands; replay execution advances this cursor.
     *
     * @return the pending decoded command for read-only inspection, or {@code null} if none was decoded
     */
    public @Nullable ReplayCommand getPendingCommand() {
        return this.pendingCommand;
    }

    /**
     * Returns issuer diagnostics for the pending command without changing player records. Unresolved records receive no Java slot fallback.
     *
     * @return the initial resolution, or {@code null} if no command was decoded
     */
    public @Nullable ReplayCommandIssuer getPendingCommandIssuer() {
        return this.pendingCommandIssuer;
    }

    /**
     * @return the pending selection payload with raw handles, or {@code null} for another opcode or malformed payload
     */
    public @Nullable ReplaySelectionCommand getPendingSelection() {
        return this.pendingSelection;
    }

    /**
     * @return the pending target payload with unresolved unit handle, or {@code null} for another opcode or malformed payload
     */
    public @Nullable ReplayTargetCommand getPendingTarget() {
        return this.pendingTarget;
    }

    /**
     * @return the command decoding error, or {@code null} if the pending payload did not fail decoding
     */
    public @Nullable String getPendingCommandError() {
        return this.pendingCommandError;
    }


    /**
     * @return the startup phase, playback progress or stopping reason
     */
    public @NotNull String getStatus() {
        if (this.closed) {
            return "Session closed.";
        }

        if (this.playbackFailure != null) {
            return this.playbackFailure;
        }

        if (this.runtime != null) {
            ReplaySession replaySession = this.runtime.getSession();

            if (replaySession != null && replaySession.isFinished()) {
                return "Replay finished; completed frame " + this.getLastCompletedFrame() + ".";
            }

            if (replaySession != null && replaySession.getEnd() == GameEnd.EUD_FAILURE) {
                return "Game ended by an EUD failure; completed frame " + this.getLastCompletedFrame() + ".";
            }

            if (replaySession != null && replaySession.getEnd() == GameEnd.RESTART) {
                return "Game ended by a Restart Game command; completed frame " + this.getLastCompletedFrame() + ".";
            }

            return "Replay " + this.runtime.getPhase() + "; completed frame " + this.getLastCompletedFrame() + ".";
        }

        return "Map input inspection only; verified world initialization is required before replay execution.";
    }

    /**
     * @return the runtime phase, or {@code construction} before the runtime exists
     */
    public @NotNull String getRuntimePhase() {
        return this.runtime == null ? CONSTRUCTION_PHASE : this.runtime.getPhase();
    }

    /**
     * @return the exception type, message and throwing method of the stopping failure, or {@code null} without failure
     */
    public @Nullable String getFailureBoundary() {
        return this.failureBoundary;
    }

    /**
     * @return {@code true} if replay startup was requested, {@code false} otherwise
     */
    public boolean isPlaybackRequested() {
        return this.playbackRequested;
    }

    /**
     * @return {@code true} if playback consumed its final frame without failure, {@code false} otherwise
     */
    public boolean isFinished() {
        return this.playbackFailure == null && this.runtime != null && this.runtime.getSession() != null && this.runtime.getSession().isFinished();
    }

    /**
     * @return {@code true} if the game ended before its last frame ({@link ReplaySession#isEnded()}), {@code false} otherwise
     */
    public boolean isEnded() {
        return this.runtime != null && this.runtime.getSession() != null && this.runtime.getSession().isEnded();
    }

    /**
     * @return why the game ended before its last frame ({@link ReplaySession#getEnd()}), or {@code null} while it runs or without a session
     */
    public @Nullable GameEnd getEnd() {
        return this.runtime == null || this.runtime.getSession() == null ? null : this.runtime.getSession().getEnd();
    }

    /**
     * @return the last successfully completed frame, or {@code -1} before successful startup
     */
    public long getLastCompletedFrame() {
        return this.runtime == null || this.runtime.getSession() == null ? -1 : this.runtime.getSession().getLastCompletedFrame();
    }

    /**
     * @return {@code true} if the established replay can attempt another frame, {@code false} otherwise
     */
    public boolean canStep() {
        return !this.closed && this.playbackFailure == null && this.runtime != null &&
               this.runtime.getSession() != null && !this.runtime.getSession().isFinished() && !this.runtime.getSession().isEnded();
    }

    /**
     * @return {@code true} if the runtime duration table exists, {@code false} otherwise
     */
    public boolean hasFrameTiming() {
        return this.runtime != null;
    }

    /**
     * @return the speed configuration of the running replay or game, whose setting and factor the replay viewer shows
     * @throws IllegalStateException if replay construction has not completed
     */
    public @NotNull GameSpeed getFrameTiming() {
        if (this.runtime == null) {
            throw Exceptions.newIllegalStateException("Replay timing is unavailable before construction.");
        }

        return this.runtime.getFrameTiming();
    }

    /**
     * Returns the current host milliseconds per frame from the runtime duration table.
     * A live game uses its requested or lobby speed, or its speed override; replay playback uses the viewer's speed, also for a replay with a
     * speed override, which only the simulation's timing takes.
     *
     * @return the frame duration in milliseconds
     * @throws IllegalStateException if replay construction has not completed
     */
    public int getFrameDuration() {
        GameSpeed timing = this.getFrameTiming();

        if (this.liveGame != null) {
            int requested = this.state.getRequestedGameSpeed();

            return timing.getDuration(requested >= 0 ? requested : this.liveGame.getSetup().getSpeed());
        }

        return timing.getTableDuration(timing.getSpeedIndex());
    }

    /**
     * @return {@code true} if this is a live game whose speed override throttles drawing ({@link GameSpeed#isDrawThrottled()}), {@code false}
     * otherwise (a replay never throttles)
     */
    public boolean isDrawThrottled() {
        return this.liveGame != null && this.runtime != null && this.runtime.getFrameTiming().isDrawThrottled();
    }

    /**
     * @return how far the live game's frame schedule may fall behind the host clock, in milliseconds ({@link GameSpeed#getLagLimit(int)})
     * @throws IllegalStateException if this session has no live game
     */
    public int getLiveLagLimit() {
        return GameSpeed.getLagLimit(this.getGameSpeed());
    }

    /**
     * Sets the replay pause state while preserving the current speed and multiplier.
     * Pausing saves the mode; resuming restores mode {@code 0} only when the saved mode was {@code 0}.
     * A changed state rebuilds the duration table and discards its prior writes.
     *
     * @param paused whether the replay is paused
     */
    public void setReplayPaused(boolean paused) {
        if (this.runtime == null || this.liveGame != null || paused == this.replayPaused) {
            return;
        }

        this.replayPaused = paused;

        GameSpeed timing = this.runtime.getFrameTiming();

        if (paused) {
            this.pausedMode = timing.getMode();
            timing.configure(timing.getSpeedIndex(), timing.getFactor(), PAUSED_TIMING_MODE);
        } else if (this.pausedMode == PLAYING_TIMING_MODE) {
            timing.configure(timing.getSpeedIndex(), timing.getFactor(), PLAYING_TIMING_MODE);
        }
    }

    /**
     * Sets the replay viewer's playback that recorded Pause and Resume commands pause and continue and the received turns' Replay Speed and Replay
     * Seek commands pause, continue and seek ({@link ReplayRuntime#setPlaybackControl}).
     *
     * @param playbackControl the viewer's playback, or {@code null} for none
     */
    public void setReplayPlaybackControl(ReplayCommandExecutor.@Nullable PlaybackControl playbackControl) {
        if (this.runtime != null) {
            this.runtime.setPlaybackControl(playbackControl);
        }
    }

    /**
     * Sets whether the next startup uses the replay viewer's local-player presentation.
     * Headless and comparison runs retain their existing player selection.
     *
     * @param interactiveReplay whether the session plays a replay in the interactive viewer
     */
    public void setInteractiveReplay(boolean interactiveReplay) {
        this.interactiveReplay = interactiveReplay;
    }

    /**
     * Sets the options' speed setting that the interactive viewer starts the next replay at, with factor {@code 1}; a replay without it starts at
     * {@link GameSpeed#PRISTINE_SPEED}.
     *
     * @param replaySpeedSetting the speed setting, {@code 0} (Slowest) through {@code 6} (Fastest)
     * @throws IllegalArgumentException if the speed setting is out of range
     */
    public void setReplaySpeedSetting(int replaySpeedSetting) {
        if (replaySpeedSetting < 0 || replaySpeedSetting > GameSpeed.FASTEST) {
            throw Exceptions.newIllegalArgumentException("Replay speed setting must be 0 through 6: {0}", replaySpeedSetting);
        }

        this.replaySpeedSetting = replaySpeedSetting;
    }

    /**
     * Sets the music rotation of the client process, which the next startup advances when it is not a campaign game. A
     * session that is not given one starts as the first game of a process.
     *
     * @param musicRotation the client process's music rotation
     */
    public void setMusicRotation(@NotNull MusicRotation musicRotation) {
        this.musicRotation = musicRotation;
    }

    /**
     * Sets the client audio the session's sounds play on, from the next replay startup on. A session that is not given one plays no sound.
     *
     * @param audio the client audio, or {@code null} for none
     */
    public void setAudio(@Nullable ClientAudio audio) {
        this.audio = audio;
    }

    /**
     * Sets the displayed game screen size with which the next startup, also a replay restart ({@link #reset}), places the start
     * screen of a replay ({@link ReplayRuntime#setScreenSize}). A session that is not given one uses the default screen size.
     *
     * @param screenWidth  the game screen width in map pixels
     * @param screenHeight the game screen height in map pixels
     * @throws IllegalArgumentException if a screen dimension is not positive
     */
    public void setScreenSize(int screenWidth, int screenHeight) {
        if (screenWidth <= 0 || screenHeight <= 0) {
            throw Exceptions.newIllegalArgumentException("The screen size {0} x {1} is not positive.", screenWidth, screenHeight);
        }

        this.screenWidth  = screenWidth;
        this.screenHeight = screenHeight;
    }

    /**
     * @return the race the game music starts with ({@link ReplayInitialization#findMusicRace}), or
     * {@link ReplayInitialization#NO_MUSIC_RACE} before the start or when the player record it reads is missing
     */
    public int getMusicRace() {
        ReplayRuntime current = this.runtime;

        return current == null ? ReplayInitialization.NO_MUSIC_RACE : current.getInitialization().findMusicRace(this.state.getPlayerTable());
    }

    /**
     * Sets the test harness listener called in a live game with the frame whose turn is about to be exchanged, before the exchange, so that it can
     * queue local commands ({@link #submitLocalCommand}) into that turn.
     *
     * @param listener the listener, or {@code null} for none
     */
    public void setTurnListener(@Nullable LongConsumer listener) {
        this.turnListener = listener;
    }


    /**
     * @return the live game, or {@code null} for replay playback
     */
    public @Nullable LiveGame getLiveGame() {
        return this.liveGame;
    }

    /**
     * @return the live game of this session
     * @throws IllegalStateException if this session plays a replay
     */
    private @NotNull LiveGame requireLiveGame() {
        if (this.liveGame == null) {
            throw Exceptions.newIllegalStateException("This session plays a replay, not a live game.");
        }

        return this.liveGame;
    }

    /**
     * @return the runtime of the started session
     * @throws IllegalStateException if the session has not started
     */
    private @NotNull ReplayRuntime requireRuntime() {
        ReplayRuntime current = this.runtime;

        if (current == null) {
            throw Exceptions.newIllegalStateException("The session has not started.");
        }

        return current;
    }

    /**
     * @return the sound playback of the started session
     * @throws IllegalStateException if the session has not started
     */
    private @NotNull ClientSoundPlayback requireSoundPlayback() {
        ClientSoundPlayback current = this.soundPlayback;

        if (current == null) {
            throw Exceptions.newIllegalStateException("The session has not started.");
        }

        return current;
    }

    /**
     * Sets the watchdog that watches this session's simulation frames ({@link SimulationWatchdog#begin}, {@link SimulationWatchdog#end}).
     *
     * @param watchdog the watchdog, or {@code null} to watch no frame
     */
    void setSimulationWatchdog(@Nullable SimulationWatchdog watchdog) {
        this.watchdog = watchdog;
    }

    /**
     * @return {@code true} if this session plays a live game, {@code false} otherwise
     */
    public boolean isLive() {
        return this.liveGame != null;
    }

    /**
     * @return {@code true} if this session runs a live network game, {@code false} otherwise
     */
    public boolean isNetworkGame() {
        return this.liveGame != null && this.liveGame.isNetworkGame();
    }

    /**
     * @return {@code true} if this session runs a live game whose local player is an observer ({@link LiveGame#isLocalObserver()}),
     * {@code false} otherwise
     */
    public boolean isLocalObserver() {
        return this.liveGame != null && this.liveGame.isLocalObserver();
    }

    /**
     * @return {@code true} if the last step awaited a network player's turn without running a frame, {@code false} otherwise
     */
    public boolean isWaitingForTurns() {
        return this.waitingForTurns;
    }

    /**
     * Records a frame the client presented, for the live pacing report ({@link #reportLivePacing()}).
     */
    void recordPresentedFrame() {
        this.pacingStatistics.recordPresentedFrame();
    }

    /**
     * Logs the pacing of a network game once a second at {@code INFO} ({@code LIVE PACING}): the frames simulated per second with their
     * average and longest simulation time, the frames presented per second, and the milliseconds per second the game waited for players' turns
     * with its longest single wait. The period of any other session is discarded without a log.
     */
    void reportLivePacing() {
        String report = this.pacingStatistics.takeReport(System.nanoTime());

        if (report != null && this.isNetworkGame()) {
            LOGGER.info("LIVE PACING: " + report);
        }
    }

    /**
     * @return the local human's player index (owner index and start slot)
     * @throws IllegalStateException if this session has no live game
     */
    public int getLocalPlayer() {
        return this.requireLiveGame().getLocalPlayer();
    }

    /**
     * @return {@code true} if a human other than the local player participates, {@code false} otherwise
     * @throws IllegalStateException if this session has no live game
     */
    public boolean hasOtherHuman() {
        return this.requireLiveGame().hasOtherHuman();
    }

    /**
     * @return the current game seed
     */
    public int getGameSeed() {
        return this.state.getGameContext().getGameSeed();
    }

    /**
     * @return the current live game speed from {@code 0} through {@code 6}: the last executed Game Speed command, else the lobby speed
     * @throws IllegalStateException if no speed is requested and this session has no live game
     */
    public int getGameSpeed() {
        int requested = this.state.getRequestedGameSpeed();

        return requested >= 0 ? requested : this.requireLiveGame().getSetup().getSpeed();
    }

    /**
     * @return the outcome screen shown to the local player: {@code 0} running, {@code 2} defeat, {@code 3} victory or draw
     * @throws IllegalStateException if this session has no live game
     */
    public int getLiveOutcome() {
        return this.requireLiveGame().getLocalOutcome();
    }

    /**
     * @return the running game's trigger local player, or {@code -1} before startup
     */
    public int getTriggerLocalPlayer() {
        return this.state.getTriggerContext() == null ? -1 : this.state.getTriggerContext().getLocalPlayer();
    }

    /**
     * @return {@code true} if a trigger phase gate pauses frame advancement, {@code false} otherwise
     */
    public boolean isGamePaused() {
        return this.state.getTriggerContext() != null && this.state.getTriggerContext().getPhaseGate() != 0;
    }

    /**
     * @return {@code true} if triggers are enabled while the phase gate is held, {@code false} otherwise
     */
    public boolean isTriggerPaused() {
        return this.state.getTriggerContext() != null && this.state.getTriggerContext().getTriggersEnabledWhileGated() != 0;
    }

    /**
     * @return the session's interface game flags; recorded replay flags are not applied
     */
    public int getGameFlags() {
        return this.gameFlags;
    }

    /**
     * Sets the game flags of a network game (the host's, which its advertisement carries), which its live game takes before its session
     * starts ({@link LiveGame#setGameFlags}).
     *
     * @param gameFlags the flags
     */
    public void setGameFlags(int gameFlags) {
        this.gameFlags = gameFlags;

        if (this.liveGame != null) {
            this.liveGame.setGameFlags(gameFlags);
        }
    }

    /**
     * @return the waiting-for-players countdown in seconds, or {@code -1} when not waiting
     */
    public int getNetworkWaitCountdown() {
        return this.liveGame == null ? -1 : this.liveGame.getWaitCountdown();
    }

    /**
     * Lists the players the waiting-for-players dialog shows; the shuffle reseeds and advances the game's C runtime generator.
     *
     * @return the names of the players whose turns are overdue ({@link LiveGame#getWaitingPlayerNames})
     */
    public @NotNull List<String> getNetworkWaitingPlayers() {
        return this.liveGame == null ? List.of() : this.liveGame.getWaitingPlayerNames(this.state.getRuntimeRandom());
    }


    /**
     * Lists the players of the active-player walk ({@link TriggerContext#getActivePlayers}) for the alliance and messaging dialogs: in a team network
     * game the force leaders in force order, otherwise slots {@code 0}-{@code 7}, either way without players out of the game.
     *
     * @return the active player slots in walk order, empty before the game's triggers exist
     */
    public int @NotNull [] getActivePlayers() {
        TriggerContext triggers = this.state.getTriggerContext();

        return triggers == null ? new int[0] : triggers.getActivePlayers();
    }

    /**
     * @param slot a slot
     * @return the player record's type
     */
    public int getPlayerType(int slot) {
        return this.state.getPlayerTable().requirePlayer(slot).getType();
    }

    /**
     * @param slot a slot
     * @return the player record's force
     */
    public int getPlayerForce(int slot) {
        return this.state.getPlayerTable().requirePlayer(slot).getTeam();
    }

    /**
     * @param slot a slot
     * @return the player's name
     */
    public @NotNull String getPlayerName(int slot) {
        List<String> names = this.replay.getPlayerNames();

        return slot >= 0 && slot < PlayerTable.PLAYABLE_PLAYER_SLOTS && slot < names.size() ? names.get(slot) : "";
    }

    /**
     * @return {@code true} if a network game has its template teams option set, {@code false} otherwise
     */
    public boolean isTeamGame() {
        LiveGame game = this.liveGame;

        return game != null && game.isNetworkGame() && game.getSetup().getTemplateOptions()[GameTemplate.TEAMS] != 0;
    }

    /**
     * @return {@code true} if the allies option is enabled and the tournament option is clear, {@code false} otherwise
     * @throws IllegalStateException if this session has no live game
     */
    public boolean areAlliancesAllowed() {
        int[] options = this.requireLiveGame().getSetup().getTemplateOptions();

        return PlayerRelations.areAlliancesAllowed(options[GameTemplate.ALLIES], options[GameTemplate.TOURNAMENT]);
    }

    /**
     * @return the local player's alliance values for every player slot
     * @throws IllegalStateException if this session has no live game
     */
    public int @NotNull [] getLocalAlliances() {
        int local = this.requireLiveGame().getLocalPlayer();
        int[] alliances = new int[PlayerTable.PLAYER_SLOTS];

        for (int player = 0; player < alliances.length; player++) {
            alliances[player] = this.state.getPlayerTable().requirePlayerRelations().getAlliance(local, player);
        }

        return alliances;
    }

    /**
     * @return the local player's unsigned 16-bit shared vision mask
     * @throws IllegalStateException if this session has no live game
     */
    public int getLocalSharedVision() {
        return this.state.getPlayerTable().requirePlayerRelations().getSharedVision(this.requireLiveGame().getLocalPlayer()) & SHARED_VISION_MASK;
    }

    /**
     * @return the current chat recipient slot, force or special recipient value
     */
    public int getChatRecipient() {
        return this.chatRecipient;
    }

    /**
     * Sets the chat recipient slot, force or special recipient value.
     *
     * @param recipient the recipient: a slot {@code 0} through {@code 7} (a force in a team game), all {@code 8}, allies {@code 9}
     */
    public void setChatRecipient(int recipient) {
        this.chatRecipient = recipient;
    }

    /**
     * @return the formatted label of the chosen recipient
     */
    public @NotNull String getChatLabel() {
        return this.chatLabel;
    }

    /**
     * Builds the chat mode's unsigned 16-bit recipient mask. Team targets expand to their human members; bit {@code 15} selects observers.
     *
     * @param mode the chat mode
     * @return the mask
     * @throws IllegalStateException if this session has no live game
     */
    public int getRecipientMask(int mode) {
        LiveGame game = this.requireLiveGame();
        int local = game.getLocalPlayer();
        boolean team = game.getSetup().getTemplateOptions()[GameTemplate.TEAMS] != 0;
        int mask = 0;

        switch (mode) {
            case ChatPrompt.MODE_ALL -> mask = CHAT_ALL_RECIPIENTS_MASK;
            case ChatPrompt.MODE_OBSERVERS -> mask = CHAT_OBSERVER_RECIPIENT_MASK;
            case ChatPrompt.MODE_ALLIES -> {
                for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
                    if (this.state.getPlayerTable().requirePlayerRelations().getAlliance(local, slot) == 0) {
                        continue;
                    }

                    if (team) {
                        mask |= this.getForceMask(this.state.getPlayerTable().requirePlayer(slot).getTeam());
                    } else {
                        mask |= 1 << (this.state.getPlayerTable().requirePlayer(slot).getId() & CHAT_PLAYER_IDENTIFIER_MASK);
                    }
                }
            }
            case ChatPrompt.MODE_RECIPIENT -> {
                if (team) {
                    mask = this.getForceMask(this.state.getPlayerTable().requirePlayer(local).getTeam()) | this.getForceMask(this.chatRecipient);
                } else {
                    mask = 1 << (this.state.getPlayerTable().requirePlayer(local).getId() & CHAT_PLAYER_IDENTIFIER_MASK);

                    if (this.chatRecipient < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                        mask |= 1 << (this.state.getPlayerTable().requirePlayer(this.chatRecipient).getId() & CHAT_PLAYER_IDENTIFIER_MASK);
                    }
                }
            }
            default -> {
            }
        }

        return mask & CHAT_ALL_RECIPIENTS_MASK;
    }

    /**
     * Builds the chat recipient mask of the human players belonging to a force.
     *
     * @param force the force
     * @return the recipient bits of the force's human players
     */
    private int getForceMask(int force) {
        int mask = 0;

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            Player player = this.state.getPlayerTable().requirePlayer(slot);

            if (player.getType() == MeleeGameSetup.TYPE_HUMAN && player.getTeam() == force) {
                mask |= 1 << (player.getId() & CHAT_PLAYER_IDENTIFIER_MASK);
            }
        }

        return mask;
    }

    /**
     * @return {@code true} if a network Top vs Bottom game or a network game with more than one known player runs, {@code false} otherwise
     */
    public boolean hasMultiplayerMinimapButtons() {
        LanClient client = this.networkClient;
        LiveGame game = this.liveGame;

        if (client == null || game == null || !game.isNetworkGame()) {
            return false;
        }

        if (game.getSetup().getGameType() == GameTemplate.TYPE_TOP_VS_BOTTOM) {
            return true;
        }

        synchronized (client) {
            ProviderSession session = client.getSession();

            if (session == null) {
                return false;
            }

            int players = 1;

            for (ProviderPeer peer : session.getPeers()) {
                if (peer.getIdentifier() != ProviderPeer.UNKNOWN_IDENTIFIER) {
                    players++;
                }
            }

            return players > 1;
        }
    }


    /**
     * @return {@code true} if this session started from a saved game ({@link #startSavedGame}), {@code false} otherwise
     */
    public boolean isSavedGame() {
        return this.savedGame;
    }

    /**
     * @return the folder of the player's saved games, or {@code null} without one
     */
    public @Nullable Path getSaveDirectory() {
        return this.saveDirectory;
    }

    /**
     * Sets the folder the saved games of the player's ID are written into and listed from.
     *
     * @param saveDirectory the folder, or {@code null} without one (Save Game and Load Game are then unavailable)
     */
    public void setSaveDirectory(@Nullable Path saveDirectory) {
        this.saveDirectory = saveDirectory == null ? null : saveDirectory.toAbsolutePath().normalize();
    }

    /**
     * @return the expansion and network variant of this game's saved files
     * @throws IllegalStateException if this session has no live game
     */
    public @NotNull SaveGameKind getSaveGameKind() {
        LiveGame game = this.requireLiveGame();

        return SaveGameKind.of(game.getSetup().isExpansion(), game.isNetworkGame());
    }

    /**
     * Checks whether a live game with a save folder and no extended SetDeaths mode may show Save Game. The local player must be first, and the
     * game's simulation plugins must allow a save ({@link Game#isSaveAllowed()}).
     *
     * @return {@code true} if Save Game is shown, {@code false} otherwise
     */
    public boolean isSaveGameShown() {
        LiveGame game = this.liveGame;

        if (game == null || this.saveDirectory == null || !game.isLocalPlayerFirst() || !this.state.isSaveAllowed()) {
            return false;
        }

        TriggerContext triggers = this.state.getTriggerContext();

        return triggers == null || (triggers.getExtendedDeathsMode() & TriggerContext.EXTENDED_SET_DEATHS_MODE) == 0;
    }

    /**
     * Checks save availability, tournament restrictions and the interval since the last network save.
     *
     * @param hostTick the host clock in milliseconds
     * @return {@code true} if the save dialog opens, {@code false} otherwise
     * @throws IllegalStateException if this session has no live game
     */
    public boolean canOpenSaveDialog(long hostTick) {
        if (!this.isSaveGameShown()) {
            return false;
        }

        LiveGame game = this.requireLiveGame();

        if (game.getSetup().getTemplate().getOption(GameTemplate.TOURNAMENT) != 0) {
            return false;
        }

        return !game.isNetworkGame() || this.lastSaveTick == NO_SAVE_TICK || hostTick > this.lastSaveTick + MULTIPLAYER_SAVE_INTERVAL_MILLISECONDS;
    }

    /**
     * Checks for a live single-player game with a save folder.
     *
     * @return {@code true} if the load dialog opens, {@code false} otherwise
     */
    public boolean canOpenLoadDialog() {
        return this.liveGame != null && !this.liveGame.isNetworkGame() && this.saveDirectory != null;
    }

    /**
     * @return the characters a saved game name must not hold: the {@link FileNameUtils#BAD_CHARACTERS_KEY} network text of the client's locale, or
     * {@link FileNameUtils#BAD_CHARACTERS} without text tables
     */
    private @NotNull String getBadFileCharacters() {
        TextTables tables = this.textTables;

        return tables == null ? FileNameUtils.BAD_CHARACTERS : tables.getNetwork().get(FileNameUtils.BAD_CHARACTERS_KEY);
    }

    /**
     * Checks the name bytes of a Save Game or Load Game command as the name of a saved game file.
     *
     * @param name the name bytes without terminator: the file name without extension
     * @return {@code true} if the name is not empty and is a valid file name in a saved game name buffer
     * ({@link FileNameUtils#isValidFileName(byte[], int, String)}), {@code false} otherwise
     */
    private boolean isValidSavedGameName(byte @NotNull [] name) {
        return name.length != 0 && FileNameUtils.isValidFileName(name, ReplayFile.SAVED_GAME_NAME_SIZE, this.getBadFileCharacters());
    }


    /**
     * Starts the common replay runtime with measured Windows host timing.
     * Retains startup failures for inspection without publishing an executable world.
     *
     * @throws IllegalStateException if the session is closed or startup was already requested
     */
    public void startReplay() {
        this.startReplay(null);
    }

    /**
     * Starts the common replay runtime with measured Windows host timing; a restart applies the vision before it to the new world before it runs
     * ({@link LocalVision#restartReplay}). Retains startup failures for inspection without publishing an executable world.
     *
     * @param previousVision the vision of the replay before a restart, or {@code null} for a first start
     * @throws IllegalStateException if the session is closed or startup was already requested
     */
    private void startReplay(@Nullable LocalVision previousVision) {
        if (this.closed || this.playbackRequested) {
            throw Exceptions.newIllegalStateException("Replay startup is unavailable for this session.");
        }

        this.playbackRequested = true;
        this.selectedPlacement = null;

        try {
            this.hostTiming = new WindowsHostTiming();

            // One set of sound channels per session: a seek restart reuses it instead of creating it again.
            if (this.soundPlayback == null) {
                this.soundPlayback = new ClientSoundPlayback(this.audio);
            }

            this.soundPlayback.setEnabled(this.soundEffectsEnabled);

            this.runtime = this.liveGame == null
                           ? new ReplayRuntime(this.replay,
                                               this.mapFile,
                                               this.gameData,
                                               this.musicRotation,
                                               this.camera.getViewportWidth(), this.camera.getViewportHeight(),
                                               INSTRUCTION_LIMIT)
                           : ReplayRuntime.createLiveGame(this.replay,
                                                          this.mapFile,
                                                          this.gameData,
                                                          this.liveGame.getLocalPlayer(),
                                                          this.liveGame.getLocalSlot(),
                                                          this.liveGame.isNetworkGame(),
                                                          this.liveGame.getTeamSlotRaces(),
                                                          this.musicRotation,
                                                          this.camera.getViewportWidth(), this.camera.getViewportHeight(),
                                                          INSTRUCTION_LIMIT);

            this.runtime.setScreenSize(this.screenWidth, this.screenHeight);

            if (this.liveGame == null && this.interactiveReplay) {
                this.runtime.useNativeReplayLocalPlayer();
            }

            this.state        = this.runtime.getGame();
            this.view         = new ScrGameView(this.state);
            this.presentation = new ScrPresentation(this.state);
            this.attachCodeConstants(this.state);

            if (previousVision != null) {
                this.state.getLocalVision().restartReplay(previousVision);
            }

            // The message lines are written from the first trigger pass on, before any console exists: the localized
            // texts, the host clock, the subtitle option and the local layout are session inputs.
            Game messageState = this.state;
            MessageLines messageLines = messageState.getInterfaceLink().getMessageLines();

            messageLines.setHostClock(MessageAreaUtils.createHostClock());
            messageLines.setSubtitles(this.gameSubtitles);

            if (this.messageLayout != null) {
                messageLines.setLayout(this.messageLayout);
            }

            if (this.textTables != null) {
                this.textTables.install(messageLines);
            }

            byte[] chatPalette = this.gameData.loadTerrainPalette(this.mapFile.getTileset());

            messageLines.setNameColors(slot -> getChatNameColor(messageState, chatPalette, slot));
            messageLines.setTeamForceNames(slot -> this.isTeamGame()
                                                   ? this.getForceDisplayName(messageState.getPlayerTable().requirePlayer(slot).getTeam())
                                                   : null);

            // The game loads from the replay file or the map file; a network setup comes from the lobby without a path, and its
            // session keeps the local copy of the map.
            Path sourcePath = this.liveGame == null ? this.replayPath
                                                    : this.liveGame.getSetup().getMapPath() != null
                                                      ? this.liveGame.getSetup().getMapPath()
                                                      : this.mapArchive;

            MpqArchive setupArchive = this.liveGame == null ? null : this.liveGame.getSetup().getMapArchive();

            // A live game's setup already holds the map file it opened; the source file shares its content.
            if (setupArchive != null && sourcePath != null) {
                this.runtime.setSourceFile(GameSourceFile.create(sourcePath, setupArchive.getArchiveData()));
            } else if (sourcePath != null) {
                this.runtime.setSourceFile(GameSourceFile.read(sourcePath));
            }

            if (this.liveGame != null) {
                this.liveGame.startSession(this.state, this.runtime.getFrameTiming());
            }

            if (this.liveGame != null && this.liveGame.isNetworkGame()) {
                ClientCamera view = this.camera;

                this.liveGame.setSyncSource(this.state, () -> Math.max(0, (int) view.getY() / TerrainTileset.TILE_SIZE));
            }

            this.state.getInterfaceLink().getLocalInterface().startInterfaceTick((int) UiClockUtils.getHostTimeMilliseconds());

            LocalInterfaceState localInterface = this.state.getInterfaceLink().getLocalInterface();

            localInterface.setInterfaceSelection(new int[InterfaceSelection.CAPACITY]);
            localInterface.setPortraitRefresh(1);
            localInterface.setStallStamp((int) UiClockUtils.getHostTimeMilliseconds() - STALL_STAMP_OFFSET_MILLISECONDS);
            localInterface.setChatLabel(new byte[LocalInterfaceState.CHAT_LABEL_SIZE]);
            localInterface.setStatusPanelCache(new int[LocalInterfaceState.STATUS_PANEL_CACHE_DWORDS]);

            this.runtime.start(this.hostTiming.readElapsedTicks(), this.hostTiming.readHostTicks());

            SaveGameFile saved = this.restoredGame;

            if (saved != null && this.liveGame != null && this.runtime.getFailure() == null) {
                this.restoredGame = null;
                SaveGameUtils.read(saved, new SaveGameContext(this.runtime, this.liveGame));
            }

            if (this.liveGame != null && this.runtime.getSession() != null) {
                this.liveGame.attachReplaySession(this.runtime.getSession());
            }

            ReplaySession replaySession = this.runtime.getSession();

            if (replaySession != null) {
                GameSession session = this.createGameSession(replaySession);

                replaySession.setCommandSource(session.getCommandSource());
            }

            this.soundPlayback.drain(this.view, this.presentation, this.camera);

            // The viewer's speed setting is the replay's speed and the speed its trigger Waits count down with.
            if (this.liveGame == null && this.interactiveReplay && this.runtime.getFailure() == null) {
                this.runtime.configureSpeed(this.replaySpeedSetting, START_TIMING_FACTOR, PLAYING_TIMING_MODE);
            }

            if (this.runtime.getFailure() != null) {
                this.playbackFailure = "Startup " + this.runtime.getPhase() + ": " + this.runtime.getFailure().getMessage();
                this.failureBoundary = describeBoundary(this.runtime.getFailure());
            }
        } catch (IOException | RuntimeException exception) {
            this.playbackFailure = "Startup " + (this.runtime == null ? CONSTRUCTION_PHASE : this.runtime.getPhase()) + ": " + exception.getMessage();
            this.failureBoundary = describeBoundary(exception);
        }

        this.refreshPendingCommand();
    }

    /**
     * Creates the engine game session of the started game through the engine {@link GameSessionFactory}: the session shows the game through this
     * session's {@link ScrGameView}, and the replay session's game loop applies its command source ({@link CommandPoint#MOD_LOOP}), which asks the
     * recorded stream ({@link ReplaySession#getController()}) alone. Replay playback and an observer's game, which have no engine player of their
     * own, are replay sessions; a player's live game is a local or network session of its own command path, the local player's commands queued
     * into the outgoing turn ({@link LocalCommandSink}).
     *
     * @param replaySession the started replay session
     * @return the created {@link GameSession} instance
     */
    private @NotNull GameSession createGameSession(@NotNull ReplaySession replaySession) {
        ScrGameView sessionView = this.view;
        GameSessionFactory factory = new GameSessionFactory((game, localPlayer) -> sessionView, CommandPoint.MOD_LOOP);
        org.fogofwarlabs.sc4j.engine.game.Game engineGame = this.state.getEngineGame();
        List<PlayerController> controllers = List.of(replaySession.getController());
        LiveGame live = this.liveGame;

        if (live == null || live.isLocalObserver()) {
            return factory.createReplay(engineGame, controllers);
        }

        org.fogofwarlabs.sc4j.engine.player.Player localPlayer = engineGame.getPlayer(live.getLocalPlayer());
        CommandSink local = new LocalCommandSink(live, localPlayer);

        return live.isNetworkGame() ? factory.createNetwork(engineGame, localPlayer, local, controllers)
                                    : factory.createLocal(engineGame, localPlayer, local, controllers);
    }

    /**
     * Resolves the session locale once ({@link GameLocaleUtils#select}) and applies it to speech.
     *
     * @param requested     the requested locale, or {@code null} for the installed locale
     * @param gameDirectory the game installation directory
     */
    private void applyLocale(@Nullable String requested, @NotNull Path gameDirectory) {
        // The game data read the executable once; each new game state copies the parsed constants.
        this.codeConstants = this.gameData.getCodeConstants();

        if (!this.codeConstants.isKnown()) {
            LOGGER.warning("NATIVE CODE CONSTANTS unknown: " +
                           gameDirectory.resolve(GameData.EXECUTABLE_DIRECTORY).resolve(GameData.EXECUTABLE_FILE_NAME));
        }

        this.attachCodeConstants(this.state);
        this.locale       = GameLocaleUtils.select(requested, gameDirectory, this.storage);
        this.mapFilePaths = new SoundPathResolver(this.storage, this.locale, this.audioSet);
        this.gameData.setSoundPaths(new SoundPathResolver(this.storage, this.getSoundLocale(), this.audioSet));
    }

    /**
     * Copies the session's parsed code constants into a game state. Unknown constants do not prevent startup.
     *
     * @param target the game state
     */
    private void attachCodeConstants(@NotNull Game target) {
        if (this.codeConstants != null) {
            target.getGameContext().getCodeConstants().copyFrom(this.codeConstants);
        }
    }

    /**
     * Returns the color a chat line names its sender in: the color the sender is shown in ({@link PlayerColorRows#getPlayerColor}).
     *
     * @param target  the game state
     * @param palette the tileset's {@code .wpe} palette
     * @param slot    the sender slot
     * @return the packed {@code 0xRRGGBBAA} color, or {@code 0} if unavailable
     */
    private static int getChatNameColor(@NotNull Game target, byte @NotNull [] palette, int slot) {
        PlayerColorRows rows = slot >= 0 && slot < PlayerTable.PLAYER_SLOTS ? target.getPlayerTable().getPlayerColorRows() : null;

        if (rows == null) {
            return 0;
        }

        return rows.getPlayerColor(slot, index -> {
            int entry = index * RenderAssets.PALETTE_ENTRY_SIZE;

            return Byte.toUnsignedInt(palette[entry]) << 3 * Byte.SIZE |
                   Byte.toUnsignedInt(palette[entry + RenderAssets.PALETTE_GREEN]) << Short.SIZE |
                   Byte.toUnsignedInt(palette[entry + RenderAssets.PALETTE_BLUE]) << Byte.SIZE |
                   Byte.toUnsignedInt(OverlayPass.OPAQUE_ALPHA);
        });
    }

    private void refreshPendingCommand() {
        ReplaySession replaySession = this.runtime == null ? null : this.runtime.getSession();
        List<ReplayCommand> commands = this.replay.getCommands();
        ReplayCommand nextCommand;

        if (replaySession != null) {
            nextCommand = replaySession.getPendingCommand();
        } else {
            nextCommand = commands.isEmpty() ? null : commands.getFirst();
        }

        this.pendingCommandIssuer = nextCommand == null ? null : ReplayCommandIssuer.resolve(this.state, nextCommand.getPlayerId());

        if (nextCommand == this.pendingCommand) {
            return;
        }

        this.pendingCommand = nextCommand;

        ReplaySelectionCommand selection = null;
        ReplayTargetCommand target = null;
        String commandError = null;

        if (this.pendingCommand != null) {
            try {
                if (ReplaySelectionCommand.supports(this.pendingCommand.getTypeId())) {
                    selection = ReplaySelectionCommand.parse(this.pendingCommand);
                } else if (ReplayTargetCommand.supports(this.pendingCommand.getTypeId())) {
                    target = ReplayTargetCommand.parse(this.pendingCommand);
                }
            } catch (IOException exception) {
                commandError = exception.getMessage();
            }
        }

        this.pendingSelection    = selection;
        this.pendingTarget       = target;
        this.pendingCommandError = commandError;
    }


    /**
     * Selects the nearest unit center whose DAT bounds cover the pointer.
     * <p>
     * Bounds receive four screen pixels of inspection padding. This is a client selection policy, not a reproduction of original selection,
     * visibility, or ownership rules.
     *
     * @param horizontal the pointer position from the left viewport edge in screen pixels
     * @param vertical   the pointer position from the top viewport edge in screen pixels
     * @return the selected unit, or {@code null} if the viewport position does not hit a unit
     * @throws NullPointerException if the world was built without game data
     */
    public @Nullable ChkUnit selectAtScreen(float horizontal, float vertical) {
        this.selectedPlacement = null;

        if (this.closed || this.playbackRequested || !Float.isFinite(horizontal) || !Float.isFinite(vertical) ||
            horizontal < 0 || vertical < 0 || horizontal >= this.camera.getViewportWidth() || vertical >= this.camera.getViewportHeight()) {
            return null;
        }

        float worldHorizontal = this.camera.screenToWorldX(horizontal);
        float worldVertical = this.camera.screenToWorldY(vertical);
        float padding = INSPECTION_PADDING_PIXELS / this.camera.getZoom();
        float closestDistance = Float.POSITIVE_INFINITY;
        ScrUnitTypeView units = this.state.requireGameData().getUnitTypes();
        List<ChkUnit> currentUnits = this.getPlacements();

        for (int unitIndex = currentUnits.size() - 1; unitIndex >= 0; unitIndex--) {
            ChkUnit unit = currentUnits.get(unitIndex);
            int unitType = unit.getUnitId();

            if (unitType >= units.getCount()) {
                continue;
            }

            float relativeHorizontal = worldHorizontal - unit.getX();
            float relativeVertical = worldVertical - unit.getY();

            if (relativeHorizontal < -units.getDimensionLeft(unitType) - padding ||
                relativeHorizontal > units.getDimensionRight(unitType) + padding ||
                relativeVertical < -units.getDimensionUp(unitType) - padding ||
                relativeVertical > units.getDimensionDown(unitType) + padding) {
                continue;
            }

            float distance = relativeHorizontal * relativeHorizontal + relativeVertical * relativeVertical;

            if (distance < closestDistance) {
                closestDistance        = distance;
                this.selectedPlacement = unit;
            }
        }

        return this.selectedPlacement;
    }

    /**
     * Selects an exact CHK UNIT record for local inspection.
     * <p>
     * This index is not a game unit handle and never issues a gameplay command.
     * Unlike pointer hit testing, this also distinguishes units at the same position.
     *
     * @param unitIndex the zero-based index in the map input list
     * @return the selected unit
     * @throws IllegalStateException     if the session is closed
     * @throws IndexOutOfBoundsException if the index is outside the current unit list
     */
    public @NotNull ChkUnit selectPlacementForInspection(int unitIndex) {
        if (this.closed) {
            throw Exceptions.newIllegalStateException("The session is closed.");
        }

        this.selectedPlacement = this.getPlacements().get(unitIndex);

        return this.selectedPlacement;
    }

    /**
     * Clears the client inspection selection without changing simulation state.
     */
    public void clearSelection() {
        this.selectedPlacement = null;
    }


    /**
     * Executes one replay frame with measured host ticks and retains the first failure.
     * Partial state remains visible, but cannot be retried as a completed frame.
     *
     * @return {@code true} if a frame completed, {@code false} otherwise
     */
    public boolean stepFrame() {
        return this.stepFrame(true);
    }

    /**
     * Executes one replay frame, optionally discarding its sound events, as when seeking or running unpaced.
     * A stopped or finished game and a missing network player's turn do not complete a frame ({@link #isWaitingForTurns()}).
     *
     * @param audible whether the frame's sound events are played
     * @return {@code true} if a frame completed, {@code false} otherwise
     */
    public boolean stepFrame(boolean audible) {
        this.waitingForTurns = false;

        if (!this.canStep()) {
            return false;
        }

        boolean stepping = false;

        try {
            if (this.liveGame != null && this.turnListener != null) {
                this.turnListener.accept(this.state.getCurrentFrame());
            }

            if (this.liveGame != null && !this.liveGame.exchangeTurn(this.state.getCurrentFrame())) {
                this.waitingForTurns = true;
                this.pacingStatistics.beginTurnWait(System.nanoTime());

                return false;
            }

            boolean completed;
            SimulationWatchdog frameWatchdog = this.watchdog;
            long stepStart = System.nanoTime();

            this.pacingStatistics.endTurnWait(stepStart);

            if (frameWatchdog != null) {
                frameWatchdog.begin(this.state.getCurrentFrame());
            }

            stepping = true;

            try {
                completed = this.requireRuntime().step(this.hostTiming.readElapsedTicks(), this.hostTiming.readHostTicks());
            } finally {
                if (frameWatchdog != null) {
                    frameWatchdog.end();
                }

                this.pacingStatistics.recordFrame(System.nanoTime() - stepStart);
            }

            if (completed) {
                FrameTiming.addSimulationStep();
            }

            if (completed && this.liveGame != null) {
                this.liveGame.updateOutcome(this.state);
            }

            if (audible) {
                this.requireSoundPlayback().drain(this.view, this.presentation, this.camera);
            } else {
                this.state.getInterfaceLink().getSoundEvents().clear();
            }

            return completed;
        } catch (RuntimeException exception) {
            this.retainStepFailure(exception);

            return false;
        } finally {
            if (stepping) {
                this.receiveMinimapPings();
            }

            this.refreshPendingCommand();
        }
    }

    /**
     * Runs a game loop of the replay while the viewer's playback is paused ({@link ReplayRuntime#stepPaused()}): the replay commands of the current
     * frame and the turn step, whose received turn runs the viewer's controls, without a frame. A finished replay runs paused loops too; a stopped or
     * ended replay and a live game run none.
     *
     * @return {@code true} if the loop ran, {@code false} otherwise
     */
    public boolean stepPausedReplay() {
        ReplaySession replaySession = this.findReplaySession();

        if (this.closed || this.playbackFailure != null || replaySession == null || replaySession.isEnded()) {
            return false;
        }

        try {
            boolean ran = this.requireRuntime().stepPaused();

            this.requireSoundPlayback().drain(this.view, this.presentation, this.camera);

            return ran;
        } catch (RuntimeException exception) {
            this.retainStepFailure(exception);

            return false;
        } finally {
            this.receiveMinimapPings();
            this.refreshPendingCommand();
        }
    }

    /**
     * Retains the failure of a game loop step as the stopping reason, with the runtime phase, the last completed frame and the
     * state frame, and its boundary.
     *
     * @param exception the failure of the step
     */
    private void retainStepFailure(@NotNull RuntimeException exception) {
        this.playbackFailure = "Blocked in " + this.requireRuntime().getPhase() + " after completed frame " + this.getLastCompletedFrame() +
                               ", state frame " + this.state.getCurrentFrame() + ": " + exception.getMessage();
        this.failureBoundary = describeBoundary(exception);
    }

    /**
     * Collects the minimap pings the last step executed ({@link ReplaySession#getExecutedCommands()}) in command order as sender
     * and two unsigned 16-bit coordinates.
     */
    private void receiveMinimapPings() {
        if (this.runtime == null || this.runtime.getSession() == null) {
            return;
        }

        for (ReplayCommand command : this.runtime.getSession().getExecutedCommands()) {
            byte[] payload = command.getPayload();

            if (command.getTypeId() != ReplayCommandIds.MINIMAP_PING || payload.length != MINIMAP_PING_PAYLOAD_BYTES) {
                continue;
            }

            ReplayCommandIssuer issuer = ReplayCommandIssuer.resolve(this.state, command.getPlayerId());

            if (issuer.getStatus() != ReplayCommandIssuer.Status.RESOLVED) {
                continue;
            }

            this.minimapPings.add(new int[]{issuer.getCommandPlayerIndex(),
                                            LittleEndianUtils.readInt(payload, 0, Short.BYTES),
                                            LittleEndianUtils.readInt(payload, MINIMAP_PING_VERTICAL_OFFSET, Short.BYTES)});
        }
    }

    /**
     * Takes the minimap ping commands executed since the last call.
     *
     * @return {@code {sender player, x, y}} per command in execution order
     */
    public @NotNull List<int[]> takeMinimapPings() {
        if (this.minimapPings.isEmpty()) {
            return List.of();
        }

        List<int[]> pings = List.copyOf(this.minimapPings);

        this.minimapPings.clear();

        return pings;
    }

    /**
     * Takes the pauses and resumes the executed Pause and Resume commands made: a live game's ({@link LiveGame}) or the
     * replay's ({@link ReplayRuntime#takePauseNotices}).
     *
     * @return the notices in execution order
     */
    public @NotNull List<PauseNotice> takePauseNotices() {
        if (this.liveGame != null) {
            return this.liveGame.takePauseNotices();
        }

        return this.runtime == null ? List.of() : this.runtime.takePauseNotices();
    }

    /**
     * Recreates the current inspection or replay startup state and clears selection while retaining the camera. A restarted replay keeps the speed
     * setting and factor its viewer played at.
     *
     * @throws IOException                   if the embedded map cannot be loaded
     * @throws IllegalStateException         if the session is closed
     * @throws UnsupportedOperationException if this session has a live game
     */
    public void reset() throws IOException {
        if (this.closed) {
            throw Exceptions.newIllegalStateException("The session is closed.");
        }

        if (this.liveGame != null) {
            throw Exceptions.newUnsupportedOperationException("A live game cannot be restarted; start a new game instead.");
        }

        Game initialState = ReplayLoaderUtils.createReplayInspectionState(this.replay, this.mapFile, this.gameData);
        boolean restartPlayback = this.playbackRequested;
        LocalVision previousVision = this.state.getLocalVision();
        GameSpeed previousTiming = this.runtime == null ? null : this.runtime.getFrameTiming();

        this.minimapPings.clear();

        this.replayPaused = false;
        this.pausedMode   = PLAYING_TIMING_MODE;
        this.state        = initialState;
        this.view         = new ScrGameView(initialState);
        this.presentation = new ScrPresentation(initialState);

        this.attachCodeConstants(initialState);

        this.runtime           = null;
        this.hostTiming        = null;
        this.playbackFailure   = null;
        this.failureBoundary   = null;
        this.playbackRequested = false;

        if (restartPlayback) {
            this.startReplay(previousVision);
        }

        if (previousTiming != null) {
            this.restoreReplaySpeed(previousTiming.getSpeedIndex(), previousTiming.getFactor());
        }

        this.refreshPendingCommand();

        this.selectedPlacement = null;
    }

    /**
     * Restores the speed of a restarted replay: an unchanged setting and factor leave the duration table intact; a change rebuilds it and, in the
     * interactive viewer, makes the setting the game speed option.
     *
     * @param setting the speed setting from {@code 0} through {@code 6}
     * @param factor  the factor
     */
    private void restoreReplaySpeed(int setting, int factor) {
        if (this.runtime == null || this.liveGame != null) {
            return;
        }

        GameSpeed timing = this.runtime.getFrameTiming();

        if (timing.getSpeedIndex() == setting && timing.getFactor() == factor) {
            return;
        }

        if (this.interactiveReplay && this.runtime.getSession() != null) {
            this.runtime.configureSpeed(setting, factor, timing.getMode());
        } else {
            timing.configure(setting, factor, timing.getMode());
        }
    }


    /**
     * Presses the replay viewer's Slow Down: queues its Replay Speed command ({@link ReplaySession#decreaseSpeed()}).
     * Without a replay session nothing happens.
     */
    public void decreaseReplaySpeed() {
        ReplaySession replaySession = this.findReplaySession();

        if (replaySession != null) {
            replaySession.decreaseSpeed();
        }
    }

    /**
     * Presses the replay viewer's Speed Up: queues its Replay Speed command ({@link ReplaySession#increaseSpeed()}).
     * Without a replay session nothing happens.
     */
    public void increaseReplaySpeed() {
        ReplaySession replaySession = this.findReplaySession();

        if (replaySession != null) {
            replaySession.increaseSpeed();
        }
    }

    /**
     * Presses the replay viewer's Play/Pause: queues its Replay Speed command ({@link ReplaySession#playOrPause()}).
     * Without a replay session nothing happens.
     */
    public void playOrPauseReplay() {
        ReplaySession replaySession = this.findReplaySession();

        if (replaySession != null) {
            replaySession.playOrPause();
        }
    }

    /**
     * Clicks the replay viewer's seek bar: queues its Replay Seek command ({@link ReplaySession#seek(int)}).
     * Without a replay session nothing happens.
     *
     * @param frame the frame to seek to
     */
    public void seekReplay(int frame) {
        ReplaySession replaySession = this.findReplaySession();

        if (replaySession != null) {
            replaySession.seek(frame);
        }
    }

    /**
     * @return the session of the replay that plays, or {@code null} before its startup and in a live game
     */
    private @Nullable ReplaySession findReplaySession() {
        return this.runtime == null || this.liveGame != null ? null : this.runtime.getSession();
    }

    /**
     * Places the game start's screen in the local view ({@link StartScreenUtils#placeStartScreen}): a replay start already placed
     * it with the set screen size ({@link #setScreenSize}), a live player's start places it now with the displayed screen size on
     * the local player's start location, and a world without a start places it on the start location of the replay's first
     * human.
     *
     * @param screenWidth  the game screen width in map pixels of a live player's start or a world without a start
     * @param screenHeight the game screen height in map pixels of a live player's start or a world without a start
     * @return the placed screen top-left {@code {x, y}} in map pixels, or {@code null} if a replay has no human player or the
     * live player has no start location
     */
    public int @Nullable [] placeStartScreen(int screenWidth, int screenHeight) {
        LocalView view = this.state.getInterfaceLink().getLocalView();
        int player = this.runtime != null ? this.runtime.getInitialization().findStartScreenPlayer()
                                          : ReplayInitialization.findNativeReplayLocalPlayer(this.replay.getHeader());

        if (!view.isKnown() && !StartScreenUtils.placeStartScreen(this.state, player, screenWidth, screenHeight)) {
            return null;
        }

        return new int[]{view.getScreenX(), view.getScreenY()};
    }


    /**
     * Stops playing game, interface and map sound effects without throwing.
     */
    public void stopSoundEffects() {
        if (this.soundPlayback != null) {
            this.soundPlayback.stopAll();
        }
    }

    /**
     * Plays a sound through the session's sound channels outside the simulation without throwing.
     *
     * @param soundIdentifier the sfxdata index
     * @param sourceUnitType  the unit type for per-unit channel rules, or {@code -1}
     * @param volume          the volume from {@code 0} through {@code 100}
     * @return {@code true} if a channel started, {@code false} otherwise
     */
    public boolean playInterfaceSound(int soundIdentifier, int sourceUnitType, int volume) {
        return this.soundPlayback != null && this.soundPlayback.playInterface(this.gameData, soundIdentifier, sourceUnitType, volume);
    }

    /**
     * Reads a file of the session's map archive named by the raw bytes of a map string, such as the sound path of a trigger action.
     * <p>
     * The storage and the map archive are searched with the undecoded bytes, so CP949 and Windows-1252 names resolve in the archive. Never throws.
     *
     * @param rawPath the raw file name bytes
     * @return the file contents, or {@code null} if neither the storage nor the map archive holds the file
     */
    public byte @Nullable [] readMapFile(byte @NotNull [] rawPath) {
        return this.readMapFile(LegacyTextUtils.decode(rawPath, 0, rawPath.length), rawPath);
    }

    private byte @Nullable [] readMapFile(@NotNull String path, byte @NotNull [] rawPath) {
        try {
            byte[] name = rawPath;

            if (this.campaignDirectory != null) {
                byte[] directory = MpqCryptoUtils.toNameBytes(this.campaignDirectory + CAMPAIGN_PATH_SEPARATOR);

                name = Arrays.copyOf(directory, directory.length + rawPath.length);
                System.arraycopy(rawPath, 0, name, directory.length, rawPath.length);
            }

            if (this.openedMapArchive == null && this.mapArchive != null) {
                this.openedMapArchive = MpqArchive.open(this.mapArchive, this.mapCompatibility);
            }

            return this.mapFilePaths.readAsset(name, this.openedMapArchive);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warning("MAP FILE UNAVAILABLE: " + path + ": " + exception.getMessage());
        }

        return null;
    }

    /**
     * Plays a map sound ({@link #readMapFile}) on the session's map sound channels, outside the installed sound definitions. Never throws.
     *
     * @param wave   the WAVE file
     * @param volume the volume from {@code 0} through {@code 100}
     * @return the playing sound, or {@code null} if nothing started
     */
    public @Nullable PlayingSound playMapSound(byte @NotNull [] wave, int volume) {
        return this.soundPlayback == null ? null : this.soundPlayback.playData(wave, volume);
    }

    /**
     * Stops a sound started by {@link #playMapSound}; a finished or replaced sound does nothing. Never throws.
     *
     * @param sound the playing sound
     */
    public void stopMapSound(@NotNull PlayingSound sound) {
        if (this.soundPlayback != null) {
            this.soundPlayback.stopData(sound);
        }
    }


    /**
     * Attaches the LAN client used under its lock for game messages.
     * Selects allies for an alliance-enabled Top vs Bottom or team game, otherwise all players.
     *
     * @param client the client whose session the game runs on
     * @throws IllegalStateException if this session has no live game
     */
    public void attachNetworkChat(@NotNull LanClient client) {
        this.networkClient = client;

        LiveGame game = this.requireLiveGame();
        int[] options = game.getSetup().getTemplateOptions();
        boolean teams = game.getSetup().getGameType() == GameTemplate.TYPE_TOP_VS_BOTTOM || options[GameTemplate.TEAMS] != 0;

        this.chatRecipient = teams && this.areAlliancesAllowed() ? ChatPrompt.RECIPIENT_ALLIES : ChatPrompt.RECIPIENT_ALL;
    }

    /**
     * Attaches the Beacon.Link community to the network game's message area and chat box, under the LAN client's lock
     * ({@link #attachNetworkChat} first).
     *
     * @param chat the community part of the game
     * @throws IllegalStateException if this session has no network client
     */
    public void attachCommunityChat(@NotNull GameCommunityChat chat) {
        LanClient client = this.networkClient;

        if (client == null) {
            throw Exceptions.newIllegalStateException("The community chat needs the network client.");
        }

        synchronized (client) {
            chat.attach();
        }

        this.communityChat = chat;
    }

    /**
     * @return {@code true} if the Beacon.Link community is attached to this session's game, {@code false} otherwise
     */
    public boolean hasCommunityChat() {
        return this.communityChat != null;
    }

    /**
     * Runs a typed community command ({@link GameCommunityChat#isCommand}) in the attached channel chat; without one it does nothing.
     *
     * @param line the typed line
     */
    public void submitCommunityCommand(@NotNull String line) {
        LanClient client = this.networkClient;
        GameCommunityChat chat = this.communityChat;

        if (client == null || chat == null) {
            return;
        }

        synchronized (client) {
            chat.submit(line);
        }
    }

    /**
     * Formats the chosen recipient's player or force name and copies the label into the local interface buffer.
     *
     * @param format the box's format
     */
    public void formatChatLabel(@NotNull String format) {
        String recipient = this.isTeamGame() ? this.getForceDisplayName(this.chatRecipient) : this.getPlayerName(this.chatRecipient);

        this.chatLabel = format.replace(CHAT_LABEL_PLACEHOLDER, recipient);

        byte[] bytes = this.chatLabel.getBytes(StandardCharsets.UTF_8);
        byte[] live = this.state.getInterfaceLink().getLocalInterface().getChatLabel();

        // The buffer is the readable start of the game's longer label buffer: a label that fills it is cut at a byte and keeps no terminator in it.
        Arrays.fill(live, (byte) 0);
        System.arraycopy(bytes, 0, live, 0, Math.min(bytes.length, live.length));
    }

    /**
     * Queues packed alliance and vision commands into the next turn.
     *
     * @param alliances the packed alliances
     * @param vision    the vision mask
     * @throws IllegalStateException if this session has no live game
     */
    public void submitAlliances(int alliances, int vision) {
        LiveGame game = this.requireLiveGame();

        game.submit(ReplayCommandIds.ALLIANCE, ReplayCommandCodecs.encodeValue(ReplayCommandIds.ALLIANCE, alliances));
        game.submit(ReplayCommandIds.VISION, ReplayCommandCodecs.encodeValue(ReplayCommandIds.VISION, vision));
    }

    /**
     * Sends the typed UTF-8 line to the mode's recipients and shows it locally as chat. Ordinary chat is recorded; a whisper is not.
     *
     * @param line the typed line
     * @param mode the chat mode the box was open in
     */
    public void sendNetworkChat(@NotNull String line, int mode) {
        LanClient client = this.networkClient;
        LiveGame game = this.liveGame;

        if (client == null || game == null) {
            return;
        }

        byte[] text = line.getBytes(StandardCharsets.UTF_8);
        int kind = mode == ChatPrompt.MODE_RECIPIENT ? WHISPER_KIND : CHAT_KIND;
        byte[] message = new byte[text.length + CHAT_PACKET_OVERHEAD];

        message[0] = (byte) kind;
        System.arraycopy(text, 0, message, CHAT_TEXT_OFFSET, text.length);

        int local = game.getLocalPlayer();

        synchronized (client) {
            ProviderSession session = client.getSession();

            if (session == null) {
                return;
            }

            // Bit 15 stands for the observers (identifiers 0x80..0x83).
            int mask = this.getRecipientMask(mode);

            if (mask == CHAT_ALL_RECIPIENTS_MASK) {
                session.sendMessage(CHAT_BROADCAST_RECIPIENT, message);
            } else {
                int self = session.getLocalIdentifier();

                for (int playerIdentifier = 0; playerIdentifier < CHAT_PLAYER_RECIPIENT_LIMIT; playerIdentifier++) {
                    if ((mask >> playerIdentifier & 1) != 0 && playerIdentifier != self) {
                        session.sendMessage(playerIdentifier, message);
                    }
                }

                if ((mask & CHAT_OBSERVER_RECIPIENT_MASK) != 0) {
                    for (int playerIdentifier = PlayerTable.FIRST_OBSERVER_SLOT;
                         playerIdentifier < PlayerTable.FIRST_OBSERVER_SLOT + PlayerTable.OBSERVER_SLOTS;
                         playerIdentifier++) {
                        if (playerIdentifier != self) {
                            session.sendMessage(playerIdentifier, message);
                        }
                    }
                }
            }
        }

        LOGGER.info("LAN GAME CHAT SENT: kind " + kind + ", mode " + mode + ", " + text.length + " bytes");

        MessageLines lines = this.state.getInterfaceLink().getMessageLines();

        lines.writeChat(local, text);

        if (kind == WHISPER_KIND) {
            return;
        }

        game.recordChat(local, text, this.state.getCurrentFrame());
    }

    /**
     * Receives chat and whispers with a known sender and a terminated nonempty text within the receive limit, then shows the attached
     * community's whispers and command answers ({@link GameCommunityChat#takeWrites}).
     * Ordinary chat is recorded with bounded text; whispers are displayed without recording.
     */
    public void receiveNetworkChat() {
        LanClient client = this.networkClient;
        LiveGame game = this.liveGame;
        GameCommunityChat community = this.communityChat;

        if (client == null || game == null) {
            return;
        }

        List<ProviderSession.Message> messages = new ArrayList<>();
        List<Consumer<MessageLines>> communityWrites;

        synchronized (client) {
            communityWrites = community == null ? List.of() : community.takeWrites();

            ProviderSession session = client.getSession();

            if (session != null) {
                for (ProviderSession.Message message = session.receiveMessage(); message != null; message = session.receiveMessage()) {
                    messages.add(message);
                }
            }
        }

        MessageLines lines = this.state.getInterfaceLink().getMessageLines();

        for (Consumer<MessageLines> write : communityWrites) {
            write.accept(lines);
        }

        for (ProviderSession.Message message : messages) {
            byte[] data = message.getData();
            // Diagnostics without the text: the kind and X bytes, the length and the sender of every game message.
            String received = "kind " + (data.length > 0 ? Byte.toUnsignedInt(data[0]) : -1) + ", " +
                              "x " + (data.length > 1 ? Byte.toUnsignedInt(data[1]) : -1) + ", " +
                              data.length + " bytes from player " + message.getSender();

            if (data.length < CHAT_TEXT_OFFSET || (data[0] != CHAT_KIND && data[0] != WHISPER_KIND)) {
                LOGGER.info("LAN GAME MESSAGE DROPPED (not chat or whisper): " + received);

                continue;
            }

            int end = CHAT_TEXT_OFFSET;

            while (end < data.length && end - CHAT_TEXT_OFFSET < CHAT_RECEIVE_LIMIT && data[end] != 0) {
                end++;
            }

            int slot = game.findSlot(message.getSender());

            if (end >= data.length || data[end] != 0 || end == CHAT_TEXT_OFFSET || slot < 0) {
                LOGGER.info("LAN GAME MESSAGE DROPPED (" + (slot < 0 ? "unknown sender" : "no terminated text") + "): " + received);

                continue;
            }

            byte[] text = Arrays.copyOfRange(data, CHAT_TEXT_OFFSET, end);

            LOGGER.info("LAN GAME CHAT: " + received + " (slot " + slot + "), text " + text.length + " bytes, shown as " +
                        (data[0] == WHISPER_KIND ? "whisper" : "chat"));

            if (data[0] == WHISPER_KIND) {
                TextTables tables = this.textTables;

                lines.writeWhisper(tables == null ? "" : tables.getNetwork().get(WHISPER_FROM_KEY), slot, text);
            } else {
                lines.writeChat(slot, text);
                game.recordChat(slot, text, this.state.getCurrentFrame());
            }
        }
    }

    /**
     * Leaves an active network game. Closing the session also leaves it.
     */
    public void leaveNetwork() {
        if (this.liveGame != null && this.liveGame.isNetworkGame()) {
            this.liveGame.leaveNetwork();
        }
    }

    /**
     * Requests that the live game drop players whose turns are overdue.
     */
    public void dropWaitingPlayers() {
        if (this.liveGame != null) {
            this.liveGame.dropWaitingPlayers();
        }
    }


    /**
     * Queues a local player's command into the next outgoing turn for execution and recording.
     * Pause and Resume commands remain outside the recorded command stream.
     *
     * @param player  the issuing player index; must be the local player
     * @param opcode  the command type byte
     * @param payload the command bytes after the type byte
     * @return {@code true} if queued, {@code false} otherwise
     * @throws IllegalArgumentException if the player is not the local player
     * @throws IllegalStateException    if this session has no live game
     */
    public boolean submitLocalCommand(int player, int opcode, byte @NotNull [] payload) {
        LiveGame game = this.requireLiveGame();

        if (player != game.getLocalPlayer()) {
            throw Exceptions.newIllegalArgumentException("Only the local player {0} issues commands, not {1}.", game.getLocalPlayer(), player);
        }

        return game.submit(opcode, payload);
    }

    /**
     * Toggles the local cheat setting of the live network game.
     *
     * @return {@code true} if enabled after the toggle, {@code false} otherwise
     * @throws IllegalStateException if this session has no live game
     */
    public boolean toggleLocalCheats() {
        LiveGame game = this.requireLiveGame();

        game.setLocalCheatsAllowed(!game.isLocalCheatsAllowed());

        return game.isLocalCheatsAllowed();
    }

    /**
     * Handles text entered between frames in a live single-player game.
     * Queues recognized cheats or discards other text; text checked as a cheat reseeds the game's C runtime generator from
     * the current time ({@link LiveGame#submitText}).
     *
     * @param text the typed line
     * @return {@link LiveGame#TEXT_CHEAT}, {@link LiveGame#TEXT_CHEAT_UNCHANGED} or {@link LiveGame#TEXT_DISCARDED}
     * @throws IllegalStateException if this session has no live game or has not started
     */
    public int submitChat(@NotNull String text) {
        LiveGame game = this.requireLiveGame();

        if (this.state.getFrame() == null || this.state.getCreepField() == null) {
            throw Exceptions.newIllegalStateException("Chat input requires a started game.");
        }

        return game.submitText(text, this.state.getCheatFlags(), this.state.areCheatsEnabled(), this.state.getRuntimeRandom(), readUnixTime());
    }

    /**
     * Applies the local critter effect between frames. It is not a command and is not recorded.
     *
     * @param poolIndex the clicked critter's unit pool index
     * @return {@code true} if a live critter was killed, {@code false} otherwise
     * @throws IllegalStateException if this session has no live game
     */
    public boolean killCritterFromInterface(int poolIndex) {
        this.requireLiveGame();

        if (this.state.getUnitSystem().getUnitPool() == null || poolIndex <= 0 ||
            poolIndex > this.state.getUnitSystem().getUnitPool().getCapacity()) {
            return false;
        }

        Unit unit = this.state.getUnitSystem().getUnitPool().getUnit(poolIndex);
        int type = unit.getUnitId();
        boolean critter = type == ScrUnitIds.CRITTER_RHYNADON ||
                          type == ScrUnitIds.CRITTER_BENGALAAS ||
                          type >= ScrUnitIds.CRITTER_SCANTID && type <= ScrUnitIds.CRITTER_URSADON;

        if (!this.state.getUnitSystem().getUnitPool().isAllocated(poolIndex) || unit.getSpriteOrNull() == null || !critter) {
            return false;
        }

        this.state.getLocalInterfaceEffects().killCritter(unit, INSTRUCTION_LIMIT);

        return true;
    }

    /**
     * Completes the trigger local player's Center View wait after the camera reaches its target. Call between frames.
     *
     * @throws IllegalStateException if the session has no started game
     */
    public void completeCenterView() {
        if (this.state.getTriggerContext() == null) {
            throw Exceptions.newIllegalStateException("Center View completion requires a started game.");
        }

        this.state.getTriggerContext().completeCenterView();
    }

    /**
     * Completes the trigger local player's transmission wait when the local portrait timer expires.
     * Call between frames; this local input is not recorded.
     *
     * @throws IllegalStateException if the session has no started game
     */
    public void completeTransmission() {
        if (this.state.getTriggerContext() == null) {
            throw Exceptions.newIllegalStateException("Transmission completion requires a started game.");
        }

        this.state.getTriggerContext().completeTransmission();
    }

    /**
     * Copies the current display palette of the game, color cycling applied.
     *
     * @param colors receives {@code 256} packed {@code 0x00BBGGRR} colors
     * @return {@code true} if the colors were copied, {@code false} if the game has no display palette
     * @throws IndexOutOfBoundsException if the array holds fewer than {@code 256} colors
     */
    public boolean copyDisplayPalette(int @NotNull [] colors) {
        PaletteAnimation palette = this.runtime == null ? null : this.runtime.getDisplayPalette();

        if (palette == null) {
            return false;
        }

        for (int index = 0; index < PaletteAnimation.COLOR_COUNT; index++) {
            colors[index] = palette.getColor(index);
        }

        return true;
    }

    /**
     * Advances the runtime palette using the host clock, minimization state and trigger pause state.
     *
     * @param colorCycling whether the palette cycles
     * @param minimized    whether the window is minimized
     * @return the cycled palette ({@code 256} packed {@code 0x00BBGGRR} colors) when it advanced, else {@code null}
     * @throws NullPointerException if an elapsed palette update has no display palette
     */
    public int @Nullable [] updateColorCycle(boolean colorCycling, boolean minimized) {
        ReplayRuntime current = this.runtime;
        WindowsHostTiming timing = this.hostTiming;

        if (current == null || timing == null) {
            return null;
        }

        current.setColorCycling(colorCycling);

        if (!current.updateColorCycle(timing.readHostTicks(), minimized, this.isGamePaused())) {
            return null;
        }

        PaletteAnimation palette = current.getDisplayPalette();

        if (palette == null) {
            throw Exceptions.newNullPointerException();
        }

        int[] colors = new int[PaletteAnimation.COLOR_COUNT];

        for (int index = 0; index < colors.length; index++) {
            colors[index] = palette.getColor(index);
        }

        return colors;
    }

    /**
     * Applies the selected live game speed immediately without sending or recording a command.
     *
     * @param speed the game speed, {@code 0} (Slowest) through {@code 6} (Fastest)
     * @throws IllegalArgumentException if the speed is out of range
     * @throws IllegalStateException    if the speed is valid but this session has no live game
     */
    public void applyGameSpeedSetting(int speed) {
        if (speed < 0 || speed > GameSpeed.FASTEST) {
            throw Exceptions.newIllegalArgumentException("Game speed must be 0 through 6: {0}", speed);
        }

        this.requireLiveGame();
        this.state.setRequestedGameSpeed(speed);
    }

    /**
     * Requests the game speed by queuing a Game Speed command into the live game's turn buffer.
     *
     * @param speed the game speed, {@code 0} (Slowest) through {@code 6} (Fastest)
     * @throws IllegalArgumentException if the speed is out of range
     * @throws IllegalStateException    if the speed is valid but this session has no live game
     */
    public void requestGameSpeed(int speed) {
        if (speed < 0 || speed > GameSpeed.FASTEST) {
            throw Exceptions.newIllegalArgumentException("Game speed must be 0 through 6.");
        }

        this.requireLiveGame().submit(ReplayCommandIds.GAME_SPEED, ReplayCommandCodecs.encodeValue(ReplayCommandIds.GAME_SPEED, speed));
    }

    /**
     * Requests a pause from the live game.
     *
     * @throws IllegalStateException if this session has no live game
     */
    public void requestPause() {
        this.requireLiveGame().requestPause();
    }

    /**
     * Requests a resume from the live game.
     *
     * @throws IllegalStateException if this session has no live game
     */
    public void requestResume() {
        this.requireLiveGame().requestResume();
    }

    /**
     * Queues the live game's selected network latency.
     *
     * @param latency the latency {@code 0} low, {@code 1} high, {@code 2} extra high
     * @throws IllegalStateException if this session has no live game
     */
    public void requestLatency(int latency) {
        this.requireLiveGame().requestLatency(latency);
    }

    /**
     * Takes the executed Latency commands of a network game ({@link LiveGame#takeLatencyNotices}).
     *
     * @return the notices: the sender's slot and the new latency
     */
    public @NotNull List<int[]> takeLatencyNotices() {
        return this.liveGame == null ? List.of() : this.liveGame.takeLatencyNotices();
    }


    /**
     * Queues a save command with the current Unix time, then stores the host tick. The file is written when the command executes.
     *
     * @param name     the name bytes without terminator: the file name without extension
     * @param hostTick the host clock in milliseconds
     * @throws IllegalStateException if this session has no live game
     */
    public void requestSaveGame(byte @NotNull [] name, long hostTick) {
        this.requireLiveGame().requestSaveGame(readUnixTime(), name);
        this.lastSaveTick = hostTick;
    }

    /**
     * Queues a load command with the listed file's time and name.
     *
     * @param file the listed saved game
     * @throws IllegalStateException if this session has no live game
     */
    public void requestLoadGame(@NotNull Path file) {
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        String name = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        int time;

        try {
            time = (int) (Files.getLastModifiedTime(file).toMillis() / MILLISECONDS_PER_SECOND);
        } catch (IOException exception) {
            time = 0;
        }

        this.requireLiveGame().requestLoadGame(time, name.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Takes the saved game an executed Load Game command asked for: the client ends this game and starts that one.
     *
     * @return the saved game file, or {@code null} when none was asked for since the last call
     */
    public @Nullable Path takeRequestedLoad() {
        Path load = this.requestedLoad;

        this.requestedLoad = null;

        return load;
    }

    /**
     * Takes the failure of the last executed Save Game command.
     *
     * @return the {@code network.json} key of the failure, or {@code null} when none occurred since the last call
     */
    public @Nullable String takeSaveFailure() {
        String failure = this.saveFailure;

        this.saveFailure = null;

        return failure;
    }

    /**
     * Writes the current game to a temporary file that replaces its destination ({@link AtomicFileUtils#write}).
     * A name whose bytes do not convert to a file name ({@link FileNameUtils#readFileName}) opens no file and records the save
     * error, as a write failure does.
     *
     * @param name the command's name bytes without terminator: the file name without extension
     * @param time the command's time DWORD
     */
    private void writeSavedGame(byte @NotNull [] name, int time) {
        Path directory = this.saveDirectory;
        ReplayRuntime running = this.runtime;
        LiveGame game = this.liveGame;

        if (directory == null || running == null || game == null || running.getSession() == null) {
            this.saveFailure = GlueSavePopup.SAVE_ERROR_KEY;

            return;
        }

        if (!this.isValidSavedGameName(name)) {
            this.saveFailure = GlueSavePopup.RESERVED_FILE_NAME_KEY;

            return;
        }

        String baseName = FileNameUtils.readFileName(name);

        if (baseName == null) {
            LOGGER.warning("SAVE GAME FAILED: the name " + TerminatedStringUtils.readText(name) + " is no file name");
            this.saveFailure = GlueSavePopup.SAVE_ERROR_KEY;

            return;
        }

        Path target = directory.resolve(baseName + this.getSaveGameKind().getExtension());

        try {
            byte[] data = SaveGameUtils.write(new SaveGameContext(running, game), this.describeSavedGame(name), time).toByteArray();

            AtomicFileUtils.write(target, data);
            LOGGER.info("SAVE GAME: " + target + " (" + data.length + " bytes, frame " + this.state.getCurrentFrame() + ")");
        } catch (IOException | RuntimeException exception) {
            LOGGER.warning("SAVE GAME FAILED: " + target + ": " + exception.getMessage());
            this.saveFailure = GlueSavePopup.SAVE_ERROR_KEY;
        }
    }

    /**
     * Builds the saved game banner from the client version, human names, game type, scenario, map path and EUD mode.
     *
     * @param name the saved game name bytes without terminator
     * @return the banner of the saved game
     * @throws IllegalStateException if this session has no live game
     */
    private @NotNull SaveGameDescription describeSavedGame(byte @NotNull [] name) {
        LiveGame game = this.requireLiveGame();
        MeleeGameSetup setup = game.getSetup();
        List<String> names = game.getReplay().getPlayerNames();
        List<String> humans = new ArrayList<>();

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS && slot < names.size(); slot++) {
            if (game.getPlayerId(slot) >= 0) {
                humans.add(names.get(slot));
            }
        }

        String gameType = SaveGameDescription.CUSTOM_GAME_TYPE;

        try {
            GameTemplates.Template template = GameTemplates.find(GameTemplates.load(this.storage),
                                                                 setup.getTemplate().getTypeId(), setup.getTemplate().getSubTypeId());

            if (template != null && !template.getName().isEmpty()) {
                gameType = template.getName();
            }
        } catch (IOException exception) {
            LOGGER.debug("SAVE GAME: no template names: " + exception.getMessage());
        }

        TriggerContext triggers = this.state.getTriggerContext();
        Path mapPath = setup.getMapPath();

        return new SaveGameDescription(SaveGameDescription.CLIENT_VERSION,
                                       game.isNetworkGame(),
                                       name,
                                       humans,
                                       gameType,
                                       setup.getScenarioName(),
                                       mapPath == null ? "" : mapPath.toString(),
                                       triggers == null ? 0 : triggers.getExtendedDeathsMode());
    }

    /**
     * Writes the live game's recorded commands through its last completed frame to a replay file, through a temporary file
     * that replaces it ({@link AtomicFileUtils#write}).
     * <p>
     * The game thread at the end of the game and the stall watchdog's thread both write the replay: one write runs at a time, and each copies the
     * recorded commands when it runs ({@link LiveGame#writeReplay}), so the last file written holds the newest commands.
     *
     * @param path the destination file
     * @throws IOException           if the file cannot be written
     * @throws IllegalStateException if this session has no live game
     */
    public synchronized void saveReplay(@NotNull Path path) throws IOException {
        LiveGame game = this.requireLiveGame();
        long frames = Math.max(0, this.getLastCompletedFrame());

        AtomicFileUtils.write(path, game.writeReplay(frames, System.currentTimeMillis() / MILLISECONDS_PER_SECOND));
    }


    /**
     * Closes this resource, relinquishing any underlying resources.
     * <p>
     * Closes the session once, leaves an active network game and releases its sound playback.
     * Game data is closed only when this session opened it; borrowed client data remains open.
     */
    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;

            if (this.liveGame != null && this.liveGame.isNetworkGame()) {
                this.liveGame.leaveNetwork();
            }

            LanClient client = this.networkClient;
            GameCommunityChat community = this.communityChat;

            if (client != null && community != null) {
                synchronized (client) {
                    community.detach();
                }

                this.communityChat = null;
            }

            this.selectedPlacement = null;

            if (this.soundPlayback != null) {
                this.soundPlayback.close();
            }

            if (this.ownsData) {
                this.gameData.close();
            }
        }
    }
}
