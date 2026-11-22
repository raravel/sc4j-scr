package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.type.constant.TriggerUnitGroups;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.*;

/**
 * Defines the functions of the {@code core} global and converts their arguments.
 * <p>
 * An integer argument is a JavaScript number that is integral and inside the signed 32-bit range; any other value throws a {@code TypeError}.
 * An id outside its range throws a {@code RangeError}. The functions are read-only and permanent properties, and every namespace of
 * {@code core} is closed to new properties, so a script cannot replace them.
 */
final class ScriptCoreUtils {
    /**
     * The id a {@code core} function returns for no unit, location or type.
     */
    static final int NO_ID = -1;

    /**
     * The number of resource types of the trigger routines: ore, gas, and ore and gas.
     */
    private static final int RESOURCE_TYPE_COUNT = 3;
    /**
     * The number of score types of the trigger routines: total, units, buildings, units and buildings, kills, razings, kills and razings, and
     * custom.
     */
    private static final int SCORE_TYPE_COUNT = 8;
    /**
     * The largest unsigned 32-bit value.
     */
    private static final long MAXIMUM_UNSIGNED_INTEGER = (1L << Integer.SIZE) - 1;
    private static final int MEMBER_ATTRIBUTES = ScriptableObject.READONLY | ScriptableObject.DONTENUM | ScriptableObject.PERMANENT;

    /**
     * Creates an empty namespace object and defines it as a property of {@code core}.
     *
     * @param context the current context
     * @param global  the global scope
     * @param core    the {@code core} object
     * @param name    the namespace name
     * @return the created namespace object
     */
    static @NotNull ScriptableObject createNamespace(@NotNull Context context, @NotNull Scriptable global, @NotNull Scriptable core,
                                                     @NotNull String name) {
        ScriptableObject namespace = (ScriptableObject) context.newObject(global);

        ScriptableObject.defineProperty(core, name, namespace, MEMBER_ATTRIBUTES);

        return namespace;
    }

    /**
     * Defines a function as a read-only, non-enumerable and permanent property of a namespace.
     *
     * @param global    the global scope
     * @param namespace the namespace object
     * @param name      the property and function name
     * @param length    the function's {@code length}
     * @param target    the function body
     */
    static void defineFunction(@NotNull Scriptable global, @NotNull Scriptable namespace, @NotNull String name, int length,
                               @NotNull Callable target) {
        ScriptableObject.defineProperty(namespace, name, new LambdaFunction(global, name, length, target), MEMBER_ATTRIBUTES);
    }

