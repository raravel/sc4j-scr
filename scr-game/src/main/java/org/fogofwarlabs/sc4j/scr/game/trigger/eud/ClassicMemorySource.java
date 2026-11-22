package org.fogofwarlabs.sc4j.scr.game.trigger.eud;

import org.jetbrains.annotations.NotNull;

/**
 * Provides regions of a game's EUD address space that the SC:R mod does not serve: memory a mod adds to the simulation
 * ({@link org.fogofwarlabs.sc4j.scr.game.extension.ScrGameExtension#createMemorySource}). The regions change EUD read values and stored bytes, so
 * they are game state: the state hash includes their bytes, and replays and network games need every player to run the same sources.
 * <p>
 * A source serves memory only where the SC:R mod serves no game state ({@link ClassicMemorySink#add}): over addresses no region serves and that
 * have no native region either, where an access fails the game, and over regions of the emulation's own storage
 * ({@link ClassicRegion#isEmulatorStorage()}), which hold zero, a write-protected value or the emulation's own bytes and no state of the game.
 * Every address a game state region serves stays as it is.
 */
public interface ClassicMemorySource {
    /**
     * Adds the source's regions to a game's address space, once, when the address space is created, after the regions of the SC:R mod and before
     * the plugins see the address space.
     *
     * @param sink the sink that adds the regions
     * @throws IllegalArgumentException if a region overlaps memory a source may not serve
     */
    void provideRegions(@NotNull ClassicMemorySink sink);
}
