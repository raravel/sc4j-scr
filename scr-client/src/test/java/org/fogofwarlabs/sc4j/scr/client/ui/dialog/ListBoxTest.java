package org.fogofwarlabs.sc4j.scr.client.ui.dialog;

import org.fogofwarlabs.sc4j.engine.client.dialog.DialogKey;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.ui.menu.ChatHistory;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Checks that list navigation pages by the rows the painter shows and the initial color of each row, message history rows included; the
 * navigation rules are the engine list model's.
 */
final class ListBoxTest {
    private static final int LIST_IDENTIFIER = 1;
    private static final int LIST_WIDTH = 200;
    /**
     * Three rows of {@code 16} pixels.
     */
    private static final int LIST_HEIGHT = 50;
    private static final int VISIBLE_ROWS = 3;
    private static final int ITEMS = 10;
    private static final int RED = 0xF40404FF;
    private static final int BLUE = 0x0C48CCFF;
    private static final int LOCAL_PLAYER = 0;
    private static final int OTHER_PLAYER = 1;
    /**
     * The message lines a game's first three messages write: line {@code 10}, then the rotation wraps to {@code 0} and {@code 1}.
     */
    private static final int FIRST_MESSAGE_LINE = 10;
    private static final int SECOND_MESSAGE_LINE = 0;
    private static final int THIRD_MESSAGE_LINE = 1;
    /**
     * The list control of the message history dialog.
     */
    private static final int HISTORY_LIST_LEFT = 16;
    private static final int HISTORY_LIST_TOP = 40;
    private static final int HISTORY_LIST_WIDTH = 370;
    private static final int HISTORY_LIST_HEIGHT = 220;
    /**
     * The row width of the message history list: its right edge {@code 16 + 370 - 1} less {@code 50}.
     */
    private static final float HISTORY_ROW_WIDTH = 335;
    /**
     * The short lines that make the message history list scroll.
     */
    private static final int HISTORY_SHORT_LINES = 20;
    private static final int LONG_MESSAGE_WORDS = 40;
    private static final int LONG_MESSAGE_SYLLABLES = 150;

    /**
     * Creates an empty list of three visible rows.
     *
     * @return the list
     */
    private static @NotNull ListBox createEmptyList() {
        DialogControl control = ScrDialogControlUtils.createControl(ScrControlType.LISTBOX,
                                                                    LIST_IDENTIFIER,
                                                                    0, 0,
                                                                    LIST_WIDTH, LIST_HEIGHT,
                                                                    Set.of("WS_VISIBLE"),
                                                                    "",
                                                                    List.of());

        return new ListBox(control, "sheet");
    }

    /**
     * Creates an empty list with the rectangle of the message history dialog's list.
     *
     * @return the list
     */
    private static @NotNull ListBox createHistoryList() {
        DialogControl control = ScrDialogControlUtils.createControl(ScrControlType.LISTBOX,
                                                                    LIST_IDENTIFIER,
                                                                    HISTORY_LIST_LEFT, HISTORY_LIST_TOP,
                                                                    HISTORY_LIST_WIDTH, HISTORY_LIST_HEIGHT,
                                                                    Set.of("WS_VISIBLE", "WS_BORDER"),
                                                                    "",
                                                                    List.of());

        return new ListBox(control, "dlg");
    }

    /**
     * Creates a list with the numbered rows used by the navigation fixtures.
     *
     * @return the populated list
     */
    private static @NotNull ListBox createList() {
        ListBox list = createEmptyList();

        for (int index = 0; index < ITEMS; index++) {
            list.addItem("row " + index, DialogPainter.CODE_BUTTON);
        }

        return list;
    }

    @Test
    void press_key_should_page_by_the_rows_the_painter_shows() {
        RecordingDialogPainter painter = new RecordingDialogPainter();
        ListBox list = createList();

        Assertions.assertEquals(VISIBLE_ROWS, list.getVisibleRows(painter));

        list.pressKey(painter, DialogKey.HOME);
        list.pressKey(painter, DialogKey.PAGE_DOWN);
        list.pressKey(painter, DialogKey.PAGE_DOWN);

        Assertions.assertEquals(4, list.getSelection(), "two pages of the three rows less one");
        Assertions.assertEquals(2, list.getTopRow(), "the selection stays in view");

        list.scrollByWheel(painter, false);

        Assertions.assertEquals(2, list.getSelection(), "without a scroll bar the wheel pages the selection");
    }

