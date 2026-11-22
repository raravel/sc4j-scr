package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.save.SaveReferences;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.fogofwarlabs.sc4j.scr.game.test.UnitTestUtils;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Checks unit lifecycle records, the tracked Medic, and the records' saved-format lists and retained payload bytes, whose layout is a save contract.
 */
final class ComputerLifecycleHookTest {
    private static final int TOWN_POSITION_X = 3808;
    private static final int TOWN_POSITION_Y = 2384;
    private static final int TRAINED_SLOT = 1;
    private static final int OBSERVER_SLOT = 2;
    private static final int MINERAL_SLOT = 3;
    private static final int WORKER_SLOT = 2;
    private static final int SCENARIO_POSITION = 320;
    private static final int THREE_TURRETS = 3;
    private static final int FOUR_TURRETS = 4;
    private static final int BUILDING_POOL_COUNT = 1000;
    private static final int GUARD_POOL_COUNT = 1000;
    private static final int BUILDING_RECORD_SIZE = 0x2C;
    private static final int GUARD_RECORD_SIZE = 0x20;
    private static final int GUARD_UNIT = 0x0C;
    private static final int GUARD_TYPE = 0x10;
    private static final int GUARD_POSITION_X = 0x12;
    private static final int GUARD_POSITION_Y = 0x14;
    /**
     * The bytes of the two guard list heads of one owner.
     */
    private static final int OWNER_GUARD_HEADS_SIZE = Integer.BYTES * 2;
    private static final int SAVED_BUILDING_WORD = 0x0E;
    private static final int SAVED_GUARD_WORD = 0x0A;
    private static final int SAVED_TOWN_BYTE = 0x1F;
    private static final int SAVED_TOWN_SIZE = 0x1CC;
    private static final int TOWN_POOL_COUNT = 100;
    private static final int SAVED_WORD_VALUE = 0xB6D3;
    private static final int SAVED_BYTE_VALUE = 0xE7;

    private static final int OBSERVER_REQUEST_KIND = 8;
    private static final int GHOST_TYPE = 1;
    private static final int MISSILE_TURRET_TYPE = 124;

    /**
     * A check run with one computer producer of the running game.
     */
    private interface ProducerCase {
        void run(@NotNull Game game, @NotNull ComputerPlayerPhase phase, @NotNull Unit producer);
    }

    @Test
    void notify_queue_slot_cleared_should_clear_only_trained_slot_of_building_record() {
        ComputerTown town = new ComputerTown(0);

        town.initialize(0, TOWN_POSITION_X, TOWN_POSITION_Y);

        ComputerUnitRecord record = new ComputerUnitRecord(ComputerUnitRecord.KIND_BUILDING, 0);

        record.town                        = town;
        record.queueKinds[TRAINED_SLOT]    = ComputerPlayer.REQUEST_KIND_TOWN;
        record.queueTargets[TRAINED_SLOT]  = town;
        record.queueKinds[OBSERVER_SLOT]   = ComputerPlayer.REQUEST_KIND_OBSERVER;
        record.queueTargets[OBSERVER_SLOT] = town;

        Unit producer = UnitTestUtils.createUnit(1);

        producer.setComputerRecord(record);

        ComputerPlayerPhase.notifyQueueSlotCleared(producer, TRAINED_SLOT);

        Assertions.assertEquals(0, record.queueKinds[TRAINED_SLOT]);
        Assertions.assertNull(record.queueTargets[TRAINED_SLOT]);
        Assertions.assertEquals(OBSERVER_REQUEST_KIND, record.queueKinds[OBSERVER_SLOT]);
        Assertions.assertSame(town, record.queueTargets[OBSERVER_SLOT]);
    }

    @Test
    void notify_loaded_should_release_reserved_worker_only_for_scv() {
        ComputerTown town = new ComputerTown(0);

        town.initialize(0, TOWN_POSITION_X, TOWN_POSITION_Y);

        ComputerUnitRecord record = new ComputerUnitRecord(ComputerUnitRecord.KIND_WORKER, 0);

        record.town = town;

        Unit drone = UnitTestUtils.createUnit(1);

        drone.setUnitId(ScrUnitIds.ZERG_DRONE);
        drone.setComputerRecord(record);
        town.reservedWorker = drone;

        ComputerPlayerPhase.notifyLoaded(drone, ScrUnitIds.ZERG_DRONE);

        Assertions.assertSame(drone, town.reservedWorker);

        Unit worker = UnitTestUtils.createUnit(WORKER_SLOT);

        worker.setUnitId(ScrUnitIds.TERRAN_SCV);
        worker.setComputerRecord(record);
        town.reservedWorker = worker;

        ComputerPlayerPhase.notifyLoaded(worker, ScrUnitIds.TERRAN_SCV);

        Assertions.assertNull(town.reservedWorker);
    }

