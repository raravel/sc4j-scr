package org.fogofwarlabs.sc4j.scr.game.session;

import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayCommandIds;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/**
 * Checks that the Turn Rate and Dynamic Turn Rate commands set the turn rate, refill the turn timeouts and set the latency silently.
 */
@RequiresInstallation
final class LiveGameNetworkTurnRateTest {
    private static final int MATCHED_PROFILE_SPEED = 4;
    private static final int MAXIMUM_WAIT_ITERATIONS = 20;
    private static final int TURNS_IN_TRANSIT = 2;

    private static final int LOWER_TURN_RATE = 16;
    private static final int SCAN_TURN_RATE = 20;
    private static final int SCAN_LATENCY = 2;

    private static final @NotNull String PLAYER_NAME = "tester";
    private static final int GAME_SEED = 12345;

    /**
     * Runs until a condition holds.
     *
     * @return the next host iteration
     */
    private static int runUntil(@NotNull LiveGame game, @NotNull ReplayRuntime live, int first, @NotNull BooleanSupplier condition) {
        int iteration = first;

        while (!condition.getAsBoolean()) {
            Assertions.assertTrue(iteration < first + MAXIMUM_WAIT_ITERATIONS, "the command arrives with its turn");

            LiveSessionScenario.exchangeTurnAndStep(game, live, iteration++);
        }

        return iteration;
    }

    @Test
    void turn_rate_commands_set_the_rate_refill_the_timeouts_and_are_not_recorded() throws IOException {
        byte[] mapData = LiveSessionScenario.readBottleneckScenario();

        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            LiveGame game = new LiveGame(MeleeGameSetup.createUseMapSettings(mapData, PLAYER_NAME, ScrRaceIds.TERRAN, 0, MATCHED_PROFILE_SPEED),
                                         GAME_SEED);
            LiveSessionScenario.Loopback network = new LiveSessionScenario.Loopback();

            game.attachNetwork(network, TURNS_IN_TRANSIT, true);

            ReplayRuntime live = ReplayRuntime.createLiveGame(game.getReplay(), ChkFile.parseTolerant(mapData), gameData,
                                                              game.getLocalPlayer(),
                                                              RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT,
                                                              RunningGameScenario.INSTRUCTION_LIMIT);

            live.start(0, 0);
            game.startSession(live.getGame(), live.getFrameTiming());

            GameSpeed timing = live.getFrameTiming();
            int speed = timing.getSpeedIndex();
            int fastTimeout = timing.getTimeouts()[speed];

            Assertions.assertFalse(game.isTurnRateScanning(), "a game without the dynamic turn rate flag never scans");

            int iteration = LiveSessionScenario.runIterations(game, live, 0, 5);

            game.getOutgoingTurn().queue(new byte[]{ReplayCommandIds.TURN_RATE, LOWER_TURN_RATE});
            iteration = runUntil(game, live, iteration, () -> game.getTurnRate() == LOWER_TURN_RATE);

            Assertions.assertTrue(timing.getTimeouts()[speed] > fastTimeout, "fewer turns per second wait longer per turn");

            game.getOutgoingTurn().queue(new byte[]{ReplayCommandIds.DYNAMIC_TURN_RATE, 1, SCAN_TURN_RATE, SCAN_LATENCY});
            runUntil(game, live, iteration, () -> game.getTurnRate() == SCAN_TURN_RATE);

            Assertions.assertEquals(SCAN_LATENCY, game.getLatency(), "the scan result sets the latency");
            Assertions.assertTrue(game.takeLatencyNotices().isEmpty(), "the scan result's latency is silent");
            Assertions.assertTrue(game.getHistory().stream().noneMatch(command -> command.getTypeId() == ReplayCommandIds.TURN_RATE ||
                                                                                  command.getTypeId() == ReplayCommandIds.DYNAMIC_TURN_RATE),
                                  "Turn Rate and Dynamic Turn Rate are not recorded");
        }
    }
}
