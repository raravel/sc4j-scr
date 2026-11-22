package org.fogofwarlabs.sc4j.scr.data.format.image;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Represents a parsed StarCraft GRP sprite file.
 * <p>
 * This parser decodes the standard scanline RLE format used by unit and sprite GRPs. Palette indices are returned unchanged;
 * palette lookup and rendering belong to the renderer module.
 */
public final class GrpFile {
    private static final int BYTE_MASK = 0xFF;
    private static final int HEADER_SIZE = 6;
    private static final int WIDTH_OFFSET = 2;
    private static final int HEIGHT_OFFSET = 4;
    private static final int FRAME_HEADER_SIZE = 8;
    private static final int FRAME_Y_OFFSET = 1;
    private static final int FRAME_WIDTH_OFFSET = 2;
    private static final int FRAME_HEIGHT_OFFSET = 3;
    private static final int FRAME_DATA_OFFSET = 4;
    private static final int LINE_OFFSET_SIZE = 2;

    /**
     * The packet flag of a transparent run.
     */
    private static final int PACKET_TRANSPARENT = 0x80;
    /**
     * The packet flag of a run that repeats one palette index.
     */
    private static final int PACKET_REPEAT = 0x40;
    /**
     * The packet type of a run of literal palette indices.
     */
    private static final int PACKET_LITERAL = 0x00;
    private static final int TRANSPARENT_COUNT_MASK = 0x7F;
    private static final int COUNT_MASK = 0x3F;
    private static final long DWORD_MASK = 0xFFFFFFFFL;

    /**
     * Represents a decoded GRP frame on its full canvas.
     */
    public static final class Frame {
        private final int xOffset;
        private final int yOffset;

        private final int width;
        private final int height;

        private final int frameWidth;
        private final int frameHeight;

        private final byte @NotNull [] pixels;
        private final byte @NotNull [] coverage;

        /**
         * Creates a {@link Frame} instance.
         *
         * @param xOffset     the frame x offset in the GRP canvas
         * @param yOffset     the frame y offset in the GRP canvas
         * @param width       the canvas width
         * @param height      the canvas height
         * @param frameWidth  the encoded rectangle width
         * @param frameHeight the encoded rectangle height
         * @param pixels      the palette indices in row-major order
         * @param coverage    the opaque RLE coverage on the canvas
         */
        private Frame(int xOffset, int yOffset,
                      int width, int height,
                      int frameWidth, int frameHeight,
                      byte @NotNull [] pixels, byte @NotNull [] coverage) {
            this.xOffset = xOffset;
            this.yOffset = yOffset;

            this.width  = width;
            this.height = height;

            this.frameWidth  = frameWidth;
            this.frameHeight = frameHeight;

            this.pixels   = pixels;
            this.coverage = coverage;
        }

        /**
         * @return the frame x offset in the GRP canvas
         */
        public int getXOffset() {
            return this.xOffset;
        }

        /**
         * @return the frame y offset in the GRP canvas
         */
        public int getYOffset() {
            return this.yOffset;
        }

        /**
         * @return the canvas width
         */
        public int getWidth() {
            return this.width;
        }

        /**
         * @return the canvas height
         */
        public int getHeight() {
            return this.height;
        }

        /**
         * @return the encoded frame rectangle width
         */
        public int getFrameWidth() {
            return this.frameWidth;
        }

        /**
         * @return the encoded frame rectangle height
         */
        public int getFrameHeight() {
            return this.frameHeight;
        }

        /**
         * @return the palette indices in row-major order
         */
        public byte @NotNull [] getPixels() {
            return this.pixels;
        }

        /**
         * @return the canvas coverage, {@code 1} for an opaque RLE pixel and {@code 0} for a transparent run
         */
        public byte @NotNull [] getCoverage() {
            return this.coverage;
        }
    }

    /**
     * Creates a {@link GrpFile} instance from the given GRP file content.
     *
     * @param data the GRP file content
     * @return the created {@link GrpFile} instance
     * @throws IOException if the GRP header or frame table is malformed
     */
    public static @NotNull GrpFile parse(byte @NotNull [] data) throws IOException {
        return parse(data, 0);
    }

