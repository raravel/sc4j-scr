package org.fogofwarlabs.sc4j.scr.net.beaconlink;

import org.fogofwarlabs.sc4j.beaconlink.client.BeaconLinkClient;
import org.fogofwarlabs.sc4j.beaconlink.peer.PeerSocket;
import org.fogofwarlabs.sc4j.beaconlink.test.client.ClientScenario;
import org.fogofwarlabs.sc4j.beaconlink.test.nat.NatType;
import org.fogofwarlabs.sc4j.engine.mod.identity.ModIdentity;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.FoundRoom;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkParticipant;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.LinkPlayer;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.RoomSettings;
import org.fogofwarlabs.sc4j.engine.net.lobby.JoinRefusal;
import org.fogofwarlabs.sc4j.engine.net.lobby.MapFolders;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandIds;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.replay.GameSourceFile;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplaySession;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.SyncChecksumUtils;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanDiscovery;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanGameInformation;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanRecord;
import org.fogofwarlabs.sc4j.scr.net.lan.LanClient;
import org.fogofwarlabs.sc4j.scr.net.lan.LanGameOptions;
import org.fogofwarlabs.sc4j.scr.net.lan.LanLobby;
import org.fogofwarlabs.sc4j.scr.net.lan.LanMapTransfer;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.fogofwarlabs.sc4j.scr.net.proxy.GameProxySocket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Plays SC:R Storm games of sc4j clients over Beacon.Link on 127.0.0.1 behind emulated NATs: the host's room is the game, every joiner finds
 * it in its game list and joins it, the lobbies reach the game start, every client plays the same commands to a fixed frame with equal unit
 * hashes at every frame, and the host's recorded replay plays back to the same hashes; every participant has one address of the games, the
 * joiners follow the host's member list when a member leaves, the host starts only when every member pair is connected, a private
 * room's host lets in only a joiner with its password, and a joiner that joins again from the lobby leaves the host's lobby at once.
 */
final class BeaconLinkProviderTest {
    private static final @NotNull String GAME_NAME = "s4jbeacongame";
    private static final @NotNull String OTHER_GAME_NAME = "s4jothergame";
    private static final @NotNull String ROOM_PASSWORD = "open sesame";
    private static final int SDK_GAME_TYPE = 3;
    private static final int GAME_SUBTYPE = 2;
    private static final int GAME_SPEED = 6;
    private static final int MAP_WIDTH = 128;
    private static final int MAP_HEIGHT = 96;
    private static final @NotNull String SCENARIO = "staredit\\scenario.chk";
    private static final @NotNull String SHARED_MAP = "(2)Bottleneck.scm";

    private static final int FRAMES = 240;
    private static final int TURNS_IN_TRANSIT = 3;
    private static final int FRAME_MILLISECONDS = 42;
    private static final int VIEWPORT_WIDTH = 640;
    private static final int VIEWPORT_HEIGHT = 480;
    private static final int INSTRUCTION_LIMIT = 100_000;
    private static final int HOST = 0;

    /**
     * The longest real time of each phase: finding the room, joining it, reaching the start, and playing the frames.
     */
    private static final long PHASE_LIMIT_MILLISECONDS = 60_000L;
    /**
     * The longest real time from a joiner's second join until the host's lobby lost the joiner's first player: well below the lobby's
     * timeout ({@link LanLobby#TIMEOUT}).
     */
    private static final long DEPARTURE_LIMIT_MILLISECONDS = 5_000L;
    private static final long POLL_PAUSE_MILLISECONDS = 1L;
    private static final long CLOCK_STEP_MILLISECONDS = 20L;
    private static final int UNREPORTED_STEPS = 150;

    /**
     * The most units of one selection.
     */
    private static final int SELECTION_LIMIT = 12;
    /**
     * The right-click command: x, y, target handle, target unit type, queued flag.
     */
    private static final int RIGHT_CLICK_SIZE = 11;
    private static final int MOVE_FRAME = 60;
    private static final int MOVE_ORIGIN = 1000;
    private static final int MOVE_PLAYER_SPACING = 100;

    /**
     * The shared address space {@code 100.64.0.0/10} of the participants' addresses: the first octet and the top bits of the second.
     */
    private static final int SHARED_ADDRESS_FIRST_OCTET = 100;
    private static final int SHARED_ADDRESS_SECOND_OCTET = 0x40;
    private static final int SHARED_ADDRESS_SECOND_OCTET_MASK = 0xC0;

    /**
     * Holds one client of a game: its Beacon.Link session, its LAN client on the session, and once the game started its live game and
     * the unit hash of every frame.
     */
    private static final class Player {
        private final @NotNull BeaconLinkClient session;
        private final @NotNull LanClient client;
        private final @NotNull List<@NotNull Integer> hashes;

        private @Nullable LiveGame live;
        private @Nullable ReplayRuntime runtime;
        private boolean moved;

