package org.fogofwarlabs.sc4j.scr.render;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.data.resource.importer.PcxSheetUtils;
import org.fogofwarlabs.sc4j.scr.data.resource.importer.TilesetGraphicsUtils;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * Checks the terrain tile decoding, the player palette remapping, the installed image, creep edge and lighting table loading and the classic frame
 * placement of {@link RenderAssets}.
 */
final class RenderAssetsTest {
    /**
     * The tiles of one CV5 group: one per variant.
     */
    private static final int GROUP_TILES = 16;
    /**
     * The CV5 groups a tile identifier can reference.
     */
    private static final int GROUP_COUNT = 2048;
    /**
     * The last CV5 group and the last variant a tile identifier can reference.
     */
    private static final int LAST_GROUP = GROUP_COUNT - 1;
    private static final int LAST_VARIANT = GROUP_TILES - 1;
    /**
     * The palette indices of a decoded terrain tile: {@code 32x32}.
     */
    private static final int TILE_PIXELS = 1024;
    /**
     * A VX4EX mini-tile reference to VR4 record {@code 1} ({@code 1 << 1}) and the same with the horizontal mirror bit.
     */
    private static final byte MINI_TILE_ONE = 2;
    private static final byte MINI_TILE_ONE_MIRRORED = 3;
    /**
     * The third byte of a VX4EX mini-tile reference: a value there points past the two test VR4 records.
     */
    private static final int MINI_TILE_REFERENCE_THIRD_BYTE = 2;
    /**
     * The palette index that fills the VR4 records of the group reference case.
     */
    private static final int FILLED_PIXEL_INDEX = 42;
    /**
     * The {@code game/tunit.pcx} header size: the run-length data starts here.
     */
    private static final int PCX_HEADER_SIZE = 128;
    /**
     * The checked {@code game/tunit.pcx} header fields as offset and value: manufacturer, encoding, bits per pixel, maximum x, plane count and bytes
     * per line.
     */
    private static final int PCX_MANUFACTURER_OFFSET = 0;
    private static final byte PCX_MANUFACTURER = 10;
    private static final int PCX_ENCODING_OFFSET = 2;
    private static final byte PCX_RUN_LENGTH_ENCODING = 1;
    private static final int PCX_BITS_PER_PIXEL_OFFSET = 3;
    private static final byte PCX_BITS_PER_PIXEL = 8;
    private static final int PCX_MAXIMUM_X_OFFSET = 8;
    private static final byte PCX_MAXIMUM_X = 127;
    private static final int PCX_PLANE_COUNT_OFFSET = 65;
    private static final byte PCX_PLANE_COUNT = 1;
    private static final int PCX_BYTES_PER_LINE_OFFSET = 66;
    private static final byte PCX_BYTES_PER_LINE = (byte) 128;
    /**
     * A PCX run byte of length {@code 0}.
     */
    private static final byte PCX_EMPTY_RUN = (byte) 192;
    /**
     * The palette index that every player remaps indices {@code 8..15} to.
     */
    private static final int REMAPPED_INDEX = 42;
    /**
     * The red bytes of the first remapped palette entry and of the remapped index entry.
     */
    private static final int REMAP_FIRST_RED = 17;
    private static final int REMAPPED_RED = 91;
    /**
     * The fourth byte of every created palette entry.
     */
    private static final int OPAQUE_ALPHA = 255;

    /**
     * The Badlands tileset; images: the Zergling ({@code zerg\zergling.grp}) and the Nydus Canal shadow ({@code zerg\znyShad.grp}).
     */
    private static final int BADLANDS_TILESET = 0;
    private static final int ZERGLING = 54;
    private static final int NYDUS_CANAL_SHADOW = 87;
    /**
     * The Nydus Canal shadow frame {@code 0}: half of its {@code 128x128} canvas and its frame rectangle's left edge, top edge and width.
     */
    private static final int NYDUS_SHADOW_HALF_CANVAS = 64;
    private static final int NYDUS_SHADOW_FRAME_LEFT = 27;
    private static final int NYDUS_SHADOW_FRAME_TOP = 36;
    private static final int NYDUS_SHADOW_FRAME_WIDTH = 53;
    /**
     * The shift of the vertical offset in a packed frame map offset.
     */
    private static final int MAP_OFFSET_VERTICAL_SHIFT = 16;
    /**
     * The Jungle tileset, the frames of its creep GRP, the columns of a lighting table, the lighting table of {@code bfire} and its rows.
     */
    private static final int JUNGLE_TILESET = 4;
    private static final int CREEP_EDGE_FRAME_COUNT = 37;
    private static final int LIGHTING_TABLE_COLUMNS = 256;
    private static final int BFIRE_TABLE = 2;
    private static final int BFIRE_ROWS = 40;
    /**
     * The Marine shadow image and the checked frames of the Marine and its shadow.
     */
    private static final int MARINE_SHADOW = 240;
    private static final int @NotNull [] SHADOW_FRAMES = {0, 68, 85, 93, 204, 212};

