package org.fogofwarlabs.sc4j.scr.client.ui.input;

import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.fogofwarlabs.sc4j.engine.client.input.KeyModifier;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks accelerator table sizes, typing keys, popup mappings, exact modifiers, repeat flags and which accelerators act on the game.
 */
final class GameAcceleratorsTest {
    private static final int PLAY_TABLE_SIZE = 72;
    private static final int CHAT_TABLE_SIZE = 47;
    private static final int POPUP_TABLE_SIZE = 12;
    /**
     * The offset of digit {@code 0}, the last id of a control group range.
     */
    private static final int DIGIT_ZERO_OFFSET = 9;
    private static final int COPY_OPERATION = 2;
    private static final int NO_MODIFIERS = 0;
    private static final int CONTROL_ALT = GameAccelerators.CONTROL | GameAccelerators.ALT;
    /**
     * The accelerator identifiers of the table entries, and the identifier of no entry.
     */
    private static final int NO_ACCELERATOR = -1;
    private static final int GROUP_ADD = 0x3e8;
    private static final int GROUP_ASSIGN = 0x3f2;
    private static final int GROUP_RECALL = 0x3fc;
    private static final int GROUP_CENTER = 0x406;
    private static final int CENTER_ON_UNIT = 0x412;
    private static final int OPTIONS = 0x417;
    private static final int LEADERBOARD_PLAYERS = 0x419;
    private static final int DECREASE_SPEED = 0x41d;
    private static final int VIEW_RECALL = 0x422;
    private static final int WINDOW_MODE = 0x426;
    private static final int RESET_ZOOM = 0x428;
    private static final int BATTLE_NET_ACTION = 0x429;
    private static final int INCREASE_SPEED_OR_CHAT_HISTORY = 0x42b;
    private static final int MOUSE_CONFINE = 0x42c;
    private static final int SHOW_TURN_RATE = 0x42d;
    private static final int EDIT_SELECT_ALL = 0;

    private static int find(@NotNull GameAccelerators.Mode mode, @NotNull Key key, int modifiers) {
        GameAccelerators.Entry entry = GameAccelerators.find(mode, key, modifiers, false);

        return entry == null ? NO_ACCELERATOR : entry.getIdentifier();
    }

    @Test
    void get_table_should_return_native_table_sizes() {
        Assertions.assertEquals(PLAY_TABLE_SIZE, GameAccelerators.getTable(GameAccelerators.Mode.PLAY).size());
        Assertions.assertEquals(CHAT_TABLE_SIZE, GameAccelerators.getTable(GameAccelerators.Mode.CHAT).size());
        Assertions.assertEquals(POPUP_TABLE_SIZE, GameAccelerators.getTable(GameAccelerators.Mode.POPUP).size());
    }

    @Test
    void entry_modifier_bits_are_engine_modifier_masks() {
        Assertions.assertEquals(KeyModifier.SHIFT.getMask(), GameAccelerators.SHIFT);
        Assertions.assertEquals(KeyModifier.CONTROL.getMask(), GameAccelerators.CONTROL);
        Assertions.assertEquals(KeyModifier.ALT.getMask(), GameAccelerators.ALT);
        Assertions.assertEquals(CENTER_ON_UNIT,
                                find(GameAccelerators.Mode.PLAY, Key.C, GameAccelerators.CONTROL | KeyModifier.CAPS_LOCK.getMask()),
                                "only Shift, Ctrl and Alt count");
    }