        private Player(@NotNull BeaconLinkClient session, @NotNull LanClient client) {
            this.session = session;
            this.client  = client;
            this.hashes  = new ArrayList<>();

            this.live    = null;
            this.runtime = null;
            this.moved   = false;
        }

        private @NotNull LiveGame requireLive() {
            LiveGame started = this.live;

            Assertions.assertNotNull(started);

            return started;
        }

        private @NotNull Game requireGame() {
            ReplayRuntime started = this.runtime;

            Assertions.assertNotNull(started);

            return started.getGame();
        }

        private @NotNull LanLobby requireLobby() {
            LanLobby lobby = this.client.getLobby();

            Assertions.assertNotNull(lobby);

            return lobby;
        }

        /**
         * Selects the local player's workers and orders them to a point.
         *
         * @param targetX the target x in pixels
         * @param targetY the target y in pixels
         */
        private void moveWorkers(int targetX, int targetY) {
            LiveGame started = this.requireLive();
            UnitPool pool = this.requireGame().getUnitSystem().getUnitPool();
            List<Integer> handles = new ArrayList<>();

            Assertions.assertNotNull(pool);

            for (int index = pool.getFirstCreatedIndex(true);
                 index != 0 && handles.size() < SELECTION_LIMIT;
                 index = pool.getNextCreatedIndex(index)) {
                Unit unit = pool.getUnit(index);
                int type = unit.getUnitId();

                if (unit.getOwnerIndex() == started.getLocalPlayer() &&
                    (type == ScrUnitIds.TERRAN_SCV || type == ScrUnitIds.ZERG_DRONE || type == ScrUnitIds.PROTOSS_PROBE)) {
                    handles.add(pool.getHandle(index));
                }
            }

            Assertions.assertFalse(handles.isEmpty(), "the local player has workers");

            ByteBuffer select = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES * handles.size()).order(ByteOrder.LITTLE_ENDIAN);

            select.put((byte) handles.size());

            for (int handle : handles) {
                select.putInt(handle);
            }

            Assertions.assertTrue(started.submit(ReplayCommandIds.SELECT_121, select.array()));

            ByteBuffer order = ByteBuffer.allocate(RIGHT_CLICK_SIZE).order(ByteOrder.LITTLE_ENDIAN);

            order.putShort((short) targetX).putShort((short) targetY).putInt(0).putShort((short) ScrUnitIds.NONE).put((byte) 0);

