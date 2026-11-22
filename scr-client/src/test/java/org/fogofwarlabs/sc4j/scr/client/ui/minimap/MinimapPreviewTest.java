package org.fogofwarlabs.sc4j.scr.client.ui.minimap;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.dat.DatTable;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceAssets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

/**
 * Checks the map preview size and scale.
 */
@RequiresInstallation
final class MinimapPreviewTest {
    private static final int TILESET = 0;
    /**
     * A map side without a preview image.
     */
    private static final int UNSUPPORTED_SIDE = 80;
    private static final int SMALL_SIDE = 64;
    private static final int SMALL_MAP_SCALE = 4;
    private static final int SMALL_MAP_TEXTURE_WIDTH = 256;
    private static final int SMALL_MAP_BITMAP_WIDTH = 128;
    /**
     * A tile identifier of an invalid group ({@code 0x7ff}).
     */
    private static final short INVALID_GROUP_TILE = (short) 0x7FF0;
    private static final int INVALID_GROUP_TILE_INDEX = 1;
    private static final int START_LOCATION_PLAYER = 0;
    /**
     * The start location's tile column and row.
     */
    private static final int START_LOCATION_TILE = 10;
    private static final int TILE_SIZE = 32;
    private static final int HALF_TILE = 16;

    @Test
    void build_preview_should_size_the_preview_by_the_map_side() throws IOException {
        try (CascStorage storage = CascStorage.open(ExternalInput.INSTALLATION.getPath())) {
            DatTable units = UnitsDat.createSchema().parse(storage.readFile("arr/units.dat"));
            InterfaceAssets assets = InterfaceAssets.load(ScrResourceProvider.borrow(storage), TILESET);

            Assertions.assertNull(MinimapImage.buildPreview(UNSUPPORTED_SIDE, UNSUPPORTED_SIDE,
                                                            new short[UNSUPPORTED_SIDE * UNSUPPORTED_SIDE],
                                                            List.of(),
                                                            UnitsDat.createTypeTable(units),
                                                            assets));

            short[] tiles = new short[SMALL_SIDE * SMALL_SIDE];

            tiles[INVALID_GROUP_TILE_INDEX] = INVALID_GROUP_TILE;

            // A start location (214) of player 0 at tile (10, 10).
            int startLocationPosition = START_LOCATION_TILE * TILE_SIZE + HALF_TILE;
            MinimapImage preview = MinimapImage.buildPreview(SMALL_SIDE, SMALL_SIDE,
                                                             tiles,
                                                             List.of(new MinimapPreviewUnit(startLocationPosition, startLocationPosition,
                                                                                            ScrUnitIds.SPECIAL_START_LOCATION,
                                                                                            START_LOCATION_PLAYER)),
                                                             UnitsDat.createTypeTable(units),
                                                             assets);

            Assertions.assertNotNull(preview);
            Assertions.assertEquals(SMALL_MAP_SCALE, preview.getScale());
            Assertions.assertEquals(SMALL_MAP_TEXTURE_WIDTH, preview.getTextureWidth());
            Assertions.assertEquals(SMALL_MAP_BITMAP_WIDTH, preview.getWidth());
        }
    }
}
