package org.fogofwarlabs.sc4j.scr.game.trigger.eud;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerScores;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerModifiers;
import org.jetbrains.annotations.NotNull;

/**
 * Translates one range of classic 1.16 addresses to game state. Offsets are relative to the base; the byte layout
 * exists at this boundary without requiring retained memory.
 */
public interface ClassicRegion {
    /**
     * The mask of the low BYTE.
     */
    int BYTE_MASK = 0xFF;

    /**
     * The mask of a byte's position inside its aligned DWORD.
     */
    int DWORD_OFFSET_MASK = Integer.BYTES - 1;

    /**
     * @return the classic base address
     */
    int getBase();

    /**
     * @return the size in bytes
     */
    int getSize();

    /**
     * Tells whether the address space accepts writes without calling the region or changing its state.
     *
     * @return {@code true} if writes are ignored, {@code false} otherwise
     */
    default boolean isWriteIgnored() {
        return false;
    }

    /**
     * Tells whether the region's bytes are plain memory: reading them has no effect, and a DWORD read at any offset and a
     * range read give what the byte reads give.
     *
     * @return {@code true} if the region's bytes are plain memory, {@code false} otherwise
     */
    default boolean isPlainMemory() {
        return false;
    }

    /**
     * Tells whether the region is served from the emulation's own storage: zero-initialized memory, a write-protected value or the emulation's
     * own node bytes, which no game state holds and a memory source may serve instead ({@link ClassicMemorySource}).
     *
     * @return {@code true} if the region is emulation storage, {@code false} otherwise
     */
    default boolean isEmulatorStorage() {
        return false;
    }

    /**
     * Returns a count that grows with every write that changes the region's bytes, for regions that track their changes so
     * readers can keep what they derived from unchanged bytes ({@link #findChangeSince}).
     *
     * @return the change count, or {@code -1} if the region does not track changes
     */
    default long getChangeCount() {
        return -1;
    }


    /**
     * Reads the unsigned byte at an offset.
     *
     * @param offset the byte offset, below {@link #getSize()}
     * @return the unsigned byte at the offset
     */
    int readByte(int offset);

    /**
     * Stores one byte, changing the domain state that holds it.
     *
     * @param offset the byte offset, below {@link #getSize()}
     * @param value  the byte value in the low eight bits
     * @throws UnsupportedOperationException if the region, or this part of it, rejects writes
     */
    void writeByte(int offset, int value);

    /**
     * Finds the first byte of a range that may have changed after a change count of {@link #getChangeCount()} was
     * observed; a region may answer a byte before the first change, never one after it.
     *
     * @param offset      the first byte offset, with {@code length} bytes inside the region
     * @param length      the number of bytes, at least {@code 1}
     * @param changeCount a change count observed earlier
     * @return the index in the range of that byte, or {@code -1} only if the range surely holds the bytes it held then;
     * {@code 0} for untracked regions
     */
    default int findChangeSince(int offset, int length, long changeCount) {
        return 0;
    }

    /**
     * Copies a range of bytes, as consecutive {@link #readByte} calls do; regions backed by a byte array override it.
     *
     * @param offset            the first byte offset, with {@code length} bytes inside the region
     * @param destination       the receiving array
     * @param destinationOffset the first index written
     * @param length            the number of bytes
     */
    default void read(int offset, byte @NotNull [] destination, int destinationOffset, int length) {
        for (int index = 0; index < length; index++) {
            destination[destinationOffset + index] = (byte) this.readByte(offset + index);
        }
    }

    /**
     * Reads an aligned little-endian DWORD; regions whose domain values are DWORDs override it to read them whole.
     *
     * @param offset the byte offset of the DWORD, with four bytes inside the region
     * @return the DWORD
     */
    default int readDword(int offset) {
        return this.readByte(offset) | this.readByte(offset + 1) << Byte.SIZE | this.readByte(offset + 2) << 2 * Byte.SIZE |
               this.readByte(offset + 3) << 3 * Byte.SIZE;
    }

    /**
     * Reads the unsigned byte at an offset from the aligned DWORD that holds it ({@link #readDword}), for regions whose
     * domain values are DWORDs.
     *
     * @param offset the byte offset, below {@link #getSize()}
     * @return the unsigned byte at the offset
     */
    default int readByteFromDword(int offset) {
        return this.readDword(offset & ~DWORD_OFFSET_MASK) >>> ((offset & DWORD_OFFSET_MASK) * Byte.SIZE) & BYTE_MASK;
    }

    /**
     * Stores one byte into the aligned DWORD that holds it: reads the DWORD ({@link #readDword}), replaces the byte and
     * stores the DWORD ({@link #writeDword}), for regions whose domain values are DWORDs.
     *
     * @param offset the byte offset, below {@link #getSize()}
     * @param value  the byte value in the low eight bits
     */
    default void writeByteToDword(int offset, int value) {
        int shift = (offset & DWORD_OFFSET_MASK) * Byte.SIZE;
        int previous = this.readDword(offset & ~DWORD_OFFSET_MASK);

        this.writeDword(offset & ~DWORD_OFFSET_MASK, LittleEndianUtils.replaceByte(previous, shift, value));
    }

    /**
     * Stores a little-endian DWORD; regions whose domain values are DWORDs override it to store them whole.
     *
     * @param offset the byte offset of the DWORD, with four bytes inside the region
     * @param value  the DWORD
     */
    default void writeDword(int offset, int value) {
        for (int index = 0; index < Integer.BYTES; index++) {
            this.writeByte(offset + index, value >>> (index * Byte.SIZE));
        }
    }

    /**
     * Applies a SetDeaths modification to an aligned DWORD. Set To stores the amount without reading the current value;
     * other modifiers read and modify it. Regions that apply the modifier more than once override this method.
     *
     * @param offset   the byte offset of the DWORD, with four bytes inside the region
     * @param modifier the trigger modifier ({@link TriggerModifiers})
     * @param amount   the amount
     */
    default void writeModified(int offset, int modifier, int amount) {
        this.writeDword(offset, modifier == TriggerModifiers.SET_TO ? amount : PlayerScores.modify(this.readDword(offset), modifier, amount));
    }
}
