package org.fogofwarlabs.sc4j.scr.client.ui.dialog;

import org.fogofwarlabs.sc4j.engine.client.host.TextComposition;
import org.fogofwarlabs.sc4j.engine.client.host.TextCompositionListener;
import org.fogofwarlabs.sc4j.engine.client.host.TextInputMethod;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.fogofwarlabs.sc4j.engine.client.input.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Shows the window's input-method composition ({@link TextInputMethod}) in the focused edit control the way the game does: each composition
 * update takes back the previous composition with caret movement and deletion keys and types the new text through the input sink; a
 * composition with clauses then moves the caret back to its cursor, one without clauses leaves the caret after its text. A refused character
 * rolls the update back and cancels the composition. It also names the input language of the edit control's language mark. Without a bound
 * input method text input stays off and the input language is {@code 0}.
 */
public final class InputMethod implements TextCompositionListener {
    private static final int INPUT_JAPANESE = 1;
    private static final int INPUT_KOREAN = 2;
    private static final int INPUT_CHINESE_TRADITIONAL = 3;
    private static final int INPUT_CHINESE_OTHER = 4;
    private static final int INPUT_ENGLISH = 5;
    private static final int INPUT_POLISH = 6;
    private static final int INPUT_RUSSIAN = 7;
    /**
     * The country of traditional Chinese input ({@code zh-TW}).
     */
    private static final @NotNull String TRADITIONAL_CHINESE_COUNTRY = "TW";

    private static @Nullable InputMethod current;

    /**
     * Receives the input events the composition sends, in the window input's terms.
     */
    public interface Sink {
        /**
         * Sends a keyboard event.
         *
         * @param event the key press or release
         */
        void onKeyEvent(@NotNull InputEvent event);

        /**
         * Sends a typed code point.
         *
         * @param codePoint the typed code point
         */
        void onCharacterTyped(int codePoint);
    }

    /**
     * Binds the input method of the client window; a window without platform input method binds one that never takes text input.
     *
     * @param method the window's input method
     * @param sink   where the composition's input events go (the window's key and character paths)
     */
    public static void install(@NotNull TextInputMethod method, @NotNull Sink sink) {
        InputMethod binding = new InputMethod(method, sink);

        method.setListener(binding);
        current = binding;
    }

    /**
     * Sets whether text input is on: the input method is attached to the window while it is enabled. Disabling text input resets the composition
     * state after the input method detached.
     *
     * @param enabled whether an edit control takes text
     */
    public static void setTextInput(boolean enabled) {
        InputMethod binding = current;

        if (binding == null || binding.method.isEnabled() == enabled) {
            return;
        }

        binding.method.setEnabled(enabled);

        if (binding.method.isEnabled() != enabled) {
            return;
        }

        if (!enabled) {
            binding.resetComposition();
        }
    }

    /**
     * Returns the input-language code of the window's keyboard layout while text input is on and the input method converts to the native characters,
     * with {@code 0} otherwise.
     *
     * @return the input-language code: {@code 0} (off, not native conversion, or another language), {@code 1} Japanese, {@code 2} Korean, {@code 3} /
     * {@code 4} Chinese, {@code 5} English, {@code 6} Polish, {@code 7} Russian
     */
    public static int getInputLanguage() {
        InputMethod binding = current;

        if (binding == null || !binding.method.isEnabled()) {
            return 0;
        }

        Locale locale = binding.method.getCompositionLocale();

        return locale == null ? 0 : getInputLanguage(locale);
    }

    /**
     * Returns the input-language code of a keyboard layout's locale, with {@code 0} for an unsupported language.
     *
     * @param locale the keyboard layout's locale
     * @return the input language
     */
    static int getInputLanguage(@NotNull Locale locale) {
        return switch (locale.getLanguage()) {
            case "zh" -> TRADITIONAL_CHINESE_COUNTRY.equals(locale.getCountry()) ? INPUT_CHINESE_TRADITIONAL : INPUT_CHINESE_OTHER;
            case "en" -> INPUT_ENGLISH;
            case "ja" -> INPUT_JAPANESE;
            case "ko" -> INPUT_KOREAN;
            case "pl" -> INPUT_POLISH;
            case "ru" -> INPUT_RUSSIAN;
            default -> 0;
        };
    }

    /**
     * Records a refused character while a composition update is typing through the input sink.
     */
    public static void rejectInsertion() {
        InputMethod binding = current;

        if (binding != null && binding.inserting) {
            binding.rejected = true;
        }
    }

    /**
     * @return the target clause of the shown composition as {start, end, cursor} characters from its start, or {@code null} without a composition
     * (for the edit draw)
     */
    public static int @Nullable [] getCompositionClause() {
        InputMethod binding = current;

        if (binding == null || !binding.composing || binding.clauseEnd <= binding.clauseStart) {
            return null;
        }

        return new int[]{binding.clauseStart, binding.clauseEnd, binding.clauseCursor};
    }

