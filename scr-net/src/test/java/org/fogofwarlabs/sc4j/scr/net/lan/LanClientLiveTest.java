package org.fogofwarlabs.sc4j.scr.net.lan;

import org.fogofwarlabs.sc4j.engine.net.lobby.MapFolders;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanDiscovery;
import org.fogofwarlabs.sc4j.scr.net.discovery.LanGameInformation;
import org.fogofwarlabs.sc4j.scr.net.provider.ProviderSession;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Checks {@link LanClient}s on this computer over the real network stack, from the game list through the map download to
 * the first frames, and the game flags and slot counts a host alone advertises.
 */
@RequiresInstallation
final class LanClientLiveTest {
    private static final @NotNull String GAME_NAME = "s4jtestgame";
    private static final @NotNull String MELEE_MAP = "(2)Bottleneck.scm";
    private static final @NotNull String SCENARIO = "staredit\\scenario.chk";
    /**
     * The map folders of a host, which hosts a map by its path and downloads none.
     */
    private static final @NotNull MapFolders NO_MAP_FOLDERS = MapFolders.of(List.of(), LanMapTransfer.DOWNLOAD_FOLDER);

    private static final int HOST_PLAYER = 0;
    private static final int JOINER_PLAYER = 1;

    private static final int TURNS_IN_TRANSIT = 3;
    /**
     * The turn rate the host chooses instead of the default {@link LanClient#DEFAULT_TURN_RATE}.
     */
    private static final int HOST_TURN_RATE = 12;
    private static final int FRAMES = 20;

    private static final long LIMIT_MILLISECONDS = 30_000;
    private static final long DISCOVERY_POLL_MILLISECONDS = 10;
    private static final long LOBBY_POLL_MILLISECONDS = 5;
    private static final long FRAME_POLL_MILLISECONDS = 1;

    private static final int COMPLETE_PERCENT = 100;

    private static final int STATE_GAME = 9;

    private static final int ORIGINAL_LIMITS_FLAG = 4;
    private static final int EUD_MAP_FLAG = 0x10;
    private static final int DYNAMIC_TURN_RATE_FLAG = 0x20;

    private static final int CLOSED_TYPE = 8;
    /**
     * The advertised observer limit of a hosted lobby, whose observer slots are all closed.
     */
    private static final int OBSERVER_LIMIT = 0;

    /**
     * Runs a host and a joiner over multicast discovery and two free UDP game ports ({@link LanTestPortUtils}): the host
     * creates a Top vs Bottom game on the installed {@code (2)Bottleneck.scm}, the joiner finds it in the game list, joins
     * without the map and downloads it from the host (map transfer {@code 0x4f}) into its download folder, the host starts
     * once the transfer is complete, both reach the game start with the same seed, and both games exchange their first
     * frames' turns at the turn rate the host chose, which its advertisement and both lobbies carry. Fails without the installed
     * game; skipped when the network does not deliver the discovery within the time limit.
     */
    @Test
    void host_and_joiner_download_the_map_and_play_their_first_frames(@TempDir @NotNull Path joinerMaps) throws Exception {
        Path mapPath = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, MELEE_MAP);

        byte[] scenario = MpqArchive.open(mapPath).readFile(SCENARIO);
        ChkFile map = ChkFile.parseTolerant(scenario);

