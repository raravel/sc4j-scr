package org.fogofwarlabs.sc4j.scr.client.ui.dialog;

import org.fogofwarlabs.sc4j.engine.client.dialog.TextEditKey;
import org.fogofwarlabs.sc4j.engine.client.host.NoTextInputMethod;
import org.fogofwarlabs.sc4j.engine.client.host.TextComposition;
import org.fogofwarlabs.sc4j.engine.client.host.TextCompositionListener;
import org.fogofwarlabs.sc4j.engine.client.host.TextInputMethod;
import org.fogofwarlabs.sc4j.engine.client.input.InputEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Locale;

/**
 * Checks the input-language mapping of keyboard layout locales, the language marks, the unbound input method and the caret of a composition
 * shown in an edit control.
 */
final class InputMethodTest {

    private static final int NO_LANGUAGE = 0;
    private static final int JAPANESE = 1;
    private static final int KOREAN = 2;
    private static final int TRADITIONAL_CHINESE = 3;
    private static final int OTHER_CHINESE = 4;
    private static final int ENGLISH = 5;
    private static final int POLISH = 6;
    private static final int RUSSIAN = 7;
    private static final int MAXIMUM_LENGTH = 78;
    private static final long TIME = 0;
    private static final int FONT_SLOT = 0;
    private static final float EDIT_RIGHT = 400;
    private static final float EDIT_HEIGHT = 20;
    /**
     * A Hangul initial consonant and the syllable it becomes, as a Korean input method composes them.
     */
    private static final @NotNull String HANGUL_INITIAL = "ㄱ";
    private static final @NotNull String HANGUL_SYLLABLE = "한";
    /**
     * Two kana in one clause, as a Japanese input method composes them.
     */
    private static final @NotNull String KANA = "かな";

    /**
     * Records the composition listener; text input is on.
     */
    private static final class RecordingTextInputMethod implements TextInputMethod {
        private @Nullable TextCompositionListener listener;

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
        }

        @Override
        public void setListener(@Nullable TextCompositionListener listener) {
            this.listener = listener;
        }

        @Override
        public @Nullable Locale getCompositionLocale() {
            return Locale.KOREA;
        }

