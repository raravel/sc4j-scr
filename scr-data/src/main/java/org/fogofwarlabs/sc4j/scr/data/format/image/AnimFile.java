package org.fogofwarlabs.sc4j.scr.data.format.image;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Reads animation entries, frame rectangles and embedded texture layers while borrowing the source array.
 */
public final class AnimFile {
    private static final int BYTE_MASK = 0xFF;
    private static final int MAGIC = 0x4D494E41;
    private static final int SCALE_OFFSET = 4;
    private static final int TYPE_OFFSET = 5;
    private static final int LAYER_COUNT_OFFSET = 8;
    private static final int ENTRY_COUNT_OFFSET = 10;
    private static final int LAYER_NAMES_OFFSET = 12;
    private static final int LAYER_NAME_COUNT = 10;
    private static final int LAYER_NAME_SIZE = 32;
    private static final int HEADER_SIZE = LAYER_NAMES_OFFSET + LAYER_NAME_COUNT * LAYER_NAME_SIZE;
    /**
     * The file type that stores one entry offset per entry after the header.
     */
    private static final int TYPE_ENTRY_TABLE = 1;
    private static final int ENTRY_OFFSET_SIZE = 4;

    private static final int ENTRY_REFERENCE_IMAGE_OFFSET = 2;
    private static final int ENTRY_WIDTH_OFFSET = 4;
    private static final int ENTRY_HEIGHT_OFFSET = 6;
    private static final int ENTRY_FRAME_TABLE_OFFSET = 8;
    private static final int ENTRY_HEADER_SIZE = 12;
    private static final int LAYER_RECORD_SIZE = 12;
    private static final int LAYER_SIZE_OFFSET = 4;
    private static final int LAYER_WIDTH_OFFSET = 8;
    private static final int LAYER_HEIGHT_OFFSET = 10;

    private static final int FRAME_SIZE = 16;
    private static final int FRAME_Y_OFFSET = 2;
    private static final int FRAME_X_OFFSET_OFFSET = 4;
    private static final int FRAME_Y_OFFSET_OFFSET = 6;
    private static final int FRAME_WIDTH_OFFSET = 8;
    private static final int FRAME_HEIGHT_OFFSET = 10;
    private static final int FRAME_FLAGS_OFFSET = 12;

    /**
     * The values kept per frame, in the order of the {@code FRAME_VALUE_*} indices.
     */
    private static final int FRAME_VALUES = 7;
    private static final int FRAME_VALUE_X = 0;
    private static final int FRAME_VALUE_Y = 1;
    private static final int FRAME_VALUE_X_OFFSET = 2;
    private static final int FRAME_VALUE_Y_OFFSET = 3;
    private static final int FRAME_VALUE_WIDTH = 4;
    private static final int FRAME_VALUE_HEIGHT = 5;
    private static final int FRAME_VALUE_FLAGS = 6;

    /**
     * Holds one image entry of an anim file.
     */
    public static final class Entry {
        private final byte @NotNull [] data;

        private final int referenceImage;

        private final int width;
        private final int height;

        private final int @NotNull [] frames;

        private final int @NotNull [] layerOffsets;
        private final int @NotNull [] layerSizes;

        private final int @NotNull [] layerWidths;
        private final int @NotNull [] layerHeights;

        private Entry(byte @NotNull [] data,
                      int referenceImage,
                      int width, int height,
                      int @NotNull [] frames,
                      int @NotNull [] layerOffsets, int @NotNull [] layerSizes,
                      int @NotNull [] layerWidths, int @NotNull [] layerHeights) {
            this.data = data;

            this.referenceImage = referenceImage;

            this.width  = width;
            this.height = height;

            this.frames = frames;

            this.layerOffsets = layerOffsets;
            this.layerSizes   = layerSizes;

            this.layerWidths  = layerWidths;
            this.layerHeights = layerHeights;
        }

        /**
         * @return {@code true} if this entry reuses another image's entry, {@code false} otherwise
         */
        public boolean isReference() {
            return this.referenceImage >= 0;
        }

        /**
         * @return the referenced image, or {@code -1} when this entry stores its own frames
         */
        public int getReferenceImage() {
            return this.referenceImage;
        }

        /**
         * @return the canvas width in HD pixels, or {@code 0} when the canvas is the classic GRP canvas
         */
        public int getWidth() {
            return this.width;
        }

        /**
         * @return the canvas height in HD pixels, or {@code 0} when the canvas is the classic GRP canvas
         */
        public int getHeight() {
            return this.height;
        }

