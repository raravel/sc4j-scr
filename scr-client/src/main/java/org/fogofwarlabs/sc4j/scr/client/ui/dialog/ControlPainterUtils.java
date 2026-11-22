package org.fogofwarlabs.sc4j.scr.client.ui.dialog;

import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.client.ui.text.StatText;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrControlType;
import org.fogofwarlabs.sc4j.scr.data.resource.dialog.ScrDialogControlUtils;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceFonts;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Set;

/**
 * Draws shared push buttons, check boxes, radio buttons, text and edit controls through a {@link DialogPainter}. Controls with their own runtime
 * objects remain with their owners. Focus outlines are drawn for push buttons and fields.
 */
public final class ControlPainterUtils {
    /**
     * The push-button flag bit selecting the three-slice frame style.
     */
    public static final int THREE_SLICE_STYLE = 0x20000000;
    /**
     * The classic frame whose width supplies the push-button tile metric.
     */
    public static final int BUTTON_TILE_FRAME = 103;
    /**
     * Push button text: format {@code 0x22}, centered both ways.
     */
    public static final int BUTTON_FORMAT = InterfaceText.CENTER | InterfaceText.MIDDLE;
    /**
     * A line on a control row, vertically centered on the row's box by the font's line box, never by the text's own glyphs, so that lines of
     * any script and any letters stand on one baseline beside their mark: the drop-down's item text, a label beside a control in the row, a
     * radio button's and a check box's label, and a list row's text beside its icon.
     */
    public static final int ROW_TEXT_FORMAT = InterfaceText.LEFT | InterfaceText.MIDDLE | InterfaceText.NO_BASELINE_TWEAK;
    /**
     * The text layout centers a line in its rectangle less the rectangle's last row ({@link InterfaceText}); a mark beside a row's text (a radio
     * button's circle, a check box) is centered in the same space, so that the two share one center.
     */
    public static final float ROW_TEXT_BOTTOM_INSET = 1;
    /**
     * The {@code LTEXT} format {@code 0x91}.
     */
    public static final int LTEXT_FORMAT = InterfaceText.LEFT | InterfaceText.TOP | InterfaceText.NO_BASELINE_TWEAK;
    /**
     * The {@code CTEXT} format {@code 0x92}.
     */
    public static final int CTEXT_FORMAT = InterfaceText.CENTER | InterfaceText.TOP | InterfaceText.NO_BASELINE_TWEAK;
    /**
     * The {@code RTEXT} format {@code 0x94}.
     */
    public static final int RTEXT_FORMAT = InterfaceText.RIGHT | InterfaceText.TOP | InterfaceText.NO_BASELINE_TWEAK;
    /**
     * The outer ring of a list's border (palette byte, nearest RGB({@code 48, 48, 48})).
     */
    public static final int BORDER_OUTER = 0x303030FF;
    /**
     * The inner ring of an unfocused list's border (palette byte, nearest black).
     */
    public static final int BORDER_INNER = 0x000000FF;
    /**
     * The focus color (palette byte, nearest RGB({@code 0, 75, 227}): the HD palette's {@code 12}, {@code 72}, {@code 204}).
     */
    public static final int FOCUS = 0x0C48CCFF;

