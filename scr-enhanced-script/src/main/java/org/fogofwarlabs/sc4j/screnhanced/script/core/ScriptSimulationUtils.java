package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.*;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitHandle;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * Converts the SC:R simulation events and decisions to the records of {@code core.events} and of the {@code decide} entry point, and copies
 * the values a decision hook left in a decision record back into the decision.
 * <p>
 * A record is a plain object of numbers and booleans with the {@code kind} of the event or decision: a unit is its slot id under its name and
 * the slot's generation under the name followed by {@code Generation} ({@code -1} for no unit), the main unit of an event also its captured
 * {@code unitType} and {@code owner}; a bullet is its entry of the bullet table under {@code bullet} and the generation it had then under
 * {@code bulletGeneration}; players, types and orders are their ids ({@code -1} for none); hit points are raw {@code 1/256} units. A
 * decision value that is missing, not an integer or out of its range in the record leaves the decision's value as it is.
 */
final class ScriptSimulationUtils {
    private static final @NotNull String KIND_PROPERTY = "kind";
    private static final @NotNull String GENERATION_SUFFIX = "Generation";

    /**
     * Creates the record of an event.
     *
     * @param context the current context
     * @param global  the global scope
     * @param pool    the unit pool
     * @param kind    the kind of the event
     * @param event   the event
     * @param frame   the frame the event happened in
     * @return the record
     */
    static @NotNull ScriptableObject createEventRecord(@NotNull Context context, @NotNull Scriptable global, @NotNull UnitPool pool,
                                                       @NotNull ScriptEventKind kind, @NotNull SimulationEvent event, int frame) {
        ScriptableObject values = (ScriptableObject) context.newObject(global);

        putNumber(values, KIND_PROPERTY, kind.ordinal());
        putNumber(values, "frame", frame);

        switch (event) {
            case UnitCreatedEvent created -> putMainUnit(values, pool, created.getUnit());
            case UnitCompletedEvent completed -> putMainUnit(values, pool, completed.getUnit());
            case UnitDiedEvent died -> {
                putMainUnit(values, pool, died.getUnit());
                putUnit(values, pool, "killer", died.getKiller());
                putNumber(values, "killerPlayer", died.getKillerPlayer());
                putNumber(values, "weapon", died.getWeapon());
            }
            case UnitDamagedEvent damaged -> {
                putMainUnit(values, pool, damaged.getTarget());
                putUnit(values, pool, "source", damaged.getSource());
                putNumber(values, "sourcePlayer", damaged.getSourcePlayer());
                putNumber(values, "weapon", damaged.getWeapon());
                putNumber(values, "amount", damaged.getDamage());
            }
            case OrderIssuedEvent order -> {
                putMainUnit(values, pool, order.getUnit());
                putNumber(values, "order", order.getOrderId());
                putUnit(values, pool, "target", order.getTarget());
                putNumber(values, "targetX", order.getPositionX());
                putNumber(values, "targetY", order.getPositionY());
                putNumber(values, "buildType", order.getTargetUnitType());
                values.put("queued", values, order.isQueued());
            }
            case TargetAcquiredEvent acquired -> {
                putMainUnit(values, pool, acquired.getUnit());
                putUnit(values, pool, "target", acquired.getTarget());
            }
            case UnitTrainedEvent trained -> {
                putMainUnit(values, pool, trained.getUnit());
                putUnit(values, pool, "producer", trained.getProducer());
            }
            case ResearchCompletedEvent research -> {
                putMainUnit(values, pool, research.getUnit());
                putNumber(values, "tech", research.getTechnology());
            }
            case UpgradeCompletedEvent upgrade -> {
                putMainUnit(values, pool, upgrade.getUnit());
                putNumber(values, "upgrade", upgrade.getUpgrade());
                putNumber(values, "level", upgrade.getLevel());
            }
            case SpellCastEvent spell -> {
                putMainUnit(values, pool, spell.getCaster());
                putNumber(values, "tech", spell.getTechnology());
                putUnit(values, pool, "target", spell.getTarget());
                putNumber(values, "targetX", spell.getPositionX());
                putNumber(values, "targetY", spell.getPositionY());
            }
            case BulletCreatedEvent bullet -> {
                putNumber(values, "bullet", bullet.getBullet().getPoolIndex());
                values.put("bullet" + GENERATION_SUFFIX, values, (double) bullet.getBulletGeneration());
                putNumber(values, "weapon", bullet.getWeapon());
                putNumber(values, "player", bullet.getOwnerIndex());
                putUnit(values, pool, "source", bullet.getSource());
                putUnit(values, pool, "target", bullet.getTarget());
                putNumber(values, "targetX", bullet.getTargetX());
                putNumber(values, "targetY", bullet.getTargetY());
            }
            case BulletHitEvent bullet -> {
                putNumber(values, "bullet", bullet.getBullet().getPoolIndex());
                values.put("bullet" + GENERATION_SUFFIX, values, (double) bullet.getBulletGeneration());
                putNumber(values, "weapon", bullet.getWeapon());
                putNumber(values, "player", bullet.getOwnerIndex());
                putUnit(values, pool, "source", bullet.getSource());
                putUnit(values, pool, "target", bullet.getTarget());
                putNumber(values, "targetX", bullet.getTargetX());
                putNumber(values, "targetY", bullet.getTargetY());
                values.put("missed", values, bullet.isMissed());
            }
            case OwnerChangedEvent owner -> {
                putMainUnit(values, pool, owner.getUnit());
                putNumber(values, "previousOwner", owner.getPreviousOwner());
                putNumber(values, "owner", owner.getOwner());
            }
            case UnitLoadedEvent loaded -> {
                putMainUnit(values, pool, loaded.getUnit());
                putUnit(values, pool, "transport", loaded.getTransport());
            }
            case UnitUnloadedEvent unloaded -> {
                putMainUnit(values, pool, unloaded.getUnit());
                putUnit(values, pool, "transport", unloaded.getTransport());
            }
            case PlayerLeftEvent left -> {
                putNumber(values, "player", left.getPlayerIndex());
                putNumber(values, "reason", left.getReason());
            }
            case PlayerResultEvent result -> {
                putNumber(values, "player", result.getPlayerIndex());
                putNumber(values, "result", result.getVictoryState());
            }
            default -> {
            }
        }

        return values;
    }

