package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.scr.game.test.UnitTestUtils;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks raw per-slot writes to the headless local selection array, the removal of units from it and a shown selection.
 */
final class HeadlessLocalSelectionTest {
    private static final int FIRST_SLOT = 3;
    private static final int FIRST_POOL_INDEX = 17;
    private static final int LAST_SLOT = 11;
    private static final int LAST_POOL_INDEX = 1700;
    private static final int EMPTY_SLOT = 0;
    private static final int FIRST_UNIT_POOL_INDEX = 5;
    private static final int SECOND_UNIT_POOL_INDEX = 6;
    private static final int THIRD_UNIT_POOL_INDEX = 7;
    /**
     * The selection of three units and the selection after the second one leaves it.
     */
    private static final int @NotNull [] THREE_UNITS = {5, 6, 7, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    private static final int @NotNull [] AFTER_SECOND_UNIT_REMOVAL = {5, 7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    /**
     * A selection whose first unit fills two slots before an empty slot, and the selection after that unit leaves it: the slots after the empty
     * slot keep their unit.
     */
    private static final int @NotNull [] SLOTS_WITH_GAP = {5, 6, 5, 0, 7, 0, 0, 0, 0, 0, 0, 0};
    private static final int @NotNull [] AFTER_FIRST_UNIT_REMOVAL = {6, 0, 0, 0, 7, 0, 0, 0, 0, 0, 0, 0};
    /**
     * The gap selection after the third unit alone was shown: the slots before the first empty slot become empty, then the shown unit fills the
     * first slot, and the slot after the empty slot keeps its unit.
     */
    private static final int @NotNull [] AFTER_THIRD_UNIT_SHOWN = {7, 0, 0, 0, 7, 0, 0, 0, 0, 0, 0, 0};

    /**
     * Writes every slot of a local selection.
     *
     * @param selection the local selection
     * @param slots     the pool indices of the twelve slots
     */
    private static void writeSlots(@NotNull LocalSelection selection, int @NotNull [] slots) {
        for (int position = 0; position < LocalSelection.CAPACITY; position++) {
            selection.setSelectedPoolIndexRaw(position, slots[position]);
        }
    }

    /**
     * Reads every slot of a local selection.
     *
     * @param selection the local selection
     * @return the pool indices of the twelve slots
     */
    private static int @NotNull [] readSlots(@NotNull LocalSelection selection) {
        int[] slots = new int[LocalSelection.CAPACITY];

        for (int position = 0; position < slots.length; position++) {
            slots[position] = selection.getSelectedPoolIndex(position);
        }

        return slots;
    }

    @Test
    void set_selected_pool_index_raw_should_be_read_back_per_slot() {
        LocalSelection selection = new HeadlessLocalSelection();

        for (int position = 0; position < LocalSelection.CAPACITY; position++) {
            Assertions.assertEquals(EMPTY_SLOT, selection.getSelectedPoolIndex(position));
        }

        selection.setSelectedPoolIndexRaw(FIRST_SLOT, FIRST_POOL_INDEX);
        selection.setSelectedPoolIndexRaw(LAST_SLOT, LAST_POOL_INDEX);

        Assertions.assertEquals(FIRST_POOL_INDEX, selection.getSelectedPoolIndex(FIRST_SLOT));
        Assertions.assertEquals(LAST_POOL_INDEX, selection.getSelectedPoolIndex(LAST_SLOT));

        selection.setSelectedPoolIndexRaw(FIRST_SLOT, EMPTY_SLOT);

        Assertions.assertEquals(EMPTY_SLOT, selection.getSelectedPoolIndex(FIRST_SLOT));
    }

    @Test
    void remove_unit_should_move_later_slots_down_over_the_unit() {
        LocalSelection selection = new HeadlessLocalSelection();

        writeSlots(selection, THREE_UNITS);

        selection.removeUnit(UnitTestUtils.createUnit(SECOND_UNIT_POOL_INDEX));

        Assertions.assertArrayEquals(AFTER_SECOND_UNIT_REMOVAL, readSlots(selection));

        selection.removeUnit(UnitTestUtils.createUnit(SECOND_UNIT_POOL_INDEX));

        Assertions.assertArrayEquals(AFTER_SECOND_UNIT_REMOVAL, readSlots(selection));
    }

    @Test
    void remove_unit_should_clear_every_slot_of_the_unit_before_the_first_empty_slot_only() {
        LocalSelection selection = new HeadlessLocalSelection();

        writeSlots(selection, SLOTS_WITH_GAP);

        selection.removeUnit(UnitTestUtils.createUnit(FIRST_UNIT_POOL_INDEX));

        Assertions.assertArrayEquals(AFTER_FIRST_UNIT_REMOVAL, readSlots(selection));

        selection.removeUnit(UnitTestUtils.createUnit(THIRD_UNIT_POOL_INDEX));

        Assertions.assertArrayEquals(AFTER_FIRST_UNIT_REMOVAL, readSlots(selection));
    }

    @Test
    void show_selection_should_replace_the_slots_as_a_local_select_does() {
        LocalSelection selection = new HeadlessLocalSelection();

        writeSlots(selection, SLOTS_WITH_GAP);

        selection.showSelection(new Unit[]{UnitTestUtils.createUnit(THIRD_UNIT_POOL_INDEX)});

        Assertions.assertArrayEquals(AFTER_THIRD_UNIT_SHOWN, readSlots(selection));

        selection.showSelection(new Unit[]{UnitTestUtils.createUnit(FIRST_UNIT_POOL_INDEX), UnitTestUtils.createUnit(SECOND_UNIT_POOL_INDEX),
                                           UnitTestUtils.createUnit(THIRD_UNIT_POOL_INDEX)});

        Assertions.assertArrayEquals(new int[]{5, 6, 7, 0, 7, 0, 0, 0, 0, 0, 0, 0}, readSlots(selection));
    }
}