    /**
     * Creates a {@link GrpFile} instance from GRP bytes whose frame data offsets count from a base: {@code 0} for a file, the address of the
     * first byte for a GRP in memory, whose offsets are absolute addresses.
     *
     * @param data the GRP bytes
     * @param base the unsigned base the frame data offsets count from
     * @return the created {@link GrpFile} instance
     * @throws IOException if the GRP header or frame table is malformed
     */
    public static @NotNull GrpFile parse(byte @NotNull [] data, int base) throws IOException {
        if (data.length < HEADER_SIZE) {
            throw Exceptions.newIOException("The GRP file is too small: {0}", data.length);
        }

        int frameCount = LittleEndianUtils.readUnsigned16(data, 0);
        int width = LittleEndianUtils.readUnsigned16(data, WIDTH_OFFSET);
        int height = LittleEndianUtils.readUnsigned16(data, HEIGHT_OFFSET);
        int frameTableOffset = HEADER_SIZE;
        long frameTableEnd = (long) frameTableOffset + (long) frameCount * FRAME_HEADER_SIZE;

        if (frameTableEnd > data.length) {
            throw Exceptions.newIOException("The GRP frame table exceeds the file size.");
        }

        return new GrpFile(data, base, frameCount, width, height, frameTableOffset);
    }


    private final byte @NotNull [] data;
    private final long base;
    private final int frameCount;

    private final int width;
    private final int height;

    private final int frameTableOffset;

    /**
     * Creates a {@link GrpFile} instance.
     *
     * @param data             the GRP file content
     * @param base             the unsigned base the frame data offsets count from
     * @param frameCount       the frame count
     * @param width            the canvas width
     * @param height           the canvas height
     * @param frameTableOffset the frame table offset
     */
    private GrpFile(byte @NotNull [] data, int base, int frameCount, int width, int height, int frameTableOffset) {
        this.data       = data;
        this.base       = base & DWORD_MASK;
        this.frameCount = frameCount;

        this.width  = width;
        this.height = height;

        this.frameTableOffset = frameTableOffset;
    }

    /**
     * @return the frame count
     */
    public int getFrameCount() {
        return this.frameCount;
    }

    /**
     * @return the canvas width
     */
    public int getWidth() {
        return this.width;
    }

    /**
     * @return the canvas height
     */
    public int getHeight() {
        return this.height;
    }

    /**
     * @param frameIndex the frame index
     * @return the encoded frame rectangle horizontal offset inside the canvas
     */
    public int getFrameXOffset(int frameIndex) {
        return this.readFrameByte(frameIndex, 0);
    }

    /**
     * @param frameIndex the frame index
     * @return the encoded frame rectangle vertical offset inside the canvas
     */
    public int getFrameYOffset(int frameIndex) {
        return this.readFrameByte(frameIndex, FRAME_Y_OFFSET);
    }

    /**
     * @param frameIndex the frame index
     * @return the encoded frame rectangle width, excluding canvas margins
     */
    public int getFrameWidth(int frameIndex) {
        return this.readFrameByte(frameIndex, FRAME_WIDTH_OFFSET);
    }

    /**
     * @param frameIndex the frame index
     * @return the encoded frame rectangle height, excluding canvas margins
     */
    public int getFrameHeight(int frameIndex) {
        return this.readFrameByte(frameIndex, FRAME_HEIGHT_OFFSET);
    }

    /**
     * @param frameIndex the frame index
     * @return the offset of the frame's line table in the GRP bytes; frames that repeat another frame share it
     * @throws IndexOutOfBoundsException if the frame index is outside the file
     */
    public int getFrameDataOffset(int frameIndex) {
        this.checkFrameIndex(frameIndex);

        return (int) this.readDataOffset(this.frameTableOffset + frameIndex * FRAME_HEADER_SIZE);
    }

