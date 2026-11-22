package org.fogofwarlabs.sc4j.scr.game.production;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.movement.MovementFlags;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.*;
import org.fogofwarlabs.sc4j.scr.game.ability.UnitNuclear;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepField;
import org.fogofwarlabs.sc4j.scr.game.movement.MovementStates;
import org.fogofwarlabs.sc4j.scr.game.order.OrderPool;
import org.fogofwarlabs.sc4j.scr.game.order.ScrOrderSystem;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.path.PathPool;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.presentation.NotificationEvents;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteEffect;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.fogofwarlabs.sc4j.scr.game.visibility.SpriteVisibility;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Applies construction reservations, Probe placement, and the Zerg Drone construction lifecycle.
 */
public final class UnitConstruction {
    /**
     * The fraction bits of a fixed-point position.
     */
    private static final int FIXED_POINT_FRACTION_BITS = 8;

    /**
     * The order states of the Probe placement: started, walking to the site, braking, warping in the building.
     */
    private static final int PROBE_STATE_START = 0;
    private static final int PROBE_STATE_APPROACHING = 1;
    private static final int PROBE_STATE_BRAKING = 2;
    private static final int PROBE_STATE_WARPING = 3;
    /**
     * The pixel distance within which a Probe places an Assimilator, and any other building.
     */
    private static final int ASSIMILATOR_PLACEMENT_REACH = 88;
    private static final int PLACEMENT_REACH = 70;

    /**
     * The order states of {@link ScrOrderIds#DRONE_LAND}: started, approaching the site, landing.
     */
    private static final int LAND_STATE_START = 0;
    private static final int LAND_STATE_APPROACHING = 1;
    private static final int LAND_STATE_LANDING = 2;
    /**
     * The pixels above the site from which a Drone approaches it.
     */
    private static final int LANDING_APPROACH_OFFSET = 7;
    /**
     * The pixel range within which a Drone over a geyser lands on it as an Extractor site.
     */
    private static final int GEYSER_LANDING_RANGE = 32;
    /**
     * The pixel distance within which a Drone lands on its site.
     */
    private static final int LANDING_REACH = 128;
    /**
     * The vertical offset of a hovering Drone's shadow.
     */
    private static final int DRONE_SHADOW_OFFSET = 7;

    /**
     * The order states of {@link ScrOrderIds#INCOMPLETE_MORPHING}: the stages and the animations that start them.
     */
    private static final int MORPH_STATE_FIRST_STAGE = 0;
    private static final int MORPH_STATE_FIRST_ANIMATION = 1;
    private static final int MORPH_STATE_SECOND_STAGE = 2;
    private static final int MORPH_STATE_SECOND_ANIMATION = 3;
    private static final int MORPH_STATE_THIRD_STAGE = 4;
    private static final int MORPH_STATE_ALMOST_BUILT = 5;
    private static final int MORPH_STATE_FINISHING = 6;
    /**
     * The first morph stage ends below these quarters of the build time.
     */
    private static final int FIRST_STAGE_QUARTERS = 3;
    /**
     * The second morph stage ends below the build time divided by this divisor, {@code 1/2} of the build time.
     */
    private static final int SECOND_STAGE_DIVISOR = 2;
    /**
     * A canceled building refunds these quarters of its cost.
     */
    private static final int REFUND_QUARTERS = 3;
    /**
     * The divisor that turns a count of quarters into that fraction of a value.
     */
    private static final int QUARTER_DIVISOR = 4;
    /**
     * The fraction bits of fixed-point hit points.
     */
    private static final int HIT_POINT_FRACTION_BITS = 8;
    /**
     * The least hit points of a completed building morph: {@code 1} whole hit point.
     */
    private static final int MINIMUM_MORPH_HIT_POINTS = 1 << HIT_POINT_FRACTION_BITS;

    private final @NotNull Game game;

    /**
     * Creates a {@link UnitConstruction} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitConstruction(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Applies the cancel-construction and cancel-morph commands. The morph variant stops at the first selected unit owned by another player,
     * returning {@code false} even after earlier cancellations.
     *
     * @param owner            the command player
     * @param allSelected      whether every selected unit is canceled rather than only a single selection
     * @param instructionLimit the order, morph, creation, completion, animation, sprite and hit point budget
     * @return {@code true} if the command reports success, {@code false} otherwise
     * @throws UnsupportedOperationException if the selection storage is missing
     */
    public boolean cancelSelected(int owner, boolean allSelected, int instructionLimit) {
        UnitSelection selection = this.game.getSelection();

        if (selection == null) {
            throw Exceptions.newUnsupportedOperationException("Cancel commands require initialized selection storage.");
        }

        if (!allSelected) {
            Unit unit = selection.getSelectedUnit(owner, 0);

            if (unit == null || selection.getSelectedUnit(owner, 1) != null || unit.getOwnerIndex() != owner) {
                return false;
            }

            this.cancelBuildingUnit(unit, instructionLimit);

            return true;
        }

        boolean canceled = false;

        for (int position = 0; position < UnitSelection.MAXIMUM_SELECTED; position++) {
            Unit unit = selection.getSelectedUnit(owner, position);

            if (unit == null) {
                break;
            }

            if (unit.isDestroyed()) {
                continue;
            }

            if (unit.getOwnerIndex() != owner) {
                return false;
            }

            if (this.cancelBuildingUnit(unit, instructionLimit)) {
                canceled = true;
            }
        }

        return canceled;
    }

