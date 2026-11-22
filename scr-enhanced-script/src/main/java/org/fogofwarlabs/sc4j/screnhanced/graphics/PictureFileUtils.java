package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.fogofwarlabs.sc4j.scr.data.format.image.DdsFile;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Reads the pictures a map script draws: a PNG file, decoded by the JDK's image reader, or a DDS file in a format the game's DDS reader decodes
 * ({@link DdsFile}). The picture has straight-alpha colours; a picture without alpha is opaque.
 */
public final class PictureFileUtils {
    /**
     * The first bytes of a PNG file.
     */
    private static final byte @NotNull [] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    /**
     * The first bytes of a DDS file.
     */
    private static final byte @NotNull [] DDS_SIGNATURE = {'D', 'D', 'S', ' '};
    private static final int CHANNEL_COUNT = 4;
    private static final int GREEN_CHANNEL = 1;
    private static final int BLUE_CHANNEL = 2;
    private static final int ALPHA_CHANNEL = 3;
    private static final int BYTE_MASK = 0xFF;
    private static final int RED_SHIFT = 16;
    private static final int GREEN_SHIFT = 8;
    private static final int ALPHA_SHIFT = 24;

    /**
     * Checks whether bytes start like a picture file this class reads.
     *
     * @param content the bytes
     * @return {@code true} if the bytes start with the PNG or DDS signature, {@code false} otherwise
     */
    public static boolean isPicture(byte @NotNull [] content) {
        return startsWith(content, PNG_SIGNATURE) || startsWith(content, DDS_SIGNATURE);
    }

    /**
     * Reads a picture file.
     *
     * @param content the bytes of a PNG or DDS file
     * @param name    the name of the file for messages
     * @return the created {@link Picture} instance
     * @throws IOException if the bytes are neither PNG nor DDS, or cannot be decoded
     */
    public static @NotNull Picture read(byte @NotNull [] content, @NotNull String name) throws IOException {
        if (startsWith(content, PNG_SIGNATURE)) {
            return readPng(content, name);
        }

        if (startsWith(content, DDS_SIGNATURE)) {
            return readDds(content, name);
        }

        throw Exceptions.newIOException("The picture {0} is neither a PNG nor a DDS file.", name);
    }

    private static @NotNull Picture readPng(byte @NotNull [] content, @NotNull String name) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));

        if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
            throw Exceptions.newIOException("The PNG {0} cannot be decoded.", name);
        }

        int width = image.getWidth();
        int height = image.getHeight();
        int[] colors = image.getRGB(0, 0, width, height, null, 0, width);

        return new Picture(width, height, PixelLayer.ofRgba(colors));
    }

    private static @NotNull Picture readDds(byte @NotNull [] content, @NotNull String name) throws IOException {
        DdsFile file = DdsFile.parse(content);
        int width = file.getWidth();
        int height = file.getHeight();

        if (width < 1 || height < 1) {
            throw Exceptions.newIOException("The DDS {0} has no pixels.", name);
        }

        byte[] rgba;

        try {
            rgba = file.decodeRgba();
        } catch (RuntimeException exception) {
            throw Exceptions.newIOException(exception, "The DDS {0} cannot be decoded.", name);
        }

        int[] colors = new int[width * height];

        for (int pixel = 0; pixel < colors.length; pixel++) {
            int offset = pixel * CHANNEL_COUNT;

            colors[pixel] = (rgba[offset + ALPHA_CHANNEL] & BYTE_MASK) << ALPHA_SHIFT | (rgba[offset] & BYTE_MASK) << RED_SHIFT |
                            (rgba[offset + GREEN_CHANNEL] & BYTE_MASK) << GREEN_SHIFT | rgba[offset + BLUE_CHANNEL] & BYTE_MASK;
        }

        return new Picture(width, height, PixelLayer.ofRgba(colors));
    }

    private static boolean startsWith(byte @NotNull [] content, byte @NotNull [] signature) {
        if (content.length < signature.length) {
            return false;
        }

        for (int index = 0; index < signature.length; index++) {
            if (content[index] != signature[index]) {
                return false;
            }
        }

        return true;
    }

    private PictureFileUtils() {
    }
}
