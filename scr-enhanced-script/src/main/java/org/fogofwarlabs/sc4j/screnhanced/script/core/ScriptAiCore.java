package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.dat.UnitsDat;
import org.fogofwarlabs.sc4j.scr.game.ai.UnitStrengthTable;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

/**
 * Defines {@code core.ai}: the state of the computer players.
 * <p>
 * {@code getUnitStrength(unitType, air)} and {@code setUnitStrength(unitType, air, value)}: the strength of a unit type against air or ground
 * targets, an unsigned DWORD of the strength table that EUD writes change ({@link UnitStrengthTable#getLiveStrengths}), from which a unit takes
 * its own strengths ({@code core.unit.getAirStrength}, {@code core.unit.getGroundStrength}) when it is created or changes.
 */
final class ScriptAiCore {
    private final @NotNull ScriptGame scriptGame;

    /**
     * Creates a {@link ScriptAiCore} instance.
     *
     * @param scriptGame the game binding
     */
    ScriptAiCore(@NotNull ScriptGame scriptGame) {
        this.scriptGame = scriptGame;
    }

    /**
     * Defines the functions of {@code core.ai}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void define(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getUnitStrength", 2, (context, scope, thisObject, arguments) -> {
            int index = this.readStrengthIndex(arguments);

            return (double) Integer.toUnsignedLong(this.requireStrengths().getLiveStrengths()[index]);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setUnitStrength", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int index = this.readStrengthIndex(arguments);
            long value = ScriptCoreUtils.readLong(arguments, 2);

            if (value < 0) {
                throw ScriptRuntime.rangeError(Exceptions.format("The strength {0} is negative.", value));
            }

            this.requireStrengths().getLiveStrengths()[index] = (int) value;

            return Context.getUndefinedValue();
        });
    }

    /**
     * Reads the unit type and direction arguments as the index of their entry in the strength table: the air strengths, then the ground ones.
     *
     * @throws EcmaError if an argument is not of its type or the unit type is outside its range
     */
    private int readStrengthIndex(@NotNull Object @NotNull [] arguments) {
        int unitType = ScriptCoreUtils.requireId("unit type", ScriptCoreUtils.readInteger(arguments, 0), ScrUnitIds.NONE);
        boolean air = ScriptCoreUtils.readBoolean(arguments, 1);

        return (air ? UnitStrengthTable.AIR : UnitStrengthTable.GROUND) * UnitsDat.UNIT_COUNT + unitType;
    }

    /**
     * @return the strength table of the game
     * @throws EcmaError if the game has none
     */
    private @NotNull UnitStrengthTable requireStrengths() {
        UnitStrengthTable strengths = this.scriptGame.getGame().getUnitStrengthTable();

        if (strengths == null) {
            throw ScriptRuntime.typeError("The game has no unit strength table.");
        }

        return strengths;
    }
}