    /**
     * Creates the record of a decision with the decision's current values.
     *
     * @param context  the current context
     * @param global   the global scope
     * @param pool     the unit pool
     * @param kind     the kind of the decision
     * @param decision the decision
     * @return the record
     */
    static @NotNull Scriptable createDecisionRecord(@NotNull Context context, @NotNull Scriptable global, @NotNull UnitPool pool,
                                                    @NotNull ScriptDecisionKind kind, @NotNull SimulationDecision decision) {
        Scriptable values = context.newObject(global);

        putNumber(values, KIND_PROPERTY, kind.ordinal());

        switch (decision) {
            case WeaponDamageDecision damage -> {
                putMainUnit(values, pool, damage.getTarget());
                putUnit(values, pool, "source", damage.getSource());
                putNumber(values, "sourcePlayer", damage.getSourcePlayer());
                putNumber(values, "weapon", damage.getWeapon());
                putNumber(values, "amount", damage.getDamage());
                values.put("cancelled", values, damage.isCancelled());
            }
            case OrderDecision order -> {
                putMainUnit(values, pool, order.getUnit());
                putNumber(values, "order", order.getOrderId());
                putUnit(values, pool, "target", order.getTargetUnit());
                putNumber(values, "targetX", order.getPositionX());
                putNumber(values, "targetY", order.getPositionY());
                putNumber(values, "buildType", order.getTargetUnitType());
                values.put("queued", values, order.isQueued());
                values.put("rejected", values, order.isRejected());
            }
            case TargetAcquisitionDecision target -> {
                putMainUnit(values, pool, target.getUnit());
                putUnit(values, pool, "target", target.getTarget());
            }
            default -> {
            }
        }

        return values;
    }

