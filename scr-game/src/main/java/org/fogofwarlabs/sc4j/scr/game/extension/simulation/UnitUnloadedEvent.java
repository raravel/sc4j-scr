package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.jetbrains.annotations.NotNull;

/**
 * A unit left its transport or Bunker: it was unloaded, or its transport died.
 */
public final class UnitUnloadedEvent extends SimulationEvent {
    private final @NotNull SimulationUnit unit;
    private final @NotNull SimulationUnit transport;

    /**
     * Creates a {@link UnitUnloadedEvent} instance.
     *
     * @param unit      the unloaded unit
     * @param transport the transport or Bunker
     */
    public UnitUnloadedEvent(@NotNull SimulationUnit unit, @NotNull SimulationUnit transport) {
        this.unit      = unit;
        this.transport = transport;
    }

    /**
     * @return the unloaded unit
     */
    public @NotNull SimulationUnit getUnit() {
        return this.unit;
    }

    /**
     * @return the transport or Bunker
     */
    public @NotNull SimulationUnit getTransport() {
        return this.transport;
    }
}
