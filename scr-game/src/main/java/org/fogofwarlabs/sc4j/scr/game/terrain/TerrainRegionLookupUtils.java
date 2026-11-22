package org.fogofwarlabs.sc4j.scr.game.terrain;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.jetbrains.annotations.NotNull;

/**
 * Applies the original partial-tile region remapping and signed pixel lookup.
 */
public final class TerrainRegionLookupUtils {
    /**
     * The mini-tiles of one tile row and column.
     */
    private static final int MINI_TILES_PER_ROW = TerrainRegions.MINI_TILES_PER_ROW;
    /**
     * The mini-tiles of one tile.
     */
    private static final int MINI_TILES_PER_TILE = MINI_TILES_PER_ROW * MINI_TILES_PER_ROW;
    /**
     * The last mini-tile row and column of a tile.
     */
    private static final int LAST_MINI_TILE = MINI_TILES_PER_ROW - 1;
    /**
     * The mask of a mini-tile column or row within its tile.
     */
    private static final int MINI_TILE_COLUMN_MASK = MINI_TILES_PER_ROW - 1;
    /**
     * The blocked bit of a miniature grid byte.
     */
    private static final int MINI_BLOCKED = 0x80;
    /**
     * The position of {@link #MINI_BLOCKED}.
     */
    private static final int MINI_BLOCKED_SHIFT = 7;
    /**
     * The value of {@link #MINI_BLOCKED} after the shift by {@link #MINI_BLOCKED_SHIFT}.
     */
    private static final int MINI_BLOCKED_VALUE = MINI_BLOCKED >>> MINI_BLOCKED_SHIFT;
    /**
     * The partial tile mask of a tile whose mini-tiles all belong to the second region.
     */
    private static final int FULL_MINI_TILE_MASK = 0xFFFF;
    /**
     * The partial tile mask bit of mini-tile {@code 0}; shifted left by a mini-tile index, it gives the bit of that mini-tile.
     */
    private static final int MINI_TILE_MASK_BIT = 1;
    /**
     * The bits of a WORD.
     */
    private static final int WORD_MASK = 0xFFFF;

    /**
     * The spiral searches walk sides of growing odd length in the directions right, down, left and up.
     */
    private static final int SPIRAL_RIGHT = 0;
    private static final int SPIRAL_DOWN = 1;
    private static final int SPIRAL_LEFT = 2;
    private static final int SPIRAL_UP = 3;
    private static final int SPIRAL_DIRECTIONS = 4;
    /**
     * The growth of a spiral side per turn.
     */
    private static final int SPIRAL_SIDE_GROWTH = 2;
    /**
     * The side length limit of the tile spiral of the destination-region fallback.
     */
    private static final int REGION_SPIRAL_LIMIT = 16;
    /**
     * The side length limit of the pixel spiral of the bounded destination adjustment.
     */
    private static final int DESTINATION_SPIRAL_LIMIT = 10;
    /**
     * The pixel step of the bounded destination adjustment.
     */
    private static final int DESTINATION_STEP = TerrainTileset.MINI_TILE_SIZE;
    /**
     * The positions of the horizontal and the vertical pixel WORD in a destination array.
     */
    private static final int DESTINATION_HORIZONTAL = 0;
    private static final int DESTINATION_VERTICAL = 1;
    /**
     * The pixel offset of a tile center from the tile origin.
     */
    private static final int TILE_CENTER_OFFSET = TerrainTileset.TILE_SIZE / 2;

    /**
     * The eight neighbors of a partial tile: the four edge neighbors first, then the four corners.
     */
    private static final int NEIGHBOR_UP = 0;
    private static final int NEIGHBOR_LEFT = 1;
    private static final int NEIGHBOR_RIGHT = 2;
    private static final int NEIGHBOR_DOWN = 3;
    private static final int NEIGHBOR_UP_LEFT = 4;
    private static final int NEIGHBOR_UP_RIGHT = 5;
    private static final int NEIGHBOR_DOWN_LEFT = 6;
    private static final int NEIGHBOR_DOWN_RIGHT = 7;
    private static final int NEIGHBOR_COUNT = 8;
    /**
     * The number of edge neighbors, which come first.
     */
    private static final int EDGE_NEIGHBORS = 4;
    /**
     * The mini-tiles a corner neighbor touches.
     */
    private static final int CORNER_CONTACT_LENGTH = 1;

