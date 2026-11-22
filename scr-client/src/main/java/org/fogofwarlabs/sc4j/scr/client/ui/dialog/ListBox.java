package org.fogofwarlabs.sc4j.scr.client.ui.dialog;

import org.fogofwarlabs.sc4j.engine.client.dialog.DialogKey;
import org.fogofwarlabs.sc4j.engine.client.dialog.ListModel;
import org.fogofwarlabs.sc4j.engine.client.dialog.ScrollModel;
import org.fogofwarlabs.sc4j.engine.client.dialog.ScrollPart;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceTimers;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/**
 * Draws list rows as classic dialog bars or icon rows with the classic scroll bar, over the engine widget models: the rows, the selection
 * and the first shown row are a {@link ListModel}, the scroll bar's value, held part and thumb drag a {@link ScrollModel}. This class
 * keeps the native metrics (row height from the font and the bar frame, the bar's arrow, track and thumb frames), the scroll repeat on
 * the client's timer list and the look. A shown scroll bar changes wheel input from selection to scrolling.
 * <p>
 * A list is the SC:R control of its {@code LISTBOX} definition: a screen attaches it in place of the control its dialog built
 * ({@link ScrDialog#attach}), and it draws its own rows ({@link #drawAt}).
 */
public final class ListBox extends ScrControl {
    /**
     * No selected row.
     */
    public static final int NO_SELECTION = ListModel.NO_SELECTION;
    /**
     * An item without a color of its own: its row starts in its color code.
     */
    public static final int NO_COLOR = 0;
    private static final int NO_ICON = -1;
    /**
     * The file list painter puts the text {@code 4} pixels right of the icon.
     */
    private static final int ICON_TEXT_GAP = 4;
    /**
     * The dialog flag of a list with a border.
     */
    private static final @NotNull String BORDER_FLAG = "WS_BORDER";

    private static final int SMALL_FONT_BAR = 53;
    private static final int SMALL_FONT_SELECTED_BAR = 56;
    private static final int BAR = 77;
    private static final int SELECTED_BAR = 80;
    private static final int TEXT_INDENT = 2;
    /**
     * The gap adds to the maximum glyph height to make the line height.
     */
    private static final int LINE_GAP = 3;
    /**
     * The rows end this far left of a shown scroll bar ({@code right edge += bar left - bar right - 3}).
     */
    private static final int SCROLL_BAR_GAP = 2;

    private static final int UP_ARROW = 0x11;
    private static final int UP_ARROW_HELD = 0x12;
    private static final int DOWN_ARROW = 0x14;
    private static final int DOWN_ARROW_HELD = 0x15;
    private static final int TRACK_TOP = 0x1D;
    private static final int TRACK_MIDDLE = 0x1E;
    private static final int TRACK_BOTTOM = 0x1F;
    private static final int THUMB = 0x1C;
    /**
     * The two pixels between an arrow and the track.
     */
    private static final int ARROW_GAP = 2;
    private static final int ENDS_EXTRA = 4;
    private static final int TRAVEL_EXTRA = 5;
    /**
     * The thumb's drag clamp above the lower arrow.
     */
    private static final int DRAG_EXTRA = 3;
    private static final int TICK_MILLISECONDS = 100;
    /**
     * The list scroll-repeat timer identifier.
     */
    private static final int REPEAT_TIMER = 0xC;

    /**
     * What a press on the list hit.
     */
    public enum Press {
        /**
         * Neither a row nor the scroll bar.
         */
        NONE,
        /**
         * A row, which is now selected.
         */
        ROW,
        /**
         * The scroll bar, which now tracks the pointer until {@link #release}.
         */
        SCROLL_BAR
    }

    /**
     * Holds one row's text and how it is drawn.
     */
    private static final class Row {
        private final @NotNull String text;
        private final int code;
        private final int color;
        private final int icon;

        private Row(@NotNull String text, int code, int color, int icon) {
            this.text  = text;
            this.code  = code;
            this.color = color;
            this.icon  = icon;
        }
    }

    /**
     * Retains the classic arrow, track and thumb sizes of the scroll bar and lays them out for a list height.
     */
    private static final class ScrollMetrics {
        private final int width;
        private final int arrowHeight;
        private final int tileHeight;
        private final int thumbWidth;
        private final int thumbHeight;

