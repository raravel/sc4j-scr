package org.fogofwarlabs.sc4j.scr.render.ui;

import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.text.Font;
import org.fogofwarlabs.sc4j.engine.client.text.TextStyle;
import org.fogofwarlabs.sc4j.scr.render.RenderAssets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Wraps, aligns and draws interface text with inline color and alignment codes. Interface coordinates are transformed to screen pixels; optional
 * clipping limits drawing to the text rectangle.
 */
public final class InterfaceText {
    /**
     * Format {@code 0x01}: left aligned.
     */
    public static final int LEFT = 0x01;
    /**
     * Format {@code 0x02}: centered horizontally.
     */
    public static final int CENTER = 0x02;
    /**
     * Format {@code 0x04}: right aligned.
     */
    public static final int RIGHT = 0x04;
    /**
     * Format {@code 0x08}: centered when the text lays out as one line, else left aligned (after the measuring pass the layout applies
     * {@code format ^= 9} for several lines and {@code format ^= 0xA} for one).
     */
    public static final int CENTER_ONE_LINE = 0x08;
    /**
     * Format {@code 0x10}: top aligned.
     */
    public static final int TOP = 0x10;
    /**
     * Format {@code 0x20}: centered vertically.
     */
    public static final int MIDDLE = 0x20;
    /**
     * Format {@code 0x40}: bottom aligned.
     */
    public static final int BOTTOM = 0x40;
    /**
     * Disables one-pixel kerning.
     */
    public static final int NO_KERNING = 0x10000;
    /**
     * Selects character wrapping instead of word wrapping.
     */
    public static final int CHARACTER_WRAP = 0x20000;
    /**
     * Format {@code 0x80}: no TrueType baseline tweak in the vertical pass (also set by the layout for multi-line texts).
     */
    public static final int NO_BASELINE_TWEAK = 0x80;
    /**
     * The line step beyond the text height ({@code line height = text height + 3}).
     */
    public static final int LINE_GAP = 3;
    /**
     * The colors of the game's text color codes ({@code tfontgam}, {@link InterfaceFont#getCodeColor}).
     */
    public static final int @NotNull [] GAME_CODE_COLORS = createGameCodeColors();

    /**
     * The formats that run the measuring pass.
     */
    private static final int MEASURED_FORMATS = CENTER_ONE_LINE | MIDDLE | BOTTOM;
    /**
     * Inline code {@code 0x12}: the rest of the line is right aligned.
     */
    private static final char RIGHT_ALIGN_CODE = 0x12;
    /**
     * Inline code {@code 0x13}: the rest of the line is centered.
     */
    private static final char CENTER_ALIGN_CODE = 0x13;
    /**
     * Inline code {@code 0x04} before the hotkey letter of a hotkey string ({@link #isHotkeyString}).
     */
    private static final char HOTKEY_COLOR_CODE = 0x04;
    /**
     * The layout control characters: tab, line feed, form feed, carriage return, substitute and space.
     */
    private static final char TAB = '\t';
    private static final char LINE_FEED = 0x0A;
    private static final char FORM_FEED = 0x0C;
    private static final char CARRIAGE_RETURN = 0x0D;
    private static final char SUBSTITUTE = 0x1A;
    private static final char SPACE = ' ';
    /**
     * The edit caret glyph ({@code U+00A6}).
     */
    private static final @NotNull String CARET_GLYPH = "¦";
    /**
     * The caret as the edit control inserts it: code {@code 0x04} followed by {@link #CARET_GLYPH}.
     */
    private static final @NotNull String CARET_MARK = "\u0004" + CARET_GLYPH;
    /**
     * The growth of the bounds words at the end of a layout call ({@link #measureBounds}).
     */
    private static final int BOUNDS_MARGIN = 2;
    /**
     * The left bounds word before the first line ({@link #measureBounds}).
     */
    private static final int BOUNDS_LEFT_START = Short.MAX_VALUE;

    private static @Nullable Probe probe;

    /**
     * Selects word or character boundaries for text wrapping.
     */
    public enum WrapMode {
        /**
         * Ends pieces between words.
         */
        WORD,
        /**
         * Ends pieces before the character that passes the right edge.
         */
        CHARACTER
    }

    /**
     * Receives every laid-out text (diagnostics, for the text-position harness).
     */
    public interface Probe {
        /**
         * Called when a text is laid out.
         *
         * @param text      the text
         * @param format    the format bits
         * @param top       the rectangle top
         * @param bottom    the inclusive rectangle bottom, or {@code top - 1} without a rectangle
         * @param inkTop    the ink top of the first line
         * @param inkBottom the ink bottom of the last line
         * @param trueType  whether the TrueType path drew the text
         */
        void onLaidOut(@NotNull String text, int format, float top, float bottom, float inkTop, float inkBottom, boolean trueType);
    }