    /**
     * Looks up a region while choosing the walkable half of a split tile.
     *
     * @param regions   the original region storage
     * @param positionX the horizontal pixel coordinate
     * @param positionY the vertical pixel coordinate
     * @return the preferred region identifier
     */
    public static int lookupPreferWalkable(@NotNull TerrainRegions regions, int positionX, int positionY) {
        int region = regions.getTileRegion((long) (positionY / TerrainTileset.TILE_SIZE) * TerrainRegions.GRID_WIDTH +
                                           positionX / TerrainTileset.TILE_SIZE);

        return region >= TerrainRegions.PARTIAL_REGION_BASE ? regions.getPartialFirstRegion(region) : region;
    }

    /**
     * Moves a destination into a required region: a tile spiral around the destination clamped to the region bounds takes the center of the first
     * tile of the region, else the region center.
     *
     * @param regions     the original tile and region storage
     * @param widthTiles  the unsigned map width WORD
     * @param heightTiles the unsigned map height WORD
     * @param region      the required region WORD
     * @param destination the mutable horizontal and vertical pixel WORDs
     */
    public static void adjustDestinationToRegion(@NotNull TerrainRegions regions,
                                                 int widthTiles, int heightTiles,
                                                 int region,
                                                 int @NotNull [] destination) {
        int normalizedRegion = region & WORD_MASK;
        int left = regions.getLeftBound(normalizedRegion);
        int top = regions.getUpperBound(normalizedRegion);
        int right = regions.getRightBound(normalizedRegion);
        int bottom = regions.getLowerBound(normalizedRegion);
        int column = Math.max(left, Math.min(right, (short) destination[DESTINATION_HORIZONTAL])) / TerrainTileset.TILE_SIZE;
        int row = Math.max(top, Math.min(bottom, (short) destination[DESTINATION_VERTICAL])) / TerrainTileset.TILE_SIZE;

        for (int sideLength = 1; sideLength < REGION_SPIRAL_LIMIT; sideLength += SPIRAL_SIDE_GROWTH) {
            for (int direction = 0; direction < SPIRAL_DIRECTIONS; direction++) {
                int horizontalStep = direction == SPIRAL_RIGHT ? 1 : direction == SPIRAL_LEFT ? -1 : 0;
                int verticalStep = direction == SPIRAL_DOWN ? 1 : direction == SPIRAL_UP ? -1 : 0;

                for (int step = 0; step < sideLength; step++) {
                    if ((column & WORD_MASK) < (widthTiles & WORD_MASK) && (row & WORD_MASK) < (heightTiles & WORD_MASK)) {
                        int candidate = regions.getTileRegion((short) row * TerrainRegions.GRID_WIDTH + (short) column);

                        if (candidate >= TerrainRegions.PARTIAL_REGION_BASE) {
                            candidate = regions.getPartialFirstRegion(candidate);
                        }

                        if (candidate == normalizedRegion) {
                            destination[DESTINATION_HORIZONTAL] = (short) ((column << TerrainTileset.TILE_SHIFT) + TILE_CENTER_OFFSET);
                            destination[DESTINATION_VERTICAL]   = (short) ((row << TerrainTileset.TILE_SHIFT) + TILE_CENTER_OFFSET);

                            return;
                        }
                    }

                    column = (short) (column + horizontalStep);
                    row    = (short) (row + verticalStep);
                }

                column = (short) (column - horizontalStep);
                row    = (short) (row - verticalStep);
            }

            column = (short) (column - 1);
            row    = (short) (row - 1);
        }

        destination[DESTINATION_HORIZONTAL] = regions.getCenterX(normalizedRegion);
        destination[DESTINATION_VERTICAL]   = regions.getCenterY(normalizedRegion);
    }

