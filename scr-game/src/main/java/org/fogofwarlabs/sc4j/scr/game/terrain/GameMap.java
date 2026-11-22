package org.fogofwarlabs.sc4j.scr.game.terrain;

import org.fogofwarlabs.sc4j.base.binary.LittleEndianUtils;
import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.visibility.SightGrid;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Retains row-major terrain tiles, mutable tile flags and overlay identifiers for a game world. The tile flags are the SC:R storage of the engine
 * sight algorithm ({@link SightGrid}): their native layout, which EUD and saves read, holds the unseen and unexplored player bytes and the
 * terrain height bits.
 */
public final class GameMap implements SightGrid {
    /**
     * The largest map width and height in tiles; the fixed tile arrays are this many tiles square.
     */
    public static final int MAXIMUM_DIMENSION = 256;
    /**
     * The number of tiles of the fixed tile arrays.
     */
    public static final int MAXIMUM_TILE_COUNT = MAXIMUM_DIMENSION * MAXIMUM_DIMENSION;
    /**
     * The index of the tile WORD array among the fixed arrays a saved game's reader filled ({@link #getSavedArray}).
     */
    public static final int SAVED_TILES = 0;
    /**
     * The index of the graphics cache WORD array among the fixed arrays a saved game's reader filled.
     */
    public static final int SAVED_MEGATILES = 1;
    /**
     * The index of the tile flag DWORD array among the fixed arrays a saved game's reader filled.
     */
    public static final int SAVED_FLAGS = 2;
    /**
     * The flags a new game keeps past the map's tiles in the fixed tile flag array: no player sees or explored the tile.
     */
    public static final int UNUSED_TILE_FLAGS = MapTileFlags.NOT_VISIBLE_OR_EXPLORED;

    /**
     * The tile heights that block the sight of a unit on high ground.
     */
    private static final int HIGH_GROUND_BLOCKERS = MapTileFlags.VERY_HIGH;
    /**
     * The tile heights that block the sight of a unit on middle ground.
     */
    private static final int MIDDLE_GROUND_BLOCKERS = MapTileFlags.VERY_HIGH | MapTileFlags.HIGH;
    /**
     * The tile heights that block the sight of a unit on low ground.
     */
    private static final int LOW_GROUND_BLOCKERS = MapTileFlags.VERY_HIGH | MapTileFlags.MIDDLE | MapTileFlags.HIGH;
    /**
     * The terrain flags a creep tile takes from its creep variant.
     */
    private static final int CREEP_TERRAIN_FLAGS = MapTileFlags.HAS_CREEP | MapTileFlags.UNWALKABLE | MapTileFlags.WALKABLE;
    /**
     * The columns at the left and right edges that the bottom border rectangles cover.
     */
    private static final int BORDER_COLUMNS = 5;
    /**
     * The rows at the bottom edge that the border rectangles cover.
     */
    private static final int BORDER_ROWS = 2;
    /**
     * The terrain flags the bottom border rectangles clear.
     */
    private static final int BORDER_CLEARED_FLAGS = MapTileFlags.PARTIALLY_WALKABLE | MapTileFlags.HAS_CREEP | MapTileFlags.WALKABLE;
    /**
     * The flags the game sets and clears on a tile after the map loader expanded its terrain: building occupancy and the creep state.
     */
    private static final int RUNTIME_FLAGS = MapTileFlags.OCCUPIED | MapTileFlags.CREEP_RECEDING | MapTileFlags.TEMPORARY_CREEP;
    /**
     * The mask of an unsigned WORD: a tile identifier.
     */
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The mask of an unsigned BYTE: the MASK byte of a tile.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * Little-endian DWORD access to the saved tile flag array at any byte offset.
     */
    private static final @NotNull VarHandle LITTLE_ENDIAN_DWORD = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private final int width;
    private final int height;
    private final int tileset;

    private final int @NotNull [] tileIdentifiers;
    private final int @Nullable [] tileFlags;

