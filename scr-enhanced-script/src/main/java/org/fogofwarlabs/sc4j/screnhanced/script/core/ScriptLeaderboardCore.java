package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerActionIds;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

/**
 * Defines {@code core.leaderboard}: the leaderboard of the trigger state, which the leaderboard actions show.
 * <p>
 * {@code show(type, operand, goal, label)} shows a leaderboard without a location, as the leaderboard actions do: the type is the action id of
 * Leader Board Control ({@value TriggerActionIds#LEADERBOARD_CONTROL}), Resources ({@value TriggerActionIds#LEADERBOARD_RESOURCES}), Kills
 * ({@value TriggerActionIds#LEADERBOARD_KILLS}) or Points ({@value TriggerActionIds#LEADERBOARD_POINTS}) or of their goal forms
 * ({@value TriggerActionIds#LEADERBOARD_GOAL_CONTROL}, {@value TriggerActionIds#LEADERBOARD_GOAL_RESOURCES},
 * {@value TriggerActionIds#LEADERBOARD_GOAL_KILLS}, {@value TriggerActionIds#LEADERBOARD_GOAL_POINTS}); the operand is a unit type or unit group
 * for control and kills, a resource type ({@code 0} ore, {@code 1} gas, {@code 2} ore and gas) for resources and a score type ({@code 0} to
 * {@code 7}) for points; the goal counts for the goal forms only. {@code showAt(type, location, unitType, goal, label)} shows Leader Board
 * Control At Location ({@value TriggerActionIds#LEADERBOARD_CONTROL_AT_LOCATION}) or its goal form
 * ({@value TriggerActionIds#LEADERBOARD_GOAL_CONTROL_AT_LOCATION}), {@code showGreed(goal)} the Greed leaderboard of the ore and gas of every
 * player. The label is a text, shown as a registered map string ({@link ScriptGame#findStringIndex}). {@code areComputersIncluded()} and
 * {@code setComputersIncluded(included)} read and set whether the leaderboards include computer players, as Leaderboard Computer Players
 * does. The shown leaderboard and the inclusion of computer players are game state: their functions are writes.
 */
final class ScriptLeaderboardCore {
    private final @NotNull ScriptGame scriptGame;

    /**
     * Creates a {@link ScriptLeaderboardCore} instance.
     *
     * @param scriptGame the game binding
     */
    ScriptLeaderboardCore(@NotNull ScriptGame scriptGame) {
        this.scriptGame = scriptGame;
    }

    /**
     * Defines the functions of {@code core.leaderboard}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void define(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "show", 4, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int type = ScriptCoreUtils.readInteger(arguments, 0);
            int operand = this.readOperand(type, arguments);
            int goal = ScriptCoreUtils.readInteger(arguments, 2);
            String label = ScriptCoreUtils.readString(arguments, 3);
            TriggerContext triggers = this.scriptGame.requireTriggerContext();

            triggers.showLeaderboard(type, operand, goal, this.scriptGame.findStringIndex(label));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "showAt", 5, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int type = ScriptCoreUtils.readInteger(arguments, 0);

            if (type != TriggerActionIds.LEADERBOARD_CONTROL_AT_LOCATION && type != TriggerActionIds.LEADERBOARD_GOAL_CONTROL_AT_LOCATION) {
                throw ScriptRuntime.rangeError(Exceptions.format("The leaderboard type {0} is not a leaderboard at a location.", type));
            }

            int location = ScriptCoreUtils.readLocationId(arguments, 1);
            int unitType = ScriptCoreUtils.readUnitOperand(arguments, 2);
            int goal = ScriptCoreUtils.readInteger(arguments, 3);
            String label = ScriptCoreUtils.readString(arguments, 4);
            TriggerContext triggers = this.scriptGame.requireTriggerContext();

            triggers.showLeaderboardAt(type, location, unitType, goal, this.scriptGame.findStringIndex(label));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "showGreed", 1, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int goal = ScriptCoreUtils.readInteger(arguments, 0);

            this.scriptGame.requireTriggerContext().showGreedLeaderboard(goal);

            return Context.getUndefinedValue();
        });

        ScriptCoreUtils.defineFunction(global, namespace, "areComputersIncluded", 0, (context, scope, thisObject, arguments) -> {
            TriggerContext triggers = this.scriptGame.requireTriggerContext();

            return triggers.areLeaderboardComputersIncluded();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setComputersIncluded", 1, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            boolean included = ScriptCoreUtils.readBoolean(arguments, 0);

            this.scriptGame.requireTriggerContext().setLeaderboardComputersIncluded(included);

            return Context.getUndefinedValue();
        });
    }

    /**
     * Reads the operand of a leaderboard without a location by its type: a unit operand for control and kills, a resource type for resources, a
     * score type for points.
     *
     * @throws EcmaError if the type is not a leaderboard without a location or the operand does not fit the type
     */
    private int readOperand(int type, @NotNull Object @NotNull [] arguments) {
        return switch (type) {
            case TriggerActionIds.LEADERBOARD_CONTROL, TriggerActionIds.LEADERBOARD_GOAL_CONTROL, TriggerActionIds.LEADERBOARD_KILLS,
                 TriggerActionIds.LEADERBOARD_GOAL_KILLS -> ScriptCoreUtils.readUnitOperand(arguments, 1);
            case TriggerActionIds.LEADERBOARD_RESOURCES,
                 TriggerActionIds.LEADERBOARD_GOAL_RESOURCES -> ScriptCoreUtils.readResourceType(arguments, 1);
            case TriggerActionIds.LEADERBOARD_POINTS, TriggerActionIds.LEADERBOARD_GOAL_POINTS -> ScriptCoreUtils.readScoreType(arguments, 1);
            default -> throw ScriptRuntime.rangeError(Exceptions.format("The leaderboard type {0} is not a leaderboard without a location.", type));
        };
    }
}
