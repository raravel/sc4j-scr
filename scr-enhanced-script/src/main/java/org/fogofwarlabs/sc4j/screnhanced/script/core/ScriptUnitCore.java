package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUpgradeIds;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.ability.UnitSpells;
import org.fogofwarlabs.sc4j.scr.game.order.OrderPool;
import org.fogofwarlabs.sc4j.scr.game.order.ScrOrderSystem;
import org.fogofwarlabs.sc4j.scr.game.order.UnitOrderEligibility;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.LocalSelection;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerUnitActions;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitStatusFlags;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitTypeTraitsUtils;
import org.fogofwarlabs.sc4j.scr.game.unit.finder.UnitSpatialIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.function.ObjIntConsumer;
import java.util.function.ToIntFunction;

/**
 * Defines {@code core.unit}: the units by the index of their unit pool slot, from {@code 1} to {@code getCapacity()}.
 * <p>
 * A slot holds a live unit while it is allocated, has a sprite and does not run the Die order; {@code getGeneration(id)} tells apart the
 * units that one slot holds in turn. {@code getIds()} lists the live units in slot order. Every other function needs a live unit and throws a
 * {@code RangeError} for another id.
 * <p>
 * Reads: {@code getType}, {@code getOwner}, {@code getX}, {@code getY}, {@code getHp}, {@code getShields}, {@code getEnergy},
 * {@code getMaxEnergy} (raw {@code 1/256} units, the maximum after the energy upgrades), {@code getOrder}, {@code getTarget} ({@code -1}
 * without a target unit), {@code getTargetX}, {@code getTargetY}, {@code getStatus} (the status flags), {@code getImageType} (the image type
 * of the main image of the unit's sprite), {@code getSprite} (the slot of the unit's sprite in {@code core.sprite}, {@code -1} for none),
 * {@code getResources} (the resource amount of a resource container), {@code getHangarCount} (the Interceptors or Scarabs inside and outside
 * the hangar), {@code isCompleted}, {@code isCloaked}, {@code isBurrowed}, {@code isInvincible} and {@code isDisabled} (the disabled status or a
 * disabling status timer).
 * <p>
 * Writes of raw amounts: {@code setHp} (the hit point routine, at least {@code 1} and at most the maximum), {@code setShields} and
 * {@code setEnergy} (at most the maximum, {@code false} for a unit without shields or energy). Writes through the routines of the trigger
 * actions and the order system: {@code setHpPercent}, {@code setShieldsPercent} and {@code setEnergyPercent} (Modify Unit Hit Points, Shield
 * Points and Energy with a percentage of the maximum), {@code setInvincible} (Set Invincibility), {@code enableDoodad} and
 * {@code disableDoodad} (Set Doodad State), {@code setResources} (Modify Unit Resource Amount, from {@code 0} to {@value #MAXIMUM_RESOURCES};
 * {@code false} for a unit that is no resource container), {@code addHangarCount(id, count)} (Modify Unit Hanger Count: up to {@code count}
 * Interceptors or Scarabs while the hangar takes another one; {@code false} for a unit without a hangar), {@code create(type, owner, x, y)}
 * (Create Unit; the new id or {@code -1}), {@code kill} and {@code remove} (Kill Unit and Remove Unit), {@code damage(id, amount, source,
 * sourcePlayer)} (the damage routine after armor and shields: the raw amount comes off the hit points and a unit whose hit points run out
 * dies, its death and kill credited to the attacking unit {@code source} and player {@code sourcePlayer}, each {@code -1} for none; a source
 * unit is revealed to the damaged unit's owner as an attacker's is; {@code false} for a subunit), {@code move(id, x, y)} (Move Unit),
 * {@code give(id, player)} (Give Units to Player) and {@code issueOrder(id, order, target, x, y, queued)} (with {@code target} {@code -1} and
 * not queued the Order action to the position; queued or with a target unit the order system after the order requirement test, which appends
 * a queued order behind the queue of an order that takes one). A write that the routine refuses returns {@code false}.
 * <p>
 * The unit fields that EUD maps read and write, as raw values with a {@code get} and a {@code set} each. The status timers count status steps
 * of eight frames: {@code StimTimer} (refreshes the speed when it starts or ends), {@code EnsnareTimer}, {@code LockdownTimer},
 * {@code StasisTimer}, {@code MaelstromTimer}, {@code PlagueTimer} and {@code IrradiateTimer} (a timer that starts runs the effect's apply
 * routine, which adds the overlay and disables or slows the unit, then takes the value; {@code 0} runs the effect's end routine),
 * {@code DefensiveMatrixTimer} (starting adds the overlays, {@code 0} ends the matrix as its expiry does), {@code AcidSporeCount} (shows the
 * overlay of the count, {@code 0} removes the spores). The other fields take the value as an EUD write stores it: {@code DefensiveMatrixHp}
 * (raw {@code 1/256} units), {@code StormTimer}, {@code RemoveTimer} (frames until a timed unit is removed), {@code ParasiteFlags} and
 * {@code BlindFlags} (one bit per player), {@code GroundCooldown}, {@code AirCooldown} and {@code SpellCooldown} (frames), {@code KillCount},
 * {@code RemainingBuildTime} and {@code RemainingResearchTime} (frames of the current production, research or upgrade). Positions:
 * {@code getRallyX}, {@code getRallyY}, {@code getRallyUnit} ({@code -1} for none), {@code setRallyPosition(id, x, y)} and
 * {@code setRallyUnit(id, unit)} ({@code false} for a unit type without a rally point), {@code getMoveTargetX}, {@code getMoveTargetY} and
 * {@code setMoveTarget(id, x, y)}; {@code getDirection} and {@code setDirection} (the direction byte, turning the unit at once);
 * {@code getCurrentSpeed} and {@code getTopSpeed} ({@code 1/256} pixels per frame). Reads: {@code getBuildQueue} (the queued unit types from
 * the current one), {@code getResearch} and {@code getUpgrade} (the technology or upgrade in progress, {@code -1} for none) and
 * {@code getAddon} ({@code -1} for none). {@code setType(id, type)} changes the unit's type through the morph routine and returns
 * {@code false} for a subunit, a subunit type, or a type whose turret does not match the unit's.
 * <p>
 * Orders: {@code useWeapon(id, weapon)} (the use-weapon routine of the unit scripts: the bullets of the weapon toward the unit's order target,
 * two for a weapon of damage factor {@code 2}, none for a weapon without a flingy), {@code playAnimation(id, animation)} (the animation request
 * the orders make: every image of the unit's sprite switches to the iscript animation, from {@code 2} (GndAttkInit) to {@code 27} (Enable),
 * and runs it at once; an image whose request gate ignores the animation and a sprite inside a section that must not be broken keep theirs),
 * {@code getOrderQueue(id)} (the orders waiting behind the
 * current one, first to last, as records {@code order}, {@code target} and {@code targetGeneration} ({@code -1} without a live target unit),
 * {@code x}, {@code y} and {@code unitType} ({@code -1} for none)),
 * {@code getSecondaryOrder}, {@code getSecondaryOrderState} and {@code setSecondaryOrder(id, order)} (the native routine: a changed order
 * clears the state and the unit in production). {@code getButtonSet} and {@code setButtonSet} (the WORD of the unit's command card button set,
 * which the native routines of morphs, completion and burrowing write again). The construction fields as an EUD write stores them:
 * {@code HpGain} and {@code ShieldGain} (raw {@code 1/256} units added each construction frame) and {@code getPreviousHp} (the whole hit points
 * a morphing unit had, which a cancelled Zerg building gives back).
 * <p>
 * Sight: {@code getVisibilityFlags} (the players whose sight covers the unit's sprite, one bit per player), {@code getDetectionFlags} (the
 * players that detect the unit), {@code isVisibleTo(id, player)} and {@code isDetectedBy(id, player)} (the visibility system's tests: seen
 * and, when cloaked or burrowed, detected). {@code getAirStrength} and {@code getGroundStrength} are the unit's values for the computer
 * players. The unit finder, in shared code only because its searches use the finder's working state: {@code findInRectangle(left, top,
 * right, bottom)} (the units whose bounds meet the rectangle) and {@code findAtPoint(x, y)} (the units whose bounds hold the point), the live
 * units in the finder's order.
 */
