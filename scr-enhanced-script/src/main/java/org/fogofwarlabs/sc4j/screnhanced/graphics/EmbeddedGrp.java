package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.scr.data.format.image.GrpFile;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageSheet;
import org.fogofwarlabs.sc4j.scr.data.resource.importer.GrpSheetUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.SequencedMap;

/**
 * A classic GRP in game memory at a pointer: the header, the frame table with absolute line table addresses, the line tables and the scanlines.
 * The header and the frame table are read on first use; a frame is read and decoded when it is asked for and kept among the frames asked for last.
 * The writes to the bytes read are observed through the memory view: a header write reads the GRP again, a frame table write changes the frame's
 * entry, and a write to a frame's bytes decodes the frame again on its next use.
 */
final class EmbeddedGrp implements ScrImageSheet {
    private static final int HEADER_SIZE = 6;
    private static final int WIDTH_OFFSET = 2;
    private static final int HEIGHT_OFFSET = 4;
    private static final int FRAME_HEADER_SIZE = 8;
    private static final int FRAME_WIDTH_OFFSET = 2;
    private static final int FRAME_HEIGHT_OFFSET = 3;
    private static final int FRAME_DATA_OFFSET = 4;
    private static final int LINE_OFFSET_SIZE = 2;
    /**
     * The offset of the line table in the one-frame GRP a frame is decoded from: after the header and one frame table entry.
     */
    private static final int FRAME_LINE_TABLE = HEADER_SIZE + FRAME_HEADER_SIZE;
    /**
     * The most bytes a scanline takes per pixel: a literal run of one pixel.
     */
    private static final int MAXIMUM_LINE_BYTES_PER_PIXEL = 2;
    /**
     * The number of decoded frames kept; the frame asked for least recently is decoded again on its next use.
     */
    private static final int MAXIMUM_DECODED_FRAMES = 64;
    private static final int BYTE_MASK = 0xFF;
    private static final long DWORD_MASK = 0xFFFFFFFFL;

    /**
     * One decoded frame and the bytes it was decoded from, as offsets from the pointer.
     */
    private static final class DecodedFrame {
        private final @NotNull SpriteSheet sheet;

        private final long start;
        private final long end;

        private DecodedFrame(@NotNull SpriteSheet sheet, long start, long end) {
            this.sheet = sheet;

            this.start = start;
            this.end   = end;
        }
    }


    private final @NotNull EmbeddedMemory memory;
    private final int pointer;

    /**
     * The header and the frame table, {@code null} while the bytes at the pointer are no GRP header with a readable frame table.
     */
    private byte @Nullable [] table;
    /**
     * Whether the header was written since the table was read.
     */
    private boolean headerChanged;
    /**
     * The end of the observed bytes, as an offset from the pointer.
     */
    private long observedEnd;
    /**
     * The decoded frames by frame index, the one asked for least recently first.
     */
    private final @NotNull SequencedMap<Integer, DecodedFrame> frames;

    /**
     * Creates an {@link EmbeddedGrp} instance for a pointer, read on first use.
     *
     * @param memory  the memory the bytes are read and observed through
     * @param pointer the address of the GRP header
     */
    EmbeddedGrp(@NotNull EmbeddedMemory memory, int pointer) {
        this.memory  = memory;
        this.pointer = pointer;

        this.table         = null;
        this.headerChanged = true;
        this.observedEnd   = 0;
        this.frames        = new LinkedHashMap<>();
    }

    /**
     * @return {@code true} if the bytes at the pointer are a GRP header with a readable frame table, {@code false} otherwise
     */
    boolean isGrp() {
        return this.readTable() != null;
    }

    /**
     * @return the frame count of the header, {@code 0} while the bytes at the pointer are no GRP
     */
    @Override
    public int getFrameCount() {
        byte[] current = this.readTable();

        return current == null ? 0 : LittleEndianUtils.readUnsigned16(current, 0);
    }

    /**
     * Returns one frame as a one-frame indexed sheet with the GRP's canvas, decoding it from the bytes at its line table address unless it is kept
     * and its bytes were not written since.
     *
     * @param frameIndex the frame index, {@code 0} up to {@link #getFrameCount()} minus one
     * @return the one-frame sheet
     * @throws IOException if the index names no frame, or the frame's bytes cannot be read or are not a GRP frame's
     */
    @Override
    public @NotNull SpriteSheet getFrame(int frameIndex) throws IOException {
        byte[] current = this.readTable();

        if (current == null || frameIndex < 0 || frameIndex >= LittleEndianUtils.readUnsigned16(current, 0)) {
            throw Exceptions.newIOException("The GRP at 0x{0} has no frame {1}.", Integer.toHexString(this.pointer), frameIndex);
        }

        DecodedFrame frame = this.frames.get(frameIndex);

        if (frame == null) {
            frame = this.decode(current, frameIndex);

            if (this.frames.size() >= MAXIMUM_DECODED_FRAMES) {
                this.frames.pollFirstEntry();
            }
        }

        this.frames.putLast(frameIndex, frame);

        return frame.sheet;
    }

