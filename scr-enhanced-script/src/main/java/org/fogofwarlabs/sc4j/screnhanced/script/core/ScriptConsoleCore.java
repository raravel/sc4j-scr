package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrConsoleArt;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrConsolePlacement;
import org.fogofwarlabs.sc4j.scr.game.presentation.LocalDisplay;
import org.fogofwarlabs.sc4j.screnhanced.graphics.ConsoleSettings;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

/**
 * Defines {@code core.console}: what a map script sets on the console ({@link ConsoleSettings}), which the classic console merges with what EUD
 * memory gives, the script's setting winning for each item it set. The functions run in shared and local code; shared code sets the console of
 * every peer, local code only the local one. They change no game state.
 * <p>
 * {@code setVisible(visible)} and {@code isVisible()} (the whole console); {@code setDialogVisible(dialog, visible)},
 * {@code setDialogPosition(dialog, x, y)} (the top left in the classic {@code 640} by {@code 480} screen), {@code isDialogVisible(dialog)},
 * {@code setDialogAnchor(dialog, anchor)} (the interface edge the classic screen of the dialog's positions stands at, the order of
 * {@link ScrConsolePlacement.Anchor}: {@code 0} left, {@code 1} centered, {@code 2} right) for the dialogs of {@link ScrConsolePlacement.Dialog}
 * in order; {@code setMinimapSize(size)} and {@code getMinimapSize()} (the side of the drawn minimap area, {@code 1} to
 * {@value #MAXIMUM_MINIMAP_SIZE} interface units, {@value ScrConsolePlacement#MINIMAP_SIZE} installed);
 * {@code setControlVisible(dialog, control, visible)}, {@code setControlPosition(dialog, control, x, y)} (the top left in the classic screen)
 * and {@code restoreControl(dialog, control)} (forgets both, so that EUD memory drives the control again) for a control of a dialog by its
 * identifier in the dialog's definition, a signed WORD; {@code replaceImage(image, imageId)} (the console art files of
 * {@link ScrConsoleArt} in order; {@code -1} for the installed file) and {@code setResourceIcon(icon, imageId)} (the resource icon frames,
 * {@code 0} minerals, {@code 1} to {@code 3} gas and {@code 4} to {@code 6} supply of the Zerg, Terran and Protoss; {@code -1} for the icon of
 * EUD memory or the installed one).
 * <p>
 * Local code only: {@code getDialogRectangle(dialog)}, {@code getControlRectangle(dialog, control)} and {@code getMinimapRectangle()} (the
 * minimap area, control {@value ScrConsolePlacement#MINIMAP_AREA_CONTROL} of the minimap dialog) return the rectangle the client last drew,
 * {@code [x, y, width, height]} in interface units ({@link LocalDisplay#findDialogRectangle}), or {@code null} for a hidden or not yet drawn
 * dialog or control.
 */
final class ScriptConsoleCore {
    private static final @NotNull ScrConsolePlacement.Dialog @NotNull [] DIALOGS = ScrConsolePlacement.Dialog.values();
    private static final @NotNull ScrConsolePlacement.Anchor @NotNull [] ANCHORS = ScrConsolePlacement.Anchor.values();
    private static final @NotNull ScrConsoleArt @NotNull [] ARTS = ScrConsoleArt.values();
    /**
     * The largest side of the minimap area, the interface height.
     */
    private static final int MAXIMUM_MINIMAP_SIZE = LocalDisplay.CLASSIC_HEIGHT;
    private static final int RECTANGLE_VALUES = 4;

    private final @NotNull ScriptGame scriptGame;
    private final @NotNull ConsoleSettings settings;
    private final @NotNull ScriptGraphicsCore graphicsCore;

    /**
     * Creates a {@link ScriptConsoleCore} instance.
     *
     * @param scriptGame   the game binding, whose local display gives the drawn rectangles
     * @param settings     the console settings of the script
     * @param graphicsCore the resource functions, which read image handles
     */
    ScriptConsoleCore(@NotNull ScriptGame scriptGame, @NotNull ConsoleSettings settings, @NotNull ScriptGraphicsCore graphicsCore) {
        this.scriptGame   = scriptGame;
        this.settings     = settings;
        this.graphicsCore = graphicsCore;
    }