    /**
     * Moves a destination to the first position of a pixel spiral whose extent lies inside the map and covers no blocked tile. Partial-tile
     * identifiers are accepted without consulting their miniature masks.
     *
     * @param regions      the original fixed-stride tile and region storage
     * @param widthPixels  the unsigned map width WORD
     * @param heightPixels the unsigned map height WORD
     * @param left         the signed left extent WORD
     * @param top          the signed top extent WORD
     * @param right        the signed right extent WORD
     * @param bottom       the signed bottom extent WORD
     * @param destination  the mutable horizontal and vertical pixel WORDs
     * @return {@code true} if a destination was found, {@code false} otherwise; unsuccessful searches also update the destination
     */
    public static boolean adjustDestination(@NotNull TerrainRegions regions,
                                            int widthPixels, int heightPixels,
                                            int left, int top, int right, int bottom,
                                            int @NotNull [] destination) {
        int horizontal = (short) destination[DESTINATION_HORIZONTAL];
        int vertical = (short) destination[DESTINATION_VERTICAL];

        for (int sideLength = 1; sideLength < DESTINATION_SPIRAL_LIMIT; sideLength += SPIRAL_SIDE_GROWTH) {
            for (int direction = 0; direction < SPIRAL_DIRECTIONS; direction++) {
                int horizontalStep = direction == SPIRAL_RIGHT ? DESTINATION_STEP : direction == SPIRAL_LEFT ? -DESTINATION_STEP : 0;
                int verticalStep = direction == SPIRAL_DOWN ? DESTINATION_STEP : direction == SPIRAL_UP ? -DESTINATION_STEP : 0;

                for (int step = 0; step < sideLength; step++) {
                    int firstHorizontal = (horizontal + left) & WORD_MASK;
                    int firstVertical = (vertical + top) & WORD_MASK;
                    int lastHorizontal = (horizontal + right) & WORD_MASK;
                    int lastVertical = (vertical + bottom) & WORD_MASK;

                    if (firstHorizontal < (widthPixels & WORD_MASK) && firstVertical < (heightPixels & WORD_MASK) &&
                        lastHorizontal < (widthPixels & WORD_MASK) && lastVertical < (heightPixels & WORD_MASK)) {
                        boolean passable = true;
                        int firstColumn = (short) firstHorizontal / TerrainTileset.TILE_SIZE;
                        int lastColumn = (short) lastHorizontal / TerrainTileset.TILE_SIZE;
                        int lastRow = (short) lastVertical / TerrainTileset.TILE_SIZE;

                        for (int row = (short) firstVertical / TerrainTileset.TILE_SIZE; row <= lastRow && passable; row++) {
                            for (int column = lastColumn; column >= firstColumn; column--) {
                                int region = regions.getTileRegion(row * TerrainRegions.GRID_WIDTH + column);

                                if (region < TerrainRegions.PARTIAL_REGION_BASE && regions.getTerrainType(region) == TerrainRegions.BLOCKED_TERRAIN) {
                                    passable = false;

                                    break;
                                }
                            }
                        }

                        if (passable) {
                            destination[DESTINATION_HORIZONTAL] = horizontal;
                            destination[DESTINATION_VERTICAL]   = vertical;

                            return true;
                        }
                    }

                    horizontal = (short) (horizontal + horizontalStep);
                    vertical   = (short) (vertical + verticalStep);
                }

                horizontal = (short) (horizontal - horizontalStep);
                vertical   = (short) (vertical - verticalStep);
            }

            horizontal = (short) (horizontal - DESTINATION_STEP);
            vertical   = (short) (vertical - DESTINATION_STEP);
        }

        destination[DESTINATION_HORIZONTAL] = horizontal;
        destination[DESTINATION_VERTICAL]   = vertical;

        return false;
    }

    /**
     * Tests whether the tile index that {@link #lookup} computes for a pixel position selects an entry of the fixed {@code 256}-column tile grid.
     * A position left of the grid or right of it in a row inside the grid selects a tile of a neighboring row.
     *
     * @param positionX the original horizontal pixel DWORD
     * @param positionY the original vertical pixel DWORD
     * @return {@code true} if the tile index lies in the grid, {@code false} otherwise
     */
    public static boolean isInTileGrid(int positionX, int positionY) {
        long tileIndex = getTileIndex(positionX, positionY);

        return tileIndex >= 0 && tileIndex < TerrainRegions.TILE_COUNT;
    }