    private int @Nullable [] overlayTileIdentifiers;
    /**
     * The fixed {@link #MAXIMUM_DIMENSION} x {@link #MAXIMUM_DIMENSION} tile, graphics cache and flag arrays a saved game's reader filled, or
     * {@code null}: past the map's tiles the arrays keep the loaded bytes, which the next save writes again.
     */
    private byte @Nullable [] @NotNull [] savedArrays;
    /**
     * The renderer's tile decal indices a saved game's reader filled, or {@code null}: the simulation does not keep them, and the next save writes
     * the loaded ones again.
     */
    private byte @Nullable [] savedDecals;

    /**
     * Creates a {@link GameMap} instance without expanded terrain flags.
     *
     * @param width           the width in tiles
     * @param height          the height in tiles
     * @param tileset         the tileset index
     * @param tileIdentifiers the tile identifiers in row-major order
     */
    public GameMap(int width, int height, int tileset, int @NotNull [] tileIdentifiers) {
        this(width, height, tileset, tileIdentifiers, null);
    }

    /**
     * Creates a {@link GameMap} instance with the initial expanded terrain flags.
     * <p>
     * Input arrays are copied once. Subsequent occupancy, creep and visibility changes belong to this map.
     *
     * @param width           the width in tiles
     * @param height          the height in tiles
     * @param tileset         the tileset index
     * @param tileIdentifiers the tile identifiers in row-major order
     * @param tileFlags       the expanded terrain flags, or {@code null} when unavailable
     * @throws IllegalArgumentException if supplied terrain flags do not match the map extent
     */
    public GameMap(int width, int height, int tileset, int @NotNull [] tileIdentifiers, int @Nullable [] tileFlags) {
        if (tileFlags != null && ((long) width * height != tileFlags.length || tileIdentifiers.length != tileFlags.length)) {
            throw Exceptions.newIllegalArgumentException("Terrain flags do not match the map extent.");
        }

        this.width   = width;
        this.height  = height;
        this.tileset = tileset;

        this.tileIdentifiers = tileIdentifiers.clone();
        this.tileFlags       = tileFlags == null ? null : tileFlags.clone();

        this.overlayTileIdentifiers = null;
        this.savedArrays            = null;
        this.savedDecals            = null;
    }

    /**
     * @return the map width in tiles
     */
    @Override
    public int getWidth() {
        return this.width;
    }

    /**
     * @return the map height in tiles
     */
    @Override
    public int getHeight() {
        return this.height;
    }

    /**
     * @return the tileset index
     */
    public int getTileset() {
        return this.tileset;
    }

    /**
     * @return the number of represented base tiles
     */
    public int getTileCount() {
        return this.tileIdentifiers.length;
    }

    /**
     * Reads the tile identifier at the given coordinates.
     *
     * @param tileX the horizontal coordinate in tiles
     * @param tileY the vertical coordinate in tiles
     * @return the tile identifier
     */
    public int getTileIdentifier(int tileX, int tileY) {
        return this.tileIdentifiers[tileY * this.width + tileX];
    }

    /**
     * @param tileIndex the row-major tile index
     * @return the current base terrain identifier
     */
    public int getTileIdentifier(int tileIndex) {
        return this.tileIdentifiers[tileIndex];
    }

    /**
     * Copies the tile identifiers as the initial terrain image for renderer upload.
     *
     * @return an independent copy of the tile identifiers
     */
    public int @NotNull [] copyTileIdentifiersForRendering() {
        return this.tileIdentifiers.clone();
    }

    /**
     * @return {@code true} if terrain overlays have been initialized, {@code false} otherwise
     */
    public boolean hasOverlayTiles() {
        return this.overlayTileIdentifiers != null;
    }

    private int @NotNull [] requireOverlayTileIdentifiers() {
        int[] overlays = this.overlayTileIdentifiers;

        if (overlays == null) {
            throw Exceptions.newIllegalStateException("Terrain overlays are unavailable.");
        }

        return overlays;
    }

    /**
     * @param tileIndex the row-major tile index
     * @return the retained terrain below an overlay, or {@code 0} when there is no overlay
     * @throws IllegalStateException if overlays have not been initialized
     */
    public int getOverlayTileIdentifier(int tileIndex) {
        return this.requireOverlayTileIdentifiers()[tileIndex];
    }

