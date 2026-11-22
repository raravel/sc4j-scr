package org.fogofwarlabs.sc4j.scr.game.order;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.content.type.OrderType;
import org.fogofwarlabs.sc4j.engine.order.OrderSystem;
import org.fogofwarlabs.sc4j.scr.content.ScrNativeIds;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrOrderTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.OrderDecision;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameRulesVersion;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Issues, queues and activates the orders of units: the untargeted orders that replace a queue, the runtime orders that replace or extend a queue,
 * and the activation of the queue head. This is the SC:R {@link OrderSystem} of the game ({@link Game#register} registers it under
 * {@link OrderSystem#KEY}); the SC:R rules call its native forms, which name orders by their {@code orders.dat} BYTE, carry the target unit type
 * and the queue flag, and keep the queue in the native order pool ({@link OrderPool}). The engine operations run the same native routines.
 * <p>
 * The order state is the engine order state of the unit: the primary order BYTE names the engine order type, and the order target point, target
 * unit and the queue of {@link OrderPool.Order} entries are the engine order fields.
 */
public final class ScrOrderSystem implements OrderSystem {
    /**
     * The {@code stat_txt} id of the full-queue text.
     */
    private static final int TEXT_QUEUE_FULL = 0x367;
    /**
     * The {@code stat_txt} id of the order-allocation failure text.
     */
    private static final int TEXT_ORDER_ALLOCATION_FAILED = 0x368;
    /**
     * The {@code stat_txt} id of the exhausted-order-pool text.
     */
    private static final int TEXT_ORDER_POOL_FULL = 0x369;

    /**
     * The greatest existing highlighted order count at which a unit with a computer record inserts before a queued order.
     */
    private static final int MAXIMUM_COMPUTER_INSERTION_COUNT = 8;

    /**
     * The mask of an unsigned BYTE: the requested order identifier and the user action flags.
     */
    private static final int BYTE_MASK = 0xFF;

    /**
     * Returns the SC:R order system of an engine game.
     *
     * @param game the engine game of an SC:R game
     * @return the order system
     * @throws IllegalStateException if the game's order system is not the SC:R one
     */
    public static @NotNull ScrOrderSystem of(org.fogofwarlabs.sc4j.engine.game.@NotNull Game game) {
        if (!(game.getOrders() instanceof ScrOrderSystem orders)) {
            throw Exceptions.newIllegalStateException("The game has no SC:R order system.");
        }

        return orders;
    }


    /**
     * Sets the secondary order and clears its state when it changes.
     *
     * @param unit    the unit
     * @param orderId the secondary order
     */
    public static void setSecondaryOrder(@NotNull Unit unit, int orderId) {
        if (unit.getSecondaryOrder() != orderId) {
            restartSecondaryOrder(unit, orderId);
        }
    }

    /**
     * Replaces the secondary order and clears its state, also when the order is unchanged.
     *
     * @param unit    the unit
     * @param orderId the secondary order
     */
    public static void restartSecondaryOrder(@NotNull Unit unit, int orderId) {
        unit.setSecondaryOrder(orderId);
        unit.setSecondaryOrderState(0);
        unit.setCurrentBuildUnit(null);
    }


    /**
     * Checks the computer-unit prelude of the queue activation: whether a burrowed unit with a computer record must unburrow before the queued order.
     * Die, Burrowed, Unburrowing and ComputerAI orders, Spider Mines and a Lurker's Guard or AttackFixedRange keep the unit burrowed.
     *
     * @param baseType the base type of the burrowed unit with a computer record ({@link Game#getBaseType(Unit)})
     * @param orderId  the order of the queue head
     * @return {@code true} if the unit must unburrow before the queued order, {@code false} otherwise
     */
    private static boolean isUnburrowRequired(int baseType, int orderId) {
        if (orderId == ScrOrderIds.DIE || orderId == ScrOrderIds.BURROWED || orderId == ScrOrderIds.UNBURROWING ||
            orderId == ScrOrderIds.COMPUTER_AI || baseType == ScrUnitIds.TERRAN_VULTURE_SPIDER_MINE) {
            return false;
        }

        return baseType != ScrUnitIds.ZERG_LURKER ||
               orderId != ScrOrderIds.GUARD && orderId != ScrOrderIds.ATTACK_FIXED_RANGE;
    }


    private @Nullable Game game;

    /**
     * Creates a {@link ScrOrderSystem} instance; it serves the SC:R game it finds when it is attached.
     */
    public ScrOrderSystem() {
        this.game = null;
    }

    /**
     * Resolves what this system uses from the game. Called once, after every system exists.
     * <p>
     * Binds the SC:R game system of the game.
     *
     * @param game the game
     */
    @Override
    public void attach(org.fogofwarlabs.sc4j.engine.game.@NotNull Game game) {
        this.game = game.getModSystem(Game.SYSTEM_KEY);
    }

    /**
     * Returns the idle order a unit returns to: ComputerAI for a unit with a computer record, otherwise its {@code units.dat} Return to Idle order.
     *
     * @param unit the unit
     * @return the idle order
     * @throws NullPointerException if the world was built without game data
     */
    public int getIdleOrder(@NotNull Unit unit) {
        return unit.getComputerRecord() != null
               ? ScrOrderIds.COMPUTER_AI
               : this.requireGame().requireGameData().getUnitTypes().getReturnToIdleOrder(unit.getUnitId());
    }

    /**
     * Replaces a unit's orders with one order, which becomes the current order.
     * <p>
     * The SC:R form is {@link #issue(Unit, int, Unit, int, int, int, boolean, int)} with the {@code orders.dat} entry of the type, no target unit
     * type and no queueing: it removes the interruptible trailing orders, appends the order and activates the queue head.
     *
     * @param unit             the unit
     * @param orderType        the order type
     * @param targetX          the x coordinate of the target point
     * @param targetY          the y coordinate of the target point
     * @param targetUnit       the target unit, or {@code null}
     * @param instructionLimit the instruction limit of each animation program run the change of the current order starts
     * @throws IllegalArgumentException      if a unit is not an SC:R unit
     * @throws IllegalStateException         if the system is not attached or the target unit has no sprite
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the order is unrepresented or the order pool is not initialized
     */
    @Override
    public void issue(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit,
                      @NotNull OrderType orderType,
                      int targetX, int targetY,
                      org.fogofwarlabs.sc4j.engine.entity.@Nullable Unit targetUnit,
                      int instructionLimit) {
        Unit scrTargetUnit = targetUnit != null ? Unit.of(targetUnit) : null;

        this.issue(Unit.of(unit), ScrNativeIds.toNative(orderType), scrTargetUnit, targetX, targetY, ScrUnitIds.NONE, false, instructionLimit);
    }

    /**
     * Issues a runtime order using the pooled queue. A queued order is appended unless the queue or the order pool is full, which only shows a status
     * text. Any other order records the user action, then, unless the unit is dying, removes the interruptible trailing orders and appends the order
     * when the capacity test allows it; the queue head activates in every case, also for a dying unit. The simulation plugins decide the order
     * first ({@link ScrSimulationPlugins#decideOrder}): a rejected order changes nothing, and the order continues with the decided values.
     *
     * @param unit             the receiving unit
     * @param orderId          the requested order BYTE
     * @param targetUnit       the target unit, or {@code null} for a position target
     * @param positionX        the target X coordinate
     * @param positionY        the target Y coordinate
     * @param targetUnitType   the target type, or {@link ScrUnitIds#NONE} for none
     * @param queue            whether to append a queueable order
     * @param instructionLimit the script limit for cleanup animations
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if the target unit has no sprite
     * @throws UnsupportedOperationException if the order is unrepresented or the order pool is not initialized
     */
    public void issue(@NotNull Unit unit,
                      int orderId,
                      @Nullable Unit targetUnit,
                      int positionX, int positionY,
                      int targetUnitType,
                      boolean queue,
                      int instructionLimit) {
        OrderPool pool = this.requireGame().requireOrderPool();

        orderId &= BYTE_MASK;

        ScrSimulationPlugins simulation = this.requireGame().getSimulationPlugins();

        if (simulation != null) {
            OrderDecision decision = simulation.decideOrder(unit, orderId, targetUnit, positionX, positionY, targetUnitType, queue);

            if (decision.isRejected()) {
                return;
            }

            orderId    = decision.getOrderId() & BYTE_MASK;
            targetUnit = decision.getTargetUnit();
            positionX  = decision.getPositionX();
            positionY  = decision.getPositionY();
        }

        if (orderId >= ScrOrderIds.NONE || orderId == ScrOrderIds.CLOAK) {
            throw Exceptions.newUnsupportedOperationException("Unrepresented runtime order branch: {0}", orderId);
        }

        ScrOrderTypeView orderTypes = this.requireGame().requireGameData().getOrderTypes();

        if (targetUnit != null) {
            if (targetUnit.getSpriteOrNull() == null) {
                throw Exceptions.newIllegalStateException("Runtime order target requires a retained sprite.");
            }

            positionX = targetUnit.getSpriteOrNull().getX();
            positionY = targetUnit.getSpriteOrNull().getY();
        }

        if (queue && orderTypes.getCanBeQueued(orderId) == 0) {
            queue = false;
        }

        if (queue) {
            queue = switch (ScrOrderIds.requireValid(unit.getPrimaryOrder())) {
                case ScrOrderIds.GUARD, ScrOrderIds.PLAYER_GUARD, ScrOrderIds.NOTHING, ScrOrderIds.PICKUP_IDLE, ScrOrderIds.PATROL,
                     ScrOrderIds.MEDIC_IDLE -> false;
                default -> true;
            };
        }

        if (queue) {
            // A full queue and a nearly exhausted order pool only show a status text.
            if (unit.getHighlightedOrderCount() >= GameRulesVersion.HIGHLIGHTED_ORDER_CAPACITY) {
                this.requireGame().getInterfaceLink().getCommandErrorEvents().raiseStatusText(unit.getOwnerIndex(), TEXT_QUEUE_FULL);

                return;
            }

            if (pool.isNearlyFull()) {
                this.requireGame().getInterfaceLink().getCommandErrorEvents().raiseStatusText(unit.getOwnerIndex(), TEXT_ORDER_POOL_FULL);

                return;
            }

            this.queueBack(unit, orderId, targetUnit, positionX, positionY, targetUnitType);
        } else {
            unit.setUserActionFlags(unit.getUserActionFlags() | Unit.USER_ACTION_ORDER_DONE);

            // A dying unit keeps its queue. An exhausted pool reports the allocation failure text and appends nothing.
            if (unit.getPrimaryOrder() != ScrOrderIds.DIE) {
                this.queueReplacingOrders(unit, orderId, targetUnit, positionX, positionY, targetUnitType, true);
            }

            this.activateNext(unit, instructionLimit);
        }
    }

    /**
     * Appends an order after a unit's queued orders.
     * <p>
     * The SC:R form is {@link #queueBack} with the {@code orders.dat} entry of the type and no target unit type; an exhausted order pool shows
     * the allocation failure text.
     *
     * @param unit       the unit
     * @param orderType  the order type
     * @param targetX    the x coordinate of the target point
     * @param targetY    the y coordinate of the target point
     * @param targetUnit the target unit, or {@code null}
     * @return {@code true} if the order was queued, {@code false} if the queue is full
     * @throws IllegalArgumentException      if a unit is not an SC:R unit
     * @throws IllegalStateException         if the system is not attached
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the order pool is not initialized
     */
    @Override
    public boolean queue(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit,
                         @NotNull OrderType orderType,
                         int targetX, int targetY,
                         org.fogofwarlabs.sc4j.engine.entity.@Nullable Unit targetUnit) {
        Unit scrUnit = Unit.of(unit);
        OrderPool.Order last = scrUnit.getLastOrder();

        this.queueBack(scrUnit, ScrNativeIds.toNative(orderType), targetUnit != null ? Unit.of(targetUnit) : null, targetX, targetY, ScrUnitIds.NONE);

        return scrUnit.getLastOrder() != last;
    }

    /**
     * Ends a unit's current order; the next queued order, or the idle order of the unit's type, becomes current.
     * <p>
     * The SC:R form is {@link #finishOrder(Unit, int)}; the idle order is {@link #getIdleOrder}.
     *
     * @param unit             the unit
     * @param instructionLimit the instruction limit of each animation program run the change of the current order starts
     * @throws IllegalArgumentException if the unit is not an SC:R unit
     */
    @Override
    public void finishOrder(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit, int instructionLimit) {
        this.finishOrder(Unit.of(unit), instructionLimit);
    }

    /**
     * Completes an order by activating its pending successor or returning to idle ({@link #returnToIdle}).
     * <p>
     * A nonempty queue records user action before activation. An empty queue selects the idle order before issuing it.
     *
     * @param unit             the receiving unit
     * @param instructionLimit the script limit for cleanup animations
     */
    public void finishOrder(@NotNull Unit unit, int instructionLimit) {
        if (unit.getFirstOrder() != null) {
            unit.setUserActionFlags(unit.getUserActionFlags() | Unit.USER_ACTION_ORDER_DONE);
            this.activateNext(unit, instructionLimit);
        } else {
            this.returnToIdle(unit, instructionLimit);
        }
    }

    /**
     * Runs the current order of one unit for one frame.
     * <p>
     * The SC:R form is the main-order dispatch of the unit phase ({@link UnitOrders#updatePrimary}) with the player type of the owner for the
     * current frame.
     *
     * @param unit             the unit
     * @param instructionLimit the instruction limit of each animation program run the order starts
     * @throws IllegalArgumentException      if the unit is not an SC:R unit
     * @throws IllegalStateException         if the system is not attached or the game runs no frame
     * @throws UnsupportedOperationException if the main order is not represented in the registry
     */
    @Override
    public void update(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit, int instructionLimit) {
        Game attached = this.requireGame();
        Unit scrUnit = Unit.of(unit);
        int ownerPlayerType = attached.requireFrame().getOwnerPlayerTypes()[scrUnit.getOwnerIndex()] & BYTE_MASK;

        attached.getUnitOrders().updatePrimary(scrUnit, ownerPlayerType, instructionLimit);
    }

    /**
     * Removes the queued orders of a unit that leaves the game and returns them to the order pool.
     * <p>
     * The SC:R removal unlinks every queued order in queue order through {@link OrderPool#remove}, as the removal of a single queued order does;
     * the current order byte stays. The SC:R unit lifecycle itself ends a unit through its Die order and does not call this.
     *
     * @param unit the unit
     * @throws IllegalArgumentException      if the unit is not an SC:R unit
     * @throws IllegalStateException         if the system is not attached
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the order pool is not initialized
     */
    @Override
    public void removeOrders(org.fogofwarlabs.sc4j.engine.entity.@NotNull Unit unit) {
        Game attached = this.requireGame();
        Unit scrUnit = Unit.of(unit);

        for (OrderPool.Order order = scrUnit.getFirstOrder(); order != null; ) {
            OrderPool.Order next = order.getNext();

            attached.requireOrderPool().remove(scrUnit, order, attached.requireGameData().getOrderTypes());

            order = next;
        }
    }


    /**
     * Issues an untargeted runtime order ({@link #issue(Unit, int, Unit, int, int, int, boolean, int)}) with no target unit, position {@code 0, 0},
     * no target type and no queueing.
     *
     * @param unit             the receiving unit
     * @param orderId          the requested order BYTE
     * @param instructionLimit the script limit for cleanup animations
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the order is unrepresented or the order pool is not initialized
     */
    public void issueUntargeted(@NotNull Unit unit, int orderId, int instructionLimit) {
        this.issue(unit, orderId, null, 0, 0, ScrUnitIds.NONE, false, instructionLimit);
    }

    /**
     * Returns a unit to its idle order ({@link #getIdleOrder}) as an untargeted runtime order.
     *
     * @param unit             the unit
     * @param instructionLimit the script limit for cleanup animations
     */
    public void returnToIdle(@NotNull Unit unit, int instructionLimit) {
        this.issueUntargeted(unit, this.getIdleOrder(unit), instructionLimit);
    }

    /**
     * Adds an internal order before the pending queue head, or appends it when the queue is empty. A unit with a computer record and more than eight
     * highlighted orders keeps a nonempty queue; an exhausted pool only shows the allocation failure text.
     *
     * @param unit           the receiving unit
     * @param orderId        the order BYTE
     * @param targetUnit     the target unit, or {@code null}
     * @param positionX      the target X coordinate
     * @param positionY      the target Y coordinate
     * @param targetUnitType the target unit type, or {@link ScrUnitIds#NONE} for none
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if an allowed insertion has no order pool
     */
    public void queueFront(@NotNull Unit unit, int orderId, @Nullable Unit targetUnit, int positionX, int positionY, int targetUnitType) {
        OrderPool.Order first = unit.getFirstOrder();

        if (first == null) {
            this.queueAfter(unit, orderId, targetUnit, positionX, positionY, targetUnitType, null);
        } else {
            this.queueBefore(unit, orderId, targetUnit, positionX, positionY, targetUnitType, first);
        }
    }

    /**
     * Appends an internal order after the pending queue without activating it; an exhausted pool only shows the allocation failure text.
     *
     * @param unit           the receiving unit
     * @param orderId        the order BYTE
     * @param targetUnit     the target unit, or {@code null}
     * @param positionX      the target X coordinate
     * @param positionY      the target Y coordinate
     * @param targetUnitType the target unit type, or {@link ScrUnitIds#NONE} for none
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the order pool is not initialized
     */
    public void queueBack(@NotNull Unit unit, int orderId, @Nullable Unit targetUnit, int positionX, int positionY, int targetUnitType) {
        this.queueAfter(unit, orderId, targetUnit, positionX, positionY, targetUnitType, null);
    }

    /**
     * Appends an internal order or inserts it after a queued predecessor without activating it. No computer queue-count guard is applied; an
     * exhausted pool only shows the allocation failure text.
     *
     * @param unit           the receiving unit
     * @param orderId        the order BYTE
     * @param targetUnit     the target unit, or {@code null}
     * @param positionX      the target X coordinate
     * @param positionY      the target Y coordinate
     * @param targetUnitType the target unit type, or {@link ScrUnitIds#NONE} for none
     * @param after          the predecessor in this unit's queue, or {@code null} to append
     * @throws IllegalStateException         if the predecessor belongs to another queue
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the order pool is not initialized
     */
    public void queueAfter(@NotNull Unit unit,
                           int orderId,
                           @Nullable Unit targetUnit,
                           int positionX, int positionY,
                           int targetUnitType,
                           @Nullable OrderPool.Order after) {
        OrderPool pool = this.requireGame().requireOrderPool();

        OrderPool.Order order = this.allocateOrder(pool, unit, orderId, targetUnit, positionX, positionY, targetUnitType);

        if (order == null) {
            return;
        }

        pool.enqueue(unit, order, after, this.requireGame().requireGameData().getOrderTypes());
    }

    /**
     * Inserts an internal order before a successor already queued by this unit. A unit with a computer record and more than eight highlighted orders
     * receives no insertion or status text; otherwise, an exhausted pool only shows the allocation failure text.
     *
     * @param unit           the receiving unit
     * @param orderId        the order BYTE
     * @param targetUnit     the target unit, or {@code null}
     * @param positionX      the target X coordinate
     * @param positionY      the target Y coordinate
     * @param targetUnitType the target unit type, or {@link ScrUnitIds#NONE} for none
     * @param before         the nonnull successor in this unit's queue
     * @throws IllegalStateException         if a queue link is rejected
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the order pool is not initialized
     */
    public void queueBefore(@NotNull Unit unit,
                            int orderId,
                            @Nullable Unit targetUnit,
                            int positionX, int positionY,
                            int targetUnitType,
                            @NotNull OrderPool.Order before) {
        if (unit.getComputerRecord() != null && unit.getHighlightedOrderCount() > MAXIMUM_COMPUTER_INSERTION_COUNT) {
            return;
        }

        OrderPool pool = this.requireGame().requireOrderPool();

        OrderPool.Order order = this.allocateOrder(pool, unit, orderId, targetUnit, positionX, positionY, targetUnitType);

        if (order == null) {
            return;
        }

        OrderPool.Order previous = before.getPrevious();

        if (previous == null) {
            pool.enqueueFront(unit, order, this.requireGame().requireGameData().getOrderTypes());
        } else {
            pool.enqueue(unit, order, previous, this.requireGame().requireGameData().getOrderTypes());
        }
    }

    /**
     * Allocates the detached order of an internal queue insertion. An exhausted pool allocates nothing and shows the allocation failure text to the
     * unit's owner.
     *
     * @param pool           the order pool
     * @param unit           the receiving unit
     * @param orderId        the order BYTE
     * @param targetUnit     the target unit, or {@code null}
     * @param positionX      the target X coordinate
     * @param positionY      the target Y coordinate
     * @param targetUnitType the target unit type, or {@link ScrUnitIds#NONE} for none
     * @return the allocated order, or {@code null} when the pool is exhausted
     */
    private @Nullable OrderPool.Order allocateOrder(@NotNull OrderPool pool,
                                                    @NotNull Unit unit,
                                                    int orderId,
                                                    @Nullable Unit targetUnit,
                                                    int positionX, int positionY,
                                                    int targetUnitType) {
        OrderPool.Order order = pool.allocate(orderId, targetUnitType, positionX, positionY, targetUnit);

        if (order == null) {
            this.requireGame().getInterfaceLink().getCommandErrorEvents().raiseStatusText(unit.getOwnerIndex(), TEXT_ORDER_ALLOCATION_FAILED);
        }

        return order;
    }

    /**
     * Replaces only trailing orders of the same type before appending, without interruption.
     *
     * @param unit           the receiving unit
     * @param orderId        the order BYTE
     * @param targetUnit     the target unit, or {@code null}
     * @param positionX      the target X coordinate
     * @param positionY      the target Y coordinate
     * @param targetUnitType the target unit type, or {@link ScrUnitIds#NONE} for none
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the order pool is not initialized
     */
    public void queueReplacingSameOrders(@NotNull Unit unit,
                                         int orderId,
                                         @Nullable Unit targetUnit,
                                         int positionX, int positionY,
                                         int targetUnitType) {
        this.queueReplacingOrders(unit, orderId, targetUnit, positionX, positionY, targetUnitType, false);
    }

    /**
     * Replaces matching trailing orders and optionally other interruptible trailing orders, then appends when the capacity test allows it. A dying
     * unit keeps its queue. The operation neither records user action nor activates the queue.
     * <p>
     * Rejected capacity leaves the remaining queue intact and reports no allocation failure.
     *
     * @param unit                the receiving unit
     * @param orderId             the order BYTE
     * @param targetUnit          the target unit, or {@code null}
     * @param positionX           the target X coordinate
     * @param positionY           the target Y coordinate
     * @param targetUnitType      the target unit type, or {@link ScrUnitIds#NONE} for none
     * @param removeInterruptible whether to remove other interruptible trailing orders
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the order pool is not initialized
     */
    public void queueReplacingOrders(@NotNull Unit unit,
                                     int orderId,
                                     @Nullable Unit targetUnit,
                                     int positionX, int positionY,
                                     int targetUnitType,
                                     boolean removeInterruptible) {
        if (unit.getPrimaryOrder() == ScrOrderIds.DIE) {
            return;
        }

        OrderPool pool = this.requireGame().requireOrderPool();

        ScrOrderTypeView orderTypes = removeInterruptible ? this.requireGame().requireGameData().getOrderTypes() : null;
        OrderPool.Order last;

        while ((last = unit.getLastOrder()) != null) {
            if ((orderTypes == null || orderTypes.getCanBeInterrupted(last.getOrderId()) == 0) && last.getOrderId() != orderId) {
                break;
            }

            pool.remove(unit, last, orderTypes == null ? this.requireGame().requireGameData().getOrderTypes() : orderTypes);
        }

        if (this.applyQueuedOrderCapacity(unit, orderId, targetUnit, positionX, positionY, targetUnitType)) {
            this.queueBack(unit, orderId, targetUnit, positionX, positionY, targetUnitType);
        }
    }

    /**
     * Removes the first pending order of a type.
     * <p>
     * Only a matching queue member reaches pool and data access.
     *
     * @param unit    the queue owner
     * @param orderId the order BYTE to remove
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the order pool is not initialized
     */
    public void removeQueued(@NotNull Unit unit, int orderId) {
        for (OrderPool.Order order = unit.getFirstOrder(); order != null; order = order.getNext()) {
            if (order.getOrderId() == orderId) {
                this.requireGame().requireOrderPool().remove(unit, order, this.requireGame().requireGameData().getOrderTypes());

                return;
            }
        }
    }

    /**
     * Completes the current order: clears {@link UnitStatusFlags#ORDER_NOT_INTERRUPTIBLE}, then finishes the order ({@link #finishOrder}).
     *
     * @param unit             the receiving unit
     * @param instructionLimit the script limit for cleanup animations
     */
    public void completeOrder(@NotNull Unit unit, int instructionLimit) {
        unit.setStatus(unit.getStatus() & ~UnitStatusFlags.ORDER_NOT_INTERRUPTIBLE);
        this.finishOrder(unit, instructionLimit);
    }

    /**
     * Completes the current order through the {@code units.dat} Return to Idle order: an empty queue first queues that order
     * ({@link #queueReplacingSameOrders}), then the order completes ({@link #completeOrder}).
     *
     * @param unit             the receiving unit
     * @param instructionLimit the script limit for cleanup animations
     * @throws NullPointerException if the world was built without game data
     */
    public void completeOrderToIdle(@NotNull Unit unit, int instructionLimit) {
        if (unit.getFirstOrder() == null) {
            int idleOrder = this.requireGame().requireGameData().getUnitTypes().getReturnToIdleOrder(unit.getUnitId());

            this.queueReplacingSameOrders(unit, idleOrder, null, 0, 0, ScrUnitIds.NONE);
        }

        this.completeOrder(unit, instructionLimit);
    }

    /**
     * Activates the queue head when the current order and script allow interruption: the head leaves the queue and becomes the main order with its
     * target, the sprite returns to idle, and a subunit inherits the order. A burrowed computer unit first queues Unburrowing in front of the head.
     *
     * @param unit             the receiving unit
     * @param instructionLimit the script limit for cleanup animations
     * @throws UnsupportedOperationException if a burrowed computer unit cannot insert Unburrowing
     * @throws IllegalStateException         if the retained main image, the pool or a live target is missing, or the unit has no sprite
     * @throws NullPointerException          if the world was built without game data
     */
    public void activateNext(@NotNull Unit unit, int instructionLimit) {
        OrderPool.Order order = unit.getFirstOrder();

        if (order != null && unit.getComputerRecord() != null && (unit.getStatus() & UnitStatusFlags.BURROWED) != 0 &&
            isUnburrowRequired(this.requireGame().getBaseType(unit), order.getOrderId())) {
            // A burrowed computer unit first unburrows at its position, then the activation runs again.
            Sprite sprite = unit.requireSprite();

            this.queueFront(unit, ScrOrderIds.UNBURROWING, null, sprite.getX(), sprite.getY(), ScrUnitIds.NONE);

            // Queuing must replace the head before activation reenters; fail if allocation left the head unchanged.
            if (unit.getFirstOrder() == order) {
                throw Exceptions.newUnsupportedOperationException("A burrowed computer unit {0} cannot insert Unburrowing before its pending order.",
                                                                  unit.getIndex());
            }

            this.completeOrder(unit, instructionLimit);

            return;
        }

        if (order == null ||
            (unit.getStatus() & (UnitStatusFlags.ORDER_NOT_INTERRUPTIBLE | UnitStatusFlags.ISCRIPT_NO_BREAK)) != 0 &&
            order.getOrderId() != ScrOrderIds.DIE) {
            return;
        }

        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null || sprite.getMainImage() == null) {
            throw Exceptions.newIllegalStateException("Order activation requires the retained main image.");
        }

        OrderPool pool = this.requireGame().getOrderPool();

        if (pool == null) {
            throw Exceptions.newIllegalStateException("Order activation requires an established pool.");
        }

        ScrUnitTypeView unitTypes = this.requireGame().requireGameData().getUnitTypes();
        ScrOrderTypeView orderTypes = this.requireGame().requireGameData().getOrderTypes();
        Unit subunit = unit.getSubunit();
        int orderId = order.getOrderId();
        Unit targetUnit = order.getTargetUnit();
        int targetUnitType = order.getUnitType();
        int positionX = order.getTargetX();
        int positionY = order.getTargetY();

        pool.remove(unit, order, orderTypes);

        int status = unit.getStatus() & ~UnitStatusFlags.ORDER_NOT_INTERRUPTIBLE;

        if (orderTypes.getCanBeInterrupted(orderId) == 0) {
            status |= UnitStatusFlags.ORDER_NOT_INTERRUPTIBLE;
        }

        unit.setStatus(status & ~(UnitStatusFlags.READY_TO_ATTACK | UnitStatusFlags.AUTOMATIC_ATTACK_STARTED));
        unit.setUserActionFlags(unit.getUserActionFlags() & (~Unit.USER_ACTION_ORDER_DONE & BYTE_MASK));
        unit.setPrimaryOrderCountdown(0);
        unit.setMovementDestinationCountdown(0);
        unit.setPrimaryOrder(orderId);

        if (targetUnit != null) {
            if (targetUnit.getSpriteOrNull() == null || targetUnit.isDestroyed()) {
                throw Exceptions.newIllegalStateException("Cannot activate an order with a dead target.");
            }

            positionX      = targetUnit.getSpriteOrNull().getX();
            positionY      = targetUnit.getSpriteOrNull().getY();
            targetUnitType = ScrUnitIds.NONE;
        }

        unit.setOrderTargetUnitType(targetUnitType);
        unit.setOrderTargetUnit(targetUnit);
        unit.setOrderTargetX(positionX);
        unit.setOrderTargetY(positionY);
        unit.setPrimaryOrderState(0);

        // The activation clears the automatic target only for units without a computer record.
        if (unit.getComputerRecord() == null) {
            unit.setAutomaticTargetUnit(null);
        }

        this.requireGame().getIscriptExecutor().runToIdle(unit, instructionLimit);

        if ((unitTypes.getFlags(unit.getUnitId()) & UnitTypeFlags.SUBUNIT) == 0 && subunit != null &&
            (unitTypes.getFlags(subunit.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0) {
            int inheritedOrder = orderId;

            if (orderId == unitTypes.getReturnToIdleOrder(unit.getUnitId())) {
                inheritedOrder = unitTypes.getReturnToIdleOrder(subunit.getUnitId());
            } else if (orderId == unitTypes.getAttackUnitOrder(unit.getUnitId())) {
                inheritedOrder = unitTypes.getAttackUnitOrder(subunit.getUnitId());
            } else if (orderId == unitTypes.getAttackMoveOrder(unit.getUnitId())) {
                inheritedOrder = unitTypes.getAttackMoveOrder(subunit.getUnitId());
            } else if (orderTypes.getSubunitInheritance(orderId) == 0) {
                return;
            }

            this.issue(subunit, inheritedOrder, targetUnit, positionX, positionY, targetUnitType, false, instructionLimit);
        }
    }

    /**
     * Applies the queued-order capacity test through {@link GameRulesVersion#appendsOrder}. A ConstructingBuilding unit requesting ResetCollision
     * with a full queue first appends it and then still runs the capacity test, which appends a second copy only when the test passes.
     *
     * @param unit           the receiving unit
     * @param orderId        the order BYTE
     * @param targetUnit     the target unit, or {@code null}
     * @param positionX      the target X coordinate
     * @param positionY      the target Y coordinate
     * @param targetUnitType the target unit type, or {@link ScrUnitIds#NONE} for none
     * @return {@code true} if the capacity test allows the order to be appended, {@code false} otherwise
     * @throws UnsupportedOperationException if the order pool is not initialized
     */
    private boolean applyQueuedOrderCapacity(@NotNull Unit unit,
                                             int orderId,
                                             @Nullable Unit targetUnit,
                                             int positionX, int positionY,
                                             int targetUnitType) {
        GameRulesVersion rules = this.requireGame().getRulesVersion();

        if (rules.isLegacyReplay()) {
            return true;
        }

        OrderPool pool = this.requireGame().requireOrderPool();

        if (unit.getPrimaryOrder() == ScrOrderIds.CONSTRUCTING_BUILDING && orderId == ScrOrderIds.RESET_COLLISION &&
            unit.getHighlightedOrderCount() >= GameRulesVersion.HIGHLIGHTED_ORDER_CAPACITY) {
            this.queueBack(unit, orderId, targetUnit, positionX, positionY, targetUnitType);
        }

        return rules.appendsOrder(pool.isNearlyFull(), unit.getHighlightedOrderCount(), unit.getPrimaryOrder());
    }

    private @NotNull Game requireGame() {
        Game attached = this.game;

        if (attached == null) {
            throw Exceptions.newIllegalStateException("The SC:R order system is not attached.");
        }

        return attached;
    }
}
