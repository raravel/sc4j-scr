package org.fogofwarlabs.sc4j.scr.game.path;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.terrain.RegionPathSearch;
import org.fogofwarlabs.sc4j.scr.game.terrain.TerrainContours;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Owns the path storage of one game: the terrain contours (collision boundaries and regions), the persistent path pool, the local path search and
 * unit path workspaces and the region path search of resource queries.
 * <p>
 * World initialization installs the terrain and unit path storage. Resource queries allocate their region search workspace lazily. Contours
 * built again for changed map tiles ({@link #replaceTerrainContours}) replace the current ones; the contours the game started with stay for the
 * computer players ({@link #getStartTerrainContours}).
 */
public final class PathSystem {
    private @Nullable TerrainContours terrainContours;
    private @Nullable TerrainContours startTerrainContours;

    private @Nullable PathPool pathPool;
    private @Nullable LocalPathSearch localPathSearch;
    private @Nullable UnitPath unitPath;

    private @Nullable RegionPathSearch resourcePathSearch;

    /**
     * Creates a {@link PathSystem} instance without storage.
     */
    public PathSystem() {
        this.terrainContours      = null;
        this.startTerrainContours = null;

        this.pathPool        = null;
        this.localPathSearch = null;
        this.unitPath        = null;

        this.resourcePathSearch = null;
    }

    /**
     * @return the terrain contour storage, or {@code null} before initialization
     */
    public @Nullable TerrainContours getTerrainContours() {
        return this.terrainContours;
    }

    /**
     * @return the terrain contours the game started with, which the computer players' regional records index, or {@code null} before initialization
     */
    public @Nullable TerrainContours getStartTerrainContours() {
        return this.startTerrainContours;
    }

    /**
     * Returns the terrain contour storage of an initialized world.
     *
     * @return the terrain contour storage
     * @throws UnsupportedOperationException if the terrain contours are not initialized
     */
    public @NotNull TerrainContours requireTerrainContours() {
        TerrainContours terrainContours = this.terrainContours;

        if (terrainContours == null) {
            throw Exceptions.newUnsupportedOperationException("The terrain contours are not initialized.");
        }

        return terrainContours;
    }

    /**
     * Sets the terrain contours of the fresh map, once.
     *
     * @param terrainContours the terrain contours of the fresh map
     * @throws IllegalStateException if the contours are already installed
     */
    public void setTerrainContours(@NotNull TerrainContours terrainContours) {
        if (this.terrainContours != null) {
            throw Exceptions.newIllegalStateException("Native terrain collision is already initialized.");
        }

        this.terrainContours      = terrainContours;
        this.startTerrainContours = terrainContours;
    }

    /**
     * Replaces the current terrain contours with contours built for changed map tiles; the start contours stay.
     *
     * @param terrainContours the terrain contours of the changed map
     * @throws IllegalStateException if no contours are installed
     */
    public void replaceTerrainContours(@NotNull TerrainContours terrainContours) {
        if (this.terrainContours == null) {
            throw Exceptions.newIllegalStateException("Native terrain collision is not initialized.");
        }

        this.terrainContours    = terrainContours;
        this.resourcePathSearch = null;
    }

    /**
     * @return the original persistent path storage, or {@code null} before initialization
     */
    public @Nullable PathPool getPathPool() {
        return this.pathPool;
    }

    /**
     * Returns the persistent path storage of an initialized world.
     *
     * @return the persistent path storage
     * @throws UnsupportedOperationException if the path pool is not initialized
     */
    public @NotNull PathPool requirePathPool() {
        PathPool pathPool = this.pathPool;

        if (pathPool == null) {
            throw Exceptions.newUnsupportedOperationException("The path pool is not initialized.");
        }

        return pathPool;
    }

    /**
     * Sets the original persistent path storage.
     *
     * @param pathPool the initialized original persistent path storage
     */
    public void setPathPool(@NotNull PathPool pathPool) {
        this.pathPool = pathPool;
    }

    /**
     * @return the retained local route workspace, or {@code null}
     */
    public @Nullable LocalPathSearch getLocalPathSearch() {
        return this.localPathSearch;
    }

    /**
     * Returns the local route workspace of an initialized world.
     *
     * @return the local route workspace
     * @throws UnsupportedOperationException if the local path search is not initialized
     */
    public @NotNull LocalPathSearch requireLocalPathSearch() {
        LocalPathSearch localPathSearch = this.localPathSearch;

        if (localPathSearch == null) {
            throw Exceptions.newUnsupportedOperationException("The local path search is not initialized.");
        }

        return localPathSearch;
    }

    /**
     * Sets the retained local route workspace.
     *
     * @param localPathSearch the initialized route workspace
     */
    public void setLocalPathSearch(@NotNull LocalPathSearch localPathSearch) {
        this.localPathSearch = localPathSearch;
    }

    /**
     * @return the retained unit path request workspace, or {@code null}
     */
    public @Nullable UnitPath getUnitPath() {
        return this.unitPath;
    }

    /**
     * Returns the unit path request workspace of an initialized world.
     *
     * @return the unit path request workspace
     * @throws UnsupportedOperationException if the unit path workspace is not initialized
     */
    public @NotNull UnitPath requireUnitPath() {
        UnitPath unitPath = this.unitPath;

        if (unitPath == null) {
            throw Exceptions.newUnsupportedOperationException("The unit path workspace is not initialized.");
        }

        return unitPath;
    }

    /**
     * Sets the retained unit path request workspace.
     *
     * @param unitPath the initialized request workspace
     */
    public void setUnitPath(@NotNull UnitPath unitPath) {
        this.unitPath = unitPath;
    }

    /**
     * Returns the region path search of resource queries over this game's terrain regions, creating it on first use.
     *
     * @return the reusable region path search of resource queries
     * @throws IllegalStateException if the terrain contours are not installed
     */
    public @NotNull RegionPathSearch getResourcePathSearch() {
        if (this.terrainContours == null) {
            throw Exceptions.newIllegalStateException("Resource path search requires terrain regions.");
        }

        if (this.resourcePathSearch == null) {
            this.resourcePathSearch = new RegionPathSearch(this.terrainContours.getRegions());
        }

        return this.resourcePathSearch;
    }
}
