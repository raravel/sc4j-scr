package org.fogofwarlabs.sc4j.scr.game.state;

import org.fogofwarlabs.sc4j.engine.game.EngineLimits;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayHeaderBuilder;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayWriter;
import org.fogofwarlabs.sc4j.scr.game.combat.ScrCombatSystem;
import org.fogofwarlabs.sc4j.scr.game.movement.ScrCollisionSystem;
import org.fogofwarlabs.sc4j.scr.game.movement.ScrMovementSystem;
import org.fogofwarlabs.sc4j.scr.game.path.ScrPathfindingSystem;
import org.fogofwarlabs.sc4j.scr.game.player.ScrEconomySystem;
import org.fogofwarlabs.sc4j.scr.game.production.ScrProductionSystem;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.test.GameTestResources;
import org.fogofwarlabs.sc4j.scr.game.test.RecordingSimulationPluginSource;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.ScrSpatialIndex;
import org.fogofwarlabs.sc4j.scr.game.visibility.ScrVisibilitySystem;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

/**
 * Tests the SC:R game as the game system of an engine game: the adopted random generator, the frame clock, the frame
 * sequence, the pool sizes, the scenario, map archive and replay sections of the setup, the replay sections and save gate of the game, the views
 * of the SC:R systems under the engine system keys and the state hash.
 */
final class GameTest {
    private static final int FRAME = 5;
    private static final int FRAME_STEP_COUNT = 6;

    private static final int IMAGES = 5000;
    private static final int SPRITES = 2500;
    private static final int SPRITE_EFFECTS = 500;
    private static final int UNITS = 1700;
    private static final int BULLETS = 100;
    private static final int ORDERS = 2000;

    private static final @NotNull String REPLAY_SECTION_TAG = "TEST";
    private static final byte @NotNull [] REPLAY_SECTION_DATA = {1, 2, 3, 4, 5};
    private static final int NAME_COUNT = 12;
    private static final int SYNTHETIC_BUILD_NUMBER = 13515;

    private static @NotNull GameMap createMap() {
        return new GameMap(1, 1, 0, new int[1]);
    }

    private static @NotNull ReplayFile createReplay(@NotNull Map<String, byte[]> sections) throws IOException {
        ReplayWriter writer = new ReplayWriter(new ReplayHeaderBuilder().build(), new byte[0], new byte[]{1},
                                               ReplayWriter.encodeNames(new byte[NAME_COUNT][0]), new byte[ReplayWriter.SKIN_SIZE],
                                               ReplayWriter.CURRENT_LIMITS, ReplayWriter.CURRENT_FIX_FLAGS,
                                               new byte[ReplayWriter.CUSTOM_COLORS_SIZE], ReplayWriter.encodeVersionBlock(SYNTHETIC_BUILD_NUMBER, 0));

        for (Map.Entry<String, byte[]> section : sections.entrySet()) {
            writer.addModSection(section.getKey(), section.getValue());
        }

        return ReplayFile.parse(writer.write());
    }


    @Test
    void create_should_attach_the_game_system_to_an_engine_game_with_the_setup_random_generator() {
        LcgRandom random = new LcgRandom(1);
        Game game = Game.create(null, createMap(), random);
        org.fogofwarlabs.sc4j.engine.game.Game engineGame = game.getEngineGame();

        game.setCurrentFrame(FRAME);

        Assertions.assertSame(game, engineGame.getModSystem(Game.SYSTEM_KEY));
        Assertions.assertSame(random, engineGame.getRandom());
        Assertions.assertEquals(FRAME, engineGame.getClock().getFrame());
        Assertions.assertEquals(FRAME_STEP_COUNT, engineGame.getFrameRunner().getPhases().getCount());
        Assertions.assertEquals(0, engineGame.getUnits().getCapacity());
        Assertions.assertNull(game.getUnitSystem().getUnitPool());
    }

    @Test
    void create_should_size_the_pools_by_the_engine_limits_of_the_native_limits() {
        int[] limits = {IMAGES, SPRITES, SPRITE_EFFECTS, UNITS, BULLETS, ORDERS, SPRITE_EFFECTS};
        Game game = Game.create(new GameSetup.Builder(null, createMap(), new LcgRandom(1)).setLimits(limits).build());
        EngineLimits engineLimits = game.getEngineGame().getLimits();

        Assertions.assertEquals(UNITS, engineLimits.getUnits());
        Assertions.assertEquals(SPRITES, engineLimits.getSprites());
        Assertions.assertEquals(IMAGES, engineLimits.getImages());
        Assertions.assertEquals(ORDERS, engineLimits.getOrders());
        Assertions.assertEquals(BULLETS, engineLimits.getProjectiles());
        Assertions.assertEquals(UNITS, game.getUnitSystem().requireUnitPool().getCapacity());
        Assertions.assertEquals(0, game.getEngineGame().getUnits().getCapacity());
    }

    @Test
    void create_should_offer_the_setup_scenario_when_the_simulation_plugins_are_created() throws Throwable {
        ChkFile scenario = ChkFile.parseTolerant(new byte[0]);
        RecordingSimulationPluginSource.Recording recording = new RecordingSimulationPluginSource.Recording();
        Game[] games = new Game[2];

        RecordingSimulationPluginSource.record(recording, () -> {
            games[0] = Game.create(new GameSetup.Builder(null, createMap(), new LcgRandom(1)).setScenario(scenario).build());
            games[1] = Game.create(null, createMap(), new LcgRandom(1));
        });

        Assertions.assertSame(scenario, games[0].getSetup().getScenario());
        Assertions.assertNull(games[1].getSetup().getScenario());
        Assertions.assertEquals(2, recording.getScenarios().size());
        Assertions.assertSame(scenario, recording.getScenarios().get(0));
        Assertions.assertNull(recording.getScenarios().get(1));
    }

