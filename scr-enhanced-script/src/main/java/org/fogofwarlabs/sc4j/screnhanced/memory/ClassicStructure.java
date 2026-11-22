package org.fogofwarlabs.sc4j.screnhanced.memory;

import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemorySink;
import org.jetbrains.annotations.NotNull;

/**
 * One structure of the classic game's memory that SC:R does not serve: the regions that hold it, laid out, sized and writable as in the classic
 * game.
 */
public interface ClassicStructure {
    /**
     * Adds the structure's regions to a game's address space.
     *
     * @param sink the sink that adds the regions
     * @param heap the heap that places the structure's buffers
     */
    void provide(@NotNull ClassicMemorySink sink, @NotNull ClassicHeap heap);
}