    /**
     * Freezes a record: its properties become read-only and permanent, and it takes no new property.
     *
     * @param record the record
     * @return the record
     */
    static @NotNull ScriptableObject freeze(@NotNull ScriptableObject record) {
        for (Object id : record.getIds()) {
            record.setAttributes((String) id, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
        }

        record.preventExtensions();

        return record;
    }

    /**
     * Reads an integer argument.
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the integer
     * @throws EcmaError if the argument is missing or not an integral number inside the 32-bit range
     */
    static int readInteger(@NotNull Object @NotNull [] arguments, int index) {
        double value = index < arguments.length && arguments[index] instanceof Number number ? number.doubleValue() : Double.NaN;

        if (!isInteger(value)) {
            throw ScriptRuntime.typeError(Exceptions.format("Argument {0} is not an integer.", index + 1));
        }

        return (int) value;
    }

    /**
     * Reads an array argument of integers.
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the integers in array order
     * @throws EcmaError if the argument is missing or not an array, or an element is not an integral number inside the 32-bit range
     */
    static int @NotNull [] readIntegers(@NotNull Object @NotNull [] arguments, int index) {
        if (index >= arguments.length || !(arguments[index] instanceof NativeArray array)) {
            throw ScriptRuntime.typeError(Exceptions.format("Argument {0} is not an array.", index + 1));
        }

        Object[] elements = array.toArray();
        int[] values = new int[elements.length];

        for (int element = 0; element < elements.length; element++) {
            double value = elements[element] instanceof Number number ? number.doubleValue() : Double.NaN;

            if (!isInteger(value)) {
                throw ScriptRuntime.typeError(Exceptions.format("Element {0} of argument {1} is not an integer.", element, index + 1));
            }

            values[element] = (int) value;
        }

        return values;
    }

    /**
     * Reads a number argument.
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the number as a float
     * @throws EcmaError if the argument is missing or not a finite number
     */
    static float readNumber(@NotNull Object @NotNull [] arguments, int index) {
        double value = index < arguments.length && arguments[index] instanceof Number number ? number.doubleValue() : Double.NaN;

        if (!Double.isFinite(value)) {
            throw ScriptRuntime.typeError(Exceptions.format("Argument {0} is not a finite number.", index + 1));
        }

        return (float) value;
    }

    private static boolean isInteger(double value) {
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE && value == StrictMath.floor(value);
    }

    /**
     * Reads an integer argument of a signed or unsigned 32-bit value.
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the integer, from {@code -2^31} to {@code 2^32 - 1}
     * @throws EcmaError if the argument is missing or not an integral number inside that range
     */
    static long readLong(@NotNull Object @NotNull [] arguments, int index) {
        double value = index < arguments.length && arguments[index] instanceof Number number ? number.doubleValue() : Double.NaN;

        if (!(value >= Integer.MIN_VALUE && value <= MAXIMUM_UNSIGNED_INTEGER) || value != StrictMath.floor(value)) {
            throw ScriptRuntime.typeError(Exceptions.format("Argument {0} is not an integer.", index + 1));
        }

        return (long) value;
    }

    /**
     * Reads a boolean argument.
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the boolean
     * @throws EcmaError if the argument is missing or not a boolean
     */
    static boolean readBoolean(@NotNull Object @NotNull [] arguments, int index) {
        if (index >= arguments.length || !(arguments[index] instanceof Boolean value)) {
            throw ScriptRuntime.typeError(Exceptions.format("Argument {0} is not a boolean.", index + 1));
        }

        return value;
    }

    /**
     * Reads a string argument.
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the string
     * @throws EcmaError if the argument is missing or not a string
     */
    static @NotNull String readString(@NotNull Object @NotNull [] arguments, int index) {
        if (index >= arguments.length || !(arguments[index] instanceof CharSequence value)) {
            throw ScriptRuntime.typeError(Exceptions.format("Argument {0} is not a string.", index + 1));
        }

        return value.toString();
    }

    /**
     * Reads a function argument.
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the function
     * @throws EcmaError if the argument is missing or not a function
     */
    static @NotNull Function readFunction(@NotNull Object @NotNull [] arguments, int index) {
        if (index >= arguments.length || !(arguments[index] instanceof Function value)) {
            throw ScriptRuntime.typeError(Exceptions.format("Argument {0} is not a function.", index + 1));
        }

        return value;
    }

    /**
     * Checks that an id lies in {@code 0} to {@code count - 1}.
     *
     * @param kind  the kind of the id for the error message
     * @param id    the id
     * @param count the number of ids
     * @return the id
     * @throws EcmaError if the id is outside the range
     */
    static int requireId(@NotNull String kind, int id, int count) {
        if (id < 0 || id >= count) {
            throw ScriptRuntime.rangeError(Exceptions.format("The {0} {1} is not from 0 to {2}.", kind, id, count - 1));
        }

        return id;
    }

    /**
     * Reads a unit operand of the trigger routines: a unit type from {@code 0} to {@value ScrUnitIds#NONE} minus one or a unit group from
     * {@value TriggerUnitGroups#ANY_UNIT} to {@value TriggerUnitGroups#FACTORIES}.
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the unit operand
     * @throws EcmaError if the argument is not an integer, or is neither a unit type nor a unit group
     */
    static int readUnitOperand(@NotNull Object @NotNull [] arguments, int index) {
        int operand = requireId("unit type", readInteger(arguments, index), TriggerUnitGroups.FACTORIES + 1);

        if (operand == ScrUnitIds.NONE) {
            throw ScriptRuntime.rangeError(Exceptions.format("The unit type {0} is neither a unit type nor a unit group.", operand));
        }

        return operand;
    }

    /**
     * Reads a resource type of the trigger routines: {@code 0} ore, {@code 1} gas or {@code 2} ore and gas.
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the resource type
     * @throws EcmaError if the argument is not an integer or not a resource type
     */
    static int readResourceType(@NotNull Object @NotNull [] arguments, int index) {
        return requireId("resource type", readInteger(arguments, index), RESOURCE_TYPE_COUNT);
    }

    /**
     * Reads a score type of the trigger routines: {@code 0} total, {@code 1} units, {@code 2} buildings, {@code 3} units and buildings,
     * {@code 4} kills, {@code 5} razings, {@code 6} kills and razings or {@code 7} custom.
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the score type
     * @throws EcmaError if the argument is not an integer or not a score type
     */
    static int readScoreType(@NotNull Object @NotNull [] arguments, int index) {
        return requireId("score type", readInteger(arguments, index), SCORE_TYPE_COUNT);
    }

    /**
     * Reads a location argument.
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the location index from {@code 0} to {@value TriggerContext#LOCATION_COUNT} minus one
     * @throws EcmaError if the argument is not an integer or not a location index
     */
    static int readLocationId(@NotNull Object @NotNull [] arguments, int index) {
        return requireId("location", readInteger(arguments, index), TriggerContext.LOCATION_COUNT);
    }

    /**
     * Reads an optional location argument: a location index or {@value #NO_ID} for none.
     *
     * @param arguments the call arguments
     * @param index     the argument index
     * @return the location index, or {@value #NO_ID} for none
     * @throws EcmaError if the argument is not an integer, or neither a location index nor {@value #NO_ID}
     */
    static int readOptionalLocationId(@NotNull Object @NotNull [] arguments, int index) {
        return readInteger(arguments, index) == NO_ID ? NO_ID : readLocationId(arguments, index);
    }


    private ScriptCoreUtils() {
    }
}
