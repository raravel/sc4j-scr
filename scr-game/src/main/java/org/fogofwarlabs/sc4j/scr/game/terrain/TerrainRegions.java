package org.fogofwarlabs.sc4j.scr.game.terrain;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.path.RegionGraph;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Retains tile classifications, connected components, region bounds and ordered neighbor metadata.
 * <p>
 * Tile classifications, connected components, bounds and partial tiles have single primitive-array owners. Ordered neighbors use inline or shared
 * short arrays without depending on a binary record layout.
 */
public final class TerrainRegions implements RegionGraph {
    /**
     * The capacity of region records.
     */
    public static final int REGION_COUNT = 5000;
    /**
     * The region count limit: the merge passes repeat while at least this many regions are live, so the identifiers of the built regions stay below
     * it.
     */
    public static final int REGION_LIMIT = 2500;
    /**
     * The terrain type of an unwalkable region group.
     */
    public static final int BLOCKED_TERRAIN = 0x1FFD;
    /**
     * The size of the saved pathing data.
     */
    public static final int SAVED_SIZE = 0x97A20;

    /**
     * The columns (and rows) of the fixed tile-region grid: tile {@code (column, row)} is {@code row * 256 + column}.
     */
    static final int GRID_WIDTH = GameMap.MAXIMUM_DIMENSION;
    /**
     * The tiles of the fixed tile-region grid.
     */
    static final int TILE_COUNT = GRID_WIDTH * GRID_WIDTH;
    /**
     * The mini-tiles of one tile row and column, the miniature grid cells of a tile.
     */
    static final int MINI_TILES_PER_ROW = 4;
    /**
     * The columns of the miniature grid: four per tile column.
     */
    static final int MINI_GRID_WIDTH = MINI_TILES_PER_ROW * GRID_WIDTH;
    /**
     * Region identifiers from this base name split (partially walkable) tiles; the rest is the split record index.
     */
    static final int PARTIAL_REGION_BASE = 0x2000;
    /**
     * No region: the neighbor sentinel and the terrain type of a removed region.
     */
    static final int NO_REGION = 0x1FFF;
    /**
     * The shift from pixels to the {@code 1/256} pixels of the fixed-point region centers.
     */
    static final int FIXED_POINT_SHIFT = RegionGraph.FIXED_POINT_SHIFT;

    /**
     * The capacity of split-tile records.
     */
    private static final int PARTIAL_REGION_CAPACITY = 25000;
    /**
     * The inline neighbor slots of one region record.
     */
    private static final int INLINE_NEIGHBOR_COUNT = 10;
    /**
     * The capacity of the shared neighbor entries of the regions with more neighbors than inline slots.
     */
    private static final int SHARED_NEIGHBOR_CAPACITY = 10000;
    /**
     * The bias of a shared neighbor start: a start below {@code -1} encodes shared entry {@code -start - 2}.
     */
    private static final int SHARED_START_BIAS = 2;
    /**
     * The neighbor scratch capacity: a region's neighbor count is a BYTE.
     */
    private static final int MAXIMUM_NEIGHBORS = 0x100;

    /**
     * The terrain types of walkable region groups: middle ground, high ground and low ground.
     */
    private static final int MIDDLE_GROUND_TERRAIN = 0x1FF9;
    private static final int HIGH_GROUND_TERRAIN = 0x1FFA;
    private static final int LOW_GROUND_TERRAIN = 0x1FFB;
    /**
     * The terrain flags that select the walkable terrain type.
     */
    private static final int GROUND_LEVEL_FLAGS = MapTileFlags.WALKABLE | MapTileFlags.MIDDLE | MapTileFlags.HIGH;
    /**
     * The component of the bottom border region, which the merge and component passes keep.
     */
    private static final int RESERVED_COMPONENT = 0x4000;

    /**
     * The shift from tiles to half tiles in {@code 1/256} pixels, the fixed-point center of a one-tile extent.
     */
    private static final int HALF_TILE_FIXED_SHIFT = 12;
    /**
     * The shift from tiles to {@code 1/256} pixels.
     */
    private static final int TILE_FIXED_SHIFT = 13;
    /**
     * Half a tile in {@code 1/256} pixels.
     */
    private static final int HALF_TILE_FIXED = 0x1000;
    /**
     * The bits of a component WORD.
     */
    private static final int WORD_MASK = 0xFFFF;

    /**
     * The positions of the rectangle bounds in a rectangle array: left, top, right and bottom.
     */
    private static final int RECTANGLE_LEFT = 0;
    private static final int RECTANGLE_TOP = 1;
    private static final int RECTANGLE_RIGHT = 2;
    private static final int RECTANGLE_BOTTOM = 3;
    private static final int RECTANGLE_SIZE = 4;
    /**
     * The divisor of a rectangle width or height that gives the offset of its center tile.
     */
    private static final int RECTANGLE_CENTER_DIVISOR = 2;
    /**
     * The largest width and height in tiles of a grown partition rectangle.
     */
    private static final int MAXIMUM_RECTANGLE_WIDTH = 8;
    private static final int MAXIMUM_RECTANGLE_HEIGHT = 7;
    /**
     * The largest ratio of the longer to the shorter side of a partition rectangle.
     */
    private static final int MAXIMUM_ASPECT_RATIO = 3;
    /**
     * The forward scans of a rectangle absorption.
     */
    private static final int ABSORB_PASSES = 2;
    /**
     * The area a partition rectangle needs at first to be taken without further attempts.
     */
    private static final int INITIAL_AREA_THRESHOLD = 56;
    /**
     * The rectangle attempts after which the best rectangle is taken.
     */
    private static final int ATTEMPT_LIMIT = 25;
    /**
     * The area at or below which the earlier regions are expanded once.
     */
    private static final int SMALL_REGION_AREA = 6;

    /**
     * The eight tile neighbors: the four edge neighbors, then the upper and the lower corners.
     */
    private static final int NEIGHBOR_DIRECTIONS = 8;
    private static final int FIRST_CORNER_DIRECTION = 4;
    private static final int FIRST_LOWER_CORNER_DIRECTION = 6;
    /**
     * The direction bit of the right corners, the upper and the lower right neighbor.
     */
    private static final int RIGHT_CORNER_BIT = 1;
    /**
     * Half the mini-tiles of a tile row and column, the offset of a corner square.
     */
    private static final int HALF_TILE_MINI_TILES = MINI_TILES_PER_ROW / 2;

    /**
     * The tile count threshold of the first merge pass and its growth per pass.
     */
    private static final int MERGE_INITIAL_THRESHOLD = 4;
    private static final int MERGE_THRESHOLD_STEP = 2;

    /**
     * The neighbor component scan: neighbors with fewer tiles do not count, and the scan stops at the limit.
     */
    private static final int MINIMUM_NEIGHBOR_TILES = 4;
    private static final int COMPONENT_SCAN_CAPACITY = 4;
    private static final int COMPONENT_COUNT_LIMIT = 5;
    /**
     * The fewest neighboring components that are recorded; fewer record zero.
     */
    private static final int MINIMUM_COMPONENT_COUNT = 2;

    /**
     * The FNV-1 64-bit prime and the fold shift of the state hash mix.
     */
    private static final long HASH_PRIME = 0x100000001b3L;
    private static final int HASH_FOLD_SHIFT = 29;

