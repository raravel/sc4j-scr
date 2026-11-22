package org.fogofwarlabs.sc4j.scr.game.order;

import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitTraits;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.dat.DatTable;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.data.format.replay.SelectionRequest;
import org.fogofwarlabs.sc4j.scr.data.type.ArrayDatColumn;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteScenario;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.test.GameResourcesExtension;
import org.fogofwarlabs.sc4j.scr.game.test.InstallationTestResources;
import org.fogofwarlabs.sc4j.scr.game.test.UnitTestUtils;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitHandle;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.util.Arrays;

/**
 * Checks how a selection command and the hotkey command resolve the unit handles they carry and which units join a multiple selection.
 * <p>
 * The hotkey test of type {@code 228} depends on the {@code units.dat} memory layout, which is the contract: the game reads the flags of the empty
 * type {@code 228} from the bytes that follow the flags array, the Target Acquisition Range bytes of the first types.
 */
@ExtendWith(GameResourcesExtension.class)
final class UnitSelectionTest {
    private static final int UNIT_CAPACITY = 16;
    private static final int MAP_DIMENSION = 8;
    private static final int MAP_TILE_COUNT = MAP_DIMENSION * MAP_DIMENSION;
    private static final int MAP_TILESET = 0;
    private static final int RANDOM_SEED = 1;
    private static final int UNIT_POSITION = 100;
    private static final int PLAYER = 0;
    private static final int EMPTY_TYPE = ScrUnitIds.NONE;
    /**
     * The unit type whose Target Acquisition Range byte is the first flags byte of the empty type {@code 228}.
     */
    private static final int ALIASED_FLAGS_TYPE = ScrUnitIds.TERRAN_MARINE;
    /**
     * The hotkey operation that stores the selection in a record.
     */
    private static final int HOTKEY_SET = 0;
    /**
     * The hotkey operation that adds the selection to a record.
     */
    private static final int HOTKEY_APPEND = 2;
    /**
     * The control group record of the hotkey test.
     */
    private static final int CONTROL_GROUP = 1;
    /**
     * The pool indices of the first and the second created unit.
     */
    private static final int FIRST_POOL_INDEX = 1;
    private static final int SECOND_POOL_INDEX = 2;
    private static final int THIRD_POOL_INDEX = 3;
    private static final int FOURTH_POOL_INDEX = 4;
    private static final int OTHER_PLAYER = 1;
    /**
     * The selection-history records of a player.
     */
    private static final int HISTORY_RECORDS = 8;

    /**
     * Creates a {@link ScrUnitTypeView} instance whose flags mark the Supply Depot as the only building of the {@code units.dat} types and read the
     * given value for every type past them.
     *
     * @param flagsPastTheTypes the flags of every type from {@code 228}
     * @return the created {@link ScrUnitTypeView} instance
     */
    private static @NotNull ScrUnitTypeView createUnitTypes(int flagsPastTheTypes) {
        int[] flags = new int[EMPTY_TYPE];

        flags[ScrUnitIds.TERRAN_SUPPLY_DEPOT] = UnitTypeFlags.BUILDING;

        return new ScrUnitTypeView(column -> new ArrayDatColumn(column == ScrUnitTypeView.Column.FLAGS ? flags : new int[EMPTY_TYPE]),
                                   ScrUnitTraits.createColumns(),
                                   UnitsDat.REQUIREMENT_INDEX,
                                   (column, index) -> flagsPastTheTypes);
    }

    /**
     * Creates a {@link Unit} instance of a type with no status or timers set.
     *
     * @param unitType the {@code units.dat} type
     * @return the created {@link Unit} instance
     */
    private static @NotNull Unit createUnit(int unitType) {
        Unit unit = UnitTestUtils.createUnit(FIRST_POOL_INDEX);

        unit.setUnitId(unitType);

        return unit;
    }

    /**
     * Creates a {@link Game} instance with a unit pool whose first units are owned by {@link #PLAYER} and have the given types.
     *
     * @param gameData  the game data
     * @param unitTypes the types of the units from pool index {@code 1}
     * @return the created {@link Game} instance
     */
    private static @NotNull Game createGame(@NotNull GameData gameData, int @NotNull ... unitTypes) {
        Game game = Game.create(gameData, new GameMap(MAP_DIMENSION, MAP_DIMENSION, MAP_TILESET, new int[MAP_TILE_COUNT]),
                                new LcgRandom(RANDOM_SEED));

        game.getUnitSystem().initializeUnitPool(UNIT_CAPACITY);

        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        for (int poolIndex = FIRST_POOL_INDEX; poolIndex <= unitTypes.length; poolIndex++) {
            Unit unit = pool.getUnit(poolIndex);

            unit.setUnitId(unitTypes[poolIndex - FIRST_POOL_INDEX]);
            unit.setOwnerIndex(PLAYER);
            unit.setPrimaryOrder(ScrOrderIds.PLAYER_GUARD);
            unit.setSprite(SpriteScenario.createSprite(0, UNIT_POSITION, UNIT_POSITION, 0));
        }

        return game;
    }


