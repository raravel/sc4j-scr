package org.fogofwarlabs.sc4j.scr.game.extension.simulation;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An order was issued to a unit, with the values an {@link OrderDecision} left: it replaces the unit's interruptible orders or is queued after them.
 * A turret follows its unit's orders and has no event of its own.
 */
public final class OrderIssuedEvent extends SimulationEvent {
    private final @NotNull SimulationUnit unit;
    private final int orderId;
    private final @Nullable SimulationUnit target;
    private final int positionX;
    private final int positionY;
    private final int targetUnitType;
    private final boolean queued;

    /**
     * Creates an {@link OrderIssuedEvent} instance.
     *
     * @param unit           the unit
     * @param orderId        the orders.dat entry
     * @param target         the target unit, or {@code null} for a position target
     * @param positionX      the target X coordinate
     * @param positionY      the target Y coordinate
     * @param targetUnitType the target unit type of a build or placement order, or {@link ScrUnitIds#NONE}
     * @param queued         whether the order was requested as a queued order
     */
    public OrderIssuedEvent(@NotNull SimulationUnit unit,
                            int orderId,
                            @Nullable SimulationUnit target,
                            int positionX, int positionY,
                            int targetUnitType,
                            boolean queued) {
        this.unit           = unit;
        this.orderId        = orderId;
        this.target         = target;
        this.positionX      = positionX;
        this.positionY      = positionY;
        this.targetUnitType = targetUnitType;
        this.queued         = queued;
    }

    /**
     * @return the unit
     */
    public @NotNull SimulationUnit getUnit() {
        return this.unit;
    }

    /**
     * @return the orders.dat entry
     */
    public int getOrderId() {
        return this.orderId;
    }

    /**
     * @return the target unit, or {@code null} for a position target
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

    /**
     * @return the target unit type of a build or placement order, or {@link ScrUnitIds#NONE}
     */
    public int getTargetUnitType() {
        return this.targetUnitType;
    }

    /**
     * @return {@code true} if the order was requested as a queued order, {@code false} otherwise
     */
    public boolean isQueued() {
        return this.queued;
    }
}
