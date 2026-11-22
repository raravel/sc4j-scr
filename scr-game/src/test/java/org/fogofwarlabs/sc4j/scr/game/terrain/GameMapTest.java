package org.fogofwarlabs.sc4j.scr.game.terrain;

import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Checks the represented terrain buffer, its visibility masks and its unavailable-state boundary.
 */
final class GameMapTest {
    private static final int HEIGHT_MAP_WIDTH = 64;
    private static final int HEIGHT_MAP_HEIGHT = 40;
    private static final int FIRST_DWORD_ABOVE_WORD = 0x10000;
    private static final int HEIGHT_DWORD_BALANCING_Y = -1024;
    private static final int MINIMUM_TERRAIN_GROUPS = 3;
    private static final int TERRAIN_GROUP_RECORD_BYTES = 52;
    private static final int TERRAIN_GROUP_TILES_OFFSET = 20;
    private static final int RAW_HIGH_MEGATILE_INDEX = 0x8000;
    private static final int HIGH_TERRAIN_TILE_IDENTIFIER = 1;
    private static final int MINIATURES_PER_MEGATILE = TerrainTileset.MINI_TILES_PER_ROW * TerrainTileset.MINI_TILES_PER_ROW;
    private static final int MEGATILE_RECORD_BYTES = MINIATURES_PER_MEGATILE * Short.BYTES;

    private static final int SMALL_MAP_WIDTH = 4;
    private static final int SMALL_MAP_HEIGHT = 3;
    private static final int SMALL_MAP_TILE_COUNT = SMALL_MAP_WIDTH * SMALL_MAP_HEIGHT;
    private static final int SMALL_MAP_PIXEL_WIDTH = SMALL_MAP_WIDTH * TerrainTileset.TILE_SIZE;
    private static final int SMALL_MAP_PIXEL_HEIGHT = SMALL_MAP_HEIGHT * TerrainTileset.TILE_SIZE;
    private static final int BORDER_MAP_WIDTH = 12;
    private static final int BORDER_MAP_HEIGHT = 4;
    private static final int BORDER_MAP_TILE_COUNT = BORDER_MAP_WIDTH * BORDER_MAP_HEIGHT;
    /**
     * The bottom rows that the terrain borders cover and the columns at both sides of those rows that they cover.
     */
    private static final int BORDER_ROWS = 2;
    private static final int BORDER_COLUMNS = 5;
    /**
     * The flags of tile {@code 0} of the numbered flag inputs; tile {@code n} has this value plus {@code n}.
     */
    private static final int NUMBERED_TILE_FLAGS_BASE = 100;
    /**
     * Unsigned WORD coordinates of {@code -1} and {@code -33}.
     */
    private static final int WORD_MINUS_ONE = 0xFFFF;
    private static final int WORD_MINUS_THIRTY_THREE = 0xFFDF;

    /**
     * The native flags read for a tile past the map tiles or outside the fixed tile array.
     */
    private static final int UNUSED_TILE_FLAGS = 0xFFFF;
    /**
     * The native terrain heights of high ground and middle ground.
     */
    private static final int HIGH_GROUND_HEIGHT = 2;
    private static final int MIDDLE_GROUND_HEIGHT = 1;
    /**
     * The native fixed tile array: the largest map width or height in tiles and the tile count of the array.
     */
    private static final int MAXIMUM_DIMENSION = 256;
    private static final int FIXED_TILE_COUNT = MAXIMUM_DIMENSION * MAXIMUM_DIMENSION;
    /**
     * A saved megatile byte with every bit set and the unsigned WORD that two of them read as.
     */
    private static final byte ALL_ONES_BYTE = (byte) 0xFF;
    private static final int ALL_ONES_WORD = 0xFFFF;

