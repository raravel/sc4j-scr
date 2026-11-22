package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.scr.game.extension.ScrScreenOverlay;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * The texture of a screen overlay of a plugin ({@link ScrScreenOverlay}): each shown pixel in a palette, the others transparent. The texels are
 * uploaded again when the overlay, its change count or the palette changed.
 */
final class ScreenOverlayTexture {
    /**
     * The alpha of a shown texel.
     */
    private static final int OPAQUE = 0xFF;

    private int texture;
    private @Nullable ScrScreenOverlay drawn;
    private long change;
    private final int @NotNull [] palette;
    private int @NotNull [] texels;

    /**
     * Creates a {@link ScreenOverlayTexture} instance without a texture.
     *
     * @param colorCount the number of palette colors
     */
    ScreenOverlayTexture(int colorCount) {
        this.texture = 0;
        this.drawn   = null;
        this.change  = -1;
        this.palette = new int[colorCount];
        this.texels  = new int[0];
    }

    /**
     * Uploads the overlay's texels when they changed.
     *
     * @param renderer the interface renderer
     * @param overlay  the overlay
     * @param palette  the palette of this frame, colors packed {@code 0x00BBGGRR}
     * @return the texture
     */
    int update(@NotNull InterfaceRenderer renderer, @NotNull ScrScreenOverlay overlay, int @NotNull [] palette) {
        int width = overlay.getWidth();
        int height = overlay.getHeight();
        boolean resized = this.texels.length != width * height;

        if (resized) {
            if (this.texture != 0) {
                renderer.deleteTexture(this.texture);
            }

            this.texture = renderer.createDynamicTexture(width, height, false);
            this.texels  = new int[width * height];
        }

        if (resized || overlay != this.drawn || overlay.getChangeCount() != this.change || !Arrays.equals(palette, this.palette)) {
            System.arraycopy(palette, 0, this.palette, 0, this.palette.length);

            for (int offset = 0; offset < this.texels.length; offset++) {
                // Palette colors are packed 0x00BBGGRR; texels are packed 0xRRGGBBAA.
                this.texels[offset] = overlay.isShown(offset) ? Integer.reverseBytes(this.palette[overlay.getPixel(offset)]) | OPAQUE : 0;
            }

            renderer.updateTexture(this.texture, width, height, this.texels);
            this.drawn  = overlay;
            this.change = overlay.getChangeCount();
        }

        return this.texture;
    }
}
