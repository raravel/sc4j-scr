package org.fogofwarlabs.sc4j.scr.game.trigger.eud;

/**
 * Observes the DWORD writes the EUD address space receives in an observed range ({@link ClassicMemoryView#observeWrites}). An observer only reads:
 * it changes no game state and no byte of the address space.
 */
@FunctionalInterface
public interface ClassicWriteObserver {
    /**
     * Called on one aligned DWORD write, after the region holding it is found and before the region stores it; a write-ignored region's write
     * is reported too, although the region keeps its bytes.
     *
     * @param address the aligned classic address
     * @param value   the DWORD the write stores
     */
    void onWrite(int address, int value);
}