    @Test
    void can_select_multiple_should_test_the_building_flag_of_the_units_dat_types_only() {
        ScrUnitTypeView unitTypes = createUnitTypes(UnitTypeFlags.BUILDING);

        Assertions.assertFalse(UnitSelection.canSelectMultiple(unitTypes, createUnit(ScrUnitIds.TERRAN_SUPPLY_DEPOT)));
        Assertions.assertTrue(UnitSelection.canSelectMultiple(unitTypes, createUnit(EMPTY_TYPE)));
    }

    @Test
    void can_select_multiple_should_test_the_powerup_flag_of_every_type() {
        Assertions.assertFalse(UnitSelection.canSelectMultiple(createUnitTypes(UnitTypeFlags.POWERUP), createUnit(EMPTY_TYPE)));
        Assertions.assertTrue(UnitSelection.canSelectMultiple(createUnitTypes(0), createUnit(EMPTY_TYPE)));
    }

    @Test
    void can_select_multiple_should_reject_disabled_units_and_the_single_selection_types() {
        ScrUnitTypeView unitTypes = createUnitTypes(0);
        Unit lockedDown = createUnit(ScrUnitIds.TERRAN_MARINE);
        Unit disabled = createUnit(ScrUnitIds.TERRAN_MARINE);

        lockedDown.setLockdownTimer(1);
        disabled.setStatus(UnitStatusFlags.DISABLED);

        Assertions.assertTrue(UnitSelection.canSelectMultiple(unitTypes, createUnit(ScrUnitIds.TERRAN_MARINE)));
        Assertions.assertFalse(UnitSelection.canSelectMultiple(unitTypes, lockedDown));
        Assertions.assertFalse(UnitSelection.canSelectMultiple(unitTypes, disabled));

        for (int unitType : new int[]{ScrUnitIds.TERRAN_VULTURE_SPIDER_MINE, ScrUnitIds.ZERG_EGG, ScrUnitIds.CRITTER_RHYNADON,
                                      ScrUnitIds.CRITTER_URSADON, ScrUnitIds.SPELL_DARK_SWARM, ScrUnitIds.SPELL_DISRUPTION_WEB,
                                      ScrUnitIds.SPECIAL_FLOOR_MISSILE_TRAP, ScrUnitIds.SPECIAL_RIGHT_WALL_FLAME_TRAP}) {
            Assertions.assertFalse(UnitSelection.canSelectMultiple(unitTypes, createUnit(unitType)), String.valueOf(unitType));
        }

        Assertions.assertTrue(UnitSelection.canSelectMultiple(unitTypes, createUnit(ScrUnitIds.ZERG_LARVA)));
    }

    @RequiresInstallation
    @Test
    void execute_should_skip_handles_whose_index_lies_outside_the_pool(@NotNull InstallationTestResources resources) throws IOException {
        int[] unitTypes = new int[UNIT_CAPACITY];

        Arrays.fill(unitTypes, ScrUnitIds.TERRAN_MARINE);

        Game game = createGame(resources.getGameData(), unitTypes);
        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        UnitSelection selection = new UnitSelection(pool, false, 0, PLAYER);
        long[] handles = {
                Integer.toUnsignedLong(pool.getHandle(FIRST_POOL_INDEX)),
                Integer.toUnsignedLong(UnitHandle.encode(0, 1, UNIT_CAPACITY)),
                UNIT_CAPACITY + 1,
                Integer.toUnsignedLong(pool.getHandle(SECOND_POOL_INDEX))
        };
        SelectionRequest request = new SelectionRequest(SelectionRequest.Operation.REPLACE, Integer.BYTES, handles);

        Assertions.assertTrue(selection.canExecute(game, request, PLAYER));

        selection.execute(game, request, PLAYER);

        Assertions.assertEquals(FIRST_POOL_INDEX, selection.getSelectedPoolIndex(PLAYER, 0));
        Assertions.assertEquals(SECOND_POOL_INDEX, selection.getSelectedPoolIndex(PLAYER, 1));
        Assertions.assertEquals(0, selection.getSelectedPoolIndex(PLAYER, 2));
    }

