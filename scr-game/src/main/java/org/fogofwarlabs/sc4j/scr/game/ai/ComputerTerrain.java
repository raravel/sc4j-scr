package org.fogofwarlabs.sc4j.scr.game.ai;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.game.path.PathSystem;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerRelations;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainContours;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainRegionLookupUtils;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainRegions;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Executes the region queries and path-choke analysis used by computer towns.
 * <p>
 * Retained arrays provide reusable scratch storage for town region activation, choke searches and unit rectangle
 * queries.
 */
public final class ComputerTerrain {
    /**
     * The smallest choke search depth: the first route entry after the source region.
     */
    private static final int SEARCH_DEPTH_MINIMUM = 2;
    /**
     * The largest choke search depth tried toward an enemy.
     */
    private static final int SEARCH_DEPTH_MAXIMUM = 6;
    /**
     * The ring of region entries of the area walk.
     */
    private static final int AREA_QUEUE_SIZE = 0x271;
    /**
     * The retained choke regions.
     */
    private static final int CHOKE_REGION_CAPACITY = TerrainRegions.REGION_COUNT;
    /**
     * The chokes one search finds by successive exclusions.
     */
    private static final int CHOKE_RESULT_CAPACITY = 4;
    /**
     * The last result slot a search writes.
     */
    private static final int LAST_CHOKE_RESULT = CHOKE_RESULT_CAPACITY - 1;
    /**
     * The largest choke count a town region activation accepts toward one enemy.
     */
    private static final int TOWN_CHOKE_LIMIT = 4;
    /**
     * The chokes the defense choke selection compares at most: its stack table.
     */
    private static final int DEFENSE_CHOKE_CAPACITY = 24;
    /**
     * The route regions a search may gain after the exclusions before the chokes count as a detour.
     */
    private static final int DETOUR_ROUTE_REGIONS = 10;
    /**
     * The choke count reported for a detour.
     */
    private static final int DETOUR_CHOKE_COUNT = 3;

    /**
     * The choke search status: chokes were found.
     */
    private static final int CHOKE_FOUND = 0;
    /**
     * The choke search status: excluding every found choke still leaves a route, which is a long detour.
     */
    private static final int CHOKE_DETOUR = 1;
    /**
     * The choke search status: the regions are not connected or the search failed.
     */
    private static final int CHOKE_SEARCH_FAILED = 2;
    /**
     * The choke search status: the route has no region with neighboring components.
     */
    private static final int CHOKE_NO_CANDIDATE = 3;
    /**
     * The choke search status: the route or the depth is too short, or source and destination are equal.
     */
    private static final int CHOKE_SHORT_ROUTE = 4;
    /**
     * The multiplier of the state hash.
     */
    private static final int HASH_MULTIPLIER = 31;

    /**
     * Returns the region's walkable and inaccessible neighbor count: its total neighbor count less its region-zero
     * neighbors. The ordered neighbor list holds the walkable neighbors, then the inaccessible ones, then region zero.
     *
     * @param terrain the terrain regions
     * @param region  the region index
     * @return the number of walkable and inaccessible neighbors at the start of the ordered neighbor list
     */
    static int getPathNeighborCount(@NotNull TerrainRegions terrain, int region) {
        return terrain.getNeighborCount(region) - terrain.getZeroNeighborCount(region);
    }


    private final @NotNull Game game;

    /**
     * The unit query buffer, one entry more than the unit capacity ({@link UnitSpatialIndex#getQueryCapacity()}); the first
     * query sizes it, because the unit pool is created after this system.
     */
    private int @NotNull [] regionUnits;
    private final int @NotNull [] visitedRegions;

    private final int @NotNull [] chokeRegions;
    private int chokeRegionCount;

    private final int @NotNull [] searchResults;
    private int searchStatus;
    private @Nullable RegionPathSearch pathSearch;
    private @Nullable TerrainRegions pathSearchRegions;