    @Test
    void create_should_serve_the_engine_systems_with_views_of_the_scr_systems() {
        org.fogofwarlabs.sc4j.engine.game.Game engineGame = Game.create(null, createMap(), new LcgRandom(1)).getEngineGame();

        Assertions.assertInstanceOf(ScrEconomySystem.class, engineGame.getEconomy());
        Assertions.assertInstanceOf(ScrSpatialIndex.class, engineGame.getSpatialIndex());
        Assertions.assertInstanceOf(ScrVisibilitySystem.class, engineGame.getVisibility());
        Assertions.assertInstanceOf(ScrCollisionSystem.class, engineGame.getCollision());
        Assertions.assertInstanceOf(ScrPathfindingSystem.class, engineGame.getPathfinding());
        Assertions.assertInstanceOf(ScrMovementSystem.class, engineGame.getMovement());
        Assertions.assertInstanceOf(ScrCombatSystem.class, engineGame.getCombat());
        Assertions.assertInstanceOf(ScrProductionSystem.class, engineGame.getProduction());
    }

    @Test
    void engine_state_hash_should_change_with_the_game_state_hash() {
        Game game = Game.create(null, createMap(), new LcgRandom(1));
        long engineHash = game.getEngineGame().computeStateHash();
        long gameHash = game.computeStateHash();

        game.incrementTurnCount();

        Assertions.assertNotEquals(gameHash, game.computeStateHash());
        Assertions.assertNotEquals(engineHash, game.getEngineGame().computeStateHash());
    }

    @Test
    void create_should_offer_the_map_archive_and_the_replay_sections_when_the_simulation_plugins_are_created() throws Throwable {
        MpqArchive archive = MpqArchive.open(GameTestResources.getPath(GameTestResources.MAP_EUD_COMPUTER));
        ReplayFile replay = createReplay(Map.of(REPLAY_SECTION_TAG, REPLAY_SECTION_DATA));
        RecordingSimulationPluginSource.Recording recording = new RecordingSimulationPluginSource.Recording();
        Game[] games = new Game[3];

        recording.setReplaySection(REPLAY_SECTION_TAG, null);

        RecordingSimulationPluginSource.record(recording, () -> {
            games[0] = Game.create(new GameSetup.Builder(null, createMap(), new LcgRandom(1)).setMapArchive(archive).build());
            games[1] = Game.create(new GameSetup.Builder(null, createMap(), new LcgRandom(1)).setReplay(replay).build());
            games[2] = Game.create(new GameSetup.Builder(null, createMap(), new LcgRandom(1)).setReplay(createReplay(Map.of())).build());
        });

        Assertions.assertSame(archive, games[0].getSetup().getMapArchive());
        Assertions.assertSame(archive, recording.getMapArchives().get(0));
        Assertions.assertNull(recording.getMapArchives().get(1));
        Assertions.assertNull(recording.getReplaySections().get(0), "a setup without a replay offers no section");
        Assertions.assertArrayEquals(REPLAY_SECTION_DATA, recording.getReplaySections().get(1));
        Assertions.assertNull(recording.getReplaySections().get(2), "a replay without the section offers none");
        Assertions.assertThrows(IllegalArgumentException.class, () -> games[1].getSetup().readReplaySection("GCFG"));
    }

    @Test
    void set_replay_section_should_keep_the_sections_by_tag_and_reject_other_tags() {
        Game game = Game.create(null, createMap(), new LcgRandom(1));
        byte[] replaced = {9};

        game.setReplaySection(REPLAY_SECTION_TAG, REPLAY_SECTION_DATA);
        game.setReplaySection("ZZZZ", new byte[0]);
        game.setReplaySection(REPLAY_SECTION_TAG, replaced);

        Map<String, byte[]> sections = game.getReplaySections();

        Assertions.assertEquals(2, sections.size());
        Assertions.assertSame(replaced, sections.get(REPLAY_SECTION_TAG), "the data is retained, not copied");
        Assertions.assertEquals(REPLAY_SECTION_TAG, sections.keySet().iterator().next());
        Assertions.assertThrows(IllegalArgumentException.class, () -> game.setReplaySection("SKIN", new byte[0]));
        Assertions.assertThrows(IllegalArgumentException.class, () -> game.setReplaySection("TOOLONG", new byte[0]));
    }

    @Test
    void is_save_allowed_should_require_every_simulation_plugin_to_allow_a_save() throws Throwable {
        RecordingSimulationPluginSource.Recording recording = new RecordingSimulationPluginSource.Recording();
        Game[] games = new Game[1];

        recording.setSaveAllowed(false);

        RecordingSimulationPluginSource.record(recording, () -> games[0] = Game.create(null, createMap(), new LcgRandom(1)));

        Assertions.assertFalse(games[0].isSaveAllowed());
        Assertions.assertTrue(Game.create(null, createMap(), new LcgRandom(1)).isSaveAllowed(), "a game without plugins can be saved");
    }
}
