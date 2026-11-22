package org.fogofwarlabs.sc4j.scr.client.ui.dialog;

import org.fogofwarlabs.sc4j.engine.resource.dialog.DialogControl;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * A dialog painter for control tests: it records the drawn texts, color codes and colors, gives every sheet frame the same
 * fixed size and measures text with a fixed character advance.
 */
final class RecordingDialogPainter implements DialogPainter {
    /**
     * The height of every sheet frame, the height of a list or combo box bar.
     */
    static final float BAR_HEIGHT = 16;
    /**
     * The line height of every font slot.
     */
    static final float LINE_HEIGHT = 15;
    private static final float FRAME_WIDTH = 8;
    private static final int CHARACTER_ADVANCE = 6;
    private static final int FONT_SLOT = 0;

    private final @NotNull List<String> texts;
    private final @NotNull List<Float> widths;
    private final @NotNull List<String> codeTexts;
    private final @NotNull List<Integer> codes;
    private final @NotNull List<Integer> colors;

    /**
     * Creates a {@link RecordingDialogPainter} instance that has recorded nothing.
     */
    RecordingDialogPainter() {
        this.texts     = new ArrayList<>();
        this.widths    = new ArrayList<>();
        this.codeTexts = new ArrayList<>();
        this.codes     = new ArrayList<>();
        this.colors    = new ArrayList<>();
    }

    /**
     * @return the texts of both text draws, in drawing order
     */
    @NotNull List<String> getTexts() {
        return this.texts;
    }

    /**
     * @return the rectangle widths of both text draws, in drawing order
     */
    @NotNull List<Float> getWidths() {
        return this.widths;
    }

    /**
     * @return the texts drawn in a color code, in drawing order
     */
    @NotNull List<String> getCodeTexts() {
        return this.codeTexts;
    }

    /**
     * @return the color codes of the code text draws, in drawing order
     */
    @NotNull List<Integer> getCodes() {
        return this.codes;
    }

    /**
     * @return the colors of the color text draws, in drawing order
     */
    @NotNull List<Integer> getColors() {
        return this.colors;
    }


    /**
     * The recording painter gives every sheet and index the same fixed-size frame.
     *
     * @param sheet the sheet path, for example {@code glue/palnl/dlg.dds.grp}
     * @param frame the frame index
     * @return the frame
     */
    @Override
    public @NotNull Frame getSheetFrame(@NotNull String sheet, int frame) {
        return new Frame() {
            /**
             * @return the width in interface pixels
             */
            @Override
            public float getWidth() {
                return FRAME_WIDTH;
            }

            /**
             * @return the height in interface pixels
             */
            @Override
            public float getHeight() {
                return BAR_HEIGHT;
            }
        };
    }

    /**
     * @param slot the font slot
     * @return the line height of the slot's font
     */
    @Override
    public float getLineHeight(int slot) {
        return LINE_HEIGHT;
    }

    /**
     * Measures the rendered text.
     * <p>
     * The recording painter measures with a fixed character advance.
     *
     * @param slot the font slot
     * @param text one line
     * @return its width in interface pixels
     */
    @Override
    public float measure(int slot, @NotNull String text) {
        return text.length() * CHARACTER_ADVANCE;
    }

    /**
     * Selects the font slot of a control.
     * <p>
     * The recording painter selects one fixed slot for every control.
     *
     * @param control a control
     * @return its font slot by its {@code RC_*TEXT} flags
     */
    @Override
    public int getControlFontSlot(@NotNull DialogControl control) {
        return FONT_SLOT;
    }

    /**
     * Draws a frame at its size, at the position plus the frame's offset of the sheet's classic {@code .grp} header, as the dialog frame draw does in
     * every asset set.
     * <p>
     * The recording painter draws nothing.
     *
     * @param frame     a frame of {@link #getSheetFrame}
     * @param positionX the interface left
     * @param positionY the interface top
     */
    @Override
    public void drawFrame(@NotNull Frame frame, float positionX, float positionY) {
    }

    /**
     * Draws a frame into a rectangle, moved by the frame's classic offset as {@link #drawFrame(Frame, float, float)}.
     * <p>
     * The recording painter draws nothing.
     *
     * @param frame     a frame of {@link #getSheetFrame}
     * @param positionX the interface left
     * @param positionY the interface top
     * @param width     the interface width
     * @param height    the interface height
     */
    @Override
    public void drawFrame(@NotNull Frame frame, float positionX, float positionY, float width, float height) {
    }

    /**
     * Draws a frame at its size exactly at a position, without the classic frame offset: the plain image draw that the list item painters use for
     * their icons (the file list painter places an {@code glue\create\iCreate} icon from the frame height alone).
     * <p>
     * The recording painter draws nothing.
     *
     * @param frame     a frame of {@link #getSheetFrame}
     * @param positionX the interface left
     * @param positionY the interface top
     */
    @Override
    public void drawImageFrame(@NotNull Frame frame, float positionX, float positionY) {
    }

    /**
     * Draws a frame at its classic offset, clipped to the half-open horizontal range from {@code clipLeft} to {@code clipRight}.
     * <p>
     * The recording painter draws nothing.
     *
     * @param frame     a frame of {@link #getSheetFrame}
     * @param positionX the interface left, before the offset
     * @param positionY the interface top, before the offset
     * @param clipLeft  the first visible column
     * @param clipRight the column after the last visible one
     */
    @Override
    public void drawFrameColumns(@NotNull Frame frame, float positionX, float positionY, float clipLeft, float clipRight) {
    }

    /**
     * Draws text through the shared layout in a color code of the current palette.
     * <p>
     * The recording painter records the text and the color code instead.
     *
     * @param slot   the font slot
     * @param text   the text
     * @param left   the rectangle left
     * @param top    the rectangle top
     * @param width  the rectangle width
     * @param height the rectangle height
     * @param format the {@link InterfaceText} format
     * @param code   the color code, for example {@link #CODE_BUTTON}
     */
    @Override
    public void drawCodeText(int slot, @NotNull String text, float left, float top, float width, float height, int format, int code) {
        this.texts.add(text);
        this.widths.add(width);
        this.codeTexts.add(text);
        this.codes.add(code);
    }

    /**
     * Draws text in a color of its own rather than a color code (the web panels' named colors, such as the lobby's color picker). Painters without
     * free colors draw it in the button code.
     * <p>
     * The recording painter records the text and the color instead.
     *
     * @param slot   the font slot
     * @param text   the text
     * @param left   the rectangle left
     * @param top    the rectangle top
     * @param width  the rectangle width
     * @param height the rectangle height
     * @param format the text format bits
     * @param color  the packed {@code 0xRRGGBBAA} color
     */
    @Override
    public void drawColorText(int slot, @NotNull String text, float left, float top, float width, float height, int format, int color) {
        this.texts.add(text);
        this.widths.add(width);
        this.colors.add(color);
    }

    /**
     * Fills an interface rectangle with a color.
     * <p>
     * The recording painter draws nothing.
     *
     * @param positionX the interface left
     * @param positionY the interface top
     * @param width     the interface width
     * @param height    the interface height
     * @param color     the packed {@code 0xRRGGBBAA} color
     */
    @Override
    public void fillRectangle(float positionX, float positionY, float width, float height, int color) {
    }
}