    /**
     * The layout of the saved pathing data: the offsets of its parts, the record sizes and the end mark.
     */
    private static final int SAVED_REGION_COUNT = 0x00;
    private static final int SAVED_SHARED_END = 0x04;
    private static final int SAVED_PARTIAL_COUNT = 0x08;
    private static final int SAVED_TILE_REGIONS = 0x0C;
    private static final int SAVED_PARTIAL_TILES = 0x2000C;
    private static final int SAVED_PARTIAL_SIZE = 6;
    private static final int SAVED_REGIONS = 0x449FC;
    private static final int SAVED_REGION_SIZE = 0x40;
    private static final int SAVED_SHARED_NEIGHBORS = 0x92BFC;
    private static final int SAVED_END_MARK_OFFSET = 0x97A1C;
    private static final int SAVED_END_MARK = 0xDEADBEEF;
    /**
     * The offsets of the first and the second region WORD of a saved split-tile record, after its mask WORD.
     */
    private static final int SAVED_PARTIAL_FIRST_REGION = 0x02;
    private static final int SAVED_PARTIAL_SECOND_REGION = 0x04;
    /**
     * The offsets of the fields of a saved region record.
     */
    private static final int SAVED_TERRAIN_TYPE = 0x00;
    private static final int SAVED_COMPONENT = 0x02;
    private static final int SAVED_TILE_COUNT = 0x04;
    private static final int SAVED_WALKABLE_NEIGHBORS = 0x06;
    private static final int SAVED_NEIGHBORS = 0x07;
    private static final int SAVED_NEIGHBOR_LIST = 0x0C;
    private static final int SAVED_CENTER_X = 0x10;
    private static final int SAVED_CENTER_Y = 0x14;
    private static final int SAVED_LEFT = 0x18;
    private static final int SAVED_UPPER = 0x1A;
    private static final int SAVED_RIGHT = 0x1C;
    private static final int SAVED_LOWER = 0x1E;
    private static final int SAVED_COMPONENT_NEIGHBORS = 0x20;
    private static final int SAVED_ZERO_NEIGHBORS = 0x21;
    private static final int SAVED_INLINE_NEIGHBORS = 0x2C;

    /**
     * The horizontal and vertical tile steps to the eight tile neighbors, in neighbor direction order.
     */
    private static final int @NotNull [] HORIZONTAL_NEIGHBORS = {0, -1, 1, 0, -1, 1, -1, 1};
    private static final int @NotNull [] VERTICAL_NEIGHBORS = {-1, 0, 0, 1, -1, -1, 1, 1};

    /**
     * Creates a {@link TerrainRegions} instance through the fresh-map region passes.
     *
     * @param map           the map with expanded tile flags
     * @param terrain       the original terrain tables
     * @param miniatureGrid the original stride-{@code 1024} miniature grid before contour consumption
     * @return the created {@link TerrainRegions} instance
     * @throws UnsupportedOperationException if the map or the regions exceed the represented storage
     */
    public static @NotNull TerrainRegions create(@NotNull GameMap map, @NotNull TerrainTileset terrain, byte @NotNull [] miniatureGrid) {
        TerrainRegions result = new TerrainRegions();
        int width = map.getWidth();
        int height = map.getHeight();

        if (width < 1 || width > GRID_WIDTH || height < 1 || height > GRID_WIDTH || miniatureGrid.length != MINI_GRID_WIDTH * MINI_GRID_WIDTH) {
            throw Exceptions.newUnsupportedOperationException("Native terrain region memory is not represented.");
        }

        for (int vertical = 0; vertical < height; vertical++) {
            for (int horizontal = 0; horizontal < width; horizontal++) {
                int flags = map.getTileFlags(horizontal, vertical);
                int terrainType = BLOCKED_TERRAIN;

                if ((flags & (MapTileFlags.WALKABLE | MapTileFlags.HAS_CREEP)) != 0 && (flags & GROUND_LEVEL_FLAGS) != 0) {
                    terrainType = switch (flags & GROUND_LEVEL_FLAGS) {
                        case MapTileFlags.WALKABLE | MapTileFlags.MIDDLE -> MIDDLE_GROUND_TERRAIN;
                        case MapTileFlags.WALKABLE | MapTileFlags.HIGH -> HIGH_GROUND_TERRAIN;
                        default -> LOW_GROUND_TERRAIN;
                    };
                }

                result.tileRegions[vertical * GRID_WIDTH + horizontal] = (short) terrainType;
            }
        }

        result.regionCount              = (short) 1;
        result.terrainTypes[0]          = (short) BLOCKED_TERRAIN;
        result.components[0]            = (short) RESERVED_COMPONENT;
        result.tileCounts[0]            = (short) width;
        result.centerHorizontalFixed[0] = (width + 1) << HALF_TILE_FIXED_SHIFT;
        result.centerVerticalFixed[0]   = (height << TILE_FIXED_SHIFT) - HALF_TILE_FIXED;
        result.leftBounds[0]            = (short) 0;
        result.upperBounds[0]           = (short) ((height - 1) << TerrainTileset.TILE_SHIFT);
        result.rightBounds[0]           = (short) (width << TerrainTileset.TILE_SHIFT);
        result.lowerBounds[0]           = (short) (height << TerrainTileset.TILE_SHIFT);

        for (int horizontal = 0; horizontal < width; horizontal++) {
            result.tileRegions[(height - 1) * GRID_WIDTH + horizontal] = (short) 0;
        }

        result.partition(width, height);
        result.rebuildNeighbors(width, height, miniatureGrid);
        result.mergeSmallRegions();
        result.compact(width, height);
        result.rebuildNeighbors(width, height, miniatureGrid);
        result.setPartialTileCount(0);
        TerrainRegionLookupUtils.remapPartialTiles(result, map, terrain, miniatureGrid);

        int regionCount = result.getRegionCount();

        if (regionCount > REGION_COUNT) {
            throw Exceptions.newUnsupportedOperationException("Native terrain region overflow notification is not represented.");
        }

        result.clearUnusedRegions(regionCount);
        result.countNeighborComponents();

        return result;
    }

    private static boolean isMiniatureSquareOpen(byte @NotNull [] grid, int offset) {
        return grid[offset] >= 0 && grid[offset + 1] >= 0 && grid[offset + MINI_GRID_WIDTH] >= 0 && grid[offset + (MINI_GRID_WIDTH + 1)] >= 0;
    }

    private static long mix(long hash, int value) {
        hash ^= Integer.toUnsignedLong(value);
        hash *= HASH_PRIME;

        return hash ^ hash >>> HASH_FOLD_SHIFT;
    }

    private short regionCount;
    private final short @NotNull [] tileRegions;

    private final short @NotNull [] terrainTypes;
    private final short @NotNull [] components;
    private final short @NotNull [] tileCounts;

    private final int @NotNull [] centerHorizontalFixed;
    private final int @NotNull [] centerVerticalFixed;
    private final short @NotNull [] leftBounds;
    private final short @NotNull [] upperBounds;
    private final short @NotNull [] rightBounds;
    private final short @NotNull [] lowerBounds;

    private final byte @NotNull [] walkableNeighborCounts;
    private final byte @NotNull [] neighborCounts;
    private final byte @NotNull [] neighboringComponentCounts;
    private final byte @NotNull [] zeroNeighborCounts;
    private final short @NotNull [] inlineNeighborRegions;
    private final short @NotNull [] sharedNeighborRegions;
    /**
     * Nonnegative starts address inline entries; values below {@code -1} encode shared starts; {@code -1} is uninitialized.
     */
    private final int @NotNull [] neighborStarts;
    /**
     * The end of the shared neighbor entries in use (the global buffer's next entry).
     */
    private int sharedNeighborEnd;

    private final short @NotNull [] partialTileMasks;
    private final short @NotNull [] partialFirstRegions;
    private final short @NotNull [] partialSecondRegions;
    private int partialTileCount;

