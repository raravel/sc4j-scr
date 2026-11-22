package org.fogofwarlabs.sc4j.scr.game.map;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.GameSettings;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkUnit;
import org.fogofwarlabs.sc4j.scr.data.format.dat.DatTable;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.test.RunningGameScenario;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

/**
 * Checks map unit routing through the public DAT memory layout contract, including gap/column aliases, input widths and setup gates.
 */
@RequiresInstallation
final class MapUnitRoutingTest {
    private static final int DAT_BLOCK_ALIGNMENT = 16;
    private static final int FIRST_GROUP_GAP_IDENTIFIER = UnitsDat.UNIT_COUNT;
    private static final int UNIT_BLOCK_COUNT = (UnitsDat.UNIT_COUNT + DAT_BLOCK_ALIGNMENT - 1) / DAT_BLOCK_ALIGNMENT;
    private static final int NEXT_GROUP_COLUMN_IDENTIFIER = UNIT_BLOCK_COUNT * DAT_BLOCK_ALIGNMENT;
    private static final int LAST_GROUP_GAP_IDENTIFIER = NEXT_GROUP_COLUMN_IDENTIFIER - 1;
    private static final int SUPPLY_ALIAS_ELEMENT_INDEX = 0;
    private static final int HIGH_UNIT_IDENTIFIER_BITS = 0x10000;
    private static final int HIGH_OWNER_BITS = 0x100;
    private static final int MAXIMUM_UNIT_IDENTIFIER = 0xFFFF;
    private static final int NON_MAP_SETTINGS_VICTORY = 1;
    private static final int STOCK_UNIT_TYPE_COUNT = 228;
    private static final int NEUTRAL_GROUP_FLAG = 0x80;

    private static @Nullable GameData gameData;
    private static @Nullable ReplayRuntime runtime;

    @BeforeAll
    static void loadGameData() throws IOException {
        gameData = GameData.loadFrom(ExternalInput.INSTALLATION.getPath());
    }

    @AfterAll
    static void closeGameData() {
        GameData installedData = gameData;

        if (installedData != null) {
            installedData.close();
        }

        gameData = null;
    }

    private static @NotNull ChkUnit createRecord(int unitTypeIdentifier, int owner) {
        return new ChkUnit(0, 0, 0, unitTypeIdentifier, 0, 0, 0, owner, 0, 0, 0, 0, 0, 0, 0);
    }

