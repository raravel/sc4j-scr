package org.fogofwarlabs.sc4j.scr.game.creep;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitGroupFlags;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.RuntimeRandom;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.MapTileFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitQueries;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.fogofwarlabs.sc4j.scr.game.visibility.LocalVision;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Executes initial and dynamic creep spread, provider bookkeeping and tile removal.
 * <p>
 * An added tile draws its graphics variant from the game's C runtime generator ({@link Game#getRuntimeRandom}), which the
 * game's other C runtime users share.
 * <p>
 * Receding tiles are recorded by the world's {@link CreepRecession}. A tile has creep when its identifier belongs to the
 * CV5 group of {@link TerrainTileset#CREEP_TILE}. A changed tile shows its new graphics at once only when the local view sees it
 * ({@link LocalVision#isTileHidden}).
 */
public final class CreepField {
    /**
     * The result of {@link #spread}: no tile was added.
     */
    public static final int SPREAD_NONE = 0;
    /**
     * The result of {@link #spread}: one tile was added.
     */
    public static final int SPREAD_ADDED = 1;
    /**
     * The result of {@link #spread}: no tile was added, but an occupied tile blocked growth.
     */
    public static final int SPREAD_BLOCKED = 2;
    /**
     * The graphics-cache WORD flag of a tile whose edges must be redrawn.
     */
    public static final int GRAPHICS_REFRESH_FLAG = 0x8000;
    /**
     * The VF4 megatile index bits of a graphics-cache WORD, excluding its redraw flag.
     */
    public static final int GRAPHICS_MEGATILE_MASK = GRAPHICS_REFRESH_FLAG - 1;

    /**
     * The horizontal radius of the creep ellipse around a creep source, in pixels.
     */
    private static final int CREEP_RADIUS_X = 320;
    /**
     * The vertical radius of the creep ellipse around a creep source, in pixels.
     */
    private static final int CREEP_RADIUS_Y = 200;
    /**
     * The ellipse test weights: {@code (CREEP_RADIUS_Y / 40)^2} for x and {@code (CREEP_RADIUS_X / 40)^2} for y.
     */
    private static final int CREEP_WEIGHT_X = 25;
    private static final int CREEP_WEIGHT_Y = 64;
    /**
     * A point {@code (x, y)} from the source is outside the ellipse when {@code x*x*25 + y*y*64} exceeds this.
     */
    private static final int CREEP_ELLIPSE = CREEP_RADIUS_X * CREEP_RADIUS_X * CREEP_WEIGHT_X;
    /**
     * The ellipse test weights of the spread scan: {@code 100} for x and {@code 256} for y.
     */
    private static final int SPREAD_WEIGHT_X = 100;
    private static final int SPREAD_WEIGHT_Y = 256;
    /**
     * A point of the spread scan is outside the ellipse when {@code x*x*100 + y*y*256} exceeds this.
     */
    private static final int SPREAD_ELLIPSE = SPREAD_WEIGHT_X * CREEP_RADIUS_X * CREEP_RADIUS_X;
    /**
     * Half a tile: the offset from a tile's corner to its center.
     */
    private static final int HALF_TILE = TerrainTileset.TILE_SIZE / 2;
    /**
     * The creep tile variants: {@code CREEP_TILE + 0..12}.
     */
    private static final int CREEP_VARIANTS = 13;
    /**
     * The candidate buckets of the creep spread: tiles by their creep neighbor count {@code 1..8}.
     */
    private static final int CANDIDATE_BUCKETS = 8;
    /**
     * The candidate tiles one bucket holds.
     */
    private static final int BUCKET_CAPACITY = 240;
    /**
     * The random source of the spread's candidate selection.
     */
    private static final int SPREAD_RANDOM_SOURCE = 26;
    /**
     * The low bits the spread's candidate selection drops from its random value.
     */
    private static final int SPREAD_RANDOM_SHIFT = 4;
    /**
     * The extra columns on each side of a footprint with rounded corners.
     */
    private static final int ROUNDED_EXTRA_COLUMNS = 2;
    /**
     * The frames before a dead creep provider retries its recession.
     */
    private static final int REMOVAL_RETRY_FRAMES = 3;

    /**
     * The pixels left of a tile's left edge the sustaining-building query reaches.
     */
    private static final int SUSTAIN_QUERY_LEFT = 0x270;
    /**
     * The pixels above a tile's top edge the sustaining-building query reaches.
     */
    private static final int SUSTAIN_QUERY_TOP = 0x180;
    /**
     * The pixels right of a tile's left edge the sustaining-building query reaches.
     */
    private static final int SUSTAIN_QUERY_RIGHT = 0x290;
    /**
     * The pixels below a tile's top edge the sustaining-building query reaches.
     */
    private static final int SUSTAIN_QUERY_BOTTOM = 0x1A0;

    /**
     * The index of the left tile column in the bounds array.
     */
    private static final int BOUNDS_LEFT = 0;
    /**
     * The index of the top tile row in the bounds array.
     */
    private static final int BOUNDS_TOP = 1;
    /**
     * The index of the right tile column in the bounds array.
     */
    private static final int BOUNDS_RIGHT = 2;
    /**
     * The index of the bottom tile row in the bounds array.
     */
    private static final int BOUNDS_BOTTOM = 3;
    /**
     * The size of the bounds array.
     */
    private static final int BOUNDS_SIZE = 4;

    /**
     * The percentage of added tiles that get one of the rare variants.
     */
    private static final int RARE_VARIANT_PERCENT = 4;
    /**
     * The scale of a percentage.
     */
    private static final int PERCENT = 100;
    /**
     * The first rare variant.
     */
    private static final int FIRST_RARE_VARIANT = 6;
    /**
     * The number of rare variants.
     */
    private static final int RARE_VARIANTS = 7;
    /**
     * The number of common variants, which come first.
     */
    private static final int COMMON_VARIANTS = 6;

    /**
     * The steps to the eight neighbors of a tile, the last step ({@code 8}) the tile itself.
     */
    private static final int @NotNull [] HORIZONTAL_STEPS = {1, 0, -1, 1, -1, 1, 0, -1, 0};
    private static final int @NotNull [] VERTICAL_STEPS = {1, 1, 1, 0, 0, -1, -1, -1, 0};
    /**
     * The neighbor steps without the tile itself.
     */
    private static final int NEIGHBOR_STEPS = 8;
    /**
     * The neighbor steps including the tile itself.
     */
    private static final int ALL_STEPS = 9;

    /**
     * The neighbor masks of the edge table: one bit per step of {@link #HORIZONTAL_STEPS}.
     */
    private static final int NEIGHBOR_MASKS = 1 << ALL_STEPS;
    /**
     * The neighbor bits of the steps (bottom right, bottom, bottom left, right, left, top right, top, top left, self).
     */
    private static final int NEIGHBOR_BOTTOM_RIGHT = 1;
    private static final int NEIGHBOR_BOTTOM = 1 << 1;
    private static final int NEIGHBOR_BOTTOM_LEFT = 1 << 2;
    private static final int NEIGHBOR_RIGHT = 1 << 3;
    private static final int NEIGHBOR_LEFT = 1 << 4;
    private static final int NEIGHBOR_TOP_RIGHT = 1 << 5;
    private static final int NEIGHBOR_TOP = 1 << 6;
    private static final int NEIGHBOR_TOP_LEFT = 1 << 7;
    private static final int NEIGHBOR_SELF = 1 << NEIGHBOR_STEPS;

    /**
     * The edge-mask corner set by the left neighbor or the top-left and top neighbor pair.
     */
    private static final int EDGE_TOP_LEFT = 1;
    /**
     * The edge-mask bit set by the top neighbor.
     */
    private static final int EDGE_TOP = 1 << 1;
    /**
     * The edge-mask corner set by the right neighbor or the top and top-right neighbor pair.
     */
    private static final int EDGE_TOP_RIGHT = 1 << 2;
    /**
     * The edge-mask corner set by the left neighbor or the bottom-left and bottom neighbor pair.
     */
    private static final int EDGE_BOTTOM_LEFT = 1 << 3;
    /**
     * The edge-mask bit set by the bottom neighbor.
     */
    private static final int EDGE_BOTTOM = 1 << 4;
    /**
     * The edge-mask corner set by the right neighbor or the bottom and bottom-right neighbor pair.
     */
    private static final int EDGE_BOTTOM_RIGHT = 1 << 5;
    /**
     * The edge-mask bit set when either opposite-corner pair is present.
     */
    private static final int EDGE_OPPOSITE_CORNERS = 1 << 6;
    /**
     * The edge masks the neighbor masks reduce to: every combination of the edge bits up to {@link #EDGE_OPPOSITE_CORNERS}.
     */
    private static final int EDGE_MASKS = EDGE_OPPOSITE_CORNERS << 1;

    /**
     * The edge identifiers of the neighbor masks of the eight neighbors.
     */
    private static final byte @NotNull [] EDGE_IDENTIFIERS = new byte[1 << NEIGHBOR_STEPS];
    /**
     * The masks that read a stored BYTE and a graphics-cache WORD unsigned.
     */
    private static final int BYTE_MASK = 0xFF;
    private static final int WORD_MASK = 0xFFFF;
    /**
     * The divisor that centers a footprint on its building position.
     */
    private static final int FOOTPRINT_CENTER_DIVISOR = 2;
    /**
     * The multiplier of the state hash.
     */
    private static final int HASH_MULTIPLIER = 31;

    static {
        byte[] masks = new byte[NEIGHBOR_MASKS];
        boolean[] used = new boolean[EDGE_MASKS];
        byte[] identifiers = new byte[EDGE_MASKS];

        // The edge mask bits set by each neighbor or neighbor pair.
        for (int neighborMask = 0; neighborMask < masks.length; neighborMask++) {
            int mask = 0;

            if ((neighborMask & NEIGHBOR_SELF) != 0) {
                mask = EDGE_MASKS - 1;
            } else {
                if ((neighborMask & NEIGHBOR_LEFT) != 0) {
                    mask |= EDGE_TOP_LEFT | EDGE_BOTTOM_LEFT;
                }

                if ((neighborMask & NEIGHBOR_RIGHT) != 0) {
                    mask |= EDGE_TOP_RIGHT | EDGE_BOTTOM_RIGHT;
                }

                if ((neighborMask & NEIGHBOR_TOP) != 0) {
                    mask |= EDGE_TOP;
                }

                if ((neighborMask & NEIGHBOR_BOTTOM) != 0) {
                    mask |= EDGE_BOTTOM;
                }

                if ((neighborMask & (NEIGHBOR_TOP_LEFT | NEIGHBOR_TOP)) == (NEIGHBOR_TOP_LEFT | NEIGHBOR_TOP)) {
                    mask |= EDGE_TOP_LEFT;
                }

                if ((neighborMask & (NEIGHBOR_TOP | NEIGHBOR_TOP_RIGHT)) == (NEIGHBOR_TOP | NEIGHBOR_TOP_RIGHT)) {
                    mask |= EDGE_TOP_RIGHT;
                }

                if ((neighborMask & (NEIGHBOR_BOTTOM_LEFT | NEIGHBOR_BOTTOM)) == (NEIGHBOR_BOTTOM_LEFT | NEIGHBOR_BOTTOM)) {
                    mask |= EDGE_BOTTOM_LEFT;
                }

                if ((neighborMask & (NEIGHBOR_BOTTOM | NEIGHBOR_BOTTOM_RIGHT)) == (NEIGHBOR_BOTTOM | NEIGHBOR_BOTTOM_RIGHT)) {
                    mask |= EDGE_BOTTOM_RIGHT;
                }

                if ((mask & (EDGE_TOP_LEFT | EDGE_BOTTOM_RIGHT)) == (EDGE_TOP_LEFT | EDGE_BOTTOM_RIGHT) ||
                    (mask & (EDGE_TOP_RIGHT | EDGE_BOTTOM_LEFT)) == (EDGE_TOP_RIGHT | EDGE_BOTTOM_LEFT)) {
                    mask |= EDGE_OPPOSITE_CORNERS;
                }
            }

            masks[neighborMask] = (byte) mask;
            used[mask]          = true;
        }

        int nextIdentifier = 0;

        for (int mask = 0; mask < used.length; mask++) {
            if (used[mask]) {
                identifiers[mask] = (byte) nextIdentifier++;
            }
        }

        for (int neighborMask = 0; neighborMask < EDGE_IDENTIFIERS.length; neighborMask++) {
            EDGE_IDENTIFIERS[neighborMask] = identifiers[masks[neighborMask] & BYTE_MASK];
        }
    }

    /**
     * Checks whether a unit type spreads creep: a completed Hatchery or Creep Colony, a Lair, Hive, Spore Colony or Sunken Colony.
     *
     * @param unitType  the unit type
     * @param completed whether it is completed
     * @return {@code true} if it spreads creep, {@code false} otherwise
     */
    public static boolean spreadsCreep(int unitType, boolean completed) {
        return (unitType == ScrUnitIds.ZERG_HATCHERY || unitType == ScrUnitIds.ZERG_CREEP_COLONY) && completed ||
               unitType == ScrUnitIds.ZERG_LAIR ||
               unitType == ScrUnitIds.ZERG_HIVE ||
               unitType == ScrUnitIds.ZERG_SPORE_COLONY ||
               unitType == ScrUnitIds.ZERG_SUNKEN_COLONY;
    }

    private final @NotNull GameMap map;
    private final @NotNull TerrainTileset terrain;

    private final int @NotNull [] graphicsCache;
    private final byte @NotNull [] edges;
    private final int @NotNull [] creepFlags;
    private final int @NotNull [] candidates;
    private final int @NotNull [] candidateCounts;
    private final int @NotNull [] bounds;
    private final int @NotNull [] restoredTile;

    private boolean initializationActive;
    private boolean graphicsInvalidationPending;
    /**
     * The frames until a dead creep provider retries its recession; the completed scan of {@link #removeProvider} or
     * {@link #retryProviderRemoval} clears it.
     */
    private int removalRetryCountdown;

    /**
     * Creates a {@link CreepField} instance with zeroed edge bytes, retaining the supplied overlay and cache state.
     *
     * @param map           the native base, overlay and expanded terrain arrays
     * @param terrain       the installed terrain resources
     * @param graphicsCache the explicitly initialized graphics-cache WORD array
     * @throws IllegalArgumentException if overlays are absent or the cache extent differs from the map
     */
    public CreepField(@NotNull GameMap map, @NotNull TerrainTileset terrain, int @NotNull [] graphicsCache) {
        int count = map.getWidth() * map.getHeight();

        if (!map.hasOverlayTiles() || graphicsCache.length != count) {
            throw Exceptions.newIllegalArgumentException("Creep requires initialized overlay and graphics-cache arrays.");
        }

        this.map     = map;
        this.terrain = terrain;

        this.graphicsCache = graphicsCache.clone();

        for (int tileIndex = 0; tileIndex < this.graphicsCache.length; tileIndex++) {
            this.graphicsCache[tileIndex] &= WORD_MASK;
        }

        this.edges = new byte[count];

        int[] variants = new int[CREEP_VARIANTS];

        for (int variant = 0; variant < variants.length; variant++) {
            variants[variant] = TerrainTileset.CREEP_TILE + variant;
        }

        this.creepFlags      = terrain.expandTileFlags(variants);
        this.candidates      = new int[CANDIDATE_BUCKETS * BUCKET_CAPACITY];
        this.candidateCounts = new int[CANDIDATE_BUCKETS];
        this.bounds          = new int[BOUNDS_SIZE];
        this.restoredTile    = new int[1];

        this.initializationActive        = true;
        this.graphicsInvalidationPending = false;
        this.removalRetryCountdown       = 0;
    }

    /**
     * @param tileIndex the native tile index
     * @return the stored topology BYTE
     */
    public int getEdgeIdentifier(int tileIndex) {
        return Byte.toUnsignedInt(this.edges[tileIndex]);
    }

    /**
     * Reads the live graphics-cache prefix before consulting retained saved tail storage.
     *
     * @param tileIndex the native tile index
     * @return the live or retained unsigned graphics-cache WORD
     */
    public int getGraphicsCache(int tileIndex) {
        if (tileIndex < this.graphicsCache.length) {
            return this.graphicsCache[tileIndex];
        }

        return this.map.getSavedGraphicsCache(tileIndex);
    }

    /**
     * @return {@code true} if graphics-cache or edge changes have marked an invalidation pending, {@code false} otherwise
     */
    public boolean hasPendingGraphicsInvalidation() {
        return this.graphicsInvalidationPending;
    }

    /**
     * Sets the visible terrain graphics identifier of a graphics-cache entry.
     *
     * @param tileIndex          the row-major cache position
     * @param megatileIdentifier the visible terrain graphics identifier
     */
    public void updateGraphicsCache(int tileIndex, int megatileIdentifier) {
        this.graphicsCache[tileIndex] = megatileIdentifier;
    }

    /**
     * Restores a graphics-cache WORD a saved game keeps.
     *
     * @param tileIndex the row-major cache position
     * @param value     the saved WORD
     */
    public void restoreGraphicsCache(int tileIndex, int value) {
        this.graphicsCache[tileIndex] = value & WORD_MASK;
    }

    /**
     * Ends the initial phase before dynamic creep recession can run.
     */
    public void finishInitialization() {
        this.initializationActive = false;
    }

    /**
     * Fills creep under a footprint during initialization and grows it until the spread adds no tile.
     *
     * @param game      the game supplying the random generator and the unit types
     * @param unitType  the original unit type WORD
     * @param positionX the original pixel coordinate DWORD
     * @param positionY the original pixel coordinate DWORD
     * @throws UnsupportedOperationException if initialization has ended or the game has another map
     */
    public void fill(@NotNull Game game, int unitType, int positionX, int positionY) {
        if (!this.initializationActive || game.getMap() != this.map) {
            throw Exceptions.newUnsupportedOperationException("Initial creep requires its active map context.");
        }

        this.fillFootprint(game, unitType, positionX, positionY);
    }

    /**
     * Fills creep under a footprint during the game, as {@link #addBuilding} does for a creep-requiring building that does not spread creep.
     *
     * @param game      the game supplying the random generator and the unit types
     * @param unitType  the original unit type WORD
     * @param positionX the original pixel coordinate DWORD
     * @param positionY the original pixel coordinate DWORD
     * @throws IllegalArgumentException if the game has another map
     */
    public void fillMoved(@NotNull Game game, int unitType, int positionX, int positionY) {
        if (game.getMap() != this.map) {
            throw Exceptions.newIllegalArgumentException("Creep fill requires its retained map.");
        }

        this.fillFootprint(game, unitType, positionX, positionY);
    }

    /**
     * Executes one spread candidate scan and tile selection.
     *
     * @param game      the game supplying the random generator and the unit types
     * @param unitType  the original unit type WORD
     * @param positionX the original pixel coordinate DWORD
     * @param positionY the original pixel coordinate DWORD
     * @return {@code true} if one tile was added, {@code false} otherwise
     */
    public boolean growOnce(@NotNull Game game, int unitType, int positionX, int positionY) {
        return this.spread(game, unitType, positionX, positionY) == SPREAD_ADDED;
    }

    /**
     * Scans and selects one creep spread candidate, returning a spread result.
     * <p>
     * A candidate tile has no creep, can have creep, is unoccupied, has at least one creep neighbor and, for
     * creep-spreading types, lies inside the elliptical range. The candidate is chosen from the fullest neighbor bucket
     * with the game random generator. If no tile is added, the result is {@link #SPREAD_BLOCKED} when a non-creep tile
     * passes the terrain check but is occupied; this check precedes neighbor and ellipse filtering.
     *
     * @param game      the game supplying the random generator and the unit types
     * @param unitType  the original unit type WORD
     * @param positionX the original pixel coordinate DWORD
     * @param positionY the original pixel coordinate DWORD
     * @return {@link #SPREAD_ADDED} if a tile was added, {@link #SPREAD_BLOCKED} if none was added but an occupied tile blocked growth,
     * {@link #SPREAD_NONE} otherwise
     * @throws IllegalArgumentException      if the game has another map
     * @throws NullPointerException          if the world was built without game data
     * @throws UnsupportedOperationException if the scan or a candidate bucket exceeds the represented storage
     */
    public int spread(@NotNull Game game, int unitType, int positionX, int positionY) {
        if (game.getMap() != this.map) {
            throw Exceptions.newIllegalArgumentException("Creep growth requires its retained map.");
        }

        ScrUnitTypeView units = game.requireGameData().getUnitTypes();
        int width = (short) units.getPlacementWidth(unitType);
        int height = (short) units.getPlacementHeight(unitType);
        int left;
        int upper;
        int right;
        int lower;
        boolean extended = spreadsCreep(unitType, true);

        if (extended) {
            left  = (positionX - CREEP_RADIUS_X) / TerrainTileset.TILE_SIZE;
            upper = (positionY - CREEP_RADIUS_Y) / TerrainTileset.TILE_SIZE;
            right = (positionX + CREEP_RADIUS_X) / TerrainTileset.TILE_SIZE;
            lower = (positionY + CREEP_RADIUS_Y) / TerrainTileset.TILE_SIZE;
        } else {
            left  = positionX / TerrainTileset.TILE_SIZE - (width / TerrainTileset.TILE_SIZE) / FOOTPRINT_CENTER_DIVISOR;
            upper = positionY / TerrainTileset.TILE_SIZE - (height / TerrainTileset.TILE_SIZE) / FOOTPRINT_CENTER_DIVISOR;
            right = left + width / TerrainTileset.TILE_SIZE - 1;
            lower = upper + height / TerrainTileset.TILE_SIZE - 1;
        }

        if (left < 0) {
            left = 0;
        } else if (right >= this.map.getWidth()) {
            right = this.map.getWidth() - 1;
        }

        if (upper < 0) {
            upper = 0;
        } else if (lower >= this.map.getHeight()) {
            lower = this.map.getHeight() - 1;
        }

        if (right >= this.map.getWidth() || lower >= this.map.getHeight()) {
            throw Exceptions.newUnsupportedOperationException("The creep scan references unrepresented tile storage.");
        }

        int[] candidates = this.candidates;
        int[] counts = this.candidateCounts;
        int result = SPREAD_NONE;

        Arrays.fill(counts, 0);

        for (int vertical = upper; vertical <= lower; vertical++) {
            for (int horizontal = left; horizontal <= right; horizontal++) {
                int tileIndex = vertical * this.map.getWidth() + horizontal;

                if (TerrainTileset.isCreepTile(this.map.getTileIdentifier(tileIndex)) || this.isBlocked(horizontal, vertical)) {
                    continue;
                }

                if ((this.map.getTileFlags(tileIndex) & MapTileFlags.OCCUPIED) != 0) {
                    result = SPREAD_BLOCKED;

                    continue;
                }

                int neighbors = 0;

                for (int step = 0; step < NEIGHBOR_STEPS; step++) {
                    int neighborX = horizontal + HORIZONTAL_STEPS[step];
                    int neighborY = vertical + VERTICAL_STEPS[step];

                    if (neighborX >= 0 && neighborX < this.map.getWidth() && neighborY >= 0 && neighborY < this.map.getHeight() &&
                        TerrainTileset.isCreepTile(this.map.getTileIdentifier(neighborX, neighborY))) {
                        neighbors++;
                    }
                }

                if (neighbors == 0) {
                    continue;
                }

                int differenceX = horizontal * TerrainTileset.TILE_SIZE + HALF_TILE - positionX;
                int differenceY = vertical * TerrainTileset.TILE_SIZE + HALF_TILE - positionY;

                if (extended && SPREAD_WEIGHT_X * differenceX * differenceX + SPREAD_WEIGHT_Y * differenceY * differenceY > SPREAD_ELLIPSE) {
                    continue;
                }

                int bucket = neighbors - 1;

                if (counts[bucket] == BUCKET_CAPACITY) {
                    throw Exceptions.newUnsupportedOperationException("The creep candidate bucket exceeds represented storage.");
                }

                candidates[bucket * BUCKET_CAPACITY + counts[bucket]++] = tileIndex;
            }
        }

        int bucket = CANDIDATE_BUCKETS - 1;

        while (bucket >= 0 && counts[bucket] == 0) {
            bucket--;
        }

        if (bucket < 0) {
            return result;
        }

        int selected = (game.getRandom().next(SPREAD_RANDOM_SOURCE) >>> SPREAD_RANDOM_SHIFT) % counts[bucket];
        int tileIndex = candidates[bucket * BUCKET_CAPACITY + selected];

        this.addTile(game, tileIndex % this.map.getWidth(), tileIndex / this.map.getWidth());

        return SPREAD_ADDED;
    }

    /**
     * Adds creep under a finished building footprint. Creep-requiring buildings that do not spread creep leave the terrain unchanged.
     * Flag {@link UnitTypeFlags#FLAG_80000000} widens the area by two columns and one row and excludes its four corners.
     *
     * @param game      the game whose map and tile occupancy are updated
     * @param unitType  the finished building type
     * @param positionX the footprint center X
     * @param positionY the footprint center Y
     * @throws IllegalArgumentException if the game has another map
     * @throws NullPointerException     if the world was built without game data
     */
    public void spreadUnderBuilding(@NotNull Game game, int unitType, int positionX, int positionY) {
        if (game.getMap() != this.map) {
            throw Exceptions.newIllegalArgumentException("Building creep requires the retained map.");
        }

        ScrUnitTypeView units = game.requireGameData().getUnitTypes();
        int abilities = units.getFlags(unitType);

        if (!spreadsCreep(unitType, true) && (abilities & UnitTypeFlags.REQUIRES_CREEP) != 0) {
            return;
        }

        game.getUnitPositioning().setFootprintOccupied(unitType, positionX, positionY, false);

        int width = units.getPlacementWidth(unitType) / TerrainTileset.TILE_SIZE;
        int height = units.getPlacementHeight(unitType) / TerrainTileset.TILE_SIZE;
        int left = positionX / TerrainTileset.TILE_SIZE - width / FOOTPRINT_CENTER_DIVISOR;
        int upper = positionY / TerrainTileset.TILE_SIZE - height / FOOTPRINT_CENTER_DIVISOR;
        int right = left + width;
        int lower = upper + height;
        boolean roundedCorners = (abilities & UnitTypeFlags.FLAG_80000000) != 0;

        if (roundedCorners) {
            left -= ROUNDED_EXTRA_COLUMNS;
            upper -= 1;
            right += ROUNDED_EXTRA_COLUMNS;
            lower += 1;
        }

        for (int vertical = upper; vertical < lower; vertical++) {
            if (vertical < 0 || vertical >= this.map.getHeight()) {
                continue;
            }

            for (int horizontal = left; horizontal < right; horizontal++) {
                if (horizontal < 0 || horizontal >= this.map.getWidth()) {
                    continue;
                }

                if (roundedCorners && (horizontal == left || horizontal == right - 1) && (vertical == upper || vertical == lower - 1)) {
                    continue;
                }

                if (this.isBlocked(horizontal, vertical) || (this.map.getTileFlags(horizontal, vertical) & MapTileFlags.OCCUPIED) != 0) {
                    continue;
                }

                this.addTile(game, horizontal, vertical);
            }
        }

        game.getUnitPositioning().setFootprintOccupied(unitType, positionX, positionY, true);
    }

    /**
     * Stops recession inside a Zerg building's creep bounds. Receding tiles without a record are skipped.
     *
     * @param game      the game owning the map and recession records
     * @param unitType  the building type
     * @param positionX the building center X
     * @param positionY the building center Y
     * @param completed whether the building is completed
     * @throws IllegalArgumentException      if the game has another map
     * @throws UnsupportedOperationException if a receding tile exists without recession storage
     */
    public void addProvider(@NotNull Game game, int unitType, int positionX, int positionY, boolean completed) {
        if (game.getMap() != this.map) {
            throw Exceptions.newIllegalArgumentException("Creep providers require the retained map.");
        }

        boolean spreads = spreadsCreep(unitType, completed);
        int[] bounds = this.computeMaximumBounds(game, unitType, positionX, positionY, completed);

        for (int vertical = bounds[BOUNDS_TOP]; vertical <= bounds[BOUNDS_BOTTOM]; vertical++) {
            int differenceY = vertical * TerrainTileset.TILE_SIZE - positionY + HALF_TILE;

            for (int horizontal = bounds[BOUNDS_LEFT]; horizontal <= bounds[BOUNDS_RIGHT]; horizontal++) {
                if ((this.map.getTileFlags(horizontal, vertical) & MapTileFlags.CREEP_RECEDING) == 0) {
                    continue;
                }

                int differenceX = horizontal * TerrainTileset.TILE_SIZE - positionX + HALF_TILE;

                if (spreads && differenceX * differenceX * CREEP_WEIGHT_X + differenceY * differenceY * CREEP_WEIGHT_Y > CREEP_ELLIPSE) {
                    continue;
                }

                CreepRecession recession = game.getCreepRecession();

                if (recession == null) {
                    throw Exceptions.newUnsupportedOperationException("Creep provider restoration requires the receding creep records.");
                }

                recession.restore(game, horizontal, vertical);
            }
        }
    }

    /**
     * Adds the creep of a building at its sprite position: a type that spreads creep when completed spreads creep under its
     * footprint ({@link #spreadUnderBuilding}), another creep-requiring type fills its footprint ({@link #fillMoved}), and
     * either then stops the recession inside its creep bounds with its current completion ({@link #addProvider}). Other
     * types leave the creep unchanged.
     *
     * @param game     the game whose map, unit types and recession records are updated
     * @param building the building, or another unit whose type leaves the creep unchanged
     * @return {@code true} if the building's type spreads creep when completed, {@code false} otherwise
     * @throws IllegalStateException if a creep building has no sprite
     * @throws NullPointerException  if the world was built without game data
     */
    public boolean addBuilding(@NotNull Game game, @NotNull Unit building) {
        int type = building.getUnitId();
        boolean spreads = spreadsCreep(type, true);

        if (!spreads && (game.requireGameData().getUnitTypes().getFlags(type) & UnitTypeFlags.REQUIRES_CREEP) == 0) {
            return false;
        }

        Sprite sprite = building.requireSprite();

        if (spreads) {
            this.spreadUnderBuilding(game, type, sprite.getX(), sprite.getY());
        } else {
            this.fillMoved(game, type, sprite.getX(), sprite.getY());
        }

        this.addProvider(game, type, (short) sprite.getX(), (short) sprite.getY(), (building.getStatus() & UnitStatusFlags.COMPLETED) != 0);

        return spreads;
    }

    /**
     * Starts recession of unsustained creep inside a removed Zerg building's creep bounds.
     * <p>
     * Creep tiles that are receding, or occupied unless the type is an Extractor, are skipped. A tile still sustained by
     * another grounded Zerg building keeps its creep. The scan stops at the first tile the free list cannot take, and a
     * completed scan clears the retry countdown; tiles already receding are skipped, so a retry continues where it stopped.
     *
     * @param game      the game owning the map, unit finder and recession records
     * @param unitType  the removed building type
     * @param positionX the building position X
     * @param positionY the building position Y
     * @param completed whether the building was completed
     * @return {@code true} if the recession scan completed, {@code false} otherwise
     * @throws IllegalArgumentException      if the game has another map
     * @throws UnsupportedOperationException if recession storage is unavailable
     */
    public boolean removeProvider(@NotNull Game game, int unitType, int positionX, int positionY, boolean completed) {
        if (game.getMap() != this.map) {
            throw Exceptions.newIllegalArgumentException("Creep providers require the retained map.");
        }

        CreepRecession recession = game.getCreepRecession();

        if (recession == null) {
            throw Exceptions.newUnsupportedOperationException("Creep provider removal requires the receding creep records.");
        }

        int skippedFlags = unitType == ScrUnitIds.ZERG_EXTRACTOR ? MapTileFlags.CREEP_RECEDING : MapTileFlags.CREEP_RECEDING | MapTileFlags.OCCUPIED;
        boolean spreads = spreadsCreep(unitType, completed);
        int[] bounds = this.computeMaximumBounds(game, unitType, positionX, positionY, completed);
        int left = bounds[BOUNDS_LEFT];
        int upper = bounds[BOUNDS_TOP];
        int right = bounds[BOUNDS_RIGHT];
        int lower = bounds[BOUNDS_BOTTOM];

        for (int vertical = upper; vertical <= lower; vertical++) {
            int differenceY = vertical * TerrainTileset.TILE_SIZE - positionY + HALF_TILE;

            for (int horizontal = left; horizontal <= right; horizontal++) {
                if (!TerrainTileset.isCreepTile(this.map.getTileIdentifier(horizontal, vertical))) {
                    continue;
                }

                if ((this.map.getTileFlags(horizontal, vertical) & skippedFlags) != 0) {
                    continue;
                }

                int differenceX = horizontal * TerrainTileset.TILE_SIZE - positionX + HALF_TILE;

                if (spreads && differenceX * differenceX * CREEP_WEIGHT_X + differenceY * differenceY * CREEP_WEIGHT_Y > CREEP_ELLIPSE) {
                    continue;
                }

                if (!this.isSustained(game, horizontal, vertical) && !recession.markReceding(game, horizontal, vertical)) {
                    return false;
                }
            }
        }

        this.removalRetryCountdown = 0;

        return true;
    }

    /**
     * Starts the recession of one creep tile, as the scan of {@link #removeProvider} does for each tile of a removed building: a creep tile that
     * is not receding, not occupied and not sustained by a grounded Zerg building takes a free recession record.
     *
     * @param game       the game owning the map, unit finder and recession records
     * @param horizontal the tile column
     * @param vertical   the tile row
     * @return {@code true} if the tile took a recession record, {@code false} if it was skipped or no record was free
     * @throws IllegalArgumentException      if the game has another map
     * @throws UnsupportedOperationException if recession storage is unavailable
     */
    public boolean recedeTile(@NotNull Game game, int horizontal, int vertical) {
        if (game.getMap() != this.map) {
            throw Exceptions.newIllegalArgumentException("Creep recession requires the retained map.");
        }

        CreepRecession recession = game.getCreepRecession();

        if (recession == null) {
            throw Exceptions.newUnsupportedOperationException("Creep recession requires the receding creep records.");
        }

        if (!TerrainTileset.isCreepTile(this.map.getTileIdentifier(horizontal, vertical)) ||
            (this.map.getTileFlags(horizontal, vertical) & (MapTileFlags.CREEP_RECEDING | MapTileFlags.OCCUPIED)) != 0 ||
            this.isSustained(game, horizontal, vertical)) {
            return false;
        }

        return recession.markReceding(game, horizontal, vertical);
    }

    /**
     * Retries the removal of a dead creep provider whose removal ran out of recession records. While the shared countdown
     * runs it only counts down; otherwise it restarts the countdown and repeats the scan of {@link #removeProvider}.
     *
     * @param game      the game owning the map and recession records
     * @param unitType  the dead building type
     * @param positionX the building position X
     * @param positionY the building position Y
     * @param completed whether the building was completed
     * @return {@code true} if the scan completed, {@code false} otherwise
     */
    public boolean retryProviderRemoval(@NotNull Game game, int unitType, int positionX, int positionY, boolean completed) {
        if (this.removalRetryCountdown != 0) {
            this.removalRetryCountdown--;

            return false;
        }

        this.removalRetryCountdown = REMOVAL_RETRY_FRAMES;

        return this.removeProvider(game, unitType, positionX, positionY, completed);
    }

    /**
     * Removes creep immediately around a destroyed Extractor.
     * <p>
     * Every creep tile inside the type's creep bounds (and the ellipse for creep-spreading types) is removed without
     * recession; sustaining buildings are not consulted.
     *
     * @param game      the game owning the map and recession records
     * @param unitType  the Extractor type
     * @param positionX the sprite position X
     * @param positionY the sprite position Y
     * @param completed whether the Extractor was completed
     * @throws IllegalArgumentException if the game has another map
     */
    public void removeExtractorCreep(@NotNull Game game, int unitType, int positionX, int positionY, boolean completed) {
        if (game.getMap() != this.map) {
            throw Exceptions.newIllegalArgumentException("Extractor creep removal requires the retained map.");
        }

        boolean spreads = spreadsCreep(unitType, completed);
        int[] bounds = this.computeMaximumBounds(game, unitType, positionX, positionY, completed);
        int left = bounds[BOUNDS_LEFT];
        int upper = bounds[BOUNDS_TOP];
        int right = bounds[BOUNDS_RIGHT];
        int lower = bounds[BOUNDS_BOTTOM];

        for (int vertical = upper; vertical <= lower; vertical++) {
            int differenceY = vertical * TerrainTileset.TILE_SIZE - positionY + HALF_TILE;

            for (int horizontal = left; horizontal <= right; horizontal++) {
                if (!TerrainTileset.isCreepTile(this.map.getTileIdentifier(horizontal, vertical))) {
                    continue;
                }

                int differenceX = horizontal * TerrainTileset.TILE_SIZE - positionX + HALF_TILE;

                if (spreads && differenceX * differenceX * CREEP_WEIGHT_X + differenceY * differenceY * CREEP_WEIGHT_Y > CREEP_ELLIPSE) {
                    continue;
                }

                this.removeTile(game, horizontal, vertical);
            }
        }
    }

    /**
     * Removes creep from one tile using its retained terrain.
     * <p>
     * The retained terrain replaces the creep identifier; a tile without retained terrain keeps its identifier. The
     * restored tile's walkability and creep terrain properties replace the creep properties, the visible graphics follow
     * the same visibility rule as creep addition, receding neighbors are reclassified and graphics edges are refreshed.
     *
     * @param game       the game owning the recession records
     * @param horizontal the tile column
     * @param vertical   the tile row
     */
    public void removeTile(@NotNull Game game, int horizontal, int vertical) {
        int tileIndex = vertical * this.map.getWidth() + horizontal;

        if (this.map.restoreTerrainUnderCreep(tileIndex)) {
            int restored = this.map.getTileIdentifier(tileIndex);
            LocalVision vision = game.getLocalVision();
            boolean hidden = vision.isTileHidden(this.map.getTileFlags(tileIndex));

            if (!hidden) {
                this.graphicsCache[tileIndex]    = this.terrain.getMegatileIdentifier(restored);
                this.graphicsInvalidationPending = true;
            }

            this.restoredTile[0] = restored;
            this.map.applyCreepTerrainFlags(tileIndex, this.terrain.expandTileFlags(this.restoredTile)[0], false);

            if (!hidden) {
                this.map.refreshCreepGraphicsFlag(tileIndex);
            }

            this.updateRecedingNeighbors(game, horizontal, vertical);

            if (vision.isTileHidden(this.map.getTileFlags(tileIndex))) {
                return;
            }
        }

        this.refreshGraphicsEdges(horizontal, vertical);
    }

    /**
     * Updates neighboring graphics edges around a changed tile.
     *
     * @param horizontal the changed tile column
     * @param vertical   the changed tile row
     */
    public void refreshGraphicsEdges(int horizontal, int vertical) {
        for (int step = 0; step < ALL_STEPS; step++) {
            int neighborX = horizontal + HORIZONTAL_STEPS[step];
            int neighborY = vertical + VERTICAL_STEPS[step];

            if (neighborX < 0 || neighborX >= this.map.getWidth() || neighborY < 0 || neighborY >= this.map.getHeight()) {
                continue;
            }

            int neighborIndex = neighborY * this.map.getWidth() + neighborX;

            if ((this.map.getTileFlags(neighborIndex) & MapTileFlags.TEMPORARY_CREEP) != 0) {
                continue;
            }

            int neighborMask = 0;

            for (int neighborStep = 0; neighborStep < ALL_STEPS; neighborStep++) {
                int candidateX = neighborX + HORIZONTAL_STEPS[neighborStep];
                int candidateY = neighborY + VERTICAL_STEPS[neighborStep];

                if (candidateX >= 0 && candidateX < this.map.getWidth() && candidateY >= 0 && candidateY < this.map.getHeight() &&
                    (this.map.getTileFlags(candidateX, candidateY) & MapTileFlags.TEMPORARY_CREEP) != 0) {
                    neighborMask |= 1 << neighborStep;
                }
            }

            this.edges[neighborIndex]                                             = EDGE_IDENTIFIERS[neighborMask & BYTE_MASK];
            this.graphicsCache[neighborIndex] |= GRAPHICS_REFRESH_FLAG;
                                                 this.graphicsInvalidationPending = true;
        }
    }

    /**
     * Folds the retained edge and cache state into a state hash.
     *
     * @param hash the preceding state hash
     * @return the hash including retained edge and cache state
     */
    public int computeStateHash(int hash) {
        hash = HASH_MULTIPLIER * hash + this.removalRetryCountdown;
        hash = HASH_MULTIPLIER * hash + (this.graphicsInvalidationPending ? 1 : 0);
        hash = HASH_MULTIPLIER * hash + (this.initializationActive ? 1 : 0);

        for (byte edge : this.edges) {
            hash = HASH_MULTIPLIER * hash + Byte.toUnsignedInt(edge);
        }

        for (int cache : this.graphicsCache) {
            hash = HASH_MULTIPLIER * hash + cache;
        }

        return hash;
    }

    private void fillFootprint(@NotNull Game game, int unitType, int positionX, int positionY) {
        ScrUnitTypeView units = game.requireGameData().getUnitTypes();
        int width = (short) units.getPlacementWidth(unitType);
        int height = (short) units.getPlacementHeight(unitType);
        int left = positionX / TerrainTileset.TILE_SIZE - width / (FOOTPRINT_CENTER_DIVISOR * TerrainTileset.TILE_SIZE);
        int upper = positionY / TerrainTileset.TILE_SIZE - height / (FOOTPRINT_CENTER_DIVISOR * TerrainTileset.TILE_SIZE);
        int right = left + width / TerrainTileset.TILE_SIZE;
        int lower = upper + height / TerrainTileset.TILE_SIZE;

        if (left < 0 || upper < 0 || right > this.map.getWidth() || lower > this.map.getHeight()) {
            throw Exceptions.newUnsupportedOperationException("The creep footprint references unrepresented tile storage.");
        }

        for (int vertical = upper; vertical < lower; vertical++) {
            for (int horizontal = left; horizontal < right; horizontal++) {
                if (!this.isBlocked(horizontal, vertical)) {
                    this.addTile(game, horizontal, vertical);
                }
            }
        }

        while (this.growOnce(game, unitType, positionX, positionY)) {
            // Grows until the spread adds no tile.
        }
    }

    private boolean isSustained(@NotNull Game game, int horizontal, int vertical) {
        UnitSpatialIndex finder = game.getUnitSystem().getUnitSpatialIndex();
        UnitPool pool = game.getUnitSystem().getUnitPool();

        if (finder == null || pool == null) {
            throw Exceptions.newUnsupportedOperationException("Creep sustenance requires the unit finder.");
        }

        ScrUnitTypeView units = game.requireGameData().getUnitTypes();
        int centerX = horizontal * TerrainTileset.TILE_SIZE + HALF_TILE;
        int centerY = vertical * TerrainTileset.TILE_SIZE + HALF_TILE;
        // The box around the tile; a negative left or top is clamped to zero and only otherwise the right or bottom to the
        // map. The finder query tests the buildings found.
        int left = horizontal * TerrainTileset.TILE_SIZE - SUSTAIN_QUERY_LEFT;
        int top = vertical * TerrainTileset.TILE_SIZE - SUSTAIN_QUERY_TOP;
        int right = horizontal * TerrainTileset.TILE_SIZE + SUSTAIN_QUERY_RIGHT;
        int bottom = vertical * TerrainTileset.TILE_SIZE + SUSTAIN_QUERY_BOTTOM;

        if (left < 0) {
            left = 0;
        } else if (right >= this.map.getWidth() * TerrainTileset.TILE_SIZE) {
            right = this.map.getWidth() * TerrainTileset.TILE_SIZE - 1;
        }

        if (top < 0) {
            top = 0;
        } else if (bottom >= this.map.getHeight() * TerrainTileset.TILE_SIZE) {
            bottom = this.map.getHeight() * TerrainTileset.TILE_SIZE - 1;
        }

        int found = finder.collectPlacementCandidates(left, top, right, bottom);

        for (int foundIndex = 0; foundIndex < found; foundIndex++) {
            Unit unit = finder.getPlacementCandidate(foundIndex);
            int unitType = unit.getUnitId();

            if (!UnitQueries.isGroundedBuilding(unit) ||
                (units.getFlags(unitType) & UnitTypeFlags.BUILDING) == 0 ||
                (units.getGroupFlags(unitType) & UnitGroupFlags.ZERG) == 0) {
                continue;
            }

            Sprite sprite = unit.getSpriteOrNull();

            if (sprite == null) {
                throw Exceptions.newUnsupportedOperationException("Creep sustenance requires building sprites.");
            }

            int spriteX = (short) sprite.getX();
            int spriteY = (short) sprite.getY();
            int[] bounds = this.computeMaximumBounds(game, unitType, spriteX, spriteY, (unit.getStatus() & UnitStatusFlags.COMPLETED) != 0);

            if (horizontal < bounds[BOUNDS_LEFT] || vertical < bounds[BOUNDS_TOP] || horizontal > bounds[BOUNDS_RIGHT] ||
                vertical > bounds[BOUNDS_BOTTOM]) {
                continue;
            }

            int differenceX = centerX - spriteX;
            int differenceY = centerY - spriteY;

            if (differenceX * differenceX * CREEP_WEIGHT_X + differenceY * differenceY * CREEP_WEIGHT_Y > CREEP_ELLIPSE) {
                continue;
            }

            return true;
        }

        return false;
    }

    /**
     * Computes the provider's maximum creep bounds into a reused array.
     *
     * @param game      the game supplying the unit types
     * @param unitType  the provider type
     * @param positionX the provider position X
     * @param positionY the provider position Y
     * @param completed whether the provider is completed
     * @return the reused bounds array of tile columns and rows
     * @throws NullPointerException if the world was built without game data
     */
    private int @NotNull [] computeMaximumBounds(@NotNull Game game, int unitType, int positionX, int positionY, boolean completed) {
        ScrUnitTypeView units = game.requireGameData().getUnitTypes();
        int fromX;
        int fromY;
        int toX;
        int toY;

        if (spreadsCreep(unitType, completed)) {
            fromX = positionX - CREEP_RADIUS_X;
            fromY = positionY - CREEP_RADIUS_Y;
            toX   = positionX + CREEP_RADIUS_X;
            toY   = positionY + CREEP_RADIUS_Y;
        } else {
            fromX = (positionX / TerrainTileset.TILE_SIZE - units.getPlacementWidth(unitType) / TerrainTileset.TILE_SIZE / FOOTPRINT_CENTER_DIVISOR) *
                    TerrainTileset.TILE_SIZE;
            fromY = (positionY / TerrainTileset.TILE_SIZE - units.getPlacementHeight(unitType) / TerrainTileset.TILE_SIZE / FOOTPRINT_CENTER_DIVISOR) *
                    TerrainTileset.TILE_SIZE;
            toX   = (fromX / TerrainTileset.TILE_SIZE + units.getPlacementWidth(unitType) / TerrainTileset.TILE_SIZE - 1) * TerrainTileset.TILE_SIZE;
            toY   = (fromY / TerrainTileset.TILE_SIZE + units.getPlacementHeight(unitType) / TerrainTileset.TILE_SIZE - 1) * TerrainTileset.TILE_SIZE;
        }

        int[] bounds = this.bounds;

        bounds[BOUNDS_LEFT]   = fromX <= 0 ? 0 : fromX / TerrainTileset.TILE_SIZE;
        bounds[BOUNDS_TOP]    = fromY <= 0 ? 0 : fromY / TerrainTileset.TILE_SIZE;
        bounds[BOUNDS_RIGHT]  = toX >= this.map.getWidth() * TerrainTileset.TILE_SIZE ? this.map.getWidth() - 1 : toX / TerrainTileset.TILE_SIZE;
        bounds[BOUNDS_BOTTOM] = toY >= this.map.getHeight() * TerrainTileset.TILE_SIZE ? this.map.getHeight() - 1 : toY / TerrainTileset.TILE_SIZE;

        return bounds;
    }

    private void updateRecedingNeighbors(@NotNull Game game, int horizontal, int vertical) {
        CreepRecession recession = game.getCreepRecession();

        if (recession != null) {
            recession.updateNeighbors(game, horizontal, vertical);

            return;
        }

        for (int step = NEIGHBOR_STEPS - 1; step >= 0; step--) {
            int neighborX = horizontal + HORIZONTAL_STEPS[step];
            int neighborY = vertical + VERTICAL_STEPS[step];

            if (neighborX >= 0 && neighborX < this.map.getWidth() && neighborY >= 0 && neighborY < this.map.getHeight() &&
                TerrainTileset.isCreepTile(this.map.getTileIdentifier(neighborX, neighborY)) &&
                (this.map.getTileFlags(neighborX, neighborY) & MapTileFlags.CREEP_RECEDING) != 0) {
                throw Exceptions.newUnsupportedOperationException("Creep recession requires an adjacent queued recession record.");
            }
        }
    }

    private boolean isBlocked(int horizontal, int vertical) {
        if ((this.map.getTileFlags(horizontal, vertical) & (MapTileFlags.UNBUILDABLE | MapTileFlags.PARTIALLY_WALKABLE)) != 0) {
            return true;
        }

        return vertical + 1 != this.map.getHeight() && (this.map.getTileFlags(horizontal, vertical + 1) & MapTileFlags.UNBUILDABLE) != 0;
    }

    private void addTile(@NotNull Game game, int horizontal, int vertical) {
        int tileIndex = vertical * this.map.getWidth() + horizontal;

        this.map.retainTerrainUnderCreep(tileIndex);

        RuntimeRandom runtimeRandom = game.getRuntimeRandom();
        int first = runtimeRandom.draw();
        int second = runtimeRandom.draw();
        int variant = first % PERCENT < RARE_VARIANT_PERCENT ? FIRST_RARE_VARIANT + second % RARE_VARIANTS : second % COMMON_VARIANTS;

        this.map.setCreepVariant(tileIndex, variant);

        LocalVision vision = game.getLocalVision();
        boolean hidden = vision.isTileHidden(this.map.getTileFlags(tileIndex));

        if (!hidden) {
            this.graphicsCache[tileIndex]    = this.terrain.getMegatileIdentifier(TerrainTileset.CREEP_TILE + variant);
            this.graphicsInvalidationPending = true;
        }

        this.map.applyCreepTerrainFlags(tileIndex, this.creepFlags[variant], !hidden);
        this.updateRecedingNeighbors(game, horizontal, vertical);

        if (vision.isTileHidden(this.map.getTileFlags(tileIndex))) {
            return;
        }

        this.refreshGraphicsEdges(horizontal, vertical);
    }
}