    @Test
    void play_table_maps_speed_group_and_option_keys() {
        Assertions.assertEquals(INCREASE_SPEED_OR_CHAT_HISTORY, find(GameAccelerators.Mode.PLAY, Key.EQUAL, 0));
        Assertions.assertEquals(INCREASE_SPEED_OR_CHAT_HISTORY, find(GameAccelerators.Mode.PLAY, Key.KEYPAD_ADD, 0));
        Assertions.assertEquals(DECREASE_SPEED, find(GameAccelerators.Mode.PLAY, Key.MINUS, 0));
        Assertions.assertEquals(GROUP_RECALL, find(GameAccelerators.Mode.PLAY, Key.DIGIT_1, 0));
        Assertions.assertEquals(GROUP_RECALL + DIGIT_ZERO_OFFSET, find(GameAccelerators.Mode.PLAY, Key.DIGIT_0, 0));
        Assertions.assertEquals(GROUP_ASSIGN, find(GameAccelerators.Mode.PLAY, Key.DIGIT_1, GameAccelerators.CONTROL));
        Assertions.assertEquals(GROUP_ADD, find(GameAccelerators.Mode.PLAY, Key.DIGIT_1, GameAccelerators.SHIFT));
        Assertions.assertEquals(GROUP_CENTER + DIGIT_ZERO_OFFSET, find(GameAccelerators.Mode.PLAY, Key.DIGIT_0, GameAccelerators.ALT));
        Assertions.assertEquals(1, GameAccelerators.getGroupDigit(GameAccelerators.GROUP_RECALL, GameAccelerators.GROUP_RECALL));
        Assertions.assertEquals(0, GameAccelerators.getGroupDigit(GameAccelerators.GROUP_RECALL + DIGIT_ZERO_OFFSET, GameAccelerators.GROUP_RECALL));
        Assertions.assertEquals(OPTIONS, find(GameAccelerators.Mode.PLAY, Key.O, GameAccelerators.ALT));
        Assertions.assertEquals(LEADERBOARD_PLAYERS, find(GameAccelerators.Mode.PLAY, Key.R, GameAccelerators.CONTROL));
        Assertions.assertEquals(MOUSE_CONFINE, find(GameAccelerators.Mode.PLAY, Key.F1, GameAccelerators.ALT));
        Assertions.assertEquals(SHOW_TURN_RATE, find(GameAccelerators.Mode.PLAY, Key.T, CONTROL_ALT));
        Assertions.assertEquals(BATTLE_NET_ACTION, find(GameAccelerators.Mode.PLAY, Key.KEYPAD_0, 0));
        Assertions.assertEquals(RESET_ZOOM, find(GameAccelerators.Mode.PLAY, Key.F6, 0));
    }

    @Test
    void find_should_match_modifiers_exactly() {
        Assertions.assertEquals(NO_ACCELERATOR, find(GameAccelerators.Mode.PLAY, Key.C, NO_MODIFIERS));
        Assertions.assertEquals(NO_ACCELERATOR, find(GameAccelerators.Mode.PLAY, Key.C, GameAccelerators.CONTROL | GameAccelerators.SHIFT));
        Assertions.assertEquals(CENTER_ON_UNIT, find(GameAccelerators.Mode.PLAY, Key.C, GameAccelerators.CONTROL));
    }

    @Test
    void chat_table_leaves_typing_keys_to_chat_line() {
        Assertions.assertEquals(NO_ACCELERATOR, find(GameAccelerators.Mode.CHAT, Key.EQUAL, 0));
        Assertions.assertEquals(NO_ACCELERATOR, find(GameAccelerators.Mode.CHAT, Key.MINUS, 0));
        Assertions.assertEquals(NO_ACCELERATOR, find(GameAccelerators.Mode.CHAT, Key.DIGIT_1, 0));
        Assertions.assertEquals(NO_ACCELERATOR, find(GameAccelerators.Mode.CHAT, Key.DIGIT_1, GameAccelerators.SHIFT));
        Assertions.assertEquals(NO_ACCELERATOR, find(GameAccelerators.Mode.CHAT, Key.F6, 0));
        Assertions.assertEquals(GROUP_ASSIGN, find(GameAccelerators.Mode.CHAT, Key.DIGIT_1, GameAccelerators.CONTROL));
        Assertions.assertEquals(VIEW_RECALL, find(GameAccelerators.Mode.CHAT, Key.F2, 0));
        Assertions.assertEquals(BATTLE_NET_ACTION, find(GameAccelerators.Mode.CHAT, Key.KEYPAD_0, 0));
    }

