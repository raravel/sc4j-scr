package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.combat.UnitDamage;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

/**
 * Defines {@code core.combat}: the combat tables of the game that EUD writes change ({@link UnitDamage}).
 * <p>
 * {@code getDamageMultiplier(damageType, size)} and {@code setDamageMultiplier(damageType, size, value)}: the factor in {@code 1/256} that
 * scales the damage of a weapons.dat damage type ({@code 0} independent, {@code 1} explosive, {@code 2} concussive, {@code 3} normal,
 * {@code 4} ignore armor) against a units.dat size ({@code 0} independent, {@code 1} small, {@code 2} medium, {@code 3} large), an unsigned
 * DWORD of the multiplier table, whose rows start with the damage type. {@code getFleeDistanceCount()}, {@code getFleeDistance(index)} and
 * {@code setFleeDistance(index, pixels)}: the distances a fleeing unit tries in order, unsigned DWORDs.
 */
final class ScriptCombatCore {
    /**
     * The number of damage types.
     */
    private static final int DAMAGE_TYPE_COUNT = 5;
    /**
     * The number of unit sizes.
     */
    private static final int SIZE_COUNT = 4;
    /**
     * The entries of one row of the multiplier table: the damage type, then one per size.
     */
    private static final int MULTIPLIER_ROW_LENGTH = SIZE_COUNT + 1;
    /**
     * The index of the entry of damage type {@code 0} and size {@code 0}, after the header row and the first row's type.
     */
    private static final int FIRST_MULTIPLIER = MULTIPLIER_ROW_LENGTH + 1;

    private final @NotNull ScriptGame scriptGame;

    /**
     * Creates a {@link ScriptCombatCore} instance.
     *
     * @param scriptGame the game binding
     */
    ScriptCombatCore(@NotNull ScriptGame scriptGame) {
        this.scriptGame = scriptGame;
    }

    /**
     * Defines the functions of {@code core.combat}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void define(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getDamageMultiplier", 2, (context, scope, thisObject, arguments) -> {
            int index = this.readMultiplierIndex(arguments);

            return (double) Integer.toUnsignedLong(this.getDamage().getDamageMultipliers()[index]);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setDamageMultiplier", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int index = this.readMultiplierIndex(arguments);
            int value = this.readUnsigned(arguments, 2);

            this.getDamage().getDamageMultipliers()[index] = value;

            return Context.getUndefinedValue();
        });

        ScriptCoreUtils.defineFunction(global, namespace, "getFleeDistanceCount", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.getDamage().getFleeDistances().length);
        ScriptCoreUtils.defineFunction(global, namespace, "getFleeDistance", 1, (context, scope, thisObject, arguments) -> {
            int[] distances = this.getDamage().getFleeDistances();
            int index = ScriptCoreUtils.requireId("flee distance", ScriptCoreUtils.readInteger(arguments, 0), distances.length);

            return (double) Integer.toUnsignedLong(distances[index]);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setFleeDistance", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int[] distances = this.getDamage().getFleeDistances();
            int index = ScriptCoreUtils.requireId("flee distance", ScriptCoreUtils.readInteger(arguments, 0), distances.length);
            int value = this.readUnsigned(arguments, 1);

            distances[index] = value;

            return Context.getUndefinedValue();
        });
    }

    private @NotNull UnitDamage getDamage() {
        return this.scriptGame.getGame().getUnitDamage();
    }

    /**
     * Reads the damage type and size arguments as the index of their entry in the multiplier table.
     *
     * @throws EcmaError if an argument is not an integer or is outside its range
     */
    private int readMultiplierIndex(@NotNull Object @NotNull [] arguments) {
        int damageType = ScriptCoreUtils.requireId("damage type", ScriptCoreUtils.readInteger(arguments, 0), DAMAGE_TYPE_COUNT);
        int size = ScriptCoreUtils.requireId("unit size", ScriptCoreUtils.readInteger(arguments, 1), SIZE_COUNT);

        return FIRST_MULTIPLIER + damageType * MULTIPLIER_ROW_LENGTH + size;
    }

    /**
     * Reads an unsigned DWORD.
     *
     * @throws EcmaError if the argument is not an integer or is outside {@code 0} to {@code 2^32 - 1}
     */
    private int readUnsigned(@NotNull Object @NotNull [] arguments, int index) {
        long value = ScriptCoreUtils.readLong(arguments, index);

        if (value < 0) {
            throw ScriptRuntime.rangeError(Exceptions.format("The value {0} is negative.", value));
        }

        return (int) value;
    }
}