        @Override
        public void cancelComposition() {
        }
    }

    /**
     * Sends the composition's key presses and characters to an edit text.
     */
    private static final class EditSink implements InputMethod.Sink {
        private final @NotNull EditText edit;

        private EditSink(@NotNull EditText edit) {
            this.edit = edit;
        }

        @Override
        public void onKeyEvent(@NotNull InputEvent event) {
            if (event.getType() != InputEvent.Type.KEY_PRESSED) {
                return;
            }

            switch (event.getKey()) {
                case LEFT -> this.edit.press(TextEditKey.LEFT, TIME);
                case RIGHT -> this.edit.press(TextEditKey.RIGHT, TIME);
                case BACKSPACE -> this.edit.press(TextEditKey.BACKSPACE, TIME);
                default -> {
                }
            }
        }

        @Override
        public void onCharacterTyped(int codePoint) {
            if (!this.edit.type(codePoint, TIME)) {
                InputMethod.rejectInsertion();
            }
        }
    }

    private static @NotNull EditText createEdit(@NotNull String text) {
        EditText edit = new EditText("", MAXIMUM_LENGTH, "");

        text.codePoints().forEach(codePoint -> edit.type(codePoint, TIME));

        return edit;
    }

    private static @NotNull TextCompositionListener install(@NotNull EditText edit) {
        RecordingTextInputMethod method = new RecordingTextInputMethod();

        InputMethod.install(method, new EditSink(edit));

        Assertions.assertNotNull(method.listener);

        return method.listener;
    }

    /**
     * Draws a focused edit control with the shared edit draw.
     *
     * @return the drawn edit text, caret included
     */
    private static @NotNull String drawEditText(@NotNull EditText edit) {
        RecordingDialogPainter painter = new RecordingDialogPainter();

        ControlPainterUtils.drawEditText(painter, FONT_SLOT, edit, EditText.ScrollMode.CARET, 0, 0, EDIT_RIGHT, EDIT_HEIGHT, EDIT_RIGHT, true,
                                         DialogPainter.CODE_TEXT);

        // The language mark is drawn first, the edit text second.
        return painter.getCodeTexts().get(1);
    }

    @Test
    void get_input_language_should_map_keyboard_layout_locales_to_input_languages() {
        Assertions.assertEquals(KOREAN, InputMethod.getInputLanguage(Locale.forLanguageTag("ko-KR")));
        Assertions.assertEquals(JAPANESE, InputMethod.getInputLanguage(Locale.forLanguageTag("ja-JP")));
        Assertions.assertEquals(ENGLISH, InputMethod.getInputLanguage(Locale.forLanguageTag("en-US")));
        Assertions.assertEquals(ENGLISH, InputMethod.getInputLanguage(Locale.forLanguageTag("en-GB")));
        Assertions.assertEquals(POLISH, InputMethod.getInputLanguage(Locale.forLanguageTag("pl-PL")));
        Assertions.assertEquals(RUSSIAN, InputMethod.getInputLanguage(Locale.forLanguageTag("ru-RU")));

        // Chinese: Taiwan (zh-TW) gives 3, the others 4.
        Assertions.assertEquals(TRADITIONAL_CHINESE, InputMethod.getInputLanguage(Locale.forLanguageTag("zh-TW")));
        Assertions.assertEquals(OTHER_CHINESE, InputMethod.getInputLanguage(Locale.forLanguageTag("zh-CN")));
        Assertions.assertEquals(OTHER_CHINESE, InputMethod.getInputLanguage(Locale.forLanguageTag("zh-HK")));

        // Other languages have no input language.
        Assertions.assertEquals(NO_LANGUAGE, InputMethod.getInputLanguage(Locale.forLanguageTag("de-DE")));
    }

    @Test
    void get_input_language_mark_should_map_each_language_to_its_mark() {
        Assertions.assertEquals("| EN", ControlPainterUtils.getInputLanguageMark(NO_LANGUAGE));
        Assertions.assertEquals("| 日本語", ControlPainterUtils.getInputLanguageMark(JAPANESE));
        Assertions.assertEquals("| 가", ControlPainterUtils.getInputLanguageMark(KOREAN));
        Assertions.assertEquals("| 中", ControlPainterUtils.getInputLanguageMark(TRADITIONAL_CHINESE));
        Assertions.assertEquals("| 中", ControlPainterUtils.getInputLanguageMark(OTHER_CHINESE));
        Assertions.assertEquals("| EN", ControlPainterUtils.getInputLanguageMark(ENGLISH));
        Assertions.assertEquals("| PL", ControlPainterUtils.getInputLanguageMark(POLISH));
        Assertions.assertEquals("| RU", ControlPainterUtils.getInputLanguageMark(RUSSIAN));
    }

    @Test
    void composition_without_clauses_should_keep_the_caret_after_its_text() {
        EditText edit = createEdit("ab");
        TextCompositionListener listener = install(edit);

        try {
            listener.onCompositionChanged(new TextComposition(HANGUL_INITIAL, 0, 0, 0));
            listener.onCompositionChanged(new TextComposition(HANGUL_SYLLABLE, 0, 0, 0));

            Assertions.assertEquals("ab" + HANGUL_SYLLABLE, edit.getText());
            Assertions.assertEquals(3, edit.getCaret());
            Assertions.assertEquals("ab" + HANGUL_SYLLABLE + EditText.CARET, drawEditText(edit));
            Assertions.assertNull(InputMethod.getCompositionClause());

            // The committed syllable stays and the next composition follows it.
            listener.onCompositionCommitted(HANGUL_SYLLABLE);
            listener.onCompositionChanged(new TextComposition(HANGUL_INITIAL, 0, 0, 0));

            Assertions.assertEquals("ab" + HANGUL_SYLLABLE + HANGUL_INITIAL, edit.getText());
            Assertions.assertEquals(4, edit.getCaret());
            Assertions.assertEquals("ab" + HANGUL_SYLLABLE + HANGUL_INITIAL + EditText.CARET, drawEditText(edit));
        } finally {
            InputMethod.install(new NoTextInputMethod(), new EditSink(edit));
        }
    }

    @Test
    void composition_with_clauses_should_move_the_caret_to_its_cursor() {
        EditText edit = createEdit("ab");
        TextCompositionListener listener = install(edit);

        try {
            listener.onCompositionChanged(new TextComposition(KANA, 0, KANA.length(), 1));

            Assertions.assertEquals("ab" + KANA, edit.getText());
            Assertions.assertEquals(3, edit.getCaret());
            Assertions.assertEquals("ab" + KANA.charAt(0) + EditText.CARET + KANA.charAt(1), drawEditText(edit));
            Assertions.assertArrayEquals(new int[]{0, KANA.length(), 1}, InputMethod.getCompositionClause());

            // The next update takes the composition back from its end.
            listener.onCompositionCommitted(KANA);

            Assertions.assertEquals("ab" + KANA, edit.getText());
            Assertions.assertEquals(4, edit.getCaret());
            Assertions.assertNull(InputMethod.getCompositionClause());
        } finally {
            InputMethod.install(new NoTextInputMethod(), new EditSink(edit));
        }
    }

    @Test
    void text_input_stays_off_and_language_stays_zero_without_bound_window() {
        InputMethod.setTextInput(true);

        Assertions.assertEquals(NO_LANGUAGE, InputMethod.getInputLanguage());
        Assertions.assertNull(InputMethod.getCompositionClause());

        // A refusal outside a composition update changes nothing.
        InputMethod.rejectInsertion();

        Assertions.assertNull(InputMethod.getCompositionClause());

        InputMethod.setTextInput(false);
    }
}