    /**
     * Tests compressed-run coverage at a frame-relative point.
     * A point exactly at a run's end belongs to that run; palette index zero remains opaque.
     *
     * @param frameIndex the frame index
     * @param positionX  the frame-relative horizontal point
     * @param positionY  the frame-relative scanline
     * @return {@code true} if the selected run is opaque, {@code false} otherwise
     * @throws IOException if the compressed scanline is malformed
     */
    public boolean isFramePointCovered(int frameIndex, int positionX, int positionY) throws IOException {
        if (positionX < 0 || positionY < 0 || positionX >= this.getFrameWidth(frameIndex) || positionY >= this.getFrameHeight(frameIndex)) {
            return false;
        }

        long dataOffset = this.readDataOffset(this.frameTableOffset + frameIndex * FRAME_HEADER_SIZE);
        long lineOffset = dataOffset + positionY * (long) LINE_OFFSET_SIZE;

        if (lineOffset < 0 || lineOffset + LINE_OFFSET_SIZE > this.data.length) {
            throw Exceptions.newIOException("The GRP coverage scanline table is truncated.");
        }

        long cursor = dataOffset + LittleEndianUtils.readUnsigned16(this.data, (int) lineOffset);
        int remaining = positionX;

        while (true) {
            if (cursor < 0 || cursor >= this.data.length) {
                throw Exceptions.newIOException("The GRP coverage scanline is truncated.");
            }

            int packet = this.data[(int) cursor++] & BYTE_MASK;
            int count = packet & ((packet & PACKET_TRANSPARENT) != 0 ? TRANSPARENT_COUNT_MASK : COUNT_MASK);

            if (count == 0) {
                throw Exceptions.newIOException("The GRP coverage scanline contains an empty run.");
            }

            remaining -= count;

            if (remaining <= 0) {
                return (packet & PACKET_TRANSPARENT) == 0;
            }

            if ((packet & PACKET_TRANSPARENT) == 0) {
                cursor += (packet & PACKET_REPEAT) != 0 ? 1 : count;
            }
        }
    }


