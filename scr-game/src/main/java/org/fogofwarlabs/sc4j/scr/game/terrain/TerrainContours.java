package org.fogofwarlabs.sc4j.scr.game.terrain;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Builds terrain contours and retains the placement-query extent state.
 */
public final class TerrainContours {
    /**
     * The number of contour arrays, one per direction.
     */
    public static final int DIRECTION_COUNT = 4;
    /**
     * The number of footprint control WORDs ({@link #getControl}).
     */
    public static final int CONTROL_COUNT = 12;
    /**
     * The size of a saved contour record.
     */
    public static final int SAVED_BOUNDARY_SIZE = 8;
    /**
     * The size of the saved contour header: four pointer marks, counts, capacities, controls.
     */
    public static final int SAVED_HEADER_SIZE = 0x38;

    /**
     * The contour directions, the edge a contour runs along: up, right, down and left.
     */
    private static final int DIRECTION_UP = 0;
    private static final int DIRECTION_RIGHT = 1;
    private static final int DIRECTION_DOWN = 2;
    private static final int DIRECTION_LEFT = 3;
    /**
     * The mask that wraps a direction.
     */
    private static final int DIRECTION_MASK = DIRECTION_COUNT - 1;
    /**
     * The direction bit of the right and the left edges, the vertical boundaries.
     */
    private static final int VERTICAL_BOUNDARY_BIT = 1;
    /**
     * The turn of a contour step that continues around an outer corner ({@link #HORIZONTAL_CORNERS}); {@code 0} turns into the next direction.
     */
    private static final int OUTER_CORNER_TURN = 1;

    /**
     * The mini-tiles of one tile row and column, the grid cells of a tile.
     */
    private static final int MINI_TILES_PER_ROW = TerrainRegions.MINI_TILES_PER_ROW;
    /**
     * The mini-tiles of one tile.
     */
    private static final int MINI_TILES_PER_TILE = MINI_TILES_PER_ROW * MINI_TILES_PER_ROW;
    /**
     * The last cell of a four-cell group of a grid row, and the mask that aligns a column to its group.
     */
    private static final int LAST_GROUP_CELL = MINI_TILES_PER_ROW - 1;

    /**
     * The blocked bit of a grid cell.
     */
    private static final int BLOCKED_SHIFT = 7;
    /**
     * A blocked grid cell.
     */
    private static final byte BLOCKED_CELL = (byte) 0x80;
    /**
     * The bits of a grid cell below the blocked bit.
     */
    private static final int UNBLOCKED_BITS = 0x7F;
    /**
     * The edge bit of the first direction; shifted left by a direction, it gives the edge bit of that direction.
     */
    private static final int DIRECTION_EDGE_BIT = 1;
    /**
     * The four edge bits of a grid cell, one per direction ({@code DIRECTION_EDGE_BIT << direction}).
     */
    private static final int EDGE_BITS = 0xF;
    private static final int EDGE_UP = DIRECTION_EDGE_BIT << DIRECTION_UP;
    private static final int EDGE_RIGHT = DIRECTION_EDGE_BIT << DIRECTION_RIGHT;
    private static final int EDGE_DOWN = DIRECTION_EDGE_BIT << DIRECTION_DOWN;
    private static final int EDGE_LEFT = DIRECTION_EDGE_BIT << DIRECTION_LEFT;
    /**
     * The blocked border columns at the left and right edges of the grid, and the smallest grid width.
     */
    private static final int BORDER_COLUMNS = 20;
    /**
     * The bottom rows of the grid whose border columns are blocked, and the smallest grid height.
     */
    private static final int BORDER_ROWS = 8;
    /**
     * The bottom rows of the grid that are blocked completely.
     */
    private static final int BLOCKED_BOTTOM_ROWS = 4;
    /**
     * The traversal step limit per grid cell before the contour production gives up.
     */
    private static final int TRANSITIONS_PER_CELL = 16;

