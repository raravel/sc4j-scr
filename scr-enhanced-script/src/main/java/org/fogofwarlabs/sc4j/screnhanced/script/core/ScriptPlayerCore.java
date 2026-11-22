package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrRaceIds;
import org.fogofwarlabs.sc4j.scr.content.ScrTechIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.content.ScrUpgradeIds;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayHeaderBuilder;
import org.fogofwarlabs.sc4j.scr.data.type.constant.TriggerUnitGroups;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.player.*;
import org.fogofwarlabs.sc4j.scr.game.presentation.LocalSelection;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.*;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalView;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.visibility.SightCoverage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.ScriptRuntime;
import org.mozilla.javascript.Scriptable;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Defines {@code core.player}: the players by slot id from {@code 0} to {@value PlayerTable#PLAYER_SLOTS} minus one.
 * <p>
 * {@code getCount()}, {@code getName(player)}, {@code getRace(player)}, {@code getType(player)}, {@code isHuman(player)},
 * {@code getMinerals(player)}, {@code setMinerals(player, amount)}, {@code addMinerals(player, amount)},
 * {@code subtractMinerals(player, amount)}, the same four for gas, {@code getSupplyUsed(player, race)}, {@code getSupplyProvided(player, race)}
 * and {@code getSupplyMax(player, race)} (half supply units of a race from {@code 0} Zerg to {@code 2} Protoss), {@code getAlliance(player,
 * other)} and {@code setAlliance(player, other, alliance)} ({@code 0} enemy, {@code 1} allied, {@code 2} allied victory), and
 * {@code endGame(player, result)}. The resource writes run the Set Resources routine: setting sets the gathered amount too, adding adds to it,
 * subtracting leaves {@code 0} when the amount is not below the current one as unsigned numbers, and players from
 * {@value PlayerTable#PLAYABLE_PLAYER_SLOTS} keep their resources. {@code getResources(player, resourceType)} reads ore, gas, or ore and gas
 * ({@code 0} to {@code 2}) as the Accumulate condition does.
 * <p>
 * The counters of the trigger conditions and actions take a unit operand, a unit type or a unit group from
 * {@value TriggerUnitGroups#ANY_UNIT} (any unit, men, buildings, factories):
 * {@code getDeaths(player, unitType)}, {@code setDeaths}, {@code addDeaths} and {@code subtractDeaths(player, unitType, amount)} (Deaths and
 * Set Deaths), {@code getKills(player, unitType)} (Kill), {@code getScore(player, scoreType)}, {@code setScore}, {@code addScore} and
 * {@code subtractScore(player, scoreType, amount)} (Score and Set Score, score types {@code 0} to {@code 7}, set for players below
 * {@value PlayerTable#PLAYABLE_PLAYER_SLOTS}), {@code countUnits(player, unitType, completed)} (Command), {@code countUnitsAt(player,
 * unitType, location, completed)} (Bring) and {@code countOpponents(player)} (Opponents). {@code killUnits} and
 * {@code removeUnits(player, unitType, location, count)} kill or remove units of the player as Kill Unit and Remove Unit do, at a location or
 * everywhere for location {@code -1}, at most {@code count} units or all for {@code 0}. {@code runAiScript(player, script)} and
 * {@code runAiScriptAt(player, script, location)} start an AI script by its four-character id, its first character in the low byte, and
 * return {@code false} when the player may not run it.
 * <p>
 * {@code setMissionObjectives(player, text)} sets the mission objectives as Set Mission Objectives does, with the text as a registered map
 * string ({@link ScriptGame#findStringIndex}).
 * <p>
 * The local display functions act when the player is the local player and change no game state, so they run in a read-only game too:
 * {@code print(player, text, forced)} (Display Text Message: a forced text always shows, another one when the subtitle option is on),
 * {@code pingMinimap(player, x, y)} (Minimap Ping at a map position), {@code playSound(player, path)} (Play WAV with a sound file path of the
 * map archive), {@code showTalkingPortrait(player, unitType, milliseconds)} (Talking Portrait), {@code setUnitSpeechMuted(player, muted)}
 * (Mute and Unmute Unit Speech) and {@code centerView(player, x, y)}, which centers the local screen on a map position at once, as the
 * multiplayer Center View action moves it ({@link LocalView#centerScreen}); it returns nothing, so the simulation does not depend on the local
 * screen, and does nothing while the local view or its display geometry is unknown.
 * <p>
 * The upgrade, technology and unit availability state of a player, which EUD writes change too: {@code getUpgradeLevel(player, upgrade)},
 * {@code setUpgradeLevel(player, upgrade, level)} (then refreshes the upgrade flags of the player's units, as a finished upgrade does),
 * {@code getMaxUpgradeLevel} and {@code setMaxUpgradeLevel}, {@code isUpgradeInProgress(player, upgrade)}, {@code isTechResearched(player,
 * tech)} and {@code setTechResearched(player, tech, researched)}, {@code isTechAvailable} and {@code setTechAvailable(player, tech,
 * available)}, {@code isTechInProgress(player, tech)}, {@code isUnitAvailable(player, unitType)} and {@code setUnitAvailable(player,
 * unitType, available)} (the map settings' production availability; a game without map settings allows every unit type, and making a type
 * unavailable there throws a {@code TypeError}).
 * <p>
 * The selections of the players from {@code 0} to {@value PlayerTable#PLAYABLE_PLAYER_SLOTS} minus one, which the selection commands change
 * and every peer holds: {@code getSelection(player)} (the ids of the live units up to the first empty position, empty for the other players)
 * and {@code setSelection(player, ids)}, which replaces the selection as a replacing selection command of the player does
 * ({@link UnitSelection#select}): at most {@value UnitSelection#MAXIMUM_SELECTED} live units, of which the command's rules take some; it
 * returns the number of selected units and shows the selection to the local player as the local selection.
 * <p>
 * {@code endGame} ends the game for a player from {@code 0} to {@value PlayerTable#PLAYABLE_PLAYER_SLOTS} minus one as the Victory
 * ({@value TriggerPlayerResults#VICTORY}), Defeat ({@value TriggerPlayerResults#DEFEAT}) and Draw ({@value TriggerPlayerResults#DRAW})
 * actions do and returns {@code false} when a defeat or a draw meets a victory.
 * <p>
 * The player state that EUD writes change, stored as such a write stores it: {@code getGatheredMinerals}, {@code setGatheredMinerals},
 * {@code getGatheredGas} and {@code setGatheredGas} (the gathered totals of the score); {@code getSharedVision(player)} and
 * {@code setSharedVision(player, mask)} (the players that see what the player's units see, one bit per player and the player's own bit
 * included; the write sets the relations and the sight coverage, as the vision command and the AI script do, without the command's
 * permission test); {@code getPausesLeft(player)} and {@code setPausesLeft(player, pauses)} (the pauses a player from {@code 0} to
 * {@value PlayerTable#PLAYABLE_PLAYER_SLOTS} minus one has left, which the network pause takes); {@code getStartLocationX(player)} and
 * {@code getStartLocationY(player)} ({@code -1} for a player without one) and {@code setStartLocation(player, x, y)} ({@code false} for a
 * player without one), the start location the computer players and the start screen read.
 * <p>
 * The color rows of the players, presentation that runs in shared and local code and changes no game state, stored as EUD writes store them:
 * {@code getUnitColor(player, index)} and {@code setUnitColor(player, index, paletteIndex)} (the palette index of the remapped unit color
 * {@code 0} to {@code 7}; the first one is also the player's HD team color), {@code getMinimapColor(player)} and
 * {@code setMinimapColor(player, paletteIndex)}. Every drawn frame reads them; a player shown in a custom color of the game keeps it in the HD
 * team color and on the minimap.
 */
final class ScriptPlayerCore {
    /**
     * The number of alliance relations: enemy, allied and allied victory.
     */
    private static final int ALLIANCE_COUNT = PlayerRelations.ALLIED_VICTORY + 1;
    /**
     * The limit of Kill Unit and Remove Unit that takes every unit.
     */
    private static final int ALL_UNITS = 999999;
    /**
     * The count of {@code killUnits} and {@code removeUnits} that takes every unit.
     */
    private static final int EVERY_UNIT_COUNT = 0;
    /**
     * The largest upgrade level, a byte.
     */
    private static final int MAXIMUM_LEVEL = 0xFF;
    /**
     * The unit availability byte of a unit type a player may produce.
     */
    private static final int UNIT_AVAILABLE = 1;
    /**
     * The mask of a BYTE, the most pauses a player has left.
     */
    private static final int BYTE_MASK = 0xFF;
    /**
     * The largest value of a WORD coordinate.
     */
    private static final int WORD_MAXIMUM = 0xFFFF;
    /**
     * The number of palette indices of a color row.
     */
    private static final int PALETTE_SIZE = 256;

    private final @NotNull ScriptGame scriptGame;

    /**
     * Creates a {@link ScriptPlayerCore} instance.
     *
     * @param scriptGame the game binding
     */
    ScriptPlayerCore(@NotNull ScriptGame scriptGame) {
        this.scriptGame = scriptGame;
    }

    /**
     * Defines the functions of {@code core.player}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void define(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getCount", 0, (context, scope, thisObject, arguments) -> (double) PlayerTable.PLAYER_SLOTS);
        ScriptCoreUtils.defineFunction(global, namespace, "getName", 1,
                                       (context, scope, thisObject, arguments) -> this.getName(this.readPlayerId(arguments, 0)));
        ScriptCoreUtils.defineFunction(global, namespace, "getRace", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.readPlayer(arguments, 0).getRace());
        ScriptCoreUtils.defineFunction(global, namespace, "getType", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.readPlayer(arguments, 0).getType());
        ScriptCoreUtils.defineFunction(global, namespace, "isHuman", 1,
                                       (context, scope, thisObject, arguments) -> this.readPlayer(arguments, 0).getType() == PlayerTypes.HUMAN);

        this.defineResourceFunctions(namespace, global);
        this.defineSupplyFunctions(namespace, global);
        this.defineCounterFunctions(namespace, global);
        this.defineUnitFunctions(namespace, global);
        this.defineAllianceFunctions(namespace, global);
        this.defineDisplayFunctions(namespace, global);
        this.defineProgressFunctions(namespace, global);
        this.defineSelectionFunctions(namespace, global);
        this.defineVisionFunctions(namespace, global);
        this.defineStartFunctions(namespace, global);
        this.defineColorFunctions(namespace, global);

        ScriptCoreUtils.defineFunction(global, namespace, "setMissionObjectives", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int player = this.readPlayerId(arguments, 0);
            String text = ScriptCoreUtils.readString(arguments, 1);
            TriggerContext triggers = this.scriptGame.requireTriggerContext();

            triggers.setMissionObjectives(player, this.scriptGame.findStringIndex(text));

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "endGame", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int player = this.readTriggerPlayerId(arguments, 0);
            int result = ScriptCoreUtils.readInteger(arguments, 1);

            if (result != TriggerPlayerResults.VICTORY && result != TriggerPlayerResults.DEFEAT && result != TriggerPlayerResults.DRAW) {
                throw ScriptRuntime.rangeError(Exceptions.format("The result {0} is not a victory, a defeat or a draw.", result));
            }

            return this.scriptGame.requireTriggerContext().endGame(player, result);
        });
    }

    private void defineResourceFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getMinerals", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.readPlayer(arguments, 0).getMinerals());
        this.defineResourceWrite(namespace, global, "setMinerals", TriggerModifiers.SET_TO, TriggerScoreActionsUtils.RESOURCE_MINERALS);
        this.defineResourceWrite(namespace, global, "addMinerals", TriggerModifiers.ADD, TriggerScoreActionsUtils.RESOURCE_MINERALS);
        this.defineResourceWrite(namespace, global, "subtractMinerals", TriggerModifiers.SUBTRACT, TriggerScoreActionsUtils.RESOURCE_MINERALS);
        ScriptCoreUtils.defineFunction(global, namespace, "getGas", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.readPlayer(arguments, 0).getGas());
        this.defineResourceWrite(namespace, global, "setGas", TriggerModifiers.SET_TO, TriggerScoreActionsUtils.RESOURCE_GAS);
        this.defineResourceWrite(namespace, global, "addGas", TriggerModifiers.ADD, TriggerScoreActionsUtils.RESOURCE_GAS);
        this.defineResourceWrite(namespace, global, "subtractGas", TriggerModifiers.SUBTRACT, TriggerScoreActionsUtils.RESOURCE_GAS);
        ScriptCoreUtils.defineFunction(global, namespace, "getResources", 2, (context, scope, thisObject, arguments) -> {
            Player player = this.readPlayer(arguments, 0);
            int resourceType = ScriptCoreUtils.readResourceType(arguments, 1);

            return (double) TriggerConditions.readResources(player, resourceType);
        });

        ScriptCoreUtils.defineFunction(global, namespace, "getGatheredMinerals", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.readPlayer(arguments, 0).getGatheredMinerals());
        ScriptCoreUtils.defineFunction(global, namespace, "setGatheredMinerals", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Player player = this.readPlayer(arguments, 0);
            int amount = ScriptCoreUtils.readInteger(arguments, 1);

            player.setGatheredMinerals(amount);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getGatheredGas", 1,
                                       (context, scope, thisObject, arguments) -> (double) this.readPlayer(arguments, 0).getGatheredGas());
        ScriptCoreUtils.defineFunction(global, namespace, "setGatheredGas", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            Player player = this.readPlayer(arguments, 0);
            int amount = ScriptCoreUtils.readInteger(arguments, 1);

            player.setGatheredGas(amount);

            return Context.getUndefinedValue();
        });
    }

    /**
     * Defines a write of a player's minerals or gas that runs the Set Resources routine with a modifier; players from
     * {@value PlayerTable#PLAYABLE_PLAYER_SLOTS} keep their resources, as Set Resources leaves them.
     */
    private void defineResourceWrite(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull String name, int modifier, int resourceType) {
        ScriptCoreUtils.defineFunction(global, namespace, name, 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int id = this.readPlayerId(arguments, 0);
            int amount = ScriptCoreUtils.readInteger(arguments, 1);
            Player player = this.requirePlayer(id);

            if (id < PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                TriggerScoreActionsUtils.setResources(player, modifier, resourceType, amount);
            }

            return Context.getUndefinedValue();
        });
    }

    private void defineSupplyFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getSupplyUsed", 2, (context, scope, thisObject, arguments) -> {
            int counter = this.readSupplyCounter(arguments);

            return (double) this.requireAccounting().getSupplyUsed(counter);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getSupplyProvided", 2, (context, scope, thisObject, arguments) -> {
            int counter = this.readSupplyCounter(arguments);

            return (double) this.requireAccounting().getSupplyProvided(counter);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getSupplyMax", 2, (context, scope, thisObject, arguments) -> {
            int counter = this.readSupplyCounter(arguments);

            return (double) this.requireAccounting().getSupplyCap(counter);
        });
    }

    /**
     * Defines the functions of the deaths, kills and score counters and of the unit and opponent counts of the trigger conditions.
     */
    private void defineCounterFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getDeaths", 2, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);
            int unitType = ScriptCoreUtils.readUnitOperand(arguments, 1);

            return (double) this.scriptGame.requireTriggerContext().getDeaths(player, unitType);
        });
        this.defineDeathsWrite(namespace, global, "setDeaths", TriggerModifiers.SET_TO);
        this.defineDeathsWrite(namespace, global, "addDeaths", TriggerModifiers.ADD);
        this.defineDeathsWrite(namespace, global, "subtractDeaths", TriggerModifiers.SUBTRACT);
        ScriptCoreUtils.defineFunction(global, namespace, "getKills", 2, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);
            int unitType = ScriptCoreUtils.readUnitOperand(arguments, 1);

            return (double) this.scriptGame.requireTriggerContext().getKills(player, unitType);
        });

        ScriptCoreUtils.defineFunction(global, namespace, "getScore", 2, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);
            int scoreType = ScriptCoreUtils.readScoreType(arguments, 1);

            return (double) this.scriptGame.requireTriggerContext().getScore(player, scoreType);
        });
        this.defineScoreWrite(namespace, global, "setScore", TriggerModifiers.SET_TO);
        this.defineScoreWrite(namespace, global, "addScore", TriggerModifiers.ADD);
        this.defineScoreWrite(namespace, global, "subtractScore", TriggerModifiers.SUBTRACT);

        ScriptCoreUtils.defineFunction(global, namespace, "countUnits", 3, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);
            int unitType = ScriptCoreUtils.readUnitOperand(arguments, 1);
            boolean completed = ScriptCoreUtils.readBoolean(arguments, 2);

            return (double) this.scriptGame.requireTriggerContext().countCommandedUnits(player, unitType, completed);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "countUnitsAt", 4, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);
            int unitType = ScriptCoreUtils.readUnitOperand(arguments, 1);
            int location = ScriptCoreUtils.readLocationId(arguments, 2);
            boolean completed = ScriptCoreUtils.readBoolean(arguments, 3);

            return (double) this.scriptGame.requireTriggerContext().countUnitsAt(player, unitType, location, completed);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "countOpponents", 1, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);

            return (double) this.scriptGame.requireTriggerContext().countOpponents(player);
        });
    }

    /**
     * Defines a write of a player's deaths of a unit operand that runs the Set Deaths routine with a modifier.
     */
    private void defineDeathsWrite(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull String name, int modifier) {
        ScriptCoreUtils.defineFunction(global, namespace, name, 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int player = this.readPlayerId(arguments, 0);
            int unitType = ScriptCoreUtils.readUnitOperand(arguments, 1);
            int amount = ScriptCoreUtils.readInteger(arguments, 2);

            this.scriptGame.requireTriggerContext().setDeaths(player, unitType, modifier, amount);

            return Context.getUndefinedValue();
        });
    }

    /**
     * Defines a write of a player's score of a score type that runs the Set Score routine with a modifier.
     */
    private void defineScoreWrite(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull String name, int modifier) {
        ScriptCoreUtils.defineFunction(global, namespace, name, 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int player = this.readTriggerPlayerId(arguments, 0);
            int scoreType = ScriptCoreUtils.readScoreType(arguments, 1);
            int amount = ScriptCoreUtils.readInteger(arguments, 2);

            this.scriptGame.requireTriggerContext().setScore(player, scoreType, modifier, amount);

            return Context.getUndefinedValue();
        });
    }

    /**
     * Defines the functions that kill or remove a player's units and start AI scripts for a player.
     */
    private void defineUnitFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        this.defineUnitRemoval(namespace, global, "killUnits", false);
        this.defineUnitRemoval(namespace, global, "removeUnits", true);

        ScriptCoreUtils.defineFunction(global, namespace, "runAiScript", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int player = this.readPlayerId(arguments, 0);
            int script = ScriptCoreUtils.readInteger(arguments, 1);

            return this.scriptGame.requireTriggerContext().runAiScript(player, script);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "runAiScriptAt", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int player = this.readPlayerId(arguments, 0);
            int script = ScriptCoreUtils.readInteger(arguments, 1);
            int location = ScriptCoreUtils.readLocationId(arguments, 2);

            return this.scriptGame.requireTriggerContext().runAiScriptAt(player, script, location);
        });
    }

    /**
     * Defines a function that kills or removes a player's units of a unit operand as Kill Unit and Remove Unit do, at a location or everywhere.
     */
    private void defineUnitRemoval(@NotNull Scriptable namespace, @NotNull Scriptable global, @NotNull String name, boolean remove) {
        ScriptCoreUtils.defineFunction(global, namespace, name, 4, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int player = this.readPlayerId(arguments, 0);
            int unitType = ScriptCoreUtils.readUnitOperand(arguments, 1);
            int location = ScriptCoreUtils.readOptionalLocationId(arguments, 2);
            int count = ScriptCoreUtils.readInteger(arguments, 3);

            if (count < 0) {
                throw ScriptRuntime.rangeError(Exceptions.format("The unit count {0} is negative.", count));
            }

            TriggerContext triggers = this.scriptGame.requireTriggerContext();
            int locationIndex = location == ScriptCoreUtils.NO_ID ? TriggerUnitActions.NO_LOCATION : location;
            int limit = count == EVERY_UNIT_COUNT ? ALL_UNITS : count;

            this.scriptGame.getGame().getTriggerUnitActions().removeUnits(triggers, player, unitType, locationIndex, limit, remove);

            return Context.getUndefinedValue();
        });
    }

    private void defineAllianceFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getAlliance", 2, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);
            int other = this.readPlayerId(arguments, 1);

            return (double) this.scriptGame.getGame().getPlayerTable().requirePlayerRelations().getAlliance(player, other);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setAlliance", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int player = this.readTriggerPlayerId(arguments, 0);
            int other = this.readPlayerId(arguments, 1);
            int alliance = ScriptCoreUtils.requireId("alliance", ScriptCoreUtils.readInteger(arguments, 2), ALLIANCE_COUNT);

            this.scriptGame.requireTriggerContext().setAllianceStatus(player, other, alliance);

            return Context.getUndefinedValue();
        });
    }

    /**
     * Defines the local display functions, which change no game state; they work in shared code only, also while the game is read-only.
     */
    private void defineDisplayFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "print", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkShared();

            int player = this.readPlayerId(arguments, 0);
            String text = ScriptCoreUtils.readString(arguments, 1);
            boolean forced = ScriptCoreUtils.readBoolean(arguments, 2);

            this.scriptGame.requireTriggerContext().displayText(player, text, forced);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "pingMinimap", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkShared();

            int player = this.readPlayerId(arguments, 0);
            int x = ScriptCoreUtils.readInteger(arguments, 1);
            int y = ScriptCoreUtils.readInteger(arguments, 2);

            this.scriptGame.requireTriggerContext().pingMinimap(player, x, y);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "playSound", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkShared();

            int player = this.readPlayerId(arguments, 0);
            byte[] path = ScriptCoreUtils.readString(arguments, 1).getBytes(StandardCharsets.UTF_8);

            this.scriptGame.requireTriggerContext().playSound(player, path);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "showTalkingPortrait", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkShared();

            int player = this.readPlayerId(arguments, 0);
            int unitType = ScriptCoreUtils.requireId("unit type", ScriptCoreUtils.readInteger(arguments, 1), ScrUnitIds.NONE);
            int milliseconds = ScriptCoreUtils.readInteger(arguments, 2);

            if (milliseconds < 0) {
                throw ScriptRuntime.rangeError(Exceptions.format("The duration {0} is negative.", milliseconds));
            }

            this.scriptGame.requireTriggerContext().showTalkingPortrait(player, unitType, milliseconds);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setUnitSpeechMuted", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkShared();

            int player = this.readPlayerId(arguments, 0);
            boolean muted = ScriptCoreUtils.readBoolean(arguments, 1);

            this.scriptGame.requireTriggerContext().setUnitSpeechMuted(player, muted);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "centerView", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkShared();

            int player = this.readPlayerId(arguments, 0);
            int x = ScriptCoreUtils.readInteger(arguments, 1);
            int y = ScriptCoreUtils.readInteger(arguments, 2);

            if (player == this.scriptGame.requireTriggerContext().getLocalPlayer()) {
                this.scriptGame.getGame().getInterfaceLink().getLocalView().centerScreen(x, y);
            }

            return Context.getUndefinedValue();
        });
    }

    /**
     * Defines the upgrade levels, the technologies and the unit availability of the players.
     */
    private void defineProgressFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getUpgradeLevel", 2, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);
            int upgrade = this.readUpgrade(arguments, 1);

            return (double) this.requireUpgrades().getLevel(player, upgrade);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setUpgradeLevel", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int player = this.readPlayerId(arguments, 0);
            int upgrade = this.readUpgrade(arguments, 1);
            int level = this.readLevel(arguments, 2);

            this.requireUpgrades().setLevel(player, upgrade, level);
            this.scriptGame.getGame().getUnitResearch().applyToPlayerUnits(player, this.scriptGame.getInstructionLimit());

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getMaxUpgradeLevel", 2, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);
            int upgrade = this.readUpgrade(arguments, 1);

            return (double) this.requireUpgrades().getMaximumLevel(player, upgrade);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setMaxUpgradeLevel", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int player = this.readPlayerId(arguments, 0);
            int upgrade = this.readUpgrade(arguments, 1);
            int level = this.readLevel(arguments, 2);

            this.requireUpgrades().setMaximumLevel(player, upgrade, level);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "isUpgradeInProgress", 2, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);
            int upgrade = this.readUpgrade(arguments, 1);

            return this.requireUpgrades().isUpgrading(player, upgrade);
        });

        ScriptCoreUtils.defineFunction(global, namespace, "isTechResearched", 2, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);
            int technology = this.readTechnology(arguments, 1);

            return this.requireTechnologies().isResearched(player, technology);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setTechResearched", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int player = this.readPlayerId(arguments, 0);
            int technology = this.readTechnology(arguments, 1);
            boolean researched = ScriptCoreUtils.readBoolean(arguments, 2);

            this.requireTechnologies().setResearched(player, technology, researched);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "isTechAvailable", 2, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);
            int technology = this.readTechnology(arguments, 1);

            return this.requireTechnologies().isAvailable(player, technology);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setTechAvailable", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int player = this.readPlayerId(arguments, 0);
            int technology = this.readTechnology(arguments, 1);
            boolean available = ScriptCoreUtils.readBoolean(arguments, 2);

            this.requireTechnologies().setAvailable(player, technology, available);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "isTechInProgress", 2, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);
            int technology = this.readTechnology(arguments, 1);

            return this.requireTechnologies().isResearching(player, technology);
        });

        ScriptCoreUtils.defineFunction(global, namespace, "isUnitAvailable", 2, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);
            int unitType = ScriptCoreUtils.requireId("unit type", ScriptCoreUtils.readInteger(arguments, 1), ScrUnitIds.NONE);
            PlayerUnitAvailability availability = this.scriptGame.getGame().getPlayerTable().getPlayerUnitAvailability();

            return availability == null || availability.isAllowed(player, unitType);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setUnitAvailable", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int player = this.readPlayerId(arguments, 0);
            int unitType = ScriptCoreUtils.requireId("unit type", ScriptCoreUtils.readInteger(arguments, 1), ScrUnitIds.NONE);
            boolean available = ScriptCoreUtils.readBoolean(arguments, 2);
            PlayerUnitAvailability availability = this.scriptGame.getGame().getPlayerTable().getPlayerUnitAvailability();

            if (availability != null) {
                availability.writeByte(player, unitType, available ? UNIT_AVAILABLE : 0);
            } else if (!available) {
                throw ScriptRuntime.typeError("The game has no unit availability: only a game with map settings has one.");
            }

            return Context.getUndefinedValue();
        });
    }

    /**
     * Defines the selections of the players, the synchronized selections that the selection commands change.
     */
    private void defineSelectionFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getSelection", 1, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);
            Game game = this.scriptGame.getGame();
            UnitSelection selection = game.getSelection();

            if (selection == null || player >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                return context.newArray(global, 0);
            }

            return ScriptUnitCore.createSelectionIds(context, global, game.getUnitSystem().requireUnitPool(),
                                                     position -> selection.getSelectedPoolIndexRaw(player, position));
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setSelection", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int player = this.readPlayerId(arguments, 0);
            int[] ids = ScriptCoreUtils.readIntegers(arguments, 1);
            Game game = this.scriptGame.getGame();
            UnitSelection selection = game.getSelection();
            UnitPool pool = game.getUnitSystem().requireUnitPool();

            if (selection == null) {
                throw ScriptRuntime.typeError("The game has no player selections.");
            }

            if (player >= PlayerTable.PLAYABLE_PLAYER_SLOTS) {
                throw ScriptRuntime.rangeError(Exceptions.format("The player {0} has no selection.", player));
            }

            if (ids.length > UnitSelection.MAXIMUM_SELECTED) {
                throw ScriptRuntime.rangeError(Exceptions.format("A selection holds at most {0} units, not {1}.", UnitSelection.MAXIMUM_SELECTED,
                                                                 ids.length));
            }

            Unit[] units = new Unit[ids.length];

            for (int index = 0; index < ids.length; index++) {
                if (!ScriptUnitCore.isLive(pool, ids[index])) {
                    throw ScriptRuntime.rangeError(Exceptions.format("The unit {0} does not exist.", ids[index]));
                }

                units[index] = pool.getUnit(ids[index]);
            }

            selection.select(game, player, units);

            int count = 0;

            while (count < UnitSelection.MAXIMUM_SELECTED && selection.getSelectedPoolIndexRaw(player, count) != LocalSelection.EMPTY_SLOT) {
                count++;
            }

            if (player == this.scriptGame.findLocalPlayer()) {
                Unit[] selected = new Unit[count];

                for (int position = 0; position < count; position++) {
                    selected[position] = pool.getUnit(selection.getSelectedPoolIndexRaw(player, position));
                }

                game.getInterfaceLink().getLocalSelection().showSelection(selected);
            }

            return (double) count;
        });
    }

    /**
     * Defines the shared vision of the players and the pauses they have left.
     */
    private void defineVisionFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getSharedVision", 1, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);

            return (double) Integer.toUnsignedLong(this.scriptGame.getGame().getPlayerTable().requirePlayerRelations().getSharedVision(player));
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setSharedVision", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int player = this.readPlayerId(arguments, 0);
            int mask = (int) ScriptCoreUtils.readLong(arguments, 1);
            Game game = this.scriptGame.getGame();
            SightCoverage sight = game.getSightCoverage();

            if (sight == null) {
                throw ScriptRuntime.typeError("The game has no sight coverage.");
            }

            game.getPlayerTable().requirePlayerRelations().setSharedVision(player, mask);
            sight.setSharedVisionMask(player, mask);

            return Context.getUndefinedValue();
        });

        ScriptCoreUtils.defineFunction(global, namespace, "getPausesLeft", 1, (context, scope, thisObject, arguments) -> {
            int player = this.readTriggerPlayerId(arguments, 0);

            return (double) (this.scriptGame.getGame().getGameContext().getContextPauses()[player] & BYTE_MASK);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setPausesLeft", 2, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int player = this.readTriggerPlayerId(arguments, 0);
            int pauses = ScriptCoreUtils.readInteger(arguments, 1);

            if (pauses < 0 || pauses > BYTE_MASK) {
                throw ScriptRuntime.rangeError(Exceptions.format("The pauses {0} are not from 0 to {1}.", pauses, BYTE_MASK));
            }

            this.scriptGame.getGame().getGameContext().getContextPauses()[player] = (byte) pauses;

            return Context.getUndefinedValue();
        });
    }

    /**
     * Defines the start locations of the players, which the computer players and the start screen read.
     */
    private void defineStartFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getStartLocationX", 1, (context, scope, thisObject, arguments) -> {
            StartLocation start = this.findStartLocation(this.readPlayerId(arguments, 0));

            return (double) (start == null ? ScriptCoreUtils.NO_ID : start.getX());
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getStartLocationY", 1, (context, scope, thisObject, arguments) -> {
            StartLocation start = this.findStartLocation(this.readPlayerId(arguments, 0));

            return (double) (start == null ? ScriptCoreUtils.NO_ID : start.getY());
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setStartLocation", 3, (context, scope, thisObject, arguments) -> {
            this.scriptGame.checkWritable();

            int player = this.readPlayerId(arguments, 0);
            int positionX = this.readWord(arguments, 1);
            int positionY = this.readWord(arguments, 2);
            List<StartLocation> starts = this.scriptGame.getGame().getPlayerTable().getStartLocations();

            for (int index = 0; index < starts.size(); index++) {
                if (starts.get(index).getOwner() == player) {
                    starts.set(index, new StartLocation(player, positionX, positionY));

                    return true;
                }
            }

            return false;
        });
    }

    /**
     * Finds the first start location a player owns.
     *
     * @return the start location, or {@code null} if the player owns none
     */
    private @Nullable StartLocation findStartLocation(int player) {
        for (StartLocation start : this.scriptGame.getGame().getPlayerTable().getStartLocations()) {
            if (start.getOwner() == player) {
                return start;
            }
        }

        return null;
    }

    /**
     * Defines the color rows of the players, presentation that every drawn frame reads.
     */
    private void defineColorFunctions(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "getUnitColor", 2, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);
            int index = ScriptCoreUtils.requireId("unit color index", ScriptCoreUtils.readInteger(arguments, 1), PlayerColorRows.UNIT_ROW);

            return (double) this.requireColorRows().getUnitColor(player, index);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setUnitColor", 3, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);
            int index = ScriptCoreUtils.requireId("unit color index", ScriptCoreUtils.readInteger(arguments, 1), PlayerColorRows.UNIT_ROW);
            int palette = readPaletteIndex(arguments, 2);

            this.requireColorRows().setUnitColor(player, index, palette);

            return Context.getUndefinedValue();
        });
        ScriptCoreUtils.defineFunction(global, namespace, "getMinimapColor", 1, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);

            return (double) this.requireColorRows().getMinimapColor(player);
        });
        ScriptCoreUtils.defineFunction(global, namespace, "setMinimapColor", 2, (context, scope, thisObject, arguments) -> {
            int player = this.readPlayerId(arguments, 0);
            int palette = readPaletteIndex(arguments, 1);

            this.requireColorRows().setMinimapColor(player, palette);

            return Context.getUndefinedValue();
        });
    }

    /**
     * @return the player color rows of the game
     * @throws EcmaError if the game has no player color rows
     */
    private @NotNull PlayerColorRows requireColorRows() {
        PlayerColorRows rows = this.scriptGame.getGame().getPlayerTable().getPlayerColorRows();

        if (rows == null) {
            throw ScriptRuntime.typeError("The game has no player colors.");
        }

        return rows;
    }

    /**
     * Reads a palette index.
     *
     * @throws EcmaError if the argument is not an integer or is outside {@code 0} to {@value #PALETTE_SIZE} minus one
     */
    private static int readPaletteIndex(@NotNull Object @NotNull [] arguments, int index) {
        return ScriptCoreUtils.requireId("palette index", ScriptCoreUtils.readInteger(arguments, index), PALETTE_SIZE);
    }

    /**
     * Reads a WORD coordinate.
     *
     * @throws EcmaError if the argument is not an integer or is outside {@code 0} to {@value #WORD_MAXIMUM}
     */
    private int readWord(@NotNull Object @NotNull [] arguments, int index) {
        int value = ScriptCoreUtils.readInteger(arguments, index);

        if (value < 0 || value > WORD_MAXIMUM) {
            throw ScriptRuntime.rangeError(Exceptions.format("The coordinate {0} is not from 0 to {1}.", value, WORD_MAXIMUM));
        }

        return value;
    }

    private int readUpgrade(@NotNull Object @NotNull [] arguments, int index) {
        return ScriptCoreUtils.requireId("upgrade type", ScriptCoreUtils.readInteger(arguments, index), ScrUpgradeIds.NONE);
    }

    private int readTechnology(@NotNull Object @NotNull [] arguments, int index) {
        return ScriptCoreUtils.requireId("tech type", ScriptCoreUtils.readInteger(arguments, index), ScrTechIds.NONE);
    }

    /**
     * Reads an upgrade level, a byte.
     *
     * @throws EcmaError if the argument is not an integer or is outside {@code 0} to {@value #MAXIMUM_LEVEL}
     */
    private int readLevel(@NotNull Object @NotNull [] arguments, int index) {
        int level = ScriptCoreUtils.readInteger(arguments, index);

        if (level < 0 || level > MAXIMUM_LEVEL) {
            throw ScriptRuntime.rangeError(Exceptions.format("The level {0} is not from 0 to {1}.", level, MAXIMUM_LEVEL));
        }

        return level;
    }

    private @NotNull PlayerUpgrades requireUpgrades() {
        return this.scriptGame.getGame().getPlayerTable().requirePlayerUpgrades();
    }

    private @NotNull PlayerTechnology requireTechnologies() {
        return this.scriptGame.getGame().getPlayerTable().requirePlayerTechnology();
    }

    private int readPlayerId(@NotNull Object @NotNull [] arguments, int index) {
        return ScriptCoreUtils.requireId("player", ScriptCoreUtils.readInteger(arguments, index), PlayerTable.PLAYER_SLOTS);
    }

    /**
     * Reads a player that owns triggers, from {@code 0} to {@value PlayerTable#PLAYABLE_PLAYER_SLOTS} minus one.
     *
     * @throws EcmaError if the argument is not an integer or not such a player
     */
    private int readTriggerPlayerId(@NotNull Object @NotNull [] arguments, int index) {
        return ScriptCoreUtils.requireId("player", ScriptCoreUtils.readInteger(arguments, index), PlayerTable.PLAYABLE_PLAYER_SLOTS);
    }

    private @NotNull Player readPlayer(@NotNull Object @NotNull [] arguments, int index) {
        return this.requirePlayer(this.readPlayerId(arguments, index));
    }

    /**
     * Returns the player of a slot.
     *
     * @throws EcmaError if the slot has no player
     */
    private @NotNull Player requirePlayer(int id) {
        Player player = this.scriptGame.getGame().getPlayerTable().getPlayer(id);

        if (player == null) {
            throw ScriptRuntime.rangeError(Exceptions.format("The player slot {0} has no player.", id));
        }

        return player;
    }

    /**
     * Reads the player and race arguments of a supply function as the index of the supply counters: the race base plus the player.
     */
    private int readSupplyCounter(@NotNull Object @NotNull [] arguments) {
        int player = this.readPlayerId(arguments, 0);
        int race = ScriptCoreUtils.requireId("race", ScriptCoreUtils.readInteger(arguments, 1), ScrRaceIds.PLAYABLE_COUNT);

        return race * PlayerTable.PLAYER_SLOTS + player;
    }

    private @NotNull UnitAccounting requireAccounting() {
        return this.scriptGame.getGame().getPlayerTable().requireUnitAccounting();
    }

    /**
     * Reads the name of a player from the game header: the null-terminated UTF-8 name of the player's record.
     *
     * @param id the player id
     * @return the name, empty if the game has no header
     */
    private @NotNull String getName(int id) {
        byte[] header = this.scriptGame.getGame().getGameContext().getGameHeaderSection();
        int start = ReplayHeaderBuilder.PLAYERS_OFFSET + id * ReplayHeaderBuilder.PLAYER_RECORD_SIZE + ReplayHeaderBuilder.PLAYER_NAME_OFFSET;
        int limit = ReplayHeaderBuilder.PLAYERS_OFFSET + (id + 1) * ReplayHeaderBuilder.PLAYER_RECORD_SIZE;

        if (header == null || limit > header.length) {
            return "";
        }

        int end = start;

        while (end < limit && header[end] != 0) {
            end++;
        }

        return new String(header, start, end - start, StandardCharsets.UTF_8);
    }
}