    @Test
    void popup_table_keeps_only_toggles_and_edit_keys() {
        Assertions.assertEquals(NO_ACCELERATOR, find(GameAccelerators.Mode.POPUP, Key.F6, 0));
        Assertions.assertEquals(NO_ACCELERATOR, find(GameAccelerators.Mode.POPUP, Key.O, GameAccelerators.ALT));
        Assertions.assertEquals(NO_ACCELERATOR, find(GameAccelerators.Mode.POPUP, Key.T, CONTROL_ALT));
        Assertions.assertEquals(WINDOW_MODE, find(GameAccelerators.Mode.POPUP, Key.ENTER, GameAccelerators.ALT));
        Assertions.assertEquals(WINDOW_MODE, find(GameAccelerators.Mode.POPUP, Key.KEYPAD_ENTER, GameAccelerators.ALT),
                                "keypad Enter is Return");
        Assertions.assertEquals(MOUSE_CONFINE, find(GameAccelerators.Mode.POPUP, Key.F1, GameAccelerators.ALT));
        Assertions.assertEquals(EDIT_SELECT_ALL + COPY_OPERATION, find(GameAccelerators.Mode.POPUP, Key.C, GameAccelerators.CONTROL));
    }

    @Test
    void find_should_match_repeats_only_for_repeatable_entries() {
        Assertions.assertNotNull(GameAccelerators.find(GameAccelerators.Mode.PLAY, Key.DIGIT_1, 0, true), "plain digits repeat");
        Assertions.assertNotNull(GameAccelerators.find(GameAccelerators.Mode.PLAY, Key.DIGIT_1, GameAccelerators.ALT, true), "Alt digits repeat");
        Assertions.assertNotNull(GameAccelerators.find(GameAccelerators.Mode.PLAY, Key.F2, 0, true), "F2 repeats");
        Assertions.assertNull(GameAccelerators.find(GameAccelerators.Mode.PLAY, Key.DIGIT_1, GameAccelerators.CONTROL, true), "Ctrl digits do not repeat");
        Assertions.assertNull(GameAccelerators.find(GameAccelerators.Mode.PLAY, Key.EQUAL, 0, true), "speed keys do not repeat");
    }

    @Test
    void is_game_action_should_separate_game_keys_from_interface_keys() {
        Assertions.assertTrue(GameAccelerators.isGameAction(find(GameAccelerators.Mode.PLAY, Key.DIGIT_1, 0)));
        Assertions.assertTrue(GameAccelerators.isGameAction(find(GameAccelerators.Mode.PLAY, Key.DIGIT_0, GameAccelerators.ALT)));
        Assertions.assertTrue(GameAccelerators.isGameAction(GROUP_ADD));
        Assertions.assertTrue(GameAccelerators.isGameAction(GROUP_ASSIGN));
        Assertions.assertTrue(GameAccelerators.isGameAction(GROUP_CENTER));
        Assertions.assertTrue(GameAccelerators.isGameAction(VIEW_RECALL));
        Assertions.assertTrue(GameAccelerators.isGameAction(CENTER_ON_UNIT));
        Assertions.assertTrue(GameAccelerators.isGameAction(DECREASE_SPEED));
        Assertions.assertFalse(GameAccelerators.isGameAction(find(GameAccelerators.Mode.PLAY, Key.O, GameAccelerators.ALT)));
        Assertions.assertFalse(GameAccelerators.isGameAction(LEADERBOARD_PLAYERS));
        Assertions.assertFalse(GameAccelerators.isGameAction(WINDOW_MODE));
        Assertions.assertFalse(GameAccelerators.isGameAction(RESET_ZOOM));
        Assertions.assertFalse(GameAccelerators.isGameAction(MOUSE_CONFINE));
        Assertions.assertFalse(GameAccelerators.isGameAction(SHOW_TURN_RATE));
        Assertions.assertFalse(GameAccelerators.isGameAction(INCREASE_SPEED_OR_CHAT_HISTORY), "resolved by the session first");
    }
}
