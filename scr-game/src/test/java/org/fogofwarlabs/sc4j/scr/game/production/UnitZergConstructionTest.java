package org.fogofwarlabs.sc4j.scr.game.production;

import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.dat.DatTable;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.data.format.replay.SelectionRequest;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.game.ai.UnitStrengthTable;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepField;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepRecession;
import org.fogofwarlabs.sc4j.scr.game.order.OrderPool;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.player.ProductionStatistics;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.presentation.CommandErrorEvents;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.state.GameRulesVersion;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMapScenario;
import org.fogofwarlabs.sc4j.scr.game.terrain.MapTileFlags;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainContours;
import org.fogofwarlabs.sc4j.scr.game.test.PlayerProgressTestUtils;
import org.fogofwarlabs.sc4j.scr.game.test.UnitTestUtils;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

/**
 * Checks the Zerg building morph lifecycle, the Drone build of an emptied queue slot, the messages of a failed Drone build, the refund and the
 * order capacity test of a canceled Egg, the queue slot clear of a canceled Cocoon, and the exit test of a canceled Nydus Canal.
 * <p>
 * The units.dat memory layout is the contract of the Drone build test, because replays reproduce the value it gives: the construction image of the
 * empty type {@code 228} is the Unit Direction bytes of types {@code 0..3}, an images.dat entry past the images.dat memory. The same holds for the
 * refund of an Egg with an empty queue slot: the empty type {@code 228} refunds the costs that the units.dat memory gives it. The unit record layout
 * is the contract of the Cocoon test: queue index {@code 10} is the EUD-visible remaining build time WORD.
 */
@RequiresInstallation
final class UnitZergConstructionTest {
    private static final int HATCHERY_TILE = 16;
    private static final int HATCHERY_X = HATCHERY_TILE * TerrainTileset.TILE_SIZE;

    /**
     * The divisor from the tile size to the offset of the tile center.
     */
    private static final int TILE_CENTER_DIVISOR = 2;
    /**
     * The Y position of the Hatchery site, at the vertical center of its tile row.
     */
    private static final int HATCHERY_Y = HATCHERY_TILE * TerrainTileset.TILE_SIZE + TerrainTileset.TILE_SIZE / TILE_CENTER_DIVISOR;
    /**
     * The center of the Nydus Canal site, whose {@code 2} x {@code 2} tile footprint starts {@code 1} tile before it on both axes.
     */
    private static final int NYDUS_X = HATCHERY_TILE * TerrainTileset.TILE_SIZE;
    private static final int NYDUS_Y = HATCHERY_TILE * TerrainTileset.TILE_SIZE;
    /**
     * The pool index of the unit that stands in for a Nydus Canal's exit.
     */
    private static final int NYDUS_EXIT_POOL_INDEX = 2;
    /**
     * The pool index of the tested unit: the Egg, Cocoon, Hatchery or Drone.
     */
    private static final int UNIT_POOL_INDEX = 1;
    /**
     * The instruction limit of the unit initialization, construction, morph and cancellation calls.
     */
    private static final int INSTRUCTION_LIMIT = 10000;

    private static final int MAP_DIMENSION = 64;
    private static final int MAP_TILE_COUNT = MAP_DIMENSION * MAP_DIMENSION;

    /**
     * The tileset of every test map and of the terrain tables read for it.
     */
    private static final int TILESET = 0;
    /**
     * The tile identifier of every tile of the world: tile group {@code 8}, without creep.
     */
    private static final int PLAIN_TILE = 128;
    /**
     * The capacities of the unit, sprite, image, order and sprite effect pools.
     */
    private static final int UNIT_CAPACITY = 4;
    private static final int SPRITE_POOL_CAPACITY = 16;
    private static final int IMAGE_POOL_CAPACITY = 64;
    private static final int ORDER_POOL_CAPACITY = 32;
    private static final int SPRITE_EFFECT_POOL_CAPACITY = 4;
    /**
     * The width of the unit handles of the selection commands, a WORD.
     */
    private static final int HANDLE_WIDTH = Short.BYTES;

