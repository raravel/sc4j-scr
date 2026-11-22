package org.fogofwarlabs.sc4j.scr.game.ability;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrWeaponIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrImageTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.SoundIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.SpriteIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerPlayerPhase;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.movement.FlingyMotionUtils;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteEffect;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.fogofwarlabs.sc4j.scr.game.visibility.SpriteVisibility;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.function.Predicate;

/**
 * Loads units into transports and Bunkers and unloads them again.
 * <p>
 * A loaded unit refers to its container through its parent link and occupies a handle slot. Transports with computer records use the computer pickup,
 * early-unload and failed-placement paths.
 */
public final class UnitTransport implements TransportCargo {
    /**
     * The order states of {@link ScrOrderIds#ENTER_TRANSPORT}: started, boarding.
     */
    private static final int ENTER_STATE_START = 0;
    private static final int ENTER_STATE_BOARDING = 1;
    /**
     * The order states of {@link ScrOrderIds#PICKUP_TRANSPORT}: started, a transport with a computer record waiting for its passenger.
     */
    private static final int PICKUP_STATE_START = 0;
    private static final int PICKUP_STATE_WAITING = 1;
    /**
     * The main order timer between two pickup steps of a transport.
     */
    private static final int PICKUP_INTERVAL = 7;
    /**
     * The range in pixels within which a unit boards its transport.
     */
    private static final int BOARDING_RANGE = 1;
    /**
     * The largest {@code units.dat} space a unit may require to load into a building container.
     */
    private static final int BUNKER_MAXIMUM_PASSENGER_SPACE = 1;

    /**
     * The order states of {@link ScrOrderIds#MOVE_UNLOAD}: started, moving to the unload point.
     */
    private static final int MOVE_UNLOAD_STATE_START = 0;
    private static final int MOVE_UNLOAD_STATE_MOVING = 1;
    /**
     * The distance in pixels from the unload point at which a transport unloads.
     */
    private static final int UNLOAD_ARRIVAL_DISTANCE = 16;
    /**
     * The largest distance in pixels at which a transport with a computer record may unload before it arrives.
     */
    private static final int EARLY_UNLOAD_MAXIMUM_DISTANCE = 0xCA0;
    /**
     * The largest distance in pixels at which a transport with a computer record unloads a Reaver early.
     */
    private static final int EARLY_REAVER_UNLOAD_MAXIMUM_DISTANCE = 0x200;
    /**
     * The main order timer of a transport after it unloaded a unit.
     */
    private static final int UNLOAD_INTERVAL = 15;
    /**
     * The main order timer of an unloaded Reaver and the spell cooldown of another unloaded unit.
     */
    private static final int UNLOADED_REAVER_DELAY = 30;
    private static final int UNLOADED_SPELL_COOLDOWN = 30;

    /**
     * The main order timer of a unit in a Bunker between two random target searches.
     */
    private static final int BUNKER_GUARD_SEARCH_DELAY = 15;
    /**
     * The direction step of the Bunker overlay, {@code 1/8} turn, and the mask of its {@code 8} direction indexes.
     */
    private static final int BUNKER_OVERLAY_DIRECTION_STEP = FlingyMotionUtils.DIRECTION_EIGHTH_TURN;
    private static final int BUNKER_OVERLAY_INDEX_MASK = 7;
    /**
     * The direction step of the Firebat flame, {@code 1/16} turn, and the mask of its {@code 16} direction indexes.
     */
    private static final int FLAME_DIRECTION_STEP = 16;
    private static final int FLAME_DIRECTION_INDEX_MASK = 15;

    private static final int BYTE_MASK = 0xFF;
    private static final int WORD_BITS = 16;
    private static final int FIXED_POINT_FRACTION_BITS = 8;
    private static final int DIRECTION_ROUNDING_DIVISOR = 2;

    private final @NotNull Game game;

    /**
     * Creates a {@link UnitTransport} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitTransport(@NotNull Game game) {
        this.game = game;
    }

    /**
     * @param transport the container
     * @param slot      the handle slot from {@code 0} through {@code 7}
     * @return the live unit in the slot, or {@code null}
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    @Override
    public @Nullable Unit getLoadedUnit(@NotNull Unit transport, int slot) {
        int handle = transport.getLoadedUnitHandle(slot);
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        if (pool.getCapacity() > UnitPool.CLASSIC_UNIT_CAPACITY) {
            handle |= ((transport.getLoadedUnitHandleExtensionBits() >>> (slot * Unit.LOADED_HANDLE_EXTENSION_BITS)) &
                       Unit.LOADED_HANDLE_EXTENSION_MASK) << WORD_BITS;
        }

        return handle == 0 ? null : pool.resolveHandle(handle);
    }

    /**
     * @param transport the container
     * @return {@code true} if any slot holds a live unit, {@code false} otherwise
     */
    @Override
    public boolean hasLoadedUnits(@NotNull Unit transport) {
        for (int slot = 0; slot < Unit.LOADED_UNIT_SLOTS; slot++) {
            if (this.getLoadedUnit(transport, slot) != null) {
                return true;
            }
        }

        return false;
    }