    /**
     * Computes the row-major tile index of a pixel position in the fixed {@code 256}-column tile grid, each coordinate divided with truncation toward
     * zero and neither bounded.
     *
     * @param positionX the original horizontal pixel DWORD
     * @param positionY the original vertical pixel DWORD
     * @return the tile index
     */
    private static long getTileIndex(int positionX, int positionY) {
        return (long) (positionY / TerrainTileset.TILE_SIZE) * TerrainRegions.GRID_WIDTH + positionX / TerrainTileset.TILE_SIZE;
    }

    /**
     * Looks up the region of a pixel position against the original fixed {@code 256}-column tile grid; a split tile selects its region by the
     * mini-tile mask.
     *
     * @param regions   the initialized region storage
     * @param positionX the original horizontal pixel DWORD
     * @param positionY the original vertical pixel DWORD
     * @return the unsigned region WORD
     */
    public static int lookup(@NotNull TerrainRegions regions, int positionX, int positionY) {
        int region = regions.getTileRegion(getTileIndex(positionX, positionY));

        if (region < TerrainRegions.PARTIAL_REGION_BASE) {
            return region;
        }

        int miniatureIndex = (positionX / TerrainTileset.MINI_TILE_SIZE & MINI_TILE_COLUMN_MASK) +
                             (positionY / TerrainTileset.MINI_TILE_SIZE & MINI_TILE_COLUMN_MASK) * MINI_TILES_PER_ROW;
        int mask = regions.getPartialTileMask(region);

        return (mask & (MINI_TILE_MASK_BIT << miniatureIndex)) != 0 ? regions.getPartialSecondRegion(region) : regions.getPartialFirstRegion(region);
    }