    /**
     * The slots of the composition clause of {@link InputMethod#getCompositionClause}: start, end and cursor.
     */
    private static final int CLAUSE_START_INDEX = 0;
    private static final int CLAUSE_END_INDEX = 1;
    private static final int CLAUSE_CURSOR_INDEX = 2;
    private static final int RING_GROWTH = 2;
    /**
     * 3-slice frame offsets from the style base: disabled, normal, pressed.
     */
    private static final int BAR_NORMAL = 3;
    private static final int BAR_PRESSED = 6;
    /**
     * Single-frame offsets from the style base: normal and pressed (disabled is the base).
     */
    private static final int FRAME_NORMAL = 1;
    private static final int FRAME_PRESSED = 2;
    /**
     * The single-frame text offset of a pressed button ({@code (flags >> 29) & 2}).
     */
    private static final int PRESSED_TEXT_SHIFT = 2;
    /**
     * The input-language marks of an edit control, indexed by the input language of {@link InputMethod#getInputLanguage}: {@code 1} Japanese,
     * {@code 2} Korean, {@code 3} and {@code 4} Chinese, {@code 6} Polish, {@code 7} Russian; every other language
     * ({@code 0, 5}) takes {@code "| EN"}.
     */
    private static final @NotNull String @NotNull [] INPUT_LANGUAGE_MARKS = {
            "| EN", "| \u65E5\u672C\u8A9E", "| \uAC00", "| \u4E2D", "| \u4E2D", "| EN", "| PL", "| RU"
    };
    /**
     * The fill of a text selection and of the composition's target clause (RGB {@code 0.5} at alpha {@code 0.3}).
     */
    private static final int COMPOSITION_FILL = 0x8080804D;
    /**
     * The caret glyph whose advance the edit room leaves free ({@code U+00A6}).
     */
    private static final @NotNull String EDIT_CARET_GLYPH = "\u00A6";
    /**
     * The gap between the input-language mark and the control's right edge.
     */
    private static final int INPUT_LANGUAGE_MARK_GAP = 5;
    /**
     * The text size flags of a control, from the smallest.
     */
    private static final @NotNull String SMALL_TEXT_FLAG = "RC_SMALLTEXT";
    private static final @NotNull String BIG_TEXT_FLAG = "RC_BIGTEXT";
    private static final @NotNull String BIGGER_TEXT_FLAG = "RC_BIGGERTEXT";

    /**
     * Draws the enabled, pressed or disabled button frame, followed by centered text. Three-slice buttons use a tiled bar; single-frame pressed
     * buttons shift their text by two pixels.
     *
     * @param painter    the painter
     * @param sheet      the dialog sheet
     * @param control    the control
     * @param left       the control's interface left
     * @param top        the control's interface top
     * @param text       the drawn string
     * @param base       the style base frame
     * @param threeSlice whether the 3-slice style is set ({@link #THREE_SLICE_STYLE})
     * @param enabled    whether the button is enabled
     * @param pressed    whether it is pressed
     * @param hovered    whether the pointer is over it
     * @throws IOException if the sheet cannot be read
     */
    public static void drawPushButton(@NotNull DialogPainter painter,
                                      @NotNull String sheet,
                                      @NotNull DialogControl control,
                                      float left, float top,
                                      @NotNull String text,
                                      int base,
                                      boolean threeSlice,
                                      boolean enabled,
                                      boolean pressed,
                                      boolean hovered) throws IOException {
        float shift = 0;

        if (threeSlice) {
            painter.drawBar(sheet,
                            base + (!enabled ? 0 : pressed ? BAR_PRESSED : BAR_NORMAL),
                            left,
                            top,
                            control.getWidth(),
                            painter.getMetricWidth(sheet, BUTTON_TILE_FRAME));
        } else {
            painter.drawFrame(painter.getSheetFrame(sheet, base + (!enabled ? 0 : pressed ? FRAME_PRESSED : FRAME_NORMAL)), left, top);

            shift = enabled && pressed ? PRESSED_TEXT_SHIFT : 0;
        }

        if (text.isEmpty()) {
            return;
        }

        int code = !enabled ? DialogPainter.CODE_DISABLED : hovered ? DialogPainter.CODE_HIGHLIGHT : DialogPainter.CODE_BUTTON;
        // A disabled button is gray throughout: the hotkey letter's inline color (code 4 in the strings) is dropped, as the
        // native popups draw it.
        int fontSlot = painter.getControlFontSlot(control);
        String shownText = enabled ? text : StatText.stripColors(text);

        painter.drawCodeText(fontSlot, shownText, left + shift, top + shift, control.getWidth(), control.getHeight(), BUTTON_FORMAT, code);
    }