    private final @NotNull TextInputMethod method;
    private final @NotNull Sink sink;

    /**
     * The shown composition length to delete during the next update.
     */
    private int compositionLength;
    private boolean composing;
    /**
     * How far the caret was moved left last time.
     */
    private int caretMovedLeft;
    /**
     * Whether the characters of an update are being typed.
     */
    private boolean inserting;
    /**
     * Whether the focused edit refused a character during the current insertion.
     */
    private boolean rejected;
    /**
     * The target clause and the cursor within the shown composition, in characters.
     */
    private int clauseStart;
    private int clauseEnd;
    private int clauseCursor;

    /**
     * Creates an {@link InputMethod} instance without composition.
     *
     * @param method the window's input method
     * @param sink   where the composition's input events go
     */
    private InputMethod(@NotNull TextInputMethod method, @NotNull Sink sink) {
        this.method = method;
        this.sink   = sink;

        this.compositionLength = 0;
        this.composing         = false;
        this.caretMovedLeft    = 0;
        this.inserting         = false;
        this.rejected          = false;
        this.clauseStart       = 0;
        this.clauseEnd         = 0;
        this.clauseCursor      = 0;
    }


    /**
     * Handles composed text that is final: the edit control takes it as typed text.
     *
     * @param text the result text, possibly empty
     */
    @Override
    public void onCompositionCommitted(@NotNull String text) {
        try {
            this.update(text, null);
        } catch (RuntimeException exception) {
            this.resetComposition();
        }
    }

    /**
     * Handles a changed composition: the edit control shows it in place of the previous one.
     *
     * @param composition the composition; an empty text ends it
     */
    @Override
    public void onCompositionChanged(@NotNull TextComposition composition) {
        try {
            this.update(composition.getText(), composition);
        } catch (RuntimeException exception) {
            this.resetComposition();
        }
    }

    /**
     * Handles a composition the platform could not report: the edit control forgets the composition it shows.
     */
    @Override
    public void onCompositionCanceled() {
        this.resetComposition();
    }

    private void resetComposition() {
        this.composing         = false;
        this.compositionLength = 0;
        this.caretMovedLeft    = 0;
        this.clauseStart       = 0;
        this.clauseEnd         = 0;
        this.clauseCursor      = 0;
    }

    /**
     * Removes the previous composition and types the new text through the input sink. A rejected insertion rolls back typed characters and cancels
     * the composition; an active composition with clauses retains its target clause and moves the caret to its cursor.
     *
     * @param text        the result or the composition text
     * @param composition the composition, or {@code null} for a result
     */
    private void update(@NotNull String text, @Nullable TextComposition composition) {
        for (int step = 0; step < this.caretMovedLeft; step++) {
            this.pressKey(Key.RIGHT);
        }

        this.caretMovedLeft = 0;

        if (this.composing) {
            for (int step = 0; step < this.compositionLength; step++) {
                this.pressKey(Key.BACKSPACE);
            }
        }

        this.composing         = false;
        this.compositionLength = 0;
        this.rejected          = false;
        this.inserting         = true;

        int typed = 0;

        for (int index = 0; index < text.length(); index = text.offsetByCodePoints(index, 1)) {
            this.sink.onCharacterTyped(text.codePointAt(index));

            if (this.rejected) {
                break;
            }

            typed++;
        }

        this.inserting = false;

        int length = text.length();

        this.clauseStart  = 0;
        this.clauseEnd    = 0;
        this.clauseCursor = length;

        if (this.rejected) {
            // Rejected typed characters are taken back and the composition is canceled.
            for (int step = 0; step < typed; step++) {
                this.pressKey(Key.BACKSPACE);
            }

            this.method.cancelComposition();

            return;
        }

        if (composition != null && length > 0) {
            // A composition without clauses keeps the caret after its text.
            if (composition.getClauseEnd() > composition.getClauseStart()) {
                this.clauseStart  = composition.getClauseStart();
                this.clauseEnd    = composition.getClauseEnd();
                this.clauseCursor = composition.getCursor();

                for (int step = this.clauseCursor; step < length; step++) {
                    this.pressKey(Key.LEFT);

                    this.caretMovedLeft++;
                }
            }

            this.composing         = true;
            this.compositionLength = length;
        }
    }

    private void pressKey(@NotNull Key key) {
        this.sink.onKeyEvent(InputEvent.createKey(InputEvent.Type.KEY_PRESSED, key, 0));
        this.sink.onKeyEvent(InputEvent.createKey(InputEvent.Type.KEY_RELEASED, key, 0));
    }
}