    /**
     * Decodes the frame at the given index.
     *
     * @param frameIndex the frame index
     * @return the decoded frame
     * @throws IOException               if the frame data is malformed or uses an unsupported packet
     * @throws IndexOutOfBoundsException if the frame index is outside the file
     */
    public @NotNull Frame decodeFrame(int frameIndex) throws IOException {
        this.checkFrameIndex(frameIndex);

        int headerOffset = this.frameTableOffset + frameIndex * FRAME_HEADER_SIZE;
        int xOffset = this.data[headerOffset] & BYTE_MASK;
        int yOffset = this.data[headerOffset + FRAME_Y_OFFSET] & BYTE_MASK;
        int frameWidth = this.data[headerOffset + FRAME_WIDTH_OFFSET] & BYTE_MASK;
        int frameHeight = this.data[headerOffset + FRAME_HEIGHT_OFFSET] & BYTE_MASK;
        long imageDataOffsetLong = this.readDataOffset(headerOffset);

        if (imageDataOffsetLong < 0 || imageDataOffsetLong > this.data.length) {
            throw Exceptions.newIOException("The GRP image data offset is out of bounds for frame {0}", frameIndex);
        }

        int imageDataOffset = (int) imageDataOffsetLong;
        byte[] pixels = new byte[this.width * this.height];
        byte[] coverage = new byte[pixels.length];

        if (frameWidth == 0 || frameHeight == 0) {
            return new Frame(xOffset, yOffset, this.width, this.height, frameWidth, frameHeight, pixels, coverage);
        }

        if (xOffset + frameWidth > this.width || yOffset + frameHeight > this.height) {
            throw Exceptions.newIOException("The GRP frame rectangle exceeds the canvas for frame {0}", frameIndex);
        }

        long lineTableEnd = (long) imageDataOffset + (long) frameHeight * LINE_OFFSET_SIZE;

        if (lineTableEnd > this.data.length) {
            throw Exceptions.newIOException("The GRP line table exceeds the file size for frame {0}", frameIndex);
        }

        for (int lineIndex = 0; lineIndex < frameHeight; lineIndex++) {
            int lineOffset = LittleEndianUtils.readUnsigned16(this.data, imageDataOffset + lineIndex * LINE_OFFSET_SIZE);
            int sourceOffset = imageDataOffset + lineOffset;
            int targetX = 0;
            int destinationY = yOffset + lineIndex;

            while (targetX < frameWidth) {
                if (sourceOffset >= this.data.length) {
                    throw Exceptions.newIOException("The GRP scanline is truncated in frame {0}", frameIndex);
                }

                int packet = this.data[sourceOffset++] & BYTE_MASK;
                int packetType = (packet & PACKET_TRANSPARENT) != 0 ? PACKET_TRANSPARENT : packet & PACKET_REPEAT;
                int packetCount = packet & (packetType == PACKET_TRANSPARENT ? TRANSPARENT_COUNT_MASK : COUNT_MASK);

                if (packetCount == 0) {
                    continue;
                }

                if (targetX + packetCount > frameWidth) {
                    throw Exceptions.newIOException("The GRP packet exceeds scanline width in frame {0}", frameIndex);
                }

                if (packetType != PACKET_TRANSPARENT) {
                    Arrays.fill(coverage,
                                destinationY * this.width + xOffset + targetX, destinationY * this.width + xOffset + targetX + packetCount,
                                (byte) 1);
                }

                switch (packetType) {
                    case PACKET_LITERAL:
                        if (sourceOffset + packetCount > this.data.length) {
                            throw Exceptions.newIOException("The GRP literal packet is truncated in frame {0}", frameIndex);
                        }

                        System.arraycopy(this.data, sourceOffset, pixels, destinationY * this.width + xOffset + targetX, packetCount);
                        sourceOffset += packetCount;
                        targetX += packetCount;

                        break;

                    case PACKET_REPEAT:
                        if (sourceOffset >= this.data.length) {
                            throw Exceptions.newIOException("The GRP repeat packet is truncated in frame {0}", frameIndex);
                        }

                        byte color = this.data[sourceOffset++];
                        int pixelOffset = destinationY * this.width + xOffset + targetX;

                        for (int repeatIndex = 0; repeatIndex < packetCount; repeatIndex++) {
                            pixels[pixelOffset + repeatIndex] = color;
                        }

                        targetX += packetCount;

                        break;

                    case PACKET_TRANSPARENT:
                        targetX += packetCount;

                        break;

                    default:
                        throw Exceptions.newIOException("Unsupported GRP packet type 0x{0} in frame {1}",
                                                        Integer.toHexString(packetType), frameIndex);
                }
            }
        }

        return new Frame(xOffset, yOffset, this.width, this.height, frameWidth, frameHeight, pixels, coverage);
    }

    /**
     * Decodes every frame in the GRP file.
     *
     * @return all decoded frames
     * @throws IOException if any frame is malformed
     */
    public @NotNull List<Frame> decodeAllFrames() throws IOException {
        List<Frame> frames = new ArrayList<>(this.frameCount);

        for (int frameIndex = 0; frameIndex < this.frameCount; frameIndex++) {
            frames.add(this.decodeFrame(frameIndex));
        }

        return Collections.unmodifiableList(frames);
    }

    /**
     * Reads the line table offset of a frame header relative to the GRP bytes.
     *
     * @param headerOffset the offset of the frame header
     * @return the offset in the GRP bytes, negative when the stored offset lies before the base
     */
    private long readDataOffset(int headerOffset) {
        return LittleEndianUtils.readUnsigned32(this.data, headerOffset + FRAME_DATA_OFFSET) - this.base;
    }

    private int readFrameByte(int frameIndex, int fieldOffset) {
        this.checkFrameIndex(frameIndex);

        return this.data[this.frameTableOffset + frameIndex * FRAME_HEADER_SIZE + fieldOffset] & BYTE_MASK;
    }

    private void checkFrameIndex(int frameIndex) {
        if (frameIndex < 0 || frameIndex >= this.frameCount) {
            throw Exceptions.newIndexOutOfBoundsException("Invalid GRP frame index: {0}", frameIndex);
        }
    }
}
