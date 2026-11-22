package org.fogofwarlabs.sc4j.scr.data.format.replay;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests the stored and dispatched command lengths and the dispatcher's walk over a command buffer.
 */
final class ReplayCommandUtilsTest {
    private static final int UNASSIGNED_TYPE = 0x24;
    private static final int TYPE_WITHOUT_LENGTH = 0x16;
    private static final int TYPE_PAST_THE_CASES = 0x6A;
    private static final int TYPE_PAST_THE_TABLE = 0x76;
    private static final int SAVED_GAME_LENGTH = 33;
    private static final int SYNC_COMMAND_LENGTH = 7;
    private static final byte TAB_CHARACTER = '\t';
    private static final byte LAST_CONTROL_CHARACTER = 0x1F;
    private static final byte UTF8_LEAD_BYTE = (byte) 0xC3;
    private static final byte UTF8_CONTINUATION_BYTE = (byte) 0xA9;

    @Test
    void get_stored_length_should_follow_the_length_table_and_the_selection_counts() {
        Assertions.assertEquals(-1, ReplayCommandUtils.getStoredLength(new byte[]{4}, 0));
        Assertions.assertEquals(1, ReplayCommandUtils.getStoredLength(new byte[]{ReplayCommandIds.KEEP_ALIVE}, 0));
        Assertions.assertEquals(SAVED_GAME_LENGTH, ReplayCommandUtils.getStoredLength(new byte[]{ReplayCommandIds.SAVE_GAME}, 0));
        Assertions.assertEquals(1, ReplayCommandUtils.getStoredLength(new byte[]{ReplayCommandIds.SYNC}, 0));
        Assertions.assertEquals(1, ReplayCommandUtils.getStoredLength(new byte[]{UNASSIGNED_TYPE}, 0));
        Assertions.assertEquals(-1, ReplayCommandUtils.getStoredLength(new byte[]{ReplayCommandIds.START_GAME}, 0));
        Assertions.assertEquals(-1, ReplayCommandUtils.getStoredLength(new byte[]{TYPE_PAST_THE_TABLE}, 0));
        Assertions.assertEquals(8, ReplayCommandUtils.getStoredLength(new byte[]{ReplayCommandIds.SELECT, 3}, 0));
        Assertions.assertEquals(14, ReplayCommandUtils.getStoredLength(new byte[]{ReplayCommandIds.SELECT_REMOVE_121, 3}, 0));
        Assertions.assertEquals(-1, ReplayCommandUtils.getStoredLength(new byte[]{ReplayCommandIds.SELECT}, 0));
    }

    @Test
    void get_command_length_should_use_the_dispatcher_lengths() {
        byte[] sync = new byte[ReplayCommandUtils.SYNC_COMMAND_LENGTH];
        byte[] saveGame = {ReplayCommandIds.SAVE_GAME, 0, 0, 0, 0, 'a', 'b', 0, 'c'};

        sync[0] = ReplayCommandIds.SYNC;

        Assertions.assertEquals(SYNC_COMMAND_LENGTH, ReplayCommandUtils.getCommandLength(sync, 0, sync.length));
        Assertions.assertEquals(-1, ReplayCommandUtils.getCommandLength(sync, 0, sync.length - 1));
        Assertions.assertEquals(8, ReplayCommandUtils.getCommandLength(saveGame, 0, saveGame.length));
        Assertions.assertEquals(-1, ReplayCommandUtils.getCommandLength(saveGame, 0, 7));
        Assertions.assertEquals(-1, ReplayCommandUtils.getCommandLength(new byte[]{UNASSIGNED_TYPE}, 0, 1));
        Assertions.assertEquals(-1, ReplayCommandUtils.getCommandLength(new byte[]{ReplayCommandIds.SELECT_121, 1, 0, 0}, 0, 4));
    }

