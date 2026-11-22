package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.production.ScrRequirementSystem;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

/**
 * Defines {@code core.requirements}: the requirement scripts of the game and the native requirement tests that read them, which the command
 * card's requirement conditions and the production, research, upgrade and order commands run.
 * <p>
 * The scripts by kind: {@code 0} orders, {@code 1} technology use, {@code 2} research, {@code 3} upgrades and {@code 4} unit production.
 * {@code getScriptLength(kind)} and {@code getScriptWord(kind, position)} read a script's WORDs, the same array an EUD write changes;
 * {@code setScriptWord(kind, position, word)} writes one in shared code. A script keeps its length. The DAT requirement index columns
 * ({@code core.dat}) keep the start positions their first test built, so a word written in place of an entry's identifier changes no start; a
 * column entry set to {@code 0xFFFF} makes the next test of the kind build the column again from the script.
 * <p>
 * {@code checkOrder(unit, order, player)}, {@code checkTechnologyUse(unit, tech, player)}, {@code checkResearch(unit, tech, player)},
 * {@code checkUpgrade(unit, upgrade, player)} and {@code checkBuild(unit, unitType, player)} run the native test for a live unit and a player
 * slot and return its result: {@code 0} when the requirements hold, else the failure code in the low byte, with {@code 0x10000} set when the
 * command card shows the button disabled rather than hidden. A test may build a DAT index column, as the command card's tests do.
 */
final class ScriptRequirementCore {
    /**
     * The number of requirement scripts.
     */
    private static final int SCRIPT_COUNT = ScrRequirementSystem.BUILD + 1;
    /**
     * The largest WORD value.
     */
    private static final int WORD_MAXIMUM = 0xFFFF;

    /**
     * Runs one native requirement test.
     */
    @FunctionalInterface
    private interface RequirementTest {
        int check(@NotNull ScrRequirementSystem requirements, @NotNull Unit unit, int identifier, int player);
    }

    private final @NotNull ScriptGame scriptGame;
    private final @NotNull ScriptUnitCore unitCore;

    /**
     * Creates a {@link ScriptRequirementCore} instance.
     *
     * @param scriptGame the game binding
     * @param unitCore   the unit functions, which read the unit arguments
     */
    ScriptRequirementCore(@NotNull ScriptGame scriptGame, @NotNull ScriptUnitCore unitCore) {
        this.scriptGame = scriptGame;
        this.unitCore   = unitCore;
    }

    /**
     * Defines the functions of {@code core.requirements}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void define(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getScriptLength", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.readScript(arguments).length);
        ScriptCoreUtils.defineFunction(global, namespace, "getScriptWord", 2, (context, scope, thisObject, arguments) -> {
            int[] script = this.readScript(arguments);

            return (double) script[ScriptCoreUtils.requireId("script position", ScriptCoreUtils.readInteger(arguments, 1), script.length)];
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setScriptWord", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int[] script = this.readScript(arguments);
            int position = ScriptCoreUtils.requireId("script position", ScriptCoreUtils.readInteger(arguments, 1), script.length);
            int word = ScriptCoreUtils.requireId("script word", ScriptCoreUtils.readInteger(arguments, 2), WORD_MAXIMUM + 1);

            script[position] = word;

            return Context.getUndefinedValue();
        });

        this.defineTest(namespace, global, "checkOrder", ScrRequirementSystem::checkOrder);
        this.defineTest(namespace, global, "checkTechnologyUse", ScrRequirementSystem::checkTechnologyUse);
        this.defineTest(namespace, global, "checkResearch", ScrRequirementSystem::checkResearch);
        this.defineTest(namespace, global, "checkUpgrade", ScrRequirementSystem::checkUpgrade);
        this.defineTest(namespace, global, "checkBuild", ScrRequirementSystem::checkBuild);
    }

    /**
     * Defines a test {@code name(unit, identifier, player)}.
     */
    private void defineTest(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull String name, @NotNull RequirementTest test) {
        ScriptCoreUtils.defineFunction(global, namespace, name, 3, (context, scope, thisObject, arguments) -> {
            Unit unit = this.unitCore.readUnit(arguments, 0);
            int identifier = ScriptCoreUtils.readInteger(arguments, 1);
            int player = ScriptCoreUtils.requireId("player", ScriptCoreUtils.readInteger(arguments, 2), PlayerTable.PLAYER_SLOTS);

            return (double) test.check(this.scriptGame.getGame().getRequirements(), unit, identifier, player);
        });
    }

    private int @NotNull [] readScript(@NotNull Object @NotNull [] arguments) {
        int kind = ScriptCoreUtils.requireId("requirement script", ScriptCoreUtils.readInteger(arguments, 0), SCRIPT_COUNT);

        return this.scriptGame.getGame().getRequirements().getScripts()[kind];
    }
}
