package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.render.Texture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.scr.client.ui.PictureTextureCache;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrDrawList;
import org.fogofwarlabs.sc4j.scr.game.presentation.LocalDisplay;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceFonts;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceTransform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Draws the draw lists of the game's plugins ({@link ScrDrawList}) on a canvas in screen coordinates. Before the commands of a space, the client
 * sets the space's mapping ({@link #setSpace}): a space coordinate {@code v} is drawn at {@code origin + v * scale}, and line thicknesses, points
 * and texts are drawn at {@code unitScale} screen pixels per unit. Circles are drawn as polygons of about {@value #CIRCLE_SEGMENT_PIXELS} screen
 * pixels per side. Texts are drawn by the game's interface text ({@link InterfaceText#drawAligned}) in the game's interface fonts
 * ({@link InterfaceFonts}, the slot of the font's order) with the game's colour codes; without fonts no text is drawn. Pictures are uploaded once
 * per picture instance.
 * <p>
 * It also measures texts for the plugins ({@link LocalDisplay.TextMeasure}).
 */
final class PluginDrawPainter implements ScrDrawList.Painter, LocalDisplay.TextMeasure {
    /**
     * The screen pixels of one side of a drawn circle.
     */
    private static final float CIRCLE_SEGMENT_PIXELS = 4;
    private static final int MINIMUM_CIRCLE_SEGMENTS = 12;
    private static final int MAXIMUM_CIRCLE_SEGMENTS = 256;
    private static final double FULL_TURN = 2 * Math.PI;
    private static final float HALF = 2;
    private static final int CORNER_COUNT = 8;
    private static final int ALPHA_SHIFT = 24;
    private static final int COLOR_SHIFT = 8;
    private static final int MAXIMUM_ALPHA = 0xFF;
    private static final int WHITE_RGBA = 0xFFFFFF00;

    /**
     * Maps the interface units of a text to the screen: a unit is {@code scale} screen pixels from the text's screen position.
     */
    private static final class TextTransform implements InterfaceTransform {
        private float originX;
        private float originY;
        private float scale;

        private TextTransform() {
            this.originX = 0;
            this.originY = 0;
            this.scale   = 1;
        }

        /**
         * @return the screen pixels per interface unit
         */
        @Override
        public float getScale() {
            return this.scale;
        }

        /**
         * @return the original interface width; a text is placed from its own position
         */
        @Override
        public float getLogicalWidth() {
            return ORIGINAL_WIDTH;
        }

        /**
         * Sets the screen position of the text's interface position {@code 0} and the screen pixels per interface unit.
         *
         * @param originX the screen x
         * @param originY the screen y
         * @param scale   the screen pixels per interface unit
         */
        private void set(float originX, float originY, float scale) {
            this.originX = originX;
            this.originY = originY;
            this.scale   = scale;
        }

        /**
         * Converts an interface x to a screen x.
         *
         * @param x an interface x
         * @return the screen x
         */
        @Override
        public float toScreenX(float x) {
            return this.originX + x * this.scale;
        }

        /**
         * Converts an interface y to a screen y.
         *
         * @param y an interface y
         * @return the screen y
         */
        @Override
        public float toScreenY(float y) {
            return this.originY + y * this.scale;
        }
    }

    /**
     * Converts a packed {@code 0xAARRGGBB} colour to the canvas's packed {@code 0xRRGGBBAA}.
     *
     * @param color the {@code 0xAARRGGBB} colour
     * @return the {@code 0xRRGGBBAA} colour
     */
    static int toCanvasColor(int color) {
        return color << COLOR_SHIFT | color >>> ALPHA_SHIFT;
    }


    private final @NotNull Canvas canvas;
    private final @Nullable InterfaceFonts fonts;
    private final @NotNull PictureTextureCache textures;
    private final @NotNull TextTransform textTransform;
    private final float @NotNull [] corners;

    private float originX;
    private float originY;
    private float scale;
    private float unitScale;

    /**
     * Creates a {@link PluginDrawPainter} instance that maps the space coordinates one to one.
     *
     * @param canvas the canvas, which also uploads the pictures; the fonts' interface renderer when there are fonts
     * @param fonts  the game's interface fonts, or {@code null} to draw no text
     */
    PluginDrawPainter(@NotNull Canvas canvas, @Nullable InterfaceFonts fonts) {
        this.canvas        = canvas;
        this.fonts         = fonts;
        this.textures      = new PictureTextureCache();
        this.textTransform = new TextTransform();
        this.corners       = new float[CORNER_COUNT];

        this.originX   = 0;
        this.originY   = 0;
        this.scale     = 1;
        this.unitScale = 1;
    }

    /**
     * @param canvas a canvas
     * @return {@code true} if the painter draws on the canvas, {@code false} otherwise
     */
    boolean isFor(@NotNull Canvas canvas) {
        return this.canvas == canvas;
    }

    /**
     * Sets how the coordinates of the next space map to the screen.
     *
     * @param originX   the screen x of the space's x {@code 0}
     * @param originY   the screen y of the space's y {@code 0}
     * @param scale     the screen pixels per space unit
     * @param unitScale the screen pixels per unit of line thickness, point and text
     */
    void setSpace(float originX, float originY, float scale, float unitScale) {
        this.originX   = originX;
        this.originY   = originY;
        this.scale     = scale;
        this.unitScale = unitScale;
    }

    /**
     * Measures a text in the game's interface fonts.
     *
     * @param text the text
     * @param font the font
     * @return the width in interface units, {@code 0} without fonts
     */
    @Override
    public float measureText(@NotNull String text, ScrDrawList.@NotNull Font font) {
        InterfaceFonts shownFonts = this.fonts;

        return shownFonts == null ? 0 : InterfaceText.measureAligned(shownFonts.getFont(font.ordinal()), text, InterfaceText.LEFT);
    }

    /**
     * Fills a rectangle.
     *
     * @param x      the left coordinate
     * @param y      the top coordinate
     * @param width  the width
     * @param height the height
     * @param color  the packed {@code 0xAARRGGBB} colour
     */
    @Override
    public void fillRectangle(float x, float y, float width, float height, int color) {
        this.canvas.fillRectangle(this.toScreenX(x), this.toScreenY(y), width * this.scale, height * this.scale, toCanvasColor(color));
    }

    /**
     * Draws a rectangle outline inside the rectangle.
     *
     * @param x         the left coordinate
     * @param y         the top coordinate
     * @param width     the width
     * @param height    the height
     * @param thickness the line thickness
     * @param color     the packed {@code 0xAARRGGBB} colour
     */
    @Override
    public void drawRectangle(float x, float y, float width, float height, float thickness, int color) {
        this.canvas.drawRectangle(this.toScreenX(x), this.toScreenY(y), width * this.scale, height * this.scale, thickness * this.unitScale,
                                  toCanvasColor(color));
    }

    /**
     * Fills a circle.
     *
     * @param x      the center x
     * @param y      the center y
     * @param radius the radius
     * @param color  the packed {@code 0xAARRGGBB} colour
     */
    @Override
    public void fillCircle(float x, float y, float radius, int color) {
        this.drawRing(x, y, 0, radius * this.scale, color);
    }

    /**
     * Draws a circle outline centered on the circle's edge.
     *
     * @param x         the center x
     * @param y         the center y
     * @param radius    the radius
     * @param thickness the line thickness
     * @param color     the packed {@code 0xAARRGGBB} colour
     */
    @Override
    public void drawCircle(float x, float y, float radius, float thickness, int color) {
        float half = thickness * this.unitScale / HALF;
        float screenRadius = radius * this.scale;

        this.drawRing(x, y, Math.max(0, screenRadius - half), screenRadius + half, color);
    }

    /**
     * Fills the ring between two screen radii around a center with convex quadrilaterals, one per side of the polygon of the outer radius; an
     * inner radius {@code 0} fills the disc.
     */
    private void drawRing(float x, float y, float innerRadius, float outerRadius, int color) {
        if (!(outerRadius > 0)) {
            return;
        }

        int segments = Math.clamp((int) Math.ceil(FULL_TURN * outerRadius / CIRCLE_SEGMENT_PIXELS), MINIMUM_CIRCLE_SEGMENTS, MAXIMUM_CIRCLE_SEGMENTS);
        float centerX = this.toScreenX(x);
        float centerY = this.toScreenY(y);
        int canvasColor = toCanvasColor(color);
        float[] points = this.corners;
        float cosine = 1;
        float sine = 0;

        for (int segment = 1; segment <= segments; segment++) {
            double angle = FULL_TURN * segment / segments;
            float nextCosine = (float) Math.cos(angle);
            float nextSine = (float) Math.sin(angle);

            points[0] = centerX + cosine * innerRadius;
            points[1] = centerY + sine * innerRadius;
            points[2] = centerX + cosine * outerRadius;
            points[3] = centerY + sine * outerRadius;
            points[4] = centerX + nextCosine * outerRadius;
            points[5] = centerY + nextSine * outerRadius;
            points[6] = centerX + nextCosine * innerRadius;
            points[7] = centerY + nextSine * innerRadius;

            this.canvas.fillQuadrilateral(points, canvasColor);

            cosine = nextCosine;
            sine   = nextSine;
        }
    }

    /**
     * Draws a straight line.
     *
     * @param sourceX      the start x
     * @param sourceY      the start y
     * @param destinationX the end x
     * @param destinationY the end y
     * @param thickness    the line thickness
     * @param color        the packed {@code 0xAARRGGBB} colour
     */
    @Override
    public void drawLine(float sourceX, float sourceY, float destinationX, float destinationY, float thickness, int color) {
        this.canvas.drawLine(this.toScreenX(sourceX), this.toScreenY(sourceY), this.toScreenX(destinationX), this.toScreenY(destinationY),
                             thickness * this.unitScale, toCanvasColor(color));
    }

    /**
     * Draws a point: a square of one unit of the space's line thickness with its top left at the position.
     *
     * @param x     the left coordinate
     * @param y     the top coordinate
     * @param color the packed {@code 0xAARRGGBB} colour
     */
    @Override
    public void drawPoint(float x, float y, int color) {
        this.canvas.fillRectangle(this.toScreenX(x), this.toScreenY(y), this.unitScale, this.unitScale, toCanvasColor(color));
    }

    /**
     * Draws a text: its lines, broken at line feeds, from the top at {@code y}, each aligned on {@code x}. Colour codes in the text switch
     * the colour as in the game's texts, also across lines.
     *
     * @param text  the text
     * @param x     the x the lines align on
     * @param y     the top coordinate
     * @param font  the font
     * @param align the alignment of the lines
     * @param color the packed {@code 0xAARRGGBB} starting colour
     */
    @Override
    public void drawText(@NotNull String text, float x, float y, ScrDrawList.@NotNull Font font, ScrDrawList.@NotNull Align align, int color) {
        InterfaceFonts shownFonts = this.fonts;

        if (shownFonts == null || text.isEmpty()) {
            return;
        }

        int format = switch (align) {
            case LEFT -> InterfaceText.LEFT;
            case CENTER -> InterfaceText.CENTER;
            case RIGHT -> InterfaceText.RIGHT;
        };

        this.textTransform.set(this.toScreenX(x), this.toScreenY(y), this.unitScale);
        InterfaceText.drawAligned(this.canvas, this.textTransform, shownFonts.getFont(font.ordinal()), text, 0, 0, format, toCanvasColor(color),
                                  InterfaceText.GAME_CODE_COLORS);
    }

    /**
     * Draws a rectangle of a picture stretched into a rectangle: the source rectangle as texture coordinates of the picture's texture.
     *
     * @param picture      the picture, whose colour layer is RGBA
     * @param sourceX      the left of the drawn part in picture pixels
     * @param sourceY      the top of the drawn part in picture pixels
     * @param sourceWidth  the width of the drawn part in picture pixels
     * @param sourceHeight the height of the drawn part in picture pixels
     * @param x            the left coordinate
     * @param y            the top coordinate
     * @param width        the drawn width
     * @param height       the drawn height
     * @param opacity      the opacity, {@code 0} to {@code 1}
     */
    @Override
    public void drawPicture(@NotNull Picture picture,
                            float sourceX, float sourceY, float sourceWidth, float sourceHeight,
                            float x, float y, float width, float height,
                            float opacity) {
        Texture texture = this.textures.findTexture(this.canvas, picture);
        int alpha = Math.round(Math.clamp(opacity, 0, 1) * MAXIMUM_ALPHA);
        float textureWidth = texture.getWidth();
        float textureHeight = texture.getHeight();

        this.canvas.drawTexture(texture, this.toScreenX(x), this.toScreenY(y), width * this.scale, height * this.scale,
                                sourceX / textureWidth, sourceY / textureHeight,
                                (sourceX + sourceWidth) / textureWidth, (sourceY + sourceHeight) / textureHeight,
                                WHITE_RGBA | alpha);
    }

    private float toScreenX(float x) {
        return this.originX + x * this.scale;
    }

    private float toScreenY(float y) {
        return this.originY + y * this.scale;
    }

    /**
     * Disposes resources: closes the textures of the drawn pictures.
     */
    void close() {
        this.textures.close();
    }
}
