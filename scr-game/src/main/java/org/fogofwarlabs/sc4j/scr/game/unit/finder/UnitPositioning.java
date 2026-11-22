package org.fogofwarlabs.sc4j.scr.game.unit.finder;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.movement.MovementFlags;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.map.ScrTerrainGrid;
import org.fogofwarlabs.sc4j.scr.game.order.OrderPool;
import org.fogofwarlabs.sc4j.scr.game.path.PathPool;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.*;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitInitializer;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;

/**
 * Places and moves units: the free-position search for a unit being placed, relocation with its movement destination,
 * the terrain walkability tests of a placement, and the building footprint occupancy of the map tiles.
 * <p>
 * These are the generic placement mechanics; the building placement rules are {@link org.fogofwarlabs.sc4j.scr.game.production.UnitPlacement}.
 */
public final class UnitPositioning {
    /**
     * The number of coordinates of a placement result: x and y.
     */
    public static final int POSITION_COORDINATES = 2;
    /**
     * The index of the x coordinate in a position pair of {@link #POSITION_COORDINATES} coordinates.
     */
    public static final int X_INDEX = 0;
    /**
     * The index of the y coordinate in a position pair.
     */
    public static final int Y_INDEX = 1;
    /**
     * The height in pixels of the band at the bottom of the map that no unit is placed or moved into.
     */
    public static final int MAP_BOTTOM_MARGIN = 32;
    /**
     * The movement destination countdown a new destination sets.
     */
    public static final int DESTINATION_COUNTDOWN = 15;

    /**
     * The command error message key of a blocked placement.
     */
    private static final byte @NotNull [] EXIT_BLOCKED = "strEXITBLOCKED".getBytes(StandardCharsets.US_ASCII);
    /**
     * The half size in pixels of the default placement rectangle around the requested position.
     */
    private static final int DEFAULT_SEARCH_HALF_SIZE = 128;
    /**
     * The pixel grid of the placement search: its first step and the alignment of its perimeter walk.
     */
    private static final int SEARCH_GRID = 8;
    /**
     * The mask of the offset of a coordinate inside its search grid cell.
     */
    private static final int SEARCH_GRID_MASK = SEARCH_GRID - 1;
    /**
     * The pixels added to the combined extents of a blocker and the incoming unit for the first step.
     */
    private static final int BLOCKER_STEP_CLEARANCE = 2;
    /**
     * The growth in pixels of the search step from one ring to the next.
     */
    private static final int SEARCH_STEP_GROWTH = 16;
    /**
     * The number of distinct WORD values; a search that runs longer repeats its WORD coordinates.
     */
    private static final int WORD_RANGE = 0x10000;
    /**
     * The number of search rings after which the WORD steps repeat.
     */
    private static final int SEARCH_RING_LIMIT = WORD_RANGE / SEARCH_STEP_GROWTH;

    /**
     * The bottom side, walked to the right; the sides follow in walk order.
     */
    private static final int SIDE_BOTTOM = 0;
    /**
     * The right side, walked upwards.
     */
    private static final int SIDE_RIGHT = 1;
    /**
     * The top side, walked to the left.
     */
    private static final int SIDE_TOP = 2;
    /**
     * The left side, walked downwards.
     */
    private static final int SIDE_LEFT = 3;
    /**
     * The number of sides of the perimeter walk.
     */
    private static final int SIDE_COUNT = 4;
    /**
     * The mask of an unsigned WORD coordinate.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The divisor that centers a footprint on its building position.
     */
    private static final int FOOTPRINT_CENTER_DIVISOR = 2;
    /**
     * The factor from a search step to the size of its search rectangle; a rectangle wider than this factor times the
     * unit width starts its bottom walk one unit width in.
     */
    private static final int SEARCH_SIZE_FACTOR = 2;
    /**
     * The fraction bits of the fixed-point position.
     */
    private static final int FIXED_POINT_FRACTION_BITS = 8;
    /**
     * The ground path distance of a destination in another ground component.
     */
    private static final int DISCONNECTED_GROUND_DISTANCE = 0x7FFF;
    /**
     * The ground path distance of a destination the region search finds no route to.
     */
    private static final int NO_GROUND_ROUTE_DISTANCE = 0x7FFE;

