package org.fogofwarlabs.sc4j.screnhanced.script.runtime;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrButtonInput;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrDrawList;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrModCommandSubmitter;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.SimulationDecision;
import org.fogofwarlabs.sc4j.scr.game.extension.simulation.SimulationEvent;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ConsoleSettings;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ImageReplacements;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptPackage;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptResources;
import org.fogofwarlabs.sc4j.screnhanced.script.core.ScriptGame;
import org.fogofwarlabs.sc4j.screnhanced.script.input.ScriptLocalInput;
import org.fogofwarlabs.sc4j.screnhanced.script.sync.ScriptCommandMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.*;

import java.io.Serial;
import java.util.function.Consumer;

/**
 * Runs the scripts of a script package for one game.
 * <p>
 * The scripts run in Rhino's interpreter with deterministic globals: {@code Math} (computed with {@link StrictMath}), {@code random} (the
 * script RNG, seeded from the game seed and separate from the game's RNG) and per module {@code log}, {@code require}, {@code module} and
 * {@code exports}. No Java class, no {@code eval}, no function constructor from strings, no {@code Date} and no timer is available. The game
 * API is the global {@code core}, the lower layer ({@link ScriptGame}), and the globals of the prelude, the upper layer ({@link ScriptPrelude}),
 * which runs when the runtime is created, outside the instruction budget. {@link #start()} loads the manifest's main script, which loads the
 * others through {@code require}.
 * <p>
 * The runtime calls the scripts through the prelude's entry points only; the prelude collects the hooks of every loaded module and calls them
 * in load order. {@link #runFrame(int)} first makes the events ({@link #queueEvent}) and the script commands ({@link #queueModCommand}) that
 * came since the previous frame the frame's, then calls {@code frame}. {@link #decide} calls {@code decide} at once with a record of the
 * decision and the game read-only, then copies what the hooks left in the record back into the decision; {@link #setReadOnly(boolean)} makes
 * the functions that change the game state throw. All of this is shared code, which runs the same on every peer.
 * <p>
 * {@link #callLocalInput}, {@link #callLocalFrame} and {@link #callRender} call {@code localInput}, {@code localFrame} and {@code render} at
 * once, on the local machine only, with the game read-only and local: no random number, no new module, the shared-only functions refused and
 * the local ones working. The values a script submits wait in the game's command outbox until {@link #submitCommands} hands them to the
 * local player's submitter. {@link #callRender} also lets the drawing functions draw: the list of a completed call is the one drawn
 * ({@link #findDrawList()}) until the next call.
 * <p>
 * The prelude runs every hook, timer and channel handler through the runtime's host, which guards each call. Each call of {@link #start()},
 * {@link #runFrame(int)}, {@link #decide}, {@link #callLocalInput}, {@link #callLocalFrame} and {@link #callRender} has an instruction budget;
 * a decision that a running call causes, as when a hook issues an order, shares the budget of that call. A shared call that exceeds it, or
 * overflows the Java stack, stops the script: the runtime calls no entry point for the rest of the game. An uncaught script error ends only
 * the hook call (or the module loading) it happened in; a decision hook that fails leaves the decision as the previous hooks left it. A
 * local call that exceeds its budget or overflows the stack ends at once without stopping the script, and a render call that does draws
 * nothing, while a hook that throws in a local call ends only itself. Every such event is written to the log output once, with the module
 * name.
 * <p>
 * Every method runs on the caller's thread; the runtime starts no thread. It is not thread-safe.
 */
public final class ScriptRuntime {
    /**
     * The instruction budget of one call that SC:R Enhanced gives a script.
     */
    public static final int INSTRUCTION_BUDGET = 5_000_000;

