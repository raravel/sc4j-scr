package org.fogofwarlabs.sc4j.screnhanced.script.runtime;

import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.*;
import org.mozilla.javascript.ScriptRuntime;

import java.io.Serial;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/**
 * The {@code Math} object of a script runtime: the members of Rhino's {@code Math} with the ECMAScript results, computed with
 * {@link StrictMath} and plain Java arithmetic so that every platform gets the same bits. {@code Math.random()} draws from the runtime's
 * {@link ScriptRandom}.
 */
final class ScriptMath extends ScriptableObject {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * The name of the global and of its class.
     */
    static final @NotNull String NAME = "Math";

    private static final int CONSTANT_ATTRIBUTES = READONLY | DONTENUM | PERMANENT;
    private static final double LN2 = 0.6931471805599453;
    private static final double LN10 = 2.302585092994046;
    private static final double LOG2E = 1.4426950408889634;
    private static final double LOG10E = 0.4342944819032518;
    private static final double SQRT1_2 = 0.7071067811865476;
    private static final double SQRT2 = 1.4142135623730951;
    private static final double HALF = 0.5;
    private static final long SIGNIFICAND_MASK = (1L << (Double.PRECISION - 1)) - 1;
    /**
     * The base 2 exponent of the lowest bit of a subnormal number.
     */
    private static final int SUBNORMAL_EXPONENT = Double.MIN_EXPONENT - (Double.PRECISION - 1);
    /**
     * The magnitude above which {@code asinh} and {@code acosh} use {@code log(|x|) + ln 2}, where {@code x * x} would lose the result.
     */
    private static final double LARGE_HYPERBOLIC_ARGUMENT = 0x1.0p28;

    /**
     * Creates the {@code Math} object and defines it as a global.
     *
     * @param global the global scope
     * @param random the random numbers of {@code Math.random()}
     */
    static void install(@NotNull ScriptableObject global, @NotNull ScriptRandom random) {
        ScriptMath math = new ScriptMath();

        math.setParentScope(global);
        math.setPrototype(ScriptableObject.getObjectPrototype(global));
        math.defineMembers(random);

        ScriptableObject.defineProperty(global, NAME, math, DONTENUM);
    }

    private static double toNumber(@NotNull Object @NotNull [] arguments, int index) {
        return index < arguments.length ? Context.toNumber(arguments[index]) : Double.NaN;
    }

    private static double round(double value) {
        double floor = StrictMath.floor(value);
        double rounded = value - floor >= HALF ? floor + 1 : floor;

        return rounded == 0 && value < 0 ? -0.0 : rounded;
    }

    /**
     * Computes the base 2 logarithm: the exact exponent for a power of two, {@code log(value) / ln 2} otherwise.
     */
    private static double log2(double value) {
        if (value > 0 && value < Double.POSITIVE_INFINITY) {
            long bits = Double.doubleToRawLongBits(value);
            int exponent = Math.getExponent(value);

            if (exponent >= Double.MIN_EXPONENT && (bits & SIGNIFICAND_MASK) == 0) {
                return exponent;
            }

            if (exponent < Double.MIN_EXPONENT && Long.bitCount(bits) == 1) {
                return Long.numberOfTrailingZeros(bits) + SUBNORMAL_EXPONENT;
            }
        }

        return StrictMath.log(value) / LN2;
    }

    private static double asinh(double value) {
        double magnitude = Math.abs(value);
        double result;

        if (magnitude > LARGE_HYPERBOLIC_ARGUMENT) {
            result = StrictMath.log(magnitude) + LN2;
        } else {
            result = StrictMath.log1p(magnitude + magnitude * magnitude / (1 + StrictMath.sqrt(1 + magnitude * magnitude)));
        }

        return StrictMath.copySign(result, value);
    }

    private static double acosh(double value) {
        if (value > LARGE_HYPERBOLIC_ARGUMENT) {
            return StrictMath.log(value) + LN2;
        }

        return StrictMath.log(value + StrictMath.sqrt(value * value - 1));
    }

    private static double atanh(double value) {
        double magnitude = Math.abs(value);

        return StrictMath.copySign(HALF * StrictMath.log1p(2 * magnitude / (1 - magnitude)), value);
    }


    private ScriptMath() {
    }

    @Override
    public @NotNull String getClassName() {
        return NAME;
    }