    @Test
    void reset_resource_and_link_state_should_clear_computer_resource_claim() {
        Unit mineral = UnitTestUtils.createUnit(MINERAL_SLOT);

        mineral.setResourceClaimedByComputer(true);

        Assertions.assertTrue(mineral.isResourceClaimedByComputer());

        mineral.resetResourceAndLinkState();

        Assertions.assertFalse(mineral.isResourceClaimedByComputer());
    }

    @Test
    @RequiresInstallation
    void register_created_unit_should_skip_ghost_after_failed_binding_with_three_turrets() throws IOException {
        this.runTurretCase(THREE_TURRETS, true, false, false, false);
    }

    @Test
    @RequiresInstallation
    void register_created_unit_should_add_ghost_after_failed_binding_with_four_turrets() throws IOException {
        this.runTurretCase(FOUR_TURRETS, true, false, false, true);
    }

    @Test
    @RequiresInstallation
    void register_created_unit_should_count_worker_turret_queue_after_failed_binding() throws IOException {
        this.runTurretCase(THREE_TURRETS, true, true, false, true);
    }

    @Test
    @RequiresInstallation
    void register_created_unit_should_skip_worker_queue_with_existing_order_target() throws IOException {
        this.runTurretCase(THREE_TURRETS, true, true, true, false);
    }

    @Test
    @RequiresInstallation
    void register_created_unit_should_add_ghost_when_fourth_turret_binds() throws IOException {
        this.runTurretCase(THREE_TURRETS, false, false, false, true);
    }

