package org.fogofwarlabs.sc4j.scr.render;

import org.jetbrains.annotations.NotNull;

import java.util.function.IntUnaryOperator;

/**
 * Holds the fog of war of a map as darkness cells: the tile levels from the tile flags, their {@code 3x3} filter, the interpolated cells of
 * {@code 8} pixels and the darkness table. An update rebuilds only the tiles around changed levels.
 */
final class FogCellGrid {
    /**
     * The highest alpha of the observer table (unexplored ground keeps {@code 1 - 218 / 255} of its brightness).
     */
    static final int OBSERVER_FOG_ALPHA = 218;
    /**
     * The highest alpha of the player table.
     */
    static final int MAXIMUM_FOG_ALPHA = 255;
    /**
     * The fog cells per tile and axis: cells of {@code 8} pixels.
     */
    private static final int FOG_CELLS_PER_TILE = 4;

    /**
     * The mask of an unsigned byte.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The position of the not-explored player bits in a tile flag DWORD.
     */
    private static final int UNEXPLORED_FLAGS_SHIFT = 8;
    /**
     * The highest level the fog tables of {@code 32} entries hold.
     */
    private static final int MAXIMUM_FOG_LEVEL = 31;
    /**
     * The shift of a division by {@link #FOG_CELLS_PER_TILE}.
     */
    private static final int FOG_CELLS_PER_TILE_SHIFT = 2;
    /**
     * The weight of the {@code 3x3} filter's center row and column (kernel {@code [1 2 1; 2 4 2; 1 2 1]}).
     */
    private static final int FILTER_CENTER_WEIGHT = 2;
    /**
     * The shift of the {@code 3x3} filter's divisor {@code 16}.
     */
    private static final int FILTER_SHIFT = 4;
    /**
     * The fraction bits of the cell interpolation's fixed-point values.
     */
    private static final int FIXED_POINT_SHIFT = 8;
    /**
     * The darkness curve of the fog tables: {@code (sqrt(1 + offset - level / 31) - base) / scale}.
     */
    private static final float FOG_CURVE_OFFSET = 0.04f;
    private static final float FOG_CURVE_BASE = 0.2f;
    private static final float FOG_CURVE_SCALE = 0.8198f;
    private static final float CHANNEL_MAXIMUM = 255.0f;

    /**
     * The darkness alpha of each fog level in the player table and in the observer table.
     */
    private static final int @NotNull [] PLAYER_ALPHAS = createAlphaTable(MAXIMUM_FOG_ALPHA);
    private static final int @NotNull [] OBSERVER_ALPHAS = createAlphaTable(OBSERVER_FOG_ALPHA);


    /**
     * Returns the darkness alpha of a fog level with a configurable alpha cap: the player table with cap {@code 255} and the observer table with cap
     * {@link #OBSERVER_FOG_ALPHA}.
     *
     * @param level the fog level, {@code 0} (unexplored) to {@code 31} (visible)
     * @param cap   the table's highest alpha
     * @return the alpha, {@code 0} to {@code cap}
     */
    static int getFogAlpha(int level, int cap) {
        float value = ((float) Math.sqrt(1.0f - level / (float) MAXIMUM_FOG_LEVEL + FOG_CURVE_OFFSET) - FOG_CURVE_BASE) / FOG_CURVE_SCALE;

        if (!(value > 0)) {
            return 0;
        }

        return Math.min(cap, Math.round(Math.min(1.0f, value) * CHANNEL_MAXIMUM));
    }

    private static int @NotNull [] createAlphaTable(int cap) {
        int[] alphas = new int[MAXIMUM_FOG_LEVEL + 1];

        for (int level = 0; level <= MAXIMUM_FOG_LEVEL; level++) {
            alphas[level] = getFogAlpha(level, cap);
        }

        return alphas;
    }


    private int @NotNull [] levels;
    private int @NotNull [] filtered;
    private int @NotNull [] cells;

    private int mapWidth;
    private int mapHeight;
    private boolean observer;

    /**
     * Creates a {@link FogCellGrid} instance without a map.
     */
    FogCellGrid() {
        this.levels   = new int[0];
        this.filtered = new int[0];
        this.cells    = new int[0];

        this.mapWidth  = 0;
        this.mapHeight = 0;
        this.observer  = false;
    }

    /**
     * @return the darkness alpha of each cell, row-major with {@link #getCellColumns} cells per row
     */
    int @NotNull [] getCells() {
        return this.cells;
    }

    /**
     * @return the cell columns of the map
     */
    int getCellColumns() {
        return this.mapWidth * FOG_CELLS_PER_TILE;
    }

    /**
     * @return the cell rows of the map
     */
    int getCellRows() {
        return this.mapHeight * FOG_CELLS_PER_TILE;
    }


