package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * The shapes, texts and pictures a plugin draws over the game ({@link ScrGamePlugin#findDrawList()}), in three spaces: the game screen above the
 * console, the map above the units and below the console, and the map on the minimap. Each space keeps its commands in the order they were added,
 * which is the order they are drawn. Coordinates are floats; colours are packed {@code 0xAARRGGBB} with straight alpha.
 * <p>
 * A list is reused: {@link #clear()} keeps its storage, so a list that is refilled every frame allocates only while it grows.
 */
public final class ScrDrawList {
    /**
     * Where the commands of a space are drawn and what their coordinates mean.
     */
    public enum Space {
        /**
         * The game screen in interface units: {@code 480} high, as wide as the interface ({@code 640} or wider), drawn above the console. Sizes,
         * line thicknesses and texts are in interface units.
         */
        SCREEN,
        /**
         * The map in map pixels, following the camera: drawn above the units and below the console. Sizes, line thicknesses and texts are in map
         * pixels, so they follow the camera zoom as the units do.
         */
        WORLD,
        /**
         * The map in map pixels mapped onto the minimap, drawn above it. Positions and sizes are in map pixels; line thicknesses, point sizes and
         * texts are in interface units.
         */
        MINIMAP
    }

    /**
     * A text font: the four font slots of the game interface.
     */
    public enum Font {
        /**
         * The small font, {@code font8}.
         */
        SMALL,
        /**
         * The default dialog font, {@code font10}.
         */
        MEDIUM,
        /**
         * The big font, {@code font16}.
         */
        LARGE,
        /**
         * The bigger font, {@code font16x}.
         */
        HUGE
    }

    /**
     * Where a text line stands from its x.
     */
    public enum Align {
        /**
         * The line starts at its x.
         */
        LEFT,
        /**
         * The line is centered on its x.
         */
        CENTER,
        /**
         * The line ends at its x.
         */
        RIGHT
    }

    /**
     * Draws the commands of one space ({@link #paint}): each method gets the values of one command in the space's coordinates.
     */
    public interface Painter {
        /**
         * Fills a rectangle.
         *
         * @param x      the left coordinate
         * @param y      the top coordinate
         * @param width  the width
         * @param height the height
         * @param color  the packed {@code 0xAARRGGBB} colour
         */
        void fillRectangle(float x, float y, float width, float height, int color);

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
        void drawRectangle(float x, float y, float width, float height, float thickness, int color);

        /**
         * Fills a circle.
         *
         * @param x      the center x
         * @param y      the center y
         * @param radius the radius
         * @param color  the packed {@code 0xAARRGGBB} colour
         */
        void fillCircle(float x, float y, float radius, int color);

        /**
         * Draws a circle outline centered on the circle's edge.
         *
         * @param x         the center x
         * @param y         the center y
         * @param radius    the radius
         * @param thickness the line thickness
         * @param color     the packed {@code 0xAARRGGBB} colour
         */
        void drawCircle(float x, float y, float radius, float thickness, int color);

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
        void drawLine(float sourceX, float sourceY, float destinationX, float destinationY, float thickness, int color);

        /**
         * Draws a point: a square of one unit of the space's line thickness with its top left at the position.
         *
         * @param x     the left coordinate
         * @param y     the top coordinate
         * @param color the packed {@code 0xAARRGGBB} colour
         */
        void drawPoint(float x, float y, int color);

        /**
         * Draws a text: its lines, broken at line feeds, from the top at {@code y}, each aligned on {@code x}. Colour codes in the text switch
         * the colour as in the game's texts.
         *
         * @param text  the text
         * @param x     the x the lines align on
         * @param y     the top coordinate
         * @param font  the font
         * @param align the alignment of the lines
         * @param color the packed {@code 0xAARRGGBB} starting colour
         */
        void drawText(@NotNull String text, float x, float y, @NotNull Font font, @NotNull Align align, int color);

        /**
         * Draws a rectangle of a picture stretched into a rectangle.
         *
         * @param picture      the picture
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
        void drawPicture(@NotNull Picture picture,
                         float sourceX, float sourceY, float sourceWidth, float sourceHeight,
                         float x, float y, float width, float height,
                         float opacity);
    }

    private static final int FILL_RECTANGLE = 0;
    private static final int DRAW_RECTANGLE = 1;
    private static final int FILL_CIRCLE = 2;
    private static final int DRAW_CIRCLE = 3;
    private static final int DRAW_LINE = 4;
    private static final int DRAW_POINT = 5;
    private static final int DRAW_TEXT = 6;
    private static final int DRAW_PICTURE = 7;

    /**
     * The float values of one command: five, and the four of a picture's source rectangle after them.
     */
    private static final int VALUE_STRIDE = 9;
    /**
     * The offset of the source rectangle in the values of a picture command.
     */
    private static final int SOURCE_OFFSET = 5;
    private static final int INITIAL_CAPACITY = 16;
    private static final int GROWTH_FACTOR = 2;
    private static final int ALIGN_SHIFT = 8;
    private static final int OPTION_MASK = 0xFF;
    private static final @NotNull Font @NotNull [] FONTS = Font.values();
    private static final @NotNull Align @NotNull [] ALIGNS = Align.values();

    /**
     * The commands of one space, in struct-of-arrays form.
     */
    private static final class Commands {
        private int count;
        private int @NotNull [] kinds;
        private float @NotNull [] values;
        private int @NotNull [] colors;
        private int @NotNull [] options;
        private @Nullable Object @NotNull [] objects;

        /**
         * Creates a {@link Commands} instance without commands.
         */
        private Commands() {
            this.count   = 0;
            this.kinds   = new int[INITIAL_CAPACITY];
            this.values  = new float[INITIAL_CAPACITY * VALUE_STRIDE];
            this.colors  = new int[INITIAL_CAPACITY];
            this.options = new int[INITIAL_CAPACITY];
            this.objects = new Object[INITIAL_CAPACITY];
        }

        /**
         * Adds a command and returns its index, growing the arrays when they are full.
         */
        private int add(int kind, float first, float second, float third, float fourth, float fifth, int color) {
            if (this.count == this.kinds.length) {
                int capacity = this.kinds.length * GROWTH_FACTOR;

                this.kinds   = Arrays.copyOf(this.kinds, capacity);
                this.values  = Arrays.copyOf(this.values, capacity * VALUE_STRIDE);
                this.colors  = Arrays.copyOf(this.colors, capacity);
                this.options = Arrays.copyOf(this.options, capacity);
                this.objects = Arrays.copyOf(this.objects, capacity);
            }

            int index = this.count++;
            int offset = index * VALUE_STRIDE;

            this.kinds[index]       = kind;
            this.values[offset]     = first;
            this.values[offset + 1] = second;
            this.values[offset + 2] = third;
            this.values[offset + 3] = fourth;
            this.values[offset + 4] = fifth;
            this.colors[index]      = color;

            return index;
        }

        /**
         * Removes every command and drops the references to their texts and pictures.
         */
        private void clear() {
            Arrays.fill(this.objects, 0, this.count, null);

            this.count = 0;
        }
    }

    private final @NotNull Commands @NotNull [] spaces;

    /**
     * Creates a {@link ScrDrawList} instance without commands.
     */
    public ScrDrawList() {
        this.spaces = new Commands[Space.values().length];

        for (int index = 0; index < this.spaces.length; index++) {
            this.spaces[index] = new Commands();
        }
    }

    /**
     * @param space the space
     * @return the number of commands of the space
     */
    public int getCount(@NotNull Space space) {
        return this.spaces[space.ordinal()].count;
    }

    /**
     * @return {@code true} if no space has a command, {@code false} otherwise
     */
    public boolean isEmpty() {
        for (Commands commands : this.spaces) {
            if (commands.count != 0) {
                return false;
            }
        }

        return true;
    }

    /**
     * Removes every command of every space, keeping the storage.
     */
    public void clear() {
        for (Commands commands : this.spaces) {
            commands.clear();
        }
    }

    /**
     * Adds a filled rectangle.
     *
     * @param space  the space
     * @param x      the left coordinate
     * @param y      the top coordinate
     * @param width  the width
     * @param height the height
     * @param color  the packed {@code 0xAARRGGBB} colour
     */
    public void fillRectangle(@NotNull Space space, float x, float y, float width, float height, int color) {
        this.spaces[space.ordinal()].add(FILL_RECTANGLE, x, y, width, height, 0, color);
    }

    /**
     * Adds a rectangle outline inside the rectangle.
     *
     * @param space     the space
     * @param x         the left coordinate
     * @param y         the top coordinate
     * @param width     the width
     * @param height    the height
     * @param thickness the line thickness
     * @param color     the packed {@code 0xAARRGGBB} colour
     */
    public void drawRectangle(@NotNull Space space, float x, float y, float width, float height, float thickness, int color) {
        this.spaces[space.ordinal()].add(DRAW_RECTANGLE, x, y, width, height, thickness, color);
    }

    /**
     * Adds a filled circle.
     *
     * @param space  the space
     * @param x      the center x
     * @param y      the center y
     * @param radius the radius
     * @param color  the packed {@code 0xAARRGGBB} colour
     */
    public void fillCircle(@NotNull Space space, float x, float y, float radius, int color) {
        this.spaces[space.ordinal()].add(FILL_CIRCLE, x, y, radius, 0, 0, color);
    }

    /**
     * Adds a circle outline centered on the circle's edge.
     *
     * @param space     the space
     * @param x         the center x
     * @param y         the center y
     * @param radius    the radius
     * @param thickness the line thickness
     * @param color     the packed {@code 0xAARRGGBB} colour
     */
    public void drawCircle(@NotNull Space space, float x, float y, float radius, float thickness, int color) {
        this.spaces[space.ordinal()].add(DRAW_CIRCLE, x, y, radius, thickness, 0, color);
    }

    /**
     * Adds a straight line.
     *
     * @param space        the space
     * @param sourceX      the start x
     * @param sourceY      the start y
     * @param destinationX the end x
     * @param destinationY the end y
     * @param thickness    the line thickness
     * @param color        the packed {@code 0xAARRGGBB} colour
     */
    public void drawLine(@NotNull Space space, float sourceX, float sourceY, float destinationX, float destinationY, float thickness, int color) {
        this.spaces[space.ordinal()].add(DRAW_LINE, sourceX, sourceY, destinationX, destinationY, thickness, color);
    }

    /**
     * Adds a point ({@link Painter#drawPoint}).
     *
     * @param space the space
     * @param x     the left coordinate
     * @param y     the top coordinate
     * @param color the packed {@code 0xAARRGGBB} colour
     */
    public void drawPoint(@NotNull Space space, float x, float y, int color) {
        this.spaces[space.ordinal()].add(DRAW_POINT, x, y, 0, 0, 0, color);
    }

    /**
     * Adds a text ({@link Painter#drawText}).
     *
     * @param space the space
     * @param text  the text
     * @param x     the x the lines align on
     * @param y     the top coordinate
     * @param font  the font
     * @param align the alignment of the lines
     * @param color the packed {@code 0xAARRGGBB} starting colour
     */
    public void drawText(@NotNull Space space, @NotNull String text, float x, float y, @NotNull Font font, @NotNull Align align, int color) {
        Commands commands = this.spaces[space.ordinal()];
        int index = commands.add(DRAW_TEXT, x, y, 0, 0, 0, color);

        commands.options[index] = font.ordinal() | align.ordinal() << ALIGN_SHIFT;
        commands.objects[index] = text;
    }

    /**
     * Adds a picture stretched into a rectangle.
     *
     * @param space   the space
     * @param picture the picture
     * @param x       the left coordinate
     * @param y       the top coordinate
     * @param width   the drawn width
     * @param height  the drawn height
     * @param opacity the opacity, {@code 0} to {@code 1}
     */
    public void drawPicture(@NotNull Space space, @NotNull Picture picture, float x, float y, float width, float height, float opacity) {
        this.drawPicture(space, picture, 0, 0, picture.getWidth(), picture.getHeight(), x, y, width, height, opacity);
    }

    /**
     * Adds a rectangle of a picture stretched into a rectangle, as a frame of a sprite sheet or a cropped picture; the source rectangle lies
     * inside the picture.
     *
     * @param space        the space
     * @param picture      the picture
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
    public void drawPicture(@NotNull Space space, @NotNull Picture picture,
                            float sourceX, float sourceY, float sourceWidth, float sourceHeight,
                            float x, float y, float width, float height,
                            float opacity) {
        Commands commands = this.spaces[space.ordinal()];
        int index = commands.add(DRAW_PICTURE, x, y, width, height, opacity, 0);
        int offset = index * VALUE_STRIDE + SOURCE_OFFSET;

        commands.values[offset]     = sourceX;
        commands.values[offset + 1] = sourceY;
        commands.values[offset + 2] = sourceWidth;
        commands.values[offset + 3] = sourceHeight;
        commands.objects[index]     = picture;
    }

    /**
     * Hands the commands of a space to a painter in their order.
     *
     * @param space   the space
     * @param painter the painter
     */
    public void paint(@NotNull Space space, @NotNull Painter painter) {
        Commands commands = this.spaces[space.ordinal()];
        float[] values = commands.values;

        for (int index = 0; index < commands.count; index++) {
            int offset = index * VALUE_STRIDE;
            int color = commands.colors[index];

            switch (commands.kinds[index]) {
                case FILL_RECTANGLE -> painter.fillRectangle(values[offset], values[offset + 1], values[offset + 2], values[offset + 3], color);
                case DRAW_RECTANGLE -> painter.drawRectangle(values[offset], values[offset + 1], values[offset + 2], values[offset + 3],
                                                             values[offset + 4], color);
                case FILL_CIRCLE -> painter.fillCircle(values[offset], values[offset + 1], values[offset + 2], color);
                case DRAW_CIRCLE -> painter.drawCircle(values[offset], values[offset + 1], values[offset + 2], values[offset + 3], color);
                case DRAW_LINE -> painter.drawLine(values[offset], values[offset + 1], values[offset + 2], values[offset + 3], values[offset + 4],
                                                   color);
                case DRAW_POINT -> painter.drawPoint(values[offset], values[offset + 1], color);
                case DRAW_TEXT -> {
                    int option = commands.options[index];

                    painter.drawText((String) commands.objects[index], values[offset], values[offset + 1], FONTS[option & OPTION_MASK],
                                     ALIGNS[option >>> ALIGN_SHIFT & OPTION_MASK], color);
                }
                case DRAW_PICTURE -> {
                    int source = offset + SOURCE_OFFSET;

                    painter.drawPicture((Picture) commands.objects[index],
                                        values[source], values[source + 1], values[source + 2], values[source + 3],
                                        values[offset], values[offset + 1], values[offset + 2], values[offset + 3],
                                        values[offset + 4]);
                }
                default -> throw Exceptions.newIllegalStateException("Unknown draw command kind {0}.", commands.kinds[index]);
            }
        }
    }
}
