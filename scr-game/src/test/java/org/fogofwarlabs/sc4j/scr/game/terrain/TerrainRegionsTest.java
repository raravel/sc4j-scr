package org.fogofwarlabs.sc4j.scr.game.terrain;

import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.test.GameTestResources;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Checks fresh-map region construction against complete diagnostic storage, the index checks before mutation, the component test of positions
 * outside the tile grid, and the signed centers and unsigned identifiers that captured region fields read back as.
 */
final class TerrainRegionsTest {
    private static final int MINIATURES_PER_TILE = 16;
    private static final int MINI_BLOCKED_SHIFT = 7;
    private static final byte MINI_BLOCKED = (byte) 0x80;
    /**
     * The blocked miniature rows at the bottom of the map, the blocked miniature columns at both sides of those rows and the bottom miniature rows
     * that are blocked across the map.
     */
    private static final int BORDER_ROWS = 8;
    private static final int BORDER_COLUMNS = 20;
    private static final int BORDER_FULL_ROWS = 4;

    private static final int REGION_CAPACITY = 5000;
    private static final int STATE_HASH_SEED = 17;

    private static final int CASE_REGION_COUNT = 4;
    private static final int CASE_REGION = 3;
    private static final int CASE_NEIGHBOR_COUNT = 2;

    /**
     * The pixel extent of the tile grid, its last pixel, a pixel one tile left of the grid and a position WORD far outside the grid.
     */
    private static final int GRID_PIXEL_EXTENT = TerrainRegions.GRID_WIDTH * TerrainTileset.TILE_SIZE;
    private static final int GRID_CORNER_PIXEL = GRID_PIXEL_EXTENT - 1;
    private static final int OFF_MAP_PIXEL = -TerrainTileset.TILE_SIZE;
    private static final int FAR_PIXEL = 50000;