        /**
         * The bar height (bottom - top): whole track tiles plus two arrows and {@code 4}.
         */
        private int height;
        /**
         * The thumb's travel (height - {@code 2} arrows - thumb - {@code 5}).
         */
        private int travel;
        /**
         * The track tiles from the top end to the bottom end.
         */
        private int tiles;

        private ScrollMetrics(DialogPainter.@NotNull Frame arrow, DialogPainter.@NotNull Frame tile, DialogPainter.@NotNull Frame thumb) {
            this.width       = (int) arrow.getClassicWidth();
            this.arrowHeight = (int) arrow.getClassicHeight();
            this.tileHeight  = Math.max(1, (int) tile.getClassicHeight());
            this.thumbWidth  = (int) thumb.getClassicWidth();
            this.thumbHeight = (int) thumb.getClassicHeight();

            this.height = 0;
            this.travel = 0;
            this.tiles  = 0;
        }

        private void layout(int listHeight) {
            int ends = 2 * this.arrowHeight + ENDS_EXTRA;

            this.height = (this.tileHeight / 2 - ends + listHeight) / this.tileHeight * this.tileHeight + ends;
            this.travel = this.height - 2 * this.arrowHeight - this.thumbHeight - TRAVEL_EXTRA;
            this.tiles  = (this.travel + this.thumbHeight) / this.tileHeight;
        }

        /**
         * @return the offset of the track's start from the bar top, below the upper arrow and its gap
         */
        private int getTrackStart() {
            return this.arrowHeight + ARROW_GAP;
        }

        /**
         * @param scroll  the bar's model
         * @param offsetY the offset from the bar top
         * @return the part under an offset from the bar top, or {@link ScrollPart#NONE} off the bar
         */
        private @NotNull ScrollPart findPart(@NotNull ScrollModel scroll, float offsetY) {
            if (offsetY < 0 || offsetY > this.height) {
                return ScrollPart.NONE;
            }

            int arrowEnd = this.getTrackStart();

            return scroll.findPart(offsetY, arrowEnd, this.height + 1 - arrowEnd, arrowEnd + scroll.getThumbOffset(this.travel), this.thumbHeight);
        }

        /**
         * Drags the bar's thumb to an offset from the bar top, clamped above the lower arrow.
         *
         * @param scroll  the bar's model
         * @param offsetY the offset from the bar top
         */
        private void drag(@NotNull ScrollModel scroll, float offsetY) {
            int limit = this.height - this.thumbHeight / 2 - this.arrowHeight - DRAG_EXTRA;

            scroll.dragTo(offsetY, this.getTrackStart(), limit, this.thumbHeight, this.travel);
        }
    }

    /**
     * Draws an optional icon at its classic size followed by text after the supplied gap, both centered vertically on the row (the text by
     * its font's line box, {@link ControlPainterUtils#ROW_TEXT_FORMAT}). The label is shortened by UTF-16 characters until it fits the right edge.
     *
     * @param canvas   the painter
     * @param fontSlot the font slot
     * @param icon     the icon, or {@code null} for a label alone
     * @param left     the icon's left (the label's without an icon)
     * @param top      the row top
     * @param height   the row height
     * @param gap      the space between the icon and the label
     * @param right    the right end of the label
     * @param text     the label
     * @param code     the color code
     */
    public static void drawIconLabel(@NotNull DialogPainter canvas,
                                     int fontSlot,
                                     DialogPainter.@Nullable Frame icon,
                                     float left, float top,
                                     float height,
                                     float gap,
                                     float right,
                                     @NotNull String text,
                                     int code) {
        float textLeft = left;

        if (icon != null) {
            canvas.drawImageFrame(icon, left, top + (int) (height / 2) - (int) (icon.getClassicHeight() / 2));

            textLeft += icon.getClassicWidth() + gap;
        }

        float width = right - textLeft;
        String shown = text;

        while (!shown.isEmpty() && canvas.measure(fontSlot, shown) > width) {
            shown = shown.substring(0, shown.length() - 1);
        }

        canvas.drawCodeText(fontSlot, shown, textLeft, top, width, height, ControlPainterUtils.ROW_TEXT_FORMAT, code);
    }

    private final @NotNull String sheetPath;
    private final @NotNull ListModel<Row> model;
    private final @NotNull ScrollModel scroll;