    private TerrainRegions() {
        this.regionCount = 0;
        this.tileRegions = new short[TILE_COUNT];

        this.terrainTypes = new short[REGION_COUNT];
        this.components   = new short[REGION_COUNT];
        this.tileCounts   = new short[REGION_COUNT];

        this.centerHorizontalFixed = new int[REGION_COUNT];
        this.centerVerticalFixed   = new int[REGION_COUNT];
        this.leftBounds            = new short[REGION_COUNT];
        this.upperBounds           = new short[REGION_COUNT];
        this.rightBounds           = new short[REGION_COUNT];
        this.lowerBounds           = new short[REGION_COUNT];

        this.walkableNeighborCounts     = new byte[REGION_COUNT];
        this.neighborCounts             = new byte[REGION_COUNT];
        this.neighboringComponentCounts = new byte[REGION_COUNT];
        this.zeroNeighborCounts         = new byte[REGION_COUNT];
        this.inlineNeighborRegions      = new short[REGION_COUNT * INLINE_NEIGHBOR_COUNT];
        this.sharedNeighborRegions      = new short[SHARED_NEIGHBOR_CAPACITY];
        this.neighborStarts             = new int[REGION_COUNT];
        Arrays.fill(this.neighborStarts, -1);
        this.sharedNeighborEnd = 0;

        this.partialTileMasks     = new short[PARTIAL_REGION_CAPACITY];
        this.partialFirstRegions  = new short[PARTIAL_REGION_CAPACITY];
        this.partialSecondRegions = new short[PARTIAL_REGION_CAPACITY];
        this.partialTileCount     = -1;
    }

    /**
     * @return the retained unsigned region count
     */
    public int getRegionCount() {
        return Short.toUnsignedInt(this.regionCount);
    }

    /**
     * @param regionIndex the region index
     * @return the region's unsigned terrain classification WORD
     */
    public int getTerrainType(int regionIndex) {
        return Short.toUnsignedInt(this.terrainTypes[regionIndex]);
    }

    /**
     * @param regionIndex the region index
     * @return the connected component of the region: regions with the same component reach each other on the ground
     */
    @Override
    public int getComponent(int regionIndex) {
        return Short.toUnsignedInt(this.components[regionIndex]);
    }

    /**
     * @param regionIndex the region index
     * @return the signed horizontal pixel center after fixed-point truncation
     */
    public int getCenterX(int regionIndex) {
        return (short) (this.centerHorizontalFixed[regionIndex] >> FIXED_POINT_SHIFT);
    }

    /**
     * @param regionIndex the region index
     * @return the signed vertical pixel center after fixed-point truncation
     */
    public int getCenterY(int regionIndex) {
        return (short) (this.centerVerticalFixed[regionIndex] >> FIXED_POINT_SHIFT);
    }

    /**
     * @param regionIndex the region index
     * @return the number of walkable neighbors, which come first in the neighbor list
     */
    @Override
    public int getWalkableNeighborCount(int regionIndex) {
        return Byte.toUnsignedInt(this.walkableNeighborCounts[regionIndex]);
    }

    /**
     * @param regionIndex   the region index
     * @param neighborIndex the position in the neighbor list, below the neighbor count
     * @return the neighboring region index
     * @throws IndexOutOfBoundsException if the neighbor index is not below the neighbor count
     * @throws IllegalStateException     if the region's neighbors have not been initialized
     */
    @Override
    public int getNeighborRegion(int regionIndex, int neighborIndex) {
        Objects.checkIndex(neighborIndex, Byte.toUnsignedInt(this.neighborCounts[regionIndex]));

        int start = this.neighborStarts[regionIndex];

        if (start == -1) {
            throw Exceptions.newIllegalStateException("Region neighbors have not been initialized.");
        }

        return start >= 0
               ? Short.toUnsignedInt(this.inlineNeighborRegions[start + neighborIndex])
               : Short.toUnsignedInt(this.sharedNeighborRegions[-start - SHARED_START_BIAS + neighborIndex]);
    }

    /**
     * @param regionIndex the region index
     * @return the unsigned tile-count WORD read by the computer defense planner
     */
    public int getTileCount(int regionIndex) {
        return Short.toUnsignedInt(this.tileCounts[regionIndex]);
    }

    /**
     * @param regionIndex the region index
     * @return the unsigned count of distinct neighboring components
     */
    public int getNeighboringComponentCount(int regionIndex) {
        return Byte.toUnsignedInt(this.neighboringComponentCounts[regionIndex]);
    }

    /**
     * @param regionIndex the region index
     * @return the signed count of region-zero neighbors
     */
    public int getZeroNeighborCount(int regionIndex) {
        return this.zeroNeighborCounts[regionIndex];
    }

    /**
     * @param regionIndex the region index
     * @return the number of neighbors of the region, walkable or not
     */
    @Override
    public int getNeighborCount(int regionIndex) {
        return Byte.toUnsignedInt(this.neighborCounts[regionIndex]);
    }

    /**
     * @param regionIndex the region index
     * @return the signed left bound WORD
     */
    public int getLeftBound(int regionIndex) {
        return this.leftBounds[regionIndex];
    }

    /**
     * @param regionIndex the region index
     * @return the signed upper bound WORD
     */
    public int getUpperBound(int regionIndex) {
        return this.upperBounds[regionIndex];
    }

    /**
     * @param regionIndex the region index
     * @return the signed right bound WORD
     */
    public int getRightBound(int regionIndex) {
        return this.rightBounds[regionIndex];
    }

    /**
     * @param regionIndex the region index
     * @return the signed lower bound WORD
     */
    public int getLowerBound(int regionIndex) {
        return this.lowerBounds[regionIndex];
    }

    int getPartialTileCount() {
        if (this.partialTileCount < 0) {
            throw Exceptions.newIllegalStateException("Native partial tile storage has not been initialized.");
        }

        return this.partialTileCount;
    }

    void setPartialTileCount(int count) {
        if (count < 0 || count > this.partialTileMasks.length) {
            throw Exceptions.newUnsupportedOperationException("Native partial tile records reference unrepresented storage.");
        }

        this.partialTileCount = count;
    }

    /**
     * @return the number of region indices: every region index is below it
     */
    @Override
    public int getRegionCapacity() {
        return REGION_COUNT;
    }

    /**
     * Finds the region of a map position.
     * <p>
     * The SC:R lookup is {@link TerrainRegionLookupUtils#lookup}.
     *
     * @param x the x coordinate in pixels
     * @param y the y coordinate in pixels
     * @return the region index
     */
    @Override
    public int findRegion(int x, int y) {
        return TerrainRegionLookupUtils.lookup(this, x, y);
    }

    /**
     * @param regionIndex the region index
     * @return the horizontal center, fixed-point
     */
    @Override
    public int getCenterHorizontalFixed(int regionIndex) {
        return this.centerHorizontalFixed[regionIndex];
    }

    /**
     * @param regionIndex the region index
     * @return the vertical center, fixed-point
     */
    @Override
    public int getCenterVerticalFixed(int regionIndex) {
        return this.centerVerticalFixed[regionIndex];
    }

    int getTileRegion(long tileIndex) {
        Objects.checkIndex(tileIndex, this.tileRegions.length);

        return Short.toUnsignedInt(this.tileRegions[(int) tileIndex]);
    }

    void setTileRegion(int tileIndex, int region) {
        this.tileRegions[tileIndex] = (short) region;
    }

    int getPartialTileMask(int regionIdentifier) {
        return Short.toUnsignedInt(this.partialTileMasks[regionIdentifier - PARTIAL_REGION_BASE]);
    }

    int getPartialFirstRegion(int regionIdentifier) {
        return Short.toUnsignedInt(this.partialFirstRegions[regionIdentifier - PARTIAL_REGION_BASE]);
    }

    int getPartialSecondRegion(int regionIdentifier) {
        return Short.toUnsignedInt(this.partialSecondRegions[regionIdentifier - PARTIAL_REGION_BASE]);
    }

