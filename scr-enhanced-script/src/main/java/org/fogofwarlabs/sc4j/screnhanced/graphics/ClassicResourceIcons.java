package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteFrame;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The resource icons of the classic console as a map writes them: a GRP whose frames hold their pixels uncompressed, rows from the top. The
 * header is the WORD frame count, WORD width and WORD height; each frame header is the BYTE left, top, width and height of the frame in the frame
 * box and the DWORD offset of its pixels from the GRP's first byte.
 */
final class ClassicResourceIcons {
    private static final int HEADER_SIZE = 6;
    private static final int WIDTH_OFFSET = 2;
    private static final int HEIGHT_OFFSET = 4;
    private static final int FRAME_HEADER_SIZE = 8;
    private static final int FRAME_TOP_OFFSET = 1;
    private static final int FRAME_WIDTH_OFFSET = 2;
    private static final int FRAME_HEIGHT_OFFSET = 3;
    private static final int FRAME_DATA_OFFSET = 4;
    private static final int BYTE_MASK = 0xFF;
    private static final long DWORD_MASK = 0xFFFFFFFFL;

    private ClassicResourceIcons() {
    }

    /**
     * Reads the icons from the bytes of their buffer.
     *
     * @param bytes the buffer, the GRP from its first byte
     * @return the indexed sheet of the frames, or {@code null} if the bytes are not such a GRP
     */
    static @Nullable SpriteSheet parse(byte @NotNull [] bytes) {
        if (bytes.length < HEADER_SIZE) {
            return null;
        }

        int count = LittleEndianUtils.readUnsigned16(bytes, 0);
        int width = LittleEndianUtils.readUnsigned16(bytes, WIDTH_OFFSET);
        int height = LittleEndianUtils.readUnsigned16(bytes, HEIGHT_OFFSET);

        if (count == 0 || width == 0 || height == 0 || HEADER_SIZE + (long) count * FRAME_HEADER_SIZE > bytes.length) {
            return null;
        }

        List<SpriteFrame> frames = new ArrayList<>(count);
        int total = 0;

        for (int frame = 0; frame < count; frame++) {
            int header = HEADER_SIZE + frame * FRAME_HEADER_SIZE;
            int left = bytes[header] & BYTE_MASK;
            int top = bytes[header + FRAME_TOP_OFFSET] & BYTE_MASK;
            int frameWidth = bytes[header + FRAME_WIDTH_OFFSET] & BYTE_MASK;
            int frameHeight = bytes[header + FRAME_HEIGHT_OFFSET] & BYTE_MASK;
            long data = LittleEndianUtils.readInt32(bytes, header + FRAME_DATA_OFFSET) & DWORD_MASK;

            if (left + frameWidth > width || top + frameHeight > height || data + (long) frameWidth * frameHeight > bytes.length) {
                return null;
            }

            frames.add(new SpriteFrame(left, top, frameWidth, frameHeight, total));
            total += frameWidth * frameHeight;
        }

        byte[] pixels = new byte[total];

        for (int frame = 0; frame < count; frame++) {
            SpriteFrame placed = frames.get(frame);
            int data = LittleEndianUtils.readInt32(bytes, HEADER_SIZE + frame * FRAME_HEADER_SIZE + FRAME_DATA_OFFSET);

            System.arraycopy(bytes, data, pixels, placed.getPixelOffset(), placed.getWidth() * placed.getHeight());
        }

        return new SpriteSheet(width, height, 0, 0, frames, PixelLayer.ofIndexed(pixels), null, null);
    }
}