    /**
     * Cancels an eligible incomplete unit or building, shared by cancel commands, production queue cancellation and the computer's reaction to a hit.
     * <p>
     * A dying or completed unit, a Guardian, Lurker, Devourer, Mutalisk or Hydralisk, and a Nydus Canal linked to an exit
     * ({@link UnitQueries#getLinkedUnit}) are not canceled; a Nydus Canal without an exit is.
     * <p>
     * Incomplete Terran and Protoss buildings refund {@code 3/4} and die; incomplete units refund fully and die. An Egg, Cocoon or Lurker Egg refunds
     * the type in its current queue slot ({@link UnitQueries#getQueueSlotType}), the empty type {@code 228} included; a Cocoon or Lurker Egg reverts
     * to its previous unit through a {@link ScrOrderIds#ZERG_BIRTH} order. An incomplete unit that is not a grounded building is canceled only when
     * it can queue the {@link ScrOrderIds#DIE} order ({@link OrderPool#canQueueDieOrder}). An incomplete Zerg building morph reverts to its completed
     * previous building with its hit points retained; a Drone-built Zerg building refunds {@code 3/4}, restores the Drone with its hit points before
     * construction and removes the building's creep provider, and a canceled Extractor instead creates a new Drone and dies.
     *
     * @param unit             the incomplete unit
     * @param instructionLimit the order, morph, creation, completion, animation, sprite and hit point budget
     * @return {@code true} if the unit was canceled, {@code false} otherwise
     * @throws IllegalStateException if the order pool is missing for a unit that is not a grounded building, or if a reverting Cocoon or Lurker Egg
     *                               has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public boolean cancelBuildingUnit(@NotNull Unit unit, int instructionLimit) {
        // The Die order prevents a second cancellation and refund, even before its order state advances.
        if (unit.getPrimaryOrder() == ScrOrderIds.DIE) {
            return false;
        }

        int status = unit.getStatus();
        int unitType = unit.getUnitId();
        int baseType = this.game.getBaseType(unitType);

        if ((status & UnitStatusFlags.COMPLETED) != 0 || baseType == ScrUnitIds.ZERG_GUARDIAN || baseType == ScrUnitIds.ZERG_LURKER ||
            baseType == ScrUnitIds.ZERG_DEVOURER) {
            return false;
        }

        // A Nydus Canal linked to an exit cannot be canceled.
        if (unitType == ScrUnitIds.ZERG_NYDUS_CANAL && UnitQueries.getLinkedUnit(unit) != null) {
            return false;
        }

        if (baseType == ScrUnitIds.ZERG_MUTALISK || baseType == ScrUnitIds.ZERG_HYDRALISK) {
            return false;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        Player player = this.game.getPlayerTable().requirePlayer(unit.getOwnerIndex());

        if (UnitQueries.isGroundedBuilding(unit)) {
            if ((units.getGroupFlags(unitType) & UnitGroupFlags.ZERG) != 0) {
                this.cancelZergBuilding(unit, instructionLimit);

                return true;
            }

            player.setMinerals(player.getMinerals() + units.getMineralCost(unitType) * REFUND_QUARTERS / QUARTER_DIVISOR);
            player.setGas(player.getGas() + units.getGasCost(unitType) * REFUND_QUARTERS / QUARTER_DIVISOR);
        } else {
            OrderPool orders = this.game.getOrderPool();

            if (orders == null) {
                throw Exceptions.newIllegalStateException("Unit cancellation requires an established order pool.");
            }

            // A unit that cannot queue the Die order is not canceled.
            if (!orders.canQueueDieOrder(unit)) {
                return false;
            }

            int refundType = unitType;

            if (units.isEgg(unitType)) {
                refundType = UnitQueries.getQueueSlotType(unit, 0);
            }

            player.setMinerals(player.getMinerals() + units.getMineralCost(refundType));
            player.setGas(player.getGas() + units.getGasCost(refundType));
        }

        int revertedType = baseType == ScrUnitIds.ZERG_COCOON
                           ? ScrUnitIds.ZERG_MUTALISK
                           : baseType == ScrUnitIds.ZERG_LURKER_EGG ? ScrUnitIds.ZERG_HYDRALISK : -1;

        if (revertedType >= 0) {
            this.game.getUnitMorphing().morph(unit, revertedType, instructionLimit);
            unit.setRemainingBuildTime(0);
            UnitLifecycle.clearCurrentQueueSlot(unit);

            int flingyType = units.getFlingyType(unit.getPreviousUnitType());
            int spriteType = this.game.requireGameData().getFlingyTypes().getSprite(flingyType);

            this.game.getIscriptExecutor().replaceSpriteImages(unit,
                                                               this.game.requireGameData().getSpriteTypes().getImage(spriteType),
                                                               0,
                                                               instructionLimit);
            unit.setOrderSignal(unit.getOrderSignal() & ~Unit.ORDER_SIGNAL_ANIMATION_DONE);
            this.game.getIscriptExecutor().runSpriteAnimation(unit.requireSprite(),
                                                              unit,
                                                              IscriptExecutor.ANIMATION_SPECIAL_STATE_2,
                                                              1,
                                                              instructionLimit);
            this.game.getOrders().issueUntargeted(unit, ScrOrderIds.ZERG_BIRTH, instructionLimit);
        } else {
            if (baseType == ScrUnitIds.TERRAN_NUCLEAR_MISSILE) {
                UnitNuclear.cancel(units, unit);
            }

            this.game.getUnitLifecycle().kill(unit, instructionLimit);
        }

        return true;
    }

    /**
     * Cancels an incomplete Zerg building.
     * <p>
     * A building morph reverts to its completed previous building with its hit points retained. A Drone-built building refunds {@code 3/4}, restores
     * the Drone with its hit points before construction, and removes the building's creep provider; a canceled Extractor instead creates a new Drone
     * and dies.
     *
     * @param building         the incomplete Zerg building
     * @param instructionLimit the order, morph, creation, completion, animation, sprite and hit point budget
     * @throws IllegalStateException         if the creation context or the creep storage is missing, or the building has no sprite
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit accounting or the unit pool is not initialized
     */
    private void cancelZergBuilding(@NotNull Unit building, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();
        Player player = this.game.getPlayerTable().requirePlayer(building.getOwnerIndex());

        if (context == null) {
            throw Exceptions.newIllegalStateException("Zerg building cancellation requires its creation context.");
        }

        UnitAccounting accounting = this.game.getPlayerTable().requireUnitAccounting();
        int queuedType = UnitQueries.getQueuedType(building);
        boolean buildingMorph = UnitQueries.isBuildingMorph(units, building);
        Sprite sprite = building.requireSprite();
        int positionX = (short) sprite.getX();
        int positionY = (short) sprite.getY();

        if (buildingMorph) {
            UnitLifecycle.clearCurrentQueueSlot(building);
            player.setMinerals(player.getMinerals() + units.getMineralCost(queuedType) * REFUND_QUARTERS / QUARTER_DIVISOR);
            player.setGas(player.getGas() + units.getGasCost(queuedType) * REFUND_QUARTERS / QUARTER_DIVISOR);
            building.setStatus(building.getStatus() | UnitStatusFlags.COMPLETED);
            accounting.updateCompleted(building, units, -1, false, this.game.getPlayerTable().getProductionStatistics());
            building.setStatus(building.getStatus() & ~UnitStatusFlags.COMPLETED);

            int hitPoints = building.getHitPoints();

            this.game.getUnitCompletion().initializeBeforePlacement(context, building, instructionLimit);
            this.game.getUnitCompletion().completeAfterPlacement(context, building, player.getType(), instructionLimit);
            this.game.getUnitHealth().setHitPoints(building, hitPoints, instructionLimit);
            this.game.getIscriptExecutor().runSpriteAnimation(building.requireSprite(),
                                                              building,
                                                              IscriptExecutor.ANIMATION_ALMOST_BUILT,
                                                              1,
                                                              instructionLimit);
            this.game.getInterfaceLink().getSoundEvents().add(SoundIds.ZERG_BUILDING_PLACEMENT, positionX, positionY, building.getUnitId());

            return;
        }

        int unitType = building.getUnitId();

        player.setMinerals(player.getMinerals() + units.getMineralCost(unitType) * REFUND_QUARTERS / QUARTER_DIVISOR);
        player.setGas(player.getGas() + units.getGasCost(unitType) * REFUND_QUARTERS / QUARTER_DIVISOR);
        accounting.revokeUnitProduction(building.getOwnerIndex(), units.getBuildScore(unitType));

        int previousHitPoints = building.getPreviousVisibleHitPoints() << HIT_POINT_FRACTION_BITS;

        if (unitType == ScrUnitIds.ZERG_EXTRACTOR) {
            Unit drone = this.game.getUnitStorageConstructor().create(context,
                                                                      accounting,
                                                                      ScrUnitIds.ZERG_DRONE,
                                                                      positionX, positionY,
                                                                      building.getOwnerIndex(),
                                                                      this.game.getUnitSystem().requireUnitPool().getCreationTag(building.getIndex()),
                                                                      instructionLimit);

            if (drone == null) {
                // The owner sees the pending creation message and the building is killed.
                this.game.getUnitStorageConstructor().notifyCreationFailure(building.getOwnerIndex());
                this.game.getUnitLifecycle().kill(building, instructionLimit);

                return;
            }

            this.game.getUnitCompletion().initializeBeforePlacement(context, drone, instructionLimit);
            this.game.getUnitCompletion().completeAfterPlacement(context,
                                                                 drone,
                                                                 this.game.getPlayerTable().requirePlayer(drone.getOwnerIndex()).getType(),
                                                                 instructionLimit);
            this.game.getUnitHealth().setHitPoints(drone, previousHitPoints, instructionLimit);
            this.game.getUnitLifecycle().kill(building, instructionLimit);

            return;
        }

        SpriteEffect spawn = this.game.getIscriptExecutor().createSpriteEffect(SpriteIds.ZERG_BUILDING_SPAWN_SMALL,
                                                                               positionX, positionY,
                                                                               0,
                                                                               instructionLimit);

        if (spawn != null) {
            Sprite spawnSprite = spawn.getSprite();

            spawnSprite.setElevationLevel(sprite.getElevationLevel() + 1);

            SpriteVisibility.updatePositionTile(spawnSprite, this.game.getMap(), this.game.getLocalVision().getComparisonMask());
        }

        this.game.getUnitMorphing().morph(building, ScrUnitIds.ZERG_DRONE, instructionLimit);
        this.game.getComputerUnits().notifyBuildingTransformed(building, instructionLimit);
        this.game.getUnitCompletion().initializeBeforePlacement(context, building, instructionLimit);
        this.game.getUnitCompletion().completeAfterPlacement(context, building, player.getType(), instructionLimit);

        SpriteImage shadow = building.requireSprite().getImages().getLast();

        if (shadow.getModifier() == SpriteImage.MODIFIER_SHADOW) {
            shadow.setOffsetY(DRONE_SHADOW_OFFSET);
        }

        this.game.getOrders().issueUntargeted(building, ScrOrderIds.RESET_COLLISION, instructionLimit);
        this.game.getOrders().queueReplacingSameOrders(building, units.getReturnToIdleOrder(ScrUnitIds.ZERG_DRONE), null, 0, 0, ScrUnitIds.NONE);
        this.game.getUnitHealth().setHitPoints(building, previousHitPoints, instructionLimit);

        CreepField creep = this.game.getCreepField();

        if (creep == null) {
            throw Exceptions.newIllegalStateException("Zerg building cancellation requires creep storage.");
        }

        creep.removeProvider(this.game, unitType, positionX, positionY, false);
        this.game.getInterfaceLink().getSoundEvents().add(SoundIds.ZERG_BUILDING_PLACEMENT, positionX, positionY, ScrUnitIds.ZERG_DRONE);
    }