        try (LanClient host = LanClient.open("hostplayer", NO_MAP_FOLDERS);
             LanClient joiner = LanClient.open("joinplayer", MapFolders.of(List.of(joinerMaps), LanMapTransfer.DOWNLOAD_FOLDER))) {
            host.setTurnRate(HOST_TURN_RATE);

            int hostPort = LanTestPortUtils.bindFreePort(port -> host.host(GAME_NAME, LanClient.HostedMap.read(mapPath, map, "Bottleneck"),
                                                                           LanGameOptions.TOP_VS_BOTTOM_1, GameSpeed.FASTEST, port));
            long deadline = System.currentTimeMillis() + LIMIT_MILLISECONDS;
            LanDiscovery.Game listed = null;

            while (listed == null && System.currentTimeMillis() < deadline) {
                host.update();

                for (LanDiscovery.Game game : joiner.getGames()) {
                    // A game of the same name on another port is the game of another test run on this computer.
                    if (game.getInformation().getName().equals(GAME_NAME) && game.getAddress().getPort() == hostPort) {
                        listed = game;
                    }
                }

                Thread.sleep(DISCOVERY_POLL_MILLISECONDS);
            }

            Assumptions.assumeTrue(listed != null, "The discovery did not see the game in time.");

            LanDiscovery.Game hostedGame = listed;

            Assertions.assertEquals(HOST_TURN_RATE, hostedGame.getInformation().getNetworkTurnRate(), "the advertisement carries the turn rate");

            LanTestPortUtils.bindFreePort(port -> joiner.join(hostedGame, port));

            while (joiner.getState() == LanClient.State.JOINING && System.currentTimeMillis() < deadline) {
                host.update();
                joiner.update();
                Thread.sleep(LOBBY_POLL_MILLISECONDS);
            }

            Assertions.assertEquals(LanClient.State.LOBBY, joiner.getState());

            LanLobby hostLobby = host.getLobby();
            LanLobby joinerLobby = joiner.getLobby();

            Assertions.assertNotNull(hostLobby);
            Assertions.assertNotNull(joinerLobby);

            while (joinerLobby.getPlayerSlot(JOINER_PLAYER) < 0 && System.currentTimeMillis() < deadline) {
                host.update();
                joiner.update();
                Thread.sleep(LOBBY_POLL_MILLISECONDS);
            }

            Assertions.assertEquals("joinplayer", hostLobby.getRecord(hostLobby.getPlayerSlot(JOINER_PLAYER)).getName());

            while (!hostLobby.isMapTransferComplete() && System.currentTimeMillis() < deadline) {
                host.update();
                joiner.update();
                Thread.sleep(LOBBY_POLL_MILLISECONDS);
            }

            Assertions.assertTrue(hostLobby.isMapTransferComplete(), "the joiner downloaded the map");
            Assertions.assertTrue(hostLobby.startGame());

            while ((hostLobby.getState() != STATE_GAME || joinerLobby.getState() != STATE_GAME) && System.currentTimeMillis() < deadline) {
                host.update();
                joiner.update();
                Thread.sleep(LOBBY_POLL_MILLISECONDS);
            }

            Assertions.assertEquals(STATE_GAME, joinerLobby.getState());
            Assertions.assertEquals(hostLobby.getSeed(), joinerLobby.getSeed());
            Assertions.assertEquals(HOST_TURN_RATE, hostLobby.getTurnRate());
            Assertions.assertEquals(HOST_TURN_RATE, joinerLobby.getTurnRate());

            Path found = joiner.getMapPath();

            Assertions.assertEquals(joinerMaps.resolve("Download").resolve(MELEE_MAP), found);
            Assertions.assertNotNull(found);
            Assertions.assertArrayEquals(Files.readAllBytes(mapPath), Files.readAllBytes(found));
            Assertions.assertEquals(COMPLETE_PERCENT, hostLobby.getDownloadPercent(JOINER_PLAYER));

            MeleeGameSetup hostSetup = hostLobby.createGameSetup(scenario, GameSpeed.FASTEST, true);
            MeleeGameSetup joinerSetup = joinerLobby.createGameSetup(scenario, GameSpeed.FASTEST, true);
            LiveGame hostGame = new LiveGame(hostSetup, hostLobby.getSeed(), HOST_PLAYER);
            LiveGame joinerGame = new LiveGame(joinerSetup, joinerLobby.getSeed(), JOINER_PLAYER);

            Assertions.assertArrayEquals(hostGame.getReplay().getHeader().getColors(), joinerGame.getReplay().getHeader().getColors());

            host.startNetworkThread();
            joiner.startNetworkThread();
            LanTurnTransport hostTransport = host.createTurnTransport();
            LanTurnTransport joinerTransport = joiner.createTurnTransport();

            Assertions.assertEquals(HOST_TURN_RATE, hostTransport.getTurnRate().getTurnsPerSecond());
            Assertions.assertEquals(HOST_TURN_RATE, joinerTransport.getTurnRate().getTurnsPerSecond());

            hostGame.attachNetwork(hostTransport, TURNS_IN_TRANSIT, false);
            joinerGame.attachNetwork(joinerTransport, TURNS_IN_TRANSIT, false);

            long hostFrame = 0;
            long joinerFrame = 0;

            while ((hostFrame < FRAMES || joinerFrame < FRAMES) && System.currentTimeMillis() < deadline) {
                if (hostFrame < FRAMES && hostGame.exchangeTurn(hostFrame)) {
                    hostFrame++;
                }

                if (joinerFrame < FRAMES && joinerGame.exchangeTurn(joinerFrame)) {
                    joinerFrame++;
                }

                Thread.sleep(FRAME_POLL_MILLISECONDS);
            }

            Assertions.assertEquals(FRAMES, hostFrame);
            Assertions.assertEquals(FRAMES, joinerFrame);
        }
    }

    /**
     * Checks advertised EUD and original-limit flags for an EUD map and a regular melee map.
     */
    @Test
    void hosted_eud_map_advertises_the_eud_and_original_limits_flags() throws Exception {
        Path eudPath = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, ExternalInput.TEST_MAPS_FOLDER, "eud_com.scx");
        Path meleePath = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, MELEE_MAP);

        try (LanClient host = LanClient.open("hostplayer", NO_MAP_FOLDERS)) {
            ChkFile eud = ChkFile.parseTolerant(MpqArchive.open(eudPath).readFile(SCENARIO));

            LanTestPortUtils.bindFreePort(port -> host.host("s4jeudgame", LanClient.HostedMap.read(eudPath, eud, "eud_com"),
                                                            LanGameOptions.USE_MAP_SETTINGS, GameSpeed.FASTEST, port));

            Assertions.assertEquals(ORIGINAL_LIMITS_FLAG | EUD_MAP_FLAG, host.getGameFlags());

            ChkFile melee = ChkFile.parseTolerant(MpqArchive.open(meleePath).readFile(SCENARIO));

            LanTestPortUtils.bindFreePort(port -> host.host("s4jmeleegame", LanClient.HostedMap.read(meleePath, melee, "Bottleneck"),
                                                            LanGameOptions.MELEE, GameSpeed.FASTEST, port));

            Assertions.assertEquals(0, host.getGameFlags());
        }
    }

    /**
     * Checks that a host choosing the dynamic turn rate advertises the provider's default turn rate and the dynamic turn rate flag, which a
     * joiner reads back from the advertised record.
     */
    @Test
    void dynamic_turn_rate_advertises_the_default_rate_and_its_flag() throws Exception {
        Path mapPath = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, MELEE_MAP);

        ChkFile map = ChkFile.parseTolerant(MpqArchive.open(mapPath).readFile(SCENARIO));

        try (LanClient host = LanClient.open("hostplayer", NO_MAP_FOLDERS)) {
            host.setTurnRate(LanClient.DYNAMIC_TURN_RATE);

            LanTestPortUtils.bindFreePort(port -> host.host(GAME_NAME, LanClient.HostedMap.read(mapPath, map, "Bottleneck"),
                                                            LanGameOptions.MELEE, GameSpeed.FASTEST, port));

            LanGameInformation hosted = host.getGameInformation();
            LanLobby lobby = host.getLobby();

            Assertions.assertNotNull(hosted);
            Assertions.assertNotNull(lobby);
            Assertions.assertEquals(DYNAMIC_TURN_RATE_FLAG, host.getGameFlags());
            Assertions.assertEquals(LiveGame.DYNAMIC_TURN_RATE_FLAG, LanClient.DYNAMIC_TURN_RATE_FLAG);
            Assertions.assertEquals(ProviderSession.DEFAULT_TURNS_PER_SECOND, hosted.getNetworkTurnRate());
            Assertions.assertEquals(ProviderSession.DEFAULT_TURNS_PER_SECOND, lobby.getTurnRate());

            LanGameInformation joined = LanGameInformation.of(hosted.toRecord(1L));

            Assertions.assertNotNull(joined);
            Assertions.assertEquals(DYNAMIC_TURN_RATE_FLAG, joined.getFlags(), "a joiner reads the flag from the advertised record");
            Assertions.assertEquals(ProviderSession.DEFAULT_TURNS_PER_SECOND, joined.getNetworkTurnRate());
        }
    }

    @Test
    void host_advertises_the_slot_counts_of_its_lobby() throws Exception {
        Path mapPath = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, MELEE_MAP);

        ChkFile map = ChkFile.parseTolerant(MpqArchive.open(mapPath).readFile(SCENARIO));

        try (LanClient host = LanClient.open("hostplayer", NO_MAP_FOLDERS)) {
            LanTestPortUtils.bindFreePort(port -> host.host(GAME_NAME, LanClient.HostedMap.read(mapPath, map, "Bottleneck"),
                                                            LanGameOptions.MELEE, GameSpeed.FASTEST, port));

            LanLobby lobby = host.getLobby();

            Assertions.assertNotNull(lobby);
            Assertions.assertTrue(lobby.setSlotState(JOINER_PLAYER, LanLobby.SLOT_CLOSED));

            long deadline = System.currentTimeMillis() + LIMIT_MILLISECONDS;

            while (lobby.getRecord(JOINER_PLAYER).getType() != CLOSED_TYPE && System.currentTimeMillis() < deadline) {
                host.update();
                Thread.sleep(LOBBY_POLL_MILLISECONDS);
            }

            host.update();

            LanGameInformation advertised = host.getGameInformation();

            Assertions.assertEquals(CLOSED_TYPE, lobby.getRecord(JOINER_PLAYER).getType());
            Assertions.assertNotNull(advertised);
            Assertions.assertEquals(1, advertised.getClosedSlots());
            Assertions.assertEquals(OBSERVER_LIMIT, advertised.getObserversMaximum());
        }
    }
}
