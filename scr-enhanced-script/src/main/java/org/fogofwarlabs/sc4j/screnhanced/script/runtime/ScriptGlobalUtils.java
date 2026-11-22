package org.fogofwarlabs.sc4j.screnhanced.script.runtime;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.*;
import org.mozilla.javascript.ScriptRuntime;

import java.util.List;

/**
 * Creates the global scope of a script runtime.
 * <p>
 * The scope starts from Rhino's safe standard objects, which expose no Java package or class. The globals that run code from strings
 * ({@code eval}, {@code Script}), depend on the clock or the platform ({@code Date}) or belong to Rhino's Java and legacy features are
 * removed. The {@code Function} constructor and the generator function constructor are replaced by functions that throw a
 * {@code TypeError}, also as the {@code constructor} of their prototypes, so that no function object leads to a constructor that compiles
 * a string; the prototypes and {@code instanceof Function} stay, and the generator replacement is the read-only global
 * {@code GeneratorFunction}. {@code Math} is replaced by {@link ScriptMath} and {@code random} is added.
 */
final class ScriptGlobalUtils {
    /**
     * The name of the script RNG global.
     */
    static final @NotNull String RANDOM_NAME = "random";

    /**
     * The globals of Rhino's safe standard objects that the runtime removes.
     */
    private static final @NotNull List<@NotNull String> REMOVED_GLOBALS = List.of(
            "eval", "Script", "Date", "Continuation", "With", "Call", "JavaException", "XML", "XMLList", "Namespace", "QName", "isXMLName"
    );
    private static final @NotNull String FUNCTION_NAME = "Function";
    private static final @NotNull String GENERATOR_FUNCTION_NAME = "__GeneratorFunction";
    private static final @NotNull String GENERATOR_FUNCTION_BUILTIN_NAME = "GeneratorFunction";
    private static final @NotNull String PROTOTYPE_PROPERTY = "prototype";
    private static final @NotNull String CONSTRUCTOR_PROPERTY = "constructor";
    /**
     * The largest bound of {@code random.nextInt}: every integer below it is exact as a JavaScript number.
     */
    private static final double LARGEST_RANDOM_BOUND = 0x1.0p53;

    /**
     * Creates the global scope.
     *
     * @param context the current context
     * @param random  the random numbers of {@code random} and {@code Math.random()}
     * @return the created global scope
     */
    static @NotNull TopLevel createGlobal(@NotNull Context context, @NotNull ScriptRandom random) {
        TopLevel global = new TopLevel();

        context.initSafeStandardObjects(global, false);

        disableConstructor(global, FUNCTION_NAME, FUNCTION_NAME);
        disableConstructor(global, GENERATOR_FUNCTION_NAME, GENERATOR_FUNCTION_BUILTIN_NAME);

        for (String name : REMOVED_GLOBALS) {
            removeGlobal(global, name);
        }

        removeGlobal(global, ScriptMath.NAME);
        ScriptMath.install(global, random);
        installRandom(context, global, random);

        global.cacheBuiltins(global, false);

        return global;
    }

    /**
     * Replaces a constructor by a function that throws a {@code TypeError} when called and is no constructor, with the same
     * {@code prototype}; the prototype's {@code constructor} becomes the replacement.
     * <p>
     * Rhino caches the built-in constructors of a top-level scope by their builtin names, and creates a new generator function
     * constructor (global {@code __GeneratorFunction}) when the scope has none under {@code GeneratorFunction}. A
     * constructor whose global name differs from its builtin name is therefore removed and the replacement is defined under the builtin name,
     * read-only and permanent, so that every module scope caches the replacement.
     *
     * @param global      the global scope
     * @param name        the global name of the constructor
     * @param builtinName the name under which Rhino looks up the constructor when it caches the built-in constructors
     */
    private static void disableConstructor(@NotNull TopLevel global, @NotNull String name, @NotNull String builtinName) {
        if (!(global.get(name, global) instanceof Scriptable constructor)) {
            throw Exceptions.newIllegalStateException("The standard objects have no constructor {0}.", name);
        }

        Object prototype = ScriptableObject.getProperty(constructor, PROTOTYPE_PROPERTY);

        if (!(prototype instanceof Scriptable prototypeObject)) {
            throw Exceptions.newIllegalStateException("The constructor {0} has no prototype.", name);
        }

        LambdaFunction replacement = new LambdaFunction(global, FUNCTION_NAME, 1, (context, scope, thisObject, arguments) -> {
            throw ScriptRuntime.typeError("Creating functions from strings is not supported.");
        });

        replacement.setImmunePrototypeProperty(prototypeObject);
        ScriptableObject.defineProperty(prototypeObject, CONSTRUCTOR_PROPERTY, replacement, ScriptableObject.DONTENUM);

        if (builtinName.equals(name)) {
            ScriptableObject.defineProperty(global, name, replacement, ScriptableObject.DONTENUM);
        } else {
            removeGlobal(global, name);
            ScriptableObject.defineProperty(global, builtinName, replacement,
                                            ScriptableObject.READONLY | ScriptableObject.DONTENUM | ScriptableObject.PERMANENT);
        }
    }

    private static void removeGlobal(@NotNull TopLevel global, @NotNull String name) {
        global.delete(name);

        if (global.has(name, global)) {
            throw Exceptions.newIllegalStateException("The global {0} cannot be removed.", name);
        }
    }

    /**
     * Defines the global {@code random}: {@code next()} (a number at least {@code 0} and below {@code 1}), {@code nextInt(bound)} (an
     * integer at least {@code 0} and below a positive integer bound up to {@code 2^53}) and {@code chance(probability)} ({@code true} with
     * the probability).
     */
    private static void installRandom(@NotNull Context context, @NotNull TopLevel global, @NotNull ScriptRandom random) {
        Scriptable randomObject = context.newObject(global);

        defineFunction(global, randomObject, "next", 0, (callContext, scope, thisObject, arguments) -> random.nextDouble());
        defineFunction(global, randomObject, "nextInt", 1, (callContext, scope, thisObject, arguments) -> {
            double bound = arguments.length > 0 ? Context.toNumber(arguments[0]) : Double.NaN;

            if (!(bound >= 1 && bound <= LARGEST_RANDOM_BOUND) || bound != StrictMath.floor(bound)) {
                throw ScriptRuntime.rangeError("random.nextInt needs an integer bound from 1 to 2^53.");
            }

            return (double) random.nextLong((long) bound);
        });
        defineFunction(global, randomObject, "chance", 1, (callContext, scope, thisObject, arguments) -> {
            double probability = arguments.length > 0 ? Context.toNumber(arguments[0]) : Double.NaN;

            return random.nextDouble() < probability;
        });

        ScriptableObject.defineProperty(global, RANDOM_NAME, randomObject, ScriptableObject.DONTENUM);
    }

    /**
     * Defines a function as a non-enumerable property of an object.
     *
     * @param global the global scope
     * @param owner  the object
     * @param name   the property and function name
     * @param length the function's {@code length}
     * @param target the function body
     */
    static void defineFunction(@NotNull Scriptable global, @NotNull Scriptable owner, @NotNull String name, int length,
                               @NotNull Callable target) {
        ScriptableObject.defineProperty(owner, name, new LambdaFunction(global, name, length, target), ScriptableObject.DONTENUM);
    }


    private ScriptGlobalUtils() {
    }
}