    private static final @NotNull Logger LOGGER = Logger.of(ScriptRuntime.class);
    private static final @NotNull String LOAD_CALL_NAME = "Loading";
    private static final @NotNull String FRAME_CALL_NAME = "frame";
    private static final @NotNull String DECIDE_CALL_NAME = "decide";
    private static final @NotNull String LOCAL_INPUT_CALL_NAME = "localInput";
    private static final @NotNull String LOCAL_FRAME_CALL_NAME = "localFrame";
    private static final @NotNull String RENDER_CALL_NAME = "render";
    private static final @NotNull String MODULE_NAME_PROPERTY = "name";
    private static final @NotNull String MODULE_SCOPE_PROPERTY = "scope";
    private static final @NotNull String CONSUMED_PROPERTY = "consumed";
    /**
     * The arguments of the host's {@code call(callName, function, arguments, module)}; the module is optional.
     */
    private static final int CALL_NAME_ARGUMENT = 0;
    private static final int CALL_FUNCTION_ARGUMENT = 1;
    private static final int CALL_ARGUMENTS_ARGUMENT = 2;
    private static final int CALL_MODULE_ARGUMENT = 3;

    /**
     * The result of one call into the script.
     */
    private enum CallResult {
        /**
         * The call returned.
         */
        COMPLETED,
        /**
         * The call threw a script error, which was logged.
         */
        FAILED,
        /**
         * The call stopped the script.
         */
        STOPPED
    }

    /**
     * Thrown through the prelude when a guarded call stopped the script or ran over the budget of a local call, so that the entry point ends
     * at once. Rhino's interpreter passes an {@link Error} through the script without running its {@code catch} and {@code finally} blocks.
     */
    private static final class CallAbortedError extends Error {
        @Serial
        private static final long serialVersionUID = 1L;

        private CallAbortedError() {
            super(null, null, false, false);
        }
    }


    /**
     * Creates a {@link ScriptRuntime} instance with the game API of a game and the {@link #INSTRUCTION_BUDGET}, which writes to the SC:R
     * Enhanced log.
     *
     * @param scriptPackage the script package
     * @param game          the game
     * @param resources     the resource file of the map
     * @param seed          the seed of the script RNG, derived from the game seed
     * @return the created {@link ScriptRuntime} instance
     */
    public static @NotNull ScriptRuntime create(@NotNull ScriptPackage scriptPackage, @NotNull Game game, @NotNull ScriptResources resources,
                                                long seed) {
        return create(scriptPackage, game, resources, seed, INSTRUCTION_BUDGET, ScriptRuntime::writeToLogger);
    }

    /**
     * Creates a {@link ScriptRuntime} instance with the game API of a game; the prelude runs before the first call and outside its instruction
     * budget.
     *
     * @param scriptPackage     the script package
     * @param game              the game
     * @param resources         the resource file of the map, which the game API reads
     * @param seed              the seed of the script RNG, derived from the game seed
     * @param instructionBudget the instruction budget of one call
     * @param logOutput         the output of the scripts' {@code log} and of the runtime's warnings and errors
     * @return the created {@link ScriptRuntime} instance
     * @throws IllegalArgumentException if the instruction budget is not positive
     * @throws IllegalStateException    if the prelude cannot be read or fails
     */
    public static @NotNull ScriptRuntime create(@NotNull ScriptPackage scriptPackage, @NotNull Game game, @NotNull ScriptResources resources,
                                                long seed, int instructionBudget, @NotNull ScriptLogOutput logOutput) {
        if (instructionBudget <= 0) {
            throw Exceptions.newIllegalArgumentException("The instruction budget {0} is not positive.", instructionBudget);
        }

        ScriptContextFactory contextFactory = new ScriptContextFactory(instructionBudget);
        ScriptRandom random = new ScriptRandom(seed);

        try (Context context = contextFactory.enterSetupContext()) {
            TopLevel global = ScriptGlobalUtils.createGlobal(context, random);
            ScriptGame scriptGame = ScriptGame.install(context, global, game, resources);

            return new ScriptRuntime(context, scriptPackage.getManifest().getMain(), logOutput, contextFactory, random, global,
                                     new ScriptModuleLoader(scriptPackage.getScripts(), global, logOutput), scriptGame);
        }
    }

    private static void writeToLogger(@NotNull LogLevel level, @NotNull String moduleName, @NotNull String message) {
        LOGGER.log(level, "{0}: {1}", moduleName, message);
    }

