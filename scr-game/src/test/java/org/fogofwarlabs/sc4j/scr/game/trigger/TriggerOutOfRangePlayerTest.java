package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerRelations;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTypes;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.test.PlayerProgressTestUtils;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks the trigger state of a current player past player 11, addressed without a range check: the alliance bytes after the alliance table,
 * the mission objectives DWORDs, and the result bytes and records of the observer slot ids.
 */
final class TriggerOutOfRangePlayerTest {
    private static final int GAME_DATA = ClassicAddressSpace.GAME_DATA_ADDRESS;
    /**
     * The game-data offset of switch bytes {@code 24..27}, the first bytes of the alliance row of player {@code 131}.
     */
    private static final int SWITCH_BYTES_24 = 0xEB68;
    private static final int LOCATION_SIZE = 20;
    private static final int LOCATION_COUNT = 255;
    /**
     * A current player past player 11: its alliance row starts at switch byte 24, its diagonal is byte 3 of location 6.
     */
    private static final int OUT_OF_RANGE_PLAYER = 131;
    private static final int DIAGONAL_LOCATION = 6;
    private static final int STATUS_ALLIED_VICTORY = 2;
    /**
     * The current player whose mission objectives DWORD is the left edge of location 0.
     */
    private static final int LOCATION_0_OBJECTIVES_PLAYER = 359;
    /**
     * The result bytes of these players are the current-record pointer and the first byte after the fallback record.
     */
    private static final int CURRENT_RECORD_RESULT = 56;
    private static final int PAST_FALLBACK_RECORD_RESULT = 72 + TriggerRecordCodec.RECORD_SIZE;
    /**
     * A player whose record lies past the sixteen player records.
     */
    private static final int PAST_RECORDS_PLAYER = 16;
    private static final int WAIT_DURATION = 0x01020304;
    /**
     * The scripts {@code +Vi0} (human players may run it) and {@code ZB3C} (computers only).
     */
    private static final int HUMAN_SCRIPT = 0x3069562B;
    private static final int COMPUTER_SCRIPT = 'Z' | 'B' << 8 | '3' << 16 | 'C' << 24;

    private static @NotNull TriggerContext createContext(int location0Left, int location6Left, int switches24) {
        int[] types = new int[PlayerTable.PLAYER_SLOTS];
        Player[] players = new Player[PlayerTable.PLAYABLE_PLAYER_SLOTS];

        types[0] = PlayerTypes.HUMAN;
        types[1] = PlayerTypes.HUMAN;

        for (int index = 0; index < players.length; index++) {
            players[index] = new Player(index, index, types[index], 0, 0);
        }

        byte[] locations = new byte[LOCATION_COUNT * LOCATION_SIZE];

        LittleEndianUtils.writeInt32(locations, 0, location0Left);
        LittleEndianUtils.writeInt32(locations, DIAGONAL_LOCATION * LOCATION_SIZE, location6Left);

        TriggerContext context = new TriggerContext();

        context.initializeClassicPlayerRecords(players);
        context.loadLocations(locations);
        context.initializeEvaluation(PlayerProgressTestUtils.createUnitAccounting(), new PlayerRelations(types, false, false, false), new int[5]);

        ClassicAddressSpace space = ClassicAddressSpace.create(context, PlayerProgressTestUtils.createPlayerScores(), null, null, new MessageLines());

        space.writeDword(GAME_DATA + SWITCH_BYTES_24, switches24);
        context.setExtendedDeathsOperations(space);

        return context;
    }

    private static int readSwitchBytes24(@NotNull TriggerContext context) {
        return ((ClassicAddressSpace) ExtendedDeathsUtils.getExtendedDeathsOperations(context)).readDword(GAME_DATA + SWITCH_BYTES_24);
    }

    @Test
    void set_alliance_status_of_player_131_writes_its_row_after_the_table() {
        TriggerContext context = createContext(0x0203, 0, 0x00080100);
        byte[] table = context.requireRelations().getAllianceBytes();

        context.writeCurrentPlayerDword(OUT_OF_RANGE_PLAYER);
        TriggerActionsUtils.setAllianceStatus(context, OUT_OF_RANGE_PLAYER, 9, PlayerRelations.ALLIED);

        // Column 9 is byte 1 of location 0; the diagonal (location 6) is 0.
        Assertions.assertEquals(0x0101, context.getLocation(0).getLeft());
        Assertions.assertEquals(0x00010100, readSwitchBytes24(context));
        Assertions.assertArrayEquals(table, context.requireRelations().getAllianceBytes(), "The alliance table is not part of the row.");
    }

    @Test
    void set_alliance_status_keeps_the_row_when_the_diagonal_holds_the_value() {
        TriggerContext context = createContext(0x0203, 0x01000000, 0x00080100);

        context.writeCurrentPlayerDword(OUT_OF_RANGE_PLAYER);
        TriggerActionsUtils.setAllianceStatus(context, OUT_OF_RANGE_PLAYER, 9, PlayerRelations.ALLIED);

        Assertions.assertEquals(0x0103, context.getLocation(0).getLeft());
        Assertions.assertEquals(0x00080100, readSwitchBytes24(context));
    }

    @Test
    void player_12_of_player_11_is_the_first_byte_after_the_table() {
        TriggerContext context = createContext(0, 0, 0);

        context.writeCurrentPlayerDword(PlayerTable.NEUTRAL_PLAYER_ID);
        TriggerActionsUtils.setAllianceStatus(context, PlayerTable.NEUTRAL_PLAYER_ID, PlayerTable.PLAYER_SLOTS, PlayerRelations.ALLIED_VICTORY);

        Assertions.assertEquals(STATUS_ALLIED_VICTORY, context.getMissionObjectivesStringIndex(0), "Byte 0 of player 0's mission objectives.");
        Assertions.assertEquals(PlayerRelations.ALLIED_VICTORY, context.requireRelations().getAlliance(PlayerTable.NEUTRAL_PLAYER_ID, 0),
                                "The diagonal was 1, so the row's nonzero bytes take the value.");
    }