    /**
     * Splits the partially walkable tiles after the primary region assignment: each such tile gets a split record whose first region holds its
     * blocked and whose second region holds its walkable mini-tiles, the other region chosen among the neighbors with the best mini-tile contact.
     *
     * @param regions        the retained region records and fixed-stride tile grid
     * @param map            the map supplying expanded tile flags and normalized tile identifiers
     * @param terrain        the installed VF4 data
     * @param miniatureFlags the original {@code 1024}-column miniature byte grid
     * @throws IllegalArgumentException      if the grids do not have the original extents
     * @throws UnsupportedOperationException if a partial tile has no primary region record
     */
    public static void remapPartialTiles(@NotNull TerrainRegions regions,
                                         @NotNull GameMap map,
                                         @NotNull TerrainTileset terrain,
                                         byte @NotNull [] miniatureFlags) {
        if (miniatureFlags.length != TerrainRegions.MINI_GRID_WIDTH * TerrainRegions.MINI_GRID_WIDTH ||
            map.getWidth() > TerrainRegions.GRID_WIDTH || map.getHeight() > TerrainRegions.GRID_WIDTH) {
            throw Exceptions.newIllegalArgumentException("Native partial regions require the original miniature and tile grid extents.");
        }

        int[] neighborRegions = new int[NEIGHBOR_COUNT];
        int[] scores = new int[NEIGHBOR_COUNT];
        int partialCount = 0;

        for (int row = 0; row < map.getHeight(); row++) {
            for (int column = 0; column < map.getWidth(); column++) {
                int mapIndex = row * map.getWidth() + column;

                if ((map.getTileFlags(mapIndex) & MapTileFlags.PARTIALLY_WALKABLE) == 0) {
                    continue;
                }

                int tileIndex = row * TerrainRegions.GRID_WIDTH + column;
                int originalRegion = regions.getTileRegion(tileIndex);

                if (originalRegion >= TerrainRegions.REGION_COUNT) {
                    throw Exceptions.newUnsupportedOperationException("Native partial remapping requires an established primary region record.");
                }

                int mask = 0;

                for (int miniatureIndex = 0; miniatureIndex < MINI_TILES_PER_TILE; miniatureIndex++) {
                    mask |= (~terrain.getMiniatureFlags(map.getTileIdentifier(mapIndex), miniatureIndex) & TerrainTileset.MINI_TILE_WALKABLE) <<
                            miniatureIndex;
                }

                neighborRegions[NEIGHBOR_UP]         = row > 0 ? regions.getTileRegion(tileIndex - TerrainRegions.GRID_WIDTH)
                                                               : TerrainRegions.NO_REGION;
                neighborRegions[NEIGHBOR_LEFT]       = column > 0 ? regions.getTileRegion(tileIndex - 1) : TerrainRegions.NO_REGION;
                neighborRegions[NEIGHBOR_RIGHT]      = column + 1 < map.getWidth() ? regions.getTileRegion(tileIndex + 1) : TerrainRegions.NO_REGION;
                neighborRegions[NEIGHBOR_DOWN]       = row + 1 < map.getHeight() ? regions.getTileRegion(tileIndex + TerrainRegions.GRID_WIDTH)
                                                                                 : TerrainRegions.NO_REGION;
                neighborRegions[NEIGHBOR_UP_LEFT]    = row > 0 && column > 0 ? regions.getTileRegion(tileIndex - (TerrainRegions.GRID_WIDTH + 1))
                                                                             : TerrainRegions.NO_REGION;
                neighborRegions[NEIGHBOR_UP_RIGHT]   = row > 0 && column + 1 < map.getWidth()
                                                       ? regions.getTileRegion(tileIndex - (TerrainRegions.GRID_WIDTH - 1))
                                                       : TerrainRegions.NO_REGION;
                neighborRegions[NEIGHBOR_DOWN_LEFT]  = row + 1 < map.getHeight() && column > 0
                                                       ? regions.getTileRegion(tileIndex + (TerrainRegions.GRID_WIDTH - 1))
                                                       : TerrainRegions.NO_REGION;
                neighborRegions[NEIGHBOR_DOWN_RIGHT] = row + 1 < map.getHeight() && column + 1 < map.getWidth()
                                                       ? regions.getTileRegion(tileIndex + (TerrainRegions.GRID_WIDTH + 1))
                                                       : TerrainRegions.NO_REGION;

                int miniatureOffset = row * (MINI_TILES_PER_ROW * TerrainRegions.MINI_GRID_WIDTH) + column * MINI_TILES_PER_ROW;
                boolean blocked = regions.getTerrainType(originalRegion) == TerrainRegions.BLOCKED_TERRAIN;
                int desiredBit = blocked ? 0 : MINI_BLOCKED_VALUE;

                for (int direction = 0; direction < EDGE_NEIGHBORS; direction++) {
                    int score = 0;

                    for (int position = 0; position < MINI_TILES_PER_ROW; position++) {
                        int offset = switch (direction) {
                            case NEIGHBOR_UP -> miniatureOffset + position;
                            case NEIGHBOR_LEFT -> miniatureOffset + position * TerrainRegions.MINI_GRID_WIDTH;
                            case NEIGHBOR_RIGHT -> miniatureOffset + position * TerrainRegions.MINI_GRID_WIDTH + LAST_MINI_TILE;
                            default -> miniatureOffset + LAST_MINI_TILE * TerrainRegions.MINI_GRID_WIDTH + position;
                        };

                        if (((miniatureFlags[offset] & MINI_BLOCKED) >>> MINI_BLOCKED_SHIFT) == desiredBit) {
                            score++;
                        }
                    }

                    scores[direction] = score;
                }

                scores[NEIGHBOR_UP_LEFT]    = (miniatureFlags[miniatureOffset] & MINI_BLOCKED) >>> MINI_BLOCKED_SHIFT;
                scores[NEIGHBOR_UP_RIGHT]   = (miniatureFlags[miniatureOffset + LAST_MINI_TILE] & MINI_BLOCKED) >>> MINI_BLOCKED_SHIFT;
                scores[NEIGHBOR_DOWN_LEFT]  = (miniatureFlags[miniatureOffset + LAST_MINI_TILE * TerrainRegions.MINI_GRID_WIDTH] & MINI_BLOCKED) >>>
                                              MINI_BLOCKED_SHIFT;
                scores[NEIGHBOR_DOWN_RIGHT] = (miniatureFlags[miniatureOffset + (LAST_MINI_TILE * TerrainRegions.MINI_GRID_WIDTH + LAST_MINI_TILE)] &
                                               MINI_BLOCKED) >>> MINI_BLOCKED_SHIFT;

                int selectedDirection = -1;
                int maximumScore = 0;

                for (int direction = blocked ? NEIGHBOR_DOWN : NEIGHBOR_DOWN_RIGHT; direction >= 0; direction--) {
                    int neighbor = neighborRegions[direction];

                    if (scores[direction] <= maximumScore || neighbor == originalRegion) {
                        continue;
                    }

                    boolean eligible;

                    if (neighbor < TerrainRegions.PARTIAL_REGION_BASE) {
                        eligible = neighbor < TerrainRegions.REGION_COUNT &&
                                   (regions.getTerrainType(neighbor) == TerrainRegions.BLOCKED_TERRAIN) != blocked;
                    } else {
                        int contactCount = 0;
                        int contactLength = direction < EDGE_NEIGHBORS ? MINI_TILES_PER_ROW : CORNER_CONTACT_LENGTH;

                        for (int position = 0; position < contactLength; position++) {
                            int offset = switch (direction) {
                                case NEIGHBOR_UP -> miniatureOffset - TerrainRegions.MINI_GRID_WIDTH + position;
                                case NEIGHBOR_LEFT -> miniatureOffset - 1 + position * TerrainRegions.MINI_GRID_WIDTH;
                                case NEIGHBOR_RIGHT -> miniatureOffset + MINI_TILES_PER_ROW + position * TerrainRegions.MINI_GRID_WIDTH;
                                case NEIGHBOR_DOWN -> miniatureOffset + MINI_TILES_PER_ROW * TerrainRegions.MINI_GRID_WIDTH + position;
                                case NEIGHBOR_UP_LEFT -> miniatureOffset - (TerrainRegions.MINI_GRID_WIDTH + 1);
                                case NEIGHBOR_UP_RIGHT -> miniatureOffset - (TerrainRegions.MINI_GRID_WIDTH - MINI_TILES_PER_ROW);
                                case NEIGHBOR_DOWN_LEFT -> miniatureOffset + (MINI_TILES_PER_ROW * TerrainRegions.MINI_GRID_WIDTH - 1);
                                default -> miniatureOffset + (MINI_TILES_PER_ROW * TerrainRegions.MINI_GRID_WIDTH + MINI_TILES_PER_ROW);
                            };

                            if (((miniatureFlags[offset] & MINI_BLOCKED) >>> MINI_BLOCKED_SHIFT) == desiredBit) {
                                contactCount++;
                            }
                        }

                        eligible = contactCount != 0;
                    }

                    if (eligible) {
                        selectedDirection = direction;
                        maximumScore      = scores[direction];
                    }
                }

                int firstRegion = originalRegion;
                int secondRegion = originalRegion;

                if (selectedDirection >= 0) {
                    int selectedRegion = neighborRegions[selectedDirection];

                    if (selectedRegion >= TerrainRegions.PARTIAL_REGION_BASE) {
                        selectedRegion = blocked ? regions.getPartialFirstRegion(selectedRegion) : regions.getPartialSecondRegion(selectedRegion);
                    }

                    if (blocked) {
                        firstRegion = selectedRegion;
                    } else {
                        secondRegion = selectedRegion;
                    }
                }

                boolean firstBlocked = regions.getTerrainType(firstRegion) == TerrainRegions.BLOCKED_TERRAIN;

                if (firstRegion == secondRegion) {
                    mask = firstBlocked ? FULL_MINI_TILE_MASK : 0;
                } else if (firstBlocked && regions.getTerrainType(secondRegion) == TerrainRegions.BLOCKED_TERRAIN) {
                    mask = FULL_MINI_TILE_MASK;
                }

                regions.setPartialTile(partialCount, mask, firstRegion, secondRegion);
                regions.setTileRegion(tileIndex, TerrainRegions.PARTIAL_REGION_BASE + partialCount);
                partialCount++;
            }
        }

        regions.setPartialTileCount(partialCount);
    }

    private TerrainRegionLookupUtils() {
    }
}
