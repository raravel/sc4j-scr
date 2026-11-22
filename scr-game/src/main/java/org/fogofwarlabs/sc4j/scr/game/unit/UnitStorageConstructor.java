package org.fogofwarlabs.sc4j.scr.game.unit;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ButtonSetIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.order.ScrOrderSystem;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.player.UnitAccounting;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.production.UnitMorphing;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.Foliage;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Creates units: {@link #create} allocates a unit slot, applies the state before and after {@link UnitInitializer#initialize} in the established
 * execution order and commits the slot.
 * <p>
 * Completed-unit initialization and placement remain separate from storage creation and active-list insertion.
 */
public final class UnitStorageConstructor {
    /**
     * The creation message key of a full unit pool.
     */
    private static final byte @NotNull [] MAXIMUM_UNITS = "strMAXUNITS".getBytes(StandardCharsets.US_ASCII);
    /**
     * The creation message key of a failed storage creation.
     */
    private static final byte @NotNull [] CANNOT_CREATE = "strCANTCREATE".getBytes(StandardCharsets.US_ASCII);
    /**
     * The empty creation message.
     */
    private static final byte @NotNull [] NO_MESSAGE = new byte[0];

    /**
     * The random source {@link #create} consumes, also when the storage creation fails.
     */
    private static final int CREATION_RANDOM_SOURCE = 14;
    /**
     * The random source of the wireframe randomizer.
     */
    private static final int WIREFRAME_RANDOM_SOURCE = 15;

    /**
     * The mask of an unsigned WORD constructor type.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The detection flags of a new unit: every player bit set.
     */
    private static final int DETECTION_FLAGS_ALL_PLAYERS = -1;
    /**
     * The shift of the vertical offset (the high WORD) in a packed special overlay offset.
     */
    private static final int OVERLAY_VERTICAL_SHIFT = Short.SIZE;
    /**
     * The divisor from a footprint size in tiles to the tile offset of the footprint center.
     */
    private static final int FOOTPRINT_CENTER_DIVISOR = 2;
    /**
     * The special overlay point of the parent's main image frame that positions a subunit.
     */
    private static final int SUBUNIT_OVERLAY_POINT = 0;

    /**
     * Resets the modeled unit state before {@link UnitInitializer#initialize} while retaining unrelated fields.
     *
     * @param unit the free unit slot that the creation reuses
     */
    public static void clearBeforeInnerInitialization(@NotNull Unit unit) {
        unit.setPreviousPsiProvider(null);
        unit.setNextPsiProvider(null);
        unit.setPsiFieldSprite(null);
        unit.setRallyTargetUnit(null);
        unit.setRallyPositionX(0);
        unit.setRallyPositionY(0);
        unit.resetResourceAndLinkState();
        unit.setPreviousCloakedUnit(null);
        unit.setNextCloakedUnit(null);
        unit.setAutomaticTargetUnit(null);
        unit.setHighlightedOrderCount(0);
        unit.setClassicEventBytes(0);
        unit.setClassicUnusedByte(0);
        unit.setAlertMark(0);
        unit.setPreviousUnitType(0);
        unit.setLocalEventBytes(0);
        unit.setPrimaryOrderCountdown(0);
        unit.setFirstOrder(null);
        unit.setLastOrder(null);
        unit.setParentUnit(null);
        unit.resetLifetimeAndStatusDurations();
        unit.resetStatusEffectsAndLoadedUnitExtensions();
        unit.setNextHitNearTargetIndex(0);
        unit.setKillCount(0);
        unit.setStatus(0);
        unit.setUserActionFlags(0);
        unit.setPathCollisionFlags(0);
        unit.setPreviousVisibleHitPoints(1);
        unit.setComputerRecord(null);
    }

    /**
     * Applies the modeled state changes after {@link UnitInitializer#initialize} succeeds.
     * <p>
     * Generation advances here while the slot remains on the free list. The caller must finish the remaining storage
     * effects before committing pool allocation.
     *
     * @param pool          the pool owning the available unit slot
     * @param unit          the unit slot after a successful {@link UnitInitializer#initialize}
     * @param requestedType the unsigned WORD constructor type
     * @param units         the initialized unit type table
     * @param random        the shared random generator
     * @throws UnsupportedOperationException if the unit type is past {@link ScrUnitIds#NONE}
     */
    public static void initializeStorageAfterInner(@NotNull UnitPool pool,
                                                   @NotNull Unit unit,
                                                   int requestedType,
                                                   @NotNull ScrUnitTypeView units,
                                                   @NotNull LcgRandom random) {
        unit.setFirstBuildQueueType(ScrUnitIds.NONE);
        unit.setSecondBuildQueueType(ScrUnitIds.NONE);
        unit.setThirdBuildQueueType(ScrUnitIds.NONE);
        unit.setFourthBuildQueueType(ScrUnitIds.NONE);
        unit.setFifthBuildQueueType(ScrUnitIds.NONE);
        pool.advanceGeneration(unit);
        unit.setBuildQueueIndex(0);

        int currentType = unit.getUnitId();
        int targetType = units.isRallyProducer(currentType) ? ButtonSetIds.CANCEL_CONSTRUCTION_WITH_RALLY : ButtonSetIds.CANCEL_CONSTRUCTION;

        UnitMorphing.writeButtonSet(units, unit, targetType);

        unit.setWireframeRandomizer(random.next(WIREFRAME_RANDOM_SOURCE));

        if (currentType > ScrUnitIds.NONE && !units.isAddedType(currentType)) {
            throw Exceptions.newUnsupportedOperationException("Unit storage of unit type {0} past {1} is not modeled.",
                                                              currentType, ScrUnitIds.NONE);
        }

        int hitPoints = (units.getFlags(currentType) & UnitTypeFlags.SUBUNIT) != 0
                        ? 1
                        : Integer.divideUnsigned(units.getHitPoints(requestedType & WORD_MASK), UnitInitializer.CONSTRUCTION_START_DIVISOR);

        unit.setHitPoints(hitPoints);

        int primaryOrder = UnitQueries.isGroundedBuilding(unit) ? units.getComputerIdleOrder(requestedType & WORD_MASK) : ScrOrderIds.NOTHING;

        unit.setPrimaryOrder(primaryOrder);
        unit.setOrderTargetUnit(null);
        unit.setOrderTargetX(0);
        unit.setOrderTargetY(0);
        unit.setPrimaryOrderState(0);
        unit.setOrderTargetUnitType(ScrUnitIds.NONE);

        ScrOrderSystem.setSecondaryOrder(unit, ScrOrderIds.NOTHING);
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitStorageConstructor} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitStorageConstructor(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Leaves the pending creation message of a storage creation that fails before its type is read: the key {@code strMAXUNITS} when
     * every slot is in use, otherwise (the position check) an empty message.
     *
     * @param unitLimit whether every unit slot is in use
     */
    public void recordEarlyCreationFailure(boolean unitLimit) {
        this.game.getGameContext().setLastErrorBytes(unitLimit ? MAXIMUM_UNITS : NO_MESSAGE);
    }

    /**
     * Shows the placement warning for a created unit that found no placement, at its sprite position (sign-extended WORDs).
     *
     * @param unitType the unit type whose name the warning shows
     * @param unit     the unplaced unit
     * @throws UnsupportedOperationException if the unit has no sprite
     */
    public void notifyUnplaceable(int unitType, @NotNull Unit unit) {
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newUnsupportedOperationException("The placement warning reads the unplaced unit's sprite position.");
        }

        this.notifyUnplaceable(unitType, (short) sprite.getX(), (short) sprite.getY());
    }

    /**
     * Shows the placement warning {@code errCANTPLACE_sdd} of a unit that could not be placed: the text for every player
     * ({@link MessageLines#writeUnplaceable}) and the error sound. The unit creation wrapper shows it for a trigger
     * building whose site fails (at the snapped placement center) and for a created unit that finds no placement (at the
     * unit position); the preplaced-unit loader shows it for a unit without placement.
     *
     * @param unitType  the unit type whose name the warning shows
     * @param positionX the X the warning shows
     * @param positionY the Y the warning shows
     */
    public void notifyUnplaceable(int unitType, int positionX, int positionY) {
        this.game.getInterfaceLink().getMessageLines().writeUnplaceable(this.game.getUnitDisplayName(unitType), unitType, positionX, positionY);
        this.game.getInterfaceLink().getCommandErrorEvents().raiseErrorMessage();
    }

    /**
     * Shows and clears the pending creation message: a nonempty key in the pending message buffer goes to the status line
     * of the player, then the buffer is emptied.
     *
     * @param player the player the message is for
     */
    public void notifyCreationFailure(int player) {
        byte[] pending = this.game.getGameContext().getLastErrorBytes();

        if (pending == null || pending.length == 0) {
            return;
        }

        String key = new String(pending, StandardCharsets.US_ASCII);

        this.game.getInterfaceLink().getCommandErrorEvents().raiseStatusKey(player, key);

        // Write the local player's status line before triggers in the same pass read it.
        TriggerContext triggers = this.game.getTriggerContext();

        if (triggers != null && triggers.getLocalPlayer() == player) {
            this.game.getInterfaceLink().getMessageLines().writeStatus(key, 0);
        }

        this.game.getGameContext().setLastErrorBytes(NO_MESSAGE);
    }

    /**
     * Initializes completion state, places a hidden unit and reports placement failure to its owner. Final completion runs only after placement
     * succeeds.
     *
     * @param creation         the unit creation inputs
     * @param unit             the successfully constructed unit
     * @param ownerPlayerType  the owner player type BYTE
     * @param instructionLimit the checked script instruction limit
     * @return {@code true} if the unit was placed and completed, {@code false} otherwise
     * @throws UnsupportedOperationException if the unit sprite or the unit accounting is unavailable
     * @throws NullPointerException          if the world was built without game data
     */
    public boolean tryComplete(@NotNull UnitCreationContext creation, @NotNull Unit unit, int ownerPlayerType, int instructionLimit) {
        this.game.getUnitCompletion().initializeBeforePlacement(creation, unit, instructionLimit);

        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newUnsupportedOperationException("Unit completion requires the unit sprite.");
        }

        if ((sprite.getFlags() & Sprite.FLAG_HIDDEN) != 0) {
            int[] position = new int[UnitPositioning.POSITION_COORDINATES];

            if (!this.game.getUnitPositioning().find(unit, sprite.getX(), sprite.getY(), position)) {
                // The owner sees the pending message the search left (strEXITBLOCKED).
                this.notifyCreationFailure(unit.getOwnerIndex());

                return false;
            }

            this.game.getUnitPositioning().move(unit, position[UnitPositioning.X_INDEX], position[UnitPositioning.Y_INDEX]);

            ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
            Unit subunit = unit.getSubunit();

            if (subunit != null && (units.getFlags(unit.getUnitId()) & UnitTypeFlags.SUBUNIT) == 0) {
                UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();

                if (accounting == null) {
                    throw Exceptions.newUnsupportedOperationException("Subunit completion requires initialized unit accounting.");
                }

                accounting.updateCompleted(subunit, units, 1, false, this.game.getPlayerTable().getProductionStatistics());
                subunit.setStatus(subunit.getStatus() | UnitStatusFlags.COMPLETED);
                subunit.setHitPoints(units.getHitPoints(subunit.getUnitId()));
                this.game.getUnitPositioning().move(subunit, position[UnitPositioning.X_INDEX], position[UnitPositioning.Y_INDEX]);
            }
        }

        this.game.getUnitCompletion().completeAfterPlacement(creation, unit, ownerPlayerType, instructionLimit);

        return true;
    }

    /**
     * Creates and links a primary unit and its required turret.
     * <p>
     * Random source {@code 14} is consumed even when storage creation fails. A required turret is created after primary
     * commitment and list insertion; turret failure destroys the primary unit and fails creation. The simulation plugins hear the primary unit's
     * creation after its list insertion.
     *
     * @param creation         the unit creation inputs
     * @param accounting       the established game-context counters
     * @param unitType         the DWORD constructor type
     * @param positionX        the horizontal DWORD coordinate
     * @param positionY        the vertical DWORD coordinate
     * @param owner            the DWORD owner
     * @param creationTag      the caller's WORD metadata
     * @param instructionLimit the iscript execution bound
     * @return the linked unit, or {@code null} for a represented creation failure
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    public @Nullable Unit create(@NotNull UnitCreationContext creation,
                                 @NotNull UnitAccounting accounting,
                                 int unitType,
                                 int positionX, int positionY,
                                 int owner,
                                 int creationTag,
                                 int instructionLimit) {
        int normalizedOwner = Integer.compareUnsigned(owner, PlayerTable.PLAYER_SLOTS) < 0 ? owner : PlayerTable.NEUTRAL_PLAYER_ID;

        this.game.getRandom().next(CREATION_RANDOM_SOURCE);

        Unit unit = this.createStorage(creation, accounting, unitType, positionX, positionY, normalizedOwner, creationTag, instructionLimit);

        if (unit == null) {
            return null;
        }

        this.game.getUnitSystem().requireUnitPool().insertCreatedUnit(unit);

        ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

        if (simulation != null) {
            simulation.reportUnitCreated(unit);
        }

        int subunitType = this.game.requireGameData().getUnitTypes().getFirstSubunit(unitType);

        if (Integer.compareUnsigned(unitType, UnitTypeTraitsUtils.LAST_NON_BUILDING_TYPE) <= 0 && subunitType != ScrUnitIds.NONE) {
            // Create the turret with the parent's registry tag and without list insertion, link both units and
            // place the turret image at the parent's first special overlay point.
            UnitPool pool = this.game.getUnitSystem().requireUnitPool();
            Unit subunit = this.createStorage(creation,
                                              accounting,
                                              subunitType,
                                              positionX, positionY,
                                              normalizedOwner,
                                              pool.getCreationTag(unit.getIndex()),
                                              instructionLimit);

            if (subunit == null) {
                // Turret failure destroys the hidden primary unit and its sprite before returning.
                this.game.getUnitLifecycle().destroyHidden(unit, instructionLimit);

                return null;
            }

            unit.setSubunit(subunit);
            subunit.setSubunit(unit);
            this.attachSubunitImage(unit, subunit);

            return unit;
        }

        unit.setSubunit(null);

        return unit;
    }

    /**
     * Initializes an available unit slot and commits it after accounting and storage setup succeed.
     * <p>
     * {@link #create} calls it with the normalized owner after consuming random source {@code 14}. Active-list insertion, subunit creation and
     * completed-unit initialization are separate operations. An unsupported later effect preserves preceding writes and does not commit the pool.
     *
     * @param creation         the unit creation inputs
     * @param accounting       the established game-context counters
     * @param unitType         the requested unit type
     * @param positionX        the horizontal DWORD coordinate
     * @param positionY        the vertical DWORD coordinate
     * @param owner            the previously normalized owner in {@code 0..11}
     * @param creationTag      the caller's WORD metadata
     * @param instructionLimit the iscript execution bound
     * @return the created storage, or {@code null} for a handled creation failure
     * @throws UnsupportedOperationException if required creation storage or effects are unavailable
     * @throws NullPointerException          if the world was built without game data
     */
    public @Nullable Unit createStorage(@NotNull UnitCreationContext creation,
                                        @NotNull UnitAccounting accounting,
                                        int unitType,
                                        int positionX, int positionY,
                                        int owner,
                                        int creationTag,
                                        int instructionLimit) {
        UnitPool pool = this.game.getUnitSystem().getUnitPool();

        if (pool == null) {
            throw Exceptions.newUnsupportedOperationException("Unit creation requires an initialized unit pool.");
        }

        if (pool.getAllocatedCount() == pool.getCapacity()) {
            // The pending creation message becomes the key strMAXUNITS.
            this.game.getGameContext().setLastErrorBytes(MAXIMUM_UNITS);

            return null;
        }

        int requestedType = unitType & WORD_MASK;
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        if (!UnitCreationBoundsUtils.isWithinCreationBounds(units, this.game.getMap(), requestedType, positionX, positionY)) {
            // The position check empties the pending creation message.
            this.game.getGameContext().setLastErrorBytes(NO_MESSAGE);

            return null;
        }

        if (pool.getFirstFreeIndex() == 0) {
            // No free slot or a failed storage creation leaves the key strCANTCREATE.
            this.game.getGameContext().setLastErrorBytes(CANNOT_CREATE);

            return null;
        }

        if (owner < 0 || owner >= PlayerTable.PLAYER_SLOTS) {
            throw Exceptions.newUnsupportedOperationException("Unit storage creation requires an owner normalized to a player slot.");
        }

        Unit unit = pool.getUnit(pool.getFirstFreeIndex());

        clearBeforeInnerInitialization(unit);

        if (!this.game.getUnitInitializer().initialize(unit, requestedType, positionX, positionY, owner, instructionLimit)) {
            this.game.getGameContext().setLastErrorBytes(CANNOT_CREATE);

            return null;
        }

        initializeStorageAfterInner(pool, unit, requestedType, units, this.game.getRandom());
        pool.prependToOwnerList(unit, owner);
        accounting.update(unit, units, 1, creation.preservesNegativeTypeCounts());
        this.finishStorageAfterAccounting(unit, requestedType, creation, instructionLimit);
        pool.commitInitializedUnit(unit);
        pool.setCreationTag(unit.getIndex(), creationTag);

        return unit;
    }

    /**
     * Places the subunit's main image at the parent's first special overlay point (mirrored when the parent is flipped) and stores the BYTE offsets.
     *
     * @param unit    the primary unit
     * @param subunit the created subunit
     * @throws IllegalStateException if either main image is absent or the special offsets cannot be loaded
     * @throws NullPointerException  if the world was built without game data
     */
    public void attachSubunitImage(@NotNull Unit unit, @NotNull Unit subunit) {
        Sprite parentSprite = unit.getSpriteOrNull();
        SpriteImage parentImage = parentSprite == null ? null : parentSprite.getMainImage();
        Sprite subunitSprite = subunit.getSpriteOrNull();
        SpriteImage subunitImage = subunitSprite == null ? null : subunitSprite.getMainImage();

        if (parentImage == null || subunitImage == null) {
            throw Exceptions.newIllegalStateException("Subunit attachment requires the main images of both units.");
        }

        int offset;

        try {
            offset = this.game.requireGameData().getImageSpecialOverlayOffset(parentImage.getImageTypeId(), parentImage.getDrawnFrameIndex(),
                                                                              SUBUNIT_OVERLAY_POINT);
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Cannot load special image offsets.");
        }

        int horizontal = (short) offset;
        int vertical = (short) (offset >>> OVERLAY_VERTICAL_SHIFT);

        if ((parentImage.getFlags() & SpriteImage.FLAG_HORIZONTALLY_FLIPPED) != 0) {
            horizontal = -horizontal;
        }

        if (subunitImage.getOffsetX() != (byte) horizontal || subunitImage.getOffsetY() != (byte) vertical) {
            subunitImage.setFlags(subunitImage.getFlags() | SpriteImage.FLAG_REDRAW);
            subunitImage.setOffsetX(horizontal);
            subunitImage.setOffsetY(vertical);
        }
    }

    /**
     * Applies the established spatial, occupancy and collision sequence of a new unit.
     *
     * @param unit           the unit being registered
     * @param foliageEnabled whether foliage is enabled, which registers a resource in the foliage
     * @throws UnsupportedOperationException if required spatial, terrain, foliage or sprite storage is unavailable, or the occupied tiles lie outside
     *                                       the terrain tile flags
     * @throws NullPointerException          if the world was built without game data
     */
    public void registerSpatially(@NotNull Unit unit, boolean foliageEnabled) {
        UnitSpatialIndex spatialIndex = this.game.getUnitSystem().getUnitSpatialIndex();

        if (spatialIndex == null) {
            throw Exceptions.newUnsupportedOperationException("Spatial registration requires an initialized unit spatial index.");
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        spatialIndex.insert(unit, units);

        if (UnitQueries.isGroundedBuilding(unit)) {
            Sprite sprite = unit.getSpriteOrNull();

            if (sprite == null) {
                throw Exceptions.newUnsupportedOperationException("Occupancy registration requires the building sprite.");
            }

            int unitType = unit.getUnitId();
            int width = (short) units.getPlacementWidth(unitType) / TerrainTileset.TILE_SIZE;
            int height = (short) units.getPlacementHeight(unitType) / TerrainTileset.TILE_SIZE;
            int left = (short) sprite.getX() / TerrainTileset.TILE_SIZE - width / FOOTPRINT_CENTER_DIVISOR;
            int upper = (short) sprite.getY() / TerrainTileset.TILE_SIZE - height / FOOTPRINT_CENTER_DIVISOR;
            GameMap map = this.game.getMap();

            if (!map.hasTileFlags()) {
                throw Exceptions.newUnsupportedOperationException("Occupancy registration requires expanded terrain tile flags.");
            }

            if (width < 0 || height < 0) {
                throw Exceptions.newUnsupportedOperationException("Occupancy registration of a negative placement size is not modeled.");
            }

            int tileCount = map.getTileFlagCount();
            int position = left + map.getWidth() * upper;

            for (int row = 0; row < height; row++) {
                for (int column = 0; column < width; column++) {
                    if (position < 0 || position >= tileCount) {
                        throw Exceptions.newUnsupportedOperationException("Occupancy registration reaches tile {0} outside the terrain tile flags.",
                                                                          Integer.toUnsignedString(position));
                    }

                    map.occupyTile(position++);
                }

                position += map.getWidth() - width;
            }
        }

        spatialIndex.notifyCollision(unit, units);

        if ((unit.getStatus() & UnitStatusFlags.FLYING) != 0) {
            spatialIndex.registerFlyingUnit(unit, units, this.game.getRandom());
        }

        int unitType = unit.getUnitId();

        if ((units.isMineralField(unitType) || unitType == ScrUnitIds.RESOURCE_VESPENE_GEYSER) && foliageEnabled) {
            Foliage foliage = this.game.getFoliage();
            Sprite sprite = unit.getSpriteOrNull();

            if (foliage == null || sprite == null) {
                throw Exceptions.newUnsupportedOperationException("Resource registration requires initialized foliage and the resource sprite.");
            }

            int width = (short) units.getPlacementWidth(unitType) / TerrainTileset.TILE_SIZE;
            int height = (short) units.getPlacementHeight(unitType) / TerrainTileset.TILE_SIZE;
            int left = (short) ((short) sprite.getX() / TerrainTileset.TILE_SIZE) - width / FOOTPRINT_CENTER_DIVISOR;
            int upper = (short) ((short) sprite.getY() / TerrainTileset.TILE_SIZE) - height / FOOTPRINT_CENTER_DIVISOR;

            foliage.markResourceFootprint(left, upper, width, height);
        }
    }

    /**
     * Finishes storage after owner and global accounting updates.
     *
     * @param unit             the storage after creation accounting
     * @param requestedType    the unsigned WORD constructor type
     * @param creation         the unit creation inputs
     * @param instructionLimit the iscript execution bound
     * @throws UnsupportedOperationException if a building refresh is requested, the unit sprite is missing or the unit type is past
     *                                       {@link ScrUnitIds#NONE}
     * @throws IllegalStateException         if the sprite is absent after construction image initialization or the unit registry is missing
     * @throws NullPointerException          if the world was built without game data
     */
    public void finishStorageAfterAccounting(@NotNull Unit unit, int requestedType, @NotNull UnitCreationContext creation, int instructionLimit) {
        Sprite sprite;

        if (UnitQueries.isGroundedBuilding(unit)) {
            this.registerSpatially(unit, creation.isFoliageEnabled());
            this.game.getIscriptExecutor().initializeConstructionImage(unit, IscriptExecutor.CONSTRUCTION_IMAGE_MODE, instructionLimit);

            sprite = unit.getSpriteOrNull();

            if (sprite == null) {
                throw Exceptions.newIllegalStateException("A building has no sprite after its construction image is initialized.");
            }

            sprite.setVisibilityFlags(0);

            if (creation.isBuildingRefreshEnabled()) {
                throw Exceptions.newUnsupportedOperationException("The building refresh after unit storage creation is not modeled.");
            }
        } else {
            int currentType = unit.getUnitId();

            int ammunition = this.game.requireGameData().getUnitTypes().getInitialAmmunition(currentType);

            if (ammunition != 0) {
                unit.setSpiderMineCount(ammunition);
            }

            sprite = unit.getSpriteOrNull();

            if (sprite == null) {
                throw Exceptions.newUnsupportedOperationException("Unit storage creation requires the unit sprite.");
            }

            sprite.setFlags(sprite.getFlags() | Sprite.FLAG_HIDDEN);
            sprite.setVisibilityFlags(0);
        }

        unit.setDetectionFlags(DETECTION_FLAGS_ALL_PLAYERS);

        int currentType = unit.getUnitId();
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        if (currentType > ScrUnitIds.NONE && !units.isAddedType(currentType)) {
            throw Exceptions.newUnsupportedOperationException("Unit storage of unit type {0} past {1} is not modeled.",
                                                              currentType, ScrUnitIds.NONE);
        }

        if ((units.getFlags(currentType) & UnitTypeFlags.SUBUNIT) != 0) {
            sprite.setFlags(sprite.getFlags() | Sprite.FLAG_TURRET);
        } else if ((sprite.getFlags() & Sprite.FLAG_HIDDEN) == 0) {
            this.game.getUnitVisibility().updateFog(unit);
        }

        if ((requestedType & WORD_MASK) != ScrUnitIds.ZERG_LARVA && creation.isRegistryPresent()) {
            UnitRegistry registry = this.game.getUnitSystem().getUnitRegistry();

            if (registry == null) {
                throw Exceptions.newIllegalStateException("The unit registry is created with the game, before any unit.");
            }

            registry.registerStoredUnit(unit, false);
        }
    }
}
