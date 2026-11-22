package org.fogofwarlabs.sc4j.scr.game.trigger.eud;

import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayFile;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.GameTestResources;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.ClassicStorageRegion;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.unit.ClassicUnitTableRegion;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitFinderScenario;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks which trigger accesses of a game's EUD address space are native access failures, and the live unit state that its classic regions serve.
 */
@RequiresInstallation
@ExtendWith(GameResourcesExtension.class)
final class ClassicAddressSpaceTest {
    /**
     * The extended Deaths address that the replay branch of the civilization defense map reads: above every region of the
     * map.
     */
    private static final int ADDRESS_ABOVE_MAP_REGIONS = 0x28D860A8;
    /**
     * The address of the Storm archive list head, which a game without a source file does not serve.
     */
    private static final int STORM_ARCHIVE_HEAD = 0x1505ADF8;

    private static final int STAMPS = 0x6BD3D0;
    private static final int ALERT_MARK_WORD_OFFSET = 0x84;
    private static final int ALERT_MARK_SHIFT = 3 * Byte.SIZE;
    private static final int ALERT_MARK_MASK = 0xFF000000;
    private static final int ATTACK_ALERT_MARK = 100;
    private static final int FULL_ALERT_MARK = 255;
    private static final int IGNORED_WRITE_SAMPLE = 7;
    /**
     * The Terran console area table, a type-7 record region, and its size.
     */
    private static final int CONSOLE_AREAS = 0x515300;
    private static final int CONSOLE_AREAS_SIZE = 0x48;

    @Test
    void read_for_trigger_should_report_access_failures_when_the_game_has_no_source_file(@NotNull InstallationTestResources resources) throws Exception {
        ReplayFile replay = ReplayFile.parse(GameTestResources.readAllBytes(GameTestResources.REPLAY_CIVILIZATION_DEFENSE_EUD_FAILURE));
        byte[] mapData = replay.getMapData();

        Assertions.assertNotNull(mapData);

        ReplayRuntime runtime = new ReplayRuntime(replay, ChkFile.parseTolerant(mapData), resources.getGameData(),
                                                  RunningGameScenario.VIEWPORT_WIDTH, RunningGameScenario.VIEWPORT_HEIGHT,
                                                  RunningGameScenario.INSTRUCTION_LIMIT);

        runtime.start(0, 0);

        Assertions.assertNull(runtime.getFailure());

        Game game = runtime.getGame();
        ClassicAddressSpace space = ClassicAddressSpace.create(game.getTriggerContext(), game);
        List<Integer> failures = new ArrayList<>();

        space.setFailureListener(failures::add);

        Assertions.assertEquals(0, space.readForTrigger(ADDRESS_ABOVE_MAP_REGIONS));
        Assertions.assertEquals(0, space.readForTrigger(ClassicStormArchives.HEAD_BASE), "no Storm archive list without a source file");
        Assertions.assertEquals(List.of(ADDRESS_ABOVE_MAP_REGIONS, STORM_ARCHIVE_HEAD), failures);
    }

    @Test
    void attack_alert_marks_and_finder_stamps_are_served_by_classic_regions() throws Exception {
        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            Game game = UnitFinderScenario.start(gameData);
            UnitPool pool = game.getUnitSystem().getUnitPool();
            UnitSpatialIndex finder = game.getUnitSystem().getUnitSpatialIndex();

            Assertions.assertNotNull(pool);
            Assertions.assertNotNull(finder);

            Unit active = pool.getUnit(pool.getFirstCreatedIndex(true));

            Assertions.assertNotNull(active.getSpriteOrNull());

            ClassicAddressSpace space = ClassicAddressSpace.create(game);
            int counterBefore = game.getUnitSystem().getUnitFinderSearchCounter().getValue();
            int markerAddress = STAMPS + Integer.BYTES * active.getIndex();
            int markWordAddress = ClassicUnitTableRegion.BASE + (active.getIndex() - 1) * ClassicUnitTableRegion.ENTRY_SIZE + ALERT_MARK_WORD_OFFSET;

            active.setAlertMark(0);
            game.getLocalInterfaceEffects().markAttackAlertArea(active.getPositionX(), active.getPositionY());

            Assertions.assertAll(
                    () -> Assertions.assertEquals(counterBefore + 1, game.getUnitSystem().getUnitFinderSearchCounter().getValue()),
                    () -> Assertions.assertEquals(counterBefore + 1, space.readDword(markerAddress)),
                    () -> Assertions.assertEquals(ATTACK_ALERT_MARK, space.readDword(markWordAddress) >>> ALERT_MARK_SHIFT)
            );

            space.writeDword(markerAddress, IGNORED_WRITE_SAMPLE);

            Assertions.assertEquals(counterBefore + 1, space.readDword(markerAddress), "The live finder marker region is write-ignored.");

            game.getLocalInterfaceEffects().decrementAttackAlertMarks();

            Assertions.assertEquals(ATTACK_ALERT_MARK - 1, space.readDword(markWordAddress) >>> ALERT_MARK_SHIFT);

            int previousWord = space.readDword(markWordAddress);

            space.writeDword(markWordAddress, previousWord & ~ALERT_MARK_MASK | ALERT_MARK_MASK);

            Assertions.assertEquals(FULL_ALERT_MARK, active.getAlertMark());

            game.getLocalInterfaceEffects().decrementAttackAlertMarks();

            Assertions.assertEquals(FULL_ALERT_MARK - 1, space.readDword(markWordAddress) >>> ALERT_MARK_SHIFT);
        }
    }

    @Test
    void type_7_records_are_emulation_storage_that_a_memory_source_may_replace() throws Exception {
        try (GameData gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            Game game = UnitFinderScenario.start(gameData);
            ClassicAddressSpace space = ClassicAddressSpace.create(game);
            ClassicRegion records = space.findRegion(CONSOLE_AREAS);

            Assertions.assertNotNull(records);
            Assertions.assertTrue(records.isEmulatorStorage());

            byte[] table = new byte[CONSOLE_AREAS_SIZE];
            ClassicMemorySink sink = new ClassicMemorySink(space);

            Assertions.assertEquals(CONSOLE_AREAS_SIZE, sink.read(CONSOLE_AREAS, table, 0, table.length));

            sink.add(new ClassicStorageRegion(CONSOLE_AREAS, table.clone()));
            space.writeDword(CONSOLE_AREAS, IGNORED_WRITE_SAMPLE);

            Assertions.assertEquals(IGNORED_WRITE_SAMPLE, space.readDword(CONSOLE_AREAS));
            Assertions.assertFalse(space.findRegion(CONSOLE_AREAS).isEmulatorStorage());
        }
    }
}
