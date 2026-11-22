package org.fogofwarlabs.sc4j.scr.data.format.terrain;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Decodes classic CV5/VF4 terrain flags used during map initialization.
 * <p>
 * This describes terrain expansion, not subsequent occupancy, creep or visibility updates. A tile WORD holds the CV5 group in
 * bits {@code 4..14}, the tile of the group in bits {@code 0..3} and the creep marker in bit {@code 15}. A VF4 record holds the
 * sixteen mini-tile flag WORDs of a megatile in row-major order: bit {@code 0} walkable, bit {@code 1} middle ground, bit
 * {@code 2} high ground, bit {@code 3} blocks view.
 */
public final class TerrainTileset {
    /**
     * The width and height of a map tile (megatile) in pixels.
     */
    public static final int TILE_SIZE = 32;
    /**
     * The shift from a pixel coordinate to a tile coordinate and back ({@code log2(TILE_SIZE)}).
     */
    public static final int TILE_SHIFT = 5;
    /**
     * The width and height of a mini-tile in pixels; a tile has four by four mini-tiles.
     */
    public static final int MINI_TILE_SIZE = 8;
    /**
     * The number of mini-tiles in a row (and a column) of a tile.
     */
    public static final int MINI_TILES_PER_ROW = 4;
    /**
     * The mask of a mini-tile column (or row) within its tile.
     */
    public static final int MINI_TILE_COLUMN_MASK = MINI_TILES_PER_ROW - 1;
    /**
     * The walkable bit of a mini-tile flag WORD.
     */
    public static final int MINI_TILE_WALKABLE = 1;
    /**
     * The middle-ground bit of a mini-tile flag WORD.
     */
    public static final int MINI_TILE_MIDDLE = 2;
    /**
     * The high-ground bit of a mini-tile flag WORD.
     */
    public static final int MINI_TILE_HIGH = 4;
    /**
     * The ground height of a high-ground mini-tile ({@link #getTerrainHeight}); low ground is {@code 0}.
     */
    public static final int HIGH_GROUND_HEIGHT = 2;
    /**
     * The ground height of a middle-ground mini-tile ({@link #getTerrainHeight}).
     */
    public static final int MIDDLE_GROUND_HEIGHT = 1;
    /**
     * The size of one CV5 record (tile group) in bytes.
     */
    public static final int CV5_RECORD_SIZE = 52;
    /**
     * The offset of the sixteen megatile WORDs in a CV5 record.
     */
    public static final int CV5_MEGATILES_OFFSET = 20;
    /**
     * The first tile WORD of the creep CV5 group: group {@code 1}, tile {@code 0}.
     */
    public static final int CREEP_TILE = 0x10;

    /**
     * The number of tiles (megatile WORDs) of one CV5 record.
     */
    private static final int GROUP_TILES = 16;
    /**
     * The offset of the group flags WORD in a CV5 record.
     */
    private static final int CV5_FLAGS_OFFSET = 2;
    /**
     * The group flag bits copied into the high WORD of the expanded tile flags.
     */
    private static final int CV5_RETAINED_FLAGS = 0xD8FA;
    private static final int MINIMUM_GROUP_COUNT = 3;

    private static final int MINI_TILES_PER_TILE = MINI_TILES_PER_ROW * MINI_TILES_PER_ROW;
    /**
     * The shift from a pixel coordinate to a mini-tile coordinate ({@code log2(MINI_TILE_SIZE)}).
     */
    private static final int MINI_TILE_SHIFT = 3;
    private static final int VF4_RECORD_SIZE = MINI_TILES_PER_TILE * Short.BYTES;
    private static final int MAXIMUM_MEGATILE_COUNT = 0xFFFF;

