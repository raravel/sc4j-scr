package org.fogofwarlabs.sc4j.scr.client.ui.console;

import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.fogofwarlabs.sc4j.scr.render.OverlayPass;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceImage;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * Checks the console hit masks of the SD console and of the placed HD pieces, and of the pictures drawn instead of pieces.
 */
final class ConsoleArtTest {
    /**
     * An SD console image two pixels wide and one pixel high whose left pixel is opaque.
     */
    private static final int STANDARD_WIDTH = 2;
    private static final int STANDARD_HEIGHT = 1;
    /**
     * The interface height: the SD console ends at the interface bottom edge.
     */
    private static final int INTERFACE_HEIGHT = 480;
    private static final int OPAQUE_ALPHA = 0xFF;
    private static final int OPAQUE_ARGB = 0xFF000000;
    /**
     * An alpha just below the alpha that counts as console.
     */
    private static final int FAINT_ALPHA = 4;
    /**
     * The HD2 piece sizes: each piece is half as wide and high in interface pixels.
     */
    private static final int SIDE_PIECE_WIDTH = 300;
    private static final int CENTER_PIECE_WIDTH = 400;
    private static final int PIECE_HEIGHT = 40;
    private static final int HD2_SCALE = 2;
    private static final int HD2_DIVISOR = 1;
    private static final float PIXEL_CENTER = 0.5f;
    private static final int CENTER_DIVISOR = 2;

    /**
     * Creates an image whose pixels all have one alpha.
     *
     * @param width  the width in pixels
     * @param height the height in pixels
     * @param alpha  the alpha of every pixel
     * @return the image
     */
    private static @NotNull InterfaceImage createImage(int width, int height, int alpha) {
        int[] alphas = new int[width * height];

        Arrays.fill(alphas, alpha);

        return createImage(width, height, alphas);
    }

    /**
     * Creates an image with the alpha of each pixel in row order.
     *
     * @param width  the width in pixels
     * @param height the height in pixels
     * @param alphas the alphas by pixel
     * @return the image
     */
    private static @NotNull InterfaceImage createImage(int width, int height, int @NotNull [] alphas) {
        byte[] rgba = new byte[width * height * OverlayPass.RGBA_BYTES];

        for (int pixel = 0; pixel < alphas.length; pixel++) {
            rgba[pixel * OverlayPass.RGBA_BYTES + OverlayPass.RGBA_ALPHA] = (byte) alphas[pixel];
        }

        return new InterfaceImage(width, height, rgba);
    }


    @Test
    void is_hit_should_test_the_standard_definition_mask_on_the_interface_bottom_edge() {
        ConsoleArt opaqueLeft = ConsoleArt.ofStandardDefinition(createImage(STANDARD_WIDTH, STANDARD_HEIGHT, new int[]{OPAQUE_ALPHA, 0}));
        ConsoleArt faint = ConsoleArt.ofStandardDefinition(createImage(STANDARD_WIDTH, STANDARD_HEIGHT, FAINT_ALPHA));
        float top = INTERFACE_HEIGHT - STANDARD_HEIGHT;

        Assertions.assertEquals(top, opaqueLeft.getTop(ConsoleArt.LEFT_PIECE));
        Assertions.assertTrue(opaqueLeft.isHit(PIXEL_CENTER, top + PIXEL_CENTER));
        Assertions.assertFalse(opaqueLeft.isHit(1 + PIXEL_CENTER, top + PIXEL_CENTER), "transparent pixel");
        Assertions.assertFalse(opaqueLeft.isHit(PIXEL_CENTER, top - PIXEL_CENTER), "above the console");
        Assertions.assertFalse(opaqueLeft.isHit(-PIXEL_CENTER, top + PIXEL_CENTER), "left of the console");
        Assertions.assertFalse(faint.isHit(PIXEL_CENTER, top + PIXEL_CENTER), "alpha below the hit alpha");
    }

