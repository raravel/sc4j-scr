package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

/**
 * Retains the game's button set overrides installed by EUD emulation or by a game plugin. An aborted commit or the end of the game restores the
 * original default sets. Button conditions and actions retain function addresses. Every replacement or restoration counts a new revision
 * ({@link #getRevision()}), so that readers that keep converted arrays know when to convert again.
 */
public final class HostButtonSets {
    /**
     * The number of button sets.
     */
    public static final int SET_COUNT = 250;

    /**
     * The mask of a WORD value.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The mask of a BYTE value.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * Holds a host button's placement, condition, action and tooltip strings. A button may carry its own texts instead of its {@code stat_txt}
     * strings: the enabled text in the {@code stat_txt} button string format (hotkey character, button kind character, then the text) and the
     * disabled text as plain text; both may hold line breaks and the text color control characters.
     */
    public static final class Button {
        private final int position;
        private final int icon;
        private final long condition;
        private final int conditionValue;
        private final long action;
        private final int actionValue;
        private final int enabledString;
        private final int disabledString;
        private final @Nullable String enabledText;
        private final @Nullable String disabledText;

        /**
         * Creates a {@link Button} instance with truncated WORD and BYTE values and the {@code stat_txt} strings.
         *
         * @param position       the position WORD
         * @param icon           the icon WORD
         * @param condition      the condition function address, {@code 0} for none
         * @param conditionValue the condition value BYTE
         * @param action         the action function address, {@code 0} for none
         * @param actionValue    the action value WORD
         * @param enabledString  the enabled string WORD
         * @param disabledString the disabled string WORD
         */
        public Button(int position, int icon,
                      long condition, int conditionValue,
                      long action, int actionValue,
                      int enabledString, int disabledString) {
            this(position, icon, condition, conditionValue, action, actionValue, enabledString, disabledString, null, null);
        }

        /**
         * Creates a {@link Button} instance with truncated WORD and BYTE values and texts that replace its {@code stat_txt} strings.
         *
         * @param position       the position WORD
         * @param icon           the icon WORD
         * @param condition      the condition function address, {@code 0} for none
         * @param conditionValue the condition value BYTE
         * @param action         the action function address, {@code 0} for none
         * @param actionValue    the action value WORD
         * @param enabledString  the enabled string WORD
         * @param disabledString the disabled string WORD
         * @param enabledText    the enabled button string (hotkey, kind, text), or {@code null} for the enabled string
         * @param disabledText   the disabled text, or {@code null} for the disabled string
         */
        public Button(int position, int icon,
                      long condition, int conditionValue,
                      long action, int actionValue,
                      int enabledString, int disabledString,
                      @Nullable String enabledText, @Nullable String disabledText) {
            this.position       = position & WORD_MASK;
            this.icon           = icon & WORD_MASK;
            this.condition      = condition;
            this.conditionValue = conditionValue & BYTE_MASK;
            this.action         = action;
            this.actionValue    = actionValue & WORD_MASK;
            this.enabledString  = enabledString & WORD_MASK;
            this.disabledString = disabledString & WORD_MASK;
            this.enabledText    = enabledText;
            this.disabledText   = disabledText;
        }

        /**
         * @return the position WORD
         */
        public int getPosition() {
            return this.position;
        }

        /**
         * @return the icon WORD
         */
        public int getIcon() {
            return this.icon;
        }

        /**
         * @return the condition function address, {@code 0} for none
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
         * @return the action function address, {@code 0} for none
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
         * @return the enabled string WORD
         */
        public int getEnabledString() {
            return this.enabledString;
        }

        /**
         * @return the disabled string WORD
         */
        public int getDisabledString() {
            return this.disabledString;
        }

        /**
         * @return the enabled button string (hotkey, kind, text) that replaces the enabled string, or {@code null} for the enabled string
         */
        public @Nullable String getEnabledText() {
            return this.enabledText;
        }

        /**
         * @return the disabled text that replaces the disabled string, or {@code null} for the disabled string
         */
        public @Nullable String getDisabledText() {
            return this.disabledText;
        }
    }

    private final int @NotNull [] counts;
    private final @NotNull Button @NotNull [] @Nullable [] buttons;
    private final int @NotNull [] connected;

    private boolean replaced;
    private int revision;

    /**
     * Creates a {@link HostButtonSets} instance with empty override storage.
     */
    public HostButtonSets() {
        this.counts    = new int[SET_COUNT];
        this.buttons   = new Button[SET_COUNT][];
        this.connected = new int[SET_COUNT];

        this.replaced = false;
        this.revision = 0;
    }

    /**
     * @return {@code true} if a commit replaced the default sets, {@code false} otherwise
     */
    public boolean isReplaced() {
        return this.replaced;
    }

    /**
     * @return the number of replacements and restorations so far, which changes with every change of the table
     */
    public int getRevision() {
        return this.revision;
    }

    /**
     * @param set the button set from {@code 0} through {@code 249}
     * @return the button count WORD of a replaced table
     * @throws IndexOutOfBoundsException if {@code set} is outside {@code 0} through {@code 249}
     */
    public int getCount(int set) {
        return this.counts[Objects.checkIndex(set, SET_COUNT)];
    }

    /**
     * @param set the button set from {@code 0} through {@code 249}
     * @return the button array of a replaced table (its length is the classic DWORD count), or {@code null} for none
     * @throws IndexOutOfBoundsException if {@code set} is outside {@code 0} through {@code 249}
     */
    public @NotNull Button @Nullable [] getButtons(int set) {
        return this.buttons[Objects.checkIndex(set, SET_COUNT)];
    }

    /**
     * @param set the button set from {@code 0} through {@code 249}
     * @return the connected set WORD of a replaced table
     * @throws IndexOutOfBoundsException if {@code set} is outside {@code 0} through {@code 249}
     */
    public int getConnectedSet(int set) {
        return this.connected[Objects.checkIndex(set, SET_COUNT)];
    }


    /**
     * Restores the default sets copied when emulation started.
     */
    public void restoreDefaults() {
        this.replaced = false;
        this.revision++;

        Arrays.fill(this.counts, 0);
        Arrays.fill(this.buttons, null);
        Arrays.fill(this.connected, 0);
    }

    /**
     * Replaces one entry; the table counts as replaced from then on.
     *
     * @param set       the button set from {@code 0} through {@code 249}
     * @param count     the button count WORD
     * @param buttons   the button array, or {@code null} for none
     * @param connected the connected set WORD
     * @throws IndexOutOfBoundsException if {@code set} is outside {@code 0} through {@code 249}
     */
    public void replace(int set, int count, @NotNull Button @Nullable [] buttons, int connected) {
        Objects.checkIndex(set, SET_COUNT);

        this.counts[set]    = count & WORD_MASK;
        this.buttons[set]   = buttons;
        this.connected[set] = connected & WORD_MASK;

        this.replaced = true;
        this.revision++;
    }
}
