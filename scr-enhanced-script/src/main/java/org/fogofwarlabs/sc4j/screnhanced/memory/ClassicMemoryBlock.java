package org.fogofwarlabs.sc4j.screnhanced.memory;

import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicRegion;
import org.jetbrains.annotations.NotNull;

/**
 * Writable plain memory of a classic structure: bytes that EUD reads and writes see as they are, with a count of the writes that changed them.
 */
public final class ClassicMemoryBlock implements ClassicRegion {
    private final int base;
    private final byte @NotNull [] storage;

    private long changeCount;

    /**
     * Creates a {@link ClassicMemoryBlock} instance over zeroed bytes.
     *
     * @param base the classic base
     * @param size the size in bytes
     */
    public ClassicMemoryBlock(int base, int size) {
        this(base, new byte[size]);
    }

    /**
     * Creates a {@link ClassicMemoryBlock} instance over initial bytes.
     *
     * @param base    the classic base
     * @param storage the initial bytes, retained (writes change them)
     */
    public ClassicMemoryBlock(int base, byte @NotNull [] storage) {
        this.base    = base;
        this.storage = storage;

        this.changeCount = 0L;
    }

    /**
     * @return the classic base address
     */
    @Override
    public int getBase() {
        return this.base;
    }

    /**
     * @return the size in bytes
     */
    @Override
    public int getSize() {
        return this.storage.length;
    }

    /**
     * Tells whether the region's bytes are plain memory: reading them has no effect, and a DWORD read at any offset and a range read give what the
     * byte reads give.
     *
     * @return {@code true}, as the block's bytes are plain memory
     */
    @Override
    public boolean isPlainMemory() {
        return true;
    }

    /**
     * Returns a count that grows with every write that changes the block's bytes ({@link #findChangeSince}).
     *
     * @return the number of writes that changed a byte
     */
    @Override
    public long getChangeCount() {
        return this.changeCount;
    }

    /**
     * Finds the first byte of a range that may have changed after a change count was observed.
     *
     * @param offset      the first byte offset, with {@code length} bytes inside the region
     * @param length      the number of bytes, at least {@code 1}
     * @param changeCount a change count observed earlier
     * @return {@code -1} if no write changed a byte since, {@code 0} otherwise
     */
    @Override
    public int findChangeSince(int offset, int length, long changeCount) {
        return changeCount == this.changeCount ? -1 : 0;
    }

    /**
     * Reads the unsigned byte at an offset.
     *
     * @param offset the byte offset, below {@link #getSize()}
     * @return the unsigned byte at the offset
     */
    @Override
    public int readByte(int offset) {
        return this.storage[offset] & BYTE_MASK;
    }

    /**
     * Stores one byte.
     *
     * @param offset the byte offset, below {@link #getSize()}
     * @param value  the byte value in the low eight bits
     */
    @Override
    public void writeByte(int offset, int value) {
        if (this.storage[offset] != (byte) value) {
            this.storage[offset] = (byte) value;
            this.changeCount++;
        }
    }

    /**
     * Copies a range of bytes.
     *
     * @param offset            the first byte offset, with {@code length} bytes inside the region
     * @param destination       the receiving array
     * @param destinationOffset the first index written
     * @param length            the number of bytes
     */
    @Override
    public void read(int offset, byte @NotNull [] destination, int destinationOffset, int length) {
        System.arraycopy(this.storage, offset, destination, destinationOffset, length);
    }
}
