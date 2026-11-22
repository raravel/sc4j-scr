package org.fogofwarlabs.sc4j.screnhanced.script.runtime;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Lists the hooks of the upper layer: top-level functions of a module with a known name, or the methods with a known name of the objects a
 * module gives to {@code defineHooks} while it loads, which the prelude collects per module and calls from the runtime's entry points. A
 * top-level function runs with {@code this} set to the module's top-level scope, a method of {@code defineHooks} with {@code this} set to its
 * object. A module may define a hook once: {@code defineHooks} may be called more than once with other hooks.
 * <p>
 * A hook name is {@value #NAME_PREFIX} followed by an upper-case letter; the runtime warns of a top-level function with such a name that no
 * hook has. A hook runs in every module that has it, in load order.
 * <p>
 * An event hook receives one event object at the start of the frame after the event; a decision hook ({@code onBefore*}) receives one
 * decision object while the game routine waits for the decision. These hooks run on every peer, as does the rest of the shared code.
 * <p>
 * A local hook ({@code onLocal*} and {@link #RENDER}) receives one event of the local player's input at once, the frame number after a played
 * frame ({@link #LOCAL_FRAME}), or the screen object to draw on ({@link #RENDER}), on the local machine only, with the game read-only
 * ({@link #isLocal()}).
 */
public enum ScriptHook {
    /**
     * Called once at the first frame, before the other hooks of the frame, without arguments.
     */
    START("onStart"),
    /**
     * Called every game frame with the frame number, after the timers, the channel handlers and the event hooks of the frame.
     */
    FRAME("onFrame"),

    /**
     * Called with the event of a created unit.
     */
    UNIT_CREATED("onUnitCreated"),
    /**
     * Called with the event of a completed unit.
     */
    UNIT_COMPLETED("onUnitCompleted"),
    /**
     * Called with the event of a unit that started to die, with its killer and the weapon when known.
     */
    UNIT_DIED("onUnitDied"),
    /**
     * Called with the event of hit point damage to a unit.
     */
    UNIT_DAMAGED("onUnitDamaged"),
    /**
     * Called with the event of an order a unit received.
     */
    ORDER_ISSUED("onOrderIssued"),
    /**
     * Called with the event of a target an automatic target search found.
     */
    TARGET_ACQUIRED("onTargetAcquired"),
    /**
     * Called with the event of a unit a producer trained.
     */
    UNIT_TRAINED("onUnitTrained"),
    /**
     * Called with the event of a finished research.
     */
    RESEARCH_COMPLETED("onResearchCompleted"),
    /**
     * Called with the event of a finished upgrade.
     */
    UPGRADE_COMPLETED("onUpgradeCompleted"),
    /**
     * Called with the event of a cast technology.
     */
    SPELL_CAST("onSpellCast"),
    /**
     * Called with the event of a created bullet.
     */
    BULLET_CREATED("onBulletCreated"),
    /**
     * Called with the event of a bullet hit.
     */
    BULLET_HIT("onBulletHit"),
    /**
     * Called with the event of a unit that changed its owner.
     */
    OWNER_CHANGED("onOwnerChanged"),
    /**
     * Called with the event of a unit that entered a transport or a Bunker.
     */
    UNIT_LOADED("onUnitLoaded"),
    /**
     * Called with the event of a unit that left a transport or a Bunker.
     */
    UNIT_UNLOADED("onUnitUnloaded"),
    /**
     * Called with the event of a player who left the game.
     */
    PLAYER_LEFT("onPlayerLeft"),
    /**
     * Called with the event of a player's result.
     */
    PLAYER_RESULT("onPlayerResult"),

    /**
     * Called on the local machine with the event of a chat line the local player submitted.
     */
    LOCAL_CHAT("onLocalChat"),
    /**
     * Called on the local machine with the event of a key the local player pressed.
     */
    LOCAL_KEY_DOWN("onLocalKeyDown"),
    /**
     * Called on the local machine with the event of a key the local player released.
     */
    LOCAL_KEY_UP("onLocalKeyUp"),
    /**
     * Called on the local machine with the event of a mouse button the local player pressed over the map.
     */
    LOCAL_MOUSE_DOWN("onLocalMouseDown"),
    /**
     * Called on the local machine with the event of a mouse button the local player released.
     */
    LOCAL_MOUSE_UP("onLocalMouseUp"),
    /**
     * Called on the local machine with the event of a command card button the local player pressed, before its action runs.
     */
    LOCAL_BUTTON_PRESSED("onLocalButtonPressed"),
    /**
     * Called on the local machine with the event of a map point or unit the local player targeted with a script target button.
     */
    LOCAL_BUTTON_TARGETED("onLocalButtonTargeted"),
    /**
     * Called on the local machine with the frame number after each game frame the machine plays, live or replayed.
     */
    LOCAL_FRAME("onLocalFrame"),
    /**
     * Called on the local machine with the screen object after each game frame the machine plays, live or replayed, after
     * {@link #LOCAL_FRAME}; what it draws is shown until the next call.
     */
    RENDER("onRender", true),

    /**
     * Called with the decision on the damage of a weapon hit.
     */
    BEFORE_DAMAGE("onBeforeDamage"),
    /**
     * Called with the decision on an order issued to a unit.
     */
    BEFORE_ORDER("onBeforeOrder"),
    /**
     * Called with the decision on the target an automatic target search found.
     */
    BEFORE_TARGET_ACQUIRED("onBeforeTargetAcquired");

    /**
     * The prefix of every hook name.
     */
    public static final @NotNull String NAME_PREFIX = "on";
    /**
     * The prefix of every local hook name.
     */
    public static final @NotNull String LOCAL_NAME_PREFIX = "onLocal";

    private static final @NotNull Map<String, ScriptHook> HOOKS_BY_FUNCTION_NAME = new HashMap<>();

    static {
        for (ScriptHook hook : values()) {
            HOOKS_BY_FUNCTION_NAME.put(hook.functionName, hook);
        }
    }

    /**
     * Finds the hook of a function name.
     *
     * @param functionName the function name
     * @return the hook with the function name, or {@code null} if no hook has it
     */
    public static @Nullable ScriptHook findByFunctionName(@NotNull String functionName) {
        return HOOKS_BY_FUNCTION_NAME.get(functionName);
    }

    /**
     * Checks whether a function name has the form of a hook name.
     *
     * @param functionName the function name
     * @return {@code true} if the name is {@value #NAME_PREFIX} followed by an upper-case ASCII letter and more, {@code false} otherwise
     */
    public static boolean isHookName(@NotNull String functionName) {
        if (functionName.length() <= NAME_PREFIX.length() || !functionName.startsWith(NAME_PREFIX)) {
            return false;
        }

        char first = functionName.charAt(NAME_PREFIX.length());

        return first >= 'A' && first <= 'Z';
    }


    private final @NotNull String functionName;
    private final boolean local;

    ScriptHook(@NotNull String functionName) {
        this(functionName, functionName.startsWith(LOCAL_NAME_PREFIX));
    }

    ScriptHook(@NotNull String functionName, boolean local) {
        this.functionName = functionName;
        this.local        = local;
    }

    /**
     * @return the name of the hook function
     */
    public @NotNull String getFunctionName() {
        return this.functionName;
    }

    /**
     * Checks whether the hook runs on the local machine only: its name starts with {@value #LOCAL_NAME_PREFIX} or it is {@link #RENDER}.
     *
     * @return {@code true} if the hook runs on the local machine only, {@code false} if it runs on every peer
     */
    public boolean isLocal() {
        return this.local;
    }
}