    /**
     * Formats the location of a script error for the log: the file and line of the innermost frame of a script module on the error's stack,
     * where the module threw the error or called the prelude function that threw it; the prelude's own frames are left out.
     *
     * @param exception the script error
     * @return {@code " (file:line)"}, or an empty string if no frame of a module is on the stack
     */
    private static @NotNull String formatScriptLocation(@NotNull RhinoException exception) {
        for (ScriptStackElement element : exception.getScriptStack()) {
            if (element.fileName != null && !ScriptPrelude.isPreludeSource(element.fileName)) {
                return Exceptions.format(" ({0}:{1})", element.fileName, element.lineNumber);
            }
        }

        String sourceName = exception.sourceName();

        return sourceName != null && !ScriptPrelude.isPreludeSource(sourceName) ? Exceptions.format(" ({0}:{1})", sourceName, exception.lineNumber())
                                                                                : "";
    }


    private final @NotNull String mainName;
    private final @NotNull ScriptLogOutput logOutput;

    private final @NotNull ScriptContextFactory contextFactory;
    private final @NotNull ScriptRandom random;
    private final @NotNull TopLevel global;
    private final @NotNull ScriptModuleLoader moduleLoader;
    private final @NotNull ScriptGame scriptGame;
    private final @NotNull ScriptPrelude prelude;

    private boolean @Nullable [] hookedDecisions;
    private int hookedDecisionsLoadCount;

    private boolean started;
    private int callDepth;
    private boolean local;
    private boolean overran;
    private boolean stopped;
    private boolean disposed;

    /**
     * Creates a {@link ScriptRuntime} instance and runs the prelude over the game API and the runtime's host.
     *
     * @param context        the current setup context
     * @param mainName       the name of the script that runs first
     * @param logOutput      the output of the runtime's warnings and errors
     * @param contextFactory the factory of the runtime's contexts
     * @param random         the script RNG of the global scope
     * @param global         the global scope
     * @param moduleLoader   the module loader
     * @param scriptGame     the game API
     * @throws IllegalStateException if the prelude cannot be read or fails
     */
    private ScriptRuntime(@NotNull Context context, @NotNull String mainName, @NotNull ScriptLogOutput logOutput,
                          @NotNull ScriptContextFactory contextFactory, @NotNull ScriptRandom random, @NotNull TopLevel global,
                          @NotNull ScriptModuleLoader moduleLoader, @NotNull ScriptGame scriptGame) {
        this.mainName  = mainName;
        this.logOutput = logOutput;

        this.contextFactory = contextFactory;
        this.random         = random;
        this.global         = global;
        this.moduleLoader   = moduleLoader;
        this.scriptGame     = scriptGame;
        this.prelude        = ScriptPrelude.load(context, global, scriptGame.getCore(), this.createHost(context));

        this.hookedDecisions          = null;
        this.hookedDecisionsLoadCount = 0;

        this.started   = false;
        this.callDepth = 0;
        this.local     = false;
        this.overran   = false;
        this.stopped   = false;
        this.disposed  = false;
    }

    /**
     * @return {@code true} if the script stopped and no entry point runs any more, {@code false} otherwise
     */
    public boolean isStopped() {
        return this.stopped;
    }

    /**
     * @return {@code true} if the game API's functions that change the game state throw, {@code false} otherwise
     */
    public boolean isReadOnly() {
        return this.scriptGame.isReadOnly();
    }

    /**
     * Sets whether the game API's functions that change the game state throw a {@code TypeError}, as they must while a decision runs.
     *
     * @param readOnly {@code true} to make the game read-only
     */
    public void setReadOnly(boolean readOnly) {
        this.scriptGame.setReadOnly(readOnly);
    }

    /**
     * @return the image graphics the script replaces, the same instance for the whole game
     */
    public @NotNull ImageReplacements getImageReplacements() {
        return this.scriptGame.getImageReplacements();
    }

    /**
     * @return the console settings of the script, the same instance for the whole game
     */
    public @NotNull ConsoleSettings getConsoleSettings() {
        return this.scriptGame.getConsoleSettings();
    }

    /**
     * Finds what the last completed {@link #callRender} drew.
     *
     * @return the draw list, or {@code null} if it drew nothing
     */
    public @Nullable ScrDrawList findDrawList() {
        return this.scriptGame.findDrawList();
    }


