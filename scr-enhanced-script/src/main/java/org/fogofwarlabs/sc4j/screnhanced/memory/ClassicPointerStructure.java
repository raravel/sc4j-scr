package org.fogofwarlabs.sc4j.screnhanced.memory;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemorySink;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * A classic pointer to a heap buffer: a writable DWORD at a fixed address that holds the address of a buffer of a fixed size, which starts with
 * the structure's initial bytes and is zero after them.
 */
public final class ClassicPointerStructure implements ClassicStructure {
    private final int address;
    private final int size;
    private final byte @NotNull [] content;

    /**
     * Creates a {@link ClassicPointerStructure} instance of a zeroed buffer.
     *
     * @param address the classic address of the pointer
     * @param size    the size of the buffer in bytes
     */
    public ClassicPointerStructure(int address, int size) {
        this(address, size, new byte[0]);
    }

    /**
     * Creates a {@link ClassicPointerStructure} instance of a buffer that starts with initial bytes.
     *
     * @param address the classic address of the pointer
     * @param size    the size of the buffer in bytes
     * @param content the initial bytes, copied
     * @throws IllegalArgumentException if the initial bytes do not fit the buffer
     */
    public ClassicPointerStructure(int address, int size, byte @NotNull [] content) {
        if (content.length > size) {
            throw Exceptions.newIllegalArgumentException("{0} initial bytes do not fit a buffer of {1} bytes.", content.length, size);
        }

        this.address = address;
        this.size    = size;
        this.content = content.clone();
    }

    /**
     * Adds the pointer and its buffer.
     *
     * @param sink the sink that adds the regions
     * @param heap the heap that places the buffer
     */
    @Override
    public void provide(@NotNull ClassicMemorySink sink, @NotNull ClassicHeap heap) {
        int buffer = heap.allocate(this.size);

        sink.add(new ClassicMemoryBlock(this.address, ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(buffer).array()));
        sink.add(new ClassicMemoryBlock(buffer, Arrays.copyOf(this.content, this.size)));
    }
}
