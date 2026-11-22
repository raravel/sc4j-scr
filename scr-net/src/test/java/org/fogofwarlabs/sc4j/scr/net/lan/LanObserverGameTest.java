package org.fogofwarlabs.sc4j.scr.net.lan;

import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplaySession;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.SyncChecksumUtils;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifies that an observer of an in-memory LAN game joins with its identifier, runs the game in step with the host as the
 * first observer slot and leaves while the host's game goes on.
 */
@RequiresInstallation
final class LanObserverGameTest {
    private static final @NotNull String MAP_NAME = "(2)Bottleneck.scm";

    private static final int CLIENTS = 2;
    private static final int HOST = 0;
    private static final int OBSERVER_CLIENT = 1;
    private static final int COMPUTER_SLOT = 1;
    private static final int FIRST_OBSERVER_RECORD = PlayerTable.PLAYABLE_PLAYER_SLOTS;
    private static final int MAXIMUM_PLAYERS = 2;

    private static final int LATENCY = 2;
    private static final int SETTLE_MILLISECONDS = 500;
    /**
     * The time the host needs to move a player to the observers.
     */
    private static final int OBSERVER_MOVE_MILLISECONDS = 1000;
    /**
     * The longest time of the map transfer and of the countdown.
     */
    private static final int MAP_TRANSFER_LIMIT_MILLISECONDS = 20_000;
    private static final int START_LIMIT_MILLISECONDS = 20_000;

    private static final int TURNS_IN_TRANSIT = 3;
    private static final int FRAME_MILLISECONDS = 42;
    private static final int VIEWPORT_WIDTH = 640;
    private static final int VIEWPORT_HEIGHT = 480;
    private static final int INSTRUCTION_LIMIT = 100_000;
    private static final int ITERATION_LIMIT = 100_000;
    private static final int FRAMES = 400;
    private static final int OBSERVER_LEAVE_FRAME = 200;

    private static final int FIRST_OBSERVER_IDENTIFIER = 0x80;
    private static final int EITHER_PREFER_COMPUTER_TYPE = 5;
    private static final int OBSERVER_TYPE = 9;
    private static final int STATE_GAME = 9;
    private static final int NO_COUNTDOWN = -1;

    private static final class Client {
        private final @NotNull LiveGame live;
        private final @NotNull ReplayRuntime runtime;
        private final @NotNull Game game;

        private Client(@NotNull LiveGame live, @NotNull ReplayRuntime runtime) {
            this.live    = live;
            this.runtime = runtime;
            this.game    = runtime.getGame();
        }

        /**
         * Exchanges the turn of the current frame and runs the frame when every player's turn arrived.
         *
         * @param now the network clock
         */
        private void step(int now) {
            if (!this.live.exchangeTurn(this.game.getCurrentFrame())) {
                return;
            }

            this.runtime.step(FRAME_MILLISECONDS, now);

            Assertions.assertNull(this.runtime.getFailure(), () -> "frame " + this.game.getCurrentFrame() + ": " + this.runtime.getFailure());
        }
    }

    /**
     * Starts a client's game from its lobby at the game start.
     *
     * @param network  the in-memory LAN of the started game
     * @param client   the client
     * @param scenario the map's scenario
     * @param mapData  the map's scenario bytes
     * @param data     the installed game data
     * @return the started game of the client
     * @throws Exception if the game cannot be started
     */
    private static @NotNull Client start(@NotNull InMemoryLanScenario network, int client,
                                         @NotNull ChkFile scenario, byte @NotNull [] mapData,
                                         @NotNull GameData data) throws Exception {
        LanLobby lobby = network.lobbies[client];

        Assertions.assertNotNull(lobby);

        LiveGame live = new LiveGame(lobby.createGameSetup(mapData, GameSpeed.FASTEST, true), lobby.getSeed(),
                                     network.sessions[client].getLocalIdentifier());

        live.attachNetwork(new LanTurnTransport(network.sessions[client], network::deliver), TURNS_IN_TRANSIT, false);

        ReplayRuntime runtime = ReplayRuntime.createLiveGame(live.getReplay(), scenario,
                                                             data,
                                                             live.getLocalPlayer(), live.getLocalSlot(),
                                                             true, live.getTeamSlotRaces(),
                                                             VIEWPORT_WIDTH, VIEWPORT_HEIGHT,
                                                             INSTRUCTION_LIMIT);

        live.startSession(runtime.getGame(), runtime.getFrameTiming());
        live.setSyncSource(runtime.getGame(), () -> 0);
        runtime.start(0, network.now);

        Assertions.assertNull(runtime.getFailure());

        ReplaySession session = runtime.getSession();

        Assertions.assertNotNull(session);

        live.attachReplaySession(session);

        return new Client(live, runtime);
    }