    private @Nullable String iconSheet;
    private boolean transparent;
    private @Nullable InterfaceTimers timers;

    /**
     * Creates a {@link ListBox} instance.
     *
     * @param control the dialog control
     * @param sheet   the dialog sheet whose frames draw the bars and arrows, for example {@code glue/palnl/dlg.dds.grp}
     */
    public ListBox(@NotNull DialogControl control, @NotNull String sheet) {
        super(control);

        this.sheetPath = sheet;
        this.model     = new ListModel<>();
        this.scroll    = new ScrollModel();

        this.iconSheet   = null;
        this.transparent = false;
        this.timers      = null;
    }

    /**
     * @return the number of items
     */
    public int getCount() {
        return this.model.getCount();
    }

    /**
     * @return the selected index, or {@link #NO_SELECTION}
     */
    public int getSelection() {
        return this.model.getSelection();
    }

    /**
     * @return the first shown row
     */
    public int getTopRow() {
        return this.model.getFirstRow();
    }

    /**
     * @param canvas the glue canvas
     * @return the row height in interface pixels
     */
    public float getItemHeight(@NotNull DialogPainter canvas) {
        float fontHeight = canvas.getLineHeight(canvas.getControlFontSlot(this.getDefinition())) - LINE_GAP;

        try {
            return Math.max(fontHeight, canvas.getSheetFrame(this.sheetPath, BAR).getHeight());
        } catch (IOException exception) {
            return fontHeight;
        }
    }

    /**
     * @param canvas the glue canvas
     * @return the number of whole rows the control shows
     */
    public int getVisibleRows(@NotNull DialogPainter canvas) {
        return (int) (this.getDefinition().getHeight() / this.getItemHeight(canvas));
    }

    /**
     * Sets whether the list background is transparent.
     *
     * @param transparent whether the rows are drawn without bars
     */
    public void setTransparent(boolean transparent) {
        this.transparent = transparent;
    }

    /**
     * Sets the items; the selection is cleared.
     *
     * @param texts the item texts
     */
    public void setItems(@NotNull List<String> texts) {
        this.model.setItems(List.of());

        for (String text : texts) {
            this.addItem(text, DialogPainter.CODE_BUTTON);
        }
    }

    /**
     * Sets the icon sheet for rows drawn with an icon and label instead of a bar.
     *
     * @param sheet the icon sheet, for example {@code glue/create/icreate.dds.grp}
     */
    public void setIconSheet(@NotNull String sheet) {
        this.iconSheet = sheet;
    }

    /**
     * Sets the client's timer list that runs the scroll repeat: timer {@code 0xC} every {@code 100} ms while an arrow or the track is held, at most
     * one step per pump. Without timers a held press steps once.
     *
     * @param timers the client's interface timers, or {@code null} to detach (the list closes)
     */
    public void setTimers(@Nullable InterfaceTimers timers) {
        this.release();

        this.timers = timers;
    }


    /**
     * @param canvas the glue canvas
     * @return {@code true} if the list has a scroll bar control (the list, cut to whole rows, is taller than a track tile and two arrows), shown or
     * not, {@code false} otherwise
     */
    private boolean hasScrollBar(@NotNull DialogPainter canvas) {
        ScrollMetrics metrics = this.getScrollMetrics(canvas);

        return metrics != null && this.getRowsHeight(canvas) > metrics.tileHeight + 2 * metrics.arrowHeight;
    }

    /**
     * @param canvas the glue canvas
     * @return the list height cut to whole rows, at least one row (bottom = top + max(rows * item height, item height))
     */
    private int getRowsHeight(@NotNull DialogPainter canvas) {
        int itemHeight = (int) this.getItemHeight(canvas);

        return Math.max(this.getVisibleRows(canvas) * itemHeight, itemHeight);
    }

