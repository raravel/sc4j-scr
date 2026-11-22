package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Exposes the local interface's selection array and the hatching selection operation that the simulation calls. Replay viewers ignore hatching
 * selection calls because the resulting commands are already recorded; a headless game uses {@link HeadlessLocalSelection}.
 */
public interface LocalSelection {
    /**
     * The number of slots of the local selection array.
     */
    int CAPACITY = UnitSelection.MAXIMUM_SELECTED;
    /**
     * The pool index stored in an empty slot.
     */
    int EMPTY_SLOT = 0;

    /**
     * Replaces local selection slots with a new selection as a local select does: the slots before the first empty slot become empty, then the new
     * selection fills the first slots. The slots after the first empty slot that the new selection does not reach keep their units.
     *
     * @param slots     the pool indices of the twelve slots, changed in place
     * @param selection the pool indices of the new selection in slot order; it may be {@code slots} itself
     * @param count     the number of units of the new selection
     */
    static void replaceSlots(int @NotNull [] slots, int @NotNull [] selection, int count) {
        int end = 0;

        while (end < CAPACITY && slots[end] != EMPTY_SLOT) {
            end++;
        }

        System.arraycopy(selection, 0, slots, 0, count);

        if (count < end) {
            Arrays.fill(slots, count, end, EMPTY_SLOT);
        }
    }

    /**
     * Removes a unit from local selection slots as the lifecycle removal does: the units before the first empty slot, except every slot of the unit,
     * are selected again in their order through {@link #replaceSlots}. The slots after the first empty slot keep their units.
     *
     * @param slots     the pool indices of the twelve slots, changed in place
     * @param poolIndex the pool index of the unit
     * @return {@code true} if the unit was in a slot before the first empty slot, {@code false} otherwise
     */
    static boolean removePoolIndex(int @NotNull [] slots, int poolIndex) {
        int end = 0;
        int count = 0;

        // The kept units move down in place; the slots from the last kept unit up to the first empty slot keep their old units until the
        // replacement, so it finds the same first empty slot.
        while (end < CAPACITY && slots[end] != EMPTY_SLOT) {
            if (slots[end] != poolIndex) {
                slots[count++] = slots[end];
            }

            end++;
        }

        replaceSlots(slots, slots, count);

        return count < end;
    }


    /**
     * @param position the slot from {@code 0} through {@code 11}
     * @return the pool index of the unit in the slot, or {@code 0} for an empty slot
     */
    int getSelectedPoolIndex(int position);

    /**
     * Sets one local selection slot directly without a command or sound; the console shows it on the next refresh.
     *
     * @param position  the slot from {@code 0} through {@code 11}
     * @param poolIndex the pool index of the unit to store, or {@code 0} for an empty slot
     */
    void setSelectedPoolIndexRaw(int position, int poolIndex);


    /**
     * Adds the second offspring of a hatched egg to the first free local selection slot when the egg was selected; leaves a full selection unchanged
     * and sends no sound.
     *
     * @param egg    the unit that hatched (the first offspring keeps the egg's unit)
     * @param second the second offspring
     */
    void addHatchedUnit(@NotNull Unit egg, @NotNull Unit second);

    /**
     * Shows units that a game change selected for the local player as the local selection, as a local select shows its selection
     * ({@link #replaceSlots}); nothing is sent and no sound plays.
     * <p>
     * The default implementation replaces the slots through {@link #setSelectedPoolIndexRaw}.
     *
     * @param units the selected units in slot order, at most {@link #CAPACITY}
     */
    default void showSelection(@NotNull Unit @NotNull [] units) {
        int[] slots = new int[CAPACITY];
        int[] selection = new int[units.length];

        for (int position = 0; position < CAPACITY; position++) {
            slots[position] = this.getSelectedPoolIndex(position);
        }

        for (int index = 0; index < units.length; index++) {
            selection[index] = units[index].getIndex();
        }

        replaceSlots(slots, selection, units.length);

        for (int position = 0; position < CAPACITY; position++) {
            this.setSelectedPoolIndexRaw(position, slots[position]);
        }
    }

    /**
     * Removes a retired or hidden unit from the local selection array, as the lifecycle deselection clears it from every selection
     * ({@link UnitSelection#removeUnit}), and a selected unit the local player stops seeing or detecting; nothing is sent.
     * <p>
     * The default implementation keeps the selection unchanged.
     *
     * @param unit the unit
     */
    default void removeUnit(@NotNull Unit unit) {
    }
}