    /**
     * Copies the values the decision hooks left in a decision record back into the decision. A changed target unit must be a live unit of
     * the generation the record names; any other target leaves the decision's target unit as it is.
     *
     * @param record   the decision record
     * @param pool     the unit pool
     * @param decision the decision
     */
    static void applyDecision(@NotNull Scriptable record, @NotNull UnitPool pool, @NotNull SimulationDecision decision) {
        switch (decision) {
            case WeaponDamageDecision damage -> {
                int amount = readInteger(record, "amount", damage.getDamage());

                if (amount >= 0) {
                    damage.setDamage(amount);
                }

                damage.setCancelled(readBoolean(record, "cancelled", damage.isCancelled()));
            }
            case OrderDecision order -> {
                int orderId = readInteger(record, "order", order.getOrderId());

                if (orderId >= 0 && orderId < ScrOrderIds.NONE) {
                    order.setOrderId(orderId);
                }

                order.setTargetUnit(readTargetUnit(record, pool, order.getTargetUnit()));
                order.setPosition(readInteger(record, "targetX", order.getPositionX()), readInteger(record, "targetY", order.getPositionY()));
                order.setRejected(readBoolean(record, "rejected", order.isRejected()));
            }
            case TargetAcquisitionDecision target -> target.setTarget(readTargetUnit(record, pool, target.getTarget()));
            default -> {
            }
        }
    }

    /**
     * Puts the main unit of an event or a decision: its slot, generation, captured type and captured owner.
     */
    private static void putMainUnit(@NotNull Scriptable values, @NotNull UnitPool pool, @NotNull SimulationUnit unit) {
        putUnit(values, pool, "unit", unit);
        putNumber(values, "unitType", unit.getUnitType());
        putNumber(values, "owner", unit.getOwnerIndex());
    }

    /**
     * Puts a captured unit: its slot and the slot's generation when it was captured.
     */
    private static void putUnit(@NotNull Scriptable values, @NotNull UnitPool pool, @NotNull String name, @Nullable SimulationUnit unit) {
        int id = unit == null ? ScriptCoreUtils.NO_ID : UnitHandle.getPoolIndex(unit.getHandle(), pool.getCapacity());
        int generation = unit == null ? ScriptCoreUtils.NO_ID : UnitHandle.getGeneration(unit.getHandle(), pool.getCapacity());

        putNumber(values, name, id);
        putNumber(values, name + GENERATION_SUFFIX, generation);
    }

    /**
     * Puts a unit with its slot's current generation.
     */
    private static void putUnit(@NotNull Scriptable values, @NotNull UnitPool pool, @NotNull String name, @Nullable Unit unit) {
        putNumber(values, name, unit == null ? ScriptCoreUtils.NO_ID : unit.getIndex());
        putNumber(values, name + GENERATION_SUFFIX, unit == null ? ScriptCoreUtils.NO_ID : pool.getGeneration(unit.getIndex()));
    }

    private static void putNumber(@NotNull Scriptable values, @NotNull String name, int value) {
        values.put(name, values, (double) value);
    }

    /**
     * Reads the target unit of a decision record: the current target while the record names its slot and generation, no unit for
     * {@code -1}, otherwise the live unit the record names.
     */
    private static @Nullable Unit readTargetUnit(@NotNull Scriptable record, @NotNull UnitPool pool, @Nullable Unit current) {
        int currentId = current == null ? ScriptCoreUtils.NO_ID : current.getIndex();
        int id = readInteger(record, "target", currentId);
        int generation = readInteger(record, "target" + GENERATION_SUFFIX, current == null ? ScriptCoreUtils.NO_ID : pool.getGeneration(currentId));

        if (id == ScriptCoreUtils.NO_ID) {
            return null;
        }

        if (id == currentId && current != null && generation == pool.getGeneration(currentId)) {
            return current;
        }

        return ScriptUnitCore.isLive(pool, id) && pool.getGeneration(id) == generation ? pool.getUnit(id) : current;
    }

    /**
     * Reads an integer of a decision record.
     *
     * @return the integer, or the fallback if the property is not an integral number inside the 32-bit range
     */
    private static int readInteger(@NotNull Scriptable record, @NotNull String name, int fallback) {
        if (!(ScriptableObject.getProperty(record, name) instanceof Number number)) {
            return fallback;
        }

        double value = number.doubleValue();

        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE && value == StrictMath.floor(value) ? (int) value : fallback;
    }

    /**
     * Reads a boolean of a decision record.
     *
     * @return the boolean, or the fallback if the property is not a boolean
     */
    private static boolean readBoolean(@NotNull Scriptable record, @NotNull String name, boolean fallback) {
        return ScriptableObject.getProperty(record, name) instanceof Boolean value ? value : fallback;
    }


    private ScriptSimulationUtils() {
    }
}
