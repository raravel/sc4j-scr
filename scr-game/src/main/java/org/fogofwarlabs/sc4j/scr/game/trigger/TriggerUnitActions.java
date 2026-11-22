package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkUnit;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitAvailabilityFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.ability.UnitHallucination;
import org.fogofwarlabs.sc4j.scr.game.ability.UnitHangar;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerUnitRecord;
import org.fogofwarlabs.sc4j.scr.game.ai.ComputerUnits;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepField;
import org.fogofwarlabs.sc4j.scr.game.map.PreplacedUnitLoaderUtils;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.player.*;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitPositioning;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Applies the unit handlers of the trigger actions: creating, giving, ordering, modifying and removing units.
 */
public final class TriggerUnitActions {
    private static final int BYTE_MASK = 0xFF;
    private static final int WORD_MASK = 0xFFFF;
    private static final int BYTE_BITS = 8;
    private static final int FIXED_POINT_FRACTION_BITS = 8;
    private static final int POSITION_CENTER_DIVISOR = 2;

    /**
     * The location index of a Kill Unit or Remove Unit action without a location ({@link #removeUnits}).
     */
    public static final int NO_LOCATION = 0xFF;

    /**
     * The size of one {@code UPRP} unit property record.
     */
    private static final int PROPERTY_RECORD_SIZE = 20;
    /**
     * The number of {@code UPRP} unit property records.
     */
    static final int PROPERTY_RECORD_COUNT = TriggerContext.UNIT_PROPERTIES_SIZE / PROPERTY_RECORD_SIZE;

    /**
     * The offsets of the fields of a {@code UPRP} record, in the layout of {@code UNIT} bytes {@code 12..31}.
     */
    private static final int PROPERTY_VALID_PROPERTIES = 0;
    private static final int PROPERTY_VALID_ELEMENTS = 2;
    private static final int PROPERTY_OWNER = 4;
    private static final int PROPERTY_HIT_POINTS = 5;
    private static final int PROPERTY_SHIELD_POINTS = 6;
    private static final int PROPERTY_ENERGY = 7;
    private static final int PROPERTY_RESOURCES = 8;
    private static final int PROPERTY_HANGAR_COUNT = 12;
    private static final int PROPERTY_STATE_FLAGS = 14;

    /**
     * The extent at the bottom of the map that a created unit stays out of: the bottom tile row.
     */
    private static final int EXCLUDED_BOTTOM_EXTENT = TerrainTileset.TILE_SIZE;

    /**
     * The resources of a created geyser or refinery.
     */
    private static final int CREATED_GAS_RESOURCES = 5000;
    /**
     * The resources of a created mineral field.
     */
    private static final int CREATED_MINERAL_RESOURCES = 1500;

    /**
     * The divisor of the Modify Unit percentages.
     */
    private static final int PERCENT = 100;

    /**
     * Returns the {@code UPRP} record {@code index} in the layout of {@code UNIT} bytes {@code 12..31}, which the unit
     * property writer reads for both sources.
     *
     * @param context the trigger context
     * @param index   the record index {@code 0..63}
     * @return the unit record with the record's properties
     * @throws UnsupportedOperationException if the map's {@code UPRP} records are not loaded
     */
    static @NotNull ChkUnit readUnitPropertyRecord(@NotNull TriggerContext context, int index) {
        byte[] records = context.unitProperties;

        if (records == null) {
            throw Exceptions.newUnsupportedOperationException("Create Unit with Properties requires the loaded unit property records.");
        }

        int offset = index * PROPERTY_RECORD_SIZE;
        long resources = LittleEndianUtils.readUnsigned32(records, offset + PROPERTY_RESOURCES);

        return new ChkUnit(0,
                           0, 0,
                           0,
                           0,
                           LittleEndianUtils.readUnsigned16(records, offset + PROPERTY_VALID_PROPERTIES),
                           LittleEndianUtils.readUnsigned16(records, offset + PROPERTY_VALID_ELEMENTS),
                           records[offset + PROPERTY_OWNER] & BYTE_MASK,
                           records[offset + PROPERTY_HIT_POINTS] & BYTE_MASK,
                           records[offset + PROPERTY_SHIELD_POINTS] & BYTE_MASK,
                           records[offset + PROPERTY_ENERGY] & BYTE_MASK,
                           resources,
                           LittleEndianUtils.readUnsigned16(records, offset + PROPERTY_HANGAR_COUNT),
                           LittleEndianUtils.readUnsigned16(records, offset + PROPERTY_STATE_FLAGS),
                           0);
    }