    /**
     * Holds the bounds words a text layout leaves: left, top, right and bottom in interface pixels, already grown by the layout call
     * ({@link #measureBounds}).
     */
    public static final class Bounds {
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;

        private Bounds(int left, int top, int right, int bottom) {
            this.left   = left;
            this.top    = top;
            this.right  = right;
            this.bottom = bottom;
        }

        /**
         * @return the left word
         */
        public int getLeft() {
            return this.left;
        }

        /**
         * @return the top word
         */
        public int getTop() {
            return this.top;
        }

        /**
         * @return the right word
         */
        public int getRight() {
            return this.right;
        }

        /**
         * @return the bottom word
         */
        public int getBottom() {
            return this.bottom;
        }

        /**
         * @return {@code right - left}
         */
        public int getWidth() {
            return this.right - this.left;
        }

        /**
         * @return {@code bottom - top}
         */
        public int getHeight() {
            return this.bottom - this.top;
        }
    }

    /**
     * Sets the diagnostics probe.
     *
     * @param textProbe the probe, or {@code null}
     */
    public static void setProbe(@Nullable Probe textProbe) {
        probe = textProbe;
    }


    /**
     * Draws a text into a rectangle with a starting color code of the game table.
     *
     * @param renderer  the interface renderer between {@code begin} and {@code end}
     * @param transform the interface-to-screen transform
     * @param font      the font
     * @param text      the text; line and form feeds break lines
     * @param left      the rectangle left
     * @param top       the rectangle top
     * @param width     the rectangle width; {@code 0} or less disables wrapping
     * @param height    the rectangle height
     * @param format    the format bits
     * @param colorCode the starting color code
     * @param clip      whether drawing is limited to the rectangle
     */
    public static void draw(@NotNull InterfaceRenderer renderer, @NotNull InterfaceTransform transform,
                            @NotNull InterfaceFont font, @NotNull String text,
                            float left, float top, float width, float height,
                            int format,
                            int colorCode,
                            boolean clip) {
        drawColored(renderer, transform, font, text, left, top, width, height, format, InterfaceFont.getCodeColor(colorCode), GAME_CODE_COLORS, clip);
    }

    /**
     * Draws pre-broken lines from a pen position without wrapping or fitting, using the inclusive clip right.
     *
     * @param renderer  the interface renderer between {@code begin} and {@code end}
     * @param transform the interface-to-screen transform
     * @param font      the font
     * @param text      the text; line and form feeds break lines
     * @param penX      the pen x
     * @param penY      the pen y (first line top)
     * @param clipRight the inclusive clip right the inline alignment codes align against
     * @param format    the format bits
     * @param colorCode the starting color code
     */
    public static void drawUnwrapped(@NotNull InterfaceRenderer renderer, @NotNull InterfaceTransform transform,
                                     @NotNull InterfaceFont font, @NotNull String text,
                                     float penX, float penY, float clipRight,
                                     int format,
                                     int colorCode) {
        layout(renderer, transform, font, text, penX, penY, 0, 0, format, InterfaceFont.getCodeColor(colorCode), GAME_CODE_COLORS, false, clipRight);
    }

    /**
     * Draws a text into a rectangle with an explicit starting color and color table (glue screens load their own {@code tfont.pcx} table).
     *
     * @param renderer   the interface renderer between {@code begin} and {@code end}
     * @param transform  the interface-to-screen transform
     * @param font       the font
     * @param text       the text; line and form feeds break lines
     * @param left       the rectangle left
     * @param top        the rectangle top
     * @param width      the rectangle width; {@code 0} or less disables wrapping
     * @param height     the rectangle height
     * @param format     the format bits
     * @param color      the packed {@code 0xRRGGBBAA} starting color
     * @param codeColors the packed colors by code ({@code 32} entries)
     * @param clip       whether drawing is limited to the rectangle
     */
    public static void drawColored(@NotNull InterfaceRenderer renderer, @NotNull InterfaceTransform transform,
                                   @NotNull InterfaceFont font, @NotNull String text,
                                   float left, float top, float width, float height,
                                   int format,
                                   int color, int @NotNull [] codeColors,
                                   boolean clip) {
        // Without a rectangle (width 0) the caller gave no clip right, so the inline alignment has nothing to align against.
        layout(renderer, transform, font, text, left, top, width, height, format, color, codeColors, clip, width > 0 ? left + width - 1 : Float.NaN);
    }

    /**
     * Draws one line from a pen position against the caller's inclusive clip right, without setting a clip.
     *
     * @param renderer  the interface renderer between {@code begin} and {@code end}
     * @param transform the interface-to-screen transform
     * @param font      the font
     * @param text      the line
     * @param x         the pen x
     * @param y         the pen y (line top)
     * @param right     the inclusive clip right of the caller
     * @param colorCode the starting color code
     */
    public static void drawAt(@NotNull InterfaceRenderer renderer, @NotNull InterfaceTransform transform,
                              @NotNull InterfaceFont font, @NotNull String text,
                              float x, float y, float right,
                              int colorCode) {
        String line = text.substring(0, findDrawnLength(text));

        if (!line.isEmpty()) {
            TextStyle style = createStyle(transform, GAME_CODE_COLORS, true);

            drawLine(renderer, transform, font, line, x, y, right, InterfaceFont.getCodeColor(colorCode), style);
        }
    }