    private static final int MAXIMUM_TILE_IDENTIFIER = 0xFFFF;
    private static final int TILE_GROUP_SHIFT = 4;
    private static final int TILE_GROUP_MASK = 0x7FF;
    /**
     * The CV5 group bits of a tile WORD in place.
     */
    private static final int TILE_GROUP_BITS = TILE_GROUP_MASK << TILE_GROUP_SHIFT;
    private static final int TILE_VARIANT_MASK = 0xF;
    private static final int TILE_CREEP_BIT = 0x8000;
    private static final int MEGATILE_INDEX_MASK = 0x7FFF;

    private static final int MINI_TILE_MIDDLE_SHIFT = 1;
    private static final int MINI_TILE_HIGH_SHIFT = 2;
    private static final int MINI_TILE_BLOCKS_VIEW = 8;
    /**
     * The number of mini-tiles above which a megatile counts as walkable or as high or middle ground.
     */
    private static final int MAJORITY_THRESHOLD = 12;

    private static final int TILE_WALKABLE = 0x10000;
    private static final int TILE_UNWALKABLE = 0x40000;
    private static final int TILE_CREEP = 0x400000;
    private static final int TILE_BLOCKS_VIEW = 0x01000000;
    private static final int TILE_MIDDLE_GROUND = 0x02000000;
    private static final int TILE_HIGH_GROUND = 0x04000000;
    private static final int TILE_PARTIALLY_WALKABLE = 0x20000000;

    private static final @NotNull String @NotNull [] TILESET_NAMES = {"badlands", "platform", "install", "ashworld", "jungle",
                                                                      "desert", "ice", "twilight"};

    /**
     * Returns the name of a tileset: the base name of its files in the {@code tileset} folder of the installed data.
     *
     * @param tileset the tileset index from {@code 0} through {@code 7}
     * @return the tileset name
     * @throws IllegalArgumentException if the tileset index is unsupported
     */
    public static @NotNull String getTilesetName(int tileset) {
        if (tileset < 0 || tileset >= TILESET_NAMES.length) {
            throw Exceptions.newIllegalArgumentException("Unsupported terrain tileset index: {0}", tileset);
        }

        return TILESET_NAMES[tileset];
    }

    /**
     * Creates a {@link TerrainTileset} instance from installed classic terrain files.
     *
     * @param storage the borrowed CASC storage
     * @param tileset the tileset index from {@code 0} through {@code 7}
     * @return the created {@link TerrainTileset} instance
     * @throws IOException if the tileset is unsupported or its data cannot be read
     */
    public static @NotNull TerrainTileset load(@NotNull CascStorage storage, int tileset) throws IOException {
        if (tileset < 0 || tileset >= TILESET_NAMES.length) {
            throw Exceptions.newIOException("Unsupported terrain tileset index: {0}", tileset);
        }

        String prefix = "tileset/" + TILESET_NAMES[tileset];

        return parse(storage.readFile(prefix + ".cv5"), storage.readFile(prefix + ".vf4"));
    }