    void setPartialTile(int index, int mask, int firstRegion, int secondRegion) {
        this.partialTileMasks[index]     = (short) mask;
        this.partialSecondRegions[index] = (short) secondRegion;
        this.partialFirstRegions[index]  = (short) firstRegion;
    }


    /**
     * Writes the regions into the {@link #SAVED_SIZE}-byte pathing data of a saved game: the WORD region count, a WORD of {@code 0}, the DWORD end of
     * the shared neighbor entries, the DWORD split tile count, the {@code 256} x {@code 256} tile-region WORDs, the {@code 25000} six-byte split
     * tiles (mask, first and second region), the {@code 5000} {@code 64}-byte region records, the {@code 10000} shared neighbor WORDs and the end
     * mark {@code 0xDEADBEEF}.
     * <p>
     * Region records hold terrain, component, tile-count and neighbor metadata, fixed-point centers, bounds and inline neighbors. The neighbor-list
     * value is a shared entry index or {@code -1} for inline storage; records past the region count write {@code 0}. Reserved fields are left
     * unchanged.
     *
     * @param data the pathing data, little-endian, at offset {@code 0}
     */
    public void writeSaveData(@NotNull ByteBuffer data) {
        data.putShort(SAVED_REGION_COUNT, this.regionCount);
        data.putInt(SAVED_SHARED_END, this.sharedNeighborEnd);
        data.putInt(SAVED_PARTIAL_COUNT, this.getPartialTileCount());

        for (int tile = 0; tile < this.tileRegions.length; tile++) {
            data.putShort(SAVED_TILE_REGIONS + tile * Short.BYTES, this.tileRegions[tile]);
        }

        for (int partial = 0; partial < PARTIAL_REGION_CAPACITY; partial++) {
            int entry = SAVED_PARTIAL_TILES + partial * SAVED_PARTIAL_SIZE;

            data.putShort(entry, this.partialTileMasks[partial]);
            data.putShort(entry + SAVED_PARTIAL_FIRST_REGION, this.partialFirstRegions[partial]);
            data.putShort(entry + SAVED_PARTIAL_SECOND_REGION, this.partialSecondRegions[partial]);
        }

        for (int region = 0; region < REGION_COUNT; region++) {
            int entry = SAVED_REGIONS + region * SAVED_REGION_SIZE;
            int start = this.neighborStarts[region];

            data.putShort(entry + SAVED_TERRAIN_TYPE, this.terrainTypes[region]);
            data.putShort(entry + SAVED_COMPONENT, this.components[region]);
            data.putShort(entry + SAVED_TILE_COUNT, this.tileCounts[region]);
            data.put(entry + SAVED_WALKABLE_NEIGHBORS, this.walkableNeighborCounts[region]);
            data.put(entry + SAVED_NEIGHBORS, this.neighborCounts[region]);
            data.putInt(entry + SAVED_NEIGHBOR_LIST, region >= this.regionCount ? 0 : start < -1 ? -start - SHARED_START_BIAS : -1);
            data.putInt(entry + SAVED_CENTER_X, this.centerHorizontalFixed[region]);
            data.putInt(entry + SAVED_CENTER_Y, this.centerVerticalFixed[region]);
            data.putShort(entry + SAVED_LEFT, this.leftBounds[region]);
            data.putShort(entry + SAVED_UPPER, this.upperBounds[region]);
            data.putShort(entry + SAVED_RIGHT, this.rightBounds[region]);
            data.putShort(entry + SAVED_LOWER, this.lowerBounds[region]);
            data.put(entry + SAVED_COMPONENT_NEIGHBORS, this.neighboringComponentCounts[region]);
            data.put(entry + SAVED_ZERO_NEIGHBORS, this.zeroNeighborCounts[region]);

            for (int neighbor = 0; neighbor < INLINE_NEIGHBOR_COUNT; neighbor++) {
                data.putShort(entry + SAVED_INLINE_NEIGHBORS + neighbor * Short.BYTES,
                              this.inlineNeighborRegions[region * INLINE_NEIGHBOR_COUNT + neighbor]);
            }
        }

        for (int neighbor = 0; neighbor < this.sharedNeighborRegions.length; neighbor++) {
            data.putShort(SAVED_SHARED_NEIGHBORS + neighbor * Short.BYTES, this.sharedNeighborRegions[neighbor]);
        }

        data.putInt(SAVED_END_MARK_OFFSET, SAVED_END_MARK);
    }

    /**
     * Reads the regions from the pathing data {@link #writeSaveData} wrote.
     *
     * @param data the pathing data, little-endian, at offset {@code 0}
     */
    public void readSaveData(@NotNull ByteBuffer data) {
        this.regionCount       = data.getShort(SAVED_REGION_COUNT);
        this.sharedNeighborEnd = data.getInt(SAVED_SHARED_END);
        this.partialTileCount  = data.getInt(SAVED_PARTIAL_COUNT);

        for (int tile = 0; tile < this.tileRegions.length; tile++) {
            this.tileRegions[tile] = data.getShort(SAVED_TILE_REGIONS + tile * Short.BYTES);
        }

        for (int partial = 0; partial < PARTIAL_REGION_CAPACITY; partial++) {
            int entry = SAVED_PARTIAL_TILES + partial * SAVED_PARTIAL_SIZE;

            this.partialTileMasks[partial]     = data.getShort(entry);
            this.partialFirstRegions[partial]  = data.getShort(entry + SAVED_PARTIAL_FIRST_REGION);
            this.partialSecondRegions[partial] = data.getShort(entry + SAVED_PARTIAL_SECOND_REGION);
        }

        for (int region = 0; region < REGION_COUNT; region++) {
            int entry = SAVED_REGIONS + region * SAVED_REGION_SIZE;
            int list = data.getInt(entry + SAVED_NEIGHBOR_LIST);

            this.terrainTypes[region]               = data.getShort(entry + SAVED_TERRAIN_TYPE);
            this.components[region]                 = data.getShort(entry + SAVED_COMPONENT);
            this.tileCounts[region]                 = data.getShort(entry + SAVED_TILE_COUNT);
            this.walkableNeighborCounts[region]     = data.get(entry + SAVED_WALKABLE_NEIGHBORS);
            this.neighborCounts[region]             = data.get(entry + SAVED_NEIGHBORS);
            this.neighborStarts[region]             = list == -1 ? region * INLINE_NEIGHBOR_COUNT : -list - SHARED_START_BIAS;
            this.centerHorizontalFixed[region]      = data.getInt(entry + SAVED_CENTER_X);
            this.centerVerticalFixed[region]        = data.getInt(entry + SAVED_CENTER_Y);
            this.leftBounds[region]                 = data.getShort(entry + SAVED_LEFT);
            this.upperBounds[region]                = data.getShort(entry + SAVED_UPPER);
            this.rightBounds[region]                = data.getShort(entry + SAVED_RIGHT);
            this.lowerBounds[region]                = data.getShort(entry + SAVED_LOWER);
            this.neighboringComponentCounts[region] = data.get(entry + SAVED_COMPONENT_NEIGHBORS);
            this.zeroNeighborCounts[region]         = data.get(entry + SAVED_ZERO_NEIGHBORS);

            int inlineNeighborStart = region * INLINE_NEIGHBOR_COUNT;

            for (int neighbor = 0; neighbor < INLINE_NEIGHBOR_COUNT; neighbor++) {
                this.inlineNeighborRegions[inlineNeighborStart + neighbor] = data.getShort(entry + SAVED_INLINE_NEIGHBORS + neighbor * Short.BYTES);
            }
        }

        for (int neighbor = 0; neighbor < this.sharedNeighborRegions.length; neighbor++) {
            this.sharedNeighborRegions[neighbor] = data.getShort(SAVED_SHARED_NEIGHBORS + neighbor * Short.BYTES);
        }
    }

