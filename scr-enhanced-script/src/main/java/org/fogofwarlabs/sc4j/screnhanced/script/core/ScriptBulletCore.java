package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.dat.WeaponsDat;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.game.combat.ProjectilePhase;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Defines {@code core.bullet}: the bullets of the bullet table by their entry, from {@code 0} to {@code getCapacity()} minus one.
 * <p>
 * An entry holds a bullet while the bullet is in the active list, from its creation until it is released after dying; {@code getGeneration(id)}
 * tells apart the bullets that one entry holds in turn: every bullet takes the next generation when it joins the active list.
 * {@code getActiveCount()} is the active count of the table and {@code getIds()} lists the active bullets in the order of the active list.
 * Every other function needs an active bullet and throws a {@code RangeError} for another id.
 * <p>
 * Reads of the bullet's fields: {@code getWeapon}, {@code getFlingyType}, {@code getX}, {@code getY}, {@code getPlayer}, {@code getSource}
 * (the unit that fired, {@code -1} once it is gone), {@code getTarget} ({@code -1} for none), {@code getTargetX}, {@code getTargetY} (the
 * center of the hit), {@code getState} ({@code 0} init, {@code 1} move, {@code 2} follow, {@code 3} bounce, {@code 4} damage over time,
 * {@code 5} dying, {@code 6} hit near the target), {@code getRemainingTime}, {@code getRemainingBounces}, {@code getDirection} and
 * {@code getSprite} ({@code -1} once its last image ended).
 * <p>
 * Writes of the fields, as an EUD write stores them, in shared code only: {@code setTarget(id, unit)} ({@code -1} for none; the follow, bounce
 * and hit-near-target states move toward the new target from the next frame and the hit damages it, the move state keeps its move target),
 * {@code setTargetPosition(id, x, y)} (the center of the hit; the move target does not change) and {@code setRemainingTime(id, frames)} (a BYTE
 * that the move and damage-over-time states count down). {@code create(weapon, source, x, y)} runs the bullet creation of the game at a map
 * position for a live unit, which aims the bullet at its order target ({@link ProjectilePhase#create}): the new bullet, or {@code -1} when
 * the game creates none (no free entry, no sprite, an attack the unit cannot make).
 */
final class ScriptBulletCore {
    /**
     * The largest remaining time of a bullet, a BYTE.
     */
    private static final int MAXIMUM_REMAINING_TIME = 0xFF;

    private final @NotNull ScriptGame scriptGame;
    private final @NotNull ScriptUnitCore unitCore;
    private final @NotNull ScriptSpriteCore spriteCore;

    /**
     * Creates a {@link ScriptBulletCore} instance.
     *
     * @param scriptGame the game binding
     * @param unitCore   the unit functions, which read unit arguments
     * @param spriteCore the sprite functions, which give the ids of sprites
     */
    ScriptBulletCore(@NotNull ScriptGame scriptGame, @NotNull ScriptUnitCore unitCore, @NotNull ScriptSpriteCore spriteCore) {
        this.scriptGame = scriptGame;
        this.unitCore   = unitCore;
        this.spriteCore = spriteCore;
    }

    /**
     * Defines the functions of {@code core.bullet}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void define(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getCapacity", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.getBullets().getCapacity());
        ScriptCoreUtils.defineFunction(global, namespace, "getActiveCount", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.getBullets().getActiveCount());
        ScriptCoreUtils.defineFunction(global, namespace, "getIds", 0, (context, scope, thisObject, arguments) -> {
            ProjectilePhase.Context bullets = this.getBullets();
            List<Object> ids = new ArrayList<>();

            for (int id = bullets.getFirstActive(); id >= 0; id = bullets.getNext(id)) {
                ids.add((double) id);
            }

            return context.newArray(global, ids.toArray());
        });
        ScriptCoreUtils.defineFunction(global, namespace, "exists", 1, (context, scope, thisObject, arguments) -> {
            int id = ScriptCoreUtils.readInteger(arguments, 0);
            ProjectilePhase.Context bullets = this.getBullets();

            return id >= 0 && id < bullets.getCapacity() && bullets.getBullet(id).isActive();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getGeneration", 1, (context, scope, thisObject, arguments) -> {
            ProjectilePhase.Context bullets = this.getBullets();
            int id = ScriptCoreUtils.requireId("bullet entry", ScriptCoreUtils.readInteger(arguments, 0), bullets.getCapacity());

            return (double) bullets.getBullet(id).getGeneration();
        });

        this.defineRead(namespace, global, "getWeapon", ProjectilePhase.Bullet::getWeapon);
        this.defineRead(namespace, global, "getFlingyType", ProjectilePhase.Bullet::getFlingyType);
        this.defineRead(namespace, global, "getX", ProjectilePhase.Bullet::getPositionX);
        this.defineRead(namespace, global, "getY", ProjectilePhase.Bullet::getPositionY);
        this.defineRead(namespace, global, "getPlayer", ProjectilePhase.Bullet::getOwner);
        this.defineRead(namespace, global, "getSource", bullet -> this.toUnitId(bullet.getSourceUnit()));
        this.defineRead(namespace, global, "getTarget", bullet -> this.toUnitId(bullet.getTargetUnit()));
        this.defineRead(namespace, global, "getTargetX", ProjectilePhase.Bullet::getTargetX);
        this.defineRead(namespace, global, "getTargetY", ProjectilePhase.Bullet::getTargetY);
        this.defineRead(namespace, global, "getState", ProjectilePhase.Bullet::getState);
        this.defineRead(namespace, global, "getRemainingTime", ProjectilePhase.Bullet::getRemainingTime);
        this.defineRead(namespace, global, "getRemainingBounces", ProjectilePhase.Bullet::getRemainingBounces);
        this.defineRead(namespace, global, "getDirection", ProjectilePhase.Bullet::getHeading);
        this.defineRead(namespace, global, "getSprite", bullet -> this.spriteCore.toSpriteId(bullet.getSprite()));

        this.defineWrites(namespace, global);
    }

    private void defineWrites(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "setTarget", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            ProjectilePhase.Bullet bullet = this.readBullet(arguments, 0);
            Unit target = ScriptCoreUtils.readInteger(arguments, 1) == ScriptCoreUtils.NO_ID ? null : this.unitCore.readUnit(arguments, 1);

            bullet.setTargetUnit(target);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setTargetPosition", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            ProjectilePhase.Bullet bullet = this.readBullet(arguments, 0);
            int x = this.readMapX(arguments, 1);
            int y = this.readMapY(arguments, 2);

            bullet.setTargetPosition(x, y);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setRemainingTime", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            ProjectilePhase.Bullet bullet = this.readBullet(arguments, 0);
            int frames = ScriptCoreUtils.requireId("remaining time", ScriptCoreUtils.readInteger(arguments, 1), MAXIMUM_REMAINING_TIME + 1);

            bullet.setRemainingTime(frames);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "create", 4, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int weapon = readWeapon(arguments, 0);
            Unit source = this.unitCore.readUnit(arguments, 1);
            int x = this.readMapX(arguments, 2);
            int y = this.readMapY(arguments, 3);
            Game game = this.scriptGame.getGame();
            ProjectilePhase.Bullet bullet = game.getProjectilePhase().create(source, weapon, x, y, this.scriptGame.getInstructionLimit());

            return (double) (bullet == null ? ScriptCoreUtils.NO_ID : bullet.getPoolIndex());
        });
    }

    /**
     * Reads a {@code weapons.dat} entry argument.
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the weapon
     * @throws EcmaError if the argument is not an integer or not a {@code weapons.dat} entry
     */
    static int readWeapon(@NotNull Object @NotNull [] arguments, int index) {
        return ScriptCoreUtils.requireId("weapon type", ScriptCoreUtils.readInteger(arguments, index), WeaponsDat.WEAPON_COUNT);
    }

    /**
     * Reads a bullet argument.
     *
     * @throws EcmaError if the argument is not an integer or names no active bullet
     */
    private @NotNull ProjectilePhase.Bullet readBullet(@NotNull Object @NotNull [] arguments, int index) {
        int id = ScriptCoreUtils.readInteger(arguments, index);
        ProjectilePhase.Context bullets = this.getBullets();

        if (id < 0 || id >= bullets.getCapacity() || !bullets.getBullet(id).isActive()) {
            throw ScriptRuntime.rangeError(Exceptions.format("The bullet {0} does not exist.", id));
        }

        return bullets.getBullet(id);
    }

    private void defineRead(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull String name,
                            @NotNull ToIntFunction<ProjectilePhase.Bullet> read) {
        ScriptCoreUtils.defineFunction(global, namespace, name, 1,
                                       (context, scope, thisObject, arguments) -> (double) read.applyAsInt(this.readBullet(arguments, 0)));
    }

    private int readMapX(@NotNull Object @NotNull [] arguments, int index) {
        int width = this.scriptGame.getGame().getMap().getWidth() * TerrainTileset.TILE_SIZE;

        return ScriptCoreUtils.requireId("x", ScriptCoreUtils.readInteger(arguments, index), width);
    }

    private int readMapY(@NotNull Object @NotNull [] arguments, int index) {
        int height = this.scriptGame.getGame().getMap().getHeight() * TerrainTileset.TILE_SIZE;

        return ScriptCoreUtils.requireId("y", ScriptCoreUtils.readInteger(arguments, index), height);
    }

    private int toUnitId(@Nullable Unit unit) {
        UnitPool pool = this.scriptGame.getUnitPool();

        return unit != null && ScriptUnitCore.isLive(pool, unit.getIndex()) ? unit.getIndex() : ScriptCoreUtils.NO_ID;
    }

    private @NotNull ProjectilePhase.Context getBullets() {
        return this.scriptGame.getGame().requireFrame().getProjectiles();
    }
}
