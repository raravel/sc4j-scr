package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.engine.client.render.Canvas;
import org.fogofwarlabs.sc4j.engine.client.render.Texture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrDrawList;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks how the plugin draw painter turns draw list commands into canvas commands without a GPU: the space mapping, the unit scale of lines and
 * points, the colour conversion, the circle polygons, one upload per picture and its opacity, and no text and a zero measure without fonts.
 */
final class PluginDrawPainterTest {
    private static final int RED = 0xFFFF0000;
    private static final int TRANSLUCENT_GREEN = 0x8000FF00;
    private static final float ORIGIN_X = 100;
    private static final float ORIGIN_Y = 50;
    private static final float SCALE = 2;
    private static final float UNIT_SCALE = 3;
    private static final float DELTA = 1e-4f;

    /**
     * Records the canvas commands as text.
     */
    private static final class RecordingCanvas implements Canvas {
        private final @NotNull List<String> commands;
        private int uploads;

        private RecordingCanvas() {
            this.commands = new ArrayList<>();
            this.uploads  = 0;
        }

        @Override
        public void begin(int width, int height) {
        }

        @Override
        public int getWidth() {
            return 0;
        }

        @Override
        public int getHeight() {
            return 0;
        }

        @Override
        public void setClip(int x, int y, int width, int height) {
        }

        @Override
        public @NotNull Texture uploadTexture(int width, int height, int @NotNull [] colors) {
            this.uploads++;

            return new Texture() {
                @Override
                public int getWidth() {
                    return width;
                }

                @Override
                public int getHeight() {
                    return height;
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public void drawTexture(@NotNull Texture texture, float x, float y, float width, float height, int color) {
            this.commands.add("texture " + texture.getWidth() + ' ' + x + ' ' + y + ' ' + width + ' ' + height + ' ' + Integer.toHexString(color));
        }

        @Override
        public void drawTexture(@NotNull Texture texture, float x, float y, float width, float height,
                                float left, float top, float right, float bottom,
                                int color) {
            this.commands.add("texture " + texture.getWidth() + ' ' + x + ' ' + y + ' ' + width + ' ' + height + ' ' + left + ' ' + top + ' ' +
                              right + ' ' + bottom + ' ' + Integer.toHexString(color));
        }

        @Override
        public void fillRectangle(float x, float y, float width, float height, int color) {
            this.commands.add("fill " + x + ' ' + y + ' ' + width + ' ' + height + ' ' + Integer.toHexString(color));
        }

        @Override
        public void drawRectangle(float x, float y, float width, float height, float thickness, int color) {
            this.commands.add("rectangle " + x + ' ' + y + ' ' + width + ' ' + height + ' ' + thickness + ' ' + Integer.toHexString(color));
        }

        @Override
        public void drawLine(float sourceX, float sourceY, float destinationX, float destinationY, float thickness, int color) {
            this.commands.add("line " + sourceX + ' ' + sourceY + ' ' + destinationX + ' ' + destinationY + ' ' + thickness + ' ' +
                              Integer.toHexString(color));
        }

        @Override
        public void fillQuadrilateral(float @NotNull [] corners, int color) {
            this.commands.add("quadrilateral " + Integer.toHexString(color));
        }

        @Override
        public void end() {
        }
    }

    private static @NotNull PluginDrawPainter createPainter(@NotNull RecordingCanvas canvas) {
        PluginDrawPainter painter = new PluginDrawPainter(canvas, null);

        painter.setSpace(ORIGIN_X, ORIGIN_Y, SCALE, UNIT_SCALE);

        return painter;
    }

    @Test
    void shapes_map_to_the_screen_with_the_space_and_unit_scales() {
        RecordingCanvas canvas = new RecordingCanvas();
        PluginDrawPainter painter = createPainter(canvas);

        painter.fillRectangle(1.5f, 2, 10, 20, RED);
        painter.drawRectangle(0, 0, 4, 5, 1, TRANSLUCENT_GREEN);
        painter.drawLine(1, 1, 2, 3, 2, RED);
        painter.drawPoint(10, 10, RED);

        Assertions.assertEquals(List.of("fill 103.0 54.0 20.0 40.0 ff0000ff",
                                        "rectangle 100.0 50.0 8.0 10.0 3.0 ff0080",
                                        "line 102.0 52.0 104.0 56.0 6.0 ff0000ff",
                                        "fill 120.0 70.0 3.0 3.0 ff0000ff"),
                                canvas.commands);
    }

    @Test
    void to_canvas_color_should_move_the_alpha_to_the_low_byte() {
        Assertions.assertEquals(0x11223344, PluginDrawPainter.toCanvasColor(0x44112233));
        Assertions.assertEquals(0xFFFFFFFF, PluginDrawPainter.toCanvasColor(0xFFFFFFFF));
    }

    @Test
    void circles_draw_polygons_whose_sides_follow_the_screen_radius() {
        RecordingCanvas canvas = new RecordingCanvas();
        PluginDrawPainter painter = createPainter(canvas);

        painter.fillCircle(0, 0, 1, RED);

        int small = canvas.commands.size();

        canvas.commands.clear();
        painter.drawCircle(0, 0, 100, 1, RED);

        int large = canvas.commands.size();

        canvas.commands.clear();
        painter.fillCircle(0, 0, 0, RED);

        Assertions.assertEquals(12, small);
        Assertions.assertTrue(large > small && large <= 256, () -> Integer.toString(large));
        Assertions.assertEquals(List.of(), canvas.commands);
    }

    @Test
    void pictures_upload_once_and_draw_their_source_rectangle_with_their_opacity() {
        RecordingCanvas canvas = new RecordingCanvas();
        PluginDrawPainter painter = createPainter(canvas);
        Picture picture = new Picture(2, 2, PixelLayer.ofRgba(new int[]{RED, RED, TRANSLUCENT_GREEN, TRANSLUCENT_GREEN}));

        painter.drawPicture(picture, 0, 0, 2, 2, 1, 1, 2, 2, 0.5f);
        painter.drawPicture(picture, 0, 0, 2, 2, 0, 0, 1, 1, 2);
        painter.drawPicture(picture, 1, 1, 1, 1, 0, 0, 1, 1, 1);

        Assertions.assertEquals(1, canvas.uploads);
        Assertions.assertEquals(List.of("texture 2 102.0 52.0 4.0 4.0 0.0 0.0 1.0 1.0 ffffff80",
                                        "texture 2 100.0 50.0 2.0 2.0 0.0 0.0 1.0 1.0 ffffffff",
                                        "texture 2 100.0 50.0 2.0 2.0 0.5 0.5 1.0 1.0 ffffffff"),
                                canvas.commands);
    }

    @Test
    void texts_need_fonts() {
        RecordingCanvas canvas = new RecordingCanvas();
        PluginDrawPainter painter = createPainter(canvas);

        painter.drawText("text", 0, 0, ScrDrawList.Font.MEDIUM, ScrDrawList.Align.CENTER, RED);

        Assertions.assertEquals(List.of(), canvas.commands);
        Assertions.assertEquals(0, painter.measureText("text", ScrDrawList.Font.MEDIUM), DELTA);
    }
}