    /**
     * Mixes retained game values in a fixed field order, independently of the saved binary layout.
     *
     * @param hash the preceding semantic state hash
     * @return the hash including tile, region, partial-tile and reusable neighbor state
     */
    public long computeStateHash(long hash) {
        hash = mix(hash, this.regionCount);
        hash = mix(hash, this.partialTileCount);

        for (short value : this.tileRegions) {
            hash = mix(hash, value);
        }

        for (short value : this.terrainTypes) {
            hash = mix(hash, value);
        }

        for (short value : this.components) {
            hash = mix(hash, value);
        }

        for (short value : this.tileCounts) {
            hash = mix(hash, value);
        }

        for (byte value : this.walkableNeighborCounts) {
            hash = mix(hash, value);
        }

        for (byte value : this.neighborCounts) {
            hash = mix(hash, value);
        }

        for (int value : this.centerHorizontalFixed) {
            hash = mix(hash, value);
        }

        for (int value : this.centerVerticalFixed) {
            hash = mix(hash, value);
        }

        for (short value : this.leftBounds) {
            hash = mix(hash, value);
        }

        for (short value : this.upperBounds) {
            hash = mix(hash, value);
        }

        for (short value : this.rightBounds) {
            hash = mix(hash, value);
        }

        for (short value : this.lowerBounds) {
            hash = mix(hash, value);
        }

        for (byte value : this.neighboringComponentCounts) {
            hash = mix(hash, value);
        }

        for (byte value : this.zeroNeighborCounts) {
            hash = mix(hash, value);
        }

        for (short value : this.inlineNeighborRegions) {
            hash = mix(hash, value);
        }

        for (short value : this.partialTileMasks) {
            hash = mix(hash, value);
        }

        for (short value : this.partialFirstRegions) {
            hash = mix(hash, value);
        }

        for (short value : this.partialSecondRegions) {
            hash = mix(hash, value);
        }

        for (short value : this.sharedNeighborRegions) {
            hash = mix(hash, value);
        }

        for (int value : this.neighborStarts) {
            hash = mix(hash, value);
        }

        return hash;
    }

    /**
     * Compares the connected components containing two pixel positions. A position whose tile index lies outside the tile grid
     * ({@link TerrainRegionLookupUtils#isInTileGrid}) shares no component.
     *
     * @param sourceHorizontal      the source horizontal pixel coordinate
     * @param sourceVertical        the source vertical pixel coordinate
     * @param destinationHorizontal the destination horizontal DWORD
     * @param destinationVertical   the destination vertical DWORD
     * @return {@code true} if both positions lie in the tile grid and their region records have the same component WORD, {@code false} otherwise
     */
    public boolean sharesComponent(int sourceHorizontal, int sourceVertical, int destinationHorizontal, int destinationVertical) {
        if (!TerrainRegionLookupUtils.isInTileGrid(sourceHorizontal, sourceVertical) ||
            !TerrainRegionLookupUtils.isInTileGrid(destinationHorizontal, destinationVertical)) {
            return false;
        }

        int sourceRegion = TerrainRegionLookupUtils.lookup(this, sourceHorizontal, sourceVertical);
        int destinationRegion = TerrainRegionLookupUtils.lookup(this, destinationHorizontal, destinationVertical);

        return this.components[sourceRegion] == this.components[destinationRegion];
    }

    private void setNeighborRegion(int regionIndex, int neighborIndex, int neighbor) {
        int start = this.neighborStarts[regionIndex];

        if (start >= 0) {
            this.inlineNeighborRegions[start + neighborIndex] = (short) neighbor;
        } else {
            this.sharedNeighborRegions[-start - SHARED_START_BIAS + neighborIndex] = (short) neighbor;
        }
    }

    private void copyRegion(int source, int destination) {
        this.terrainTypes[destination] = this.terrainTypes[source];
        this.components[destination]   = this.components[source];
        this.tileCounts[destination]   = this.tileCounts[source];

        this.walkableNeighborCounts[destination] = this.walkableNeighborCounts[source];
        this.neighborCounts[destination]         = this.neighborCounts[source];

        this.centerHorizontalFixed[destination] = this.centerHorizontalFixed[source];
        this.centerVerticalFixed[destination]   = this.centerVerticalFixed[source];
        this.leftBounds[destination]            = this.leftBounds[source];
        this.upperBounds[destination]           = this.upperBounds[source];
        this.rightBounds[destination]           = this.rightBounds[source];
        this.lowerBounds[destination]           = this.lowerBounds[source];

        this.neighboringComponentCounts[destination] = this.neighboringComponentCounts[source];
        this.zeroNeighborCounts[destination]         = this.zeroNeighborCounts[source];

        System.arraycopy(this.inlineNeighborRegions, source * INLINE_NEIGHBOR_COUNT,
                         this.inlineNeighborRegions, destination * INLINE_NEIGHBOR_COUNT,
                         INLINE_NEIGHBOR_COUNT);
    }

    private void clearUnusedRegions(int count) {
        Arrays.fill(this.terrainTypes, count, REGION_COUNT, (short) 0);
        Arrays.fill(this.components, count, REGION_COUNT, (short) 0);
        Arrays.fill(this.tileCounts, count, REGION_COUNT, (short) 0);
        Arrays.fill(this.walkableNeighborCounts, count, REGION_COUNT, (byte) 0);
        Arrays.fill(this.neighborCounts, count, REGION_COUNT, (byte) 0);

        Arrays.fill(this.centerHorizontalFixed, count, REGION_COUNT, 0);
        Arrays.fill(this.centerVerticalFixed, count, REGION_COUNT, 0);
        Arrays.fill(this.leftBounds, count, REGION_COUNT, (short) 0);
        Arrays.fill(this.upperBounds, count, REGION_COUNT, (short) 0);
        Arrays.fill(this.rightBounds, count, REGION_COUNT, (short) 0);
        Arrays.fill(this.lowerBounds, count, REGION_COUNT, (short) 0);

        Arrays.fill(this.neighboringComponentCounts, count, REGION_COUNT, (byte) 0);
        Arrays.fill(this.zeroNeighborCounts, count, REGION_COUNT, (byte) 0);
        Arrays.fill(this.inlineNeighborRegions, count * INLINE_NEIGHBOR_COUNT, this.inlineNeighborRegions.length, (short) 0);
    }

    private void growRectangle(int horizontal, int vertical, int type, int width, int height, int @NotNull [] rectangle) {
        int right = horizontal + 1;
        int bottom = vertical + 1;
        int maximumRight = Math.min(horizontal + MAXIMUM_RECTANGLE_WIDTH, width);
        int maximumBottom = Math.min(vertical + MAXIMUM_RECTANGLE_HEIGHT, height);
        boolean growRight = true;
        boolean growBottom = true;
        boolean cornerMatches = true;

        while (right < maximumRight && bottom < maximumBottom && (growRight || growBottom)) {
            if (growRight) {
                for (int row = vertical; row < bottom; row++) {
                    if (this.getTileRegion((long) row * GRID_WIDTH + right) != type) {
                        growRight = false;

                        break;
                    }
                }
            }

            if (growBottom) {
                for (int column = horizontal; column < right; column++) {
                    if (this.getTileRegion((long) bottom * GRID_WIDTH + column) != type) {
                        growBottom = false;

                        break;
                    }
                }
            }

            cornerMatches &= this.getTileRegion((long) bottom * GRID_WIDTH + right) == type;

            if (cornerMatches) {
                if (growBottom) {
                    bottom++;
                }

                if (growRight) {
                    right++;
                }
            } else if (growBottom) {
                bottom++;
            } else if (growRight) {
                right++;
            }
        }

        int rectangleWidth = right - horizontal;
        int rectangleHeight = bottom - vertical;

        if (rectangleWidth > rectangleHeight) {
            rectangleWidth = Math.min(rectangleWidth, rectangleHeight * MAXIMUM_ASPECT_RATIO);
        } else {
            rectangleHeight = Math.min(rectangleHeight, rectangleWidth * MAXIMUM_ASPECT_RATIO);
        }

        rectangle[RECTANGLE_LEFT]   = horizontal;
        rectangle[RECTANGLE_TOP]    = vertical;
        rectangle[RECTANGLE_RIGHT]  = horizontal + rectangleWidth;
        rectangle[RECTANGLE_BOTTOM] = vertical + rectangleHeight;
    }

