package org.fogofwarlabs.sc4j.scr.client.glue.lobby;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogDefinition;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueCanvas;
import org.fogofwarlabs.sc4j.scr.client.glue.GlueContext;
import org.fogofwarlabs.sc4j.scr.client.glue.GluePopup;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.EditControl;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.WebPanelStyleUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.WebScrollBar;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shows a Beacon.Link popup over the channel chat on one of the install's chat popup dialogs (a dimmed screen, the popup's background and
 * its page): the page's title, a framed list of rows ({@link WebRowList#drawFramed}, rows as high as the participant lists' rows), a note line
 * (a hint, or a separator with a word in its middle), a text field over the page's width, and the row of buttons at the bottom that share the
 * row's width ({@code .sideBySideButtons}). The title and the note are the screens' texts, the field and the buttons the screens' framed text
 * field and buttons. The channel list and the add-friend popup share it; each fills the rows and handles its buttons.
 */
abstract class BeaconLinkPopup extends GluePopup {
    /**
     * The input's control id; the popup dialogs do not use it.
     */
    static final int INPUT = 90;

    private static final int PAGE = 11;
    private static final int LIST_SCROLL = 91;
    private static final int TITLE = 94;
    private static final int NOTE = 95;

    /**
     * The page ({@code .selectionHolder}: padding {@code 10}): the title (the screens' big text, centered, a line of {@code 20} ending at the
     * list), the framed list, the input row and the button row at the bottom.
     */
    private static final int PADDING = 10;
    private static final int TITLE_TOP = 6;
    private static final int TITLE_HEIGHT = 20;
    private static final int LIST_TOP = TITLE_TOP + TITLE_HEIGHT;
    /**
     * The text field and the buttons ({@code 8} between the field's row and the buttons' row; the buttons of a row {@code 2} % of its width
     * apart). The note line (the screens' small text, a line of {@code 14}, {@code 4} below the list and above the field) is a hint, or a
     * word between two lines ({@code .straightLine}: {@code 1} px {@code #a2b1f5}).
     */
    private static final int FIELD_HEIGHT = FRAMED_CONTROL_HEIGHT;
    private static final int ROW_GAP = 8;
    private static final float BUTTON_GAP_SHARE = 0.02f;
    private static final int NOTE_HEIGHT = 14;
    private static final int NOTE_GAP = 4;
    private static final float SEPARATOR_GAP = 6;
    private static final int SIDE_COUNT = 2;
    private static final int CENTER_DIVISOR = 2;

    private static final @NotNull String VISIBLE_FLAG = "WS_VISIBLE";
    private static final @NotNull String SMALL_TEXT_FLAG = "RC_SMALLTEXT";
    private static final @NotNull String BIG_TEXT_FLAG = "RC_BIGTEXT";
    private static final @NotNull String TAB_STOP_FLAG = "WS_TABSTOP";
    private static final @NotNull String HOTKEY_FLAG = "WS_HOTKEY";
    private static final @NotNull String ESCAPE_FLAG = "CTRL_ESCAPE";
    private static final @NotNull String ENTER_FLAG = "CTRL_ENTER";
    private static final @NotNull String HORIZONTAL_CENTER_FLAG = "FB_HTEXTCENTER";
    private static final @NotNull String VERTICAL_CENTER_FLAG = "FB_VTEXTCENTER";

    /**
     * A button of the popup: its control id, its label (with the hotkey character first) and whether Enter or Esc activates it.
     */
    static final class Button {
        private final int id;
        private final @NotNull String label;
        private final boolean enter;
        private final boolean escape;

        /**
         * Creates a {@link Button} instance.
         *
         * @param id     the control id
         * @param label  the label, its hotkey character first
         * @param enter  whether Enter activates the button
         * @param escape whether Esc activates the button
         */
        Button(int id, @NotNull String label, boolean enter, boolean escape) {
            this.id     = id;
            this.label  = label;
            this.enter  = enter;
            this.escape = escape;
        }
    }

    private static @NotNull DialogControl createButton(@NotNull Button button, int left, int top, int width) {
        Set<String> flags = new HashSet<>(Set.of(VISIBLE_FLAG, TAB_STOP_FLAG, HOTKEY_FLAG, HORIZONTAL_CENTER_FLAG, VERTICAL_CENTER_FLAG,
                                                 FRAMED_FLAG));

        if (button.enter) {
            flags.add(ENTER_FLAG);
        }

        if (button.escape) {
            flags.add(ESCAPE_FLAG);
        }

        return ScrDialogControlUtils.createControl(ScrControlType.FLCBTN,
                                                   button.id,
                                                   left, top,
                                                   width, FIELD_HEIGHT,
                                                   flags,
                                                   "",
                                                   List.of());
    }