    /**
     * Draws a text from an anchor without wrapping or fitting: the lines, broken at line and form feeds, go down from {@code y}, each placed on
     * {@code x} by the horizontal alignment of the format ({@link #LEFT} starts at {@code x}, {@link #CENTER} centers on it, {@link #RIGHT} ends
     * at it). Inline alignment codes do not move the pen; the color carries across the lines as in {@link #draw}.
     *
     * @param canvas     the interface renderer between {@code begin} and {@code end}
     * @param transform  the interface-to-screen transform
     * @param font       the font
     * @param text       the text
     * @param x          the x the lines align on
     * @param y          the top of the first line
     * @param format     the format bits: {@link #LEFT}, {@link #CENTER} or {@link #RIGHT}, and {@link #NO_KERNING}
     * @param color      the packed {@code 0xRRGGBBAA} starting color
     * @param codeColors the packed colors by code ({@code 32} entries)
     */
    public static void drawAligned(@NotNull Canvas canvas, @NotNull InterfaceTransform transform,
                                   @NotNull InterfaceFont font, @NotNull String text,
                                   float x, float y,
                                   int format,
                                   int color, int @NotNull [] codeColors) {
        if (text.isEmpty()) {
            return;
        }

        boolean kerning = (format & NO_KERNING) == 0;
        TextStyle style = createStyle(transform, codeColors, kerning);

        drawLines(canvas, transform, font, breakLines(font, text, kerning), x, x, y, format, Float.NaN, color, style);
    }

    /**
     * Measures a text as {@link #drawAligned} draws it: the width of its widest line.
     *
     * @param font   the font
     * @param text   the text
     * @param format the format bits; {@link #NO_KERNING} drops the kerning
     * @return the width in interface pixels
     */
    public static float measureAligned(@NotNull InterfaceFont font, @NotNull String text, int format) {
        boolean kerning = (format & NO_KERNING) == 0;
        float width = 0;

        for (String line : breakLines(font, text, kerning)) {
            width = Math.max(width, measureWithoutCaretGlyph(font, line, kerning));
        }

        return width;
    }

    /**
     * Breaks a text into lines at line and form feeds only; a form feed or a drawing-stop control byte ends the text.
     *
     * @param font    the font
     * @param text    the text
     * @param kerning whether the one-pixel kerning is added
     * @return the lines
     */
    private static @NotNull List<String> breakLines(@NotNull InterfaceFont font, @NotNull String text, boolean kerning) {
        return truncateLines(text, wrap(font, text, 0, kerning, WrapMode.WORD), 0);
    }

    private static void layout(@NotNull InterfaceRenderer renderer, @NotNull InterfaceTransform transform,
                               @NotNull InterfaceFont font, @NotNull String text,
                               float left, float top, float width, float height,
                               int format,
                               int color, int @NotNull [] codeColors,
                               boolean clip,
                               float alignmentRight) {
        if (text.isEmpty()) {
            return;
        }

        boolean kerning = (format & NO_KERNING) == 0;
        TextStyle style = createStyle(transform, codeColors, kerning);
        float right = left + width - 1;
        // The wrap limit is the clip width right - left (the wrap breaks when floor(pen) passes the right edge).
        List<String> lines = truncateLines(text,
                                           wrap(font,
                                                text,
                                                width > 0 ? right - left : 0,
                                                kerning,
                                                (format & CHARACTER_WRAP) != 0 ? WrapMode.CHARACTER : WrapMode.WORD),
                                           height > 0 ? (int) height : 0);
        float bottom = top + height - 1;

        if (height > 0) {
            // Fit: a line is laid out only while its top plus the .fnt maximum height stays within the clip bottom.
            int fitting = 0;
            float lineTop = top;

            while (fitting < lines.size() && lineTop + font.getMaximumHeight() <= bottom) {
                lineTop += font.getTextHeight(lines.get(fitting)) + LINE_GAP;
                fitting++;
            }

            if (fitting == 0) {
                return;
            }

            lines = lines.subList(0, fitting);
        }

        float y = top;

        if ((format & MEASURED_FORMATS) != 0 && (format & TOP) == 0 && height > 0) {
            y = placeVertically(font, lines, format, top, bottom);
        }

        if ((format & CENTER_ONE_LINE) != 0) {
            format ^= lines.size() > 1 ? CENTER_ONE_LINE | LEFT : CENTER_ONE_LINE | CENTER;
        }

        if (clip) {
            int screenLeft = (int) transform.toScreenX(left);
            int screenTop = (int) transform.toScreenY(top);

            renderer.setClip(screenLeft,
                             screenTop,
                             (int) Math.ceil(transform.toScreenX(left + width)) - screenLeft,
                             (int) Math.ceil(transform.toScreenY(top + height)) - screenTop);
        }

        Probe listener = probe;

        if (listener != null) {
            float[] first = font.getInkBounds(lines.getFirst());
            float[] last = font.getInkBounds(lines.getLast());
            float lastTop = y;

            for (int index = 0; index < lines.size() - 1; index++) {
                lastTop += font.getTextHeight(lines.get(index)) + LINE_GAP;
            }

            if (first != null && last != null) {
                listener.onLaidOut(text, format, top, bottom, y + first[0], lastTop + last[1], font.isTrueType(lines.getFirst()));
            }
        }

        drawLines(renderer, transform, font, lines, left, right, y, format, alignmentRight, color, style);

        if (clip) {
            renderer.setClip(0, 0, -1, -1);
        }
    }

