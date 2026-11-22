package org.fogofwarlabs.sc4j.scr.data.format.terrain;

import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Tests original terrain thresholds, bit overlap and installed classic assets.
 */
final class TerrainTilesetTest {
    private static final int TILE_VARIANTS_PER_GROUP = 16;
    private static final int MINIATURES_PER_MEGATILE = TerrainTileset.MINI_TILES_PER_ROW * TerrainTileset.MINI_TILES_PER_ROW;
    private static final int RAW_MEGATILE_FLAG = 0x8000;
    private static final int RAW_MEGATILE_COUNT = 0x8003;
    private static final int FIRST_RAW_VARIANT = 13;
    private static final int LAST_RAW_VARIANT = 15;
    private static final int WALKABLE_COUNT_THRESHOLD = 12;
    private static final int HIGH_COUNT_THRESHOLD = 12;
    private static final int MIDDLE_COUNT_THRESHOLD = 6;
    private static final int INSTALLED_TILESET_COUNT = 8;
    private static final int MAXIMUM_TILE_GROUPS = 2048;
    private static final int FIRST_UNREPRESENTED_MEGATILE_COUNT = 65536;
    private static final int MINIMUM_TERRAIN_GROUPS = 3;
    private static final int TERRAIN_GROUP_RECORD_BYTES = 52;
    private static final int TERRAIN_GROUP_TILES_OFFSET = 20;
    private static final int TERRAIN_GROUP_FLAGS_OFFSET = Short.BYTES;
    private static final int LAST_MINIATURE_INDEX = TerrainTileset.MINI_TILES_PER_ROW * TerrainTileset.MINI_TILES_PER_ROW - 1;
    private static final int MEGATILE_RECORD_BYTES = (LAST_MINIATURE_INDEX + 1) * Short.BYTES;
    private static final int DIRECT_MEGATILE_COUNT = 2;
    private static final int DIRECT_FLAG_WORD = 0x8001;
    private static final int WORD_MASK = 0xFFFF;
    private static final int WALKABLE_BIT = 1;
    private static final int HEIGHT_BITS = 6;
    private static final int BLOCKS_VIEW_BIT = 8;

    private static final int WALKABLE_TILE_FLAG = 0x10000;
    private static final int UNWALKABLE_TILE_FLAG = 0x40000;
    private static final int CREEP_TILE_FLAG = 0x400000;
    private static final int MIDDLE_GROUND_TILE_FLAG = 0x02000000;
    private static final int HIGH_GROUND_TILE_FLAG = 0x04000000;
    private static final int PARTIALLY_WALKABLE_TILE_FLAG = 0x20000000;