    /**
     * Loads the main script within one instruction budget.
     *
     * @throws IllegalStateException if the runtime has started already or is disposed
     */
    public void start() {
        this.checkNotDisposed();

        if (this.started) {
            throw Exceptions.newIllegalStateException("The script runtime has started already.");
        }

        this.started = true;

        try (Context context = this.contextFactory.enterOwnContext()) {
            this.enterCall();

            try {
                this.runCall(context, this.mainName, LOAD_CALL_NAME, () -> this.moduleLoader.load(context, this.mainName));
            } finally {
                this.exitCall();
            }
        }
    }

    /**
     * Adds an event to those of the next {@link #runFrame(int)} ({@code core.events}); an event is not added once the script stopped.
     *
     * @param event the event, whose values were captured when it happened
     * @param frame the frame it happened in
     * @throws IllegalStateException if the runtime is disposed
     */
    public void queueEvent(@NotNull SimulationEvent event, int frame) {
        this.checkNotDisposed();

        if (!this.stopped) {
            this.scriptGame.queueEvent(event, frame);
        }
    }

    /**
     * Reads a mod command of a player: a script command adds to the player's value ({@code core.command}), and the value it completes joins
     * the commands of the next {@link #runFrame(int)}. A value that does not decode is logged and dropped; no value is added once the script
     * stopped.
     *
     * @param player the slot id of the issuing player
     * @param data   the command data
     * @throws IllegalStateException if the runtime is disposed
     */
    public void queueModCommand(int player, byte @NotNull [] data) {
        this.checkNotDisposed();

        ScriptCommandMessage message = this.scriptGame.readModCommand(player, data);

        if (message == null || this.stopped) {
            return;
        }

        try (Context context = this.contextFactory.enterOwnContext()) {
            this.scriptGame.queueCommand(context, message);
        } catch (IllegalArgumentException exception) {
            this.logOutput.write(LogLevel.WARNING, this.mainName,
                                 Exceptions.format("A script command of player {0} whose value does not decode is dropped: {1}", player,
                                                   exception.getMessage()));
        }
    }

    /**
     * Starts a frame and calls the prelude's {@code frame(frame)} within one instruction budget: the hooks of the frame's events, timers,
     * commands and the frame. Does nothing once the script stopped.
     *
     * @param frame the frame number
     * @throws IllegalStateException if the runtime has not started or is disposed
     */
    public void runFrame(int frame) {
        this.checkNotDisposed();

        if (!this.started) {
            throw Exceptions.newIllegalStateException("The script runtime has not started.");
        }

        if (this.stopped) {
            return;
        }

        this.scriptGame.startFrame();

        try (Context context = this.contextFactory.enterOwnContext()) {
            this.enterCall();

            try {
                this.runCall(context, this.mainName, FRAME_CALL_NAME, () -> this.prelude.frame(context, frame));
            } finally {
                this.exitCall();
            }
        }
    }

    /**
     * Calls the prelude's {@code decide(kind, record)} with the record of a decision and the game read-only, then copies what the decision
     * hooks left in the record back into the decision. Does nothing before {@link #start()}, once the script stopped, for a decision no script
     * decides, or when the prelude reported that no hook takes the decision's kind and no module has loaded since.
     *
     * @param decision the decision
     * @throws IllegalStateException if the runtime is disposed
     */
    public void decide(@NotNull SimulationDecision decision) {
        this.checkNotDisposed();

        int kind = this.scriptGame.findDecisionKind(decision);
        boolean[] hooked = this.hookedDecisions;

        if (kind < 0 || !this.started || this.stopped ||
            hooked != null && this.hookedDecisionsLoadCount == this.moduleLoader.getLoadCount() && (kind >= hooked.length || !hooked[kind])) {
            return;
        }

        boolean readOnly = this.scriptGame.isReadOnly();

        try (Context context = this.contextFactory.enterOwnContext()) {
            this.enterCall();
            this.scriptGame.setReadOnly(true);

            try {
                Scriptable[] record = new Scriptable[1];

                this.runCall(context, this.mainName, DECIDE_CALL_NAME, () -> {
                    record[0] = this.scriptGame.createDecisionRecord(context, decision);

                    this.prelude.decide(context, kind, record[0]);
                });

                if (record[0] != null) {
                    this.scriptGame.applyDecision(record[0], decision);
                }
            } finally {
                this.scriptGame.setReadOnly(readOnly);
                this.exitCall();
            }
        }
    }

