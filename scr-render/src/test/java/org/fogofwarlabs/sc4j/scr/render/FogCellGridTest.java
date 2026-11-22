package org.fogofwarlabs.sc4j.scr.render;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

/**
 * Checks that the fog cells of {@link FogCellGrid} equal a whole-map rebuild with the darkness curve evaluated per cell, over random vision
 * changes, vision masks, visible levels, tables and map sizes.
 */
final class FogCellGridTest {
    private static final long SEED = 0x5C4F06L;
    private static final int STEP_COUNT = 600;
    /**
     * The map sizes in tiles, {@code {width, height}}; the last is a one-tile map.
     */
    private static final int @NotNull [] @NotNull [] MAP_SIZES = {{37, 23}, {64, 64}, {5, 1}, {1, 9}, {1, 1}};
    /**
     * The steps between two map size changes.
     */
    private static final int MAP_SIZE_STEPS = 120;
    /**
     * The largest side of a changed tile rectangle.
     */
    private static final int MAXIMUM_CHANGE_SIZE = 7;
    /**
     * One step in this many changes the vision mask, the visible level or the table; one in {@link #NO_CHANGE_STEP} changes nothing.
     */
    private static final int SETTING_CHANGE_STEP = 9;
    private static final int NO_CHANGE_STEP = 5;
    /**
     * The tile flag word of all players: the low byte not visible, the next byte not explored.
     */
    private static final int FLAG_MASK = 0xFFFF;
    private static final int PLAYER_MASK = 0xFF;
    private static final int VISIBLE_LEVEL_COUNT = 256;
    private static final int DEFAULT_VISIBLE_LEVEL = 31;

    private static final int CELLS_PER_TILE = 4;
    private static final int MAXIMUM_LEVEL = 31;
    private static final int OBSERVER_ALPHA = 218;
    private static final int PLAYER_ALPHA = 255;
    private static final int UNEXPLORED_SHIFT = 8;
    private static final int FILTER_SHIFT = 4;
    private static final int FIXED_SHIFT = 8;
    private static final int FIXED_ONE = 1 << FIXED_SHIFT;
    private static final float CURVE_OFFSET = 0.04f;
    private static final float CURVE_BASE = 0.2f;
    private static final float CURVE_SCALE = 0.8198f;
    private static final float CHANNEL_MAXIMUM = 255.0f;

    private static int calculateReferenceAlpha(int level, int cap) {
        float value = ((float) Math.sqrt(1.0f - level / (float) MAXIMUM_LEVEL + CURVE_OFFSET) - CURVE_BASE) / CURVE_SCALE;

        if (!(value > 0)) {
            return 0;
        }

        return Math.min(cap, Math.round(Math.min(1.0f, value) * CHANNEL_MAXIMUM));
    }

