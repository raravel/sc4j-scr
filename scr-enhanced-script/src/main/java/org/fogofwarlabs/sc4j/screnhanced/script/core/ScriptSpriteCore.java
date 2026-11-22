package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.dat.SpritesDat;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.sprite.*;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.visibility.SpriteVisibility;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Defines {@code core.sprite} and {@code core.image}: the sprites of the sprite pool and the images of the image pool, by their slot from
 * {@code 0} to {@code getCapacity()} minus one.
 * <p>
 * A slot holds a sprite or an image while it is allocated; {@code getGeneration(id)} tells apart the sprites, or the images, that one slot holds in
 * turn: every allocation takes the next generation of its pool. {@code getIds()} lists the allocated sprites in slot order. Every other function
 * needs an allocated slot and throws a {@code RangeError} for another id. The pools also hold the sprites and images of the buildings the local
 * player remembers in the fog of war, so the slots and generations can differ from peer to peer in a network game: shared code may show them but
 * should not let them decide the game.
 * <p>
 * {@code core.sprite}: {@code getType} (the {@code sprites.dat} entry), {@code getX}, {@code getY}, {@code getOwner}, {@code getElevation},
 * {@code getFlags}, {@code getVisibilityFlags} (the players that see the sprite, one bit per player), {@code getImages(id)} (the images of the
 * image pool in the order of the sprite's list, from the image drawn on top; the local selection circles and health bars are no images of the
 * pool) and {@code getMainImage(id)} ({@code -1} for none). {@code createEffect(spriteType, x, y, owner, elevation)} creates a sprite that lives
 * as long as its image scripts run, as the game creates the sprite of a spell or of dust ({@code IscriptExecutor.createSpriteEffect}), then
 * sets its elevation and its visibility from the tile below it, as every native caller does; it returns the sprite or {@code -1} when the pools
 * are full. It works in shared code only.
 * <p>
 * {@code core.image}: {@code getType} (the {@code images.dat} entry), {@code getSprite} ({@code -1} for none), {@code getFrame} (the drawn
 * frame), {@code getDirection} (the frame offset of the direction inside the frame group), {@code getDrawFunction}, {@code getFlags},
 * {@code getOffsetX} and {@code getOffsetY}.
 */
final class ScriptSpriteCore {
    /**
     * The largest elevation level of a sprite, a BYTE.
     */
    private static final int MAXIMUM_ELEVATION = 0xFF;

    private final @NotNull ScriptGame scriptGame;

    /**
     * Creates a {@link ScriptSpriteCore} instance.
     *
     * @param scriptGame the game binding
     */
    ScriptSpriteCore(@NotNull ScriptGame scriptGame) {
        this.scriptGame = scriptGame;
    }