    private void absorbRectangle(int @NotNull [] rectangle, int region, int type, int width, int height) {
        // Both forward scans include the writes made earlier in the same scan.
        for (int pass = 0; pass < ABSORB_PASSES; pass++) {
            for (int vertical = rectangle[RECTANGLE_TOP]; vertical < rectangle[RECTANGLE_BOTTOM]; vertical++) {
                for (int horizontal = rectangle[RECTANGLE_LEFT]; horizontal < rectangle[RECTANGLE_RIGHT]; horizontal++) {
                    int tileIndex = vertical * GRID_WIDTH + horizontal;

                    if (this.getTileRegion(tileIndex) == type &&
                        (vertical > 0 && this.getTileRegion(tileIndex - GRID_WIDTH) == region ||
                         horizontal > 0 && this.getTileRegion(tileIndex - 1) == region ||
                         horizontal + 1 < width && this.getTileRegion(tileIndex + 1) == region ||
                         vertical + 1 < height && this.getTileRegion(tileIndex + GRID_WIDTH) == region)) {
                        this.tileRegions[tileIndex] = (short) region;
                    }
                }
            }
        }
    }

    private void partition(int width, int height) {
        int horizontalCursor = 0;
        int verticalCursor = 0;
        int areaThreshold = INITIAL_AREA_THRESHOLD;
        boolean expandedSmallRegions = false;
        int[] candidate = new int[RECTANGLE_SIZE];
        int[] best = new int[RECTANGLE_SIZE];

        while (true) {
            Arrays.fill(best, 0);

            int bestArea = 0;
            int bestType = 0;
            int attempts = 0;
            int horizontal = horizontalCursor;
            int vertical = verticalCursor;

            if (horizontal >= width) {
                horizontal = 0;
                vertical   = (vertical + 1) % height;
            }

            int initialHorizontal = horizontal;
            int initialVertical = vertical;

            do {
                int type = this.getTileRegion((long) vertical * GRID_WIDTH + horizontal);

                if (type >= REGION_COUNT) {
                    this.growRectangle(horizontal, vertical, type, width, height, candidate);
                    attempts++;

                    int area = (candidate[RECTANGLE_RIGHT] - candidate[RECTANGLE_LEFT]) * (candidate[RECTANGLE_BOTTOM] - candidate[RECTANGLE_TOP]);

                    if (area > bestArea) {
                        bestArea = area;
                        bestType = type;
                        System.arraycopy(candidate, 0, best, 0, RECTANGLE_SIZE);
                    }

                    if (bestArea >= areaThreshold || attempts > ATTEMPT_LIMIT) {
                        horizontalCursor = best[RECTANGLE_RIGHT];
                        verticalCursor   = best[RECTANGLE_TOP];

                        break;
                    }

                    horizontal = candidate[RECTANGLE_RIGHT] - 1;
                }

                horizontal++;

                if (horizontal >= width) {
                    horizontal = 0;
                    vertical   = (vertical + 1) % height;
                }
            } while (horizontal != initialHorizontal || vertical != initialVertical);

            int region = Short.toUnsignedInt(this.regionCount);

            if (region >= REGION_COUNT) {
                throw Exceptions.newUnsupportedOperationException("Native terrain partition overflow failure is not represented.");
            }

            if (bestArea == 0) {
                return;
            }

            for (vertical = best[RECTANGLE_TOP]; vertical < best[RECTANGLE_BOTTOM]; vertical++) {
                for (horizontal = best[RECTANGLE_LEFT]; horizontal < best[RECTANGLE_RIGHT]; horizontal++) {
                    this.tileRegions[vertical * GRID_WIDTH + horizontal] = (short) region;
                }
            }

            this.centerHorizontalFixed[region] = best[RECTANGLE_LEFT] + (best[RECTANGLE_RIGHT] - best[RECTANGLE_LEFT]) / RECTANGLE_CENTER_DIVISOR;
            this.centerVerticalFixed[region]   = best[RECTANGLE_TOP] + (best[RECTANGLE_BOTTOM] - best[RECTANGLE_TOP]) / RECTANGLE_CENTER_DIVISOR;
            best[RECTANGLE_LEFT]               = Math.max(0, best[RECTANGLE_LEFT] - 1);
            best[RECTANGLE_TOP]                = Math.max(0, best[RECTANGLE_TOP] - 1);
            best[RECTANGLE_RIGHT]              = Math.min(width, best[RECTANGLE_RIGHT] + 1);
            best[RECTANGLE_BOTTOM]             = Math.min(height, best[RECTANGLE_BOTTOM] + 1);
            this.absorbRectangle(best, region, bestType, width, height);

            this.leftBounds[region]  = (short) best[RECTANGLE_LEFT];
            this.upperBounds[region] = (short) best[RECTANGLE_TOP];
            this.rightBounds[region] = (short) best[RECTANGLE_RIGHT];
            this.lowerBounds[region] = (short) best[RECTANGLE_BOTTOM];

            this.terrainTypes[region] = (short) bestType;
            this.regionCount          = (short) (region + 1);
            areaThreshold             = bestArea;

            if (bestArea <= SMALL_REGION_AREA && !expandedSmallRegions) {
                expandedSmallRegions = true;

                for (int priorRegion = 1; priorRegion <= region; priorRegion++) {
                    candidate[RECTANGLE_LEFT]   = Math.max(0, this.leftBounds[priorRegion] - 1);
                    candidate[RECTANGLE_TOP]    = Math.max(0, this.upperBounds[priorRegion] - 1);
                    candidate[RECTANGLE_RIGHT]  = Math.min(width, this.rightBounds[priorRegion] + 1);
                    candidate[RECTANGLE_BOTTOM] = Math.min(height, this.lowerBounds[priorRegion] + 1);

                    this.leftBounds[priorRegion]  = (short) candidate[RECTANGLE_LEFT];
                    this.upperBounds[priorRegion] = (short) candidate[RECTANGLE_TOP];
                    this.rightBounds[priorRegion] = (short) candidate[RECTANGLE_RIGHT];
                    this.lowerBounds[priorRegion] = (short) candidate[RECTANGLE_BOTTOM];

                    this.absorbRectangle(candidate, priorRegion, Short.toUnsignedInt(this.terrainTypes[priorRegion]), width, height);
                }
            }
        }
    }

