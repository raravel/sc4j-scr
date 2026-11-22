package org.fogofwarlabs.sc4j.scr.client.ui.command;

import org.fogofwarlabs.sc4j.scr.data.type.constant.ButtonSetIds;
import org.fogofwarlabs.sc4j.scr.game.presentation.HostButtonSets;
import org.jetbrains.annotations.NotNull;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Holds the fixed command-button table in position order.
 * Sets below {@code 228} belong to unit types; later sets provide interface menus, cancellation and replay controls.
 * Entries that share a button array retain the same Java array identity for mixed-selection comparisons.
 */
public final class ButtonSetTable {
    /**
     * The number of fixed button sets.
     */
    public static final int SET_COUNT = 250;

    /**
     * The empty set and no-set marker ({@code 0xe4}).
     */
    public static final int NO_SET = ButtonSetIds.EMPTY;

    private static final int NO_CONNECTED_SET = 0xFFFF;

    private static final @NotNull ButtonDefinition @NotNull [] @NotNull [] SETS = new ButtonDefinition[SET_COUNT][];
    private static final int @NotNull [] CONNECTED_SETS = new int[SET_COUNT];

    static {
        defineSets0();
        defineSets1();
        defineSets2();
        defineSets3();
        defineSets4();
    }

    /**
     * Returns the buttons of a set in position order.
     *
     * @param set the button set from {@code 0} through {@code 249}
     * @return the shared button definitions; callers must not modify the array
     * @throws IndexOutOfBoundsException if the set is outside the table
     */
    public static @NotNull ButtonDefinition @NotNull [] getButtons(int set) {
        return SETS[Objects.checkIndex(set, SET_COUNT)];
    }

    /**
     * Returns the connected set stored with a set entry, {@code 0xffff} for none.
     *
     * @param set the button set from {@code 0} through {@code 249}
     * @return the connected set value
     * @throws IndexOutOfBoundsException if the set is outside the table
     */
    public static int getConnectedSet(int set) {
        return CONNECTED_SETS[Objects.checkIndex(set, SET_COUNT)];
    }

    /**
     * Converts the table to the buttons of a game's table ({@link HostButtonSets}): the condition and action functions as their addresses, the
     * strings as their ids. The sets that share an array here share one converted array.
     *
     * @return the converted arrays by set, each new
     */
    public static HostButtonSets.@NotNull Button @NotNull [] @NotNull [] createHostButtons() {
        HostButtonSets.Button[][] converted = new HostButtonSets.Button[SET_COUNT][];
        Map<ButtonDefinition[], HostButtonSets.Button[]> arrays = new IdentityHashMap<>();

        for (int set = 0; set < SET_COUNT; set++) {
            converted[set] = arrays.computeIfAbsent(SETS[set], buttons -> {
                HostButtonSets.Button[] array = new HostButtonSets.Button[buttons.length];

                for (int index = 0; index < buttons.length; index++) {
                    ButtonDefinition button = buttons[index];

                    array[index] = new HostButtonSets.Button(button.getPosition(), button.getIcon(),
                                                             button.getCondition().getNativeAddress(), button.getConditionValue(),
                                                             button.getAction().getNativeAddress(), button.getActionValue(),
                                                             button.getEnabledString(), button.getDisabledString());
                }

                return array;
            });
        }

        return converted;
    }

    private static void defineSet(int set, int connected, @NotNull ButtonDefinition @NotNull [] buttons) {
        SETS[set]           = buttons;
        CONNECTED_SETS[set] = connected;
    }

    private static @NotNull ButtonDefinition createButton(int position, int icon,
                                                          @NotNull ButtonCondition condition, int conditionValue,
                                                          @NotNull ButtonAction action, int actionValue,
                                                          int enabledString, int disabledString) {
        return new ButtonDefinition(position, icon, condition, conditionValue, action, actionValue, enabledString, disabledString);
    }

