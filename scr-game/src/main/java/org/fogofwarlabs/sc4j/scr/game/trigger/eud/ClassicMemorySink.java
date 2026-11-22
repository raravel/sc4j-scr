package org.fogofwarlabs.sc4j.scr.game.trigger.eud;

import org.jetbrains.annotations.NotNull;

/**
 * Adds the regions of a game's memory sources ({@link ClassicMemorySource}) to its EUD address space.
 */
public final class ClassicMemorySink {
    private final @NotNull ClassicAddressSpace space;

    /**
     * Creates a {@link ClassicMemorySink} instance over an address space.
     *
     * @param space the address space
     */
    public ClassicMemorySink(@NotNull ClassicAddressSpace space) {
        this.space = space;
    }

    /**
     * Adds a region. The region may cover regions of the emulation's own storage ({@link ClassicRegion#isEmulatorStorage()}) whole, which it
     * replaces, and addresses that neither a region of the address space nor a native region serves; it must end at or below the map-dependent
     * regions ({@link NativeRegionLayout#MAP_REGION_BASE}).
     *
     * @param region the region, whose base and size stay fixed
     * @throws IllegalArgumentException if the region is empty, reaches the map-dependent regions, overlaps a region that is not emulation
     *                                  storage or covers part of one, overlaps an earlier provided region, or covers an address with a
     *                                  native region that no region of the address space serves
     */
    public void add(@NotNull ClassicRegion region) {
        this.space.provide(region);
    }

    /**
     * Reads bytes the address space serves, regions added so far included, without failing the game.
     *
     * @param address     the classic address of the first byte
     * @param destination the receiving array
     * @param offset      the first index written
     * @param length      the number of bytes
     * @return the number of bytes read, {@code length} unless the read stopped at an address no region serves
     */
    public int read(int address, byte @NotNull [] destination, int offset, int length) {
        return new ClassicMemoryView(this.space).read(address, destination, offset, length);
    }
}