    @RequiresInstallation
    @Test
    void select_should_replace_the_selection_by_the_command_rules_without_scope_accounting(@NotNull InstallationTestResources resources) throws IOException {
        Game game = createGame(resources.getGameData(), ScrUnitIds.TERRAN_MARINE, ScrUnitIds.TERRAN_SUPPLY_DEPOT, ScrUnitIds.TERRAN_MARINE,
                               ScrUnitIds.TERRAN_MARINE);
        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        Unit[] units = {pool.getUnit(FIRST_POOL_INDEX), pool.getUnit(SECOND_POOL_INDEX), pool.getUnit(THIRD_POOL_INDEX),
                        pool.getUnit(FOURTH_POOL_INDEX)};
        UnitSelection selection = new UnitSelection(pool, true, 0, PLAYER);

        units[3].setOwnerIndex(OTHER_PLAYER);
        selection.setSelectedPoolIndexRaw(PLAYER, 0, FOURTH_POOL_INDEX);
        selection.setSelectedPoolIndexRaw(PLAYER, 1, SECOND_POOL_INDEX);
        selection.select(game, PLAYER, units);

        // The building and the other player's unit cannot join a selection of more than one unit.
        Assertions.assertEquals(FIRST_POOL_INDEX, selection.getSelectedPoolIndex(PLAYER, 0));
        Assertions.assertEquals(THIRD_POOL_INDEX, selection.getSelectedPoolIndex(PLAYER, 1));
        Assertions.assertEquals(0, selection.getSelectedPoolIndex(PLAYER, 2));
        Assertions.assertEquals(0, selection.getScopeTotal(PLAYER), "no command ran");

        boolean recorded = false;

        for (int historyIndex = 0; historyIndex < HISTORY_RECORDS; historyIndex++) {
            recorded |= selection.getHistoryHandle(PLAYER, historyIndex, 0) == pool.getHandle(FIRST_POOL_INDEX);
        }

        Assertions.assertTrue(recorded, "a selection of more than one unit is stored as a history record");

        selection.select(game, PLAYER, new Unit[]{units[1]});

        Assertions.assertEquals(SECOND_POOL_INDEX, selection.getSelectedPoolIndex(PLAYER, 0), "a building alone is selected");
        Assertions.assertEquals(0, selection.getSelectedPoolIndex(PLAYER, 1));

        Unit[] tooMany = new Unit[UnitSelection.MAXIMUM_SELECTED + 1];

        Arrays.fill(tooMany, units[0]);

        Assertions.assertThrows(IllegalArgumentException.class, () -> selection.select(game, PLAYER, tooMany));
    }

    @RequiresInstallation
    @Test
    void execute_should_add_a_unit_of_the_empty_type_228_after_another_unit(@NotNull InstallationTestResources resources) throws IOException {
        Game game = createGame(resources.getGameData(), ScrUnitIds.TERRAN_MARINE, EMPTY_TYPE);
        UnitPool pool = game.getUnitSystem().getUnitPool();

        Assertions.assertNotNull(pool);

        UnitSelection selection = new UnitSelection(pool, false, 0, PLAYER);
        long[] handles = {Integer.toUnsignedLong(pool.getHandle(FIRST_POOL_INDEX)), Integer.toUnsignedLong(pool.getHandle(SECOND_POOL_INDEX))};
        SelectionRequest request = new SelectionRequest(SelectionRequest.Operation.REPLACE, Integer.BYTES, handles);

        Assertions.assertTrue(selection.canExecute(game, request, PLAYER));

        selection.execute(game, request, PLAYER);

        Assertions.assertEquals(FIRST_POOL_INDEX, selection.getSelectedPoolIndex(PLAYER, 0));
        Assertions.assertEquals(SECOND_POOL_INDEX, selection.getSelectedPoolIndex(PLAYER, 1));
    }

    @RequiresInstallation
    @Test
    void execute_control_group_should_append_a_type_228_unit_whose_flags_hold_the_building_bit(@NotNull InstallationTestResources resources) throws IOException {
        try (GameData gameData = GameData.loadFrom(resources.getGamePath())) {
            DatTable units = gameData.getUnits();

            // The building bit is in the first flags byte of type 228: the Target Acquisition Range byte of type 0.
            units.set(UnitsDat.TARGET_ACQUISITION_RANGE, ALIASED_FLAGS_TYPE,
                      units.get(UnitsDat.TARGET_ACQUISITION_RANGE, ALIASED_FLAGS_TYPE) | UnitTypeFlags.BUILDING);

            Assertions.assertNotEquals(0, gameData.getUnitTypes().getFlags(EMPTY_TYPE) & UnitTypeFlags.BUILDING);

            Game game = createGame(gameData, ScrUnitIds.TERRAN_MARINE, EMPTY_TYPE);
            UnitPool pool = game.getUnitSystem().getUnitPool();

            Assertions.assertNotNull(pool);

            UnitSelection selection = new UnitSelection(pool, false, 0, PLAYER);

            selection.setSelectedPoolIndexRaw(PLAYER, 0, FIRST_POOL_INDEX);

            Assertions.assertTrue(selection.executeControlGroup(game, PLAYER, CONTROL_GROUP, HOTKEY_SET));

            selection.setSelectedPoolIndexRaw(PLAYER, 0, SECOND_POOL_INDEX);

            Assertions.assertTrue(selection.executeControlGroup(game, PLAYER, CONTROL_GROUP, HOTKEY_APPEND));
            Assertions.assertEquals(pool.getHandle(FIRST_POOL_INDEX), selection.getControlGroupHandle(PLAYER, CONTROL_GROUP, 0));
            Assertions.assertEquals(pool.getHandle(SECOND_POOL_INDEX), selection.getControlGroupHandle(PLAYER, CONTROL_GROUP, 1));
        }
    }
}
