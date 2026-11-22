package org.fogofwarlabs.sc4j.screnhanced.memory;

import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemorySink;
import org.jetbrains.annotations.NotNull;

/**
 * A classic buffer at a fixed address with the size the classic game gave it: a zeroed writable block from that address, over the smaller
 * buffer SC:R serves there and the addresses after it.
 */
public final class ClassicBufferStructure implements ClassicStructure {
    private final int address;
    private final int size;

    /**
     * Creates a {@link ClassicBufferStructure} instance.
     *
     * @param address the classic address of the buffer
     * @param size    the size of the buffer in bytes
     */
    public ClassicBufferStructure(int address, int size) {
        this.address = address;
        this.size    = size;
    }

    /**
     * Adds the buffer.
     *
     * @param sink the sink that adds the regions
     * @param heap the heap, which the buffer does not use
     */
    @Override
    public void provide(@NotNull ClassicMemorySink sink, @NotNull ClassicHeap heap) {
        sink.add(new ClassicMemoryBlock(this.address, this.size));
    }
}
