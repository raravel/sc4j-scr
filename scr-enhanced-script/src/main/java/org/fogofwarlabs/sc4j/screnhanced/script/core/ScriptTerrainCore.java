package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.map.MapTileChanges;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.MapTileFlags;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

import java.io.IOException;

/**
 * Defines {@code core.terrain}: the tiles of the map by tile coordinates ({@code 32} pixels), as MTXM values ({@code group << 4 | index}, the
 * CV5 group and the tile's index in it).
 * <p>
 * {@code getTile(tileX, tileY)} reads the tile the map has after the changes queued so far, the tile below creep for a tile with creep;
 * {@code getTileFlags(tileX, tileY)} reads the tile's flag DWORD as the game holds it now (the sight bits of {@code core.vision}, walkability,
 * creep, elevation and occupation, {@link MapTileFlags}); {@code hasCreep(tileX, tileY)} tests the creep flag of those flags, which the rules
 * of the game read (placement, larvae, spells) and the creep routines of {@code core.creep} set and clear;
 * {@code setTile(tileX, tileY, value)} queues a change, which the game applies at its next trigger step, after the simulation plugins' frame
 * start and before the triggers, as on a map that had the tile from the start ({@link MapTileChanges}); {@code getTileGroupCount()} is the
 * number of CV5 groups of the map's tileset and {@code isValidTile(value)} tells whether a value names a tile of it. The terrain a value gives
 * a tile:
 * {@code isTileWalkable(value)} (more than twelve of its sixteen mini-tiles are walkable), {@code isTilePartiallyWalkable(value)} (some but
 * not all are), {@code isTileBuildable(value)}, {@code hasTileCreep(value)}, {@code getTileElevation(value)} ({@code 0} low, {@code 1}
 * middle and {@code 2} high ground) and {@code isTileBlockingSight(value)} (a tile that blocks the sight of ground units at every
 * elevation). A coordinate outside the map or a value that names no tile of the tileset throws a {@code RangeError}.
 */
final class ScriptTerrainCore {
    /**
     * The elevations of {@code getTileElevation}.
     */
    private static final int LOW_GROUND = 0;
    private static final int MIDDLE_GROUND = TerrainTileset.MIDDLE_GROUND_HEIGHT;
    private static final int HIGH_GROUND = TerrainTileset.HIGH_GROUND_HEIGHT;

    private final @NotNull ScriptGame scriptGame;

    /**
     * Creates a {@link ScriptTerrainCore} instance.
     *
     * @param scriptGame the game binding
     */
    ScriptTerrainCore(@NotNull ScriptGame scriptGame) {
        this.scriptGame = scriptGame;
    }