    /**
     * Clamps a coordinate so that a unit with the given extents stays inside the map on one axis.
     *
     * @param coordinate     the coordinate, read as a signed WORD
     * @param minimumExtent  the unit's extent toward the lower coordinates
     * @param maximumExtent  the unit's extent toward the higher coordinates
     * @param mapExtent      the map size in pixels on this axis
     * @param excludedExtent the pixels excluded at the high end of the axis ({@link #MAP_BOTTOM_MARGIN} vertically, {@code 0} horizontally)
     * @return the clamped coordinate as an unsigned WORD
     */
    public static int clampCoordinate(int coordinate, int minimumExtent, int maximumExtent, int mapExtent, int excludedExtent) {
        if ((short) coordinate < (short) minimumExtent) {
            return minimumExtent & WORD_MASK;
        }

        int limit = (mapExtent & WORD_MASK) - (short) maximumExtent - excludedExtent;

        return (short) coordinate >= limit ? (limit - 1) & WORD_MASK : coordinate & WORD_MASK;
    }


    private final @NotNull Game game;

    /**
     * Creates a {@link UnitPositioning} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public UnitPositioning(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Tests whether a unit's destination shares its ground component. A unit without the ground-elevation path flag
     * accepts the destination without reading its sprite or terrain contours.
     *
     * @param unit      the moving unit
     * @param positionX the destination horizontal WORD
     * @param positionY the destination vertical WORD
     * @return {@code true} if the flag is clear or the positions share a component, {@code false} otherwise
     * @throws UnsupportedOperationException if terrain regions are unavailable
     */
    public boolean isGroundReachable(@NotNull Unit unit, int positionX, int positionY) {
        if ((unit.getPathCollisionFlags() & UnitInitializer.GROUND_ELEVATION_PATH_FLAG) == 0) {
            return true;
        }

        TerrainContours terrain = this.game.getPathSystem().getTerrainContours();

        if (terrain == null) {
            throw Exceptions.newUnsupportedOperationException("Ground reachability requires terrain regions.");
        }

        return unit.sharesTerrainComponent(terrain.getRegions(), (short) positionX, (short) positionY);
    }

    /**
     * Returns straight distance for a unit without the ground-elevation path flag, otherwise straight distance inside
     * one region or for a short route, or the sum of consecutive region-center distances along a longer route.
     * <p>
     * The unit's position and orders are not changed by this query.
     *
     * @param unit      the moving unit
     * @param positionX the destination horizontal WORD
     * @param positionY the destination vertical WORD
     * @return the distance DWORD, {@link #DISCONNECTED_GROUND_DISTANCE} across components or {@link #NO_GROUND_ROUTE_DISTANCE} without a route
     * @throws UnsupportedOperationException if the unit has no sprite or terrain regions are unavailable
     */
    public int getGroundPathDistance(@NotNull Unit unit, int positionX, int positionY) {
        int targetHorizontal = (short) positionX;
        int targetVertical = (short) positionY;

        if ((unit.getPathCollisionFlags() & UnitInitializer.GROUND_ELEVATION_PATH_FLAG) == 0) {
            Sprite sprite = unit.getSpriteOrNull();

            if (sprite == null) {
                throw Exceptions.newUnsupportedOperationException("Ground path distance requires a sprite.");
            }

            int unitVertical = (short) sprite.getY();
            int unitHorizontal = (short) sprite.getX();

            return RegionPathSearch.calculateDistance(unitHorizontal, targetHorizontal, unitVertical, targetVertical);
        }

        TerrainContours terrain = this.game.getPathSystem().getTerrainContours();

        if (terrain == null) {
            throw Exceptions.newUnsupportedOperationException("Ground path distance requires terrain regions.");
        }

        TerrainRegions regions = terrain.getRegions();

        if (!unit.sharesTerrainComponent(regions, targetHorizontal, targetVertical)) {
            return DISCONNECTED_GROUND_DISTANCE;
        }

        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newUnsupportedOperationException("Ground path distance requires a sprite.");
        }

        int unitHorizontal = (short) sprite.getX();
        int unitVertical = (short) sprite.getY();
        int sourceRegion = TerrainRegionLookupUtils.lookup(regions, unitHorizontal, unitVertical);
        int destinationRegion = TerrainRegionLookupUtils.lookup(regions, targetHorizontal, targetVertical);