    /**
     * Returns the terrain of a tile: the tile retained below creep, otherwise the tile identifier.
     *
     * @param tileIndex the row-major tile index
     * @return the terrain tile WORD
     */
    public int getTerrainTileIdentifier(int tileIndex) {
        int retained = this.overlayTileIdentifiers == null ? 0 : this.overlayTileIdentifiers[tileIndex] & WORD_MASK;

        return retained != 0 ? retained : this.tileIdentifiers[tileIndex];
    }

    /**
     * Reads height at signed DWORD pixel coordinates using borrowed terrain resources.
     * <p>
     * Signed division and overflowing row-major arithmetic select one tile index. The retained overlay WORD is read before the base WORD and takes
     * precedence when nonzero. Individual axes are not clipped.
     *
     * @param terrain   the borrowed terrain resources
     * @param positionX the horizontal pixel DWORD
     * @param positionY the vertical pixel DWORD
     * @return the selected miniature's height from {@code 0} through {@code 2}
     * @throws IllegalStateException     if overlays have not been initialized
     * @throws IllegalArgumentException  if the selected tile or its raw height megatile is outside the terrain resources
     * @throws IndexOutOfBoundsException if the computed index reaches an unrepresented overlay or base entry
     */
    public int getTerrainHeight(@NotNull TerrainTileset terrain, int positionX, int positionY) {
        int tileIndex = positionY / TerrainTileset.TILE_SIZE * this.width + positionX / TerrainTileset.TILE_SIZE;
        int tileIdentifier = this.getOverlayTileIdentifier(tileIndex) & WORD_MASK;

        if (tileIdentifier == 0) {
            tileIdentifier = this.getTileIdentifier(tileIndex) & WORD_MASK;
        }

        return terrain.getTerrainHeight(tileIdentifier, positionX, positionY);
    }

    /**
     * @return {@code true} if expanded terrain flags are available, {@code false} otherwise
     */
    public boolean hasTileFlags() {
        return this.tileFlags != null;
    }

    /**
     * Returns the terrain flags at a sprite WORD position through the row-major index {@code width * (y / 32) + x / 32}, which does not bound either
     * coordinate.
     * <p>
     * Both WORDs are read as signed 16-bit values and divided with truncation toward zero, so a coordinate from {@code 0xFFE1} to {@code 0xFFFF}
     * reads the first column or row. A column past the right edge reads the first tiles of the following row, and a column left of the left edge
     * reads the last tiles of the previous row.
     * <p>
     * An index past the map's tiles reads the fixed tile flag array ({@link #getStoredTileFlags}). An index outside that array reads
     * {@link #UNUSED_TILE_FLAGS}, so no player sees the position.
     *
     * @param positionX the horizontal position WORD in pixels
     * @param positionY the vertical position WORD in pixels
     * @return the terrain flags
     * @throws IllegalStateException if expanded terrain flags are unavailable
     */
    public int getTileFlagsAtPosition(int positionX, int positionY) {
        if (this.tileFlags == null) {
            throw Exceptions.newIllegalStateException("Expanded terrain flags are unavailable.");
        }

        int tileIndex = this.width * ((short) positionY / TerrainTileset.TILE_SIZE) + (short) positionX / TerrainTileset.TILE_SIZE;

        if (tileIndex < 0 || tileIndex >= MAXIMUM_TILE_COUNT) {
            return UNUSED_TILE_FLAGS;
        }

        return this.getStoredTileFlags(tileIndex);
    }

    /**
     * Checks the terrain flags at a sprite WORD position, read as {@link #getTileFlagsAtPosition} reads them, against a required and a rejected flag
     * mask.
     *
     * @param positionX     the horizontal position WORD in pixels
     * @param positionY     the vertical position WORD in pixels
     * @param requiredFlags the flags of which the tile needs at least one
     * @param rejectedFlags the flags of which the tile may have none
     * @return {@code true} if the tile has a required flag and no rejected flag, {@code false} otherwise
     * @throws IllegalStateException if expanded terrain flags are unavailable
     */
    public boolean hasTileFlagsAtPosition(int positionX, int positionY, int requiredFlags, int rejectedFlags) {
        int flags = this.getTileFlagsAtPosition(positionX, positionY);

        return (flags & requiredFlags) != 0 && (flags & rejectedFlags) == 0;
    }

