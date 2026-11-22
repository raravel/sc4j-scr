package org.fogofwarlabs.sc4j.screnhanced.memory;

import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemorySink;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A classic bitmap: a writable header at a fixed address, WORD width, WORD height and DWORD pixel address, and a zeroed buffer of one byte per
 * pixel, rows from the top.
 */
public final class ClassicBitmapStructure implements ClassicStructure {
    /**
     * The size of the bitmap header.
     */
    public static final int HEADER_SIZE = 8;

    private final int address;
    private final int width;
    private final int height;

    /**
     * Creates a {@link ClassicBitmapStructure} instance.
     *
     * @param address the classic address of the header
     * @param width   the width in pixels
     * @param height  the height in pixels
     */
    public ClassicBitmapStructure(int address, int width, int height) {
        this.address = address;
        this.width   = width;
        this.height  = height;
    }

    /**
     * Adds the header and the pixel buffer.
     *
     * @param sink the sink that adds the regions
     * @param heap the heap that places the pixel buffer
     */
    @Override
    public void provide(@NotNull ClassicMemorySink sink, @NotNull ClassicHeap heap) {
        int pixels = heap.allocate(this.width * this.height);
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);

        header.putShort((short) this.width).putShort((short) this.height).putInt(pixels);

        sink.add(new ClassicMemoryBlock(this.address, header.array()));
        sink.add(new ClassicMemoryBlock(pixels, this.width * this.height));
    }
}