        if (sourceRegion == destinationRegion) {
            int directVertical = (short) sprite.getY();
            int directHorizontal = (short) sprite.getX();

            return RegionPathSearch.calculateDistance(directHorizontal, targetHorizontal, directVertical, targetVertical);
        }

        RegionPathSearch search = this.game.getPathSystem().getResourcePathSearch();

        if (!search.search(sourceRegion, unitHorizontal, unitVertical, destinationRegion, targetHorizontal, targetVertical)) {
            return NO_GROUND_ROUTE_DISTANCE;
        }

        int routeCount = (short) search.getRouteCount();

        if (routeCount <= RegionPathSearch.DIRECT_ROUTE_COUNT) {
            int directVertical = (short) sprite.getY();
            int directHorizontal = (short) sprite.getX();

            return RegionPathSearch.calculateDistance(directHorizontal, targetHorizontal, directVertical, targetVertical);
        }

        int previousHorizontal = (short) regions.getCenterX(search.getRouteRegion(0));
        int previousVertical = (short) regions.getCenterY(search.getRouteRegion(0));
        int totalDistance = 0;

        for (int routeIndex = 1; routeIndex < routeCount; routeIndex++) {
            int region = search.getRouteRegion(routeIndex);
            int centerHorizontal = (short) regions.getCenterX(region);
            int centerVertical = (short) regions.getCenterY(region);

            totalDistance += RegionPathSearch.calculateDistance(previousHorizontal, centerHorizontal, previousVertical, centerVertical);
            previousHorizontal = centerHorizontal;
            previousVertical   = centerVertical;
        }

