package org.fogofwarlabs.sc4j.scr.game.extension;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks the screen overlay: a set pixel is shown with its palette index until it is hidden, the shown count and the change count follow the
 * changes, and a size that is not positive is rejected.
 */
final class ScrScreenOverlayTest {
    private static final int WIDTH = 4;
    private static final int HEIGHT = 3;
    private static final int PIXEL = 5;
    private static final int INDEX = 200;

    @Test
    void set_pixels_are_shown_and_counted() {
        ScrScreenOverlay overlay = new ScrScreenOverlay(WIDTH, HEIGHT);

        Assertions.assertEquals(0, overlay.getShownCount());
        Assertions.assertFalse(overlay.isShown(PIXEL));

        overlay.setPixel(PIXEL, INDEX);

        Assertions.assertTrue(overlay.isShown(PIXEL));
        Assertions.assertEquals(INDEX, overlay.getPixel(PIXEL));
        Assertions.assertEquals(1, overlay.getShownCount());
        Assertions.assertEquals(1, overlay.getChangeCount());

        overlay.setPixel(PIXEL, INDEX);

        Assertions.assertEquals(1, overlay.getChangeCount());

        overlay.setPixel(PIXEL, 0);

        Assertions.assertEquals(0, overlay.getPixel(PIXEL));
        Assertions.assertTrue(overlay.isShown(PIXEL));
        Assertions.assertEquals(1, overlay.getShownCount());
        Assertions.assertEquals(2, overlay.getChangeCount());
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> overlay.setPixel(WIDTH * HEIGHT, 0));

        overlay.hidePixel(PIXEL);
        overlay.hidePixel(PIXEL);

        Assertions.assertFalse(overlay.isShown(PIXEL));
        Assertions.assertEquals(0, overlay.getShownCount());
        Assertions.assertEquals(3, overlay.getChangeCount());
    }

    @Test
    void a_size_that_is_not_positive_is_rejected() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new ScrScreenOverlay(0, HEIGHT));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new ScrScreenOverlay(WIDTH, -1));
    }
}
