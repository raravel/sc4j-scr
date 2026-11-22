package org.fogofwarlabs.sc4j.scr.client.ui.command;

import org.fogofwarlabs.sc4j.scr.content.ScrAddedUnitType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Describes one command button of a native button set.
 * <p>
 * The fields follow the 32-byte native button record: position, icon, condition, action, condition value, action value,
 * enabled string and disabled string. String identifiers are one-based {@code stat_txt} indices; zero means none. A button a mod adds
 * ({@link ScrAddedUnitType.TrainButton}) carries its texts instead, and so may a button of a table a game plugin changed: the enabled text in
 * the {@code stat_txt} button string format (hotkey, button kind, text) and the plain disabled text.
 */
public final class ButtonDefinition {
    /**
     * The button kind character of a unit production button string, the second character after the hotkey.
     */
    public static final char UNIT_PRODUCTION_KIND = 1;
    /**
     * The table index of a button that is no entry of a set's table.
     */
    public static final int NO_TABLE_INDEX = -1;

    private final int position;
    private final int icon;

    private final @NotNull ButtonCondition condition;
    private final int conditionValue;

    private final @NotNull ButtonAction action;
    private final int actionValue;

    private final int enabledString;
    private final int disabledString;
    private final ScrAddedUnitType.@Nullable TrainButton addedButton;
    private final @Nullable String enabledText;
    private final @Nullable String disabledText;

    private final int tableIndex;

    /**
     * Creates a {@link ButtonDefinition} instance whose strings are {@code stat_txt} strings.
     *
     * @param position       the command card position from {@code 1} through {@code 9}
     * @param icon           the {@code cmdicons} frame
     * @param condition      the condition function
     * @param conditionValue the value passed to the condition
     * @param action         the action function
     * @param actionValue    the value passed to the action
     * @param enabledString  the one-based {@code stat_txt} string of the enabled button
     * @param disabledString the one-based {@code stat_txt} string of the disabled button, or {@code 0}
     */
    public ButtonDefinition(int position,
                            int icon,
                            @NotNull ButtonCondition condition, int conditionValue,
                            @NotNull ButtonAction action, int actionValue,
                            int enabledString, int disabledString) {
        this(position, icon, condition, conditionValue, action, actionValue, enabledString, disabledString, null, null, null, NO_TABLE_INDEX);
    }

    /**
     * Creates a {@link ButtonDefinition} instance of an entry of a changed table, whose texts replace its {@code stat_txt} strings when given.
     *
     * @param position       the command card position from {@code 1} through {@code 9}
     * @param icon           the {@code cmdicons} frame
     * @param condition      the condition function
     * @param conditionValue the value passed to the condition
     * @param action         the action function
     * @param actionValue    the value passed to the action
     * @param enabledString  the one-based {@code stat_txt} string of the enabled button
     * @param disabledString the one-based {@code stat_txt} string of the disabled button, or {@code 0}
     * @param enabledText    the enabled button string (hotkey, kind, text), or {@code null} for the enabled string
     * @param disabledText   the disabled text, or {@code null} for the disabled string
     * @param tableIndex     the index of the entry in its set's table
     */
    public ButtonDefinition(int position,
                            int icon,
                            @NotNull ButtonCondition condition, int conditionValue,
                            @NotNull ButtonAction action, int actionValue,
                            int enabledString, int disabledString,
                            @Nullable String enabledText, @Nullable String disabledText,
                            int tableIndex) {
        this(position, icon, condition, conditionValue, action, actionValue, enabledString, disabledString, null, enabledText, disabledText,
             tableIndex);
    }

    /**
     * Creates a {@link ButtonDefinition} instance of a button a mod adds, whose strings are its texts.
     *
     * @param position       the command card position from {@code 1} through {@code 9}
     * @param icon           the {@code cmdicons} frame
     * @param condition      the condition function
     * @param conditionValue the value passed to the condition
     * @param action         the action function
     * @param actionValue    the value passed to the action
     * @param addedButton    the added button, whose texts the tooltip shows
     */
    public ButtonDefinition(int position,
                            int icon,
                            @NotNull ButtonCondition condition, int conditionValue,
                            @NotNull ButtonAction action, int actionValue,
                            ScrAddedUnitType.@NotNull TrainButton addedButton) {
        this(position, icon, condition, conditionValue, action, actionValue, 0, 0, addedButton,
             String.valueOf(addedButton.getHotkey()) + UNIT_PRODUCTION_KIND + addedButton.getText(), null, NO_TABLE_INDEX);
    }

    private ButtonDefinition(int position,
                             int icon,
                             @NotNull ButtonCondition condition, int conditionValue,
                             @NotNull ButtonAction action, int actionValue,
                             int enabledString, int disabledString, ScrAddedUnitType.@Nullable TrainButton addedButton,
                             @Nullable String enabledText, @Nullable String disabledText,
                             int tableIndex) {
        this.position = position;
        this.icon     = icon;

        this.condition      = condition;
        this.conditionValue = conditionValue;

        this.action      = action;
        this.actionValue = actionValue;

        this.enabledString  = enabledString;
        this.disabledString = disabledString;
        this.addedButton    = addedButton;
        this.enabledText    = enabledText;
        this.disabledText   = disabledText;

        this.tableIndex = tableIndex;
    }

    /**
     * @return the command card position from {@code 1} through {@code 9}
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
     * @return the condition function
     */
    public @NotNull ButtonCondition getCondition() {
        return this.condition;
    }

    /**
     * @return the value passed to the condition
     */
    public int getConditionValue() {
        return this.conditionValue;
    }

    /**
     * @return the action function
     */
    public @NotNull ButtonAction getAction() {
        return this.action;
    }

    /**
     * @return the value passed to the action
     */
    public int getActionValue() {
        return this.actionValue;
    }

    /**
     * @return the one-based {@code stat_txt} string of the enabled button
     */
    public int getEnabledString() {
        return this.enabledString;
    }

    /**
     * @return the one-based {@code stat_txt} string of the disabled button, or {@code 0}
     */
    public int getDisabledString() {
        return this.disabledString;
    }

    /**
     * @return the button a mod adds, whose texts the button shows, or {@code null} for a native button
     */
    public ScrAddedUnitType.@Nullable TrainButton getAddedButton() {
        return this.addedButton;
    }

    /**
     * Returns the enabled button string in the {@code stat_txt} button string format (hotkey, button kind, text), of the default language for a
     * button a mod adds.
     *
     * @return the enabled button string, or {@code null} if the button uses its enabled {@code stat_txt} string
     */
    public @Nullable String getEnabledText() {
        return this.enabledText;
    }

    /**
     * @return the disabled text of an entry of a changed table, or {@code null} for the disabled {@code stat_txt} string or a mod's button
     */
    public @Nullable String getDisabledText() {
        return this.disabledText;
    }

    /**
     * @return the index of the entry in its set's changed table, {@link #NO_TABLE_INDEX} for a default table button or a mod's button
     */
    public int getTableIndex() {
        return this.tableIndex;
    }
}
