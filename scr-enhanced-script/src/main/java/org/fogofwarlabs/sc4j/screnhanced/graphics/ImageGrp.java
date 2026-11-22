package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageSheet;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * A classic GRP file that an image draws in the classic graphics instead of its installed GRP ({@link ImageGraphics}): the bytes of a GRP file in a
 * byte range whose frame table offsets count from the file's first byte. The frames are read as {@link EmbeddedGrp} reads a GRP in game memory,
 * each decoded when it is drawn; the range is not copied and never changes.
 */
public final class ImageGrp implements ScrImageSheet {
    /**
     * Reads a GRP file from a byte range after checking that it is a GRP header with a readable frame table and that every frame decodes.
     *
     * @param data   the bytes, not copied and not changed afterwards
     * @param offset the first byte of the GRP file
     * @param length the size of the GRP file
     * @param name   the name of the GRP for messages
     * @return the read {@link ImageGrp} instance
     * @throws IOException               if the bytes are no GRP header with a readable frame table or a frame cannot be decoded
     * @throws IndexOutOfBoundsException if the range lies outside the bytes
     */
    public static @NotNull ImageGrp read(byte @NotNull [] data, int offset, int length, @NotNull String name) throws IOException {
        EmbeddedGrp grp = new EmbeddedGrp(EmbeddedMemory.of(data, offset, length), 0);

        if (!grp.isGrp()) {
            throw Exceptions.newIOException("The GRP {0} has no GRP header with a readable frame table.", name);
        }

        for (int frameIndex = 0; frameIndex < grp.getFrameCount(); frameIndex++) {
            try {
                grp.getFrame(frameIndex);
            } catch (IOException exception) {
                throw Exceptions.newIOException(exception, "Frame {0} of the GRP {1} cannot be decoded.", frameIndex, name);
            }
        }

        return new ImageGrp(name, grp);
    }


    private final @NotNull String name;
    private final @NotNull EmbeddedGrp grp;

    /**
     * Creates an {@link ImageGrp} instance.
     *
     * @param name the name of the GRP
     * @param grp  the checked GRP
     */
    private ImageGrp(@NotNull String name, @NotNull EmbeddedGrp grp) {
        this.name = name;
        this.grp  = grp;
    }

    /**
     * @return the name of the GRP
     */
    public @NotNull String getName() {
        return this.name;
    }

    /**
     * @return the number of frames
     */
    @Override
    public int getFrameCount() {
        return this.grp.getFrameCount();
    }

    /**
     * Returns one frame as a one-frame indexed sheet. The same instance is returned while the frame is kept among the frames decoded last, so
     * what a renderer makes of a frame can be kept under that instance.
     *
     * @param frameIndex the frame index, {@code 0} up to {@link #getFrameCount()} minus one
     * @return the one-frame sheet
     * @throws IOException if the index names no frame or the frame cannot be decoded
     */
    @Override
    public @NotNull SpriteSheet getFrame(int frameIndex) throws IOException {
        return this.grp.getFrame(frameIndex);
    }
}
