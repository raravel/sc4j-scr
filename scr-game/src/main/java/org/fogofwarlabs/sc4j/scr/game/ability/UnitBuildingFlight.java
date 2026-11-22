package org.fogofwarlabs.sc4j.scr.game.ability;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnKey;
import org.fogofwarlabs.sc4j.engine.entity.column.ColumnReset;
import org.fogofwarlabs.sc4j.engine.entity.column.IntColumn;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrImageTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.SpriteIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepField;
import org.fogofwarlabs.sc4j.scr.game.movement.FlingyMotionUtils;
import org.fogofwarlabs.sc4j.scr.game.movement.UnitSpeed;
import org.fogofwarlabs.sc4j.scr.game.order.OrderPool;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.path.PathPool;
import org.fogofwarlabs.sc4j.scr.game.presentation.NotificationEvents;
import org.fogofwarlabs.sc4j.scr.game.production.UnitProduction;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteEffect;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.fogofwarlabs.sc4j.scr.game.visibility.SpriteVisibility;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;

/**
 * Lifts Terran buildings off and lands them.
 * <p>
 * Lifting off vacates the footprint, abandons the add-on and turns the building into a flying, movable, turnable unit at the air elevation level.
 * Landing reserves the target footprint first, descends at {@link #LIFT_SPEED} ({@code 1} pixel per frame), then restores the grounded building
 * state, kills units that cannot survive under it, and reconnects a neutral add-on at the landed position.
 * <p>
 * The landing byte of the native unit union is registered as a unit pool column ({@link #LANDING_COLUMNS}). The column
 * keeps its value when a slot is reused, as the union field does; unit creation and the unit reset methods set it.
 */
public final class UnitBuildingFlight {
    /**
     * The BYTE that is nonzero while a flying building has reserved its landing footprint ({@link Unit#getLandingByte()}).
     */
    public static final @NotNull ColumnKey<IntColumn> LANDING_COLUMN = ColumnKey.ofInt("landing", 0, ColumnReset.KEEP);
    /**
     * The unit pool columns of the landing state, in registration order.
     */
    public static final @NotNull List<@NotNull ColumnKey<?>> LANDING_COLUMNS = List.of(LANDING_COLUMN);

    /**
     * The shift of the vertical coordinate in a packed overlay offset.
     */
    private static final int OVERLAY_VERTICAL_SHIFT = Short.SIZE;

    /**
     * The height in pixels at which a lifted building flies above its ground position, and its shadow offset.
     */
    private static final int FLIGHT_HEIGHT = 42;
    /**
     * The flight height of a lifted worker type.
     */
    private static final int WORKER_FLIGHT_HEIGHT = 7;
    /**
     * The sprite elevation level of a landed building.
     */
    private static final int LANDED_ELEVATION = 4;
    /**
     * The top speed and speed of a lifting or landing building: {@code 1} pixel per frame ({@code 8} fraction bits).
     */
    private static final int LIFT_SPEED = 256;

    /**
     * The order states of {@link ScrOrderIds#LIFTING_OFF}: turning, facing the waypoint, starting the animation, rising.
     */
    private static final int LIFT_STATE_TURNING = 0;
    private static final int LIFT_STATE_FACING = 1;
    private static final int LIFT_STATE_STARTING = 2;
    private static final int LIFT_STATE_RISING = 3;
    /**
     * The order states of {@link ScrOrderIds#BUILDING_LAND}: started, approaching the site, turning, descending.
     */
    private static final int LAND_STATE_START = 0;
    private static final int LAND_STATE_APPROACHING = 1;
    private static final int LAND_STATE_TURNING = 2;
    private static final int LAND_STATE_DESCENDING = 3;

    /**
     * The overlay positions of the unflipped dust sprites and of the flipped dust sprites.
     */
    private static final int DUST_FIRST_POSITION = 0;
    private static final int DUST_LAST_POSITION = 8;
    private static final int FLIPPED_DUST_FIRST_POSITION = 16;
    private static final int FLIPPED_DUST_LAST_POSITION = 24;
    /**
     * The overlay coordinate of an undefined dust position.
     */
    private static final int UNDEFINED_DUST_OFFSET = 127;

