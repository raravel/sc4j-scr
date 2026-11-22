package org.fogofwarlabs.sc4j.scr.game.presentation;

import org.fogofwarlabs.sc4j.scr.data.format.casc.AssetResolver;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrConsolePlacement;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrDrawList;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks the local display: the classic screen until the client supplies one, the supplied screen, font measure, pointer and console rectangles,
 * and the refusal of an empty screen.
 */
final class LocalDisplayTest {
    private static final int WIDE_WIDTH = 853;
    private static final float HD_SCALE = 2.25f;
    private static final float MEASURED_WIDTH = 17.5f;
    private static final float POINTER_X = 700.25f;
    private static final float POINTER_Y = -3.5f;
    private static final int RECTANGLE_VALUES = 4;
    private static final int CONTROL = 7;

    @Test
    void local_display_starts_as_the_classic_screen() {
        LocalDisplay display = new LocalDisplay();

        Assertions.assertFalse(display.isKnown());
        Assertions.assertEquals(LocalDisplay.CLASSIC_WIDTH, display.getInterfaceWidth());
        Assertions.assertEquals(LocalDisplay.CLASSIC_HEIGHT, display.getInterfaceHeight());
        Assertions.assertEquals(1, display.getScale());
        Assertions.assertEquals(AssetResolver.AssetSet.SD, display.getGraphics());
        Assertions.assertEquals(0, display.measureText("text", ScrDrawList.Font.MEDIUM));
        Assertions.assertFalse(display.isPointerKnown());
    }

    @Test
    void console_rectangles_come_from_the_supplied_source_only() {
        LocalDisplay display = new LocalDisplay();
        float[] rectangle = new float[RECTANGLE_VALUES];

        Assertions.assertFalse(display.findDialogRectangle(ScrConsolePlacement.Dialog.MINIMAP, rectangle));

        display.setConsoleRectangles(new LocalDisplay.ConsoleRectangles() {
            @Override
            public boolean findDialogRectangle(ScrConsolePlacement.@NotNull Dialog dialog, float @NotNull [] values) {
                values[0] = dialog.ordinal();

                return dialog == ScrConsolePlacement.Dialog.MINIMAP;
            }

            @Override
            public boolean findControlRectangle(ScrConsolePlacement.@NotNull Dialog dialog, int control, float @NotNull [] values) {
                values[0] = control;

                return true;
            }
        });

        Assertions.assertTrue(display.findDialogRectangle(ScrConsolePlacement.Dialog.MINIMAP, rectangle));
        Assertions.assertEquals(ScrConsolePlacement.Dialog.MINIMAP.ordinal(), rectangle[0]);
        Assertions.assertFalse(display.findDialogRectangle(ScrConsolePlacement.Dialog.MENU, rectangle));
        Assertions.assertTrue(display.findControlRectangle(ScrConsolePlacement.Dialog.MENU, CONTROL, rectangle));
        Assertions.assertEquals(CONTROL, rectangle[0]);

        display.setConsoleRectangles(null);

        Assertions.assertFalse(display.findControlRectangle(ScrConsolePlacement.Dialog.MENU, CONTROL, rectangle));
    }

    @Test
    void set_pointer_should_keep_the_pointer_in_interface_units() {
        LocalDisplay display = new LocalDisplay();

        display.setPointer(POINTER_X, POINTER_Y);

        Assertions.assertTrue(display.isPointerKnown());
        Assertions.assertEquals(POINTER_X, display.getPointerX());
        Assertions.assertEquals(POINTER_Y, display.getPointerY());
    }

    @Test
    void set_screen_should_keep_the_supplied_screen_and_measure() {
        LocalDisplay display = new LocalDisplay();

        display.setScreen(WIDE_WIDTH, LocalDisplay.CLASSIC_HEIGHT, HD_SCALE, AssetResolver.AssetSet.HD);
        display.setTextMeasure((text, font) -> font == ScrDrawList.Font.LARGE ? MEASURED_WIDTH : 0);

        Assertions.assertTrue(display.isKnown());
        Assertions.assertEquals(WIDE_WIDTH, display.getInterfaceWidth());
        Assertions.assertEquals(HD_SCALE, display.getScale());
        Assertions.assertEquals(AssetResolver.AssetSet.HD, display.getGraphics());
        Assertions.assertEquals(MEASURED_WIDTH, display.measureText("text", ScrDrawList.Font.LARGE));
        Assertions.assertThrows(IllegalArgumentException.class, () -> display.setScreen(0, LocalDisplay.CLASSIC_HEIGHT, 1, AssetResolver.AssetSet.SD));
        Assertions.assertThrows(IllegalArgumentException.class, () -> display.setScreen(WIDE_WIDTH, LocalDisplay.CLASSIC_HEIGHT, 0,
                                                                                        AssetResolver.AssetSet.SD));
    }
}
