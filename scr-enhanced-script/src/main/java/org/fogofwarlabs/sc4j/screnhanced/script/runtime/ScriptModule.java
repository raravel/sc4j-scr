package org.fogofwarlabs.sc4j.screnhanced.script.runtime;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.*;
import org.mozilla.javascript.ScriptRuntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a loaded script of a script runtime: its name, its own top-level scope, its {@code module} object, the hooks it defined with
 * {@code defineHooks} and the record the prelude collects its hooks from.
 */
final class ScriptModule {
    private static final @NotNull String EXPORTS_PROPERTY = "exports";
    private static final @NotNull String NAME_PROPERTY = "name";
    private static final @NotNull String SCOPE_PROPERTY = "scope";
    private static final @NotNull String DEFINED_HOOKS_PROPERTY = "definedHooks";
    private static final @NotNull String ERROR_NAME = "Error";

    private final @NotNull String name;
    private final @NotNull TopLevel scope;
    private final @NotNull ScriptableObject moduleObject;

    private final @NotNull Map<String, Function> definedHooks;
    private @Nullable Scriptable record;
    private boolean loaded;

    /**
     * Creates a {@link ScriptModule} instance that is loading.
     *
     * @param name         the script name
     * @param scope        the module's top-level scope
     * @param moduleObject the module's {@code module} object
     */
    ScriptModule(@NotNull String name, @NotNull TopLevel scope, @NotNull ScriptableObject moduleObject) {
        this.name         = name;
        this.scope        = scope;
        this.moduleObject = moduleObject;

        this.definedHooks = new LinkedHashMap<>();
        this.record       = null;
        this.loaded       = false;
    }

    /**
     * @return the script name
     */
    @NotNull String getName() {
        return this.name;
    }

    /**
     * @return the module's top-level scope
     */
    @NotNull TopLevel getScope() {
        return this.scope;
    }

    /**
     * @return the current value of {@code module.exports}
     */
    @Nullable Object getExports() {
        return ScriptableObject.getProperty(this.moduleObject, EXPORTS_PROPERTY);
    }

    /**
     * @return {@code true} if the module's script has run to its end, {@code false} while it loads
     */
    boolean isLoaded() {
        return this.loaded;
    }

    /**
     * Marks the module loaded: its script has run to its end.
     */
    void setLoaded() {
        this.loaded = true;
    }

    /**
     * Defines hooks of the module for {@code defineHooks(hooks)}: each own enumerable property of the object becomes the hook of its name, a
     * function that runs with {@code this} set to the object. The call adds no hook when one property fails.
     *
     * @param context   the current context
     * @param global    the global scope
     * @param arguments the arguments of the call
     * @throws EcmaError if the module has loaded, the argument is no object, a property name is no hook name, a value is no function, or a
     *                   hook is defined already, in {@code defineHooks} or as a top-level function
     */
    void defineHooks(@NotNull Context context, @NotNull Scriptable global, @NotNull Object @NotNull [] arguments) {
        if (this.loaded) {
            throw ScriptRuntime.constructError(ERROR_NAME, "defineHooks works only while the module loads.");
        }

        if (arguments.length == 0 || !(arguments[0] instanceof Scriptable hooks) || hooks instanceof Function) {
            throw ScriptRuntime.typeError("defineHooks needs an object of hooks.");
        }

        Map<String, Function> added = new LinkedHashMap<>();

        for (Object id : hooks.getIds()) {
            String hookName = Context.toString(id);

            if (ScriptHook.findByFunctionName(hookName) == null) {
                throw ScriptRuntime.typeError(Exceptions.format("{0} is not a known hook.", hookName));
            }

            if (!(ScriptableObject.getProperty(hooks, hookName) instanceof Function hook)) {
                throw ScriptRuntime.typeError(Exceptions.format("The hook {0} is not a function.", hookName));
            }

            if (this.definedHooks.containsKey(hookName)) {
                throw ScriptRuntime.constructError(ERROR_NAME, Exceptions.format("The hook {0} is defined twice with defineHooks.", hookName));
            }

            this.checkNoTopLevelHook(hookName);

            added.put(hookName, new BoundFunction(context, global, hook, hooks, ScriptRuntime.emptyArgs));
        }

        this.definedHooks.putAll(added);
    }

    /**
     * Checks that no hook defined with {@code defineHooks} is also a top-level function of the scope, as when the module has run to its end.
     *
     * @throws EcmaError if a hook is both
     */
    void checkDefinedHooks() {
        for (String hookName : this.definedHooks.keySet()) {
            this.checkNoTopLevelHook(hookName);
        }
    }

    private void checkNoTopLevelHook(@NotNull String hookName) {
        if (this.scope.get(hookName, this.scope) instanceof Function) {
            throw ScriptRuntime.constructError(ERROR_NAME, Exceptions.format("The hook {0} is both a top-level function and defined with " +
                                                                             "defineHooks.", hookName));
        }
    }

    /**
     * Returns the module's record for the prelude, created once: an object with the module's {@code name}, {@code scope} and
     * {@code definedHooks} (the hooks of {@code defineHooks} by name), to which the prelude adds the hooks it collected.
     *
     * @param context the current context
     * @param global  the global scope
     * @return the record
     */
    @NotNull Scriptable getRecord(@NotNull Context context, @NotNull Scriptable global) {
        Scriptable moduleRecord = this.record;

        if (moduleRecord == null) {
            moduleRecord = context.newObject(global);

            ScriptableObject.putProperty(moduleRecord, NAME_PROPERTY, this.name);
            ScriptableObject.putProperty(moduleRecord, SCOPE_PROPERTY, this.scope);

            Scriptable hooks = context.newObject(global);

            for (Map.Entry<String, Function> hook : this.definedHooks.entrySet()) {
                ScriptableObject.putProperty(hooks, hook.getKey(), hook.getValue());
            }

            ScriptableObject.putProperty(moduleRecord, DEFINED_HOOKS_PROPERTY, hooks);

            this.record = moduleRecord;
        }

        return moduleRecord;
    }

    /**
     * Lists the top-level functions of the scope with the form of a hook name that no hook has.
     *
     * @return the names, in definition order
     */
    @NotNull List<@NotNull String> findUnknownHookNames() {
        List<String> unknownNames = new ArrayList<>();

        for (Object id : this.scope.getAllIds()) {
            if (id instanceof String functionName && ScriptHook.isHookName(functionName) && ScriptHook.findByFunctionName(functionName) == null &&
                this.scope.get(functionName, this.scope) instanceof Function) {
                unknownNames.add(functionName);
            }
        }

        return unknownNames;
    }
}