    /**
     * Returns the flags the fixed {@link #MAXIMUM_DIMENSION} x {@link #MAXIMUM_DIMENSION} tile flag array holds at a row-major index, which starts
     * with the map's tiles.
     * <p>
     * The map's tiles hold their current flags. Past them, a loaded game holds the flags its saved game stored and a new game holds
     * {@link #UNUSED_TILE_FLAGS}.
     *
     * @param tileIndex the row-major index in the fixed array
     * @return the tile flags
     * @throws IllegalStateException     if expanded terrain flags are unavailable
     * @throws IndexOutOfBoundsException if the index lies outside the fixed array
     */
    public int getStoredTileFlags(int tileIndex) {
        if (this.tileFlags == null) {
            throw Exceptions.newIllegalStateException("Expanded terrain flags are unavailable.");
        }

        if (tileIndex < 0 || tileIndex >= MAXIMUM_TILE_COUNT) {
            throw Exceptions.newIndexOutOfBoundsException("Tile index is outside the fixed tile flag array: {0}", tileIndex);
        }

        if (tileIndex < this.tileFlags.length) {
            return this.tileFlags[tileIndex];
        }

        if (this.savedArrays == null) {
            return UNUSED_TILE_FLAGS;
        }

        return (int) LITTLE_ENDIAN_DWORD.get(this.savedArrays[SAVED_FLAGS], tileIndex * Integer.BYTES);
    }

    /**
     * @return the number of represented terrain flag entries
     * @throws IllegalStateException if expanded terrain flags are unavailable
     */
    public int getTileFlagCount() {
        if (this.tileFlags == null) {
            throw Exceptions.newIllegalStateException("Expanded terrain flags are unavailable.");
        }

        return this.tileFlags.length;
    }

    /**
     * Returns the represented terrain flags for the given tile.
     *
     * @param tileX the horizontal coordinate in tiles
     * @param tileY the vertical coordinate in tiles
     * @return the terrain flags
     * @throws IllegalStateException     if expanded terrain flags are unavailable
     * @throws IndexOutOfBoundsException if the coordinates are outside the map
     */
    public int getTileFlags(int tileX, int tileY) {
        if (this.tileFlags == null) {
            throw Exceptions.newIllegalStateException("Expanded terrain flags are unavailable.");
        }

        if (tileX < 0 || tileX >= this.width || tileY < 0 || tileY >= this.height) {
            throw Exceptions.newIndexOutOfBoundsException("Terrain coordinates are outside the map: {0}, {1}", tileX, tileY);
        }

        return this.tileFlags[tileY * this.width + tileX];
    }

    /**
     * @param tileIndex the row-major tile index
     * @return the terrain, occupancy and visibility flags
     * @throws IllegalStateException if expanded terrain flags are unavailable
     */
    public int getTileFlags(int tileIndex) {
        if (this.tileFlags == null) {
            throw Exceptions.newIllegalStateException("Expanded terrain flags are unavailable.");
        }

        return this.tileFlags[tileIndex];
    }

    /**
     * @return a copy of the renderer's tile decal indices a saved game's reader filled, or {@code null} for a game that was not loaded
     */
    public byte @Nullable [] getSavedDecals() {
        return this.savedDecals == null ? null : this.savedDecals.clone();
    }

    /**
     * @param arrayIndex the array index: {@link #SAVED_TILES}, {@link #SAVED_MEGATILES} or {@link #SAVED_FLAGS}
     * @return a copy of a fixed array a saved game's reader filled, or {@code null} for a game that was not loaded
     */
    public byte @Nullable [] getSavedArray(int arrayIndex) {
        return this.savedArrays == null ? null : this.savedArrays[arrayIndex].clone();
    }