    /**
     * Draws a text control as its type procedure: format {@code 0x91} ({@code LTEXT}), {@code 0x92} ({@code CTEXT}) or {@code 0x94} ({@code RTEXT})
     * over the control rectangle, only for a nonempty text; color code {@code 5} when disabled, else the given code.
     *
     * @param painter the painter
     * @param control the control
     * @param left    the control's interface left
     * @param top     the control's interface top
     * @param text    the drawn string
     * @param enabled whether the control is enabled
     * @param code    the color code of an enabled control ({@code 2} by default)
     */
    public static void drawText(@NotNull DialogPainter painter,
                                @NotNull DialogControl control,
                                float left, float top,
                                @NotNull String text,
                                boolean enabled,
                                int code) {
        drawText(painter, control, left, top, text, enabled, code, getTextFormat(control));
    }

    /**
     * Draws a text control with a format set by its owner instead of the type's (a screen that overrides the format of one control),
     * otherwise as {@link #drawText(DialogPainter, DialogControl, float, float, String, boolean, int)}.
     *
     * @param painter the painter
     * @param control the control
     * @param left    the control's interface left
     * @param top     the control's interface top
     * @param text    the drawn string
     * @param enabled whether the control is enabled
     * @param code    the color code of an enabled control
     * @param format  the {@link InterfaceText} format
     */
    public static void drawText(@NotNull DialogPainter painter,
                                @NotNull DialogControl control,
                                float left, float top,
                                @NotNull String text,
                                boolean enabled,
                                int code,
                                int format) {
        if (text.isEmpty()) {
            return;
        }

        painter.drawCodeText(painter.getControlFontSlot(control),
                             text,
                             left, top,
                             control.getWidth(), control.getHeight(),
                             format,
                             enabled ? code : DialogPainter.CODE_DISABLED);
    }

    /**
     * Draws the input-language mark and visible edit text, then fills the selection and IME target clause. The scroll room reserves the caret glyph
     * and language mark. A nonzero explicit color uses the web-panel text style.
     *
     * @param painter     the painter
     * @param fontSlot    the control's font slot
     * @param edit        the edit text
     * @param mode        the scroll mode of the loaded resource set
     * @param left        the padded text left
     * @param top         the padded text top
     * @param right       the control's inclusive right edge less its pad
     * @param height      the control height
     * @param screenRight the inclusive right edge of the screen (the mark's clip)
     * @param focused     whether the control has the keyboard focus
     * @param code        the color code
     */
    public static void drawEditText(@NotNull DialogPainter painter,
                                    int fontSlot,
                                    @NotNull EditText edit,
                                    @NotNull EditText.ScrollMode mode,
                                    float left, float top,
                                    float right, float height,
                                    float screenRight,
                                    boolean focused,
                                    int code) {
        drawEditText(painter, fontSlot, edit, mode, left, top, right, height, screenRight, focused, code, 0);
    }