    /**
     * Reads the tile levels from the tile flags and rebuilds the cells of the tiles whose filtered level may have changed; a new map size or
     * table rebuilds every cell.
     *
     * @param tileFlags    the current tile flag DWORD for each row-major tile index
     * @param mapWidth     the map width in tiles, above {@code 0}
     * @param mapHeight    the map height in tiles, above {@code 0}
     * @param visionMask   the eight-bit mask of players whose vision is shown
     * @param visibleLevel the level of visible tiles, the game's visible level BYTE (default {@code 31})
     * @param observer     whether the observer table is used
     * @return {@code true} if a cell was rebuilt, {@code false} otherwise
     */
    boolean update(@NotNull IntUnaryOperator tileFlags, int mapWidth, int mapHeight, int visionMask, int visibleLevel, boolean observer) {
        int tileCount = mapWidth * mapHeight;
        boolean rebuilt = mapWidth != this.mapWidth || mapHeight != this.mapHeight || observer != this.observer;

        if (rebuilt) {
            this.mapWidth  = mapWidth;
            this.mapHeight = mapHeight;
            this.observer  = observer;

            if (this.levels.length < tileCount) {
                this.levels   = new int[tileCount];
                this.filtered = new int[tileCount];
                this.cells    = new int[tileCount * FOG_CELLS_PER_TILE * FOG_CELLS_PER_TILE];
            }
        }

        int mask = visionMask & BYTE_MASK;
        int visible = visibleLevel & BYTE_MASK;
        int changedLeft = mapWidth;
        int changedTop = mapHeight;
        int changedRight = -1;
        int changedBottom = -1;

        for (int row = 0; row < mapHeight; row++) {
            int rowIndex = row * mapWidth;

            for (int column = 0; column < mapWidth; column++) {
                int flags = tileFlags.applyAsInt(rowIndex + column);
                int level = (~flags & mask) != 0 ? visible : (~(flags >>> UNEXPLORED_FLAGS_SHIFT) & mask) != 0 ? visible >>> 1 : 0;

                if (level != this.levels[rowIndex + column]) {
                    this.levels[rowIndex + column] = level;

                    changedLeft   = Math.min(changedLeft, column);
                    changedRight  = Math.max(changedRight, column);
                    changedTop    = Math.min(changedTop, row);
                    changedBottom = row;
                }
            }
        }

        if (rebuilt) {
            this.filterLevels(0, 0, mapWidth - 1, mapHeight - 1);
            this.interpolateCells(0, 0, mapWidth - 1, mapHeight - 1);

            return true;
        }

        if (changedBottom < 0) {
            return false;
        }

        // The filter reads the neighbouring tiles; a tile's cells read the filtered tile, its right and lower neighbours.
        int filteredLeft = Math.max(changedLeft - 1, 0);
        int filteredTop = Math.max(changedTop - 1, 0);
        int filteredRight = Math.min(changedRight + 1, mapWidth - 1);
        int filteredBottom = Math.min(changedBottom + 1, mapHeight - 1);

        this.filterLevels(filteredLeft, filteredTop, filteredRight, filteredBottom);
        this.interpolateCells(Math.max(filteredLeft - 1, 0), Math.max(filteredTop - 1, 0), filteredRight, filteredBottom);

        return true;
    }

    /**
     * Filters the tile levels of a tile rectangle with the {@code 3x3} kernel, the map edge repeated.
     *
     * @param left   the first tile column
     * @param top    the first tile row
     * @param right  the last tile column
     * @param bottom the last tile row
     */
    private void filterLevels(int left, int top, int right, int bottom) {
        int width = this.mapWidth;
        int height = this.mapHeight;

        for (int row = top; row <= bottom; row++) {
            for (int column = left; column <= right; column++) {
                int sum = 0;

                for (int rowOffset = -1; rowOffset <= 1; rowOffset++) {
                    int sampleRow = Math.clamp(row + rowOffset, 0, height - 1);

                    for (int columnOffset = -1; columnOffset <= 1; columnOffset++) {
                        int sampleColumn = Math.clamp(column + columnOffset, 0, width - 1);

                        sum += this.levels[sampleRow * width + sampleColumn] *
                               (columnOffset == 0 ? FILTER_CENTER_WEIGHT : 1) * (rowOffset == 0 ? FILTER_CENTER_WEIGHT : 1);
                    }
                }

                this.filtered[row * width + column] = Math.min(sum >>> FILTER_SHIFT, MAXIMUM_FOG_LEVEL);
            }
        }
    }

    /**
     * Interpolates the cells of a tile rectangle between the filtered levels of each tile and its right and lower neighbours, and maps their
     * levels through the darkness table.
     *
     * @param left   the first tile column
     * @param top    the first tile row
     * @param right  the last tile column
     * @param bottom the last tile row
     */
    private void interpolateCells(int left, int top, int right, int bottom) {
        int width = this.mapWidth;
        int cellColumns = width * FOG_CELLS_PER_TILE;
        int[] alphas = this.observer ? OBSERVER_ALPHAS : PLAYER_ALPHAS;

        for (int row = top; row <= bottom; row++) {
            int below = Math.min(row + 1, this.mapHeight - 1);

            for (int column = left; column <= right; column++) {
                int next = Math.min(column + 1, width - 1);
                int topLeft = this.filtered[row * width + column] << FIXED_POINT_SHIFT;
                int topRight = this.filtered[row * width + next] << FIXED_POINT_SHIFT;
                int leftStep = ((this.filtered[below * width + column] << FIXED_POINT_SHIFT) - topLeft) / FOG_CELLS_PER_TILE;
                int rightStep = ((this.filtered[below * width + next] << FIXED_POINT_SHIFT) - topRight) / FOG_CELLS_PER_TILE;

                for (int cellRow = 0; cellRow < FOG_CELLS_PER_TILE; cellRow++) {
                    int from = topLeft + cellRow * leftStep >> FIXED_POINT_SHIFT;
                    int to = topRight + cellRow * rightStep >> FIXED_POINT_SHIFT;
                    int cellIndex = (row * FOG_CELLS_PER_TILE + cellRow) * cellColumns + column * FOG_CELLS_PER_TILE;

                    for (int cell = 0; cell < FOG_CELLS_PER_TILE; cell++) {
                        int level = from + (cell * (to - from) >> FOG_CELLS_PER_TILE_SHIFT) & BYTE_MASK;

                        this.cells[cellIndex + cell] = alphas[Math.min(level, MAXIMUM_FOG_LEVEL)];
                    }
                }
            }
        }
    }
}