    /**
     * Creates a {@link TerrainTileset} instance with VF4 walkability and height thresholds.
     *
     * @param groups         the CV5 records of {@code 52} bytes each
     * @param miniatureFlags the VF4 records of {@code 16} little-endian words each
     * @return the created {@link TerrainTileset} instance
     * @throws IOException if the records are truncated or outside the represented bounds
     */
    public static @NotNull TerrainTileset parse(byte @NotNull [] groups, byte @NotNull [] miniatureFlags) throws IOException {
        if (groups.length < MINIMUM_GROUP_COUNT * CV5_RECORD_SIZE || groups.length % CV5_RECORD_SIZE != 0 || miniatureFlags.length == 0 ||
            miniatureFlags.length % VF4_RECORD_SIZE != 0 || miniatureFlags.length / VF4_RECORD_SIZE > MAXIMUM_MEGATILE_COUNT) {
            throw Exceptions.newIOException("Invalid CV5 or VF4 record extent.");
        }

        int[] megatileFlags = new int[miniatureFlags.length / VF4_RECORD_SIZE];

        for (int megatileIndex = 0; megatileIndex < megatileFlags.length; megatileIndex++) {
            int bitOneCount = 0;
            int bitTwoCount = 0;
            int bitFourCount = 0;
            int combinedBits = 0;

            for (int miniatureIndex = 0; miniatureIndex < MINI_TILES_PER_TILE; miniatureIndex++) {
                int flags = LittleEndianUtils.readUnsigned16(miniatureFlags, megatileIndex * VF4_RECORD_SIZE + miniatureIndex * Short.BYTES);

                bitOneCount += flags & MINI_TILE_WALKABLE;
                bitTwoCount += (flags >>> MINI_TILE_MIDDLE_SHIFT) & 1;
                bitFourCount += (flags >>> MINI_TILE_HIGH_SHIFT) & 1;
                combinedBits |= flags;
            }

            int flags = bitOneCount > MAJORITY_THRESHOLD ? TILE_WALKABLE : TILE_UNWALKABLE;

            if (bitOneCount > 0 && bitOneCount < MINI_TILES_PER_TILE) {
                flags |= TILE_PARTIALLY_WALKABLE;
            }

            if (bitFourCount >= MAJORITY_THRESHOLD) {
                flags |= TILE_HIGH_GROUND;
            } else if (bitTwoCount + bitFourCount >= MAJORITY_THRESHOLD) {
                flags |= TILE_MIDDLE_GROUND;
            }

            if ((combinedBits & MINI_TILE_BLOCKS_VIEW) != 0) {
                flags |= TILE_BLOCKS_VIEW;
            }

            megatileFlags[megatileIndex] = flags;
        }

        return new TerrainTileset(groups.clone(), miniatureFlags.clone(), megatileFlags);
    }

    /**
     * Reads the megatile WORD of every tile of every CV5 record, in record order.
     *
     * @param groups the CV5 records
     * @return the raw megatile WORDs, {@code 16} per record
     */
    public static short @NotNull [] readMegatileIdentifiers(byte @NotNull [] groups) {
        short[] megatiles = new short[groups.length / CV5_RECORD_SIZE * GROUP_TILES];

        for (int group = 0; group < groups.length / CV5_RECORD_SIZE; group++) {
            int megatilesOffset = group * CV5_RECORD_SIZE + CV5_MEGATILES_OFFSET;

            for (int tile = 0; tile < GROUP_TILES; tile++) {
                megatiles[group * GROUP_TILES + tile] = (short) LittleEndianUtils.readUnsigned16(groups, megatilesOffset + tile * Short.BYTES);
            }
        }

        return megatiles;
    }

    /**
     * Reads the CV5 group index of a tile WORD.
     *
     * @param tileIdentifier the tile WORD
     * @return the group index in bits {@code 4..14} of the tile WORD
     */
    public static int getGroupIndex(int tileIdentifier) {
        return (tileIdentifier >>> TILE_GROUP_SHIFT) & TILE_GROUP_MASK;
    }

    /**
     * Computes the offset of the megatile WORD of a tile WORD in the CV5 records.
     * <p>
     * The group index is not checked against the records.
     *
     * @param tileIdentifier the tile WORD
     * @return the offset of the megatile WORD of the tile WORD's group and tile
     */
    public static int getMegatileOffset(int tileIdentifier) {
        return getGroupIndex(tileIdentifier) * CV5_RECORD_SIZE + CV5_MEGATILES_OFFSET + (tileIdentifier & TILE_VARIANT_MASK) * Short.BYTES;
    }

    /**
     * @param tileIdentifier the tile WORD
     * @return {@code true} if the tile WORD is in the creep CV5 group, {@code false} otherwise
     */
    public static boolean isCreepTile(int tileIdentifier) {
        return (tileIdentifier & TILE_GROUP_BITS) == CREEP_TILE;
    }


    private final byte @NotNull [] groups;
    private final byte @NotNull [] miniatureFlags;
    private final int @NotNull [] megatileFlags;