    /**
     * Defines the functions of {@code core.terrain}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void define(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getTile", 2, (context, scope, thisObject, arguments) -> {
            int tileX = this.readTileX(arguments, 0);
            int tileY = this.readTileY(arguments, 1);

            return (double) this.getChanges().getTile(tileX, tileY);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getTileFlags", 2, (context, scope, thisObject, arguments) -> {
            int tileX = this.readTileX(arguments, 0);
            int tileY = this.readTileY(arguments, 1);
            GameMap map = this.scriptGame.getGame().getMap();

            if (!map.hasTileFlags()) {
                throw ScriptRuntime.typeError("The map has no tile flags.");
            }

            return (double) Integer.toUnsignedLong(map.getTileFlags(tileX, tileY));
        });
        ScriptCoreUtils.defineFunction(global, namespace, "hasCreep", 2, (context, scope, thisObject, arguments) -> {
            int tileX = this.readTileX(arguments, 0);
            int tileY = this.readTileY(arguments, 1);
            GameMap map = this.scriptGame.getGame().getMap();

            if (!map.hasTileFlags()) {
                throw ScriptRuntime.typeError("The map has no tile flags.");
            }

            return (map.getTileFlags(tileX, tileY) & MapTileFlags.HAS_CREEP) != 0;
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setTile", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int tileX = this.readTileX(arguments, 0);
            int tileY = this.readTileY(arguments, 1);
            int value = this.readTile(arguments, 2);

            this.getChanges().setTile(tileX, tileY, value);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getTileGroupCount", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.requireTileset().getGroupCount());
        ScriptCoreUtils.defineFunction(global, namespace, "isValidTile", 1, (context, scope, thisObject, arguments) -> {
            int value = ScriptCoreUtils.readInteger(arguments, 0);

            return this.getChanges().isValidTile(value);
        });

        ScriptCoreUtils.defineFunction(global, namespace, "isTileWalkable", 1,
                                       (context, scope, thisObject, arguments) -> (this.readFlags(arguments) & MapTileFlags.WALKABLE) != 0);
        ScriptCoreUtils.defineFunction(global, namespace, "isTilePartiallyWalkable", 1,
                                       (context, scope, thisObject, arguments) -> (this.readFlags(arguments) & MapTileFlags.PARTIALLY_WALKABLE) != 0);
        ScriptCoreUtils.defineFunction(global, namespace, "isTileBuildable", 1,
                                       (context, scope, thisObject, arguments) -> (this.readFlags(arguments) & MapTileFlags.UNBUILDABLE) == 0);
        ScriptCoreUtils.defineFunction(global, namespace, "hasTileCreep", 1,
                                       (context, scope, thisObject, arguments) -> (this.readFlags(arguments) & MapTileFlags.HAS_CREEP) != 0);
        ScriptCoreUtils.defineFunction(global, namespace, "getTileElevation", 1, (context, scope, thisObject, arguments) -> {
            int flags = this.readFlags(arguments);

            if ((flags & MapTileFlags.HIGH) != 0) {
                return (double) HIGH_GROUND;
            }

            return (double) ((flags & MapTileFlags.MIDDLE) != 0 ? MIDDLE_GROUND : LOW_GROUND);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "isTileBlockingSight", 1,
                                       (context, scope, thisObject, arguments) -> (this.readFlags(arguments) & MapTileFlags.VERY_HIGH) != 0);
    }

    private @NotNull MapTileChanges getChanges() {
        return this.scriptGame.getGame().getMapTileChanges();
    }

    private int readTileX(@NotNull Object @NotNull [] arguments, int index) {
        return ScriptCoreUtils.requireId("tile x", ScriptCoreUtils.readInteger(arguments, index), this.scriptGame.getGame().getMap().getWidth());
    }

    private int readTileY(@NotNull Object @NotNull [] arguments, int index) {
        return ScriptCoreUtils.requireId("tile y", ScriptCoreUtils.readInteger(arguments, index), this.scriptGame.getGame().getMap().getHeight());
    }

    /**
     * Reads a tile value argument.
     *
     * @throws EcmaError if the argument is not an integer or names no tile of the map's tileset
     */
    private int readTile(@NotNull Object @NotNull [] arguments, int index) {
        int value = ScriptCoreUtils.readInteger(arguments, index);

        if (!this.getChanges().isValidTile(value)) {
            throw ScriptRuntime.rangeError(Exceptions.format("The tile {0} is not a tile of the tileset of the map.", value));
        }

        return value;
    }

    /**
     * Reads the terrain flags a tile value argument gives a tile.
     *
     * @throws EcmaError if the argument is not an integer or names no tile of the map's tileset
     */
    private int readFlags(@NotNull Object @NotNull [] arguments) {
        return this.getChanges().getTerrainFlags(this.readTile(arguments, 0));
    }

    private @NotNull TerrainTileset requireTileset() {
        Game game = this.scriptGame.getGame();

        try {
            return game.requireGameData().getTerrainTileset(game.getMap().getTileset());
        } catch (IOException exception) {
            throw Exceptions.newIllegalStateException(exception, "The tileset of the map cannot be loaded.");
        }
    }
}