    /**
     * Creates a {@link ComputerTerrain} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public ComputerTerrain(@NotNull Game game) {
        this.game = game;

        this.regionUnits    = new int[0];
        this.visitedRegions = new int[ComputerPlayerPhase.REGION_CAPACITY];

        this.chokeRegions     = new int[CHOKE_REGION_CAPACITY];
        this.chokeRegionCount = 0;

        this.searchResults     = new int[CHOKE_RESULT_CAPACITY];
        this.searchStatus      = 0;
        this.pathSearch        = null;
        this.pathSearchRegions = null;
    }

    /**
     * @return the terrain regions the game started with ({@link PathSystem#getStartTerrainContours}), which the regional records index
     * @throws UnsupportedOperationException if the terrain regions are not initialized
     */
    @NotNull TerrainRegions getRegions() {
        TerrainContours contours = this.game.getPathSystem().getStartTerrainContours();

        if (contours == null) {
            throw Exceptions.newUnsupportedOperationException("Computer region queries require initialized terrain regions.");
        }

        return contours.getRegions();
    }

    /**
     * @return the computer phase's reusable region path search storage
     */
    @NotNull RegionPathSearch getPathSearch() {
        return this.getComputerTerrain().getPathSearch(this.getRegions());
    }

    private @NotNull RegionPathSearch getPathSearch(@NotNull TerrainRegions regions) {
        if (this.pathSearch == null || this.pathSearchRegions != regions) {
            this.pathSearch        = new RegionPathSearch(regions);
            this.pathSearchRegions = regions;
        }

        return this.pathSearch;
    }

    /**
     * @param index the result index of the last unit query
     * @return the one-based pool index of the unit
     */
    public int getRegionUnit(int index) {
        return this.regionUnits[index];
    }

    /**
     * @param region the region index
     * @return {@code true} if the region is the inaccessible classification, {@code false} otherwise
     */
    boolean isInaccessible(int region) {
        return this.getRegions().getTerrainType(region) == TerrainRegions.BLOCKED_TERRAIN;
    }

    /**
     * Returns the region under the unit's sprite position.
     *
     * @param unit the unit
     * @return the region index
     * @throws UnsupportedOperationException if the unit has no sprite
     */
    public int getUnitRegion(@NotNull Unit unit) {
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null) {
            throw Exceptions.newUnsupportedOperationException("The unit region lookup requires the unit sprite.");
        }

