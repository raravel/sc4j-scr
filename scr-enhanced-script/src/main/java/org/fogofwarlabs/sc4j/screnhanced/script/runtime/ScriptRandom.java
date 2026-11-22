package org.fogofwarlabs.sc4j.screnhanced.script.runtime;

import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ScriptRuntime;

/**
 * Generates the random numbers of a script runtime with SplitMix64: the state advances by a fixed odd constant and each output is the mixed
 * state. The same seed gives the same numbers on every platform.
 * <p>
 * A locked generator ({@link #setLocked}), as while local code runs, refuses every draw, so that the numbers stay the same on every peer.
 */
final class ScriptRandom {
    private static final long STATE_INCREMENT = 0x9E3779B97F4A7C15L;
    private static final long FIRST_MULTIPLIER = 0xBF58476D1CE4E5B9L;
    private static final long SECOND_MULTIPLIER = 0x94D049BB133111EBL;
    private static final int FIRST_SHIFT = 30;
    private static final int SECOND_SHIFT = 27;
    private static final int THIRD_SHIFT = 31;
    private static final int DOUBLE_SHIFT = Long.SIZE - 53;
    private static final double DOUBLE_UNIT = 0x1.0p-53;

    private long state;
    private boolean locked;

    /**
     * Creates a {@link ScriptRandom} instance that is not locked.
     *
     * @param seed the initial state
     */
    ScriptRandom(long seed) {
        this.state  = seed;
        this.locked = false;
    }

    /**
     * Sets whether a draw throws a {@code TypeError}, as it must while local code runs.
     *
     * @param locked {@code true} to refuse the draws
     */
    void setLocked(boolean locked) {
        this.locked = locked;
    }

    /**
     * Generates the next 64 random bits.
     *
     * @return the next random {@code long}
     * @throws EcmaError if the generator is locked
     */
    long nextLong() {
        if (this.locked) {
            throw ScriptRuntime.typeError("Local code cannot draw random numbers.");
        }

        this.state += STATE_INCREMENT;

        long mixed = this.state;

        mixed = (mixed ^ (mixed >>> FIRST_SHIFT)) * FIRST_MULTIPLIER;
        mixed = (mixed ^ (mixed >>> SECOND_SHIFT)) * SECOND_MULTIPLIER;

        return mixed ^ (mixed >>> THIRD_SHIFT);
    }

    /**
     * Generates a random number from the upper 53 bits of {@link #nextLong()}.
     *
     * @return a random number at least {@code 0} and less than {@code 1}
     */
    double nextDouble() {
        return (this.nextLong() >>> DOUBLE_SHIFT) * DOUBLE_UNIT;
    }

    /**
     * Generates a uniform random integer below a bound: 63 random bits modulo the bound, drawn again while the draw falls into the last
     * incomplete multiple of the bound.
     *
     * @param bound the bound, positive
     * @return a random integer at least {@code 0} and less than {@code bound}
     */
    long nextLong(long bound) {
        long bits = this.nextLong() >>> 1;
        long value = bits % bound;

        while (bits - value + (bound - 1) < 0) {
            bits  = this.nextLong() >>> 1;
            value = bits % bound;
        }

        return value;
    }
}