    /**
     * Lays out the scroll bar while the items do not fit and sets its model to the rows: the value is the first shown row, up to the items
     * less the shown rows, and a page is the shown rows.
     *
     * @param canvas the glue canvas
     * @return the scroll bar metrics while the items do not fit, or {@code null}
     */
    private @Nullable ScrollMetrics getShownScrollBar(@NotNull DialogPainter canvas) {
        int rows = this.getVisibleRows(canvas);
        int count = this.model.getCount();

        if (count <= rows || !this.hasScrollBar(canvas)) {
            return null;
        }

        ScrollMetrics metrics = this.getScrollMetrics(canvas);

        if (metrics == null) {
            return null;
        }

        metrics.layout(this.getRowsHeight(canvas));

        this.scroll.setRange(0, Math.max(0, count - rows));
        this.scroll.setPage(rows);
        this.scroll.setValue(this.model.getFirstRow());

        return metrics;
    }

    private @Nullable ScrollMetrics getScrollMetrics(@NotNull DialogPainter canvas) {
        try {
            return new ScrollMetrics(canvas.getSheetFrame(this.sheetPath, UP_ARROW),
                                     canvas.getSheetFrame(this.sheetPath, TRACK_MIDDLE),
                                     canvas.getSheetFrame(this.sheetPath, THUMB));
        } catch (IOException exception) {
            return null;
        }
    }


    /**
     * Appends an item.
     *
     * @param text the item text
     * @param code the color code of the unselected row
     */
    public void addItem(@NotNull String text, int code) {
        this.addItem(text, code, NO_COLOR);
    }

    /**
     * Appends an item with a color of its own: the row text starts in that color, selected or not, and its inline color codes still change it.
     *
     * @param text  the item text
     * @param code  the color code of the unselected row without a color
     * @param color the packed {@code 0xRRGGBBAA} starting color, or {@link #NO_COLOR}
     */
    public void addItem(@NotNull String text, int code, int color) {
        this.model.addItem(new Row(text, code, color, NO_ICON));
    }

    /**
     * Appends an item drawn with an icon of the icon sheet ({@link #setIconSheet}).
     *
     * @param text the item text
     * @param icon the icon frame of the unselected row; the selected row uses the next frame
     */
    public void addIconItem(@NotNull String text, int icon) {
        this.model.addItem(new Row(text, DialogPainter.CODE_BUTTON, NO_COLOR, icon));
    }

    /**
     * Removes an item; a selection past the end moves to the last row.
     *
     * @param index the index
     */
    public void removeItem(int index) {
        this.model.removeItem(index);
    }

    /**
     * Selects a row and scrolls it into view.
     *
     * @param index the index, or {@link #NO_SELECTION}
     * @param rows  the visible rows
     */
    public void select(int index, int rows) {
        this.model.select(index, rows);
    }

    /**
     * Shows a row first without changing the selection.
     *
     * @param first the index of the first shown row, clamped so that the shown rows hold items
     * @param rows  the visible rows
     */
    public void scrollTo(int first, int rows) {
        this.model.scrollTo(first, rows);
    }


    /**
     * Presses the list: the scroll bar when it is shown and hit, else the row under the pointer.
     *
     * @param canvas  the glue canvas
     * @param offsetX the offset from the control left in interface pixels
     * @param offsetY the offset from the control top in interface pixels
     * @param time    the time in milliseconds
     * @return what was hit
     */
    public @NotNull Press pressAt(@NotNull DialogPainter canvas, float offsetX, float offsetY, long time) {
        ScrollMetrics metrics = this.getShownScrollBar(canvas);

        if (metrics != null && offsetX >= this.getDefinition().getWidth() - metrics.width) {
            ScrollPart part = metrics.findPart(this.scroll, offsetY);

            if (part == ScrollPart.NONE) {
                return Press.NONE;
            }

            this.scroll.press(part);

            InterfaceTimers current = this.timers;

            if (current != null && !this.scroll.isDragging()) {
                current.arm(this, REPEAT_TIMER, TICK_MILLISECONDS, (int) time, clock -> this.repeatScroll());
            }

            if (this.scroll.isDragging()) {
                metrics.drag(this.scroll, offsetY);
            }

            this.model.scrollTo(this.scroll.getValue(), this.scroll.getPage());

            return Press.SCROLL_BAR;
        }

        return this.model.selectAt(offsetY, this.getItemHeight(canvas), this.getVisibleRows(canvas)) ? Press.ROW : Press.NONE;
    }

    /**
     * Repeats the held arrow or track part for one tick of the scroll-repeat timer.
     */
    private void repeatScroll() {
        this.scroll.setValue(this.model.getFirstRow());

        if (this.scroll.repeat()) {
            this.model.scrollTo(this.scroll.getValue(), this.scroll.getPage());
        }
    }

