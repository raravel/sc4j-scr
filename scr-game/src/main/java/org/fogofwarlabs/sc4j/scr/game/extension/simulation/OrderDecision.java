package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An order issued to a unit, before the unit's order queue changes: a plugin rejects the order, which leaves the unit's orders unchanged, or
 * changes the order, its target unit or its target position. A target unit replaces the target position with its sprite position, as for any
 * issued order. An order the routine does not represent fails as it fails without a plugin.
 * <p>
 * The Die order and the orders of a turret, which follows its unit, offer no decision.
 */
public final class OrderDecision extends SimulationDecision {
    private final @NotNull SimulationUnit unit;
    private final int targetUnitType;
    private final boolean queued;

    private int orderId;
    private @Nullable Unit targetUnit;
    private int positionX;
    private int positionY;
    private boolean rejected;

    /**
     * Creates an {@link OrderDecision} instance that is not rejected.
     *
     * @param unit           the receiving unit
     * @param orderId        the orders.dat entry
     * @param targetUnit     the target unit, or {@code null} for a position target
     * @param positionX      the target X coordinate
     * @param positionY      the target Y coordinate
     * @param targetUnitType the target unit type of a build or placement order, or {@link ScrUnitIds#NONE}
     * @param queued         whether the order is requested as a queued order
     */
    public OrderDecision(@NotNull SimulationUnit unit,
                         int orderId,
                         @Nullable Unit targetUnit,
                         int positionX, int positionY,
                         int targetUnitType,
                         boolean queued) {
        this.unit           = unit;
        this.targetUnitType = targetUnitType;
        this.queued         = queued;

        this.orderId    = orderId;
        this.targetUnit = targetUnit;
        this.positionX  = positionX;
        this.positionY  = positionY;
        this.rejected   = false;
    }

    /**
     * @return the receiving unit
     */
    public @NotNull SimulationUnit getUnit() {
        return this.unit;
    }

    /**
     * @return the target unit type of a build or placement order, or {@link ScrUnitIds#NONE}
     */
    public int getTargetUnitType() {
        return this.targetUnitType;
    }

    /**
     * @return {@code true} if the order is requested as a queued order, {@code false} otherwise
     */
    public boolean isQueued() {
        return this.queued;
    }

    /**
     * @return the orders.dat entry
     */
    public int getOrderId() {
        return this.orderId;
    }

    /**
     * Sets the order the unit receives.
     *
     * @param orderId the orders.dat entry
     */
    public void setOrderId(int orderId) {
        this.orderId = orderId;
    }

    /**
     * @return the target unit, or {@code null} for a position target
     */
    public @Nullable Unit getTargetUnit() {
        return this.targetUnit;
    }

    /**
     * Sets the target unit of the order.
     *
     * @param targetUnit the target unit, which must have a sprite, or {@code null} for a position target
     */
    public void setTargetUnit(@Nullable Unit targetUnit) {
        this.targetUnit = targetUnit;
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

    /**
     * Sets the target position of the order, which a target unit replaces.
     *
     * @param positionX the target X coordinate
     * @param positionY the target Y coordinate
     */
    public void setPosition(int positionX, int positionY) {
        this.positionX = positionX;
        this.positionY = positionY;
    }

    /**
     * @return {@code true} if the order is rejected, {@code false} otherwise
     */
    public boolean isRejected() {
        return this.rejected;
    }

    /**
     * Sets whether the order is rejected.
     *
     * @param rejected whether the unit's orders stay unchanged
     */
    public void setRejected(boolean rejected) {
        this.rejected = rejected;
    }
}