    @Test
    void foes_of_player_131_compare_the_bytes_after_the_table() {
        TriggerContext context = createContext(0, 0, 0x00000100);

        context.writeCurrentPlayerDword(OUT_OF_RANGE_PLAYER);

        int first = TriggerPlayersUtils.getNextSelectedPlayer(context, TriggerPlayerSelectors.FOES, TriggerContext.WALK_START);

        Assertions.assertEquals(0, first, "Switch byte 24 is 0: player 0 is a foe.");
        Assertions.assertEquals(PlayerTable.PLAYER_SLOTS, TriggerPlayersUtils.getNextSelectedPlayer(context, TriggerPlayerSelectors.FOES, first),
                                "Switch byte 25 is 1: player 1 is not.");
    }

    @Test
    void mission_objectives_of_a_player_past_11_write_the_game_data_dword() {
        TriggerContext context = createContext(0, 0, 0);

        ExtendedDeathsUtils.writeGameDataDword(context, TriggerContext.MISSION_OBJECTIVES_OFFSET + Integer.BYTES * LOCATION_0_OBJECTIVES_PLAYER, 77);

        Assertions.assertEquals(77, context.getLocation(0).getLeft());
    }

    @Test
    void result_bytes_past_player_7_address_the_trigger_globals() {
        TriggerContext context = createContext(0, 0, 0);

        context.setPlayerResult(OUT_OF_RANGE_PLAYER, TriggerPlayerResults.SECURED);

        Assertions.assertEquals(TriggerPlayerResults.SECURED, context.getPlayerResult(OUT_OF_RANGE_PLAYER));
        Assertions.assertEquals(TriggerPlayerResults.NONE, context.getPlayerResult(3), "No other player's result changes.");

        context.setPlayerResult(PlayerTable.PLAYABLE_PLAYER_SLOTS, 1);

        Assertions.assertTrue(context.isWaiting(0), "The result byte of player 8 is the waiting byte of player 0.");
        Assertions.assertThrows(UnsupportedOperationException.class, () -> context.getPlayerResult(CURRENT_RECORD_RESULT),
                                "The current-record pointer is not represented.");
        Assertions.assertThrows(UnsupportedOperationException.class, () -> context.getPlayerResult(PAST_FALLBACK_RECORD_RESULT));
    }

    @Test
    void waits_of_player_131_use_the_fallback_record_bytes() {
        TriggerContext context = createContext(0, 0, 0);

        Assertions.assertFalse(context.isWaiting(OUT_OF_RANGE_PLAYER));

        context.beginWait(OUT_OF_RANGE_PLAYER, WAIT_DURATION);

        Assertions.assertTrue(context.isWaiting(OUT_OF_RANGE_PLAYER));
        Assertions.assertEquals(WAIT_DURATION, context.getWaitTimer(OUT_OF_RANGE_PLAYER));

        for (int player = 0; player < PlayerTable.PLAYABLE_PLAYER_SLOTS; player++) {
            Assertions.assertFalse(context.isWaiting(player));
            Assertions.assertEquals(0, context.getWaitTimer(player));
        }

        // The wait DWORD of player 131 is byte 20 of action 4 of the fallback record, its group operand.
        Assertions.assertEquals(WAIT_DURATION, context.requireFallbackTrigger().definition.getAction(4).getSecondaryOperand());
    }

    @Test
    void player_records_follow_the_native_lookup() {
        TriggerContext context = createContext(0, 0, 0);

        Assertions.assertEquals(PlayerTypes.HUMAN, context.getPlayerType(1));
        Assertions.assertEquals(PlayerTypes.NONE, context.getPlayerType(OUT_OF_RANGE_PLAYER), "Observer record 15.");
        Assertions.assertEquals(PlayerTypes.NONE, context.getPlayerType(PlayerTable.PLAYER_SLOTS), "Observer record 12.");
        Assertions.assertThrows(UnsupportedOperationException.class, () -> context.getPlayerType(PlayerTable.NEUTRAL_PLAYER_ID),
                                "Records 8..11 are the world's players.");
        Assertions.assertThrows(UnsupportedOperationException.class, () -> context.getPlayerType(PAST_RECORDS_PLAYER));
    }

    @Test
    void run_ai_script_reads_the_record_only_for_computer_scripts() {
        TriggerContext context = createContext(0, 0, 0);

        Assertions.assertTrue(TriggerActionsUtils.canRunScript(context, HUMAN_SCRIPT, OUT_OF_RANGE_PLAYER));
        Assertions.assertTrue(TriggerActionsUtils.canRunScript(context, HUMAN_SCRIPT, PAST_RECORDS_PLAYER), "No record is read.");
        Assertions.assertFalse(TriggerActionsUtils.canRunScript(context, COMPUTER_SCRIPT, OUT_OF_RANGE_PLAYER));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> TriggerActionsUtils.canRunScript(context, COMPUTER_SCRIPT, PAST_RECORDS_PLAYER));
    }

    @Test
    void opponents_of_a_player_from_8_count_none() {
        TriggerContext context = createContext(0, 0, 0);

        Assertions.assertEquals(1, TriggerConditions.countOpponents(context, 0));
        Assertions.assertEquals(0, TriggerConditions.countOpponents(context, OUT_OF_RANGE_PLAYER));
        Assertions.assertEquals(0, TriggerConditions.countOpponents(context, -1));
    }
}