    private static @NotNull GameSettings createSettings(boolean useMapSettings, boolean createMeleeUnits) {
        boolean[] meleePlayers = new boolean[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        meleePlayers[RunningGameScenario.HUMAN] = createMeleeUnits;

        return new GameSettings(useMapSettings ? 0 : NON_MAP_SETTINGS_VICTORY, 0, 0, 0, meleePlayers, 0);
    }


    @BeforeEach
    void startGame() throws IOException {
        GameData installedData = gameData;

        Assertions.assertNotNull(installedData);

        runtime = RunningGameScenario.startLiveGame(installedData);
    }

    @AfterEach
    void releaseGame() {
        runtime = null;
    }

    @Test
    void requests_creation_should_accept_a_stock_neutral_group_for_a_melee_owner() {
        RunningGameScenario.runInCommandDispatch(runtime, () -> {
            Assertions.assertNotNull(runtime);

            Game game = runtime.getGame();
            int unitTypeIdentifier = ScrUnitIds.RESOURCE_MINERAL_FIELD;
            ScrUnitTypeView types = game.requireGameData().getUnitTypes();

            Assertions.assertNotEquals(0, types.getGroupFlags(unitTypeIdentifier) & UnitGroupFlags.NEUTRAL);
            Assertions.assertTrue(MapUnitLoader.requestsCreation(game, createRecord(unitTypeIdentifier, RunningGameScenario.HUMAN),
                                                                 createSettings(true, true), false));
        });
    }

    @Test
    void requests_creation_should_reject_a_stock_nonneutral_group_for_a_melee_owner() {
        RunningGameScenario.runInCommandDispatch(runtime, () -> {
            Assertions.assertNotNull(runtime);

            Game game = runtime.getGame();
            int unitTypeIdentifier = ScrUnitIds.TERRAN_MARINE;
            ScrUnitTypeView types = game.requireGameData().getUnitTypes();

            Assertions.assertEquals(0, types.getGroupFlags(unitTypeIdentifier) & UnitGroupFlags.NEUTRAL);
            Assertions.assertFalse(MapUnitLoader.requestsCreation(game, createRecord(unitTypeIdentifier, RunningGameScenario.HUMAN),
                                                                  createSettings(true, true), false));
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {FIRST_GROUP_GAP_IDENTIFIER, LAST_GROUP_GAP_IDENTIFIER})
    void requests_creation_should_read_the_represented_group_alignment_gap(int unitTypeIdentifier) {
        RunningGameScenario.runInCommandDispatch(runtime, () -> {
            Assertions.assertNotNull(runtime);

            Game game = runtime.getGame();
            GameData routingData = game.requireGameData();
            DatTable units = routingData.getUnits();

            Assertions.assertEquals(STOCK_UNIT_TYPE_COUNT, units.getCount(UnitsDat.GROUP_FLAGS));
            Assertions.assertEquals(0, routingData.getUnitTypes().getGroupFlags(unitTypeIdentifier));

            long randomCalls = game.getRandom().getTotalRandomCounts();

            Assertions.assertFalse(MapUnitLoader.requestsCreation(game, createRecord(unitTypeIdentifier, RunningGameScenario.HUMAN),
                                                                  createSettings(true, true), false));
            Assertions.assertEquals(randomCalls, game.getRandom().getTotalRandomCounts());
        });
    }

    @Test
    void requests_creation_should_follow_a_public_write_to_the_next_group_column() {
        RunningGameScenario.runInCommandDispatch(runtime, () -> {
            Assertions.assertNotNull(runtime);

            Game game = runtime.getGame();
            GameData routingData = game.requireGameData();
            DatTable units = routingData.getUnits();
            ScrUnitTypeView types = routingData.getUnitTypes();
            int previousSupply = units.get(UnitsDat.SUPPLY_PROVIDED, SUPPLY_ALIAS_ELEMENT_INDEX);

            try {
                units.set(UnitsDat.SUPPLY_PROVIDED, SUPPLY_ALIAS_ELEMENT_INDEX, 0);

                Assertions.assertEquals(0, types.getGroupFlags(NEXT_GROUP_COLUMN_IDENTIFIER));
                Assertions.assertFalse(MapUnitLoader.requestsCreation(game, createRecord(NEXT_GROUP_COLUMN_IDENTIFIER, RunningGameScenario.HUMAN),
                                                                      createSettings(true, true), false));

                units.set(UnitsDat.SUPPLY_PROVIDED, SUPPLY_ALIAS_ELEMENT_INDEX, UnitGroupFlags.NEUTRAL);

                Assertions.assertEquals(NEUTRAL_GROUP_FLAG, types.getGroupFlags(NEXT_GROUP_COLUMN_IDENTIFIER));
                Assertions.assertTrue(MapUnitLoader.requestsCreation(game, createRecord(NEXT_GROUP_COLUMN_IDENTIFIER, RunningGameScenario.HUMAN),
                                                                     createSettings(true, true), false));
            } finally {
                units.set(UnitsDat.SUPPLY_PROVIDED, SUPPLY_ALIAS_ELEMENT_INDEX, previousSupply);
            }

            Assertions.assertEquals(previousSupply, units.get(UnitsDat.SUPPLY_PROVIDED, SUPPLY_ALIAS_ELEMENT_INDEX));
        });
    }

    @Test
    void requests_creation_should_mask_the_type_word_and_owner_byte_before_routing() {
        RunningGameScenario.runInCommandDispatch(runtime, () -> {
            Assertions.assertNotNull(runtime);

            Game game = runtime.getGame();
            ChkUnit record = createRecord(FIRST_GROUP_GAP_IDENTIFIER | HIGH_UNIT_IDENTIFIER_BITS, RunningGameScenario.HUMAN | HIGH_OWNER_BITS);

            Assertions.assertEquals(0, game.requireGameData().getUnitTypes().getGroupFlags(FIRST_GROUP_GAP_IDENTIFIER));
            Assertions.assertFalse(MapUnitLoader.requestsCreation(game, record, createSettings(true, true), false));
        });
    }

    @Test
    void requests_creation_should_bypass_the_group_read_when_map_settings_are_disabled() {
        RunningGameScenario.runInCommandDispatch(runtime, () -> {
            Assertions.assertNotNull(runtime);

            Game game = runtime.getGame();

            Assertions.assertTrue(MapUnitLoader.requestsCreation(game, createRecord(MAXIMUM_UNIT_IDENTIFIER, RunningGameScenario.HUMAN),
                                                                 createSettings(false, true), false));
        });
    }

    @Test
    void requests_creation_should_bypass_the_group_read_when_owner_melee_creation_is_disabled() {
        RunningGameScenario.runInCommandDispatch(runtime, () -> {
            Assertions.assertNotNull(runtime);

            Game game = runtime.getGame();

            Assertions.assertTrue(MapUnitLoader.requestsCreation(game, createRecord(MAXIMUM_UNIT_IDENTIFIER, RunningGameScenario.HUMAN),
                                                                 createSettings(true, false), false));
        });
    }

    @Test
    void requests_creation_should_reject_start_locations_before_group_filtering() {
        RunningGameScenario.runInCommandDispatch(runtime, () -> {
            Assertions.assertNotNull(runtime);

            Game game = runtime.getGame();
            ChkUnit record = createRecord(ScrUnitIds.SPECIAL_START_LOCATION, RunningGameScenario.HUMAN);

            Assertions.assertFalse(MapUnitLoader.requestsCreation(game, record, createSettings(true, true), false));
        });
    }

    @Test
    void requests_creation_should_reject_restricted_playable_records_before_group_filtering() {
        RunningGameScenario.runInCommandDispatch(runtime, () -> {
            Assertions.assertNotNull(runtime);

            Game game = runtime.getGame();

            Assertions.assertFalse(MapUnitLoader.requestsCreation(game, createRecord(ScrUnitIds.TERRAN_MARINE, RunningGameScenario.HUMAN),
                                                                  createSettings(true, true), true));
        });
    }

    @Test
    void requests_creation_should_keep_restricted_neutral_resources() {
        RunningGameScenario.runInCommandDispatch(runtime, () -> {
            Assertions.assertNotNull(runtime);

            Game game = runtime.getGame();
            ChkUnit record = createRecord(ScrUnitIds.RESOURCE_MINERAL_FIELD, PlayerTable.NEUTRAL_PLAYER_ID);

            Assertions.assertTrue(MapUnitLoader.requestsCreation(game, record, createSettings(true, true), true));
        });
    }

    @Test
    void requests_creation_should_reject_a_closed_player_before_group_filtering() {
        RunningGameScenario.runInCommandDispatch(runtime, () -> {
            Assertions.assertNotNull(runtime);

            Game game = runtime.getGame();
            Player player = game.getPlayerTable().getPlayer(RunningGameScenario.HUMAN);

            Assertions.assertNotNull(player);

            int previousType = player.getType();

            try {
                player.setType(PlayerTypes.CLOSED);

                Assertions.assertFalse(MapUnitLoader.requestsCreation(game, createRecord(MAXIMUM_UNIT_IDENTIFIER, RunningGameScenario.HUMAN),
                                                                      createSettings(true, true), false));
            } finally {
                player.setType(previousType);
            }

            Assertions.assertEquals(previousType, player.getType());
        });
    }

    @Test
    void requests_creation_should_preserve_the_common_owner_failure_beyond_known_group_memory() {
        RunningGameScenario.runInCommandDispatch(runtime, () -> {
            Assertions.assertNotNull(runtime);

            Game game = runtime.getGame();
            ScrUnitTypeView types = game.requireGameData().getUnitTypes();

            Assertions.assertThrows(UnsupportedOperationException.class, () -> types.getGroupFlags(MAXIMUM_UNIT_IDENTIFIER));
            Assertions.assertThrows(UnsupportedOperationException.class,
                                    () -> MapUnitLoader.requestsCreation(game, createRecord(MAXIMUM_UNIT_IDENTIFIER, RunningGameScenario.HUMAN),
                                                                         createSettings(true, true), false));
        });
    }
}