    /**
     * Draws the input-language mark and visible edit text, then fills the selection and IME target clause. The scroll room reserves the caret glyph
     * and language mark. A nonzero explicit color uses the web-panel text style.
     *
     * @param painter     the painter
     * @param fontSlot    the control's font slot
     * @param edit        the edit text
     * @param mode        the scroll mode of the loaded resource set
     * @param left        the text left
     * @param top         the text top
     * @param right       the control's inclusive right edge less its pad
     * @param height      the control height
     * @param screenRight the inclusive right edge of the screen (the mark's clip)
     * @param focused     whether the control has the keyboard focus
     * @param code        the color code
     * @param color       the packed {@code 0xRRGGBBAA} color, or {@code 0} for the code
     */
    public static void drawEditText(@NotNull DialogPainter painter,
                                    int fontSlot,
                                    @NotNull EditText edit,
                                    @NotNull EditText.ScrollMode mode,
                                    float left, float top,
                                    float right, float height,
                                    float screenRight,
                                    boolean focused,
                                    int code,
                                    int color) {
        // A page's text field lays its text out in one line box of its family (InterfaceFonts.toInputLineBox): Latin and
        // Hangul text, the mark and the composition share one baseline.
        int slot = InterfaceFonts.toInputLineBox(fontSlot);
        String mark = getInputLanguageMark(InputMethod.getInputLanguage());
        float markWidth = painter.measure(slot, mark);
        float markLeft = right - INPUT_LANGUAGE_MARK_GAP - markWidth;

        if (color == 0) {
            painter.drawCodeText(slot, mark, markLeft, top, screenRight - markLeft + 1, height, InterfaceText.LEFT | InterfaceText.TOP, code);
        } else {
            WebPanelStyleUtils.drawText(painter, slot, mark, markLeft, top, screenRight - markLeft + 1, height,
                                        InterfaceText.LEFT | InterfaceText.TOP, color);
        }

        float room = right - left + 1 - painter.measure(slot, EDIT_CARET_GLYPH) - markWidth;

        edit.updateScroll(mode, room, character -> painter.measure(slot, character));
        edit.setTextLeft(left);

        String text = edit.getDrawnText(focused);

        if (!text.isEmpty()) {
            if (color == 0) {
                painter.drawCodeText(slot, text, left, top, right - left + 1, height,
                                     InterfaceText.LEFT | InterfaceText.TOP | InterfaceText.CHARACTER_WRAP, code);
            } else {
                WebPanelStyleUtils.drawText(painter, slot, text, left, top, right - left + 1, height,
                                            InterfaceText.LEFT | InterfaceText.TOP | InterfaceText.CHARACTER_WRAP, color);
            }
        }

        String visible = edit.getText().substring(Math.min(edit.getScroll(), edit.getText().length()));

        // The selection of the edit operations (TextEditOperation) is marked as the composition clause is: one fill rule for
        // every text input.
        if (focused) {
            fillTextRange(painter,
                          slot,
                          visible,
                          edit.getSelectionStart() - edit.getScroll(),
                          edit.getSelectionEnd() - edit.getScroll(),
                          left,
                          top);
        }

        int[] clause = InputMethod.getCompositionClause();

        if (clause == null) {
            return;
        }

        // The composition starts at the caret less the IME cursor; the clause is filled from the floor of the width before it
        // over the ceiling of its own width (the caret glyph advances nothing here), the .fnt maximum height tall.
        int compositionStart = edit.getCaret() - edit.getScroll() - clause[CLAUSE_CURSOR_INDEX];

        fillTextRange(painter,
                      slot,
                      visible,
                      compositionStart + clause[CLAUSE_START_INDEX],
                      compositionStart + clause[CLAUSE_END_INDEX],
                      left,
                      top);
    }

    /**
     * Fills the selected characters {@code [start, end)} of a drawn line, clamped to it: from the floor of the width before them over the
     * ceiling of their own width, one line of the font tall. The text inputs and the read-only logs mark their selection with this one fill.
     *
     * @param painter the painter
     * @param slot    the line's font slot
     * @param line    the drawn line
     * @param start   the first selected UTF-16 index of the line
     * @param end     the UTF-16 index after the selected characters
     * @param left    the line's left edge
     * @param top     the line's top edge
     */
    public static void fillTextRange(@NotNull DialogPainter painter, int slot, @NotNull String line, int start, int end, float left, float top) {
        int from = Math.clamp(start, 0, line.length());
        int to = Math.clamp(end, from, line.length());

        if (to <= from) {
            return;
        }

        float x = left + (float) Math.floor(painter.measure(slot, line.substring(0, from)));
        float width = (float) Math.ceil(painter.measure(slot, line.substring(from, to)));

        painter.fillRectangle(x, top, width, painter.getLineHeight(slot) - InterfaceText.LINE_GAP, COMPOSITION_FILL);
    }

    /**
     * @param language an input language of {@link InputMethod#getInputLanguage}
     * @return its mark
     */
    public static @NotNull String getInputLanguageMark(int language) {
        return language >= 0 && language < INPUT_LANGUAGE_MARKS.length ? INPUT_LANGUAGE_MARKS[language] : INPUT_LANGUAGE_MARKS[0];
    }

    /**
     * @param control a text control
     * @return the format its type procedure sets: {@code 0x92} {@code CTEXT}, {@code 0x94} {@code RTEXT}, else {@code 0x91} ({@code LTEXT})
     */
    public static int getTextFormat(@NotNull DialogControl control) {
        return switch (ScrDialogControlUtils.getType(control)) {
            case CTEXT -> CTEXT_FORMAT;
            case RTEXT -> RTEXT_FORMAT;
            default -> LTEXT_FORMAT;
        };
    }