    /**
     * Reserves a building queue entry without charging its construction cost. After the cost test and the construction order, the builder's build
     * queue is reset ({@link UnitLifecycle#resetBuildQueue}), which cancels or refunds its entries, and the building type is pushed into the queue
     * ({@link UnitProduction#pushBuildQueue}). A Terran building placing an add-on records the add-on type instead of a queue entry.
     *
     * @param builder          the constructing unit
     * @param orderId          the construction order
     * @param unitType         the building type
     * @param positionX        the building center X
     * @param positionY        the building center Y
     * @param instructionLimit the order issue and build queue reset budget
     * @return {@code true} if the builder is not a Drone that is building or landing uninterruptibly and the cost and supply test passes,
     * {@code false} otherwise
     * @throws UnsupportedOperationException if the order is not a construction order
     * @throws NullPointerException          if the world was built without game data
     */
    public boolean place(@NotNull Unit builder, int orderId, int unitType, int positionX, int positionY, int instructionLimit) {
        if (orderId != ScrOrderIds.DRONE_START_BUILD && orderId != ScrOrderIds.PLACE_BUILDING &&
            orderId != ScrOrderIds.PLACE_PROTOSS_BUILDING && orderId != ScrOrderIds.PLACE_ADDON) {
            throw Exceptions.newUnsupportedOperationException("Construction reservation order is not represented: {0}", orderId);
        }

        if (this.game.getBaseType(builder) == ScrUnitIds.ZERG_DRONE &&
            (builder.getPrimaryOrder() == ScrOrderIds.DRONE_BUILD ||
             builder.getPrimaryOrder() == ScrOrderIds.DRONE_LAND &&
             (builder.getStatus() & (UnitStatusFlags.ORDER_NOT_INTERRUPTIBLE | UnitStatusFlags.ISCRIPT_NO_BREAK)) != 0)) {
            return false;
        }

        // The build command's cost and supply test shows its error.
        if (!this.game.getCostTests().testUnit(this.game, builder.getOwnerIndex(), unitType, true, true)) {
            return false;
        }

        this.game.getOrders().issue(builder, orderId, null, positionX, positionY, ScrUnitIds.NONE, false, instructionLimit);

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        // A building constructing another building records the add-on type instead of a production queue entry.
        boolean addon = (units.getFlags(builder.getUnitId()) & UnitTypeFlags.BUILDING) != 0 &&
                        (units.getFlags(unitType) & UnitTypeFlags.BUILDING) != 0;

        this.game.getUnitLifecycle().resetBuildQueue(builder, instructionLimit);

        if (addon) {
            builder.setAddonBuildType(unitType);
        } else {
            this.game.getUnitProduction().pushBuildQueue(builder, unitType);
        }

        return true;
    }

