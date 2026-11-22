package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.jetbrains.annotations.NotNull;

/**
 * An automatic target search of a unit found a target, after a {@link TargetAcquisitionDecision}.
 */
public final class TargetAcquiredEvent extends SimulationEvent {
    private final @NotNull SimulationUnit unit;
    private final @NotNull SimulationUnit target;

    /**
     * Creates a {@link TargetAcquiredEvent} instance.
     *
     * @param unit   the searching unit
     * @param target the found target
     */
    public TargetAcquiredEvent(@NotNull SimulationUnit unit, @NotNull SimulationUnit target) {
        this.unit   = unit;
        this.target = target;
    }

    /**
     * @return the searching unit
     */
    public @NotNull SimulationUnit getUnit() {
        return this.unit;
    }

    /**
     * @return the found target
     */
    public @NotNull SimulationUnit getTarget() {
        return this.target;
    }
}