    /**
     * Applies the type predicates of Kill Unit and Remove Unit: the owner must match; men, buildings and factories
     * require their StarEdit group flag, other values the exact type. Any Unit accepts every unit of the owner.
     *
     * @param units    the unit type table
     * @param unit     the unit
     * @param player   the owner the unit must have
     * @param unitType the unit type or unit group of the action
     * @return {@code true} if the unit matches, {@code false} otherwise
     */
    private static boolean matchesTriggerUnit(@NotNull ScrUnitTypeView units, @NotNull Unit unit, int player, int unitType) {
        if (unit.getOwnerIndex() != player) {
            return false;
        }

        return TriggerLocations.matchesGroupType(units, unit.getUnitId(), unitType & WORD_MASK);
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link TriggerUnitActions} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public TriggerUnitActions(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Kills or removes one player's units for Kill Unit and Remove Unit. Without a location the player's unit list is
     * traversed, skipping subunits, removed or dying units and units rejected by the type predicate; with a location the
     * rectangle query results pass the elevation filter, and the contents of transports and carried powerups are handled
     * first without counting them. Removal hides the unit before it is killed; each counted unit decrements the limit.
     *
     * @param context  the trigger context
     * @param player   the player whose units are killed or removed
     * @param unitType the unit type or unit group (its low WORD is used)
     * @param location the zero-based location index, or {@link #NO_LOCATION}
     * @param limit    the number of units to kill or remove ({@code 999999} for all units)
     * @param remove   whether the units are removed instead of killed
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if a location action has no unit rectangle query or the unit pool is not initialized
     * @throws IllegalStateException         if the terrain miniature flags cannot be loaded
     */
    public void removeUnits(@NotNull TriggerContext context, int player, int unitType, int location, int limit, boolean remove) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        if (location == NO_LOCATION) {
            int remaining = limit;
            // A player from 12 has no unit list; the location path matches no owner from 12 either.
            int index = this.game.getUnitSystem().requireUnitPool().getFirstOwnerIndex(player);

            while (index != 0) {
                UnitPool pool = this.game.getUnitSystem().requireUnitPool();
                Unit unit = pool.getUnit(index);
                int next = pool.getNextOwnerIndex(index);

                if ((units.getFlags(unit.getUnitId()) & UnitTypeFlags.SUBUNIT) == 0 && !ComputerUnits.isDeadOrHidden(unit) &&
                    matchesTriggerUnit(units, unit, player, unitType)) {
                    this.removeUnit(unit, remove, false);

                    if (--remaining == 0) {
                        return;
                    }
                }

                index = next;
            }

            return;
        }

        UnitSpatialIndex spatial = this.game.getUnitSystem().getUnitSpatialIndex();

        if (spatial == null) {
            throw Exceptions.newUnsupportedOperationException("Kill and Remove Unit At Location require the unit spatial index.");
        }

        TerrainTileset tileset;

        try {
            tileset = this.game.requireGameData().getTerrainTileset(this.game.getMap().getTileset());
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Location actions require the terrain miniature flags.");
        }

        int[] found = TriggerLocations.findUnitsIn(spatial, units, context.locations[location]);
        int[] remaining = {limit};

        for (int poolIndex : found) {
            if (this.removeLocationUnit(units,
                                        tileset,
                                        this.game.getUnitSystem().requireUnitPool().getUnit(poolIndex),
                                        player,
                                        unitType,
                                        context.locations[location].getElevationFlags(),
                                        true,
                                        remaining,
                                        remove)) {
                return;
            }
        }
    }

    /**
     * Kills or removes one unit of a location: the elevation filter for a counted unit, then the loaded units and the
     * carried powerup uncounted, then the unit itself.
     *
     * @param units          the unit type table
     * @param tileset        the terrain tileset of the elevation filter
     * @param unit           the unit
     * @param player         the owner the unit must have
     * @param unitType       the unit type or unit group of the action
     * @param elevationFlags the location's elevation flags
     * @param counted        {@code true} if the unit counts against the limit and passes the elevation filter
     * @param remaining      the remaining limit at index {@code 0}, decremented for every counted unit
     * @param remove         {@code true} for Remove Unit, {@code false} for Kill Unit
     * @return {@code true} if the counted limit reached zero, {@code false} otherwise
     */
    private boolean removeLocationUnit(@NotNull ScrUnitTypeView units,
                                       @NotNull TerrainTileset tileset,
                                       @NotNull Unit unit,
                                       int player,
                                       int unitType,
                                       int elevationFlags,
                                       boolean counted,
                                       int @NotNull [] remaining,
                                       boolean remove) {
        if (counted && this.game.getTriggerLocations().isExcludedByElevation(tileset, unit, elevationFlags)) {
            return false;
        }

        int type = unit.getUnitId();
        boolean providesSpace = TriggerLocations.providesSpace(units, this.game.getPlayerTable().getPlayerUpgrades(), unit);

        if (providesSpace) {
            for (int slot = 0; slot < units.getSpaceProvided(type); slot++) {
                Unit loaded = this.game.getUnitTransport().getLoadedUnit(unit, slot);

                if (loaded != null) {
                    this.removeLocationUnit(units, tileset, loaded, player, unitType, elevationFlags, false, remaining, remove);
                }
            }
        }

        // A worker's carried powerup is handled next, uncounted.
        Unit carried = (units.getFlags(type) & UnitTypeFlags.WORKER) != 0 ? unit.getCarriedPowerup() : null;

        if (carried != null) {
            this.removeLocationUnit(units, tileset, carried, player, unitType, elevationFlags, false, remaining, remove);
        }

        if (ComputerUnits.isDeadOrHidden(unit) || !matchesTriggerUnit(units, unit, player, unitType)) {
            return false;
        }

        this.removeUnit(unit, remove, providesSpace);

        if (!counted) {
            return false;
        }

        return --remaining[0] == 0;
    }

    /**
     * Kills or removes one unit as Kill Unit and Remove Unit do: a powerup a worker carries is released through its carrier instead; a removed
     * unit is hidden before it is killed, except a unit that provides space whose loaded units the caller handled, which its Die order hides.
     * The unit is not tested for its owner, its type or its state. The location actions kill or remove the loaded units of a unit that provides
     * space ({@link TriggerLocations#providesSpace}) first; the actions without a location do not.
     *
     * @param unit          the unit
     * @param remove        {@code true} for Remove Unit, {@code false} for Kill Unit
     * @param providesSpace {@code true} if the unit provides space and its loaded units were killed or removed first, {@code false} otherwise
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the order pool is not initialized
     */
    public void removeUnit(@NotNull Unit unit, boolean remove, boolean providesSpace) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        // A powerup carried by a worker is released through its carrier instead; an uncarried powerup is hidden and killed
        // like any other unit.
        if ((units.getFlags(unit.getUnitId()) & UnitTypeFlags.POWERUP) != 0 && unit.getPowerupCarrier() != null) {
            this.game.getUnitPowerups().release(unit.getPowerupCarrier(), Integer.MAX_VALUE);

            return;
        }

        if (providesSpace && remove) {
            // A transport is not hidden now: its Die order hides it, then it is killed.
            unit.setUserActionFlags(unit.getUserActionFlags() | Unit.USER_ACTION_HIDE_ON_DEATH);
            this.game.getUnitLifecycle().kill(unit, Integer.MAX_VALUE);

            return;
        }

        if (remove) {
            this.game.getUnitLifecycle().hide(unit, true);
        }

        this.game.getUnitLifecycle().kill(unit, Integer.MAX_VALUE);
    }