    /**
     * Returns the width of the buttons that share a row's width ({@code .sideBySideButtons}) and the space between them.
     *
     * @return the width of each button
     */
    private static int getButtonWidth(@NotNull List<Button> buttons, int rowWidth) {
        float gap = rowWidth * BUTTON_GAP_SHARE;

        return (int) ((rowWidth - (buttons.size() - 1) * gap) / buttons.size());
    }

    private final @NotNull String locale;
    private final @NotNull WebRowList rows;
    private final @NotNull List<Button> buttons;

    /**
     * The page's layout in dialog pixels, set when the dialog loads.
     */
    private int pageLeft;
    private int pageTop;
    private int pageWidth;
    private int listHeight;
    private @Nullable EditControl input;

    /**
     * Creates a {@link BeaconLinkPopup} instance.
     *
     * @param dialog        the popup dialog, for example {@code gluBattlenetChannelListPopup}
     * @param paletteFolder the palette folder of the chat screen
     * @param locale        the install locale of the texts, for example {@code koKR}
     * @param buttons       the buttons of the bottom row, in order
     */
    BeaconLinkPopup(@NotNull String dialog, @NotNull String paletteFolder, @NotNull String locale, @NotNull List<Button> buttons) {
        super(dialog, paletteFolder, null);

        this.locale  = locale;
        this.rows    = new WebRowList(ParticipantRowsUtils.ROW_HEIGHT);
        this.buttons = buttons;

        this.pageLeft   = 0;
        this.pageTop    = 0;
        this.pageWidth  = 0;
        this.listHeight = 0;
        this.input      = null;
    }

    /**
     * @return the install locale of the texts
     */
    final @NotNull String getLocale() {
        return this.locale;
    }

    /**
     * Clears the selected row.
     */
    final void clearSelection() {
        this.rows.setSelection(WebRowList.NO_SELECTION);
    }

    /**
     * @return the text typed into the input
     */
    final @NotNull String getInputText() {
        EditControl edit = this.input;

        return edit == null ? "" : edit.getEditText().getText();
    }

    /**
     * Sets the input's text; the input keeps the keyboard focus.
     *
     * @param text the text
     */
    final void setInputText(@NotNull String text) {
        this.input = this.setEditText(INPUT, text, this.getInputLength(), "");
    }

    /**
     * @return the page's title
     */
    abstract @NotNull String getTitle();

    /**
     * @return the hint the empty input shows, or an empty string for none
     */
    abstract @NotNull String getInputHint();

    /**
     * @return the note line between the list and the input
     */
    abstract @NotNull String getNote();

    /**
     * @return {@code true} if the note is a word between two lines, {@code false} if it is a hint
     */
    abstract boolean isNoteSeparator();

    /**
     * @return the most characters the input takes
     */
    abstract int getInputLength();

    /**
     * @return the number of list rows
     */
    abstract int getRowCount();

    /**
     * Draws a list row.
     *
     * @param canvas   the glue canvas
     * @param row      the row index
     * @param selected whether the row is the selected one
     * @param left     the row's left edge in interface pixels
     * @param top      the row's top edge in interface pixels
     * @param width    the row's width
     * @throws IOException if an image cannot be read
     */
    abstract void drawRow(@NotNull GlueCanvas canvas, int row, boolean selected, float left, float top, float width) throws IOException;

    /**
     * Called after a press selected a row of the list or the space below its rows.
     *
     * @param row         the selected row, or {@link WebRowList#NO_SELECTION}
     * @param doublePress whether the press is a double press
     */
    abstract void onRowPressed(int row, boolean doublePress);

    /**
     * Updates the buttons' states after the selection or the input changed.
     */
    abstract void updateButtons();


