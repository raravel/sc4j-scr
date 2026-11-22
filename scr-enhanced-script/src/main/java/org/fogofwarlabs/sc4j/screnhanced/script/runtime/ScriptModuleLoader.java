package org.fogofwarlabs.sc4j.screnhanced.script.runtime;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.LogLevel;
import org.fogofwarlabs.sc4j.screnhanced.script.ScriptPackage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.*;
import org.mozilla.javascript.ScriptRuntime;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads the scripts of a script runtime as CommonJS-style modules and keeps them in load order.
 * <p>
 * Each module runs once in its own top-level scope, whose prototype is the global scope, with {@code module} ({@code id} and
 * {@code exports}), {@code exports}, {@code require}, {@code log} and {@code defineHooks} ({@link ScriptModule#defineHooks}).
 * {@code require(id)} returns the {@code module.exports} of a script: an id that starts with {@code ./} or {@code ../} names a script relative
 * to the folder of the requiring script, any other id a script relative to the package root, and {@value ScriptPackage#SCRIPT_EXTENSION} is
 * added when the id does not end with it. A module loads at its first {@code require}; later calls return the cached module, and a module that
 * is still loading returns its current {@code module.exports}, so cycles end. A module whose loading fails is forgotten. Local code
 * ({@link #setLocal}) loads no new module, which would run on one machine only. A module whose hook of {@code defineHooks} is also a top-level
 * function when it has run to its end fails to load. When a module has loaded, it joins the loaded modules that the prelude collects the hooks
 * of ({@link #getLoadedRecords}), and each top-level function with a hook name that no hook has logs one warning.
 */
final class ScriptModuleLoader {
    private static final char NAME_SEPARATOR = '/';
    private static final @NotNull String CURRENT_FOLDER_SEGMENT = ".";
    private static final @NotNull String PARENT_FOLDER_SEGMENT = "..";
    private static final @NotNull String ERROR_NAME = "Error";
    private static final @NotNull String MODULE_PROPERTY = "module";
    private static final @NotNull String ID_PROPERTY = "id";
    private static final @NotNull String EXPORTS_PROPERTY = "exports";
    private static final @NotNull String REQUIRE_PROPERTY = "require";
    private static final @NotNull String LOG_PROPERTY = "log";
    private static final @NotNull String DEFINE_HOOKS_PROPERTY = "defineHooks";
    private static final @NotNull String MESSAGE_SEPARATOR = " ";

    /**
     * Resolves a {@code require} id to a script name.
     *
     * @param requiringName the name of the requiring script
     * @param id            the id
     * @return the script name, or {@code null} if the id is empty, starts with {@code /}, has an empty segment or leaves the package root
     */
    static @Nullable String resolveName(@NotNull String requiringName, @NotNull String id) {
        if (id.isEmpty() || id.charAt(0) == NAME_SEPARATOR) {
            return null;
        }

        List<String> segments = new ArrayList<>();

        if (id.startsWith(CURRENT_FOLDER_SEGMENT + NAME_SEPARATOR) || id.startsWith(PARENT_FOLDER_SEGMENT + NAME_SEPARATOR)) {
            String[] requiringSegments = requiringName.split(String.valueOf(NAME_SEPARATOR));

            segments.addAll(Arrays.asList(requiringSegments).subList(0, requiringSegments.length - 1));
        }

        for (String segment : id.split(String.valueOf(NAME_SEPARATOR), -1)) {
            if (segment.equals(CURRENT_FOLDER_SEGMENT)) {
                continue;
            }

            if (segment.equals(PARENT_FOLDER_SEGMENT)) {
                if (segments.isEmpty()) {
                    return null;
                }

                segments.removeLast();
                continue;
            }

            if (segment.isEmpty()) {
                return null;
            }

            segments.add(segment);
        }

        if (segments.isEmpty()) {
            return null;
        }

        String name = String.join(String.valueOf(NAME_SEPARATOR), segments);

        return name.endsWith(ScriptPackage.SCRIPT_EXTENSION) ? name : name + ScriptPackage.SCRIPT_EXTENSION;
    }

    private static @NotNull String joinMessage(@NotNull Object @NotNull [] arguments) {
        StringBuilder message = new StringBuilder();

        for (Object argument : arguments) {
            if (!message.isEmpty()) {
                message.append(MESSAGE_SEPARATOR);
            }

            message.append(Context.toString(argument));
        }

        return message.toString();
    }


    private final @NotNull Map<String, byte[]> scripts;
    private final @NotNull TopLevel global;
    private final @NotNull ScriptLogOutput logOutput;

    private final @NotNull Map<String, ScriptModule> modulesByName;
    private final @NotNull List<ScriptModule> modules;
    private @Nullable Scriptable loadedRecords;
    private int loadCount;

    private boolean local;

    /**
     * Creates a {@link ScriptModuleLoader} instance without modules.
     *
     * @param scripts   the script contents by name
     * @param global    the global scope
     * @param logOutput the output of {@code log} and of the hook warnings
     */
    ScriptModuleLoader(@NotNull Map<String, byte[]> scripts, @NotNull TopLevel global, @NotNull ScriptLogOutput logOutput) {
        this.scripts   = scripts;
        this.global    = global;
        this.logOutput = logOutput;

        this.modulesByName = new HashMap<>();
        this.modules       = new ArrayList<>();
        this.loadedRecords = null;
        this.loadCount     = 0;

        this.local = false;
    }

    /**
     * Sets whether local code runs: {@code require} of a module that has not loaded then throws a {@code TypeError}.
     *
     * @param local {@code true} while local code runs
     */
    void setLocal(boolean local) {
        this.local = local;
    }

    /**
     * @return the number of modules that have loaded so far
     */
    int getLoadCount() {
        return this.loadCount;
    }

    /**
     * Finds the loaded module of a top-level scope.
     *
     * @param scope the scope
     * @return the module, or {@code null} if no loaded or loading module has the scope
     */
    @Nullable ScriptModule findModule(@NotNull Scriptable scope) {
        for (ScriptModule module : this.modules) {
            if (module.getScope() == scope) {
                return module;
            }
        }

        return null;
    }

    /**
     * Returns the records of the loaded modules ({@link ScriptModule#getRecord}) in the order their loading started, as a script array: the
     * same array until another module has loaded.
     *
     * @param context the current context
     * @return the array of the records
     */
    @NotNull Scriptable getLoadedRecords(@NotNull Context context) {
        Scriptable records = this.loadedRecords;

        if (records != null) {
            return records;
        }

        List<Object> loaded = new ArrayList<>();

        for (ScriptModule module : this.modules) {
            if (module.isLoaded()) {
                loaded.add(module.getRecord(context, this.global));
            }
        }

        records = context.newArray(this.global, loaded.toArray());

        this.loadedRecords = records;

        return records;
    }

    /**
     * Loads a script as a module, the first one of the runtime.
     *
     * @param context the current context
     * @param name    the script name
     * @throws IllegalStateException if the package has no script with the name
     */
    void load(@NotNull Context context, @NotNull String name) {
        byte[] source = this.scripts.get(name);

        if (source == null) {
            throw Exceptions.newIllegalStateException("The script package has no script {0}.", name);
        }

        this.loadModule(context, name, source);
    }

    private @Nullable Object require(@NotNull Context context, @NotNull ScriptModule requiring, @NotNull Object @NotNull [] arguments) {
        if (arguments.length == 0 || !(arguments[0] instanceof CharSequence id)) {
            throw ScriptRuntime.typeError("require needs a script name.");
        }

        String name = resolveName(requiring.getName(), id.toString());

        if (name == null) {
            throw ScriptRuntime.constructError(ERROR_NAME, Exceptions.format("The script name {0} is not valid.", id));
        }

        ScriptModule module = this.modulesByName.get(name);

        if (module != null) {
            return module.getExports();
        }

        if (this.local) {
            throw ScriptRuntime.typeError(Exceptions.format("Local code cannot load the module {0}.", name));
        }

        byte[] source = this.scripts.get(name);

        if (source == null) {
            throw ScriptRuntime.constructError(ERROR_NAME, Exceptions.format("The script package has no script {0}.", name));
        }

        return this.loadModule(context, name, source).getExports();
    }

    private @NotNull ScriptModule loadModule(@NotNull Context context, @NotNull String name, byte @NotNull [] source) {
        ScriptModule module = this.createModule(context, name);

        this.modulesByName.put(name, module);
        this.modules.add(module);

        try {
            Script script = context.compileString(new String(source, StandardCharsets.UTF_8), name, 1, null);

            script.exec(context, module.getScope());
            module.checkDefinedHooks();
        } catch (RuntimeException | Error exception) {
            this.modulesByName.remove(name);
            this.modules.remove(module);

            throw exception;
        }

        module.setLoaded();

        this.loadedRecords = null;
        this.loadCount++;

        for (String unknownName : module.findUnknownHookNames()) {
            this.logOutput.write(LogLevel.WARNING, name, Exceptions.format("{0} is not a known hook and is not called.", unknownName));
        }

        return module;
    }

    private @NotNull ScriptModule createModule(@NotNull Context context, @NotNull String name) {
        TopLevel scope = new TopLevel();
        ScriptableObject moduleObject = (ScriptableObject) context.newObject(this.global);
        Scriptable exports = context.newObject(this.global);
        ScriptModule module = new ScriptModule(name, scope, moduleObject);

        scope.setPrototype(this.global);
        scope.setParentScope(null);
        scope.cacheBuiltins(this.global, false);

        moduleObject.defineProperty(ID_PROPERTY, name, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
        moduleObject.defineProperty(EXPORTS_PROPERTY, exports, ScriptableObject.EMPTY);

        scope.defineProperty(MODULE_PROPERTY, moduleObject, ScriptableObject.DONTENUM);
        scope.defineProperty(EXPORTS_PROPERTY, exports, ScriptableObject.DONTENUM);
        ScriptGlobalUtils.defineFunction(this.global, scope, REQUIRE_PROPERTY, 1,
                                         (callContext, callScope, thisObject, arguments) -> this.require(callContext, module, arguments));
        scope.defineProperty(LOG_PROPERTY, this.createLog(context, name), ScriptableObject.DONTENUM);
        ScriptGlobalUtils.defineFunction(this.global, scope, DEFINE_HOOKS_PROPERTY, 1, (callContext, callScope, thisObject, arguments) -> {
            module.defineHooks(callContext, this.global, arguments);

            return Context.getUndefinedValue();
        });

        return module;
    }

    /**
     * Creates the {@code log} object of a module: {@code info}, {@code warn} and {@code error} write their arguments, converted to strings
     * and joined with spaces, at the matching level with the module name.
     */
    private @NotNull Scriptable createLog(@NotNull Context context, @NotNull String moduleName) {
        Scriptable log = context.newObject(this.global);

        this.defineLogFunction(log, "info", LogLevel.INFO, moduleName);
        this.defineLogFunction(log, "warn", LogLevel.WARNING, moduleName);
        this.defineLogFunction(log, "error", LogLevel.ERROR, moduleName);

        return log;
    }

    private void defineLogFunction(@NotNull Scriptable log, @NotNull String name, @NotNull LogLevel level, @NotNull String moduleName) {
        ScriptGlobalUtils.defineFunction(this.global, log, name, 1, (context, scope, thisObject, arguments) -> {
            this.logOutput.write(level, moduleName, joinMessage(arguments));

            return Context.getUndefinedValue();
        });
    }

    /**
     * Forgets every module.
     */
    void clear() {
        this.modulesByName.clear();
        this.modules.clear();

        this.loadedRecords = null;
    }
}
