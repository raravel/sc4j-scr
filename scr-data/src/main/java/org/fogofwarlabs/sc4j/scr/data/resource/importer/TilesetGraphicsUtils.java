package org.fogofwarlabs.sc4j.scr.data.resource.importer;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.resource.graphics.Palette;
import org.fogofwarlabs.sc4j.engine.resource.graphics.PixelLayer;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TileGraphics;
import org.fogofwarlabs.sc4j.engine.resource.graphics.TileShape;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Imports the pictures of an SC:R tileset: the {@code .vx4ex} megatiles composed from the {@code .vr4} minitiles into
 * {@link TileGraphics} ({@value TerrainTileset#TILE_SIZE} by {@value TerrainTileset#TILE_SIZE} palette indices per
 * megatile, megatile {@code n} is tile {@code n}), and the {@code .wpe} palette.
 * <p>
 * Which megatile a map tile shows stays a lookup of the {@code .cv5} groups ({@link TerrainTileset}); the terrain logic
 * keeps reading the tileset tables from the game data.
 */
public final class TilesetGraphicsUtils {
    /**
     * The entries of a palette.
     */
    private static final int PALETTE_ENTRIES = 256;
    /**
     * The bytes of a {@code .wpe} entry: red, green, blue and an unused byte.
     */
    private static final int PALETTE_ENTRY_SIZE = 4;
    private static final int PALETTE_GREEN = 1;
    private static final int PALETTE_BLUE = 2;
    /**
     * The packed layout of an opaque {@code 0xAARRGGBB} colour.
     */
    private static final int OPAQUE_ALPHA = 0xFF000000;
    private static final int RED_SHIFT = 16;
    private static final int GREEN_SHIFT = 8;

    /**
     * The minitiles per megatile row and per megatile.
     */
    private static final int MINI_TILES_PER_ROW = TerrainTileset.TILE_SIZE / TerrainTileset.MINI_TILE_SIZE;
    private static final int MINI_TILES_PER_TILE = MINI_TILES_PER_ROW * MINI_TILES_PER_ROW;
    /**
     * The size of a VX4EX record: one DWORD minitile reference per minitile.
     */
    private static final int VX4EX_RECORD_SIZE = MINI_TILES_PER_TILE * Integer.BYTES;
    /**
     * The size of a VR4 record: the palette indices of one minitile.
     */
    private static final int VR4_RECORD_SIZE = TerrainTileset.MINI_TILE_SIZE * TerrainTileset.MINI_TILE_SIZE;
    /**
     * The VX4EX minitile reference layout: the horizontal mirror bit below the VR4 record index.
     */
    private static final int MINI_TILE_MIRRORED = 1;
    private static final int MINI_TILE_INDEX_SHIFT = 1;

    /**
     * Creates the megatile pictures of a tileset.
     *
     * @param megatiles the {@code .vx4ex} records
     * @param minitiles the {@code .vr4} records
     * @return the tile graphics, one tile per megatile
     * @throws IOException if a record size is invalid or a megatile names a minitile outside the {@code .vr4} records
     */
    public static @NotNull TileGraphics createTileGraphics(byte @NotNull [] megatiles, byte @NotNull [] minitiles) throws IOException {
        if (megatiles.length % VX4EX_RECORD_SIZE != 0 || minitiles.length % VR4_RECORD_SIZE != 0 || megatiles.length == 0) {
            throw Exceptions.newIOException("Invalid VX4EX or VR4 record size.");
        }

        int tileCount = megatiles.length / VX4EX_RECORD_SIZE;
        int tilePixels = TerrainTileset.TILE_SIZE * TerrainTileset.TILE_SIZE;
        byte[] pixels = new byte[tileCount * tilePixels];

        for (int tileIndex = 0; tileIndex < tileCount; tileIndex++) {
            int tileOffset = tileIndex * VX4EX_RECORD_SIZE;
            int destinationOffset = tileIndex * tilePixels;

            for (int miniTileIndex = 0; miniTileIndex < MINI_TILES_PER_TILE; miniTileIndex++) {
                int reference = LittleEndianUtils.readInt32(megatiles, tileOffset + miniTileIndex * Integer.BYTES);
                long miniTileOffset = Integer.toUnsignedLong(reference >>> MINI_TILE_INDEX_SHIFT) * VR4_RECORD_SIZE;

                if (miniTileOffset + VR4_RECORD_SIZE > minitiles.length) {
                    throw Exceptions.newIOException("Minitile is out of bounds: {0}", reference >>> MINI_TILE_INDEX_SHIFT);
                }

                int destinationX = miniTileIndex % MINI_TILES_PER_ROW * TerrainTileset.MINI_TILE_SIZE;
                int destinationY = miniTileIndex / MINI_TILES_PER_ROW * TerrainTileset.MINI_TILE_SIZE;

                for (int pixelY = 0; pixelY < TerrainTileset.MINI_TILE_SIZE; pixelY++) {
                    for (int pixelX = 0; pixelX < TerrainTileset.MINI_TILE_SIZE; pixelX++) {
                        int sourceX = (reference & MINI_TILE_MIRRORED) != 0 ? TerrainTileset.MINI_TILE_SIZE - 1 - pixelX : pixelX;
                        int destinationIndex = destinationOffset + (destinationY + pixelY) * TerrainTileset.TILE_SIZE + destinationX + pixelX;

                        pixels[destinationIndex] = minitiles[(int) miniTileOffset + pixelY * TerrainTileset.MINI_TILE_SIZE + sourceX];
                    }
                }
            }
        }

        return new TileGraphics(TerrainTileset.TILE_SIZE, TerrainTileset.TILE_SIZE, TileShape.SQUARE, PixelLayer.ofIndexed(pixels));
    }

    /**
     * Creates the palette of a {@code .wpe} file: {@code 256} opaque colours.
     *
     * @param contents the {@code .wpe} file
     * @return the palette
     * @throws IOException if the file does not hold {@code 256} four-byte entries
     */
    public static @NotNull Palette createPalette(byte @NotNull [] contents) throws IOException {
        if (contents.length != PALETTE_ENTRIES * PALETTE_ENTRY_SIZE) {
            throw Exceptions.newIOException("The WPE palette must have 256 four-byte entries.");
        }

        int[] colors = new int[PALETTE_ENTRIES];

        for (int index = 0; index < PALETTE_ENTRIES; index++) {
            int offset = index * PALETTE_ENTRY_SIZE;

            colors[index] = OPAQUE_ALPHA | Byte.toUnsignedInt(contents[offset]) << RED_SHIFT |
                            Byte.toUnsignedInt(contents[offset + PALETTE_GREEN]) << GREEN_SHIFT | Byte.toUnsignedInt(contents[offset + PALETTE_BLUE]);
        }

        return new Palette(colors);
    }

    private TilesetGraphicsUtils() {
    }
}