final class ScriptUnitCore {
    /**
     * The fixed-point shift of hit points, shields and energy.
     */
    private static final int FIXED_POINT_SHIFT = 8;
    /**
     * The largest resource amount of a resource container, a WORD.
     */
    private static final int MAXIMUM_RESOURCES = 0xFFFF;
    /**
     * The largest value of a BYTE field, and the mask of a byte.
     */
    private static final int BYTE_MAXIMUM = 0xFF;
    /**
     * The largest value of a WORD field.
     */
    private static final int WORD_MAXIMUM = 0xFFFF;

    private final @NotNull ScriptGame scriptGame;

    /**
     * Creates a {@link ScriptUnitCore} instance.
     *
     * @param scriptGame the game binding
     */
    ScriptUnitCore(@NotNull ScriptGame scriptGame) {
        this.scriptGame = scriptGame;
    }

    /**
     * Checks whether a slot holds a live unit.
     *
     * @param pool the unit pool
     * @param id   the slot index
     * @return {@code true} if the slot is allocated, its unit has a sprite and does not run the Die order, {@code false} otherwise
     */
    static boolean isLive(@NotNull UnitPool pool, int id) {
        if (id < 1 || id > pool.getCapacity() || !pool.isAllocated(id)) {
            return false;
        }

        Unit unit = pool.getUnit(id);

        return unit.getSpriteOrNull() != null && unit.getPrimaryOrder() != ScrOrderIds.DIE;
    }

    /**
     * Creates the array of the live units of a selection: the units of its positions up to the first empty one, in position order, without the
     * units that are not live.
     *
     * @param context   the current context
     * @param global    the global scope
     * @param pool      the unit pool
     * @param selection the pool index at a position from {@code 0} to {@link UnitSelection#MAXIMUM_SELECTED} minus one, {@code 0} for an empty position
     * @return the array of the unit ids
     */
    static @NotNull Scriptable createSelectionIds(@NotNull Context context, @NotNull Scriptable global, @NotNull UnitPool pool,
                                                  @NotNull IntUnaryOperator selection) {
        List<Object> ids = new ArrayList<>();

        for (int position = 0; position < UnitSelection.MAXIMUM_SELECTED; position++) {
            int id = selection.applyAsInt(position);

            if (id == LocalSelection.EMPTY_SLOT) {
                break;
            }

            if (isLive(pool, id)) {
                ids.add((double) id);
            }
        }

        return context.newArray(global, ids.toArray());
    }