    /**
     * Loads the screen's dialog: {@code rez\<dialog name>.ui.json}, or an empty dialog without a name. A screen whose
     * native content is not a control list builds its dialog here.
     * <p>
     * Keeps the dialog's controls and adds the title, the list's scroll bar, the note, the input and the buttons on the page.
     *
     * @param glueContext the shared services
     * @return the dialog
     * @throws IOException if the dialog cannot be read or has no page
     */
    @Override
    protected final @NotNull DialogDefinition loadDialog(@NotNull GlueContext glueContext) throws IOException {
        DialogDefinition file = super.loadDialog(glueContext);
        DialogControl page = ScrDialogControlUtils.findControl(file, PAGE);

        if (page == null) {
            throw Exceptions.newIOException("{0} has no page.", file.getName());
        }

        int innerLeft = page.getX() + PADDING;
        int innerWidth = page.getWidth() - SIDE_COUNT * PADDING;
        int buttonWidth = getButtonWidth(this.buttons, innerWidth);
        int buttonGap = Math.round(innerWidth * BUTTON_GAP_SHARE);
        int buttonRowTop = page.getY() + page.getHeight() - PADDING - FIELD_HEIGHT;
        int inputRowTop = buttonRowTop - ROW_GAP - FIELD_HEIGHT;
        List<DialogControl> controls = new ArrayList<>(file.getControls());

        this.pageLeft   = page.getX();
        this.pageTop    = page.getY();
        this.pageWidth  = page.getWidth();
        this.listHeight = inputRowTop - SIDE_COUNT * NOTE_GAP - NOTE_HEIGHT - page.getY() - LIST_TOP;

        controls.add(ScrDialogControlUtils.createControl(ScrControlType.CTEXT,
                                                         TITLE,
                                                         innerLeft, page.getY() + TITLE_TOP,
                                                         innerWidth, TITLE_HEIGHT,
                                                         Set.of(VISIBLE_FLAG, BIG_TEXT_FLAG),
                                                         "",
                                                         List.of()));
        controls.add(WebScrollBar.createDefinition(LIST_SCROLL, page));
        controls.add(ScrDialogControlUtils.createControl(this.isNoteSeparator() ? ScrControlType.CTEXT : ScrControlType.LTEXT,
                                                         NOTE,
                                                         innerLeft, page.getY() + LIST_TOP + this.listHeight + NOTE_GAP,
                                                         innerWidth, NOTE_HEIGHT,
                                                         Set.of(VISIBLE_FLAG, SMALL_TEXT_FLAG),
                                                         "",
                                                         List.of()));
        controls.add(ScrDialogControlUtils.createControl(ScrControlType.EDITTEXT,
                                                         INPUT,
                                                         innerLeft, inputRowTop,
                                                         innerWidth, FIELD_HEIGHT,
                                                         Set.of(VISIBLE_FLAG, TAB_STOP_FLAG, FRAMED_FLAG),
                                                         "",
                                                         List.of()));

        // The bottom row spans the page's width: the last button ends at its right edge.
        int buttonLeft = innerLeft;

        for (int index = 0; index < this.buttons.size(); index++) {
            int right = index == this.buttons.size() - 1 ? innerLeft + innerWidth : buttonLeft + buttonWidth;

            controls.add(createButton(this.buttons.get(index), buttonLeft, buttonRowTop, right - buttonLeft));
            buttonLeft += buttonWidth + buttonGap;
        }

        return new DialogDefinition(file.getName(), file.getX(), file.getY(), file.getWidth(), file.getHeight(), controls);
    }

    /**
     * Called after the dialog is loaded: hide, disable or fill controls here.
     * <p>
     * Names the buttons, shows the title and the note, prepares the list's scroll bar and gives the empty input the keyboard focus.
     *
     * @param glueContext the shared services
     */
    @Override
    protected void onOpen(@NotNull GlueContext glueContext) {
        for (Button button : this.buttons) {
            this.bindControl(button.id).setReplacedText(button.label);
        }

        this.bindControl(TITLE).setReplacedText(this.getTitle());
        this.bindControl(NOTE).setReplacedText(this.getNote());

        this.rows.setScrollBar(this.requireDialogState().attach(new WebScrollBar(this.bindControl(LIST_SCROLL).getDefinition())));
        this.setInputText("");
        this.updateButtons();
    }

    /**
     * Called after the text of an {@code EDITTEXT} changed.
     * <p>
     * Updates the buttons' states.
     *
     * @param control the control id
     */
    @Override
    protected void onEditChanged(int control) {
        this.updateButtons();
    }

