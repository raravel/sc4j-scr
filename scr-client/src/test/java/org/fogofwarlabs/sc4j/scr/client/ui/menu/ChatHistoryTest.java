package org.fogofwarlabs.sc4j.scr.client.ui.menu;

import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ListBox;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Checks color-preserving message wrapping, closed history, live appends, row limits and scrolling.
 */
final class ChatHistoryTest {
    /**
     * Every character is {@code 10} pixels wide.
     */
    private static final double CHARACTER_WIDTH = 10.0;
    private static final @NotNull ToDoubleFunction<String> MEASURE = text -> text.length() * CHARACTER_WIDTH;
    /**
     * Measures every code point but an unpaired surrogate {@code 10} pixels wide.
     */
    private static final @NotNull ToDoubleFunction<String> CODE_POINT_MEASURE = text -> text.codePoints()
                                                                                            .filter(codePoint -> codePoint < Character.MIN_SURROGATE ||
                                                                                                                 codePoint > Character.MAX_SURROGATE)
                                                                                            .count() * CHARACTER_WIDTH;
    private static final @NotNull String EMOJI = "\uD83D\uDE00";
    private static final int CODE = 2;
    private static final int SENDER = 0xF40404FF;
    /**
     * A row width of four characters.
     */
    private static final float NARROW_WIDTH = 40;
    /**
     * A row width of ten characters.
     */
    private static final float SHORT_TEXT_WIDTH = 100;
    /**
     * The row width of the list control: its right edge {@code 16 + 370 - 1} less {@code 50}.
     */
    private static final float ROW_WIDTH = 335;
    private static final int SHOWN_ROWS = 3;
    private static final int MANY_SHOWN_ROWS = 10;
    private static final int EXTRA_LINES = 10;
    /**
     * The first shown row of a scrolled full list.
     */
    private static final int SCROLLED_ROW = 100;
    /**
     * The most rows that the history fills into its list.
     */
    private static final int MAXIMUM_ROWS = 0xFF;
    private static final int LIST_CONTROL = 1;
    private static final int LIST_LEFT = 16;
    private static final int LIST_TOP = 40;
    private static final int LIST_WIDTH = 370;
    private static final int LIST_HEIGHT = 220;

    private static @NotNull DialogControl createListControl() {
        return ScrDialogControlUtils.createControl(ScrControlType.LISTBOX,
                                                   LIST_CONTROL,
                                                   LIST_LEFT, LIST_TOP,
                                                   LIST_WIDTH, LIST_HEIGHT,
                                                   Set.of("WS_VISIBLE"),
                                                   "",
                                                   List.of());
    }

    @Test
    void wrap_should_carry_last_color_code_to_next_row() {
        List<String> rows = ChatHistory.wrap("ab\u0007cdefgh", MEASURE, NARROW_WIDTH);

        Assertions.assertEquals(List.of("ab\u0007c", "\u0007def", "\u0007gh"), rows);
        Assertions.assertEquals(List.of("short"), ChatHistory.wrap("short", MEASURE, SHORT_TEXT_WIDTH));
    }

    @Test
    void row_width_should_end_before_the_list_right_edge() {
        Assertions.assertEquals(ROW_WIDTH, ChatHistory.getRowWidth(createListControl()));
    }

    @Test
    void wrap_should_carry_only_color_codes() {
        Assertions.assertEquals(List.of("ab\tc", "defg", "h"), ChatHistory.wrap("ab\tcdefgh", MEASURE, NARROW_WIDTH));
    }

    @Test
    void wrap_should_keep_surrogate_pairs_in_one_row() {
        List<String> rows = ChatHistory.wrap("a" + EMOJI.repeat(5), CODE_POINT_MEASURE, NARROW_WIDTH);

        Assertions.assertEquals(List.of("a" + EMOJI.repeat(3), EMOJI.repeat(2)), rows);
    }

    @Test
    void chat_history_keeps_records_and_appends_only_while_open() {
        ChatHistory history = new ChatHistory();

        history.add("one", CODE);
        history.add("two", CODE);

        ListBox list = new ListBox(createListControl(), "dlg");

        history.open(list, MEASURE, SHOWN_ROWS);

        Assertions.assertEquals(2, list.getCount());

        history.add("three", CODE);
        history.add("four", CODE);

        Assertions.assertEquals(4, list.getCount());
        Assertions.assertEquals(1, list.getTopRow(), "a list showing its last row keeps the new last row in view");

        history.close();

        Assertions.assertNull(history.getList());

        history.add("five", CODE);

        Assertions.assertEquals(4, list.getCount(), "a closed dialog's list takes no rows");
        Assertions.assertEquals(5, history.getEntryCount());
    }

    @Test
    void add_should_select_new_last_row_of_open_list() {
        ChatHistory history = new ChatHistory();
        ListBox list = new ListBox(createListControl(), "dlg");

        history.open(list, MEASURE, SHOWN_ROWS);
        history.add("one", CODE, SENDER);

        Assertions.assertEquals(0, list.getSelection(), "adding a line selects the new last row");

        history.add("two", CODE);

        Assertions.assertEquals(1, list.getSelection());
    }

    @Test
    void open_should_fill_list_with_at_most_maximum_rows() {
        ChatHistory history = new ChatHistory();

        for (int index = 0; index < ChatHistory.MAXIMUM_ROWS + EXTRA_LINES; index++) {
            history.add("line " + index, CODE);
        }

        ListBox list = new ListBox(createListControl(), "dlg");

        history.open(list, MEASURE, MANY_SHOWN_ROWS);

        Assertions.assertEquals(MAXIMUM_ROWS, list.getCount());
    }

    @Test
    void add_to_full_list_should_keep_shown_rows() {
        ChatHistory history = new ChatHistory();

        for (int index = 0; index < ChatHistory.MAXIMUM_ROWS; index++) {
            history.add("line " + index, CODE);
        }

        ListBox list = new ListBox(createListControl(), "dlg");

        history.open(list, MEASURE, MANY_SHOWN_ROWS);
        list.select(SCROLLED_ROW, MANY_SHOWN_ROWS);
        history.add("new", CODE);

        Assertions.assertEquals(MAXIMUM_ROWS, list.getCount());
        Assertions.assertEquals(SCROLLED_ROW - 1, list.getTopRow(), "the first row goes and the shown rows stay in view");

        list.select(list.getCount() - MANY_SHOWN_ROWS - 1, MANY_SHOWN_ROWS);

        int topRow = list.getTopRow();

        history.add("newer", CODE);

        Assertions.assertEquals(topRow - 1, list.getTopRow(), "a list one row above its end does not follow the new row");
    }
}