        return TerrainRegionLookupUtils.lookup(this.getRegions(), (short) sprite.getX(), (short) sprite.getY());
    }

    /**
     * Returns the region at a position.
     *
     * @param positionX the horizontal pixel
     * @param positionY the vertical pixel
     * @return the region index
     */
    public int getRegion(int positionX, int positionY) {
        return TerrainRegionLookupUtils.lookup(this.getRegions(), positionX, positionY);
    }

    /**
     * Tests whether the owner has a worker or a building in the region.
     *
     * @param owner  the owner
     * @param region the region index
     * @return {@code true} if a unit of the owner in the region is a worker or building, {@code false} otherwise
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    boolean hasWorkerOrBuilding(int owner, int region) {
        ComputerTerrain terrain = this.getComputerTerrain();
        int count = terrain.collectRegionUnits(region);

        for (int index = 0; index < count; index++) {
            Unit unit = this.game.getUnitSystem().requireUnitPool().getUnit(terrain.regionUnits[index]);

            if (unit.getOwnerIndex() == owner &&
                (this.game.getComputerUnits().isWorker(unit) ||
                 UnitQueries.isBuilding(this.game.requireGameData().getUnitTypes(), unit.getUnitId()))) {
                return true;
            }
        }

        return false;
    }

    private @NotNull ComputerTerrain getComputerTerrain() {
        ComputerPlayerPhase phase = this.game.getComputerPlayerPhase();

        if (phase == null) {
            throw Exceptions.newIllegalStateException("Computer region queries require the computer-player phase.");
        }

        return this.game.getComputerTerrain();
    }


    /**
     * Measures the area around a position for aiscript {@code region_size}: a breadth walk over the walkable neighbors
     * from the region at the position, summing the tile counts until the limit is reached. The walk keeps no visited
     * marks; its queue is a ring of {@value #AREA_QUEUE_SIZE} entries.
     *
     * @param positionX the town X WORD
     * @param positionY the town Y WORD
     * @param limit     the tile limit
     * @return the limit when reached, otherwise the sum of the walked regions
     */
    int measureArea(int positionX, int positionY, int limit) {
        TerrainRegions regions = this.getRegions();
        int[] queue = new int[AREA_QUEUE_SIZE];
        int head = 0;
        int tail = 1;
        int sum = 0;

        queue[0] = this.getRegion(positionX, positionY);

        do {
            int region = queue[head];

            head = head == AREA_QUEUE_SIZE - 1 ? 0 : head + 1;
            sum += regions.getTileCount(region);

            if (Integer.compareUnsigned(sum, limit) >= 0) {
                return limit;
            }

            int neighbors = regions.getWalkableNeighborCount(region);

            for (int neighborIndex = 0; neighborIndex < neighbors; neighborIndex++) {
                queue[tail] = regions.getNeighborRegion(region, neighborIndex);
                tail        = tail == AREA_QUEUE_SIZE - 1 ? 0 : tail + 1;
            }
        } while (head != tail);

        return sum;
    }

    /**
     * Collects the units in a region: the rectangle query over the region bounds, filtered to units whose sprite lies
     * in the region.
     *
     * @param region the region index
     * @return the number of one-based pool indices written in horizontal-edge order
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    int collectRegionUnits(int region) {
        TerrainRegions regions = this.getRegions();
        int resultCount = this.collectRectangleUnits((short) regions.getLeftBound(region), (short) regions.getUpperBound(region),
                                                     (short) regions.getRightBound(region), (short) regions.getLowerBound(region));
        int filtered = 0;

        for (int resultIndex = 0; resultIndex < resultCount; resultIndex++) {
            int poolIndex = this.regionUnits[resultIndex];

            if (this.getUnitRegion(this.game.getUnitSystem().requireUnitPool().getUnit(poolIndex)) == region) {
                this.regionUnits[filtered++] = poolIndex;
            }
        }

        return filtered;
    }

    /**
     * Collects the units in a rectangle, writing one-based pool indices in horizontal-edge order to the query buffer
     * read by {@link #getRegionUnit}.
     *
     * @param left   the left bound
     * @param top    the top bound
     * @param right  the right bound
     * @param bottom the bottom bound
     * @return the number of units
     * @throws UnsupportedOperationException if the game has no unit spatial index
     */
    public int collectRectangleUnits(int left, int top, int right, int bottom) {
        UnitSpatialIndex index = this.game.getUnitSystem().getUnitSpatialIndex();

        if (index == null) {
            throw Exceptions.newUnsupportedOperationException("Computer unit queries require the unit spatial index.");
        }

        int[] found = index.findAllInRectangleUnexpanded(left, top, right, bottom);

        if (this.regionUnits.length != index.getQueryCapacity()) {
            this.regionUnits = new int[index.getQueryCapacity()];
        }

        System.arraycopy(found, 0, this.regionUnits, 0, found.length);

        return found.length;
    }

    /**
     * Collects the units in a rectangle with {@code 16}-bit bounds and copies the result list so callers can keep it while
     * issuing orders.
     *
     * @param left   the left bound
     * @param top    the top bound
     * @param right  the right bound
     * @param bottom the bottom bound
     * @return the units in horizontal-edge order
     * @throws UnsupportedOperationException if the unit pool is not initialized
     */
    @NotNull Unit @NotNull [] collectRectangle(int left, int top, int right, int bottom) {
        int found = this.collectRectangleUnits((short) left, (short) top, (short) right, (short) bottom);
        Unit[] units = new Unit[found];

        for (int index = 0; index < found; index++) {
            units[index] = this.game.getUnitSystem().requireUnitPool().getUnit(this.regionUnits[index]);
        }

        return units;
    }

    /**
     * Collects the units in a rectangle with {@code 16}-bit bounds and copies their pool indices, so callers can keep the
     * list while the units change; each unit is resolved by the caller when it is visited.
     *
     * @param left   the left bound
     * @param top    the top bound
     * @param right  the right bound
     * @param bottom the bottom bound
     * @return the one-based pool indices of the units in horizontal-edge order
     */
    public int @NotNull [] collectRectanglePoolIndices(int left, int top, int right, int bottom) {
        int found = this.collectRectangleUnits((short) left, (short) top, (short) right, (short) bottom);
        int[] poolIndices = new int[found];

        for (int index = 0; index < found; index++) {
            poolIndices[index] = this.getRegionUnit(index);
        }

        return poolIndices;
    }

    /**
     * Finds the nearest eligible unit of one enemy player: alive, visible, not a subunit, an enemy of the owner and not
     * in an inaccessible region.
     *
     * @param owner     the evaluating owner
     * @param enemy     the enemy player whose unit list is traversed
     * @param positionX the horizontal reference pixel
     * @param positionY the vertical reference pixel
     * @return the nearest unit, or {@code null}
     * @throws UnsupportedOperationException if the unit pool is not initialized
     * @throws IllegalStateException         if the unit has no sprite
     */
    @Nullable Unit findNearestEnemyUnit(int owner, int enemy, int positionX, int positionY) {
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        Unit nearest = null;
        int nearestDistance = ComputerPlayerPhase.NO_DISTANCE;

        for (int poolIndex = pool.getFirstOwnerIndex(enemy); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (ComputerUnits.isDeadOrHidden(unit) || this.game.getComputerUnits().isSubunit(unit) ||
                !this.game.getComputerUnits().isEnemy(owner, unit)) {
                continue;
            }

            if (this.isInaccessible(this.getUnitRegion(unit))) {
                continue;
            }

            Sprite sprite = unit.requireSprite();
            int distance = RegionPathSearch.calculateDistance(positionX, (short) sprite.getX(), positionY, (short) sprite.getY());

            if (Integer.compareUnsigned(distance, nearestDistance) < 0) {
                nearest         = unit;
                nearestDistance = distance;
            }
        }

        return nearest;
    }

    /**
     * Records the path chokes toward each enemy and activates the regions near the town: a flood from the town region
     * that stops at the chokes, {@code depthLimit} deep when chokes exist and {@value #SEARCH_DEPTH_MINIMUM} deep
     * otherwise.
     *
     * @param phase      the owning computer phase
     * @param owner      the town owner
     * @param positionX  the town horizontal pixel
     * @param positionY  the town vertical pixel
     * @param depthLimit the flood depth used when chokes exist
     */
    void activateTownRegions(@NotNull ComputerPlayerPhase phase, int owner, int positionX, int positionY, int depthLimit) {
        TerrainRegions regions = this.getRegions();
        int townRegion = this.getRegion(positionX, positionY);

        this.recordChokes(owner, townRegion, TOWN_CHOKE_LIMIT);
        Arrays.fill(this.visitedRegions, 0);

        int limit = this.chokeRegionCount != 0 ? depthLimit : SEARCH_DEPTH_MINIMUM;

        this.floodActivate(phase, regions, owner, townRegion, 1, limit);
    }

    /**
     * Replaces the retained choke list with the chokes between the region and the nearest unit of each enemy, taking
     * the shallowest search depth that finds between one and {@code limit} chokes.
     *
     * @param owner  the evaluating owner
     * @param region the source region
     * @param limit  the largest accepted choke count
     */
    void recordChokes(int owner, int region, int limit) {
        TerrainRegions regions = this.getRegions();
        int centerX = (short) regions.getCenterX(region);
        int centerY = (short) regions.getCenterY(region);
        PlayerRelations relations = this.game.getPlayerTable().requirePlayerRelations();

        this.chokeRegionCount = 0;

        for (int enemy = 0; enemy < PlayerTable.PLAYABLE_PLAYER_SLOTS; enemy++) {
            if (relations.getAlliance(owner, enemy) != PlayerRelations.ENEMY) {
                continue;
            }

            Unit target = this.findNearestEnemyUnit(owner, enemy, centerX, centerY);

            if (target == null) {
                continue;
            }

            int targetRegion = this.getUnitRegion(target);

            for (int depth = SEARCH_DEPTH_MINIMUM; depth <= SEARCH_DEPTH_MAXIMUM; depth++) {
                int found = this.findChokes(region, targetRegion, depth);

                if (found != 0 && Integer.compareUnsigned(found, limit) <= 0) {
                    for (int foundIndex = 0; foundIndex < found; foundIndex++) {
                        int choke = this.searchResults[foundIndex];
                        boolean known = false;

                        for (int chokeIndex = 0; chokeIndex < this.chokeRegionCount; chokeIndex++) {
                            if (this.chokeRegions[chokeIndex] == choke) {
                                known = true;

                                break;
                            }
                        }

                        if (!known) {
                            this.chokeRegions[this.chokeRegionCount++] = choke;
                        }
                    }

                    break;
                }
            }
        }
    }

    /**
     * Selects, of the retained chokes, the one farthest by path tiles from the owner's Bunkers, Creep Colonies and
     * Sunken Colonies.
     *
     * @param owner the owner
     * @return the choke region, or {@code -1} when no choke is retained
     * @throws UnsupportedOperationException if the retained choke count exceeds the defense selection capacity or the unit pool is not initialized
     * @throws IllegalStateException         if the unit has no sprite
     */
    int selectDefenseChoke(int owner) {
        ComputerTerrain terrain = this.getComputerTerrain();
        int count = terrain.chokeRegionCount;

        if (count == 0) {
            return -1;
        }

        if (count == 1) {
            return terrain.chokeRegions[0];
        }

        if (count > DEFENSE_CHOKE_CAPACITY) {
            throw Exceptions.newUnsupportedOperationException("Native defense choke selection overflows its 24-entry stack table.");
        }

        TerrainRegions regions = this.getRegions();
        int[] nearest = new int[count];
        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        Arrays.fill(nearest, ComputerPlayerPhase.NO_DISTANCE);

        for (int poolIndex = pool.getFirstOwnerIndex(owner); poolIndex != 0; poolIndex = pool.getNextOwnerIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);
            int type = this.game.requireGameData().getBaseType(unit);

            if (ComputerUnits.isDeadOrHidden(unit) ||
                type != ScrUnitIds.TERRAN_BUNKER && type != ScrUnitIds.ZERG_CREEP_COLONY && type != ScrUnitIds.ZERG_SUNKEN_COLONY) {
                continue;
            }

            for (int index = 0; index < count; index++) {
                int region = terrain.chokeRegions[index];
                Sprite sprite = unit.requireSprite();
                int distance = this.game.getComputerPlacement().getPathTiles(sprite.getX(), sprite.getY(),
                                                                             (short) regions.getCenterX(region), (short) regions.getCenterY(region));

                if (Integer.compareUnsigned(distance, nearest[index]) < 0) {
                    nearest[index] = distance;
                }
            }
        }

        int best = 0;
        int bestDistance = nearest[0];

        for (int index = 1; index < count; index++) {
            if (Integer.compareUnsigned(nearest[index], bestDistance) > 0) {
                bestDistance = nearest[index];
                best         = index;
            }
        }

        return terrain.chokeRegions[best];
    }

    /**
     * Activates a region and floods on through its walkable neighbors until a retained choke, a visited region or the
     * depth limit stops it.
     */
    private void floodActivate(@NotNull ComputerPlayerPhase phase, @NotNull TerrainRegions regions, int owner, int region, int depth, int limit) {
        if (region == 0 || this.visitedRegions[region] != 0 || depth > limit) {
            return;
        }

        phase.getRegions().activate(phase, owner, region, false);

        this.visitedRegions[region] = 1;

        for (int chokeIndex = 0; chokeIndex < this.chokeRegionCount; chokeIndex++) {
            if (this.chokeRegions[chokeIndex] == region) {
                return;
            }
        }

        int neighborCount = regions.getWalkableNeighborCount(region);

        for (int neighborIndex = 0; neighborIndex < neighborCount; neighborIndex++) {
            this.floodActivate(phase, regions, owner, regions.getNeighborRegion(region, neighborIndex), depth + 1, limit);
        }
    }

    /**
     * Marks the path chokes toward each enemy as defended town regions: an idle choke region becomes
     * {@link ComputerRegionStates#ALLY_TOWN} with the town and keep-strength flags and, unless inaccessible, the
     * defense line's required ground strength.
     *
     * @param phase     the owning computer phase
     * @param owner     the town owner
     * @param positionX the town horizontal pixel
     * @param positionY the town vertical pixel
     */
    void defendTownChokes(@NotNull ComputerPlayerPhase phase, int owner, int positionX, int positionY) {
        int townRegion = this.getRegion(positionX, positionY);
        PlayerRelations relations = this.game.getPlayerTable().requirePlayerRelations();
        ComputerRegions records = phase.getRegions();

        for (int enemy = 0; enemy < PlayerTable.PLAYABLE_PLAYER_SLOTS; enemy++) {
            if (relations.getAlliance(owner, enemy) != PlayerRelations.ENEMY) {
                continue;
            }

            Unit target = this.findNearestEnemyUnit(owner, enemy, positionX, positionY);

            if (target == null) {
                continue;
            }

            int targetRegion = this.getUnitRegion(target);

            for (int depth = SEARCH_DEPTH_MINIMUM; depth <= SEARCH_DEPTH_MAXIMUM; depth++) {
                int found = this.findChokes(townRegion, targetRegion, depth);

                if (found == 0) {
                    continue;
                }

                for (int foundIndex = 0; foundIndex < found; foundIndex++) {
                    int choke = this.searchResults[foundIndex];
                    int index = records.getIndex(owner, choke);

                    if (records.getState(index) != ComputerRegionStates.IDLE) {
                        continue;
                    }

                    records.setState(phase, index, ComputerRegionStates.ALLY_TOWN);
                    records.setFlags(index,
                                     records.getFlags(index) | (ComputerRegionStates.FLAG_TOWN | ComputerRegionStates.FLAG_KEEP_REQUIRED_STRENGTH));

                    if (!this.isInaccessible(choke)) {
                        records.setRequiredGroundStrength(index, ComputerRegions.MINIMUM_REQUIRED_STRENGTH);
                    }
                }

                break;
            }
        }
    }

    /**
     * Finds the chokes of the route between two regions at a search depth, writing them to {@link #searchResults}:
     * the best choke of the route, then the best choke of each route found with the earlier chokes excluded.
     *
     * @return the number of choke regions found by successive exclusions
     */
    private int findChokes(int source, int destination, int depth) {
        TerrainRegions regions = this.getRegions();

        if (source == destination || depth < SEARCH_DEPTH_MINIMUM) {
            this.searchStatus = CHOKE_SHORT_ROUTE;

            return 0;
        }

        if (regions.getComponent(destination) != regions.getComponent(source)) {
            this.searchStatus = CHOKE_SEARCH_FAILED;

            return 0;
        }

        RegionPathSearch search = this.getPathSearch(regions);
        int sourceX = (short) regions.getCenterX(source);
        int sourceY = (short) regions.getCenterY(source);
        int destinationX = (short) regions.getCenterX(destination);
        int destinationY = (short) regions.getCenterY(destination);
        int status = this.selectChoke(regions, search, source, destination, sourceX, sourceY, destinationX, destinationY, depth, 0);

        this.searchStatus = status;

        if (status != CHOKE_FOUND) {
            return 0;
        }

        int originalRouteCount = (short) search.getRouteCount();
        int found = 1;
        int excludedCount = 0;

        try {
            while (true) {
                search.setExcludedRegion(this.searchResults[found - 1], true);

                excludedCount     = found;
                status            = this.selectChoke(regions,
                                                     search,
                                                     source, destination,
                                                     sourceX, sourceY,
                                                     destinationX, destinationY,
                                                     depth,
                                                     found < LAST_CHOKE_RESULT ? found : LAST_CHOKE_RESULT);
                this.searchStatus = status;

                if (status != CHOKE_FOUND || ++found >= CHOKE_RESULT_CAPACITY) {
                    break;
                }
            }
        } finally {
            for (int excluded = 0; excluded < excludedCount; excluded++) {
                search.setExcludedRegion(this.searchResults[excluded], false);
            }
        }

        if (status == CHOKE_FOUND) {
            if (originalRouteCount + DETOUR_ROUTE_REGIONS < (short) search.getRouteCount()) {
                this.searchStatus = CHOKE_DETOUR;

                return DETOUR_CHOKE_COUNT;
            }

            this.searchStatus = CHOKE_NO_CANDIDATE;

            return 0;
        }

        if (status == CHOKE_NO_CANDIDATE) {
            return 0;
        }

        this.searchStatus = CHOKE_FOUND;

        return found;
    }

    /**
     * Selects the route region with the most neighboring components, later regions winning ties.
     *
     * @return {@value #CHOKE_FOUND} if found, {@value #CHOKE_SEARCH_FAILED} on search failure, {@value #CHOKE_NO_CANDIDATE}
     * without candidate, {@value #CHOKE_SHORT_ROUTE} for a short route
     */
    private int selectChoke(@NotNull TerrainRegions regions,
                            @NotNull RegionPathSearch search,
                            int source, int destination,
                            int sourceX, int sourceY,
                            int destinationX, int destinationY,
                            int depth,
                            int resultIndex) {
        if (!search.search(source, sourceX, sourceY, destination, destinationX, destinationY) || search.isIncomplete()) {
            return CHOKE_SEARCH_FAILED;
        }

        int routeCount = (short) search.getRouteCount();

        if (routeCount < SEARCH_DEPTH_MINIMUM) {
            return CHOKE_SHORT_ROUTE;
        }

        int limit = Math.min(depth, routeCount - RegionPathSearch.DIRECT_ROUTE_COUNT);

        if (limit < SEARCH_DEPTH_MINIMUM) {
            return CHOKE_SHORT_ROUTE;
        }

        int best = 0;

        for (int routeIndex = SEARCH_DEPTH_MINIMUM; routeIndex <= limit; routeIndex++) {
            int region = search.getRouteRegion(routeIndex);
            int value = regions.getNeighboringComponentCount(region);

            if (value != 0 && best <= value) {
                this.searchResults[resultIndex] = region;

                best = value;
            }
        }

        return best != 0 ? CHOKE_FOUND : CHOKE_NO_CANDIDATE;
    }

    /**
     * Includes the retained chokes and the last search status in the state hash.
     *
     * @param hash the preceding diagnostic hash
     * @return the hash including this storage
     */
    int computeStateHash(int hash) {
        hash = HASH_MULTIPLIER * hash + this.chokeRegionCount;

        for (int index = 0; index < this.chokeRegionCount; index++) {
            hash = HASH_MULTIPLIER * hash + this.chokeRegions[index];
        }

        return HASH_MULTIPLIER * hash + this.searchStatus;
    }
}