    /**
     * Moves the pointer while the scroll bar is pressed: the thumb follows it, and an arrow or the track is held only while the pointer
     * stays over it.
     *
     * @param canvas  the glue canvas
     * @param offsetY the offset from the control top in interface pixels
     */
    public void dragTo(@NotNull DialogPainter canvas, float offsetY) {
        ScrollMetrics metrics = this.getShownScrollBar(canvas);

        if (metrics == null || this.scroll.getHeldPart() == ScrollPart.NONE) {
            return;
        }

        if (!this.scroll.isDragging()) {
            this.scroll.movePointer(metrics.findPart(this.scroll, offsetY));

            return;
        }

        metrics.drag(this.scroll, offsetY);

        this.model.scrollTo(this.scroll.getValue(), this.scroll.getPage());
    }

    /**
     * Follows the scroll bar once per frame: a held press ends when the bar is gone, and the repeat timer steps with the bar's current
     * rows.
     *
     * @param canvas the glue canvas
     */
    public void update(@NotNull DialogPainter canvas) {
        if (this.getShownScrollBar(canvas) == null) {
            this.release();
        }
    }

    /**
     * Moves the selection by one row.
     *
     * @param canvas the glue canvas
     * @param down   {@code true} for the next row
     */
    public void step(@NotNull DialogPainter canvas, boolean down) {
        this.model.step(down, this.getVisibleRows(canvas));
    }

    /**
     * Moves the selection for a navigation key and keeps it visible ({@link ListModel#moveSelection}). Without a selection, only Home and
     * End choose a row.
     *
     * @param canvas the glue canvas
     * @param key    {@link DialogKey#UP}, {@link DialogKey#DOWN}, {@link DialogKey#HOME}, {@link DialogKey#END}, {@link DialogKey#PAGE_UP}
     *               or {@link DialogKey#PAGE_DOWN}
     * @throws IllegalArgumentException if the key is not a navigation key
     */
    public void pressKey(@NotNull DialogPainter canvas, @NotNull DialogKey key) {
        this.model.moveSelection(key, this.getVisibleRows(canvas));
    }

    /**
     * Scrolls one row when a scrollbar is available; otherwise moves the current selection by visible rows less one.
     *
     * @param canvas the glue canvas
     * @param down   {@code true} for a notch toward the user
     */
    public void scrollByWheel(@NotNull DialogPainter canvas, boolean down) {
        this.model.scrollByWheel(down, this.getVisibleRows(canvas), this.hasScrollBar(canvas));
    }