    private static int @NotNull [] buildReferenceCells(int @NotNull [] flags, int width, int height, int visionMask, int visibleLevel,
                                                       boolean observer) {
        int[] levels = new int[width * height];
        int[] filtered = new int[width * height];
        int cellColumns = width * CELLS_PER_TILE;
        int[] cells = new int[cellColumns * height * CELLS_PER_TILE];
        int mask = visionMask & PLAYER_MASK;
        int visible = visibleLevel & PLAYER_MASK;
        int cap = observer ? OBSERVER_ALPHA : PLAYER_ALPHA;

        for (int index = 0; index < levels.length; index++) {
            levels[index] = (~flags[index] & mask) != 0 ? visible : (~(flags[index] >>> UNEXPLORED_SHIFT) & mask) != 0 ? visible >>> 1 : 0;
        }

        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int sum = 0;

                for (int rowOffset = -1; rowOffset <= 1; rowOffset++) {
                    for (int columnOffset = -1; columnOffset <= 1; columnOffset++) {
                        sum += levels[Math.clamp(row + rowOffset, 0, height - 1) * width + Math.clamp(column + columnOffset, 0, width - 1)] *
                               (columnOffset == 0 ? 2 : 1) * (rowOffset == 0 ? 2 : 1);
                    }
                }

                filtered[row * width + column] = Math.min(sum >>> FILTER_SHIFT, MAXIMUM_LEVEL);
            }
        }

        for (int row = 0; row < height; row++) {
            int below = Math.min(row + 1, height - 1);

            for (int column = 0; column < width; column++) {
                int right = Math.min(column + 1, width - 1);
                int topLeft = filtered[row * width + column] << FIXED_SHIFT;
                int topRight = filtered[row * width + right] << FIXED_SHIFT;
                int leftStep = ((filtered[below * width + column] << FIXED_SHIFT) - topLeft) / CELLS_PER_TILE;
                int rightStep = ((filtered[below * width + right] << FIXED_SHIFT) - topRight) / CELLS_PER_TILE;

                for (int cellRow = 0; cellRow < CELLS_PER_TILE; cellRow++) {
                    int from = topLeft + cellRow * leftStep >> FIXED_SHIFT;
                    int to = topRight + cellRow * rightStep >> FIXED_SHIFT;

                    for (int cell = 0; cell < CELLS_PER_TILE; cell++) {
                        int level = Math.floorDiv(from * FIXED_ONE + cell * (to - from) * (FIXED_ONE / CELLS_PER_TILE), FIXED_ONE) & PLAYER_MASK;
                        int cellIndex = (row * CELLS_PER_TILE + cellRow) * cellColumns + column * CELLS_PER_TILE + cell;

                        cells[cellIndex] = calculateReferenceAlpha(Math.min(level, MAXIMUM_LEVEL), cap);
                    }
                }
            }
        }

        return cells;
    }


    @Test
    void fog_alpha_tables_match_the_darkness_curve() {
        for (int level = 0; level <= MAXIMUM_LEVEL; level++) {
            Assertions.assertEquals(calculateReferenceAlpha(level, PLAYER_ALPHA), FogCellGrid.getFogAlpha(level, PLAYER_ALPHA));
            Assertions.assertEquals(calculateReferenceAlpha(level, OBSERVER_ALPHA), FogCellGrid.getFogAlpha(level, OBSERVER_ALPHA));
        }
    }

    @Test
    void updated_cells_equal_a_whole_map_rebuild() {
        Random random = new Random(SEED);
        FogCellGrid grid = new FogCellGrid();
        int[] flags = new int[0];
        int width = 0;
        int height = 0;
        int visionMask = 1;
        int visibleLevel = DEFAULT_VISIBLE_LEVEL;
        boolean observer = false;

        for (int step = 0; step < STEP_COUNT; step++) {
            if (step % MAP_SIZE_STEPS == 0) {
                int[] size = MAP_SIZES[step / MAP_SIZE_STEPS % MAP_SIZES.length];

                width  = size[0];
                height = size[1];
                flags  = new int[width * height];

                Arrays.fill(flags, FLAG_MASK);
            } else if (random.nextInt(SETTING_CHANGE_STEP) == 0) {
                visionMask   = random.nextInt(PLAYER_MASK + 1);
                visibleLevel = random.nextBoolean() ? DEFAULT_VISIBLE_LEVEL : random.nextInt(VISIBLE_LEVEL_COUNT);
                observer     = random.nextBoolean();
            } else if (random.nextInt(NO_CHANGE_STEP) != 0) {
                int left = random.nextInt(width);
                int top = random.nextInt(height);
                int right = Math.min(width, left + 1 + random.nextInt(MAXIMUM_CHANGE_SIZE));
                int bottom = Math.min(height, top + 1 + random.nextInt(MAXIMUM_CHANGE_SIZE));
                int value = random.nextInt(FLAG_MASK + 1);

                for (int row = top; row < bottom; row++) {
                    Arrays.fill(flags, row * width + left, row * width + right, value);
                }
            }

            int[] current = flags;
            int[] expected = buildReferenceCells(flags, width, height, visionMask, visibleLevel, observer);

            grid.update(index -> current[index], width, height, visionMask, visibleLevel, observer);

            Assertions.assertEquals(width * CELLS_PER_TILE, grid.getCellColumns());
            Assertions.assertEquals(height * CELLS_PER_TILE, grid.getCellRows());
            Assertions.assertArrayEquals(expected, Arrays.copyOf(grid.getCells(), expected.length), "fog cells at step " + step);
        }
    }

    @Test
    void an_update_without_a_level_change_rebuilds_nothing() {
        FogCellGrid grid = new FogCellGrid();
        int[] flags = new int[MAP_SIZES[0][0] * MAP_SIZES[0][1]];

        Arrays.fill(flags, FLAG_MASK);

        Assertions.assertTrue(grid.update(index -> flags[index], MAP_SIZES[0][0], MAP_SIZES[0][1], 1, DEFAULT_VISIBLE_LEVEL, false));
        Assertions.assertFalse(grid.update(index -> flags[index], MAP_SIZES[0][0], MAP_SIZES[0][1], 1, DEFAULT_VISIBLE_LEVEL, false));

        flags[0] = 0;

        Assertions.assertTrue(grid.update(index -> flags[index], MAP_SIZES[0][0], MAP_SIZES[0][1], 1, DEFAULT_VISIBLE_LEVEL, false));
    }
}