    @Test
    void draw_should_use_item_color_whether_row_is_selected_or_not() throws IOException {
        RecordingDialogPainter painter = new RecordingDialogPainter();
        ListBox list = createEmptyList();

        list.addItem("plain", DialogPainter.CODE_BUTTON);
        list.addItem("red", DialogPainter.CODE_BUTTON, RED);
        list.drawAt(painter, 0, 0);

        Assertions.assertEquals(List.of("plain"), painter.getCodeTexts());
        Assertions.assertEquals(List.of(DialogPainter.CODE_BUTTON), painter.getCodes());
        Assertions.assertEquals(List.of(RED), painter.getColors());

        list.select(1, VISIBLE_ROWS);
        painter.getCodeTexts().clear();
        painter.getCodes().clear();
        painter.getColors().clear();
        list.drawAt(painter, 0, 0);

        Assertions.assertEquals(List.of(RED), painter.getColors(), "the selected row keeps its own color");

        list.select(0, VISIBLE_ROWS);
        painter.getCodeTexts().clear();
        painter.getCodes().clear();
        painter.getColors().clear();
        list.drawAt(painter, 0, 0);

        Assertions.assertEquals(List.of(DialogPainter.CODE_HIGHLIGHT), painter.getCodes(),
                                "a selected row without a color is drawn in the highlight code");
    }

    @Test
    void chat_history_rows_should_keep_player_colors_of_own_and_received_lines() throws IOException {
        MessageLines lines = new MessageLines();
        ChatHistory history = new ChatHistory();

        lines.setLayout(text -> new int[]{text.length});
        lines.setHostClock(() -> 0);
        lines.setPlayerNames(new byte[][]{"Own".getBytes(StandardCharsets.UTF_8), "Other".getBytes(StandardCharsets.UTF_8)});
        lines.setNameColors(player -> player == LOCAL_PLAYER ? RED : BLUE);
        lines.setLocalPlayer(LOCAL_PLAYER);
        lines.setChatListener((line, style, color) -> history.add(new String(line, StandardCharsets.UTF_8), style, color));

        lines.writeChat(LOCAL_PLAYER, "hello".getBytes(StandardCharsets.UTF_8));
        lines.writeChat(OTHER_PLAYER, "hi".getBytes(StandardCharsets.UTF_8));

        ListBox list = createEmptyList();
        RecordingDialogPainter painter = new RecordingDialogPainter();

        history.open(list, text -> painter.measure(0, text), VISIBLE_ROWS);
        lines.writeChat(LOCAL_PLAYER, "bye".getBytes(StandardCharsets.UTF_8));

        Assertions.assertEquals(2, list.getSelection(), "the newest row is selected");

        list.drawAt(painter, 0, 0);

        Assertions.assertEquals(List.of(), painter.getCodeTexts());
        Assertions.assertEquals(List.of(RED, BLUE, RED), painter.getColors(), "older and newest rows start in their sender's color");
        Assertions.assertEquals(List.of(lines.getColor(FIRST_MESSAGE_LINE), lines.getColor(SECOND_MESSAGE_LINE), lines.getColor(THIRD_MESSAGE_LINE)),
                                painter.getColors(),
                                "the history rows start in the colors of the shown message lines");
    }

    @Test
    void chat_history_rows_should_fit_the_row_width_and_be_drawn_whole() throws IOException {
        RecordingDialogPainter painter = new RecordingDialogPainter();
        ChatHistory history = new ChatHistory();
        ListBox list = createHistoryList();

        for (int index = 0; index < HISTORY_SHORT_LINES; index++) {
            history.add("Other:\u0002 line " + index, MessageLines.STYLE_CHAT_TEXT);
        }

        history.add("Own:\u0003 " + "word ".repeat(LONG_MESSAGE_WORDS), MessageLines.STYLE_CHAT_TEXT, RED);
        history.add("Other:\u0002 " + "\u314B".repeat(LONG_MESSAGE_SYLLABLES), MessageLines.STYLE_CHAT_TEXT, BLUE);
        history.open(list, text -> painter.measure(0, text), list.getVisibleRows(painter));
        list.drawAt(painter, HISTORY_LIST_LEFT, HISTORY_LIST_TOP);

        Assertions.assertFalse(painter.getTexts().isEmpty());

        for (int index = 0; index < painter.getTexts().size(); index++) {
            String row = painter.getTexts().get(index);
            // The text draw wraps a row at its rectangle width less one and keeps only the first line.
            int drawnLength = InterfaceText.wrapBreaks(row, painter.getWidths().get(index) - 1, text -> painter.measure(0, text))[0];

            Assertions.assertEquals(row.length(), drawnLength, "row " + index + " is drawn whole: " + row);
            Assertions.assertTrue(painter.measure(0, row) <= HISTORY_ROW_WIDTH, "row " + index + " fits the row width: " + row);
        }
    }
}
