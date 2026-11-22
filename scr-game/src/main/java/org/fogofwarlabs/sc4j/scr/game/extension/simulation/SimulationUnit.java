package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;

/**
 * A unit as an event or a decision names it: the unit object and its handle, unit type and owner at that moment. The unit object is a slot of the
 * unit pool that a later unit may take; the handle tells the units of a slot apart.
 */
public final class SimulationUnit {
    private final @NotNull Unit unit;
    private final int handle;
    private final int unitType;
    private final int ownerIndex;

    /**
     * Creates a {@link SimulationUnit} instance.
     *
     * @param unit       the unit
     * @param handle     the unit's handle in the unit pool
     * @param unitType   the native unit type WORD
     * @param ownerIndex the native owner BYTE
     */
    public SimulationUnit(@NotNull Unit unit, int handle, int unitType, int ownerIndex) {
        this.unit       = unit;
        this.handle     = handle;
        this.unitType   = unitType;
        this.ownerIndex = ownerIndex;
    }

    /**
     * @return the unit object, a slot of the unit pool
     */
    public @NotNull Unit getUnit() {
        return this.unit;
    }

    /**
     * @return the unit's handle in the unit pool: the slot and its generation
     */
    public int getHandle() {
        return this.handle;
    }

    /**
     * @return the native unit type WORD
     */
    public int getUnitType() {
        return this.unitType;
    }

    /**
     * @return the native owner BYTE: the owner's player slot
     */
    public int getOwnerIndex() {
        return this.ownerIndex;
    }
}