    @Test
    void get_terrain_height_should_match_reference_digest_for_raw_megatiles_and_negative_positions() throws IOException, NoSuchAlgorithmException {
        ByteBuffer groups = ByteBuffer.allocate(MINIMUM_TERRAIN_GROUPS * TERRAIN_GROUP_RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer miniatureFlags = ByteBuffer.allocate(RAW_MEGATILE_COUNT * MEGATILE_RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);

        for (int groupIndex = 0; groupIndex < MINIMUM_TERRAIN_GROUPS; groupIndex++) {
            for (int variant = 0; variant < TILE_VARIANTS_PER_GROUP; variant++) {
                int megatileIndex = groupIndex == 2 && variant >= FIRST_RAW_VARIANT
                                    ? RAW_MEGATILE_FLAG + variant - FIRST_RAW_VARIANT
                                    : groupIndex * TILE_VARIANTS_PER_GROUP + variant;

                groups.putShort(groupIndex * TERRAIN_GROUP_RECORD_BYTES + TERRAIN_GROUP_TILES_OFFSET + variant * Short.BYTES, (short) megatileIndex);
            }
        }

        for (int megatileIndex = 0; megatileIndex < RAW_MEGATILE_COUNT; megatileIndex++) {
            for (int miniatureIndex = 0; miniatureIndex < MINIATURES_PER_MEGATILE; miniatureIndex++) {
                int flags = megatileIndex * 97 + miniatureIndex * 769 + 0xFACE;

                if (megatileIndex >= RAW_MEGATILE_FLAG) {
                    flags ^= HEIGHT_BITS;
                }

                miniatureFlags.putShort(megatileIndex * MEGATILE_RECORD_BYTES + miniatureIndex * Short.BYTES, (short) flags);
            }
        }

        TerrainTileset terrain = TerrainTileset.parse(groups.array(), miniatureFlags.array());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        for (int positionY = -31; positionY < 256; positionY++) {
            for (int positionX = -31; positionX < 256; positionX++) {
                int tileIndex = positionY / TerrainTileset.TILE_SIZE * 8 + positionX / TerrainTileset.TILE_SIZE;
                int base = (tileIndex * 29 + 7) % 48 | ((tileIndex & 1) != 0 ? 0x8000 : 0);
                int overlay = tileIndex % 3 == 0 ? 0 : (tileIndex * 13 + 11) % 48;

                digest.update((byte) terrain.getTerrainHeight(overlay != 0 ? overlay : base, positionX, positionY));
            }
        }

        Assertions.assertEquals("6d92de993c0bc871b34511c1d0c5a5c8039bc903f3e4483396a0fb163270e14c", HexFormat.of().formatHex(digest.digest()));
        Assertions.assertThrows(IllegalArgumentException.class, () -> terrain.getTerrainHeight(0x30, 0, 0));
    }

    @Test
    void get_miniature_flags_should_keep_individual_words_after_masked_megatile_lookup() throws IOException {
        ByteBuffer groups = ByteBuffer.allocate(MINIMUM_TERRAIN_GROUPS * TERRAIN_GROUP_RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);

        groups.putShort(TERRAIN_GROUP_RECORD_BYTES + TERRAIN_GROUP_TILES_OFFSET + 3 * Short.BYTES, (short) 0x8001);

        ByteBuffer miniatureFlags = ByteBuffer.allocate(DIRECT_MEGATILE_COUNT * MEGATILE_RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);

        for (int miniatureIndex = 0; miniatureIndex < MINIATURES_PER_MEGATILE; miniatureIndex++) {
            miniatureFlags.putShort(MEGATILE_RECORD_BYTES + miniatureIndex * Short.BYTES, (short) (0xFFF0 | miniatureIndex));
        }

        TerrainTileset terrain = TerrainTileset.parse(groups.array(), miniatureFlags.array());

        miniatureFlags.putShort(MEGATILE_RECORD_BYTES, (short) 0);

        for (int miniatureIndex = 0; miniatureIndex < MINIATURES_PER_MEGATILE; miniatureIndex++) {
            Assertions.assertEquals(0xFFF0 | miniatureIndex, terrain.getMiniatureFlags(0x8013, miniatureIndex));
        }

        Assertions.assertThrows(IllegalArgumentException.class, () -> terrain.getMiniatureFlags(0x8013, MINIATURES_PER_MEGATILE));
    }

    @Test
    void expand_tile_flags_should_keep_walkable_threshold_and_partial_tile_flag() throws IOException {
        for (int bitOneCount = 0; bitOneCount <= MINIATURES_PER_MEGATILE; bitOneCount++) {
            ByteBuffer miniatureFlags = ByteBuffer.allocate(MEGATILE_RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);

            for (int miniatureIndex = 0; miniatureIndex < MINIATURES_PER_MEGATILE; miniatureIndex++) {
                miniatureFlags.putShort((short) (0xFFF0 | (miniatureIndex < bitOneCount ? WALKABLE_BIT : 0)));
            }

            TerrainTileset terrain = TerrainTileset.parse(new byte[MINIMUM_TERRAIN_GROUPS * TERRAIN_GROUP_RECORD_BYTES], miniatureFlags.array());
            int expected = bitOneCount > WALKABLE_COUNT_THRESHOLD ? WALKABLE_TILE_FLAG : UNWALKABLE_TILE_FLAG;

            if (bitOneCount > 0 && bitOneCount < MINIATURES_PER_MEGATILE) {
                expected |= PARTIALLY_WALKABLE_TILE_FLAG;
            }

            Assertions.assertEquals(expected, terrain.expandTileFlags(new int[]{0})[0], "bit-one count " + bitOneCount);
        }
    }

    @Test
    void expand_tile_flags_should_count_overlapping_height_bits_separately_and_prefer_bit_four() throws IOException {
        for (int populatedCount = 0; populatedCount <= MINIATURES_PER_MEGATILE; populatedCount++) {
            ByteBuffer miniatureFlags = ByteBuffer.allocate(MEGATILE_RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);

            for (int miniatureIndex = 0; miniatureIndex < MINIATURES_PER_MEGATILE; miniatureIndex++) {
                miniatureFlags.putShort((short) (miniatureIndex < populatedCount ? HEIGHT_BITS : 0));
            }

            int expected = UNWALKABLE_TILE_FLAG;

            if (populatedCount >= HIGH_COUNT_THRESHOLD) {
                expected |= HIGH_GROUND_TILE_FLAG;
            } else if (populatedCount >= MIDDLE_COUNT_THRESHOLD) {
                expected |= MIDDLE_GROUND_TILE_FLAG;
            }

            Assertions.assertEquals(expected,
                                    TerrainTileset.parse(new byte[MINIMUM_TERRAIN_GROUPS * TERRAIN_GROUP_RECORD_BYTES],
                                                         miniatureFlags.array()).expandTileFlags(new int[]{0})[0]);
        }
    }

    @Test
    void expand_tile_flags_should_combine_group_mask_variant_and_sign_bit_without_low_word_state() throws IOException {
        ByteBuffer groups = ByteBuffer.allocate(MINIMUM_TERRAIN_GROUPS * TERRAIN_GROUP_RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);

        groups.putShort(TERRAIN_GROUP_FLAGS_OFFSET, (short) 0xFFFF);
        groups.putShort(TERRAIN_GROUP_TILES_OFFSET + 7 * Short.BYTES, (short) 0x8001);

        byte[] miniatureFlags = new byte[DIRECT_MEGATILE_COUNT * MEGATILE_RECORD_BYTES];

        miniatureFlags[MEGATILE_RECORD_BYTES + LAST_MINIATURE_INDEX * Short.BYTES] = BLOCKS_VIEW_BIT;

        TerrainTileset terrain = TerrainTileset.parse(groups.array(), miniatureFlags);

        Assertions.assertEquals(MINIMUM_TERRAIN_GROUPS, terrain.getGroupCount());
        Assertions.assertEquals(0xD9FE0000, terrain.expandTileFlags(new int[]{0x8007})[0]);
        Assertions.assertEquals(0xD8FE0000, terrain.expandTileFlags(new int[]{0x8000})[0]);

        groups.putShort(TERRAIN_GROUP_FLAGS_OFFSET, (short) 0);

        Assertions.assertEquals(0xD9FE0000, terrain.expandTileFlags(new int[]{0x8007})[0]);
        Assertions.assertThrows(IllegalArgumentException.class, () -> terrain.expandTileFlags(new int[]{0x30}));
    }

    @Test
    void tileset_rejects_unrepresented_asset_extents_and_megatile_reads() throws IOException {
        Assertions.assertThrows(IOException.class,
                                () -> TerrainTileset.parse(new byte[(MINIMUM_TERRAIN_GROUPS - 1) * TERRAIN_GROUP_RECORD_BYTES],
                                                           new byte[MEGATILE_RECORD_BYTES]));
        Assertions.assertThrows(IOException.class,
                                () -> TerrainTileset.parse(new byte[MINIMUM_TERRAIN_GROUPS * TERRAIN_GROUP_RECORD_BYTES],
                                                           new byte[MEGATILE_RECORD_BYTES - 1]));
        Assertions.assertThrows(IOException.class,
                                () -> TerrainTileset.parse(new byte[MINIMUM_TERRAIN_GROUPS * TERRAIN_GROUP_RECORD_BYTES],
                                                           new byte[FIRST_UNREPRESENTED_MEGATILE_COUNT * MEGATILE_RECORD_BYTES]));

        byte[] groups = new byte[MINIMUM_TERRAIN_GROUPS * TERRAIN_GROUP_RECORD_BYTES];

        groups[TERRAIN_GROUP_TILES_OFFSET] = 1;

        TerrainTileset terrain = TerrainTileset.parse(groups, new byte[MEGATILE_RECORD_BYTES]);

        Assertions.assertThrows(IllegalArgumentException.class, () -> terrain.expandTileFlags(new int[]{0}));
    }

    @RequiresInstallation
    @Test
    void load_should_read_every_installed_tileset() throws IOException {
        Path installationDirectory = ExternalInput.INSTALLATION.getPath();

        Assertions.assertNotNull(installationDirectory);

        try (CascStorage storage = CascStorage.open(installationDirectory)) {
            for (int tilesetIndex = 0; tilesetIndex < INSTALLED_TILESET_COUNT; tilesetIndex++) {
                TerrainTileset terrain = TerrainTileset.load(storage, tilesetIndex);

                Assertions.assertTrue(terrain.getGroupCount() >= MINIMUM_TERRAIN_GROUPS);

                int tileIdentifier = (Math.min(terrain.getGroupCount(), MAXIMUM_TILE_GROUPS) - 1) * TILE_VARIANTS_PER_GROUP + LAST_RAW_VARIANT;
                int[] flags = terrain.expandTileFlags(new int[]{0x20, 0x8020, tileIdentifier});

                Assertions.assertEquals(flags[0] | CREEP_TILE_FLAG, flags[1]);
                Assertions.assertEquals(0, (flags[0] | flags[1] | flags[2]) & WORD_MASK);
            }
        }
    }

    @Test
    void get_megatile_miniature_flags_should_read_the_selected_unsigned_word_without_cv5_lookup() throws IOException {
        ByteBuffer groups = ByteBuffer.allocate(MINIMUM_TERRAIN_GROUPS * TERRAIN_GROUP_RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer miniatureFlags = ByteBuffer.allocate(DIRECT_MEGATILE_COUNT * MEGATILE_RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);

        groups.putShort(TERRAIN_GROUP_TILES_OFFSET, (short) 1);
        miniatureFlags.putShort(MEGATILE_RECORD_BYTES, (short) DIRECT_FLAG_WORD);
        miniatureFlags.putShort(MEGATILE_RECORD_BYTES + LAST_MINIATURE_INDEX * Short.BYTES, (short) WORD_MASK);

        TerrainTileset terrain = TerrainTileset.parse(groups.array(), miniatureFlags.array());

        Assertions.assertEquals(DIRECT_FLAG_WORD, terrain.getMegatileMiniatureFlags(1, 0));
        Assertions.assertEquals(DIRECT_FLAG_WORD, terrain.getMiniatureFlags(0, 0));
        Assertions.assertEquals(WORD_MASK, terrain.getMegatileMiniatureFlags(1, LAST_MINIATURE_INDEX));
        Assertions.assertThrows(IllegalArgumentException.class, () -> terrain.getMegatileMiniatureFlags(-1, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> terrain.getMegatileMiniatureFlags(DIRECT_MEGATILE_COUNT, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> terrain.getMegatileMiniatureFlags(1, -1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> terrain.getMegatileMiniatureFlags(1, LAST_MINIATURE_INDEX + 1));
    }

    @Test
    void has_tile_should_need_an_installed_group_and_megatile() throws IOException {
        ByteBuffer groups = ByteBuffer.allocate(MINIMUM_TERRAIN_GROUPS * TERRAIN_GROUP_RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);

        groups.putShort(TERRAIN_GROUP_TILES_OFFSET + Short.BYTES, (short) DIRECT_MEGATILE_COUNT);
        groups.putShort(TERRAIN_GROUP_RECORD_BYTES + TERRAIN_GROUP_TILES_OFFSET, (short) (RAW_MEGATILE_FLAG | 1));

        TerrainTileset terrain = TerrainTileset.parse(groups.array(), new byte[DIRECT_MEGATILE_COUNT * MEGATILE_RECORD_BYTES]);
        int lastTile = MINIMUM_TERRAIN_GROUPS * TILE_VARIANTS_PER_GROUP - 1;

        Assertions.assertTrue(terrain.hasTile(0));
        Assertions.assertTrue(terrain.hasTile(TILE_VARIANTS_PER_GROUP));
        Assertions.assertTrue(terrain.hasTile(lastTile));
        Assertions.assertTrue(terrain.hasTile(RAW_MEGATILE_FLAG | lastTile));
        Assertions.assertFalse(terrain.hasTile(1));
        Assertions.assertFalse(terrain.hasTile(lastTile + 1));
        Assertions.assertFalse(terrain.hasTile(-1));
        Assertions.assertFalse(terrain.hasTile(WORD_MASK + 1));
    }
}