    /**
     * Plays the host's game for {@code 400} frames while client {@code 1} plays the same game as an observer and leaves at
     * frame {@code 200}; both games hash alike while the observer plays.
     *
     * @param network          the in-memory LAN of the started game
     * @param scenario         the map's scenario
     * @param mapData          the map's scenario bytes
     * @param installationPath the installed game folder
     * @throws Exception if the game data cannot be loaded
     */
    private static void play(@NotNull InMemoryLanScenario network,
                             @NotNull ChkFile scenario, byte @NotNull [] mapData,
                             @NotNull Path installationPath) throws Exception {
        try (GameData data = GameData.loadFrom(installationPath)) {
            Client host = start(network, HOST, scenario, mapData, data);
            Client observer = start(network, OBSERVER_CLIENT, scenario, mapData, data);
            Game game = host.game;
            boolean observerLeft = false;

            Assertions.assertEquals(PlayerTable.FIRST_OBSERVER_SLOT, observer.live.getLocalPlayer(), "the observer plays the first observer slot");
            Assertions.assertEquals(PlayerTable.FIRST_OBSERVER_SLOT, observer.live.getLocalSlot());
            Assertions.assertTrue(observer.game.getLocalVision().isObserver(), "the observer's game has an observer's view");

            for (int iteration = 0; iteration < ITERATION_LIMIT && game.getCurrentFrame() < FRAMES; iteration++) {
                network.now++;

                if (!observerLeft && observer.game.getCurrentFrame() == OBSERVER_LEAVE_FRAME) {
                    network.sessions[OBSERVER_CLIENT].leave(LiveGame.LEAVE_REASON);
                    observerLeft = true;
                }

                if (!observerLeft) {
                    observer.step(network.now);
                }

                host.step(network.now);

                if (!observerLeft && observer.game.getCurrentFrame() == game.getCurrentFrame()) {
                    Assertions.assertEquals(SyncChecksumUtils.calculateUnitHash(game), SyncChecksumUtils.calculateUnitHash(observer.game),
                                            "unit hash at frame " + game.getCurrentFrame());
                }
            }

            Assertions.assertEquals(OBSERVER_LEAVE_FRAME, observer.game.getCurrentFrame(), "the observer's game ran until it left");
            Assertions.assertEquals(0, observer.live.getSyncMismatches());
            Assertions.assertEquals(FRAMES, game.getCurrentFrame(), "the host's game ran on with the observer and after it left");
            Assertions.assertEquals(NO_COUNTDOWN, host.live.getWaitCountdown(), "no waiting for players");
            Assertions.assertEquals(0, host.live.getSyncMismatches());
        }
    }


    @Test
    void observer_joins_with_an_observer_identifier_watches_and_leaves() throws Exception {
        Path mapPath = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, MAP_NAME);

        byte[] mapFile = Files.readAllBytes(mapPath);
        byte[] mapData = MpqArchive.open(mapPath).readFile("staredit\\scenario.chk");
        ChkFile scenario = ChkFile.parseTolerant(mapData);
        LanGameOptions options = LanGameOptions.MELEE;
        LanLobby.Setup setup = LanLobby.Setup.create(scenario, options,
                                                     "host",
                                                     MAP_NAME,
                                                     mapFile.length, LanMapTransfer.calculateChecksum(mapFile, mapFile.length));
        InMemoryLanScenario network = new InMemoryLanScenario(CLIENTS);

        network.latency = LATENCY;
        network.host(setup, options.encode(), mapPath, MAXIMUM_PLAYERS, PlayerTable.OBSERVER_SLOTS);

        LanLobby host = network.lobbies[HOST];

        Assertions.assertNotNull(host);
        Assertions.assertTrue(host.setSlotState(COMPUTER_SLOT, LanLobby.SLOT_COMPUTER));

        network.run(SETTLE_MILLISECONDS);

        Assertions.assertTrue(host.setSlotState(FIRST_OBSERVER_RECORD, LanLobby.SLOT_OPEN));

        network.run(SETTLE_MILLISECONDS);

        Assertions.assertEquals(EITHER_PREFER_COMPUTER_TYPE, host.getRecord(COMPUTER_SLOT).getType());

        network.join(OBSERVER_CLIENT, mapPath.getParent(), null);

        Assertions.assertEquals(FIRST_OBSERVER_IDENTIFIER, network.sessions[OBSERVER_CLIENT].getLocalIdentifier(),
                                "Storm gives the joiner the first observer identifier");
        Assertions.assertEquals(FIRST_OBSERVER_IDENTIFIER, host.getRecord(FIRST_OBSERVER_RECORD).getPlayerId());
        Assertions.assertEquals(OBSERVER_TYPE, host.getRecord(FIRST_OBSERVER_RECORD).getType());