    /**
     * Creates the local input of a command card button press or target in the values of the lower layer ({@link ScriptGame#createButtonInput}).
     *
     * @param input the button input
     * @return the created {@link ScriptLocalInput} instance
     */
    public @NotNull ScriptLocalInput createButtonInput(@NotNull ScrButtonInput input) {
        return this.scriptGame.createButtonInput(input);
    }

    /**
     * Calls the prelude's {@code localInput(record)} with the record of a local input, the game read-only and local. Does nothing before
     * {@link #start()}, once the script stopped and while another call runs.
     *
     * @param input the local input
     * @return {@code true} if a hook consumed the input, {@code false} otherwise
     * @throws IllegalStateException if the runtime is disposed
     */
    public boolean callLocalInput(@NotNull ScriptLocalInput input) {
        this.checkNotDisposed();

        ScriptableObject[] record = new ScriptableObject[1];
        boolean[] consumed = new boolean[1];
        CallResult result = this.callLocal(LOCAL_INPUT_CALL_NAME, context -> {
            record[0]   = this.scriptGame.createInputRecord(context, input);
            consumed[0] = this.prelude.localInput(context, record[0]);
        });

        if (record[0] == null) {
            return false;
        }

        if (result != CallResult.COMPLETED) {
            consumed[0] = Boolean.TRUE.equals(ScriptableObject.getProperty(record[0], CONSUMED_PROPERTY));
        }

        this.scriptGame.addInput(record[0]);

        return consumed[0];
    }

    /**
     * Starts a local frame and calls the prelude's {@code localFrame(frame)} with the game read-only and local as for {@link #callLocalInput}.
     * Does nothing before {@link #start()}, once the script stopped and while another call runs.
     *
     * @param frame the number of the frame the local machine played
     * @throws IllegalStateException if the runtime is disposed
     */
    public void callLocalFrame(int frame) {
        this.checkNotDisposed();

        this.callLocal(LOCAL_FRAME_CALL_NAME, context -> {
            this.scriptGame.startLocalFrame();
            this.prelude.localFrame(context, frame);
        });
    }

    /**
     * Calls the prelude's {@code render()} with the game read-only and local as for {@link #callLocalInput}, the drawing functions drawing into
     * a new list. The new list replaces the drawn one; a call that exceeds the budget or overflows the stack replaces it with nothing. Before
     * {@link #start()} and once the script stopped, nothing is drawn. Does nothing while another call runs.
     *
     * @throws IllegalStateException if the runtime is disposed
     */
    public void callRender() {
        this.checkNotDisposed();

        if (this.callDepth > 0) {
            return;
        }

        this.scriptGame.beginRender();

        try {
            this.callLocal(RENDER_CALL_NAME, this.prelude::render);
        } finally {
            this.scriptGame.endRender(!this.overran);
        }
    }

    /**
     * Calls an entry point of local code within one instruction budget, the game read-only and local.
     *
     * @return the result of the call, {@code null} if it did not run
     */
    private @Nullable CallResult callLocal(@NotNull String callName, @NotNull Consumer<Context> call) {
        if (this.callDepth > 0) {
            return null;
        }

        this.overran = false;

        if (!this.started || this.stopped) {
            return null;
        }

        boolean readOnly = this.scriptGame.isReadOnly();

        try (Context context = this.contextFactory.enterOwnContext()) {
            this.enterCall();
            this.setLocal(true);

            try {
                return this.runCall(context, this.mainName, callName, () -> call.accept(context));
            } finally {
                this.setLocal(false);
                this.scriptGame.setReadOnly(readOnly);
                this.exitCall();
            }
        }
    }

    private void setLocal(boolean local) {
        this.local = local;

        this.scriptGame.setReadOnly(local);
        this.scriptGame.setLocal(local);
        this.random.setLocked(local);
        this.moduleLoader.setLocal(local);
    }