    /**
     * The bias of the first coordinate of a record, which makes the signed value sort as unsigned.
     */
    private static final int SIGN_BIAS = 0x8000;
    /**
     * The two-bit fields of the record flags.
     */
    private static final int TWO_BIT_MASK = 0x3;
    /**
     * The low bit of a two-bit field of the record flags.
     */
    private static final int TWO_BIT_LOW_BIT = 1;
    /**
     * The factor that moves a bit into the high bit of a two-bit field of the record flags.
     */
    private static final int TWO_BIT_HIGH_FACTOR = 2;
    /**
     * The bit of a turn that the record offsets and the record flags use.
     */
    private static final int TURN_BIT = 1;
    /**
     * The positions of the turn bits and of the second two-bit field in the record flags.
     */
    private static final int LOW_TURN_SHIFT = 4;
    private static final int HIGH_TURN_SHIFT = 5;
    private static final int HIGH_FIELD_SHIFT = 2;
    /**
     * The bit positions of the fields of a sorted contour entry: the signed coordinate, the biased first coordinate, the last coordinate and the
     * record flags, above the direction in the low byte.
     */
    private static final int ENTRY_COORDINATE_SHIFT = 48;
    private static final int ENTRY_FIRST_SHIFT = 32;
    private static final int ENTRY_LAST_SHIFT = 16;
    private static final int ENTRY_FLAGS_SHIFT = 8;
    /**
     * The bit positions of the fields of a saved contour record above its coordinate WORD: the first and the last coordinate, the direction and the
     * record flags.
     */
    private static final int RECORD_FIRST_SHIFT = 16;
    private static final int RECORD_LAST_SHIFT = 32;
    private static final int RECORD_DIRECTION_SHIFT = 48;
    private static final int RECORD_FLAGS_SHIFT = 56;
    /**
     * The bits of a WORD and of a BYTE.
     */
    private static final int WORD_MASK = 0xFFFF;
    private static final int BYTE_MASK = 0xFF;

    /**
     * The unit type bit that makes the rest of the WORD a radius.
     */
    private static final int RADIUS_FLAG = 0x8000;
    /**
     * The radius bits of a radius WORD.
     */
    private static final int RADIUS_MASK = 0x7FFF;

    /**
     * The first direction and the previous turn of a contour that starts at a cell, indexed by its edge bits ({@code 255} for none).
     */
    private static final int @NotNull [] INITIAL_DIRECTIONS = {255, 0, 1, 0, 2, 0, 1, 0, 3, 0, 1, 0, 2, 0, 1, 0};
    private static final int @NotNull [] INITIAL_TURNS = {255, 1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 0, 1, 0, 1, 0};
    /**
     * The grid index, horizontal and vertical steps to the next cell, by direction.
     */
    private static final int @NotNull [] STEPS = {-TerrainRegions.MINI_GRID_WIDTH, 1, TerrainRegions.MINI_GRID_WIDTH, -1};
    private static final int @NotNull [] HORIZONTAL_STEPS = {0, 1, 0, -1};
    private static final int @NotNull [] VERTICAL_STEPS = {-1, 0, 1, 0};
    /**
     * The horizontal and vertical steps around an outer corner, by direction.
     */
    private static final int @NotNull [] HORIZONTAL_CORNERS = {1, 1, -1, -1};
    private static final int @NotNull [] VERTICAL_CORNERS = {-1, 1, 1, -1};
    /**
     * The pixel offsets of the start and the end of a contour record in its cell, by direction plus four times the turn.
     */
    private static final int @NotNull [] START_HORIZONTAL = {-1, 8, 8, -1, 0, 8, 7, -1};
    private static final int @NotNull [] START_VERTICAL = {-1, -1, 8, 8, -1, 0, 8, 7};
    private static final int @NotNull [] END_HORIZONTAL = {8, 8, -1, -1, 7, 8, 0, -1};
    private static final int @NotNull [] END_VERTICAL = {-1, 8, 8, -1, -1, 7, 8, 0};

    /**
     * The initial record capacity of each contour array.
     */
    private static final int INITIAL_CAPACITY = 5000;
    /**
     * The record capacity added to every contour array when one of them is full.
     */
    private static final int CAPACITY_GROWTH = 2500;
    /**
     * The mark a saved game stores in place of each contour array pointer.
     */
    private static final int SAVED_POINTER_MARK = 0xDEADBEEF;

    /**
     * The twelve footprint control WORDs: the four extents, four constant WORDs ({@code 1}, {@code -1}, {@code -1}, {@code 1}) and the four adjusted
     * extents.
     */
    private static final int CONTROL_UPPER = 0;
    private static final int CONTROL_NEGATIVE_RIGHT = 1;
    private static final int CONTROL_NEGATIVE_LOWER = 2;
    private static final int CONTROL_LEFT = 3;
    private static final int CONTROL_FIRST_ONE = 4;
    private static final int CONTROL_FIRST_MINUS_ONE = 5;
    private static final int CONTROL_SECOND_MINUS_ONE = 6;
    private static final int CONTROL_SECOND_ONE = 7;
    private static final int CONTROL_UPPER_PLUS_ONE = 8;
    private static final int CONTROL_COMPLEMENTED_RIGHT = 9;
    private static final int CONTROL_COMPLEMENTED_LOWER = 10;
    private static final int CONTROL_LEFT_PLUS_ONE = 11;
    /**
     * The number of constant control WORDs.
     */
    private static final int CONSTANT_CONTROL_COUNT = 4;
    /**
     * The divisor of a rectangle width or height that gives its extents around the center.
     */
    private static final int HALF_EXTENT_DIVISOR = 2;

