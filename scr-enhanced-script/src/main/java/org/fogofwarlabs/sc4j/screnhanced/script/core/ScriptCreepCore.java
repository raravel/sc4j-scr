package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.creep.CreepField;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

/**
 * Defines {@code core.creep}: the routines of the game that add and remove creep, in shared code only. A footprint or a spread scan that leaves
 * the map throws a {@code RangeError}.
 * <p>
 * {@code fill(unitType, x, y)} covers the footprint of a building of the unit type centered at the map position with creep and grows it until
 * no tile is added, as the game does for a building that needs creep ({@link CreepField#fillMoved}). {@code spread(unitType, x, y)} runs one
 * spread step of a creep source of the unit type there ({@link CreepField#spread}): {@code 1} when it added a tile, {@code 2} when it added
 * none and an occupied tile blocked it, else {@code 0}; a type that spreads creep keeps to its ellipse. {@code removeTile(tileX, tileY)}
 * removes the creep of a tile at once and gives back the terrain below it ({@link CreepField#removeTile}). {@code recede(tileX, tileY)} starts
 * the recession of a creep tile as a removed building does ({@link CreepField#recedeTile}): a tile that is not receding, not occupied and not
 * sustained by a grounded Zerg building recedes later at the pace of the recession; it returns {@code false} for a skipped tile and when no
 * recession record is free. Creep that a script adds has no source, so it stays until a script removes it or makes it recede.
 */
final class ScriptCreepCore {
    private final @NotNull ScriptGame scriptGame;

    /**
     * Creates a {@link ScriptCreepCore} instance.
     *
     * @param scriptGame the game binding
     */
    ScriptCreepCore(@NotNull ScriptGame scriptGame) {
        this.scriptGame = scriptGame;
    }

    /**
     * Defines the functions of {@code core.creep}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void define(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "fill", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int unitType = readUnitType(arguments);
            int x = this.readMapX(arguments, 1);
            int y = this.readMapY(arguments, 2);

            try {
                this.requireCreepField().fillMoved(this.scriptGame.getGame(), unitType, x, y);
            } catch (UnsupportedOperationException exception) {
                throw ScriptRuntime.rangeError(Exceptions.format("The footprint of the unit type {0} at {1}, {2} leaves the map.", unitType, x, y));
            }

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "spread", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int unitType = readUnitType(arguments);
            int x = this.readMapX(arguments, 1);
            int y = this.readMapY(arguments, 2);

            try {
                return (double) this.requireCreepField().spread(this.scriptGame.getGame(), unitType, x, y);
            } catch (UnsupportedOperationException exception) {
                throw ScriptRuntime.rangeError(Exceptions.format("The spread of the unit type {0} at {1}, {2} leaves the map.", unitType, x, y));
            }
        });
        ScriptCoreUtils.defineFunction(global, namespace, "removeTile", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int tileX = this.readTileX(arguments);
            int tileY = this.readTileY(arguments);

            this.requireCreepField().removeTile(this.scriptGame.getGame(), tileX, tileY);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "recede", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int tileX = this.readTileX(arguments);
            int tileY = this.readTileY(arguments);
            CreepField creep = this.requireCreepField();

            if (this.scriptGame.getGame().getCreepRecession() == null) {
                throw ScriptRuntime.typeError("The game has no creep recession.");
            }

            return creep.recedeTile(this.scriptGame.getGame(), tileX, tileY);
        });
    }

    private static int readUnitType(@NotNull Object @NotNull [] arguments) {
        return ScriptCoreUtils.requireId("unit type", ScriptCoreUtils.readInteger(arguments, 0), ScrUnitIds.NONE);
    }

    private int readMapX(@NotNull Object @NotNull [] arguments, int index) {
        return ScriptCoreUtils.requireId("x", ScriptCoreUtils.readInteger(arguments, index), this.getMap().getWidth() * TerrainTileset.TILE_SIZE);
    }

    private int readMapY(@NotNull Object @NotNull [] arguments, int index) {
        return ScriptCoreUtils.requireId("y", ScriptCoreUtils.readInteger(arguments, index), this.getMap().getHeight() * TerrainTileset.TILE_SIZE);
    }

    private int readTileX(@NotNull Object @NotNull [] arguments) {
        return ScriptCoreUtils.requireId("tile x", ScriptCoreUtils.readInteger(arguments, 0), this.getMap().getWidth());
    }

    private int readTileY(@NotNull Object @NotNull [] arguments) {
        return ScriptCoreUtils.requireId("tile y", ScriptCoreUtils.readInteger(arguments, 1), this.getMap().getHeight());
    }

    private @NotNull GameMap getMap() {
        return this.scriptGame.getGame().getMap();
    }

    /**
     * @return the creep field of the game
     * @throws EcmaError if the game has no creep field
     */
    private @NotNull CreepField requireCreepField() {
        Game game = this.scriptGame.getGame();
        CreepField creep = game.getCreepField();

        if (creep == null) {
            throw ScriptRuntime.typeError("The game has no creep.");
        }

        return creep;
    }
}