    /**
     * Defines the functions of {@code core.console}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void define(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "setVisible", 1, (context, scope, thisObject, arguments) -> {
            this.settings.setConsoleVisible(ScriptCoreUtils.readBoolean(arguments, 0));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "isVisible", 0, (context, scope, thisObject, arguments) -> this.settings.isConsoleVisible());
        ScriptCoreUtils.defineFunction(global, namespace, "setDialogVisible", 2, (context, scope, thisObject, arguments) -> {
            this.settings.setDialogVisible(readDialog(arguments), ScriptCoreUtils.readBoolean(arguments, 1));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "isDialogVisible", 1,
                                       (context, scope, thisObject, arguments) -> this.settings.isDialogVisible(readDialog(arguments)));
        ScriptCoreUtils.defineFunction(global, namespace, "setDialogPosition", 3, (context, scope, thisObject, arguments) -> {
            this.settings.setDialogPosition(readDialog(arguments), ScriptCoreUtils.readInteger(arguments, 1), ScriptCoreUtils.readInteger(arguments, 2));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setDialogAnchor", 2, (context, scope, thisObject, arguments) -> {
            int anchor = ScriptCoreUtils.requireId("console anchor", ScriptCoreUtils.readInteger(arguments, 1), ANCHORS.length);

            this.settings.setDialogAnchor(readDialog(arguments), ANCHORS[anchor]);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setMinimapSize", 1, (context, scope, thisObject, arguments) -> {
            int size = ScriptCoreUtils.readInteger(arguments, 0);

            if (size < 1 || size > MAXIMUM_MINIMAP_SIZE) {
                throw ScriptRuntime.rangeError(Exceptions.format("The minimap size {0} is not from 1 to {1}.", size, MAXIMUM_MINIMAP_SIZE));
            }

            this.settings.setMinimapSize(size);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getMinimapSize", 0,
                                       (context, scope, thisObject, arguments) -> (double) this.settings.getMinimapSize());
        ScriptCoreUtils.defineFunction(global, namespace, "getDialogRectangle", 1, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkLocal();

            float[] rectangle = new float[RECTANGLE_VALUES];

            if (!this.getLocalDisplay().findDialogRectangle(readDialog(arguments), rectangle)) {
                return null;
            }

            return createRectangle(context, global, rectangle);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getControlRectangle", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkLocal();

            float[] rectangle = new float[RECTANGLE_VALUES];

            if (!this.getLocalDisplay().findControlRectangle(readDialog(arguments), readControl(arguments), rectangle)) {
                return null;
            }

            return createRectangle(context, global, rectangle);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getMinimapRectangle", 0, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkLocal();

            float[] rectangle = new float[RECTANGLE_VALUES];
            ScrConsolePlacement.Dialog minimap = ScrConsolePlacement.Dialog.MINIMAP;

            if (!this.getLocalDisplay().findControlRectangle(minimap, ScrConsolePlacement.MINIMAP_AREA_CONTROL, rectangle)) {
                return null;
            }

            return createRectangle(context, global, rectangle);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setControlVisible", 3, (context, scope, thisObject, arguments) -> {
            this.settings.setControlVisible(readDialog(arguments), readControl(arguments), ScriptCoreUtils.readBoolean(arguments, 2));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setControlPosition", 4, (context, scope, thisObject, arguments) -> {
            this.settings.setControlPosition(readDialog(arguments), readControl(arguments), ScriptCoreUtils.readInteger(arguments, 2),
                                             ScriptCoreUtils.readInteger(arguments, 3));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "restoreControl", 2, (context, scope, thisObject, arguments) -> {
            this.settings.restoreControl(readDialog(arguments), readControl(arguments));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "replaceImage", 2, (context, scope, thisObject, arguments) -> {
            int art = ScriptCoreUtils.requireId("console image", ScriptCoreUtils.readInteger(arguments, 0), ARTS.length);

            this.settings.setArt(ARTS[art], this.graphicsCore.readOptionalPicture(arguments, 1));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setResourceIcon", 2, (context, scope, thisObject, arguments) -> {
            int icon = ScriptCoreUtils.requireId("resource icon", ScriptCoreUtils.readInteger(arguments, 0), ConsoleSettings.RESOURCE_ICON_COUNT);

            this.settings.setResourceIcon(icon, this.graphicsCore.readOptionalPicture(arguments, 1));

            return Context.getUndefinedValue();
        });
    }

    private static @NotNull Scriptable createRectangle(@NotNull Context context, @NotNull Scriptable global, float @NotNull [] rectangle) {
        Object[] values = new Object[rectangle.length];

        for (int index = 0; index < rectangle.length; index++) {
            values[index] = (double) rectangle[index];
        }

        return context.newArray(global, values);
    }

    private @NotNull LocalDisplay getLocalDisplay() {
        return this.scriptGame.getGame().getInterfaceLink().getLocalDisplay();
    }

    private static ScrConsolePlacement.@NotNull Dialog readDialog(@NotNull Object @NotNull [] arguments) {
        return DIALOGS[ScriptCoreUtils.requireId("console dialog", ScriptCoreUtils.readInteger(arguments, 0), DIALOGS.length)];
    }

    /**
     * Reads the control identifier of the second argument.
     *
     * @throws EcmaError if the argument is not an integer or is outside the signed WORD of a control identifier
     */
    private static int readControl(@NotNull Object @NotNull [] arguments) {
        int control = ScriptCoreUtils.readInteger(arguments, 1);

        if (control < Short.MIN_VALUE || control > Short.MAX_VALUE) {
            throw ScriptRuntime.rangeError(Exceptions.format("The control identifier {0} is not from {1} to {2}.", control, Short.MIN_VALUE,
                                                             Short.MAX_VALUE));
        }

        return control;
    }
}