    @Test
    @RequiresInstallation
    void register_completed_unit_should_reject_worker_of_military_or_guard_producer() throws IOException {
        this.runRecordProducerCases((game, phase, producer) -> {
            Unit worker = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_SCV,
                                                         SCENARIO_POSITION, SCENARIO_POSITION, RunningGameScenario.COMPUTER);

            Assertions.assertThrows(UnsupportedOperationException.class,
                                    () -> phase.registerCompletedUnit(producer, worker, RunningGameScenario.INSTRUCTION_LIMIT));
            Assertions.assertNull(worker.getComputerRecord());
        });
    }

    @Test
    @RequiresInstallation
    void register_created_unit_should_reject_only_town_building_of_military_or_guard_producer() throws IOException {
        this.runRecordProducerCases((game, phase, producer) -> {
            Unit depot = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_SUPPLY_DEPOT,
                                                        SCENARIO_POSITION, SCENARIO_POSITION, RunningGameScenario.COMPUTER);
            Unit marine = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_MARINE,
                                                         SCENARIO_POSITION, SCENARIO_POSITION, RunningGameScenario.COMPUTER);

            Assertions.assertThrows(UnsupportedOperationException.class,
                                    () -> phase.registerCreatedUnit(producer, depot, RunningGameScenario.INSTRUCTION_LIMIT));
            Assertions.assertDoesNotThrow(() -> phase.registerCreatedUnit(producer, marine, RunningGameScenario.INSTRUCTION_LIMIT));
            Assertions.assertNull(depot.getComputerRecord());
            Assertions.assertNull(marine.getComputerRecord());
        });
    }

    @Test
    @RequiresInstallation
    void register_hooks_bind_worker_and_town_building_to_town_of_town_record_producer() throws IOException {
        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = RunningGameScenario.start(data);

            RunningGameScenario.runInCommandDispatch(runtime, () -> {
                Game game = runtime.getGame();
                ComputerPlayerPhase phase = game.getComputerPlayerPhase();
                UnitPool pool = game.getUnitSystem().requireUnitPool();

                Assertions.assertNotNull(phase);

                Unit producer = null;

                for (int index = pool.getFirstOwnerIndex(RunningGameScenario.COMPUTER); index != 0; index = pool.getNextOwnerIndex(index)) {
                    ComputerUnitRecord record = pool.getUnit(index).getComputerRecord();

                    if (record != null && record.town != null) {
                        producer = pool.getUnit(index);

                        break;
                    }
                }

                Assertions.assertNotNull(producer);

                ComputerTown town = producer.requireComputerRecord().requireTown();
                Unit worker = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_SCV,
                                                             SCENARIO_POSITION, SCENARIO_POSITION, RunningGameScenario.COMPUTER);
                Unit depot = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_SUPPLY_DEPOT,
                                                            SCENARIO_POSITION, SCENARIO_POSITION, RunningGameScenario.COMPUTER);

                phase.registerCompletedUnit(producer, worker, RunningGameScenario.INSTRUCTION_LIMIT);
                phase.registerCreatedUnit(producer, depot, RunningGameScenario.INSTRUCTION_LIMIT);

                Assertions.assertEquals(ComputerUnitRecord.KIND_WORKER, worker.requireComputerRecord().getKind());
                Assertions.assertSame(town, worker.requireComputerRecord().town);
                Assertions.assertSame(worker.getComputerRecord(), town.firstWorker);
                Assertions.assertEquals(ComputerUnitRecord.KIND_BUILDING, depot.requireComputerRecord().getKind());
                Assertions.assertSame(town, depot.requireComputerRecord().town);
                Assertions.assertSame(depot.getComputerRecord(), town.firstBuilding);
            });
        }
    }

    @Test
    @RequiresInstallation
    void computer_pool_reuse_preserves_imported_building_guard_and_activated_town_payloads() throws IOException {
        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = RunningGameScenario.start(data);

            RunningGameScenario.runInCommandDispatch(runtime, () -> {
                Game game = runtime.getGame();
                ComputerPlayerPhase phase = game.getComputerPlayerPhase();

                Assertions.assertNotNull(phase);

                ComputerTown town = phase.getFirstTown(RunningGameScenario.COMPUTER);

                Assertions.assertNotNull(town);

                ByteBuffer workers = ByteBuffer.allocate(ComputerPlayerPhase.SAVED_WORKERS_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer buildings = ByteBuffer.allocate(ComputerPlayerPhase.SAVED_BUILDINGS_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer towns = ByteBuffer.allocate(ComputerPlayerPhase.SAVED_TOWNS_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer townHeads = ByteBuffer.allocate(ComputerPlayerPhase.SAVED_TOWN_HEADS_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer guards = ByteBuffer.allocate(ComputerPlayerPhase.SAVED_GUARDS_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer guardHeads = ByteBuffer.allocate(ComputerPlayerPhase.SAVED_GUARD_HEADS_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                SaveReferences references = new SaveReferences(game);

                phase.getMilitary().releaseEmptyGuards(RunningGameScenario.COMPUTER);
                phase.writeSaveTowns(workers, buildings, towns, townHeads, references);
                phase.writeSaveGuards(guards, guardHeads, references);

                int buildingSlot = buildings.getInt(BUILDING_POOL_COUNT * BUILDING_RECORD_SIZE) - 1;
                int townSlot = towns.getInt(TOWN_POOL_COUNT * SAVED_TOWN_SIZE) - 1;
                int guardSlot = guards.getInt(GUARD_POOL_COUNT * GUARD_RECORD_SIZE) - 1;

                Assertions.assertTrue(buildingSlot >= 0);
                Assertions.assertTrue(townSlot >= 0);
                Assertions.assertTrue(guardSlot >= 0);

                buildings.putShort(buildingSlot * BUILDING_RECORD_SIZE + SAVED_BUILDING_WORD, (short) SAVED_WORD_VALUE);
                towns.put(townSlot * SAVED_TOWN_SIZE + SAVED_TOWN_BYTE, (byte) SAVED_BYTE_VALUE);
                guards.putShort(guardSlot * GUARD_RECORD_SIZE + SAVED_GUARD_WORD, (short) SAVED_WORD_VALUE);

                phase.readSaveTowns(workers, buildings, towns, townHeads, references);
                phase.readSaveGuards(guards, guardHeads, references);

                buildings.putShort(buildingSlot * BUILDING_RECORD_SIZE + SAVED_BUILDING_WORD, (short) 0);
                towns.put(townSlot * SAVED_TOWN_SIZE + SAVED_TOWN_BYTE, (byte) 0);
                guards.putShort(guardSlot * GUARD_RECORD_SIZE + SAVED_GUARD_WORD, (short) 0);

                ComputerTown activated = phase.startTown(RunningGameScenario.COMPUTER, SCENARIO_POSITION, SCENARIO_POSITION,
                                                         0, RunningGameScenario.INSTRUCTION_LIMIT);

                Assertions.assertNotNull(activated);
                Assertions.assertEquals(townSlot, activated.getPoolIndex());

                Unit building = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_SUPPLY_DEPOT,
                                                               SCENARIO_POSITION, SCENARIO_POSITION, RunningGameScenario.COMPUTER);

                phase.bindUnit(town, building, RunningGameScenario.INSTRUCTION_LIMIT);

                Assertions.assertNotNull(building.getComputerRecord());
                Assertions.assertEquals(buildingSlot, building.getComputerRecord().getPoolIndex());

                phase.releaseBuildingRecord(building);

                Unit replacement = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_SUPPLY_DEPOT,
                                                                  SCENARIO_POSITION, SCENARIO_POSITION, RunningGameScenario.COMPUTER);

                phase.bindUnit(town, replacement, RunningGameScenario.INSTRUCTION_LIMIT);

                Assertions.assertNotNull(replacement.getComputerRecord());
                Assertions.assertEquals(buildingSlot, replacement.getComputerRecord().getPoolIndex());

                phase.getMilitary().addEmptyGuard(RunningGameScenario.COMPUTER, ScrUnitIds.TERRAN_GHOST, SCENARIO_POSITION, SCENARIO_POSITION);
                phase.getMilitary().releaseEmptyGuards(RunningGameScenario.COMPUTER);
                phase.writeSaveGuards(guards, guardHeads, references);

                Assertions.assertEquals(guardSlot + 1, guards.getInt(GUARD_POOL_COUNT * GUARD_RECORD_SIZE));
                Assertions.assertEquals(SAVED_WORD_VALUE, Short.toUnsignedInt(guards.getShort(guardSlot * GUARD_RECORD_SIZE + SAVED_GUARD_WORD)));

                phase.getMilitary().addEmptyGuard(RunningGameScenario.COMPUTER, ScrUnitIds.TERRAN_GHOST, SCENARIO_POSITION, SCENARIO_POSITION);
                phase.writeSaveGuards(guards, guardHeads, references);
                phase.writeSaveTowns(workers, buildings, towns, townHeads, references);

                Assertions.assertEquals(guardSlot + 1, guardHeads.getInt(Integer.BYTES));
                Assertions.assertEquals(SAVED_WORD_VALUE, Short.toUnsignedInt(guards.getShort(guardSlot * GUARD_RECORD_SIZE + SAVED_GUARD_WORD)));
                Assertions.assertEquals(SAVED_WORD_VALUE,
                                        Short.toUnsignedInt(buildings.getShort(buildingSlot * BUILDING_RECORD_SIZE + SAVED_BUILDING_WORD)));
                Assertions.assertEquals(SAVED_BYTE_VALUE, Byte.toUnsignedInt(towns.get(townSlot * SAVED_TOWN_SIZE + SAVED_TOWN_BYTE)));
            });
        }
    }

    @Test
    @RequiresInstallation
    void neutral_medic_death_and_owner_change_keep_tracked_computer_medic() throws IOException {
        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = RunningGameScenario.start(data);

            RunningGameScenario.runInCommandDispatch(runtime, () -> {
                Game game = runtime.getGame();
                ComputerPlayerPhase phase = game.getComputerPlayerPhase();

                Assertions.assertNotNull(phase);

                Unit computerMedic = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_MEDIC,
                                                                    SCENARIO_POSITION, SCENARIO_POSITION, RunningGameScenario.COMPUTER);

                phase.adoptUnit(computerMedic, RunningGameScenario.INSTRUCTION_LIMIT);

                Unit tracked = phase.getPlayer(RunningGameScenario.COMPUTER).getMedic();

                Assertions.assertNotNull(tracked);

                Unit dying = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_MEDIC,
                                                            SCENARIO_POSITION, SCENARIO_POSITION, PlayerTable.NEUTRAL_PLAYER_ID);
                Unit transferred = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_MEDIC,
                                                                  SCENARIO_POSITION, SCENARIO_POSITION, PlayerTable.NEUTRAL_PLAYER_ID);

                game.getUnitLifecycle().kill(dying, RunningGameScenario.INSTRUCTION_LIMIT);
                game.getUnitOwnership().setOwner(transferred, RunningGameScenario.HUMAN, false, RunningGameScenario.INSTRUCTION_LIMIT);

                Assertions.assertSame(tracked, phase.getPlayer(RunningGameScenario.COMPUTER).getMedic());
                Assertions.assertEquals(RunningGameScenario.HUMAN, transferred.getOwnerIndex());
            });
        }
    }

    private void runTurretCase(int turretCount, boolean exhaustBuildings, boolean queueTurret, boolean existingTarget, boolean addsGuard) throws IOException {
        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = RunningGameScenario.start(data);

            RunningGameScenario.runInCommandDispatch(runtime, () -> {
                Game game = runtime.getGame();
                ComputerPlayerPhase phase = game.getComputerPlayerPhase();
                UnitPool pool = game.getUnitSystem().requireUnitPool();

                Assertions.assertNotNull(phase);

                ComputerTown town = null;
                Unit producer = null;

                for (int index = pool.getFirstOwnerIndex(RunningGameScenario.COMPUTER); index != 0; index = pool.getNextOwnerIndex(index)) {
                    Unit candidate = pool.getUnit(index);
                    ComputerUnitRecord record = candidate.getComputerRecord();

                    if (record != null && record.town != null) {
                        town     = record.town;
                        producer = candidate;

                        break;
                    }
                }

                Assertions.assertNotNull(town);
                Assertions.assertNotNull(producer);

                Unit townProducer = producer;

                for (ComputerUnitRecord record = town.firstBuilding; record != null; record = record.next) {
                    Assertions.assertNotEquals(MISSILE_TURRET_TYPE, record.requireUnit().getUnitId());
                }

                for (int index = 0; index < turretCount; index++) {
                    Unit turret = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_MISSILE_TURRET,
                                                                 SCENARIO_POSITION, SCENARIO_POSITION, RunningGameScenario.COMPUTER);

                    if (turret.getComputerRecord() == null) {
                        phase.bindUnit(town, turret, RunningGameScenario.INSTRUCTION_LIMIT);
                    }

                    Assertions.assertNotNull(turret.getComputerRecord());
                    Assertions.assertSame(town, turret.getComputerRecord().town);
                }

                if (queueTurret) {
                    Unit worker = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_SCV,
                                                                 SCENARIO_POSITION, SCENARIO_POSITION, RunningGameScenario.COMPUTER);

                    if (worker.getComputerRecord() == null) {
                        phase.bindUnit(town, worker, RunningGameScenario.INSTRUCTION_LIMIT);
                    }

                    worker.setFirstBuildQueueType(ScrUnitIds.TERRAN_MISSILE_TURRET);
                    worker.setBuildQueueIndex(0);
                    worker.setPrimaryOrder(ScrOrderIds.PLACE_BUILDING);
                    worker.setOrderTargetUnit(existingTarget ? producer : null);
                    worker.setCurrentBuildUnit(null);

                    Assertions.assertEquals(existingTarget ? 0 : 1, game.getComputerUnits().countProducing(worker, ScrUnitIds.TERRAN_MISSILE_TURRET));
                }

                ByteBuffer workers = ByteBuffer.allocate(ComputerPlayerPhase.SAVED_WORKERS_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer buildings = ByteBuffer.allocate(ComputerPlayerPhase.SAVED_BUILDINGS_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer towns = ByteBuffer.allocate(ComputerPlayerPhase.SAVED_TOWNS_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer townHeads = ByteBuffer.allocate(ComputerPlayerPhase.SAVED_TOWN_HEADS_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                SaveReferences references = new SaveReferences(game);

                phase.writeSaveTowns(workers, buildings, towns, townHeads, references);

                if (exhaustBuildings) {
                    while (buildings.getInt(BUILDING_POOL_COUNT * BUILDING_RECORD_SIZE) != 0) {
                        Unit building = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_SUPPLY_DEPOT,
                                                                       SCENARIO_POSITION, SCENARIO_POSITION, RunningGameScenario.COMPUTER);

                        if (building.getComputerRecord() == null) {
                            phase.bindUnit(town, building, RunningGameScenario.INSTRUCTION_LIMIT);
                        }

                        Assertions.assertNotNull(building.getComputerRecord());

                        phase.writeSaveTowns(workers, buildings, towns, townHeads, references);
                    }
                }

                Unit turret = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_MISSILE_TURRET,
                                                             SCENARIO_POSITION, SCENARIO_POSITION, RunningGameScenario.COMPUTER);
                ByteBuffer guards = ByteBuffer.allocate(ComputerPlayerPhase.SAVED_GUARDS_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer guardHeads = ByteBuffer.allocate(ComputerPlayerPhase.SAVED_GUARD_HEADS_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                int ownerHead = RunningGameScenario.COMPUTER * OWNER_GUARD_HEADS_SIZE + Integer.BYTES;

                phase.writeSaveGuards(guards, guardHeads, references);

                int previousHead = guardHeads.getInt(ownerHead);

                Assertions.assertDoesNotThrow(() -> phase.registerCreatedUnit(townProducer, turret, RunningGameScenario.INSTRUCTION_LIMIT));

                phase.writeSaveGuards(guards, guardHeads, references);

                int currentHead = guardHeads.getInt(ownerHead);

                if (addsGuard) {
                    Assertions.assertNotEquals(previousHead, currentHead);

                    int offset = (currentHead - 1) * GUARD_RECORD_SIZE;

                    Assertions.assertEquals(previousHead, guards.getInt(offset));
                    Assertions.assertEquals(0, guards.getInt(offset + GUARD_UNIT));
                    Assertions.assertEquals(GHOST_TYPE, Short.toUnsignedInt(guards.getShort(offset + GUARD_TYPE)));

                    Sprite sprite = turret.getSpriteOrNull();

                    Assertions.assertNotNull(sprite);
                    Assertions.assertEquals((short) sprite.getX(), guards.getShort(offset + GUARD_POSITION_X));
                    Assertions.assertEquals((short) sprite.getY(), guards.getShort(offset + GUARD_POSITION_Y));
                } else {
                    Assertions.assertEquals(previousHead, currentHead);
                }

                if (exhaustBuildings) {
                    Assertions.assertNull(turret.getComputerRecord());

                    phase.writeSaveTowns(workers, buildings, towns, townHeads, references);

                    Assertions.assertEquals(0, buildings.getInt(BUILDING_POOL_COUNT * BUILDING_RECORD_SIZE));
                } else {
                    Assertions.assertNotNull(turret.getComputerRecord());
                    Assertions.assertSame(town, turret.getComputerRecord().town);
                }
            });
        }
    }

    /**
     * Runs the check with a computer Marine holding a military record and with a computer Firebat holding a guard record.
     */
    private void runRecordProducerCases(@NotNull ProducerCase check) throws IOException {
        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            ReplayRuntime runtime = RunningGameScenario.start(data);

            RunningGameScenario.runInCommandDispatch(runtime, () -> {
                Game game = runtime.getGame();
                ComputerPlayerPhase phase = game.getComputerPlayerPhase();

                Assertions.assertNotNull(phase);

                Unit military = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_MARINE,
                                                               SCENARIO_POSITION, SCENARIO_POSITION, RunningGameScenario.COMPUTER);
                Unit guard = RunningGameScenario.createUnit(game, ScrUnitIds.TERRAN_FIREBAT,
                                                            SCENARIO_POSITION, SCENARIO_POSITION, RunningGameScenario.COMPUTER);
                int region = phase.getRegions().getIndex(RunningGameScenario.COMPUTER, game.getComputerTerrain().getUnitRegion(military));

                phase.getMilitary().addToRegion(phase, region, military, true, RunningGameScenario.INSTRUCTION_LIMIT);
                phase.getMilitary().addGuard(guard);

                Assertions.assertEquals(ComputerUnitRecord.KIND_MILITARY, military.requireComputerRecord().getKind());
                Assertions.assertEquals(ComputerUnitRecord.KIND_GUARD, guard.requireComputerRecord().getKind());

                check.run(game, phase, military);
                check.run(game, phase, guard);
            });
        }
    }
}
