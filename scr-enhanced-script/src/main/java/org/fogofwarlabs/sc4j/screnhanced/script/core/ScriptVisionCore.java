package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.visibility.VisibilitySystem;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.game.terrain.MapTileFlags;
import org.fogofwarlabs.sc4j.scr.game.visibility.SightCoverage;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

/**
 * Defines {@code core.vision}: the sight of the players on the tiles of the map.
 * <p>
 * {@code getTileVisibility(tileX, tileY)} reads the low WORD of a tile's flags: bit {@code p} is set while player {@code p} does not see the
 * tile, bit {@code 8 + p} while player {@code p} has not explored it. The game marks every tile unseen every hundred frames and the sight of
 * the units clears the bits again; the explored bits stay clear. {@code setTileVisibility(tileX, tileY, bits)} replaces the low WORD of one
 * tile as an EUD write does ({@link GameMap#setTileVisibility}), in shared code only: the visible bits last until the game marks the tiles unseen
 * or the units' sight clears them, the explored bits stay. {@code isVisible(player, x, y)} and {@code isExplored(player, x, y)} test the
 * tile at a map position as the visibility system does. {@code reveal(players, x, y, sightRange, air)} runs the native sight routine of a unit
 * ({@link SightCoverage#reveal}) for the players of a bit mask ({@code 0} to {@value #MAXIMUM_REVEAL_MASK}) with a sight range from {@code 0} to
 * {@value SightCoverage#DESCRIPTOR_COUNT} minus one: an air reveal sees over the terrain, a ground reveal not over higher ground; what it reveals
 * stays seen until the next time the game marks the tiles unseen.
 * <p>
 * {@code getFogBrightness()} and {@code setFogBrightness(level)} read and store the fog brightness of visible tiles, the byte EUD writes change
 * (default {@code 0x1F}, full brightness): the world's fog draws visible tiles at this level, explored tiles at half of it and unexplored tiles
 * black; the minimap does not use it. They are presentation: they run in shared and local code and change no game state.
 */
final class ScriptVisionCore {
    /**
     * The largest player mask of a reveal: players {@code 0} to {@code 7}.
     */
    private static final int MAXIMUM_REVEAL_MASK = 0xFF;
    /**
     * The largest fog brightness of visible tiles, a byte.
     */
    private static final int MAXIMUM_FOG_BRIGHTNESS = 0xFF;

    private final @NotNull ScriptGame scriptGame;

    /**
     * Creates a {@link ScriptVisionCore} instance.
     *
     * @param scriptGame the game binding
     */
    ScriptVisionCore(@NotNull ScriptGame scriptGame) {
        this.scriptGame = scriptGame;
    }

    /**
     * Defines the functions of {@code core.vision}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void define(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getTileVisibility", 2, (context, scope, thisObject, arguments) -> {
            GameMap map = this.requireTileFlags();
            int tileX = ScriptCoreUtils.requireId("tile x", ScriptCoreUtils.readInteger(arguments, 0), map.getWidth());
            int tileY = ScriptCoreUtils.requireId("tile y", ScriptCoreUtils.readInteger(arguments, 1), map.getHeight());

            return (double) (map.getTileFlags(tileX, tileY) & MapTileFlags.NOT_VISIBLE_OR_EXPLORED);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setTileVisibility", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            GameMap map = this.requireTileFlags();
            int tileX = ScriptCoreUtils.requireId("tile x", ScriptCoreUtils.readInteger(arguments, 0), map.getWidth());
            int tileY = ScriptCoreUtils.requireId("tile y", ScriptCoreUtils.readInteger(arguments, 1), map.getHeight());
            int bits = ScriptCoreUtils.readInteger(arguments, 2);

            if (bits < 0 || bits > MapTileFlags.NOT_VISIBLE_OR_EXPLORED) {
                throw ScriptRuntime.rangeError(Exceptions.format("The visibility bits {0} are not from 0 to {1}.", bits,
                                                                 MapTileFlags.NOT_VISIBLE_OR_EXPLORED));
            }

            map.setTileVisibility(tileX, tileY, bits);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getFogBrightness", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.scriptGame.getGame().getGameContext().getVisibleFogLevel());
        ScriptCoreUtils.defineFunction(global, namespace, "setFogBrightness", 1, (context, scope, thisObject, arguments) -> {
            int level = ScriptCoreUtils.readInteger(arguments, 0);

            if (level < 0 || level > MAXIMUM_FOG_BRIGHTNESS) {
                throw ScriptRuntime.rangeError(Exceptions.format("The fog brightness {0} is not from 0 to {1}.", level, MAXIMUM_FOG_BRIGHTNESS));
            }

            this.scriptGame.getGame().getGameContext().setVisibleFogLevel(level);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "isVisible", 3, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayer(arguments);
            int x = ScriptCoreUtils.readInteger(arguments, 1);
            int y = ScriptCoreUtils.readInteger(arguments, 2);

            this.requireTileFlags();

            return this.getVisibility().isVisible(this.scriptGame.getGame().getEngineGame().getPlayer(player), x, y);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "isExplored", 3, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayer(arguments);
            int x = ScriptCoreUtils.readInteger(arguments, 1);
            int y = ScriptCoreUtils.readInteger(arguments, 2);

            this.requireTileFlags();

            return this.getVisibility().isExplored(this.scriptGame.getGame().getEngineGame().getPlayer(player), x, y);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "reveal", 5, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int mask = ScriptCoreUtils.readInteger(arguments, 0);
            int x = ScriptCoreUtils.readInteger(arguments, 1);
            int y = ScriptCoreUtils.readInteger(arguments, 2);
            int sightRange = ScriptCoreUtils.requireId("sight range", ScriptCoreUtils.readInteger(arguments, 3), SightCoverage.DESCRIPTOR_COUNT);
            boolean air = ScriptCoreUtils.readBoolean(arguments, 4);
            Game game = this.scriptGame.getGame();
            SightCoverage sight = game.getSightCoverage();

            if (mask < 0 || mask > MAXIMUM_REVEAL_MASK) {
                throw ScriptRuntime.rangeError(Exceptions.format("The player mask {0} is not from 0 to {1}.", mask, MAXIMUM_REVEAL_MASK));
            }

            if (sight == null) {
                throw ScriptRuntime.typeError("The game has no sight coverage.");
            }

            this.requireTileFlags();
            sight.reveal(game, (short) x, (short) y, air, mask, sightRange);

            return Context.getUndefinedValue();
        });
    }

    private @NotNull VisibilitySystem getVisibility() {
        return this.scriptGame.getGame().getEngineGame().getVisibility();
    }

    private int readPlayer(@NotNull Object @NotNull [] arguments) {
        return ScriptCoreUtils.requireId("player", ScriptCoreUtils.readInteger(arguments, 0), PlayerTable.PLAYER_SLOTS);
    }

    /**
     * @return the map of the game
     * @throws EcmaError if the map has no tile flags
     */
    private @NotNull GameMap requireTileFlags() {
        GameMap map = this.scriptGame.getGame().getMap();

        if (!map.hasTileFlags()) {
            throw ScriptRuntime.typeError("The map has no tile flags.");
        }

        return map;
    }
}
