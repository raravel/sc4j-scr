package org.fogofwarlabs.sc4j.scr.render.ui;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Checks the alignment, wrapping and end-of-text rules of {@link InterfaceText} and the color it carries across lines.
 */
final class InterfaceTextTest {
    /**
     * The first printable character and the advance of every printable character.
     */
    private static final char FIRST_PRINTABLE = 0x20;
    private static final int CHARACTER_WIDTH = 10;
    /**
     * The message area row: the pen and the inclusive right edge {@code W - 10} of the {@code 640}-wide interface.
     */
    private static final int MESSAGE_ROW_PEN = 10;
    private static final int MESSAGE_ROW_RIGHT = 630;
    /**
     * The inclusive right edge of the wrapping cases.
     */
    private static final int WRAP_RIGHT = 55;
    /**
     * The carriage returns after the line, more than a message line holds in bytes.
     */
    private static final int PADDING_LENGTH = 300;
    /**
     * Ten pixels per printable character; control codes advance nothing.
     */
    private static final @NotNull ToDoubleFunction<String> MEASURE = text -> {
        int width = 0;

        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) >= FIRST_PRINTABLE) {
                width += CHARACTER_WIDTH;
            }
        }

        return width;
    };
    /**
     * The pen color a text starts in: the game's code 2.
     */
    private static final int START_COLOR = InterfaceFont.getCodeColor(2);

    /**
     * Finds the pen color of a line's first glyph as a font draws it: each code changes the pen through the game's code colors, and the color
     * before a code becomes the one code {@code 1} switches back to.
     *
     * @param line the drawn line
     * @return the packed {@code 0xRRGGBBAA} color
     */
    private static int findStartColor(@NotNull String line) {
        InterfaceCodeColors colors = new InterfaceCodeColors(InterfaceText.GAME_CODE_COLORS);
        int color = START_COLOR;
        int previous = START_COLOR;

        for (int index = 0; index < line.length() && colors.isColorCode(line.charAt(index)); index++) {
            int next = colors.getColor(line.charAt(index), START_COLOR, color, previous);

            previous = color;
            color    = next;
        }

        return color;
    }

    @Test
    void compute_alignment_shifts_should_be_empty_without_alignment_codes() {
        Assertions.assertArrayEquals(new int[0], InterfaceText.computeAlignmentShifts("\u0004plain", MESSAGE_ROW_PEN, MESSAGE_ROW_RIGHT, MEASURE));
    }

    @Test
    void compute_alignment_shifts_should_center_the_rest_between_pen_and_right() {
        // The message area row: pen 10, inclusive right W - 10 = 630; "\x13\x07" then 50 px of text:
        // shift = (630 - (int) (10 + 50)) >> 1 = 285, so the text spans 295 .. 345 around the row center 320.
        Assertions.assertArrayEquals(new int[]{285},
                                     InterfaceText.computeAlignmentShifts("\u0013\u000712345", MESSAGE_ROW_PEN, MESSAGE_ROW_RIGHT, MEASURE));
    }

    @Test
    void compute_alignment_shifts_should_end_the_rest_at_the_inclusive_right_for_the_right_code() {
        // "ab" stays at the pen, "\x12" moves the rest "cd" to end at 630: shift = 630 - (int) (10 + 20 + 20) = 580.
        Assertions.assertArrayEquals(new int[]{580}, InterfaceText.computeAlignmentShifts("ab\u0012cd", MESSAGE_ROW_PEN, MESSAGE_ROW_RIGHT, MEASURE));
    }

    @Test
    void compute_alignment_shifts_should_measure_a_later_code_from_the_moved_pen() {
        // First "\x13": rest 40 px, shift = (100 - 40) >> 1 = 30, pen 30; then "ab" moves the pen to 50 and "\x12" puts
        // "cd" at the right: 100 - (int) (50 + 20) = 30.
        Assertions.assertArrayEquals(new int[]{30, 30}, InterfaceText.computeAlignmentShifts("\u0013ab\u0012cd", 0, 100, MEASURE));
    }

    @Test
    void compute_alignment_shifts_should_truncate_fractional_pens() {
        // (int) 100 - (int) (0.5 + 25.25 + 20) = 55, halved by the arithmetic shift to 27.
        ToDoubleFunction<String> fractional = text -> text.equals("ab") ? 25.25 : text.isEmpty() ? 0 : 20;

        Assertions.assertArrayEquals(new int[]{27}, InterfaceText.computeAlignmentShifts("ab\u0013cd", 0.5f, 100, fractional));
    }

    @Test
    void find_wrap_pen_should_measure_the_rest_only_up_to_the_word_wrap_point() {
        // From pen 0 with right 55: "aaa" ends at 30, the spaces at 50, "bbb" would end at 80 > 55, so the pass stops
        // before it with the spaces included.
        Assertions.assertEquals(50f, InterfaceText.findWrapPen("aaa  bbb", 0, 0, WRAP_RIGHT, MEASURE));
        // Spaces that already pass the right edge end the pass with them.
        Assertions.assertEquals(60f, InterfaceText.findWrapPen("aaa   bbb", 0, 0, WRAP_RIGHT, MEASURE));
        // A line feed ends it where it is; color codes and 0x12/0x13 advance nothing and do not start a word.
        Assertions.assertEquals(30f, InterfaceText.findWrapPen("\u0013\u0004aaa\nbbb", 0, 0, 100, MEASURE));
        // 0x0B belongs to its word.
        Assertions.assertEquals(0f, InterfaceText.findWrapPen("aa\u000Bbbbbbbbbb", 0, 0, WRAP_RIGHT, MEASURE));
        // The floored pen is compared: a word ending at 55.9 still fits right 55.
        Assertions.assertEquals(55.9f, InterfaceText.findWrapPen("ab", 0, 35.9f, WRAP_RIGHT, MEASURE));
    }

    @Test
    void compute_alignment_shifts_should_center_on_the_wrap_point_when_the_rest_overflows() {
        // "\x13" at pen 0, right 55: the rest "aaa  bbb" stops at 50 (see above): shift = (55 - 50) >> 1 = 2.
        Assertions.assertArrayEquals(new int[]{2}, InterfaceText.computeAlignmentShifts("\u0013aaa  bbb", 0, WRAP_RIGHT, MEASURE));
    }

    @Test
    void compute_alignment_shifts_should_center_a_line_padded_with_carriage_returns() {
        // Carriage returns advance nothing: "\x13\x05ab cd" and its padding center like "ab cd" alone, (630 - (int) (10 + 50)) >> 1 = 285.
        String line = "\u0013\u0005ab cd" + "\r".repeat(PADDING_LENGTH);

        Assertions.assertArrayEquals(new int[]{285}, InterfaceText.computeAlignmentShifts(line, MESSAGE_ROW_PEN, MESSAGE_ROW_RIGHT, MEASURE));
    }

    @Test
    void wrap_breaks_should_keep_a_padded_line_in_one_piece() {
        // No length limit breaks a piece: the padding stays with its line and the next piece starts after the line feed.
        String line = "\u0013\u0005ab cd" + "\r".repeat(PADDING_LENGTH) + "\n";
        String text = line + "\u0013ef";

        Assertions.assertArrayEquals(new int[]{line.length(), text.length()},
                                     InterfaceText.wrapBreaks(text, MESSAGE_ROW_RIGHT - MESSAGE_ROW_PEN, MEASURE));
    }

    @Test
    void wrap_breaks_should_end_a_piece_after_the_codes_before_the_word_that_passes_the_right_edge() {
        // Right 55: "aaa " fits and "bbb" would end at 70, so the piece ends at the word with the alignment and color codes before it.
        Assertions.assertArrayEquals(new int[]{6, 9}, InterfaceText.wrapBreaks("aaa \u0013\u0005bbb", WRAP_RIGHT, MEASURE));
    }

    @Test
    void truncate_lines_should_stop_at_an_unknown_control_byte_and_fill_with_empty_lines() {
        List<String> lines = List.of("ab\u000Bcd", "ef");

        Assertions.assertEquals(List.of("ab", "", ""), InterfaceText.truncateLines("ab\u000Bcd\nef", lines, 2));
        Assertions.assertEquals(List.of("ab"), InterfaceText.truncateLines("ab\u0014cd\nef", List.of("ab\u0014cd", "ef"), 0));

        // Color, alignment, tab, carriage return and 0x1A codes do not end drawing.
        List<String> kept = List.of("\u0004a\u0012b\tc\rd\u001Ae");

        Assertions.assertEquals(kept, InterfaceText.truncateLines(kept.getFirst(), kept, 3));
    }

    @Test
    void truncate_lines_should_end_the_layout_after_the_form_feed_line() {
        Assertions.assertEquals(List.of("ab"), InterfaceText.truncateLines("ab\fcd", List.of("ab", "cd"), 3));
        Assertions.assertEquals(List.of("ab", "cd"), InterfaceText.truncateLines("ab\ncd", List.of("ab", "cd"), 3));
    }

    @Test
    void find_line_end_should_end_between_words_and_keep_the_start_on_a_long_first_word() {
        // Right 55: "aaa bbb" would end at 70, so the piece ends at the word start 4 (the space stays on it).
        Assertions.assertEquals(4, InterfaceText.findLineEnd("aaa bbb", 0, 0, WRAP_RIGHT, InterfaceText.WrapMode.WORD, MEASURE));
        // A line feed ends the piece after itself.
        Assertions.assertEquals(3, InterfaceText.findLineEnd("ab\ncd", 0, 0, WRAP_RIGHT, InterfaceText.WrapMode.WORD, MEASURE));
        // The first word does not fit, so the starting offset is retained.
        Assertions.assertEquals(0, InterfaceText.findLineEnd("abcdefgh ij", 0, 0, WRAP_RIGHT, InterfaceText.WrapMode.WORD, MEASURE));
        // Leading spaces are taken before a first word that does not fit.
        Assertions.assertEquals(2, InterfaceText.findLineEnd("  abcdefgh", 0, 0, WRAP_RIGHT, InterfaceText.WrapMode.WORD, MEASURE));
    }

    @Test
    void find_line_end_should_stop_before_the_character_that_passes_the_right_edge_in_character_mode() {
        // Right 55: the sixth character ends at 60 > 55; spaces advance untested, so the second space (to 60) stays.
        Assertions.assertEquals(5, InterfaceText.findLineEnd("abcdefgh", 0, 0, WRAP_RIGHT, InterfaceText.WrapMode.CHARACTER, MEASURE));
        Assertions.assertEquals(6, InterfaceText.findLineEnd("ab cd efgh", 0, 0, WRAP_RIGHT, InterfaceText.WrapMode.CHARACTER, MEASURE));
        // No floor: "b" ending at 55.5 does not fit right 55.
        Assertions.assertEquals(1, InterfaceText.findLineEnd("ab", 0, 35.5f, WRAP_RIGHT, InterfaceText.WrapMode.CHARACTER, MEASURE));
        Assertions.assertEquals(3, InterfaceText.findLineEnd("ab\ncd", 0, 0, WRAP_RIGHT, InterfaceText.WrapMode.CHARACTER, MEASURE));
    }

    @Test
    void remove_caret_glyph_should_drop_only_the_edit_caret_glyph() {
        // The caret string 04 A6 01 keeps its color codes but loses the glyph for measuring and wrapping.
        Assertions.assertEquals("ab\u0004\u0001cd", InterfaceText.removeCaretGlyph("ab\u0004¦\u0001cd"));
        // A U+00A6 not preceded by code 0x04 is an ordinary glyph.
        Assertions.assertEquals("a¦b", InterfaceText.removeCaretGlyph("a¦b"));
    }

    @Test
    void carry_color_codes_should_start_each_line_with_the_codes_of_the_lines_before() {
        List<String> lines = List.of("a\u0003b", "c\u0006d\u0001", "", "e");

        Assertions.assertEquals(List.of("a\u0003b", "\u0003c\u0006d\u0001", "\u0003\u0006\u0001", "\u0003\u0006\u0001e"),
                                InterfaceText.carryColorCodes(lines));
        Assertions.assertEquals(List.of("a", "b"), InterfaceText.carryColorCodes(List.of("a", "b")));
    }

    @Test
    void carried_color_codes_should_give_each_line_the_pen_color_of_the_end_of_the_line_before() {
        // Line 1 ends in code 3; line 2 starts in it, switches to 6 and back to 3 with code 1; line 3 starts in 3 again.
        List<String> drawnLines = InterfaceText.carryColorCodes(List.of("a\u0003b", "c\u0006d\u0001", "e"));

        Assertions.assertEquals(START_COLOR, findStartColor(drawnLines.get(0)));
        Assertions.assertEquals(InterfaceFont.getCodeColor(3), findStartColor(drawnLines.get(1)));
        Assertions.assertEquals(InterfaceFont.getCodeColor(3), findStartColor(drawnLines.get(2)));
    }

    @Test
    void is_hotkey_string_should_find_the_marked_hotkey_letter_in_any_label() {
        Assertions.assertTrue(InterfaceText.isHotkeyString("l\u0004L\u0001ow"));
        Assertions.assertTrue(InterfaceText.isHotkeyString("pS\u0004p\u0001eed"));
        // A translated label marks the hotkey after its text.
        Assertions.assertTrue(InterfaceText.isHotkeyString("l낮음 (\u0004L\u0001)"));
        // A highlighted word that is not the first character, plain text and an empty string carry no hotkey.
        Assertions.assertFalse(InterfaceText.isHotkeyString("WARNING: a \u0004permanent\u0002 operation"));
        Assertions.assertFalse(InterfaceText.isHotkeyString("Latency"));
        Assertions.assertFalse(InterfaceText.isHotkeyString("l\u0004"));
        Assertions.assertFalse(InterfaceText.isHotkeyString(""));
    }
}