    /**
     * The test player, the owner of the test units.
     */
    private static final int PLAYER = 0;
    /**
     * The player identifier of the test player.
     */
    private static final int PLAYER_ID = 0;
    /**
     * The team of the test player.
     */
    private static final int TEAM = 0;
    /**
     * The local player comparison value of the unit creation context and the selection, a slot that owns no test unit.
     */
    private static final int LOCAL_PLAYER = 8;
    /**
     * The team size of the selection outside a team game.
     */
    private static final int NO_TEAM_SIZE = 0;
    /**
     * The local vision comparison mask and the replay visibility mask: the bit of the test player.
     */
    private static final int LOCAL_COMPARISON_MASK = 1 << PLAYER;
    private static final int REPLAY_VISIBILITY_MASK = 1 << PLAYER;
    /**
     * The C runtime random state of the creep initialization.
     */
    private static final int CREEP_RANDOM_STATE = 1;
    /**
     * The starting minerals and gas of the test player, and a mineral count that cannot pay for a Lair.
     */
    private static final int STARTING_MINERALS = 1000;
    private static final int STARTING_GAS = 1000;
    private static final int INSUFFICIENT_MINERALS = 10;
    /**
     * A canceled Drone-built building refunds these quarters of its cost.
     */
    private static final int REFUND_QUARTERS = 3;
    /**
     * The divisor that turns a count of quarters into that fraction of a cost.
     */
    private static final int QUARTER_DIVISOR = 4;
    /**
     * The build queue index whose WORD is the energy, the first WORD after the {@code 5} queue slots.
     */
    private static final int ENERGY_QUEUE_INDEX = 5;
    /**
     * The build queue index whose WORD is the remaining build time.
     */
    private static final int BUILD_TIME_QUEUE_INDEX = 10;

    /**
     * The hit points the Hatchery lacks, in the fixed-point hit point unit.
     */
    private static final int HIT_POINT_DEFICIT = 0x2800;
    /**
     * The resource and larva side counts of the Hatchery, which the morph keeps.
     */
    private static final int RESOURCES = 3;
    private static final int SECOND_LARVA_SIDE_COUNT = 5;
    private static final int THIRD_LARVA_SIDE_COUNT = 7;
    private static final int FOURTH_LARVA_SIDE_COUNT = 11;
    /**
     * The frames after which the test stops waiting for the morph.
     */
    private static final int MORPH_FRAME_LIMIT = 20000;
    /**
     * The morph state that waits for the finishing animation.
     */
    private static final int MORPH_STATE_FINISHING = 6;
    /**
     * The empty unit type, the type of an emptied queue slot.
     */
    private static final int EMPTY_UNIT_TYPE = 228;
    /**
     * The completed status flag.
     */
    private static final int COMPLETED_FLAG = 1;
    /**
     * The tile group bits of a creep tile.
     */
    private static final int CREEP_TILE_GROUP = 0x10;
    /**
     * The tile group bits of a tile identifier.
     */
    private static final int TILE_GROUP_MASK = 0x7FF0;
    /**
     * The unit types that the morphs, the cancellations and the Drone builds leave.
     */
    private static final int EGG_TYPE = 36;
    private static final int DRONE_TYPE = 41;
    private static final int MUTALISK_TYPE = 43;
    private static final int HATCHERY_TYPE = 131;
    private static final int LAIR_TYPE = 132;
    private static final int NYDUS_CANAL_TYPE = 134;
    /**
     * The orders of a killed unit, of a queued building morph and of a charged building morph.
     */
    private static final int DIE_ORDER = 0;
    private static final int ZERG_BUILDING_MORPH_ORDER = 43;
    private static final int INCOMPLETE_MORPHING_ORDER = 45;
    /**
     * The order that a failed Drone build queues.
     */
    private static final int RESET_COLLISION_ORDER = 150;
    /**
     * The {@code stat_txt} id of "You can't build there.".
     */
    private static final int CANNOT_BUILD_THERE_TEXT = 0x358;
    /**
     * The failure text of a DAT read outside the DAT memory.
     */
    private static final @NotNull String DAT_MEMORY_FAILURE = "outside the DAT memory";

    private static @Nullable GameData gameData;