    /**
     * Called first for every press of the screen that no open popup or open combo box takes, while the screen takes input.
     * <p>
     * Takes presses on the list's scroll bar, and selects the row under a press on the list.
     *
     * @param x the interface x
     * @param y the interface y
     * @return {@code true} if the press was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onPointerPressed(float x, float y) {
        if (this.rows.press(x, y)) {
            return true;
        }

        float listLeft = this.getDialogLeft() + this.pageLeft + PADDING + WebRowList.FRAME_PADDING;
        float listTop = this.getDialogTop() + this.pageTop + LIST_TOP + WebRowList.FRAME_PADDING;
        float listWidth = this.pageWidth - SIDE_COUNT * PADDING - SIDE_COUNT * WebRowList.FRAME_PADDING;
        float listHeight = this.listHeight - SIDE_COUNT * WebRowList.FRAME_PADDING;

        if (!GlueCanvas.contains(x, y, listLeft, listTop, listWidth, listHeight)) {
            return false;
        }

        this.rows.setSelection(this.rows.findRow(x, y, listLeft, listTop, listWidth, listHeight, this.getRowCount()));
        this.onRowPressed(this.rows.getSelection(), this.isDoublePress());
        this.updateButtons();

        return true;
    }

    /**
     * Called for every pointer move of the screen (after an open combo box or popup takes it).
     * <p>
     * Drags the list's scroll bar.
     *
     * @param x the interface x
     * @param y the interface y
     */
    @Override
    protected void onPointerMoved(float x, float y) {
        this.rows.drag(y);
    }

    /**
     * Called for every release of the left button of the screen.
     * <p>
     * Releases the list's scroll bar.
     *
     * @param x the interface x
     * @param y the interface y
     */
    @Override
    protected void onPointerReleased(float x, float y) {
        this.rows.release();
    }

    /**
     * Called first for a wheel notch of the screen.
     * <p>
     * Scrolls the list.
     *
     * @param x    the pointer's interface x
     * @param y    the pointer's interface y
     * @param down {@code true} for a notch toward the user
     * @return {@code true} if the notch was consumed, {@code false} otherwise
     */
    @Override
    protected boolean onWheel(float x, float y, boolean down) {
        this.rows.scrollByWheel(down);

        return true;
    }

    /**
     * Draws one control.
     * <p>
     * Draws the page (the list, and the separator's lines around the note's word); the other controls draw as on every screen.
     *
     * @param canvas  the glue canvas
     * @param control the control
     * @param left    its left edge in interface pixels
     * @param top     its top edge in interface pixels
     * @throws IOException if an image cannot be read
     */
    @Override
    protected void drawControl(@NotNull GlueCanvas canvas, @NotNull DialogControl control, float left, float top) throws IOException {
        if (control.getId() == PAGE) {
            this.drawPage(canvas, left, top);

            return;
        }

        super.drawControl(canvas, control, left, top);
    }

    private void drawPage(@NotNull GlueCanvas canvas, float left, float top) throws IOException {
        float innerLeft = left + PADDING;
        float innerWidth = this.pageWidth - SIDE_COUNT * PADDING;
        float listTop = top + LIST_TOP;

        this.rows.drawFramed(canvas, innerLeft, listTop, innerWidth, this.listHeight, this.getRowCount(), this::drawRow);

        if (!this.isNoteSeparator()) {
            return;
        }

        // The note's word stands between two lines across the row.
        float wordWidth = canvas.measure(GlueCanvas.SMALL_FONT, this.getNote());
        float wordLeft = innerLeft + (innerWidth - wordWidth) / CENTER_DIVISOR;
        float lineTop = listTop + this.listHeight + NOTE_GAP + (float) Math.floor(NOTE_HEIGHT / (float) CENTER_DIVISOR);

        canvas.fillRectangle(innerLeft, lineTop, wordLeft - SEPARATOR_GAP - innerLeft, 1, WebPanelStyleUtils.TEXT);
        canvas.fillRectangle(wordLeft + wordWidth + SEPARATOR_GAP, lineTop, innerLeft + innerWidth - wordLeft - wordWidth - SEPARATOR_GAP, 1,
                             WebPanelStyleUtils.TEXT);
    }

    /**
     * Shows the popup's hint in the empty input; other fields have none.
     *
     * @param control an {@code EDITTEXT} control with {@link #FRAMED_FLAG}
     * @return the hint the text field shows in gray while it is empty, or an empty string for none
     */
    @Override
    protected @NotNull String getEditHint(@NotNull DialogControl control) {
        return control.getId() == INPUT ? this.getInputHint() : super.getEditHint(control);
    }
}