    private static void defineSets0() {
        defineSet(0, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 237, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 0, ButtonAction.STIM_PACKS, 0, 334, 346),
                });

        defineSet(1, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 252, ButtonCondition.CAN_CLOAK, 10, ButtonAction.CLOAK, 10, 344, 355),
                createButton(7, 253, ButtonCondition.CAN_DECLOAK, 10, ButtonAction.DECLOAK, 0, 345, 0),
                createButton(8, 240, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 1, ButtonAction.USE_TECHNOLOGY, 1, 335, 347),
                createButton(9, 311, ButtonCondition.NUCLEAR_SILO_READY, 0, ButtonAction.NUCLEAR_STRIKE, 0, 685, 761),
                });

        defineSet(2, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 243, ButtonCondition.ANY_TECHNOLOGY_USE_REQUIREMENT, 3, ButtonAction.USE_TECHNOLOGY, 3, 336, 348),
                });

        defineSet(3, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                });
        defineSet(4, NO_CONNECTED_SET, new ButtonDefinition[0]);

        defineSet(5, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.NONE_SIEGED, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.NONE_SIEGED, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.NONE_SIEGED, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 245, ButtonCondition.CAN_SIEGE, 5, ButtonAction.SIEGE, 5, 338, 349),
                createButton(7, 246, ButtonCondition.CAN_UNSIEGE, 5, ButtonAction.UNSIEGE, 0, 339, 0),
                });
        defineSet(6, NO_CONNECTED_SET, new ButtonDefinition[0]);

        defineSet(7, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ANY_NOT_CONSTRUCTING, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ANY_NOT_CONSTRUCTING, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_NOT_CONSTRUCTING, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 232, ButtonCondition.ANY_NOT_CONSTRUCTING, 0, ButtonAction.REPAIR, 0, 677, 0),
                createButton(5, 231, ButtonCondition.SCV_CAN_GATHER, 0, ButtonAction.GATHER, 0, 675, 0),
                createButton(6, 233, ButtonCondition.SCV_CAN_RETURN_CARGO, 0, ButtonAction.RETURN_CARGO, 0, 676, 0),
                createButton(7, 234,
                             ButtonCondition.TERRAN_BASIC_STRUCTURES, 0,
                             ButtonAction.CHANGE_BUTTONS, ButtonSetIds.TERRAN_BASIC_STRUCTURES,
                             678, 0),
                createButton(8, 235,
                             ButtonCondition.TERRAN_ADVANCED_STRUCTURES, 0,
                             ButtonAction.CHANGE_BUTTONS, ButtonSetIds.TERRAN_ADVANCED_STRUCTURES,
                             679, 0),
                createButton(9, 236, ButtonCondition.ALL_CONSTRUCTING, 0, ButtonAction.STOP, 0, 700, 0),
                });

        defineSet(8, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 252, ButtonCondition.CAN_CLOAK, 9, ButtonAction.CLOAK, 9, 344, 354),
                createButton(7, 253, ButtonCondition.CAN_DECLOAK, 9, ButtonAction.DECLOAK, 0, 345, 0),
                });

        defineSet(9, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 247, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 6, ButtonAction.USE_TECHNOLOGY, 6, 340, 350),
                createButton(8, 241, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 2, ButtonAction.USE_TECHNOLOGY, 2, 341, 351),
                createButton(9, 242, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 7, ButtonAction.USE_TECHNOLOGY, 7, 342, 352),
                });
        defineSet(10, NO_CONNECTED_SET, SETS[0]);

        defineSet(11, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(8, 309, ButtonCondition.ANY_CAN_LOAD, 0, ButtonAction.LOAD, 0, 683, 0),
                createButton(9, 312, ButtonCondition.ANY_HAS_LOADED, 0, ButtonAction.UNLOAD_ALL, 0, 684, 0),
                });

        defineSet(12, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 251, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 8, ButtonAction.USE_TECHNOLOGY, 8, 343, 353),
                });
        defineSet(13, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(14, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(15, NO_CONNECTED_SET, SETS[3]);

        defineSet(16, 1, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 252, ButtonCondition.CAN_CLOAK, 10, ButtonAction.CLOAK, 10, 344, 355),
                createButton(7, 253, ButtonCondition.CAN_DECLOAK, 10, ButtonAction.DECLOAK, 0, 345, 0),
                createButton(8, 240, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 1, ButtonAction.USE_TECHNOLOGY, 1, 335, 347),
                });
        defineSet(17, NO_CONNECTED_SET, SETS[3]);
        defineSet(18, NO_CONNECTED_SET, new ButtonDefinition[0]);

        defineSet(19, 2, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 243, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 3, ButtonAction.USE_TECHNOLOGY, 3, 336, 348),
                });
        defineSet(20, NO_CONNECTED_SET, SETS[0]);
        defineSet(21, NO_CONNECTED_SET, SETS[8]);
        defineSet(22, NO_CONNECTED_SET, SETS[9]);
        defineSet(23, NO_CONNECTED_SET, SETS[5]);
        defineSet(24, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(25, NO_CONNECTED_SET, SETS[5]);
        defineSet(26, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(27, NO_CONNECTED_SET, SETS[3]);
        defineSet(28, NO_CONNECTED_SET, SETS[12]);
        defineSet(29, NO_CONNECTED_SET, SETS[12]);
        defineSet(30, NO_CONNECTED_SET, SETS[5]);
        defineSet(31, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(32, NO_CONNECTED_SET, SETS[0]);
        defineSet(33, NO_CONNECTED_SET, new ButtonDefinition[0]);

        defineSet(34, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 365, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 34, ButtonAction.HEAL, 34, 1247, 0),
                createButton(8, 367, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 24, ButtonAction.USE_TECHNOLOGY, 24, 1248, 1250),
                createButton(9, 373, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 30, ButtonAction.USE_TECHNOLOGY, 30, 1249, 1251),
                });

        defineSet(35, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 41, ButtonCondition.UNIT_REQUIREMENT, 41, ButtonAction.UNIT_MORPH, 41, 578, 0),
                createButton(2, 37, ButtonCondition.UNIT_REQUIREMENT, 37, ButtonAction.UNIT_MORPH, 37, 575, 738),
                createButton(3, 42, ButtonCondition.UNIT_REQUIREMENT, 42, ButtonAction.UNIT_MORPH, 42, 579, 0),
                createButton(4, 38, ButtonCondition.UNIT_REQUIREMENT, 38, ButtonAction.UNIT_MORPH, 38, 576, 739),
                createButton(5, 43, ButtonCondition.UNIT_REQUIREMENT, 43, ButtonAction.UNIT_MORPH, 43, 580, 741),
                createButton(6, 47, ButtonCondition.UNIT_REQUIREMENT, 47, ButtonAction.UNIT_MORPH, 47, 584, 745),
                createButton(7, 45, ButtonCondition.UNIT_REQUIREMENT, 45, ButtonAction.UNIT_MORPH, 45, 582, 743),
                createButton(8, 39, ButtonCondition.UNIT_REQUIREMENT, 39, ButtonAction.UNIT_MORPH, 39, 577, 740),
                createButton(9, 46, ButtonCondition.UNIT_REQUIREMENT, 46, ButtonAction.UNIT_MORPH, 46, 583, 744),
                });
        defineSet(36, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(9, 236, ButtonCondition.ALWAYS, 0, ButtonAction.CANCEL_MORPH, 0, 695, 0),
                });

        defineSet(37, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.NONE_BURROWED, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.NONE_BURROWED, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.NONE_BURROWED_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.NONE_BURROWED, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.NONE_BURROWED, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(9, 259, ButtonCondition.CAN_BURROW, 11, ButtonAction.BURROW, 11, 372, 382),
                createButton(9, 260, ButtonCondition.CAN_UNBURROW, 11, ButtonAction.UNBURROW, 0, 373, 0),
                });

        defineSet(38, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.NONE_BURROWED, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.NONE_BURROWED, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.NONE_BURROWED_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.NONE_BURROWED, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.NONE_BURROWED, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 103, ButtonCondition.LURKER_ASPECT, 103, ButtonAction.UNIT_MORPH, 103, 1289, 1298),
                createButton(9, 259, ButtonCondition.CAN_BURROW, 11, ButtonAction.BURROW, 11, 372, 382),
                createButton(9, 260, ButtonCondition.CAN_UNBURROW, 11, ButtonAction.UNBURROW, 0, 373, 0),
                });
        defineSet(39, NO_CONNECTED_SET, SETS[3]);
        defineSet(40, NO_CONNECTED_SET, SETS[3]);

        defineSet(41, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.NONE_BURROWED, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.NONE_BURROWED, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.NONE_BURROWED_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(5, 231, ButtonCondition.NONE_BURROWED_NOT_CARRYING, 0, ButtonAction.GATHER, 0, 675, 0),
                createButton(6, 233, ButtonCondition.NONE_BURROWED_CARRYING, 0, ButtonAction.RETURN_CARGO, 0, 676, 0),
                createButton(7, 257,
                             ButtonCondition.ZERG_BASIC_STRUCTURES, 0,
                             ButtonAction.CHANGE_BUTTONS, ButtonSetIds.ZERG_BASIC_STRUCTURES,
                             680, 0),
                createButton(8, 258,
                             ButtonCondition.ZERG_ADVANCED_STRUCTURES, 0,
                             ButtonAction.CHANGE_BUTTONS, ButtonSetIds.ZERG_ADVANCED_STRUCTURES,
                             681, 0),
                createButton(9, 259, ButtonCondition.CAN_BURROW, 11, ButtonAction.BURROW, 11, 372, 382),
                createButton(9, 260, ButtonCondition.CAN_UNBURROW, 11, ButtonAction.UNBURROW, 0, 373, 0),
                });
        defineSet(42, NO_CONNECTED_SET, SETS[11]);

        defineSet(43, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 44, ButtonCondition.UNIT_REQUIREMENT, 44, ButtonAction.UNIT_MORPH, 44, 581, 742),
                createButton(8, 62, ButtonCondition.UNIT_REQUIREMENT, 62, ButtonAction.UNIT_MORPH, 62, 1288, 1297),
                });
        defineSet(44, NO_CONNECTED_SET, SETS[3]);

        defineSet(45, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(6, 313, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 12, ButtonAction.USE_TECHNOLOGY, 12, 374, 0),
                createButton(7, 271, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 18, ButtonAction.USE_TECHNOLOGY, 18, 377, 386),
                createButton(8, 40, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 13, ButtonAction.USE_TECHNOLOGY, 13, 375, 384),
                createButton(9, 267, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 17, ButtonAction.USE_TECHNOLOGY, 17, 381, 389),
                });

        defineSet(46, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.NONE_BURROWED, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.NONE_BURROWED, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(4, 254, ButtonCondition.NONE_BURROWED, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.NONE_BURROWED, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(6, 302, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 16, ButtonAction.USE_TECHNOLOGY, 16, 379, 388),
                createButton(7, 270, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 14, ButtonAction.USE_TECHNOLOGY, 14, 376, 385),
                createButton(8, 265, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 15, ButtonAction.USE_TECHNOLOGY, 15, 378, 387),
                createButton(9, 259, ButtonCondition.CAN_BURROW, 11, ButtonAction.BURROW, 11, 372, 382),
                createButton(9, 260, ButtonCondition.CAN_UNBURROW, 11, ButtonAction.UNBURROW, 0, 373, 0),
                });
        defineSet(47, NO_CONNECTED_SET, SETS[3]);
        defineSet(48, NO_CONNECTED_SET, SETS[3]);
        defineSet(49, NO_CONNECTED_SET, SETS[45]);
    }

    private static void defineSets1() {
        defineSet(50, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.NONE_BURROWED, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.NONE_BURROWED, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.NONE_BURROWED_CAN_ATTACK, 0, ButtonAction.INFESTED_TERRAN_ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.NONE_BURROWED, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.NONE_BURROWED, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(9, 259, ButtonCondition.CAN_BURROW, 11, ButtonAction.BURROW, 11, 372, 382),
                createButton(9, 260, ButtonCondition.CAN_UNBURROW, 11, ButtonAction.UNBURROW, 0, 373, 0),
                });

        defineSet(51, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(6, 302, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 16, ButtonAction.USE_TECHNOLOGY, 16, 380, 0),
                createButton(7, 252, ButtonCondition.CAN_CLOAK_OWN_TECHNOLOGY, 10, ButtonAction.CLOAK, 10, 344, 0),
                createButton(7, 253, ButtonCondition.CAN_DECLOAK_OWN_TECHNOLOGY, 10, ButtonAction.DECLOAK, 10, 345, 0),
                createButton(8, 267, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 17, ButtonAction.USE_TECHNOLOGY, 17, 381, 0),
                createButton(9, 275, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 19, ButtonAction.USE_TECHNOLOGY, 19, 400, 0),
                });
        defineSet(52, NO_CONNECTED_SET, SETS[46]);
        defineSet(53, NO_CONNECTED_SET, SETS[37]);
        defineSet(54, NO_CONNECTED_SET, SETS[37]);
        defineSet(55, NO_CONNECTED_SET, SETS[3]);
        defineSet(56, NO_CONNECTED_SET, SETS[3]);
        defineSet(57, NO_CONNECTED_SET, SETS[11]);
        defineSet(58, NO_CONNECTED_SET, SETS[3]);
        defineSet(59, NO_CONNECTED_SET, SETS[36]);

        defineSet(60, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 369, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 25, ButtonAction.USE_TECHNOLOGY, 25, 1265, 1269),
                });

        defineSet(61, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(9, 68, ButtonCondition.TECHNOLOGY_USE_DISABLED, 28, ButtonAction.STOP, 28, 1264, 1271),
                createButton(9, 63, ButtonCondition.DARK_ARCHON_MELD, 63, ButtonAction.DARK_ARCHON_MELD, 63, 1264, 1272),
                });
        defineSet(62, NO_CONNECTED_SET, SETS[3]);

        defineSet(63, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 372, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 29, ButtonAction.USE_TECHNOLOGY, 29, 1267, 1273),
                createButton(8, 371, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 27, ButtonAction.USE_TECHNOLOGY, 27, 1266, 1270),
                createButton(9, 381, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 31, ButtonAction.USE_TECHNOLOGY, 31, 1268, 1274),
                });

        defineSet(64, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(5, 231, ButtonCondition.ANY_NOT_CARRYING, 0, ButtonAction.GATHER, 0, 675, 0),
                createButton(6, 233, ButtonCondition.ANY_CARRYING, 0, ButtonAction.RETURN_CARGO, 0, 676, 0),
                createButton(7, 272,
                             ButtonCondition.PROTOSS_BASIC_STRUCTURES, 0,
                             ButtonAction.CHANGE_BUTTONS, ButtonSetIds.PROTOSS_BASIC_STRUCTURES,
                             678, 0),
                createButton(8, 273,
                             ButtonCondition.PROTOSS_ADVANCED_STRUCTURES, 0,
                             ButtonAction.CHANGE_BUTTONS, ButtonSetIds.PROTOSS_ADVANCED_STRUCTURES,
                             679, 0),
                });
        defineSet(65, NO_CONNECTED_SET, SETS[3]);
        defineSet(66, NO_CONNECTED_SET, SETS[3]);

        defineSet(67, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 275, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 19, ButtonAction.USE_TECHNOLOGY, 19, 400, 405),
                createButton(8, 277, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 20, ButtonAction.USE_TECHNOLOGY, 20, 401, 406),
                createButton(9, 68, ButtonCondition.TECHNOLOGY_USE_DISABLED, 23, ButtonAction.STOP, 23, 404, 409),
                createButton(9, 68, ButtonCondition.ARCHON_WARP, 68, ButtonAction.ARCHON_WARP, 68, 404, 410),
                });
        defineSet(68, NO_CONNECTED_SET, SETS[3]);
        defineSet(69, NO_CONNECTED_SET, SETS[11]);
        defineSet(70, NO_CONNECTED_SET, SETS[3]);

        defineSet(71, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 280, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 21, ButtonAction.USE_TECHNOLOGY, 21, 402, 407),
                createButton(8, 278, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 22, ButtonAction.USE_TECHNOLOGY, 22, 403, 408),
                });

        defineSet(72, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.CARRIER_MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.CARRIER_STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.CARRIER_HAS_INTERCEPTORS, 0, ButtonAction.CARRIER_ATTACK, 0, 666, 759),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 73, ButtonCondition.HANGAR_UNIT_REQUIREMENT, 73, ButtonAction.TRAIN_HANGAR_UNIT, 73, 607, 0),
                createButton(9, 236, ButtonCondition.TRAINING_UNIT, 0, ButtonAction.CANCEL_LAST, 254, 693, 0),
                });
        defineSet(73, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(74, NO_CONNECTED_SET, SETS[3]);
        defineSet(75, NO_CONNECTED_SET, SETS[3]);
        defineSet(76, NO_CONNECTED_SET, SETS[3]);
        defineSet(77, NO_CONNECTED_SET, SETS[3]);
        defineSet(78, NO_CONNECTED_SET, SETS[3]);

        defineSet(79, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 275, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 19, ButtonAction.USE_TECHNOLOGY, 19, 400, 405),
                createButton(8, 277, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 20, ButtonAction.USE_TECHNOLOGY, 20, 401, 406),
                });
        defineSet(80, NO_CONNECTED_SET, SETS[3]);

        defineSet(81, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.CARRIER_MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.REAVER_STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.REAVER_HAS_SCARABS, 0, ButtonAction.REAVER_ATTACK, 0, 666, 760),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 85, ButtonCondition.HANGAR_UNIT_REQUIREMENT, 85, ButtonAction.TRAIN_HANGAR_UNIT, 85, 609, 0),
                createButton(9, 236, ButtonCondition.TRAINING_UNIT, 0, ButtonAction.CANCEL_LAST, 254, 693, 0),
                });
        defineSet(82, NO_CONNECTED_SET, SETS[72]);
        defineSet(83, NO_CONNECTED_SET, SETS[81]);
        defineSet(84, NO_CONNECTED_SET, SETS[3]);
        defineSet(85, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(86, NO_CONNECTED_SET, SETS[71]);
        defineSet(87, NO_CONNECTED_SET, SETS[79]);
        defineSet(88, NO_CONNECTED_SET, SETS[3]);
        defineSet(89, NO_CONNECTED_SET, SETS[3]);
        defineSet(90, NO_CONNECTED_SET, SETS[3]);
        defineSet(91, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(92, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(93, NO_CONNECTED_SET, SETS[3]);
        defineSet(94, NO_CONNECTED_SET, SETS[3]);
        defineSet(95, NO_CONNECTED_SET, SETS[3]);
        defineSet(96, NO_CONNECTED_SET, SETS[3]);
        defineSet(97, NO_CONNECTED_SET, SETS[36]);
        defineSet(98, NO_CONNECTED_SET, SETS[60]);
        defineSet(99, NO_CONNECTED_SET, SETS[16]);
    }

    private static void defineSets2() {
        defineSet(100, NO_CONNECTED_SET, SETS[16]);
        defineSet(101, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(102, NO_CONNECTED_SET, SETS[12]);

        defineSet(103, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.NONE_BURROWED, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.SAME_BURROW_STATE, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.CAN_UNBURROW, 11, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.NONE_BURROWED, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.NONE_BURROWED, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(9, 259, ButtonCondition.CAN_BURROW, 11, ButtonAction.BURROW, 11, 372, 382),
                createButton(9, 260, ButtonCondition.CAN_UNBURROW, 11, ButtonAction.UNBURROW, 0, 373, 0),
                });

        defineSet(104, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 252, ButtonCondition.CAN_CLOAK_OWN_TECHNOLOGY, 10, ButtonAction.CLOAK, 10, 344, 0),
                createButton(7, 253, ButtonCondition.CAN_DECLOAK_OWN_TECHNOLOGY, 10, ButtonAction.DECLOAK, 10, 345, 0),
                createButton(8, 240, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 1, ButtonAction.USE_TECHNOLOGY, 1, 335, 347),
                createButton(9, 302, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 16, ButtonAction.USE_TECHNOLOGY, 16, 380, 0),
                });
        defineSet(105, NO_CONNECTED_SET, new ButtonDefinition[0]);

        defineSet(106, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.NOT_GROUNDED, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(1, 7, ButtonCondition.UNIT_REQUIREMENT, 7, ButtonAction.TRAIN, 7, 592, 0),
                createButton(2, 229, ButtonCondition.NOT_GROUNDED, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(6, 286, ButtonCondition.RALLY_ORDER, 0, ButtonAction.SET_RALLY_POINT, 0, 672, 0),
                createButton(7, 107, ButtonCondition.UNIT_REQUIREMENT, 107, ButtonAction.BUILD_ADDON, 107, 658, 708),
                createButton(8, 108, ButtonCondition.UNIT_REQUIREMENT, 108, ButtonAction.BUILD_ADDON, 108, 659, 709),
                createButton(9, 283, ButtonCondition.CAN_LAND, 0, ButtonAction.LAND, 106, 670, 0),
                createButton(9, 282, ButtonCondition.CAN_LIFT_OFF, 0, ButtonAction.LIFT_OFF, 0, 671, 0),
                createButton(9, 236, ButtonCondition.TRAINING_UNIT, 0, ButtonAction.CANCEL_LAST, 254, 693, 0),
                createButton(9, 236, ButtonCondition.BUILDING_ADDON, 0, ButtonAction.CANCEL_ADDON, 0, 694, 0),
                });

        defineSet(107, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 250, ButtonCondition.TECHNOLOGY_USE_REQUIREMENT, 4, ButtonAction.USE_TECHNOLOGY, 4, 337, 0),
                });

        defineSet(108, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 14, ButtonCondition.SINGLE_UNIT_REQUIREMENT, 14, ButtonAction.TRAIN, 14, 597, 0),
                createButton(9, 236, ButtonCondition.TRAINING_UNIT, 0, ButtonAction.CANCEL_LAST, 254, 693, 0),
                });
        defineSet(109, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(110, NO_CONNECTED_SET, new ButtonDefinition[0]);

        defineSet(111, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.NOT_GROUNDED, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(1, 0, ButtonCondition.UNIT_REQUIREMENT, 0, ButtonAction.TRAIN, 0, 586, 0),
                createButton(2, 229, ButtonCondition.NOT_GROUNDED, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(2, 32, ButtonCondition.UNIT_REQUIREMENT, 32, ButtonAction.TRAIN, 32, 588, 702),
                createButton(3, 1, ButtonCondition.UNIT_REQUIREMENT, 1, ButtonAction.TRAIN, 1, 587, 701),
                createButton(4, 34, ButtonCondition.UNIT_REQUIREMENT, 34, ButtonAction.TRAIN, 34, 1290, 1294),
                createButton(6, 286, ButtonCondition.RALLY_ORDER, 0, ButtonAction.SET_RALLY_POINT, 0, 672, 0),
                createButton(9, 283, ButtonCondition.CAN_LAND, 0, ButtonAction.LAND, 111, 670, 0),
                createButton(9, 282, ButtonCondition.CAN_LIFT_OFF, 0, ButtonAction.LIFT_OFF, 0, 671, 0),
                createButton(9, 236, ButtonCondition.TRAINING_UNIT, 0, ButtonAction.CANCEL_LAST, 254, 693, 0),
                createButton(9, 236, ButtonCondition.BUILDING_ADDON, 0, ButtonAction.CANCEL_ADDON, 0, 694, 0),
                });

        defineSet(112, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 238, ButtonCondition.UPGRADE_REQUIREMENT, 16, ButtonAction.UPGRADE, 16, 472, 0),
                createButton(2, 237, ButtonCondition.RESEARCH_REQUIREMENT, 0, ButtonAction.RESEARCH, 0, 324, 0),
                createButton(4, 366, ButtonCondition.RESEARCH_REQUIREMENT, 24, ButtonAction.RESEARCH, 24, 1245, 0),
                createButton(5, 373, ButtonCondition.RESEARCH_REQUIREMENT, 30, ButtonAction.RESEARCH, 30, 1246, 0),
                createButton(6, 384, ButtonCondition.UPGRADE_REQUIREMENT, 51, ButtonAction.UPGRADE, 51, 1281, 0),
                createButton(9, 236, ButtonCondition.RESEARCHING, 0, ButtonAction.CANCEL_RESEARCH, 0, 692, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(113, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.NOT_GROUNDED, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(1, 2, ButtonCondition.UNIT_REQUIREMENT, 2, ButtonAction.TRAIN, 2, 589, 0),
                createButton(2, 229, ButtonCondition.NOT_GROUNDED, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(2, 5, ButtonCondition.UNIT_REQUIREMENT, 5, ButtonAction.TRAIN, 5, 591, 704),
                createButton(3, 3, ButtonCondition.UNIT_REQUIREMENT, 3, ButtonAction.TRAIN, 3, 590, 703),
                createButton(6, 286, ButtonCondition.RALLY_ORDER, 0, ButtonAction.SET_RALLY_POINT, 0, 672, 0),
                createButton(7, 120, ButtonCondition.UNIT_REQUIREMENT, 120, ButtonAction.BUILD_ADDON, 120, 663, 0),
                createButton(9, 283, ButtonCondition.CAN_LAND, 0, ButtonAction.LAND, 113, 670, 0),
                createButton(9, 282, ButtonCondition.CAN_LIFT_OFF, 0, ButtonAction.LIFT_OFF, 0, 671, 0),
                createButton(9, 236, ButtonCondition.TRAINING_UNIT, 0, ButtonAction.CANCEL_LAST, 254, 693, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                createButton(9, 236, ButtonCondition.BUILDING_ADDON, 0, ButtonAction.CANCEL_ADDON, 0, 694, 0),
                });

        defineSet(114, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.NOT_GROUNDED, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(1, 8, ButtonCondition.UNIT_REQUIREMENT, 8, ButtonAction.TRAIN, 8, 593, 0),
                createButton(2, 229, ButtonCondition.NOT_GROUNDED, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(2, 11, ButtonCondition.UNIT_REQUIREMENT, 11, ButtonAction.TRAIN, 11, 595, 706),
                createButton(3, 9, ButtonCondition.UNIT_REQUIREMENT, 9, ButtonAction.TRAIN, 9, 594, 705),
                createButton(4, 12, ButtonCondition.UNIT_REQUIREMENT, 12, ButtonAction.TRAIN, 12, 596, 707),
                createButton(5, 58, ButtonCondition.UNIT_REQUIREMENT, 58, ButtonAction.TRAIN, 58, 1291, 1295),
                createButton(6, 286, ButtonCondition.RALLY_ORDER, 0, ButtonAction.SET_RALLY_POINT, 0, 672, 0),
                createButton(7, 115, ButtonCondition.UNIT_REQUIREMENT, 115, ButtonAction.BUILD_ADDON, 115, 660, 0),
                createButton(9, 283, ButtonCondition.CAN_LAND, 0, ButtonAction.LAND, 114, 670, 0),
                createButton(9, 282, ButtonCondition.CAN_LIFT_OFF, 0, ButtonAction.LIFT_OFF, 0, 671, 0),
                createButton(9, 236, ButtonCondition.TRAINING_UNIT, 0, ButtonAction.CANCEL_LAST, 254, 693, 0),
                createButton(9, 236, ButtonCondition.BUILDING_ADDON, 0, ButtonAction.CANCEL_ADDON, 0, 694, 0),
                });

        defineSet(115, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 252, ButtonCondition.RESEARCH_REQUIREMENT, 9, ButtonAction.RESEARCH, 9, 332, 0),
                createButton(2, 284, ButtonCondition.UPGRADE_REQUIREMENT, 22, ButtonAction.UPGRADE, 22, 478, 0),
                createButton(9, 236, ButtonCondition.RESEARCHING, 0, ButtonAction.CANCEL_RESEARCH, 0, 692, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(116, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.NOT_GROUNDED, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(1, 241, ButtonCondition.RESEARCH_REQUIREMENT, 2, ButtonAction.RESEARCH, 2, 326, 0),
                createButton(2, 229, ButtonCondition.NOT_GROUNDED, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(2, 242, ButtonCondition.RESEARCH_REQUIREMENT, 7, ButtonAction.RESEARCH, 7, 330, 0),
                createButton(3, 248, ButtonCondition.UPGRADE_REQUIREMENT, 19, ButtonAction.UPGRADE, 19, 475, 0),
                createButton(7, 117, ButtonCondition.UNIT_REQUIREMENT, 117, ButtonAction.BUILD_ADDON, 117, 661, 0),
                createButton(8, 118, ButtonCondition.UNIT_REQUIREMENT, 118, ButtonAction.BUILD_ADDON, 118, 662, 0),
                createButton(9, 283, ButtonCondition.CAN_LAND, 0, ButtonAction.LAND, 116, 670, 0),
                createButton(9, 282, ButtonCondition.CAN_LIFT_OFF, 0, ButtonAction.LIFT_OFF, 0, 671, 0),
                createButton(9, 236, ButtonCondition.RESEARCHING, 0, ButtonAction.CANCEL_RESEARCH, 0, 692, 0),
                createButton(9, 236, ButtonCondition.BUILDING_ADDON, 0, ButtonAction.CANCEL_ADDON, 0, 694, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(117, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 240, ButtonCondition.RESEARCH_REQUIREMENT, 1, ButtonAction.RESEARCH, 1, 325, 0),
                createButton(2, 252, ButtonCondition.RESEARCH_REQUIREMENT, 10, ButtonAction.RESEARCH, 10, 333, 0),
                createButton(4, 249, ButtonCondition.UPGRADE_REQUIREMENT, 20, ButtonAction.UPGRADE, 20, 476, 0),
                createButton(5, 256, ButtonCondition.UPGRADE_REQUIREMENT, 21, ButtonAction.UPGRADE, 21, 477, 0),
                createButton(9, 236, ButtonCondition.RESEARCHING, 0, ButtonAction.CANCEL_RESEARCH, 0, 692, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(118, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 251, ButtonCondition.RESEARCH_REQUIREMENT, 8, ButtonAction.RESEARCH, 8, 331, 0),
                createButton(2, 285, ButtonCondition.UPGRADE_REQUIREMENT, 23, ButtonAction.UPGRADE, 23, 479, 0),
                createButton(9, 236, ButtonCondition.RESEARCHING, 0, ButtonAction.CANCEL_RESEARCH, 0, 692, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });
        defineSet(119, NO_CONNECTED_SET, new ButtonDefinition[0]);

        defineSet(120, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 287, ButtonCondition.UPGRADE_REQUIREMENT, 17, ButtonAction.UPGRADE, 17, 473, 0),
                createButton(2, 243, ButtonCondition.RESEARCH_REQUIREMENT, 3, ButtonAction.RESEARCH, 3, 327, 0),
                createButton(3, 245, ButtonCondition.RESEARCH_REQUIREMENT, 5, ButtonAction.RESEARCH, 5, 328, 0),
                createButton(4, 380, ButtonCondition.UPGRADE_REQUIREMENT, 54, ButtonAction.UPGRADE, 54, 1282, 1287),
                createButton(9, 236, ButtonCondition.RESEARCHING, 0, ButtonAction.CANCEL_RESEARCH, 0, 692, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });
        defineSet(121, NO_CONNECTED_SET, new ButtonDefinition[0]);

        defineSet(122, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.NOT_GROUNDED, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(1, 288, ButtonCondition.UPGRADE_REQUIREMENT, 7, ButtonAction.UPGRADE, 7, 463, 504),
                createButton(2, 229, ButtonCondition.NOT_GROUNDED, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(2, 292, ButtonCondition.UPGRADE_REQUIREMENT, 0, ButtonAction.UPGRADE, 0, 456, 501),
                createButton(9, 283, ButtonCondition.CAN_LAND, 0, ButtonAction.LAND, 122, 670, 0),
                createButton(9, 282, ButtonCondition.CAN_LIFT_OFF, 0, ButtonAction.LIFT_OFF, 0, 671, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(123, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 289, ButtonCondition.UPGRADE_REQUIREMENT, 8, ButtonAction.UPGRADE, 8, 464, 507),
                createButton(2, 290, ButtonCondition.UPGRADE_REQUIREMENT, 9, ButtonAction.UPGRADE, 9, 465, 513),
                createButton(4, 293, ButtonCondition.UPGRADE_REQUIREMENT, 1, ButtonAction.UPGRADE, 1, 457, 510),
                createButton(5, 291, ButtonCondition.UPGRADE_REQUIREMENT, 2, ButtonAction.UPGRADE, 2, 458, 516),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(124, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(2, 229, ButtonCondition.ANY_ENABLED_CAN_ATTACK, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_ENABLED_CAN_ATTACK, 0, ButtonAction.BUILDING_ATTACK, 0, 666, 0),
                });

        defineSet(125, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(8, 309, ButtonCondition.ANY_CAN_LOAD, 0, ButtonAction.LOAD, 0, 683, 0),
                createButton(9, 312, ButtonCondition.ANY_HAS_LOADED, 0, ButtonAction.UNLOAD_ALL, 0, 684, 0),
                });
        defineSet(126, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(127, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(128, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(129, NO_CONNECTED_SET, new ButtonDefinition[0]);

        defineSet(130, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.NOT_GROUNDED, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(1, 50, ButtonCondition.UNIT_REQUIREMENT, 50, ButtonAction.TRAIN, 50, 585, 0),
                createButton(2, 229, ButtonCondition.NOT_GROUNDED, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(6, 286, ButtonCondition.RALLY_ORDER, 0, ButtonAction.SET_RALLY_POINT, 0, 672, 0),
                createButton(9, 283, ButtonCondition.CAN_LAND, 0, ButtonAction.LAND, 130, 670, 0),
                createButton(9, 282, ButtonCondition.CAN_LIFT_OFF, 0, ButtonAction.LIFT_OFF, 0, 671, 0),
                createButton(9, 236, ButtonCondition.TRAINING_UNIT, 0, ButtonAction.CANCEL_LAST, 254, 693, 0),
                });

        defineSet(131, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 35, ButtonCondition.ALWAYS, 0, ButtonAction.SELECT_LARVA, 0, 674, 0),
                createButton(2, 286, ButtonCondition.RALLY_ORDER, 0, ButtonAction.SET_RALLY_POINT, 0, 672, 0),
                createButton(3, 259, ButtonCondition.RESEARCH_REQUIREMENT, 11, ButtonAction.RESEARCH, 11, 364, 0),
                createButton(7, 132, ButtonCondition.UNIT_REQUIREMENT, 132, ButtonAction.BUILDING_MORPH, 132, 624, 746),
                createButton(9, 236, ButtonCondition.RESEARCHING, 0, ButtonAction.CANCEL_RESEARCH, 0, 692, 0),
                });

        defineSet(132, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 35, ButtonCondition.ALWAYS, 0, ButtonAction.SELECT_LARVA, 0, 674, 0),
                createButton(2, 286, ButtonCondition.RALLY_ORDER, 0, ButtonAction.SET_RALLY_POINT, 0, 672, 0),
                createButton(3, 259, ButtonCondition.RESEARCH_REQUIREMENT, 11, ButtonAction.RESEARCH, 11, 364, 0),
                createButton(4, 261, ButtonCondition.UPGRADE_REQUIREMENT, 24, ButtonAction.UPGRADE, 24, 480, 0),
                createButton(5, 262, ButtonCondition.UPGRADE_REQUIREMENT, 25, ButtonAction.UPGRADE, 25, 481, 0),
                createButton(6, 296, ButtonCondition.UPGRADE_REQUIREMENT, 26, ButtonAction.UPGRADE, 26, 482, 0),
                createButton(7, 133, ButtonCondition.UNIT_REQUIREMENT, 133, ButtonAction.BUILDING_MORPH, 133, 625, 747),
                createButton(9, 236, ButtonCondition.RESEARCHING, 0, ButtonAction.CANCEL_RESEARCH, 0, 692, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(133, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 35, ButtonCondition.ALWAYS, 0, ButtonAction.SELECT_LARVA, 0, 674, 0),
                createButton(2, 286, ButtonCondition.RALLY_ORDER, 0, ButtonAction.SET_RALLY_POINT, 0, 672, 0),
                createButton(3, 259, ButtonCondition.RESEARCH_REQUIREMENT, 11, ButtonAction.RESEARCH, 11, 364, 0),
                createButton(4, 261, ButtonCondition.UPGRADE_REQUIREMENT, 24, ButtonAction.UPGRADE, 24, 480, 0),
                createButton(5, 262, ButtonCondition.UPGRADE_REQUIREMENT, 25, ButtonAction.UPGRADE, 25, 481, 0),
                createButton(6, 296, ButtonCondition.UPGRADE_REQUIREMENT, 26, ButtonAction.UPGRADE, 26, 482, 0),
                createButton(9, 236, ButtonCondition.RESEARCHING, 0, ButtonAction.CANCEL_RESEARCH, 0, 692, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(134, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 134, ButtonCondition.NO_NYDUS_EXIT, 134, ButtonAction.PLACE_NYDUS_EXIT, 134, 629, 0),
                });

        defineSet(135, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 266, ButtonCondition.UPGRADE_REQUIREMENT, 29, ButtonAction.UPGRADE, 29, 485, 0),
                createButton(2, 268, ButtonCondition.UPGRADE_REQUIREMENT, 30, ButtonAction.UPGRADE, 30, 486, 0),
                createButton(4, 103, ButtonCondition.RESEARCH_REQUIREMENT, 32, ButtonAction.RESEARCH, 32, 1253, 1254),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                createButton(9, 236, ButtonCondition.RESEARCHING, 0, ButtonAction.CANCEL_RESEARCH, 0, 692, 0),
                });

        defineSet(136, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 265, ButtonCondition.RESEARCH_REQUIREMENT, 15, ButtonAction.RESEARCH, 15, 369, 0),
                createButton(2, 302, ButtonCondition.RESEARCH_REQUIREMENT, 16, ButtonAction.RESEARCH, 16, 371, 0),
                createButton(3, 295, ButtonCondition.UPGRADE_REQUIREMENT, 32, ButtonAction.UPGRADE, 32, 488, 0),
                createButton(9, 236, ButtonCondition.RESEARCHING, 0, ButtonAction.CANCEL_RESEARCH, 0, 692, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(137, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 301, ButtonCondition.UPGRADE_REQUIREMENT, 12, ButtonAction.UPGRADE, 12, 468, 528),
                createButton(2, 298, ButtonCondition.UPGRADE_REQUIREMENT, 4, ButtonAction.UPGRADE, 4, 460, 531),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(138, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 40, ButtonCondition.RESEARCH_REQUIREMENT, 13, ButtonAction.RESEARCH, 13, 366, 0),
                createButton(2, 267, ButtonCondition.RESEARCH_REQUIREMENT, 17, ButtonAction.RESEARCH, 17, 370, 0),
                createButton(3, 294, ButtonCondition.UPGRADE_REQUIREMENT, 31, ButtonAction.UPGRADE, 31, 487, 0),
                createButton(9, 236, ButtonCondition.RESEARCHING, 0, ButtonAction.CANCEL_RESEARCH, 0, 692, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(139, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 299, ButtonCondition.UPGRADE_REQUIREMENT, 10, ButtonAction.UPGRADE, 10, 466, 519),
                createButton(2, 300, ButtonCondition.UPGRADE_REQUIREMENT, 11, ButtonAction.UPGRADE, 11, 467, 522),
                createButton(3, 297, ButtonCondition.UPGRADE_REQUIREMENT, 3, ButtonAction.UPGRADE, 3, 459, 525),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(140, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 378, ButtonCondition.UPGRADE_REQUIREMENT, 53, ButtonAction.UPGRADE, 53, 1283, 0),
                createButton(2, 379, ButtonCondition.UPGRADE_REQUIREMENT, 52, ButtonAction.UPGRADE, 52, 1284, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(141, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 301, ButtonCondition.UPGRADE_REQUIREMENT, 12, ButtonAction.UPGRADE, 12, 468, 528),
                createButton(2, 298, ButtonCondition.UPGRADE_REQUIREMENT, 4, ButtonAction.UPGRADE, 4, 460, 531),
                createButton(7, 137, ButtonCondition.UNIT_REQUIREMENT, 137, ButtonAction.BUILDING_MORPH, 137, 626, 751),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(142, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 263, ButtonCondition.UPGRADE_REQUIREMENT, 27, ButtonAction.UPGRADE, 27, 483, 0),
                createButton(2, 264, ButtonCondition.UPGRADE_REQUIREMENT, 28, ButtonAction.UPGRADE, 28, 484, 534),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(143, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(7, 144, ButtonCondition.UNIT_REQUIREMENT, 144, ButtonAction.BUILDING_MORPH, 144, 627, 757),
                createButton(8, 146, ButtonCondition.UNIT_REQUIREMENT, 146, ButtonAction.BUILDING_MORPH, 146, 628, 758),
                });
        defineSet(144, NO_CONNECTED_SET, SETS[124]);
        defineSet(145, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(146, NO_CONNECTED_SET, SETS[124]);
        defineSet(147, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(148, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(149, NO_CONNECTED_SET, new ButtonDefinition[0]);
    }

    private static void defineSets3() {
        defineSet(150, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(151, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(152, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(153, NO_CONNECTED_SET, new ButtonDefinition[0]);

        defineSet(154, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 64, ButtonCondition.UNIT_REQUIREMENT, 64, ButtonAction.TRAIN, 64, 599, 0),
                createButton(6, 286, ButtonCondition.RALLY_ORDER, 0, ButtonAction.SET_RALLY_POINT, 0, 672, 0),
                createButton(9, 236, ButtonCondition.TRAINING_UNIT, 0, ButtonAction.CANCEL_LAST, 254, 693, 0),
                });

        defineSet(155, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 69, ButtonCondition.UNIT_REQUIREMENT, 69, ButtonAction.TRAIN, 69, 603, 0),
                createButton(2, 83, ButtonCondition.UNIT_REQUIREMENT, 83, ButtonAction.TRAIN, 83, 608, 723),
                createButton(3, 84, ButtonCondition.UNIT_REQUIREMENT, 84, ButtonAction.TRAIN, 84, 598, 724),
                createButton(6, 286, ButtonCondition.RALLY_ORDER, 0, ButtonAction.SET_RALLY_POINT, 0, 672, 0),
                createButton(9, 236, ButtonCondition.TRAINING_UNIT, 0, ButtonAction.CANCEL_LAST, 254, 693, 0),
                });
        defineSet(156, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(157, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(158, NO_CONNECTED_SET, new ButtonDefinition[0]);

        defineSet(159, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 276, ButtonCondition.UPGRADE_REQUIREMENT, 39, ButtonAction.UPGRADE, 39, 495, 0),
                createButton(2, 317, ButtonCondition.UPGRADE_REQUIREMENT, 38, ButtonAction.UPGRADE, 38, 494, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(160, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 65, ButtonCondition.UNIT_REQUIREMENT, 65, ButtonAction.TRAIN, 65, 600, 0),
                createButton(2, 66, ButtonCondition.UNIT_REQUIREMENT, 66, ButtonAction.TRAIN, 66, 601, 719),
                createButton(3, 67, ButtonCondition.UNIT_REQUIREMENT, 67, ButtonAction.TRAIN, 67, 602, 720),
                createButton(4, 61, ButtonCondition.UNIT_REQUIREMENT, 61, ButtonAction.TRAIN, 61, 1293, 1296),
                createButton(6, 286, ButtonCondition.RALLY_ORDER, 0, ButtonAction.SET_RALLY_POINT, 0, 672, 0),
                createButton(9, 236, ButtonCondition.TRAINING_UNIT, 0, ButtonAction.CANCEL_LAST, 254, 693, 0),
                });
        defineSet(161, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(162, NO_CONNECTED_SET, SETS[124]);

        defineSet(163, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 307, ButtonCondition.UPGRADE_REQUIREMENT, 34, ButtonAction.UPGRADE, 34, 490, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(164, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 306, ButtonCondition.UPGRADE_REQUIREMENT, 14, ButtonAction.UPGRADE, 14, 470, 538),
                createButton(2, 304, ButtonCondition.UPGRADE_REQUIREMENT, 6, ButtonAction.UPGRADE, 6, 462, 544),
                createButton(3, 281, ButtonCondition.UPGRADE_REQUIREMENT, 33, ButtonAction.UPGRADE, 33, 489, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(165, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 275, ButtonCondition.RESEARCH_REQUIREMENT, 19, ButtonAction.RESEARCH, 19, 395, 0),
                createButton(2, 277, ButtonCondition.RESEARCH_REQUIREMENT, 20, ButtonAction.RESEARCH, 20, 396, 0),
                createButton(3, 318, ButtonCondition.UPGRADE_REQUIREMENT, 40, ButtonAction.UPGRADE, 40, 496, 0),
                createButton(4, 371, ButtonCondition.RESEARCH_REQUIREMENT, 27, ButtonAction.RESEARCH, 27, 1261, 0),
                createButton(5, 381, ButtonCondition.RESEARCH_REQUIREMENT, 31, ButtonAction.RESEARCH, 31, 1263, 0),
                createButton(6, 385, ButtonCondition.UPGRADE_REQUIREMENT, 49, ButtonAction.UPGRADE, 49, 1286, 0),
                createButton(9, 236, ButtonCondition.RESEARCHING, 0, ButtonAction.CANCEL_RESEARCH, 0, 692, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(166, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 305, ButtonCondition.UPGRADE_REQUIREMENT, 13, ButtonAction.UPGRADE, 13, 469, 535),
                createButton(2, 303, ButtonCondition.UPGRADE_REQUIREMENT, 5, ButtonAction.UPGRADE, 5, 461, 541),
                createButton(3, 310, ButtonCondition.UPGRADE_REQUIREMENT, 15, ButtonAction.UPGRADE, 15, 471, 547),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(167, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 70, ButtonCondition.UNIT_REQUIREMENT, 70, ButtonAction.TRAIN, 70, 604, 0),
                createButton(2, 72, ButtonCondition.UNIT_REQUIREMENT, 72, ButtonAction.TRAIN, 72, 606, 722),
                createButton(3, 71, ButtonCondition.UNIT_REQUIREMENT, 71, ButtonAction.TRAIN, 71, 605, 721),
                createButton(4, 60, ButtonCondition.UNIT_REQUIREMENT, 60, ButtonAction.TRAIN, 60, 1292, 0),
                createButton(6, 286, ButtonCondition.RALLY_ORDER, 0, ButtonAction.SET_RALLY_POINT, 0, 672, 0),
                createButton(9, 236, ButtonCondition.TRAINING_UNIT, 0, ButtonAction.CANCEL_LAST, 254, 693, 0),
                });
        defineSet(168, NO_CONNECTED_SET, new ButtonDefinition[0]);

        defineSet(169, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 319, ButtonCondition.UPGRADE_REQUIREMENT, 41, ButtonAction.UPGRADE, 41, 497, 0),
                createButton(2, 320, ButtonCondition.UPGRADE_REQUIREMENT, 42, ButtonAction.UPGRADE, 42, 498, 0),
                createButton(3, 321, ButtonCondition.UPGRADE_REQUIREMENT, 43, ButtonAction.UPGRADE, 43, 499, 0),
                createButton(4, 368, ButtonCondition.RESEARCH_REQUIREMENT, 25, ButtonAction.RESEARCH, 25, 1260, 0),
                createButton(5, 383, ButtonCondition.UPGRADE_REQUIREMENT, 47, ButtonAction.UPGRADE, 47, 1285, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                createButton(9, 236, ButtonCondition.RESEARCHING, 0, ButtonAction.CANCEL_RESEARCH, 0, 692, 0),
                });

        defineSet(170, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 280, ButtonCondition.RESEARCH_REQUIREMENT, 21, ButtonAction.RESEARCH, 21, 397, 0),
                createButton(2, 278, ButtonCondition.RESEARCH_REQUIREMENT, 22, ButtonAction.RESEARCH, 22, 398, 0),
                createButton(3, 322, ButtonCondition.UPGRADE_REQUIREMENT, 44, ButtonAction.UPGRADE, 44, 500, 0),
                createButton(9, 236, ButtonCondition.RESEARCHING, 0, ButtonAction.CANCEL_RESEARCH, 0, 692, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(171, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 314, ButtonCondition.UPGRADE_REQUIREMENT, 35, ButtonAction.UPGRADE, 35, 491, 0),
                createButton(2, 315, ButtonCondition.UPGRADE_REQUIREMENT, 36, ButtonAction.UPGRADE, 36, 492, 0),
                createButton(3, 316, ButtonCondition.UPGRADE_REQUIREMENT, 37, ButtonAction.UPGRADE, 37, 493, 0),
                createButton(9, 236, ButtonCondition.UPGRADING, 0, ButtonAction.CANCEL_UPGRADE, 0, 691, 0),
                });

        defineSet(172, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 308, ButtonCondition.NOT_DISABLED, 0, ButtonAction.RECHARGE_SHIELDS, 0, 673, 0),
                });
        defineSet(173, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(174, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(175, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(176, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(177, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(178, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(179, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(180, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(181, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(182, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(183, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(184, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(185, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(186, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(187, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(188, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(189, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(190, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(191, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(192, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(193, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(194, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(195, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(196, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(197, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 283, ButtonCondition.ALWAYS, 0, ButtonAction.PLACE_BEACON, 0, 686, 0),
                });
        defineSet(198, NO_CONNECTED_SET, SETS[197]);
        defineSet(199, NO_CONNECTED_SET, SETS[197]);
    }

    private static void defineSets4() {
        defineSet(200, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(201, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(202, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(203, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(204, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(205, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(206, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(207, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(208, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(209, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(210, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(211, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(212, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(213, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(214, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(215, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(216, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(217, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(218, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(219, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(220, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(221, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(222, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(223, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(224, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(225, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(226, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(227, NO_CONNECTED_SET, new ButtonDefinition[0]);
        defineSet(ButtonSetIds.EMPTY, NO_CONNECTED_SET, new ButtonDefinition[0]);

        defineSet(ButtonSetIds.CANCEL_TARGETING, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(9, 236, ButtonCondition.ALWAYS, 0, ButtonAction.CANCEL_TARGETING, 0, 688, 0),
                });

        defineSet(ButtonSetIds.CANCEL_PLACEMENT, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(9, 236, ButtonCondition.ALWAYS, 0, ButtonAction.CANCEL_PLACEMENT, 0, 688, 0),
                });

        defineSet(ButtonSetIds.CANCEL_CONSTRUCTION, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(9, 236, ButtonCondition.INCOMPLETE, 0, ButtonAction.CANCEL_CONSTRUCTION, 0, 689, 0),
                });

        defineSet(ButtonSetIds.CANCEL_CONSTRUCTION_WITH_RALLY, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(6, 286, ButtonCondition.RALLY_ORDER, 0, ButtonAction.SET_RALLY_POINT, 0, 672, 0),
                createButton(9, 236, ButtonCondition.INCOMPLETE, 0, ButtonAction.CANCEL_CONSTRUCTION, 0, 689, 0),
                });

        defineSet(ButtonSetIds.CANCEL_BUILDING, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(9, 236, ButtonCondition.INCOMPLETE, 0, ButtonAction.CANCEL_CONSTRUCTION, 0, 696, 0),
                });

        defineSet(ButtonSetIds.CANCEL_BUILDING_WITH_RALLY, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(2, 286, ButtonCondition.RALLY_ORDER, 0, ButtonAction.SET_RALLY_POINT, 0, 672, 0),
                createButton(9, 236, ButtonCondition.INCOMPLETE, 0, ButtonAction.CANCEL_CONSTRUCTION, 0, 696, 0),
                });

        defineSet(ButtonSetIds.CANCEL_INFESTATION, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(9, 236, ButtonCondition.INCOMPLETE, 0, ButtonAction.CANCEL_INFESTATION, 0, 697, 0),
                });

        defineSet(ButtonSetIds.CANCEL_MORPH, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 35, ButtonCondition.IS_HATCHERY, 0, ButtonAction.SELECT_LARVA, 0, 674, 0),
                createButton(2, 286, ButtonCondition.HATCHERY_RALLY_ORDER, 0, ButtonAction.SET_RALLY_POINT, 0, 672, 0),
                createButton(9, 236, ButtonCondition.INCOMPLETE, 0, ButtonAction.CANCEL_CONSTRUCTION, 0, 698, 0),
                });

        defineSet(ButtonSetIds.CANCEL_NUCLEAR_STRIKE, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(9, 236, ButtonCondition.ALWAYS, 0, ButtonAction.CANCEL_NUCLEAR_STRIKE, 0, 699, 0),
                });

        defineSet(ButtonSetIds.ZERG_BASIC_STRUCTURES, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 131, ButtonCondition.UNIT_REQUIREMENT, 131, ButtonAction.ZERG_BUILD, 131, 613, 0),
                createButton(2, 143, ButtonCondition.UNIT_REQUIREMENT, 143, ButtonAction.ZERG_BUILD, 143, 614, 0),
                createButton(3, 149, ButtonCondition.UNIT_REQUIREMENT, 149, ButtonAction.ZERG_BUILD, 149, 615, 0),
                createButton(4, 142, ButtonCondition.UNIT_REQUIREMENT, 142, ButtonAction.ZERG_BUILD, 142, 616, 756),
                createButton(5, 139, ButtonCondition.UNIT_REQUIREMENT, 139, ButtonAction.ZERG_BUILD, 139, 617, 753),
                createButton(7, 135, ButtonCondition.UNIT_REQUIREMENT, 135, ButtonAction.ZERG_BUILD, 135, 618, 749),
                createButton(9, 236, ButtonCondition.ALWAYS, 0, ButtonAction.CHANGE_BUTTONS, ButtonSetIds.EMPTY, 688, 0),
                });

        defineSet(ButtonSetIds.TERRAN_BASIC_STRUCTURES, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 106, ButtonCondition.UNIT_REQUIREMENT, 106, ButtonAction.TERRAN_BUILD, 106, 646, 0),
                createButton(2, 109, ButtonCondition.UNIT_REQUIREMENT, 109, ButtonAction.TERRAN_BUILD, 109, 647, 0),
                createButton(3, 110, ButtonCondition.UNIT_REQUIREMENT, 110, ButtonAction.TERRAN_BUILD, 110, 648, 0),
                createButton(4, 111, ButtonCondition.UNIT_REQUIREMENT, 111, ButtonAction.TERRAN_BUILD, 111, 649, 710),
                createButton(5, 122, ButtonCondition.UNIT_REQUIREMENT, 122, ButtonAction.TERRAN_BUILD, 122, 650, 715),
                createButton(6, 124, ButtonCondition.UNIT_REQUIREMENT, 124, ButtonAction.TERRAN_BUILD, 124, 651, 717),
                createButton(7, 112, ButtonCondition.UNIT_REQUIREMENT, 112, ButtonAction.TERRAN_BUILD, 112, 652, 711),
                createButton(8, 125, ButtonCondition.UNIT_REQUIREMENT, 125, ButtonAction.TERRAN_BUILD, 125, 653, 718),
                createButton(9, 236, ButtonCondition.ALWAYS, 0, ButtonAction.CHANGE_BUTTONS, ButtonSetIds.EMPTY, 688, 0),
                });

        defineSet(ButtonSetIds.PROTOSS_BASIC_STRUCTURES, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 154, ButtonCondition.UNIT_REQUIREMENT, 154, ButtonAction.PROTOSS_BUILD, 154, 630, 0),
                createButton(2, 156, ButtonCondition.UNIT_REQUIREMENT, 156, ButtonAction.PROTOSS_BUILD, 156, 631, 0),
                createButton(3, 157, ButtonCondition.UNIT_REQUIREMENT, 157, ButtonAction.PROTOSS_BUILD, 157, 632, 0),
                createButton(4, 160, ButtonCondition.UNIT_REQUIREMENT, 160, ButtonAction.PROTOSS_BUILD, 160, 633, 725),
                createButton(5, 166, ButtonCondition.UNIT_REQUIREMENT, 166, ButtonAction.PROTOSS_BUILD, 166, 634, 726),
                createButton(6, 162, ButtonCondition.UNIT_REQUIREMENT, 162, ButtonAction.PROTOSS_BUILD, 162, 635, 727),
                createButton(7, 164, ButtonCondition.UNIT_REQUIREMENT, 164, ButtonAction.PROTOSS_BUILD, 164, 636, 729),
                createButton(8, 172, ButtonCondition.UNIT_REQUIREMENT, 172, ButtonAction.PROTOSS_BUILD, 172, 637, 728),
                createButton(9, 236, ButtonCondition.ALWAYS, 0, ButtonAction.CHANGE_BUTTONS, ButtonSetIds.EMPTY, 688, 0),
                });

        defineSet(ButtonSetIds.ZERG_ADVANCED_STRUCTURES, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 141, ButtonCondition.UNIT_REQUIREMENT, 141, ButtonAction.ZERG_BUILD, 141, 620, 750),
                createButton(2, 138, ButtonCondition.UNIT_REQUIREMENT, 138, ButtonAction.ZERG_BUILD, 138, 621, 752),
                createButton(3, 134, ButtonCondition.UNIT_REQUIREMENT, 134, ButtonAction.ZERG_BUILD, 134, 619, 748),
                createButton(4, 140, ButtonCondition.UNIT_REQUIREMENT, 140, ButtonAction.ZERG_BUILD, 140, 622, 754),
                createButton(5, 136, ButtonCondition.UNIT_REQUIREMENT, 136, ButtonAction.ZERG_BUILD, 136, 623, 755),
                createButton(9, 236, ButtonCondition.ALWAYS, 0, ButtonAction.CHANGE_BUTTONS, ButtonSetIds.EMPTY, 688, 0),
                });

        defineSet(ButtonSetIds.TERRAN_ADVANCED_STRUCTURES, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 113, ButtonCondition.UNIT_REQUIREMENT, 113, ButtonAction.TERRAN_BUILD, 113, 654, 712),
                createButton(2, 114, ButtonCondition.UNIT_REQUIREMENT, 114, ButtonAction.TERRAN_BUILD, 114, 655, 713),
                createButton(3, 116, ButtonCondition.UNIT_REQUIREMENT, 116, ButtonAction.TERRAN_BUILD, 116, 656, 714),
                createButton(4, 123, ButtonCondition.UNIT_REQUIREMENT, 123, ButtonAction.TERRAN_BUILD, 123, 657, 716),
                createButton(9, 236, ButtonCondition.ALWAYS, 0, ButtonAction.CHANGE_BUTTONS, ButtonSetIds.EMPTY, 688, 0),
                });

        defineSet(ButtonSetIds.PROTOSS_ADVANCED_STRUCTURES, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 155, ButtonCondition.UNIT_REQUIREMENT, 155, ButtonAction.PROTOSS_BUILD, 155, 638, 730),
                createButton(2, 167, ButtonCondition.UNIT_REQUIREMENT, 167, ButtonAction.PROTOSS_BUILD, 167, 642, 731),
                createButton(3, 163, ButtonCondition.UNIT_REQUIREMENT, 163, ButtonAction.PROTOSS_BUILD, 163, 640, 732),
                createButton(4, 171, ButtonCondition.UNIT_REQUIREMENT, 171, ButtonAction.PROTOSS_BUILD, 171, 645, 734),
                createButton(5, 169, ButtonCondition.UNIT_REQUIREMENT, 169, ButtonAction.PROTOSS_BUILD, 169, 643, 735),
                createButton(6, 165, ButtonCondition.UNIT_REQUIREMENT, 165, ButtonAction.PROTOSS_BUILD, 165, 641, 736),
                createButton(7, 159, ButtonCondition.UNIT_REQUIREMENT, 159, ButtonAction.PROTOSS_BUILD, 159, 639, 733),
                createButton(8, 170, ButtonCondition.UNIT_REQUIREMENT, 170, ButtonAction.PROTOSS_BUILD, 170, 644, 737),
                createButton(9, 236, ButtonCondition.ALWAYS, 0, ButtonAction.CHANGE_BUTTONS, ButtonSetIds.EMPTY, 688, 0),
                });

        defineSet(ButtonSetIds.MIXED_UNITS, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ANY_CAN_MOVE, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ANY_HAS_RIGHT_CLICK, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ANY_CAN_MOVE, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ANY_CAN_MOVE, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                });

        defineSet(ButtonSetIds.MIXED_WORKERS, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(5, 231, ButtonCondition.ANY_NOT_CARRYING, 0, ButtonAction.GATHER, 0, 675, 0),
                createButton(6, 233, ButtonCondition.ANY_CARRYING, 0, ButtonAction.RETURN_CARGO, 0, 676, 0),
                });

        defineSet(ButtonSetIds.MIXED_CLOAKABLE_UNITS, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(1, 228, ButtonCondition.ALWAYS, 0, ButtonAction.MOVE, 0, 664, 0),
                createButton(2, 229, ButtonCondition.ALWAYS, 0, ButtonAction.STOP, 0, 665, 0),
                createButton(3, 230, ButtonCondition.ANY_CAN_ATTACK, 0, ButtonAction.ATTACK, 0, 666, 0),
                createButton(4, 254, ButtonCondition.ALWAYS, 0, ButtonAction.PATROL, 0, 667, 0),
                createButton(5, 255, ButtonCondition.ALWAYS, 0, ButtonAction.HOLD_POSITION, 0, 668, 0),
                createButton(7, 252, ButtonCondition.CAN_CLOAK_OWN_TECHNOLOGY, 0, ButtonAction.CLOAK, 0, 344, 0),
                createButton(7, 253, ButtonCondition.CAN_DECLOAK_OWN_TECHNOLOGY, 0, ButtonAction.DECLOAK, 0, 345, 0),
                });
        defineSet(ButtonSetIds.MIXED_BURROWABLE_UNITS, NO_CONNECTED_SET, SETS[37]);

        defineSet(ButtonSetIds.REPLAY_PAUSED, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(7, 386, ButtonCondition.REPLAY_PLAY_PAUSE, 0, ButtonAction.REPLAY_PLAY_PAUSE, 0, 1542, 0),
                createButton(8, 389, ButtonCondition.REPLAY_SLOW_DOWN, 0, ButtonAction.REPLAY_SLOW_DOWN, 0, 1544, 0),
                createButton(9, 388, ButtonCondition.REPLAY_SPEED_UP, 0, ButtonAction.REPLAY_SPEED_UP, 0, 1541, 0),
                });

        defineSet(ButtonSetIds.REPLAY_PLAYING, NO_CONNECTED_SET, new ButtonDefinition[]{
                createButton(7, 387, ButtonCondition.REPLAY_PLAY_PAUSE, 0, ButtonAction.REPLAY_PLAY_PAUSE, 0, 1543, 0),
                createButton(8, 389, ButtonCondition.REPLAY_SLOW_DOWN, 0, ButtonAction.REPLAY_SLOW_DOWN, 0, 1544, 0),
                createButton(9, 388, ButtonCondition.REPLAY_SPEED_UP, 0, ButtonAction.REPLAY_SPEED_UP, 0, 1541, 0),
                });
    }

    private ButtonSetTable() {
    }
}