    /**
     * Sets invincibility for one unit of a location: the top-level elevation filter, the owner selector, the contents of a
     * unit providing space (without the elevation filter), then the unit type; the set modifier sets, the clear modifier
     * clears and the toggle modifier toggles the invincible status.
     *
     * @param context        the trigger context
     * @param units          the unit type table
     * @param tileset        the terrain tileset of the elevation filter
     * @param unit           the unit
     * @param unitType       the unit operand
     * @param selector       the owner selector
     * @param elevationFlags the location's elevation flags
     * @param checkElevation whether the elevation filter applies
     * @param modifier       the modifier
     */
    void setInvincibility(@NotNull TriggerContext context,
                          @NotNull ScrUnitTypeView units,
                          @NotNull TerrainTileset tileset,
                          @NotNull Unit unit,
                          int unitType,
                          int selector,
                          int elevationFlags, boolean checkElevation,
                          int modifier) {
        if (checkElevation && this.game.getTriggerLocations().isExcludedByElevation(tileset, unit, elevationFlags)) {
            return;
        }

        if (!TriggerPlayersUtils.isOwnerSelected(context, selector, unit.getOwnerIndex())) {
            return;
        }

        int type = unit.getUnitId();
        boolean providesSpace = TriggerLocations.providesSpace(units, this.game.getPlayerTable().getPlayerUpgrades(), unit);

        if (providesSpace) {
            for (int slot = 0; slot < units.getSpaceProvided(type); slot++) {
                Unit loaded = this.game.getUnitTransport().getLoadedUnit(unit, slot);

                if (loaded != null) {
                    this.setInvincibility(context, units, tileset, loaded, unitType, selector, elevationFlags, false, modifier);
                }
            }
        }

        if (!TriggerLocations.matchesGroupType(units, type, unitType & WORD_MASK)) {
            return;
        }

        switch (modifier & BYTE_MASK) {
            case TriggerModifiers.SET -> this.setInvincible(unit, true);
            case TriggerModifiers.CLEAR -> this.setInvincible(unit, false);
            case TriggerModifiers.TOGGLE -> this.setInvincible(unit, (unit.getStatus() & UnitStatusFlags.INVINCIBLE) == 0);
            default -> {
            }
        }
    }