    private TerrainTileset(byte @NotNull [] groups, byte @NotNull [] miniatureFlags, int @NotNull [] megatileFlags) {
        this.groups         = groups;
        this.miniatureFlags = miniatureFlags;
        this.megatileFlags  = megatileFlags;
    }

    /**
     * @return the number of installed CV5 records
     */
    public int getGroupCount() {
        return this.groups.length / CV5_RECORD_SIZE;
    }

    /**
     * Checks whether a tile WORD names a tile of this tileset: its CV5 group is one of the records and its megatile one of the VF4 records, the
     * tiles a map's terrain expands ({@link #expandTileFlags}) without failing.
     *
     * @param tileIdentifier the tile WORD
     * @return {@code true} if the tile WORD is from {@code 0} through {@code 0xFFFF} with an installed group and megatile, {@code false} otherwise
     */
    public boolean hasTile(int tileIdentifier) {
        return tileIdentifier >= 0 && tileIdentifier <= MAXIMUM_TILE_IDENTIFIER && getGroupIndex(tileIdentifier) < this.getGroupCount() &&
               (this.readMegatileWord(tileIdentifier) & MEGATILE_INDEX_MASK) < this.megatileFlags.length;
    }

    /**
     * Reads the CV5 megatile WORD of a tile.
     *
     * @param tileIdentifier the tile WORD
     * @return the raw CV5 megatile WORD, including its stored high bit
     * @throws IllegalArgumentException if the lookup references unrepresented terrain memory
     */
    public int getMegatileIdentifier(int tileIdentifier) {
        int groupIndex = getGroupIndex(tileIdentifier);

        if (tileIdentifier < 0 || tileIdentifier > MAXIMUM_TILE_IDENTIFIER || groupIndex >= this.getGroupCount()) {
            throw Exceptions.newIllegalArgumentException("Unrepresented terrain megatile lookup.");
        }

        return this.readMegatileWord(tileIdentifier);
    }

    /**
     * Reads a VF4 WORD after the CV5 megatile lookup.
     *
     * @param tileIdentifier the normalized map tile WORD
     * @param miniatureIndex the miniature index in row-major order from {@code 0..15}
     * @return the unsigned VF4 flag WORD
     * @throws IllegalArgumentException if the lookup references unrepresented terrain memory
     */
    public int getMiniatureFlags(int tileIdentifier, int miniatureIndex) {
        int groupIndex = getGroupIndex(tileIdentifier);

        if (tileIdentifier < 0 || tileIdentifier > MAXIMUM_TILE_IDENTIFIER || groupIndex >= this.getGroupCount() || miniatureIndex < 0 ||
            miniatureIndex >= MINI_TILES_PER_TILE) {
            throw Exceptions.newIllegalArgumentException("Unrepresented terrain miniature lookup.");
        }

        int megatileIndex = this.readMegatileWord(tileIdentifier) & MEGATILE_INDEX_MASK;

        if (megatileIndex >= this.megatileFlags.length) {
            throw Exceptions.newIllegalArgumentException("Terrain megatile is outside VF4: {0}", megatileIndex);
        }

        return this.getMegatileMiniatureFlags(megatileIndex, miniatureIndex);
    }

    /**
     * Reads terrain height after the caller selects an overlay or base tile WORD.
     * <p>
     * The raw megatile WORD is used without masking its high bit.
     *
     * @param tileIdentifier the selected tile WORD
     * @param positionX      the horizontal pixel DWORD
     * @param positionY      the vertical pixel DWORD
     * @return the VF4 height from {@code 0} through {@code 2}
     * @throws IllegalArgumentException if the lookup references unrepresented terrain memory
     */
    public int getTerrainHeight(int tileIdentifier, int positionX, int positionY) {
        int groupIndex = getGroupIndex(tileIdentifier);

        if (tileIdentifier < 0 || tileIdentifier > MAXIMUM_TILE_IDENTIFIER || groupIndex >= this.getGroupCount()) {
            throw Exceptions.newIllegalArgumentException("Unrepresented terrain height lookup.");
        }

        int megatileIndex = this.readMegatileWord(tileIdentifier);

        if (megatileIndex >= this.megatileFlags.length) {
            throw Exceptions.newIllegalArgumentException("Terrain height megatile is outside VF4: {0}", megatileIndex);
        }

        int miniatureIndex = ((positionY >>> MINI_TILE_SHIFT) & MINI_TILE_COLUMN_MASK) * MINI_TILES_PER_ROW +
                             ((positionX >>> MINI_TILE_SHIFT) & MINI_TILE_COLUMN_MASK);
        int flags = this.getMegatileMiniatureFlags(megatileIndex, miniatureIndex);

        return (flags & MINI_TILE_HIGH) != 0 ? HIGH_GROUND_HEIGHT : (flags >>> MINI_TILE_MIDDLE_SHIFT) & 1;
    }