    /**
     * Reads one unsigned graphics-cache WORD from the retained saved snapshot without copying its array.
     * <p>
     * These snapshot bytes can differ from the live map prefix.
     *
     * @param tileIndex the row-major index in the fixed native tile array
     * @return the retained unsigned graphics-cache WORD
     * @throws IndexOutOfBoundsException if the index is outside the fixed array or the requested WORD exceeds the snapshot
     * @throws IllegalStateException     if no saved arrays have been retained
     */
    public int getSavedGraphicsCache(int tileIndex) {
        if (tileIndex < 0 || tileIndex >= MAXIMUM_TILE_COUNT) {
            throw Exceptions.newIndexOutOfBoundsException("Graphics-cache index is outside the fixed tile array: {0}", tileIndex);
        }

        byte[][] savedArrays = this.savedArrays;

        if (savedArrays == null) {
            throw Exceptions.newIllegalStateException("Saved graphics-cache storage is unavailable.");
        }

        byte[] graphicsCache = savedArrays[SAVED_MEGATILES];
        int byteOffset = tileIndex * Short.BYTES;

        if (byteOffset > graphicsCache.length - Short.BYTES) {
            throw Exceptions.newIndexOutOfBoundsException("Graphics-cache WORD exceeds retained saved storage at tile {0}.", tileIndex);
        }

        return LittleEndianUtils.readUnsigned16(graphicsCache, byteOffset);
    }


    /**
     * Retains the base tile before creep replaces its visible terrain. An already retained overlay is left unchanged.
     *
     * @param tileIndex the row-major tile index
     * @throws IllegalStateException if overlays have not been initialized
     */
    public void retainTerrainUnderCreep(int tileIndex) {
        int[] overlays = this.requireOverlayTileIdentifiers();

        if (overlays[tileIndex] == 0) {
            overlays[tileIndex] = this.tileIdentifiers[tileIndex];
        }
    }

    /**
     * Restores the terrain retained below creep.
     * <p>
     * A retained identifier of {@code 0} leaves the tile unchanged.
     *
     * @param tileIndex the row-major tile index
     * @return {@code true} if the tile identifier was restored and the retained value cleared, {@code false} otherwise
     * @throws IllegalStateException if overlays have not been initialized
     */
    public boolean restoreTerrainUnderCreep(int tileIndex) {
        int[] overlays = this.requireOverlayTileIdentifiers();
        int retained = overlays[tileIndex];

        if (retained == 0) {
            return false;
        }

        this.tileIdentifiers[tileIndex] = retained;
        overlays[tileIndex]             = 0;

        return true;
    }

    /**
     * Sets or clears the receding-creep tile flag {@link MapTileFlags#CREEP_RECEDING}.
     *
     * @param tileIndex the row-major tile index
     * @param receding  whether the tile's creep is receding
     */
    public void setCreepReceding(int tileIndex, boolean receding) {
        int flags = this.getTileFlags(tileIndex);

        this.tileFlags[tileIndex] = receding ? flags | MapTileFlags.CREEP_RECEDING : flags & ~MapTileFlags.CREEP_RECEDING;
    }

    /**
     * Sets the tile identifier to a creep tile.
     *
     * @param tileIndex the row-major tile index
     * @param variant   the creep terrain variant chosen by the game random stream
     */
    public void setCreepVariant(int tileIndex, int variant) {
        this.tileIdentifiers[tileIndex] = TerrainTileset.CREEP_TILE + variant;
    }

    /**
     * Updates the terrain properties of a newly applied creep tile.
     *
     * @param tileIndex       the row-major tile index
     * @param creepFlags      the terrain properties of the selected creep variant
     * @param graphicsChanged whether the graphics cache was updated
     */
    public void applyCreepTerrainFlags(int tileIndex, int creepFlags, boolean graphicsChanged) {
        int flags = this.getTileFlags(tileIndex);

        if (graphicsChanged) {
            flags |= MapTileFlags.TEMPORARY_CREEP;
        }

        flags = flags & ~CREEP_TERRAIN_FLAGS | creepFlags & CREEP_TERRAIN_FLAGS;

        if ((flags & MapTileFlags.WALKABLE) != 0) {
            flags &= ~MapTileFlags.UNWALKABLE;
        }

        if ((flags & CREEP_TERRAIN_FLAGS) == 0) {
            flags |= MapTileFlags.UNWALKABLE;
        }

        this.tileFlags[tileIndex] = flags;
    }

