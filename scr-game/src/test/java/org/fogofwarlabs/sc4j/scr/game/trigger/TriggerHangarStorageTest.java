package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.ability.UnitHangar;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

/**
 * Checks completed subordinate storage through the trigger caller's capacity and creation-tag contract.
 */
@RequiresInstallation
final class TriggerHangarStorageTest {
    private static final int PARENT_X = 3560;
    private static final int PARENT_Y = 2160;
    private static final int CREATION_TAG = 0xABCD;
    private static final int ADDED_COUNT = 2;

    @ParameterizedTest
    @ValueSource(ints = {ScrUnitIds.PROTOSS_CARRIER, ScrUnitIds.HERO_GANTRITHOR, ScrUnitIds.PROTOSS_REAVER, ScrUnitIds.HERO_WARBRINGER})
    void modify_hangar_should_keep_completed_hidden_children_and_tags(int parentType) throws IOException {
        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = RunningGameScenario.start(gameData);

            RunningGameScenario.runInCommandDispatch(runtime, () -> {
                Game game = runtime.getGame();
                Unit parent = RunningGameScenario.createUnit(game, parentType, PARENT_X, PARENT_Y, RunningGameScenario.HUMAN);
                UnitPool pool = game.getUnitSystem().getUnitPool();

                Assertions.assertNotNull(pool);

                pool.setCreationTag(parent.getIndex(), CREATION_TAG);

                Assertions.assertTrue(game.getTriggerUnitActions().modifyHangar(parent, ADDED_COUNT, RunningGameScenario.INSTRUCTION_LIMIT));
                Assertions.assertEquals(ADDED_COUNT, parent.getHangarCount(false));
                Assertions.assertEquals(0, parent.getHangarCount(true));

                Unit child = parent.getFirstHangarUnit(false);
                Unit previous = null;

                for (int index = 0; index < ADDED_COUNT; index++) {
                    Assertions.assertNotNull(child);
                    Assertions.assertSame(parent, child.getHangarParent());
                    Assertions.assertSame(previous, child.getPreviousHangarUnit());
                    Assertions.assertEquals(UnitHangar.getSubordinateType(game.requireGameData().getUnitTypes(), parent.getUnitId()),
                                            child.getUnitId());
                    Assertions.assertEquals(CREATION_TAG, pool.getCreationTag(child.getIndex()));
                    Assertions.assertNotEquals(0, child.getStatus() & UnitStatusFlags.COMPLETED);
                    Assertions.assertNotEquals(0, child.requireSprite().getFlags() & Sprite.FLAG_HIDDEN);
                    Assertions.assertFalse(child.isHangarOutside());

                    previous = child;
                    child    = child.getNextHangarUnit();
                }

                Assertions.assertNull(child);
            });
        }
    }
}
