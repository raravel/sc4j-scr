package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.engine.client.host.PresentThrottle;
import org.fogofwarlabs.sc4j.engine.lockstep.LockstepTransport;
import org.fogofwarlabs.sc4j.engine.lockstep.PeerTurn;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.NetworkPeerUtils;
import org.fogofwarlabs.sc4j.scr.game.session.SyncChecksumUtils;
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
 * Checks the live game loop pacing of the speed overrides on two clients of a network game, each running the client's passes on a simulated host
 * clock: a pass runs the live frames due ({@link ReplayPlayback#takeLiveDueFrames}) after their turn exchanges, giving the time of frames whose
 * turn is missing back ({@link ReplayPlayback#refundLiveFrames}), then draws if the draw throttle lets it ({@link PresentThrottle} with
 * {@link GameSpeed#THROTTLED_DRAW_INTERVAL}), and a drawn pass costs more host time than a pass without drawing. At {@code #x4} and {@code #x5}
 * several frames run per drawn frame and the drawn frames keep the draw interval; without an override and at {@code #x2} every pass draws and
 * the clients run no more frames than they draw; both clients stay in step whatever their drawing costs. A user's frame rate limit
 * below the draw interval's rate ({@link PresentThrottle#setFrameRateLimit}) spaces the drawn frames of {@code #x3} to {@code #x5} to the limit's
 * period and leaves the frame schedule as it is: the clients run no fewer frames than without it.
 */
@RequiresInstallation
final class LiveDrawThrottleTest {
    private static final @NotNull String MAP_NAME = "(2)Bottleneck.scm";
    private static final @NotNull String SCENARIO_FILE = "staredit\\scenario.chk";
    private static final int GAME_SEED = 1;
    private static final int CLIENTS = 2;
    private static final int TURNS_IN_TRANSIT = 2;

    /**
     * The simulated host milliseconds each game runs, a pass without drawing, and the drawn passes of the two clients.
     */
    private static final int RUN_MILLISECONDS = 1000;
    private static final int PASS_MILLISECONDS = 1;
    private static final int @NotNull [] DRAW_MILLISECONDS = {3, 7};
    private static final double MILLISECONDS_PER_SECOND = 1000;

    /**
     * The frames per drawn frame at {@code #x5} stay above this: native draws every 16 ms with a 1 ms frame.
     */
    private static final int FASTEST_OVERRIDE_FRAMES_PER_DRAW = 4;
    /**
     * The user's frame rate limit of the limited runs, below the draw interval's {@code 62.5} frames per second; {@code 0} is none.
     */
    private static final int FRAME_RATE_LIMIT = 30;
    private static final int NO_FRAME_RATE_LIMIT = 0;
    private static final @NotNull List<String> THROTTLED_GAME_NAMES = List.of("Pacing #x3", "Pacing #x4", "Pacing #x5");

    /**
     * Both clients' sent turns, in order; each client takes both players' next turns once both arrived.
     */
    private static final class Network {
        private final @NotNull List<List<byte[]>> sent;
        private final int @NotNull [] received;

        /**
         * Creates a {@link Network} instance without turns.
         */
        private Network() {
            this.sent     = List.of(new ArrayList<>(), new ArrayList<>());
            this.received = new int[CLIENTS];
        }

        /**
         * Creates the {@link LockstepTransport} instance of a client.
         *
         * @param client the client index
         * @return the created {@link LockstepTransport} instance
         */
        private @NotNull LockstepTransport createTransport(int client) {
            return new LockstepTransport() {
                /**
                 * @return the local turns sent and not yet received back
                 */
                @Override
                public int getTurnsInTransit() {
                    return Network.this.sent.get(client).size() - Network.this.received[client];
                }

                /**
                 * Sends the local player's next turn.
                 *
                 * @param turn the turn bytes
                 */
                @Override
                public void sendTurn(byte @NotNull [] turn) {
                    Network.this.sent.get(client).add(turn);
                }

                /**
                 * Takes both players' next turns once both arrived, the second client's first.
                 *
                 * @return the participants' turns in execution order, or {@code null} while a participant's turn is missing
                 */
                @Override
                public @Nullable List<@NotNull PeerTurn> receiveTurns() {
                    int next = Network.this.received[client];

                    if (Network.this.sent.getFirst().size() <= next || Network.this.sent.get(1).size() <= next) {
                        return null;
                    }

                    Network.this.received[client]++;

                    return List.of(new PeerTurn(NetworkPeerUtils.getPeer(1), Network.this.sent.get(1).get(next)),
                                   new PeerTurn(NetworkPeerUtils.getPeer(0), Network.this.sent.getFirst().get(next)));
                }
            };
        }
    }

    /**
     * One client's game, its pacing and what its passes did.
     */
    private static final class Client {
        private final @NotNull LiveGame game;
        private final @NotNull ReplayRuntime runtime;
        private final @NotNull ReplayPlayback playback;
        private final @NotNull PresentThrottle throttle;
        private final int drawMilliseconds;

        private final @NotNull List<Integer> frameHashes;

        private int nextPass;
        private int lastPass;
        private int drawnFrames;
        private int lastDrawn;
        private int shortestDrawGap;

        /**
         * Creates a {@link Client} instance whose first pass runs at once.
         *
         * @param game             the live game
         * @param runtime          the started runtime
         * @param drawMilliseconds the host time of a drawn pass
         * @param frameRateLimit   the user's frame rate limit, {@code 0} for none
         */
        private Client(@NotNull LiveGame game, @NotNull ReplayRuntime runtime, int drawMilliseconds, int frameRateLimit) {
            this.game             = game;
            this.runtime          = runtime;
            this.playback         = new ReplayPlayback(ReplayPlayback.getNormalSpeedIndex());
            this.throttle         = new PresentThrottle(GameSpeed.THROTTLED_DRAW_INTERVAL);
            this.drawMilliseconds = drawMilliseconds;

            this.frameHashes = new ArrayList<>();

            this.nextPass        = 0;
            this.lastPass        = 0;
            this.drawnFrames     = 0;
            this.lastDrawn       = -1;
            this.shortestDrawGap = Integer.MAX_VALUE;

            this.throttle.setFrameRateLimit(frameRateLimit);
            this.throttle.setActive(runtime.getFrameTiming().isDrawThrottled(), 0);
        }

        /**
         * Runs one pass at the host time: the due frames after their turn exchanges, then the drawing if the throttle lets it.
         *
         * @param now the host time in milliseconds
         */
        private void runPass(int now) {
            int frameDuration = this.runtime.getFrameTiming().getDuration(GameSpeed.FASTEST);
            double elapsedSeconds = (now - this.lastPass) / MILLISECONDS_PER_SECOND;
            int dueFrames = this.playback.takeLiveDueFrames(elapsedSeconds, frameDuration, GameSpeed.getLagLimit(GameSpeed.FASTEST));

            this.lastPass = now;

            for (int index = 0; index < dueFrames; index++) {
                long frame = this.runtime.getGame().getCurrentFrame();

                if (!this.game.exchangeTurn(frame)) {
                    // A frame waiting for a player's turn ran nothing; its time and the time of the later due frames stay owed.
                    this.playback.refundLiveFrames(dueFrames - index);

                    break;
                }

                this.runtime.step(frameDuration, now);

                Assertions.assertNull(this.runtime.getFailure(), () -> "frame " + frame + ": " + this.runtime.getFailure());
                Assertions.assertEquals(frame + 1, this.runtime.getGame().getCurrentFrame());

                this.frameHashes.add(SyncChecksumUtils.calculateUnitHash(this.runtime.getGame()) ^ this.runtime.getGame().getRandom().getState());
            }

            if (!this.throttle.takePresentDue(now)) {
                this.nextPass = now + PASS_MILLISECONDS;

                return;
            }

            if (this.lastDrawn >= 0) {
                this.shortestDrawGap = Math.min(this.shortestDrawGap, now - this.lastDrawn);
            }

            this.lastDrawn = now;
            this.drawnFrames++;
            this.nextPass = now + this.drawMilliseconds;
        }
    }

    @Test
    void fast_overrides_run_several_frames_per_drawn_frame_and_both_clients_stay_in_step() throws IOException {
        Client[] plain = this.play("Pacing", NO_FRAME_RATE_LIMIT);
        Client[] second = this.play("Pacing #x2", NO_FRAME_RATE_LIMIT);
        Client[] fourth = this.play("Pacing #x4", NO_FRAME_RATE_LIMIT);
        Client[] fifth = this.play("Pacing #x5", NO_FRAME_RATE_LIMIT);

        for (Client[] clients : List.of(plain, second)) {
            for (Client client : clients) {
                Assertions.assertFalse(client.throttle.isActive());
                Assertions.assertTrue(client.frameHashes.size() <= client.drawnFrames, "no more frames than drawn frames");
                Assertions.assertEquals(client.drawMilliseconds, client.shortestDrawGap, "every pass draws");
            }
        }

        for (Client[] clients : List.of(fourth, fifth)) {
            for (Client client : clients) {
                Assertions.assertTrue(client.throttle.isActive());
                Assertions.assertTrue(client.frameHashes.size() > client.drawnFrames,
                                      "frames " + client.frameHashes.size() + ", drawn " + client.drawnFrames);
                Assertions.assertTrue(client.shortestDrawGap >= GameSpeed.THROTTLED_DRAW_INTERVAL, "drawn every " + client.shortestDrawGap + " ms");
            }
        }

        for (Client client : fifth) {
            Assertions.assertTrue(client.frameHashes.size() > FASTEST_OVERRIDE_FRAMES_PER_DRAW * client.drawnFrames,
                                  "frames " + client.frameHashes.size() + ", drawn " + client.drawnFrames);
        }
    }

    @Test
    void user_frame_rate_limit_spaces_the_drawn_frames_of_fast_overrides_and_keeps_their_frame_schedule() throws IOException {
        for (String gameName : THROTTLED_GAME_NAMES) {
            Client[] unlimited = this.play(gameName, NO_FRAME_RATE_LIMIT);
            Client[] limited = this.play(gameName, FRAME_RATE_LIMIT);

            for (int index = 0; index < CLIENTS; index++) {
                Client client = limited[index];
                String text = gameName + ", client " + index;

                Assertions.assertTrue(client.throttle.isActive(), text);
                Assertions.assertTrue(client.shortestDrawGap * FRAME_RATE_LIMIT >= MILLISECONDS_PER_SECOND,
                                      text + ": drawn every " + client.shortestDrawGap + " ms");
                Assertions.assertTrue(client.drawnFrames < unlimited[index].drawnFrames,
                                      text + ": drawn " + client.drawnFrames + ", without the limit " + unlimited[index].drawnFrames);
                Assertions.assertTrue(client.frameHashes.size() >= unlimited[index].frameHashes.size(),
                                      text + ": frames " + client.frameHashes.size() + ", without the limit " + unlimited[index].frameHashes.size());
            }
        }
    }

    /**
     * Plays a two-player network game of a game name for {@link #RUN_MILLISECONDS} on the simulated host clock and checks that both clients
     * computed the same state in every frame both completed.
     *
     * @param gameName       the game name, whose suffix selects the speed override
     * @param frameRateLimit the user's frame rate limit, {@code 0} for none
     * @return the clients after the run
     * @throws IOException if the installed map or game data cannot be read
     */
    private @NotNull Client @NotNull [] play(@NotNull String gameName, int frameRateLimit) throws IOException {
        Path mapPath = ExternalInput.INSTALLATION.resolveFile(ExternalInput.MAPS_FOLDER, MAP_NAME);
        byte[] mapData = MpqArchive.open(mapPath).readFile(SCENARIO_FILE);
        Network network = new Network();
        Client[] clients = new Client[CLIENTS];

        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            for (int index = 0; index < CLIENTS; index++) {
                MeleeGameSetup setup = MeleeGameSetup.createUseMapSettings(mapData, "client" + index, ScrRaceIds.TERRAN, 0, GameSpeed.FASTEST);

                setup.setNetworkGameInformation(gameName, 0);

                LiveGame game = new LiveGame(setup, GAME_SEED);

                game.attachNetwork(network.createTransport(index), TURNS_IN_TRANSIT, true);

                ReplayRuntime runtime = ReplayRuntime.createLiveGame(game.getReplay(), ChkFile.parseTolerant(mapData),
                                                                     gameData,
                                                                     game.getLocalPlayer(), true,
                                                                     RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT,
                                                                     RunningGameScenario.INSTRUCTION_LIMIT);

                runtime.start(0, 0);
                game.startSession(runtime.getGame(), runtime.getFrameTiming());
                clients[index] = new Client(game, runtime, DRAW_MILLISECONDS[index], frameRateLimit);
            }

            for (int now = 0; now < RUN_MILLISECONDS; now++) {
                for (Client client : clients) {
                    if (now >= client.nextPass) {
                        client.runPass(now);
                    }
                }
            }
        }

        List<Integer> first = clients[0].frameHashes;
        List<Integer> second = clients[1].frameHashes;
        int common = Math.min(first.size(), second.size());

        Assertions.assertTrue(common > 0, gameName + ": both clients ran frames");
        Assertions.assertEquals(first.subList(0, common), second.subList(0, common), gameName + ": both clients computed the same frames");

        return clients;
    }
}