    private void updateBounds(int width, int height) {
        int regionCount = Short.toUnsignedInt(this.regionCount);

        for (int region = 0; region < regionCount; region++) {
            this.tileCounts[region]  = (short) 0;
            this.leftBounds[region]  = Short.MAX_VALUE;
            this.upperBounds[region] = Short.MAX_VALUE;
            this.rightBounds[region] = -1;
            this.lowerBounds[region] = -1;
        }

        for (int vertical = 0; vertical < height; vertical++) {
            for (int horizontal = 0; horizontal < width; horizontal++) {
                int region = this.getTileRegion((long) vertical * GRID_WIDTH + horizontal);

                if (region >= REGION_COUNT) {
                    continue;
                }

                this.tileCounts[region]  = (short) (Short.toUnsignedInt(this.tileCounts[region]) + 1);
                this.leftBounds[region]  = (short) Math.min(this.leftBounds[region], horizontal * TerrainTileset.TILE_SIZE);
                this.upperBounds[region] = (short) Math.min(this.upperBounds[region], vertical * TerrainTileset.TILE_SIZE);
                this.rightBounds[region] = (short) Math.max(this.rightBounds[region], (horizontal + 1) * TerrainTileset.TILE_SIZE);
                this.lowerBounds[region] = (short) Math.max(this.lowerBounds[region], (vertical + 1) * TerrainTileset.TILE_SIZE);
            }
        }

        for (int region = 0; region < regionCount; region++) {
            if (Short.toUnsignedInt(this.tileCounts[region]) == 0 && Short.toUnsignedInt(this.terrainTypes[region]) != NO_REGION) {
                this.terrainTypes[region] = (short) NO_REGION;
            }
        }
    }

    private int buildNeighbors(int region, int width, int height, byte @NotNull [] grid, int sharedStart) {
        int left = this.leftBounds[region] / TerrainTileset.TILE_SIZE;
        int top = this.upperBounds[region] / TerrainTileset.TILE_SIZE;
        int right = this.rightBounds[region] / TerrainTileset.TILE_SIZE;
        int bottom = this.lowerBounds[region] / TerrainTileset.TILE_SIZE;
        boolean blocked = Short.toUnsignedInt(this.terrainTypes[region]) == BLOCKED_TERRAIN;
        int count = 0;

        for (int vertical = top; vertical < bottom; vertical++) {
            for (int horizontal = left; horizontal < right; horizontal++) {
                if (this.getTileRegion(vertical * GRID_WIDTH + horizontal) != region) {
                    continue;
                }

                for (int direction = 0; direction < NEIGHBOR_DIRECTIONS; direction++) {
                    int neighborHorizontal = horizontal + HORIZONTAL_NEIGHBORS[direction];
                    int neighborVertical = vertical + VERTICAL_NEIGHBORS[direction];

                    if (neighborHorizontal < 0 || neighborHorizontal >= width || neighborVertical < 0 || neighborVertical >= height) {
                        continue;
                    }

                    int neighbor = this.getTileRegion((long) neighborVertical * GRID_WIDTH + neighborHorizontal);

                    if (neighbor == NO_REGION || neighbor == region) {
                        continue;
                    }

                    if (!blocked && direction >= FIRST_CORNER_DIRECTION && Short.toUnsignedInt(this.terrainTypes[neighbor]) != BLOCKED_TERRAIN) {
                        int miniatureOffset = vertical * (MINI_TILES_PER_ROW * MINI_GRID_WIDTH) + horizontal * MINI_TILES_PER_ROW;
                        int currentCorner = miniatureOffset +
                                            (direction >= FIRST_LOWER_CORNER_DIRECTION ? HALF_TILE_MINI_TILES * MINI_GRID_WIDTH : 0) +
                                            ((direction & RIGHT_CORNER_BIT) != 0 ? HALF_TILE_MINI_TILES : 0);
                        int neighborCorner = currentCorner +
                                             (direction >= FIRST_LOWER_CORNER_DIRECTION ? HALF_TILE_MINI_TILES * MINI_GRID_WIDTH
                                                                                        : -HALF_TILE_MINI_TILES * MINI_GRID_WIDTH) +
                                             ((direction & RIGHT_CORNER_BIT) != 0 ? HALF_TILE_MINI_TILES : -HALF_TILE_MINI_TILES);
                        int horizontalCorner = currentCorner + ((direction & RIGHT_CORNER_BIT) != 0 ? HALF_TILE_MINI_TILES : -HALF_TILE_MINI_TILES);
                        int verticalCorner = currentCorner + (direction >= FIRST_LOWER_CORNER_DIRECTION ? HALF_TILE_MINI_TILES * MINI_GRID_WIDTH
                                                                                                        : -HALF_TILE_MINI_TILES * MINI_GRID_WIDTH);

                        if (!isMiniatureSquareOpen(grid, currentCorner) || !isMiniatureSquareOpen(grid, neighborCorner) ||
                            !isMiniatureSquareOpen(grid, horizontalCorner) && !isMiniatureSquareOpen(grid, verticalCorner)) {
                            continue;
                        }
                    }

                    boolean found = false;

                    for (int index = 0; index < count; index++) {
                        found |= Short.toUnsignedInt(this.sharedNeighborRegions[sharedStart + index]) == neighbor;
                    }

                    if (!found) {
                        if (sharedStart + count >= this.sharedNeighborRegions.length) {
                            throw Exceptions.newUnsupportedOperationException("Native terrain neighbor scratch overflow is not represented.");
                        }

                        this.sharedNeighborRegions[sharedStart + count] = (short) neighbor;
                        count++;
                    }
                }
            }
        }

        if (count <= INLINE_NEIGHBOR_COUNT) {
            this.neighborStarts[region] = region * INLINE_NEIGHBOR_COUNT;

            for (int index = 0; index < count; index++) {
                this.inlineNeighborRegions[region * INLINE_NEIGHBOR_COUNT + index] = this.sharedNeighborRegions[sharedStart + index];
            }

            for (int index = count; index < INLINE_NEIGHBOR_COUNT; index++) {
                this.inlineNeighborRegions[region * INLINE_NEIGHBOR_COUNT + index] = NO_REGION;
            }
        } else {
            this.neighborStarts[region] = -sharedStart - SHARED_START_BIAS;
            sharedStart += count;
        }

        this.zeroNeighborCounts[region] = (byte) 0;
        this.neighborCounts[region]     = (byte) count;

        return sharedStart;
    }

