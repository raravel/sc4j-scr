package org.fogofwarlabs.sc4j.scr.client.ui.dialog;

import org.fogofwarlabs.sc4j.base.text.TextBoundaryUtils;
import org.fogofwarlabs.sc4j.engine.client.dialog.TextClipboard;
import org.fogofwarlabs.sc4j.engine.client.dialog.TextEditKey;
import org.fogofwarlabs.sc4j.engine.client.dialog.TextEditModel;
import org.fogofwarlabs.sc4j.engine.client.dialog.TextEditOperation;
import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceTimers;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Edits the text of an edit control in the client's look: the engine {@link TextEditModel} holds the text, the limits, the selection, the
 * clipboard operations and the undo/redo history; this class adds the caret glyph, its blinking on the interface timers, the first visible
 * character that the active scroll mode selects and the left edge of the drawn text, which maps a pointer position to an index
 * ({@link #findIndexAt}). Every key and press restarts the caret blink.
 */
public final class EditText {
    /**
     * The caret blink period in milliseconds.
     */
    public static final int CARET_BLINK_MILLISECONDS = 500;
    /**
     * The highlighted caret glyph followed by the normal text color code.
     */
    public static final @NotNull String CARET = "\u0004¦\u0001";
    /**
     * The edit control's caret timer id.
     */
    private static final int CARET_TIMER = 2;

    /**
     * Selects whether scrolling follows the caret or keeps the text end visible.
     */
    public enum ScrollMode {
        /**
         * Resource set {@code 1} (the glue): the first visible character follows the caret.
         */
        CARET,
        /**
         * Resource set {@code 2} (loaded at game start): the end of the text stays visible.
         */
        END
    }

    private final @NotNull TextEditModel model;
    /**
     * The first visible UTF-16 character index.
     */
    private int scroll;
    /**
     * The interface x of the first visible character at the last draw ({@link #setTextLeft}).
     */
    private float textLeft;

    /**
     * The interface timer list used for caret blinking while attached.
     */
    private @Nullable InterfaceTimers timers;
    private boolean caretOn;

    /**
     * Creates an {@link EditText} instance with the caret at the end.
     *
     * @param text          the initial text
     * @param maximumLength the maximum number of code points
     * @param rejected      the refused characters
     */
    public EditText(@NotNull String text, int maximumLength, @NotNull String rejected) {
        this.model    = new TextEditModel(text, maximumLength, rejected);
        this.scroll   = 0;
        this.textLeft = 0;

        this.timers  = null;
        this.caretOn = true;
    }

    /**
     * @return the text
     */
    public @NotNull String getText() {
        return this.model.getText();
    }

    /**
     * @return the caret position
     */
    public int getCaret() {
        return this.model.getCaret();
    }

    /**
     * @return the first visible UTF-16 character index
     */
    public int getScroll() {
        return this.scroll;
    }

    /**
     * @return the first selected character; equal to {@link #getSelectionEnd} without a selection
     */
    public int getSelectionStart() {
        return this.model.getSelectionStart();
    }

    /**
     * @return the index after the last selected character; equal to {@link #getSelectionStart} without a selection
     */
    public int getSelectionEnd() {
        return this.model.getSelectionEnd();
    }

    /**
     * Sets the interface x of the first visible character, where the text was drawn.
     *
     * @param textLeft the left edge of the drawn text in interface pixels
     */
    public void setTextLeft(float textLeft) {
        this.textLeft = textLeft;
    }

    /**
     * Sets the owner's test of the whole text a change would leave; a change it refuses does not happen (typing refuses
     * the character, pasting stops at it).
     *
     * @param acceptance the test, or {@code null} for none
     */
    public void setAcceptance(@Nullable Predicate<String> acceptance) {
        this.model.setAcceptance(acceptance);
    }

    /**
     * Returns visible text, inserting the caret glyph only while focused and the caret blink is on.
     * A caret before the first visible character is omitted.
     *
     * @param focused whether the control has the keyboard focus (only then the caret shows)
     * @return the drawn text
     */
    public @NotNull String getDrawnText(boolean focused) {
        String text = this.model.getText();
        int caret = this.model.getCaret();
        int first = Math.min(this.scroll, text.length());
        String current = text.substring(first);

        if (!focused || !this.caretOn || caret < first) {
            return current;
        }

        return current.substring(0, caret - first) + CARET + current.substring(caret - first);
    }


    /**
     * Finds the index under a pointer position on the drawn line ({@link TextBoundaryUtils#findIndexAt}): from the first visible character
     * at the left edge of the last draw.
     *
     * @param x       the pointer's interface x
     * @param measure the width of a text in the drawn font
     * @return the UTF-16 index of the text
     */
    public int findIndexAt(float x, @NotNull ToDoubleFunction<String> measure) {
        String text = this.model.getText();
        int first = Math.min(this.scroll, text.length());

        return first + TextBoundaryUtils.findIndexAt(text.substring(first), x - this.textLeft, measure);
    }

    /**
     * Updates the first visible character within the supplied text room.
     * CARET mode first brings the caret into view; END mode starts at the text end. Both then walk backward by character advance.
     *
     * @param mode    the scroll mode
     * @param width   the room in interface pixels
     * @param advance the advance of one character
     */
    public void updateScroll(@NotNull ScrollMode mode, float width, @NotNull ToDoubleFunction<String> advance) {
        String text = this.model.getText();
        int caret = this.model.getCaret();

        this.scroll = Math.min(this.scroll, text.length());

        if (mode == ScrollMode.CARET) {
            if (caret < this.scroll) {
                this.scroll = caret;

                return;
            }

            float used = 0;

            for (int index = this.scroll; index < caret; index++) {
                used += (float) advance.applyAsDouble(String.valueOf(text.charAt(index)));
            }

            if (used < width) {
                return;
            }

            this.scroll = caret;
        } else {
            this.scroll = text.length();
        }

        float room = width;

        while (this.scroll > 0) {
            float previous = (float) advance.applyAsDouble(String.valueOf(text.charAt(this.scroll - 1)));

            if (previous >= room) {
                break;
            }

            room -= previous;
            this.scroll--;
        }
    }

    /**
     * Types a character ({@link TextEditModel#type}).
     *
     * @param codePoint the character
     * @param time      the current time in milliseconds
     * @return {@code true} if the text changed, {@code false} otherwise
     */
    public boolean type(int codePoint, long time) {
        this.restartCaret(time);

        return this.model.type(codePoint);
    }

    /**
     * Applies an editing key without extending the selection ({@link #press(TextEditKey, boolean, long)}).
     *
     * @param key  the key
     * @param time the current time in milliseconds
     * @return {@code true} if the text changed, {@code false} otherwise
     */
    public boolean press(@NotNull TextEditKey key, long time) {
        return this.press(key, false, time);
    }

    /**
     * Applies an editing key ({@link TextEditModel#press(TextEditKey, boolean)}); Backspace and Delete remove the selection when there is one,
     * Left and Right end it at its start or end; while extending, the caret keys extend it.
     *
     * @param key    the key
     * @param extend whether the caret keys extend the selection (Shift held)
     * @param time   the current time in milliseconds
     * @return {@code true} if the text changed, {@code false} otherwise
     */
    public boolean press(@NotNull TextEditKey key, boolean extend, long time) {
        this.restartCaret(time);

        return this.model.press(key, extend);
    }

    /**
     * Moves the caret to an index under the pointer ({@link TextEditModel#moveCaret}).
     *
     * @param index  the UTF-16 index
     * @param extend whether the move extends the selection (a drag, or a press with Shift held)
     * @param time   the current time in milliseconds
     */
    public void moveCaret(int index, boolean extend, long time) {
        this.restartCaret(time);
        this.model.moveCaret(index, extend);
    }

    /**
     * Selects the word around an index ({@link TextEditModel#selectWord}).
     *
     * @param index the UTF-16 index
     * @param time  the current time in milliseconds
     */
    public void selectWord(int index, long time) {
        this.restartCaret(time);
        this.model.selectWord(index);
    }

    /**
     * Applies an edit operation of the popup accelerator table (ids {@code 0..5}, {@link TextEditModel#apply}).
     *
     * @param operation the operation
     * @param clipboard the clipboard (the window's system clipboard in the client)
     * @param time      the current time in milliseconds
     * @return {@code true} if the text changed, {@code false} otherwise
     */
    public boolean apply(@NotNull TextEditOperation operation, @NotNull TextClipboard clipboard, long time) {
        this.restartCaret(time);

        return this.model.apply(operation, clipboard);
    }

    /**
     * Runs the caret blink on the client's timer list as the edit control does: timer {@code 2} every {@code 500} ms toggles the caret, and every key
     * restarts it with the caret shown.
     *
     * @param timers the client's interface timers
     * @param now    the interface clock in milliseconds
     */
    public void attach(@NotNull InterfaceTimers timers, long now) {
        if (this.timers == timers) {
            return;
        }

        this.timers = timers;

        this.restartCaret(now);
    }

    /**
     * Kills the caret timer (the control closes).
     */
    public void detach() {
        if (this.timers != null) {
            this.timers.killAll(this);
        }

        this.timers = null;
    }

    private void restartCaret(long now) {
        InterfaceTimers current = this.timers;

        if (current == null) {
            return;
        }

        this.caretOn = true;

        current.arm(this, CARET_TIMER, CARET_BLINK_MILLISECONDS, (int) now, clock -> this.caretOn = !this.caretOn);
    }
}
