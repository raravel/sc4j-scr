package org.fogofwarlabs.sc4j.scr.game.map;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepField;
import org.fogofwarlabs.sc4j.scr.game.path.PathPool;
import org.fogofwarlabs.sc4j.scr.game.path.PathSystem;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.MapTileFlags;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainContours;
import org.fogofwarlabs.sc4j.scr.game.unit.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Changes the tiles of a running game's map, so that the game continues as on a map that had the new tiles from the start, as far as the
 * game's routines allow.
 * <p>
 * {@link #setTile} queues a change and {@link #getTile} reads the tile the map has after the queued changes. The frame's trigger step applies
 * the queued changes ({@link #applyChanges}) after the simulation plugins' frame start and before the triggers, the last change of a tile
 * winning, so every peer and every replay changes the same tiles at the same point of the same frame. Applying the changes:
 * <ul>
 * <li>replaces each changed tile ({@link GameMap#replaceTerrain}) with the terrain flags the map loader gives it (the tileset's walkability,
 * heights, sight blocking, buildability and creep bits, the bottom border rule), keeping the per-player visibility, the building occupancy and
 * the creep; a tile below creep keeps the creep and retains the new tile below it; the graphics cache of a tile without creep takes the new
 * tile's megatile;</li>
 * <li>builds the terrain contours and regions of the whole map again from the terrain without creep, through the loader's
 * {@link TerrainContours#create}, keeps the footprint controls of the previous contours, and makes every unit that follows a path search a
 * new one;</li>
 * <li>moves each ground unit that the terrain under it no longer carries to a free walkable position near it
 * ({@link UnitRelocation#displace}), in the order of the unit list, and finishes the order of a moved
 * completed unit, as Move Unit does.</li>
 * </ul>
 * The computer players and the resource areas keep the regions the game started with ({@link PathSystem#getStartTerrainContours}).
 */
public final class MapTileChanges {
    /**
     * The value of a tile without a queued change.
     */
    private static final int NO_CHANGE = -1;

    private final @NotNull Game game;

    private int @Nullable [] pendingTiles;
    private int pendingCount;

    /**
     * Creates a {@link MapTileChanges} instance without queued changes.
     *
     * @param game the game whose map changes
     */
    public MapTileChanges(@NotNull Game game) {
        this.game = game;

        this.pendingTiles = null;
        this.pendingCount = 0;
    }

    /**
     * Reads a tile of the map after the queued changes: the queued tile, else the terrain of the map, the tile retained below creep first.
     *
     * @param tileX the horizontal coordinate in tiles
     * @param tileY the vertical coordinate in tiles
     * @return the tile WORD, the CV5 group shifted left by {@code 4} plus the tile's index in the group
     * @throws IndexOutOfBoundsException if the coordinates are outside the map
     */
    public int getTile(int tileX, int tileY) {
        int tileIndex = this.toTileIndex(tileX, tileY);
        int[] pending = this.pendingTiles;

        if (pending != null && pending[tileIndex] != NO_CHANGE) {
            return pending[tileIndex];
        }

        return this.game.getMap().getTerrainTileIdentifier(tileIndex);
    }

    /**
     * Queues a change of a tile of the map, which the frame's trigger step applies.
     *
     * @param tileX          the horizontal coordinate in tiles
     * @param tileY          the vertical coordinate in tiles
     * @param tileIdentifier the new tile WORD ({@link #isValidTile})
     * @throws IndexOutOfBoundsException if the coordinates are outside the map
     * @throws IllegalArgumentException  if the tile WORD names no tile of the map's tileset
     * @throws IllegalStateException     if the map has no expanded terrain flags or overlays, or the tileset cannot be loaded
     */
    public void setTile(int tileX, int tileY, int tileIdentifier) {
        int tileIndex = this.toTileIndex(tileX, tileY);
        GameMap map = this.game.getMap();

        if (!map.hasTileFlags() || !map.hasOverlayTiles()) {
            throw Exceptions.newIllegalStateException("The map tiles of a game without terrain flags do not change.");
        }

        if (!this.isValidTile(tileIdentifier)) {
            throw Exceptions.newIllegalArgumentException("The tile {0} is not a tile of the tileset of the map.", tileIdentifier);
        }

        int[] pending = this.pendingTiles;

        if (pending == null) {
            pending = new int[map.getTileCount()];
            Arrays.fill(pending, NO_CHANGE);
            this.pendingTiles = pending;
        }

        if (pending[tileIndex] == NO_CHANGE) {
            this.pendingCount++;
        }

        pending[tileIndex] = tileIdentifier;
    }

    /**
     * @return {@code true} if a change is queued, {@code false} otherwise
     */
    public boolean hasPendingChanges() {
        return this.pendingCount > 0;
    }

    /**
     * Checks whether a tile WORD names a tile of the map's tileset ({@link TerrainTileset#hasTile}).
     *
     * @param tileIdentifier the tile WORD
     * @return {@code true} if the tile WORD names a tile of the map's tileset, {@code false} otherwise
     * @throws IllegalStateException if the tileset cannot be loaded
     */
    public boolean isValidTile(int tileIdentifier) {
        return this.requireTileset().hasTile(tileIdentifier);
    }

    /**
     * Returns the terrain flags the map loader gives a tile WORD ({@link TerrainTileset#expandTileFlags}), before the bottom border rule.
     *
     * @param tileIdentifier the tile WORD
     * @return the terrain flags ({@link MapTileFlags})
     * @throws IllegalArgumentException if the tile WORD names no tile of the map's tileset
     * @throws IllegalStateException    if the tileset cannot be loaded
     */
    public int getTerrainFlags(int tileIdentifier) {
        TerrainTileset terrain = this.requireTileset();

        if (!terrain.hasTile(tileIdentifier)) {
            throw Exceptions.newIllegalArgumentException("The tile {0} is not a tile of the tileset of the map.", tileIdentifier);
        }

        return terrain.expandTileFlags(new int[]{tileIdentifier})[0];
    }

    /**
     * Applies the queued changes, which the frame's trigger step does after the simulation plugins' frame start: the tiles, then the contours
     * and regions of the whole map, the paths and the ground units on terrain that no longer carries them. Nothing happens without a queued
     * change.
     *
     * @param instructionLimit the instruction limit of the animation programs an order change runs
     * @throws IllegalStateException         if the tileset cannot be loaded
     * @throws UnsupportedOperationException if the contours of the changed map cannot be built
     */
    public void applyChanges(int instructionLimit) {
        int[] pending = this.pendingTiles;

        if (pending == null || this.pendingCount == 0) {
            return;
        }

        GameMap map = this.game.getMap();
        TerrainTileset terrain = this.requireTileset();
        CreepField creep = this.game.getCreepField();
        int[] tile = new int[1];

        for (int tileIndex = 0; tileIndex < pending.length; tileIndex++) {
            int tileIdentifier = pending[tileIndex];

            if (tileIdentifier == NO_CHANGE) {
                continue;
            }

            pending[tileIndex] = NO_CHANGE;
            tile[0]            = map.getTerrainTileIdentifier(tileIndex);

            int previousFlags = map.applyTerrainBorder(tileIndex, terrain.expandTileFlags(tile)[0]);

            tile[0] = tileIdentifier;

            int flags = map.applyTerrainBorder(tileIndex, terrain.expandTileFlags(tile)[0]);
            boolean underCreep = map.getOverlayTileIdentifier(tileIndex) != 0;

            map.replaceTerrain(tileIndex, tileIdentifier, previousFlags, flags);

            if (creep != null && !underCreep) {
                int graphicsCache = terrain.getMegatileIdentifier(tileIdentifier) | tileIdentifier & CreepField.GRAPHICS_REFRESH_FLAG;

                creep.updateGraphicsCache(tileIndex, graphicsCache);
            }
        }

        this.pendingCount = 0;

        PathSystem paths = this.game.getPathSystem();
        TerrainContours previous = paths.getTerrainContours();

        if (previous == null) {
            return;
        }

        TerrainContours contours = TerrainContours.create(this.createLoadedMap(map, terrain), terrain);

        contours.copyFootprintControls(previous);
        paths.replaceTerrainContours(contours);
        this.updateUnits(instructionLimit);
    }

    /**
     * Creates the map the loader builds contours from: the map's terrain without creep, its expanded terrain flags with the bottom border rule and
     * empty overlays.
     */
    private @NotNull GameMap createLoadedMap(@NotNull GameMap map, @NotNull TerrainTileset terrain) {
        int[] tileIdentifiers = new int[map.getTileCount()];

        for (int tileIndex = 0; tileIndex < tileIdentifiers.length; tileIndex++) {
            tileIdentifiers[tileIndex] = map.getTerrainTileIdentifier(tileIndex);
        }

        GameMap loaded = new GameMap(map.getWidth(), map.getHeight(), map.getTileset(), tileIdentifiers, terrain.expandTileFlags(tileIdentifiers));

        loaded.initializeTerrainBorders();
        loaded.initializeOverlayTiles();

        return loaded;
    }

    /**
     * Makes every unit that follows a path search a new one, then moves the ground units that the terrain no longer carries, found first, in the
     * order of the unit list.
     */
    private void updateUnits(int instructionLimit) {
        UnitPool pool = this.game.getUnitSystem().getUnitPool();

        if (pool == null) {
            return;
        }

        PathPool paths = this.game.getPathSystem().getPathPool();

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (paths != null && unit.getPathIndex() >= 0) {
                paths.setFlags(unit.getPathIndex(), paths.getFlags(unit.getPathIndex()) | PathPool.FLAG_NEW_MOVE_TARGET);
            }
        }

        ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();
        List<Unit> displaced = new ArrayList<>();

        for (int poolIndex = pool.getFirstCreatedIndex(true); poolIndex != 0; poolIndex = pool.getNextCreatedIndex(poolIndex)) {
            Unit unit = pool.getUnit(poolIndex);

            if (this.isDisplaced(unit, units)) {
                displaced.add(unit);
            }
        }

        for (Unit unit : displaced) {
            if (this.game.getUnitRelocation().displace(unit) && (unit.getStatus() & UnitStatusFlags.COMPLETED) != 0) {
                this.game.getOrders().finishOrder(unit, instructionLimit);
            }
        }
    }

    /**
     * Checks whether the terrain under a unit no longer carries it: a shown, living, non-building ground unit that is not a subunit, whose
     * placement the terrain refuses.
     */
    private boolean isDisplaced(@NotNull Unit unit, @NotNull ScrUnitTypeView units) {
        Sprite sprite = unit.getSpriteOrNull();

        if (sprite == null || (sprite.getFlags() & Sprite.FLAG_HIDDEN) != 0 || (unit.getStatus() & UnitStatusFlags.FLYING) != 0 ||
            unit.getPrimaryOrder() == ScrOrderIds.DIE || UnitQueries.isGroundedBuilding(unit) ||
            (units.getFlags(unit.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0) {
            return false;
        }

        return !this.game.getUnitPositioning().isTerrainPlacementWalkable(unit.getUnitId(), sprite.getX(), sprite.getY());
    }

    private int toTileIndex(int tileX, int tileY) {
        GameMap map = this.game.getMap();

        if (tileX < 0 || tileX >= map.getWidth() || tileY < 0 || tileY >= map.getHeight()) {
            throw Exceptions.newIndexOutOfBoundsException("The tile {0}, {1} is outside the map.", tileX, tileY);
        }

        return tileY * map.getWidth() + tileX;
    }

    private @NotNull TerrainTileset requireTileset() {
        try {
            return this.game.requireGameData().getTerrainTileset(this.game.getMap().getTileset());
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "The tileset of the map cannot be loaded.");
        }
    }
}