    /**
     * Selects the font slot of a control from its text size flags, giving small text priority over the other size flags.
     *
     * @param control the control
     * @return the {@link InterfaceFonts} slot
     */
    public static int getFontSlot(@NotNull DialogControl control) {
        return InterfaceFonts.getSlot(ScrDialogControlUtils.hasFlag(control, SMALL_TEXT_FLAG), ScrDialogControlUtils.hasFlag(control, BIG_TEXT_FLAG),
                                      ScrDialogControlUtils.hasFlag(control, BIGGER_TEXT_FLAG));
    }

    /**
     * Selects the font slot of a set of control flag names, giving small text priority over the other size flags.
     *
     * @param flags the control flag names
     * @return the {@link InterfaceFonts} slot
     */
    public static int getFontSlot(@NotNull Set<@NotNull String> flags) {
        return InterfaceFonts.getSlot(flags.contains(SMALL_TEXT_FLAG), flags.contains(BIG_TEXT_FLAG), flags.contains(BIGGER_TEXT_FLAG));
    }

    /**
     * Combines the horizontal and vertical {@code FB_*} alignment flags, choosing the first matching flag on each axis.
     *
     * @param control the control
     * @return the {@link InterfaceText} format
     */
    public static int getButtonFormat(@NotNull DialogControl control) {
        int format = 0;

        if (ScrDialogControlUtils.hasFlag(control, "FB_HTEXTLEFT")) {
            format = InterfaceText.LEFT;
        } else if (ScrDialogControlUtils.hasFlag(control, "FB_HTEXTCENTER")) {
            format = InterfaceText.CENTER;
        } else if (ScrDialogControlUtils.hasFlag(control, "FB_HTEXTRIGHT")) {
            format = InterfaceText.RIGHT;
        }

        if (ScrDialogControlUtils.hasFlag(control, "FB_VTEXTTOP")) {
            format |= InterfaceText.TOP;
        } else if (ScrDialogControlUtils.hasFlag(control, "FB_VTEXTCENTER")) {
            format |= InterfaceText.MIDDLE;
        } else if (ScrDialogControlUtils.hasFlag(control, "FB_VTEXTBOTTOM")) {
            format |= InterfaceText.BOTTOM;
        }

        return format;
    }

    /**
     * Draws an animated-button label in its {@code TEXTBOX} child rectangle, or the control rectangle when no child exists. Disabled labels discard
     * inline colors; enabled labels blend the button and highlight codes by the clamped hover level.
     *
     * @param painter the painter
     * @param control the control
     * @param left    the control's interface left
     * @param top     the control's interface top
     * @param text    the drawn string
     * @param enabled whether the button is enabled
     * @param level   the hover level from {@code 0} through {@code 1}
     */
    public static void drawFlicButtonLabel(@NotNull DialogPainter painter,
                                           @NotNull DialogControl control,
                                           float left, float top,
                                           @NotNull String text,
                                           boolean enabled,
                                           float level) {
        if (text.isEmpty()) {
            return;
        }

        DialogControl textBox = ScrDialogControlUtils.findChild(control, ScrControlType.TEXTBOX);
        float textLeft = textBox == null ? left : left + textBox.getX();
        float textTop = textBox == null ? top : top + textBox.getY();
        float width = textBox == null ? control.getWidth() : textBox.getWidth();
        float height = textBox == null ? control.getHeight() : textBox.getHeight();
        int slot = painter.getControlFontSlot(control);
        int format = getButtonFormat(control);

        // A disabled label is gray throughout, its hotkey character too (the native screens show no colored hotkey on a
        // disabled button): the inline color codes are left out.
        if (!enabled) {
            painter.drawCodeText(slot, StatText.stripColors(text), textLeft, textTop, width, height, format, DialogPainter.CODE_DISABLED);
        } else {
            painter.drawBlendedCodeText(slot,
                                        text,
                                        textLeft, textTop,
                                        width, height,
                                        format,
                                        DialogPainter.CODE_BUTTON, DialogPainter.CODE_HIGHLIGHT,
                                        Math.clamp(level, 0f, 1f));
        }
    }

