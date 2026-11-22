package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.jetbrains.annotations.NotNull;

/**
 * A unit was created: it holds a slot of the unit pool and is in the unit lists, not yet completed. A unit's turret is part of the unit and has no
 * event of its own.
 */
public final class UnitCreatedEvent extends SimulationEvent {
    private final @NotNull SimulationUnit unit;

    /**
     * Creates a {@link UnitCreatedEvent} instance.
     *
     * @param unit the created unit
     */
    public UnitCreatedEvent(@NotNull SimulationUnit unit) {
        this.unit = unit;
    }

    /**
     * @return the created unit
     */
    public @NotNull SimulationUnit getUnit() {
        return this.unit;
    }
}
