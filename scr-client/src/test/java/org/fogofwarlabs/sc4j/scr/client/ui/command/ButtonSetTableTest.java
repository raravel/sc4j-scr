package org.fogofwarlabs.sc4j.scr.client.ui.command;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ButtonSetIds;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

/**
 * Checks transcribed button sets, native position order and shared array identities.
 */
final class ButtonSetTableTest {
    private static final int FIRST_POSITION = 1;
    private static final int LAST_POSITION = 9;
    /**
     * The button records of all {@code 250} sets, each shared native array counted for every set that uses it.
     */
    private static final int BUTTON_RECORD_COUNT = 896;
    private static final int MARINE_SET = ScrUnitIds.TERRAN_MARINE;
    private static final int MARINE_BUTTON_COUNT = 6;
    private static final int MARINE_STIM_PACKS_BUTTON = 5;
    private static final int STIM_PACKS_POSITION = 7;
    private static final int MOVE_STRING = 664;
    private static final int COMMAND_CENTER_SET = ScrUnitIds.TERRAN_COMMAND_CENTER;
    private static final int TRAIN_SCV_BUTTON = 1;
    private static final int CANCEL_LAST_BUTTON = 8;
    /**
     * The action value of Cancel Last: the last queue slot, {@code 254}.
     */
    private static final int CANCEL_LAST_VALUE = 254;
    /**
     * The mixed-selection set of burrowable units, which shares the Zergling's button array.
     */
    private static final int BURROW_SET = 247;
    /**
     * An interface set whose last button changes back to the empty set.
     */
    private static final int CHANGE_BUTTONS_SET = 239;
    private static final int CHANGE_BUTTONS_BUTTON = 8;
    private static final int PLAY_PAUSE_ICON = 386;
    private static final int PLAYING_PLAY_PAUSE_ICON = 387;
    private static final int SCV_TYPE = 7;
    private static final int EMPTY_SET = 228;