    /**
     * Reads one unsigned VF4 miniature WORD from a selected megatile, without a CV5 lookup or high-bit masking.
     *
     * @param megatileIndex  the selected VF4 megatile index
     * @param miniatureIndex the miniature index in row-major order from {@code 0..15}
     * @return the unsigned VF4 flag WORD
     * @throws IllegalArgumentException if either index is outside the loaded VF4 records
     */
    public int getMegatileMiniatureFlags(int megatileIndex, int miniatureIndex) {
        if (megatileIndex < 0 || megatileIndex >= this.megatileFlags.length || miniatureIndex < 0 || miniatureIndex >= MINI_TILES_PER_TILE) {
            throw Exceptions.newIllegalArgumentException("Unrepresented direct terrain miniature lookup: {0}, {1}", megatileIndex, miniatureIndex);
        }

        return LittleEndianUtils.readUnsigned16(this.miniatureFlags, megatileIndex * VF4_RECORD_SIZE + miniatureIndex * Short.BYTES);
    }

    /**
     * Expands normalized MTXM words into the initially zeroed DWORD flag array.
     * <p>
     * Call the tileset-aware CHK accessor first to preserve normalization between repeated payloads. Only initial terrain flags
     * are produced; dynamic low-word state must not be regenerated through this method.
     *
     * @param tileIdentifiers the normalized map tile words in row-major order
     * @return the expanded flags with initial low words equal to {@code 0}
     * @throws IllegalArgumentException if an input group or megatile is outside the loaded records
     */
    public int @NotNull [] expandTileFlags(int @NotNull [] tileIdentifiers) {
        int[] tileFlags = new int[tileIdentifiers.length];

        for (int tileIndex = 0; tileIndex < tileIdentifiers.length; tileIndex++) {
            int tileIdentifier = tileIdentifiers[tileIndex];
            int groupIndex = getGroupIndex(tileIdentifier);

            if (tileIdentifier < 0 || tileIdentifier > MAXIMUM_TILE_IDENTIFIER || groupIndex >= this.getGroupCount()) {
                throw Exceptions.newIllegalArgumentException("Unnormalized terrain group at tile {0}", tileIndex);
            }

            int megatileIndex = this.readMegatileWord(tileIdentifier) & MEGATILE_INDEX_MASK;

            if (megatileIndex >= this.megatileFlags.length) {
                throw Exceptions.newIllegalArgumentException("Terrain megatile is outside VF4: {0}", megatileIndex);
            }

            int groupFlags = LittleEndianUtils.readUnsigned16(this.groups, groupIndex * CV5_RECORD_SIZE + CV5_FLAGS_OFFSET) & CV5_RETAINED_FLAGS;
            int flags = (groupFlags << Short.SIZE) | this.megatileFlags[megatileIndex];

            if ((tileIdentifier & TILE_CREEP_BIT) != 0) {
                flags |= TILE_CREEP;
            }

            tileFlags[tileIndex] = flags;
        }

        return tileFlags;
    }

    private int readMegatileWord(int tileIdentifier) {
        return LittleEndianUtils.readUnsigned16(this.groups, getMegatileOffset(tileIdentifier));
    }
}