        network.runUntil(MAP_TRANSFER_LIMIT_MILLISECONDS, lan -> {
            LanLobby hostLobby = lan.lobbies[HOST];

            Assertions.assertNotNull(hostLobby);

            return hostLobby.isMapTransferComplete();
        });
        network.run(SETTLE_MILLISECONDS);

        Assertions.assertTrue(host.startGame());

        network.runUntil(START_LIMIT_MILLISECONDS, lan -> {
            LanLobby hostLobby = lan.lobbies[HOST];

            Assertions.assertNotNull(hostLobby);

            if (hostLobby.getState() != STATE_GAME) {
                return false;
            }

            LanLobby activeObserverLobby = lan.lobbies[OBSERVER_CLIENT];

            Assertions.assertNotNull(activeObserverLobby);

            return activeObserverLobby.getState() == STATE_GAME;
        });

        LanLobby observerLobby = network.lobbies[OBSERVER_CLIENT];

        Assertions.assertNotNull(observerLobby);
        Assertions.assertEquals(STATE_GAME, observerLobby.getState(), "the observer's lobby reached the game");

        play(network, scenario, mapData, ExternalInput.INSTALLATION.getPath());
    }

    /**
     * Checks that a player who moved from its slot to the observers keeps its player identifier ({@code 1}): its departure is recorded as its Leave
     * Game command, whose issuer matches no player record, so the command is not dispatched and the game goes on.
     */
    @Test
    void player_moved_to_the_observers_leaves_without_stopping_the_game() throws Exception {
        Path mapPath = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, MAP_NAME);

        byte[] mapFile = Files.readAllBytes(mapPath);
        byte[] mapData = MpqArchive.open(mapPath).readFile("staredit\\scenario.chk");
        ChkFile scenario = ChkFile.parseTolerant(mapData);
        LanGameOptions options = LanGameOptions.MELEE;
        LanLobby.Setup setup = LanLobby.Setup.create(scenario, options,
                                                     "host",
                                                     MAP_NAME,
                                                     mapFile.length, LanMapTransfer.calculateChecksum(mapFile, mapFile.length));
        InMemoryLanScenario network = new InMemoryLanScenario(CLIENTS);
        int joinerPlayer = 1;

        network.latency = LATENCY;
        network.host(setup, options.encode(), mapPath, MAXIMUM_PLAYERS, PlayerTable.OBSERVER_SLOTS);

        LanLobby host = network.lobbies[HOST];

        Assertions.assertNotNull(host);
        Assertions.assertTrue(host.setSlotState(FIRST_OBSERVER_RECORD, LanLobby.SLOT_OPEN));

        network.run(SETTLE_MILLISECONDS);
        network.join(OBSERVER_CLIENT, mapPath.getParent(), null);

        Assertions.assertEquals(joinerPlayer, network.sessions[OBSERVER_CLIENT].getLocalIdentifier());

        LanLobby observerLobby = network.lobbies[OBSERVER_CLIENT];

        Assertions.assertNotNull(observerLobby);

        observerLobby.requestObserver();
        network.run(OBSERVER_MOVE_MILLISECONDS);

        Assertions.assertEquals(joinerPlayer, host.getRecord(FIRST_OBSERVER_RECORD).getPlayerId(), "the player moved to the first observer slot");
        Assertions.assertTrue(host.setSlotState(COMPUTER_SLOT, LanLobby.SLOT_COMPUTER));

        network.run(SETTLE_MILLISECONDS);
        network.runUntil(MAP_TRANSFER_LIMIT_MILLISECONDS, lan -> {
            LanLobby hostLobby = lan.lobbies[HOST];

            Assertions.assertNotNull(hostLobby);

            return hostLobby.isMapTransferComplete();
        });

        Assertions.assertTrue(host.startGame());

        network.runUntil(START_LIMIT_MILLISECONDS, lan -> {
            LanLobby hostLobby = lan.lobbies[HOST];

            Assertions.assertNotNull(hostLobby);

            if (hostLobby.getState() != STATE_GAME) {
                return false;
            }

            LanLobby activeObserverLobby = lan.lobbies[OBSERVER_CLIENT];

            Assertions.assertNotNull(activeObserverLobby);

            return activeObserverLobby.getState() == STATE_GAME;
        });

        LanLobby startedObserverLobby = network.lobbies[OBSERVER_CLIENT];

        Assertions.assertNotNull(startedObserverLobby);
        Assertions.assertEquals(STATE_GAME, startedObserverLobby.getState());

        play(network, scenario, mapData, ExternalInput.INSTALLATION.getPath());
    }
}