    @BeforeAll
    static void loadGameData() throws IOException {
        gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath());
    }

    @AfterAll
    static void closeGameData() {
        if (gameData != null) {
            gameData.close();
        }
    }

    private static @NotNull Game createWorld() throws IOException {
        int[] tiles = new int[MAP_TILE_COUNT];

        Arrays.fill(tiles, PLAIN_TILE);

        int[] flags = new int[MAP_TILE_COUNT];

        Arrays.fill(flags, MapTileFlags.NOT_VISIBLE_OR_EXPLORED | MapTileFlags.WALKABLE);

        GameMap map = new GameMap(MAP_DIMENSION, MAP_DIMENSION, TILESET, tiles, flags);

        GameMapScenario.setOverlays(map, new int[MAP_TILE_COUNT]);

        Game game = Game.create(gameData, map, new LcgRandom(0));

        game.getUnitSystem().initializeUnitPool(UNIT_CAPACITY);
        game.getSpriteSystem().initializeSpritePool(SPRITE_POOL_CAPACITY);
        game.getSpriteSystem().initializeImagePool(IMAGE_POOL_CAPACITY);
        game.initializeOrderPool(ORDER_POOL_CAPACITY);
        game.initializeExecutionScope();
        game.initializeUnitSpatialIndex();

        Assertions.assertNotNull(gameData);

        game.setUnitStrengthTable(new UnitStrengthTable(gameData.getUnitTypes(), gameData.getWeaponTypes()));
        game.getUnitSystem().setUnitCreationContext(new UnitCreationContext(false, false, false, false, true, LOCAL_PLAYER));

        UnitAccounting accounting = PlayerProgressTestUtils.createUnitAccounting();

        accounting.initializeForGame(null);
        game.getPlayerTable().setUnitAccounting(accounting);

        int[] cache = new int[MAP_TILE_COUNT];
        CreepField creep = new CreepField(map, gameData.getTerrainTileset(TILESET), cache);

        game.getRuntimeRandom().seed(CREEP_RANDOM_STATE);
        game.setReplayPlayback(true);
        game.getLocalVision().acceptDialog(REPLAY_VISIBILITY_MASK, false);
        game.getLocalVision().setComparisonMask(LOCAL_COMPARISON_MASK);
        creep.finishInitialization();
        game.setCreepField(creep);

        Player player = new Player(PLAYER, PLAYER_ID, PlayerTypes.HUMAN, ScrRaceIds.ZERG, TEAM);

        player.setMinerals(STARTING_MINERALS);
        player.setGas(STARTING_GAS);
        game.getPlayerTable().setPlayer(PLAYER, player);

        return game;
    }

    private static @NotNull Unit createEgg(@NotNull Game game, int eggType) {
        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        Unit egg = pool.getUnit(UNIT_POOL_INDEX);

        UnitStorageConstructor.clearBeforeInnerInitialization(egg);

        Assertions.assertTrue(game.getUnitInitializer().initialize(egg, eggType, HATCHERY_X, HATCHERY_Y, PLAYER, INSTRUCTION_LIMIT));
        Assertions.assertEquals(0, egg.getStatus() & (UnitStatusFlags.COMPLETED | UnitStatusFlags.GROUNDED_BUILDING));

        for (int slot = 0; slot < Unit.BUILD_QUEUE_SLOTS; slot++) {
            setQueueSlot(egg, slot, ScrUnitIds.NONE);
        }

        return egg;
    }

    /**
     * Initializes the pool unit as a completed Drone at the Nydus Canal site that is about to build a Nydus Canal there.
     *
     * @param game      the game of {@link #createWorld}
     * @param creepSite whether the site has creep
     * @return the pool unit, the Drone
     * @throws IOException if the terrain tileset cannot be read
     */
    private static @NotNull Unit createNydusCanalDrone(@NotNull Game game, boolean creepSite) throws IOException {
        CreepField creep = game.getCreepField();
        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(gameData);
        Assertions.assertNotNull(creep);
        Assertions.assertNotNull(pool);

        game.getPathSystem().setTerrainContours(TerrainContours.create(game.getMap(), gameData.getTerrainTileset(TILESET)));
        // A canceled Drone-built building leaves its spawn effect, counts its restored Drone as completed and recedes its creep.
        game.getSpriteSystem().initializeSpriteEffectPool(SPRITE_EFFECT_POOL_CAPACITY);
        game.getPlayerTable().setProductionStatistics(new ProductionStatistics(false,
                                                                               new int[ProductionStatistics.VALUE_COUNT],
                                                                               new float[ProductionStatistics.VALUE_COUNT]));
        game.setCreepRecession(new CreepRecession(0, CreepRecession.RECORD_COUNT, new int[CreepRecession.LIST_COUNT]));
        // The player has seen the whole map.
        game.getMap().clearVisibilityBits(MapTileFlags.NOT_VISIBLE_OR_EXPLORED);

        if (creepSite) {
            creep.fillMoved(game, ScrUnitIds.ZERG_NYDUS_CANAL, NYDUS_X, NYDUS_Y);
        }

        Unit drone = pool.getUnit(UNIT_POOL_INDEX);

        UnitStorageConstructor.clearBeforeInnerInitialization(drone);

        Assertions.assertTrue(game.getUnitInitializer().initialize(drone, ScrUnitIds.ZERG_DRONE, NYDUS_X, NYDUS_Y, PLAYER, INSTRUCTION_LIMIT));
        game.getUnitStorageConstructor().registerSpatially(drone, false);
        drone.setStatus(drone.getStatus() | UnitStatusFlags.COMPLETED);
        drone.setOrderTargetX(NYDUS_X);
        drone.setOrderTargetY(NYDUS_Y);
        drone.setBuildQueueIndex(Unit.FIRST_QUEUE_SLOT);

        for (int slot = 0; slot < Unit.BUILD_QUEUE_SLOTS; slot++) {
            setQueueSlot(drone, slot, ScrUnitIds.NONE);
        }

        setQueueSlot(drone, Unit.FIRST_QUEUE_SLOT, ScrUnitIds.ZERG_NYDUS_CANAL);

        return drone;
    }

    /**
     * Initializes the pool unit as a completed Drone on a creep site and has it build an incomplete Nydus Canal without an exit.
     *
     * @param game the game of {@link #createWorld}
     * @return the pool unit, now the incomplete Nydus Canal
     * @throws IOException if the terrain tileset cannot be read
     */
    private static @NotNull Unit createIncompleteNydusCanal(@NotNull Game game) throws IOException {
        Unit drone = createNydusCanalDrone(game, true);

        game.getUnitConstruction().buildDrone(drone, INSTRUCTION_LIMIT);

        Assertions.assertEquals(NYDUS_CANAL_TYPE, drone.getUnitId());
        Assertions.assertEquals(0, drone.getStatus() & UnitStatusFlags.COMPLETED);
        Assertions.assertNull(drone.getNydusExit());

        return drone;
    }

    private static void setQueueSlot(@NotNull Unit unit, int slot, int unitType) {
        switch (slot) {
            case Unit.FIRST_QUEUE_SLOT -> unit.setFirstBuildQueueType(unitType);
            case Unit.SECOND_QUEUE_SLOT -> unit.setSecondBuildQueueType(unitType);
            case Unit.THIRD_QUEUE_SLOT -> unit.setThirdBuildQueueType(unitType);
            case Unit.FOURTH_QUEUE_SLOT -> unit.setFourthBuildQueueType(unitType);
            default -> unit.setFifthBuildQueueType(unitType);
        }
    }


    @Test
    void hatchery_morphs_into_lair_through_charged_incomplete_morphing() throws IOException {
        Game game = createWorld();

        Assertions.assertNotNull(gameData);

        DatTable units = gameData.getUnits();
        UnitAccounting accounting = game.getPlayerTable().getUnitAccounting();

        Assertions.assertNotNull(accounting);

        ProductionScenario.registerCompletedPrerequisite(gameData, accounting, ScrUnitIds.ZERG_SPAWNING_POOL);

        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        Unit hatchery = pool.getUnit(UNIT_POOL_INDEX);

        UnitStorageConstructor.clearBeforeInnerInitialization(hatchery);

        Assertions.assertTrue(game.getUnitInitializer().initialize(hatchery,
                                                                   ScrUnitIds.ZERG_HATCHERY,
                                                                   HATCHERY_X, HATCHERY_Y,
                                                                   PLAYER,
                                                                   INSTRUCTION_LIMIT));
        game.getUnitStorageConstructor().registerSpatially(hatchery, false);
        accounting.update(hatchery, UnitsDat.createTypeTable(units), 1, false);
        hatchery.setStatus(hatchery.getStatus() | UnitStatusFlags.COMPLETED);
        accounting.updateCompleted(hatchery, UnitsDat.createTypeTable(units), 1, false, null);
        hatchery.setPrimaryOrder(ScrOrderIds.NOTHING);
        hatchery.setBuildQueueIndex(Unit.FIRST_QUEUE_SLOT);

        for (int slot = 0; slot < Unit.BUILD_QUEUE_SLOTS; slot++) {
            setQueueSlot(hatchery, slot, ScrUnitIds.NONE);
        }

        hatchery.setHitPoints(units.get("Hit Points", ScrUnitIds.ZERG_HATCHERY) - HIT_POINT_DEFICIT);
        hatchery.setResources(RESOURCES);
        hatchery.setSecondLarvaSideCount(SECOND_LARVA_SIDE_COUNT);
        hatchery.setThirdLarvaSideCount(THIRD_LARVA_SIDE_COUNT);
        hatchery.setFourthLarvaSideCount(FOURTH_LARVA_SIDE_COUNT);

        UnitSelection selection = new UnitSelection(pool, false, NO_TEAM_SIZE, LOCAL_PLAYER);

        game.setSelection(selection);

        int handle = UnitHandle.encode(UNIT_POOL_INDEX, pool.getGeneration(UNIT_POOL_INDEX), UNIT_CAPACITY);

        selection.execute(game, new SelectionRequest(SelectionRequest.Operation.REPLACE, HANDLE_WIDTH, new long[]{handle}), PLAYER);

        Player player = game.getPlayerTable().getPlayer(PLAYER);

        Assertions.assertTrue(game.getUnitProduction().morphBuilding(PLAYER, ScrUnitIds.ZERG_LAIR, INSTRUCTION_LIMIT));
        Assertions.assertNotNull(player);

        Assertions.assertEquals(STARTING_MINERALS, player.getMinerals());
        Assertions.assertEquals(STARTING_GAS, player.getGas());
        Assertions.assertEquals(LAIR_TYPE, hatchery.getFirstBuildQueueType());
        Assertions.assertEquals(ZERG_BUILDING_MORPH_ORDER, hatchery.getPrimaryOrder());

        game.getUnitConstruction().startZergBuildingMorph(hatchery, INSTRUCTION_LIMIT);

        Assertions.assertEquals(STARTING_MINERALS - units.get("Mineral Cost", ScrUnitIds.ZERG_LAIR), player.getMinerals());
        Assertions.assertEquals(STARTING_GAS - units.get("Vespene Cost", ScrUnitIds.ZERG_LAIR), player.getGas());
        Assertions.assertEquals(0, hatchery.getStatus() & UnitStatusFlags.COMPLETED);
        Assertions.assertEquals(1, accounting.getCompletedTypeCount(ScrUnitIds.ZERG_HATCHERY, PLAYER));
        Assertions.assertEquals(units.get("Build Time", ScrUnitIds.ZERG_LAIR), hatchery.getRemainingBuildTime());
        Assertions.assertEquals(INCOMPLETE_MORPHING_ORDER, hatchery.getPrimaryOrder());

        int hitPoints = hatchery.getHitPoints();

        for (int frame = 0; frame < MORPH_FRAME_LIMIT && hatchery.getPrimaryOrderState() != MORPH_STATE_FINISHING; frame++) {
            game.getUnitConstruction().updateZergMorphing(hatchery, INSTRUCTION_LIMIT);
        }

        Assertions.assertEquals(MORPH_STATE_FINISHING, hatchery.getPrimaryOrderState());
        Assertions.assertEquals(0, hatchery.getRemainingBuildTime());
        Assertions.assertEquals(hitPoints, hatchery.getHitPoints());

        hatchery.setOrderSignal(hatchery.getOrderSignal() | Unit.ORDER_SIGNAL_ANIMATION_DONE);
        game.getUnitConstruction().updateZergMorphing(hatchery, INSTRUCTION_LIMIT);

        Assertions.assertEquals(LAIR_TYPE, hatchery.getUnitId());
        Assertions.assertEquals(HATCHERY_TYPE, hatchery.getPreviousUnitType());
        Assertions.assertEquals(COMPLETED_FLAG, hatchery.getStatus() & UnitStatusFlags.COMPLETED);
        Assertions.assertEquals(0, accounting.getCompletedTypeCount(ScrUnitIds.ZERG_HATCHERY, PLAYER));
        Assertions.assertEquals(1, accounting.getCompletedTypeCount(ScrUnitIds.ZERG_LAIR, PLAYER));
        Assertions.assertEquals(0, accounting.getTypeCount(ScrUnitIds.ZERG_HATCHERY, PLAYER));
        Assertions.assertEquals(1, accounting.getTypeCount(ScrUnitIds.ZERG_LAIR, PLAYER));

        int expectedHitPoints = Math.min(units.get("Hit Points", ScrUnitIds.ZERG_LAIR),
                                         hitPoints + units.get("Hit Points", ScrUnitIds.ZERG_LAIR) -
                                         units.get("Hit Points", ScrUnitIds.ZERG_HATCHERY));

        Assertions.assertEquals(expectedHitPoints, hatchery.getHitPoints());
        Assertions.assertEquals(EMPTY_UNIT_TYPE, hatchery.getFirstBuildQueueType());
        Assertions.assertEquals(0, hatchery.getBuildQueueIndex());
        Assertions.assertEquals(RESOURCES, hatchery.getResources());
        Assertions.assertEquals(SECOND_LARVA_SIDE_COUNT, hatchery.getSecondLarvaSideCount());
        Assertions.assertEquals(THIRD_LARVA_SIDE_COUNT, hatchery.getThirdLarvaSideCount());
        Assertions.assertEquals(FOURTH_LARVA_SIDE_COUNT, hatchery.getFourthLarvaSideCount());

        GameMap map = game.getMap();
        int centerTile = HATCHERY_Y / TerrainTileset.TILE_SIZE * map.getWidth() + HATCHERY_X / TerrainTileset.TILE_SIZE;

        Assertions.assertNotEquals(0, map.getTileFlags(centerTile) & MapTileFlags.OCCUPIED);
        Assertions.assertEquals(CREEP_TILE_GROUP, map.getTileIdentifier(centerTile) & TILE_GROUP_MASK);
    }

    @Test
    void start_zerg_building_morph_should_queue_idle_without_charging_when_resources_missing() throws IOException {
        Game game = createWorld();
        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        Unit hatchery = pool.getUnit(UNIT_POOL_INDEX);

        UnitStorageConstructor.clearBeforeInnerInitialization(hatchery);

        Assertions.assertTrue(game.getUnitInitializer().initialize(hatchery,
                                                                   ScrUnitIds.ZERG_HATCHERY,
                                                                   HATCHERY_X, HATCHERY_Y,
                                                                   PLAYER,
                                                                   INSTRUCTION_LIMIT));
        hatchery.setStatus(hatchery.getStatus() | UnitStatusFlags.COMPLETED);
        hatchery.setPrimaryOrder(ScrOrderIds.ZERG_BUILDING_MORPH);
        hatchery.setBuildQueueIndex(Unit.FIRST_QUEUE_SLOT);
        setQueueSlot(hatchery, Unit.FIRST_QUEUE_SLOT, ScrUnitIds.ZERG_LAIR);

        for (int slot = Unit.SECOND_QUEUE_SLOT; slot < Unit.BUILD_QUEUE_SLOTS; slot++) {
            setQueueSlot(hatchery, slot, ScrUnitIds.NONE);
        }

        Player player = game.getPlayerTable().getPlayer(PLAYER);

        Assertions.assertNotNull(player);

        player.setMinerals(INSUFFICIENT_MINERALS);

        game.getUnitConstruction().startZergBuildingMorph(hatchery, INSTRUCTION_LIMIT);

        Assertions.assertEquals(ZERG_BUILDING_MORPH_ORDER, hatchery.getPrimaryOrder());
        Assertions.assertEquals(COMPLETED_FLAG, hatchery.getStatus() & UnitStatusFlags.COMPLETED);
        Assertions.assertEquals(INSUFFICIENT_MINERALS, player.getMinerals());

        OrderPool.Order lastOrder = hatchery.getLastOrder();

        Assertions.assertNotNull(lastOrder);
        Assertions.assertNotNull(gameData);

        Assertions.assertEquals(gameData.getUnits().get("Return to Idle", ScrUnitIds.ZERG_HATCHERY), lastOrder.getOrderId());
    }

    @Test
    void start_zerg_building_morph_should_read_the_energy_as_the_build_type_after_the_last_queue_slot() throws IOException {
        Game game = createWorld();
        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);
        Assertions.assertNotNull(gameData);

        Unit hatchery = pool.getUnit(UNIT_POOL_INDEX);

        UnitStorageConstructor.clearBeforeInnerInitialization(hatchery);

        Assertions.assertTrue(game.getUnitInitializer().initialize(hatchery,
                                                                   ScrUnitIds.ZERG_HATCHERY,
                                                                   HATCHERY_X, HATCHERY_Y,
                                                                   PLAYER,
                                                                   INSTRUCTION_LIMIT));
        hatchery.setStatus(hatchery.getStatus() | UnitStatusFlags.COMPLETED);
        hatchery.setPrimaryOrder(ScrOrderIds.ZERG_BUILDING_MORPH);
        hatchery.setBuildQueueIndex(ENERGY_QUEUE_INDEX);
        hatchery.setEnergy(ScrUnitIds.ZERG_LAIR);

        for (int slot = 0; slot < Unit.BUILD_QUEUE_SLOTS; slot++) {
            setQueueSlot(hatchery, slot, ScrUnitIds.NONE);
        }

        Player player = game.getPlayerTable().getPlayer(PLAYER);

        Assertions.assertNotNull(player);

        game.getUnitConstruction().startZergBuildingMorph(hatchery, INSTRUCTION_LIMIT);

        DatTable units = gameData.getUnits();

        Assertions.assertEquals(STARTING_MINERALS - units.get("Mineral Cost", ScrUnitIds.ZERG_LAIR), player.getMinerals());
        Assertions.assertEquals(units.get("Build Time", ScrUnitIds.ZERG_LAIR), hatchery.getRemainingBuildTime());
        Assertions.assertEquals(INCOMPLETE_MORPHING_ORDER, hatchery.getPrimaryOrder());
    }

    @Test
    void cancel_building_unit_should_refund_the_wrapped_queue_slot_of_an_egg_after_the_last_queue_slot() throws IOException {
        Game game = createWorld();
        Unit egg = createEgg(game, ScrUnitIds.ZERG_EGG);

        egg.setBuildQueueIndex(ENERGY_QUEUE_INDEX);
        egg.setEnergy(ScrUnitIds.ZERG_ULTRALISK);
        setQueueSlot(egg, Unit.FIRST_QUEUE_SLOT, ScrUnitIds.ZERG_HYDRALISK);

        Player player = game.getPlayerTable().getPlayer(PLAYER);

        Assertions.assertNotNull(player);
        Assertions.assertNotNull(gameData);
        Assertions.assertTrue(game.getUnitConstruction().cancelBuildingUnit(egg, INSTRUCTION_LIMIT));

        ScrUnitTypeView units = gameData.getUnitTypes();

        Assertions.assertEquals(STARTING_MINERALS + units.getMineralCost(ScrUnitIds.ZERG_HYDRALISK), player.getMinerals());
        Assertions.assertEquals(STARTING_GAS + units.getGasCost(ScrUnitIds.ZERG_HYDRALISK), player.getGas());
    }

    @Test
    void cancel_building_unit_should_refund_the_empty_type_cost_for_an_egg_with_an_empty_queue_slot() throws IOException {
        Game game = createWorld();
        Unit egg = createEgg(game, ScrUnitIds.ZERG_EGG);

        egg.setBuildQueueIndex(Unit.FIRST_QUEUE_SLOT);

        Player player = game.getPlayerTable().getPlayer(PLAYER);

        Assertions.assertNotNull(player);
        Assertions.assertNotNull(gameData);
        Assertions.assertTrue(game.getUnitConstruction().cancelBuildingUnit(egg, INSTRUCTION_LIMIT));

        ScrUnitTypeView units = gameData.getUnitTypes();

        Assertions.assertEquals(STARTING_MINERALS + units.getMineralCost(ScrUnitIds.NONE), player.getMinerals());
        Assertions.assertEquals(STARTING_GAS + units.getGasCost(ScrUnitIds.NONE), player.getGas());
    }

    @Test
    void cancel_building_unit_should_leave_the_empty_type_in_the_build_time_of_a_cocoon_at_queue_index_ten() throws IOException {
        Game game = createWorld();
        Unit cocoon = createEgg(game, ScrUnitIds.ZERG_COCOON);
        UnitSpatialIndex spatialIndex = game.getUnitSystem().getUnitSpatialIndex();

        Assertions.assertNotNull(spatialIndex);

        // The Mutalisk the Cocoon reverts to registers as a flying unit.
        spatialIndex.initializeFlyingUnits();
        game.getUnitStorageConstructor().registerSpatially(cocoon, false);
        setQueueSlot(cocoon, Unit.FIRST_QUEUE_SLOT, ScrUnitIds.ZERG_GUARDIAN);
        cocoon.setBuildQueueIndex(BUILD_TIME_QUEUE_INDEX);
        cocoon.setRemainingBuildTime(0);

        Player player = game.getPlayerTable().getPlayer(PLAYER);

        Assertions.assertNotNull(player);
        Assertions.assertNotNull(gameData);
        Assertions.assertTrue(game.getUnitConstruction().cancelBuildingUnit(cocoon, INSTRUCTION_LIMIT));

        ScrUnitTypeView units = gameData.getUnitTypes();

        Assertions.assertEquals(STARTING_MINERALS + units.getMineralCost(ScrUnitIds.ZERG_GUARDIAN), player.getMinerals());
        Assertions.assertEquals(MUTALISK_TYPE, cocoon.getUnitId());
        // The revert sets the remaining build time to 0 and then empties the current queue slot, which is that WORD.
        Assertions.assertEquals(EMPTY_UNIT_TYPE, cocoon.getRemainingBuildTime());
    }

    @Test
    void cancel_building_unit_should_refuse_an_egg_that_holds_eight_highlighted_orders() throws IOException {
        Game game = createWorld();
        Unit egg = createEgg(game, ScrUnitIds.ZERG_EGG);

        setQueueSlot(egg, Unit.FIRST_QUEUE_SLOT, ScrUnitIds.ZERG_HYDRALISK);
        egg.setHighlightedOrderCount(GameRulesVersion.HIGHLIGHTED_ORDER_CAPACITY);

        Player player = game.getPlayerTable().getPlayer(PLAYER);

        Assertions.assertNotNull(player);
        Assertions.assertFalse(game.getUnitConstruction().cancelBuildingUnit(egg, INSTRUCTION_LIMIT));
        Assertions.assertEquals(STARTING_MINERALS, player.getMinerals());
        Assertions.assertEquals(EGG_TYPE, egg.getUnitId());
        Assertions.assertNotEquals(DIE_ORDER, egg.getPrimaryOrder());
    }

    @Test
    void cancel_building_unit_should_refuse_an_egg_when_the_order_pool_has_no_free_order() throws IOException {
        Game game = createWorld();
        Unit egg = createEgg(game, ScrUnitIds.ZERG_EGG);
        OrderPool orders = game.getOrderPool();

        Assertions.assertNotNull(orders);

        setQueueSlot(egg, Unit.FIRST_QUEUE_SLOT, ScrUnitIds.ZERG_HYDRALISK);

        for (int order = orders.getAllocatedCount(); order < ORDER_POOL_CAPACITY; order++) {
            orders.allocate(ScrOrderIds.PLAYER_GUARD, ScrUnitIds.NONE, 0, 0, null);
        }

        Player player = game.getPlayerTable().getPlayer(PLAYER);

        Assertions.assertNotNull(player);
        Assertions.assertFalse(game.getUnitConstruction().cancelBuildingUnit(egg, INSTRUCTION_LIMIT));
        Assertions.assertEquals(STARTING_MINERALS, player.getMinerals());
        Assertions.assertNotEquals(DIE_ORDER, egg.getPrimaryOrder());
    }

    @Test
    void cancel_building_unit_should_restore_the_drone_of_a_nydus_canal_without_an_exit() throws IOException {
        Game game = createWorld();
        Unit canal = createIncompleteNydusCanal(game);
        Player player = game.getPlayerTable().getPlayer(PLAYER);

        Assertions.assertNotNull(player);
        Assertions.assertNotNull(gameData);

        int minerals = player.getMinerals();
        int mineralCost = gameData.getUnitTypes().getMineralCost(ScrUnitIds.ZERG_NYDUS_CANAL);

        Assertions.assertTrue(game.getUnitConstruction().cancelBuildingUnit(canal, INSTRUCTION_LIMIT));
        Assertions.assertEquals(minerals + mineralCost * REFUND_QUARTERS / QUARTER_DIVISOR, player.getMinerals());
        Assertions.assertEquals(DRONE_TYPE, canal.getUnitId());
    }

    @Test
    void cancel_building_unit_should_refuse_a_nydus_canal_with_an_exit() throws IOException {
        Game game = createWorld();
        Unit canal = createIncompleteNydusCanal(game);
        Player player = game.getPlayerTable().getPlayer(PLAYER);

        Assertions.assertNotNull(player);

        int minerals = player.getMinerals();

        canal.setNydusExit(UnitTestUtils.createUnit(NYDUS_EXIT_POOL_INDEX));

        Assertions.assertFalse(game.getUnitConstruction().cancelBuildingUnit(canal, INSTRUCTION_LIMIT));
        Assertions.assertEquals(minerals, player.getMinerals());
        Assertions.assertEquals(NYDUS_CANAL_TYPE, canal.getUnitId());
        Assertions.assertEquals(0, canal.getStatus() & UnitStatusFlags.COMPLETED);
    }

    @Test
    void build_drone_should_throw_when_an_emptied_queue_slot_reads_past_the_images_dat_memory() throws IOException {
        Game game = createWorld();

        Assertions.assertNotNull(gameData);

        game.getPathSystem().setTerrainContours(TerrainContours.create(game.getMap(), gameData.getTerrainTileset(TILESET)));

        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        Unit drone = pool.getUnit(UNIT_POOL_INDEX);

        UnitStorageConstructor.clearBeforeInnerInitialization(drone);

        Assertions.assertTrue(game.getUnitInitializer().initialize(drone, ScrUnitIds.ZERG_DRONE, HATCHERY_X, HATCHERY_Y, PLAYER, INSTRUCTION_LIMIT));
        game.getUnitStorageConstructor().registerSpatially(drone, false);
        drone.setStatus(drone.getStatus() | UnitStatusFlags.COMPLETED);
        drone.setOrderTargetX(HATCHERY_X);
        drone.setOrderTargetY(HATCHERY_Y);
        drone.setBuildQueueIndex(Unit.FIRST_QUEUE_SLOT);

        for (int slot = 0; slot < Unit.BUILD_QUEUE_SLOTS; slot++) {
            setQueueSlot(drone, slot, ScrUnitIds.NONE);
        }

        // The construction image of type 228 is the Unit Direction BYTEs of types 0..3, an images.dat entry far past the images.dat memory, which the
        // game reads without a bound.
        UnsupportedOperationException failure = Assertions.assertThrows(UnsupportedOperationException.class,
                                                                        () -> game.getUnitConstruction().buildDrone(drone, INSTRUCTION_LIMIT));

        Assertions.assertTrue(failure.getMessage().contains(DAT_MEMORY_FAILURE), failure.getMessage());
        Assertions.assertEquals(EMPTY_UNIT_TYPE, drone.getUnitId());
    }

    @Test
    void build_drone_should_stop_without_a_message_when_the_drone_is_off_its_target() throws IOException {
        Game game = createWorld();
        Unit drone = createNydusCanalDrone(game, true);

        drone.setOrderTargetX(NYDUS_X + TerrainTileset.TILE_SIZE);
        game.getUnitConstruction().buildDrone(drone, INSTRUCTION_LIMIT);

        Assertions.assertEquals(DRONE_TYPE, drone.getUnitId());
        Assertions.assertEquals(RESET_COLLISION_ORDER, drone.getPrimaryOrder());
        Assertions.assertEquals(0, game.getInterfaceLink().getCommandErrorEvents().getCount());
    }

    @Test
    void build_drone_should_show_cannot_build_there_when_the_site_is_blocked() throws IOException {
        Game game = createWorld();
        Unit drone = createNydusCanalDrone(game, false);

        game.getUnitConstruction().buildDrone(drone, INSTRUCTION_LIMIT);

        CommandErrorEvents events = game.getInterfaceLink().getCommandErrorEvents();

        Assertions.assertEquals(DRONE_TYPE, drone.getUnitId());
        Assertions.assertEquals(RESET_COLLISION_ORDER, drone.getPrimaryOrder());
        Assertions.assertEquals(1, events.getCount());
        Assertions.assertEquals(CANNOT_BUILD_THERE_TEXT, events.get(0).getTextIdentifier());
        Assertions.assertEquals(CommandErrorEvents.Sound.BLOCKED_BUILD_LOCATION, events.get(0).getSound());
        Assertions.assertEquals(PLAYER, events.get(0).getPlayer());
    }
}