    /**
     * Draws laid-out lines down from {@code top}, each placed by the horizontal alignment of the format between {@code left} and {@code right}.
     * The colour carries across the lines ({@link #carryColorCodes}).
     *
     * @param canvas         the interface renderer between {@code begin} and {@code end}
     * @param transform      the interface-to-screen transform
     * @param font           the font
     * @param lines          the laid-out lines
     * @param left           the left of the lines
     * @param right          the right of the lines
     * @param top            the top of the first line
     * @param format         the format bits
     * @param alignmentRight the inclusive clip right the inline alignment codes align against, or {@code NaN} for none
     * @param color          the packed {@code 0xRRGGBBAA} starting color
     * @param style          the style of the layout ({@link #createStyle})
     */
    private static void drawLines(@NotNull Canvas canvas, @NotNull InterfaceTransform transform,
                                  @NotNull InterfaceFont font, @NotNull List<String> lines,
                                  float left, float right, float top,
                                  int format,
                                  float alignmentRight,
                                  int color, @NotNull TextStyle style) {
        boolean kerning = style.isKerning();
        List<String> drawnLines = carryColorCodes(lines);
        float y = top;

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            float x = left;

            if ((format & CENTER) != 0) {
                x = left + (right - left) / RenderAssets.HALF_DIVISOR - measureWithoutCaretGlyph(font, line, kerning) / RenderAssets.HALF_DIVISOR;
            } else if ((format & RIGHT) != 0) {
                x = right - measureWithoutCaretGlyph(font, line, kerning);
            }

            drawLine(canvas, transform, font, drawnLines.get(index), x, y, alignmentRight, color, style);
            y += font.getTextHeight(line) + LINE_GAP;
        }
    }

    /**
     * Returns lines as they are drawn: each line starts with the color codes of the lines before it, so that the pen color of the end of a line
     * is the color of the start of the next one and code {@code 1} switches back across line breaks.
     *
     * @param lines the laid-out lines
     * @return the lines with the color codes of the lines before them
     */
    static @NotNull List<String> carryColorCodes(@NotNull List<String> lines) {
        List<String> drawnLines = new ArrayList<>(lines.size());
        StringBuilder colorCodes = new StringBuilder();

        for (String line : lines) {
            drawnLines.add(colorCodes.isEmpty() ? line : colorCodes + line);

            for (int index = 0; index < line.length(); index++) {
                if (InterfaceFont.isColorCode(line.charAt(index))) {
                    colorCodes.append(line.charAt(index));
                }
            }
        }

        return drawnLines;
    }

    /**
     * Creates the engine text style of a layout: the code table as an {@link InterfaceCodeColors} hook, the kerning switch and the transform's screen
     * pixels per interface pixel as the scale, so that each segment is drawn through the engine {@link Font} with its pen in screen pixels.
     *
     * @param transform  the interface-to-screen transform
     * @param codeColors the packed colors by code
     * @param kerning    whether the one-pixel kerning is added
     * @return the created {@link TextStyle} instance
     */
    private static @NotNull TextStyle createStyle(@NotNull InterfaceTransform transform, int @NotNull [] codeColors, boolean kerning) {
        return TextStyle.builder().setColors(new InterfaceCodeColors(codeColors)).setKerning(kerning).setScale(transform.getScale()).build();
    }

    /**
     * Draws one line with inline alignment jumps and persistent color codes. The caret does not advance the pen and is drawn after the line at its
     * saved position. Each segment is drawn through the engine {@link Font} form in the layout's style, with its pen in screen pixels.
     *
     * @param canvas    the interface renderer between {@code begin} and {@code end}
     * @param transform the interface-to-screen transform
     * @param font      the font
     * @param line      the line
     * @param x         the pen x
     * @param y         the pen y (line top)
     * @param right     the inclusive clip right the inline alignment codes align against, or {@code NaN} for none (no jumps)
     * @param color     the packed {@code 0xRRGGBBAA} starting color
     * @param style     the style of the layout ({@link #createStyle})
     */
    private static void drawLine(@NotNull Canvas canvas, @NotNull InterfaceTransform transform,
                                 @NotNull InterfaceFont font, @NotNull String line,
                                 float x, float y, float right,
                                 int color, @NotNull TextStyle style) {
        boolean kerning = style.isKerning();
        int caret = line.indexOf(CARET_MARK);
        String drawn = removeCaretGlyph(line);
        float screenY = transform.toScreenY(y);
        int[] shifts = Float.isNaN(right) ? new int[0] : computeAlignmentShifts(drawn, x, right, text -> font.measure(text, kerning));
        StringBuilder colorCodes = new StringBuilder();
        float pen = x;
        float caretPen = x;
        int start = 0;
        int shift = 0;

        for (int index = 0; index <= drawn.length(); index++) {
            if (index < drawn.length() && !isAlignmentCode(drawn.charAt(index))) {
                continue;
            }

            String segment = drawn.substring(start, index);

            if (caret >= start && caret < index || caret == index && index == drawn.length()) {
                caretPen = pen + font.measure(segment.substring(0, Math.min(segment.length(), caret + 1 - start)), kerning);
            }

            if (!segment.isEmpty()) {
                font.draw(canvas, colorCodes + segment, transform.toScreenX(pen), screenY, color, style);
            }

            if (index == drawn.length()) {
                break;
            }

            pen += font.measure(segment, kerning) + (shift < shifts.length ? shifts[shift++] : 0);

            for (int code = 0; code < segment.length(); code++) {
                if (InterfaceFont.isColorCode(segment.charAt(code))) {
                    colorCodes.append(segment.charAt(code));
                }
            }

            start = index + 1;
        }

        if (caret >= 0) {
            // The color codes before the caret, its own code 0x04 included, give the caret glyph its color.
            StringBuilder caretCodes = new StringBuilder();

            for (int code = 0; code <= caret; code++) {
                if (InterfaceFont.isColorCode(drawn.charAt(code))) {
                    caretCodes.append(drawn.charAt(code));
                }
            }

            font.draw(canvas, caretCodes + CARET_GLYPH, transform.toScreenX(caretPen), screenY, color, style);
        }
    }

    /**
     * Places the first line using middle or bottom alignment and rounds the resulting position. A single TrueType line may receive the
     * half-minimum-glyph-top baseline adjustment.
     *
     * @param font   the font
     * @param lines  the laid-out lines
     * @param format the format bits
     * @param top    the rectangle top
     * @param bottom the inclusive rectangle bottom
     * @return the top of the first line
     */
    private static float placeVertically(@NotNull InterfaceFont font, @NotNull List<String> lines, int format, float top, float bottom) {
        float height = getLayoutHeight(font, lines, format);
        float space = bottom - top - 1;
        float y = (format & MIDDLE) != 0 ? top + (space - height) / RenderAssets.HALF_DIVISOR : top + space - height;
        boolean tweak = lines.size() == 1 && (format & NO_BASELINE_TWEAK) == 0;

        if (font.isTrueTypeMode() && tweak) {
            y -= font.getMinimumTop(lines.getFirst()) / RenderAssets.HALF_DIVISOR;
        }

        return Math.round(y);
    }


    /**
     * Returns the measured bottom of a single eligible TrueType line or the summed line heights and gaps.
     *
     * @param font   the font
     * @param lines  the laid-out lines
     * @param format the format bits
     * @return the height in interface pixels
     */
    public static float getLayoutHeight(@NotNull InterfaceFont font, @NotNull List<String> lines, int format) {
        if (lines.isEmpty()) {
            return 0;
        }

        if (lines.size() == 1 && (format & NO_BASELINE_TWEAK) == 0 && font.isTrueTypeMode() && font.isTrueType(lines.getFirst())) {
            int bottom = font.getMeasuredHeight(lines.getFirst());

            if (bottom > 0) {
                return bottom;
            }
        }

        float height = 0;

        for (String line : lines) {
            height += font.getTextHeight(line) + LINE_GAP;
        }

        return height - LINE_GAP;
    }

    /**
     * Measures laid-out lines as a left-aligned text layout call leaves its bounds words:
     * <ul>
     *   <li>the words start at {@code left = 0x7fff}, {@code right = 0}, {@code top = bottom = trunc(penY)};</li>
     *   <li>each line: {@code left = min(left, trunc(penX))}, {@code right = max(right, trunc(ceil(penX + width)))}, both moved by each jump of
     *       {@link #computeAlignmentShifts} against {@code clipRight}, {@code bottom = max(bottom, trunc(lineY + glyph bottom))}; a line followed by
     *       more text moves the pen by the line step ({@link InterfaceFont#getLineStep}) and adds {@code gap - 1} to the bottom word, which the end
     *       of the text takes back;</li>
     *   <li>at the end of the call every word grows by {@code 2} within the clip rectangle: {@code left = max(clipLeft, left - 2)},
     *       {@code top = max(clipTop, top - 2)}, {@code right = min(clipRight, right + 2)}, {@code bottom = min(clipBottom, bottom + 2)}.</li>
     * </ul>
     *
     * @param font       the font
     * @param lines      the laid-out lines
     * @param penX       the pen x (the clip left of the call)
     * @param penY       the pen y
     * @param gap        the line gap byte of the call
     * @param clipLeft   the clip left word
     * @param clipTop    the clip top word
     * @param clipRight  the clip right word
     * @param clipBottom the clip bottom word
     * @return the bounds
     */
    public static @NotNull Bounds measureBounds(@NotNull InterfaceFont font, @NotNull List<String> lines,
                                                int penX, int penY,
                                                int gap,
                                                int clipLeft, int clipTop, int clipRight, int clipBottom) {
        int left = BOUNDS_LEFT_START;
        int right = 0;
        int bottom = penY;
        float lineY = penY;

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);

            left  = Math.min(left, penX);
            right = Math.max(right, (int) Math.ceil(penX + measureWithoutCaretGlyph(font, line, true)));

            // Each alignment jump also moves both words.
            for (int shift : computeAlignmentShifts(line, penX, clipRight, text -> measureWithoutCaretGlyph(font, text, true))) {
                left += shift;
                right += shift;
            }

            float lineBottom = font.getLineBottom(line);

            if (lineBottom > 0) {
                bottom = Math.max(bottom, (int) (lineY + lineBottom));
            }

            if (index < lines.size() - 1) {
                lineY += font.getLineStep(line, gap);
                bottom += gap - 1;
            }
        }

        if (lines.isEmpty()) {
            left = penX;
        }

        return new Bounds(Math.max(clipLeft, left - BOUNDS_MARGIN),
                          Math.max(clipTop, penY - BOUNDS_MARGIN),
                          Math.min(clipRight, right + BOUNDS_MARGIN),
                          Math.min(clipBottom, bottom + BOUNDS_MARGIN));
    }

    /**
     * Computes the inline right- and center-alignment pen jumps against the inclusive right edge. Each later alignment code measures again from
     * the moved pen.
     *
     * @param line    the laid-out line (no line or form feed)
     * @param pen     the pen x where the line starts
     * @param right   the inclusive clip right
     * @param measure the advance of a text in interface pixels
     * @return the jump of each alignment code in order; empty when the line has none
     */
    public static int @NotNull [] computeAlignmentShifts(@NotNull String line, float pen, float right, @NotNull ToDoubleFunction<String> measure) {
        int count = 0;

        for (int index = 0; index < line.length(); index++) {
            if (isAlignmentCode(line.charAt(index))) {
                count++;
            }
        }

        int[] shifts = new int[count];
        int start = 0;
        int shift = 0;

        for (int index = 0; index < line.length() && shift < count; index++) {
            char character = line.charAt(index);

            if (!isAlignmentCode(character)) {
                continue;
            }

            pen += (float) measure.applyAsDouble(line.substring(start, index));

            int jump = (int) right - (int) findWrapPen(line, index + 1, pen, right, measure);

            if (character == CENTER_ALIGN_CODE) {
                jump >>= 1;
            }

            shifts[shift++] = jump;

            pen += jump;
            start = index + 1;
        }

        return shifts;
    }

    /**
     * Measures text without advancing for the edit caret glyph.
     *
     * @param font    the font
     * @param text    the text
     * @param kerning whether the one-pixel kerning is added
     * @return the width in font pixels
     */
    private static float measureWithoutCaretGlyph(@NotNull InterfaceFont font, @NotNull String text, boolean kerning) {
        return font.measure(removeCaretGlyph(text), kerning);
    }

    /**
     * Removes the caret glyph that follows code {@code 0x04}; the code itself stays, so the colors after it are unchanged.
     *
     * @param text the text
     * @return the text without the caret glyph
     */
    static @NotNull String removeCaretGlyph(@NotNull String text) {
        int caret = text.indexOf(CARET_MARK);

        return caret < 0 ? text : text.substring(0, caret + 1) + text.substring(caret + CARET_MARK.length());
    }


    /**
     * Wraps a text; see {@link #wrapBreaks(String, float, ToDoubleFunction)}. A line or form feed ends its piece and is not part of the line.
     *
     * @param font  the font
     * @param text  the text
     * @param width the wrap width in interface pixels; {@code 0} or less only breaks at line and form feeds
     * @return the lines
     */
    public static @NotNull List<String> wrap(@NotNull InterfaceFont font, @NotNull String text, float width) {
        return wrap(font, text, width, true, WrapMode.WORD);
    }

    private static @NotNull List<String> wrap(@NotNull InterfaceFont font, @NotNull String text,
                                              float width, boolean kerning, @NotNull WrapMode mode) {
        List<String> lines = new ArrayList<>();
        int start = 0;

        for (int end : wrapBreaks(text, width, mode, piece -> measureWithoutCaretGlyph(font, piece, kerning))) {
            String piece = text.substring(start, end);

            if (!piece.isEmpty() && (piece.charAt(piece.length() - 1) == LINE_FEED || piece.charAt(piece.length() - 1) == FORM_FEED)) {
                piece = piece.substring(0, piece.length() - 1);
            }

            lines.add(piece);
            start = end;
        }

        return lines;
    }

    /**
     * Returns the word-wrap piece ends of a text in the font, falling back to character wrapping when the first word does not fit; see
     * {@link #wrapBreaks(String, float, ToDoubleFunction)}.
     *
     * @param font  the font
     * @param text  the text
     * @param width the wrap width; {@code 0} or less only breaks at line and form feeds
     * @return the end index of each piece, the last one {@code text.length()}
     */
    public static int @NotNull [] wrapBreaks(@NotNull InterfaceFont font, @NotNull String text, float width) {
        return wrapBreaks(text, width, piece -> measureWithoutCaretGlyph(font, piece, true));
    }

    /**
     * Returns the word-wrap piece ends of a text, falling back to character wrapping when the first word does not fit. Each piece starts at
     * pen {@code 0} and ends where the wrap stops; line and form feeds end pieces. A piece has no length limit: codes that advance nothing,
     * such as carriage returns, stay in the piece of the text before them.
     *
     * @param text    the text
     * @param width   the wrap width; {@code 0} or less only breaks at line and form feeds
     * @param measure the advance of a text in interface pixels
     * @return the end index of each piece, the last one {@code text.length()}
     */
    public static int @NotNull [] wrapBreaks(@NotNull String text, float width, @NotNull ToDoubleFunction<String> measure) {
        return wrapBreaks(text, width, WrapMode.WORD, measure);
    }

    private static int @NotNull [] wrapBreaks(@NotNull String text, float width, @NotNull WrapMode mode, @NotNull ToDoubleFunction<String> measure) {
        float right = width > 0 ? width : Float.NaN;
        List<Integer> ends = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = findLineEnd(text, start, 0, right, mode, measure);

            // Fall back to character wrapping when the first word cannot fit.
            if (end == start && mode == WrapMode.WORD) {
                end = findLineEnd(text, start, 0, right, WrapMode.CHARACTER, measure);
            }

            if (end == start) {
                end = start + 1;
            }

            ends.add(end);
            start = end;
        }

        if (ends.isEmpty()) {
            ends.add(text.length());
        }

        int[] result = new int[ends.size()];

        for (int index = 0; index < result.length; index++) {
            result[index] = ends.get(index);
        }

        return result;
    }

    /**
     * Returns the pen where word wrapping stops after starting at {@code start} with pen {@code pen}.
     *
     * @param text    the text
     * @param start   the index where the pass starts
     * @param pen     the pen x at {@code start}
     * @param right   the inclusive clip right
     * @param measure the advance of a text in interface pixels
     * @return the pen where the pass stops
     */
    static float findWrapPen(@NotNull String text, int start, float pen, float right, @NotNull ToDoubleFunction<String> measure) {
        return pen + (float) measure.applyAsDouble(text.substring(start, findLineEnd(text, start, pen, right, WrapMode.WORD, measure)));
    }

    /**
     * Returns the end of one word- or character-wrapped piece. Line and form feeds end a piece; a first word that does not fit makes no progress in
     * word mode.
     *
     * @param text    the text
     * @param start   the index where the pass starts
     * @param pen     the pen x at {@code start}
     * @param right   the inclusive clip right; {@code NaN} breaks only at line and form feeds
     * @param mode    the wrap mode
     * @param measure the advance of a text in interface pixels
     * @return the end index of the piece
     */
    static int findLineEnd(@NotNull String text, int start, float pen, float right,
                           @NotNull WrapMode mode, @NotNull ToDoubleFunction<String> measure) {
        boolean limited = !Float.isNaN(right);
        int index = start;

        if (mode == WrapMode.CHARACTER) {
            for (; index < text.length(); index++) {
                char character = text.charAt(index);

                if (character == LINE_FEED || character == FORM_FEED) {
                    return index + 1;
                }

                if (character == SPACE || character == TAB || character == SUBSTITUTE || isAlignmentCode(character)) {
                    continue;
                }

                if (limited && pen + (float) measure.applyAsDouble(text.substring(start, index + 1)) > right) {
                    return index;
                }
            }

            return index;
        }

        while (true) {
            while (index < text.length()) {
                char character = text.charAt(index);

                if (character == LINE_FEED || character == FORM_FEED) {
                    return index + 1;
                }

                if (character != SPACE && character != TAB && character != CARRIAGE_RETURN && character != SUBSTITUTE &&
                    !isAlignmentCode(character) && !InterfaceFont.isColorCode(character)) {
                    break;
                }

                index++;
            }

            if (index == text.length() || limited && pen + (float) measure.applyAsDouble(text.substring(start, index)) > right) {
                return index;
            }

            int wordStart = index;

            while (index < text.length()) {
                char character = text.charAt(index);

                if (character == SPACE || character == TAB || character == LINE_FEED || character == FORM_FEED ||
                    character == SUBSTITUTE || isAlignmentCode(character)) {
                    break;
                }

                index++;
            }

            if (limited && Math.floor(pen + (float) measure.applyAsDouble(text.substring(start, index))) > right) {
                return wordStart;
            }

            if (index == text.length()) {
                return index;
            }
        }
    }

    /**
     * Truncates lines at drawing-stop control bytes and appends requested empty-line padding. A form feed ends the layout after its line.
     *
     * @param text    the text the lines were wrapped from
     * @param lines   the wrapped lines (line and form feeds removed)
     * @param padding the empty lines added after a line ended by a byte (the fit to the rectangle keeps what fits); {@code 0} without a rectangle
     * @return the lines to lay out
     */
    static @NotNull List<String> truncateLines(@NotNull String text, @NotNull List<String> lines, int padding) {
        int offset = 0;

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int end = findDrawnLength(line);

            if (end < line.length()) {
                List<String> ended = new ArrayList<>(lines.subList(0, index));

                ended.add(line.substring(0, end));

                for (int empty = 0; empty < padding; empty++) {
                    ended.add("");
                }

                return ended;
            }

            offset += line.length();

            if (offset < text.length() && text.charAt(offset) == FORM_FEED) {
                return lines.subList(0, index + 1);
            }

            if (offset < text.length() && text.charAt(offset) == LINE_FEED) {
                offset++;
            }
        }

        return lines;
    }

    /**
     * Returns the line length before the first unsupported drawing control character, or the full line length.
     *
     * @param line the line
     * @return the drawn length in characters
     */
    private static int findDrawnLength(@NotNull String line) {
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);

            if (character < SPACE && character != TAB && character != LINE_FEED && character != CARRIAGE_RETURN &&
                character != SUBSTITUTE && !isAlignmentCode(character) && !InterfaceFont.isColorCode(character)) {
                return index;
            }
        }

        return line.length();
    }

    private static boolean isAlignmentCode(char character) {
        return character == RIGHT_ALIGN_CODE || character == CENTER_ALIGN_CODE;
    }


    /**
     * Removes the hotkey prefix of a {@code WS_HOTKEY} / {@code WS_HOTVKEY} control string: the control text skips the first UTF-8 character.
     *
     * @param text the control string
     * @return the text without its first character
     */
    public static @NotNull String stripHotkey(@NotNull String text) {
        return text.isEmpty() ? text : text.substring(Character.charCount(text.codePointAt(0)));
    }

    /**
     * Tests whether a typed key is the hotkey of a {@code WS_HOTKEY} string, ignoring case.
     *
     * @param text a {@code WS_HOTKEY} string (its first character is the hotkey)
     * @param key  a typed character
     * @return {@code true} if the key is the string's hotkey, ignoring case, {@code false} otherwise
     */
    public static boolean isHotkey(@NotNull String text, char key) {
        return !text.isEmpty() && Character.toLowerCase(text.charAt(0)) == Character.toLowerCase(key);
    }

    /**
     * Tests whether a string carries a hotkey prefix: its first character is the hotkey, and the rest marks the same letter, ignoring case, with
     * the hotkey color code {@code 0x04} ({@code l<04>L<01>ow}, {@code e<04>E<01>xtra High}, {@code lLow (<04>L<01>)} with a translated label).
     * The page strings of the installed tables use it without a {@code WS_HOTKEY} control; the page shows the label without the hotkey character
     * and the marked letter highlighted.
     *
     * @param text a string
     * @return {@code true} if the first character is the string's hotkey, {@code false} otherwise
     */
    public static boolean isHotkeyString(@NotNull String text) {
        if (text.isEmpty()) {
            return false;
        }

        int first = text.codePointAt(0);
        int hotkey = Character.toLowerCase(first);

        for (int index = text.indexOf(HOTKEY_COLOR_CODE, Character.charCount(first)); index >= 0 && index + 1 < text.length();
             index = text.indexOf(HOTKEY_COLOR_CODE, index + 1)) {
            if (Character.toLowerCase(text.codePointAt(index + 1)) == hotkey) {
                return true;
            }
        }

        return false;
    }


    private static int @NotNull [] createGameCodeColors() {
        int[] colors = new int[InterfaceFont.CONTROL_CODE_COUNT];

        for (int code = 0; code < colors.length; code++) {
            colors[code] = InterfaceFont.getCodeColor(code);
        }

        return colors;
    }

    private InterfaceText() {
    }
}
