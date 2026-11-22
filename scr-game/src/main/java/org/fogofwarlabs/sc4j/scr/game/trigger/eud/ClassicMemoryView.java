package org.fogofwarlabs.sc4j.scr.game.trigger.eud;

import org.jetbrains.annotations.NotNull;

/**
 * Gives a game's plugins read-only access to its EUD address space ({@link ClassicAddressSpace}): byte reads that stop where no region serves
 * the address, and observers of the writes in a range. It offers no write.
 */
public final class ClassicMemoryView {
    private final @NotNull ClassicAddressSpace space;

    /**
     * Creates a {@link ClassicMemoryView} instance over an address space.
     *
     * @param space the address space
     */
    public ClassicMemoryView(@NotNull ClassicAddressSpace space) {
        this.space = space;
    }

    /**
     * Adds an observer of the writes to a range ({@link ClassicWriteObserver}); observers of one write are called in the order they were added.
     *
     * @param base     the classic address of the range
     * @param size     the size of the range in bytes, at least {@code 1}
     * @param observer the observer
     * @throws IllegalArgumentException if the size is not positive
     */
    public void observeWrites(int base, int size, @NotNull ClassicWriteObserver observer) {
        this.space.addWriteObserver(base, size, observer);
    }

    /**
     * Reads bytes as the aligned DWORD reads of a trigger condition give them, without reporting an access failure; the read stops at the
     * first DWORD no represented region serves.
     *
     * @param address     the classic address of the first byte
     * @param destination the receiving array
     * @param offset      the first index written
     * @param length      the number of bytes
     * @return the number of bytes read, {@code length} unless the read stopped
     */
    public int read(int address, byte @NotNull [] destination, int offset, int length) {
        return this.space.readQuietly(address, destination, offset, length);
    }
}
