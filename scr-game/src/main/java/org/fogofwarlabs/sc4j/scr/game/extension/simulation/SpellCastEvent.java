package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.fogofwarlabs.sc4j.scr.game.production.UnitResearch;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A unit cast a technology: it paid the energy of a spell order and started casting, or it used Stim Packs or started cloaking. The target position
 * is the order target of a spell order and the caster's position otherwise.
 */
public final class SpellCastEvent extends SimulationEvent {
    private final @NotNull SimulationUnit caster;
    private final int technology;
    private final @Nullable SimulationUnit target;
    private final int positionX;
    private final int positionY;

    /**
     * Creates a {@link SpellCastEvent} instance.
     *
     * @param caster     the casting unit
     * @param technology the techdata.dat entry of the order's technology, or {@link UnitResearch#NO_TECHNOLOGY} for a spell order without one
     * @param target     the target unit, or {@code null} for none
     * @param positionX  the target X coordinate
     * @param positionY  the target Y coordinate
     */
    public SpellCastEvent(@NotNull SimulationUnit caster, int technology, @Nullable SimulationUnit target, int positionX, int positionY) {
        this.caster     = caster;
        this.technology = technology;
        this.target     = target;
        this.positionX  = positionX;
        this.positionY  = positionY;
    }

    /**
     * @return the casting unit
     */
    public @NotNull SimulationUnit getCaster() {
        return this.caster;
    }

    /**
     * @return the techdata.dat entry of the order's technology, or {@link UnitResearch#NO_TECHNOLOGY} for a spell order without one
     */
    public int getTechnology() {
        return this.technology;
    }

    /**
     * @return the target unit, or {@code null} for none
     */
    public @Nullable SimulationUnit getTarget() {
        return this.target;
    }

    /**
     * @return the target X coordinate
     */
    public int getPositionX() {
        return this.positionX;
    }

    /**
     * @return the target Y coordinate
     */
    public int getPositionY() {
        return this.positionY;
    }
}