    /**
     * Advances Probe approach, braking and building creation before the primary order timer.
     *
     * @param builder          the Probe
     * @param instructionLimit the order, creation, ownership, morph, image and animation budget
     * @throws IllegalStateException         if the unit creation context is missing or the builder or the building has no sprite
     * @throws UnsupportedOperationException if the unit accounting, the unit pool or the image pool is not initialized
     */
    public void updateProtoss(@NotNull Unit builder, int instructionLimit) {
        // A builder with a computer record ignores unit collisions.
        if (builder.getComputerRecord() != null) {
            builder.setStatus(builder.getStatus() | UnitStatusFlags.GATHERING);
        }

        int phase = builder.getPrimaryOrderState();
        int positionX = (short) builder.getOrderTargetX();
        int positionY = (short) builder.getOrderTargetY();

        if (phase == PROBE_STATE_START) {
            this.game.getEngineGame().getMovement().setDestination(builder, positionX, positionY);
            builder.setDirectionTargetX(positionX);
            builder.setDirectionTargetY(positionY);
            builder.setPrimaryOrderState(PROBE_STATE_APPROACHING);

            return;
        }

        if (phase == PROBE_STATE_WARPING) {
            Unit building = builder.getOrderTargetUnit();

            if (building != null && building.getUnitId() == ScrUnitIds.PROTOSS_ASSIMILATOR && builder.getFirstOrder() == null) {
                this.game.getOrders().issue(builder, ScrOrderIds.WAIT_FOR_GAS, building, 0, 0, ScrUnitIds.NONE, false, instructionLimit);
            } else {
                this.game.getOrders().finishOrder(builder, instructionLimit);
            }

            this.game.getIscriptExecutor().runSpriteAnimation(builder.requireSprite(),
                                                              builder,
                                                              IscriptExecutor.ANIMATION_WALKING_TO_IDLE,
                                                              1,
                                                              instructionLimit);

            return;
        }

        // An emptied queue slot (228) is not checked: the walk goes on, and the site builds a unit of that type.
        int unitType = UnitQueries.getQueuedType(builder);

        if (phase == PROBE_STATE_BRAKING && (builder.getMovementFlags() & MovementFlags.ACCELERATING) != 0) {
            return;
        }

        int distance = RegionPathSearch.calculateDistance(0, (positionX << FIXED_POINT_FRACTION_BITS) - builder.getFixedPositionX(),
                                                          0, (positionY << FIXED_POINT_FRACTION_BITS) - builder.getFixedPositionY()) >>
                       FIXED_POINT_FRACTION_BITS;

        if (distance > (unitType == ScrUnitIds.PROTOSS_ASSIMILATOR ? ASSIMILATOR_PLACEMENT_REACH : PLACEMENT_REACH)) {
            if (this.game.getEngineGame().getMovement().isAtDestination(builder)) {
                this.game.getEngineGame().getMovement().setDestination(builder, positionX, positionY);
                builder.setDirectionTargetX(positionX);
                builder.setDirectionTargetY(positionY);
            }

            return;
        }

        if (phase == PROBE_STATE_APPROACHING) {
            this.game.getUnitPointMovement().stopAndFaceDestination(builder);
            builder.setPrimaryOrderState(PROBE_STATE_BRAKING);

            return;
        }

        // The cost and supply test shows its error; a blocked site shows "You can't build there.".
        if (!this.game.getCostTests().testUnit(this.game, builder.getOwnerIndex(), unitType, true, true)) {
            this.game.getOrders().finishOrder(builder, instructionLimit);

            return;
        }

        if (!this.game.getUnitPlacement().canPlaceBuilding(builder, builder.getOwnerIndex(), unitType, positionX, positionY, true, false)) {
            this.game.getInterfaceLink().getCommandErrorEvents().raiseBlockedBuildLocation(this.game, builder);
            this.game.getOrders().finishOrder(builder, instructionLimit);

            return;
        }

        UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();

        if (context == null) {
            throw Exceptions.newIllegalStateException("Construction requires initialized creation context.");
        }

        this.game.getCostTests().spend(this.game, builder.getOwnerIndex());

        Unit building = unitType == ScrUnitIds.PROTOSS_ASSIMILATOR
                        ? this.buildRefinery(builder, unitType, instructionLimit)
                        : this.game.getUnitStorageConstructor().create(context,
                                                                       this.game.getPlayerTable().requireUnitAccounting(),
                                                                       unitType,
                                                                       positionX, positionY,
                                                                       builder.getOwnerIndex(),
                                                                       this.game.getUnitSystem().requireUnitPool().getCreationTag(builder.getIndex()),
                                                                       instructionLimit);

        UnitLifecycle.clearCurrentQueueSlot(builder);

        if (building == null) {
            // The builder's owner sees the pending creation message.
            this.game.getUnitStorageConstructor().notifyCreationFailure(builder.getOwnerIndex());
            this.game.getOrders().finishOrder(builder, instructionLimit);

            return;
        }

        Sprite sprite = building.requireSprite();

        for (SpriteImage image : sprite.getImages()) {
            this.game.getSpriteSystem().requireImagePool().recycle(image);
        }

        sprite.clearImages();
        this.game.getIscriptExecutor().createImage(sprite, null, ImageIds.WARP_ANCHOR, 0, 0, 0, building, instructionLimit);
        this.game.getComputerUnits().notifyCreated(builder, building, instructionLimit);

        if (unitType == ScrUnitIds.PROTOSS_ASSIMILATOR) {
            this.game.getIscriptExecutor().createImageBelowMain(building, ImageIds.VESPENE_GEYSER, instructionLimit);
        }

        this.game.getOrders().issueUntargeted(building, ScrOrderIds.INCOMPLETE_WARPING, instructionLimit);

        builder.setOrderTargetUnit(building);
        builder.setPrimaryOrderState(PROBE_STATE_WARPING);

        Sprite builderSprite = builder.requireSprite();

        this.game.getInterfaceLink().getSoundEvents().add(SoundIds.PROTOSS_BUILDING_WARP_START,
                                                          builderSprite.getX(), builderSprite.getY(),
                                                          builder.getUnitId());
    }

    /**
     * Converts the Drone build request into its landing order followed by the queued build order.
     *
     * @param drone            the building Drone
     * @param instructionLimit the order activation animation budget
     */
    public void startDroneBuild(@NotNull Unit drone, int instructionLimit) {
        int positionX = drone.getOrderTargetX();
        int positionY = drone.getOrderTargetY();

        this.game.getOrders().issue(drone, ScrOrderIds.DRONE_LAND, null, positionX, positionY, ScrUnitIds.NONE, false, instructionLimit);
        this.game.getOrders().queueBack(drone, ScrOrderIds.DRONE_BUILD, null, positionX, positionY, ScrUnitIds.NONE);
    }