    /**
     * Sets or clears the invincible status of one unit, as Set Invincibility does for each unit it selects.
     *
     * @param unit       the unit
     * @param invincible {@code true} to make the unit invincible, {@code false} to make it vulnerable
     */
    public void setInvincible(@NotNull Unit unit, boolean invincible) {
        unit.setStatus(invincible ? unit.getStatus() | UnitStatusFlags.INVINCIBLE : unit.getStatus() & ~UnitStatusFlags.INVINCIBLE);
    }

    /**
     * Disables a doodad as Set Doodad State does with the disable state: a unit that is not disabled yet receives the DisableDoodad order
     * through the empty-queue order path.
     *
     * @param unit             the doodad unit
     * @param instructionLimit the iscript budget
     */
    public void disableDoodad(@NotNull Unit unit, int instructionLimit) {
        if (!UnitOrderEligibility.isDisabled(unit)) {
            this.game.getOrders().issueUntargeted(unit, ScrOrderIds.DISABLE_DOODAD, instructionLimit);
        }
    }

    /**
     * Enables a doodad as Set Doodad State does with the enable state: a disabled unit loses its disabled status and receives the
     * EnableDoodad order through the empty-queue order path.
     *
     * @param unit             the doodad unit
     * @param instructionLimit the iscript budget
     */
    public void enableDoodad(@NotNull Unit unit, int instructionLimit) {
        if (UnitOrderEligibility.isDisabled(unit)) {
            unit.setStatus(unit.getStatus() & ~UnitStatusFlags.DISABLED);
            this.game.getOrders().issueUntargeted(unit, ScrOrderIds.ENABLE_DOODAD, instructionLimit);
        }
    }

    /**
     * Tests the filters of Move Unit: the elevation filter, removed or dying units, the owner selector, buildings
     * executing BuildingLand or BuildingLiftoff, Nuclear Missiles, Dark Swarm, mineral fields, geysers and other
     * resource containers, the trap doodads and the unit-type test.
     *
     * @param context        the trigger context
     * @param units          the unit type table
     * @param tileset        the terrain tileset of the elevation filter
     * @param unit           the unit
     * @param selector       the owner selector
     * @param unitType       the unit operand
     * @param elevationFlags the location's elevation flags
     * @return {@code true} if Move Unit moves the unit, {@code false} otherwise
     */
    boolean acceptsMoveUnit(@NotNull TriggerContext context,
                            @NotNull ScrUnitTypeView units,
                            @NotNull TerrainTileset tileset,
                            @NotNull Unit unit,
                            int selector,
                            int unitType,
                            int elevationFlags) {
        if (this.game.getTriggerLocations().isExcludedByElevation(tileset, unit, elevationFlags) || ComputerUnits.isDeadOrHidden(unit) ||
            !TriggerPlayersUtils.isOwnerSelected(context, selector, unit.getOwnerIndex())) {
            return false;
        }

        return this.canMoveUnit(unit) && TriggerLocations.matchesGroupType(units, unit.getUnitId(), unitType);
    }

