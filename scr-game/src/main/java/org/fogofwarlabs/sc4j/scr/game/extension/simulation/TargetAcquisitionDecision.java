package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The target an automatic target search of a unit found: a plugin replaces it or clears it, so the search finds nothing. The routine does not
 * test a replacement again: it must be a unit with a sprite that the searching unit can attack. A search that finds nothing offers no
 * decision.
 */
public final class TargetAcquisitionDecision extends SimulationDecision {
    private final @NotNull SimulationUnit unit;

    private @Nullable Unit target;

    /**
     * Creates a {@link TargetAcquisitionDecision} instance.
     *
     * @param unit   the searching unit
     * @param target the found target
     */
    public TargetAcquisitionDecision(@NotNull SimulationUnit unit, @NotNull Unit target) {
        this.unit = unit;

        this.target = target;
    }

    /**
     * @return the searching unit
     */
    public @NotNull SimulationUnit getUnit() {
        return this.unit;
    }

    /**
     * @return the target the search finds, or {@code null} for none
     */
    public @Nullable Unit getTarget() {
        return this.target;
    }

    /**
     * Sets the target the search finds.
     *
     * @param target the target, or {@code null} for none
     */
    public void setTarget(@Nullable Unit target) {
        this.target = target;
    }
}