        return totalDistance;
    }

    /**
     * Tests whether the terrain accepts a unit footprint: the footprint lies inside the map, the center point
     * is walkable and the terrain contours leave the footprint clear.
     *
     * @param unitType  the unit type WORD
     * @param positionX the horizontal WORD coordinate
     * @param positionY the vertical WORD coordinate
     * @return {@code true} if the terrain accepts the unit footprint, {@code false} otherwise
     * @throws UnsupportedOperationException if the terrain contours are not initialized
     * @throws NullPointerException          if the world was built without game data
     */
    public boolean isTerrainPlacementWalkable(int unitType, int positionX, int positionY) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int horizontal = (short) positionX;
        int vertical = (short) positionY;
        int normalizedType = unitType & WORD_MASK;

        if (horizontal < (short) units.getDimensionLeft(normalizedType) ||
            horizontal + (short) units.getDimensionRight(normalizedType) >= this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE ||
            vertical < (short) units.getDimensionUp(normalizedType) ||
            vertical + (short) units.getDimensionDown(normalizedType) >= this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE ||
            !this.isTerrainPointWalkable(horizontal, vertical)) {
            return false;
        }

        TerrainContours terrain = this.game.getPathSystem().getTerrainContours();

        if (terrain == null) {
            throw Exceptions.newUnsupportedOperationException("Native terrain placement requires initialized contour storage.");
        }

        return terrain.isPlacementClear(normalizedType, horizontal, vertical, units);
    }

    /**
     * Tests whether a terrain point is walkable: creep is walkable, a fully walkable or unwalkable tile decides by its
     * flag, and a partially walkable tile decides by the walkable bit of the mini-tile under the point: the walk cell of the point in the game's
     * terrain grid ({@link ScrTerrainGrid#isWalkable}), with both WORDs read as signed values and divided with truncation toward zero.
     *
     * @param positionX the horizontal WORD coordinate
     * @param positionY the vertical WORD coordinate
     * @return {@code true} if the terrain point is walkable, {@code false} otherwise
     * @throws UnsupportedOperationException if tile flags are absent or the tile index is outside storage
     * @throws IllegalStateException         if the tileset's mini-tile flags cannot be loaded
     * @throws NullPointerException          if the world was built without game data
     */
    public boolean isTerrainPointWalkable(int positionX, int positionY) {
        return this.game.getTerrain().isWalkable((short) positionX / TerrainTileset.MINI_TILE_SIZE, (short) positionY / TerrainTileset.MINI_TILE_SIZE);
    }

    /**
     * Sets a unit's movement destination, clamped to the map, and invalidates its path.
     * <p>
     * A unit already heading for the position is left alone; a unit with a computer record that the computer controller
     * picks up by transport keeps its destination. Otherwise the destination, waypoint and movement flags are updated,
     * the destination countdown restarts, the unit becomes movable and the lifted flag follows the first order.
     *
     * @param unit      the unit
     * @param positionX the signed DWORD destination coordinate
     * @param positionY the signed DWORD destination coordinate
     * @return {@code true} if the destination was processed, {@code false} otherwise
     * @throws UnsupportedOperationException if required path storage is unavailable
     * @throws NullPointerException          if the world was built without game data
     */
    public boolean setMovementDestination(@NotNull Unit unit, int positionX, int positionY) {
        if ((short) unit.getMovementDestinationX() == positionX && (short) unit.getMovementDestinationY() == positionY) {
            return true;
        }

        // A unit with a computer record runs the computer transport test first.
        if (unit.getComputerRecord() != null && this.game.getHooks().requiresPickup(unit, positionX, positionY)) {
            return false;
        }

        if (unit.getPathIndex() >= 0) {
            PathPool paths = this.game.getPathSystem().getPathPool();

            if (paths == null) {
                throw Exceptions.newUnsupportedOperationException("Native destination processing requires initialized path storage.");
            }

            paths.setFlags(unit.getPathIndex(), paths.getFlags(unit.getPathIndex()) | PathPool.FLAG_NEW_MOVE_TARGET);
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int unitType = unit.getUnitId();
        int horizontal = clampCoordinate(positionX,
                                         units.getDimensionLeft(unitType), units.getDimensionRight(unitType),
                                         this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE,
                                         0);
        int vertical = clampCoordinate(positionY,
                                       units.getDimensionUp(unitType), units.getDimensionDown(unitType),
                                       this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE,
                                       MAP_BOTTOM_MARGIN);

        if ((short) unit.getMovementDestinationX() != (short) horizontal || (short) unit.getMovementDestinationY() != (short) vertical) {
            unit.setMovementDestinationX(horizontal);
            unit.setMovementDestinationY(vertical);
            unit.setMovementFlags(unit.getMovementFlags() & ~MovementFlags.BRAKING | MovementFlags.ORDERED);
            unit.setMovementWaypointX(unit.getMovementDestinationX());
            unit.setMovementWaypointY(unit.getMovementDestinationY());
            unit.setMovementTargetUnit(null);
        }

        unit.setMovementDestinationCountdown(DESTINATION_COUNTDOWN);
        unit.setStatus(unit.getStatus() & ~UnitStatusFlags.IMMOVABLE);

        OrderPool.Order order = unit.getFirstOrder();

        if (order != null && this.game.requireGameData().getOrderTypes().getCanBeLifted(order.getOrderId()) == 0) {
            unit.setMovementFlags(unit.getMovementFlags() | MovementFlags.PASSING_THROUGH);
        } else {
            unit.setMovementFlags(unit.getMovementFlags() & ~MovementFlags.PASSING_THROUGH);
        }

        return true;
    }

    /**
     * Sets or clears map tile occupancy over a building placement footprint.
     *
     * @param unitType  the building type whose placement box is used
     * @param positionX the footprint center X
     * @param positionY the footprint center Y
     * @param occupied  whether to set occupancy
     * @throws NullPointerException if the world was built without game data
     */
    public void setFootprintOccupied(int unitType, int positionX, int positionY, boolean occupied) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        GameMap map = this.game.getMap();
        int width = (short) units.getPlacementWidth(unitType) / TerrainTileset.TILE_SIZE;
        int height = (short) units.getPlacementHeight(unitType) / TerrainTileset.TILE_SIZE;
        int left = (short) positionX / TerrainTileset.TILE_SIZE - width / FOOTPRINT_CENTER_DIVISOR;
        int upper = (short) positionY / TerrainTileset.TILE_SIZE - height / FOOTPRINT_CENTER_DIVISOR;

        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int tileIndex = (upper + row) * map.getWidth() + left + column;

                if (occupied) {
                    map.occupyTile(tileIndex);
                } else {
                    map.vacateTile(tileIndex);
                }
            }
        }
    }

    private boolean isPlacementWithinMap(@NotNull ScrUnitTypeView units, int unitType, int horizontal, int vertical) {
        return (short) (horizontal - units.getDimensionLeft(unitType)) >= 0 &&
               (short) (horizontal + units.getDimensionRight(unitType)) < this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE &&
               (short) (vertical - units.getDimensionUp(unitType)) >= 0 &&
               (short) (vertical + units.getDimensionDown(unitType)) < this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE - MAP_BOTTOM_MARGIN;
    }

    /**
     * Finds a free position for a unit being placed within the default rectangle of {@code 128} pixels around the requested position.
     *
     * @param unit      the unit being placed
     * @param positionX the requested horizontal WORD coordinate
     * @param positionY the requested vertical WORD coordinate
     * @param result    the two WORD output coordinates, written only on success
     * @return {@code true} if a free position was found, {@code false} otherwise
     * @throws IllegalArgumentException if the result has fewer than two coordinates
     */
    public boolean find(@NotNull Unit unit, int positionX, int positionY, int @NotNull [] result) {
        return this.find(unit,
                         positionX, positionY,
                         (short) (positionX - DEFAULT_SEARCH_HALF_SIZE), (short) (positionY - DEFAULT_SEARCH_HALF_SIZE),
                         (short) (positionX + (DEFAULT_SEARCH_HALF_SIZE - 1)), (short) (positionY + (DEFAULT_SEARCH_HALF_SIZE - 1)),
                         false,
                         result);
    }

    /**
     * Searches a supplied placement rectangle for a free position, optionally displacing an initially terrain-blocked unit.
     * <p>
     * The search walks growing rectangles around the requested position; a free requested position is taken as it is.
     * A blocked search leaves the exit-blocked command error.
     *
     * @param unit              the incoming unit
     * @param positionX         the requested horizontal coordinate
     * @param positionY         the requested vertical coordinate
     * @param minimumHorizontal the rectangle's left edge
     * @param minimumVertical   the rectangle's upper edge
     * @param maximumHorizontal the rectangle's right edge
     * @param maximumVertical   the rectangle's lower edge
     * @param terrainDisplaces  whether terrain blockage starts the surrounding search
     * @param result            the two output coordinates, written only on success
     * @return {@code true} if a free position was found, {@code false} otherwise
     * @throws IllegalArgumentException      if the result has fewer than two coordinates
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if required placement storage is unavailable or the search repeats its steps
     */
    public boolean find(@NotNull Unit unit,
                        int positionX, int positionY,
                        int minimumHorizontal, int minimumVertical,
                        int maximumHorizontal, int maximumVertical,
                        boolean terrainDisplaces,
                        int @NotNull [] result) {
        return this.search(unit,
                           positionX, positionY,
                           minimumHorizontal, minimumVertical,
                           maximumHorizontal, maximumVertical,
                           terrainDisplaces, true,
                           result);
    }

    /**
     * Finds a free position for a ground unit that the terrain under it no longer carries, within the default rectangle of {@code 128} pixels
     * around the requested position: the search of {@link #find(Unit, int, int, int, int, int, int, boolean, int[])} with terrain blockage
     * starting the surrounding search, without the condition that the position share the terrain component of the unit's position.
     *
     * @param unit      the unit
     * @param positionX the requested horizontal WORD coordinate
     * @param positionY the requested vertical WORD coordinate
     * @param result    the two WORD output coordinates, written only on success
     * @return {@code true} if a free position was found, {@code false} otherwise
     * @throws IllegalArgumentException      if the result has fewer than two coordinates
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if required placement storage is unavailable or the search repeats its steps
     */
    public boolean findWalkable(@NotNull Unit unit, int positionX, int positionY, int @NotNull [] result) {
        return this.search(unit,
                           positionX, positionY,
                           (short) (positionX - DEFAULT_SEARCH_HALF_SIZE), (short) (positionY - DEFAULT_SEARCH_HALF_SIZE),
                           (short) (positionX + (DEFAULT_SEARCH_HALF_SIZE - 1)), (short) (positionY + (DEFAULT_SEARCH_HALF_SIZE - 1)),
                           true, false,
                           result);
    }

    /**
     * Searches a placement rectangle for a free position ({@link #find(Unit, int, int, int, int, int, int, boolean, int[])}), where a ground
     * unit's position has to share the terrain component of the unit's position if asked.
     */
    private boolean search(@NotNull Unit unit,
                           int positionX, int positionY,
                           int minimumHorizontal, int minimumVertical,
                           int maximumHorizontal, int maximumVertical,
                           boolean terrainDisplaces, boolean sameComponent,
                           int @NotNull [] result) {
        if (result.length < POSITION_COORDINATES) {
            throw Exceptions.newIllegalArgumentException("Placement output requires two coordinates.");
        }

        UnitSpatialIndex spatialIndex = this.game.getUnitSystem().getUnitSpatialIndex();

        if (spatialIndex == null) {
            throw Exceptions.newUnsupportedOperationException("Native placement requires initialized spatial query storage.");
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int unitType = unit.getUnitId();
        int horizontal = (short) positionX;
        int vertical = (short) positionY;

        minimumHorizontal = Math.max(0, minimumHorizontal);
        minimumVertical   = Math.max(0, minimumVertical);

        int mapWidth = this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE;
        int mapHeight = this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE;

        if (maximumHorizontal >= mapWidth) {
            maximumHorizontal = (short) (mapWidth - 1);
        }

        if (maximumVertical >= mapHeight - MAP_BOTTOM_MARGIN) {
            maximumVertical = (short) (mapHeight - (MAP_BOTTOM_MARGIN + 1));
        }

        Unit blocker = spatialIndex.findPlacementBlocker(this.game.getHooks().getCollisionRules(), unit, units, horizontal, vertical);

        if (blocker == null && this.isPlacementWithinMap(units, unitType, horizontal, vertical)) {
            if ((unit.getStatus() & UnitStatusFlags.FLYING) != 0 || this.isTerrainPlacementWalkable(unitType, horizontal, vertical)) {
                result[X_INDEX] = positionX & WORD_MASK;
                result[Y_INDEX] = positionY & WORD_MASK;

                return true;
            }

            if (!terrainDisplaces) {
                this.game.getGameContext().setLastErrorBytes(EXIT_BLOCKED);

                return false;
            }
        }

        int horizontalStep = SEARCH_GRID;
        int verticalStep = SEARCH_GRID;

        if (blocker != null) {
            int blockerType = blocker.getUnitId();
            int combinedWidth = (short) units.getDimensionRight(blockerType) + (short) units.getDimensionLeft(unitType) + BLOCKER_STEP_CLEARANCE;
            int combinedHeight = (short) units.getDimensionDown(blockerType) + (short) units.getDimensionUp(unitType) + BLOCKER_STEP_CLEARANCE;

            if (combinedWidth > SEARCH_GRID) {
                horizontalStep = (short) combinedWidth;
            }

            if (combinedHeight > SEARCH_GRID) {
                verticalStep = (short) combinedHeight;
            }
        }

        for (int attempt = 0; attempt < SEARCH_RING_LIMIT; attempt++) {
            if (horizontal - horizontalStep < minimumHorizontal && horizontal + horizontalStep > maximumHorizontal &&
                vertical - verticalStep < minimumVertical && vertical + verticalStep > maximumVertical) {
                this.game.getGameContext().setLastErrorBytes(EXIT_BLOCKED);

                return false;
            }

            if (this.searchRectangle(unit,
                                     (short) (horizontal - horizontalStep), (short) (vertical - verticalStep),
                                     (short) (horizontalStep * SEARCH_SIZE_FACTOR), (short) (verticalStep * SEARCH_SIZE_FACTOR),
                                     minimumHorizontal, minimumVertical,
                                     maximumHorizontal, maximumVertical,
                                     sameComponent,
                                     result)) {
                return true;
            }

            horizontalStep = (short) (horizontalStep + SEARCH_STEP_GROWTH);
            verticalStep   = (short) (verticalStep + SEARCH_STEP_GROWTH);
        }

        throw Exceptions.newUnsupportedOperationException("Native placement search has repeated its WORD step cycle.");
    }

    /**
     * Moves a unit to a position clamped to the map: updates its fixed and pixel positions and its sprite, sets the
     * movement destination of a unit that is not dying, moves its finder edges and notifies collisions of a visible
     * unit. Subunits stop after the destination.
     *
     * @param unit      the unit being moved
     * @param positionX the horizontal WORD coordinate
     * @param positionY the vertical WORD coordinate
     * @throws UnsupportedOperationException if required retained movement state is unavailable
     * @throws NullPointerException          if the world was built without game data
     */
    public void move(@NotNull Unit unit, int positionX, int positionY) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int unitType = unit.getUnitId();
        int horizontal = clampCoordinate(positionX,
                                         units.getDimensionLeft(unitType), units.getDimensionRight(unitType),
                                         this.game.getMap().getWidth() * TerrainTileset.TILE_SIZE,
                                         0);
        int vertical = clampCoordinate(positionY,
                                       units.getDimensionUp(unitType), units.getDimensionDown(unitType),
                                       this.game.getMap().getHeight() * TerrainTileset.TILE_SIZE,
                                       MAP_BOTTOM_MARGIN);
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null || this.game.getSpriteSystem().getSpritePool() == null) {
            throw Exceptions.newUnsupportedOperationException("Native placement movement requires retained sprite storage.");
        }

        int previousHorizontal = (short) sprite.getX();
        int previousVertical = (short) sprite.getY();

        unit.setFixedPositionX((short) horizontal << FIXED_POINT_FRACTION_BITS);
        unit.setFixedPositionY((short) vertical << FIXED_POINT_FRACTION_BITS);
        unit.setPositionX(horizontal);
        unit.setPositionY(vertical);
        this.game.getSpriteSystem().getSpritePool().relocate(sprite, horizontal, vertical, this.game.getMap().getHeight());

        if (unit.getPrimaryOrder() != ScrOrderIds.DIE) {
            this.setMovementDestination(unit, (short) horizontal, (short) vertical);
            unit.setDirectionTargetX(horizontal);
            unit.setDirectionTargetY(vertical);
        }

        if ((units.getFlags(unitType) & UnitTypeFlags.SUBUNIT) != 0) {
            return;
        }

        UnitSpatialIndex spatialIndex = this.game.getUnitSystem().getUnitSpatialIndex();

        if (unit.getMinimumHorizontalEdgeIndex() >= 0 && ((short) horizontal != previousHorizontal || (short) vertical != previousVertical)) {
            if (spatialIndex == null) {
                throw Exceptions.newUnsupportedOperationException("Native placement movement requires indexed edge storage.");
            }

            spatialIndex.relocate(unit, (short) horizontal - previousHorizontal, (short) vertical - previousVertical);
        }

        if ((sprite.getFlags() & Sprite.FLAG_HIDDEN) == 0) {
            if (spatialIndex == null) {
                throw Exceptions.newUnsupportedOperationException("Native placement movement requires initialized collision query storage.");
            }

            spatialIndex.notifyCollision(unit, units);
        }
    }

    /**
     * Walks the perimeter of one search rectangle on the search grid, side by side, and takes the first position that
     * no unit blocks, that shares the unit's terrain component (ground units, if asked) and whose terrain accepts the unit;
     * a blocker makes the walk jump past it.
     *
     * @param unit              the incoming unit
     * @param horizontal        the rectangle's left edge before grid alignment
     * @param vertical          the rectangle's upper edge before grid alignment
     * @param width             the rectangle width
     * @param height            the rectangle height
     * @param minimumHorizontal the search area's left edge
     * @param minimumVertical   the search area's upper edge
     * @param maximumHorizontal the search area's right edge
     * @param maximumVertical   the search area's lower edge
     * @param sameComponent     whether a ground unit's position has to share the terrain component of the unit's position
     * @param result            the two output coordinates, written only on success
     * @return {@code true} if a free position was found, {@code false} otherwise
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if a perimeter side repeats its WORD coordinates or the unit spatial index is not initialized
     * @throws IllegalStateException         if the blocker has no sprite
     */
    private boolean searchRectangle(@NotNull Unit unit,
                                    int horizontal, int vertical,
                                    int width, int height,
                                    int minimumHorizontal, int minimumVertical,
                                    int maximumHorizontal, int maximumVertical,
                                    boolean sameComponent,
                                    int @NotNull [] result) {
        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        int unitType = unit.getUnitId();
        int unitWidth = (short) (units.getDimensionLeft(unitType) + units.getDimensionRight(unitType) + 1);
        int left = Math.max(minimumHorizontal, (short) (horizontal & ~SEARCH_GRID_MASK));
        int right = Math.min(maximumHorizontal, (short) ((horizontal + width + SEARCH_GRID_MASK) & ~SEARCH_GRID_MASK));
        int upper = Math.max(minimumVertical, (short) (vertical & ~SEARCH_GRID_MASK));
        int lower = Math.min(maximumVertical, (short) ((vertical + height + SEARCH_GRID_MASK) & ~SEARCH_GRID_MASK));
        int initialHorizontal = left;

        if (width > unitWidth * SEARCH_SIZE_FACTOR) {
            initialHorizontal = (short) (initialHorizontal + ((unitWidth + SEARCH_GRID_MASK) & ~SEARCH_GRID_MASK));
        }

        for (int side = 0; side < SIDE_COUNT; side++) {
            boolean horizontalSide = side == SIDE_BOTTOM || side == SIDE_TOP;
            boolean increasing = side == SIDE_BOTTOM || side == SIDE_LEFT;
            int coordinate = switch (side) {
                case SIDE_BOTTOM -> initialHorizontal;
                case SIDE_RIGHT -> lower;
                case SIDE_TOP -> right;
                default -> upper;
            };
            int limit = switch (side) {
                case SIDE_BOTTOM -> right;
                case SIDE_RIGHT -> upper;
                case SIDE_TOP -> left;
                default -> lower;
            };
            int fixedCoordinate = switch (side) {
                case SIDE_BOTTOM -> lower;
                case SIDE_RIGHT -> right;
                case SIDE_TOP -> upper;
                default -> left;
            };

            for (int attempt = 0; increasing ? coordinate <= limit : coordinate >= limit; attempt++) {
                if (attempt >= WORD_RANGE) {
                    throw Exceptions.newUnsupportedOperationException("Native placement perimeter repeats its WORD coordinates.");
                }

                int positionX = horizontalSide ? coordinate : fixedCoordinate;
                int positionY = horizontalSide ? fixedCoordinate : coordinate;
                int step = SEARCH_GRID;

                if (this.isPlacementWithinMap(units, unitType, positionX, positionY)) {
                    Unit blocker = this.game.getUnitSystem().requireUnitSpatialIndex().findPlacementBlocker(this.game.getHooks().getCollisionRules(),
                                                                                                            unit, units, positionX, positionY);

                    if (blocker == null) {
                        if ((!sameComponent || this.isGroundReachable(unit, positionX, positionY)) &&
                            ((unit.getStatus() & UnitStatusFlags.FLYING) != 0 || this.isTerrainPlacementWalkable(unitType, positionX, positionY))) {
                            result[X_INDEX] = positionX & WORD_MASK;
                            result[Y_INDEX] = positionY & WORD_MASK;

                            return true;
                        }
                    } else {
                        int blockerType = blocker.getUnitId();
                        Sprite blockerSprite = blocker.requireSprite();
                        int blockerMinimum = horizontalSide
                                             ? (short) (blockerSprite.getX() - units.getDimensionLeft(blockerType))
                                             : (short) (blockerSprite.getY() - units.getDimensionUp(blockerType));
                        int blockerMaximum = horizontalSide
                                             ? (short) (blockerSprite.getX() + units.getDimensionRight(blockerType))
                                             : (short) (blockerSprite.getY() + units.getDimensionDown(blockerType));
                        int incomingMinimum = (short) (coordinate - (horizontalSide
                                                                     ? units.getDimensionLeft(unitType)
                                                                     : units.getDimensionUp(unitType)));
                        int incomingMaximum = (short) (coordinate + (horizontalSide
                                                                     ? units.getDimensionRight(unitType)
                                                                     : units.getDimensionDown(unitType)));

                        step = increasing ? blockerMaximum - incomingMinimum + 1 : incomingMaximum - blockerMinimum + 1;

                        int remainder = (increasing ? coordinate + step : coordinate - step) & SEARCH_GRID_MASK;

                        if (remainder != 0) {
                            step += SEARCH_GRID - remainder;
                        }
                    }
                }

                coordinate = (short) (increasing ? coordinate + step : coordinate - step);
            }
        }

        return false;
    }
}