    /**
     * Defines the functions of {@code core.unit}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void define(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getCapacity", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.getPool().getCapacity());
        ScriptCoreUtils.defineFunction(global, namespace, "getIds", 0, (context, scope, thisObject, arguments) -> {
            UnitPool pool = this.getPool();
            List<Object> ids = new ArrayList<>();

            for (int id = 1; id <= pool.getCapacity(); id++) {
                if (isLive(pool, id)) {
                    ids.add((double) id);
                }
            }

            return context.newArray(global, ids.toArray());
        });
        ScriptCoreUtils.defineFunction(global, namespace, "exists", 1, (context, scope, thisObject, arguments) -> {
            int id = ScriptCoreUtils.readInteger(arguments, 0);

            return isLive(this.getPool(), id);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getGeneration", 1, (context, scope, thisObject, arguments) -> {
            UnitPool pool = this.getPool();
            int id = ScriptCoreUtils.readInteger(arguments, 0);

            if (id < 1 || id > pool.getCapacity()) {
                throw ScriptRuntime.rangeError(Exceptions.format("The unit slot {0} is not from 1 to {1}.", id, pool.getCapacity()));
            }

            return (double) pool.getGeneration(id);
        });

        this.defineReads(namespace, global);
        this.defineWrites(namespace, global);
        this.defineStatusFunctions(namespace, global);
        this.defineProductionFunctions(namespace, global);
        this.defineMovementFunctions(namespace, global);
        this.defineVisionFunctions(namespace, global);
        this.defineOrderFunctions(namespace, global);
        this.defineFinderFunctions(namespace, global);
    }

    private void defineReads(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        this.defineRead(namespace, global, "getType", Unit::getUnitId);
        this.defineRead(namespace, global, "getOwner", Unit::getOwnerIndex);
        this.defineRead(namespace, global, "getX", Unit::getPositionX);
        this.defineRead(namespace, global, "getY", Unit::getPositionY);
        this.defineRead(namespace, global, "getHp", Unit::getHitPoints);
        this.defineRead(namespace, global, "getShields", Unit::getShieldPoints);
        this.defineRead(namespace, global, "getEnergy", Unit::getEnergy);
        this.defineRead(namespace, global, "getMaxEnergy", this::getMaximumEnergy);
        this.defineRead(namespace, global, "getOrder", Unit::getPrimaryOrder);
        this.defineRead(namespace, global, "getTarget", unit -> {
            Unit target = unit.getOrderTargetUnit();

            return target != null && isLive(this.getPool(), target.getIndex()) ? target.getIndex() : ScriptCoreUtils.NO_ID;
        });
        this.defineRead(namespace, global, "getTargetX", Unit::getOrderTargetX);
        this.defineRead(namespace, global, "getTargetY", Unit::getOrderTargetY);
        this.defineRead(namespace, global, "getStatus", Unit::getStatus);
        this.defineRead(namespace, global, "getImageType", unit -> {
            Sprite sprite = unit.getSpriteOrNull();
            SpriteImage image = sprite == null ? null : sprite.getMainImage();

            return image == null ? ScriptCoreUtils.NO_ID : image.getImageTypeId();
        });
        this.defineRead(namespace, global, "getSprite", unit -> {
            Sprite sprite = unit.getSpriteOrNull();

            return sprite == null ? ScriptCoreUtils.NO_ID : sprite.getPoolIndex();
        });
        this.defineRead(namespace, global, "getResources", Unit::getResources);
        this.defineRead(namespace, global, "getHangarCount", unit -> unit.getHangarCount(false) + unit.getHangarCount(true));

        this.defineStatusRead(namespace, global, "isCompleted", UnitStatusFlags.COMPLETED);
        this.defineStatusRead(namespace, global, "isCloaked", UnitStatusFlags.CLOAKED | UnitStatusFlags.PASSIVELY_CLOAKED);
        this.defineStatusRead(namespace, global, "isBurrowed", UnitStatusFlags.BURROWED);
        this.defineStatusRead(namespace, global, "isInvincible", UnitStatusFlags.INVINCIBLE);
        ScriptCoreUtils.defineFunction(global, namespace, "isDisabled", 1,
                                       (context, scope, thisObject, arguments) -> UnitOrderEligibility.isDisabled(this.readUnit(arguments, 0)));
    }

    private void defineWrites(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "setHp", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int hitPoints = this.readAmount(arguments, 1);

            this.getGame().getUnitHealth().setHitPoints(unit, Math.max(hitPoints, 1), this.scriptGame.getInstructionLimit());

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setShields", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int shields = this.readAmount(arguments, 1);

            if (this.getUnitTypes().getShieldEnabled(unit.getUnitId()) == 0) {
                return false;
            }

            unit.setShieldPoints(Math.min(shields, this.getMaximumShields(unit)));

            return true;
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setEnergy", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int energy = this.readAmount(arguments, 1);

            if (!UnitTypeTraitsUtils.isSpellcaster(this.getUnitTypes(), unit)) {
                return false;
            }

            unit.setEnergy(Math.min(energy, this.getMaximumEnergy(unit)));

            return true;
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setHpPercent", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int percent = this.readAmount(arguments, 1);

            this.getGame().getTriggerUnitActions().modifyHitPoints(unit, percent, this.scriptGame.getInstructionLimit());

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setShieldsPercent", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);

            return this.getGame().getTriggerUnitActions().modifyShields(unit, this.readAmount(arguments, 1));
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setEnergyPercent", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);

            return this.getGame().getTriggerUnitActions().modifyEnergy(unit, this.readAmount(arguments, 1));
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setInvincible", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            boolean invincible = ScriptCoreUtils.readBoolean(arguments, 1);

            this.getGame().getTriggerUnitActions().setInvincible(unit, invincible);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "enableDoodad", 1, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);

            this.getGame().getTriggerUnitActions().enableDoodad(unit, this.scriptGame.getInstructionLimit());

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "disableDoodad", 1, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);

            this.getGame().getTriggerUnitActions().disableDoodad(unit, this.scriptGame.getInstructionLimit());

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setResources", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int amount = this.readAmount(arguments, 1);

            if (amount > MAXIMUM_RESOURCES) {
                throw ScriptRuntime.rangeError(Exceptions.format("The resource amount {0} is above {1}.", amount, MAXIMUM_RESOURCES));
            }

            return this.getGame().getTriggerUnitActions().modifyResources(unit, amount, this.scriptGame.getInstructionLimit());
        });
        ScriptCoreUtils.defineFunction(global, namespace, "addHangarCount", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int count = this.readAmount(arguments, 1);

            return this.getGame().getTriggerUnitActions().modifyHangar(unit, count, this.scriptGame.getInstructionLimit());
        });

        ScriptCoreUtils.defineFunction(global, namespace, "create", 4, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            return (double) this.createUnit(arguments);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "kill", 1, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            return this.removeUnit(this.readUnit(arguments, 0), false);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "damage", 4, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int amount = this.readAmount(arguments, 1);
            Unit source = ScriptCoreUtils.readInteger(arguments, 2) == ScriptCoreUtils.NO_ID ? null : this.readUnit(arguments, 2);
            int sourcePlayer = ScriptCoreUtils.readInteger(arguments, 3);

            if (sourcePlayer != ScriptCoreUtils.NO_ID) {
                ScriptCoreUtils.requireId("player", sourcePlayer, PlayerTable.PLAYER_SLOTS);
            }

            if ((this.getUnitTypes().getFlags(unit.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0) {
                return false;
            }

            this.getGame().getUnitDamage().dealUnitDamage(unit, amount, source, sourcePlayer, source != null, this.scriptGame.getInstructionLimit());

            return true;
        });
        ScriptCoreUtils.defineFunction(global, namespace, "remove", 1, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            return this.removeUnit(this.readUnit(arguments, 0), true);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "move", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);

            return this.moveUnit(unit, ScriptCoreUtils.readInteger(arguments, 1), ScriptCoreUtils.readInteger(arguments, 2));
        });
        ScriptCoreUtils.defineFunction(global, namespace, "give", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int owner = ScriptCoreUtils.requireId("player", ScriptCoreUtils.readInteger(arguments, 1), PlayerTable.PLAYER_SLOTS);

            return this.getGame().getUnitOwnership().giveUnit(unit, owner, this.scriptGame.getInstructionLimit());
        });
        ScriptCoreUtils.defineFunction(global, namespace, "issueOrder", 6, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();
            this.issueOrder(arguments);

            return Context.getUndefinedValue();
        });
    }

    /**
     * Defines the status timers, the cooldowns and the kill count.
     */
    private void defineStatusFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        UnitSpells spells = this.getGame().getUnitSpells();