        /**
         * @return the number of frames
         */
        public int getFrameCount() {
            return this.frames.length / FRAME_VALUES;
        }

        /**
         * @param frameIndex the frame index
         * @return the frame rectangle's left texture coordinate in HD pixels
         */
        public int getFrameX(int frameIndex) {
            return this.frames[frameIndex * FRAME_VALUES + FRAME_VALUE_X];
        }

        /**
         * @param frameIndex the frame index
         * @return the frame rectangle's top texture coordinate in HD pixels
         */
        public int getFrameY(int frameIndex) {
            return this.frames[frameIndex * FRAME_VALUES + FRAME_VALUE_Y];
        }

        /**
         * @param frameIndex the frame index
         * @return the rectangle's horizontal offset inside the canvas in HD pixels
         */
        public int getFrameXOffset(int frameIndex) {
            return this.frames[frameIndex * FRAME_VALUES + FRAME_VALUE_X_OFFSET];
        }

        /**
         * @param frameIndex the frame index
         * @return the rectangle's vertical offset inside the canvas in HD pixels
         */
        public int getFrameYOffset(int frameIndex) {
            return this.frames[frameIndex * FRAME_VALUES + FRAME_VALUE_Y_OFFSET];
        }

        /**
         * @param frameIndex the frame index
         * @return the rectangle width in HD pixels
         */
        public int getFrameWidth(int frameIndex) {
            return this.frames[frameIndex * FRAME_VALUES + FRAME_VALUE_WIDTH];
        }

        /**
         * @param frameIndex the frame index
         * @return the rectangle height in HD pixels
         */
        public int getFrameHeight(int frameIndex) {
            return this.frames[frameIndex * FRAME_VALUES + FRAME_VALUE_HEIGHT];
        }

        /**
         * @param frameIndex the frame index
         * @return the frame record's trailing flag word
         */
        public int getFrameFlags(int frameIndex) {
            return this.frames[frameIndex * FRAME_VALUES + FRAME_VALUE_FLAGS];
        }

        /**
         * @param layer the layer index
         * @return the recorded texture width of the layer
         */
        public int getLayerWidth(int layer) {
            return this.layerWidths[layer];
        }

        /**
         * @param layer the layer index
         * @return the recorded texture height of the layer
         */
        public int getLayerHeight(int layer) {
            return this.layerHeights[layer];
        }

        /**
         * @param layer the layer index
         * @return {@code true} if the layer stores a texture, {@code false} otherwise
         */
        public boolean hasLayer(int layer) {
            return layer >= 0 && layer < this.layerSizes.length && this.layerSizes[layer] != 0;
        }

        /**
         * Parses a layer's embedded DDS texture.
         *
         * @param layer the layer index
         * @return the parsed {@link DdsFile} instance, or {@code null} when the layer is absent
         * @throws IOException if the embedded texture is invalid
         */
        public @Nullable DdsFile getLayerTexture(int layer) throws IOException {
            if (!this.hasLayer(layer)) {
                return null;
            }

            return DdsFile.parse(this.data, this.layerOffsets[layer], this.layerSizes[layer]);
        }
    }

    /**
     * Creates an {@link AnimFile} instance from anim file content.
     *
     * @param data the anim file content
     * @return the created {@link AnimFile} instance
     * @throws IOException if the header, entry table, textures or frame table exceed the content
     */
    public static @NotNull AnimFile parse(byte @NotNull [] data) throws IOException {
        if (data.length < HEADER_SIZE || LittleEndianUtils.readInt32(data, 0) != MAGIC) {
            throw Exceptions.newIOException("Missing ANIM header.");
        }

        int scale = data[SCALE_OFFSET] & BYTE_MASK;
        int type = data[TYPE_OFFSET] & BYTE_MASK;
        int layerCount = LittleEndianUtils.readUnsigned16(data, LAYER_COUNT_OFFSET);
        int entryCount = LittleEndianUtils.readUnsigned16(data, ENTRY_COUNT_OFFSET);

        if (layerCount > LAYER_NAME_COUNT || scale == 0) {
            throw Exceptions.newIOException("Invalid ANIM scale {0} or layer count {1}", scale, layerCount);
        }

        String[] layerNames = new String[layerCount];

        for (int layer = 0; layer < layerCount; layer++) {
            int start = LAYER_NAMES_OFFSET + layer * LAYER_NAME_SIZE;
            int end = start;

            while (end < start + LAYER_NAME_SIZE && data[end] != 0) {
                end++;
            }

            layerNames[layer] = new String(data, start, end - start, StandardCharsets.US_ASCII);
        }

        Entry[] entries = new Entry[entryCount];

        if (type == TYPE_ENTRY_TABLE) {
            if (HEADER_SIZE + (long) entryCount * ENTRY_OFFSET_SIZE > data.length) {
                throw Exceptions.newIOException("ANIM entry table exceeds the content.");
            }

            for (int entry = 0; entry < entryCount; entry++) {
                entries[entry] = parseEntry(data, LittleEndianUtils.readInt32(data, HEADER_SIZE + entry * ENTRY_OFFSET_SIZE), layerCount);
            }
        } else if (entryCount == 1) {
            entries[0] = parseEntry(data, HEADER_SIZE, layerCount);
        } else {
            throw Exceptions.newIOException("Unsupported ANIM type {0} with {1} entries.", type, entryCount);
        }

        return new AnimFile(scale, type, layerNames, entries);
    }

