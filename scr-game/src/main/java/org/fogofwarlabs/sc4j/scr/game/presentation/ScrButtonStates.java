package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Objects;

/**
 * Holds the local command card state that a game plugin sets: the button states that the script button condition reads by its condition value,
 * and a button set the local command card shows next, as the Change Buttons action shows one.
 * <p>
 * The states are the values of the native button conditions: {@link #ENABLED}, {@link #HIDDEN} and {@link #DISABLED}. Every state starts
 * {@link #HIDDEN}, the native zero value. The state differs from peer to peer and is no simulation state.
 */
public final class ScrButtonStates {
    /**
     * The number of states: one per condition value BYTE.
     */
    public static final int COUNT = 0x100;
    /**
     * The condition result of an enabled button.
     */
    public static final int ENABLED = 1;
    /**
     * The condition result of a hidden button, which leaves the position to the next button of the set.
     */
    public static final int HIDDEN = 0;
    /**
     * The condition result of a disabled, grayed button.
     */
    public static final int DISABLED = -1;
    /**
     * The requested button set while none is requested.
     */
    public static final int NO_REQUEST = -1;

    private final int @NotNull [] states;

    private int requestedSet;

    /**
     * Creates a {@link ScrButtonStates} instance with every state {@link #HIDDEN} and no requested set.
     */
    public ScrButtonStates() {
        this.states = new int[COUNT];

        this.requestedSet = NO_REQUEST;

        Arrays.fill(this.states, HIDDEN);
    }

    /**
     * @param value the condition value from {@code 0} through {@code 255}
     * @return the state of the value: {@link #ENABLED}, {@link #HIDDEN} or {@link #DISABLED}
     * @throws IndexOutOfBoundsException if the value is outside {@code 0} through {@code 255}
     */
    public int getState(int value) {
        return this.states[Objects.checkIndex(value, COUNT)];
    }

    /**
     * Sets the state of a condition value.
     *
     * @param value the condition value from {@code 0} through {@code 255}
     * @param state {@link #ENABLED}, {@link #HIDDEN} or {@link #DISABLED}
     * @throws IndexOutOfBoundsException if the value is outside {@code 0} through {@code 255}
     * @throws IllegalArgumentException  if the state is no condition result
     */
    public void setState(int value, int state) {
        Objects.checkIndex(value, COUNT);

        if (state != ENABLED && state != HIDDEN && state != DISABLED) {
            throw Exceptions.newIllegalArgumentException("The button state {0} is no condition result.", state);
        }

        this.states[value] = state;
    }

    /**
     * Requests the button set the local command card shows next, as the Change Buttons action with the set as its value does.
     *
     * @param set the button set; values of {@code 250} and above show the unit's own set
     */
    public void requestButtonSet(int set) {
        this.requestedSet = set;
    }

    /**
     * Takes the requested button set: the command card shows it at its next refresh.
     *
     * @return the requested set, or {@link #NO_REQUEST}
     */
    public int takeRequestedButtonSet() {
        int set = this.requestedSet;

        this.requestedSet = NO_REQUEST;

        return set;
    }

    /**
     * Sets every state back to {@link #HIDDEN} and forgets the requested set.
     */
    public void clear() {
        Arrays.fill(this.states, HIDDEN);

        this.requestedSet = NO_REQUEST;
    }
}
