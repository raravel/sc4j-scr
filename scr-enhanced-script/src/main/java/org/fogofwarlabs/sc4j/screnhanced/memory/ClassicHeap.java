package org.fogofwarlabs.sc4j.screnhanced.memory;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;

/**
 * Places the buffers of classic structures in a range of the address space that neither SC:R nor the SC:R mod serves, in the order the
 * structures ask, so every game gets the same addresses.
 */
public final class ClassicHeap {
    /**
     * The first heap address.
     */
    public static final int BASE = 0x0F000000;
    /**
     * The end of the heap range.
     */
    public static final int END = 0x15000000;
    /**
     * The alignment of a buffer.
     */
    public static final int ALIGNMENT = 0x1000;

    private int next;

    /**
     * Creates an empty {@link ClassicHeap} instance.
     */
    public ClassicHeap() {
        this.next = BASE;
    }

    /**
     * Places a buffer.
     *
     * @param size the size in bytes, at least {@code 1}
     * @return the classic address of the buffer
     * @throws IllegalArgumentException if the size is not positive or the heap range is full
     */
    public int allocate(int size) {
        if (size <= 0 || size > END - this.next) {
            throw Exceptions.newIllegalArgumentException("A classic heap buffer of {0} bytes does not fit.", size);
        }

        int address = this.next;

        this.next = address + ((size + ALIGNMENT - 1) & -ALIGNMENT);

        return address;
    }
}