    /**
     * The factor of the doubled coordinates of the placement search, which places the query half a pixel before or after a record coordinate.
     */
    private static final int DOUBLED_COORDINATE_FACTOR = 2;
    /**
     * The shift that halves the sum of the search bounds into their midpoint.
     */
    private static final int MIDPOINT_SHIFT = 1;

    /**
     * Creates a {@link TerrainContours} instance through the represented grid and contour production.
     *
     * @param map     the normalized original map tiles
     * @param tileset the original CV5 and VF4 data
     * @return the created {@link TerrainContours} instance
     * @throws UnsupportedOperationException if the map is too small or too large for the grid, the contour capacity or the tied boundary ordering is
     *                                       unsupported, or the contour traversal does not terminate
     */
    public static @NotNull TerrainContours create(@NotNull GameMap map, @NotNull TerrainTileset tileset) {
        int width = map.getWidth() * MINI_TILES_PER_ROW;
        int height = map.getHeight() * MINI_TILES_PER_ROW;
        byte[] grid = createGrid(map, tileset);
        TerrainContours result = new TerrainContours(TerrainRegions.create(map, tileset, grid));

        int searchHorizontal = 0;
        int searchVertical = 0;
        int transitions = 0;

        while (true) {
            int horizontal = searchHorizontal & ~LAST_GROUP_CELL;
            int vertical = searchVertical;
            int initialIndex = vertical * TerrainRegions.MINI_GRID_WIDTH + horizontal;
            int index;

            while (true) {
                index = vertical * TerrainRegions.MINI_GRID_WIDTH + horizontal;

                if (((grid[index] | grid[index + 1] | grid[index + (LAST_GROUP_CELL - 1)] | grid[index + LAST_GROUP_CELL]) & UNBLOCKED_BITS) != 0) {
                    while (grid[index] <= 0) {
                        index++;
                        horizontal++;
                    }

                    break;
                }

                horizontal += MINI_TILES_PER_ROW;

                if (horizontal == width) {
                    horizontal = 0;
                    vertical   = (vertical + 1) % height;
                }

                if (vertical * TerrainRegions.MINI_GRID_WIDTH + horizontal == initialIndex) {
                    for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
                        long[] entries = Arrays.copyOf(result.boundaries[direction], result.counts[direction]);

                        Arrays.sort(entries);

                        for (int entryIndex = 1; entryIndex < entries.length; entryIndex++) {
                            if (entries[entryIndex] >> ENTRY_FIRST_SHIFT == entries[entryIndex - 1] >> ENTRY_FIRST_SHIFT &&
                                entries[entryIndex] != entries[entryIndex - 1]) {
                                throw Exceptions.newUnsupportedOperationException("Native contour qsort contains an unresolved equal-key ordering.");
                            }
                        }

                        result.boundaries[direction] = entries;
                    }

                    return result;
                }
            }

            searchHorizontal = horizontal;
            searchVertical   = vertical;

            int direction = INITIAL_DIRECTIONS[grid[index] & EDGE_BITS];
            int previousTurn = INITIAL_TURNS[grid[index] & EDGE_BITS];

            if (direction == DIRECTION_DOWN) {
                horizontal--;
                vertical++;
                index        = vertical * TerrainRegions.MINI_GRID_WIDTH + horizontal;
                direction    = INITIAL_DIRECTIONS[grid[index] & EDGE_BITS];
                previousTurn = INITIAL_TURNS[grid[index] & EDGE_BITS];
            }

            int offsetIndex = direction + (previousTurn & TURN_BIT) * DIRECTION_COUNT;
            int startHorizontal = (horizontal * TerrainTileset.MINI_TILE_SIZE + START_HORIZONTAL[offsetIndex]) & WORD_MASK;
            int startVertical = (vertical * TerrainTileset.MINI_TILE_SIZE + START_VERTICAL[offsetIndex]) & WORD_MASK;
            int initialHorizontal = startHorizontal;
            int initialVertical = startVertical;

            grid[index] ^= (byte) (DIRECTION_EDGE_BIT << direction);

            while (true) {
                if (++transitions >= width * height * TRANSITIONS_PER_CELL) {
                    throw Exceptions.newUnsupportedOperationException("Native contour traversal did not terminate within represented grid edges.");
                }

                int nextDirection = (direction + 1) & DIRECTION_MASK;
                int bit = DIRECTION_EDGE_BIT << direction;
                int nextBit = DIRECTION_EDGE_BIT << nextDirection;

                grid[index] ^= (byte) bit;

                int currentTurn = 0;

                if ((grid[index] & nextBit) == 0) {
                    while (true) {
                        int nextIndex = index + STEPS[nextDirection];

                        if ((grid[nextIndex] & bit) == 0) {
                            currentTurn = OUTER_CORNER_TURN;

                            break;
                        }

                        grid[nextIndex] ^= (byte) bit;
                        horizontal += HORIZONTAL_STEPS[nextDirection];
                        vertical += VERTICAL_STEPS[nextDirection];
                        index = nextIndex;

                        if ((grid[index] & nextBit) != 0) {
                            break;
                        }
                    }
                }

                offsetIndex = direction + currentTurn * DIRECTION_COUNT;

                int endHorizontal = (horizontal * TerrainTileset.MINI_TILE_SIZE + END_HORIZONTAL[offsetIndex]) & WORD_MASK;
                int endVertical = (vertical * TerrainTileset.MINI_TILE_SIZE + END_VERTICAL[offsetIndex]) & WORD_MASK;
                int firstBits = (~(direction * TWO_BIT_HIGH_FACTOR) ^ previousTurn * TWO_BIT_HIGH_FACTOR ^ direction) & TWO_BIT_MASK;
                int secondBits = ((currentTurn ^ direction) * TWO_BIT_HIGH_FACTOR ^ direction ^ TWO_BIT_LOW_BIT) & TWO_BIT_MASK;
                int flags = direction < DIRECTION_DOWN
                            ? (previousTurn & TURN_BIT) << LOW_TURN_SHIFT | currentTurn << HIGH_TURN_SHIFT |
                              secondBits << HIGH_FIELD_SHIFT | firstBits
                            : (previousTurn & TURN_BIT) << HIGH_TURN_SHIFT | currentTurn << LOW_TURN_SHIFT |
                              firstBits << HIGH_FIELD_SHIFT | secondBits;
                int coordinate = (direction & VERTICAL_BOUNDARY_BIT) == 0 ? startVertical : startHorizontal;
                int first = switch (direction) {
                    case DIRECTION_UP -> startHorizontal;
                    case DIRECTION_RIGHT -> startVertical;
                    case DIRECTION_DOWN -> endHorizontal;
                    default -> endVertical;
                };
                int last = switch (direction) {
                    case DIRECTION_UP -> endHorizontal;
                    case DIRECTION_RIGHT -> endVertical;
                    case DIRECTION_DOWN -> startHorizontal;
                    default -> startVertical;
                };

                for (int axis = 0; axis < DIRECTION_COUNT; axis++) {
                    if (result.counts[axis] == result.boundaries[axis].length) {
                        int capacity = result.boundaries[axis].length + CAPACITY_GROWTH;

                        if (capacity > Short.MAX_VALUE) {
                            throw Exceptions.newUnsupportedOperationException("Native contour capacity becomes a negative allocation length.");
                        }

                        for (int otherAxis = 0; otherAxis < DIRECTION_COUNT; otherAxis++) {
                            result.boundaries[otherAxis] = Arrays.copyOf(result.boundaries[otherAxis], capacity);
                        }

                        result.capacity = capacity;

                        break;
                    }
                }

                long boundary = (long) (short) coordinate << ENTRY_COORDINATE_SHIFT | (long) (first ^ SIGN_BIAS) << ENTRY_FIRST_SHIFT |
                                (long) last << ENTRY_LAST_SHIFT | flags << ENTRY_FLAGS_SHIFT | direction;

                result.boundaries[direction][result.counts[direction]++] = boundary;

                if (currentTurn == 0) {
                    direction = nextDirection;
                } else {
                    horizontal += HORIZONTAL_CORNERS[direction];
                    vertical += VERTICAL_CORNERS[direction];
                    index     = vertical * TerrainRegions.MINI_GRID_WIDTH + horizontal;
                    direction = (direction - 1) & DIRECTION_MASK;
                }

                previousTurn    = currentTurn;
                startHorizontal = endHorizontal;
                startVertical   = endVertical;

                if (startHorizontal == initialHorizontal && startVertical == initialVertical) {
                    grid[index] ^= (byte) (DIRECTION_EDGE_BIT << direction);

                    break;
                }
            }
        }
    }

    /**
     * Creates a {@link TerrainContours} instance with miniature-grid and region storage but without contour records, before a saved game replaces the
     * pathing data.
     *
     * @param map     the normalized original map tiles
     * @param tileset the original CV5 and VF4 data
     * @return the created {@link TerrainContours} instance with no contour records
     * @throws UnsupportedOperationException if the map is too small or too large for the grid or the regions exceed the represented storage
     */
    public static @NotNull TerrainContours createWithoutBoundaries(@NotNull GameMap map, @NotNull TerrainTileset tileset) {
        return new TerrainContours(TerrainRegions.create(map, tileset, createGrid(map, tileset)));
    }

    /**
     * Builds the miniature walkability grid: one byte per walkability miniature (bit {@code 7} blocked), the map's border rows and columns blocked,
     * and the four edge bits of every open miniature.
     *
     * @param map     the normalized original map tiles
     * @param tileset the original CV5 and VF4 data
     * @return the miniature grid, {@code 1024} cells square
     * @throws UnsupportedOperationException if the map is too small or too large for the grid
     */
    private static byte @NotNull [] createGrid(@NotNull GameMap map, @NotNull TerrainTileset tileset) {
        int width = map.getWidth() * MINI_TILES_PER_ROW;
        int height = map.getHeight() * MINI_TILES_PER_ROW;

        if (width < BORDER_COLUMNS || height < BORDER_ROWS || width > TerrainRegions.MINI_GRID_WIDTH || height > TerrainRegions.MINI_GRID_WIDTH) {
            throw Exceptions.newUnsupportedOperationException("Native terrain contour generation references unrepresented map memory.");
        }

        byte[] grid = new byte[TerrainRegions.MINI_GRID_WIDTH * TerrainRegions.MINI_GRID_WIDTH];

        for (int tileY = 0; tileY < map.getHeight(); tileY++) {
            for (int tileX = 0; tileX < map.getWidth(); tileX++) {
                int tileIdentifier = map.getTileIdentifier(tileY * map.getWidth() + tileX);

                for (int miniatureIndex = 0; miniatureIndex < MINI_TILES_PER_TILE; miniatureIndex++) {
                    int miniature = tileset.getMiniatureFlags(tileIdentifier, miniatureIndex);
                    int gridX = tileX * MINI_TILES_PER_ROW + miniatureIndex % MINI_TILES_PER_ROW;
                    int gridY = tileY * MINI_TILES_PER_ROW + miniatureIndex / MINI_TILES_PER_ROW;
                    byte cell = (byte) (((miniature & TerrainTileset.MINI_TILE_WALKABLE) ^ TerrainTileset.MINI_TILE_WALKABLE) << BLOCKED_SHIFT);

                    grid[gridY * TerrainRegions.MINI_GRID_WIDTH + gridX] = cell;
                }
            }
        }

        for (int vertical = height - BORDER_ROWS; vertical < height; vertical++) {
            Arrays.fill(grid, vertical * TerrainRegions.MINI_GRID_WIDTH, vertical * TerrainRegions.MINI_GRID_WIDTH + BORDER_COLUMNS, BLOCKED_CELL);
            Arrays.fill(grid, vertical * TerrainRegions.MINI_GRID_WIDTH + width - BORDER_COLUMNS,
                        vertical * TerrainRegions.MINI_GRID_WIDTH + width, BLOCKED_CELL);

            if (vertical >= height - BLOCKED_BOTTOM_ROWS) {
                Arrays.fill(grid, vertical * TerrainRegions.MINI_GRID_WIDTH, vertical * TerrainRegions.MINI_GRID_WIDTH + width, BLOCKED_CELL);
            }
        }

        for (int vertical = 0; vertical < height; vertical++) {
            for (int horizontal = 0; horizontal < width; horizontal++) {
                int index = vertical * TerrainRegions.MINI_GRID_WIDTH + horizontal;

                if (grid[index] < 0) {
                    continue;
                }

                if (vertical == 0 || grid[index - TerrainRegions.MINI_GRID_WIDTH] < 0) {
                    grid[index] |= EDGE_UP;
                }

                if (horizontal == width - 1 || grid[index + 1] < 0) {
                    grid[index] |= EDGE_RIGHT;
                }

                if (vertical == height - 1 || grid[index + TerrainRegions.MINI_GRID_WIDTH] < 0) {
                    grid[index] |= EDGE_DOWN;
                }

                if (horizontal == 0 || grid[index - 1] < 0) {
                    grid[index] |= EDGE_LEFT;
                }
            }
        }

        return grid;
    }

    private final @NotNull TerrainRegions regions;

    private final long @NotNull [] @NotNull [] boundaries;
    private final int @NotNull [] counts;
    private int capacity;

    private short upperExtent;
    private short negativeRightExtent;
    private short negativeLowerExtent;
    private short leftExtent;

    private short upperExtentPlusOne;
    private short complementedRightExtent;
    private short complementedLowerExtent;
    private short leftExtentPlusOne;

    /**
     * Creates a {@link TerrainContours} instance with empty boundary arrays.
     *
     * @param regions the miniature-grid and region storage
     */
    private TerrainContours(@NotNull TerrainRegions regions) {
        this.regions = regions;

        this.boundaries = new long[DIRECTION_COUNT][INITIAL_CAPACITY];
        this.counts     = new int[DIRECTION_COUNT];
        this.capacity   = INITIAL_CAPACITY;

        this.upperExtent         = 0;
        this.negativeRightExtent = 0;
        this.negativeLowerExtent = 0;
        this.leftExtent          = 0;

        this.upperExtentPlusOne      = 0;
        this.complementedRightExtent = 0;
        this.complementedLowerExtent = 0;
        this.leftExtentPlusOne       = 0;
    }

    /**
     * @param direction the original contour array from {@code 0..3}
     * @return the number of eight-byte contour records
     * @throws IndexOutOfBoundsException if the direction is outside {@code 0..3}
     */
    public int getBoundaryCount(int direction) {
        return this.counts[Objects.checkIndex(direction, DIRECTION_COUNT)];
    }

    /**
     * @param direction the original contour array from {@code 0..3}
     * @param index     the sorted contour index
     * @return the original little-endian eight-byte contour record
     * @throws IndexOutOfBoundsException if the direction or the index is outside its range
     */
    public long getBoundary(int direction, int index) {
        long entry = this.boundaries[Objects.checkIndex(direction, DIRECTION_COUNT)][Objects.checkIndex(index, this.counts[direction])];

        return entry >>> ENTRY_COORDINATE_SHIFT |
               (((entry >>> ENTRY_FIRST_SHIFT) ^ SIGN_BIAS) & WORD_MASK) << RECORD_FIRST_SHIFT |
               ((entry >>> ENTRY_LAST_SHIFT) & WORD_MASK) << RECORD_LAST_SHIFT |
               (entry & BYTE_MASK) << RECORD_DIRECTION_SHIFT |
               ((entry >>> ENTRY_FLAGS_SHIFT) & BYTE_MASK) << RECORD_FLAGS_SHIFT;
    }

    /**
     * @param index the index of the footprint control WORD, {@code 0..11}
     * @return the retained unsigned WORD
     * @throws IndexOutOfBoundsException if the index is outside {@code 0..11}
     */
    public int getControl(int index) {
        return Short.toUnsignedInt(this.getSignedFootprintControl(Objects.checkIndex(index, CONTROL_COUNT)));
    }

    /**
     * @return the original fresh-map region storage
     */
    public @NotNull TerrainRegions getRegions() {
        return this.regions;
    }

    /**
     * @return the size of the saved contours: the header and every contour record
     */
    public int getSavedSize() {
        int size = SAVED_HEADER_SIZE;

        for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
            size += this.counts[direction] * SAVED_BOUNDARY_SIZE;
        }

        return size;
    }


    /**
     * Establishes the eight signed extents used by subsequent contour queries.
     *
     * @param unitType the unit type WORD, or radius with bit {@code 15} set
     * @param units    the original unit extents
     */
    public void initializeCollisionFootprint(int unitType, @NotNull ScrUnitTypeView units) {
        int normalizedType = unitType & WORD_MASK;
        int left = normalizedType < RADIUS_FLAG ? units.getDimensionLeft(normalizedType) : normalizedType & RADIUS_MASK;
        int upper = normalizedType < RADIUS_FLAG ? units.getDimensionUp(normalizedType) : normalizedType & RADIUS_MASK;
        int right = normalizedType < RADIUS_FLAG ? units.getDimensionRight(normalizedType) : normalizedType & RADIUS_MASK;
        int lower = normalizedType < RADIUS_FLAG ? units.getDimensionDown(normalizedType) : normalizedType & RADIUS_MASK;

        this.upperExtent             = (short) upper;
        this.negativeRightExtent     = (short) -right;
        this.negativeLowerExtent     = (short) -lower;
        this.leftExtent              = (short) left;
        this.upperExtentPlusOne      = (short) (upper + 1);
        this.complementedRightExtent = (short) ~right;
        this.complementedLowerExtent = (short) ~lower;
        this.leftExtentPlusOne       = (short) (left + 1);
    }

    /**
     * Copies the footprint control WORDs ({@link #getControl}) of other contours, the extents their last placement query left.
     *
     * @param source the contours to copy from
     */
    public void copyFootprintControls(@NotNull TerrainContours source) {
        this.upperExtent             = source.upperExtent;
        this.negativeRightExtent     = source.negativeRightExtent;
        this.negativeLowerExtent     = source.negativeLowerExtent;
        this.leftExtent              = source.leftExtent;
        this.upperExtentPlusOne      = source.upperExtentPlusOne;
        this.complementedRightExtent = source.complementedRightExtent;
        this.complementedLowerExtent = source.complementedLowerExtent;
        this.leftExtentPlusOne       = source.leftExtentPlusOne;
    }

    /**
     * Sets the unit type's footprint extents and tests that no contour intersects the placement footprint.
     *
     * @param unitType  the unit type WORD, or radius with bit {@code 15} set
     * @param positionX the horizontal WORD coordinate
     * @param positionY the vertical WORD coordinate
     * @param units     the original initialized unit type table
     * @return {@code true} if no contour intersects the placement footprint, {@code false} otherwise
     */
    public boolean isPlacementClear(int unitType, int positionX, int positionY, @NotNull ScrUnitTypeView units) {
        this.initializeCollisionFootprint(unitType, units);

        return this.isPlacementClear(positionX, positionY);
    }

    private boolean isPlacementClear(int positionX, int positionY) {
        int horizontal = (short) positionX;
        int vertical = (short) positionY;

        for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
            long[] entries = this.boundaries[direction];
            boolean horizontalBoundary = (direction & VERTICAL_BOUNDARY_BIT) == 0;
            boolean backward = direction == DIRECTION_UP || direction == DIRECTION_LEFT;
            int coordinate = horizontalBoundary ? vertical : horizontal;
            int otherCoordinate = horizontalBoundary ? horizontal : vertical;
            int firstExtent = horizontalBoundary ? this.negativeRightExtent : this.negativeLowerExtent;
            int lastExtent = horizontalBoundary ? this.leftExtent : this.upperExtent;
            int doubled = (short) (coordinate * DOUBLED_COORDINATE_FACTOR + (backward ? -1 : 1));
            int lowerIndex = 0;
            int upperIndex = entries.length;

            while (lowerIndex < upperIndex) {
                int middleIndex = (lowerIndex + upperIndex) >>> MIDPOINT_SHIFT;
                int doubledEntry = (short) ((short) (entries[middleIndex] >>> ENTRY_COORDINATE_SHIFT) * DOUBLED_COORDINATE_FACTOR);

                if (doubled < doubledEntry) {
                    upperIndex = middleIndex;
                } else {
                    lowerIndex = middleIndex + 1;
                }
            }

            int entryIndex = backward ? upperIndex - 1 : upperIndex;

            while (entryIndex >= 0 && entryIndex < entries.length) {
                long entry = entries[entryIndex];
                int adjusted = (short) ((short) (entry >>> ENTRY_COORDINATE_SHIFT) + this.getSignedFootprintControl((int) entry & BYTE_MASK));

                if (backward ? adjusted < coordinate : adjusted > coordinate) {
                    break;
                }

                int first = (short) (((entry >>> ENTRY_FIRST_SHIFT) ^ SIGN_BIAS) + firstExtent);
                int last = (short) ((entry >>> ENTRY_LAST_SHIFT) + lastExtent);

                if (first <= otherCoordinate && last >= otherCoordinate) {
                    return false;
                }

                entryIndex += backward ? -1 : 1;
            }
        }

        return true;
    }

    /**
     * Tests the inclusive interior of a rectangular group movement footprint.
     *
     * @param positionX the horizontal center
     * @param positionY the vertical center
     * @param width     the rectangle width
     * @param height    the rectangle height
     * @return {@code true} if no terrain contour intersects the interior, {@code false} otherwise
     */
    public boolean isRectanglePlacementClear(int positionX, int positionY, int width, int height) {
        this.upperExtent             = (short) ((height - 1) / HALF_EXTENT_DIVISOR);
        this.negativeRightExtent     = (short) -(width - width / HALF_EXTENT_DIVISOR - 1);
        this.negativeLowerExtent     = (short) -(height - height / HALF_EXTENT_DIVISOR - 1);
        this.leftExtent              = (short) ((width - 1) / HALF_EXTENT_DIVISOR);
        this.upperExtentPlusOne      = (short) (this.upperExtent + 1);
        this.complementedRightExtent = (short) (this.negativeRightExtent - 1);
        this.complementedLowerExtent = (short) (this.negativeLowerExtent - 1);
        this.leftExtentPlusOne       = (short) (this.leftExtent + 1);

        return this.isPlacementClear(positionX, positionY);
    }

    /**
     * Writes the contours into the pathing data of a saved game: the four array pointers as {@code 0xDEADBEEF} marks, the four WORD counts, the four
     * WORD capacities, the twelve footprint control WORDs and then the eight-byte records of each array.
     *
     * @param data the pathing data, little-endian, at the contours
     */
    public void writeSaveData(@NotNull ByteBuffer data) {
        for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
            data.putInt(SAVED_POINTER_MARK);
        }

        for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
            data.putShort((short) this.counts[direction]);
        }

        for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
            data.putShort((short) this.capacity);
        }

        for (int control = 0; control < CONTROL_COUNT; control++) {
            data.putShort(this.getSignedFootprintControl(control));
        }

        for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
            for (int index = 0; index < this.counts[direction]; index++) {
                data.putLong(this.getBoundary(direction, index));
            }
        }
    }

    /**
     * Reads the contours that {@link #writeSaveData} wrote.
     *
     * @param data the pathing data, little-endian, at the contours
     */
    public void readSaveData(@NotNull ByteBuffer data) {
        data.position(data.position() + DIRECTION_COUNT * Integer.BYTES);

        for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
            this.counts[direction] = Short.toUnsignedInt(data.getShort());
        }

        for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
            this.capacity = Short.toUnsignedInt(data.getShort());
        }

        this.upperExtent         = data.getShort();
        this.negativeRightExtent = data.getShort();
        this.negativeLowerExtent = data.getShort();
        this.leftExtent          = data.getShort();

        data.position(data.position() + CONSTANT_CONTROL_COUNT * Short.BYTES);

        this.upperExtentPlusOne      = data.getShort();
        this.complementedRightExtent = data.getShort();
        this.complementedLowerExtent = data.getShort();
        this.leftExtentPlusOne       = data.getShort();

        for (int direction = 0; direction < DIRECTION_COUNT; direction++) {
            long[] entries = new long[this.counts[direction]];

            for (int index = 0; index < entries.length; index++) {
                long record = data.getLong();
                long coordinate = (short) record;
                long first = ((record >>> RECORD_FIRST_SHIFT) ^ SIGN_BIAS) & WORD_MASK;
                long last = (record >>> RECORD_LAST_SHIFT) & WORD_MASK;
                long recordDirection = (record >>> RECORD_DIRECTION_SHIFT) & BYTE_MASK;
                long flags = (record >>> RECORD_FLAGS_SHIFT) & BYTE_MASK;

                entries[index] = coordinate << ENTRY_COORDINATE_SHIFT | first << ENTRY_FIRST_SHIFT | last << ENTRY_LAST_SHIFT |
                                 flags << ENTRY_FLAGS_SHIFT | recordDirection;
            }

            this.boundaries[direction] = entries;
        }
    }

    private short getSignedFootprintControl(int index) {
        return switch (index) {
            case CONTROL_UPPER -> this.upperExtent;
            case CONTROL_NEGATIVE_RIGHT -> this.negativeRightExtent;
            case CONTROL_NEGATIVE_LOWER -> this.negativeLowerExtent;
            case CONTROL_LEFT -> this.leftExtent;
            case CONTROL_FIRST_ONE, CONTROL_SECOND_ONE -> 1;
            case CONTROL_FIRST_MINUS_ONE, CONTROL_SECOND_MINUS_ONE -> -1;
            case CONTROL_UPPER_PLUS_ONE -> this.upperExtentPlusOne;
            case CONTROL_COMPLEMENTED_RIGHT -> this.complementedRightExtent;
            case CONTROL_COMPLEMENTED_LOWER -> this.complementedLowerExtent;
            case CONTROL_LEFT_PLUS_ONE -> this.leftExtentPlusOne;
            default -> throw Exceptions.newIndexOutOfBoundsException("The footprint control {0} is outside 0..{1}.", index, CONTROL_COUNT - 1);
        };
    }
}