    @Test
    void decode_tile_from_records_should_decode_extended_references_and_horizontal_mirroring() throws IOException {
        int variant = 5;
        byte[] groups = new byte[TerrainTileset.CV5_RECORD_SIZE];
        byte[] tiles = new byte[2 * RenderAssets.VX4EX_RECORD_SIZE];
        byte[] pixels = new byte[2 * RenderAssets.VR4_RECORD_SIZE];

        groups[TerrainTileset.CV5_MEGATILES_OFFSET + variant * Short.BYTES] = 1;

        for (int miniTileIndex = 0; miniTileIndex < RenderAssets.MINI_TILES_PER_TILE; miniTileIndex++) {
            tiles[RenderAssets.VX4EX_RECORD_SIZE + miniTileIndex * Integer.BYTES] = miniTileIndex % 2 == 0 ? MINI_TILE_ONE : MINI_TILE_ONE_MIRRORED;
        }

        for (int pixelIndex = 0; pixelIndex < RenderAssets.VR4_RECORD_SIZE; pixelIndex++) {
            pixels[RenderAssets.VR4_RECORD_SIZE + pixelIndex] = (byte) (pixelIndex + 1);
        }

        byte[] decoded = RenderAssets.decodeTile(groups, TilesetGraphicsUtils.createTileGraphics(tiles, pixels), variant);

        Assertions.assertEquals(1, decoded[0]);
        Assertions.assertEquals(8, decoded[7]);
        Assertions.assertEquals(8, decoded[8]);
        Assertions.assertEquals(1, decoded[15]);
        Assertions.assertEquals(9, decoded[TerrainTileset.TILE_SIZE]);
        Assertions.assertEquals(64, decoded[7 * TerrainTileset.TILE_SIZE + 7]);
        Assertions.assertEquals(1, decoded[8 * TerrainTileset.TILE_SIZE]);
        Assertions.assertEquals(57, decoded[31 * TerrainTileset.TILE_SIZE + 31]);

        // A reference byte above the mirror bit points past the VR4 records.
        tiles[RenderAssets.VX4EX_RECORD_SIZE + MINI_TILE_REFERENCE_THIRD_BYTE] = MINI_TILE_ONE;
        Assertions.assertThrows(IOException.class,
                                () -> RenderAssets.decodeTile(groups, TilesetGraphicsUtils.createTileGraphics(tiles, pixels), variant));
    }

    @Test
    void decode_tile_from_records_should_exclude_the_high_tile_flag_from_the_group_reference() throws IOException {
        byte[] groups = new byte[GROUP_COUNT * TerrainTileset.CV5_RECORD_SIZE];
        byte[] tiles = new byte[2 * RenderAssets.VX4EX_RECORD_SIZE];
        byte[] pixels = new byte[2 * RenderAssets.VR4_RECORD_SIZE];

        groups[LAST_GROUP * TerrainTileset.CV5_RECORD_SIZE + TerrainTileset.CV5_MEGATILES_OFFSET + LAST_VARIANT * Short.BYTES] = 1;

        for (int miniTileIndex = 0; miniTileIndex < RenderAssets.MINI_TILES_PER_TILE; miniTileIndex++) {
            tiles[RenderAssets.VX4EX_RECORD_SIZE + miniTileIndex * Integer.BYTES] = MINI_TILE_ONE;
        }

        Arrays.fill(pixels, RenderAssets.VR4_RECORD_SIZE, pixels.length, (byte) FILLED_PIXEL_INDEX);

        byte[] decoded = RenderAssets.decodeTile(groups, TilesetGraphicsUtils.createTileGraphics(tiles, pixels), 0x7FFF);

        Assertions.assertEquals(FILLED_PIXEL_INDEX, decoded[0]);
        Assertions.assertArrayEquals(decoded, RenderAssets.decodeTile(groups, TilesetGraphicsUtils.createTileGraphics(tiles, pixels), 0xFFFF));
        Assertions.assertEquals(0, RenderAssets.decodeTile(groups, TilesetGraphicsUtils.createTileGraphics(tiles, pixels), 0xFFFE)[0]);
        Assertions.assertThrows(IOException.class,
                                () -> RenderAssets.decodeTile(new byte[TerrainTileset.CV5_RECORD_SIZE],
                                                              TilesetGraphicsUtils.createTileGraphics(tiles, pixels), 0x8010));
    }