    @Test
    void get_command_length_should_end_a_saved_game_name_at_a_control_character() {
        byte[] saveGame = {ReplayCommandIds.SAVE_GAME, 0, 0, 0, 0, 'a', TAB_CHARACTER, 'b', 0};
        byte[] loadGame = {ReplayCommandIds.LOAD_GAME, 0, 0, 0, 0, LAST_CONTROL_CHARACTER, 0};
        byte[] accentedName = {ReplayCommandIds.LOAD_GAME, 0, 0, 0, 0, 'a', UTF8_LEAD_BYTE, UTF8_CONTINUATION_BYTE, 0};
        byte[] buffer = {ReplayCommandIds.STOP, 0, ReplayCommandIds.SAVE_GAME, 0, 0, 0, 0, TAB_CHARACTER, 0, ReplayCommandIds.STOP, 0};

        Assertions.assertEquals(-1, ReplayCommandUtils.getCommandLength(saveGame, 0, saveGame.length));
        Assertions.assertEquals(-1, ReplayCommandUtils.getCommandLength(loadGame, 0, loadGame.length));
        Assertions.assertEquals(accentedName.length, ReplayCommandUtils.getCommandLength(accentedName, 0, accentedName.length));
        Assertions.assertEquals(2, ReplayCommandUtils.walk(buffer, 0, buffer.length, (data, offset, commandLength) -> {
        }));
    }

    @Test
    void is_dispatched_should_name_the_types_with_a_dispatcher_case() {
        Assertions.assertTrue(ReplayCommandUtils.isDispatched(ReplayCommandIds.KEEP_ALIVE));
        Assertions.assertTrue(ReplayCommandUtils.isDispatched(ReplayCommandIds.SYNC));
        Assertions.assertTrue(ReplayCommandUtils.isDispatched(ReplayCommandIds.SELECT_REMOVE_121));
        Assertions.assertFalse(ReplayCommandUtils.isDispatched(UNASSIGNED_TYPE));
        Assertions.assertFalse(ReplayCommandUtils.isDispatched(ReplayCommandIds.VOICE_ENABLE));
        Assertions.assertFalse(ReplayCommandUtils.isDispatched(ReplayCommandIds.BRIEFING_START));
        Assertions.assertFalse(ReplayCommandUtils.isDispatched(TYPE_WITHOUT_LENGTH));
        Assertions.assertFalse(ReplayCommandUtils.isDispatched(TYPE_PAST_THE_CASES));
    }

    @Test
    void walk_should_run_the_commands_that_fit_and_stop_before_a_truncated_select() {
        byte[] buffer = {ReplayCommandIds.ALLIANCE, 1, 2, 3, 4, ReplayCommandIds.SELECT_121, 1};
        List<int[]> walked = new ArrayList<>();

        int length = ReplayCommandUtils.walk(buffer, 0, buffer.length, (data, offset, commandLength) -> walked.add(new int[]{offset, commandLength}));

        Assertions.assertEquals(5, length);
        Assertions.assertEquals(1, walked.size());
        Assertions.assertArrayEquals(new int[]{0, 5}, walked.get(0));
    }

    @Test
    void walk_should_stop_at_a_type_without_a_dispatcher_case() {
        byte[] buffer = {ReplayCommandIds.STOP, 0, UNASSIGNED_TYPE, ReplayCommandIds.STOP, 0};
        List<int[]> walked = new ArrayList<>();

        int length = ReplayCommandUtils.walk(buffer, 0, buffer.length, (data, offset, commandLength) -> walked.add(new int[]{offset, commandLength}));

        Assertions.assertEquals(2, length);
        Assertions.assertEquals(1, walked.size());
    }

    @Test
    void walk_should_run_nothing_for_an_empty_or_negative_buffer() {
        byte[] buffer = {ReplayCommandIds.STOP, 0};

        Assertions.assertEquals(0, ReplayCommandUtils.walk(buffer, 0, 0, (data, offset, commandLength) -> Assertions.fail("walked")));
        Assertions.assertEquals(0, ReplayCommandUtils.walk(buffer, 0, -2, (data, offset, commandLength) -> Assertions.fail("walked")));
    }