    /**
     * Hands the script commands that wait in the game's outbox to the local player's submitter, in order until the outgoing turn has no room;
     * the rest waits for the next call. Without a submitter, as in a replay or for an observer, the waiting commands are dropped.
     *
     * @param submitter the local player's mod command submitter, or {@code null} if the game has none
     * @throws IllegalStateException if the runtime is disposed
     */
    public void submitCommands(@Nullable ScrModCommandSubmitter submitter) {
        this.checkNotDisposed();
        this.scriptGame.getCommandOutbox().submit(submitter);
    }

    /**
     * Plays the sounds that local code played since the previous call ({@code core.sound.playAt}) with the sounds of the current game loop
     * iteration, which the client plays after it.
     *
     * @throws IllegalStateException if the runtime is disposed
     */
    public void playLocalSounds() {
        this.checkNotDisposed();
        this.scriptGame.playLocalSounds();
    }

    /**
     * Starts a call into the script: a call while no other call runs gets a new instruction budget.
     */
    private void enterCall() {
        if (this.callDepth == 0) {
            this.contextFactory.resetInstructionCount();
        }

        this.callDepth++;
    }

    private void exitCall() {
        this.callDepth--;
    }

    /**
     * Creates the host the prelude's entry points run on: {@code getModules()} (the records of the loaded modules,
     * {@link ScriptModuleLoader#getLoadedRecords}), {@code call(callName, function, arguments, module)} (a guarded call, {@link #callGuarded})
     * and {@code setDecisionHooks(hooked)} (which decision kinds have hooks, by kind id, since the last module loaded).
     */
    private @NotNull Scriptable createHost(@NotNull Context context) {
        Scriptable host = context.newObject(this.global);

        ScriptGlobalUtils.defineFunction(this.global, host, "getModules", 0,
                                         (callContext, scope, thisObject, arguments) -> this.moduleLoader.getLoadedRecords(callContext));
        ScriptGlobalUtils.defineFunction(this.global, host, "call", 4,
                                         (callContext, scope, thisObject, arguments) -> this.callGuarded(callContext, arguments));
        ScriptGlobalUtils.defineFunction(this.global, host, "setDecisionHooks", 1, (callContext, scope, thisObject, arguments) -> {
            Object[] hooked = callContext.getElements((Scriptable) arguments[0]);
            boolean[] decisions = new boolean[hooked.length];

            for (int kind = 0; kind < hooked.length; kind++) {
                decisions[kind] = Boolean.TRUE.equals(hooked[kind]);
            }

            this.hookedDecisions          = decisions;
            this.hookedDecisionsLoadCount = this.moduleLoader.getLoadCount();

            return Context.getUndefinedValue();
        });

        return host;
    }

    /**
     * Runs one call of a hook, timer or channel handler for the prelude: the function with the arguments, in the scope of the module record
     * when one is given, otherwise in its own top-level scope, logged under that scope's module. A call that stops the script or exceeds the
     * budget of a local call ends the running entry point.
     *
     * @return {@code true} if the call returned, {@code false} if it threw a script error
     * @throws CallAbortedError if the call stopped the script or exceeded the budget of a local call
     */
    private boolean callGuarded(@NotNull Context context, @NotNull Object @NotNull [] arguments) {
        String callName = Context.toString(arguments[CALL_NAME_ARGUMENT]);
        Function function = (Function) arguments[CALL_FUNCTION_ARGUMENT];
        Object[] functionArguments = context.getElements((Scriptable) arguments[CALL_ARGUMENTS_ARGUMENT]);
        Scriptable scope;
        String moduleName;

        if (arguments.length > CALL_MODULE_ARGUMENT && arguments[CALL_MODULE_ARGUMENT] instanceof Scriptable module) {
            scope      = (Scriptable) ScriptableObject.getProperty(module, MODULE_SCOPE_PROPERTY);
            moduleName = Context.toString(ScriptableObject.getProperty(module, MODULE_NAME_PROPERTY));
        } else {
            scope = ScriptableObject.getTopLevelScope(function);

            ScriptModule module = this.moduleLoader.findModule(scope);

            moduleName = module == null ? this.mainName : module.getName();
        }

        CallResult result = this.runCall(context, moduleName, callName, () -> function.call(context, scope, scope, functionArguments));

        if (result == CallResult.STOPPED || this.local && this.overran) {
            throw new CallAbortedError();
        }

        return result == CallResult.COMPLETED;
    }