    /**
     * Moves the Drone above its building site and lowers it onto the footprint center. Extractor sites switch the Drone to air-level approach over
     * the geyser; failed placement discards the queued build order and returns the Drone to idle.
     *
     * @param drone            the landing Drone
     * @param instructionLimit the order animation budget
     * @throws IllegalStateException if the Drone's sprite is missing or the order state is outside the order's states
     * @throws NullPointerException  if the world was built without game data
     */
    public void landDrone(@NotNull Unit drone, int instructionLimit) {
        // A builder with a computer record ignores unit collisions.
        if (drone.getComputerRecord() != null) {
            drone.setStatus(drone.getStatus() | UnitStatusFlags.GATHERING);
        }

        int buildType = UnitQueries.getQueuedType(drone);
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int positionX = (short) drone.getOrderTargetX();
        int positionY = (short) drone.getOrderTargetY();
        Sprite sprite = drone.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newIllegalStateException("Drone landing requires its sprite.");
        }

        int phase = drone.getPrimaryOrderState();

        if (phase == LAND_STATE_START) {
            this.game.getEngineGame().getMovement().setDestination(drone, positionX, positionY - LANDING_APPROACH_OFFSET);
            drone.setDirectionTargetX(positionX);
            drone.setDirectionTargetY(positionY - LANDING_APPROACH_OFFSET);
            drone.setPrimaryOrderState(LAND_STATE_APPROACHING);
        } else if (phase == LAND_STATE_APPROACHING) {
            if (!this.game.getEngineGame().getMovement().isAtDestination(drone)) {
                return;
            }

            boolean success = false;

            if ((drone.getStatus() & UnitStatusFlags.IMMOVABLE) == 0) {
                success = this.game.getUnitPlacement().canPlaceBuilding(drone, drone.getOwnerIndex(), buildType, positionX, positionY, true, true);

                if (success && buildType == ScrUnitIds.ZERG_EXTRACTOR) {
                    Unit geyser = this.findBuildingAtCenter(positionX, positionY, ScrUnitIds.RESOURCE_VESPENE_GEYSER);

                    if (geyser != null && this.game.getUnitTargetMovement().isTargetInRange(drone, geyser, GEYSER_LANDING_RANGE)) {
                        drone.setStatus(drone.getStatus() & ~UnitStatusFlags.GROUND_UNIT);
                        sprite.setElevationLevel(units.getElevationLevel(drone.getUnitId()) + 1);
                        this.game.getOrders().queueReplacingSameOrders(drone, ScrOrderIds.RESET_COLLISION, null, 0, 0, ScrUnitIds.NONE);
                        this.game.getOrders().queueReplacingSameOrders(drone,
                                                                       units.getReturnToIdleOrder(drone.getUnitId()),
                                                                       null,
                                                                       0, 0,
                                                                       ScrUnitIds.NONE);
                        this.game.getEngineGame().getMovement().setDestination(drone, positionX, positionY);
                        drone.setDirectionTargetX(positionX);
                        drone.setDirectionTargetY(positionY);
                        drone.setStatus(drone.getStatus() | UnitStatusFlags.ORDER_NOT_INTERRUPTIBLE);
                        drone.setPrimaryOrderState(LAND_STATE_LANDING);

                        return;
                    }
                }
            }

            if (success) {
                int distance = RegionPathSearch.calculateDistance(0, (positionX << FIXED_POINT_FRACTION_BITS) - drone.getFixedPositionX(),
                                                                  0, (positionY << FIXED_POINT_FRACTION_BITS) - drone.getFixedPositionY()) >>
                               FIXED_POINT_FRACTION_BITS;

                if (distance <= LANDING_REACH) {
                    SpriteImage shadow = sprite.getImages().getLast();

                    if (shadow.getModifier() == SpriteImage.MODIFIER_SHADOW) {
                        shadow.freezeMapY();
                    }

                    this.game.getEngineGame().getMovement().setDestination(drone, positionX, positionY);
                    drone.setDirectionTargetX(positionX);
                    drone.setDirectionTargetY(positionY);
                    drone.setStatus(drone.getStatus() | UnitStatusFlags.ORDER_NOT_INTERRUPTIBLE);
                    drone.setPrimaryOrderState(LAND_STATE_LANDING);
                } else {
                    success = false;
                }
            }

            if (!success) {
                // The landing-interrupted notification.
                this.game.getInterfaceLink().getNotificationEvents().raise(NotificationEvents.Kind.LANDING_INTERRUPTED, drone);
                this.game.getOrders().removeQueued(drone, ScrOrderIds.DRONE_BUILD);
                this.game.getOrders().issueUntargeted(drone, units.getReturnToIdleOrder(drone.getUnitId()), instructionLimit);
            }
        } else if (phase == LAND_STATE_LANDING) {
            if (!this.game.getEngineGame().getMovement().isAtDestination(drone)) {
                return;
            }

            SpriteImage shadow = sprite.getImages().getLast();

            if (shadow.getModifier() == SpriteImage.MODIFIER_SHADOW) {
                shadow.setOffsetY(0);
                shadow.unfreezeMapY();
            }

            if ((drone.getStatus() & UnitStatusFlags.IMMOVABLE) == 0) {
                OrderPool.Order firstOrder = drone.getFirstOrder();

                if (firstOrder != null && firstOrder.getOrderId() == ScrOrderIds.DRONE_BUILD) {
                    this.game.getUnitPositioning().move(drone, positionX, positionY);
                } else {
                    if (shadow.getModifier() == SpriteImage.MODIFIER_SHADOW) {
                        shadow.setOffsetY(DRONE_SHADOW_OFFSET);
                    }

                    this.game.getOrders().queueFront(drone, ScrOrderIds.RESET_COLLISION, null, 0, 0, ScrUnitIds.NONE);
                }
            } else {
                // The landing-interrupted notification.
                this.game.getInterfaceLink().getNotificationEvents().raise(NotificationEvents.Kind.LANDING_INTERRUPTED, drone);
                this.game.getOrders().removeQueued(drone, ScrOrderIds.DRONE_BUILD);

                if (shadow.getModifier() == SpriteImage.MODIFIER_SHADOW) {
                    shadow.setOffsetY(DRONE_SHADOW_OFFSET);
                }

                this.game.getOrders().queueFront(drone, ScrOrderIds.RESET_COLLISION, null, 0, 0, ScrUnitIds.NONE);
            }

            this.game.getOrders().completeOrder(drone, instructionLimit);
        } else {
            throw Exceptions.newIllegalStateException("Drone landing state is outside its state machine: {0}", phase);
        }
    }

    /**
     * Spends the building cost and transforms the landed Drone into the incomplete building. An Extractor converts the geyser and removes the Drone;
     * every other building morphs the Drone itself. A Drone off its target, or a placement, supply or resource failure, restores ground collision.
     *
     * @param drone            the landed Drone
     * @param instructionLimit the order, ownership, morph, image and construction image budget
     * @throws IllegalStateException         if the Drone's sprite is missing
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit accounting is not initialized
     */
    public void buildDrone(@NotNull Unit drone, int instructionLimit) {
        int buildType = UnitQueries.getQueuedType(drone);
        Sprite sprite = drone.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newIllegalStateException("Drone construction requires its sprite.");
        }

        // A Drone off its target stops without a message.
        if (sprite.getX() != drone.getOrderTargetX() || sprite.getY() != drone.getOrderTargetY()) {
            this.finishDroneBuildFailure(drone, instructionLimit);

            return;
        }

        // A blocked site shows "You can't build there."; the cost and supply test shows its own error.
        if (!this.game.getUnitPlacement().canPlaceBuilding(drone,
                                                           drone.getOwnerIndex(),
                                                           buildType,
                                                           (short) sprite.getX(), (short) sprite.getY(),
                                                           true,
                                                           true)) {
            this.game.getInterfaceLink().getCommandErrorEvents().raiseBlockedBuildLocation(this.game, drone);
            this.finishDroneBuildFailure(drone, instructionLimit);

            return;
        }

        if (!this.game.getCostTests().testUnit(this.game, drone.getOwnerIndex(), buildType, true, true)) {
            this.finishDroneBuildFailure(drone, instructionLimit);

            return;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        this.game.getCostTests().spend(this.game, drone.getOwnerIndex());

        // A carried powerup is put down.
        if (drone.getCarriedPowerup() != null) {
            this.game.getUnitPowerups().drop(drone, instructionLimit);
        }

        if (buildType == ScrUnitIds.ZERG_EXTRACTOR) {
            Unit extractor = this.buildRefinery(drone, buildType, instructionLimit);

            if (extractor == null) {
                this.finishDroneBuildFailure(drone, instructionLimit);

                return;
            }

            this.game.getComputerUnits().notifyCreated(drone, extractor, instructionLimit);
            drone.setUserActionFlags(drone.getUserActionFlags() | Unit.USER_ACTION_HIDE_ON_DEATH);
            this.game.getUnitLifecycle().kill(drone, instructionLimit);
            this.startZergConstruction(extractor, instructionLimit);
            this.game.getIscriptExecutor().createImageBelowMain(extractor, ImageIds.VESPENE_GEYSER2, instructionLimit);
        } else {
            UnitAccounting accounting = this.game.getPlayerTable().requireUnitAccounting();

            accounting.updateCompleted(drone, units, -1, false, this.game.getPlayerTable().getProductionStatistics());
            drone.setStatus(drone.getStatus() & ~UnitStatusFlags.COMPLETED);
            this.game.getUnitMorphing().morph(drone, buildType, instructionLimit);
            this.game.getComputerUnits().notifyWorkerTransformed(drone, instructionLimit);
            this.startZergConstruction(drone, instructionLimit);
        }
    }

    private void finishDroneBuildFailure(@NotNull Unit drone, int instructionLimit) {
        SpriteImage shadow = drone.requireSprite().getImages().getLast();

        if (shadow.getModifier() == SpriteImage.MODIFIER_SHADOW) {
            shadow.setOffsetY(DRONE_SHADOW_OFFSET);
        }

        this.game.getOrders().queueFront(drone, ScrOrderIds.RESET_COLLISION, null, 0, 0, ScrUnitIds.NONE);
        this.game.getOrders().completeOrder(drone, instructionLimit);
    }

    /**
     * Converts the geyser at the builder's target into the builder's refinery.
     *
     * @param builder          the constructing worker
     * @param refineryType     the Extractor, Refinery or Assimilator type
     * @param instructionLimit the ownership, morph and image budget
     * @return the converted refinery, or {@code null} if no geyser occupies the target tile
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit accounting is not initialized
     */
    @Nullable Unit buildRefinery(@NotNull Unit builder, int refineryType, int instructionLimit) {
        Unit target = builder.getOrderTargetUnit();

        if (target == null || target.getUnitId() != ScrUnitIds.RESOURCE_VESPENE_GEYSER) {
            target = this.findBuildingAtCenter((short) builder.getOrderTargetX(), (short) builder.getOrderTargetY(),
                                               ScrUnitIds.RESOURCE_VESPENE_GEYSER);

            if (target == null) {
                return null;
            }
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        this.game.getPlayerTable().requireUnitAccounting().updateCompleted(target,
                                                                           units,
                                                                           -1,
                                                                           true,
                                                                           this.game.getPlayerTable().getProductionStatistics());
        target.setStatus(target.getStatus() & ~UnitStatusFlags.COMPLETED);
        this.game.getUnitOwnership().changeOwner(target, builder.getOwnerIndex(), false, instructionLimit);
        this.game.getUnitMorphing().morph(target, refineryType, instructionLimit);
        // The geyser under construction takes the cancel-construction button set.
        UnitMorphing.writeButtonSet(units, target, ButtonSetIds.CANCEL_CONSTRUCTION);
        this.game.getIscriptExecutor().createImageBelowMain(target, ImageIds.VESPENE_GEYSER2, instructionLimit);
        target.setHitPoints(units.getHitPoints(refineryType) / UnitInitializer.CONSTRUCTION_START_DIVISOR);

        return target;
    }

    private void startZergConstruction(@NotNull Unit building, int instructionLimit) {
        CreepField creep = this.game.getCreepField();
        Sprite sprite = building.getSpriteOrNull();

        if (creep == null || sprite == null) {
            throw Exceptions.newIllegalStateException("Zerg construction requires creep storage and the building sprite.");
        }

        creep.addProvider(this.game,
                          building.getUnitId(),
                          (short) sprite.getX(), (short) sprite.getY(),
                          (building.getStatus() & UnitStatusFlags.COMPLETED) != 0);

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        // The rally-producing building types take the cancel set with a rally button.
        UnitMorphing.writeButtonSet(units,
                                    building,
                                    units.isRallyProducer(building.getUnitId())
                                    ? ButtonSetIds.CANCEL_BUILDING_WITH_RALLY
                                    : ButtonSetIds.CANCEL_BUILDING);
        this.game.getIscriptExecutor().initializeConstructionImage(building, IscriptExecutor.CONSTRUCTION_IMAGE_MODE, instructionLimit);
        building.setHitPoints(units.getHitPoints(building.getUnitId()) / UnitInitializer.CONSTRUCTION_START_DIVISOR);
        this.game.getOrders().issueUntargeted(building, ScrOrderIds.INCOMPLETE_MORPHING, instructionLimit);
        UnitLifecycle.clearCurrentQueueSlot(building);
    }

    /**
     * Charges a queued Zerg building morph and starts its incomplete state. The building stays counted as completed until the morph finishes.
     * Insufficient supply or resources append the idle order without completing the current order.
     *
     * @param building         the morphing building
     * @param instructionLimit the construction image, animation and order budget
     * @throws IllegalStateException if the building has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public void startZergBuildingMorph(@NotNull Unit building, int instructionLimit) {
        int buildType = UnitQueries.getQueuedType(building);
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        // The cost and supply test shows its error.
        if (!this.game.getCostTests().testUnit(this.game, building.getOwnerIndex(), buildType, true, true)) {
            this.game.getOrders().queueReplacingSameOrders(building, units.getReturnToIdleOrder(building.getUnitId()), null, 0, 0, ScrUnitIds.NONE);

            return;
        }

        this.game.getCostTests().spend(this.game, building.getOwnerIndex());
        building.setStatus(building.getStatus() & ~UnitStatusFlags.COMPLETED);
        // A morphing Zerg building takes the cancel-morph button set.
        UnitMorphing.writeButtonSet(units, building, ButtonSetIds.CANCEL_MORPH);
        this.game.getIscriptExecutor().initializeConstructionImage(building, IscriptExecutor.CONSTRUCTION_IMAGE_MODE, instructionLimit);
        building.setRemainingBuildTime(units.getBuildTime(buildType));
        this.game.getIscriptExecutor().runSpriteAnimation(building.requireSprite(),
                                                          building,
                                                          IscriptExecutor.ANIMATION_SPECIAL_STATE_1,
                                                          1,
                                                          instructionLimit);
        this.game.getOrders().issueUntargeted(building, ScrOrderIds.INCOMPLETE_MORPHING, instructionLimit);
    }

    /**
     * Advances an incomplete Zerg building or building morph through the {@link IscriptExecutor#ANIMATION_SPECIAL_STATE_1} and
     * {@link IscriptExecutor#ANIMATION_SPECIAL_STATE_2} animations at {@code 3/4} and {@code 1/2} of the remaining build time,
     * {@link IscriptExecutor#ANIMATION_ALMOST_BUILT} at completion, and completion when the script signals.
     * <p>
     * A building morph into an added unit type that is no building completes as a unit: the building's creep and Larvae are left behind.
     *
     * @param building         the incomplete building
     * @param instructionLimit the animation, morph, hit point and completion budget
     * @throws IllegalStateException         if the building's sprite, the creation context, the owner or the creep storage is missing, or the order
     *                                       state is outside the order's states
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit accounting is not initialized
     */
    public void updateZergMorphing(@NotNull Unit building, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int buildType = building.getUnitId();
        int queuedType = UnitQueries.getQueuedType(building);

        if (units.isBuildingMorphType(queuedType)) {
            buildType = queuedType;
        }

        boolean buildingMorph = units.isBuildingMorphType(buildType);
        // A building morph whose result is an added type that is no building.
        boolean unitMorph = buildingMorph && units.isAddedType(buildType) && (units.getFlags(buildType) & UnitTypeFlags.BUILDING) == 0;
        Sprite sprite = building.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newIllegalStateException("Incomplete morphing requires its sprite.");
        }

        int buildTime = units.getBuildTime(buildType);
        int phase = building.getPrimaryOrderState();

        switch (phase) {
            case MORPH_STATE_FIRST_STAGE -> {
                if (building.getRemainingBuildTime() < buildTime * FIRST_STAGE_QUARTERS / QUARTER_DIVISOR) {
                    building.setPrimaryOrderState(MORPH_STATE_FIRST_ANIMATION);
                }
            }
            case MORPH_STATE_FIRST_ANIMATION -> {
                if (building.getUnitId() == ScrUnitIds.ZERG_EXTRACTOR) {
                    this.game.getIscriptExecutor().destroyFirstImage(sprite, ImageIds.VESPENE_GEYSER2);
                }

                this.game.getIscriptExecutor().runSpriteAnimation(sprite, building, IscriptExecutor.ANIMATION_SPECIAL_STATE_1, 1, instructionLimit);
                building.setPrimaryOrderState(MORPH_STATE_SECOND_STAGE);
            }
            case MORPH_STATE_SECOND_STAGE -> {
                if (building.getRemainingBuildTime() < buildTime / SECOND_STAGE_DIVISOR) {
                    building.setPrimaryOrderState(MORPH_STATE_SECOND_ANIMATION);
                }
            }
            case MORPH_STATE_SECOND_ANIMATION -> {
                this.game.getIscriptExecutor().runSpriteAnimation(sprite, building, IscriptExecutor.ANIMATION_SPECIAL_STATE_2, 1, instructionLimit);
                building.setPrimaryOrderState(MORPH_STATE_THIRD_STAGE);
            }
            case MORPH_STATE_THIRD_STAGE -> {
                if (building.getRemainingBuildTime() == 0) {
                    building.setPrimaryOrderState(MORPH_STATE_ALMOST_BUILT);
                }
            }
            case MORPH_STATE_ALMOST_BUILT -> {
                this.game.getIscriptExecutor().runSpriteAnimation(sprite, building, IscriptExecutor.ANIMATION_ALMOST_BUILT, 1, instructionLimit);
                building.setPrimaryOrderState(MORPH_STATE_FINISHING);
                // The building's work-done notification.
                this.game.getInterfaceLink().getNotificationEvents().raise(NotificationEvents.Kind.WORK_DONE, building);
            }
            case MORPH_STATE_FINISHING -> {
                if ((building.getOrderSignal() & Unit.ORDER_SIGNAL_ANIMATION_DONE) == 0) {
                    return;
                }

                building.setOrderSignal(building.getOrderSignal() & ~Unit.ORDER_SIGNAL_ANIMATION_DONE);

                int buildingX = (short) sprite.getX();
                int buildingY = (short) sprite.getY();

                if (buildingMorph) {
                    building.setStatus(building.getStatus() | UnitStatusFlags.COMPLETED);
                    this.game.getPlayerTable().requireUnitAccounting().updateCompleted(building,
                                                                                       units,
                                                                                       -1,
                                                                                       false,
                                                                                       this.game.getPlayerTable().getProductionStatistics());
                    building.setStatus(building.getStatus() & ~UnitStatusFlags.COMPLETED);

                    int hitPoints = building.getHitPoints();

                    this.game.getUnitMorphing().morph(building, buildType, instructionLimit);

                    hitPoints += units.getHitPoints(buildType) - units.getHitPoints(building.getPreviousUnitType());

                    this.game.getUnitHealth().setHitPoints(building, Math.max(hitPoints, MINIMUM_MORPH_HIT_POINTS), instructionLimit);
                    building.setRemainingBuildTime(0);

                    // A unit leaves the queue entry that the reinitialization clears only for a building, and its computer building record.
                    if (unitMorph) {
                        UnitLifecycle.clearCurrentQueueSlot(building);
                        this.game.getComputerUnits().notifyBuildingTransformed(building, instructionLimit);
                    }
                }

                UnitCreationContext context = this.game.getUnitSystem().getUnitCreationContext();
                Player player = this.game.getPlayerTable().getPlayer(building.getOwnerIndex());
                CreepField creep = this.game.getCreepField();

                if (context == null || player == null || creep == null) {
                    throw Exceptions.newIllegalStateException("Incomplete morph completion requires its creation context, owner and creep storage.");
                }

                this.game.getUnitCompletion().initializeBeforePlacement(context, building, instructionLimit);
                this.game.getUnitCompletion().completeAfterPlacement(context, building, player.getType(), instructionLimit);

                if (unitMorph) {
                    this.completeUnitMorph(building, buildingX, buildingY, creep, instructionLimit);

                    return;
                }

                sprite = building.requireSprite();

                creep.addProvider(this.game, building.getUnitId(), (short) sprite.getX(), (short) sprite.getY(), true);
                this.game.getIscriptExecutor().runSpriteAnimation(sprite, building, IscriptExecutor.ANIMATION_ALMOST_BUILT, 1, instructionLimit);
                creep.spreadUnderBuilding(this.game, buildType, (short) sprite.getX(), (short) sprite.getY());

                return;
            }
            default -> throw Exceptions.newIllegalStateException("Incomplete morphing state is outside its state machine: {0}", phase);
        }

        if (building.getRemainingBuildTime() != 0) {
            this.game.getUnitBuildTime().advance(building);
        }

        if (!buildingMorph) {
            this.game.getUnitHealth().setHitPoints(building,
                                                   building.getHitPoints() + this.game.getUnitBuildTime().getHitPointIncrement(building),
                                                   instructionLimit);
        }
    }

    /**
     * Completes a Zerg building that morphed into a unit: the building's Larvae and Eggs lose their parent, so a Larva dies once the creep under it
     * recedes; the unit stops spawning Larvae and growing creep and the building stops providing creep, so its creep recedes; a ground unit restores
     * its ground collision and returns to its idle order, as a Drone does after a canceled construction; a flying unit (its type flies, so the
     * reinitialization made it a flyer with the air units' elevation) has no ground collision and returns to its idle order at once.
     *
     * @param unit             the unit the building morphed into, whose previous type is the building type
     * @param positionX        the building center X
     * @param positionY        the building center Y
     * @param creep            the creep storage
     * @param instructionLimit the order budget
     * @throws UnsupportedOperationException if the unit pool or the receding creep records are not initialized
     * @throws NullPointerException          if the world was built without game data
     */
    private void completeUnitMorph(@NotNull Unit unit, int positionX, int positionY, @NotNull CreepField creep, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        // Larvae and Eggs are never hidden, so the visible units hold every child of the building.
        for (int index = pool.getFirstCreatedIndex(true); index != 0; index = pool.getNextCreatedIndex(index)) {
            Unit child = pool.getUnit(index);

            if (child.getParentUnit() == unit && units.isLarvaOrEgg(child.getUnitId())) {
                child.setParentUnit(null);
            }
        }

        // The unit stops the building's Larva spawning and creep growth, as the stop-creep-growth order does, and its creep recedes.
        ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.NOTHING);
        creep.removeProvider(this.game, unit.getPreviousUnitType(), positionX, positionY, true);

        if ((unit.getStatus() & UnitStatusFlags.FLYING) != 0) {
            this.game.getOrders().completeOrderToIdle(unit, instructionLimit);

            return;
        }

        this.game.getOrders().issueUntargeted(unit, ScrOrderIds.RESET_COLLISION, instructionLimit);
        this.game.getOrders().queueReplacingSameOrders(unit, units.getReturnToIdleOrder(unit.getUnitId()), null, 0, 0, ScrUnitIds.NONE);
    }

    /**
     * Restores ground collision after a Drone leaves a construction attempt.
     *
     * @param unit             the unit restoring collision
     * @param instructionLimit the order activation budget
     * @throws IllegalStateException         if the unit's sprite or the spatial collision state is missing
     * @throws UnsupportedOperationException if the unit holds a path and the path pool is missing
     * @throws NullPointerException          if the world was built without game data
     */
    public void resetCollision(@NotNull Unit unit, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        Sprite sprite = unit.getSpriteOrNull();
        UnitSpatialIndex spatial = this.game.getUnitSystem().getUnitSpatialIndex();

        if (sprite == null || spatial == null) {
            throw Exceptions.newIllegalStateException("Collision reset requires the sprite and spatial collision state.");
        }

        sprite.setElevationLevel(units.getElevationLevel(unit.getUnitId()));
        unit.setStatus(unit.getStatus() & ~(UnitStatusFlags.NO_COLLIDE | UnitStatusFlags.GATHERING) | UnitStatusFlags.GROUND_UNIT);
        spatial.notifyCollision(unit, units);

        if (unit.getPathIndex() >= 0) {
            PathPool paths = this.game.getPathSystem().getPathPool();

            if (paths == null) {
                throw Exceptions.newUnsupportedOperationException("Collision reset requires the path pool.");
            }

            paths.release(unit.getPathIndex());
            unit.setPathIndex(-1);
        }

        unit.setMovementState(MovementStates.INIT);
        UnitInitializer.updateGroundElevationPathFlag(unit, sprite.getElevationLevel());

        this.game.getOrders().completeOrderToIdle(unit, instructionLimit);
    }

    private @Nullable Unit findBuildingAtCenter(int positionX, int positionY, int unitType) {
        UnitSpatialIndex spatial = this.game.getUnitSystem().getUnitSpatialIndex();

        if (spatial == null) {
            throw Exceptions.newIllegalStateException("The building site lookup requires the unit spatial index.");
        }

        // The point query, then the first unit of the type whose sprite shares the point's tile.
        for (int poolIndex : spatial.findAllAtPoint(positionX, positionY)) {
            Unit candidate = this.game.getUnitSystem().requireUnitPool().getUnit(poolIndex);

            if (candidate.getUnitId() != unitType) {
                continue;
            }

            Sprite candidateSprite = candidate.requireSprite();

            if (candidateSprite.getX() / TerrainTileset.TILE_SIZE == positionX / TerrainTileset.TILE_SIZE &&
                candidateSprite.getY() / TerrainTileset.TILE_SIZE == positionY / TerrainTileset.TILE_SIZE) {
                return candidate;
            }
        }

        return null;
    }
}