    @Test
    void walk_replay_turn_should_run_the_leading_replay_turn_commands_only() {
        byte[] turn = {ReplayCommandIds.SYNC, 0, 0, 0, 0, 0, 0, ReplayCommandIds.LATENCY, 1, ReplayCommandIds.STOP, 0,
                       ReplayCommandIds.PAUSE, ReplayCommandIds.RESUME};
        List<int[]> walked = new ArrayList<>();

        int length = ReplayCommandUtils.walkReplayTurn(turn, 0, turn.length,
                                                       (data, offset, commandLength) -> walked.add(new int[]{offset, commandLength}));

        Assertions.assertEquals(9, length, "The Stop command ends the walk; the Pause and Resume after it do not run.");
        Assertions.assertEquals(2, walked.size());
        Assertions.assertArrayEquals(new int[]{0, SYNC_COMMAND_LENGTH}, walked.get(0));
        Assertions.assertArrayEquals(new int[]{SYNC_COMMAND_LENGTH, 2}, walked.get(1));
        Assertions.assertTrue(ReplayCommandUtils.isReplayTurnType(ReplayCommandIds.REPLAY_SEEK));
        Assertions.assertFalse(ReplayCommandUtils.isReplayTurnType(ReplayCommandIds.SAVE_GAME));
        Assertions.assertFalse(ReplayCommandUtils.isReplayTurnType(TYPE_PAST_THE_TABLE));
    }

    @Test
    void walk_replay_turn_should_stop_before_a_truncated_replay_speed() {
        byte[] turn = {ReplayCommandIds.KEEP_ALIVE, ReplayCommandIds.REPLAY_SPEED, 0, 4, 0, 0, 0};

        Assertions.assertEquals(1, ReplayCommandUtils.walkReplayTurn(turn, 0, turn.length, (data, offset, commandLength) -> {
        }));
    }

    @Test
    void get_stored_length_should_take_the_mod_command_length_from_its_length_byte() {
        byte modCommand = (byte) ReplayCommandIds.MOD_COMMAND;

        Assertions.assertTrue(ReplayCommandUtils.isDispatched(ReplayCommandIds.MOD_COMMAND));
        Assertions.assertEquals(-1, ReplayCommandUtils.getFixedLength(ReplayCommandIds.MOD_COMMAND));
        Assertions.assertEquals(ReplayModCommand.HEADER_SIZE, ReplayCommandUtils.getStoredLength(new byte[]{modCommand, 0}, 0));
        Assertions.assertEquals(ReplayModCommand.HEADER_SIZE + ReplayModCommand.MAXIMUM_DATA_LENGTH,
                                ReplayCommandUtils.getStoredLength(new byte[]{modCommand, (byte) ReplayModCommand.MAXIMUM_DATA_LENGTH}, 0));
        Assertions.assertEquals(-1, ReplayCommandUtils.getStoredLength(new byte[]{modCommand, (byte) (ReplayModCommand.MAXIMUM_DATA_LENGTH + 1)}, 0));
        Assertions.assertEquals(-1, ReplayCommandUtils.getStoredLength(new byte[]{modCommand}, 0));
    }

    @Test
    void walk_should_run_a_mod_command_that_fits_and_stop_at_a_truncated_one() {
        byte modCommand = (byte) ReplayCommandIds.MOD_COMMAND;
        byte[] buffer = {modCommand, 2, 7, 8, ReplayCommandIds.STOP, 0, modCommand, 3, 1};
        List<int[]> walked = new ArrayList<>();

        int length = ReplayCommandUtils.walk(buffer, 0, buffer.length, (data, offset, commandLength) -> walked.add(new int[]{offset, commandLength}));

        Assertions.assertEquals(6, length);
        Assertions.assertEquals(2, walked.size());
        Assertions.assertArrayEquals(new int[]{0, 4}, walked.get(0));
    }
}