    /**
     * Creates a {@link TerrainTileset} instance whose raw high megatile differs from its masked low megatile.
     *
     * @return the created {@link TerrainTileset} instance
     * @throws IOException if the format fixture cannot be parsed
     */
    private static @NotNull TerrainTileset createHeightTerrain() throws IOException {
        ByteBuffer groups = ByteBuffer.allocate(MINIMUM_TERRAIN_GROUPS * TERRAIN_GROUP_RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer miniatureFlags = ByteBuffer.allocate((RAW_HIGH_MEGATILE_INDEX + 1) * MEGATILE_RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);

        groups.putShort(TERRAIN_GROUP_TILES_OFFSET + HIGH_TERRAIN_TILE_IDENTIFIER * Short.BYTES, (short) RAW_HIGH_MEGATILE_INDEX);

        for (int miniatureIndex = 0; miniatureIndex < MINIATURES_PER_MEGATILE; miniatureIndex++) {
            miniatureFlags.putShort(miniatureIndex * Short.BYTES, (short) TerrainTileset.MINI_TILE_MIDDLE);
            miniatureFlags.putShort(RAW_HIGH_MEGATILE_INDEX * MEGATILE_RECORD_BYTES + miniatureIndex * Short.BYTES,
                                    (short) TerrainTileset.MINI_TILE_HIGH);
        }

        return TerrainTileset.parse(groups.array(), miniatureFlags.array());
    }


    @Test
    void game_maps_copy_terrain_inputs_independently_across_worlds() {
        int[] identifiers = {1};
        int[] flags = {2};
        int[] overlays = {3};

        GameMap first = new GameMap(1, 1, 0, identifiers, flags);
        GameMap second = new GameMap(1, 1, 0, identifiers, flags);

        GameMapScenario.setOverlays(first, overlays);
        GameMapScenario.setOverlays(second, overlays);

        identifiers[0] = 4;
        flags[0]       = 5;
        overlays[0]    = 6;

        first.setCreepVariant(0, 1);
        first.occupyTile(0);
        GameMapScenario.setOverlays(first, new int[]{9});

        int[] renderSnapshot = first.copyTileIdentifiersForRendering();

        renderSnapshot[0] = 99;

        Assertions.assertEquals(17, first.getTileIdentifier(0));
        Assertions.assertEquals(1, second.getTileIdentifier(0, 0));
        Assertions.assertEquals(2, second.getTileFlags(0, 0));
        Assertions.assertEquals(3, second.getOverlayTileIdentifier(0));
    }

    @Test
    void initialize_overlay_tiles_should_run_once_without_replacing_retained_creep_terrain() {
        GameMap map = new GameMap(1, 1, 4, new int[]{0x8001});

        Assertions.assertFalse(map.hasOverlayTiles());

        map.initializeOverlayTiles();

        Assertions.assertEquals(0, map.getOverlayTileIdentifier(0));

        map.retainTerrainUnderCreep(0);
        map.setCreepVariant(0, 2);

        Assertions.assertThrows(IllegalStateException.class, map::initializeOverlayTiles);
        Assertions.assertEquals(0x8001, map.getOverlayTileIdentifier(0));
        Assertions.assertEquals(18, map.getTileIdentifier(0));
    }

    @Test
    void terrain_borders_apply_bottom_rectangles_and_overlays_reject_mismatched_length() {
        int[] flags = new int[BORDER_MAP_TILE_COUNT];

        Arrays.fill(flags, 0xFFFFFFFF);

        GameMap map = new GameMap(BORDER_MAP_WIDTH, BORDER_MAP_HEIGHT, 0, new int[BORDER_MAP_TILE_COUNT], flags);

        Assertions.assertFalse(map.hasOverlayTiles());

        map.initializeTerrainBorders();

        for (int tileIndex = 0; tileIndex < BORDER_MAP_TILE_COUNT; tileIndex++) {
            boolean border = tileIndex >= BORDER_MAP_TILE_COUNT - BORDER_MAP_WIDTH ||
                             tileIndex >= BORDER_MAP_TILE_COUNT - BORDER_ROWS * BORDER_MAP_WIDTH &&
                             (tileIndex % BORDER_MAP_WIDTH < BORDER_COLUMNS || tileIndex % BORDER_MAP_WIDTH >= BORDER_MAP_WIDTH - BORDER_COLUMNS);

            Assertions.assertEquals(border ? 0xDFBEFFFF : 0xFFFFFFFF, map.getTileFlags(tileIndex));
        }

        int[] overlays = new int[BORDER_MAP_TILE_COUNT];

        overlays[17] = 0x8001;
        GameMapScenario.setOverlays(map, overlays);

        Assertions.assertEquals(0x8001, map.getOverlayTileIdentifier(17));
        Assertions.assertThrows(IllegalArgumentException.class, () -> GameMapScenario.setOverlays(map, new int[BORDER_MAP_TILE_COUNT - 1]));
    }

    @Test
    void get_tile_flags_should_reject_queries_without_expanded_terrain() {
        GameMap map = new GameMap(2, 2, 0, new int[4]);

        Assertions.assertFalse(map.hasTileFlags());
        Assertions.assertThrows(IllegalStateException.class, () -> map.getTileFlags(0));
        Assertions.assertThrows(IllegalStateException.class, () -> map.getTileFlags(0, 0));
    }

    @Test
    void get_tile_flags_should_read_row_major_without_wrapping_coordinates() {
        GameMap map = new GameMap(2, 2, 0, new int[4], new int[]{0x10000, 0x40000, 0x20010000, 0x04010000});

        Assertions.assertTrue(map.hasTileFlags());
        Assertions.assertEquals(0x10000, map.getTileFlags(0, 0));
        Assertions.assertEquals(0x40000, map.getTileFlags(1, 0));
        Assertions.assertEquals(0x20010000, map.getTileFlags(0, 1));
        Assertions.assertEquals(0x04010000, map.getTileFlags(1, 1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> map.getTileFlags(2, 0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> map.getTileFlags(-1, 1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> map.getTileFlags(0, -1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> map.getTileFlags(0, 2));
    }

    @Test
    void set_tile_visibility_should_replace_only_the_low_word_of_one_tile() {
        GameMap map = new GameMap(2, 2, 0, new int[4], new int[]{0x10000, 0x40000, 0x2001FFFF, 0x04010000});

        map.setTileVisibility(0, 1, 0x12345);

        Assertions.assertEquals(0x20012345, map.getTileFlags(0, 1));
        Assertions.assertEquals(0x10000, map.getTileFlags(0, 0));
        Assertions.assertEquals(0x04010000, map.getTileFlags(1, 1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> map.setTileVisibility(2, 0, 0));
        Assertions.assertThrows(IllegalStateException.class, () -> new GameMap(2, 2, 0, new int[4]).setTileVisibility(0, 0, 0));
    }

    @Test
    void game_map_rejects_terrain_buffers_with_mismatched_extents() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new GameMap(2, 2, 0, new int[4], new int[3]));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new GameMap(2, 2, 0, new int[3], new int[4]));
    }

    @Test
    void get_tile_flags_at_position_should_read_next_row_past_the_right_edge() {
        int[] flags = new int[SMALL_MAP_TILE_COUNT];

        for (int index = 0; index < flags.length; index++) {
            flags[index] = index + NUMBERED_TILE_FLAGS_BASE;
        }

        GameMap map = new GameMap(SMALL_MAP_WIDTH, SMALL_MAP_HEIGHT, 0, new int[SMALL_MAP_TILE_COUNT], flags);

        Assertions.assertEquals(map.getTileFlags(1, 2), map.getTileFlagsAtPosition(TerrainTileset.TILE_SIZE + 5, 2 * TerrainTileset.TILE_SIZE + 31));
        Assertions.assertEquals(map.getTileFlags(0, 1), map.getTileFlagsAtPosition(SMALL_MAP_PIXEL_WIDTH, 0),
                                "Column four reads the next row's first tile.");
        Assertions.assertEquals(map.getTileFlags(1, 1), map.getTileFlagsAtPosition(SMALL_MAP_PIXEL_WIDTH + 40, 5));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> map.getTileFlags(SMALL_MAP_WIDTH, 0));
    }

    @Test
    void get_tile_flags_at_position_should_read_unused_tile_flags_past_the_map_tiles() {
        GameMap map = new GameMap(SMALL_MAP_WIDTH, SMALL_MAP_HEIGHT, 0, new int[SMALL_MAP_TILE_COUNT], new int[SMALL_MAP_TILE_COUNT]);

        Assertions.assertEquals(UNUSED_TILE_FLAGS,
                                map.getTileFlagsAtPosition(SMALL_MAP_PIXEL_WIDTH, SMALL_MAP_PIXEL_HEIGHT - TerrainTileset.TILE_SIZE),
                                "Column four of the last row reads the first tile past the map.");
        Assertions.assertEquals(UNUSED_TILE_FLAGS, map.getTileFlagsAtPosition(0, SMALL_MAP_PIXEL_HEIGHT));
        Assertions.assertEquals(UNUSED_TILE_FLAGS, map.getTileFlagsAtPosition(Short.MAX_VALUE, Short.MAX_VALUE));
    }

    @Test
    void get_tile_flags_at_position_should_read_saved_flags_past_the_map_tiles_of_a_loaded_game() {
        int savedFlags = 0x00A1B2C3;
        GameMap map = new GameMap(SMALL_MAP_WIDTH, SMALL_MAP_HEIGHT, 0, new int[SMALL_MAP_TILE_COUNT], new int[SMALL_MAP_TILE_COUNT]);
        ByteBuffer flags = ByteBuffer.allocate(FIXED_TILE_COUNT * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);

        flags.putInt(0, savedFlags);
        flags.putInt((SMALL_MAP_TILE_COUNT + 1) * Integer.BYTES, savedFlags);
        map.retainSavedArrays(new byte[FIXED_TILE_COUNT * Short.BYTES], new byte[FIXED_TILE_COUNT * Short.BYTES], flags.array());

        Assertions.assertEquals(savedFlags, map.getTileFlagsAtPosition(TerrainTileset.TILE_SIZE, SMALL_MAP_PIXEL_HEIGHT));
        Assertions.assertEquals(0, map.getTileFlagsAtPosition(0, SMALL_MAP_PIXEL_HEIGHT));
        Assertions.assertEquals(0, map.getTileFlagsAtPosition(0, 0), "The map's tiles keep their current flags, not the saved ones.");
    }

    @Test
    void get_tile_flags_at_position_should_read_unused_tile_flags_outside_the_fixed_tile_array() {
        GameMap map = new GameMap(SMALL_MAP_WIDTH, SMALL_MAP_HEIGHT, 0, new int[SMALL_MAP_TILE_COUNT], new int[SMALL_MAP_TILE_COUNT]);
        GameMap widestMap = new GameMap(MAXIMUM_DIMENSION, 1, 0, new int[MAXIMUM_DIMENSION], new int[MAXIMUM_DIMENSION]);

        Assertions.assertEquals(UNUSED_TILE_FLAGS, map.getTileFlagsAtPosition(0, WORD_MINUS_THIRTY_THREE), "Row -1 reads before the array.");
        Assertions.assertEquals(UNUSED_TILE_FLAGS, map.getTileFlagsAtPosition(WORD_MINUS_THIRTY_THREE, 0),
                                "Column -1 of row zero reads before the array.");
        Assertions.assertEquals(UNUSED_TILE_FLAGS, widestMap.getTileFlagsAtPosition(0, MAXIMUM_DIMENSION * TerrainTileset.TILE_SIZE),
                                "Row 256 of a 256-tile-wide map reads past the array.");
    }

    @Test
    void get_tile_flags_at_position_should_read_word_coordinates_as_signed() {
        int[] flags = new int[SMALL_MAP_TILE_COUNT];

        for (int index = 0; index < flags.length; index++) {
            flags[index] = index + NUMBERED_TILE_FLAGS_BASE;
        }

        GameMap map = new GameMap(SMALL_MAP_WIDTH, SMALL_MAP_HEIGHT, 0, new int[SMALL_MAP_TILE_COUNT], flags);

        Assertions.assertEquals(map.getTileFlags(0, 1), map.getTileFlagsAtPosition(WORD_MINUS_ONE, TerrainTileset.TILE_SIZE),
                                "-1 truncates to column zero.");
        Assertions.assertEquals(map.getTileFlags(1, 0), map.getTileFlagsAtPosition(TerrainTileset.TILE_SIZE, WORD_MINUS_ONE),
                                "-1 truncates to row zero.");
        Assertions.assertEquals(map.getTileFlags(3, 1), map.getTileFlagsAtPosition(WORD_MINUS_THIRTY_THREE, 2 * TerrainTileset.TILE_SIZE),
                                "Column -1 reads the previous row's last tile.");
    }

    @Test
    void has_tile_flags_at_position_should_need_a_required_flag_and_no_rejected_flag_at_signed_positions() {
        int walkableOrCreep = MapTileFlags.WALKABLE | MapTileFlags.HAS_CREEP;
        int[] flags = new int[SMALL_MAP_TILE_COUNT];

        flags[3] = MapTileFlags.HAS_CREEP | MapTileFlags.OCCUPIED;
        flags[4] = MapTileFlags.WALKABLE;

        GameMap map = new GameMap(SMALL_MAP_WIDTH, SMALL_MAP_HEIGHT, 0, new int[SMALL_MAP_TILE_COUNT], flags);

        Assertions.assertTrue(map.hasTileFlagsAtPosition(WORD_MINUS_ONE, TerrainTileset.TILE_SIZE, walkableOrCreep, MapTileFlags.OCCUPIED),
                              "-1 truncates to column zero of row one.");
        Assertions.assertTrue(map.hasTileFlagsAtPosition(SMALL_MAP_PIXEL_WIDTH, 0, walkableOrCreep, MapTileFlags.OCCUPIED),
                              "Column four reads the next row's first tile.");
        Assertions.assertFalse(map.hasTileFlagsAtPosition(WORD_MINUS_THIRTY_THREE, TerrainTileset.TILE_SIZE, walkableOrCreep, MapTileFlags.OCCUPIED),
                               "Column -1 reads the previous row's occupied last tile.");
        Assertions.assertTrue(map.hasTileFlagsAtPosition(WORD_MINUS_THIRTY_THREE, TerrainTileset.TILE_SIZE, walkableOrCreep, 0));
        Assertions.assertFalse(map.hasTileFlagsAtPosition(0, 0, walkableOrCreep, 0));
        Assertions.assertFalse(map.hasTileFlagsAtPosition(0, WORD_MINUS_THIRTY_THREE, walkableOrCreep, 0), "Row -1 reads before the array.");
        Assertions.assertTrue(map.hasTileFlagsAtPosition(0, WORD_MINUS_THIRTY_THREE, MapTileFlags.NOT_VISIBLE, MapTileFlags.OCCUPIED),
                              "Outside the array no player sees the tile.");
    }

    @Test
    void get_terrain_height_should_keep_dword_coordinates_and_wrapping_row_major_arithmetic() throws IOException {
        TerrainTileset terrain = createHeightTerrain();
        GameMap map = new GameMap(HEIGHT_MAP_WIDTH, HEIGHT_MAP_HEIGHT, 0, new int[HEIGHT_MAP_WIDTH * HEIGHT_MAP_HEIGHT]);

        map.initializeOverlayTiles();
        map.restoreOverlayTileIdentifier(0, HIGH_TERRAIN_TILE_IDENTIFIER);

        Assertions.assertEquals(HIGH_GROUND_HEIGHT, map.getTerrainHeight(terrain, FIRST_DWORD_ABOVE_WORD, HEIGHT_DWORD_BALANCING_Y));
        Assertions.assertThrows(IndexOutOfBoundsException.class,
                                () -> map.getTerrainHeight(terrain, (short) FIRST_DWORD_ABOVE_WORD, HEIGHT_DWORD_BALANCING_Y));
        Assertions.assertEquals(HIGH_GROUND_HEIGHT, map.getTerrainHeight(terrain, 0, Integer.MIN_VALUE));

        map.restoreOverlayTileIdentifier(0, 0);

        Assertions.assertEquals(MIDDLE_GROUND_HEIGHT, map.getTerrainHeight(terrain, 0, 0));

        map.restoreTileIdentifier(HEIGHT_MAP_WIDTH, HIGH_TERRAIN_TILE_IDENTIFIER);

        Assertions.assertEquals(HIGH_GROUND_HEIGHT, map.getTerrainHeight(terrain, HEIGHT_MAP_WIDTH * TerrainTileset.TILE_SIZE, 0));
    }

    @Test
    void get_terrain_height_should_require_overlay_storage_before_selecting_a_tile() throws IOException {
        TerrainTileset terrain = createHeightTerrain();
        GameMap map = new GameMap(1, 1, 0, new int[]{HIGH_TERRAIN_TILE_IDENTIFIER});

        Assertions.assertThrows(IllegalStateException.class, () -> map.getTerrainHeight(terrain, 0, 0));

        map.initializeOverlayTiles();

        Assertions.assertEquals(HIGH_GROUND_HEIGHT, map.getTerrainHeight(terrain, 0, 0));
    }

    @Test
    void get_saved_graphics_cache_should_validate_fixed_index_then_snapshot_then_actual_word_extent() {
        GameMap map = new GameMap(1, 1, 0, new int[]{0});

        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> map.getSavedGraphicsCache(-1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> map.getSavedGraphicsCache(FIXED_TILE_COUNT));
        Assertions.assertThrows(IllegalStateException.class, () -> map.getSavedGraphicsCache(0));

        map.retainSavedArrays(new byte[0], new byte[Short.BYTES - 1], new byte[0]);

        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> map.getSavedGraphicsCache(0));
    }

    @Test
    void get_saved_graphics_cache_should_read_unsigned_words_from_independently_retained_bytes() {
        byte[] megatiles = {ALL_ONES_BYTE, ALL_ONES_BYTE};
        GameMap map = new GameMap(1, 1, 0, new int[]{0});

        map.retainSavedArrays(new byte[0], megatiles, new byte[0]);
        megatiles[0] = 0;

        Assertions.assertEquals(ALL_ONES_WORD, map.getSavedGraphicsCache(0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> map.getSavedGraphicsCache(1));
    }

    @Test
    void apply_mask_should_replace_visible_and_explored_bytes_and_keep_terrain_flags() {
        GameMap map = new GameMap(2, 1, 0, new int[2], new int[]{MapTileFlags.HAS_CREEP | MapTileFlags.WALKABLE | 0x1234, MapTileFlags.WALKABLE});

        map.applyMask(0, 0x0F);
        map.applyMask(1, 0xFF);

        Assertions.assertEquals(0x00410000 | 0x0F0F, map.getTileFlags(0));
        Assertions.assertEquals(0x0001FFFF, map.getTileFlags(1));
    }

    @Test
    void apply_terrain_border_should_match_initialize_terrain_borders() {
        int[] flags = new int[BORDER_MAP_TILE_COUNT];

        Arrays.fill(flags, 0xFFFFFFFF);

        GameMap map = new GameMap(BORDER_MAP_WIDTH, BORDER_MAP_HEIGHT, 0, new int[BORDER_MAP_TILE_COUNT], flags);

        map.initializeTerrainBorders();

        for (int tileIndex = 0; tileIndex < BORDER_MAP_TILE_COUNT; tileIndex++) {
            Assertions.assertEquals(map.getTileFlags(tileIndex), map.applyTerrainBorder(tileIndex, 0xFFFFFFFF), "tile " + tileIndex);
        }
    }

    @Test
    void replace_terrain_should_keep_visibility_occupancy_and_creep() {
        int previous = MapTileFlags.WALKABLE | MapTileFlags.MIDDLE;
        int replacement = MapTileFlags.UNWALKABLE | MapTileFlags.UNBUILDABLE | MapTileFlags.HIGH;
        GameMap map = new GameMap(3, 1, 0, new int[]{0x21, 0x22, 0x23}, new int[]{previous | MapTileFlags.OCCUPIED | 0x0102, previous | 0x00FF,
                                                                                  previous | MapTileFlags.OCCUPIED});

        map.initializeOverlayTiles();
        map.retainTerrainUnderCreep(1);
        map.setCreepVariant(1, 3);
        map.applyCreepTerrainFlags(1, MapTileFlags.HAS_CREEP | MapTileFlags.WALKABLE, true);

        map.replaceTerrain(0, 0x45, previous, replacement);
        map.replaceTerrain(1, 0x46, previous, replacement);
        map.replaceTerrain(2, 0x47, previous | MapTileFlags.OCCUPIED, replacement);

        Assertions.assertEquals(0x45, map.getTileIdentifier(0));
        Assertions.assertEquals(0x45, map.getTerrainTileIdentifier(0));
        Assertions.assertEquals(replacement | MapTileFlags.OCCUPIED | 0x0102, map.getTileFlags(0));

        Assertions.assertEquals(TerrainTileset.CREEP_TILE + 3, map.getTileIdentifier(1));
        Assertions.assertEquals(0x46, map.getOverlayTileIdentifier(1));
        Assertions.assertEquals(0x46, map.getTerrainTileIdentifier(1));
        Assertions.assertEquals(MapTileFlags.WALKABLE | MapTileFlags.HAS_CREEP | MapTileFlags.UNBUILDABLE | MapTileFlags.HIGH |
                                MapTileFlags.TEMPORARY_CREEP | 0x00FF, map.getTileFlags(1));

        Assertions.assertEquals(0x47, map.getTileIdentifier(2));
        Assertions.assertEquals(replacement, map.getTileFlags(2));
    }
}
