package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.engine.lockstep.LockstepTransport;
import org.fogofwarlabs.sc4j.engine.lockstep.PeerTurn;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandIds;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.NetworkPeerUtils;
import org.fogofwarlabs.sc4j.scr.game.session.setup.GameTemplate;
import org.fogofwarlabs.sc4j.scr.game.session.setup.LobbyResolution;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks that a client joined as an observer of a network melee game starts and runs the game with the observer's view, sends turns without
 * Sync commands and does not check the players' Sync commands.
 */
@RequiresInstallation
final class ObserverSessionTest {
    private static final @NotNull String MAP_FILE = "(2)Bottleneck.scm";
    private static final @NotNull String HOST_NAME = "host";
    private static final @NotNull String OBSERVER_NAME = "observer";

    private static final int HOST_PLAYER = 0;
    private static final int HOST_SLOT = 0;
    private static final int COMPUTER_SLOT = 1;
    private static final int OBSERVER_PLAYER = PlayerTable.FIRST_OBSERVER_SLOT;
    private static final int MELEE_FORCE = 1;
    private static final int GAME_SEED = 0x1234;

    private static final int TURNS_IN_TRANSIT = 3;
    private static final int FRAMES = 300;
    private static final int STEP_LIMIT = 10_000;

    private static final LobbyResolution.@NotNull Options MELEE = new LobbyResolution.Options(true, false, 0, true, false);

    /**
     * The turns of a game of the host and the observer: the host's turns are Keep Alive, the observer's the turns its game sends.
     */
    private static final class ObserverTransport implements LockstepTransport {
        private final @NotNull List<byte[]> sent;
        private int received;

        /**
         * Creates an {@link ObserverTransport} instance without turns.
         */
        private ObserverTransport() {
            this.sent     = new ArrayList<>();
            this.received = 0;
        }

        /**
         * @return the observer's turns sent and not yet received back
         */
        @Override
        public int getTurnsInTransit() {
            return this.sent.size() - this.received;
        }

        /**
         * Sends the observer's next turn.
         *
         * @param turn the turn bytes
         */
        @Override
        public void sendTurn(byte @NotNull [] turn) {
            this.sent.add(turn);
        }

        /**
         * Takes the observer's next turn with the host's.
         *
         * @return the turns in execution order, or {@code null} while the observer sent none
         */
        @Override
        public @Nullable List<@NotNull PeerTurn> receiveTurns() {
            if (this.received >= this.sent.size()) {
                return null;
            }

            byte[] turn = this.sent.get(this.received++);

            return List.of(new PeerTurn(NetworkPeerUtils.getPeer(OBSERVER_PLAYER), turn),
                           new PeerTurn(NetworkPeerUtils.getPeer(HOST_PLAYER), new byte[]{ReplayCommandIds.KEEP_ALIVE}));
        }
    }

    @Test
    void observer_starts_and_runs_a_network_game_with_an_observer_view() throws IOException {
        Path gameDirectory = ExternalInput.INSTALLATION.getPath();
        Path mapPath = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, MAP_FILE);
        byte[] mapData = MpqArchive.open(mapPath).readFile(ChkFile.ARCHIVE_ENTRY);
        MeleeGameSetup setup = MeleeGameSetup.createNetwork(mapData, HOST_NAME, createTable(), MELEE, GameTemplate.MELEE, GameSpeed.FASTEST, true);
        ObserverTransport transport = new ObserverTransport();

        try (CascStorage storage = CascStorage.open(gameDirectory); GameData gameData = GameData.load(storage);
             ClientSession session = ClientSession.startNetwork(gameDirectory, storage, gameData, mapPath, setup, GAME_SEED, OBSERVER_PLAYER,
                                                                transport, TURNS_IN_TRANSIT, false, null)) {
            LiveGame live = session.getLiveGame();

            Assertions.assertNotNull(live);
            Assertions.assertTrue(live.isLocalObserver());
            Assertions.assertEquals(PlayerTable.FIRST_OBSERVER_SLOT, live.getLocalPlayer());

            session.getCamera().setViewportSize(RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT);
            session.startReplay();

            Assertions.assertTrue(session.canStep(), session.getStatus());
            Assertions.assertTrue(session.getGame().getLocalVision().isObserver(), "the game has an observer's view");

            for (int step = 0; step < STEP_LIMIT && session.getGame().getCurrentFrame() < FRAMES; step++) {
                session.stepFrame(false);

                Assertions.assertTrue(session.canStep(), session.getStatus());
            }

            Assertions.assertEquals(FRAMES, session.getGame().getCurrentFrame());
            Assertions.assertEquals(0, live.getSyncMismatches());

            // The game start's turn carries Sync record 1; the observer's later turns carry no Sync command.
            for (int index = 1; index < transport.sent.size(); index++) {
                Assertions.assertArrayEquals(new byte[]{ReplayCommandIds.KEEP_ALIVE}, transport.sent.get(index), "turn " + index);
            }
        }
    }

    /**
     * Creates a {@link LobbyResolution.Table} instance of a melee game of the host in slot {@code 0}, a computer in slot {@code 1} and an
     * observer in the first observer record; the other slots are closed.
     *
     * @return the created {@link LobbyResolution.Table} instance
     */
    private static LobbyResolution.@NotNull Table createTable() {
        LobbyResolution.Table table = new LobbyResolution.Table();

        for (int slot = 0; slot < PlayerTable.PLAYABLE_PLAYER_SLOTS; slot++) {
            table.type[slot]         = LobbyResolution.TYPE_CLOSED;
            table.templateType[slot] = LobbyResolution.TYPE_OPEN;
            table.savedType[slot]    = LobbyResolution.TYPE_OPEN;
            table.race[slot]         = ScrRaceIds.TERRAN;
            table.templateRace[slot] = ScrRaceIds.TERRAN;
            table.team[slot]         = MELEE_FORCE;
            table.templateTeam[slot] = MELEE_FORCE;
            table.lobbyColor[slot]   = slot;
        }

        table.playerId[HOST_SLOT] = HOST_PLAYER;
        table.type[HOST_SLOT]     = MeleeGameSetup.TYPE_HUMAN;
        table.name[HOST_SLOT]     = HOST_NAME;
        table.type[COMPUTER_SLOT] = MeleeGameSetup.TYPE_COMPUTER;

        table.observerId[0]   = OBSERVER_PLAYER;
        table.observerName[0] = OBSERVER_NAME;

        return table;
    }
}