    /**
     * Draws the control natively at a position: a plain control through its dialog's procedure painter ({@link ScrDialog#setPainter}),
     * nothing while it is not in an SC:R dialog with one; a widget subclass draws its own look.
     * <p>
     * The list draws its rows, its border and its shown scroll bar.
     *
     * @param canvas the SC:R dialog painter
     * @param left   the control's left edge in interface pixels, the shift included
     * @param top    the control's top edge in interface pixels, the vertical shift included
     * @throws IOException if the bar sheet cannot be read
     */
    @Override
    public void drawAt(@NotNull DialogPainter canvas, float left, float top) throws IOException {
        float itemHeight = this.getItemHeight(canvas);

        // Draw the border around the complete rows before drawing their backgrounds.
        if (!this.transparent && ScrDialogControlUtils.hasFlag(this.getDefinition(), BORDER_FLAG)) {
            ControlPainterUtils.drawFieldBorder(canvas, left, top, this.getDefinition().getWidth(), this.getVisibleRows(canvas) * itemHeight, false);
        }

        boolean smallFont = canvas.getLineHeight(canvas.getControlFontSlot(this.getDefinition())) - LINE_GAP <
                            canvas.getSheetFrame(this.sheetPath, BAR).getHeight();
        // The row tile is the width of frame 53 or 77.
        int tile = canvas.getMetricWidth(this.sheetPath, smallFont ? SMALL_FONT_BAR : BAR);
        int firstRow = this.model.getFirstRow();
        int rows = Math.min((int) (this.getDefinition().getHeight() / itemHeight), this.model.getCount() - firstRow);
        int fontSlot = canvas.getControlFontSlot(this.getDefinition());
        ScrollMetrics metrics = this.getShownScrollBar(canvas);
        float rowWidth = metrics == null ? this.getDefinition().getWidth() : this.getDefinition().getWidth() - metrics.width - SCROLL_BAR_GAP;
        String sheet = this.iconSheet;

        for (int row = 0; row < rows; row++) {
            int index = firstRow + row;
            Row item = this.model.getItem(index);
            boolean selected = index == this.model.getSelection();

            if (sheet != null) {
                float iconTop = top + row * itemHeight;
                DialogPainter.Frame image = item.icon != NO_ICON ? canvas.getSheetFrame(sheet, selected ? item.icon + 1 : item.icon) : null;
                int code = selected ? DialogPainter.CODE_HIGHLIGHT : item.code;

                drawIconLabel(canvas, fontSlot, image,
                              left + TEXT_INDENT, iconTop,
                              itemHeight + 1,
                              ICON_TEXT_GAP,
                              left + rowWidth,
                              item.text,
                              code);

                continue;
            }

            int frame = smallFont ? selected ? SMALL_FONT_SELECTED_BAR : SMALL_FONT_BAR : selected ? SELECTED_BAR : BAR;
            float rowTop = top + row * itemHeight;

            if (!this.transparent) {
                canvas.drawBar(this.sheetPath, frame, left, rowTop, rowWidth, tile);
            }

            // Row text includes the bottom pixel below the control-height rectangle.
            if (item.color == NO_COLOR) {
                canvas.drawCodeText(fontSlot,
                                    item.text,
                                    left + TEXT_INDENT, rowTop,
                                    rowWidth - TEXT_INDENT, itemHeight + 1,
                                    InterfaceText.LEFT | InterfaceText.TOP,
                                    selected ? DialogPainter.CODE_HIGHLIGHT : item.code);
            } else {
                // A row with its own color starts in it, selected or not.
                canvas.drawColorText(fontSlot,
                                     item.text,
                                     left + TEXT_INDENT, rowTop,
                                     rowWidth - TEXT_INDENT, itemHeight + 1,
                                     InterfaceText.LEFT | InterfaceText.TOP,
                                     item.color);
            }
        }

        if (metrics != null) {
            this.drawScrollBar(canvas, metrics, left + this.getDefinition().getWidth() - metrics.width, top);
        }
    }

    private void drawScrollBar(@NotNull DialogPainter canvas, @NotNull ScrollMetrics metrics, float left, float top) throws IOException {
        boolean upHeld = this.scroll.isPartPressed(ScrollPart.STEP_BACK);
        boolean downHeld = this.scroll.isPartPressed(ScrollPart.STEP_FORWARD);

        canvas.drawFrame(canvas.getSheetFrame(this.sheetPath, upHeld ? UP_ARROW_HELD : UP_ARROW), left, top);

        int rowTop = metrics.getTrackStart();

        canvas.drawFrame(canvas.getSheetFrame(this.sheetPath, TRACK_TOP), left, top + rowTop);

        for (int tile = 1; tile < metrics.tiles; tile++) {
            rowTop += metrics.tileHeight;

            canvas.drawFrame(canvas.getSheetFrame(this.sheetPath, TRACK_MIDDLE), left, top + rowTop);
        }

        rowTop += metrics.tileHeight;

        canvas.drawFrame(canvas.getSheetFrame(this.sheetPath, TRACK_BOTTOM), left, top + rowTop);
        canvas.drawFrame(canvas.getSheetFrame(this.sheetPath, downHeld ? DOWN_ARROW_HELD : DOWN_ARROW),
                         left, top + rowTop + metrics.tileHeight + ARROW_GAP);

        int offset = this.scroll.getThumbOffset(metrics.travel);
        float thumbLeft = left + ((metrics.width - metrics.thumbWidth) >> 1) - 1;

        canvas.drawFrame(canvas.getSheetFrame(this.sheetPath, THUMB), thumbLeft, top + offset + ARROW_GAP + metrics.arrowHeight);
    }


    /**
     * Releases the scroll bar: its held part and the scroll-repeat timer.
     */
    public void release() {
        InterfaceTimers current = this.timers;

        if (current != null) {
            current.kill(this, REPEAT_TIMER);
        }

        this.scroll.release();
    }
}
