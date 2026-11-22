package org.fogofwarlabs.sc4j.scr.game.ability;

import org.fogofwarlabs.sc4j.base.exception.IgnoreException;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepField;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepRecession;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks that an Infested Command Center lifts off when the creep recession records are exhausted, and that a non-building unit that finishes
 * lifting off gets its modified top speed back.
 */
@RequiresInstallation
final class UnitBuildingFlightTest {
    private static final int BUILDING_X = 2400;
    private static final int BUILDING_Y = 1400;
    private static final int TILE_X_INDEX = 0;
    private static final int TILE_Y_INDEX = 1;

    private static final int UNIT_X = 2800;
    private static final int UNIT_Y = 1400;

    private static final int LIFTING_OFF_ORDER = 74;
    private static final int LIFTING_OFF_STATE_RISING = 3;
    private static final int LIFT_SPEED = 256;
    private static final int ENSNARE_TIMER = 1;

    private static @Nullable GameData gameData;
    private static @Nullable ReplayRuntime runtime;

    @BeforeAll
    static void startGame() throws IOException {
        gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath());
        runtime  = RunningGameScenario.start(gameData);
    }

    @AfterAll
    static void closeGameData() {
        GameData installedData = gameData;

        runtime = null;

        if (installedData != null) {
            IgnoreException.execute(
                    installedData::close
            );
        }

        gameData = null;
    }


    @Test
    void update_liftoff_should_lift_off_when_recession_records_are_exhausted() {
        RunningGameScenario.runInCommandDispatch(runtime, () -> {
            Assertions.assertNotNull(runtime);

            Game game = runtime.getGame();
            Unit building = RunningGameScenario.createUnit(game,
                                                           ScrUnitIds.ZERG_INFESTED_COMMAND_CENTER,
                                                           BUILDING_X, BUILDING_Y,
                                                           RunningGameScenario.COMPUTER);
            Sprite sprite = building.getSpriteOrNull();

            Assertions.assertNotNull(sprite);

            CreepField creep = game.getCreepField();

            Assertions.assertNotNull(creep);

            creep.spreadUnderBuilding(game, building.getUnitId(), sprite.getX(), sprite.getY());

            CreepRecession recession = game.getCreepRecession();

            Assertions.assertNotNull(recession);

            List<int[]> marked = new ArrayList<>();

            for (int tile = 0; recession.markReceding(game, tile % game.getMap().getWidth(), tile / game.getMap().getWidth()); tile++) {
                marked.add(new int[]{tile % game.getMap().getWidth(), tile / game.getMap().getWidth()});
            }

            try {
                game.getUnitBuildingFlight().updateLiftoff(building, RunningGameScenario.INSTRUCTION_LIMIT);
            } finally {
                for (int[] tile : marked) {
                    recession.restore(game, tile[TILE_X_INDEX], tile[TILE_Y_INDEX]);
                }
            }

            Assertions.assertEquals(LIFTING_OFF_ORDER, building.getPrimaryOrder(), "Liftoff goes on without a free creep recession record.");
            Assertions.assertNotEquals(0, building.getStatus() & UnitStatusFlags.FLYING);
        });
    }

    @Test
    void update_lifting_off_should_restore_modified_top_speed_of_non_building() {
        RunningGameScenario.runInCommandDispatch(runtime, () -> {
            Assertions.assertNotNull(runtime);
            Assertions.assertNotNull(gameData);

            Game game = runtime.getGame();
            Unit unit = RunningGameScenario.createUnit(game, ScrUnitIds.ZERG_DEVOURER, UNIT_X, UNIT_Y, RunningGameScenario.COMPUTER);
            Sprite sprite = unit.getSpriteOrNull();

            Assertions.assertNotNull(sprite);

            unit.setPrimaryOrder(LIFTING_OFF_ORDER);
            unit.setPrimaryOrderState(LIFTING_OFF_STATE_RISING);
            unit.setOrderSignal(unit.getOrderSignal() | Unit.ORDER_SIGNAL_LIFT_DONE);
            unit.setMovementDestinationX(sprite.getX());
            unit.setMovementDestinationY(sprite.getY());
            unit.setTopSpeed(LIFT_SPEED);
            unit.setSpeedDecreaseByte(ENSNARE_TIMER);

            game.getUnitBuildingFlight().updateLiftingOff(unit, RunningGameScenario.INSTRUCTION_LIMIT);

            int flingyType = gameData.getUnitTypes().getFlingyType(ScrUnitIds.ZERG_DEVOURER);

            Assertions.assertEquals(gameData.getFlingyTypes().getTopSpeed(flingyType) >>> 1,
                                    unit.getTopSpeed(),
                                    "A slowed non-building keeps half of its flingy top speed.");
            Assertions.assertEquals(0, unit.getOrderSignal() & Unit.ORDER_SIGNAL_LIFT_DONE);
            Assertions.assertNotEquals(LIFTING_OFF_ORDER, unit.getPrimaryOrder());
        });
    }
}