    @RequiresInstallation
    @Test
    void matches_native_region_construction_cases() throws Exception {
        String[] replayFiles = {GameTestResources.REPLAY_ZVP_FIGHTING_SPIRIT, GameTestResources.REPLAY_PVP_FORTRESS};
        String[] expected = {"afb083a28f36d25d1683cb4e4909921fc837a2bcf299cd046de556e1bd87b63b",
                             "4b0616838e47462f36c08d729a50072cb24ec1af001a8bd87260703934342a22"};
        int[] expectedCounts = {447, 362};
        int[] expectedPartialCounts = {1702, 1337};

        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            for (int caseIndex = 0; caseIndex < replayFiles.length; caseIndex++) {
                ReplayMapScenario replayMap = ReplayMapScenario.load(storage, replayFiles[caseIndex]);
                TerrainTileset terrain = replayMap.getTerrain();
                int[] identifiers = replayMap.getTileIdentifiers();
                GameMap map = replayMap.getMap();
                int width = map.getWidth();
                int height = map.getHeight();
                byte[] grid = new byte[TerrainRegions.MINI_GRID_WIDTH * TerrainRegions.MINI_GRID_WIDTH];

                for (int vertical = 0; vertical < height; vertical++) {
                    for (int horizontal = 0; horizontal < width; horizontal++) {
                        for (int miniature = 0; miniature < MINIATURES_PER_TILE; miniature++) {
                            int flags = terrain.getMiniatureFlags(identifiers[vertical * width + horizontal], miniature);
                            int gridIndex = (vertical * TerrainRegions.MINI_TILES_PER_ROW + miniature / TerrainRegions.MINI_TILES_PER_ROW) *
                                            TerrainRegions.MINI_GRID_WIDTH +
                                            horizontal * TerrainRegions.MINI_TILES_PER_ROW + miniature % TerrainRegions.MINI_TILES_PER_ROW;

                            grid[gridIndex] = (byte) (((flags & TerrainTileset.MINI_TILE_WALKABLE) ^ TerrainTileset.MINI_TILE_WALKABLE) <<
                                                      MINI_BLOCKED_SHIFT);
                        }
                    }
                }

                int miniatureWidth = width * TerrainRegions.MINI_TILES_PER_ROW;
                int miniatureHeight = height * TerrainRegions.MINI_TILES_PER_ROW;

                for (int vertical = miniatureHeight - BORDER_ROWS; vertical < miniatureHeight; vertical++) {
                    int rowStart = vertical * TerrainRegions.MINI_GRID_WIDTH;

                    Arrays.fill(grid, rowStart, rowStart + BORDER_COLUMNS, MINI_BLOCKED);
                    Arrays.fill(grid, rowStart + miniatureWidth - BORDER_COLUMNS, rowStart + miniatureWidth, MINI_BLOCKED);

                    if (vertical >= miniatureHeight - BORDER_FULL_ROWS) {
                        Arrays.fill(grid, rowStart, rowStart + miniatureWidth, MINI_BLOCKED);
                    }
                }

                TerrainRegions regions = TerrainRegions.create(map, terrain, grid);

                Assertions.assertEquals(expectedCounts[caseIndex], regions.getRegionCount(), replayFiles[caseIndex]);
                Assertions.assertEquals(expectedPartialCounts[caseIndex], regions.getPartialTileCount(), replayFiles[caseIndex]);

                String resource = caseIndex == 0 ? GameTestResources.CAPTURE_REPRESENTATIVE_TERRAIN_REGIONS
                                                 : GameTestResources.CAPTURE_NON_ZERG_TERRAIN_REGIONS;
                byte[] captured = GameTestResources.readNativeCapture(resource);

                Assertions.assertEquals(expected[caseIndex],
                                        HexFormat.of().formatHex(MessageDigest.getInstance(TerrainRegionCheck.DIGEST_ALGORITHM).digest(captured)),
                                        replayFiles[caseIndex]);

                TerrainRegionCheck.assertMatches(captured, regions);
            }
        }
    }

    @Test
    void terrain_regions_reject_invalid_indices_before_mutation() {
        TerrainRegions regions = TerrainRegionCheck.create().getRegions();
        long original = regions.computeStateHash(STATE_HASH_SEED);

        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> regions.getTerrainType(REGION_CAPACITY));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> regions.getComponent(-1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> regions.getTileRegion(1L << Integer.SIZE));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> regions.setTileRegion(-1, 7));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> regions.setPartialTile(TerrainRegionCheck.PARTIAL_COUNT, 1, 2, 3));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> regions.getPartialFirstRegion(TerrainRegionCheck.PARTIAL_REGION_BASE - 1));
        Assertions.assertEquals(original, regions.computeStateHash(STATE_HASH_SEED));
    }

    @Test
    void positions_outside_the_tile_grid_share_no_component() {
        TerrainRegions regions = TerrainRegionCheck.create().getRegions();

        Assertions.assertTrue(TerrainRegionLookupUtils.isInTileGrid(OFF_MAP_PIXEL, GRID_CORNER_PIXEL));
        Assertions.assertFalse(TerrainRegionLookupUtils.isInTileGrid(OFF_MAP_PIXEL, 0));
        Assertions.assertFalse(TerrainRegionLookupUtils.isInTileGrid(FAR_PIXEL, FAR_PIXEL));
        Assertions.assertFalse(TerrainRegionLookupUtils.isInTileGrid((short) FAR_PIXEL, (short) FAR_PIXEL));
        Assertions.assertTrue(regions.sharesComponent(OFF_MAP_PIXEL, GRID_CORNER_PIXEL, GRID_CORNER_PIXEL, GRID_CORNER_PIXEL));
        Assertions.assertFalse(regions.sharesComponent(GRID_CORNER_PIXEL, GRID_CORNER_PIXEL, FAR_PIXEL, FAR_PIXEL));
        Assertions.assertFalse(regions.sharesComponent(GRID_CORNER_PIXEL, GRID_CORNER_PIXEL, (short) FAR_PIXEL, (short) FAR_PIXEL));
        Assertions.assertFalse(regions.sharesComponent(GRID_CORNER_PIXEL, GRID_PIXEL_EXTENT, GRID_CORNER_PIXEL, GRID_CORNER_PIXEL));
        Assertions.assertFalse(regions.sharesComponent(OFF_MAP_PIXEL, 0, GRID_CORNER_PIXEL, GRID_CORNER_PIXEL));
    }

    @Test
    void captured_region_fields_decode_signed_centers_and_unsigned_identifiers() {
        TerrainRegionCheck check = TerrainRegionCheck.create();
        int record = TerrainRegionCheck.NATIVE_REGIONS + CASE_REGION * TerrainRegionCheck.NATIVE_REGION_SIZE;

        check.writeWord(TerrainRegionCheck.NATIVE_REGION_COUNT, CASE_REGION_COUNT);
        check.writeWord(record + TerrainRegionCheck.NATIVE_TERRAIN_TYPE, 0x1FFD);
        check.writeWord(record + TerrainRegionCheck.NATIVE_COMPONENT, 0xFEDC);
        check.writeByte(record + TerrainRegionCheck.NATIVE_WALKABLE_NEIGHBORS, 255);
        check.writeInteger(record + TerrainRegionCheck.NATIVE_CENTER_X, 0xFF8000FF);
        check.writeInteger(record + TerrainRegionCheck.NATIVE_CENTER_Y, 0x017FFF80);

        TerrainRegions regions = check.getRegions();

        Assertions.assertEquals(4, regions.getRegionCount());
        Assertions.assertEquals(0x1FFD, regions.getTerrainType(CASE_REGION));
        Assertions.assertEquals(0xFEDC, regions.getComponent(CASE_REGION));
        Assertions.assertEquals(-32768, regions.getCenterX(CASE_REGION));
        Assertions.assertEquals(32767, regions.getCenterY(CASE_REGION));
        Assertions.assertEquals(255, regions.getWalkableNeighborCount(CASE_REGION));

        check.writeByte(record + TerrainRegionCheck.NATIVE_NEIGHBORS, CASE_NEIGHBOR_COUNT);
        check.writeWord(record + TerrainRegionCheck.NATIVE_INLINE_NEIGHBORS, 0xFFFE);
        check.writeWord(record + TerrainRegionCheck.NATIVE_INLINE_NEIGHBORS + Short.BYTES, 7);

        Assertions.assertEquals(0xFFFE, check.getRegions().getNeighborRegion(CASE_REGION, 0));
        Assertions.assertEquals(7, check.getRegions().getNeighborRegion(CASE_REGION, 1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> check.getRegions().getNeighborRegion(CASE_REGION, CASE_NEIGHBOR_COUNT));

        check.assertMatchesDomain();
    }
}
