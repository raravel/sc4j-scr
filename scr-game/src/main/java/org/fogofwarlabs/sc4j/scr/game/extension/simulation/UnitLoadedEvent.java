package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.jetbrains.annotations.NotNull;

/**
 * A unit was loaded into a transport or a Bunker.
 */
public final class UnitLoadedEvent extends SimulationEvent {
    private final @NotNull SimulationUnit unit;
    private final @NotNull SimulationUnit transport;

    /**
     * Creates a {@link UnitLoadedEvent} instance.
     *
     * @param unit      the loaded unit
     * @param transport the transport or Bunker
     */
    public UnitLoadedEvent(@NotNull SimulationUnit unit, @NotNull SimulationUnit transport) {
        this.unit      = unit;
        this.transport = transport;
    }

    /**
     * @return the loaded unit
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