    /**
     * Collects the pool indexes of the units whose boxes overlap a rectangle.
     *
     * @param spatial the unit finder
     * @param left    the left edge
     * @param upper   the upper edge
     * @param right   the right edge
     * @param lower   the lower edge
     * @return the pool indexes in finder order
     */
    private static int @NotNull [] collectCovered(@NotNull UnitSpatialIndex spatial, int left, int upper, int right, int lower) {
        int count = spatial.collectPlacementCandidates(left, upper, right, lower);
        int[] result = new int[count];

        for (int index = 0; index < count; index++) {
            result[index] = spatial.getPlacementCandidate(index).getIndex();
        }

        return result;
    }

    /**
     * @param unit the unit
     * @return {@code true} if the unit's target heading points at its direction target, {@code false} otherwise
     * @throws IllegalStateException if the unit has no sprite
     */
    private static boolean isFacingDirectionTarget(@NotNull Unit unit) {
        Sprite sprite = unit.requireSprite();

        return unit.getTargetHeading() == FlingyMotionUtils.calculateDirection((short) unit.getDirectionTargetX() - sprite.getX(),
                                                                               (short) unit.getDirectionTargetY() - sprite.getY());
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitBuildingFlight} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitBuildingFlight(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Applies the lift-off command to the single selected building.
     *
     * @param owner            the command player
     * @param positionX        the unsigned horizontal command coordinate
     * @param positionY        the unsigned vertical command coordinate
     * @param instructionLimit the order activation budget
     * @return {@code true} if the building accepted the command, {@code false} otherwise
     * @throws UnsupportedOperationException if the selection storage is missing
     * @throws NullPointerException          if the world was built without game data
     */
    public boolean liftOff(int owner, int positionX, int positionY, int instructionLimit) {
        if (positionX < 0 ||
            positionY < 0 ||
            positionX >= this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE ||
            positionY >= this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE) {
            return false;
        }

        UnitSelection selection = this.game.getSelection();

        if (selection == null) {
            throw Exceptions.newUnsupportedOperationException("Lift-off requires initialized selection storage.");
        }

        Unit building = selection.getSelectedUnit(owner, 0);

        if (building == null || selection.getSelectedUnit(owner, 1) != null) {
            return false;
        }

        if (building.isDestroyed()) {
            return false;
        }

        if (UnitProduction.isBusy(building)) {
            return false;
        }

        if (!this.game.getUnitOrderEligibility().canReceiveOrder(building, ScrOrderIds.BUILDING_LIFTOFF, owner)) {
            return false;
        }

        this.game.getOrders().issue(building, ScrOrderIds.BUILDING_LIFTOFF, null, positionX, positionY, ScrUnitIds.NONE, false, instructionLimit);
        this.game.getOrders().queueReplacingSameOrders(
                building,
                this.game.requireGameData().getUnitTypes().getReturnToIdleOrder(building.getUnitId()),
                null,
                positionX, positionY - FLIGHT_HEIGHT,
                ScrUnitIds.NONE
        );

        return true;
    }

    /**
     * Turns a grounded flying-capable building into a flying unit and continues with {@link ScrOrderIds#LIFTING_OFF}.
     *
     * @param building         the building executing {@link ScrOrderIds#BUILDING_LIFTOFF}
     * @param instructionLimit the ownership, order and animation budget
     * @throws UnsupportedOperationException if an Infested Command Center lifts off without the creep field or the unit spatial index is not
     *                                       initialized
     * @throws IllegalStateException         if the building has no sprite
     * @throws NullPointerException          if the world was built without game data
     */
    public void updateLiftoff(@NotNull Unit building, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int type = building.getUnitId();
        int abilities = units.getFlags(type);

        if (!UnitQueries.isGroundedBuilding(building) || (abilities & UnitTypeFlags.FLYING_BUILDING) == 0) {
            this.game.getOrders().completeOrder(building, instructionLimit);

            return;
        }

        if (building.getAddon() != null) {
            this.game.getUnitAddons().abandon(building, instructionLimit);
        }

        if (units.isRallyProducer(type)) {
            building.setRallyTargetUnit(building);
        }

        Sprite sprite = building.requireSprite();

        this.game.getUnitPositioning().setFootprintOccupied(type, sprite.getX(), sprite.getY(), false);

        int status = building.getStatus() & ~(UnitStatusFlags.GROUNDED_BUILDING | UnitStatusFlags.CAN_MOVE) |
                     UnitStatusFlags.CAN_TURN | UnitStatusFlags.FLYING;

        if ((abilities & UnitTypeFlags.SUBUNIT) == 0) {
            status |= UnitStatusFlags.CAN_MOVE;
        }

        building.setStatus(status);
        sprite.setElevationLevel(Sprite.AIR_ELEVATION_LEVEL);
        this.resetMovementState(building);

        UnitSpatialIndex spatial = this.game.getUnitSystem().requireUnitSpatialIndex();

        spatial.notifyCollision(building, units);
        spatial.remove(building);
        spatial.insert(building, units);

        SpriteImage shadow = sprite.getImages().getLast();

        if (shadow.getModifier() == SpriteImage.MODIFIER_SHADOW) {
            shadow.freezeMapY();
        }

        building.setDirectionTargetX(sprite.getX());
        building.setDirectionTargetY(sprite.getY() - FLIGHT_HEIGHT);

        if (type == ScrUnitIds.ZERG_INFESTED_COMMAND_CENTER) {
            CreepField creep = this.game.getCreepField();

            if (creep == null) {
                throw Exceptions.newUnsupportedOperationException("Infested Command Center lift-off requires the creep field.");
            }

            // A creep removal that runs out of recession records is not retried here.
            creep.removeProvider(this.game, type, sprite.getX(), sprite.getY(), (building.getStatus() & UnitStatusFlags.COMPLETED) != 0);
        }

        building.setPrimaryOrder(ScrOrderIds.LIFTING_OFF);
    }

    /**
     * Raises a lifted building by its shadow offset and waits for the lift-off animation signal. A unit whose type is not a building gets its
     * modified top speed back when it has risen ({@link UnitSpeed#calculateModifiedTopSpeed}).
     *
     * @param building         the building executing {@link ScrOrderIds#LIFTING_OFF}
     * @param instructionLimit the order, animation and sprite initialization budget
     * @throws IllegalStateException if the building has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public void updateLiftingOff(@NotNull Unit building, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        Sprite sprite = building.requireSprite();
        boolean worker = (units.getFlags(building.getUnitId()) & UnitTypeFlags.WORKER) != 0;
        int phase = building.getPrimaryOrderState();

        if (phase == LIFT_STATE_TURNING || phase == LIFT_STATE_FACING) {
            if (phase == LIFT_STATE_TURNING) {
                if (!(building.getPositionX() == (short) building.getDirectionTargetX() &&
                      building.getPositionY() == (short) building.getDirectionTargetY()) &&
                    !isFacingDirectionTarget(building)) {
                    return;
                }

                building.setPrimaryOrderState(LIFT_STATE_FACING);
            }

            int positionX = sprite.getX();
            int positionY = sprite.getY() + sprite.getImages().getLast().getOffsetY() - (worker ? WORKER_FLIGHT_HEIGHT : FLIGHT_HEIGHT);

            this.game.getEngineGame().getMovement().setDestination(building, positionX, positionY);
            building.setDirectionTargetX(positionX);
            building.setDirectionTargetY(positionY);
            building.setPrimaryOrderState(LIFT_STATE_STARTING);
        } else if (phase == LIFT_STATE_STARTING || phase == LIFT_STATE_RISING) {
            if (phase == LIFT_STATE_STARTING) {
                this.game.getIscriptExecutor().runSpriteAnimation(sprite, building, IscriptExecutor.ANIMATION_LIFT_OFF, 1, instructionLimit);

                SpriteImage mainImage = sprite.getMainImage();

                if (mainImage != null && this.game.requireGameData().getImageTypes().getLiftoffDustOverlay(mainImage.getImageTypeId()) != 0) {
                    this.createDust(sprite, ScrImageTypeView.OVERLAY_LIFTOFF_DUST, DUST_FIRST_POSITION, DUST_LAST_POSITION, false, instructionLimit);
                    this.createDust(sprite,
                                    ScrImageTypeView.OVERLAY_LIFTOFF_DUST,
                                    FLIPPED_DUST_FIRST_POSITION, FLIPPED_DUST_LAST_POSITION,
                                    true,
                                    instructionLimit);
                }

                building.setTopSpeed(LIFT_SPEED);
                building.setNextSpeed(LIFT_SPEED);
                FlingyMotionUtils.setSpeed(building, LIFT_SPEED);
                building.setPrimaryOrderState(LIFT_STATE_RISING);
            }

            if (this.game.getEngineGame().getMovement().isAtDestination(building) && (building.getOrderSignal() & Unit.ORDER_SIGNAL_LIFT_DONE) != 0) {
                building.setOrderSignal(building.getOrderSignal() & ~Unit.ORDER_SIGNAL_LIFT_DONE);

                if ((units.getFlags(building.getUnitId()) & UnitTypeFlags.BUILDING) == 0) {
                    building.setTopSpeed(this.game.getUnitSpeed().calculateModifiedTopSpeed(building));
                }

                SpriteImage shadow = sprite.getImages().getLast();

                if (shadow.getModifier() == SpriteImage.MODIFIER_SHADOW) {
                    shadow.setOffsetY(worker ? WORKER_FLIGHT_HEIGHT : FLIGHT_HEIGHT);
                    shadow.unfreezeMapY();
                }

                this.game.getOrders().completeOrder(building, instructionLimit);
            }
        }
    }

    /**
     * Lands a flying building at its order target. A grounded building lifts off first. A blocked site raises the landing-interrupted notification,
     * discards a queued {@link ScrOrderIds#PLACE_ADDON} and ends the order.
     *
     * @param building         the building executing {@link ScrOrderIds#BUILDING_LAND}
     * @param instructionLimit the order, animation, sprite initialization and add-on connection budget
     * @throws IllegalStateException         if the building has no sprite
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the order pool is not initialized
     */
    public void updateLanding(@NotNull Unit building, int instructionLimit) {
        int positionX = (short) building.getOrderTargetX();
        int positionY = (short) building.getOrderTargetY();

        if (UnitQueries.isGroundedBuilding(building)) {
            this.game.getOrders().issue(building, ScrOrderIds.BUILDING_LIFTOFF, null, positionX, positionY, ScrUnitIds.NONE, false, instructionLimit);
            this.game.getOrders().queueReplacingSameOrders(building, ScrOrderIds.BUILDING_LAND, null, positionX, positionY, ScrUnitIds.NONE);

            return;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int type = building.getUnitId();
        Sprite sprite = building.requireSprite();
        int phase = building.getPrimaryOrderState();

        if (phase == LAND_STATE_START) {
            int destinationY = positionY - sprite.getImages().getLast().getOffsetY();

            this.game.getEngineGame().getMovement().setDestination(building, positionX, destinationY);
            building.setDirectionTargetX(positionX);
            building.setDirectionTargetY(destinationY);
            building.setPrimaryOrderState(LAND_STATE_APPROACHING);
        } else if (phase == LAND_STATE_APPROACHING) {
            if (this.game.getEngineGame().getMovement().isAtDestination(building)) {
                if (!this.game.getUnitPlacement().canPlaceBuilding(building, building.getOwnerIndex(), type, positionX, positionY, true, false)) {
                    this.game.getInterfaceLink().getNotificationEvents().raise(NotificationEvents.Kind.LANDING_INTERRUPTED, building);

                    OrderPool.Order next = building.getFirstOrder();

                    if (next != null && next.getOrderId() == ScrOrderIds.PLACE_ADDON) {
                        this.game.requireOrderPool().remove(building, next, this.game.requireGameData().getOrderTypes());
                    }

                    this.game.getOrders().finishOrder(building, instructionLimit);
                } else {
                    this.game.getUnitPositioning().setFootprintOccupied(type, positionX, positionY, true);
                    building.setLanding(true);
                    this.game.getEngineGame().getMovement().setDestination(building, positionX, positionY);
                    building.setDirectionTargetX(positionX);
                    building.setDirectionTargetY(positionY);

                    SpriteImage shadow = sprite.getImages().getLast();

                    if (shadow.getModifier() == SpriteImage.MODIFIER_SHADOW) {
                        shadow.freezeMapY();
                    }

                    building.setStatus(building.getStatus() | UnitStatusFlags.ORDER_NOT_INTERRUPTIBLE);
                    building.setTopSpeed(LIFT_SPEED);
                    building.setNextSpeed(LIFT_SPEED);
                    FlingyMotionUtils.setSpeed(building, LIFT_SPEED);
                    building.setPrimaryOrderState(LAND_STATE_TURNING);
                }
            }
        } else if (phase == LAND_STATE_TURNING) {
            if (building.getPositionX() == (short) building.getDirectionTargetX() && building.getPositionY() == (short) building.getDirectionTargetY() ||
                isFacingDirectionTarget(building)) {
                this.game.getIscriptExecutor().runSpriteAnimation(sprite, building, IscriptExecutor.ANIMATION_LANDING, 1, instructionLimit);
                building.setPrimaryOrderState(LAND_STATE_DESCENDING);
            }
        }

        if (building.getPrimaryOrderState() == LAND_STATE_DESCENDING && (building.getOrderSignal() & Unit.ORDER_SIGNAL_LIFT_DONE) != 0 &&
            this.game.getEngineGame().getMovement().isAtDestination(building)) {
            this.land(building, units, sprite, positionX, positionY, instructionLimit);
        }
    }

    /**
     * Finishes a landing: the building becomes a grounded building at the site, raises landing dust, kills the units it buries, keeps or replaces its
     * queued orders, lifts off again when a grounded unit still blocks it, and connects a neutral add-on.
     *
     * @param building         the landing building
     * @param units            the unit type table
     * @param sprite           the building's sprite
     * @param positionX        the horizontal landing position
     * @param positionY        the vertical landing position
     * @param instructionLimit the order, animation, sprite initialization and add-on connection budget
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit spatial index, the unit pool or the order pool is not initialized
     */
    private void land(@NotNull Unit building,
                      @NotNull ScrUnitTypeView units,
                      @NotNull Sprite sprite,
                      int positionX, int positionY,
                      int instructionLimit) {
        building.setOrderSignal(building.getOrderSignal() & ~Unit.ORDER_SIGNAL_LIFT_DONE);

        UnitSpatialIndex spatial = this.game.getUnitSystem().requireUnitSpatialIndex();

        spatial.remove(building);

        if ((building.getStatus() & UnitStatusFlags.FLYING) != 0) {
            spatial.unregisterFlyingUnit(building, units);
        }

        building.setStatus(building.getStatus() & ~(UnitStatusFlags.FLYING | UnitStatusFlags.CAN_TURN | UnitStatusFlags.CAN_MOVE) |
                           UnitStatusFlags.GROUNDED_BUILDING);
        sprite.setElevationLevel(LANDED_ELEVATION);
        this.resetMovementState(building);
        this.game.getUnitPositioning().move(building, positionX, positionY);
        spatial.insert(building, units);
        spatial.notifyCollision(building, units);

        SpriteImage shadow = sprite.getImages().getLast();

        shadow.setOffsetY(0);

        if (shadow.getModifier() == SpriteImage.MODIFIER_SHADOW) {
            shadow.unfreezeMapY();
        }

        SpriteImage mainImage = sprite.getMainImage();

        if (mainImage != null && this.game.requireGameData().getImageTypes().getLandingDustOverlay(mainImage.getImageTypeId()) != 0) {
            this.createDust(sprite, ScrImageTypeView.OVERLAY_LANDING_DUST, DUST_FIRST_POSITION, DUST_LAST_POSITION, false, instructionLimit);
            this.createDust(sprite,
                            ScrImageTypeView.OVERLAY_LANDING_DUST,
                            FLIPPED_DUST_FIRST_POSITION, FLIPPED_DUST_LAST_POSITION,
                            true,
                            instructionLimit);
        }

        building.setLanding(false);

        int type = building.getUnitId();
        int left = sprite.getX() - units.getDimensionLeft(type);
        int upper = sprite.getY() - units.getDimensionUp(type);
        int right = sprite.getX() + units.getDimensionRight(type);
        int lower = sprite.getY() + units.getDimensionDown(type);
        int[] covered = collectCovered(spatial, left, upper, right, lower);

        for (int index : covered) {
            Unit unit = this.game.getUnitSystem().requireUnitPool().getUnit(index);

            if (unit == building || this.game.getEngineGame().getCombat().isEnemy(unit, building)) {
                continue;
            }

            if (!this.game.getUnitQueries().canMove(unit) && this.game.getUnitTargeting().canAttack(unit) ||
                (unit.getStatus() & UnitStatusFlags.BURROWED) != 0) {
                this.game.getUnitLifecycle().kill(unit, instructionLimit);
            }
        }

        OrderPool.Order first = building.getFirstOrder();

        if (first != null) {
            if (first.getOrderId() == ScrOrderIds.MOVE || first.getOrderId() == ScrOrderIds.FOLLOW) {
                this.game.getOrders().queueFront(building, ScrOrderIds.BUILDING_LIFTOFF, null, 0, 0, ScrUnitIds.NONE);
            } else {
                OrderPool.Order last;

                while ((last = building.getLastOrder()) != null && last.getOrderId() != ScrOrderIds.PLACE_ADDON) {
                    this.game.requireOrderPool().remove(building, last, this.game.requireGameData().getOrderTypes());
                }

                if (building.getFirstOrder() == null) {
                    this.game.getOrders().issueUntargeted(building, units.getReturnToIdleOrder(type), instructionLimit);
                }
            }
        }

        covered = collectCovered(spatial, left, upper, right, lower);

        for (int index : covered) {
            Unit unit = this.game.getUnitSystem().requireUnitPool().getUnit(index);

            if (unit == building) {
                continue;
            }

            if ((UnitQueries.isGroundedBuilding(unit) || !this.game.getUnitQueries().canMove(unit)) &&
                this.game.getUnitTargeting().canAttack(unit) && unit.getPrimaryOrder() != ScrOrderIds.DIE) {
                this.game.getOrders().issueUntargeted(building, ScrOrderIds.BUILDING_LIFTOFF, instructionLimit);
                this.game.getOrders().queueReplacingSameOrders(building, units.getReturnToIdleOrder(type), null, 0, 0, ScrUnitIds.NONE);

                break;
            }
        }

        this.game.getOrders().completeOrder(building, instructionLimit);
        this.game.getUnitAddons().findAndConnect(building, instructionLimit);
    }

    /**
     * Creates a dust sprite for each defined overlay position, with consecutive sprite types starting at
     * {@link SpriteIds#BUILDING_LANDING_DUST_TYPE1}.
     *
     * @param sprite           the building's sprite
     * @param overlayType      the dust overlay table of the building's main image
     * @param firstPosition    the first overlay position
     * @param lastPosition     the last overlay position
     * @param flipped          {@code true} to flip the dust images horizontally, {@code false} otherwise
     * @param instructionLimit the sprite initialization budget
     * @throws IllegalStateException if the main image or its overlay offsets are missing
     * @throws NullPointerException  if the world was built without game data
     */
    private void createDust(@NotNull Sprite sprite, int overlayType, int firstPosition, int lastPosition, boolean flipped, int instructionLimit) {
        SpriteImage mainImage = sprite.getMainImage();

        if (mainImage == null) {
            throw Exceptions.newIllegalStateException("Building dust requires the main image.");
        }

        int spriteType = SpriteIds.BUILDING_LANDING_DUST_TYPE1;

        for (int position = firstPosition; position != lastPosition + 1; position++, spriteType++) {
            int offset;

            try {
                offset = this.game.requireGameData().getImageOverlayOffset(mainImage.getImageTypeId(),
                                                                           overlayType,
                                                                           mainImage.getDrawnFrameIndex(),
                                                                           position);
            } catch (IOException exception) {
                throw Exceptions.newIllegalStateException(exception, "Cannot load building dust overlay offsets.");
            }

            int offsetX = (short) offset;

            if ((mainImage.getFlags() & SpriteImage.FLAG_HORIZONTALLY_FLIPPED) != 0) {
                offsetX = -offsetX;
            }

            int offsetY = offset >> OVERLAY_VERTICAL_SHIFT;

            if (offsetX == UNDEFINED_DUST_OFFSET || offsetY == UNDEFINED_DUST_OFFSET) {
                continue;
            }

            SpriteEffect dust = this.game.getIscriptExecutor().createSpriteEffect(spriteType,
                                                                                  sprite.getX() + offsetX, sprite.getY() + offsetY,
                                                                                  0,
                                                                                  instructionLimit);

            if (dust == null) {
                continue;
            }

            Sprite dustSprite = dust.getSprite();

            dustSprite.setElevationLevel(sprite.getElevationLevel() + 1);

            SpriteVisibility.updatePositionTile(dustSprite, this.game.getMap(), this.game.getLocalVision().getComparisonMask());

            if (flipped) {
                for (SpriteImage image : dustSprite.getImages()) {
                    if ((image.getFlags() & SpriteImage.FLAG_HORIZONTALLY_FLIPPED) == 0) {
                        image.setFlags(image.getFlags() | SpriteImage.FLAG_HORIZONTALLY_FLIPPED | SpriteImage.FLAG_REDRAW);
                    }
                }
            }
        }
    }

    /**
     * Places a building in flight at once, as the in-transit unit property of the map loader and Create Unit with Properties (after the
     * {@link ScrOrderIds#BUILDING_LIFTOFF} eligibility test): the shadow image moves to the flight height, animation
     * {@link IscriptExecutor#ANIMATION_SPECIAL_STATE_1} runs, the unit leaves the unit finder and frees its footprint, becomes a turning and, unless
     * it is a subunit, moving, non-grounded unit, rises to the air elevation level, becomes flying (both status writes end in the movement reset) and
     * is registered again.
     *
     * @param building         the flying-capable building
     * @param instructionLimit the animation budget
     * @throws UnsupportedOperationException if the unit finder is missing
     * @throws IllegalStateException         if the building has no sprite
     * @throws NullPointerException          if the world was built without game data
     */
    public void enterTransit(@NotNull Unit building, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int type = building.getUnitId();
        Sprite sprite = building.requireSprite();
        SpriteImage shadow = sprite.getImages().getLast();

        if (shadow.getModifier() == SpriteImage.MODIFIER_SHADOW) {
            shadow.setOffsetY(FLIGHT_HEIGHT);
        }

        this.game.getIscriptExecutor().runSpriteAnimation(sprite, building, IscriptExecutor.ANIMATION_SPECIAL_STATE_1, 1, instructionLimit);

        UnitSpatialIndex spatial = this.game.getUnitSystem().getUnitSpatialIndex();

        if (spatial == null) {
            throw Exceptions.newUnsupportedOperationException("In-transit placement requires the unit finder.");
        }

        spatial.remove(building);

        if (UnitQueries.isGroundedBuilding(building)) {
            this.game.getUnitPositioning().setFootprintOccupied(type, sprite.getX(), sprite.getY(), false);
        }

        int status = building.getStatus() & ~(UnitStatusFlags.GROUNDED_BUILDING | UnitStatusFlags.CAN_MOVE) | UnitStatusFlags.CAN_TURN;

        if ((units.getFlags(type) & UnitTypeFlags.SUBUNIT) == 0) {
            status |= UnitStatusFlags.CAN_MOVE;
        }

        building.setStatus(status);
        this.resetMovementState(building);
        sprite.setElevationLevel(Sprite.AIR_ELEVATION_LEVEL);
        building.setStatus(building.getStatus() | UnitStatusFlags.FLYING);
        this.resetMovementState(building);

        UnitCreationContext creation = this.game.getUnitSystem().getUnitCreationContext();

        this.game.getUnitStorageConstructor().registerSpatially(building, creation != null && creation.isFoliageEnabled());
    }

    /**
     * Releases the unit's path, clears movement state and sets its ground collision path flag by its elevation.
     *
     * @param unit the unit
     * @throws UnsupportedOperationException if the unit has a path but the path pool is missing
     * @throws IllegalStateException         if the unit has no sprite
     */
    private void resetMovementState(@NotNull Unit unit) {
        if (unit.getPathIndex() >= 0) {
            PathPool paths = this.game.getPathSystem().getPathPool();

            if (paths == null) {
                throw Exceptions.newUnsupportedOperationException("Movement reset requires the path pool.");
            }

            paths.release(unit.getPathIndex());
            unit.setPathIndex(-1);
        }

        unit.setMovementState(0);
        UnitInitializer.updateGroundElevationPathFlag(unit, unit.requireSprite().getElevationLevel());
    }
}