    /**
     * Draws a one-pixel outer border, followed by the inner black border. The focused outer border uses the focus color.
     *
     * @param painter the painter
     * @param left    the control's interface left
     * @param top     the control's interface top
     * @param width   the control width
     * @param height  the control height
     * @param focused whether the field has the keyboard focus
     */
    public static void drawFieldBorder(@NotNull DialogPainter painter, float left, float top, float width, float height, boolean focused) {
        drawRing(painter, left - 1, top - 1, width + RING_GROWTH, height + RING_GROWTH, focused ? FOCUS : BORDER_OUTER);
        drawRing(painter, left, top, width, height, BORDER_INNER);
    }

    private static void drawRing(@NotNull DialogPainter painter, float left, float top, float width, float height, int color) {
        painter.fillRectangle(left, top, width, 1, color);
        painter.fillRectangle(left, top + height - 1, width, 1, color);
        painter.fillRectangle(left, top + 1, 1, height - RING_GROWTH, color);
        painter.fillRectangle(left + width - 1, top + 1, 1, height - RING_GROWTH, color);
    }

    /**
     * Draws a control of a dialog by its type as the draw table does, for the types this class owns: push buttons, radio buttons, check boxes and
     * text controls. Hidden controls are skipped.
     *
     * @param painter    the painter
     * @param dialog     the open dialog (visibility, enabled, pressed, checked)
     * @param control    the control
     * @param left       the control's interface left
     * @param top        the control's interface top
     * @param sheet      the dialog sheet
     * @param text       the drawn string ({@link ScrDialog#getDrawnString})
     * @param hovered    the hovered control id, or {@link ScrDialog#NONE}
     * @param buttonBase the push button style base frame
     * @param threeSlice whether push buttons use the 3-slice style
     * @return {@code true} if the type is drawn here, {@code false} otherwise (the other types are left to the caller)
     * @throws IOException if the sheet cannot be read
     */
    public static boolean draw(@NotNull DialogPainter painter,
                               @NotNull ScrDialog dialog,
                               @NotNull DialogControl control,
                               float left, float top,
                               @NotNull String sheet,
                               @NotNull String text,
                               int hovered,
                               int buttonBase,
                               boolean threeSlice) throws IOException {
        if (!dialog.isVisible(control)) {
            return true;
        }

        boolean enabled = dialog.isEnabled(control);
        boolean over = enabled && hovered == control.getId();
        boolean pressed = over && dialog.getPressedId() == control.getId();

        switch (ScrDialogControlUtils.getType(control)) {
            case PUSHBUTTON, DEFPUSHBUTTON -> {
                drawPushButton(painter, sheet, control, left, top, text, buttonBase, threeSlice, enabled, pressed, over);

                // The focused button's outline around its rectangle in the focus color, from one pixel left of
                // and above the rectangle to its inclusive right and bottom edges (as measured on the native game menu).
                if (dialog.isButtonFocused(control)) {
                    drawRing(painter, left - 1, top - 1, control.getWidth() + 1, control.getHeight() + 1, FOCUS);
                }
            }
            case CHECKBOX, RADIOBUTTON -> CheckBoxUtils.draw(painter,
                                                             sheet,
                                                             control,
                                                             left, top,
                                                             text,
                                                             dialog.requireControl(control.getId()).isChecked(),
                                                             enabled,
                                                             pressed,
                                                             over);
            case LTEXT, CTEXT, RTEXT -> {
                // A procedure's run-time format or text size (ScrDialog.setTextFormat / setFontSlot) replaces the type's.
                Integer format = dialog.requireControl(control.getId()).getTextFormat();
                Integer slot = dialog.requireControl(control.getId()).getFontSlot();

                if (!text.isEmpty()) {
                    painter.drawCodeText(slot == null ? painter.getControlFontSlot(control) : slot,
                                         text,
                                         left, top,
                                         control.getWidth(), control.getHeight(),
                                         format == null ? getTextFormat(control) : format,
                                         enabled ? DialogPainter.CODE_TEXT : DialogPainter.CODE_DISABLED);
                }
            }
            default -> {
                return false;
            }
        }

        return true;
    }


    private ControlPainterUtils() {
    }
}