    @Test
    void find_megatile_index_should_read_the_group_reference_without_the_high_tile_flag() throws IOException {
        byte[] groups = new byte[GROUP_COUNT * TerrainTileset.CV5_RECORD_SIZE];

        groups[LAST_GROUP * TerrainTileset.CV5_RECORD_SIZE + TerrainTileset.CV5_MEGATILES_OFFSET + LAST_VARIANT * Short.BYTES] = 3;

        Assertions.assertEquals(3, RenderAssets.findMegatileIndex(groups, 0x7FFF));
        Assertions.assertEquals(3, RenderAssets.findMegatileIndex(groups, 0xFFFF));
        Assertions.assertEquals(0, RenderAssets.findMegatileIndex(groups, 0));
        Assertions.assertThrows(IOException.class, () -> RenderAssets.findMegatileIndex(new byte[TerrainTileset.CV5_RECORD_SIZE], 0x8010));
    }

    @Test
    void decode_tile_from_records_should_reject_references_outside_the_graphics_files() {
        Assertions.assertThrows(IOException.class,
                                () -> RenderAssets.decodeTile(new byte[TerrainTileset.CV5_RECORD_SIZE],
                                                              TilesetGraphicsUtils.createTileGraphics(new byte[RenderAssets.VX4EX_RECORD_SIZE],
                                                                                                      new byte[RenderAssets.VR4_RECORD_SIZE]),
                                                              GROUP_TILES));
        Assertions.assertThrows(IOException.class,
                                () -> RenderAssets.decodeTile(new byte[TerrainTileset.CV5_RECORD_SIZE],
                                                              TilesetGraphicsUtils.createTileGraphics(new byte[RenderAssets.VX4EX_RECORD_SIZE],
                                                                                                      new byte[RenderAssets.VR4_RECORD_SIZE]),
                                                              Integer.MAX_VALUE));

        byte[] groups = new byte[TerrainTileset.CV5_RECORD_SIZE];

        groups[TerrainTileset.CV5_MEGATILES_OFFSET] = 1;
        Assertions.assertThrows(IOException.class,
                                () -> RenderAssets.decodeTile(groups,
                                                              TilesetGraphicsUtils.createTileGraphics(new byte[RenderAssets.VX4EX_RECORD_SIZE],
                                                                                                      new byte[RenderAssets.VR4_RECORD_SIZE]),
                                                              0));
    }

    @Test
    void create_palette_should_remap_players_without_treating_wpe_reserved_bytes_as_alpha() throws IOException {
        byte[] palette = new byte[RenderAssets.PALETTE_BYTES];

        palette[RenderAssets.REMAP_FIRST_INDEX * RenderAssets.PALETTE_ENTRY_SIZE] = REMAP_FIRST_RED;
        palette[REMAPPED_INDEX * RenderAssets.PALETTE_ENTRY_SIZE]                 = REMAPPED_RED;

        // A PCX header (manufacturer, encoding, bits per pixel, maximum x, planes, bytes per line) and a 128 by 1 image.
        byte[] playerColors = new byte[PCX_HEADER_SIZE + RenderAssets.REMAP_COLORS];

        playerColors[PCX_MANUFACTURER_OFFSET]   = PCX_MANUFACTURER;
        playerColors[PCX_ENCODING_OFFSET]       = PCX_RUN_LENGTH_ENCODING;
        playerColors[PCX_BITS_PER_PIXEL_OFFSET] = PCX_BITS_PER_PIXEL;
        playerColors[PCX_MAXIMUM_X_OFFSET]      = PCX_MAXIMUM_X;
        playerColors[PCX_PLANE_COUNT_OFFSET]    = PCX_PLANE_COUNT;
        playerColors[PCX_BYTES_PER_LINE_OFFSET] = PCX_BYTES_PER_LINE;
        Arrays.fill(playerColors, PCX_HEADER_SIZE, playerColors.length, (byte) REMAPPED_INDEX);

        byte[] decoded = RenderAssets.createPalette(TilesetGraphicsUtils.createPalette(palette), PcxSheetUtils.createSheet(playerColors));

        Assertions.assertEquals(REMAP_FIRST_RED, decoded[RenderAssets.REMAP_FIRST_INDEX * RenderAssets.PALETTE_ENTRY_SIZE]);
        Assertions.assertEquals(REMAPPED_RED, decoded[RenderAssets.PALETTE_BYTES + RenderAssets.REMAP_FIRST_INDEX * RenderAssets.PALETTE_ENTRY_SIZE]);
        Assertions.assertEquals(REMAPPED_RED, decoded[RenderAssets.REMAP_ROWS * RenderAssets.PALETTE_BYTES + 15 * RenderAssets.PALETTE_ENTRY_SIZE]);
        Assertions.assertEquals(OPAQUE_ALPHA, Byte.toUnsignedInt(decoded[RenderAssets.PALETTE_FOURTH]));
        Assertions.assertEquals(OPAQUE_ALPHA, Byte.toUnsignedInt(decoded[RenderAssets.PALETTE_BYTES + RenderAssets.PALETTE_FOURTH]));

        // A run byte at the end of the row has no value byte.
        playerColors[PCX_HEADER_SIZE] = PCX_EMPTY_RUN;
        Assertions.assertThrows(IOException.class,
                                () -> RenderAssets.createPalette(TilesetGraphicsUtils.createPalette(palette),
                                                                 PcxSheetUtils.createSheet(playerColors)));
    }