    private void defineMembers(@NotNull ScriptRandom random) {
        this.defineProperty("E", Math.E, CONSTANT_ATTRIBUTES);
        this.defineProperty("PI", Math.PI, CONSTANT_ATTRIBUTES);
        this.defineProperty("LN10", LN10, CONSTANT_ATTRIBUTES);
        this.defineProperty("LN2", LN2, CONSTANT_ATTRIBUTES);
        this.defineProperty("LOG2E", LOG2E, CONSTANT_ATTRIBUTES);
        this.defineProperty("LOG10E", LOG10E, CONSTANT_ATTRIBUTES);
        this.defineProperty("SQRT1_2", SQRT1_2, CONSTANT_ATTRIBUTES);
        this.defineProperty("SQRT2", SQRT2, CONSTANT_ATTRIBUTES);

        this.defineFunction("toSource", 0, (context, scope, thisObject, arguments) -> NAME);
        this.defineUnary("abs", Math::abs);
        this.defineUnary("acos", StrictMath::acos);
        this.defineUnary("acosh", ScriptMath::acosh);
        this.defineUnary("asin", StrictMath::asin);
        this.defineUnary("asinh", ScriptMath::asinh);
        this.defineUnary("atan", StrictMath::atan);
        this.defineUnary("atanh", ScriptMath::atanh);
        this.defineBinary("atan2", StrictMath::atan2);
        this.defineUnary("cbrt", StrictMath::cbrt);
        this.defineUnary("ceil", StrictMath::ceil);
        this.defineFunction("clz32", 1, (context, scope, thisObject, arguments) -> {
            long value = ScriptRuntime.toUint32(arguments.length > 0 ? arguments[0] : Context.getUndefinedValue());

            return (double) Integer.numberOfLeadingZeros((int) value);
        });
        this.defineUnary("cos", StrictMath::cos);
        this.defineUnary("cosh", StrictMath::cosh);
        this.defineUnary("exp", StrictMath::exp);
        this.defineUnary("expm1", StrictMath::expm1);
        this.defineUnary("floor", StrictMath::floor);
        this.defineUnary("fround", value -> (float) value);
        this.defineFunction("hypot", 2, (context, scope, thisObject, arguments) -> {
            double result = 0;

            for (Object argument : arguments) {
                result = StrictMath.hypot(result, Context.toNumber(argument));
            }

            return result;
        });
        this.defineFunction("imul", 2, (context, scope, thisObject, arguments) -> {
            int first = ScriptRuntime.toInt32(arguments, 0);
            int second = ScriptRuntime.toInt32(arguments, 1);

            return (double) (first * second);
        });
        this.defineUnary("log", StrictMath::log);
        this.defineUnary("log1p", StrictMath::log1p);
        this.defineUnary("log10", StrictMath::log10);
        this.defineUnary("log2", ScriptMath::log2);
        this.defineFunction("max", 2, (context, scope, thisObject, arguments) -> {
            double result = Double.NEGATIVE_INFINITY;

            for (Object argument : arguments) {
                result = Math.max(result, Context.toNumber(argument));
            }

            return result;
        });
        this.defineFunction("min", 2, (context, scope, thisObject, arguments) -> {
            double result = Double.POSITIVE_INFINITY;

            for (Object argument : arguments) {
                result = Math.min(result, Context.toNumber(argument));
            }

            return result;
        });
        this.defineBinary("pow", StrictMath::pow);
        this.defineFunction("random", 0, (context, scope, thisObject, arguments) -> random.nextDouble());
        this.defineUnary("round", ScriptMath::round);
        this.defineUnary("sign", Math::signum);
        this.defineUnary("sin", StrictMath::sin);
        this.defineUnary("sinh", StrictMath::sinh);
        this.defineUnary("sqrt", StrictMath::sqrt);
        this.defineUnary("tan", StrictMath::tan);
        this.defineUnary("tanh", StrictMath::tanh);
        this.defineUnary("trunc", value -> value < 0 ? StrictMath.ceil(value) : StrictMath.floor(value));
    }

    private void defineUnary(@NotNull String name, @NotNull DoubleUnaryOperator operator) {
        this.defineFunction(name, 1, (context, scope, thisObject, arguments) -> operator.applyAsDouble(toNumber(arguments, 0)));
    }

    private void defineBinary(@NotNull String name, @NotNull DoubleBinaryOperator operator) {
        this.defineFunction(name, 2, (context, scope, thisObject, arguments) -> {
            double first = toNumber(arguments, 0);
            double second = toNumber(arguments, 1);

            return operator.applyAsDouble(first, second);
        });
    }

    private void defineFunction(@NotNull String name, int length, @NotNull Callable target) {
        this.defineProperty(name, new LambdaFunction(this.getParentScope(), name, length, target), DONTENUM);
    }
}