    /**
     * Runs one call into the script and then, unless the call runs inside another call, the promise jobs it queued. An uncaught script
     * error ends the call and is logged; an exceeded instruction budget or a Java stack overflow stops the script, or in local code ends the
     * call.
     *
     * @param context    the current context
     * @param moduleName the name of the module the call belongs to
     * @param callName   the name of the call for the log
     * @param call       the call
     * @return the result of the call, {@link CallResult#STOPPED} if the promise jobs stopped the script
     */
    private @NotNull CallResult runCall(@NotNull Context context, @NotNull String moduleName, @NotNull String callName, @NotNull Runnable call) {
        CallResult result = this.runGuarded(moduleName, callName, call);

        if (result == CallResult.STOPPED || this.callDepth > 1) {
            return result;
        }

        return this.runGuarded(moduleName, callName, context::processMicrotasks) == CallResult.STOPPED ? CallResult.STOPPED : result;
    }

    /**
     * Runs an action that calls into the script and handles what the script throws.
     *
     * @return the result of the action
     */
    private @NotNull CallResult runGuarded(@NotNull String moduleName, @NotNull String callName, @NotNull Runnable action) {
        if (this.stopped) {
            return CallResult.STOPPED;
        }

        try {
            action.run();
        } catch (CallAbortedError error) {
            return this.stopped ? CallResult.STOPPED : CallResult.FAILED;
        } catch (ScriptContextFactory.InstructionBudgetError error) {
            if (this.local) {
                this.overran = true;
                this.logOutput.write(LogLevel.ERROR, moduleName, Exceptions.format("{0} ran more than the budget of instructions.", callName));

                return CallResult.FAILED;
            }

            this.stop(moduleName, Exceptions.format("{0} ran more than the budget of instructions; the script stopped.", callName));
        } catch (StackOverflowError error) {
            if (this.local) {
                this.overran = true;
                this.logOutput.write(LogLevel.ERROR, moduleName, Exceptions.format("{0} overflowed the stack.", callName));

                return CallResult.FAILED;
            }

            this.stop(moduleName, Exceptions.format("{0} overflowed the stack; the script stopped.", callName));
        } catch (RhinoException exception) {
            this.logOutput.write(LogLevel.ERROR, moduleName, Exceptions.format("{0} failed: {1}{2}", callName, exception.details(),
                                                                               formatScriptLocation(exception)));

            return CallResult.FAILED;
        } catch (RuntimeException exception) {
            this.logOutput.write(LogLevel.ERROR, moduleName, Exceptions.format("{0} failed: {1}", callName, exception));

            return CallResult.FAILED;
        }

        return this.stopped ? CallResult.STOPPED : CallResult.COMPLETED;
    }

    /**
     * Stops the script and logs why, once: a call that a stopped nested call returns to stops again at its next instruction report.
     */
    private void stop(@NotNull String moduleName, @NotNull String message) {
        if (this.stopped) {
            return;
        }

        this.stopped = true;

        this.logOutput.write(LogLevel.ERROR, moduleName, message);
    }

    private void checkNotDisposed() {
        if (this.disposed) {
            throw Exceptions.newIllegalStateException("The script runtime is disposed.");
        }
    }

    /**
     * Disposes resources.
     * <p>
     * The runtime forgets its modules, events, inputs and waiting script commands; later calls of {@link #start()}, {@link #runFrame(int)},
     * {@link #queueEvent}, {@link #queueModCommand}, {@link #decide}, {@link #callLocalInput}, {@link #callLocalFrame}, {@link #callRender}
     * and {@link #submitCommands} fail.
     */
    public void dispose() {
        this.disposed = true;
        this.stopped  = true;

        this.moduleLoader.clear();
        this.scriptGame.clear();
    }
}