    private void rebuildNeighbors(int width, int height, byte @NotNull [] grid) {
        this.updateBounds(width, height);

        int regionCount = Short.toUnsignedInt(this.regionCount);
        int sharedStart = 0;
        int[] blockedNeighbors = new int[MAXIMUM_NEIGHBORS];
        int[] zeroNeighbors = new int[MAXIMUM_NEIGHBORS];

        for (int region = 0; region < regionCount; region++) {
            if (Short.toUnsignedInt(this.tileCounts[region]) == 0) {
                continue;
            }

            sharedStart = this.buildNeighbors(region, width, height, grid, sharedStart);

            int count = Byte.toUnsignedInt(this.neighborCounts[region]);
            int walkableCount = 0;
            int blockedCount = 0;
            int zeroCount = 0;

            for (int index = 0; index < count; index++) {
                int neighbor = this.getNeighborRegion(region, index);

                if (neighbor == 0) {
                    zeroNeighbors[zeroCount++] = neighbor;
                } else if (Short.toUnsignedInt(this.terrainTypes[neighbor]) == BLOCKED_TERRAIN) {
                    blockedNeighbors[blockedCount++] = neighbor;
                } else {
                    this.setNeighborRegion(region, walkableCount++, neighbor);
                }
            }

            this.walkableNeighborCounts[region] = (byte) walkableCount;

            for (int index = 0; index < blockedCount; index++) {
                this.setNeighborRegion(region, walkableCount++, blockedNeighbors[index]);
            }

            if (zeroCount != 0) {
                this.zeroNeighborCounts[region] = (byte) zeroCount;

                for (int index = 0; index < zeroCount; index++) {
                    this.setNeighborRegion(region, walkableCount++, zeroNeighbors[index]);
                }
            }
        }

        this.sharedNeighborEnd = sharedStart;

        // A center below the grid width is still the tile center of the partition; it becomes a fixed-point pixel center.
        for (int region = 1; region < regionCount; region++) {
            if (this.centerHorizontalFixed[region] < GRID_WIDTH) {
                this.centerHorizontalFixed[region] = (this.centerHorizontalFixed[region] << TILE_FIXED_SHIFT) + HALF_TILE_FIXED;
            }

            if (this.centerVerticalFixed[region] < GRID_WIDTH) {
                this.centerVerticalFixed[region] = (this.centerVerticalFixed[region] << TILE_FIXED_SHIFT) + HALF_TILE_FIXED;
            }
        }

        for (int region = 0; region < regionCount; region++) {
            if (Short.toUnsignedInt(this.components[region]) < RESERVED_COMPONENT) {
                this.components[region] = (short) 0;
            }
        }

        int component = 1;
        int[] regionStack = new int[REGION_COUNT];
        int[] neighborStack = new int[REGION_COUNT];

        for (int region = 0; region < regionCount; region++) {
            if (Short.toUnsignedInt(this.components[region]) != 0 || Short.toUnsignedInt(this.tileCounts[region]) == 0) {
                continue;
            }

            int depth = 0;

            regionStack[0]          = region;
            neighborStack[0]        = Short.toUnsignedInt(this.terrainTypes[region]) == BLOCKED_TERRAIN
                                      ? Byte.toUnsignedInt(this.walkableNeighborCounts[region])
                                      : 0;
            this.components[region] = (short) component;

            while (depth >= 0) {
                int currentRegion = regionStack[depth];
                int end = Byte.toUnsignedInt(Short.toUnsignedInt(this.terrainTypes[currentRegion]) == BLOCKED_TERRAIN
                                             ? this.neighborCounts[currentRegion]
                                             : this.walkableNeighborCounts[currentRegion]);

                if (neighborStack[depth] >= end) {
                    depth--;

                    continue;
                }

                int neighbor = this.getNeighborRegion(currentRegion, neighborStack[depth]++);

                if (Short.toUnsignedInt(this.components[neighbor]) == 0) {
                    this.components[neighbor] = (short) component;
                    regionStack[++depth]      = neighbor;
                    neighborStack[depth]      = Short.toUnsignedInt(this.terrainTypes[neighbor]) == BLOCKED_TERRAIN
                                                ? Byte.toUnsignedInt(this.walkableNeighborCounts[neighbor])
                                                : 0;
                }
            }

            component = (component + 1) & WORD_MASK;
        }
    }

    private void mergeSmallRegions() {
        int threshold = MERGE_INITIAL_THRESHOLD;
        int liveCount;
        int regionCount = Short.toUnsignedInt(this.regionCount);

        do {
            threshold += MERGE_THRESHOLD_STEP;

            for (int region = regionCount - 1; region >= 0; region--) {
                int count = Short.toUnsignedInt(this.tileCounts[region]);

                if (count == 0 || Short.toUnsignedInt(this.components[region]) >= RESERVED_COMPONENT || count >= threshold) {
                    continue;
                }

                int selected = -1;
                int selectedCount = 0;

                for (int index = 0; index < Byte.toUnsignedInt(this.neighborCounts[region]); index++) {
                    int neighbor = this.getNeighborRegion(region, index);
                    int neighborCount = Short.toUnsignedInt(this.tileCounts[neighbor]);

                    if (neighborCount != 0 && Short.toUnsignedInt(this.components[neighbor]) < RESERVED_COMPONENT &&
                        Short.toUnsignedInt(this.terrainTypes[neighbor]) == Short.toUnsignedInt(this.terrainTypes[region]) &&
                        (selected < 0 || neighborCount < selectedCount)) {
                        selected      = neighbor;
                        selectedCount = neighborCount;
                    }
                }

                if (selected < 0) {
                    continue;
                }

                for (int vertical = this.upperBounds[region] / TerrainTileset.TILE_SIZE;
                     vertical < this.lowerBounds[region] / TerrainTileset.TILE_SIZE; vertical++) {
                    for (int horizontal = this.leftBounds[region] / TerrainTileset.TILE_SIZE;
                         horizontal < this.rightBounds[region] / TerrainTileset.TILE_SIZE; horizontal++) {
                        int tileIndex = vertical * GRID_WIDTH + horizontal;

                        if (this.getTileRegion(tileIndex) == region) {
                            this.tileRegions[tileIndex] = (short) selected;
                        }
                    }
                }

                this.tileCounts[selected]  = (short) (selectedCount + count);
                this.tileCounts[region]    = (short) 0;
                this.terrainTypes[region]  = (short) NO_REGION;
                this.leftBounds[selected]  = (short) Math.min(this.leftBounds[selected], this.leftBounds[region]);
                this.upperBounds[selected] = (short) Math.min(this.upperBounds[selected], this.upperBounds[region]);
                this.rightBounds[selected] = (short) Math.max(this.rightBounds[selected], this.rightBounds[region]);
                this.lowerBounds[selected] = (short) Math.max(this.lowerBounds[selected], this.lowerBounds[region]);
            }

            liveCount = 0;

            for (int region = 0; region < regionCount; region++) {
                if (Short.toUnsignedInt(this.tileCounts[region]) != 0) {
                    liveCount++;
                }
            }
        } while (liveCount >= REGION_LIMIT);
    }

    private void compact(int width, int height) {
        int regionCount = Short.toUnsignedInt(this.regionCount);
        int[] mapping = new int[regionCount];
        int liveCount = 0;

        for (int region = 0; region < regionCount; region++) {
            mapping[region] = liveCount;

            if (Short.toUnsignedInt(this.tileCounts[region]) != 0) {
                liveCount++;
            }
        }

        for (int region = 0; region < regionCount; region++) {
            if (Short.toUnsignedInt(this.tileCounts[region]) == 0) {
                continue;
            }

            int neighborStart = this.neighborStarts[region];

            for (int index = 0; index < Byte.toUnsignedInt(this.neighborCounts[region]); index++) {
                this.setNeighborRegion(region, index, mapping[this.getNeighborRegion(region, index)]);
            }

            this.copyRegion(region, mapping[region]);
            this.neighborStarts[mapping[region]] = neighborStart;
        }

        this.regionCount = (short) liveCount;

        for (int vertical = 0; vertical < height; vertical++) {
            for (int horizontal = 0; horizontal < width; horizontal++) {
                int tileIndex = vertical * GRID_WIDTH + horizontal;

                this.tileRegions[tileIndex] = (short) mapping[this.getTileRegion(tileIndex)];
            }
        }
    }

    private void countNeighborComponents() {
        int[] scannedComponents = new int[COMPONENT_SCAN_CAPACITY];

        for (int region = 0; region < Short.toUnsignedInt(this.regionCount); region++) {
            int count = 0;

            for (int index = Byte.toUnsignedInt(this.walkableNeighborCounts[region]);
                 index < Byte.toUnsignedInt(this.neighborCounts[region]); index++) {
                int neighbor = this.getNeighborRegion(region, index);

                if (Short.toUnsignedInt(this.tileCounts[neighbor]) < MINIMUM_NEIGHBOR_TILES) {
                    continue;
                }

                int component = Short.toUnsignedInt(this.components[neighbor]);
                boolean found = false;

                for (int previous = 0; previous < count; previous++) {
                    found |= scannedComponents[previous] == component;
                }

                if (!found) {
                    if (++count >= COMPONENT_COUNT_LIMIT) {
                        break;
                    }

                    scannedComponents[count - 1] = component;
                }
            }

            this.neighboringComponentCounts[region] = (byte) (count < MINIMUM_COMPONENT_COUNT ? 0 : count);
        }
    }
}