            Assertions.assertTrue(started.submit(ReplayCommandIds.RIGHT_CLICK_121, order.array()));
        }
    }

    /**
     * Drives the scenario in real time: the protocol clock follows the real time, the nodes and sessions are polled, and every client runs
     * its network once.
     */
    private static final class Driver {
        private final @NotNull ClientScenario link;
        private final @NotNull List<@NotNull Player> players;
        private final long realStart;
        private final long protocolStart;

        private Driver(@NotNull ClientScenario link, @NotNull List<@NotNull Player> players) {
            this.link          = link;
            this.players       = players;
            this.realStart     = System.nanoTime();
            this.protocolStart = link.getClock().getEpochMillis();
        }

        private int getElapsedMilliseconds() {
            return (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - this.realStart);
        }

        private void step() throws IOException {
            long target = this.protocolStart + this.getElapsedMilliseconds();

            if (target > this.link.getClock().getEpochMillis()) {
                this.link.getClock().advance(target - this.link.getClock().getEpochMillis());
            }

            this.link.pollAll();

            for (Player player : this.players) {
                player.client.update();
            }
        }

        private void runUntil(@NotNull BooleanSupplier condition, @NotNull String description) throws IOException, InterruptedException {
            this.runUntil(condition, PHASE_LIMIT_MILLISECONDS, description);
        }

        private void runUntil(@NotNull BooleanSupplier condition, long limitMilliseconds, @NotNull String description) throws IOException, InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limitMilliseconds);

            while (!condition.getAsBoolean()) {
                Assertions.assertTrue(System.nanoTime() < deadline, () -> "not reached in time: " + description);
                this.step();
                Thread.sleep(POLL_PAUSE_MILLISECONDS);
            }
        }
    }

    /**
     * Opens a Beacon.Link session on 127.0.0.1, waits until it registers at the node and opens a provider on it.
     */
    private static @NotNull BeaconLinkProvider openProvider(@NotNull ClientScenario link, @NotNull PeerSocket node, @NotNull ModIdentity identity,
                                                            @NotNull List<@NotNull BeaconLinkProvider> providers) throws IOException {
        BeaconLinkClient session = link.openClient(identity, List.of(ClientScenario.listed(node)));
        BeaconLinkProvider provider = new BeaconLinkProvider(session);

        link.await(() -> session.getObservedAddress() != null, "the session registers");
        providers.add(provider);

        return provider;
    }

    /**
     * Creates the record of a hosted game on a 128x96 map of the SDK game type 3, subtype 2, at the fastest speed.
     */
    private static @NotNull LanRecord createRecord(@NotNull String name) {
        return LanRecord.createBuilder(name, 1L).add("_name", name).add("_type", Integer.toString(SDK_GAME_TYPE))
                        .add("_subtype", Integer.toString(GAME_SUBTYPE)).add("game_speed", Integer.toString(GAME_SPEED))
                        .add("map_name", "Bottleneck").add("map_width", Integer.toString(MAP_WIDTH))
                        .add("map_height", Integer.toString(MAP_HEIGHT)).add("players_current", "1").add("players_max", "4").build();
    }

    /**
     * Opens a provider, lists the host's game and connects to it.
     */
    private static @NotNull BeaconLinkProvider joinProvider(@NotNull ClientScenario link, @NotNull PeerSocket node, @NotNull ModIdentity identity,
                                                            @NotNull List<@NotNull BeaconLinkProvider> providers) throws IOException {
        BeaconLinkProvider joiner = openProvider(link, node, identity, providers);

        runProviders(link, providers, () -> !joiner.getGames().isEmpty(), "the joiner lists the game");
        joiner.openGameSocket(GameProxySocket.GAME_PORT);
        joiner.connect(joiner.getGames().getFirst());
        runProviders(link, providers, () -> joiner.getConnectedGame() != null, "the joiner reaches the host");

        return joiner;
    }

    /**
     * Moves the protocol clock in small steps and updates the providers until a condition holds.
     */
    private static void runProviders(@NotNull ClientScenario link, @NotNull List<@NotNull BeaconLinkProvider> providers,
                                     @NotNull BooleanSupplier condition, @NotNull String description) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PHASE_LIMIT_MILLISECONDS);

        while (!condition.getAsBoolean()) {
            Assertions.assertTrue(System.nanoTime() < deadline, () -> "not reached in time: " + description);
            link.getClock().advance(CLOCK_STEP_MILLISECONDS);
            link.pollAll();

            for (BeaconLinkProvider provider : providers) {
                provider.update();
            }
        }
    }

    /**
     * Opens a Beacon.Link session behind an emulated NAT, waits until it registers at the node and opens a LAN client of the map folders on
     * it.
     */
    private static @NotNull Player openPlayer(@NotNull ClientScenario link, @NotNull PeerSocket node, @NotNull NatType type,
                                              @NotNull ModIdentity identity, @NotNull String name, @NotNull MapFolders mapFolders) throws IOException {
        BeaconLinkClient session = link.openClient(identity, List.of(ClientScenario.listed(node)), link.openNat(type).openEndpoint());

        link.await(() -> session.getObservedAddress() != null, "the session registers");

        return new Player(session, LanClient.open(name, new BeaconLinkProvider(session), mapFolders));
    }

    /**
     * Starts a player's live game from its lobby with the client's turn stream.
     */
    private static void startGame(@NotNull Player player, @NotNull ChkFile scenario, byte @NotNull [] mapData, @NotNull Path mapPath,
                                  byte @NotNull [] mapFile, @NotNull GameData data, int now) throws IOException {
        LanLobby lobby = player.requireLobby();
        MeleeGameSetup setup = lobby.createGameSetup(mapData, GameSpeed.FASTEST, true);
        LanClient client = player.client;

        Assertions.assertNotNull(client.getSession());

        LiveGame live = new LiveGame(setup, lobby.getSeed(), client.getSession().getLocalIdentifier());

        live.attachNetwork(client.createTurnTransport(), TURNS_IN_TRANSIT, false);

        ReplayRuntime runtime = ReplayRuntime.createLiveGame(live.getReplay(), scenario,
                                                             data,
                                                             live.getLocalPlayer(), true,
                                                             VIEWPORT_WIDTH, VIEWPORT_HEIGHT,
                                                             INSTRUCTION_LIMIT);

        runtime.setSourceFile(GameSourceFile.create(mapPath, mapFile));
        live.setSyncSource(runtime.getGame(), () -> 0);
        runtime.start(0, now);

        Assertions.assertNull(runtime.getFailure());

        ReplaySession session = runtime.getSession();

        Assertions.assertNotNull(session);

        live.attachReplaySession(session);
        player.live    = live;
        player.runtime = runtime;
    }

    /**
     * Plays the player's recorded replay back and compares the unit hash of every frame with the live game's.
     */
    private static void playBack(@NotNull Player player, @NotNull GameData data) throws IOException {
        ReplayFile saved = ReplayFile.parse(player.requireLive().writeReplay(FRAMES, 0));
        byte[] savedMapData = saved.getMapData();

        Assertions.assertNotNull(savedMapData);

        ReplayRuntime playback = new ReplayRuntime(saved, ChkFile.parseTolerant(savedMapData),
                                                   data,
                                                   VIEWPORT_WIDTH, VIEWPORT_HEIGHT,
                                                   INSTRUCTION_LIMIT);

        playback.start(0, 0);

        Assertions.assertNull(playback.getFailure());

        List<Integer> hashes = new ArrayList<>();

        for (int frame = 0; frame < FRAMES; frame++) {
            Assertions.assertTrue(playback.step(FRAME_MILLISECONDS, frame * FRAME_MILLISECONDS),
                                  () -> "replay frame failed: " + playback.getFailure());
            hashes.add(SyncChecksumUtils.calculateUnitHash(playback.getGame()));
        }

        Assertions.assertEquals(player.hashes, hashes, "the replay plays back to the live game's hashes");
    }

    @Test
    void get_address_should_give_each_participant_one_address_outside_the_private_networks() {
        byte[] firstKey = new byte[LinkPlayer.KEY_LENGTH];
        byte[] secondKey = new byte[LinkPlayer.KEY_LENGTH];

        secondKey[0] = 1;

        LinkParticipant first = LinkParticipant.of(LinkPlayer.of(firstKey), 1L);
        InetSocketAddress address = BeaconLinkProvider.getAddress(first);

        Assertions.assertEquals(address, BeaconLinkProvider.getAddress(LinkParticipant.of(LinkPlayer.of(firstKey), 1L)));
        Assertions.assertNotEquals(address, BeaconLinkProvider.getAddress(LinkParticipant.of(LinkPlayer.of(secondKey), 1L)));
        Assertions.assertNotEquals(address, BeaconLinkProvider.getAddress(LinkParticipant.of(LinkPlayer.of(firstKey), 2L)),
                                   "another connection of the same account");
        Assertions.assertEquals(SHARED_ADDRESS_FIRST_OCTET, address.getAddress().getAddress()[0]);
        Assertions.assertEquals(SHARED_ADDRESS_SECOND_OCTET, address.getAddress().getAddress()[1] & SHARED_ADDRESS_SECOND_OCTET_MASK);
    }

    @Test
    void joiners_remove_the_member_the_host_no_longer_lists() throws Exception {
        try (ClientScenario link = new ClientScenario()) {
            ModIdentity identity = ClientScenario.createIdentity("1.0.0");
            PeerSocket node = link.openNode(ClientScenario.createNodeConfiguration());
            List<BeaconLinkProvider> providers = new ArrayList<>();
            BeaconLinkProvider host = openProvider(link, node, identity, providers);
            LanRecord record = LanRecord.createBuilder(GAME_NAME, 1L).add("_name", GAME_NAME).add("_type", "2").add("map_name", "Bottleneck")
                                        .add("players_current", "1").add("players_max", "4").build();

            host.setGame(record, host.openGameSocket(GameProxySocket.GAME_PORT).getLocalPort());

            BeaconLinkProvider first = joinProvider(link, node, identity, providers);
            BeaconLinkProvider second = joinProvider(link, node, identity, providers);
            LinkParticipant hostParticipant = host.getSession().getParticipant();
            LinkParticipant secondParticipant = second.getSession().getParticipant();

            runProviders(link, providers, () -> first.getSession().getOverlay().getParticipants().contains(secondParticipant),
                         "the first joiner adds the second from the host's list");
            second.close();
            providers.remove(second);
            runProviders(link, providers, () -> first.getSession().getOverlay().getParticipants().equals(List.of(hostParticipant)),
                         "the first joiner removes the member the host no longer lists");
        }
    }

    @Test
    void searcher_lists_the_room_data_and_the_rooms_of_another_version_apart() throws Exception {
        try (ClientScenario link = new ClientScenario()) {
            PeerSocket node = link.openNode(ClientScenario.createNodeConfiguration());
            List<BeaconLinkProvider> providers = new ArrayList<>();
            BeaconLinkProvider host = openProvider(link, node, ClientScenario.createIdentity("1.0.0"), providers);
            BeaconLinkProvider otherHost = openProvider(link, node, ClientScenario.createIdentity("2.0.0"), providers);
            BeaconLinkProvider searcher = openProvider(link, node, ClientScenario.createIdentity("1.0.0"), providers);

            host.setGame(createRecord(GAME_NAME), host.openGameSocket(GameProxySocket.GAME_PORT).getLocalPort());
            otherHost.setGame(createRecord(OTHER_GAME_NAME), otherHost.openGameSocket(GameProxySocket.GAME_PORT).getLocalPort());
            runProviders(link, providers, () -> searcher.getRooms().size() == 2, "the searcher lists both rooms");

            FoundRoom joinable = searcher.getRooms().getFirst();
            FoundRoom other = searcher.getRooms().getLast();
            ScrRoomData data = ScrRoomData.decode(joinable.getSettings().getModData());
            LanGameInformation listed = searcher.getGames().getFirst().getInformation();
            LanGameInformation hosted = LanGameInformation.of(createRecord(GAME_NAME));

            Assertions.assertEquals(GAME_NAME, joinable.getSettings().getRoomName());
            Assertions.assertNull(joinable.getJoinRefusal());
            Assertions.assertEquals(OTHER_GAME_NAME, other.getSettings().getRoomName());
            Assertions.assertNotNull(other.getJoinRefusal(), "a room of another version");
            Assertions.assertEquals(1, searcher.getGames().size(), "only the joinable room is a game");
            Assertions.assertNotNull(data);
            Assertions.assertNotNull(hosted);
            Assertions.assertEquals(hosted.getGameType(), data.getGameType());
            Assertions.assertEquals(GAME_SUBTYPE, data.getSubtype());
            Assertions.assertEquals(GAME_SPEED, data.getSpeed());
            Assertions.assertEquals(MAP_WIDTH, data.getMapWidth());
            Assertions.assertEquals(MAP_HEIGHT, data.getMapHeight());
            Assertions.assertEquals(hosted.getGameType(), listed.getGameType());
            Assertions.assertEquals(GAME_SUBTYPE, listed.getSubtype());
            Assertions.assertEquals(GAME_SPEED, listed.getGameSpeed());
            Assertions.assertEquals(MAP_WIDTH, listed.getMapWidth());
            Assertions.assertEquals(MAP_HEIGHT, listed.getMapHeight());
            Assertions.assertTrue(host.getRooms().stream().noneMatch(room -> room.getHost().equals(host.getSession().getParticipant())),
                                  "a host does not list its own room");
        }
    }

    @Test
    void private_room_refuses_a_wrong_password_with_its_reason_and_lets_the_password_in() throws Exception {
        try (ClientScenario link = new ClientScenario()) {
            ModIdentity identity = ClientScenario.createIdentity("1.0.0");
            PeerSocket node = link.openNode(ClientScenario.createNodeConfiguration());
            List<BeaconLinkProvider> providers = new ArrayList<>();
            BeaconLinkProvider host = openProvider(link, node, identity, providers);

            host.setPassword(ROOM_PASSWORD);
            host.setGame(createRecord(GAME_NAME), host.openGameSocket(GameProxySocket.GAME_PORT).getLocalPort());

            BeaconLinkProvider guesser = openProvider(link, node, identity, providers);

            runProviders(link, providers, () -> !guesser.getGames().isEmpty(), "the guesser lists the game");

            FoundRoom listed = guesser.getRooms().getFirst();

            Assertions.assertTrue(listed.isPrivate());
            Assertions.assertEquals(identity.getModId(), listed.getModId());
            Assertions.assertEquals(identity.getModVersion().toString(), listed.getModVersion());

            guesser.openGameSocket(GameProxySocket.GAME_PORT);
            guesser.setPassword("open barley");
            guesser.connect(guesser.getGames().getFirst());
            runProviders(link, providers, () -> guesser.getConnectionFailure() != null, "the host refuses the guesser");

            Assertions.assertEquals(ProviderSession.JoinState.REJECTED, guesser.getConnectionFailure());
            Assertions.assertEquals(JoinRefusal.WRONG_PASSWORD, guesser.getJoinRefusal());

            BeaconLinkProvider friend = openProvider(link, node, identity, providers);

            runProviders(link, providers, () -> !friend.getGames().isEmpty(), "the friend lists the game");
            friend.openGameSocket(GameProxySocket.GAME_PORT);
            friend.setPassword(ROOM_PASSWORD);
            friend.connect(friend.getGames().getFirst());
            runProviders(link, providers, () -> friend.getConnectedGame() != null || friend.getConnectionFailure() != null,
                         "the friend reaches the host");

            Assertions.assertNotNull(friend.getConnectedGame(), () -> "the friend is refused: " + friend.getJoinRefusal());
            Assertions.assertNull(friend.getJoinRefusal());
        }
    }

    @Test
    void room_data_should_read_back_and_refuse_another_format() {
        ScrRoomData data = new ScrRoomData(SDK_GAME_TYPE, GAME_SUBTYPE, GAME_SPEED, MAP_WIDTH, MAP_HEIGHT);
        byte[] encoded = data.encode();
        ScrRoomData read = ScrRoomData.decode(encoded);

        Assertions.assertEquals(ScrRoomData.ENCODED_LENGTH, encoded.length);
        Assertions.assertTrue(encoded.length <= RoomSettings.MAXIMUM_MOD_DATA_LENGTH);
        Assertions.assertNotNull(read);
        Assertions.assertEquals(data.toString(), read.toString());

        encoded[0] = (byte) (ScrRoomData.FORMAT + 1);

        Assertions.assertNull(ScrRoomData.decode(encoded));
        Assertions.assertNull(ScrRoomData.decode(new byte[0]));
        Assertions.assertNull(ScrRoomData.decode(new byte[ScrRoomData.ENCODED_LENGTH + 1]));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new ScrRoomData(-1, 0, 0, 0, 0));
    }

    @Test
    void host_is_ready_to_start_only_when_every_member_reported_every_other() throws Exception {
        try (ClientScenario link = new ClientScenario()) {
            ModIdentity identity = ClientScenario.createIdentity("1.0.0");
            PeerSocket node = link.openNode(ClientScenario.createNodeConfiguration());
            List<BeaconLinkProvider> providers = new ArrayList<>();
            BeaconLinkProvider host = openProvider(link, node, identity, providers);
            LanRecord record = LanRecord.createBuilder(GAME_NAME, 1L).add("_name", GAME_NAME).add("_type", "2").add("map_name", "Bottleneck")
                                        .add("players_current", "1").add("players_max", "4").build();

            Assertions.assertTrue(host.isReadyToStart(), "a client that hosts no game");

            host.setGame(record, host.openGameSocket(GameProxySocket.GAME_PORT).getLocalPort());

            BeaconLinkProvider first = joinProvider(link, node, identity, providers);

            runProviders(link, providers, host::isReadyToStart, "the only member is connected");

            BeaconLinkProvider second = joinProvider(link, node, identity, providers);

            // The second member's provider stops before it reports: its channel to the first may open, the host has no word of it.
            providers.remove(second);

            for (int step = 0; step < UNREPORTED_STEPS; step++) {
                link.getClock().advance(CLOCK_STEP_MILLISECONDS);
                link.pollAll();
                host.update();
                first.update();
            }

            Assertions.assertFalse(host.isReadyToStart(), "the second member has not reported");

            providers.add(second);
            runProviders(link, providers, host::isReadyToStart, "every member reported every other");

            Assertions.assertTrue(first.isReadyToStart() && second.isReadyToStart(), "joiners host no game");
            Assertions.assertNull(host.getFailureText());
        }
    }

    @Test
    @RequiresInstallation
    void two_players_behind_nats_play_the_same_storm_game_over_beacon_link() throws Exception {
        this.play("(2)Bottleneck.scm", NatType.PORT_RESTRICTED, new NatType[]{NatType.ADDRESS_RESTRICTED}, false);
    }

    @Test
    @RequiresInstallation
    void three_players_play_the_same_storm_game_with_the_symmetric_pair_through_the_host() throws Exception {
        this.play("(3)Holy Ground.scm", NatType.FULL_CONE, new NatType[]{NatType.SYMMETRIC, NatType.SYMMETRIC}, true);
    }

    /**
     * Two runs of one install on this computer: the host hosts a map of the install's map folder and keeps it open, the other run joins its
     * room and takes the same file from the shared map folder; nothing is downloaded or written.
     */
    @Test
    @RequiresInstallation
    void a_run_of_the_same_install_joins_with_the_map_the_host_has_open(@TempDir @NotNull Path maps) throws Exception {
        Path installed = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, SHARED_MAP);
        Path mapPath = Files.copy(installed, maps.resolve(SHARED_MAP));
        ChkFile scenario = ChkFile.parseTolerant(MpqArchive.open(mapPath).readFile(SCENARIO));
        MapFolders mapFolders = MapFolders.of(List.of(maps), LanMapTransfer.DOWNLOAD_FOLDER);
        List<Player> players = new ArrayList<>();

        try (ClientScenario link = new ClientScenario();
             FileChannel held = FileChannel.open(mapPath, StandardOpenOption.READ)) {
            ModIdentity identity = ClientScenario.createIdentity("1.0.0");
            PeerSocket node = link.openNode(ClientScenario.createNodeConfiguration());
            Driver driver = new Driver(link, players);

            try {
                Player host = openPlayer(link, node, NatType.FULL_CONE, identity, "host", mapFolders);

                players.add(host);
                host.client.host(GAME_NAME, LanClient.HostedMap.read(mapPath, scenario, SHARED_MAP), LanGameOptions.MELEE, GameSpeed.FASTEST);

                Player joiner = openPlayer(link, node, NatType.FULL_CONE, identity, "joiner", mapFolders);
                LanDiscovery.Game[] listed = new LanDiscovery.Game[1];

                players.add(joiner);
                driver.runUntil(() -> {
                    for (LanDiscovery.Game game : joiner.client.getGames()) {
                        if (game.getInformation().getName().equals(GAME_NAME)) {
                            listed[0] = game;
                        }
                    }

                    return listed[0] != null;
                }, "the joiner lists the room");
                joiner.client.join(listed[0]);
                driver.runUntil(() -> {
                    LanLobby lobby = joiner.client.getLobby();

                    return joiner.client.getState() == LanClient.State.LOBBY && lobby != null && lobby.getState() >= LanLobby.STATE_LOBBY;
                }, "the joiner reaches the lobby");

                LanLobby hostLobby = host.requireLobby();
                LanLobby joinerLobby = joiner.requireLobby();

                driver.runUntil(() -> hostLobby.isMapTransferComplete() || joinerLobby.getRefusal() != LanLobby.NO_REFUSAL,
                                "the joiner holds the map");

                Assertions.assertEquals(LanLobby.NO_REFUSAL, joinerLobby.getRefusal(), "the joiner's lobby goes on");
                Assertions.assertTrue(hostLobby.isMapTransferComplete());
                Assertions.assertEquals(mapPath, joiner.client.getMapPath());
                Assertions.assertFalse(Files.exists(maps.resolve(LanMapTransfer.DOWNLOAD_FOLDER)), "nothing is downloaded");
                Assertions.assertTrue(held.isOpen());
            } finally {
                for (Player player : players) {
                    player.client.close();
                }
            }
        }
    }

    /**
     * A joiner joins the room again at once after its first join reached the lobby: the host's lobby loses the first join's player at once
     * (its leave arrives, or its channel's end drops it), and the second join ends in a lobby or a failure instead of a join that never ends.
     */
    @Test
    @RequiresInstallation
    void host_loses_the_first_player_at_once_when_a_joiner_joins_again_from_the_lobby(@TempDir @NotNull Path maps) throws Exception {
        Path installed = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, SHARED_MAP);
        Path mapPath = Files.copy(installed, maps.resolve(SHARED_MAP));
        ChkFile scenario = ChkFile.parseTolerant(MpqArchive.open(mapPath).readFile(SCENARIO));
        MapFolders mapFolders = MapFolders.of(List.of(maps), LanMapTransfer.DOWNLOAD_FOLDER);
        List<Player> players = new ArrayList<>();

        try (ClientScenario link = new ClientScenario()) {
            ModIdentity identity = ClientScenario.createIdentity("1.0.0");
            PeerSocket node = link.openNode(ClientScenario.createNodeConfiguration());
            Driver driver = new Driver(link, players);

            try {
                Player host = openPlayer(link, node, NatType.FULL_CONE, identity, "host", mapFolders);

                players.add(host);
                host.client.host(GAME_NAME, LanClient.HostedMap.read(mapPath, scenario, SHARED_MAP), LanGameOptions.MELEE, GameSpeed.FASTEST);

                Player joiner = openPlayer(link, node, NatType.FULL_CONE, identity, "joiner", mapFolders);
                LanDiscovery.Game[] listed = new LanDiscovery.Game[1];

                players.add(joiner);
                driver.runUntil(() -> {
                    for (LanDiscovery.Game game : joiner.client.getGames()) {
                        if (game.getInformation().getName().equals(GAME_NAME)) {
                            listed[0] = game;
                        }
                    }

                    return listed[0] != null;
                }, "the joiner lists the room");
                joiner.client.join(listed[0]);
                driver.runUntil(() -> {
                    LanLobby lobby = joiner.client.getLobby();

                    return joiner.client.getState() == LanClient.State.LOBBY && lobby != null && lobby.getState() >= LanLobby.STATE_LOBBY;
                }, "the joiner reaches the lobby");

                LanLobby hostLobby = host.requireLobby();
                List<String> departures = new ArrayList<>();

                for (LanLobby.Notice notice = hostLobby.pollNotice(); notice != null; notice = hostLobby.pollNotice()) {
                    Assertions.assertEquals(LanLobby.Notice.JOINED, notice.getKind(), "the host only saw the joiner join");
                }

                joiner.client.join(listed[0]);
                driver.runUntil(() -> {
                    for (LanLobby.Notice notice = hostLobby.pollNotice(); notice != null; notice = hostLobby.pollNotice()) {
                        if (notice.getKind() != LanLobby.Notice.JOINED) {
                            departures.add(notice.getKind() + " " + notice.getName());
                        }
                    }

                    return !departures.isEmpty();
                }, DEPARTURE_LIMIT_MILLISECONDS, "the host's lobby loses the first join's player");
                driver.runUntil(() -> joiner.client.getState() != LanClient.State.JOINING, "the second join ends");

                Assertions.assertEquals(List.of(LanLobby.Notice.LEFT + " joiner"), departures, "the first join's leave reaches the host with its name");
                Assertions.assertTrue(joiner.client.getState() == LanClient.State.LOBBY || joiner.client.getState() == LanClient.State.FAILED,
                                      () -> "the second join ends in " + joiner.client.getState());
            } finally {
                for (Player player : players) {
                    player.client.close();
                }
            }
        }
    }

    /**
     * Plays a melee game of sc4j clients over Beacon.Link on an installed map that every client holds.
     *
     * @param mapName       the installed map's file name
     * @param hostType      the NAT of the host
     * @param joinerTypes   the NATs of the joiners
     * @param relayExpected whether a pair of joiners reaches each other only through the host
     * @throws Exception if the map cannot be read or the game fails
     */
    private void play(@NotNull String mapName, @NotNull NatType hostType, @NotNull NatType @NotNull [] joinerTypes, boolean relayExpected) throws Exception {
        Path mapPath = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, mapName);
        byte[] mapFile = Files.readAllBytes(mapPath);
        byte[] mapData = MpqArchive.open(mapPath).readFile(SCENARIO);
        ChkFile scenario = ChkFile.parseTolerant(mapData);
        MapFolders mapFolders = MapFolders.of(List.of(mapPath.getParent()), LanMapTransfer.DOWNLOAD_FOLDER);
        List<Player> players = new ArrayList<>();

        try (ClientScenario link = new ClientScenario()) {
            ModIdentity identity = ClientScenario.createIdentity("1.0.0");
            PeerSocket node = link.openNode(ClientScenario.createNodeConfiguration());
            Driver driver = new Driver(link, players);

            try {
                Player host = openPlayer(link, node, hostType, identity, "host", mapFolders);

                players.add(host);
                host.client.host(GAME_NAME, LanClient.HostedMap.read(mapPath, scenario, mapName), LanGameOptions.MELEE, GameSpeed.FASTEST);

                for (NatType joinerType : joinerTypes) {
                    Player joiner = openPlayer(link, node, joinerType, identity, "joiner" + players.size(), mapFolders);
                    LanDiscovery.Game[] listed = new LanDiscovery.Game[1];

                    players.add(joiner);
                    driver.runUntil(() -> {
                        for (LanDiscovery.Game game : joiner.client.getGames()) {
                            if (game.getInformation().getName().equals(GAME_NAME)) {
                                listed[0] = game;
                            }
                        }

                        return listed[0] != null;
                    }, "the joiner lists the room");

                    Assertions.assertEquals(BeaconLinkProvider.getAddress(host.session.getParticipant()), listed[0].getAddress());

                    joiner.client.join(listed[0]);
                    driver.runUntil(() -> {
                        LanLobby lobby = joiner.client.getLobby();

                        return joiner.client.getState() == LanClient.State.LOBBY && lobby != null && lobby.getState() >= LanLobby.STATE_LOBBY;
                    }, "the joiner reaches the lobby");
                }

                LanLobby hostLobby = host.requireLobby();

                // Every client knows every other player: the joiners reached each other through the host's player information.
                driver.runUntil(() -> {
                    if (!hostLobby.isMapTransferComplete()) {
                        return false;
                    }

                    for (Player player : players) {
                        for (Player other : players) {
                            if (other != player && player.client.getSession() != null && other.client.getSession() != null &&
                                player.client.getSession().findPeer(other.client.getSession().getLocalIdentifier()) == null) {
                                return false;
                            }
                        }
                    }

                    return true;
                }, "every joiner reported the map and every client knows every player");

                Assertions.assertTrue(hostLobby.startGame());
                driver.runUntil(() -> players.stream().allMatch(player -> player.requireLobby().getState() == LanLobby.STATE_GAME),
                                "every lobby reaches the game");

                for (Player player : players) {
                    Assertions.assertEquals(hostLobby.getSeed(), player.requireLobby().getSeed());
                }

                try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
                    for (Player player : players) {
                        startGame(player, scenario, mapData, mapPath, mapFile, data, driver.getElapsedMilliseconds());
                    }

                    driver.runUntil(() -> {
                        boolean done = true;

                        for (Player player : players) {
                            LiveGame live = player.requireLive();
                            ReplayRuntime runtime = player.runtime;
                            long frame = player.requireGame().getCurrentFrame();

                            Assertions.assertNotNull(runtime);

                            if (frame >= FRAMES) {
                                continue;
                            }

                            done = false;

                            if (frame == MOVE_FRAME && !player.moved) {
                                player.moveWorkers(MOVE_ORIGIN + MOVE_PLAYER_SPACING * live.getLocalPlayer(), MOVE_ORIGIN + (int) frame);

                                player.moved = true;
                            }

                            if (live.exchangeTurn(frame)) {
                                runtime.step(FRAME_MILLISECONDS, driver.getElapsedMilliseconds());

                                Assertions.assertNull(runtime.getFailure(), () -> "frame " + frame + ": " + runtime.getFailure());
                                Assertions.assertEquals(frame + 1, player.requireGame().getCurrentFrame());

                                player.hashes.add(SyncChecksumUtils.calculateUnitHash(player.requireGame()));
                            }
                        }

                        return done;
                    }, "every client plays " + FRAMES + " frames");

                    Player first = players.get(HOST);

                    for (Player player : players) {
                        Assertions.assertEquals(first.hashes, player.hashes, "every client hashes every frame alike");
                        Assertions.assertEquals(0, player.requireLive().getSyncMismatches());
                        Assertions.assertEquals(first.requireLive().getHistory().size(), player.requireLive().getHistory().size());
                    }

                    Assertions.assertEquals(players.size() * 2, first.requireLive().getHistory().size(), "every player's select and order ran");
                    Assertions.assertEquals(relayExpected, host.session.getRelayedCount() > 0, "the host relays for a pair without a direct path");

                    playBack(first, data);
                }
            } finally {
                for (Player player : players) {
                    player.client.close();
                }
            }
        }
    }
}
