package org.fogofwarlabs.sc4j.scr.net.lan;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.engine.net.lobby.MapFolders;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkSection;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.save.SaveGameKind;
import org.fogofwarlabs.sc4j.scr.game.save.ScrSaveFormat;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.trigger.ScrTriggerSystem;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanDiscovery;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanGameInformation;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanPresenceInformation;
import org.fogofwarlabs.sc4j.scr.net.extension.ScrNetworkExtensionUtils;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderPeer;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.fogofwarlabs.sc4j.scr.net.proxy.GameProxySocket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * One LAN multiplayer client: the network it lists, hosts and joins games on ({@link NetworkProvider}: the LAN discovery and UDP on the
 * local network, open from the game list on, or Beacon.Link), and once a game is created or joined the game socket
 * ({@link GameProxySocket}), the provider session ({@link ProviderSession}) and the lobby ({@link LanLobby}), the same on every network.
 * The glue screens drive it with {@link #update()} once per frame; after the lobby's countdown the game takes the session's turn stream
 * over ({@link #createTurnTransport()}).
 */
public final class LanClient implements AutoCloseable {
    /**
     * The departure reason of leaving a lobby (Cancel).
     */
    public static final int LEAVE_REASON = 0x40000001;
    /**
     * The most latency bars of a lobby slot.
     */
    public static final int MAXIMUM_LATENCY_BARS = 5;
    /**
     * Game flags bit {@code 2}: the original unit limits.
     */
    public static final int ORIGINAL_LIMITS_FLAG = 4;
    /**
     * Game flags bit {@code 4}: the map uses EUD triggers.
     */
    public static final int EUD_MAP_FLAG = 0x10;
    /**
     * The network turn rates a host chooses from ({@code net_turn_rate}, turns per second), in the order the create game page lists them.
     */
    public static final int @NotNull [] TURN_RATES = {8, 10, 12, 14, 16, 20, 24};
    /**
     * The network turn rate of a hosted game unless the host chooses another ({@link #setTurnRate}).
     */
    public static final int DEFAULT_TURN_RATE = 24;
    /**
     * The turn rate choice of the dynamic turn rate ({@link #setTurnRate}), which the create game page lists first.
     */
    public static final int DYNAMIC_TURN_RATE = 0;
    /**
     * Game flags bit {@code 5}: the dynamic turn rate.
     */
    public static final int DYNAMIC_TURN_RATE_FLAG = LiveGame.DYNAMIC_TURN_RATE_FLAG;

    private static final @NotNull Logger LOGGER = Logger.of(LanClient.class);

    /**
     * The advertised turn rates and their round-trip latency thresholds in milliseconds.
     */
    private static final int @NotNull [] LATENCY_RATES = {0, 8, 10, 12, 14, 16, 20, 24};
    private static final int @NotNull [] @NotNull [] LATENCY_THRESHOLDS = {
            {187, 250, 400, 625},
            {281, 375, 500, 625},
            {225, 300, 400, 500},
            {188, 250, 333, 417},
            {161, 214, 286, 357},
            {141, 188, 250, 313},
            {113, 150, 200, 250},
            {94, 125, 167, 208}
    };

    /**
     * The size of the client's presence GUID.
     */
    private static final int GUID_SIZE = 16;

    /**
     * The presence revision while browsing, after hosting and after the hosted game ended.
     */
    private static final int PRESENCE_REVISION_BROWSING = 0;
    private static final int PRESENCE_REVISION_HOSTING = 1;
    private static final int PRESENCE_REVISION_WITHDRAWN = 2;

    /**
     * The pause of the network thread between two pumps.
     */
    private static final int NETWORK_INTERVAL_MILLISECONDS = 5;
    /**
     * The time without a turn exchange after which the network thread reports the game idle, and between two reports.
     */
    private static final int IDLE_REPORT_MILLISECONDS = 1000;
    /**
     * The longest wait for the network thread to end.
     */
    private static final int NETWORK_THREAD_JOIN_MILLISECONDS = 1000;
    private static final long NANOSECONDS_PER_MILLISECOND = 1_000_000L;

    /**
     * The message digest algorithm of the advertised map MD5.
     */
    private static final @NotNull String MD5_ALGORITHM = "MD5";

    /**
     * The state of the joined or created game.
     */
    public enum State {
        /**
         * Browsing the game list.
         */
        BROWSING,
        /**
         * Joining a game: waiting for the host's answer.
         */
        JOINING,
        /**
         * In a lobby.
         */
        LOBBY,
        /**
         * The join failed: see {@link #getJoinFailure()}.
         */
        FAILED
    }

    /**
     * The map of a hosted game: the file and the values the game list advertises and the joiners check.
     */
    public static final class HostedMap {
        /**
         * Creates a {@link HostedMap} instance with the map file's advertised values.
         *
         * @param path     the map file
         * @param scenario the parsed scenario
         * @param name     the scenario name
         * @return the created {@link HostedMap} instance
         * @throws IOException if the file cannot be read
         */
        public static @NotNull HostedMap read(@NotNull Path path, @NotNull ChkFile scenario, @NotNull String name) throws IOException {
            byte[] contents = Files.readAllBytes(path);
            long checksum = Integer.toUnsignedLong(LanMapTransfer.calculateChecksum(contents, contents.length));
            String md5;

            try {
                md5 = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance(MD5_ALGORITHM).digest(contents));
            } catch (NoSuchAlgorithmException exception) {
                throw Exceptions.newIOException(exception, "{0}", exception);
            }

            return new HostedMap(path, scenario, contents.length, checksum, md5, name);
        }


        private final @NotNull Path path;
        private final @NotNull ChkFile scenario;
        private final long fileSize;
        private final long crc;
        private final @NotNull String md5;
        private final @NotNull String name;

        /**
         * Creates a {@link HostedMap} instance.
         *
         * @param path     the map file
         * @param scenario the scenario
         * @param fileSize the file size
         * @param crc      the file checksum ({@code map_crc}: the complement of the file's CRC-32)
         * @param md5      the file MD5 in upper case hex ({@code map_md5})
         * @param name     the scenario name
         */
        public HostedMap(@NotNull Path path, @NotNull ChkFile scenario, long fileSize, long crc, @NotNull String md5, @NotNull String name) {
            this.path     = path;
            this.scenario = scenario;
            this.fileSize = fileSize;
            this.crc      = crc;
            this.md5      = md5;
            this.name     = name;
        }

        /**
         * @return the map file
         */
        public @NotNull Path getPath() {
            return this.path;
        }

        /**
         * @return the scenario
         */
        public @NotNull ChkFile getScenario() {
            return this.scenario;
        }

        /**
         * @return the file size
         */
        public long getFileSize() {
            return this.fileSize;
        }

        /**
         * @return the file checksum ({@code map_crc}: the complement of the file's CRC-32)
         */
        public long getCrc() {
            return this.crc;
        }

        /**
         * @return the file MD5 in upper case hex ({@code map_md5})
         */
        public @NotNull String getMd5() {
            return this.md5;
        }

        /**
         * @return the scenario name
         */
        public @NotNull String getName() {
            return this.name;
        }
    }

    /**
     * Creates a {@link LanClient} instance that opens the LAN protocol for a player: the discovery starts and publishes the player's presence.
     *
     * @param playerName the player's name
     * @param mapFolders the map folders a joiner searches for the game's map and downloads a missing map into
     * @return the created {@link LanClient} instance
     * @throws IOException if the multicast socket cannot be opened
     */
    public static @NotNull LanClient open(@NotNull String playerName, @NotNull MapFolders mapFolders) throws IOException {
        return open(playerName, LocalNetworkProvider.open(), mapFolders);
    }

    /**
     * Creates a {@link LanClient} instance for a player on a network, which publishes the player's presence; the client owns the network and
     * closes it.
     *
     * @param playerName the player's name
     * @param network    the network
     * @param mapFolders the map folders a joiner searches for the game's map and downloads a missing map into
     * @return the created {@link LanClient} instance
     */
    public static @NotNull LanClient open(@NotNull String playerName, @NotNull NetworkProvider network, @NotNull MapFolders mapFolders) {
        LanClient client = new LanClient(playerName, network, mapFolders);

        client.network.setPresence(LanPresenceInformation.create(playerName, null, client.guid).toRecord(PRESENCE_REVISION_BROWSING));

        return client;
    }

    /**
     * Returns the latency bars of a player: none without a matching peer or for the local player; one while the player's
     * round trip is unknown (the peer's last keep-alive round trip, valid once a ping went out and its answer came back,
     * {@link ProviderPeer#hasRoundTripTime}); otherwise five less the thresholds of the turn rate that the round trip
     * reaches ({@link #getLatencyBars(int, int)}).
     *
     * @param session  the session, {@code null} for none
     * @param player   the player id, or {@code -1} for a slot without a player
     * @param turnRate the game's network turn rate
     * @return the number of bars, {@code 0}..{@link #MAXIMUM_LATENCY_BARS}
     */
    public static int getLatencyBars(@Nullable ProviderSession session, int player, int turnRate) {
        if (session == null || player < 0 || player == session.getLocalIdentifier()) {
            return 0;
        }

        ProviderPeer peer = session.findPeer(player);

        // No bars without a peer; one without a round trip.
        if (peer == null) {
            return 0;
        }

        if (!peer.hasRoundTripTime()) {
            return 1;
        }

        return getLatencyBars(peer.getRoundTripTime(), turnRate);
    }

    /**
     * Returns the bars of a known round trip: five, one fewer for each threshold of the turn rate that the round trip
     * reaches (unsigned); a rate without thresholds takes the first table (rate {@code 0}).
     *
     * @param roundTrip the round trip in milliseconds
     * @param turnRate  the network turn rate
     * @return the number of bars, {@code 1}..{@link #MAXIMUM_LATENCY_BARS}
     */
    public static int getLatencyBars(int roundTrip, int turnRate) {
        int[] thresholds = LATENCY_THRESHOLDS[0];
        int bars = MAXIMUM_LATENCY_BARS;

        for (int index = 0; index < LATENCY_RATES.length; index++) {
            if (LATENCY_RATES[index] == turnRate) {
                thresholds = LATENCY_THRESHOLDS[index];
            }
        }

        for (int threshold : thresholds) {
            if (Integer.compareUnsigned(roundTrip, threshold) < 0) {
                break;
            }

            bars--;
        }

        return bars;
    }

    /**
     * Returns the low {@code 32} bits of the monotonic nanosecond clock converted to milliseconds.
     *
     * @return the tick in milliseconds
     */
    static int getCurrentTick() {
        return (int) (System.nanoTime() / NANOSECONDS_PER_MILLISECOND);
    }

    /**
     * Checks the map summary's EUD mark: a {@code TRIG} section with a record that passes the EUD scan.
     *
     * @param scenario the map
     * @return {@code true} if a {@code TRIG} section has a record that passes the EUD scan, {@code false} otherwise
     */
    private static boolean hasEudTriggers(@NotNull ChkFile scenario) {
        for (ChkSection section : scenario.getSections(ChkFile.TRIGGER_SECTION)) {
            if (ScrTriggerSystem.usesExtendedDeaths(section.getPayload())) {
                return true;
            }
        }

        return false;
    }

    private static @NotNull String getFileName(@NotNull Path path) {
        Path name = path.getFileName();

        return name == null ? "" : name.toString();
    }


    private final @NotNull String playerName;
    private final @NotNull NetworkProvider network;
    private final byte @NotNull [] guid;

    private @Nullable GameProxySocket socket;
    private @Nullable ProviderSession session;
    private @Nullable LanLobby lobby;
    private @Nullable LanGameAdvertisement advertisement;
    private LanDiscovery.@Nullable Game joinedGame;
    private boolean joinStarted;
    private @NotNull State state;
    private ProviderSession.@Nullable JoinState joinFailure;
    /**
     * The game flags a hosted game is created with ({@link #setGameFlags}).
     */
    private int gameFlags;
    /**
     * The network turn rate a hosted game is created with ({@link #setTurnRate}).
     */
    private int turnRate;

    private @Nullable Path mapPath;
    private final @NotNull MapFolders mapFolders;
    /**
     * The saved game the hosted or joined lobby loads, or {@code null} for a new game.
     */
    private @Nullable LanSavedGame savedGame;
    /**
     * The folder a joiner searches for the saved game an advertisement names ({@link #setSaveDirectory}).
     */
    private @Nullable Path saveDirectory;
    /**
     * The format of the multiplayer saved games of the client's product ({@code mlx} or {@code mlt} files).
     */
    private @NotNull ScrSaveFormat saveFormat;
    /**
     * Whether the last join failed because no saved game of the advertised save id was found.
     */
    private boolean savedGameMissing;

    private @Nullable Thread networkThread;
    private volatile @Nullable LanTurnTransport transport;
    private volatile boolean networkRunning;
    private int packetsIn;
    private int packetsOut;

    private LanClient(@NotNull String playerName, @NotNull NetworkProvider network, @NotNull MapFolders mapFolders) {
        this.playerName = playerName;
        this.network    = network;
        this.guid       = new byte[GUID_SIZE];

        new SecureRandom().nextBytes(this.guid);

        this.socket        = null;
        this.session       = null;
        this.lobby         = null;
        this.advertisement = null;
        this.joinedGame    = null;
        this.joinStarted   = false;
        this.state         = State.BROWSING;
        this.joinFailure   = null;
        this.gameFlags     = 0;
        this.turnRate      = DEFAULT_TURN_RATE;

        this.mapPath          = null;
        this.mapFolders       = mapFolders;
        this.savedGame        = null;
        this.saveDirectory    = null;
        this.saveFormat       = new ScrSaveFormat(SaveGameKind.MULTIPLAYER);
        this.savedGameMissing = false;

        this.networkThread  = null;
        this.transport      = null;
        this.networkRunning = false;
        this.packetsIn      = 0;
        this.packetsOut     = 0;
    }

    /**
     * @return the local player's name
     */
    public @NotNull String getPlayerName() {
        return this.playerName;
    }

    /**
     * @return the network the client lists, hosts and joins games on
     */
    public @NotNull NetworkProvider getNetwork() {
        return this.network;
    }

    /**
     * @return the games on the client's network
     */
    public @NotNull List<LanDiscovery.Game> getGames() {
        return this.network.getGames();
    }

    /**
     * @return the state
     */
    public @NotNull State getState() {
        return this.state;
    }

    /**
     * @return why the join failed ({@link ProviderSession.JoinState#REJECTED} or {@link ProviderSession.JoinState#TIMED_OUT}),
     * or {@code null} without a failed join
     */
    public ProviderSession.@Nullable JoinState getJoinFailure() {
        return this.joinFailure;
    }

    /**
     * @return the lobby, or {@code null} outside a lobby
     */
    public @Nullable LanLobby getLobby() {
        return this.lobby;
    }

    /**
     * @return the session, or {@code null} before a game is created or joined
     */
    public @Nullable ProviderSession getSession() {
        return this.session;
    }

    /**
     * @return the game flags of the hosted game, or of the joined game as its advertisement listed them
     */
    public int getGameFlags() {
        LanGameInformation game = this.getGameInformation();

        return game == null ? 0 : game.getFlags();
    }

    /**
     * Sets the game flags of the next hosted game (bit {@code 0} custom hotkeys disabled, bit {@code 2} the original unit
     * limits), which its advertisement carries.
     *
     * @param gameFlags the flags
     */
    public void setGameFlags(int gameFlags) {
        this.gameFlags = gameFlags;
    }

    /**
     * Sets the network turn rate of the next hosted game ({@code net_turn_rate}): its advertisement and its lobby carry it, every player's game
     * takes it for its lockstep turns. {@link #DYNAMIC_TURN_RATE} hosts the provider's default turns per second
     * ({@link ProviderSession#DEFAULT_TURNS_PER_SECOND}) and adds {@link #DYNAMIC_TURN_RATE_FLAG} to the advertised game flags.
     *
     * @param turnRate the turns per second, one of {@link #TURN_RATES}, or {@link #DYNAMIC_TURN_RATE}
     * @throws IllegalArgumentException if the turn rate is neither one of {@link #TURN_RATES} nor {@link #DYNAMIC_TURN_RATE}
     */
    public void setTurnRate(int turnRate) {
        if (turnRate != DYNAMIC_TURN_RATE && Arrays.stream(TURN_RATES).noneMatch(rate -> rate == turnRate)) {
            throw Exceptions.newIllegalArgumentException("The turn rate {0} is not one of {1}.", turnRate, Arrays.toString(TURN_RATES));
        }

        this.turnRate = turnRate;
    }

    /**
     * @return the turns per second a hosted game advertises: the chosen turn rate, or the provider's default for {@link #DYNAMIC_TURN_RATE}
     */
    private int getHostedTurnRate() {
        return this.turnRate == DYNAMIC_TURN_RATE ? ProviderSession.DEFAULT_TURNS_PER_SECOND : this.turnRate;
    }

    /**
     * @param flags the game flags of a hosted game
     * @return the flags with {@link #DYNAMIC_TURN_RATE_FLAG} when the chosen turn rate is {@link #DYNAMIC_TURN_RATE}
     */
    private int addTurnRateFlag(int flags) {
        return this.turnRate == DYNAMIC_TURN_RATE ? flags | DYNAMIC_TURN_RATE_FLAG : flags;
    }

    /**
     * @return {@code true} if the local player hosts the game, {@code false} otherwise
     */
    public boolean isHost() {
        return this.advertisement != null;
    }

    /**
     * Returns the latency bars of a lobby slot's player in this client's game ({@link #getLatencyBars(ProviderSession, int, int)}).
     *
     * @param player the player id, or {@code -1} for a slot without a player
     * @return the number of bars, {@code 0}..{@link #MAXIMUM_LATENCY_BARS}
     */
    public synchronized int getLatencyBars(int player) {
        LanGameInformation game = this.getGameInformation();

        return getLatencyBars(this.session, player, game == null ? 0 : game.getNetworkTurnRate());
    }

    /**
     * @return the hosted game's advertisement, or for a joiner the listed game's, or {@code null}
     */
    public @Nullable LanGameInformation getGameInformation() {
        LanGameAdvertisement hosted = this.advertisement;

        if (hosted != null) {
            return hosted.getGameInformation();
        }

        return this.joinedGame == null ? null : this.joinedGame.getInformation();
    }

    /**
     * @return {@code true} if the joined or hosted game uses the Use Map Settings template, whose game start opens the
     * ready room, {@code false} otherwise
     */
    public synchronized boolean isUseMapSettings() {
        ProviderSession current = this.session;
        byte[] data = current == null ? new byte[0] : current.getGameData();

        return data.length >= LanGameOptions.SIZE && LanGameOptions.parse(data).isUseMapSettings();
    }

    /**
     * @return the provider packets received so far
     */
    public synchronized int getPacketsIn() {
        return this.packetsIn;
    }

    /**
     * @return the provider packets sent so far
     */
    public synchronized int getPacketsOut() {
        return this.packetsOut;
    }

    /**
     * @return the game's map file: the host's chosen file, the map of a saved game's own file (no map transfer), or the
     * joiner's copy once the lobby's map transfer holds it ({@link LanLobby#getMapFile()}: found in the map
     * folders or downloaded), {@code null} before
     */
    public @Nullable Path getMapPath() {
        if (this.mapPath != null) {
            return this.mapPath;
        }

        LanSavedGame saved = this.savedGame;

        if (saved != null) {
            return saved.getSetup().getMapPath();
        }

        LanLobby current = this.lobby;
        Path file = current == null ? null : current.getMapFile();

        if (file != null) {
            this.mapPath = file;
        }

        return file;
    }

    /**
     * Sets the folder a joiner searches for the saved game an advertisement names and the kind of its files. Takes
     * effect for the next join.
     *
     * @param directory the multiplayer saved game folder, or {@code null} for none (a saved game then cannot be joined)
     * @param kind      the kind of the client's multiplayer saved games
     */
    public void setSaveDirectory(@Nullable Path directory, @NotNull SaveGameKind kind) {
        this.saveDirectory = directory;
        this.saveFormat    = new ScrSaveFormat(kind);
    }

    /**
     * @return the saved game the hosted or joined lobby loads, or {@code null} for a new game
     */
    public @Nullable LanSavedGame getSavedGame() {
        return this.savedGame;
    }

    /**
     * @return {@code true} if the last join failed because no saved game of the advertised save id was found, {@code false} otherwise
     */
    public boolean isSavedGameMissing() {
        return this.savedGameMissing;
    }

    /**
     * @return the game speed of the created or joined game, {@code 0}..{@code 6} (Fastest without one)
     */
    public int getSpeed() {
        LanGameInformation game = this.getGameInformation();

        return game == null ? GameSpeed.FASTEST : game.getGameSpeed();
    }


    /**
     * Creates the lobby table from the map and options, opens the provider game, and publishes the game advertisement and the host's presence.
     *
     * @param gameName the advertised game name
     * @param map      the map
     * @param options  the game options
     * @param speed    the game speed, {@code 0}..{@code 6}
     * @throws IOException if the game port cannot be opened
     */
    public void host(@NotNull String gameName, @NotNull HostedMap map, @NotNull LanGameOptions options, int speed) throws IOException {
        this.host(gameName, map, options, speed, GameProxySocket.GAME_PORT);
    }

    /**
     * Creates a game as host on another UDP port (two clients on one computer; the advertisement names the port).
     *
     * @param gameName the game name
     * @param map      the map
     * @param options  the game options
     * @param speed    the game speed, {@code 0}..{@code 6}
     * @param port     the game port
     * @throws IOException if the game port cannot be opened
     */
    public void host(@NotNull String gameName, @NotNull HostedMap map, @NotNull LanGameOptions options, int speed, int port) throws IOException {
        this.leave();

        LanLobby.Setup setup = LanLobby.Setup.create(map.getScenario(), options,
                                                     this.playerName,
                                                     getFileName(map.getPath()), (int) map.getFileSize(), (int) map.getCrc());
        GameProxySocket opened = this.network.openGameSocket(port);
        ProviderSession created = this.createSession(opened);
        int slots = 0;

        for (int index = 0; index < PlayerTable.PLAYABLE_PLAYER_SLOTS; index++) {
            int type = setup.getRecord(index).getType();

            if (type == LanLobby.TYPE_OPEN || type == PlayerTypes.HUMAN) {
                slots++;
            }
        }

        // Use Map Settings also reserves the template's computer slots.
        int maximumPlayers = slots;

        if (options.isUseMapSettings()) {
            for (int index = 0; index < PlayerTable.PLAYABLE_PLAYER_SLOTS; index++) {
                int type = setup.getTemplateType(index);

                if (type == PlayerTypes.COMPUTER || type == PlayerTypes.EITHER_PREFER_COMPUTER) {
                    maximumPlayers++;
                }
            }
        }

        ChkFile scenario = map.getScenario();
        LanGameStatistics statistics = new LanGameStatistics(0,
                                                             scenario.getWidth(), scenario.getHeight(),
                                                             1, slots,
                                                             speed, LanGameStatistics.HOST_MAP_STATUS,
                                                             options.getTypeId(), options.getSubTypeId(),
                                                             this.getHostedTurnRate(), scenario.getTileset(), false,
                                                             this.playerName, map.getName(),
                                                             0, PlayerTable.OBSERVER_SLOTS, 0);

        // EUD maps advertise both original unit limits and the EUD flag.
        int flags = this.addTurnRateFlag(this.gameFlags);

        if (hasEudTriggers(scenario)) {
            flags |= ORIGINAL_LIMITS_FLAG | EUD_MAP_FLAG;
        }

        created.createGame(gameName, statistics.encode(), "",
                           maximumPlayers, PlayerTable.OBSERVER_SLOTS,
                           0, 0, options.encode(),
                           this.playerName, "");

        this.socket        = opened;
        this.session       = created;
        this.mapPath       = map.getPath();
        this.advertisement = new LanGameAdvertisement(getFileName(map.getPath()), map.getFileSize(), map.getCrc(), map.getMd5(), flags);

        LOGGER.info("LAN HOST: map " + getFileName(map.getPath()) + " (" + map.getFileSize() +
                    " bytes, crc " + Long.toHexString(map.getCrc()) + "), options " +
                    HexFormat.of().formatHex(options.encode()) + ", flags 0x" + Integer.toHexString(flags));
        this.advertise();
        this.network.setPresence(LanPresenceInformation.create(this.playerName, gameName, this.guid).toRecord(PRESENCE_REVISION_HOSTING));

        // The lobby counts its slots, and the advertisement follows them.
        this.lobby = new LanLobby(created, LanClient::getCurrentTick, setup, this.playerName, new byte[LanLobby.PLAYER_BLOCK_SIZE]);
        this.lobby.setMapFolders(this.mapFolders);
        this.lobby.setMapDownloader(ScrNetworkExtensionUtils.createMapDownloader(this, this.lobby));
        this.lobby.setTurnRate(this.getHostedTurnRate());
        this.lobby.shareMap(map.getPath());
        this.advertise();

        this.state = State.LOBBY;
    }

    /**
     * Hosts a multiplayer saved game using its saved lobby and advertised save id. Each machine uses the map in its own
     * saved file; the host does not start a map transfer.
     *
     * @param gameName the advertised game name
     * @param saved    the saved game
     * @param port     the game port
     * @throws IOException if the game port cannot be opened
     */
    public void hostSaved(@NotNull String gameName, @NotNull LanSavedGame saved, int port) throws IOException {
        this.leave();

        MeleeGameSetup setup = saved.getSetup();
        LanGameOptions options = saved.getOptions();
        Path savedMap = setup.getMapPath();
        HostedMap map = savedMap != null && Files.isRegularFile(savedMap) ? HostedMap.read(savedMap, setup.getMap(), setup.getScenarioName()) : null;
        String mapFileName = savedMap == null ? "" : getFileName(savedMap);
        long mapFileSize = map == null ? 0 : map.getFileSize();
        long mapCrc = map == null ? 0 : map.getCrc();
        String mapMd5 = map == null ? "" : map.getMd5();
        int saveGameIdentifier = saved.getInformation().getSaveGameId();
        LanLobby.Setup lobbySetup = saved.createLobbySetup(this.playerName, mapFileName, (int) mapFileSize, (int) mapCrc);
        GameProxySocket opened = this.network.openGameSocket(port);
        ProviderSession created = this.createSession(opened);

        // Saved games reserve every slot that is neither empty nor closed.
        int slots = 0;

        for (int index = 0; index < PlayerTable.PLAYABLE_PLAYER_SLOTS; index++) {
            int type = lobbySetup.getRecord(index).getType();

            if (type != PlayerTypes.NONE && type != PlayerTypes.CLOSED) {
                slots++;
            }
        }

        ChkFile scenario = setup.getMap();
        LanGameStatistics statistics = new LanGameStatistics(saveGameIdentifier,
                                                             scenario.getWidth(), scenario.getHeight(),
                                                             1, slots,
                                                             setup.getSpeed(), LanGameStatistics.HOST_MAP_STATUS,
                                                             options.getTypeId(), options.getSubTypeId(),
                                                             this.getHostedTurnRate(), scenario.getTileset(), false,
                                                             this.playerName, setup.getScenarioName(),
                                                             0, PlayerTable.OBSERVER_SLOTS, 0);
        int flags = this.addTurnRateFlag(this.gameFlags | (setup.isClassicLimits() ? ORIGINAL_LIMITS_FLAG : 0));

        created.createGame(gameName, statistics.encode(), "", slots, PlayerTable.OBSERVER_SLOTS, 0, 0, options.encode(), this.playerName, "");

        this.socket        = opened;
        this.session       = created;
        this.mapPath       = savedMap;
        this.savedGame     = saved;
        this.advertisement = new LanGameAdvertisement(mapFileName, mapFileSize, mapCrc, mapMd5, flags);

        LOGGER.info("LAN HOST SAVED GAME: " + saved.getFile().getFileName() + ", save id 0x" + Integer.toHexString(saveGameIdentifier) +
                    ", options " + HexFormat.of().formatHex(options.encode()));
        this.advertise();
        this.network.setPresence(LanPresenceInformation.create(this.playerName, gameName, this.guid).toRecord(PRESENCE_REVISION_HOSTING));

        // The lobby counts its slots, and the advertisement follows them.
        this.lobby = new LanLobby(created, LanClient::getCurrentTick, lobbySetup, this.playerName, new byte[LanLobby.PLAYER_BLOCK_SIZE]);
        this.lobby.setMapFolders(this.mapFolders);
        this.lobby.setMapDownloader(ScrNetworkExtensionUtils.createMapDownloader(this, this.lobby));
        this.lobby.setTurnRate(this.getHostedTurnRate());
        this.advertise();

        this.state = State.LOBBY;
    }

    /**
     * Joins a listed game; {@link #update()} then follows the join until the lobby opens or the join fails.
     *
     * @param game the listed game
     * @throws IOException if the game port cannot be opened
     */
    public void join(LanDiscovery.@NotNull Game game) throws IOException {
        this.join(game, GameProxySocket.GAME_PORT);
    }

    /**
     * Joins a listed game from another local UDP port (two clients on one computer).
     *
     * @param game the listed game
     * @param port the local game port
     * @throws IOException if the port cannot be opened
     */
    public void join(LanDiscovery.@NotNull Game game, int port) throws IOException {
        this.leave();

        GameProxySocket opened = this.network.openGameSocket(port);
        ProviderSession created = this.createSession(opened);

        // The join request goes out once the network reached the host: at once on the local network.
        this.network.connect(game);

        LanDiscovery.Game connected = this.network.getConnectedGame();

        if (connected != null) {
            created.beginJoin(connected.getAddress(), this.playerName, "", "");
        }

        this.socket           = opened;
        this.session          = created;
        this.joinedGame       = connected == null ? game : connected;
        this.joinStarted      = connected != null;
        this.joinFailure      = null;
        this.savedGameMissing = false;
        this.state            = State.JOINING;
    }

    /**
     * Runs the network once: reads the game socket into the session, updates the session, follows a join, runs the
     * lobby until its game starts, and keeps the hosted game's advertisement following the session's player counts and
     * game statistics.
     */
    public void update() {
        this.pump();

        ProviderSession current = this.session;

        if (current == null) {
            return;
        }

        if (this.state == State.JOINING && !this.joinStarted) {
            LanDiscovery.Game connected = this.network.getConnectedGame();
            ProviderSession.JoinState failure = this.network.getConnectionFailure();

            if (connected != null) {
                current.beginJoin(connected.getAddress(), this.playerName, "", "");

                this.joinedGame  = connected;
                this.joinStarted = true;
            } else if (failure != null) {
                this.joinFailure = failure;
                this.state       = State.FAILED;

                this.closeSession();
            }

            return;
        }

        if (this.state == State.JOINING) {
            ProviderSession.JoinState joinState = current.pollJoin();

            if (joinState == ProviderSession.JoinState.JOINED) {
                // A saved game requires a local file with the advertised id.
                LanGameInformation listed = this.getGameInformation();
                LanSavedGame saved = null;

                if (listed != null && listed.getSaveGameId() != 0) {
                    Path directory = this.saveDirectory;

                    saved = directory == null ? null : LanSavedGame.find(directory, this.saveFormat, (int) listed.getSaveGameId());

                    if (saved == null) {
                        LOGGER.warning("LAN JOIN: no saved game of save id 0x" + Long.toHexString(listed.getSaveGameId()) + " in " + directory);
                        this.leave();

                        this.savedGameMissing = true;
                        this.state            = State.FAILED;

                        return;
                    }
                }

                this.savedGame = saved;
                this.lobby     = new LanLobby(current, LanClient::getCurrentTick, null, this.playerName, new byte[LanLobby.PLAYER_BLOCK_SIZE]);

                if (saved != null) {
                    this.lobby.setSavedGame(saved.getInformation());
                }

                this.lobby.setMapFolders(this.mapFolders);
                this.lobby.setMapDownloader(ScrNetworkExtensionUtils.createMapDownloader(this, this.lobby));

                LanGameInformation joined = this.getGameInformation();

                this.lobby.setTurnRate(joined == null ? 0 : joined.getNetworkTurnRate());

                this.state = State.LOBBY;
            } else if (joinState == ProviderSession.JoinState.REJECTED || joinState == ProviderSession.JoinState.TIMED_OUT) {
                this.joinFailure = joinState;
                this.state       = State.FAILED;

                this.closeSession();
            }

            return;
        }

        LanLobby currentLobby = this.lobby;

        if (currentLobby != null && currentLobby.getState() != LanLobby.STATE_GAME) {
            currentLobby.update();
            this.advertise();

            // Starting the game withdraws its advertisement and clears the lobby presence.
            LanGameAdvertisement hosted = this.advertisement;
            LanGameInformation game = hosted == null ? null : hosted.getGameInformation();

            if (currentLobby.getState() == LanLobby.STATE_GAME && game != null) {
                this.network.withdraw(game.getName());
                this.network.setPresence(LanPresenceInformation.create(this.playerName, null, this.guid).toRecord(PRESENCE_REVISION_WITHDRAWN));
            }
        }
    }

    /**
     * Opens the ready room's turn phase after the lobby's game start ({@link LanLobby#beginBriefing()}).
     */
    public synchronized void beginBriefing() {
        LanLobby current = this.lobby;

        if (current != null) {
            current.beginBriefing();
        }
    }

    /**
     * Presses the ready room's Start ({@link LanLobby#readyBriefing()}).
     */
    public synchronized void readyBriefing() {
        LanLobby current = this.lobby;

        if (current != null) {
            current.readyBriefing();
        }
    }

    /**
     * Runs the network and one turn step of the ready room ({@link LanLobby#updateBriefing()}).
     *
     * @return {@code true} if every player is ready and the game starts, {@code false} otherwise
     */
    public synchronized boolean updateBriefing() {
        this.pump();

        LanLobby current = this.lobby;

        return current != null && current.updateBriefing();
    }

    /**
     * Reads the game socket into the session and updates it: the network pump of the lobby and of the game's turn
     * stream. A player whose connection the network saw end is dropped from the lobby at once, unless the player's leave arrived
     * first.
     */
    public synchronized void pump() {
        this.network.update();

        GameProxySocket opened = this.socket;
        ProviderSession current = this.session;

        if (opened == null || current == null) {
            return;
        }

        opened.poll((source, packet) -> {
            this.packetsIn++;
            current.receive(source, packet);
        });
        current.update();

        for (InetSocketAddress departed = this.network.pollDeparture(); departed != null; departed = this.network.pollDeparture()) {
            this.dropDeparted(current, departed);
        }
    }

    /**
     * Keeps the network running on its own thread every {@value #NETWORK_INTERVAL_MILLISECONDS} milliseconds while the game loads and runs.
     * The session is used under this client's lock, which the turn transport ({@link #createTurnTransport()}) also holds.
     */
    public synchronized void startNetworkThread() {
        if (this.networkThread != null) {
            return;
        }

        this.networkRunning = true;

        Thread thread = new Thread(() -> {
            long lastIdleReport = 0;

            while (this.networkRunning) {
                try {
                    this.pump();

                    LanTurnTransport game = this.transport;
                    long now = System.currentTimeMillis();

                    if (game != null && !game.hasLeft() && game.getLastExchange() != 0 && now - game.getLastExchange() > IDLE_REPORT_MILLISECONDS &&
                        now - lastIdleReport > IDLE_REPORT_MILLISECONDS) {
                        // The game did not ask for turns for a second: no turns go out, the other players wait.
                        LOGGER.info("LAN GAME IDLE: no turn exchange for " + (now - game.getLastExchange()) + " ms, packets in " +
                                    this.getPacketsIn() + " out " + this.getPacketsOut());
                        game.logPendingEvents();

                        lastIdleReport = now;
                    }

                    Thread.sleep(NETWORK_INTERVAL_MILLISECONDS);
                } catch (InterruptedException exception) {
                    return;
                } catch (RuntimeException exception) {
                    LOGGER.warning("LAN NETWORK THREAD: " + exception);
                }
            }
        }, "lan-network");

        thread.setDaemon(true);
        thread.start();

        this.networkThread = thread;
    }

    /**
     * Stops the network thread of {@link #startNetworkThread()}.
     */
    public void stopNetworkThread() {
        Thread thread;

        synchronized (this) {
            thread              = this.networkThread;
            this.networkThread  = null;
            this.networkRunning = false;
        }

        if (thread != null) {
            thread.interrupt();

            try {
                thread.join(NETWORK_THREAD_JOIN_MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Creates a {@link LanTurnTransport} instance that continues the lobby's turn stream in the started game, with this client's pump.
     *
     * @return the created {@link LanTurnTransport} instance
     * @throws IllegalStateException if no game is joined or created
     */
    public @NotNull LanTurnTransport createTurnTransport() {
        ProviderSession current = this.session;

        if (current == null) {
            throw Exceptions.newIllegalStateException("No game is joined or created.");
        }

        LanTurnTransport created = new LanTurnTransport(current, this::pump, this, this);

        this.transport = created;

        return created;
    }

    /**
     * Ends the network state of a game the local player left ({@link LanTurnTransport#leave}): the network thread stops
     * and the session closes; the network stays open. Does nothing for a transport of an earlier game.
     *
     * @param left the transport that left
     */
    public void endGame(@NotNull LanTurnTransport left) {
        if (this.transport != left) {
            return;
        }

        this.stopNetworkThread();

        synchronized (this) {
            this.transport = null;
            this.closeSession();
            this.state = State.BROWSING;
        }

        LOGGER.info("LAN GAME END: the network state of the left game closed");
    }

    /**
     * Leaves the lobby or game (subtype {@code 11} to every player) and withdraws the hosted game; the network stays open.
     */
    public void leave() {
        this.stopNetworkThread();

        LanLobby lobbyLeft = this.lobby;

        if (lobbyLeft != null) {
            lobbyLeft.closeMapTransfer();
        }

        ProviderSession current = this.session;

        if (current != null && current.getLocalIdentifier() != ProviderPeer.UNKNOWN_IDENTIFIER) {
            current.leave(LEAVE_REASON);
        }

        this.pump();
        this.closeSession();

        this.state = State.BROWSING;
    }

    /**
     * {@return a string representation of the object}
     * <p>
     * Satisfying this method's contract implies a non-{@code null}
     * result must be returned.
     *
     * @apiNote In general, the
     * {@code toString} method returns a string that
     * "textually represents" this object. The result should
     * be a concise but informative representation that is easy for a
     * person to read.
     * It is recommended that all subclasses override this method.
     * The string output is not necessarily stable over time or across
     * JVM invocations.
     * @implSpec Returns the client player name and state.
     */
    @Override
    public @NotNull String toString() {
        return String.format(Locale.ROOT, "LanClient[%s, %s]", this.playerName, this.state);
    }

    /**
     * Drops the lobby's player at an address whose connection the network saw end ({@link ProviderSession#dropPlayer}), unless the player
     * left already: the lobby frees the player's slot at its next step instead of after its timeout. The game's turn transport handles a
     * missing player on its own.
     *
     * @param current the session
     * @param address the player's address in the game
     */
    private void dropDeparted(@NotNull ProviderSession current, @NotNull InetSocketAddress address) {
        LanLobby currentLobby = this.lobby;

        if (currentLobby == null || currentLobby.getState() == LanLobby.STATE_GAME) {
            return;
        }

        for (ProviderPeer peer : current.getPeers()) {
            if (address.equals(peer.getAddress()) && peer.getIdentifier() != ProviderPeer.UNKNOWN_IDENTIFIER &&
                (peer.getFlags() & ProviderPeer.FLAG_DEPARTED) == 0) {
                LOGGER.info("LAN LOBBY DROP: player " + peer.getIdentifier() + " at " + address + " lost its connection");
                current.dropPlayer(peer.getIdentifier(), LanLobby.DROP_REASON);

                return;
            }
        }
    }

    /**
     * Creates a {@link ProviderSession} instance that sends through the game socket and counts the sent packets.
     *
     * @param gameSocket the game socket
     * @return the created {@link ProviderSession} instance
     */
    private @NotNull ProviderSession createSession(@NotNull GameProxySocket gameSocket) {
        return new ProviderSession((address, packet) -> {
            this.packetsOut++;
            gameSocket.send(address, packet);
        }, LanClient::getCurrentTick);
    }

    /**
     * Rebuilds the hosted game's advertisement from the session ({@link LanGameAdvertisement#update}) and publishes the
     * record when it changed.
     */
    private void advertise() {
        LanGameAdvertisement hosted = this.advertisement;
        ProviderSession current = this.session;

        if (hosted == null || current == null || !hosted.update(current)) {
            return;
        }

        GameProxySocket opened = this.socket;

        this.network.setGame(hosted.toRecord(), opened == null ? GameProxySocket.GAME_PORT : opened.getLocalPort());
    }


    private void closeSession() {
        LanLobby current = this.lobby;

        if (current != null) {
            current.closeMapDownloader();
        }

        LanGameAdvertisement hosted = this.advertisement;
        LanGameInformation game = hosted == null ? null : hosted.getGameInformation();

        if (game != null) {
            this.network.withdraw(game.getName());
            this.network.setPresence(LanPresenceInformation.create(this.playerName, null, this.guid).toRecord(PRESENCE_REVISION_WITHDRAWN));
        }

        GameProxySocket opened = this.socket;

        if (opened != null) {
            opened.close();
        }

        this.socket        = null;
        this.session       = null;
        this.lobby         = null;
        this.advertisement = null;
        this.joinedGame    = null;
        this.mapPath       = null;
        this.savedGame     = null;
    }


    /**
     * Closes this resource, relinquishing any underlying resources.
     * This method is invoked automatically on objects managed by the
     * {@code try}-with-resources statement.
     *
     * @apiNote While this interface method is declared to throw {@code
     * Exception}, implementers are <em>strongly</em> encouraged to
     * declare concrete implementations of the {@code close} method to
     * throw more specific exceptions, or to throw no exception at all
     * if the close operation cannot fail.
     *
     * <p>
     * Cases where the close operation may fail require careful
     * attention by implementers. It is strongly advised to relinquish
     * the underlying resources and to internally <em>mark</em> the
     * resource as closed, prior to throwing the exception. The {@code
     * close} method is unlikely to be invoked more than once and so
     * this ensures that the resources are released in a timely manner.
     * Furthermore it reduces problems that could arise when the resource
     * wraps, or is wrapped, by another resource.
     *
     * <p>
     * <em>Implementers of this interface are also strongly advised
     * to not have the {@code close} method throw {@link
     * InterruptedException}.</em>
     * <p>
     * This exception interacts with a thread's interrupted status,
     * and runtime misbehavior is likely to occur if an {@code
     * InterruptedException} is {@linkplain Throwable#addSuppressed
     * suppressed}.
     * <p>
     * More generally, if it would cause problems for an
     * exception to be suppressed, the {@code AutoCloseable.close}
     * method should not throw it.
     *
     * <p>
     * Note that unlike the {@link java.io.Closeable#close close}
     * method of {@link java.io.Closeable}, this {@code close} method
     * is <em>not</em> required to be idempotent.  In other words,
     * calling this {@code close} method more than once may have some
     * visible side effect, unlike {@code Closeable.close} which is
     * required to have no effect if called more than once.
     * <p>
     * However, implementers of this interface are strongly encouraged
     * to make their {@code close} methods idempotent.
     * @implSpec Leaves the game and closes the network; a failed leave is logged.
     */
    @Override
    public void close() {
        try {
            this.leave();
        } catch (RuntimeException exception) {
            LOGGER.warning("LAN LEAVE FAILED: " + exception.getMessage());
        }

        this.network.close();
    }
}
