package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.jetbrains.annotations.NotNull;

/**
 * A building finished researching a technology for its owner.
 */
public final class ResearchCompletedEvent extends SimulationEvent {
    private final @NotNull SimulationUnit unit;
    private final int technology;

    /**
     * Creates a {@link ResearchCompletedEvent} instance.
     *
     * @param unit       the researching building
     * @param technology the techdata.dat entry
     */
    public ResearchCompletedEvent(@NotNull SimulationUnit unit, int technology) {
        this.unit       = unit;
        this.technology = technology;
    }

    /**
     * @return the researching building
     */
    public @NotNull SimulationUnit getUnit() {
        return this.unit;
    }

    /**
     * @return the techdata.dat entry
     */
    public int getTechnology() {
        return this.technology;
    }
}
