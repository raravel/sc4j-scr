package org.fogofwarlabs.sc4j.scr.client.ui.dialog;

import org.fogofwarlabs.sc4j.engine.client.dialog.TextEditKey;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.function.ToDoubleFunction;

/**
 * Checks the first visible character of the caret-following and end-following scroll modes and the drawn caret.
 */
final class EditTextTest {
    private static final int TYPED_MAXIMUM_LENGTH = 78;
    private static final long TIME = 0;
    /**
     * The fixed advance of every character in interface pixels.
     */
    private static final float CHARACTER_ADVANCE = 10;
    /**
     * Ten pixels per character.
     */
    private static final @NotNull ToDoubleFunction<String> ADVANCE = character -> CHARACTER_ADVANCE;
    /**
     * The width of a text at ten pixels per character.
     */
    private static final @NotNull ToDoubleFunction<String> MEASURE = text -> text.length() * CHARACTER_ADVANCE;
    /**
     * The interface x where the visible text was drawn.
     */
    private static final float TEXT_LEFT = 100;
    /**
     * The text room in interface pixels: four and a half characters.
     */
    private static final float ROOM = 45;
    /**
     * The highlighted caret glyph followed by the normal text color code.
     */
    private static final @NotNull String CARET = "\u0004¦\u0001";

    /**
     * Types the supplied characters into an empty fixture text input.
     *
     * @param text the characters to type
     * @return the populated text input
     */
    private static @NotNull EditText createTypedEditText(@NotNull String text) {
        EditText edit = new EditText("", TYPED_MAXIMUM_LENGTH, "");

        for (char character : text.toCharArray()) {
            edit.type(character, TIME);
        }

        return edit;
    }

    @Test
    void end_mode_keeps_end_of_text_visible() {
        EditText edit = createTypedEditText("abcdefghij");

        // Room 45: going back from the end takes characters while their advance is below the room left: 45, 35, 25, 15, 5.
        edit.updateScroll(EditText.ScrollMode.END, ROOM, ADVANCE);

        Assertions.assertEquals(6, edit.getScroll());
        Assertions.assertEquals("ghij", edit.getDrawnText(false));
        Assertions.assertEquals("ghij" + CARET, edit.getDrawnText(true));
    }

    @Test
    void end_mode_shows_short_text_from_its_start() {
        EditText edit = createTypedEditText("abc");

        edit.updateScroll(EditText.ScrollMode.END, ROOM, ADVANCE);

        Assertions.assertEquals(0, edit.getScroll());
        Assertions.assertEquals("abc", edit.getDrawnText(false));
    }

    @Test
    void caret_mode_scrolls_only_when_text_before_caret_fills_room() {
        EditText edit = createTypedEditText("abcd");

        // 40 < 45: no change.
        edit.updateScroll(EditText.ScrollMode.CARET, ROOM, ADVANCE);

        Assertions.assertEquals(0, edit.getScroll());

        edit.type('e', TIME);
        // 50 >= 45: the caret (5) becomes the first visible character, then four characters come back (45, 35, 25, 15, 5).
        edit.updateScroll(EditText.ScrollMode.CARET, ROOM, ADVANCE);

        Assertions.assertEquals(1, edit.getScroll());

        // A caret before the first visible character becomes it.
        edit.press(TextEditKey.HOME, TIME);
        edit.updateScroll(EditText.ScrollMode.CARET, ROOM, ADVANCE);

        Assertions.assertEquals(0, edit.getScroll());
    }

    @Test
    void get_drawn_text_should_omit_caret_before_first_visible_character() {
        EditText edit = createTypedEditText("abcdefghij");

        edit.updateScroll(EditText.ScrollMode.END, ROOM, ADVANCE);
        edit.press(TextEditKey.HOME, TIME);

        Assertions.assertEquals("ghij", edit.getDrawnText(true));
    }

    @Test
    void find_index_at_should_map_the_pointer_from_the_first_visible_character() {
        EditText edit = createTypedEditText("abcdefghij");

        // The first visible character is g (6), drawn at x 100.
        edit.updateScroll(EditText.ScrollMode.END, ROOM, ADVANCE);
        edit.setTextLeft(TEXT_LEFT);

        Assertions.assertEquals(6, edit.findIndexAt(TEXT_LEFT - CHARACTER_ADVANCE, MEASURE));
        Assertions.assertEquals(7, edit.findIndexAt(TEXT_LEFT + 14, MEASURE));
        Assertions.assertEquals(8, edit.findIndexAt(TEXT_LEFT + 15, MEASURE));
        Assertions.assertEquals(10, edit.findIndexAt(TEXT_LEFT + ROOM * 2, MEASURE));
    }

    @Test
    void pointer_press_and_drag_select_text() {
        EditText edit = createTypedEditText("hello world");

        edit.setTextLeft(TEXT_LEFT);
        // A press at the second l, a drag past the o of world.
        edit.moveCaret(edit.findIndexAt(TEXT_LEFT + 31, MEASURE), false, TIME);
        edit.moveCaret(edit.findIndexAt(TEXT_LEFT + 78, MEASURE), true, TIME);

        Assertions.assertEquals(3, edit.getSelectionStart());
        Assertions.assertEquals(8, edit.getSelectionEnd());

        // A Shift press extends from the press.
        edit.moveCaret(edit.findIndexAt(TEXT_LEFT, MEASURE), true, TIME);

        Assertions.assertEquals(0, edit.getSelectionStart());
        Assertions.assertEquals(3, edit.getSelectionEnd());

        edit.selectWord(edit.findIndexAt(TEXT_LEFT + 72, MEASURE), TIME);

        Assertions.assertEquals(6, edit.getSelectionStart());
        Assertions.assertEquals(11, edit.getSelectionEnd());
    }

    @Test
    void press_should_extend_the_selection_with_shift() {
        EditText edit = createTypedEditText("gg wp");

        edit.press(TextEditKey.LEFT, true, TIME);
        edit.press(TextEditKey.LEFT, true, TIME);

        Assertions.assertEquals(3, edit.getSelectionStart());
        Assertions.assertEquals(5, edit.getSelectionEnd());
        Assertions.assertTrue(edit.type('x', TIME));
        Assertions.assertEquals("gg x", edit.getText());
    }
}
