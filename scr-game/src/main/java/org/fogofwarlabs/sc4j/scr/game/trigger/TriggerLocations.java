package org.fogofwarlabs.sc4j.scr.game.trigger;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUpgradeIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.TriggerUnitGroups;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerUpgrades;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Runs the location handlers of the trigger actions and conditions: unit searches in locations, location moves and the
 * elevation filter.
 */
public final class TriggerLocations {
    /**
     * The location elevation flag of a ground unit at height {@code 0}; heights {@code 1} and {@code 2} follow.
     */
    static final int GROUND_ELEVATION_FLAG = 0x1;
    /**
     * The location elevation flag of an air unit over height {@code 0}; heights {@code 1} and {@code 2} follow.
     */
    static final int AIR_ELEVATION_FLAG = 0x8;
    private static final int WORD_MASK = 0xFFFF;

    /**
     * Returns the units whose boxes intersect a location, its bounds taken as unsigned WORDs.
     *
     * @param index    the unit spatial index
     * @param units    the unit type table
     * @param location the location
     * @return the pool indices of the units
     */
    static int @NotNull [] findUnitsIn(@NotNull UnitSpatialIndex index, @NotNull ScrUnitTypeView units, @NotNull TriggerLocation location) {
        return index.findAllInRectangle(units,
                                        location.getLeft() & WORD_MASK, location.getTop() & WORD_MASK,
                                        location.getRight() & WORD_MASK, location.getBottom() & WORD_MASK);
    }

    /**
     * Applies the unit-type test of the trigger unit operands (the location callbacks, Kill Unit, Remove Unit and Set
     * Invincibility): the unit groups any unit, men, buildings and factories (by their group flags), other values the
     * exact type.
     *
     * @param units    the unit type table
     * @param type     the unit's type
     * @param unitType the unit type or unit group of the trigger
     * @return {@code true} if the type matches, {@code false} otherwise
     */
    static boolean matchesGroupType(@NotNull ScrUnitTypeView units, int type, int unitType) {
        int groupFlags = units.getGroupFlags(type);

        return switch (unitType) {
            case TriggerUnitGroups.ANY_UNIT -> true;
            case TriggerUnitGroups.MEN -> (groupFlags & UnitGroupFlags.MEN) != 0;
            case TriggerUnitGroups.BUILDINGS -> (groupFlags & UnitGroupFlags.BUILDING) != 0;
            case TriggerUnitGroups.FACTORIES -> (groupFlags & UnitGroupFlags.FACTORY) != 0;
            default -> type == unitType;
        };
    }