    /**
     * Sums the {@code units.dat} space required by the live units in all {@link Unit#LOADED_UNIT_SLOTS} loaded-unit slots of a container, whatever
     * space its type provides.
     *
     * @param transport the container
     * @return the occupied space
     * @throws NullPointerException if the world was built without game data
     */
    public int getOccupiedSpace(@NotNull Unit transport) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int space = 0;

        for (int slot = 0; slot < Unit.LOADED_UNIT_SLOTS; slot++) {
            Unit loaded = this.getLoadedUnit(transport, slot);

            if (loaded != null) {
                space += units.getSpaceRequired(loaded.getUnitId());
            }
        }

        return space;
    }

    /**
     * Visits the live units in the container's loaded-unit slots below the {@code units.dat} space provided by its type, in slot order, until the
     * visitor accepts one. Each slot is read when it is visited, so a unit the visitor removes from the container is not visited again.
     *
     * @param transport the container
     * @param visitor   the test run on each loaded unit; {@code true} ends the walk
     * @return {@code true} if the visitor accepted a loaded unit, {@code false} otherwise
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the container type provides more space than the loaded-unit slots
     */
    public boolean forEachLoadedUnit(@NotNull Unit transport, @NotNull Predicate<Unit> visitor) {
        int slots = this.game.requireGameData().getUnitTypes().getSpaceProvided(transport.getUnitId());

        if (slots > Unit.LOADED_UNIT_SLOTS) {
            throw Exceptions.newUnsupportedOperationException("Unit type {0} provides more space than the loaded-unit slots.", transport.getUnitId());
        }

        for (int slot = 0; slot < slots; slot++) {
            Unit loaded = this.getLoadedUnit(transport, slot);

            if (loaded != null && visitor.test(loaded)) {
                return true;
            }
        }

        return false;
    }

    /**
     * @param transport the transport or Bunker
     * @param target    the unit to load
     * @return {@code true} if the completed, enabled container of the same owner has room for the unburrowed target (a building takes only non-Zerg
     * Terran units requiring at most {@code 1} space), {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    public boolean canLoad(@NotNull Unit transport, @NotNull Unit target) {
        if ((transport.getStatus() & UnitStatusFlags.COMPLETED) == 0 || UnitOrderEligibility.isDisabled(transport)) {
            return false;
        }

        if ((target.getStatus() & UnitStatusFlags.BURROWED) != 0 || transport.getOwnerIndex() != target.getOwnerIndex()) {
            return false;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int required = units.getSpaceRequired(target.getUnitId());

        if ((units.getFlags(transport.getUnitId()) & UnitTypeFlags.BUILDING) != 0) {
            int race = units.getGroupFlags(target.getUnitId());

            if ((race & UnitGroupFlags.ZERG) != 0 || (race & UnitGroupFlags.TERRAN) == 0 || required > BUNKER_MAXIMUM_PASSENGER_SPACE) {
                return false;
            }
        }

        int left = units.getSpaceProvided(transport.getUnitId());

        for (int slot = 0; slot < Unit.LOADED_UNIT_SLOTS; slot++) {
            Unit loaded = this.getLoadedUnit(transport, slot);

            if (loaded != null) {
                left -= units.getSpaceRequired(loaded.getUnitId());
            }
        }

        return Integer.compareUnsigned(left, required) >= 0;
    }

    /**
     * Issues {@link ScrOrderIds#UNLOAD} to eligible selected transports that hold units.
     *
     * @param owner            the command player
     * @param queue            whether to append the order
     * @param instructionLimit the order activation budget
     * @throws UnsupportedOperationException if the selection storage is missing
     */
    public void unloadAll(int owner, boolean queue, int instructionLimit) {
        UnitSelection selection = this.game.getSelection();

        if (selection == null) {
            throw Exceptions.newUnsupportedOperationException("Unloading requires initialized selection storage.");
        }

        for (int position = 0; position < UnitSelection.MAXIMUM_SELECTED; position++) {
            Unit unit = selection.getSelectedUnit(owner, position);

            if (unit == null) {
                break;
            }

            if (unit.isDestroyed()) {
                continue;
            }

            if (!this.game.getUnitOrderEligibility().canReceiveOrder(unit, ScrOrderIds.UNLOAD, owner) || !this.hasLoadedUnits(unit)) {
                continue;
            }

            this.game.getOrders().issue(unit, ScrOrderIds.UNLOAD, null, 0, 0, ScrUnitIds.NONE, queue, instructionLimit);
        }
    }

    /**
     * Unloads a loaded unit belonging to the command player.
     *
     * @param owner            the command player
     * @param target           the loaded unit, or {@code null}
     * @param instructionLimit the order and script budget
     * @return {@code true} if the unit was unloaded, {@code false} otherwise
     */
    public boolean unload(int owner, @Nullable Unit target, int instructionLimit) {
        if (target == null || target.getOwnerIndex() != owner) {
            return false;
        }

        if (target.getParentUnit() == null || (target.getStatus() & UnitStatusFlags.LOADED) == 0) {
            return false;
        }

        return this.unloadUnit(target, instructionLimit);
    }

    /**
     * Follows a loadable transport and boards when in range.
     *
     * @param unit             the boarding unit
     * @param instructionLimit the order and script budget
     * @throws IllegalStateException if the unit or the target has no sprite
     */
    public void updateEnterTransport(@NotNull Unit unit, int instructionLimit) {
        Unit target = unit.getOrderTargetUnit();

        if (target == null || (target.getStatus() & UnitStatusFlags.HALLUCINATION) != 0 || !this.game.getUnitQueries().isTransport(target) ||
            !this.canLoad(target, unit)) {
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        if (unit.getPrimaryOrderState() == ENTER_STATE_START && target.getPrimaryOrder() != ScrOrderIds.PICKUP_TRANSPORT) {
            if ((target.getStatus() & UnitStatusFlags.CAN_MOVE) != 0) {
                Sprite sprite = unit.requireSprite();

                this.game.getOrders().queueFront(target, ScrOrderIds.PICKUP_TRANSPORT, unit, sprite.getX(), sprite.getY(), ScrUnitIds.NONE);
                this.game.getOrders().activateNext(target, instructionLimit);
            }

            unit.setPrimaryOrderState(ENTER_STATE_BOARDING);
        }

        Sprite targetSprite = target.requireSprite();

        unit.setDirectionTargetX(targetSprite.getX());
        unit.setDirectionTargetY(targetSprite.getY());
        this.game.getEngineGame().getCombat().pursueTarget(unit, target, instructionLimit);

        if (this.game.getUnitTargeting().isInRange(unit, target, BOARDING_RANGE)) {
            this.load(target, unit, instructionLimit);
        }
    }

    /**
     * Runs the transport pickup order. A passenger with a computer record must be waiting for the pickup ({@link ScrOrderIds#PICKUP4}), and a transport
     * with a computer record waits in its order instead of continuing with {@link ScrOrderIds#PICKUP_IDLE}.
     *
     * @param unit             the transport
     * @param instructionLimit the order and script budget
     * @throws IllegalStateException if the target has no sprite
     */
    public void updatePickupTransport(@NotNull Unit unit, int instructionLimit) {
        Unit target = unit.getOrderTargetUnit();

        if (target != null && target.getComputerRecord() != null && target.getPrimaryOrder() != ScrOrderIds.PICKUP4) {
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        if (target != null && unit.getPrimaryOrderState() != PICKUP_STATE_START) {
            return;
        }

        if (target == null || !this.canLoad(unit, target)) {
            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        if (unit.getOrderTimer() != 0) {
            return;
        }

        unit.setOrderTimer(PICKUP_INTERVAL);

        if (this.game.getUnitTargeting().isInRange(unit, target, BOARDING_RANGE)) {
            this.game.getUnitPointMovement().stopAndFaceDestination(unit);

            if (target.getPrimaryOrder() != ScrOrderIds.ENTER_TRANSPORT && target.getPrimaryOrder() != ScrOrderIds.PICKUP4) {
                this.game.getOrders().issue(target, ScrOrderIds.ENTER_TRANSPORT, unit, 0, 0, ScrUnitIds.NONE, false, instructionLimit);
            }

            if (unit.getComputerRecord() != null) {
                unit.setPrimaryOrderState(PICKUP_STATE_WAITING);

                return;
            }

            Sprite targetSprite = target.requireSprite();

            this.game.getOrders().queueFront(unit, ScrOrderIds.PICKUP_IDLE, target, targetSprite.getX(), targetSprite.getY(), ScrUnitIds.NONE);
            this.game.getOrders().activateNext(unit, instructionLimit);
        } else {
            this.game.getUnitTargetMovement().moveToTargetReset(unit, target);
        }
    }

    /**
     * Resumes picking up the remembered unit. Without a loadable target a transport with a computer record continues with
     * {@link ScrOrderIds#COMPUTER_AI}.
     *
     * @param unit             the transport
     * @param instructionLimit the order activation budget
     * @throws IllegalStateException if the target has no sprite
     */
    public void updatePickupIdle(@NotNull Unit unit, int instructionLimit) {
        Unit target = unit.getOrderTargetUnit();

        if (target == null || unit.getComputerRecord() != null && !this.canLoad(unit, target)) {
            if (unit.getComputerRecord() != null) {
                this.game.getOrders().issueUntargeted(unit, ScrOrderIds.COMPUTER_AI, instructionLimit);
            }

            return;
        }

        Sprite targetSprite = target.requireSprite();

        this.game.getOrders().queueFront(unit, ScrOrderIds.PICKUP_TRANSPORT, target, targetSprite.getX(), targetSprite.getY(), ScrUnitIds.NONE);
        this.game.getOrders().activateNext(unit, instructionLimit);
    }

    /**
     * Orders a loadable target into the Bunker, then finishes the pickup order.
     *
     * @param unit             the Bunker
     * @param instructionLimit the order activation budget
     */
    public void updatePickupBunker(@NotNull Unit unit, int instructionLimit) {
        Unit target = unit.getOrderTargetUnit();

        if (target != null && this.canLoad(unit, target)) {
            this.game.getOrders().issue(target, ScrOrderIds.ENTER_TRANSPORT, unit, 0, 0, ScrUnitIds.NONE, false, instructionLimit);
        }

        this.game.getOrders().finishOrder(unit, instructionLimit);
    }

    /**
     * Runs the unload order: grounded buildings try every unit at once, other transports a unit per main timer. A transport with a computer record
     * that cannot place its passenger moves to another region and keeps the order.
     *
     * @param unit             the transport
     * @param instructionLimit the order and script budget
     * @throws UnsupportedOperationException if a computer transport runs without the computer-player phase
     */
    public void updateUnload(@NotNull Unit unit, int instructionLimit) {
        if (UnitQueries.isGroundedBuilding(unit)) {
            for (int slot = 0; slot < Unit.LOADED_UNIT_SLOTS; slot++) {
                Unit loaded = this.getLoadedUnit(unit, slot);

                if (loaded != null) {
                    this.unloadUnit(loaded, instructionLimit);
                }
            }

            this.game.getOrders().finishOrder(unit, instructionLimit);

            return;
        }

        if (unit.getOrderTimer() != 0) {
            return;
        }

        Unit first = null;
        boolean last = true;

        for (int slot = 0; slot < Unit.LOADED_UNIT_SLOTS; slot++) {
            Unit loaded = this.getLoadedUnit(unit, slot);

            if (loaded == null) {
                continue;
            }

            if (first == null) {
                first = loaded;
            } else {
                last = false;

                break;
            }
        }

        boolean unloaded = first != null && this.unloadUnit(first, instructionLimit);

        if (first != null && !unloaded && unit.getComputerRecord() != null && !UnitQueries.isGroundedBuilding(unit)) {
            ComputerPlayerPhase phase = this.game.getComputerPlayerPhase();

            if (phase == null) {
                throw Exceptions.newUnsupportedOperationException("Computer transports require the computer-player phase.");
            }

            this.game.getComputerOrders().moveTransportToNeighbor(phase, unit, instructionLimit);

            return;
        }

        if (!unloaded || last) {
            this.game.getOrders().finishOrder(unit, instructionLimit);
        }
    }

    /**
     * Moves to the unload point, then activates {@link ScrOrderIds#UNLOAD}. A transport with a computer record may unload before it arrives.
     *
     * @param unit             the transport
     * @param instructionLimit the order and script budget
     * @throws IllegalStateException if the order target has no sprite
     */
    public void updateMoveUnload(@NotNull Unit unit, int instructionLimit) {
        if (unit.getPrimaryOrderState() == MOVE_UNLOAD_STATE_START && unit.getOrderTargetUnit() != null) {
            Sprite targetSprite = unit.getOrderTargetUnit().requireSprite();

            unit.setOrderTargetX(targetSprite.getX());
            unit.setOrderTargetY(targetSprite.getY());
            unit.setOrderTargetUnit(null);
            unit.setPrimaryOrderState(MOVE_UNLOAD_STATE_MOVING);
        }

        int positionX = (short) unit.getOrderTargetX();
        int positionY = (short) unit.getOrderTargetY();
        int distance = RegionPathSearch.calculateDistance(unit.getFixedPositionX(), positionX << FIXED_POINT_FRACTION_BITS,
                                                          unit.getFixedPositionY(), positionY << FIXED_POINT_FRACTION_BITS) >>
                       FIXED_POINT_FRACTION_BITS;

        if (distance <= UNLOAD_ARRIVAL_DISTANCE) {
            this.game.getOrders().queueFront(unit, ScrOrderIds.UNLOAD, null, 0, 0, ScrUnitIds.NONE);
            this.game.getOrders().activateNext(unit, instructionLimit);
        } else {
            this.game.getEngineGame().getMovement().setDestination(unit, positionX, positionY);
            unit.setDirectionTargetX(positionX);
            unit.setDirectionTargetY(positionY);

            if (unit.getComputerRecord() != null && this.canUnloadEarly(unit, positionX, positionY, distance)) {
                this.game.getOrders().queueFront(unit, ScrOrderIds.UNLOAD, null, 0, 0, ScrUnitIds.NONE);
                this.game.getOrders().activateNext(unit, instructionLimit);
            }
        }
    }

    /**
     * Tests whether a transport with a computer record may unload before it reaches the unload point: it stands on the point's ground height, within
     * {@link #EARLY_UNLOAD_MAXIMUM_DISTANCE}, on a safe route from its region to the point's, carries no worker and can place every passenger where
     * it is; a Reaver needs the point within {@link #EARLY_REAVER_UNLOAD_MAXIMUM_DISTANCE}.
     *
     * @param unit      the transport
     * @param positionX the horizontal unload point
     * @param positionY the vertical unload point
     * @param distance  the distance to the unload point
     * @return {@code true} if the transport unloads here, {@code false} otherwise
     * @throws IllegalStateException if the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    private boolean canUnloadEarly(@NotNull Unit unit, int positionX, int positionY, int distance) {
        Sprite sprite = unit.requireSprite();
        int vertical = (short) sprite.getY();
        int horizontal = (short) sprite.getX();

        if (this.game.getComputerResources().getTerrainHeight(horizontal, vertical) !=
            this.game.getComputerResources().getTerrainHeight(positionX, positionY)) {
            return false;
        }

        if (Integer.compareUnsigned(distance, EARLY_UNLOAD_MAXIMUM_DISTANCE) > 0) {
            return false;
        }

        if (!this.game.getComputerMilitary().isRouteSafe(unit, positionX, positionY)) {
            return false;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        for (int slot = 0; slot < Unit.LOADED_UNIT_SLOTS; slot++) {
            Unit passenger = this.getLoadedUnit(unit, slot);

            if (passenger != null && units.hasEntry(passenger.getUnitId()) &&
                (units.getFlags(passenger.getUnitId()) & UnitTypeFlags.WORKER) != 0) {
                return false;
            }
        }

        boolean near = Integer.compareUnsigned(distance, EARLY_REAVER_UNLOAD_MAXIMUM_DISTANCE) <= 0;

        for (int slot = 0; slot < Unit.LOADED_UNIT_SLOTS; slot++) {
            Unit passenger = this.getLoadedUnit(unit, slot);

            if (passenger == null) {
                continue;
            }

            if (this.game.getBaseType(passenger) == ScrUnitIds.PROTOSS_REAVER && !near) {
                return false;
            }

            // The test moves the passenger to the transport before its placement search.
            this.game.getUnitPositioning().move(passenger, sprite.getX(), sprite.getY());

            if (!this.game.getUnitPositioning().find(passenger, sprite.getX(), sprite.getY(), new int[UnitPositioning.POSITION_COORDINATES])) {
                return false;
            }
        }

        return true;
    }

    /**
     * Detaches a dying loaded unit from its container, then unloads Bunker passengers or kills other passengers.
     *
     * @param unit             the dying unit
     * @param instructionLimit the order and script budget
     */
    public void destroy(@NotNull Unit unit, int instructionLimit) {
        if ((unit.getStatus() & UnitStatusFlags.LOADED) != 0) {
            this.unloadWithoutPlacement(unit, true);
        }

        for (int slot = 0; slot < Unit.LOADED_UNIT_SLOTS; slot++) {
            Unit loaded = this.getLoadedUnit(unit, slot);

            if (loaded == null) {
                continue;
            }

            if (unit.getUnitId() != ScrUnitIds.TERRAN_BUNKER || !this.unloadUnit(loaded, instructionLimit)) {
                this.game.getUnitLifecycle().kill(loaded, instructionLimit);
            }
        }

        for (int slot = 0; slot < Unit.LOADED_UNIT_SLOTS; slot++) {
            unit.setLoadedUnitHandle(slot, 0);
        }

        unit.setLoadedUnitHandleExtensionBits(0);
    }

    /**
     * Kills the live units held in the container's loaded slots.
     *
     * @param transport        the dying transport
     * @param instructionLimit the order budget
     */
    public void killLoadedUnits(@NotNull Unit transport, int instructionLimit) {
        for (int slot = 0; slot < Unit.LOADED_UNIT_SLOTS; slot++) {
            Unit loaded = this.getLoadedUnit(transport, slot);

            if (loaded != null) {
                this.game.getUnitLifecycle().kill(loaded, instructionLimit);
            }
        }
    }

    /**
     * Stores the target's handle in the first free slot, hides it and places it or its turret at a grounded container.
     *
     * @param transport        the transport or Bunker
     * @param target           the unit to load
     * @param instructionLimit the animation budget
     * @throws IllegalStateException         if the target or the transport has no sprite, or the transport has no free slot
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    public void load(@NotNull Unit transport, @NotNull Unit target, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int provided = units.getSpaceProvided(transport.getUnitId());
        int slot = -1;

        for (int index = 0; index < provided; index++) {
            if (this.getLoadedUnit(transport, index) == null) {
                slot = index;

                break;
            }
        }

        if (slot < 0) {
            throw Exceptions.newIllegalStateException("A transport accepted a unit without a free slot.");
        }

        this.game.getUnitSounds().play(SoundIds.TRANSPORT_LOAD + this.game.getUnitSounds().getRace(transport), transport);

        int handle = this.game.getUnitSystem().requireUnitPool().getHandle(target.getIndex());

        transport.setLoadedUnitHandle(slot, handle);

        if (this.game.getUnitSystem().requireUnitPool().getCapacity() > UnitPool.CLASSIC_UNIT_CAPACITY) {
            int shift = slot * Unit.LOADED_HANDLE_EXTENSION_BITS;
            int bits = transport.getLoadedUnitHandleExtensionBits() & ~(Unit.LOADED_HANDLE_EXTENSION_MASK << shift) |
                       ((handle >>> WORD_BITS) & Unit.LOADED_HANDLE_EXTENSION_MASK) << shift;

            transport.setLoadedUnitHandleExtensionBits(bits);
        }

        this.game.getHooks().notifyTransportLoaded(target);
        target.setParentUnit(transport);
        target.setStatus(target.getStatus() | UnitStatusFlags.LOADED);
        this.game.getUnitLifecycle().hide(target, true);
        this.game.getIscriptExecutor().runSpriteAnimation(target.requireSprite(),
                                                          target,
                                                          IscriptExecutor.ANIMATION_WALKING_TO_IDLE,
                                                          0,
                                                          instructionLimit);

        if (UnitQueries.isGroundedBuilding(transport)) {
            target.setStatus(target.getStatus() & ~UnitStatusFlags.CAN_MOVE | UnitStatusFlags.IN_BUNKER);
            this.game.getUnitInitializer().resetMovementState(target);

            Sprite transportSprite = transport.requireSprite();
            Unit turret = target.getSubunit();

            if (turret != null && this.game.getUnitTargeting().isTurret(turret)) {
                // Only the turret moves to the Bunker when the passenger has a turret.
                this.game.getUnitPositioning().move(turret, transportSprite.getX(), transportSprite.getY());
                turret.setStatus(turret.getStatus() & ~UnitStatusFlags.CAN_MOVE | UnitStatusFlags.IN_BUNKER);
                this.game.getUnitInitializer().resetMovementState(turret);
            } else {
                this.game.getUnitPositioning().move(target, transportSprite.getX(), transportSprite.getY());
            }
        }

        ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

        if (simulation != null) {
            simulation.reportUnitLoaded(target, transport);
        }
    }

    /**
     * Places a loaded unit next to its container and returns it to its idle order (a unit with a computer record to {@link ScrOrderIds#COMPUTER_AI}); a
     * transport then waits, and the unloaded unit waits to attack.
     *
     * @param unit             the loaded unit
     * @param instructionLimit the order and script budget
     * @return {@code true} if the unit was unloaded, {@code false} otherwise
     * @throws IllegalStateException if the container has no sprite
     */
    private boolean unloadUnit(@NotNull Unit unit, int instructionLimit) {
        Unit container = unit.getParentUnit();

        if (container == null || UnitOrderEligibility.isDisabled(container)) {
            return false;
        }

        if (!UnitQueries.isGroundedBuilding(container) && container.getOrderTimer() != 0) {
            return false;
        }

        Sprite containerSprite = container.requireSprite();

        this.game.getUnitPositioning().move(unit, containerSprite.getX(), containerSprite.getY());

        int[] position = new int[UnitPositioning.POSITION_COORDINATES];

        if (!this.game.getUnitPositioning().find(unit, containerSprite.getX(), containerSprite.getY(), position)) {
            return false;
        }

        container.setOrderTimer(UNLOAD_INTERVAL);
        this.game.getUnitPositioning().move(unit, position[UnitPositioning.X_INDEX], position[UnitPositioning.Y_INDEX]);

        if (unit.getSubunit() != null) {
            this.game.getUnitPositioning().move(unit.getSubunit(), position[UnitPositioning.X_INDEX], position[UnitPositioning.Y_INDEX]);
        }

        this.game.getIscriptExecutor().runToIdle(unit, instructionLimit);
        this.unloadWithoutPlacement(unit, false);
        this.game.getOrders().returnToIdle(unit, instructionLimit);

        if (!UnitQueries.isGroundedBuilding(container)) {
            if (this.game.getBaseType(unit) == ScrUnitIds.PROTOSS_REAVER) {
                unit.setOrderTimer(UNLOADED_REAVER_DELAY);
            } else {
                int cooldowns = unit.getCooldownTimers();
                int groundWeapon = this.game.getUnitTargeting().getGroundWeapon(unit);
                int airWeapon = this.game.getUnitTargeting().getAirWeapon(unit);

                if (groundWeapon != ScrWeaponIds.NONE) {
                    cooldowns = cooldowns & ~Unit.GROUND_COOLDOWN_MASK |
                                (this.game.getUnitAttack().getModifiedCooldown(unit, groundWeapon) & BYTE_MASK) << Unit.GROUND_COOLDOWN_SHIFT;
                }

                if (airWeapon != ScrWeaponIds.NONE) {
                    cooldowns = cooldowns & ~Unit.AIR_COOLDOWN_MASK |
                                (this.game.getUnitAttack().getModifiedCooldown(unit, airWeapon) & BYTE_MASK) << Unit.AIR_COOLDOWN_SHIFT;
                }

                unit.setCooldownTimers(cooldowns & ~Unit.SPELL_COOLDOWN_MASK | UNLOADED_SPELL_COOLDOWN << Unit.SPELL_COOLDOWN_SHIFT);
            }
        }

        return true;
    }

    /**
     * Frees the unit's container slot and restores movement state for the unit and its turret.
     *
     * @param unit               the loaded unit
     * @param containerDestroyed {@code true} if the container dies, which moves the unit to it instead of showing it
     * @throws IllegalStateException         if the unit is missing from its container or the container has no sprite
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the creation context is missing or the unit pool is not initialized
     */
    private void unloadWithoutPlacement(@NotNull Unit unit, boolean containerDestroyed) {
        if ((unit.getStatus() & UnitStatusFlags.LOADED) == 0) {
            return;
        }

        Unit container = unit.getParentUnit();

        if (container != null) {
            int handle = this.game.getUnitSystem().requireUnitPool().getHandle(unit.getIndex());
            int slot = -1;
            int provided = this.game.requireGameData().getUnitTypes().getSpaceProvided(container.getUnitId());

            for (int index = 0; index < provided; index++) {
                int stored = container.getLoadedUnitHandle(index);

                if (this.game.getUnitSystem().requireUnitPool().getCapacity() > UnitPool.CLASSIC_UNIT_CAPACITY) {
                    stored |= ((container.getLoadedUnitHandleExtensionBits() >>> (index * Unit.LOADED_HANDLE_EXTENSION_BITS)) &
                               Unit.LOADED_HANDLE_EXTENSION_MASK) << WORD_BITS;
                }

                if (stored == handle) {
                    slot = index;

                    break;
                }
            }

            if (slot < 0) {
                throw Exceptions.newIllegalStateException("A loaded unit is missing from its container.");
            }

            container.setLoadedUnitHandle(slot, 0);

            if (this.game.getUnitSystem().requireUnitPool().getCapacity() > UnitPool.CLASSIC_UNIT_CAPACITY) {
                container.setLoadedUnitHandleExtensionBits(container.getLoadedUnitHandleExtensionBits() &
                                                           ~(Unit.LOADED_HANDLE_EXTENSION_MASK << (slot * Unit.LOADED_HANDLE_EXTENSION_BITS)));
            }

            unit.setParentUnit(null);
            unit.setStatus(unit.getStatus() & ~UnitStatusFlags.LOADED);

            if (containerDestroyed) {
                Sprite containerSprite = container.requireSprite();

                this.game.getUnitPositioning().move(unit, containerSprite.getX(), containerSprite.getY());
            } else {
                UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();

                if (context == null) {
                    throw Exceptions.newUnsupportedOperationException("Unloading requires the creation context.");
                }

                this.game.getUnitVisibility().show(unit, context.isFoliageEnabled());
                this.game.getUnitSounds().play(SoundIds.TRANSPORT_UNLOAD + this.game.getUnitSounds().getRace(container), container);
            }
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        boolean canMove = (units.getFlags(unit.getUnitId()) & UnitTypeFlags.CAN_MOVE) != 0;

        unit.setStatus(unit.getStatus() & ~UnitStatusFlags.IN_BUNKER & ~UnitStatusFlags.CAN_MOVE | (canMove ? UnitStatusFlags.CAN_MOVE : 0));
        this.game.getUnitInitializer().resetMovementState(unit);

        Unit turret = unit.getSubunit();

        if (turret != null && this.game.getUnitTargeting().isTurret(turret)) {
            // Clear the turret's Bunker state and reset its movement independently.
            boolean turretCanMove = (units.getFlags(turret.getUnitId()) & UnitTypeFlags.CAN_MOVE) != 0;

            turret.setStatus(turret.getStatus() & ~UnitStatusFlags.IN_BUNKER & ~UnitStatusFlags.CAN_MOVE |
                             (turretCanMove ? UnitStatusFlags.CAN_MOVE : 0));
            this.game.getUnitInitializer().resetMovementState(turret);
        }

        ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

        if (simulation != null && container != null) {
            simulation.reportUnitUnloaded(unit, container);
        }
    }

    /**
     * Runs {@link ScrOrderIds#BUNKER_GUARD}: a Marine, Firebat or Ghost, including supported hero variants, attacks its target or periodically acquires
     * a random one.
     *
     * @param unit             the unit in a Bunker
     * @param instructionLimit the attack animation and sprite initialization budget
     */
    public void updateBunkerGuard(@NotNull Unit unit, int instructionLimit) {
        int type = unit.getUnitId();
        int baseType = this.game.getBaseType(type);
        boolean canFire = baseType == ScrUnitIds.TERRAN_MARINE || baseType == ScrUnitIds.HERO_JIM_RAYNOR_MARINE ||
                          this.game.requireGameData().getUnitTypes().isGhost(type) ||
                          baseType == ScrUnitIds.TERRAN_FIREBAT || baseType == ScrUnitIds.HERO_GUI_MONTAG;

        if (!canFire) {
            return;
        }

        unit.markReadyToAttack();

        if (this.game.getUnitAttack().tryAttackSomething(unit, instructionLimit)) {
            unit.setDirectionTargetX(unit.getOrderTargetX());
            unit.setDirectionTargetY(unit.getOrderTargetY());
        } else if (unit.getOrderTimer() == 0) {
            unit.setOrderTimer(BUNKER_GUARD_SEARCH_DELAY);

            Unit target = this.game.getUnitTargeting().findAcquireRandomTarget(unit);

            unit.setOrderTargetUnit(target);

            if (target != null) {
                unit.setPrimaryOrderCountdown(0);
            }
        }
    }

    /**
     * Creates a Bunker overlay or Firebat flame at the attack overlay point selected by the unit heading.
     *
     * @param unit             the unit firing from a Bunker
     * @param instructionLimit the sprite initialization budget
     * @throws IllegalStateException if the Bunker, its main image or its overlay offsets are missing, or the Bunker or the unit has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public void createBunkerFireAnimation(@NotNull Unit unit, int instructionLimit) {
        int directionIndex = unit.getHeading() & BYTE_MASK;
        int index = (directionIndex + BUNKER_OVERLAY_DIRECTION_STEP / DIRECTION_ROUNDING_DIVISOR) /
                    BUNKER_OVERLAY_DIRECTION_STEP & BUNKER_OVERLAY_INDEX_MASK;
        int baseType = this.game.getBaseType(unit);
        boolean firebat = baseType == ScrUnitIds.TERRAN_FIREBAT || baseType == ScrUnitIds.HERO_GUI_MONTAG;
        int spriteType = firebat ? SpriteIds.FLAME_THROWER : SpriteIds.BUNKER_OVERLAY;
        int heading = firebat
                      ? FLAME_DIRECTION_STEP *
                        ((directionIndex + FLAME_DIRECTION_STEP / DIRECTION_ROUNDING_DIVISOR) / FLAME_DIRECTION_STEP & FLAME_DIRECTION_INDEX_MASK)
                      : BUNKER_OVERLAY_DIRECTION_STEP * index;
        SpriteImage bunkerImage = unit.requireParentUnit().requireSprite().getMainImage();

        if (bunkerImage == null) {
            throw Exceptions.newIllegalStateException("Bunker fire animations require the Bunker main image.");
        }

        int offset;

        try {
            offset = this.game.requireGameData().getImageOverlayOffset(bunkerImage.getImageTypeId(),
                                                                       ScrImageTypeView.OVERLAY_ATTACK,
                                                                       bunkerImage.getDrawnFrameIndex(),
                                                                       index);
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Cannot load the Bunker attack overlay.");
        }

        int horizontal = (short) offset;

        if ((bunkerImage.getFlags() & SpriteImage.FLAG_HORIZONTALLY_FLIPPED) != 0) {
            horizontal = -horizontal;
        }

        Sprite sprite = unit.requireSprite();
        SpriteEffect effect = this.game.getIscriptExecutor().createSpriteEffect(spriteType,
                                                                                sprite.getX() + horizontal,
                                                                                sprite.getY() + (short) (offset >>> WORD_BITS),
                                                                                0,
                                                                                instructionLimit);

        if (effect == null) {
            return;
        }

        Sprite effectSprite = effect.getSprite();

        effectSprite.setElevationLevel(sprite.getElevationLevel() + 1);
        SpriteVisibility.updatePositionTile(effectSprite, this.game.getMap(), this.game.getLocalVision().getComparisonMask());
        IscriptExecutor.applySpriteDirection(this.game, effectSprite, heading);
    }
}