    @Test
    void get_buttons_should_return_all_sets_in_position_order_and_reject_out_of_range() {
        int buttons = 0;

        for (int set = 0; set < ButtonSetTable.SET_COUNT; set++) {
            int previous = 0;

            for (ButtonDefinition button : ButtonSetTable.getButtons(set)) {
                Assertions.assertTrue(button.getPosition() >= FIRST_POSITION && button.getPosition() <= LAST_POSITION, "set " + set);
                Assertions.assertTrue(button.getPosition() >= previous, "set " + set + " is not sorted by position");

                previous = button.getPosition();
                buttons++;
            }
        }

        Assertions.assertEquals(BUTTON_RECORD_COUNT, buttons);
        Assertions.assertEquals(0, ButtonSetTable.getButtons(ButtonSetTable.NO_SET).length);
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> ButtonSetTable.getButtons(ButtonSetTable.SET_COUNT));
    }

    @Test
    void get_buttons_should_return_marine_and_command_center_sets() {
        ButtonDefinition[] marine = ButtonSetTable.getButtons(MARINE_SET);

        Assertions.assertEquals(MARINE_BUTTON_COUNT, marine.length);
        Assertions.assertEquals(ButtonAction.MOVE, marine[0].getAction());
        Assertions.assertEquals(MOVE_STRING, marine[0].getEnabledString());
        Assertions.assertEquals(ButtonAction.STIM_PACKS, marine[MARINE_STIM_PACKS_BUTTON].getAction());
        Assertions.assertEquals(ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, marine[MARINE_STIM_PACKS_BUTTON].getCondition());
        Assertions.assertEquals(STIM_PACKS_POSITION, marine[MARINE_STIM_PACKS_BUTTON].getPosition());

        ButtonDefinition[] commandCenter = ButtonSetTable.getButtons(COMMAND_CENTER_SET);

        Assertions.assertEquals(ButtonAction.TRAIN, commandCenter[TRAIN_SCV_BUTTON].getAction());
        Assertions.assertEquals(SCV_TYPE, commandCenter[TRAIN_SCV_BUTTON].getActionValue());
        Assertions.assertEquals(ButtonAction.CANCEL_LAST, commandCenter[CANCEL_LAST_BUTTON].getAction());
        Assertions.assertEquals(CANCEL_LAST_VALUE, commandCenter[CANCEL_LAST_BUTTON].getActionValue());
    }

    @Test
    void get_buttons_should_return_shared_arrays_for_sets_with_same_buttons() {
        Assertions.assertSame(ButtonSetTable.getButtons(MARINE_SET), ButtonSetTable.getButtons(ScrUnitIds.HERO_GUI_MONTAG));
        Assertions.assertSame(ButtonSetTable.getButtons(MARINE_SET), ButtonSetTable.getButtons(ScrUnitIds.HERO_JIM_RAYNOR_MARINE));
        Assertions.assertSame(ButtonSetTable.getButtons(ScrUnitIds.TERRAN_SIEGE_TANK_TANK_MODE),
                              ButtonSetTable.getButtons(ScrUnitIds.TERRAN_SIEGE_TANK_SIEGE_MODE));
        Assertions.assertSame(ButtonSetTable.getButtons(ScrUnitIds.ZERG_ZERGLING), ButtonSetTable.getButtons(BURROW_SET));
        Assertions.assertNotSame(ButtonSetTable.getButtons(MARINE_SET), ButtonSetTable.getButtons(ScrUnitIds.TERRAN_GHOST));
    }

    @Test
    void button_sets_reference_every_condition_and_action() {
        Set<ButtonCondition> conditions = EnumSet.noneOf(ButtonCondition.class);
        Set<ButtonAction> actions = EnumSet.noneOf(ButtonAction.class);

        for (int set = 0; set < ButtonSetTable.SET_COUNT; set++) {
            for (ButtonDefinition button : ButtonSetTable.getButtons(set)) {
                conditions.add(button.getCondition());
                actions.add(button.getAction());
            }
        }

        // The table holds every native function; SC:R's own script functions are for game plugins.
        Assertions.assertEquals(EnumSet.complementOf(EnumSet.of(ButtonCondition.SCRIPT)), conditions);
        Assertions.assertEquals(EnumSet.complementOf(EnumSet.of(ButtonAction.SCRIPT, ButtonAction.SCRIPT_TARGET)), actions);
    }

    @Test
    void get_buttons_should_return_interface_and_replay_sets() {
        Assertions.assertEquals(ButtonAction.CANCEL_TARGETING, ButtonSetTable.getButtons(ButtonSetIds.CANCEL_TARGETING)[0].getAction());
        Assertions.assertEquals(ButtonAction.CANCEL_PLACEMENT, ButtonSetTable.getButtons(ButtonSetIds.CANCEL_PLACEMENT)[0].getAction());
        Assertions.assertEquals(ButtonAction.CHANGE_BUTTONS, ButtonSetTable.getButtons(CHANGE_BUTTONS_SET)[CHANGE_BUTTONS_BUTTON].getAction());
        Assertions.assertEquals(EMPTY_SET, ButtonSetTable.getButtons(CHANGE_BUTTONS_SET)[CHANGE_BUTTONS_BUTTON].getActionValue());
        Assertions.assertEquals(ButtonAction.REPLAY_PLAY_PAUSE, ButtonSetTable.getButtons(ButtonSetIds.REPLAY_PAUSED)[0].getAction());
        Assertions.assertEquals(PLAY_PAUSE_ICON, ButtonSetTable.getButtons(ButtonSetIds.REPLAY_PAUSED)[0].getIcon());
        Assertions.assertEquals(PLAYING_PLAY_PAUSE_ICON, ButtonSetTable.getButtons(ButtonSetIds.REPLAY_PLAYING)[0].getIcon());
    }
}