    /**
     * Finds the size of the anim at the start of some bytes: the end of its header, its first entry's records, its frame table or its layer
     * textures, whichever is last. Bytes that end before the header or the entry's records give the length they must hold to find it.
     *
     * @param data   the bytes, from the anim's start
     * @param length the number of valid bytes in {@code data}
     * @return the anim's size; a value above {@code length} if more bytes are needed to find it; {@code -1} if the bytes hold no anim header or
     * the entry's records are invalid
     */
    public static long findContentSize(byte @NotNull [] data, int length) {
        if (length < Integer.BYTES) {
            return HEADER_SIZE;
        }

        if (LittleEndianUtils.readInt32(data, 0) != MAGIC) {
            return -1;
        }

        if (length < HEADER_SIZE + ENTRY_OFFSET_SIZE) {
            return HEADER_SIZE + ENTRY_OFFSET_SIZE;
        }

        int layerCount = LittleEndianUtils.readUnsigned16(data, LAYER_COUNT_OFFSET);

        if (layerCount > LAYER_NAME_COUNT || (data[SCALE_OFFSET] & BYTE_MASK) == 0) {
            return -1;
        }

        long entry = (data[TYPE_OFFSET] & BYTE_MASK) == TYPE_ENTRY_TABLE ? LittleEndianUtils.readUnsigned32(data, HEADER_SIZE) : HEADER_SIZE;
        long recordsEnd = entry + ENTRY_HEADER_SIZE + (long) layerCount * LAYER_RECORD_SIZE;

        if (entry < HEADER_SIZE || recordsEnd > Integer.MAX_VALUE) {
            return -1;
        }

        if (recordsEnd > length) {
            return recordsEnd;
        }

        int offset = (int) entry;
        int frameCount = LittleEndianUtils.readUnsigned16(data, offset);
        long size = recordsEnd;

        if (frameCount == 0) {
            return size;
        }

        int frameTable = LittleEndianUtils.readInt32(data, offset + ENTRY_FRAME_TABLE_OFFSET);

        if (frameTable < 0) {
            return -1;
        }

        size = Math.max(size, frameTable + (long) frameCount * FRAME_SIZE);

        for (int layer = 0; layer < layerCount; layer++) {
            int record = offset + ENTRY_HEADER_SIZE + layer * LAYER_RECORD_SIZE;
            int layerOffset = LittleEndianUtils.readInt32(data, record);
            int layerSize = LittleEndianUtils.readInt32(data, record + LAYER_SIZE_OFFSET);

            if (layerSize != 0) {
                if (layerOffset < 0 || layerSize < 0) {
                    return -1;
                }

                size = Math.max(size, (long) layerOffset + layerSize);
            }
        }

        return size;
    }