    /**
     * Updates the cached creep marker after the graphics tile changes.
     *
     * @param tileIndex the row-major tile index
     */
    public void refreshCreepGraphicsFlag(int tileIndex) {
        int flags = this.getTileFlags(tileIndex);

        this.tileFlags[tileIndex] = TerrainTileset.isCreepTile(this.tileIdentifiers[tileIndex])
                                    ? flags | MapTileFlags.TEMPORARY_CREEP
                                    : flags & ~MapTileFlags.TEMPORARY_CREEP;
    }

    /**
     * Marks a tile as occupied by a building footprint.
     *
     * @param tileIndex the row-major tile index covered by a building footprint
     */
    public void occupyTile(int tileIndex) {
        this.tileFlags[tileIndex] = this.getTileFlags(tileIndex) | MapTileFlags.OCCUPIED;
    }

    /**
     * Clears building occupancy when its visible footprint is removed.
     *
     * @param tileIndex the row-major tile index
     */
    public void vacateTile(int tileIndex) {
        this.tileFlags[tileIndex] = this.getTileFlags(tileIndex) & ~MapTileFlags.OCCUPIED;
    }

    /**
     * @return the flags a ground reveal assumes for a tile before it reveals it: no player sees the tile
     */
    @Override
    public int getNotVisibleFlags() {
        return MapTileFlags.NOT_VISIBLE;
    }

    /**
     * Returns the tile flag bits that block the sight of a ground reveal from an elevation: the bits of the tiles a viewer standing at that
     * elevation does not see past.
     * <p>
     * The SC:R heights: high ground ({@link TerrainTileset#HIGH_GROUND_HEIGHT}) is blocked by {@link MapTileFlags#VERY_HIGH}, middle ground also by
     * {@link MapTileFlags#HIGH}, and every other height also by {@link MapTileFlags#MIDDLE}.
     *
     * @param elevation the elevation of the viewer
     * @return the blocking terrain bits, {@code 0} for none
     */
    @Override
    public int getGroundBlockers(int elevation) {
        return elevation == TerrainTileset.HIGH_GROUND_HEIGHT
               ? HIGH_GROUND_BLOCKERS
               : elevation == TerrainTileset.MIDDLE_GROUND_HEIGHT ? MIDDLE_GROUND_BLOCKERS : LOW_GROUND_BLOCKERS;
    }

    /**
     * Reveals a tile to players: they see it now and have explored it.
     * <p>
     * The SC:R tile clears the players' current and explored visibility bits.
     *
     * @param tileIndex  the tile index
     * @param playerMask the players, one bit per player index
     * @return the tile flags afterwards, which the sight test of the farther tiles reads
     */
    @Override
    public int revealTile(int tileIndex, int playerMask) {
        int flags = this.getTileFlags(tileIndex) & ~(playerMask | playerMask << MapTileFlags.NOT_EXPLORED_SHIFT);

        this.tileFlags[tileIndex] = flags;

        return flags;
    }

    /**
     * Replaces the visible and explored bytes of a tile with one MASK byte. A byte of {@code 0xFF} leaves the tile unseen and unexplored; the terrain
     * bits remain unchanged.
     *
     * @param tileIndex the row-major tile index
     * @param mask      the unsigned MASK byte, one bit per player that has not seen the tile
     */
    public void applyMask(int tileIndex, int mask) {
        mask &= BYTE_MASK;

        this.tileFlags[tileIndex] = this.getTileFlags(tileIndex) & ~MapTileFlags.NOT_VISIBLE_OR_EXPLORED | mask |
                                    mask << MapTileFlags.NOT_EXPLORED_SHIFT;
    }