    @RequiresInstallation
    @Test
    void render_assets_load_all_installed_tilesets_and_base_unit_images() throws IOException {
        String[] names = {"badlands", "platform", "install", "ashworld", "jungle", "desert", "ice", "twilight"};
        int marine = ScrUnitIds.TERRAN_MARINE;
        int[] sampleUnits = {
                marine, ScrUnitIds.TERRAN_SCV, ScrUnitIds.ZERG_ZERGLING, ScrUnitIds.ZERG_DRONE, ScrUnitIds.ZERG_OVERLORD,
                ScrUnitIds.PROTOSS_PROBE,
                ScrUnitIds.TERRAN_COMMAND_CENTER, ScrUnitIds.ZERG_HATCHERY, ScrUnitIds.PROTOSS_NEXUS,
                ScrUnitIds.RESOURCE_MINERAL_FIELD, ScrUnitIds.RESOURCE_MINERAL_FIELD_TYPE_2,
                ScrUnitIds.RESOURCE_MINERAL_FIELD_TYPE_3,
                ScrUnitIds.RESOURCE_VESPENE_GEYSER
        };

        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            for (int tilesetIndex = 0; tilesetIndex < names.length; tilesetIndex++) {
                RenderAssets assets = RenderAssets.load(ScrResourceProvider.borrow(storage), tilesetIndex);
                int groupCount = storage.readFile("tileset/" + names[tilesetIndex] + ".cv5").length / TerrainTileset.CV5_RECORD_SIZE;

                for (int tileIdentifier = 0; tileIdentifier < groupCount * GROUP_TILES; tileIdentifier++) {
                    Assertions.assertEquals(TILE_PIXELS, assets.decodeTile(tileIdentifier).length);
                }

                if (tilesetIndex == 0) {
                    byte[] marineContents = storage.readFile("unit/terran/marine.grp");
                    int marineFrameCount = LittleEndianUtils.readUnsigned16(marineContents, 0);

                    Assertions.assertEquals(marineFrameCount, assets.getImageFrameCount(assets.getUnitImage(marine)));

                    for (int unitType = 0; unitType < UnitsDat.UNIT_COUNT; unitType++) {
                        int imageIndex = assets.getUnitImage(unitType);
                        int frameCount = assets.getImageFrameCount(imageIndex);

                        Assertions.assertTrue(frameCount > 0, "No frames for unit " + unitType);

                        ClassicFrame frame = assets.decodeImage(imageIndex, 0);

                        Assertions.assertEquals(frame.getWidth() * frame.getHeight(), frame.getPixels().length);

                        ClassicFrame finalFrame = assets.decodeImage(imageIndex, frameCount - 1);

                        Assertions.assertEquals(frame.getWidth(), finalFrame.getWidth());
                        Assertions.assertEquals(frame.getHeight(), finalFrame.getHeight());
                        Assertions.assertThrows(IOException.class, () -> assets.decodeImage(imageIndex, frameCount));
                    }

                    Assertions.assertThrows(IOException.class, () -> assets.getImageFrameCount(-1));
                    Assertions.assertThrows(IOException.class, () -> assets.getImageFrameCount(Integer.MAX_VALUE));
                    Assertions.assertThrows(IOException.class, () -> assets.decodeImage(assets.getUnitImage(marine), -1));
                }

                for (int unitType : sampleUnits) {
                    ClassicFrame frame = assets.decodeImage(assets.getUnitImage(unitType), 0);

                    Assertions.assertTrue(frame.getWidth() > 0);
                    Assertions.assertTrue(frame.getHeight() > 0);
                    Assertions.assertTrue(IntStream.range(0, frame.getPixels().length).anyMatch(index -> frame.getPixels()[index] != 0));
                }
            }
        }
    }

    @RequiresInstallation
    @Test
    void is_previous_frame_repeated_should_find_the_repeated_classic_direction_frames() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            RenderAssets assets = RenderAssets.load(ScrResourceProvider.borrow(storage), BADLANDS_TILESET);

            // The Zergling GRP has one frame for every two directions: odd frames of a 17-frame set repeat the one before.
            Assertions.assertTrue(!assets.isPreviousFrameRepeated(ZERGLING, 0));
            Assertions.assertTrue(assets.isPreviousFrameRepeated(ZERGLING, 1));
            Assertions.assertTrue(!assets.isPreviousFrameRepeated(ZERGLING, 2));
            Assertions.assertTrue(assets.isPreviousFrameRepeated(ZERGLING, 15));
            Assertions.assertTrue(!assets.isPreviousFrameRepeated(ZERGLING, 16));
        }
    }

    @RequiresInstallation
    @Test
    void nydus_canal_shadow_is_placed_at_its_classic_map_position() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            RenderAssets assets = RenderAssets.load(ScrResourceProvider.borrow(storage), BADLANDS_TILESET);

            // 128x128 canvas, frame 0 at (27, 36), 53x56.
            int offset = assets.getFrameMapOffset(NYDUS_CANAL_SHADOW, 0, false);
            int flipped = assets.getFrameMapOffset(NYDUS_CANAL_SHADOW, 0, true);

            Assertions.assertEquals(NYDUS_SHADOW_FRAME_LEFT - NYDUS_SHADOW_HALF_CANVAS, (short) offset);
            Assertions.assertEquals(NYDUS_SHADOW_FRAME_TOP - NYDUS_SHADOW_HALF_CANVAS, (short) (offset >>> MAP_OFFSET_VERTICAL_SHIFT));
            Assertions.assertEquals(NYDUS_SHADOW_HALF_CANVAS - (NYDUS_SHADOW_FRAME_LEFT + NYDUS_SHADOW_FRAME_WIDTH), (short) flipped);
            Assertions.assertEquals(NYDUS_SHADOW_FRAME_TOP - NYDUS_SHADOW_HALF_CANVAS, (short) (flipped >>> MAP_OFFSET_VERTICAL_SHIFT));
        }
    }

    @RequiresInstallation
    @Test
    void render_assets_load_creep_edges_lighting_tables_and_even_sized_marine_frames() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            RenderAssets assets = RenderAssets.load(ScrResourceProvider.borrow(storage), JUNGLE_TILESET);
            byte[] palette = assets.copyPalette();

            palette[0] ^= 1;
            Assertions.assertFalse(Arrays.equals(palette, assets.copyPalette()), "the palette is a copy");
            Assertions.assertEquals(CREEP_EDGE_FRAME_COUNT, assets.getCreepEdgeFrameCount());

            for (int tableIndex = 0; tableIndex < RenderAssets.LIGHTING_TABLE_NAMES.length; tableIndex++) {
                Assertions.assertEquals(0, assets.getLightingTable(tableIndex).length % LIGHTING_TABLE_COLUMNS);
            }

            Assertions.assertEquals(BFIRE_ROWS * LIGHTING_TABLE_COLUMNS, assets.getLightingTable(BFIRE_TABLE).length, "bfire has 40 rows.");

            int marineImage = assets.getUnitImage(ScrUnitIds.TERRAN_MARINE);

            for (int frameIndex : SHADOW_FRAMES) {
                ClassicFrame shadow = assets.decodeImage(MARINE_SHADOW, frameIndex);
                ClassicFrame body = assets.decodeImage(marineImage, frameIndex);

                Assertions.assertEquals(0, shadow.getWidth() & 1);
                Assertions.assertEquals(0, shadow.getHeight() & 1);
                Assertions.assertEquals(0, body.getWidth() & 1);
                Assertions.assertEquals(0, body.getHeight() & 1);
            }
        }
    }
}