    private static @NotNull Entry parseEntry(byte @NotNull [] data, int offset, int layerCount) throws IOException {
        if (offset < HEADER_SIZE || offset + (long) ENTRY_HEADER_SIZE + layerCount * (long) LAYER_RECORD_SIZE > data.length) {
            throw Exceptions.newIOException("ANIM entry at {0} exceeds the content.", offset);
        }

        int frameCount = LittleEndianUtils.readUnsigned16(data, offset);
        int[] layerOffsets = new int[layerCount];
        int[] layerSizes = new int[layerCount];
        int[] layerWidths = new int[layerCount];
        int[] layerHeights = new int[layerCount];

        if (frameCount == 0) {
            return new Entry(data,
                             LittleEndianUtils.readUnsigned16(data, offset + ENTRY_REFERENCE_IMAGE_OFFSET),
                             0, 0,
                             new int[0],
                             layerOffsets, layerSizes,
                             layerWidths, layerHeights);
        }

        int width = LittleEndianUtils.readUnsigned16(data, offset + ENTRY_WIDTH_OFFSET);
        int height = LittleEndianUtils.readUnsigned16(data, offset + ENTRY_HEIGHT_OFFSET);
        int frameTable = LittleEndianUtils.readInt32(data, offset + ENTRY_FRAME_TABLE_OFFSET);

        for (int layer = 0; layer < layerCount; layer++) {
            int record = offset + ENTRY_HEADER_SIZE + layer * LAYER_RECORD_SIZE;

            layerOffsets[layer] = LittleEndianUtils.readInt32(data, record);
            layerSizes[layer]   = LittleEndianUtils.readInt32(data, record + LAYER_SIZE_OFFSET);
            layerWidths[layer]  = LittleEndianUtils.readUnsigned16(data, record + LAYER_WIDTH_OFFSET);
            layerHeights[layer] = LittleEndianUtils.readUnsigned16(data, record + LAYER_HEIGHT_OFFSET);

            if (layerSizes[layer] != 0 &&
                (layerOffsets[layer] < 0 || layerSizes[layer] < 0 || (long) layerOffsets[layer] + layerSizes[layer] > data.length)) {
                throw Exceptions.newIOException("ANIM layer {0} texture exceeds the content.", layer);
            }
        }

        if (frameTable < 0 || (long) frameTable + (long) frameCount * FRAME_SIZE > data.length) {
            throw Exceptions.newIOException("ANIM frame table exceeds the content.");
        }

        int[] frames = new int[frameCount * FRAME_VALUES];

        for (int frame = 0; frame < frameCount; frame++) {
            int record = frameTable + frame * FRAME_SIZE;
            int base = frame * FRAME_VALUES;

            frames[base + FRAME_VALUE_X]        = LittleEndianUtils.readUnsigned16(data, record);
            frames[base + FRAME_VALUE_Y]        = LittleEndianUtils.readUnsigned16(data, record + FRAME_Y_OFFSET);
            frames[base + FRAME_VALUE_X_OFFSET] = (short) LittleEndianUtils.readUnsigned16(data, record + FRAME_X_OFFSET_OFFSET);
            frames[base + FRAME_VALUE_Y_OFFSET] = (short) LittleEndianUtils.readUnsigned16(data, record + FRAME_Y_OFFSET_OFFSET);
            frames[base + FRAME_VALUE_WIDTH]    = LittleEndianUtils.readUnsigned16(data, record + FRAME_WIDTH_OFFSET);
            frames[base + FRAME_VALUE_HEIGHT]   = LittleEndianUtils.readUnsigned16(data, record + FRAME_HEIGHT_OFFSET);
            frames[base + FRAME_VALUE_FLAGS]    = LittleEndianUtils.readInt32(data, record + FRAME_FLAGS_OFFSET);
        }

        return new Entry(data, -1, width, height, frames, layerOffsets, layerSizes, layerWidths, layerHeights);
    }


    private final int scale;
    private final int type;

    private final @NotNull String @NotNull [] layerNames;
    private final @NotNull Entry @NotNull [] entries;

    private AnimFile(int scale, int type, @NotNull String @NotNull [] layerNames, @NotNull Entry @NotNull [] entries) {
        this.scale = scale;
        this.type  = type;

        this.layerNames = layerNames;
        this.entries    = entries;
    }

    /**
     * @return the asset scale: {@code 4} for HD, {@code 2} for HD2 and {@code 1} for SD
     */
    public int getScale() {
        return this.scale;
    }

    /**
     * @return the file type byte
     */
    public int getType() {
        return this.type;
    }

    /**
     * @return the number of layers
     */
    public int getLayerCount() {
        return this.layerNames.length;
    }

    /**
     * @param layer the layer index
     * @return the layer name
     */
    public @NotNull String getLayerName(int layer) {
        return this.layerNames[layer];
    }

    /**
     * @return the number of entries
     */
    public int getEntryCount() {
        return this.entries.length;
    }

    /**
     * @param entryIndex the entry index
     * @return the entry
     */
    public @NotNull Entry getEntry(int entryIndex) {
        return this.entries[entryIndex];
    }


    /**
     * Finds a layer by name.
     *
     * @param name the layer name, for example {@code "teamcolor"}
     * @return the layer index, or {@code -1} when the file has no such layer
     */
    public int findLayer(@NotNull String name) {
        for (int layer = 0; layer < this.layerNames.length; layer++) {
            if (this.layerNames[layer].equals(name)) {
                return layer;
            }
        }

        return -1;
    }
}
