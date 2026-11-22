package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.jetbrains.annotations.NotNull;

/**
 * A producer finished training a unit and the unit was placed.
 */
public final class UnitTrainedEvent extends SimulationEvent {
    private final @NotNull SimulationUnit unit;
    private final @NotNull SimulationUnit producer;

    /**
     * Creates a {@link UnitTrainedEvent} instance.
     *
     * @param unit     the trained unit
     * @param producer the producing unit
     */
    public UnitTrainedEvent(@NotNull SimulationUnit unit, @NotNull SimulationUnit producer) {
        this.unit     = unit;
        this.producer = producer;
    }

    /**
     * @return the trained unit
     */
    public @NotNull SimulationUnit getUnit() {
        return this.unit;
    }

    /**
     * @return the producing unit
     */
    public @NotNull SimulationUnit getProducer() {
        return this.producer;
    }
}