    /**
     * Replaces the visible and explored bytes of one tile, as an EUD write of the tile's low WORD does; the terrain bits remain unchanged. The
     * visible bits last until the game updates the sight again; the explored bits stay.
     *
     * @param tileX the horizontal coordinate in tiles
     * @param tileY the vertical coordinate in tiles
     * @param bits  the low WORD: bit {@code p} if player {@code p} does not see the tile, bit {@code 8 + p} if it has not explored it
     * @throws IllegalStateException     if expanded terrain flags are unavailable
     * @throws IndexOutOfBoundsException if the coordinates are outside the map
     */
    public void setTileVisibility(int tileX, int tileY, int bits) {
        int flags = this.getTileFlags(tileX, tileY);

        this.tileFlags[tileY * this.width + tileX] = flags & ~MapTileFlags.NOT_VISIBLE_OR_EXPLORED | bits & MapTileFlags.NOT_VISIBLE_OR_EXPLORED;
    }

    /**
     * Clears the given per-player visibility bits of every tile, as the reveal cheat does with the masks {@code 0xFFFF} (visible and explored) and
     * {@code 0xFF} (visible).
     *
     * @param bits the bits to clear in every tile DWORD
     */
    public void clearVisibilityBits(int bits) {
        int count = this.getTileFlagCount();

        for (int tileIndex = 0; tileIndex < count; tileIndex++) {
            this.tileFlags[tileIndex] &= ~bits;
        }
    }

    /**
     * Marks every tile unseen for the next ordered sight pass, retaining explored bits.
     */
    public void resetVisibility() {
        int count = this.getTileFlagCount();

        for (int tileIndex = 0; tileIndex < count; tileIndex++) {
            this.tileFlags[tileIndex] |= MapTileFlags.NOT_VISIBLE;
        }
    }

    /**
     * Allocates zeroed overlay storage before map section loading.
     *
     * @throws IllegalStateException         if overlays have already been initialized
     * @throws UnsupportedOperationException if the dimensions exceed represented Java array storage
     */
    public void initializeOverlayTiles() {
        if (this.overlayTileIdentifiers != null) {
            throw Exceptions.newIllegalStateException("Terrain overlays are already initialized.");
        }

        long tileCount = (long) this.width * this.height;

        if (this.width < 0 || this.height < 0 || tileCount > Integer.MAX_VALUE) {
            throw Exceptions.newUnsupportedOperationException("Native overlay extent exceeds represented array storage.");
        }

        this.overlayTileIdentifiers = new int[(int) tileCount];
    }

    /**
     * Stores a tile identifier read from a saved game's MTXM array.
     *
     * @param tileIndex      the row-major tile index
     * @param tileIdentifier the tile WORD
     */
    public void restoreTileIdentifier(int tileIndex, int tileIdentifier) {
        this.tileIdentifiers[tileIndex] = tileIdentifier & WORD_MASK;
    }

    /**
     * Keeps the fixed arrays a saved game's reader filled, whose bytes past the map's tiles the next save writes again.
     *
     * @param tiles     the tile WORD array
     * @param megatiles the graphics cache WORD array
     * @param flags     the tile flag DWORD array
     */
    public void retainSavedArrays(byte @NotNull [] tiles, byte @NotNull [] megatiles, byte @NotNull [] flags) {
        this.savedArrays = new byte[][]{tiles.clone(), megatiles.clone(), flags.clone()};
    }

    /**
     * Keeps the renderer's tile decal indices a saved game's reader filled, which the next save writes again.
     *
     * @param decals one byte per map tile
     */
    public void retainSavedDecals(byte @NotNull [] decals) {
        this.savedDecals = decals.clone();
    }

    /**
     * Stores tile flags read from a saved game's flag array.
     *
     * @param tileIndex the row-major tile index
     * @param flags     the flag DWORD
     * @throws IllegalStateException if expanded terrain flags are unavailable
     */
    public void restoreTileFlags(int tileIndex, int flags) {
        if (this.tileFlags == null) {
            throw Exceptions.newIllegalStateException("Expanded terrain flags are unavailable.");
        }

        this.tileFlags[tileIndex] = flags;
    }