    /**
     * Returns the header and the frame table, reading them again after a header write.
     *
     * @return the bytes, or {@code null} if the bytes at the pointer are no GRP header with a readable frame table
     */
    private byte @Nullable [] readTable() {
        if (!this.headerChanged) {
            return this.table;
        }

        this.headerChanged = false;
        this.frames.clear();

        byte[] header = new byte[HEADER_SIZE];
        byte[] current = null;

        if (this.memory.read(this.pointer, header, 0, HEADER_SIZE) == HEADER_SIZE) {
            int frameCount = LittleEndianUtils.readUnsigned16(header, 0);
            int width = LittleEndianUtils.readUnsigned16(header, WIDTH_OFFSET);
            int height = LittleEndianUtils.readUnsigned16(header, HEIGHT_OFFSET);

            if (frameCount != 0 && width != 0 && height != 0) {
                current = new byte[HEADER_SIZE + frameCount * FRAME_HEADER_SIZE];

                if (this.memory.read(this.pointer, current, 0, current.length) != current.length) {
                    current = null;
                }
            }
        }

        this.table = current;
        this.observe(current == null ? HEADER_SIZE : current.length);

        return current;
    }

    /**
     * Reads and decodes one frame as a one-frame GRP: its frame table entry, its line table and the scanline bytes its line offsets reach.
     *
     * @param current    the header and the frame table
     * @param frameIndex the frame index
     * @return the decoded frame
     * @throws IOException if the frame's bytes cannot be read or are not a GRP frame's
     */
    private @NotNull DecodedFrame decode(byte @NotNull [] current, int frameIndex) throws IOException {
        int entry = HEADER_SIZE + frameIndex * FRAME_HEADER_SIZE;
        int frameWidth = current[entry + FRAME_WIDTH_OFFSET] & BYTE_MASK;
        int frameHeight = current[entry + FRAME_HEIGHT_OFFSET] & BYTE_MASK;
        long lineTable = (LittleEndianUtils.readUnsigned32(current, entry + FRAME_DATA_OFFSET) - (this.pointer & DWORD_MASK)) & DWORD_MASK;
        int length = 0;

        if (frameWidth != 0 && frameHeight != 0) {
            byte[] lines = new byte[frameHeight * LINE_OFFSET_SIZE];

            if (this.memory.read(this.pointer + (int) lineTable, lines, 0, lines.length) != lines.length) {
                throw Exceptions.newIOException("The line table of frame {0} of the GRP at 0x{1} cannot be read.",
                                                frameIndex, Integer.toHexString(this.pointer));
            }

            int lastLine = 0;

            for (int line = 0; line < frameHeight; line++) {
                lastLine = Math.max(lastLine, LittleEndianUtils.readUnsigned16(lines, line * LINE_OFFSET_SIZE));
            }

            length = Math.max(lines.length, lastLine + frameWidth * MAXIMUM_LINE_BYTES_PER_PIXEL);
        }

        byte[] data = new byte[FRAME_LINE_TABLE + length];

        System.arraycopy(current, 0, data, 0, HEADER_SIZE);
        System.arraycopy(current, entry, data, HEADER_SIZE, FRAME_HEADER_SIZE);
        LittleEndianUtils.writeInt16(data, 0, 1);
        LittleEndianUtils.writeInt32(data, HEADER_SIZE + FRAME_DATA_OFFSET, FRAME_LINE_TABLE);

        int count = length == 0 ? 0 : this.memory.read(this.pointer + (int) lineTable, data, FRAME_LINE_TABLE, length);

        this.observe(lineTable + count);

        try {
            return new DecodedFrame(GrpSheetUtils.createSheet(GrpFile.parse(data)), lineTable, lineTable + count);
        } catch (RuntimeException exception) {
            throw Exceptions.newIOException(exception, "Frame {0} of the GRP at 0x{1} is malformed.", frameIndex, Integer.toHexString(this.pointer));
        }
    }

    /**
     * Observes the writes to the bytes from the pointer up to an offset, past the bytes observed so far.
     *
     * @param end the end of the bytes to observe, as an offset from the pointer
     */
    private void observe(long end) {
        if (end > this.observedEnd) {
            this.memory.observeWrites(this.pointer + (int) this.observedEnd, (int) (end - this.observedEnd), this::onWrite);
            this.observedEnd = end;
        }
    }

    /**
     * Hears a DWORD write to the GRP's bytes: a header write reads the GRP again, a frame table write is copied into the table and decodes the
     * frames of its entries again, and a write to the bytes of decoded frames decodes them again.
     *
     * @param address the aligned classic address
     * @param value   the written DWORD
     */
    private void onWrite(int address, int value) {
        long offset = (address & DWORD_MASK) - (this.pointer & DWORD_MASK);
        long end = offset + Integer.BYTES;

        if (offset < HEADER_SIZE) {
            this.headerChanged = true;

            return;
        }

        byte[] current = this.table;

        if (current != null && offset < current.length) {
            for (int index = 0; index < Integer.BYTES && offset + index < current.length; index++) {
                current[(int) offset + index] = (byte) (value >>> index * Byte.SIZE);
            }

            int first = (int) (offset - HEADER_SIZE) / FRAME_HEADER_SIZE;
            int last = (int) (Math.min(end, current.length) - 1 - HEADER_SIZE) / FRAME_HEADER_SIZE;

            for (int frameIndex = first; frameIndex <= last; frameIndex++) {
                this.frames.remove(frameIndex);
            }
        }

        if (!this.frames.isEmpty()) {
            this.frames.values().removeIf(frame -> frame.start < end && offset < frame.end);
        }
    }
}
