package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.jetbrains.annotations.NotNull;

/**
 * A unit was completed: a created unit placed in the game, a finished building, a trained or morphed unit. The routine that completes a unit does not
 * know what produced it; a trained unit also has a {@link UnitTrainedEvent} that names its producer.
 */
public final class UnitCompletedEvent extends SimulationEvent {
    private final @NotNull SimulationUnit unit;

    /**
     * Creates a {@link UnitCompletedEvent} instance.
     *
     * @param unit the completed unit
     */
    public UnitCompletedEvent(@NotNull SimulationUnit unit) {
        this.unit = unit;
    }

    /**
     * @return the completed unit
     */
    public @NotNull SimulationUnit getUnit() {
        return this.unit;
    }
}