    /**
     * Tests whether a unit provides space whose contents the location tests also visit: a unit that is no hallucination
     * and provides transport space, an Overlord only with Ventral Sacs. Without player upgrade levels an Overlord provides
     * none.
     *
     * @param units    the unit type table
     * @param upgrades the player upgrade levels, or {@code null} when they are not loaded
     * @param unit     the unit
     * @return {@code true} if the unit provides space, {@code false} otherwise
     */
    public static boolean providesSpace(@NotNull ScrUnitTypeView units, @Nullable PlayerUpgrades upgrades, @NotNull Unit unit) {
        int type = unit.getUnitId();

        return (unit.getStatus() & UnitStatusFlags.HALLUCINATION) == 0 && units.getSpaceProvided(type) != 0 &&
               (units.getBaseType(type) != ScrUnitIds.ZERG_OVERLORD ||
                upgrades != null && upgrades.getLevel(unit.getOwnerIndex(), ScrUpgradeIds.VENTRAL_SACS) != 0);
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link TriggerLocations} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public TriggerLocations(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Applies the elevation filter: air units test their air flag shifted by the ground height, ground units their ground
     * flag, in the location's elevation flags.
     *
     * @param tileset        the terrain tileset
     * @param unit           the unit
     * @param elevationFlags the location's elevation flags
     * @return {@code true} if the location excludes the unit's elevation, {@code false} otherwise
     * @throws IllegalStateException if the unit has no sprite
     */
    boolean isExcludedByElevation(@NotNull TerrainTileset tileset, @NotNull Unit unit, int elevationFlags) {
        boolean flying = (unit.getStatus() & UnitStatusFlags.FLYING) != 0;
        Sprite sprite = unit.requireSprite();
        int vertical = (short) sprite.getY();
        int horizontal = (short) sprite.getX();
        int height = this.game.getMap().getTerrainHeight(tileset, horizontal, vertical);
        int excluded = flying ? AIR_ELEVATION_FLAG << height : GROUND_ELEVATION_FLAG << height;

        return (elevationFlags & excluded) != 0;
    }

    /**
     * Moves a location: the rectangle is offset so its center is the given point, a negative left or top edge moves the
     * rectangle to zero keeping its extent, and a right or bottom edge at or beyond the map pixel extent moves it back to
     * end at the last pixel. Move Location moves its destination location here.
     *
     * @param location the location, one of the game's trigger locations ({@link TriggerContext#getLocation})
     * @param centerX  the new horizontal center
     * @param centerY  the new vertical center
     */
    public void moveLocation(@NotNull TriggerLocation location, int centerX, int centerY) {
        int left = location.getLeft();
        int top = location.getTop();
        int right = location.getRight();
        int bottom = location.getBottom();
        int offsetX = centerX - location.getCenterX();
        int offsetY = centerY - location.getCenterY();

        left += offsetX;
        right += offsetX;
        top += offsetY;
        bottom += offsetY;

        if (left < 0) {
            right -= left;
            left = 0;
        }

        if (top < 0) {
            bottom -= top;
            top = 0;
        }

        int mapWidth = (this.game.getMap().getWidth() << TerrainTileset.TILE_SHIFT) & WORD_MASK;
        int mapHeight = (this.game.getMap().getHeight() << TerrainTileset.TILE_SHIFT) & WORD_MASK;

        if (right >= mapWidth) {
            left  = left - 1 + mapWidth - right;
            right = mapWidth - 1;
        }

        if (bottom >= mapHeight) {
            top    = top - 1 + mapHeight - bottom;
            bottom = mapHeight - 1;
        }

        location.setBounds(left, top, right, bottom);
    }

    /**
     * Finds the first unit of a location's rectangle query that the location search accepts.
     *
     * @param context  the trigger context
     * @param unitType the unit type or unit group
     * @param selector the owner selector
     * @param location the location index
     * @return the unit, or {@code null} if none matches
     * @throws UnsupportedOperationException if the game has no unit spatial index or the unit pool is not initialized
     * @throws NullPointerException          if the world was built without game data
     * @throws IllegalStateException         if the terrain tileset cannot be read
     */
    @Nullable Unit findLocationUnit(@NotNull TriggerContext context, int unitType, int selector, int location) {
        UnitSpatialIndex spatial = this.game.getUnitSystem().getUnitSpatialIndex();

        if (spatial == null) {
            throw Exceptions.newUnsupportedOperationException("Location unit searches require the unit spatial index.");
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        TerrainTileset tileset;

        try {
            tileset = this.game.requireGameData().getTerrainTileset(this.game.getMap().getTileset());
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Location searches require the terrain miniature flags.");
        }

        int[] found = findUnitsIn(spatial, units, context.locations[location]);

        for (int poolIndex : found) {
            Unit unit = this.game.getUnitSystem().requireUnitPool().getUnit(poolIndex);

            if (this.matchesLocationSearch(context, units, tileset, unit, unitType, selector,
                                           context.locations[location].getElevationFlags(), true)) {
                return unit;
            }
        }

        return null;
    }

    /**
     * Applies the location search callback: the elevation filter at the top level, a carried powerup, the owner
     * selector, the contents of a unit providing space, then the unit-type test.
     *
     * @param context        the trigger context
     * @param units          the unit type table
     * @param tileset        the terrain tileset
     * @param unit           the unit
     * @param unitType       the unit type or unit group
     * @param selector       the owner selector
     * @param elevationFlags the location's elevation flags
     * @param checkElevation {@code true} to apply the elevation filter
     * @return {@code true} if the unit, its powerup or a loaded unit matches, {@code false} otherwise
     */
    private boolean matchesLocationSearch(@NotNull TriggerContext context,
                                          @NotNull ScrUnitTypeView units,
                                          @NotNull TerrainTileset tileset,
                                          @NotNull Unit unit,
                                          int unitType,
                                          int selector,
                                          int elevationFlags, boolean checkElevation) {
        if (checkElevation && this.isExcludedByElevation(tileset, unit, elevationFlags)) {
            return false;
        }

        int type = unit.getUnitId();

        // A worker's carried powerup is searched first, without the elevation filter.
        Unit carried = (units.getFlags(type) & UnitTypeFlags.WORKER) != 0 ? unit.getCarriedPowerup() : null;

        if (carried != null && this.matchesLocationSearch(context, units, tileset, carried, unitType, selector, elevationFlags, false)) {
            return true;
        }

        if (!TriggerPlayersUtils.isOwnerSelected(context, selector, unit.getOwnerIndex())) {
            return false;
        }

        boolean providesSpace = providesSpace(units, this.game.getPlayerTable().getPlayerUpgrades(), unit);

        if (providesSpace) {
            for (int slot = 0; slot < units.getSpaceProvided(type); slot++) {
                Unit loaded = this.game.getUnitTransport().getLoadedUnit(unit, slot);

                if (loaded != null && this.matchesLocationSearch(context, units, tileset, loaded, unitType, selector, elevationFlags, false)) {
                    return true;
                }
            }
        }

        return matchesGroupType(units, type, unitType & WORD_MASK);
    }

    /**
     * Returns the terrain tileset of the game's map.
     *
     * @return the terrain tileset
     * @throws NullPointerException  if the world was built without game data
     * @throws IllegalStateException if the terrain tileset cannot be read
     */
    @NotNull TerrainTileset requireTileset() {
        try {
            return this.game.requireGameData().getTerrainTileset(this.game.getMap().getTileset());
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "Location actions require the terrain miniature flags.");
        }
    }

    /**
     * Returns the units of the rectangle query over a location's low WORD bounds.
     *
     * @param context  the trigger context
     * @param location the location index
     * @return the pool indices of the units
     * @throws UnsupportedOperationException if the game has no unit spatial index
     * @throws NullPointerException          if the world was built without game data
     */
    int @NotNull [] findLocationUnits(@NotNull TriggerContext context, int location) {
        UnitSpatialIndex spatial = this.game.getUnitSystem().getUnitSpatialIndex();

        if (spatial == null) {
            throw Exceptions.newUnsupportedOperationException("Location actions require the unit spatial index.");
        }

        return findUnitsIn(spatial, this.game.requireGameData().getUnitTypes(), context.locations[location]);
    }
}
