package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.jetbrains.annotations.NotNull;

/**
 * A unit changes its owner, before the change. A turret follows its unit's owner and has no event of its own.
 */
public final class OwnerChangedEvent extends SimulationEvent {
    private final @NotNull SimulationUnit unit;
    private final int previousOwner;
    private final int owner;

    /**
     * Creates an {@link OwnerChangedEvent} instance.
     *
     * @param unit          the unit, with its previous owner
     * @param previousOwner the previous owner's player slot
     * @param owner         the new owner's player slot
     */
    public OwnerChangedEvent(@NotNull SimulationUnit unit, int previousOwner, int owner) {
        this.unit          = unit;
        this.previousOwner = previousOwner;
        this.owner         = owner;
    }

    /**
     * @return the unit, with its previous owner
     */
    public @NotNull SimulationUnit getUnit() {
        return this.unit;
    }

    /**
     * @return the previous owner's player slot
     */
    public int getPreviousOwner() {
        return this.previousOwner;
    }

    /**
     * @return the new owner's player slot
     */
    public int getOwner() {
        return this.owner;
    }
}
