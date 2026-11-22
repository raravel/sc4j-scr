package org.fogofwarlabs.sc4j.screnhanced.script.runtime;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;

import java.io.Serial;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Creates the Rhino contexts of one script runtime and counts their instructions.
 * <p>
 * Every context runs in Rhino's interpreter with the newest ECMAScript language version, exposes no Java class, uses the root locale
 * and UTC, and limits the interpreter stack depth. The interpreter reports its instruction count every
 * {@value #INSTRUCTION_OBSERVER_THRESHOLD} instructions; the factory adds the reports since {@link #resetInstructionCount()} and throws
 * {@link InstructionBudgetError} once they exceed the budget. The interpreter's instruction count depends only on the script, so the
 * budget runs out at the same point on every platform. A setup context ({@link #enterSetupContext()}) counts nothing.
 */
final class ScriptContextFactory extends ContextFactory {
    /**
     * The number of instructions between two reports of the interpreter.
     */
    private static final int INSTRUCTION_OBSERVER_THRESHOLD = 10_000;
    /**
     * The deepest nesting of script function calls; a deeper call throws a script error.
     */
    private static final int MAXIMUM_INTERPRETER_STACK_DEPTH = 10_000;
    private static final @NotNull String TIME_ZONE = "UTC";

    /**
     * Thrown when the instructions of one call exceed the budget. Rhino's interpreter passes an {@link Error} through the script without
     * running its {@code catch} and {@code finally} blocks, so a script cannot continue after it.
     */
    static final class InstructionBudgetError extends Error {
        @Serial
        private static final long serialVersionUID = 1L;

        private InstructionBudgetError() {
            super(null, null, false, false);
        }
    }


    private final long instructionBudget;

    private long instructionCount;

    /**
     * Creates a {@link ScriptContextFactory} instance.
     *
     * @param instructionBudget the largest number of instructions of one call
     */
    ScriptContextFactory(long instructionBudget) {
        this.instructionBudget = instructionBudget;

        this.instructionCount = 0;
    }

    /**
     * Enters a new context of this factory on the current thread.
     *
     * @return the entered context, to be closed by the caller
     * @throws IllegalStateException if the thread is in a context of another factory
     */
    @NotNull Context enterOwnContext() {
        Context context = this.enterContext();

        if (context.getFactory() != this) {
            context.close();

            throw Exceptions.newIllegalStateException("The thread is in a script context of another factory.");
        }

        return context;
    }

    /**
     * Enters a new context of this factory on the current thread that counts no instructions, for the runtime's own setup code.
     *
     * @return the entered context, to be closed by the caller
     * @throws IllegalStateException if the thread is in a script context already
     */
    @NotNull Context enterSetupContext() {
        if (Context.getCurrentContext() != null) {
            throw Exceptions.newIllegalStateException("The thread is in a script context already.");
        }

        Context context = this.enterOwnContext();

        context.setInstructionObserverThreshold(0);

        return context;
    }

    /**
     * Starts a new call: the instruction count returns to {@code 0}.
     */
    void resetInstructionCount() {
        this.instructionCount = 0;
    }

    @Override
    protected void onContextCreated(@NotNull Context context) {
        super.onContextCreated(context);

        context.setInterpretedMode(true);
        context.setLanguageVersion(Context.VERSION_ECMASCRIPT);
        context.setClassShutter(className -> false);
        context.setLocale(Locale.ROOT);
        context.setTimeZone(TimeZone.getTimeZone(TIME_ZONE));
        context.setMaximumInterpreterStackDepth(MAXIMUM_INTERPRETER_STACK_DEPTH);
        context.setInstructionObserverThreshold(INSTRUCTION_OBSERVER_THRESHOLD);
    }

    @Override
    protected void observeInstructionCount(@NotNull Context context, int instructionCount) {
        this.instructionCount += instructionCount;

        if (this.instructionCount > this.instructionBudget) {
            throw new InstructionBudgetError();
        }
    }
}