        ScriptCoreUtils.defineFunction(global, namespace, "getStimTimer", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.readUnit(arguments, 0).getSpeedIncreaseByte());
        ScriptCoreUtils.defineFunction(global, namespace, "setStimTimer", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int timer = this.readValue(arguments, 1, BYTE_MAXIMUM);
            int previous = unit.getSpeedIncreaseByte();

            unit.setSpeedIncreaseByte(timer);

            if ((previous == 0) != (timer == 0)) {
                this.getGame().getEngineGame().getMovement().recalculateSpeed(unit, this.scriptGame.getInstructionLimit());
            }

            return Context.getUndefinedValue();
        });
        this.defineStatusTimer(namespace, global, "EnsnareTimer", Unit::getSpeedDecreaseByte, Unit::setSpeedDecreaseByte, spells::applyEnsnare,
                               spells::removeEnsnare);
        this.defineStatusTimer(namespace, global, "LockdownTimer", Unit::getLockdownTimer, Unit::setLockdownTimer, spells::applyLockdown,
                               spells::removeLockdown);
        this.defineStatusTimer(namespace, global, "StasisTimer", Unit::getStasisTimer, Unit::setStasisTimer, spells::applyStasis,
                               spells::removeStasis);
        this.defineStatusTimer(namespace, global, "MaelstromTimer", Unit::getMaelstromTimer, Unit::setMaelstromTimer, spells::applyMaelstrom,
                               spells::removeMaelstrom);
        this.defineStatusTimer(namespace, global, "PlagueTimer", Unit::getPlagueTimer, Unit::setPlagueTimer, spells::applyPlague,
                               (unit, instructionLimit) -> spells.removePlague(unit));
        this.defineStatusTimer(namespace, global, "IrradiateTimer", Unit::getIrradiateTimer, Unit::setIrradiateTimer,
                               (unit, instructionLimit) -> spells.applyIrradiation(unit.getIrradiatedBy(), unit.getIrradiateOwner(), unit,
                                                                                   instructionLimit),
                               (unit, instructionLimit) -> spells.removeIrradiate(unit));

        ScriptCoreUtils.defineFunction(global, namespace, "getDefensiveMatrixTimer", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.readUnit(arguments, 0).getDefensiveMatrixTimer());
        ScriptCoreUtils.defineFunction(global, namespace, "setDefensiveMatrixTimer", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int timer = this.readValue(arguments, 1, BYTE_MAXIMUM);
            int previous = unit.getDefensiveMatrixTimer();

            if (timer == 0) {
                if (previous != 0) {
                    spells.dealDefensiveMatrixDamage(unit, unit.getDefensiveMatrixHealth(), this.scriptGame.getInstructionLimit());
                }

                return Context.getUndefinedValue();
            }

            unit.setDefensiveMatrixTimer(timer);

            if (previous == 0) {
                spells.createDefensiveMatrixImage(unit, this.scriptGame.getInstructionLimit());
            }

            return Context.getUndefinedValue();
        });
        this.defineFieldWrite(namespace, global, "DefensiveMatrixHp", Unit::getDefensiveMatrixHealth, Unit::setDefensiveMatrixHealth, WORD_MAXIMUM);

        ScriptCoreUtils.defineFunction(global, namespace, "getAcidSporeCount", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.readUnit(arguments, 0).getAcidSporeCount());
        ScriptCoreUtils.defineFunction(global, namespace, "setAcidSporeCount", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int count = this.readValue(arguments, 1, BYTE_MAXIMUM);

            if (count == 0) {
                if (unit.getAcidSporeCount() != 0) {
                    spells.removeAcidSpores(unit);
                }

                return Context.getUndefinedValue();
            }

            unit.setAcidSporeCount(count);
            spells.updateAcidSporeImage(unit, this.scriptGame.getInstructionLimit());

            return Context.getUndefinedValue();
        });

        this.defineFieldWrite(namespace, global, "StormTimer", Unit::getStormTimer, Unit::setStormTimer, BYTE_MAXIMUM);
        this.defineFieldWrite(namespace, global, "RemoveTimer", Unit::getRemainingLifetime, Unit::setRemainingLifetime, WORD_MAXIMUM);
        this.defineFieldWrite(namespace, global, "ParasiteFlags", Unit::getParasiteFlags, Unit::setParasiteFlags, BYTE_MAXIMUM);
        this.defineFieldWrite(namespace, global, "BlindFlags", Unit::getBlindnessState, Unit::setBlindnessState, BYTE_MAXIMUM);
        this.defineCooldown(namespace, global, "GroundCooldown", Unit.GROUND_COOLDOWN_SHIFT);
        this.defineCooldown(namespace, global, "AirCooldown", Unit.AIR_COOLDOWN_SHIFT);
        this.defineCooldown(namespace, global, "SpellCooldown", Unit.SPELL_COOLDOWN_SHIFT);
        this.defineFieldWrite(namespace, global, "KillCount", Unit::getKillCount, Unit::setKillCount, BYTE_MAXIMUM);
    }

    /**
     * Defines the build queue, the remaining times, the research, the upgrade, the add-on, the rally point and the unit type change.
     */
    private void defineProductionFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getBuildQueue", 1, (context, scope, thisObject, arguments) -> {
            Unit unit = this.readUnit(arguments, 0);
            List<Object> types = new ArrayList<>();

            for (int offset = 0; offset < Unit.BUILD_QUEUE_SLOTS; offset++) {
                int type = unit.getBuildQueueUnitType(Math.floorMod(unit.getBuildQueueIndex() + offset, Unit.BUILD_QUEUE_SLOTS));

                if (type == ScrUnitIds.NONE) {
                    break;
                }

                types.add((double) type);
            }

            return context.newArray(global, types.toArray());
        });
        this.defineFieldWrite(namespace, global, "RemainingBuildTime", Unit::getRemainingBuildTime, Unit::setRemainingBuildTime, WORD_MAXIMUM);
        this.defineFieldWrite(namespace, global, "RemainingResearchTime", Unit::getUpgradeResearchTime, Unit::setUpgradeResearchTime, WORD_MAXIMUM);
        this.defineRead(namespace, global, "getResearch",
                        unit -> unit.getResearchType() < ScrTechIds.NONE ? unit.getResearchType() : ScriptCoreUtils.NO_ID);
        this.defineRead(namespace, global, "getUpgrade",
                        unit -> unit.getUpgradeType() < ScrUpgradeIds.NONE ? unit.getUpgradeType() : ScriptCoreUtils.NO_ID);
        this.defineRead(namespace, global, "getAddon", unit -> this.toId(unit.getAddon()));

        this.defineRead(namespace, global, "getRallyX", Unit::getRallyPositionX);
        this.defineRead(namespace, global, "getRallyY", Unit::getRallyPositionY);
        this.defineRead(namespace, global, "getRallyUnit", unit -> this.toId(unit.getRallyTargetUnit()));
        ScriptCoreUtils.defineFunction(global, namespace, "setRallyPosition", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int positionX = this.readValue(arguments, 1, WORD_MAXIMUM);
            int positionY = this.readValue(arguments, 2, WORD_MAXIMUM);

            if (!this.getUnitTypes().isRallyProducer(unit.getUnitId())) {
                return false;
            }

            unit.setRallyPositionX(positionX);
            unit.setRallyPositionY(positionY);

            return true;
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setRallyUnit", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            Unit target = ScriptCoreUtils.readInteger(arguments, 1) == ScriptCoreUtils.NO_ID ? null : this.readUnit(arguments, 1);

            if (!this.getUnitTypes().isRallyProducer(unit.getUnitId())) {
                return false;
            }

            unit.setRallyTargetUnit(target);

            return true;
        });

        ScriptCoreUtils.defineFunction(global, namespace, "setType", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int type = ScriptCoreUtils.requireId("unit type", ScriptCoreUtils.readInteger(arguments, 1), ScrUnitIds.NONE);

            return this.changeType(unit, type);
        });
    }

    /**
     * Defines the direction, the speeds and the movement target.
     */
    private void defineMovementFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        this.defineRead(namespace, global, "getDirection", Unit::getHeading);
        ScriptCoreUtils.defineFunction(global, namespace, "setDirection", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int direction = this.readValue(arguments, 1, BYTE_MAXIMUM);

            unit.resetDirection(this.getGame(), direction);

            return Context.getUndefinedValue();
        });
        this.defineRead(namespace, global, "getCurrentSpeed", Unit::getCurrentSpeed);
        this.defineRead(namespace, global, "getTopSpeed", Unit::getTopSpeed);
        this.defineRead(namespace, global, "getMoveTargetX", Unit::getMovementDestinationX);
        this.defineRead(namespace, global, "getMoveTargetY", Unit::getMovementDestinationY);
        ScriptCoreUtils.defineFunction(global, namespace, "setMoveTarget", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int positionX = this.readValue(arguments, 1, WORD_MAXIMUM);
            int positionY = this.readValue(arguments, 2, WORD_MAXIMUM);

            unit.setMovementDestinationX(positionX);
            unit.setMovementDestinationY(positionY);

            return Context.getUndefinedValue();
        });
    }

    /**
     * Defines the read and the write of a status timer with the native routines of its effect: a timer that starts runs the effect's apply
     * routine (overlay, disabling, speed) and then takes the value, a timer set to {@code 0} runs the effect's end routine, any other value
     * replaces the timer.
     */
    private void defineStatusTimer(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull String name, @NotNull ToIntFunction<Unit> read,
                                   @NotNull ObjIntConsumer<Unit> write, @NotNull ObjIntConsumer<Unit> apply, @NotNull ObjIntConsumer<Unit> remove) {
        this.defineRead(namespace, global, "get" + name, read);
        ScriptCoreUtils.defineFunction(global, namespace, "set" + name, 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int timer = this.readValue(arguments, 1, BYTE_MAXIMUM);
            int previous = read.applyAsInt(unit);

            if (timer == 0) {
                if (previous != 0) {
                    remove.accept(unit, this.scriptGame.getInstructionLimit());
                }

                return Context.getUndefinedValue();
            }

            if (previous == 0) {
                apply.accept(unit, this.scriptGame.getInstructionLimit());
            }

            write.accept(unit, timer);

            return Context.getUndefinedValue();
        });
    }

    /**
     * Defines the read and the write of a field that no routine keeps, which the write stores as an EUD write does.
     */
    private void defineFieldWrite(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull String name, @NotNull ToIntFunction<Unit> read,
                                  @NotNull ObjIntConsumer<Unit> write, int maximum) {
        this.defineRead(namespace, global, "get" + name, read);
        ScriptCoreUtils.defineFunction(global, namespace, "set" + name, 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int value = this.readValue(arguments, 1, maximum);

            write.accept(unit, value);

            return Context.getUndefinedValue();
        });
    }

    /**
     * Defines the read and the write of a cooldown byte of the timer DWORD.
     */
    private void defineCooldown(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull String name, int shift) {
        this.defineFieldWrite(namespace, global, name, unit -> unit.getCooldownTimers() >>> shift & BYTE_MAXIMUM,
                              (unit, value) -> unit.setCooldownTimers(unit.getCooldownTimers() & ~(BYTE_MAXIMUM << shift) | value << shift),
                              BYTE_MAXIMUM);
    }

    /**
     * Defines a read of a live unit that returns an integer.
     */
    private void defineRead(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull String name,
                            @NotNull ToIntFunction<Unit> read) {
        ScriptCoreUtils.defineFunction(global, namespace, name, 1,
                                       (context, scope, thisObject, arguments) -> (double) read.applyAsInt(this.readUnit(arguments, 0)));
    }

    /**
     * Defines a read of a live unit that tests status flags.
     */
    private void defineStatusRead(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull String name, int flags) {
        ScriptCoreUtils.defineFunction(global, namespace, name, 1,
                                       (context, scope, thisObject, arguments) -> (this.readUnit(arguments, 0).getStatus() & flags) != 0);
    }

    private @NotNull Game getGame() {
        return this.scriptGame.getGame();
    }

    private @NotNull UnitPool getPool() {
        return this.getGame().getUnitSystem().requireUnitPool();
    }

    private @NotNull ScrUnitTypeView getUnitTypes() {
        return this.getGame().requireGameData().getUnitTypes();
    }

    /**
     * Reads a unit argument.
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the live unit the argument names
     * @throws EcmaError if the argument is not an integer or names no live unit
     */
    @NotNull Unit readUnit(@NotNull Object @NotNull [] arguments, int index) {
        int id = ScriptCoreUtils.readInteger(arguments, index);
        UnitPool pool = this.getPool();

        if (!isLive(pool, id)) {
            throw ScriptRuntime.rangeError(Exceptions.format("The unit {0} does not exist.", id));
        }

        return pool.getUnit(id);
    }

    /**
     * Reads a raw amount of hit points, shields or energy.
     *
     * @throws EcmaError if the argument is not an integer or is negative
     */
    private int readAmount(@NotNull Object @NotNull [] arguments, int index) {
        int amount = ScriptCoreUtils.readInteger(arguments, index);

        if (amount < 0) {
            throw ScriptRuntime.rangeError(Exceptions.format("The amount {0} is negative.", amount));
        }

        return amount;
    }

    /**
     * Reads a value of a unit field.
     *
     * @throws EcmaError if the argument is not an integer or is outside {@code 0} to the maximum
     */
    private int readValue(@NotNull Object @NotNull [] arguments, int index, int maximum) {
        int value = ScriptCoreUtils.readInteger(arguments, index);

        if (value < 0 || value > maximum) {
            throw ScriptRuntime.rangeError(Exceptions.format("The value {0} is not from 0 to {1}.", value, maximum));
        }

        return value;
    }

    /**
     * @return the id of a live unit, or {@code -1} for none or a unit that is gone
     */
    private int toId(@Nullable Unit unit) {
        return unit != null && isLive(this.getPool(), unit.getIndex()) ? unit.getIndex() : ScriptCoreUtils.NO_ID;
    }

    /**
     * Changes a unit's type through the morph routine, which keeps the unit, its owner, order and links, scales its hit points and rebuilds its
     * sprite and status overlays.
     *
     * @return {@code true} if the routine took the unit, {@code false} for a subunit, a subunit type, or a type with a turret for a unit without one or the reverse
     */
    private boolean changeType(@NotNull Unit unit, int type) {
        ScrUnitTypeView units = this.getUnitTypes();
        boolean turret = unit.getSubunit() != null;

        if ((units.getFlags(unit.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0 || (units.getFlags(type) & UnitTypeFlags.SUBUNIT) != 0 ||
            turret != (units.getFirstSubunit(type) != ScrUnitIds.NONE)) {
            return false;
        }

        this.getGame().getUnitMorphing().morph(unit, type, this.scriptGame.getInstructionLimit());

        return true;
    }

    private int getMaximumShields(@NotNull Unit unit) {
        ScrUnitTypeView units = this.getUnitTypes();

        return units.getShieldEnabled(unit.getUnitId()) != 0 ? units.getShieldAmount(unit.getUnitId()) << FIXED_POINT_SHIFT : 0;
    }

    private int getMaximumEnergy(@NotNull Unit unit) {
        ScrUnitTypeView units = this.getUnitTypes();

        return UnitTypeTraitsUtils.isSpellcaster(units, unit) ? this.getGame().getUnitInitializer().getMaximumEnergy(unit, units) : 0;
    }

    /**
     * Creates a unit as Create Unit does for one player: a mineral field or a Vespene Geyser belongs to the neutral player.
     *
     * @return the id of the created unit, or {@code -1} if the routine creates none
     */
    private int createUnit(@NotNull Object @NotNull [] arguments) {
        int type = ScriptCoreUtils.requireId("unit type", ScriptCoreUtils.readInteger(arguments, 0), ScrUnitIds.NONE);
        int owner = ScriptCoreUtils.requireId("player", ScriptCoreUtils.readInteger(arguments, 1), PlayerTable.PLAYER_SLOTS);
        int positionX = ScriptCoreUtils.readInteger(arguments, 2);
        int positionY = ScriptCoreUtils.readInteger(arguments, 3);

        if (this.getUnitTypes().isMineralField(type) || type == ScrUnitIds.RESOURCE_VESPENE_GEYSER) {
            owner = PlayerTable.NEUTRAL_PLAYER_ID;
        }

        Unit unit = this.getGame().getTriggerUnitActions().createUnit(type, positionX, positionY, owner, this.scriptGame.getInstructionLimit());

        return unit != null ? unit.getIndex() : ScriptCoreUtils.NO_ID;
    }

    /**
     * Kills or removes a unit as Kill Unit and Remove Unit do without a location; a subunit stays with its unit.
     *
     * @return {@code true} if the routine took the unit, {@code false} for a subunit
     */
    private boolean removeUnit(@NotNull Unit unit, boolean remove) {
        if ((this.getUnitTypes().getFlags(unit.getUnitId()) & UnitTypeFlags.SUBUNIT) != 0) {
            return false;
        }

        this.getGame().getTriggerUnitActions().removeUnit(unit, remove, false);

        return true;
    }

    /**
     * Moves a unit as Move Unit does.
     *
     * @return {@code true} if Move Unit takes the unit, {@code false} otherwise
     */
    private boolean moveUnit(@NotNull Unit unit, int positionX, int positionY) {
        TriggerUnitActions actions = this.getGame().getTriggerUnitActions();

        if (!actions.canMoveUnit(unit)) {
            return false;
        }

        actions.moveUnit(unit, positionX, positionY, this.scriptGame.getInstructionLimit());

        return true;
    }

    /**
     * Issues an order: an order that replaces the queue to a position as the Order action gives it; a queued order, and an order to a target
     * unit, through the order system after the order requirement test for the unit's owner.
     */
    private void issueOrder(@NotNull Object @NotNull [] arguments) {
        Unit unit = this.readUnit(arguments, 0);
        int order = ScriptCoreUtils.requireId("order", ScriptCoreUtils.readInteger(arguments, 1), ScrOrderIds.NONE);
        Unit target = ScriptCoreUtils.readInteger(arguments, 2) == ScriptCoreUtils.NO_ID ? null : this.readUnit(arguments, 2);
        int positionX = ScriptCoreUtils.readInteger(arguments, 3);
        int positionY = ScriptCoreUtils.readInteger(arguments, 4);
        boolean queued = ScriptCoreUtils.readBoolean(arguments, 5);

        if (order == ScrOrderIds.CLOAK) {
            throw ScriptRuntime.rangeError(Exceptions.format("The order {0} cannot be issued.", order));
        }

        int instructionLimit = this.scriptGame.getInstructionLimit();

        if (target == null && !queued) {
            this.getGame().getTriggerUnitActions().issueUnitOrder(unit, order, positionX, positionY, instructionLimit);
        } else if (this.getGame().getUnitOrderEligibility().canReceiveOrder(unit, order, unit.getOwnerIndex())) {
            this.getGame().getOrders().issue(unit, order, target, positionX, positionY, ScrUnitIds.NONE, queued, instructionLimit);
        }
    }

    /**
     * Defines the sight and detection of a unit and its strengths for the computer players.
     */
    private void defineVisionFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getVisibilityFlags", 1, (context, scope, thisObject, arguments) -> {
            Unit unit = this.readUnit(arguments, 0);

            return (double) Integer.toUnsignedLong(unit.requireSprite().getVisibilityFlags());
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getDetectionFlags", 1, (context, scope, thisObject, arguments) -> {
            Unit unit = this.readUnit(arguments, 0);

            return (double) Integer.toUnsignedLong(unit.getDetectionFlags());
        });
        ScriptCoreUtils.defineFunction(global, namespace, "isVisibleTo", 2, (context, scope, thisObject, arguments) -> {
            Unit unit = this.readUnit(arguments, 0);
            int player = ScriptCoreUtils.requireId("player", ScriptCoreUtils.readInteger(arguments, 1), PlayerTable.PLAYER_SLOTS);
            org.fogofwarlabs.sc4j.engine.game.Game engine = this.getGame().getEngineGame();

            return engine.getVisibility().isUnitVisible(engine.getPlayer(player), unit);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "isDetectedBy", 2, (context, scope, thisObject, arguments) -> {
            Unit unit = this.readUnit(arguments, 0);
            int player = ScriptCoreUtils.requireId("player", ScriptCoreUtils.readInteger(arguments, 1), PlayerTable.PLAYER_SLOTS);
            org.fogofwarlabs.sc4j.engine.game.Game engine = this.getGame().getEngineGame();

            return engine.getVisibility().isDetected(engine.getPlayer(player), unit);
        });
        this.defineRead(namespace, global, "getAirStrength", Unit::getAirStrength);
        this.defineRead(namespace, global, "getGroundStrength", Unit::getGroundStrength);
    }

    /**
     * Defines the order queue, the secondary order and the construction gains of a unit.
     */
    private void defineOrderFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "useWeapon", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int weapon = ScriptBulletCore.readWeapon(arguments, 1);

            this.getGame().getProjectilePhase().useWeapon(unit, weapon, this.scriptGame.getInstructionLimit());

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "playAnimation", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int animation = ScriptCoreUtils.readInteger(arguments, 1);

            if (animation <= IscriptExecutor.ANIMATION_DEATH || animation > IscriptExecutor.ANIMATION_ENABLE) {
                throw ScriptRuntime.rangeError(Exceptions.format("The animation {0} is not from {1} to {2}.", animation,
                                                                 IscriptExecutor.ANIMATION_DEATH + 1, IscriptExecutor.ANIMATION_ENABLE));
            }

            this.getGame().getIscriptExecutor().runSpriteAnimation(unit.requireSprite(), unit, animation, 0, this.scriptGame.getInstructionLimit());

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getOrderQueue", 1, (context, scope, thisObject, arguments) -> {
            Unit unit = this.readUnit(arguments, 0);
            UnitPool pool = this.getPool();
            List<Object> records = new ArrayList<>();

            for (OrderPool.Order order = unit.getFirstOrder(); order != null; order = order.getNext()) {
                ScriptableObject record = (ScriptableObject) context.newObject(global);
                Unit target = order.getTargetUnit();
                boolean live = target != null && isLive(pool, target.getIndex());

                record.put("order", record, (double) order.getOrderId());
                record.put("target", record, (double) (live ? target.getIndex() : ScriptCoreUtils.NO_ID));
                record.put("targetGeneration", record, (double) (live ? pool.getGeneration(target.getIndex()) : ScriptCoreUtils.NO_ID));
                record.put("x", record, (double) order.getTargetX());
                record.put("y", record, (double) order.getTargetY());
                record.put("unitType", record, (double) (order.getUnitType() < ScrUnitIds.NONE ? order.getUnitType() : ScriptCoreUtils.NO_ID));
                records.add(ScriptCoreUtils.freeze(record));
            }

            return context.newArray(global, records.toArray());
        });

        this.defineRead(namespace, global, "getSecondaryOrder", Unit::getSecondaryOrder);
        this.defineRead(namespace, global, "getSecondaryOrderState", Unit::getSecondaryOrderState);
        ScriptCoreUtils.defineFunction(global, namespace, "setSecondaryOrder", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Unit unit = this.readUnit(arguments, 0);
            int order = ScriptCoreUtils.requireId("order", ScriptCoreUtils.readInteger(arguments, 1), ScrOrderIds.NONE);

            ScrOrderSystem.setSecondaryOrder(unit, order);

            return Context.getUndefinedValue();
        });

        this.defineFieldWrite(namespace, global, "ButtonSet", Unit::getCurrentButtonSet, Unit::setCurrentButtonSet, WORD_MAXIMUM);
        this.defineFieldWrite(namespace, global, "HpGain", Unit::getConstructionHitPointIncrement, Unit::setConstructionHitPointIncrement,
                              WORD_MAXIMUM);
        this.defineFieldWrite(namespace, global, "ShieldGain", Unit::getConstructionShieldIncrement, Unit::setConstructionShieldIncrement,
                              WORD_MAXIMUM);
        this.defineRead(namespace, global, "getPreviousHp", Unit::getPreviousVisibleHitPoints);
    }

    /**
     * Defines the searches of the unit finder, which share the finder's working state with the simulation and so run in shared code only.
     */
    private void defineFinderFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "findInRectangle", 4, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkShared();

            int left = ScriptCoreUtils.readInteger(arguments, 0);
            int top = ScriptCoreUtils.readInteger(arguments, 1);
            int right = ScriptCoreUtils.readInteger(arguments, 2);
            int bottom = ScriptCoreUtils.readInteger(arguments, 3);
            UnitSpatialIndex finder = this.requireFinder();

            if (!finder.hasQueryBounds()) {
                throw ScriptRuntime.typeError("The unit finder has no query bounds.");
            }

            return this.createLiveIds(context, global, finder.findAllInRectangle(this.getUnitTypes(), left, top, right, bottom));
        });
        ScriptCoreUtils.defineFunction(global, namespace, "findAtPoint", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkShared();

            int x = ScriptCoreUtils.readInteger(arguments, 0);
            int y = ScriptCoreUtils.readInteger(arguments, 1);

            return this.createLiveIds(context, global, this.requireFinder().findAllAtPoint(x, y));
        });
    }

    /**
     * @return the unit finder of the game
     * @throws EcmaError if the game has none
     */
    private @NotNull UnitSpatialIndex requireFinder() {
        UnitSpatialIndex finder = this.getGame().getUnitSystem().getUnitSpatialIndex();

        if (finder == null) {
            throw ScriptRuntime.typeError("The game has no unit finder.");
        }

        return finder;
    }

    /**
     * Creates the array of the live units among the pool indices of a search, in search order.
     */
    private @NotNull Scriptable createLiveIds(@NotNull Context context, @NotNull Scriptable global, int @NotNull [] indices) {
        UnitPool pool = this.getPool();
        List<Object> ids = new ArrayList<>();

        for (int id : indices) {
            if (isLive(pool, id)) {
                ids.add((double) id);
            }
        }

        return context.newArray(global, ids.toArray());
    }
}
