package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks the draw list: each space keeps its commands with their values in the order they were added, a picture its source rectangle (the whole
 * picture by default), clearing keeps nothing, and a list that grows past its first storage keeps every command.
 */
final class ScrDrawListTest {
    private static final int RED = 0xFFFF0000;
    private static final int TRANSLUCENT_BLUE = 0x800000FF;
    private static final int MANY_COMMANDS = 100;

    /**
     * Records the painted commands as text.
     */
    private static final class RecordingPainter implements ScrDrawList.Painter {
        private final @NotNull List<String> commands;

        private RecordingPainter() {
            this.commands = new ArrayList<>();
        }

        @Override
        public void fillRectangle(float x, float y, float width, float height, int color) {
            this.commands.add("fillRectangle " + x + ' ' + y + ' ' + width + ' ' + height + ' ' + Integer.toHexString(color));
        }

        @Override
        public void drawRectangle(float x, float y, float width, float height, float thickness, int color) {
            this.commands.add("drawRectangle " + x + ' ' + y + ' ' + width + ' ' + height + ' ' + thickness + ' ' + Integer.toHexString(color));
        }

        @Override
        public void fillCircle(float x, float y, float radius, int color) {
            this.commands.add("fillCircle " + x + ' ' + y + ' ' + radius + ' ' + Integer.toHexString(color));
        }

        @Override
        public void drawCircle(float x, float y, float radius, float thickness, int color) {
            this.commands.add("drawCircle " + x + ' ' + y + ' ' + radius + ' ' + thickness + ' ' + Integer.toHexString(color));
        }

        @Override
        public void drawLine(float sourceX, float sourceY, float destinationX, float destinationY, float thickness, int color) {
            this.commands.add("drawLine " + sourceX + ' ' + sourceY + ' ' + destinationX + ' ' + destinationY + ' ' + thickness + ' ' +
                              Integer.toHexString(color));
        }

        @Override
        public void drawPoint(float x, float y, int color) {
            this.commands.add("drawPoint " + x + ' ' + y + ' ' + Integer.toHexString(color));
        }

        @Override
        public void drawText(@NotNull String text, float x, float y, ScrDrawList.@NotNull Font font, ScrDrawList.@NotNull Align align, int color) {
            this.commands.add("drawText " + text + ' ' + x + ' ' + y + ' ' + font + ' ' + align + ' ' + Integer.toHexString(color));
        }

        @Override
        public void drawPicture(@NotNull Picture picture,
                                float sourceX, float sourceY, float sourceWidth, float sourceHeight,
                                float x, float y, float width, float height,
                                float opacity) {
            this.commands.add("drawPicture " + picture.getWidth() + ' ' + sourceX + ' ' + sourceY + ' ' + sourceWidth + ' ' + sourceHeight + ' ' +
                              x + ' ' + y + ' ' + width + ' ' + height + ' ' + opacity);
        }
    }

    private static @NotNull List<String> paint(@NotNull ScrDrawList list, ScrDrawList.@NotNull Space space) {
        RecordingPainter painter = new RecordingPainter();

        list.paint(space, painter);

        return painter.commands;
    }

    @Test
    void spaces_keep_their_commands_in_order() {
        ScrDrawList list = new ScrDrawList();
        Picture picture = new Picture(2, 1, PixelLayer.ofRgba(new int[]{RED, TRANSLUCENT_BLUE}));

        list.fillRectangle(ScrDrawList.Space.SCREEN, 1.5f, 2, 3, 4, RED);
        list.drawText(ScrDrawList.Space.WORLD, "a\nb", 10, 20, ScrDrawList.Font.HUGE, ScrDrawList.Align.RIGHT, TRANSLUCENT_BLUE);
        list.drawRectangle(ScrDrawList.Space.SCREEN, 0.25f, 0, 8, 9, 2, TRANSLUCENT_BLUE);
        list.fillCircle(ScrDrawList.Space.MINIMAP, 64, 32, 16, RED);
        list.drawCircle(ScrDrawList.Space.MINIMAP, 1, 2, 3, 0.5f, RED);
        list.drawLine(ScrDrawList.Space.SCREEN, 1, 2, 3, 4, 5, RED);
        list.drawPoint(ScrDrawList.Space.WORLD, 7, 8, RED);
        list.drawPicture(ScrDrawList.Space.SCREEN, picture, 5, 6, 20, 10, 0.5f);
        list.drawPicture(ScrDrawList.Space.WORLD, picture, 1, 0, 1, 1, 30, 40, 16, 16, 1);

        Assertions.assertEquals(List.of("fillRectangle 1.5 2.0 3.0 4.0 ffff0000",
                                        "drawRectangle 0.25 0.0 8.0 9.0 2.0 800000ff",
                                        "drawLine 1.0 2.0 3.0 4.0 5.0 ffff0000",
                                        "drawPicture 2 0.0 0.0 2.0 1.0 5.0 6.0 20.0 10.0 0.5"),
                                paint(list, ScrDrawList.Space.SCREEN));
        Assertions.assertEquals(List.of("drawText a\nb 10.0 20.0 HUGE RIGHT 800000ff", "drawPoint 7.0 8.0 ffff0000",
                                        "drawPicture 2 1.0 0.0 1.0 1.0 30.0 40.0 16.0 16.0 1.0"),
                                paint(list, ScrDrawList.Space.WORLD));
        Assertions.assertEquals(List.of("fillCircle 64.0 32.0 16.0 ffff0000", "drawCircle 1.0 2.0 3.0 0.5 ffff0000"),
                                paint(list, ScrDrawList.Space.MINIMAP));
        Assertions.assertEquals(4, list.getCount(ScrDrawList.Space.SCREEN));
        Assertions.assertFalse(list.isEmpty());
    }

    @Test
    void clear_removes_every_command() {
        ScrDrawList list = new ScrDrawList();

        list.fillRectangle(ScrDrawList.Space.SCREEN, 0, 0, 1, 1, RED);
        list.drawText(ScrDrawList.Space.MINIMAP, "x", 0, 0, ScrDrawList.Font.SMALL, ScrDrawList.Align.LEFT, RED);
        list.clear();

        Assertions.assertTrue(list.isEmpty());
        Assertions.assertEquals(List.of(), paint(list, ScrDrawList.Space.SCREEN));
        Assertions.assertEquals(List.of(), paint(list, ScrDrawList.Space.MINIMAP));
    }

    @Test
    void a_growing_list_keeps_every_command() {
        ScrDrawList list = new ScrDrawList();

        for (int index = 0; index < MANY_COMMANDS; index++) {
            list.drawText(ScrDrawList.Space.SCREEN, Integer.toString(index), index, 0, ScrDrawList.Font.MEDIUM, ScrDrawList.Align.CENTER, RED);
        }

        List<String> commands = paint(list, ScrDrawList.Space.SCREEN);

        Assertions.assertEquals(MANY_COMMANDS, commands.size());
        Assertions.assertEquals("drawText 99 99.0 0.0 MEDIUM CENTER ffff0000", commands.getLast());
    }
}
