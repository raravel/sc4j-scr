package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.game.NetworkReplayScenario;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.replay.GameSourceFile;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.session.setup.MeleeGameSetup;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.test.*;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks that a game plugin that observes every EUD write and reads the memory around it leaves the simulation unchanged: an EUD map runs to the
 * same state hash after every frame with and without it, and without counting no game gets a plugin.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ScrGameExtensionTest {
    private static final @NotNull String HUMAN_NAME = "tester";
    private static final int SEED = 0x6AB9B92E;
    private static final int FRAMES = 480;

    /**
     * Runs the EUD map as a Use Map Settings game and records the state hash before every frame and after the last.
     *
     * @param resources the installation resources
     * @param map       the EUD map
     * @param games     receives the created game
     * @return the state hashes
     * @throws Exception if the map cannot be read or the game cannot run
     */
    private static @NotNull List<Long> runStateHashes(@NotNull InstallationTestResources resources, @NotNull Path map, @NotNull List<Game> games) throws Exception {
        byte[] scenario = MpqArchive.open(map).readFile(ChkFile.ARCHIVE_ENTRY);
        ChkFile chk = ChkFile.parseTolerant(scenario);
        MeleeGameSetup setup = MeleeGameSetup.createUseMapSettings(scenario, HUMAN_NAME, ScrRaceIds.TERRAN, 0, GameSpeed.FASTEST);
        LiveGame live = new LiveGame(setup, SEED);
        ReplayRuntime runtime = ReplayRuntime.createLiveGame(live.getReplay(), chk, resources.getGameData(), live.getLocalPlayer(),
                                                             RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT,
                                                             RunningGameScenario.INSTRUCTION_LIMIT);
        Game game = runtime.getGame();
        List<Long> hashes = new ArrayList<>();

        games.add(game);
        NetworkReplayScenario.copyCodeConstants(game, resources.getGamePath());
        NetworkReplayScenario.prepare(game);
        runtime.setSourceFile(GameSourceFile.read(map));
        runtime.start(0, 0);

        new ReplayRunScenario(runtime, GameSpeed.FASTEST_DURATION, frame -> {
            hashes.add(game.computeStateHash());
            live.exchangeTurn(frame);
        }).runTo(FRAMES);

        hashes.add(game.computeStateHash());

        return hashes;
    }

    @Test
    void an_observing_plugin_leaves_the_state_hash_of_every_frame_unchanged(@NotNull InstallationTestResources resources) throws Throwable {
        Path map = GameTestResources.getPath(GameTestResources.MAP_EUD_COMPUTER);
        List<Game> plain = new ArrayList<>();
        List<Game> observed = new ArrayList<>();
        List<Long> expected = runStateHashes(resources, map, plain);
        List<List<Long>> actual = new ArrayList<>();
        long writes = MemoryReadingPluginSource.count(() -> actual.add(runStateHashes(resources, map, observed)));

        Assertions.assertEquals(0, plain.getFirst().getPlugins().length);
        Assertions.assertEquals(1, observed.getFirst().getPlugins().length);
        Assertions.assertTrue(writes > 0, "the map writes EUD memory");
        Assertions.assertEquals(FRAMES + 1, expected.size());
        Assertions.assertEquals(expected, actual.getFirst());
    }
}