    /**
     * Tests the unit filters of Move Unit: removed or dying units, buildings executing BuildingLand or BuildingLiftoff, Nuclear Missiles, Dark
     * Swarm, mineral fields, geysers and other resource containers and the trap doodads are not moved.
     *
     * @param unit the unit
     * @return {@code true} if Move Unit moves the unit ({@link #moveUnit}), {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    public boolean canMoveUnit(@NotNull Unit unit) {
        if (ComputerUnits.isDeadOrHidden(unit)) {
            return false;
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int type = unit.getUnitId();

        if (UnitQueries.isBuilding(units, type) &&
            (unit.getPrimaryOrder() == ScrOrderIds.BUILDING_LAND || unit.getPrimaryOrder() == ScrOrderIds.BUILDING_LIFTOFF)) {
            return false;
        }

        return units.getBaseType(type) != ScrUnitIds.TERRAN_NUCLEAR_MISSILE && type != ScrUnitIds.SPELL_DARK_SWARM &&
               !units.isMineralField(type) && type != ScrUnitIds.RESOURCE_VESPENE_GEYSER &&
               (units.getFlags(type) & UnitTypeFlags.RESOURCE_CONTAINER) == 0 &&
               (type < ScrUnitIds.SPECIAL_FLOOR_MISSILE_TRAP || type > ScrUnitIds.SPECIAL_RIGHT_WALL_FLAME_TRAP);
    }

    /**
     * Moves one unit as Move Unit does: a grounded building moves through {@link UnitBuildingMove}, another unit is relocated and, when it was
     * moved and is completed, finishes its order, so a pending queue activates or it returns to its computer or idle order. The unit is not
     * tested ({@link #canMoveUnit}).
     *
     * @param unit             the unit
     * @param targetX          the horizontal center to move to
     * @param targetY          the vertical center to move to
     * @param instructionLimit the order and script budget
     */
    public void moveUnit(@NotNull Unit unit, int targetX, int targetY, int instructionLimit) {
        if (UnitQueries.isGroundedBuilding(unit)) {
            this.game.getUnitBuildingMove().move(unit, targetX, targetY, instructionLimit);

            return;
        }

        boolean moved = this.game.getUnitRelocation().relocate(unit, targetX, targetY);

        // A pending queue activates, otherwise the unit returns to its computer or idle order.
        if (moved && (unit.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
            this.game.getOrders().finishOrder(unit, instructionLimit);
        }
    }

    /**
     * Creates a {@link Unit} instance of a listed type below {@code 228}, clamps its center inside the map and completes it at
     * a free position. Kills an unplaceable unit. A failed building site and an unplaceable unit show the warning
     * {@code errCANTPLACE_sdd} ({@link UnitStorageConstructor#notifyUnplaceable}).
     * <p>
     * The creep of a creep building ({@link CreepField#addBuilding}) is added before the placement, so an unplaceable unit
     * leaves it. A placed unit of any type is then finished in this order: a creep spreader provides creep again as a
     * completed unit, an add-on becomes neutral, a grounded Terran building connects an add-on, the computer AI adopts the
     * unit, the building power refresh is requested, and resource types receive their resources.
     *
     * @param unitType         the {@code units.dat} type
     * @param positionX        the center X
     * @param positionY        the center Y
     * @param owner            the owner
     * @param instructionLimit the iscript budget
     * @return the created {@link Unit} instance, or {@code null} if the type is not listed, the building site fails, the
     * creation fails or the unit cannot be placed
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the creation context, the accounting, the owner or the creep field is missing
     */
    public @Nullable Unit createUnit(int unitType, int positionX, int positionY, int owner, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        if (unitType >= ScrUnitIds.NONE || (units.getAvailabilityFlags(unitType) & UnitAvailabilityFlags.UNIT_LISTING) == 0) {
            return null;
        }

        UnitCreationContext creation = this.game.getUnitSystem().getUnitCreationContext();
        UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();
        Player player = this.game.getPlayerTable().getPlayer(owner);
        CreepField creep = this.game.getCreepField();

        if (creation == null || accounting == null || player == null || creep == null) {
            throw Exceptions.newUnsupportedOperationException("Trigger unit creation requires the established creation context and creep field.");
        }

        int horizontal = UnitPositioning.clampCoordinate(positionX,
                                                         units.getDimensionLeft(unitType), units.getDimensionRight(unitType),
                                                         this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE,
                                                         0);
        int vertical = UnitPositioning.clampCoordinate(positionY,
                                                       units.getDimensionUp(unitType), units.getDimensionDown(unitType),
                                                       this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE,
                                                       EXCLUDED_BOTTOM_EXTENT);
        boolean building = (units.getFlags(unitType) & UnitTypeFlags.BUILDING) != 0;

        if (building) {
            // Snap the placement box to the tile grid inside the map and validate the site without a builder.
            int width = units.getPlacementWidth(unitType);
            int height = units.getPlacementHeight(unitType);
            int left = Math.max(0, Math.min(horizontal - width / POSITION_CENTER_DIVISOR,
                                            this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE - 1));
            int top = Math.max(0, Math.min(vertical - height / POSITION_CENTER_DIVISOR,
                                           this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE - 1));

            horizontal = left / TerrainTileset.TILE_SIZE * TerrainTileset.TILE_SIZE + width / POSITION_CENTER_DIVISOR;
            vertical   = top / TerrainTileset.TILE_SIZE * TerrainTileset.TILE_SIZE + height / POSITION_CENTER_DIVISOR;

            if (!this.game.getUnitPlacement().canPlaceTriggerBuilding(owner, unitType, horizontal, vertical)) {
                // The warning names the requested type at the snapped placement center.
                this.game.getUnitStorageConstructor().notifyUnplaceable(unitType, horizontal, vertical);

                return null;
            }
        }

        int ownerByte = owner & BYTE_MASK;
        Unit unit = this.game.getUnitStorageConstructor().create(creation,
                                                                 accounting,
                                                                 unitType,
                                                                 horizontal, vertical,
                                                                 owner,
                                                                 ownerByte | ownerByte << BYTE_BITS,
                                                                 instructionLimit);

        if (unit == null) {
            // The owner sees the pending creation message.
            this.game.getUnitStorageConstructor().notifyCreationFailure(ownerByte);

            return null;
        }

        // The creep comes before the placement test, so an unplaceable unit leaves it.
        creep.addBuilding(this.game, unit);

        boolean placed = this.game.getUnitStorageConstructor().tryComplete(creation, unit, player.getType(), instructionLimit);

        if (!placed) {
            // After the owner's pending message, the warning names the requested type at the unit's position; the unit is
            // then killed.
            this.game.getUnitStorageConstructor().notifyUnplaceable(unitType, unit);
            this.game.getUnitLifecycle().kill(unit, instructionLimit);

            return null;
        }

        this.finishCreatedUnit(units, creep, unit, instructionLimit);

        return unit;
    }

    /**
     * Finishes a placed created unit of any type: a creep spreader provides creep again as a completed unit, an add-on becomes
     * neutral, a grounded Terran building connects an add-on, the computer AI adopts the unit, the building power refresh is
     * requested, and a geyser or a completed refinery receives its gas and a mineral field its minerals.
     *
     * @param units            the unit type table
     * @param creep            the creep field
     * @param unit             the placed unit
     * @param instructionLimit the iscript budget
     * @throws IllegalStateException if the unit has no sprite
     */
    private void finishCreatedUnit(@NotNull ScrUnitTypeView units, @NotNull CreepField creep, @NotNull Unit unit, int instructionLimit) {
        int type = unit.getUnitId();

        if (CreepField.spreadsCreep(type, true)) {
            Sprite sprite = unit.requireSprite();

            creep.addProvider(this.game, type, (short) sprite.getX(), (short) sprite.getY(), (unit.getStatus() & UnitStatusFlags.COMPLETED) != 0);
        }

        if ((units.getFlags(type) & UnitTypeFlags.ADDON) != 0) {
            this.game.getUnitOwnership().setOwner(unit, PlayerTable.NEUTRAL_PLAYER_ID, false, instructionLimit);
        }

        if (UnitQueries.isGroundedBuilding(unit) && UnitRacesUtils.getDisplayRace(units, type) == ScrRaceIds.TERRAN) {
            this.game.getUnitAddons().findAndConnect(unit, instructionLimit);
        }

        this.game.getComputerUnits().notifyAdopted(unit, instructionLimit);
        this.game.requestBuildingPowerRefresh();

        boolean completedRefinery = (unit.getStatus() & UnitStatusFlags.COMPLETED) != 0 && units.isGasStructure(type);

        if (type == ScrUnitIds.RESOURCE_VESPENE_GEYSER || completedRefinery) {
            unit.setResources(CREATED_GAS_RESOURCES);
        } else if (units.isMineralField(type)) {
            PreplacedUnitLoaderUtils.setResources(this.game, unit, CREATED_MINERAL_RESOURCES, instructionLimit);
        }
    }

    /**
     * Applies Order to one unit after the elevation and owner tests: a unit the unit operand does not match is skipped, any other unit receives
     * the order as {@link #issueUnitOrder(Unit, int, int, int, int)} gives it.
     *
     * @param context  the trigger context
     * @param units    the unit type table
     * @param unit     the unit
     * @param unitType the unit operand
     * @param order    the order
     * @param targetX  the target X
     * @param targetY  the target Y
     */
    void issueUnitOrder(@NotNull TriggerContext context,
                        @NotNull ScrUnitTypeView units,
                        @NotNull Unit unit,
                        int unitType,
                        int order,
                        int targetX, int targetY) {
        if (TriggerLocations.matchesGroupType(units, unit.getUnitId(), unitType)) {
            this.issueUnitOrder(unit, order, targetX, targetY, context.instructionLimit);
        }
    }

    /**
     * Gives one unit an order as the Order action does: buildings executing BuildingLand or BuildingLiftoff, units that cannot move, Nuclear
     * Missiles, Interceptors, Scarabs and units of rescuable players are skipped. A burrowed unit unburrows and queues the order without
     * interruption; another unit receives it when it can receive the order. Move and Attack Move also move a guard computer record's position.
     *
     * @param unit             the unit
     * @param order            the orders.dat entry
     * @param targetX          the target X
     * @param targetY          the target Y
     * @param instructionLimit the order and script budget
     * @throws NullPointerException if the world was built without game data
     */
    public void issueUnitOrder(@NotNull Unit unit, int order, int targetX, int targetY, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int type = unit.getUnitId();

        if ((units.getFlags(type) & UnitTypeFlags.BUILDING) != 0 &&
            (unit.getPrimaryOrder() == ScrOrderIds.BUILDING_LAND || unit.getPrimaryOrder() == ScrOrderIds.BUILDING_LIFTOFF)) {
            return;
        }

        int baseType = units.getBaseType(type);

        if ((unit.getStatus() & UnitStatusFlags.CAN_MOVE) == 0 || baseType == ScrUnitIds.TERRAN_NUCLEAR_MISSILE ||
            baseType == ScrUnitIds.PROTOSS_INTERCEPTOR || baseType == ScrUnitIds.PROTOSS_SCARAB) {
            return;
        }

        Player owner = this.game.getPlayerTable().getPlayer(unit.getOwnerIndex());
        int ownerType = owner == null ? 0 : owner.getType() & BYTE_MASK;

        if (ownerType == PlayerTypes.RESCUE_PASSIVE || ownerType == PlayerTypes.RESCUE_ACTIVE) {
            return;
        }

        if ((unit.getStatus() & UnitStatusFlags.BURROWED) != 0) {
            this.game.getUnitBurrowing().unburrowUnit(unit, instructionLimit);
            this.game.getOrders().queueReplacingSameOrders(unit, order, null, targetX, targetY, ScrUnitIds.NONE);
        } else {
            if (!this.game.getUnitOrderEligibility().canReceiveOrder(unit, order, unit.getOwnerIndex())) {
                return;
            }

            this.game.getOrders().issue(unit, order, null, targetX, targetY, ScrUnitIds.NONE, false, instructionLimit);
        }

        ComputerUnitRecord record = unit.getComputerRecord();

        if (record != null && (order == ScrOrderIds.MOVE || order == ScrOrderIds.ATTACK_MOVE) &&
            record.getKind() == ComputerUnitRecord.KIND_GUARD) {
            record.guardX = targetX & WORD_MASK;
            record.guardY = targetY & WORD_MASK;
        }
    }

    /**
     * Applies Give Units to Player to one unit: the current player selector names the current player, and the unit is given as
     * {@link UnitOwnership#giveUnit} gives it.
     *
     * @param context      the trigger context
     * @param unit         the unit
     * @param ownerOperand the receiving player operand
     * @return {@code true} if the unit was given, {@code false} otherwise
     */
    boolean giveUnit(@NotNull TriggerContext context, @NotNull Unit unit, int ownerOperand) {
        int owner = ownerOperand == TriggerPlayerSelectors.CURRENT_PLAYER ? context.currentPlayer : ownerOperand;

        return this.game.getUnitOwnership().giveUnit(unit, owner, context.instructionLimit);
    }

    /**
     * Applies Modify Unit Hit Points to one unit: the hit points become the unsigned {@code 32}-bit percentage of the
     * maximum (at least one eighth-bit unit); hallucinations and Broodlings then receive their default removal timer.
     *
     * @param unit             the unit
     * @param percent          the percentage
     * @param instructionLimit the iscript budget
     * @return always {@code true}, since every unit counts
     * @throws NullPointerException if the world was built without game data
     */
    public boolean modifyHitPoints(@NotNull Unit unit, int percent, int instructionLimit) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int hitPoints = Integer.divideUnsigned(units.getHitPoints(unit.getUnitId()) * percent, PERCENT);

        this.game.getUnitHealth().setHitPoints(unit, hitPoints == 0 ? 1 : hitPoints, instructionLimit);

        if ((unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0 || units.getBaseType(unit.getUnitId()) == ScrUnitIds.ZERG_BROODLING) {
            UnitHallucination.setRemovalTimer(unit, (unit.getStatus() & UnitStatusFlags.HALLUCINATION) != 0
                                                    ? UnitHallucination.HALLUCINATION_REMOVAL_TIMER
                                                    : UnitHallucination.BROODLING_REMOVAL_TIMER);
        }

        return true;
    }

    /**
     * Applies Modify Unit Shield Points to one unit with shields: the shields become the unsigned percentage of the maximum, limited to it.
     *
     * @param unit    the unit
     * @param percent the percentage
     * @return {@code true} if the unit has shields, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    public boolean modifyShields(@NotNull Unit unit, int percent) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int type = unit.getUnitId();

        if (units.getShieldEnabled(type) == 0) {
            return false;
        }

        int maximum = units.getShieldAmount(type);
        int shields = Integer.divideUnsigned(maximum * percent, PERCENT);

        unit.setShieldPoints(Math.min(shields, maximum) << FIXED_POINT_FRACTION_BITS);

        return true;
    }

    /**
     * Applies Modify Unit Energy to one spellcaster that is not a hallucination: the energy becomes the unsigned
     * percentage of the whole maximum, truncated to a BYTE and limited to the maximum.
     *
     * @param unit    the unit
     * @param percent the percentage
     * @return {@code true} if the unit is a spellcaster and not a hallucination, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    public boolean modifyEnergy(@NotNull Unit unit, int percent) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

        if (!UnitTypeTraitsUtils.isSpellcaster(units, unit)) {
            return false;
        }

        int maximum = this.game.getUnitInitializer().getMaximumEnergy(unit, units) >> FIXED_POINT_FRACTION_BITS;
        int energy = Integer.divideUnsigned(maximum * percent, PERCENT) & BYTE_MASK;

        unit.setEnergy(Math.min(energy, maximum) << FIXED_POINT_FRACTION_BITS);

        return true;
    }

    /**
     * Applies Modify Unit Resource Amount to one resource container: the resource WORD is stored and mineral fields
     * refresh their depletion animation.
     *
     * @param unit             the unit
     * @param amount           the resource amount (its low WORD is used)
     * @param instructionLimit the iscript budget
     * @return {@code true} if the unit is a resource container, {@code false} otherwise
     * @throws NullPointerException if the world was built without game data
     */
    public boolean modifyResources(@NotNull Unit unit, int amount, int instructionLimit) {
        if ((this.game.requireGameData().getUnitTypes().getFlags(unit.getUnitId()) & UnitTypeFlags.RESOURCE_CONTAINER) == 0) {
            return false;
        }

        PreplacedUnitLoaderUtils.setResources(this.game, unit, amount & WORD_MASK, instructionLimit);

        return true;
    }

    /**
     * Applies Modify Unit Hanger Count to a Carrier or Reaver: up to {@code count} subordinates (Interceptors or Scarabs)
     * are added while the requirement test accepts another one (represented by {@link UnitHangar#canBuildFighter}); each
     * is constructed at the parent, completed, linked to its parent and hidden in the inside hangar list.
     *
     * @param unit             the unit
     * @param count            the number of subordinates to add
     * @param instructionLimit the iscript budget
     * @return {@code true} if at least one creation attempt was made, {@code false} otherwise
     * @throws UnsupportedOperationException if the accounting, the creation context or the owner is missing, or the unit pool is not initialized
     * @throws IllegalStateException         if the unit has no sprite
     */
    public boolean modifyHangar(@NotNull Unit unit, int count, int instructionLimit) {
        int type = unit.getUnitId();

        if (!this.game.requireGameData().getUnitTypes().hasHangar(type)) {
            return false;
        }

        int subordinate = this.game.requireGameData().getUnitTypes().isCarrier(type) ? ScrUnitIds.PROTOSS_INTERCEPTOR : ScrUnitIds.PROTOSS_SCARAB;
        UnitAccounting accounting = this.game.getPlayerTable().getUnitAccounting();
        UnitCreationContext creation = this.game.getUnitSystem().getUnitCreationContext();
        Player owner = this.game.getPlayerTable().getPlayer(unit.getOwnerIndex());

        if (accounting == null || creation == null || owner == null) {
            throw Exceptions.newUnsupportedOperationException("Modify Unit Hanger Count requires accounting and the creation context.");
        }

        int added = 0;

        while (Integer.compareUnsigned(added, count) < 0) {
            if (!this.game.getUnitHangar().canBuildFighter(unit, subordinate)) {
                break;
            }

            Sprite sprite = unit.requireSprite();
            Unit fighter = this.game.getUnitStorageConstructor().create(creation,
                                                                        accounting,
                                                                        subordinate,
                                                                        sprite.getX(), sprite.getY(),
                                                                        unit.getOwnerIndex(),
                                                                        this.game.getUnitSystem().requireUnitPool().getCreationTag(unit.getIndex()),
                                                                        instructionLimit);

            if (fighter != null) {
                this.game.getUnitCompletion().initializeBeforePlacement(creation, fighter, instructionLimit);
                this.game.getUnitCompletion().completeAfterPlacement(creation, fighter, owner.getType(), instructionLimit);
                fighter.setHangarParent(unit);
                this.game.getUnitHangar().storeInside(unit, fighter);
            }

            added++;
        }

        return added != 0;
    }

    /**
     * Drops the targets of every unit of a player whose weapon-targeting order targets a unit of a player it is now
     * allied with, unless the target is still an enemy.
     *
     * @param owner the player whose units drop their targets
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    void clearAlliedOrderTargets(int owner) {
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        PlayerRelations relations = this.game.getPlayerTable().requirePlayerRelations();

        for (int index = pool.getFirstOwnerIndex(owner); index != 0; index = pool.getNextOwnerIndex(index)) {
            Unit unit = pool.getUnit(index);
            Unit target = unit.getOrderTargetUnit();

            if (target == null || this.game.requireGameData().getOrderTypes().getUseWeaponTargeting(unit.getPrimaryOrder()) == 0) {
                continue;
            }

            if (relations.getAlliance(owner, target.getOwnerIndex()) != PlayerRelations.ENEMY && !this.game.getUnitTargeting().isEnemy(unit, target)) {
                unit.setOrderTargetUnit(null);
            }
        }
    }
}