    @Test
    void is_hit_should_test_each_hd_piece_at_its_placed_position() {
        InterfaceImage side = createImage(SIDE_PIECE_WIDTH, PIECE_HEIGHT, OPAQUE_ALPHA);
        InterfaceImage transparentSide = createImage(SIDE_PIECE_WIDTH, PIECE_HEIGHT, 0);
        InterfaceImage center = createImage(CENTER_PIECE_WIDTH, PIECE_HEIGHT, OPAQUE_ALPHA);
        ConsoleArt art = ConsoleArt.ofPieces(new InterfaceImage[]{side, center, transparentSide}, HD2_DIVISOR);

        art.placePieces(ConsoleLayout.INTERFACE_WIDTH, HdConsoleLayout.Variant.WIDE);

        float leftTop = art.getTop(ConsoleArt.LEFT_PIECE) + PIXEL_CENTER;
        float rightX = art.getLeft(ConsoleArt.RIGHT_PIECE) + art.getWidth(ConsoleArt.RIGHT_PIECE) - PIXEL_CENTER;
        float centerMiddle = art.getLeft(ConsoleArt.CENTER_PIECE) + art.getWidth(ConsoleArt.CENTER_PIECE) / CENTER_DIVISOR;

        Assertions.assertEquals(SIDE_PIECE_WIDTH / (float) HD2_SCALE, art.getWidth(ConsoleArt.LEFT_PIECE));
        Assertions.assertTrue(art.isHit(art.getLeft(ConsoleArt.LEFT_PIECE) + PIXEL_CENTER, leftTop), "left piece");
        Assertions.assertFalse(art.isHit(rightX, art.getTop(ConsoleArt.RIGHT_PIECE) + PIXEL_CENTER), "transparent right piece");
        Assertions.assertTrue(art.isHit(centerMiddle, art.getTop(ConsoleArt.CENTER_PIECE) + PIXEL_CENTER), "center piece");
        Assertions.assertFalse(art.isHit(centerMiddle, art.getTop(ConsoleArt.CENTER_PIECE) - PIXEL_CENTER), "above every piece");
    }

    @Test
    void a_picture_should_keep_the_piece_place_and_size_and_give_the_hit_mask() {
        ConsoleArt art = ConsoleArt.ofStandardDefinition(createImage(STANDARD_WIDTH, STANDARD_HEIGHT, OPAQUE_ALPHA));
        // Four pixels wide: the right half of the piece is transparent.
        Picture picture = new Picture(4, 1, PixelLayer.ofRgba(new int[]{OPAQUE_ARGB, OPAQUE_ARGB, 0, 0}));
        float top = INTERFACE_HEIGHT - STANDARD_HEIGHT;

        Assertions.assertFalse(art.hasPicture());

        art.setPicture(ConsoleArt.LEFT_PIECE, picture);

        Assertions.assertTrue(art.hasPicture());
        Assertions.assertSame(picture, art.findPicture(ConsoleArt.LEFT_PIECE));
        Assertions.assertEquals(STANDARD_WIDTH, art.getWidth(ConsoleArt.LEFT_PIECE));
        Assertions.assertTrue(art.isHit(PIXEL_CENTER, top + PIXEL_CENTER));
        Assertions.assertFalse(art.isHit(1 + PIXEL_CENTER, top + PIXEL_CENTER), "transparent half of the picture");

        art.setPicture(ConsoleArt.LEFT_PIECE, null);

        Assertions.assertFalse(art.hasPicture());
        Assertions.assertTrue(art.isHit(1 + PIXEL_CENTER, top + PIXEL_CENTER), "the image again");
    }

    @Test
    void a_picture_should_give_the_hit_mask_of_its_hd_piece_only() {
        InterfaceImage side = createImage(SIDE_PIECE_WIDTH, PIECE_HEIGHT, OPAQUE_ALPHA);
        InterfaceImage center = createImage(CENTER_PIECE_WIDTH, PIECE_HEIGHT, OPAQUE_ALPHA);
        ConsoleArt art = ConsoleArt.ofPieces(new InterfaceImage[]{side, center, side}, HD2_DIVISOR);

        art.placePieces(ConsoleLayout.INTERFACE_WIDTH, HdConsoleLayout.Variant.WIDE);
        art.setPicture(ConsoleArt.RIGHT_PIECE, new Picture(1, 1, PixelLayer.ofRgba(new int[]{0})));

        float rightX = art.getLeft(ConsoleArt.RIGHT_PIECE) + art.getWidth(ConsoleArt.RIGHT_PIECE) - PIXEL_CENTER;

        Assertions.assertEquals(SIDE_PIECE_WIDTH / (float) HD2_SCALE, art.getWidth(ConsoleArt.RIGHT_PIECE));
        Assertions.assertFalse(art.isHit(rightX, art.getTop(ConsoleArt.RIGHT_PIECE) + PIXEL_CENTER), "transparent picture of the right piece");
        Assertions.assertTrue(art.isHit(art.getLeft(ConsoleArt.LEFT_PIECE) + PIXEL_CENTER, art.getTop(ConsoleArt.LEFT_PIECE) + PIXEL_CENTER),
                              "left piece image");
    }

    @Test
    void place_pieces_should_reject_the_standard_definition_art() {
        ConsoleArt art = ConsoleArt.ofStandardDefinition(createImage(STANDARD_WIDTH, STANDARD_HEIGHT, OPAQUE_ALPHA));

        Assertions.assertThrows(IllegalStateException.class, () -> art.placePieces(ConsoleLayout.INTERFACE_WIDTH, HdConsoleLayout.Variant.WIDE));
    }
}
