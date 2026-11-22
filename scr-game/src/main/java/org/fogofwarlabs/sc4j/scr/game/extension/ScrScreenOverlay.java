package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

/**
 * A picture that a plugin draws over the game screen and its console: palette indices of a classic screen, rows from the top, each pixel shown
 * once it is set. The change count grows with every change of a shown pixel.
 */
public final class ScrScreenOverlay {
    private static final int BYTE_MASK = 0xFF;

    private final int width;
    private final int height;
    private final byte @NotNull [] pixels;
    private final boolean @NotNull [] shown;

    private int shownCount;
    private long changeCount;

    /**
     * Creates a {@link ScrScreenOverlay} instance with no shown pixel.
     *
     * @param width  the screen width in pixels
     * @param height the screen height in pixels
     * @throws IllegalArgumentException if a size is not positive
     */
    public ScrScreenOverlay(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw Exceptions.newIllegalArgumentException("Invalid screen size {0} by {1}", width, height);
        }

        this.width  = width;
        this.height = height;
        this.pixels = new byte[width * height];
        this.shown  = new boolean[width * height];

        this.shownCount  = 0;
        this.changeCount = 0;
    }

    /**
     * @return the screen width in pixels
     */
    public int getWidth() {
        return this.width;
    }

    /**
     * @return the screen height in pixels
     */
    public int getHeight() {
        return this.height;
    }

    /**
     * @return the number of shown pixels
     */
    public int getShownCount() {
        return this.shownCount;
    }

    /**
     * @return the number of changes of shown pixels so far
     */
    public long getChangeCount() {
        return this.changeCount;
    }

    /**
     * Sets a pixel and shows it.
     *
     * @param offset the pixel offset, {@code row * width + column}
     * @param value  the palette index
     * @throws IndexOutOfBoundsException if the offset is outside the screen
     */
    public void setPixel(int offset, int value) {
        byte index = (byte) value;

        if (this.shown[offset] && this.pixels[offset] == index) {
            return;
        }

        if (!this.shown[offset]) {
            this.shown[offset] = true;
            this.shownCount++;
        }

        this.pixels[offset] = index;
        this.changeCount++;
    }

    /**
     * Hides a pixel.
     *
     * @param offset the pixel offset, {@code row * width + column}
     * @throws IndexOutOfBoundsException if the offset is outside the screen
     */
    public void hidePixel(int offset) {
        if (!this.shown[offset]) {
            return;
        }

        this.shown[offset] = false;
        this.shownCount--;
        this.changeCount++;
    }

    /**
     * @param offset the pixel offset, {@code row * width + column}
     * @return {@code true} if the pixel is shown, {@code false} otherwise
     * @throws IndexOutOfBoundsException if the offset is outside the screen
     */
    public boolean isShown(int offset) {
        return this.shown[offset];
    }

    /**
     * @param offset the pixel offset, {@code row * width + column}
     * @return the palette index of the pixel
     * @throws IndexOutOfBoundsException if the offset is outside the screen
     */
    public int getPixel(int offset) {
        return this.pixels[offset] & BYTE_MASK;
    }
}