    /**
     * Stores the terrain retained below an overlay, read from a saved game's overlay array.
     *
     * @param tileIndex      the row-major tile index
     * @param tileIdentifier the retained tile WORD, or {@code 0}
     * @throws IllegalStateException if overlays have not been initialized
     */
    public void restoreOverlayTileIdentifier(int tileIndex, int tileIdentifier) {
        this.requireOverlayTileIdentifiers()[tileIndex] = tileIdentifier & WORD_MASK;
    }

    /**
     * Applies the bottom-edge terrain flag rectangles: the last two rows within five columns of the left and right edges and the whole last row lose
     * their walkable, creep and partially walkable flags and become unbuildable.
     *
     * @throws IllegalStateException         if expanded terrain flags are unavailable
     * @throws UnsupportedOperationException if a rectangle would reference unrepresented storage
     */
    public void initializeTerrainBorders() {
        this.getTileFlagCount();

        int[] flags = this.tileFlags;

        if (this.width < BORDER_COLUMNS || this.height < BORDER_ROWS) {
            throw Exceptions.newUnsupportedOperationException("Native MTXM border rectangles require represented map storage.");
        }

        for (int tileY = this.height - BORDER_ROWS; tileY < this.height; tileY++) {
            for (int tileX = 0; tileX < this.width; tileX++) {
                int tileIndex = tileY * this.width + tileX;

                flags[tileIndex] = this.applyTerrainBorder(tileIndex, flags[tileIndex]);
            }
        }
    }

    /**
     * Applies the bottom-edge terrain flag rectangles of {@link #initializeTerrainBorders} to the expanded flags of one tile: a tile inside them
     * loses its walkable, creep and partially walkable flags and becomes unbuildable.
     *
     * @param tileIndex    the row-major tile index
     * @param terrainFlags the tile's expanded terrain flags
     * @return the terrain flags the map loader gives the tile
     */
    public int applyTerrainBorder(int tileIndex, int terrainFlags) {
        int tileX = tileIndex % this.width;
        int tileY = tileIndex / this.width;

        if (tileY < this.height - BORDER_ROWS || tileY != this.height - 1 && tileX >= BORDER_COLUMNS && tileX < this.width - BORDER_COLUMNS) {
            return terrainFlags;
        }

        return (terrainFlags & ~BORDER_CLEARED_FLAGS) | MapTileFlags.UNBUILDABLE;
    }

    /**
     * Replaces the terrain of a tile with another tile, as a map that had the other tile from the start.
     * <p>
     * The terrain bits become the new tile's loaded terrain flags; the per-player visibility and the occupancy and creep bits the game set or
     * cleared since the previous terrain was loaded stay. A tile below creep keeps the creep tile, its walkable and creep bits, and retains the
     * new tile below the creep, which the creep's recession restores.
     *
     * @param tileIndex            the row-major tile index
     * @param tileIdentifier       the new tile WORD
     * @param previousTerrainFlags the loaded terrain flags of the previous terrain ({@link #getTerrainTileIdentifier})
     * @param terrainFlags         the loaded terrain flags of the new tile ({@link #applyTerrainBorder})
     * @throws IllegalStateException if expanded terrain flags or overlays are unavailable
     */
    public void replaceTerrain(int tileIndex, int tileIdentifier, int previousTerrainFlags, int terrainFlags) {
        int[] overlays = this.requireOverlayTileIdentifiers();
        int flags = this.getTileFlags(tileIndex);
        int set = flags & ~previousTerrainFlags & RUNTIME_FLAGS;
        int cleared = ~flags & previousTerrainFlags & RUNTIME_FLAGS;
        int replaced = (terrainFlags | set) & ~cleared & ~MapTileFlags.NOT_VISIBLE_OR_EXPLORED | flags & MapTileFlags.NOT_VISIBLE_OR_EXPLORED;

        if (overlays[tileIndex] != 0) {
            overlays[tileIndex] = tileIdentifier & WORD_MASK;
            replaced            = replaced & ~CREEP_TERRAIN_FLAGS | flags & CREEP_TERRAIN_FLAGS;
        } else {
            this.tileIdentifiers[tileIndex] = tileIdentifier & WORD_MASK;
        }

        this.tileFlags[tileIndex] = replaced;
    }
}
