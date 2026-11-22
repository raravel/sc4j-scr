package org.fogofwarlabs.sc4j.screnhanced.graphics;

import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemoryView;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicWriteObserver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The bytes that embedded graphics ({@link EmbeddedGrp}, {@link EmbeddedAnim}) are read from: a game's memory view, whose writes are observed, or a
 * byte range that never changes, addressed from {@code 0}.
 */
final class EmbeddedMemory {
    /**
     * Creates an {@link EmbeddedMemory} instance over a game's memory view.
     *
     * @param view the memory view
     * @return the created {@link EmbeddedMemory} instance
     */
    static @NotNull EmbeddedMemory of(@NotNull ClassicMemoryView view) {
        return new EmbeddedMemory(view, null, 0, 0);
    }

    /**
     * Creates an {@link EmbeddedMemory} instance over a byte range, which is not copied and must not change: address {@code 0} is the first byte
     * of the range, and no write is ever observed.
     *
     * @param data   the bytes
     * @param offset the first byte of the range
     * @param length the size of the range
     * @return the created {@link EmbeddedMemory} instance
     * @throws IndexOutOfBoundsException if the range lies outside the bytes
     */
    static @NotNull EmbeddedMemory of(byte @NotNull [] data, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, data.length);

        return new EmbeddedMemory(null, data, offset, length);
    }


    private final @Nullable ClassicMemoryView view;
    private final byte @Nullable [] data;
    private final int offset;
    private final int length;

    /**
     * Creates an {@link EmbeddedMemory} instance.
     *
     * @param view   the memory view, or {@code null} for a byte range
     * @param data   the bytes of a byte range, or {@code null} for a memory view
     * @param offset the first byte of the range
     * @param length the size of the range
     */
    private EmbeddedMemory(@Nullable ClassicMemoryView view, byte @Nullable [] data, int offset, int length) {
        this.view   = view;
        this.data   = data;
        this.offset = offset;
        this.length = length;
    }

    /**
     * Reads bytes; a read stops where the memory view serves no address or where the byte range ends.
     *
     * @param address     the address of the first byte
     * @param destination the receiving array
     * @param offset      the first index written
     * @param length      the number of bytes
     * @return the number of bytes read, {@code length} unless the read stopped
     */
    int read(int address, byte @NotNull [] destination, int offset, int length) {
        if (this.view != null) {
            return this.view.read(address, destination, offset, length);
        }

        if (this.data == null || address < 0 || address >= this.length) {
            return 0;
        }

        int count = Math.min(length, this.length - address);

        System.arraycopy(this.data, this.offset + address, destination, offset, count);

        return count;
    }

    /**
     * Observes the writes to a range of the memory view; a byte range is never written, so its observers are never called.
     *
     * @param base     the address of the range
     * @param size     the size of the range in bytes, at least {@code 1}
     * @param observer the observer
     */
    void observeWrites(int base, int size, @NotNull ClassicWriteObserver observer) {
        if (this.view != null) {
            this.view.observeWrites(base, size, observer);
        }
    }
}
