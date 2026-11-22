package org.fogofwarlabs.sc4j.screnhanced.script.input;

/**
 * The command card button of a local input ({@link ScriptInputKind#BUTTON_PRESSED}, {@link ScriptInputKind#BUTTON_TARGETED}) in the values of
 * the lower layer: the set and the button's index in it, its fields with the condition and action ids of {@code core.buttons}, and for a target
 * the map position and the unit id with its generation.
 */
public final class ScriptButtonPress {
    /**
     * The id of no unit, no button index and an unknown function.
     */
    public static final int NONE = -1;

    private final int set;
    private final int button;
    private final int position;

    private final int condition;
    private final int conditionValue;
    private final int action;
    private final int actionValue;

    private final boolean queued;
    private final boolean hotkey;

    private final int x;
    private final int y;
    private final int target;
    private final int targetGeneration;

    /**
     * Creates a {@link ScriptButtonPress} instance.
     *
     * @param set              the shown button set
     * @param button           the button's index in the set, or {@link #NONE}
     * @param position         the button position from {@code 1} through {@code 9}
     * @param condition        the condition id, or {@link #NONE}
     * @param conditionValue   the condition value BYTE
     * @param action           the action id, or {@link #NONE}
     * @param actionValue      the action value WORD
     * @param queued           whether Shift was held
     * @param hotkey           whether a hotkey pressed the button, else a mouse click
     * @param x                the targeted map X in pixels, {@code 0} for a press
     * @param y                the targeted map Y in pixels, {@code 0} for a press
     * @param target           the targeted unit id, or {@link #NONE} for the ground and a press
     * @param targetGeneration the targeted unit's generation, or {@link #NONE}
     */
    public ScriptButtonPress(int set, int button, int position,
                             int condition, int conditionValue,
                             int action, int actionValue,
                             boolean queued, boolean hotkey,
                             int x, int y, int target, int targetGeneration) {
        this.set      = set;
        this.button   = button;
        this.position = position;

        this.condition      = condition;
        this.conditionValue = conditionValue;
        this.action         = action;
        this.actionValue    = actionValue;

        this.queued = queued;
        this.hotkey = hotkey;

        this.x                = x;
        this.y                = y;
        this.target           = target;
        this.targetGeneration = targetGeneration;
    }

    /**
     * @return the shown button set
     */
    public int getSet() {
        return this.set;
    }

    /**
     * @return the button's index in the set, or {@link #NONE}
     */
    public int getButton() {
        return this.button;
    }

    /**
     * @return the button position from {@code 1} through {@code 9}
     */
    public int getPosition() {
        return this.position;
    }

    /**
     * @return the condition id, or {@link #NONE}
     */
    public int getCondition() {
        return this.condition;
    }

    /**
     * @return the condition value BYTE
     */
    public int getConditionValue() {
        return this.conditionValue;
    }

    /**
     * @return the action id, or {@link #NONE}
     */
    public int getAction() {
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
     * @return the targeted map X in pixels, {@code 0} for a press
     */
    public int getX() {
        return this.x;
    }

    /**
     * @return the targeted map Y in pixels, {@code 0} for a press
     */
    public int getY() {
        return this.y;
    }

    /**
     * @return the targeted unit id, or {@link #NONE}
     */
    public int getTarget() {
        return this.target;
    }

    /**
     * @return the targeted unit's generation, or {@link #NONE}
     */
    public int getTargetGeneration() {
        return this.targetGeneration;
    }
}
