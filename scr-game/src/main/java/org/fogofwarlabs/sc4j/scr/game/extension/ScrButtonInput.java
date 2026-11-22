package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.scr.game.presentation.HostButtonSets;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A command card button the local player pressed, or the map point a script target button targeted, that a game plugin sees
 * ({@link ScrGamePlugin#onButtonInput}). The button is named by its button set, its index in the set's table and its fields as the table holds
 * them ({@link HostButtonSets.Button}): the condition and action are function addresses.
 */
public final class ScrButtonInput {
    /**
     * The button index of a button that is no entry of the set's table, as the production buttons of added unit types.
     */
    public static final int NO_INDEX = -1;

    private final int set;
    private final int index;
    private final int position;
    private final int icon;

    private final long condition;
    private final int conditionValue;
    private final long action;
    private final int actionValue;

    private final boolean queued;
    private final boolean hotkey;

    private final boolean targeted;
    private final int mapX;
    private final int mapY;
    private final @Nullable Unit target;

    /**
     * Creates a {@link ScrButtonInput} instance of a button press.
     *
     * @param set            the shown button set
     * @param index          the button's index in the set's table, or {@link #NO_INDEX}
     * @param position       the button position from {@code 1} through {@code 9}
     * @param icon           the {@code cmdicons} frame
     * @param condition      the condition function address
     * @param conditionValue the condition value BYTE
     * @param action         the action function address
     * @param actionValue    the action value WORD
     * @param queued         whether Shift was held
     * @param hotkey         whether a hotkey pressed the button, else a mouse click
     * @return the created {@link ScrButtonInput} instance
     */
    public static @NotNull ScrButtonInput ofPress(int set, int index, int position, int icon,
                                                  long condition, int conditionValue,
                                                  long action, int actionValue,
                                                  boolean queued, boolean hotkey) {
        return new ScrButtonInput(set, index, position, icon, condition, conditionValue, action, actionValue, queued, hotkey, false, 0, 0, null);
    }


    private ScrButtonInput(int set, int index, int position, int icon,
                           long condition, int conditionValue,
                           long action, int actionValue,
                           boolean queued, boolean hotkey,
                           boolean targeted, int mapX, int mapY, @Nullable Unit target) {
        this.set      = set;
        this.index    = index;
        this.position = position;
        this.icon     = icon;

        this.condition      = condition;
        this.conditionValue = conditionValue;
        this.action         = action;
        this.actionValue    = actionValue;

        this.queued = queued;
        this.hotkey = hotkey;

        this.targeted = targeted;
        this.mapX     = mapX;
        this.mapY     = mapY;
        this.target   = target;
    }

    /**
     * @return the shown button set
     */
    public int getSet() {
        return this.set;
    }

    /**
     * @return the button's index in the set's table, or {@link #NO_INDEX}
     */
    public int getIndex() {
        return this.index;
    }

    /**
     * @return the button position from {@code 1} through {@code 9}
     */
    public int getPosition() {
        return this.position;
    }

    /**
     * @return the {@code cmdicons} frame
     */
    public int getIcon() {
        return this.icon;
    }

    /**
     * @return the condition function address
     */
    public long getCondition() {
        return this.condition;
    }

    /**
     * @return the condition value BYTE
     */
    public int getConditionValue() {
        return this.conditionValue;
    }

    /**
     * @return the action function address
     */
    public long getAction() {
        return this.action;
    }

    /**
     * @return the action value WORD
     */
    public int getActionValue() {
        return this.actionValue;
    }

    /**
     * @return {@code true} if Shift was held, {@code false} otherwise
     */
    public boolean isQueued() {
        return this.queued;
    }

    /**
     * @return {@code true} if a hotkey pressed the button, {@code false} for a mouse click
     */
    public boolean isHotkey() {
        return this.hotkey;
    }

    /**
     * @return {@code true} for the map point of a script target button, {@code false} for a button press
     */
    public boolean isTargeted() {
        return this.targeted;
    }

    /**
     * @return the targeted map X in pixels, {@code 0} for a button press
     */
    public int getMapX() {
        return this.mapX;
    }

    /**
     * @return the targeted map Y in pixels, {@code 0} for a button press
     */
    public int getMapY() {
        return this.mapY;
    }

    /**
     * @return the targeted unit, or {@code null} for a ground target and a button press
     */
    public @Nullable Unit getTarget() {
        return this.target;
    }

    /**
     * Creates the input of a map point that the button of this press targets.
     *
     * @param mapX   the targeted map X in pixels
     * @param mapY   the targeted map Y in pixels
     * @param target the targeted unit, or {@code null} for the ground
     * @param queued whether Shift was held at the targeting click
     * @return the created {@link ScrButtonInput} instance
     */
    public @NotNull ScrButtonInput createTargeted(int mapX, int mapY, @Nullable Unit target, boolean queued) {
        return new ScrButtonInput(this.set, this.index, this.position, this.icon, this.condition, this.conditionValue, this.action,
                                  this.actionValue, queued, this.hotkey, true, mapX, mapY, target);
    }
}