    /**
     * Defines the functions of {@code core.sprite}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void defineSprites(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getCapacity", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.getSpritePool().getCapacity());
        ScriptCoreUtils.defineFunction(global, namespace, "getIds", 0, (context, scope, thisObject, arguments) -> {
            SpritePool pool = this.getSpritePool();
            List<Object> ids = new ArrayList<>();

            for (int id = 0; id < pool.getCapacity(); id++) {
                if (pool.isAllocated(id)) {
                    ids.add((double) id);
                }
            }

            return context.newArray(global, ids.toArray());
        });
        ScriptCoreUtils.defineFunction(global, namespace, "exists", 1, (context, scope, thisObject, arguments) -> {
            int id = ScriptCoreUtils.readInteger(arguments, 0);
            SpritePool pool = this.getSpritePool();

            return id >= 0 && id < pool.getCapacity() && pool.isAllocated(id);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getGeneration", 1, (context, scope, thisObject, arguments) -> {
            SpritePool pool = this.getSpritePool();
            int id = ScriptCoreUtils.requireId("sprite slot", ScriptCoreUtils.readInteger(arguments, 0), pool.getCapacity());

            return (double) pool.getGeneration(id);
        });

        this.defineSpriteRead(namespace, global, "getType", Sprite::getSpriteTypeId);
        this.defineSpriteRead(namespace, global, "getX", Sprite::getX);
        this.defineSpriteRead(namespace, global, "getY", Sprite::getY);
        this.defineSpriteRead(namespace, global, "getOwner", Sprite::getOwner);
        this.defineSpriteRead(namespace, global, "getElevation", Sprite::getElevationLevel);
        this.defineSpriteRead(namespace, global, "getFlags", Sprite::getFlags);
        this.defineSpriteRead(namespace, global, "getVisibilityFlags", Sprite::getVisibilityFlags);
        this.defineSpriteRead(namespace, global, "getMainImage", sprite -> this.toImageId(sprite.getMainImage()));
        ScriptCoreUtils.defineFunction(global, namespace, "getImages", 1, (context, scope, thisObject, arguments) -> {
            Sprite sprite = this.readSprite(arguments, 0);
            SpriteImagePool pool = this.getImagePool();
            List<Object> ids = new ArrayList<>();

            for (SpriteImage image : sprite.getImages()) {
                if (image.getPool() == pool) {
                    ids.add((double) image.getIndex());
                }
            }

            return context.newArray(global, ids.toArray());
        });
        ScriptCoreUtils.defineFunction(global, namespace, "createEffect", 5, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Game game = this.scriptGame.getGame();
            int spriteType = ScriptCoreUtils.requireId("sprite type", ScriptCoreUtils.readInteger(arguments, 0), SpritesDat.SPRITE_COUNT);
            int x = ScriptCoreUtils.requireId("x", ScriptCoreUtils.readInteger(arguments, 1), game.getMap().getWidth() * TerrainTileset.TILE_SIZE);
            int y = ScriptCoreUtils.requireId("y", ScriptCoreUtils.readInteger(arguments, 2), game.getMap().getHeight() * TerrainTileset.TILE_SIZE);
            int owner = ScriptCoreUtils.requireId("player", ScriptCoreUtils.readInteger(arguments, 3), PlayerTable.PLAYER_SLOTS);
            int elevation = ScriptCoreUtils.requireId("elevation", ScriptCoreUtils.readInteger(arguments, 4), MAXIMUM_ELEVATION + 1);
            SpriteEffect effect = game.getIscriptExecutor().createSpriteEffect(spriteType, x, y, owner, this.scriptGame.getInstructionLimit());

            if (effect == null) {
                return (double) ScriptCoreUtils.NO_ID;
            }

            Sprite sprite = effect.getSprite();

            sprite.setElevationLevel(elevation);
            SpriteVisibility.updatePositionTile(sprite, game.getMap(), game.getLocalVision().getComparisonMask());

            return (double) sprite.getPoolIndex();
        });
    }

    /**
     * Defines the functions of {@code core.image}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void defineImages(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getCapacity", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.getImagePool().getCapacity());
        ScriptCoreUtils.defineFunction(global, namespace, "exists", 1, (context, scope, thisObject, arguments) -> {
            int id = ScriptCoreUtils.readInteger(arguments, 0);
            SpriteImagePool pool = this.getImagePool();

            return id >= 0 && id < pool.getCapacity() && pool.isAllocated(id);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getGeneration", 1, (context, scope, thisObject, arguments) -> {
            SpriteImagePool pool = this.getImagePool();
            int id = ScriptCoreUtils.requireId("image slot", ScriptCoreUtils.readInteger(arguments, 0), pool.getCapacity());

            return (double) pool.getGeneration(id);
        });

        this.defineImageRead(namespace, global, "getType", SpriteImage::getImageTypeId);
        this.defineImageRead(namespace, global, "getSprite", image -> {
            Sprite sprite = image.getSprite();

            return sprite == null ? ScriptCoreUtils.NO_ID : sprite.getPoolIndex();
        });
        this.defineImageRead(namespace, global, "getFrame", SpriteImage::getDrawnFrameIndex);
        this.defineImageRead(namespace, global, "getDirection", SpriteImage::getFrameIndexOffset);
        this.defineImageRead(namespace, global, "getDrawFunction", SpriteImage::getModifier);
        this.defineImageRead(namespace, global, "getFlags", SpriteImage::getFlags);
        this.defineImageRead(namespace, global, "getOffsetX", SpriteImage::getOffsetX);
        this.defineImageRead(namespace, global, "getOffsetY", SpriteImage::getOffsetY);
    }

    /**
     * Reads a sprite argument.
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the allocated sprite the argument names
     * @throws EcmaError if the argument is not an integer or names no allocated sprite
     */
    @NotNull Sprite readSprite(@NotNull Object @NotNull [] arguments, int index) {
        int id = ScriptCoreUtils.readInteger(arguments, index);
        SpritePool pool = this.getSpritePool();

        if (id < 0 || id >= pool.getCapacity() || !pool.isAllocated(id)) {
            throw ScriptRuntime.rangeError(Exceptions.format("The sprite {0} does not exist.", id));
        }

        return pool.getSprite(id);
    }

    /**
     * Reads an image argument.
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the allocated image of the image pool the argument names
     * @throws EcmaError if the argument is not an integer or names no allocated image
     */
    @NotNull SpriteImage readImage(@NotNull Object @NotNull [] arguments, int index) {
        int id = ScriptCoreUtils.readInteger(arguments, index);
        SpriteImagePool pool = this.getImagePool();

        if (id < 0 || id >= pool.getCapacity() || !pool.isAllocated(id)) {
            throw ScriptRuntime.rangeError(Exceptions.format("The image {0} does not exist.", id));
        }

        return pool.getImage(id);
    }

    /**
     * @param sprite a sprite, or {@code null}
     * @return the slot of the sprite while it is allocated, or {@code -1} for none
     */
    int toSpriteId(@Nullable Sprite sprite) {
        if (sprite == null) {
            return ScriptCoreUtils.NO_ID;
        }

        SpritePool pool = this.getSpritePool();
        int id = sprite.getPoolIndex();

        return id >= 0 && id < pool.getCapacity() && pool.getSprite(id) == sprite && pool.isAllocated(id) ? id : ScriptCoreUtils.NO_ID;
    }

    private int toImageId(@Nullable SpriteImage image) {
        return image == null || image.getPool() != this.getImagePool() ? ScriptCoreUtils.NO_ID : image.getIndex();
    }

    private void defineSpriteRead(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull String name,
                                  @NotNull ToIntFunction<Sprite> read) {
        ScriptCoreUtils.defineFunction(global, namespace, name, 1,
                                       (context, scope, thisObject, arguments) -> (double) read.applyAsInt(this.readSprite(arguments, 0)));
    }

    private void defineImageRead(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull String name,
                                 @NotNull ToIntFunction<SpriteImage> read) {
        ScriptCoreUtils.defineFunction(global, namespace, name, 1,
                                       (context, scope, thisObject, arguments) -> (double) read.applyAsInt(this.readImage(arguments, 0)));
    }

    private @NotNull SpritePool getSpritePool() {
        return this.scriptGame.getGame().getSpriteSystem().requireSpritePool();
    }

    private @NotNull SpriteImagePool getImagePool() {
        return this.scriptGame.getGame().getSpriteSystem().requireImagePool();
    }
}
