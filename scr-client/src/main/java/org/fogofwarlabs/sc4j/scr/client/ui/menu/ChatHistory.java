package org.fogofwarlabs.sc4j.scr.client.ui.menu;

import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.ListBox;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceFont;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Keeps formatted message records and appends wrapped rows while the history list is open.
 * Rows retain the message color and continuation color codes and fit the list's row width ({@link #getRowWidth}).
 * A full list drops its first row and keeps its shown rows in view.
 * Appending selects the new last row when the list was already at its end.
 */
public final class ChatHistory {
    /**
     * The most rows a list holds: the list keeps its row count in a byte.
     */
    public static final int MAXIMUM_ROWS = 0xFF;
    /**
     * The pixels a row keeps clear before the list's right edge.
     */
    private static final int ROW_RIGHT_MARGIN = 50;

    /**
     * One kept message record: its formatted text, its color code and its starting color.
     */
    private static final class Entry {
        private final @NotNull String text;
        private final int code;
        private final int color;

        private Entry(@NotNull String text, int code, int color) {
            this.text  = text;
            this.code  = code;
            this.color = color;
        }
    }

    /**
     * Returns the row width of a history list: the list's right edge relative to its dialog (its left plus its width less one) less
     * {@link #ROW_RIGHT_MARGIN}, for rows measured from a pen at {@code 0}.
     *
     * @param control the list control
     * @return the row width in interface pixels
     */
    public static float getRowWidth(@NotNull DialogControl control) {
        return control.getX() + control.getWidth() - 1 - ROW_RIGHT_MARGIN;
    }

    /**
     * Wraps text before a code point exceeds the row width. Continuation rows retain the preceding row's last color code
     * ({@link InterfaceFont#isColorCode}).
     *
     * @param text    the text
     * @param measure measures a text in the list's font
     * @param width   the row width; {@code 0} or less keeps one row
     * @return the rows
     */
    public static @NotNull List<String> wrap(@NotNull String text, @NotNull ToDoubleFunction<String> measure, float width) {
        List<String> rows = new ArrayList<>();

        if (width <= 0 || measure.applyAsDouble(text) <= width) {
            rows.add(text);

            return rows;
        }

        StringBuilder row = new StringBuilder();
        int rowStart = 0;
        char carried = 0;
        int index = 0;

        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            int length = row.length();

            row.appendCodePoint(codePoint);

            // A row keeps at least one code point after its carried color code.
            if (length > rowStart && measure.applyAsDouble(row.toString()) > width) {
                row.setLength(length);

                String finished = row.toString();

                rows.add(finished);

                for (int colorIndex = 0; colorIndex < finished.length(); colorIndex++) {
                    char colorCharacter = finished.charAt(colorIndex);

                    if (InterfaceFont.isColorCode(colorCharacter)) {
                        carried = colorCharacter;
                    }
                }

                row.setLength(0);

                if (carried != 0) {
                    row.append(carried);
                }

                rowStart = row.length();
                row.appendCodePoint(codePoint);
            }

            index += Character.charCount(codePoint);
        }

        if (!row.isEmpty()) {
            rows.add(row.toString());
        }

        return rows;
    }

    private final @NotNull List<Entry> entries;

    private @Nullable ListBox list;
    private @Nullable ToDoubleFunction<String> measure;
    private float width;
    private int rows;

    /**
     * Creates a {@link ChatHistory} instance without message records for a new game.
     */
    public ChatHistory() {
        this.entries = new ArrayList<>();

        this.list    = null;
        this.measure = null;
        this.width   = 0;
        this.rows    = 0;
    }

    /**
     * @return the number of kept message records
     */
    public int getEntryCount() {
        return this.entries.size();
    }

    /**
     * @return the open dialog's list, or {@code null} while the dialog is closed
     */
    public @Nullable ListBox getList() {
        return this.list;
    }


    /**
     * Keeps a message record without a starting color.
     *
     * @param text the formatted line
     * @param code the line's color code
     */
    public void add(@NotNull String text, int code) {
        this.add(text, code, ListBox.NO_COLOR);
    }

    /**
     * Keeps a message record and, while the dialog is open, appends its rows to the list.
     *
     * @param text  the formatted line
     * @param code  the line's color code
     * @param color the packed {@code 0xRRGGBBAA} starting color of the line (the sender's player color), or {@link ListBox#NO_COLOR}
     */
    public void add(@NotNull String text, int code, int color) {
        Entry entry = new Entry(text, code, color);

        this.entries.add(entry);

        if (this.list != null && this.measure != null) {
            this.append(this.list, this.measure, this.width, entry);
        }
    }

    /**
     * Opens the dialog's list: the rows of every kept record are added at the list's row width ({@link #getRowWidth}), the last row in
     * view.
     *
     * @param list    the dialog's list control (control {@code 1})
     * @param measure measures a text in the list's font, in interface pixels
     * @param rows    the rows the list shows
     */
    public void open(@NotNull ListBox list, @NotNull ToDoubleFunction<String> measure, int rows) {
        this.list    = list;
        this.measure = measure;
        this.width   = getRowWidth(list.getDefinition());
        this.rows    = rows;

        list.setItems(List.of());

        for (Entry entry : this.entries) {
            this.append(list, measure, this.width, entry);
        }
    }

    private void append(@NotNull ListBox list, @NotNull ToDoubleFunction<String> measure, float width, @NotNull Entry entry) {
        for (String row : wrap(entry.text, measure, width)) {
            // Select and keep the new row in view when the list was at its end before the row.
            boolean atEnd = list.getTopRow() + this.rows >= list.getCount();

            if (list.getCount() >= MAXIMUM_ROWS) {
                list.removeItem(0);

                // The shown rows stay in view.
                if (list.getTopRow() > 0) {
                    list.scrollTo(list.getTopRow() - 1, this.rows);
                }
            }

            list.addItem(row, entry.code, entry.color);

            if (atEnd) {
                list.select(list.getCount() - 1, this.rows);
            }
        }
    }


    /**
     * Detaches the open list and its measurement state while retaining message records.
     */
    public void close() {
        this.list    = null;
        this.measure = null;
        this.width   = 0;
        this.rows    = 0;
    }
}
